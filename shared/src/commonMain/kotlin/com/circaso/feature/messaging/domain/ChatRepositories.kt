package com.circaso.feature.messaging.domain

import com.circaso.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Chats and their members. Reads come from Room; `refresh` reloads from the server. Everything that changes a chat
 * on the server is **online-only**: the controls are disabled offline, nothing is queued.
 */
interface ChatsRepository {
    val chats: Flow<List<ChatSummary>>
    fun chat(chatId: String): Flow<Chat?>

    suspend fun refresh(): AppResult<Unit>
    suspend fun refreshChat(chatId: String): AppResult<Chat>

    /** Opens (or finds) the direct chat with a person (M4). */
    suspend fun openDirect(userId: String): AppResult<Chat>

    /** Creates a group (M5). [idempotencyKey] stays the same across retries of one attempt, so there is never a second group. */
    suspend fun createGroup(draft: GroupDraft, idempotencyKey: String): AppResult<GroupCreated>

    suspend fun rename(chatId: String, name: String): AppResult<Chat>
    suspend fun setMembersCanAdd(chatId: String, allowed: Boolean): AppResult<Chat>
    suspend fun addMembers(chatId: String, userIds: List<String>): AppResult<GroupCreated>
    suspend fun removeMember(chatId: String, userId: String): AppResult<Unit>
    suspend fun leave(chatId: String): AppResult<Unit>
    suspend fun setRole(chatId: String, userId: String, role: MemberRole): AppResult<Chat>

    /** The safety number between me and [userId], once we have each other's identity keys. */
    suspend fun securityCode(userId: String): SecurityCode?
}

/**
 * Messages. Sending a text is the one **queueable** write in messaging: it is saved as Waiting and goes out in order
 * when the connection allows (design G3). Reading and typing are light signals, sent only while connected.
 */
interface MessagesRepository {
    fun thread(chatId: String): Flow<List<Message>>

    /** The newest [limit] messages, oldest first (the thread's window; it grows as the person scrolls up). */
    fun thread(chatId: String, limit: Int): Flow<List<Message>>

    /** Saves the message on this phone and queues it. */
    suspend fun send(chatId: String, text: String, replyTo: String? = null)

    /** Queueable like text: the card for a payment already made (or signed) from this chat. */
    suspend fun sendPayment(chatId: String, card: PaymentCard)

    /** Writes a call into the chat's history once it's over; one entry per [callId], later calls to it are ignored. */
    suspend fun logCall(chatId: String, callId: String, outgoing: Boolean, video: Boolean, outcome: CallOutcome, durationSeconds: Long, callerId: String, at: Long)

    /** A failed message, sent again (a new client id when the first attempt is older than the server remembers). */
    suspend fun retry(localId: Long)
    suspend fun discard(localId: Long)

    /** Deletes one of my messages for everyone (M14): sender only, within 48 hours. Online-only. */
    suspend fun deleteForEveryone(localId: Long): AppResult<Unit>

    /** I have the chat open and have seen everything in it: moves the read marker and tells the sender. */
    suspend fun markRead(chatId: String)

    suspend fun setTyping(chatId: String, typing: Boolean)

    /** Who is typing in a chat right now (cleared after a few seconds without a new signal). */
    fun typing(chatId: String): Flow<List<String>>

    /** Pulls what is waiting on the server (M13). Called when the socket connects, on foreground and on a push. */
    suspend fun syncInbox(): AppResult<Unit>

    suspend fun settings(): AppResult<MessagingSettings>
    suspend fun updateSettings(readReceipts: Boolean?, typing: Boolean?): AppResult<MessagingSettings>
}

data class MessagingSettings(val readReceipts: Boolean, val typing: Boolean)
