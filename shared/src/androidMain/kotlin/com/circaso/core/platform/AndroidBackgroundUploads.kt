package com.circaso.core.platform

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.circaso.core.sync.AndroidBackgroundSync
import com.circaso.core.sync.OutboxWorkerRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** What is uploading right now, by a token per upload, with its bytes; the notification shows it. */
internal object UploadsInFlight {
    data class Entry(val kind: UploadKind, val label: String?, val sent: Long = 0, val total: Long = 0) {
        val percent: Int? get() = if (total > 0) (sent * 100 / total).toInt().coerceIn(0, 100) else null
    }

    val active = MutableStateFlow<Map<Long, Entry>>(emptyMap())
    private val next = AtomicLong()
    fun add(kind: UploadKind, label: String?): Long = next.incrementAndGet().also { id -> active.update { it + (id to Entry(kind, label)) } }
    fun remove(id: Long) = active.update { it - id }

    /** Only a change of whole percent is published: a 30 MB video is a hundred notification updates, not thousands. */
    fun progress(id: Long, sent: Long, total: Long) = active.update { now ->
        val e = now[id] ?: return@update now
        val moved = e.copy(sent = sent, total = total)
        if (moved.percent == e.percent && e.total == total) now else now + (id to moved)
    }
}

/**
 * Android's side of [BackgroundUploads]. Whatever runs the upload, it is never left to chance:
 * - Started while Circaso is open (the usual case: the person taps Send): [UploadService], a `dataSync` foreground
 *   service, keeps the process alive with a quiet notification after they leave.
 * - Started by the outbox's WorkManager job (the connection came back while Circaso was closed): that job promotes
 *   itself to the foreground with the same notification ([OutboxWorkerRun]); where Android 12+ refuses a foreground
 *   start from the background it carries on as a WorkManager job (about ten minutes, then retried).
 * - Always: a WorkManager job is queued as a safety net, so an upload cut off by the process dying (MIUI, a swipe
 *   away, Android 15's daily `dataSync` limit) resumes on its own with a connection, where it stopped
 *   ([ResumableUpload]).
 * The service is never stopped from here (stopping one before it reached the foreground crashes on Android 12+); it
 * watches [UploadsInFlight] and stops itself once nothing is left, which also clears the notification.
 */
class AndroidBackgroundUploads(private val context: Context) : BackgroundUploads {
    override suspend fun <T> keep(kind: UploadKind, label: String?, work: suspend () -> T): T {
        val token = UploadsInFlight.add(kind, label)
        AndroidBackgroundSync.scheduleResume(context)
        val worker = currentCoroutineContext()[OutboxWorkerRun]
        if (worker != null) {
            worker.promote()
        } else {
            // Allowed while Circaso is open or already holds a foreground service; refused from the background on
            // Android 12+ (ForegroundServiceStartNotAllowedException), when the safety-net job is what resumes it.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, UploadService::class.java)) }
                .onSuccess { Log.i(Tag, "upload ${kind.name}: foreground service") }
                .onFailure { Log.w(Tag, "upload ${kind.name}: foreground service refused (${it.javaClass.simpleName}); the outbox job resumes it if cut off") }
        }
        var reported = -1
        return try {
            withContext(UploadProgress { sent, total ->
                UploadsInFlight.progress(token, sent, total)
                val p = if (total > 0) (sent * 10 / total).toInt() else -1
                if (p != reported) { reported = p; Log.i(Tag, "upload ${kind.name}: ${p * 10}% of $total bytes") }
            }) { work() }
        } finally {
            UploadsInFlight.remove(token)
            Log.i(Tag, "upload ${kind.name}: finished")
        }
    }

