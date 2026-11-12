package com.circaso.feature.messaging.data.repository

import com.circaso.feature.messaging.domain.CallOutcome

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.Diagnostics
import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.CiphertextKind
import com.circaso.core.crypto.WireFormat
import com.circaso.core.realtime.RealtimeClient
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxEntity
import com.circaso.core.sync.OutboxHandler
import com.circaso.core.sync.OutboxProcessor
import com.circaso.core.sync.OutboxType
import com.circaso.core.sync.nowMillis
import com.circaso.feature.messaging.data.local.ChatEntity
import com.circaso.feature.messaging.data.local.ChatMemberEntity
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.local.MessageEntity
import com.circaso.feature.messaging.data.local.MessageKind
import com.circaso.feature.messaging.data.local.MessageReceiptEntity
import com.circaso.feature.messaging.data.local.MessageStatus
import com.circaso.feature.messaging.data.local.MessagesDao
import com.circaso.feature.messaging.data.local.PendingInboxEntity
import com.circaso.feature.messaging.data.remote.AckRequest
import com.circaso.feature.messaging.data.remote.InboxMessageDto
import com.circaso.feature.messaging.data.remote.MessagesRemote
import com.circaso.feature.messaging.data.remote.MessagingSettingsRequest
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.remote.RecipientDto
import com.circaso.feature.messaging.data.remote.SendChatMessageRequest
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.messaging.domain.MessagesRepository
import com.circaso.feature.messaging.domain.PaymentCard
import com.circaso.feature.messaging.domain.MessagingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** What a queued send points at. The message row holds everything else, including the encrypted request once built. */
@Serializable
data class ChatMessageJob(val localId: Long)

@OptIn(ExperimentalUuidApi::class)
internal fun newClientMessageId() = Uuid.random().toString()

