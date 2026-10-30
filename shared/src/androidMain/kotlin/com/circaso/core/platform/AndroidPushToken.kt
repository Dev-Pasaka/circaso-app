package com.circaso.core.platform

import com.circaso.core.push.PushTokenRegistrar
import com.google.firebase.messaging.FirebaseMessaging
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.local.MessagesDao
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.MessagesRepository
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
        // A ringing call (guide CL1): ring now, the call itself loads when the app opens.
        // Calls (guide CL1): `kind` is call.ringing / call.missed / call.ended, `callKind` VOICE or VIDEO.
        message.data["callId"]?.let { callId ->
            runCatching {
                val koin = KoinPlatform.getKoin()
                val notifier = koin.get<com.circaso.core.calls.AndroidCallNotifier>()
                when (message.data["kind"]) {
                    "call.ringing" -> {
                        val chatId = message.data["chatId"]
                        val title = runBlocking { withTimeoutOrNull(2_000) { chatId?.let { koin.get<ChatsDao>().chat(it)?.name } } } ?: message.data["callerName"] ?: "Incoming call"
                        notifier.ringing(callId, title, message.data["callKind"] == "VIDEO")
                    }
                    // Cancelled or answered elsewhere: stop ringing. A missed call arrives as an alert the system shows.
                    else -> notifier.clear(callId)
                }
            }
            return
        }
        // Money received (a data push, so the app picks the sound): "Bob sent you money".
        if (message.data["kind"] == "payment.received") {
            runCatching {
                val koin = KoinPlatform.getKoin()
                val off = runBlocking { withTimeoutOrNull(1_000) { koin.get<com.circaso.core.datastore.AppPreferences>().flag(com.circaso.core.datastore.AppPreferences.MoneySoundOff).first() } } ?: false
                val notifications = koin.get<ChatNotifications>() as? AndroidChatNotifications
                notifications?.showMoney(message.data["title"] ?: "Circaso", message.data["body"] ?: "You received money", coins = !off)
            }
            return
        }
        val chatId = message.data["chatId"] ?: return
        val messageId = message.data["messageId"]
        runCatching {
            val koin = KoinPlatform.getKoin()
            val notifications = koin.get<ChatNotifications>()
            val chats = koin.get<ChatsRepository>()
            val messages = koin.get<MessagesRepository>()
            val chatsDao = koin.get<ChatsDao>()
            val messagesDao = koin.get<MessagesDao>()
            runBlocking {
                withTimeoutOrNull(SyncWindowMillis) {
                    if (messageId == null) {
                        // Added to a group: load it, then say so.
                        chats.refreshChat(chatId)
                        notifications.show(chatId, chatsDao.chat(chatId)?.name ?: "New group", "You were added to a group", null)
                    } else {
                        messages.syncInbox()
                        val row = messagesDao.byMessageId(messageId)
                        val chat = chatsDao.chat(chatId)
                        val sender = row?.let { r -> chatsDao.members(chatId).firstOrNull { it.userId == r.senderId }?.displayName }
                        val title = if (chat?.kind == "GROUP") chat.name ?: "Group" else sender ?: "New message"
                        val text = when {
                            row?.text != null && chat?.kind == "GROUP" && sender != null -> "$sender: ${row.text}"
                            row?.text != null -> row.text
                            else -> "New message"
                        }
                        notifications.show(chatId, title, text, messageId)
                    }
                }
            }
        }
    }

    private companion object {
        /** FCM gives a data message about ten seconds of foreground time; leave room for the notification itself. */
        const val SyncWindowMillis = 8_000L
    }
}