    override fun failed(kind: UploadKind) {
        if (onScreen()) return // the screen already shows it, with Send Again
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, FailedChannel)
            .setSmallIcon(com.circaso.shared.R.drawable.ic_stat_circaso)
            .setContentTitle(kind.failed)
            .setContentText("Open Circaso to send it again.")
            .setContentIntent(openApp(context, 21))
            .setAutoCancel(true)
            .build()
        runCatching { notifications.notify(FailedId + kind.ordinal, n) }
    }

    private fun onScreen(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    companion object {
        internal const val Tag = "CircasoUploads"
        internal const val SendingChannel = "uploads"
        internal const val FailedChannel = "uploads_failed"
        internal const val FailedId = 4200
        /** The service's notification; the outbox job's own foreground notification is [WorkerNotificationId]. */
        internal const val ServiceNotificationId = 4100
        const val WorkerNotificationId = 4101

        internal fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val m = context.getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(NotificationChannel(SendingChannel, "Sending", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while photos, videos, voice notes and moments finish sending"
            })
            m.createNotificationChannel(NotificationChannel(FailedChannel, "Didn't send", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When something you sent needs you to send it again"
            })
        }

        private fun openApp(context: Context, request: Int): PendingIntent? {
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) } ?: return null
            return PendingIntent.getActivity(context, request, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * The quiet "Sending…" notification: "Sending a video to Amina…" with its percent, or "Sending 3 items…".
         * The name stays off the lock screen. No cancel button: a stopped upload would only come back from the outbox
         * (it resumes after a stop by design); deleting the message in the chat is the way to take it back.
         */
        fun sendingNotification(context: Context): Notification = sendingNotification(context, UploadsInFlight.active.value.values)

        internal fun sendingNotification(context: Context, entries: Collection<UploadsInFlight.Entry>): Notification {
            ensureChannels(context)
            val title = when {
                entries.isEmpty() -> "Sending…"
                entries.size == 1 -> entries.first().kind.sendingTo(entries.first().label) + "…"
                else -> "Sending ${entries.size} items…"
            }
            val total = entries.sumOf { it.total }
            val percent = if (total > 0 && entries.all { it.total > 0 }) (entries.sumOf { it.sent } * 100 / total).toInt().coerceIn(0, 100) else null
            val text = (percent?.let { "$it% · " } ?: "") + "It carries on if you leave Circaso."
            val public = NotificationCompat.Builder(context, SendingChannel)
                .setSmallIcon(com.circaso.shared.R.drawable.ic_stat_circaso)
                .setContentTitle(if (entries.size > 1) "Sending ${entries.size} items…" else "Sending…")
                .build()
            return NotificationCompat.Builder(context, SendingChannel)
                .setSmallIcon(com.circaso.shared.R.drawable.ic_stat_circaso)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, percent ?: 0, percent == null)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setContentIntent(openApp(context, 20))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
                .build()
        }
    }
}

/** Keeps uploads going in the background; shows what is sending and stops itself when nothing is. */
class UploadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always reach the foreground first: Android requires it of every startForegroundService.
        val started = runCatching {
            ServiceCompat.startForeground(this, AndroidBackgroundUploads.ServiceNotificationId, AndroidBackgroundUploads.sendingNotification(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }
        if (started.isFailure) {
            Log.w(AndroidBackgroundUploads.Tag, "foreground start refused: ${started.exceptionOrNull()?.javaClass?.simpleName}")
            stopSelf(); return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            scope.launch {
                UploadsInFlight.active.collect { active ->
                    if (active.isEmpty()) {
                        // A short grace, so back-to-back photos keep one notification instead of flickering.
                        delay(800)
                        if (UploadsInFlight.active.value.isEmpty()) stop()
                    } else {
                        runCatching { getSystemService(NotificationManager::class.java).notify(AndroidBackgroundUploads.ServiceNotificationId, AndroidBackgroundUploads.sendingNotification(this@UploadService, active.values)) }
                    }
                }
            }
        }
        // Not restarted by Android: an upload that was cut off is the outbox's to resume (its safety-net job).
        return START_NOT_STICKY
    }

    /**
     * Android 15+: `dataSync` services get six hours a day. At the limit, stop at once (going on would crash the app);
     * the uploads carry on while the process lives and the outbox's job resumes anything cut off.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(AndroidBackgroundUploads.Tag, "dataSync time limit reached: stopping the service; the outbox job resumes")
        AndroidBackgroundSync.scheduleResume(this)
        stop()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