internal fun isoToMillis(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

/**
 * Messages from Room; a sent text goes through the outbox (design G3). Receipts, typing, deletions and key changes
 * arrive as realtime frames and land here through [MessagingFrameHandler].
 */
class MessagesRepositoryImpl(
    private val dao: MessagesDao,
    private val chats: ChatsDao,
    private val remote: MessagesRemote,
    private val inbox: InboxProcessor,
    private val outbox: Outbox,
    private val kickOutbox: () -> Unit,
    private val realtime: RealtimeClient,
    private val json: Json,
    private val ownUserId: suspend () -> String?,
    private val now: () -> Long = ::nowMillis,
    private val reactionsDao: com.circaso.feature.messaging.data.local.ReactionsDao? = null,
    private val paymentCards: ChatPaymentCards? = null,
    /** Names I saved for people: senders, typing and notices about someone use them. */
    private val names: com.circaso.feature.names.domain.PeopleNames = com.circaso.feature.names.domain.PeopleNames.None,
) : MessagesRepository {
    /** messageId -> each person's reaction (userId to emoji), oldest first. */
    override fun reactions(chatId: String): kotlinx.coroutines.flow.Flow<Map<String, List<Pair<String, String>>>> =
        reactionsDao?.observe(chatId)?.let { f -> kotlinx.coroutines.flow.flow { f.collect { rows -> emit(rows.groupBy({ it.messageId }, { it.userId to it.emoji })) } } }
            ?: kotlinx.coroutines.flow.flowOf(emptyMap())

    /**
     * My reaction to [messageId] ([emoji] null takes it back), WhatsApp-style: shown at once, sent as an encrypted
     * `reaction` message through the outbox (so it waits offline like any message).
     */
    override suspend fun myId(): String? = ownUserId()

    override suspend fun react(chatId: String, messageId: String, emoji: String?) {
        val me = ownUserId() ?: return
        val at = now()
        if (emoji == null) reactionsDao?.remove(messageId, me) else reactionsDao?.upsert(com.circaso.feature.messaging.data.local.MessageReactionEntity(chatId, messageId, me, emoji, at))
        val localId = dao.insert(
            MessageEntity(
                clientMessageId = newClientMessageId(), messageId = null, chatId = chatId, senderId = me, outgoing = true,
                kind = MessageKind.Reaction, text = emoji.orEmpty(), replyTo = messageId, status = MessageStatus.Waiting, errorCode = null,
                cachedRequest = null, sentAt = null, orderAt = at, createdAt = at,
            ),
        )
        outbox.enqueue(OutboxType.ChatMessage, json.encodeToString(ChatMessageJob.serializer(), ChatMessageJob(localId)))
        kickOutbox()
    }

    /** chatId -> (userId -> when their typing signal expires). */
    private val typingState = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())

    /** userId -> presence, from `presence.changed` frames for the chats being watched. */
    private val presenceState = MutableStateFlow<Map<String, com.circaso.feature.messaging.domain.Presence>>(emptyMap())

    override fun thread(chatId: String, limit: Int): Flow<List<Message>> =
        combine(dao.observeRecent(chatId, limit), chats.observeMembers(chatId), flow { emit(ownUserId()) }, names.saved) { rows, members, me, saved ->
            rows.asReversed().map { it.toDomain(members, me, saved) }
        }

    override fun thread(chatId: String): Flow<List<Message>> =
        combine(dao.observeThread(chatId), chats.observeMembers(chatId), flow { emit(ownUserId()) }, names.saved) { rows, members, me, saved ->
            rows.map { it.toDomain(members, me, saved) }
        }

    override suspend fun send(chatId: String, text: String, replyTo: String?) = send(chatId, text, replyTo, null)

    override suspend fun quoted(chatId: String, messageId: String): Message? {
        val row = dao.byMessageId(messageId)?.takeIf { it.chatId == chatId } ?: return null
        return row.toDomain(chats.observeMembers(chatId).first(), ownUserId())
    }

    override suspend fun sendMomentReply(chatId: String, text: String, quote: com.circaso.core.crypto.MomentQuote) = send(chatId, text, null, quote)

    private suspend fun send(chatId: String, text: String, replyTo: String?, quote: com.circaso.core.crypto.MomentQuote?) {
        val me = ownUserId() ?: return
        val at = now()
        val localId = dao.insert(
            MessageEntity(
                clientMessageId = newClientMessageId(), messageId = null, chatId = chatId, senderId = me, outgoing = true,
                kind = MessageKind.Text, text = text, replyTo = replyTo, status = MessageStatus.Waiting, errorCode = null,
                cachedRequest = null, sentAt = null, orderAt = at, createdAt = at,
                momentQuote = quote?.let { json.encodeToString(com.circaso.core.crypto.MomentQuote.serializer(), it) },
            ),
        )
        chats.touch(chatId, at)
        outbox.enqueue(OutboxType.ChatMessage, json.encodeToString(ChatMessageJob.serializer(), ChatMessageJob(localId)))
        kickOutbox()
    }

    override suspend fun sendPayment(chatId: String, card: PaymentCard) {
        val me = ownUserId() ?: return
        val at = now()
        val write = suspend {
            // The wallet may already have put this payment's card in the chat (it confirmed before this ran): that
            // card is sent instead of a second one.
            val walletCard = paymentCards?.walletCardFor(chatId, card.transferId)
            if (walletCard != null) {
                dao.upsert(walletCard.copy(clientMessageId = newClientMessageId(), text = card.note, status = MessageStatus.Waiting, createdAt = at))
                walletCard.localId
            } else {
                dao.insert(
                    MessageEntity(
                        clientMessageId = newClientMessageId(), messageId = null, chatId = chatId, senderId = me, outgoing = true,
                        kind = MessageKind.Payment, text = card.note, replyTo = null, status = MessageStatus.Waiting, errorCode = null,
                        cachedRequest = null, sentAt = null, orderAt = at, createdAt = at,
                        transferId = card.transferId, amountMinor = card.amountMinor, asset = card.asset, blockchain = card.blockchain,
                    ),
                )
            }
        }
        val localId = paymentCards?.exclusive(write) ?: write()
        chats.touch(chatId, at)
        outbox.enqueue(OutboxType.ChatMessage, json.encodeToString(ChatMessageJob.serializer(), ChatMessageJob(localId)))
        kickOutbox()
    }

    override suspend fun logCall(chatId: String, callId: String, outgoing: Boolean, video: Boolean, outcome: CallOutcome, durationSeconds: Long, callerId: String, at: Long) {
        val key = "call-$callId"
        if (dao.byClientMessageId(key) != null) return
        val details = buildJsonObject { put("video", video); put("outcome", outcome.name); put("seconds", durationSeconds) }
        // A call's end can be heard twice at once (the hang-up's answer, call.ended, the 5 s re-read): the second write
        // is dropped by the unique key instead of failing (it closed the app at every hang-up, Redmi 2026-11-09).
        val inserted = dao.insertIfAbsent(
            MessageEntity(
                clientMessageId = key, messageId = null, chatId = chatId, senderId = callerId, outgoing = outgoing,
                kind = MessageKind.Call, text = details.toString(), replyTo = null, status = MessageStatus.Received, errorCode = null,
                cachedRequest = null, sentAt = null, orderAt = at, createdAt = at,
            ),
        )
        if (inserted != -1L) chats.touch(chatId, at)
    }

    override suspend fun retry(localId: Long) {
        val message = dao.byLocalId(localId) ?: return
        if (message.status != MessageStatus.Failed) return
        // Past the server's idempotency window the old client id could be answered from memory with nothing sent: use a new one.
        if (now() - message.createdAt > OutboxProcessor.StaleAfterMillis) {
            dao.setClientMessageId(localId, newClientMessageId())
            dao.setCachedRequest(localId, null)
        }
        dao.setStatus(localId, MessageStatus.Waiting, null)
        outbox.enqueue(OutboxType.ChatMessage, json.encodeToString(ChatMessageJob.serializer(), ChatMessageJob(localId)))
        kickOutbox()
    }

    override suspend fun discard(localId: Long) {
        val message = dao.byLocalId(localId) ?: return
        if (message.status == MessageStatus.Failed || message.status == MessageStatus.Waiting) dao.delete(localId)
    }

    override suspend fun deleteForEveryone(localId: Long): AppResult<Unit> {
        val message = dao.byLocalId(localId) ?: return AppResult.Failure(AppError.Unexpected(null))
        val messageId = message.messageId ?: return AppResult.Failure(AppError.Unexpected(null))
        return when (val result = remote.delete(message.chatId, messageId)) {
            is AppResult.Failure -> result
            is AppResult.Success -> { dao.markDeleted(messageId); AppResult.Success(Unit) }
        }
    }

    override suspend fun markRead(chatId: String) {
        val chat = chats.chat(chatId) ?: return
        // Everything counted as unread is read now, calls included: they are local rows with no server id, so the
        // receipt below can't name them, but they must still leave the unread count (a missed group call stayed "2").
        val newestAt = dao.newestIncomingOrderAt(chatId) ?: return
        if (chat.readUpToOrderAt >= newestAt) return
        chats.markReadUpTo(chatId, newestAt)
        // The read receipt names the newest message the server knows.
        val newest = dao.newestIncoming(chatId) ?: return
        if (chat.readUpToOrderAt < newest.orderAt) {
            realtime.send("messaging", "read", buildJsonObject { put("chatId", chatId); put("upToMessageId", newest.messageId!!) })
        }
    }

    override suspend fun setTyping(chatId: String, typing: Boolean) {
        realtime.send("messaging", "typing", buildJsonObject { put("chatId", chatId); put("on", typing) })
    }

    override fun typing(chatId: String): Flow<List<String>> =
        combine(typingState, chats.observeMembers(chatId), names.saved) { state, members, saved ->
            val t = now()
            state[chatId].orEmpty().filterValues { it > t }.keys.mapNotNull { id -> saved[id] ?: members.firstOrNull { it.userId == id }?.displayName }
        }

    override suspend fun syncInbox(): AppResult<Unit> = inbox.sync()

    override suspend fun settings(): AppResult<MessagingSettings> =
        remote.settings(MessagingSettingsRequest()).let { r -> if (r is AppResult.Success) AppResult.Success(MessagingSettings(r.value.readReceipts, r.value.typing)) else r as AppResult.Failure }

    override suspend fun updateSettings(readReceipts: Boolean?, typing: Boolean?): AppResult<MessagingSettings> =
        remote.settings(MessagingSettingsRequest(readReceipts, typing)).let { r -> if (r is AppResult.Success) AppResult.Success(MessagingSettings(r.value.readReceipts, r.value.typing)) else r as AppResult.Failure }

    override fun presence(chatId: String): Flow<com.circaso.feature.messaging.domain.Presence?> =
        combine(presenceState, chats.observeMembers(chatId), flow { emit(ownUserId()) }) { state, members, me ->
            members.filter { it.userId != me }.singleOrNull()?.let { state[it.userId] }
        }

    override suspend fun watchPresence(chatId: String) {
        realtime.send("messaging", "presence.watch", buildJsonObject { put("chatId", chatId) })
    }

    // ---- frames

    fun onPresence(userId: String, online: Boolean, lastSeenAt: Long?) {
        presenceState.update { it + (userId to com.circaso.feature.messaging.domain.Presence(online, lastSeenAt)) }
    }

    fun onTyping(chatId: String, userId: String, on: Boolean) {
        typingState.update { state ->
            val inChat = state[chatId].orEmpty().toMutableMap()
            if (on) inChat[userId] = now() + TypingShowsForMillis else inChat.remove(userId)
            state + (chatId to inChat)
        }
    }

    /** Receipts move my message forward only, and only once every recipient has reached that step. */
    suspend fun onReceipts(chatId: String, receipts: List<MessageReceiptEntity>) {
        if (receipts.isEmpty()) return
        dao.upsertReceipts(receipts)
        val me = ownUserId()
        val recipients = chats.members(chatId).map { it.userId }.filter { it != me }.toSet()
        for (messageId in receipts.map { it.messageId }.toSet()) {
            val byUser = dao.receipts(messageId).associate { it.userId to it.status }
            val everyone = recipients.isNotEmpty() && recipients.all { it in byUser }
            when {
                everyone && recipients.all { byUser[it] == MessagingWire.Read } ->
                    dao.advanceStatus(messageId, MessageStatus.Read, listOf(MessageStatus.Sent, MessageStatus.Delivered))
                everyone -> dao.advanceStatus(messageId, MessageStatus.Delivered, listOf(MessageStatus.Sent))
            }
        }
    }

    suspend fun onDeleted(messageId: String) = dao.markDeleted(messageId)

    /** Their phone was replaced before my message reached it: say so, and let the person send it again. */
    suspend fun onUndeliverable(messageId: String) {
        val message = dao.byMessageId(messageId) ?: return
        if (message.outgoing) dao.setStatus(message.localId, MessageStatus.Failed, Undeliverable)
    }

    companion object {
        const val TypingShowsForMillis = 6_000L
        const val Undeliverable = "messaging.undeliverable"
    }
}

