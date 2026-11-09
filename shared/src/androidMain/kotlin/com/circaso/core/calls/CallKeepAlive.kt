package com.circaso.core.calls

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.circaso.feature.calls.CallController
import com.circaso.feature.calls.CallPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a call going when the person leaves Circaso (Pasaka, 2026-10-30): while a call is connecting or on, a
 * foreground service holds the microphone (and the camera while it's on) with an ongoing "Call in progress"
 * notification that returns to the call. It stops the moment the call ends.
 */
class CallKeepAlive(private val context: Context, private val controller: CallController, private val scope: CoroutineScope) {
    fun start() {
        // On the main thread, where the service's own callbacks run, so its bookkeeping needs no locks.
        scope.launch(Dispatchers.Main) {
            controller.state
                .map { s -> s?.takeIf { it.phase == CallPhase.Connecting || it.phase == CallPhase.Active || it.phase == CallPhase.Ringing || it.phase == CallPhase.Reconnecting }?.let { CallService.Spec(it.title, it.camera, it.video) } }
                .distinctUntilChanged()
                .collect { spec -> if (spec == null) CallService.stop() else CallService.show(context, spec) }
        }
    }
}

/**
 * The foreground service itself: no work of its own, it only tells Android a call is live so nothing is cut off.
 *
 * Android closes the whole app when a service started with startForegroundService doesn't reach the foreground in
 * time, and a stop that lands before it does counts as not reaching it (Redmi, 2026-11-09: the app closed on every
 * call). So it's started once per call and every later change goes to the running service; a stop that arrives
 * while a start is still on its way waits for it; and every start reaches the foreground before anything else.
 */
class CallService : Service() {
    data class Spec(val title: String, val camera: Boolean, val videoCall: Boolean)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pending > 0) pending--
        val spec = wanted ?: shown ?: Spec("", camera = false, videoCall = false)
        if (!present(spec)) {
            // Nothing Android would accept: stopping now is the least bad outcome, and logged so it's seen.
            Log.w(Tag, "call service couldn't reach the foreground")
            stopSelf()
            return START_NOT_STICKY
        }
        running = this
        if (wanted == null && pending == 0) finish()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping Circaso away keeps the call: the service is what keeps it alive.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    /** Shows [spec]'s notification in the foreground; false when Android refused every type this call could use. */
    private fun present(spec: Spec): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(ChannelId, "Ongoing calls", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while a call is on" })
        }
        val title = spec.title.ifBlank { "Circaso" }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val tap = open?.let { PendingIntent.getActivity(this, 7, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val notification: Notification = NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(com.circaso.shared.R.drawable.ic_stat_circaso)
            .setContentTitle(if (spec.videoCall) "Video call · $title" else "Call · $title")
            .setContentText("Call in progress. Tap to return.")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(tap)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        // Android refuses a type whose permission isn't granted, and the camera's from the background: the camera type
        // is tried only while it's on and allowed, and the microphone alone is the fallback.
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val withCamera = spec.camera && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val attempts = if (withCamera) listOf(mic or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA, mic) else listOf(mic)
        for (types in attempts) {
            val ok = runCatching { ServiceCompat.startForeground(this, NotificationId, notification, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types else 0) }
                .onFailure { Log.w(Tag, "startForeground refused types=$types", it) }
                .isSuccess
            if (ok) { shown = spec; return true }
        }
        return false
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (running === this) running = null
        shown = null
    }

    companion object {
        const val ChannelId = "ongoing_call"
        const val NotificationId = 4401
        private const val Tag = "CallService"

        // Main thread only (CallKeepAlive collects there and Android calls the service there).
        private var running: CallService? = null
        /** Starts asked of Android that haven't reached onStartCommand yet: a stop must wait for them. */
        private var pending = 0
        /** What the call wants shown now; null once it has ended. */
        private var wanted: Spec? = null
        private var shown: Spec? = null

        fun show(context: Context, spec: Spec) {
            wanted = spec
            running?.let { service ->
                if (pending == 0) { if (spec != shown) service.present(spec); return }
            }
            if (pending > 0) return // the start on its way shows the latest spec
            // Without the microphone the service can't be a call's, so it isn't started at all (starting it would
            // only end in Android closing the app).
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java)) }
                .onSuccess { pending++ }
                .onFailure { Log.w(Tag, "call service not started", it) }
        }

        fun stop() {
            wanted = null
            // With a start still on its way, onStartCommand reaches the foreground first and then finishes.
            if (pending == 0) running?.finish()
        }
    }
}
