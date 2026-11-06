package com.circaso.core.platform

import com.circaso.core.push.PushTokenRegistrar
import com.google.firebase.messaging.FirebaseMessaging
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.core.notifications.PushNotices
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.mp.KoinPlatform
import kotlin.coroutines.resume

/** This phone's Firebase Cloud Messaging token. Null when Firebase cannot give one now (offline, Play services missing). */
class AndroidPushTokenProvider : PushTokenProvider {
    override suspend fun token(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (continuation.isActive) continuation.resume(if (task.isSuccessful) task.result else null)
        }
    }
}

/**
 * Firebase calls this when it issues a new token (the registrar sends it if someone is signed in) and for each
 * data-only push. A message push carries `chatId` and `messageId` (guide, section 8): the inbox is pulled and
 * decrypted first, inside FCM's short window, and the notification shows the real sender and text.
 */
class CircasoMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        runCatching { KoinPlatform.getKoin().get<PushTokenRegistrar>().onNewToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Spaces is archived (2026-11-05): an older server's space.* pushes have nothing to open here, so say nothing.
        if (message.data["kind"]?.startsWith("space.") == true) return
        runCatching {
            val koin = KoinPlatform.getKoin()
            val notices = koin.get<PushNotices>()
            val callId = message.data["callId"]
            // A ringing call (guide CL1): ring now, the call itself loads when the app opens. With Notification
            // privacy on it says only "Incoming call".
            if (callId != null && message.data["kind"] == "call.ringing") {
                val notifier = koin.get<com.circaso.core.calls.AndroidCallNotifier>()
                runBlocking {
                    val chatId = message.data["chatId"]
                    val name = withTimeoutOrNull(2_000) { chatId?.let { koin.get<ChatsDao>().chat(it)?.name } } ?: message.data["callerName"]
                    val content = withTimeoutOrNull(1_000) { notices.incomingCall(name, message.data["callKind"] == "VIDEO") }
                        ?: com.circaso.core.notifications.NotificationText.compose(com.circaso.core.notifications.NotificationEvent.IncomingCall(null, false), private = true, fiat = null)
                    notifier.ringing(callId, content.title, content.text)
                }
                return
            }
            // Cancelled or answered elsewhere: stop ringing (a missed one is then worded below).
            if (callId != null) koin.get<com.circaso.core.calls.AndroidCallNotifier>().clear(callId)
            val notifications = koin.get<ChatNotifications>() as? AndroidChatNotifications ?: return
            runBlocking {
                withTimeoutOrNull(SyncWindowMillis) {
                    // A push with its own alert ("Payment sent", "Name is on Circaso"…) reaches here only while Circaso is
                    // on screen, where the system doesn't show it: show its words with its tap, so it can still be opened.
                    val notice = notices.forPush(message.data)
                        ?: message.notification?.takeIf { message.data["kind"] != "session.revoked" }?.let { alert ->
                            com.circaso.core.notifications.Notice(
                                com.circaso.core.notifications.NotificationContent(alert.title ?: "Circaso", alert.body.orEmpty()),
                                com.circaso.core.notifications.NoticeChannel.Message,
                                "push:${message.messageId}",
                                com.circaso.core.notifications.NotificationDestination.fromPush(message.data)
                                    ?: com.circaso.core.notifications.NotificationDestination.Inbox,
                            )
                        }
                        ?: return@withTimeoutOrNull
                    val coinsOff = koin.get<com.circaso.core.datastore.AppPreferences>().flag(com.circaso.core.datastore.AppPreferences.MoneySoundOff).first()
                    notifications.show(notice, coins = !coinsOff)
                }
            }
        }
    }

    private companion object {
        /** FCM gives a data message about ten seconds of foreground time; leave room for the notification itself. */
        const val SyncWindowMillis = 8_000L
    }
}