/** Sends one queued text (type `chat.message`). The message row is the source of truth; the outbox row only points at it. */
class ChatSendHandler(
    private val dao: MessagesDao,
    private val chats: ChatsDao,
    private val remote: MessagesRemote,
    private val sessions: SessionCipher,
    private val groupKeys: GroupKeys,
    private val json: Json,
    private val ownUserId: suspend () -> String?,
    private val refreshChat: suspend (String) -> AppResult<*>,
    private val now: () -> Long = ::nowMillis,
    /** Uploads a photo or voice note's encrypted file before its message goes (MediaMessages.uploadPending). */
    private val uploadMedia: (suspend (localId: Long) -> AppResult<Unit>)? = null,
    /** Keeps a photo, video or voice note going when the person leaves Circaso mid-upload. */
    private val uploads: com.circaso.core.platform.BackgroundUploads = com.circaso.core.platform.NoBackgroundUploads,
    /** Names I saved for people: "Sending a video to …" uses mine. */
    private val names: com.circaso.feature.names.domain.PeopleNames = com.circaso.feature.names.domain.PeopleNames.None,
) : OutboxHandler {
    override val type = OutboxType.ChatMessage
    private val sealer = ChatSealer(dao, chats, sessions, groupKeys, ownUserId, now)

    /**
     * A photo, video or voice note (upload and send) runs as background work, so it finishes when the person leaves
     * Circaso mid-upload; if it fails for good while they're away, they're told.
     */
    override suspend fun send(item: OutboxEntity): AppResult<Unit> {
        val job = runCatching { json.decodeFromString(ChatMessageJob.serializer(), item.payload) }.getOrNull() ?: return AppResult.Success(Unit)
        val first = dao.byLocalId(job.localId) ?: return AppResult.Success(Unit)
        val kind = if (isMediaKind(first.kind)) when (MediaDetails.read(first.text)?.kind) {
            MediaDetails.Image -> com.circaso.core.platform.UploadKind.Photo
            MediaDetails.Video -> com.circaso.core.platform.UploadKind.Video
            MediaDetails.Voice -> com.circaso.core.platform.UploadKind.VoiceNote
            MediaDetails.File -> com.circaso.core.platform.UploadKind.File
            else -> null
        } else null
        if (kind == null) return sendNow(item, job)
        return uploads.keep(kind, recipient(first.chatId)) { sendNow(item, job) }.also {
            dao.byLocalId(job.localId)?.takeIf { it.status == MessageStatus.Failed }?.let { uploads.failed(kind, it.chatId, it.localId) }
        }
    }

    /** Who it goes to, for "Sending a video to Amina…": the group's name, else the other person's. */
    private suspend fun recipient(chatId: String): String? {
        val chat = chats.chat(chatId) ?: return null
        if (chat.kind != MessagingWire.Direct) return chat.name?.takeIf { it.isNotBlank() }
        val me = ownUserId()
        return chats.members(chatId).firstOrNull { it.userId != me }?.let { m -> names.of(m.userId, m.displayName) }?.takeIf { it.isNotBlank() }
    }

    private suspend fun sendNow(item: OutboxEntity, job: ChatMessageJob): AppResult<Unit> {
        var message = dao.byLocalId(job.localId) ?: return AppResult.Success(Unit) // discarded meanwhile
        if (message.status == MessageStatus.Deleted || message.messageId != null) return AppResult.Success(Unit)
        dao.setStatus(message.localId, MessageStatus.Sending, null)
        val chat = chats.chat(message.chatId) ?: return failed(message, AppError.Api("messaging.chat_not_found", 404, null, null, emptyMap()))
        if (isMediaKind(message.kind) && uploadMedia != null) {
            (uploadMedia.invoke(message.localId) as? AppResult.Failure)?.let { return outcome(message, it.error) }
            message = dao.byLocalId(message.localId) ?: return AppResult.Success(Unit)
        }

        var request = message.cachedRequest?.let { runCatching { json.decodeFromString(SendChatMessageRequest.serializer(), it) }.getOrNull() }
        if (request == null) {
            request = when (val built = build(chat, message)) {
                is AppResult.Failure -> return outcome(message, built.error)
                is AppResult.Success -> built.value.also { dao.setCachedRequest(message.localId, json.encodeToString(SendChatMessageRequest.serializer(), it)) }
            }
        }
        var result = remote.send(chat.chatId, request, item.idempotencyKey)
        if (result is AppResult.Failure && (result.error as? AppError.Api)?.code == "messaging.recipients_mismatch") {
            // The members changed under us: reload them, encrypt again for the current set, send once more.
            refreshChat(chat.chatId)
            dao.setCachedRequest(message.localId, null)
            val fresh = chats.chat(chat.chatId) ?: return failed(message, result.error)
            request = when (val built = build(fresh, message)) {
                is AppResult.Failure -> return outcome(message, built.error)
                is AppResult.Success -> built.value.also { dao.setCachedRequest(message.localId, json.encodeToString(SendChatMessageRequest.serializer(), it)) }
            }
            result = remote.send(chat.chatId, request, item.idempotencyKey)
        }
        return when (result) {
            is AppResult.Success -> {
                val sentAt = result.value.sentAt
                dao.markSent(message.localId, result.value.messageId, sentAt, isoToMillis(sentAt) ?: message.orderAt, MessageStatus.Sent)
                chats.touch(chat.chatId, now())
                AppResult.Success(Unit)
            }
            is AppResult.Failure -> outcome(message, result.error)
        }
    }

    /** A retryable failure leaves the message Waiting (the queue pauses); a refusal marks it Failed (design G2). */
    private suspend fun outcome(message: MessageEntity, error: AppError): AppResult<Unit> {
        val retryable = error is AppError.Network || error is AppError.Unexpected || (error as AppError.Api).let { it.httpStatus >= 500 || it.httpStatus == 429 || it.code == "request.in_progress" }
        if (retryable) dao.setStatus(message.localId, MessageStatus.Waiting, null) else return failed(message, error)
        return AppResult.Failure(error)
    }

    private suspend fun failed(message: MessageEntity, error: AppError): AppResult<Unit> {
        dao.setStatus(message.localId, MessageStatus.Failed, (error as? AppError.Api)?.code ?: "unexpected")
        return AppResult.Failure(error)
    }

    private suspend fun build(chat: ChatEntity, message: MessageEntity): AppResult<SendChatMessageRequest> {
        val payload = message.toChatPayload(json) ?: return AppResult.Failure(AppError.Unexpected(null))
        return sealer.seal(chat, ChatPayload.encode(payload), message.clientMessageId, pushable = true)
    }
}

