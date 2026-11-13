package com.circaso.core.notifications

import com.circaso.core.session.AuthManager
import com.circaso.core.session.AuthState
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.MessagesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reply and Mark as read on a chat notification, without opening Circaso (2026-11-13): Android's notification actions
 * (ChatNotificationActionReceiver) and iPhone's (the CHAT_MESSAGE category, NotificationActions.ios.kt) both come
 * here, so both phones follow one set of rules.
 *
 * A reply goes through the outbox exactly as one typed in the chat: encrypted when the outbox sends it, now or once
 * there's a connection. Replying is reading, so the chat is marked read too. Nothing is sent to a chat I've left or to
 * someone I blocked, nor while nobody is signed in.
 */
class NotificationReplies(
    private val chatsDao: ChatsDao,
    private val chats: ChatsRepository,
    private val messages: MessagesRepository,
    private val contacts: ContactsRepository,
    /** The session's state ([AuthManager.state]). */
    private val authState: kotlinx.coroutines.flow.StateFlow<AuthState>,
    /** The signed-in person ([AuthManager.userId]). */
    private val ownUserId: () -> String?,
) {
    sealed interface Outcome {
        /** In the outbox: it sends on its own. */
        data object Queued : Outcome

        /** Nothing typed: nothing to do. */
        data object Empty : Outcome

        /**
         * Not queued: signed out, a chat I've left, someone I blocked, or the write didn't finish in time.
         * [chatTitle] names the chat as the chat list does (saved names), when it's known. [canRetry] is false when
         * another try would fail the same way (a chat I've left, someone I blocked, signed out).
         */
        data class NotSent(val chatTitle: String?, val canRetry: Boolean = true) : Outcome
    }

    /** Queues [text] for [chatId] and, unless the caller does it later ([thenRead] false), marks the chat read. */
    suspend fun reply(chatId: String, text: String, thenRead: Boolean = true): Outcome {
        val words = text.trim()
        if (words.isEmpty()) return Outcome.Empty
        val title = title(chatId)
        if (!canReply(chatId)) return Outcome.NotSent(title, canRetry = false)
        val queued = attempt { messages.send(chatId, words) }
        if (!queued) return Outcome.NotSent(title)
        if (thenRead) markRead(chatId)
        return Outcome.Queued
    }

    /**
     * Marks [chatId] read here and on the server (now over the socket, or once it's back). [pullFirst] fetches the
     * inbox first: on iPhone the message being answered was only opened by the notification extension, so the app
     * hasn't saved it yet and "read" would stop short of it.
     */
    suspend fun markRead(chatId: String, pullFirst: Boolean = false) {
        if (!signedIn()) return
        if (pullFirst) withTimeoutOrNull(PullMillis) { runCatching { messages.syncInbox() } }
        attempt { messages.markRead(chatId) }
    }

    /** A chat I'm still in, with nobody I blocked on the other side, while signed in. */
    suspend fun canReply(chatId: String): Boolean {
        if (!signedIn()) return false
        val chat = chatsDao.chat(chatId) ?: return false
        if (chat.left) return false
        if (chat.kind == MessagingWire.Group) return true
        val me = ownUserId()
        val peer = chatsDao.members(chatId).firstOrNull { it.userId != me }?.userId ?: return true
        // Not known in time counts as blocked: a reply must never reach someone I blocked.
        val blocked = withTimeoutOrNull(StepMillis) { contacts.blocks.first() } ?: return false
        return blocked.none { it.userId == peer }
    }

    /**
     * After the outbox's try: my latest message with [text] in [chatId] was refused by the server for good (removed
     * from the group on another phone, say). One only waiting for a connection is not refused: it sends later.
     */
    suspend fun refused(chatId: String, text: String): Boolean = runCatching {
        val words = text.trim()
        val mine = withTimeoutOrNull(StepMillis) { messages.thread(chatId).first() }.orEmpty()
            .filterIsInstance<com.circaso.feature.messaging.domain.Message.Text>()
            .lastOrNull { it.outgoing && it.text == words }
        mine?.state == com.circaso.feature.messaging.domain.DeliveryState.Failed
    }.getOrDefault(false)

    /** The chat's name as the chat list shows it (saved names), for "couldn't send"; null when unknown. */
    suspend fun title(chatId: String): String? =
        runCatching { withTimeoutOrNull(StepMillis) { chats.chat(chatId).first() }?.title }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Launched in the background just for this, the session may still be loading from the secure store. */
    private suspend fun signedIn(): Boolean {
        withTimeoutOrNull(StepMillis) { authState.first { it !is AuthState.Restoring } }
        return authState.value is AuthState.Active && ownUserId() != null
    }

    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        withTimeoutOrNull(StepMillis) { block() } != null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private companion object {
        /** Each step is a local write; the outbox does the sending. */
        const val StepMillis = 4_000L

        /** The inbox over the network, before marking read (iPhone only). */
        const val PullMillis = 8_000L
    }
}
