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
    /**
     * M8: the group's name and description in one change (2026-11-14); both are sealed on the phone, so the server
     * reads neither. [description] blank removes it. Online-only, admins only.
     */
    suspend fun editGroup(chatId: String, name: String, description: String): AppResult<Chat> = rename(chatId, name)
    /**
     * M8: a new group photo (encrypted, uploaded, referenced in the sealed metadata), or null to remove it. [name] is
     * the group's name as the admin typed it, needed only when this phone can't read the current one (its key is lost:
     * the code `messaging.group_name_needed` asks for it).
     */
    suspend fun setGroupPhoto(chatId: String, image: com.circaso.core.platform.GroupPhotoImage?, name: String? = null): AppResult<Chat> =
        AppResult.Failure(com.circaso.core.common.AppError.Unexpected(null))
    suspend fun setMembersCanAdd(chatId: String, allowed: Boolean): AppResult<Chat>
    suspend fun addMembers(chatId: String, userIds: List<String>): AppResult<GroupCreated>
    suspend fun removeMember(chatId: String, userId: String): AppResult<Unit>
    suspend fun leave(chatId: String): AppResult<Unit>
    suspend fun setRole(chatId: String, userId: String, role: MemberRole): AppResult<Chat>

    /** The safety number between me and [userId], once we have each other's identity keys. */
    suspend fun securityCode(userId: String): SecurityCode?

    /**
     * Asks the members of each group this phone can't name (no metadata key: a new phone) for its key, once this
     * phone's own keys are on the server; at most once every 10 minutes a group. Best effort, from Room only.
     */
    suspend fun askForMissingGroupKeys() {}
}

/**
 * Group invites (2026-11-09): adding someone to a group invites them, and they join when they accept. My invites are
 * kept on this phone so they show offline; answering them, and a group's Invited list, need the server.
 */
interface GroupInvitesRepository {
    /** My open invites, newest first. */
    val invites: Flow<List<GroupInvite>>

    /** A group's Invited list changed on the server (`invites.changed`): group info reloads it if open. */
    val changed: Flow<String>

    suspend fun refresh(): AppResult<Unit>

    /** Joins the group (M24); its chat is stored and returned, the invite leaves the list. */
    suspend fun accept(inviteId: String): AppResult<Chat>

    /** Declines (M25): the invite leaves the list and nobody is told. */
    suspend fun decline(inviteId: String): AppResult<Unit>

    /** The group's open invites (M26), for group info. */
    suspend fun pending(chatId: String): AppResult<List<PendingInvite>>

    /** Takes back an open invite (M27): an admin, or whoever sent it. */
    suspend fun cancel(chatId: String, userId: String): AppResult<Unit>
}

/**
 * Error codes made on the phone (not the server's) for a group whose metadata key this phone doesn't have; worded in
 * [com.circaso.core.common.ErrorMessages] like any other code.
 */
object GroupKeyErrors {
    /** The members are being asked for the key. */
    const val Missing = "messaging.meta_key_missing"
    /** The same, said to an admin, who may give the group a new key a minute after asking. */
    const val Asked = "messaging.meta_key_asked"
    /** A new key seals the name again and this phone can't read the current one: the admin types it. */
    const val NameNeeded = "messaging.group_name_needed"
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

    /** A message a reply quotes, from this phone's history by its server id; null when the phone doesn't have it. */
    suspend fun quoted(chatId: String, messageId: String): Message? = null
    /** A reply to someone's moment (V8): an ordinary text that quotes it; it waits offline like any message. */
    suspend fun sendMomentReply(chatId: String, text: String, quote: com.circaso.core.crypto.MomentQuote)
    /** Reactions by message id: each person's (userId, emoji). */
    fun reactions(chatId: String): kotlinx.coroutines.flow.Flow<Map<String, List<Pair<String, String>>>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    /** React to a message; null takes my reaction back. */
    suspend fun react(chatId: String, messageId: String, emoji: String?) {}
    /** Who I am, to tell my reactions from others'. */
    suspend fun myId(): String? = null

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

    /** Sends the read receipts that waited for the socket ([markRead] with no connection). Called when it connects. */
    suspend fun flushReads() {}

    suspend fun setTyping(chatId: String, typing: Boolean)

    /** Who is typing in a chat right now (cleared after a few seconds without a new signal). */
    fun typing(chatId: String): Flow<List<String>>

    /** The other person's presence in a direct chat, as the server last told us (null until it does). */
    fun presence(chatId: String): Flow<Presence?> = kotlinx.coroutines.flow.flowOf(null)

    /** Asks the server for the other members' presence while the chat is open (repeat every [PresenceWatchEveryMillis]). */
    suspend fun watchPresence(chatId: String) {}

    /** Pulls what is waiting on the server (M13). Called when the socket connects, on foreground and on a push. */
    suspend fun syncInbox(): AppResult<Unit>

    suspend fun settings(): AppResult<MessagingSettings>
    suspend fun updateSettings(readReceipts: Boolean?, typing: Boolean?): AppResult<MessagingSettings>
}

data class MessagingSettings(val readReceipts: Boolean, val typing: Boolean)

/** Someone's presence: online now, or when they were last in the app. */
data class Presence(val online: Boolean, val lastSeenAt: Long?)

/** The server keeps a watch for 60 s; the open chat renews it before then. */
const val PresenceWatchEveryMillis = 45_000L