/**
 * What one of my messages says, end to end, rebuilt from its row: what the outbox sends, and what a retry receipt
 * sends again. Null for a row that can't be sent (a notice, a call, media whose details are gone).
 */
internal fun MessageEntity.toChatPayload(json: Json): ChatPayload? {
    val sentAt = Instant.fromEpochMilliseconds(createdAt).toString()
    return when {
        kind == MessageKind.System || kind == MessageKind.Call -> null
        kind == MessageKind.Payment ->
            ChatPayload(type = ChatPayload.Payment, text = text, clientSentAt = sentAt, transferId = transferId, amountMinor = amountMinor, asset = asset, blockchain = blockchain)
        isMediaKind(kind) -> MediaDetails.read(text)?.toPayload(sentAt)
        kind == MessageKind.Reaction -> ChatPayload(type = ChatPayload.Reaction, text = text, replyTo = replyTo, clientSentAt = sentAt)
        else -> ChatPayload(type = ChatPayload.Text, text = text, replyTo = replyTo, clientSentAt = sentAt,
            moment = momentQuote?.let { runCatching { json.decodeFromString(com.circaso.core.crypto.MomentQuote.serializer(), it) }.getOrNull() })
    }
}

/**
 * Encrypts one payload for everyone in a chat: a Signal session to the other person in a direct chat, the sender key
 * in a group (handed out first where it hasn't been). Used by the outbox for messages and by live location for the
 * updates that move a pin.
 */
