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

    @Query("DELETE FROM chat") suspend fun clearChats()
    @Query("DELETE FROM chat_member") suspend fun clearMembers()
    @Query("DELETE FROM group_meta_key") suspend fun clearMetaKeys()
    @Query("DELETE FROM sender_key_delivery") suspend fun clearDeliveries()
}

@Dao
interface MessagesDao {
    @Query("SELECT * FROM message WHERE chatId = :chatId ORDER BY orderAt ASC, localId ASC")
    fun observeThread(chatId: String): Flow<List<MessageEntity>>

    /** The newest [limit] messages of a chat, newest first: a thread opens on these and loads older ones as it scrolls up. */
    @Query("SELECT * FROM message WHERE chatId = :chatId ORDER BY orderAt DESC, localId DESC LIMIT :limit")
    fun observeRecent(chatId: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT m.* FROM message m WHERE m.localId = (SELECT localId FROM message WHERE chatId = m.chatId ORDER BY orderAt DESC, localId DESC LIMIT 1)")
    fun observeLatestPerChat(): Flow<List<MessageEntity>>

    @Query("SELECT c.chatId AS chatId, COUNT(m.localId) AS count FROM chat c JOIN message m ON m.chatId = c.chatId WHERE m.outgoing = 0 AND m.kind != 'system' AND m.orderAt > c.readUpToOrderAt GROUP BY c.chatId")
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

suspend fun ChatsDao.clearAll() { clearChats(); clearMembers(); clearMetaKeys(); clearDeliveries() }
suspend fun MessagesDao.clearAll() { clearMessages(); clearReceipts(); clearPending() }
