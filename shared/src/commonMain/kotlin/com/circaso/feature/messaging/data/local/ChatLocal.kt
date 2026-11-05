package com.circaso.feature.messaging.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A chat as the server describes it (M6/M7), plus the opened group name. [left] marks a group this phone was removed from. */
@Entity(tableName = "chat")
data class ChatEntity(
    @PrimaryKey val chatId: String,
    val kind: String,
    val name: String?,
    val sealedMeta: String?,
    val metaVersion: Int,
    val version: Int,
    val membersCanAdd: Boolean,
    val lastMessageId: String?,
    val left: Boolean,
    /** Incoming messages with [MessageEntity.orderAt] above this are unread. */
    val readUpToOrderAt: Long,
    val updatedAt: Long,
    /** The private chat with a space's owner (SH2): which space it came through. Null for every other chat. */
    @androidx.room.ColumnInfo(defaultValue = "NULL") val viaSpaceId: String? = null,
    /** The group photo the server keeps ([com.circaso.feature.messaging.data.remote.ChatDto.avatarMediaId]); null for none or unknown. */
    @androidx.room.ColumnInfo(defaultValue = "NULL") val avatarMediaId: String? = null,
)

@Entity(tableName = "chat_member", primaryKeys = ["chatId", "userId"])
data class ChatMemberEntity(
    val chatId: String,
    val userId: String,
    val role: String,
    val displayName: String,
    val handle: String?,
    val avatarUrl: String?,
    val joinedAt: String,
)

/**
 * One message in a thread, mine or theirs. Outgoing: [status] follows the send (Waiting → Sending → Sent → Delivered →
 * Read, or Failed), [clientMessageId] is what the server dedupes on, [cachedRequest] is the encrypted request so a
 * retry sends identical bytes. Incoming: [messageId] and [sentAt] come from the server. [orderAt] orders the thread:
 * the server's time when known, the phone's when not.
 */