class ChatSealer(
    private val dao: MessagesDao,
    private val chats: ChatsDao,
    private val sessions: SessionCipher,
    private val groupKeys: GroupKeys,
    private val ownUserId: suspend () -> String?,
    private val now: () -> Long = ::nowMillis,
) {
    suspend fun seal(chat: ChatEntity, plaintext: ByteArray, clientMessageId: String, pushable: Boolean): AppResult<SendChatMessageRequest> {
        val me = ownUserId() ?: return AppResult.Failure(AppError.Unexpected(null))
        return if (chat.kind == MessagingWire.Direct) {
            val peer = chats.members(chat.chatId).firstOrNull { it.userId != me } ?: return AppResult.Failure(AppError.Api("messaging.recipients_mismatch", 409, null, null, emptyMap()))
            when (val sealed = sessions.seal(peer.userId, plaintext)) {
                is AppResult.Failure -> sealed
                is AppResult.Success -> {
                    if (sealed.value.identityChanged) insertSystemRow(dao, chat.chatId, peer.userId, "${peer.displayName}'s security code changed", now())
                    AppResult.Success(SendChatMessageRequest(clientMessageId, MessagingWire.KindMessage, recipients = listOf(RecipientDto(peer.userId, Base64.encode(sealed.value.wire))), pushable = pushable))
                }
            }
        } else {
            val members = chats.members(chat.chatId).map { it.userId }
            when (val distributed = groupKeys.ensureDistributed(chat.chatId, members)) {
                is AppResult.Failure -> return distributed
                is AppResult.Success -> Unit
            }
            when (val sealed = groupKeys.encryptForGroup(chat.chatId, plaintext)) {
                is AppResult.Failure -> sealed
                is AppResult.Success -> AppResult.Success(SendChatMessageRequest(clientMessageId, MessagingWire.KindMessage, ciphertext = Base64.encode(sealed.value), pushable = pushable))
            }
        }
    }
}

/** A notice the phone writes into a thread (never sent anywhere). */
internal suspend fun insertSystemRow(dao: MessagesDao, chatId: String, aboutUserId: String, text: String, at: Long) {
    dao.insert(
        MessageEntity(
            clientMessageId = newClientMessageId(), messageId = null, chatId = chatId, senderId = aboutUserId, outgoing = false,
            kind = MessageKind.System, text = text, replyTo = null, status = MessageStatus.Received, errorCode = null,
            cachedRequest = null, sentAt = null, orderAt = at, createdAt = at,
        ),
    )
}

/**
 * Receives messages: the inbox over HTTP (M13) and `message.new` frames. Each one is decrypted, saved, then
 * acknowledged; a duplicate is acknowledged without being saved; one this phone cannot open is saved as unreadable
 * and acknowledged, so it never comes back; a group message whose sender key has not arrived waits unacknowledged.
 */
