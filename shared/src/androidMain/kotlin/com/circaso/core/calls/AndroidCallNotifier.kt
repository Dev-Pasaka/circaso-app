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
 * Design H1 while Circaso is in the background or closed: the ringing push is data-only, so the app shows its own call
 * notification (Android's call style: Answer and Decline) with the ringtone. On a locked or idle phone it opens full
 * screen straight onto the answer screen, as a phone call does; over another app it's a heads-up. It stops after the
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

    /** [title] and [text] come worded from `PushNotices.incomingCall`: "Incoming call" alone with Notification privacy on. */
    fun ringing(callId: String, title: String, text: String) {
        if (!manager.areNotificationsEnabled()) return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ExtraCallId, callId)
        }
        val tap = open?.let { PendingIntent.getActivity(context, callId.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) } ?: return
        val decline = PendingIntent.getBroadcast(
            context, callId.hashCode(),
            Intent(context, CallDeclineReceiver::class.java).putExtra(ExtraCallId, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val caller = androidx.core.app.Person.Builder().setName(title).setImportant(true).build()
        val n = NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(com.circaso.shared.R.drawable.ic_stat_circaso)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, tap))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(RingMillis)
            .setContentIntent(tap)
            // Full screen on a locked or idle phone. Android 14+ lets the person turn this off for an app; then it's a
            // heads-up notification that still rings.
            .setFullScreenIntent(tap, true)
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
