package com.circaso.core.calls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Design H1 while Circaso is in the background: the ringing push is data-only, so the app shows its own high-priority
 * call notification with the ringtone. Tapping it opens the app straight onto the incoming call; it stops after the
 * backend's 45-second ring.
 */
class AndroidCallNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(ChannelId, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming voice and video calls"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun ringing(callId: String, title: String, video: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ExtraCallId, callId)
        }
        val tap = open?.let { PendingIntent.getActivity(context, callId.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val n = NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(title)
            .setContentText(if (video) "Circaso video call" else "Circaso voice call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(RingMillis)
            .setContentIntent(tap)
            .addAction(0, "Answer", tap)
            .build()
        runCatching { manager.notify(callId.hashCode(), n) }
    }

    fun clear(callId: String) = manager.cancel(callId.hashCode())

    companion object {
        const val ChannelId = "calls"
        const val ExtraCallId = "com.circaso.callId"
        const val RingMillis = 45_000L
    }
}
