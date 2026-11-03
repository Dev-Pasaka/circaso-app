package com.circaso.core.platform

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** What is uploading right now, by a token per upload; [UploadService] shows it and stops when it is empty. */
internal object UploadsInFlight {
    val active = MutableStateFlow<Map<Long, UploadKind>>(emptyMap())
    private val next = AtomicLong()
    fun add(kind: UploadKind): Long = next.incrementAndGet().also { id -> active.update { it + (id to kind) } }
    fun remove(id: Long) = active.update { it - id }
}

/**
 * Android's side of [BackgroundUploads]: while anything uploads, [UploadService] (a `dataSync` foreground service)
 * keeps the process alive with a quiet notification, so leaving Circaso doesn't stop it. The service is never
 * stopped from here (stopping one before it reached the foreground crashes on Android 12+); it watches
 * [UploadsInFlight] and stops itself once nothing is left.
 */
class AndroidBackgroundUploads(private val context: Context) : BackgroundUploads {
    override suspend fun <T> keep(kind: UploadKind, work: suspend () -> T): T {
        val token = UploadsInFlight.add(kind)
        // Allowed while Circaso is open or already holds a foreground service; otherwise the work still runs, just
        // without the guarantee (an outbox run in the background is WorkManager's, which has its own).
        runCatching { ContextCompat.startForegroundService(context, Intent(context, UploadService::class.java)) }
        return try { work() } finally { UploadsInFlight.remove(token) }
    }

    override fun failed(kind: UploadKind) {
        if (onScreen()) return // the screen already shows it, with Send Again
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return
        ensureChannels(context)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val tap = open?.let { PendingIntent.getActivity(context, 21, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val n = NotificationCompat.Builder(context, FailedChannel)
            .setSmallIcon(com.circaso.shared.R.drawable.ic_stat_circaso)
            .setContentTitle(kind.failed)
            .setContentText("Open Circaso to send it again.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { notifications.notify(FailedId + kind.ordinal, n) }
    }

    private fun onScreen(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    internal companion object {
        const val SendingChannel = "uploads"
        const val FailedChannel = "uploads_failed"
        const val FailedId = 4200

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val m = context.getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(NotificationChannel(SendingChannel, "Sending", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while photos, videos, voice notes and moments finish sending"
            })
            m.createNotificationChannel(NotificationChannel(FailedChannel, "Didn't send", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When something you sent needs you to send it again"
            })
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
            ServiceCompat.startForeground(this, NotificationId, notification(UploadsInFlight.active.value.values),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }
        if (started.isFailure) { stopSelf(); return START_NOT_STICKY }
        if (!watching) {
            watching = true
            scope.launch {
                UploadsInFlight.active.collect { active ->
                    if (active.isEmpty()) {
                        // A short grace, so back-to-back photos keep one notification instead of flickering.
                        delay(800)
                        if (UploadsInFlight.active.value.isEmpty()) {
                            ServiceCompat.stopForeground(this@UploadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    } else {
                        runCatching { getSystemService(NotificationManager::class.java).notify(NotificationId, notification(active.values)) }
                    }
                }
            }
        }
        // Not restarted by Android: an upload that was cut off is the outbox's to resume.
        return START_NOT_STICKY
    }

    private fun notification(kinds: Collection<UploadKind>): android.app.Notification {
        AndroidBackgroundUploads.ensureChannels(this)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val tap = open?.let { PendingIntent.getActivity(this, 20, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val title = when {
            kinds.isEmpty() -> "Sending"
            kinds.size == 1 -> kinds.first().sending + "…"
            else -> "Sending ${kinds.size} items…"
        }
        return NotificationCompat.Builder(this, AndroidBackgroundUploads.SendingChannel)
            .setSmallIcon(com.circaso.shared.R.drawable.ic_stat_circaso)
            .setContentTitle(title)
            .setContentText("It carries on if you leave Circaso.")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tap)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val NotificationId = 4100
    }
}
