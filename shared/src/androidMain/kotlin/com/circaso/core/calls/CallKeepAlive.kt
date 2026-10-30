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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.circaso.feature.calls.CallController
import com.circaso.feature.calls.CallPhase
import kotlinx.coroutines.CoroutineScope
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
        scope.launch {
            controller.state
                .map { s -> s?.takeIf { it.phase == CallPhase.Connecting || it.phase == CallPhase.Active || it.phase == CallPhase.Ringing || it.phase == CallPhase.Reconnecting }?.let { Live(it.title, it.camera, it.video) } }
                .distinctUntilChanged()
                .collect { live ->
                    if (live == null) context.stopService(Intent(context, CallService::class.java))
                    else ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).putExtra(CallService.Title, live.title).putExtra(CallService.Camera, live.camera).putExtra(CallService.VideoCall, live.videoCall))
                }
        }
    }

    private data class Live(val title: String, val camera: Boolean, val videoCall: Boolean)
}

/** The foreground service itself: no work of its own, it only tells Android a call is live so nothing is cut off. */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(Title).orEmpty().ifBlank { "Circaso" }
        val camera = intent?.getBooleanExtra(Camera, false) == true
        val video = intent?.getBooleanExtra(VideoCall, false) == true
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(ChannelId, "Ongoing calls", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while a call is on" })
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val tap = open?.let { PendingIntent.getActivity(this, 7, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val notification: Notification = NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(if (video) "Video call · $title" else "Call · $title")
            .setContentText("Call in progress. Tap to return.")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(tap)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        // The camera type only while the camera is on and allowed; Android refuses a type whose permission isn't granted.
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (camera && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        val micAllowed = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micAllowed) { stopSelf(); return START_NOT_STICKY }
        runCatching { ServiceCompat.startForeground(this, NotificationId, notification, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types else 0) }
            .onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping Circaso away keeps the call: the service is what keeps it alive.
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        const val Title = "title"
        const val Camera = "camera"
        const val VideoCall = "video"
        const val ChannelId = "ongoing_call"
        const val NotificationId = 4401
    }
}
