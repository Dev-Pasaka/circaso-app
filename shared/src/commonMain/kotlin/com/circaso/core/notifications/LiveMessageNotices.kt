package com.circaso.core.notifications

import com.circaso.core.common.Diagnostics
import com.circaso.core.platform.ChatNotifications
import com.circaso.core.sync.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The messages this phone already showed a notification for, by message id (2026-11-13). A message can now reach the
 * phone twice, as a socket frame and as a push (the server pushes while Circaso is in the background), and whichever
 * path words it first posts it: the other finds it [claim]ed and stays quiet, so the phone buzzes once. Both paths use
 * the same notification key too (`chat:<chatId>`, `money:<transferId>`), so even a miss replaces rather than stacks.
 */
class PostedMessages(private val keep: Int = Keep) {
    private val lock = Mutex()
    private val ids = LinkedHashSet<String>()

    /** Whether a notification for [messageId] was already posted. */
    suspend fun posted(messageId: String): Boolean = lock.withLock { messageId in ids }

    /** True for the first caller only: that caller posts the notification. */
    suspend fun claim(messageId: String): Boolean = lock.withLock {
        if (!ids.add(messageId)) return@withLock false
        if (ids.size > keep) ids.remove(ids.first())
        true
    }

    private companion object { const val Keep = 500 }
}

/** Shows a [Notice] the way the platform does (Android: its channels and the coins chime). */
fun interface NoticePoster {
    suspend fun post(notice: Notice)

    /** [notice] for the message [messageId] that came over the socket (iOS keeps a copy for its push, see [NotificationSnapshotWriter.remember]). */
    suspend fun post(notice: Notice, messageId: String) = post(notice)
}

/** iOS until its own local notifications: APNs shows the push, which the server now sends while Circaso is away. */
object NoNoticePoster : NoticePoster {
    override suspend fun post(notice: Notice) {}
}

/** One `message.new` that was just decrypted and saved from the socket. */
class LiveMessage(val chatId: String, val messageId: String, val senderId: String, val sentAt: String?)

/**
 * Posts the notification for a message that arrived over the socket, at once (2026-11-13). Before, only a push ever
 * posted one, and the server never pushed while the socket looked open, which it does for a while after Circaso
 * leaves the screen (and all the time on Android, where the socket keeps running): the sender saw "delivered" at
 * once while the notification came only when the socket dropped. Not for my own messages, and not for the chat on
 * screen while Circaso is in front. Worded by [PushNotices] like the push, under the same key, with privacy.
 */
class LiveMessageNotices(
    /** [PushNotices.forArrived]: the words and key its push would get. */
    private val word: suspend (chatId: String, messageId: String) -> Notice?,
    private val notifications: ChatNotifications,
    private val posted: PostedMessages,
    private val poster: NoticePoster,
    private val ownUserId: suspend () -> String?,
    private val scope: CoroutineScope,
    private val now: () -> Long = ::nowMillis,
) {
    /**
     * Whether Circaso is in front, from the app's lifecycle. The open thread stays [ChatNotifications.activeChatId]
     * when the person leaves Circaso from it, and its messages are notified then (Android's notifier checks the same).
     */
    var inFront: Boolean = false
        private set

    fun onForeground() { inFront = true }

    fun onBackground() { inFront = false }

    fun onArrived(message: LiveMessage) {
        val arrivedAt = now()
        scope.launch {
            if (message.senderId == ownUserId()) return@launch
            if (inFront && notifications.activeChatId == message.chatId) return@launch
            val notice = runCatching { word(message.chatId, message.messageId) }.getOrNull() ?: return@launch
            if (!posted.claim(message.messageId)) return@launch
            poster.post(notice, message.messageId)
            val done = now()
            val sent = message.sentAt?.let { isoMillis(it) }?.let { " ${done - it} ms after sent," }.orEmpty()
            Diagnostics.note("notify: socket message posted ${done - arrivedAt} ms after the frame,$sent ${if (inFront) "in app" else "in background"}")
        }
    }

    private fun isoMillis(iso: String) = runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrNull()
}