class InboxProcessor(
    private val dao: MessagesDao,
    private val chats: ChatsDao,
    private val remote: MessagesRemote,
    private val sessions: SessionCipher,
    private val groupKeys: GroupKeys,
    private val realtime: RealtimeClient,
    private val loadChat: suspend (String) -> AppResult<*>,
    private val now: () -> Long = ::nowMillis,
    /** A payment card arrived: the wallet reads the transfer (W5) so the card can show its state. */
    private val onPaymentReceived: suspend (transferId: String) -> Unit = {},
    private val reactionsDao: com.circaso.feature.messaging.data.local.ReactionsDao? = null,
    /** Asks for unreadable messages again and answers others' asks (retry receipts). */
    private val retries: RetryReceipts? = null,
    /** Payment cards keyed by transfer, so the wallet's fallback card and the end-to-end one are one card. */
    private val paymentCards: ChatPaymentCards? = null,
) {
    private val lock = Mutex()

    /** Set when a message couldn't be opened, so a live frame's [askAgain] has something to ask for. */
    private var unreadableSince = false

    /**
     * Asks the senders of messages this phone couldn't open to send them again (outside the inbox lock: it goes to
     * the network). After a sync always (older unreadable rows and asks the rate limit held back); after a live frame
     * only when it was unreadable.
     */
    private suspend fun askAgain(always: Boolean) {
        val r = retries ?: return
        if (!always && !unreadableSince) return
        unreadableSince = false
        r.sweep()
    }

    /** A reaction that just arrived, by the id of the message that carried it: what a push names ("reacted 👍 to …"). */
    class ArrivedReaction(val chatId: String, val senderId: String, val emoji: String, val targetMessageId: String)
    private val recentReactions = mutableMapOf<String, ArrivedReaction>()
    fun reactionCarriedBy(messageId: String): ArrivedReaction? = recentReactions.remove(messageId)


    suspend fun sync(): AppResult<Unit> = syncLocked().also { if (it is AppResult.Success) askAgain(always = true) }

    private suspend fun syncLocked(): AppResult<Unit> = lock.withLock {
        var after: String? = null
        do {
            val page = when (val r = remote.inbox(after)) {
                is AppResult.Failure -> return@withLock r
                is AppResult.Success -> r.value
            }
            val done = page.messages.filter { handle(it) }.map { it.messageId }
            ack(done)
            after = page.next
        } while (after != null && page.messages.isNotEmpty())
        dropStalePending()
        AppResult.Success(Unit)
    }

    /** `message.new`: the ciphertext comes with the frame when small enough; otherwise it waits in the inbox. */
    suspend fun onMessageNew(data: JsonObject) {
        val dto = runCatching {
            InboxMessageDto(
                messageId = data.string("messageId")!!, chatId = data.string("chatId")!!, senderId = data.string("senderId")!!,
                kind = data.string("kind")!!, ciphertext = data.string("ciphertext") ?: return sync().let { }, sentAt = data.string("sentAt")!!,
            )
        }.getOrNull() ?: return
        lock.withLock { if (handle(dto)) ack(listOf(dto.messageId)) }
        askAgain(always = false)
    }

    private suspend fun ack(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.chunked(100).forEach { chunk ->
            val sent = realtime.send("messaging", "ack", buildJsonObject { put("messageIds", buildJsonArray { chunk.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) })
            if (!sent) remote.ack(AckRequest(chunk))
        }
    }

    /** Returns true when the message can be acknowledged. */
    private suspend fun handle(dto: InboxMessageDto): Boolean {
        if (dao.byMessageId(dto.messageId) != null) return true
        if (chats.chat(dto.chatId) == null) loadChat(dto.chatId)
        val chatKnown = chats.chat(dto.chatId) != null
        val bytes = runCatching { Base64.decode(dto.ciphertext) }.getOrNull() ?: return unreadable(dto, "not base64")
        val frame = WireFormat.decode(bytes) ?: return unreadable(dto, "unknown wire")
        return when (frame.type) {
            WireFormat.Type.PreKeyMessage, WireFormat.Type.WhisperMessage -> {
                val kind = if (frame.type == WireFormat.Type.PreKeyMessage) CiphertextKind.PreKey else CiphertextKind.Whisper
                when (val opened = sessions.open(dto.senderId, kind, frame.body)) {
                    is Opened.Duplicate -> true
                    is Opened.Unreadable -> unreadable(dto, opened.reason)
                    is Opened.Plain -> {
                        if (opened.identityChanged && chatKnown) insertSystemRow(dao, dto.chatId, dto.senderId, "${senderName(dto)}'s security code changed", now())
                        deliver(dto, opened.bytes)
                    }
                }
            }
            WireFormat.Type.SenderKeyMessage -> when (val opened = groupKeys.openFromGroup(dto.chatId, dto.senderId, frame.body)) {
                is OpenedGroup.Duplicate -> true
                is OpenedGroup.Unreadable -> unreadable(dto, opened.reason)
                is OpenedGroup.NoSenderKey -> { park(dto); false }
                is OpenedGroup.Plain -> deliver(dto, opened.bytes)
            }
            WireFormat.Type.SenderKeyDistribution -> unreadable(dto, "bare distribution")
            // Someone couldn't open one of my messages and asks for it again (never shown; answered once).
            WireFormat.Type.RetryRequest -> { retries?.onRequest(dto.chatId, dto.senderId, frame.body); true }
        }
    }

    private suspend fun deliver(received: InboxMessageDto, plaintext: ByteArray): Boolean {
        val payload = ChatPayload.decode(plaintext) ?: return unreadable(received, "not a payload")
        // A message sent again because this phone asked: it takes the place (id, time) of the row that couldn't show.
        var replaces: MessageEntity? = null
        val dto = when (val original = payload.resendOf) {
            null -> received
            else -> {
                // Only what a thread shows is ever sent again; keys and controls never are.
                if (payload.type in NeverResent) return true
                val (asSaved, target) = retries?.resent(received, original) ?: return true
                replaces = target
                asSaved
            }
        }
        val saved = deliverPayload(dto, payload)
        replaces?.let { if (saved) dao.delete(it.localId) }
        return true
    }

    /** True when [payload] was saved or applied; false when it was refused as unreadable. */
    private suspend fun deliverPayload(dto: InboxMessageDto, payload: ChatPayload): Boolean {
        when (payload.type) {
            ChatPayload.Text, ChatPayload.Payment -> {
                val at = isoToMillis(dto.sentAt) ?: now()
                val payment = payload.type == ChatPayload.Payment
                if (payment && payload.transferId.isNullOrBlank()) return refuse(dto, "payment without a transfer")
                val row = MessageEntity(
                    clientMessageId = newClientMessageId(), messageId = dto.messageId, chatId = dto.chatId, senderId = dto.senderId, outgoing = false,
                    kind = if (payment) MessageKind.Payment else MessageKind.Text, text = if (payment) payload.text else payload.text.orEmpty(), replyTo = payload.replyTo,
                    status = MessageStatus.Received, errorCode = null, cachedRequest = null, sentAt = dto.sentAt, orderAt = at, createdAt = now(),
                    transferId = payload.transferId, amountMinor = payload.amountMinor, asset = payload.asset, blockchain = payload.blockchain,
                    momentQuote = payload.moment?.let { kotlinx.serialization.json.Json.encodeToString(com.circaso.core.crypto.MomentQuote.serializer(), it) },
                )
                // One card per transfer: the wallet may already have put this payment's card in the chat.
                if (payment && paymentCards != null) paymentCards.receive(row) else dao.insert(row)
                chats.touch(dto.chatId, at)
                if (payment) onPaymentReceived(payload.transferId!!)
            }
            ChatPayload.Image, ChatPayload.Voice, ChatPayload.Video, ChatPayload.File, ChatPayload.Location, ChatPayload.LiveLocation -> {
                val details = payload.toMediaDetails() ?: return refuse(dto, "incomplete ${payload.type}")
                val at = isoToMillis(dto.sentAt) ?: now()
                dao.insert(
                    MessageEntity(
                        clientMessageId = newClientMessageId(), messageId = dto.messageId, chatId = dto.chatId, senderId = dto.senderId, outgoing = false,
                        kind = mediaRowKind(details), text = MediaDetails.write(details), replyTo = null,
                        status = MessageStatus.Received, errorCode = null, cachedRequest = null, sentAt = dto.sentAt, orderAt = at, createdAt = now(),
                    ),
                )
                chats.touch(dto.chatId, at)
            }
            ChatPayload.LiveUpdate, ChatPayload.LiveStop -> moveLiveShare(dto, payload)
            // Someone's reaction: kept with the message it is on, never shown as a message of its own.
            ChatPayload.Reaction -> {
                val target = payload.replyTo ?: return refuse(dto, "reaction without a target")
                val emoji = payload.text.orEmpty()
                if (emoji.isNotEmpty()) recentReactions[dto.messageId] = ArrivedReaction(dto.chatId, dto.senderId, emoji, target)
                if (emoji.isEmpty()) reactionsDao?.remove(target, dto.senderId)
                else reactionsDao?.upsert(com.circaso.feature.messaging.data.local.MessageReactionEntity(dto.chatId, target, dto.senderId, emoji.take(16), isoToMillis(dto.sentAt) ?: now()))
            }
            ChatPayload.SenderKey, ChatPayload.MetaKey, ChatPayload.Control -> {
                groupKeys.processDistribution(dto.chatId, dto.senderId, payload)
                // The group's name may be readable now, and messages that waited for this key can open.
                loadChat(dto.chatId)
                retryPending(dto.chatId, dto.senderId)
            }
            // A member's phone lost this group's key and asks for it: answered 1:1 when I hold it. Never shown.
            ChatPayload.MetaKeyRequest -> groupKeys.answerMetaKeyRequest(dto.chatId, dto.senderId)
            // A member asked for the key has none either: this phone stops waiting on them. Never shown.
            ChatPayload.MetaKeyNone -> groupKeys.onNoKey(dto.chatId, dto.senderId)
            else -> return refuse(dto, "unknown payload ${payload.type}")
        }
        return true
    }

    private suspend fun refuse(dto: InboxMessageDto, reason: String): Boolean { unreadable(dto, reason); return false }

    /**
     * A live location's update or stop: moves (or ends) the pin of the message the share started as. An update older
     * than the pin, or for a share this phone never saw start, is dropped; the next one carries the current point.
     */
    private suspend fun moveLiveShare(dto: InboxMessageDto, payload: ChatPayload) {
        val liveId = payload.liveId ?: return
        val row = dao.liveShare(dto.chatId, dto.senderId, liveId) ?: return
        val details = MediaDetails.read(row.text) ?: return
        val moved = when (payload.type) {
            ChatPayload.LiveStop -> details.copy(liveEnded = true)
            else -> {
                val at = payload.liveAt ?: return
                val lat = payload.latitude ?: return
                val lng = payload.longitude ?: return
                if (details.liveEnded == true || at <= (details.liveAt ?: 0)) return
                details.copy(latitude = lat, longitude = lng, accuracy = payload.accuracy, liveAt = at)
            }
        }
        dao.setText(row.localId, MediaDetails.write(moved))
    }

    private suspend fun retryPending(chatId: String, senderId: String) {
        val ready = dao.pendingFrom(chatId, senderId)
        val acked = ready.filter { p -> handle(InboxMessageDto(p.messageId, p.chatId, p.senderId, p.kind, p.ciphertext, emptyList(), p.sentAt)).also { if (it) dao.deletePending(p.messageId) } }
        ack(acked.map { it.messageId })
    }

    private suspend fun park(dto: InboxMessageDto) =
        dao.upsertPending(PendingInboxEntity(dto.messageId, dto.chatId, dto.senderId, dto.kind, dto.ciphertext, dto.sentAt, now()))

    /** After a day without the key the message will never open: say so in the thread and let the server forget it. */
    private suspend fun dropStalePending() {
        val stale = dao.pendingOlderThan(now() - PendingKeepMillis)
        if (stale.isEmpty()) return
        stale.forEach { p -> unreadable(InboxMessageDto(p.messageId, p.chatId, p.senderId, p.kind, p.ciphertext, emptyList(), p.sentAt), "no sender key"); dao.deletePending(p.messageId) }
        ack(stale.map { it.messageId })
    }

    private suspend fun unreadable(dto: InboxMessageDto, reason: String): Boolean {
        Diagnostics.note("inbox: unreadable message ($reason)")
        if (dto.kind == MessagingWire.KindMessage) {
            unreadableSince = true // ask its sender to send it again (retry receipt)
            dao.insert(
                MessageEntity(
                    clientMessageId = newClientMessageId(), messageId = dto.messageId, chatId = dto.chatId, senderId = dto.senderId, outgoing = false,
                    kind = MessageKind.Text, text = null, replyTo = null, status = MessageStatus.Undecryptable, errorCode = reason,
                    cachedRequest = null, sentAt = dto.sentAt, orderAt = isoToMillis(dto.sentAt) ?: now(), createdAt = now(),
                ),
            )
        }
        return true
    }

    private suspend fun senderName(dto: InboxMessageDto) = chats.members(dto.chatId).firstOrNull { it.userId == dto.senderId }?.displayName ?: "Someone"

    private fun JsonObject.string(key: String) = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val PendingKeepMillis = 24L * 60 * 60 * 1000
        val NeverResent = setOf(ChatPayload.SenderKey, ChatPayload.MetaKey, ChatPayload.MetaKeyRequest, ChatPayload.MetaKeyNone, ChatPayload.Control, ChatPayload.MomentBody, ChatPayload.LiveUpdate, ChatPayload.LiveStop)
    }
}

