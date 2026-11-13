package com.circaso.core.platform

import com.circaso.core.push.PushTokenRegistrar
import com.google.firebase.messaging.FirebaseMessaging
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.core.notifications.PushNotices
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.async
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
        com.circaso.core.common.Diagnostics.note("push: received ${message.data["kind"] ?: "notice"} (priority ${message.priority})")
        // Spaces is archived (2026-11-05): an older server's space.* pushes have nothing to open here, so say nothing.
        if (message.data["kind"]?.startsWith("space.") == true) return
        // Someone's new phone can't open my moments (silent, 2026-11-08): seal fresh copies for it now, showing nothing.
        if (message.data["kind"] == com.circaso.feature.status.data.repository.MomentKeyRequestPush.Kind) {
            runCatching {
                val koin = KoinPlatform.getKoin()
                runBlocking {
                    com.circaso.feature.status.data.repository.MomentKeyRequestPush.handle(
                        message.data,
                        repository = { koin.get() },
                        drain = { koin.get<com.circaso.core.sync.OutboxProcessor>().drain() },
                        windowMillis = SyncWindowMillis,
                    )
                }
            }
            return
        }
        // A member's new phone can't read a group's name and photo (silent, 2026-11-09): pulling the inbox answers it.
        if (message.data["kind"] == com.circaso.feature.messaging.data.repository.GroupKeyRequestPush.Kind) {
            runCatching {
                val koin = KoinPlatform.getKoin()
                runBlocking {
                    com.circaso.feature.messaging.data.repository.GroupKeyRequestPush.handle(
                        message.data,
                        sync = { koin.get<com.circaso.feature.messaging.domain.MessagesRepository>().syncInbox() },
                        windowMillis = SyncWindowMillis,
                    )
                }
            }
            return
        }
        runCatching {
            val koin = KoinPlatform.getKoin()
            val notices = koin.get<PushNotices>()
            val callId = message.data["callId"]
            // A ringing call (guide CL1): ring now, the call itself loads when the app opens. With Notification
            // privacy on it says only "Incoming call".
            if (callId != null && message.data["kind"] == "call.ringing") {
                // The server pushes every ring, socket or not (2026-11-11). Circaso in front with this call already on
                // its answer screen (the frame came first): that screen rings, so no notification over it.
                val onScreen = koin.get<com.circaso.feature.calls.CallController>().state.value
                if (koin.get<com.circaso.core.platform.CurrentActivity>().inFront.value &&
                    onScreen?.callId == callId && onScreen.phase == com.circaso.feature.calls.CallPhase.Incoming
                ) return
                val notifier = koin.get<com.circaso.core.calls.AndroidCallNotifier>()
                runBlocking {
                    val chatId = message.data["chatId"]
                    // The caller's photo (the group's for a group call) from this phone's chats, looked up beside the
                    // wording; never with Notification privacy on (a check that doesn't answer counts as on).
                    val photoLookup = async {
                        if (withTimeoutOrNull(200) { notices.privacyOn() } != false) null
                        else withTimeoutOrNull(250) { chatId?.let { koin.get<com.circaso.feature.messaging.domain.ChatsRepository>().chat(it).first()?.avatarUrl } }
                    }
                    // A direct call is named as the chat is (the name I saved for them, else theirs); a group by its name.
                    val name = withTimeoutOrNull(2_000) {
                        chatId?.let { id ->
                            val chat = koin.get<com.circaso.feature.messaging.domain.ChatsRepository>().chat(id).first()
                            if (chat?.kind == com.circaso.feature.messaging.domain.ChatKind.Direct) chat.members.firstOrNull { it.userId == chat.peerId }?.displayName
                            else koin.get<ChatsDao>().chat(id)?.name
                        }
                    } ?: message.data["callerName"]
                    val content = withTimeoutOrNull(1_000) { notices.incomingCall(name, message.data["callKind"] == "VIDEO") }
                        ?: com.circaso.core.notifications.NotificationText.compose(com.circaso.core.notifications.NotificationEvent.IncomingCall(null, false), private = true, fiat = null)
                    // Already done in the usual case; the ring waits at most a beat more for it.
                    val photo = withTimeoutOrNull(100) { photoLookup.await() }.also { if (it == null) photoLookup.cancel() }
                    notifier.ringWithPhoto(callId, content.title, content.text, photo)
                }
                return
            }
            // Cancelled or answered elsewhere: stop ringing (a missed one is then worded below).
            if (callId != null) koin.get<com.circaso.core.calls.AndroidCallNotifier>().clear(callId)
            val notifications = koin.get<ChatNotifications>() as? AndroidChatNotifications ?: return
            // A message the socket already brought and notified (the server pushes while Circaso is away, 2026-11-13):
            // shown once, so the phone doesn't buzz twice for it.
            val messageId = message.data["messageId"]?.takeIf { it.isNotBlank() }
            val posted = koin.get<com.circaso.core.notifications.PostedMessages>()
            if (messageId != null && runBlocking { posted.posted(messageId) }) {
                com.circaso.core.common.Diagnostics.note("push: message already notified from the socket")
                return
            }
            val received = com.circaso.core.sync.nowMillis()
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
                    if (messageId != null && !posted.claim(messageId)) return@withTimeoutOrNull
                    val coinsOff = koin.get<com.circaso.core.datastore.AppPreferences>().flag(com.circaso.core.datastore.AppPreferences.MoneySoundOff).first()
                    notifications.show(notice, coins = !coinsOff)
                    if (messageId != null) com.circaso.core.common.Diagnostics.note("notify: push message posted ${com.circaso.core.sync.nowMillis() - received} ms after the push")
                }
            }
        }
    }

    private companion object {
        /** FCM gives a data message about ten seconds of foreground time; leave room for the notification itself. */
        const val SyncWindowMillis = 8_000L
    }
}
