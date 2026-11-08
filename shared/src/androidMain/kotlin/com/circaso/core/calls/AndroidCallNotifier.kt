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
import androidx.core.graphics.drawable.IconCompat
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.toBitmap
import coil3.transform.CircleCropTransformation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Design H1 while Circaso is in the background or closed: the ringing push is data-only, so the app shows its own call
 * notification (Android's call style: Answer and Decline) with the ringtone. On a locked or idle phone it opens full
 * screen straight onto the answer screen, as a phone call does; over another app it's a heads-up. It stops after the
 * backend's 45-second ring.
 */
class AndroidCallNotifier(private val context: Context, private val scope: CoroutineScope? = null) {
    private val manager = NotificationManagerCompat.from(context)

    /** Calls ringing now, by id, with when they started: a photo that arrives late only updates a call still ringing. */
    private val ringingSince = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Its own loader (the app's singleton is set up by the UI, which may not be running), on Coil's process-wide disk
     * cache: the photo the chat list already downloaded is read from disk, keyed as [com.circaso.designsystem.components.Avatar] keys it.
     */
    private val images by lazy {
        ImageLoader.Builder(context).components { add(KtorNetworkFetcherFactory()) }.build()
    }

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

    /**
     * Rings with the caller's photo when this phone has it. The ring never waits on it: the photo gets [PhotoWaitMillis]
     * (a disk-cache read is a few milliseconds); if it isn't ready the call rings without it, and the notification is
     * updated, silently, once the photo arrives while the call is still ringing. [photoUrl] is null with Notification
     * privacy on, so a private ring never shows who is calling.
     */
    suspend fun ringWithPhoto(callId: String, title: String, text: String, photoUrl: String?) {
        if (!manager.areNotificationsEnabled()) return
        val pending = photoUrl?.let { url -> (scope ?: CoroutineScope(Dispatchers.IO)).async(Dispatchers.IO) { runCatching { photo(url) }.getOrNull() } }
        val quick = pending?.let { withTimeoutOrNull(PhotoWaitMillis) { it.await() } }
        ringing(callId, title, text, quick)
        if (pending != null && quick == null) {
            (scope ?: CoroutineScope(Dispatchers.IO)).launch {
                val late = withTimeoutOrNull(PhotoLateMillis) { pending.await() } ?: return@launch
                val since = ringingSince[callId] ?: return@launch
                // Only as an update to a ring still showing: once it was tapped, answered or cancelled, a new post would
                // ring again and reopen the answer screen.
                if (!showing(callId)) { ringingSince.remove(callId); return@launch }
                ringing(callId, title, text, late, startedAt = since)
            }
        }
    }

    private fun showing(callId: String): Boolean = runCatching {
        context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id == callId.hashCode() }
    }.getOrDefault(false)

    /** The caller's photo as a circle, small enough for a notification; null when it can't be had. */
    private suspend fun photo(url: String): IconCompat? {
        val key = url.substringBefore('?')
        val request = ImageRequest.Builder(context).data(url).diskCacheKey(key).memoryCacheKey("$key@notification")
            .size(PhotoPx).transformations(CircleCropTransformation()).allowHardware(false).build()
        val result = images.execute(request) as? SuccessResult ?: return null
        return IconCompat.createWithBitmap(result.image.toBitmap())
    }

    /** [title] and [text] come worded from `PushNotices.incomingCall`: "Incoming call" alone with Notification privacy on. */
    fun ringing(callId: String, title: String, text: String, icon: IconCompat? = null, startedAt: Long = System.currentTimeMillis()) {
        if (!manager.areNotificationsEnabled()) return
        val left = RingMillis - (System.currentTimeMillis() - startedAt)
        if (left <= 0) return
        ringingSince[callId] = startedAt
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
        val caller = androidx.core.app.Person.Builder().setName(title).setIcon(icon).setImportant(true).build()
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
            .setTimeoutAfter(left)
            // The update that adds a late photo must not ring again or reopen the full-screen answer screen.
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            // Full screen on a locked or idle phone. Android 14+ lets the person turn this off for an app; then it's a
            // heads-up notification that still rings.
            .setFullScreenIntent(tap, true)
            .build()
        runCatching { manager.notify(callId.hashCode(), n) }
    }

    fun clear(callId: String) {
        ringingSince.remove(callId)
        manager.cancel(callId.hashCode())
    }

    companion object {
        const val ChannelId = "calls"
        const val ExtraCallId = "com.circaso.callId"
        const val RingMillis = 45_000L
        /** How long a ring waits for the caller's photo before it rings without it. */
        const val PhotoWaitMillis = 300L
        /** How long a photo that missed the ring may still arrive and be added. */
        const val PhotoLateMillis = 8_000L
        /** The photo's size in the notification, in pixels (the call style's avatar is 48–64 dp). */
        const val PhotoPx = 192
    }
}