/** Routes the `messaging` realtime frames (guide, section 8) to the repositories. */
class MessagingFrameHandler(
    private val inbox: InboxProcessor,
    private val messages: MessagesRepositoryImpl,
    private val chats: ChatsRepositoryImpl,
    private val chatsDao: ChatsDao,
    private val messagesDao: MessagesDao,
    private val sessions: SessionCipher,
    private val now: () -> Long = ::nowMillis,
    /** Group invites (2026-11-09); null in tests that don't need them. */
    private val invites: GroupInvitesRepositoryImpl? = null,
) {
    suspend fun handle(type: String, data: JsonObject?) {
        val d = data ?: return
        when (type) {
            "message.new" -> inbox.onMessageNew(d)
            "receipt" -> {
                val chatId = d.str("chatId") ?: return
                val receipts = (d["receipts"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { r ->
                    val o = r as? JsonObject ?: return@mapNotNull null
                    MessageReceiptEntity(o.str("messageId") ?: return@mapNotNull null, o.str("userId") ?: return@mapNotNull null, o.str("status") ?: return@mapNotNull null, o.str("at") ?: "")
                }
                messages.onReceipts(chatId, receipts)
            }
            "presence.changed" -> messages.onPresence(
                d.str("userId") ?: return,
                (d["online"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
                d.str("lastSeenAt")?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
            )
            "typing" -> messages.onTyping(d.str("chatId") ?: return, d.str("userId") ?: return, (d["on"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true")
            "message.deleted" -> messages.onDeleted(d.str("messageId") ?: return)
            "message.undeliverable" -> messages.onUndeliverable(d.str("messageId") ?: return)
            "keys.changed" -> {
                val userId = d.str("userId") ?: return
                sessions.reset(userId)
                for (chat in chatsDao.chats()) {
                    val member = chatsDao.members(chat.chatId).firstOrNull { it.userId == userId } ?: continue
                    insertSystemRow(messagesDao, chat.chatId, userId, "${member.displayName}'s security code changed", now())
                }
            }
            "member.added" -> chats.onMemberAdded(
                d.str("chatId") ?: return,
                (d["userIds"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                joined = (d["joined"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
            )
            // Group invites (backend PR #83): mine came or went; a group's Invited list changed.
            "invite.received" -> invites?.onInviteReceived()
            "invite.removed" -> invites?.onInviteRemoved(d.str("inviteId") ?: return)
            "invites.changed" -> invites?.onInvitesChanged(d.str("chatId") ?: return)
            "member.removed" -> chats.onMemberRemoved(d.str("chatId") ?: return, d.str("userId") ?: return, d.str("byUserId"))
            "chat.updated" -> chats.onChatUpdated(
                d.str("chatId") ?: return, d.int("version") ?: return,
                if ("avatarMediaId" in d) ChatsRepositoryImpl.Avatar(d.str("avatarMediaId")) else null,
            )
        }
    }

    private fun JsonObject.str(key: String) = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String) = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
}

// ---- mapping

/**
 * A notice about someone ("Amina joined", "Amina's security code changed") was written with their name at the time; it
 * reads with the name I saved for them instead, so it matches the rest of the chat.
 */
internal fun systemText(text: String, profileName: String?, savedName: String?): String =
    if (savedName != null && !profileName.isNullOrBlank() && text.startsWith(profileName)) savedName + text.removePrefix(profileName) else text

internal fun MessageEntity.toDomain(members: List<ChatMemberEntity>, me: String?, saved: Map<String, String> = emptyMap()): Message {
    val member = members.firstOrNull { it.userId == senderId }
    val name = saved[senderId].takeIf { senderId != me } ?: member?.displayName ?: if (senderId == me) "You" else "Someone"
    return when {
        kind == MessageKind.System -> Message.System(localId, chatId, orderAt, sentAt, systemText(text.orEmpty(), member?.displayName, saved[senderId]))
        status == MessageStatus.Deleted -> Message.Deleted(localId, chatId, orderAt, sentAt, outgoing)
        isMediaKind(kind) -> {
            val d = MediaDetails.read(text) ?: MediaDetails(MediaDetails.Location)
            Message.Media(
                localId = localId, chatId = chatId, orderAt = orderAt, sentAt = sentAt, messageId = messageId, senderId = senderId, senderName = name,
                outgoing = outgoing, details = d, state = deliveryState(status), errorCode = errorCode,
            )
        }
        kind == MessageKind.Call -> {
            val d = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text.orEmpty()) as JsonObject }.getOrNull()
            val p = { k: String -> (d?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content }
            Message.Call(
                localId, chatId, orderAt, sentAt, outgoing = outgoing, video = p("video") == "true",
                outcome = runCatching { CallOutcome.valueOf(p("outcome").orEmpty()) }.getOrDefault(CallOutcome.Completed),
                durationSeconds = p("seconds")?.toLongOrNull() ?: 0, callerName = name,
            )
        }
        status == MessageStatus.Deleted -> Message.Deleted(localId, chatId, orderAt, sentAt, outgoing)
        status == MessageStatus.Undecryptable -> Message.Unreadable(localId, chatId, orderAt, sentAt, name)
        kind == MessageKind.Payment && transferId != null -> Message.Payment(
            localId = localId, chatId = chatId, orderAt = orderAt, sentAt = sentAt, messageId = messageId, senderId = senderId, senderName = name,
            outgoing = outgoing, transferId = transferId, amountMinor = amountMinor ?: "0", asset = asset ?: "USDC", blockchain = blockchain.orEmpty(),
            note = text?.takeIf { it.isNotBlank() }, errorCode = errorCode, state = deliveryState(status),
        )
        else -> Message.Text(
            localId = localId, chatId = chatId, orderAt = orderAt, sentAt = sentAt, messageId = messageId, senderId = senderId, senderName = name,
            outgoing = outgoing, text = text.orEmpty(), replyTo = replyTo, errorCode = errorCode,
            state = deliveryState(status),
            momentQuote = momentQuote?.let { runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(com.circaso.core.crypto.MomentQuote.serializer(), it) }.getOrNull() },
        )
    }
}

private fun deliveryState(status: String): DeliveryState = when (status) {
    MessageStatus.Waiting -> DeliveryState.Waiting
    MessageStatus.Sending -> DeliveryState.Sending
    MessageStatus.Sent -> DeliveryState.Sent
    MessageStatus.Delivered -> DeliveryState.Delivered
    MessageStatus.Read -> DeliveryState.Read
    MessageStatus.Failed -> DeliveryState.Failed
    else -> DeliveryState.Delivered // an incoming message is, by definition, delivered
}