@Entity(
    tableName = "message",
    indices = [Index("chatId", "orderAt"), Index(value = ["clientMessageId"], unique = true), Index("messageId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val clientMessageId: String,
    val messageId: String?,
    val chatId: String,
    val senderId: String,
    val outgoing: Boolean,
    val kind: String,
    val text: String?,
    val replyTo: String?,
    val status: String,
    val errorCode: String?,
    val cachedRequest: String?,
    val sentAt: String?,
    val orderAt: Long,
    val createdAt: Long,
    /** Payment cards only (kind [MessageKind.Payment]); the note is in [text]. */
    val transferId: String? = null,
    val amountMinor: String? = null,
    val asset: String? = null,
    val blockchain: String? = null,
    /** A reply to a moment (V8): the quote as JSON ([com.circaso.core.crypto.MomentQuote]). */
    @androidx.room.ColumnInfo(defaultValue = "NULL") val momentQuote: String? = null,
)

object MessageStatus {
    const val Waiting = "waiting"
    const val Sending = "sending"
    const val Sent = "sent"
    const val Delivered = "delivered"
    const val Read = "read"
    const val Failed = "failed"
    const val Deleted = "deleted"
    const val Undecryptable = "undecryptable"
    /** A received message that is not mine to mark: incoming rows carry this. */
    const val Received = "received"
}

object MessageKind {
    const val Text = "text"
    /** My reaction on its way out: hidden from the thread and lists; [MessageEntity.replyTo] is the target, text the emoji. */
    const val Reaction = "reaction"
    /** A payment made from the chat: the card shows the transfer, the wallet keeps its state. */
    const val Payment = "payment"
    /** "‹Name›'s security code changed" and other notices the phone writes itself. */
    const val System = "system"
    /** A call in this chat, written by the phone when it ends (WhatsApp-style call history); `text` holds its details. */
    const val Call = "call"
    /** Media (photo, voice note, shared location): `text` holds MediaDetails JSON. */
    const val Image = "image"
    const val Voice = "voice"
    const val Video = "video"
    const val Location = "location"
}

/** The furthest receipt per person for one of my messages (group receipts arrive batched). */
@Entity(tableName = "message_receipt", primaryKeys = ["messageId", "userId"])
data class MessageReceiptEntity(val messageId: String, val userId: String, val status: String, val at: String)

/** A group message that arrived before its sender key; retried when the key comes, dropped after a day. */
@Entity(tableName = "pending_inbox")
data class PendingInboxEntity(
    @PrimaryKey val messageId: String,
    val chatId: String,
    val senderId: String,
    val kind: String,
    val ciphertext: String,
    val sentAt: String,
    val receivedAt: Long,
)

/** The key a group's sealed name is under, per `metaVersion`; sealed like every other private record. */
@Entity(tableName = "group_meta_key", primaryKeys = ["chatId", "metaVersion"])
data class GroupMetaKeyEntity(val chatId: String, val metaVersion: Int, val key: ByteArray, val createdAt: Long)

/** Which members already hold which of my sender keys, so a distribution is sent once per member per key. */
@Entity(tableName = "sender_key_delivery", primaryKeys = ["chatId", "userId", "distributionId"])
data class SenderKeyDeliveryEntity(val chatId: String, val userId: String, val distributionId: String, val at: Long)

/** The newest message per chat and the unread count, for the chat list. */
data class ChatUnread(val chatId: String, val count: Int)

@Dao
interface ChatsDao {
    @Query("SELECT * FROM chat WHERE left = 0 ORDER BY updatedAt DESC")
    fun observeChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chat WHERE chatId = :chatId")
    fun observeChat(chatId: String): Flow<ChatEntity?>

    @Query("SELECT * FROM chat WHERE chatId = :chatId")
    suspend fun chat(chatId: String): ChatEntity?

    @Query("SELECT * FROM chat")
    suspend fun chats(): List<ChatEntity>

    @Upsert
    suspend fun upsertChat(chat: ChatEntity)

    @Query("UPDATE chat SET left = 1 WHERE chatId = :chatId")
    suspend fun markLeft(chatId: String)

    @Query("UPDATE chat SET readUpToOrderAt = :orderAt WHERE chatId = :chatId AND readUpToOrderAt < :orderAt")
    suspend fun markReadUpTo(chatId: String, orderAt: Long)

    @Query("UPDATE chat SET updatedAt = :at WHERE chatId = :chatId AND updatedAt < :at")
    suspend fun touch(chatId: String, at: Long)

    @Query("DELETE FROM chat WHERE chatId = :chatId")
    suspend fun deleteChat(chatId: String)

    @Query("SELECT * FROM chat_member WHERE chatId = :chatId ORDER BY joinedAt")
    fun observeMembers(chatId: String): Flow<List<ChatMemberEntity>>

    @Query("SELECT * FROM chat_member WHERE chatId = :chatId")
    suspend fun members(chatId: String): List<ChatMemberEntity>

    @Query("SELECT * FROM chat_member")
    fun observeAllMembers(): Flow<List<ChatMemberEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<ChatMemberEntity>)

    @Query("DELETE FROM chat_member WHERE chatId = :chatId")
    suspend fun deleteMembers(chatId: String)

    @Query("DELETE FROM chat_member WHERE chatId = :chatId AND userId = :userId")
    suspend fun deleteMember(chatId: String, userId: String)

    @Query("SELECT * FROM group_meta_key WHERE chatId = :chatId AND metaVersion = :metaVersion")
    suspend fun metaKey(chatId: String, metaVersion: Int): GroupMetaKeyEntity?

    @Query("SELECT * FROM group_meta_key WHERE chatId = :chatId ORDER BY metaVersion DESC LIMIT 1")
    suspend fun latestMetaKey(chatId: String): GroupMetaKeyEntity?

    @Upsert
    suspend fun upsertMetaKey(key: GroupMetaKeyEntity)

    @Query("SELECT userId FROM sender_key_delivery WHERE chatId = :chatId AND distributionId = :distributionId")
    suspend fun deliveredTo(chatId: String, distributionId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeliveries(rows: List<SenderKeyDeliveryEntity>)

    @Query("DELETE FROM sender_key_delivery WHERE chatId = :chatId")
    suspend fun deleteDeliveries(chatId: String)

    /** [userId] no longer holds any of my sender keys under [chatId] (a new phone, a new identity). */
    @Query("DELETE FROM sender_key_delivery WHERE chatId = :chatId AND userId = :userId")
    suspend fun deleteDelivery(chatId: String, userId: String)

    /**
     * [userId] has a new identity (a new phone): none of my sender keys, in any group, reached it. Deliveries were
     * kept per person, not per identity, so without this their new phone could never open my group messages.
     */
    @Query("DELETE FROM sender_key_delivery WHERE userId = :userId")
    suspend fun deleteDeliveriesTo(userId: String)

    @Query("DELETE FROM chat") suspend fun clearChats()
    @Query("DELETE FROM chat_member") suspend fun clearMembers()
    @Query("DELETE FROM group_meta_key") suspend fun clearMetaKeys()
    @Query("DELETE FROM sender_key_delivery") suspend fun clearDeliveries()
}

/** One person's reaction to one message (WhatsApp-style); each person has at most one per message. */
@androidx.room.Entity(tableName = "message_reaction", primaryKeys = ["messageId", "userId"], indices = [androidx.room.Index("chatId")])
data class MessageReactionEntity(val chatId: String, val messageId: String, val userId: String, val emoji: String, val at: Long)

@Dao
interface ReactionsDao {
    @Query("SELECT * FROM message_reaction WHERE chatId = :chatId ORDER BY at")
    fun observe(chatId: String): Flow<List<MessageReactionEntity>>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MessageReactionEntity)

    @Query("DELETE FROM message_reaction WHERE messageId = :messageId AND userId = :userId")
    suspend fun remove(messageId: String, userId: String)

    @Query("DELETE FROM message_reaction")
    suspend fun clear()
}

@Dao
interface MessagesDao {
    @Query("SELECT * FROM message WHERE chatId = :chatId AND kind != 'reaction' ORDER BY orderAt ASC, localId ASC")
    fun observeThread(chatId: String): Flow<List<MessageEntity>>

    /** The newest [limit] messages of a chat, newest first: a thread opens on these and loads older ones as it scrolls up. */
    @Query("SELECT * FROM message WHERE chatId = :chatId AND kind != 'reaction' ORDER BY orderAt DESC, localId DESC LIMIT :limit")
    fun observeRecent(chatId: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT m.* FROM message m WHERE m.localId = (SELECT localId FROM message WHERE chatId = m.chatId AND kind != 'reaction' ORDER BY orderAt DESC, localId DESC LIMIT 1)")
    fun observeLatestPerChat(): Flow<List<MessageEntity>>

    @Query("SELECT c.chatId AS chatId, COUNT(m.localId) AS count FROM chat c JOIN message m ON m.chatId = c.chatId WHERE m.outgoing = 0 AND m.kind != 'system' AND m.kind != 'reaction' AND m.orderAt > c.readUpToOrderAt GROUP BY c.chatId")
    fun observeUnread(): Flow<List<ChatUnread>>

    @Query("SELECT * FROM message WHERE localId = :localId")
    suspend fun byLocalId(localId: Long): MessageEntity?

    @Query("SELECT * FROM message WHERE messageId = :messageId")
    suspend fun byMessageId(messageId: String): MessageEntity?

    @Query("SELECT * FROM message WHERE clientMessageId = :clientMessageId")
    suspend fun byClientMessageId(clientMessageId: String): MessageEntity?

    @Query("SELECT * FROM message WHERE chatId = :chatId AND outgoing = 0 AND kind != 'system' AND messageId IS NOT NULL ORDER BY orderAt DESC, localId DESC LIMIT 1")
    suspend fun newestIncoming(chatId: String): MessageEntity?

    /** The newest incoming row of any kind that counts as unread, including calls (which have no server id). */
    @Query("SELECT MAX(orderAt) FROM message WHERE chatId = :chatId AND outgoing = 0 AND kind != 'system'")
    suspend fun newestIncomingOrderAt(chatId: String): Long?

    @Query("SELECT * FROM message WHERE chatId = :chatId AND outgoing = 1 AND status IN ('sent', 'delivered') AND messageId IS NOT NULL")
    suspend fun outgoingAwaitingReceipt(chatId: String): List<MessageEntity>

    /** The message a live location share started as (its updates move this row's pin). */
    @Query("SELECT * FROM message WHERE chatId = :chatId AND senderId = :senderId AND kind = 'location' AND text LIKE '%\"liveId\":\"' || :liveId || '\"%' LIMIT 1")
    suspend fun liveShare(chatId: String, senderId: String, liveId: String): MessageEntity?

    /** This phone's own live shares, ended or not; callers check `liveNow`. */
    @Query("SELECT * FROM message WHERE outgoing = 1 AND kind = 'location' AND text LIKE '%\"liveId\"%'")
    suspend fun ownLiveShares(): List<MessageEntity>

    /** Every live share in a chat, for its map. */
    @Query("SELECT * FROM message WHERE chatId = :chatId AND kind = 'location' AND text LIKE '%\"liveId\"%' ORDER BY orderAt ASC")
    fun observeLiveShares(chatId: String): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query("UPDATE message SET status = :status, errorCode = :errorCode WHERE localId = :localId")
    suspend fun setStatus(localId: Long, status: String, errorCode: String?)

    @Query("UPDATE message SET text = :text, cachedRequest = NULL WHERE localId = :localId")
    suspend fun setText(localId: Long, text: String)

    @Query("UPDATE message SET status = :status WHERE messageId = :messageId AND outgoing = 1 AND status IN (:from)")
    suspend fun advanceStatus(messageId: String, status: String, from: List<String>)

    @Query("UPDATE message SET cachedRequest = :request WHERE localId = :localId")
    suspend fun setCachedRequest(localId: Long, request: String?)

    @Query("UPDATE message SET messageId = :messageId, sentAt = :sentAt, orderAt = :orderAt, status = :status, errorCode = NULL, cachedRequest = NULL WHERE localId = :localId")
    suspend fun markSent(localId: Long, messageId: String, sentAt: String, orderAt: Long, status: String)

    @Query("UPDATE message SET clientMessageId = :clientMessageId WHERE localId = :localId")
    suspend fun setClientMessageId(localId: Long, clientMessageId: String)

    @Query("UPDATE message SET status = 'deleted', text = NULL, cachedRequest = NULL WHERE messageId = :messageId")
    suspend fun markDeleted(messageId: String)

    @Query("DELETE FROM message WHERE localId = :localId")
    suspend fun delete(localId: Long)

    @Query("DELETE FROM message WHERE chatId = :chatId")
    suspend fun deleteThread(chatId: String)

    @Upsert
    suspend fun upsertReceipts(receipts: List<MessageReceiptEntity>)

    @Query("SELECT * FROM message_receipt WHERE messageId = :messageId")
    suspend fun receipts(messageId: String): List<MessageReceiptEntity>

    @Query("SELECT * FROM message_receipt WHERE messageId IN (:messageIds)")
    fun observeReceipts(messageIds: List<String>): Flow<List<MessageReceiptEntity>>

    @Upsert
    suspend fun upsertPending(pending: PendingInboxEntity)

    @Query("SELECT * FROM pending_inbox WHERE chatId = :chatId AND senderId = :senderId ORDER BY sentAt")
    suspend fun pendingFrom(chatId: String, senderId: String): List<PendingInboxEntity>

    @Query("SELECT * FROM pending_inbox WHERE receivedAt < :before")
    suspend fun pendingOlderThan(before: Long): List<PendingInboxEntity>

    @Query("DELETE FROM pending_inbox WHERE messageId = :messageId")
    suspend fun deletePending(messageId: String)

    @Query("DELETE FROM message") suspend fun clearMessages()
    @Query("DELETE FROM message_receipt") suspend fun clearReceipts()
    @Query("DELETE FROM pending_inbox") suspend fun clearPending()
}

/**
 * Retry receipts (DB v19): which of their messages I asked a sender to send again ([role] [RetryRole.Asked], [peerId]
 * the sender) and which of mine I sent again for someone ([RetryRole.Answered], [peerId] who asked). Each happens
 * once per message and person.
 */
@Entity(tableName = "message_retry", primaryKeys = ["messageId", "peerId", "role"])
data class MessageRetryEntity(val messageId: String, val peerId: String, val role: String, val chatId: String, val at: Long)

object RetryRole {
    const val Asked = "asked"
    const val Answered = "answered"
}

/** What recovery needs: unreadable messages not yet asked about, retry bookkeeping, and a chat's payment card by transfer. */
@Dao
interface RecoveryDao {
    /** Their messages this phone couldn't open, received since [since], that nobody has been asked to send again; newest first. */
    @Query(
        "SELECT * FROM message m WHERE m.status = 'undecryptable' AND m.outgoing = 0 AND m.messageId IS NOT NULL AND m.createdAt >= :since " +
            "AND NOT EXISTS (SELECT 1 FROM message_retry r WHERE r.messageId = m.messageId AND r.role = 'asked') ORDER BY m.orderAt DESC LIMIT :limit",
    )
    suspend fun unreadableToAsk(since: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM message_retry WHERE messageId = :messageId AND peerId = :peerId AND role = :role")
    suspend fun retry(messageId: String, peerId: String, role: String): MessageRetryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRetry(row: MessageRetryEntity): Long

    /** The payment card for [transferId] in [chatId], mine or theirs, whether it came end to end or from the wallet. */
    @Query("SELECT * FROM message WHERE chatId = :chatId AND kind = 'payment' AND transferId = :transferId ORDER BY localId LIMIT 1")
    suspend fun paymentCard(chatId: String, transferId: String): MessageEntity?

    @Query("DELETE FROM message_retry") suspend fun clear()
}

suspend fun ChatsDao.clearAll() { clearChats(); clearMembers(); clearMetaKeys(); clearDeliveries() }
suspend fun MessagesDao.clearAll() { clearMessages(); clearReceipts(); clearPending() }
