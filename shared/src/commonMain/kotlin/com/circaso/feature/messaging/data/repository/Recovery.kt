package com.circaso.feature.messaging.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.Diagnostics
import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.WireFormat
import com.circaso.core.sync.nowMillis
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.local.MessageEntity
import com.circaso.feature.messaging.data.local.MessageKind
import com.circaso.feature.messaging.data.local.MessageRetryEntity
import com.circaso.feature.messaging.data.local.MessageStatus
import com.circaso.feature.messaging.data.local.MessagesDao
import com.circaso.feature.messaging.data.local.RecoveryDao
import com.circaso.feature.messaging.data.local.RetryRole
import com.circaso.feature.messaging.data.remote.InboxMessageDto
import com.circaso.feature.messaging.data.remote.MessagesRemote
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.remote.RecipientDto
import com.circaso.feature.messaging.data.remote.SendChatMessageRequest
import com.circaso.feature.wallet.domain.ChatPaymentSeen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * A chat's payment cards, keyed by transfer: one card per transfer per chat, whichever way it arrives. Money is never
 * hidden by decryption: when the server's own record says a payment from this chat is on chain ([fromWallet]), the
 * card is there even if the end-to-end card is unreadable on this phone or never arrives (this phone may be new). When
 * the end-to-end card does arrive ([receive]), it fills in that card (its note, its message id, its place) instead of
 * adding a second one. Nothing here is optimistic: a card from the wallet exists only for a confirmed transfer, and
 * every card shows the transfer's own state.
 */
class ChatPaymentCards(
    private val dao: MessagesDao,
    private val chats: ChatsDao,
    private val recovery: RecoveryDao,
    private val ownUserId: suspend () -> String?,
    private val loadChat: suspend (String) -> AppResult<*>,
    private val now: () -> Long = ::nowMillis,
) {
    private val lock = Mutex()

    /** Runs [block] so that no card for the same transfer is written meanwhile (my own send uses it too). */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

    /**
     * The wallet says [seen] is on chain. Only direct chats: a group's money has its own cards (contributions,
     * payouts) built from the group's records. A chat this phone doesn't have yet is loaded first; if that fails the
     * next sync tries again.
     */
    suspend fun fromWallet(seen: ChatPaymentSeen) {
        val me = ownUserId() ?: return
        if (chats.chat(seen.chatId) == null) loadChat(seen.chatId)
        val chat = chats.chat(seen.chatId) ?: return
        if (chat.kind != MessagingWire.Direct) return
        val other = chats.members(seen.chatId).firstOrNull { it.userId != me } ?: return
        if (seen.counterpartUserId != null && seen.counterpartUserId != other.userId) return
        lock.withLock {
            if (recovery.paymentCard(seen.chatId, seen.transferId) != null) return
            dao.insert(
                MessageEntity(
                    clientMessageId = fallbackId(seen.transferId), messageId = null, chatId = seen.chatId,
                    senderId = if (seen.outgoing) me else other.userId, outgoing = seen.outgoing,
                    kind = MessageKind.Payment, text = null, replyTo = null,
                    status = if (seen.outgoing) MessageStatus.Sent else MessageStatus.Received, errorCode = null,
                    cachedRequest = null, sentAt = null, orderAt = seen.at, createdAt = now(),
                    transferId = seen.transferId, amountMinor = seen.amountMinor, asset = seen.asset, blockchain = seen.blockchain,
                ),
            )
        }
        chats.touch(seen.chatId, seen.at)
    }

    /**
     * Their end-to-end card [row]: saved, unless the wallet already put this transfer's card in the chat, which then
     * takes the note, the message id and the time from it. A second end-to-end card for one transfer is dropped.
     */
    suspend fun receive(row: MessageEntity) = lock.withLock {
        val transferId = row.transferId ?: return@withLock
        val existing = recovery.paymentCard(row.chatId, transferId)
        when {
            existing == null -> dao.insert(row)
            existing.messageId == null && !existing.outgoing && existing.senderId == row.senderId -> dao.upsert(
                existing.copy(messageId = row.messageId, text = row.text, sentAt = row.sentAt, orderAt = row.orderAt, replyTo = row.replyTo),
            )
            else -> Unit
        }
    }

    /** A card the wallet made for my own payment (this phone is new), which my send can take over instead of adding one. */
    suspend fun walletCardFor(chatId: String, transferId: String): MessageEntity? =
        recovery.paymentCard(chatId, transferId)?.takeIf { it.outgoing && it.messageId == null && it.clientMessageId == fallbackId(transferId) }

    companion object {
        fun fallbackId(transferId: String) = "pay-$transferId"
    }
}

/**
 * Retry receipts, as Signal does them. When this phone can't open someone's message (no session, a bad mac, a session
 * left from their old phone), it asks them, once per message, to send it again ([sweep]): a small unencrypted
 * [WireFormat.Type.RetryRequest] naming only the message id, as a `CONTROL` message in a direct chat or a 1:1
 * `SENDER_KEY` message in a group (the only kinds the server lets go to one person). Their phone, if it still has the
 * message ([onRequest]: my outgoing rows are in Room), starts a fresh session to this phone's current identity
 * ([SessionCipher.restart]) and sends it again, 1:1, with [ChatPayload.resendOf] naming the original; [resent] then
 * puts it in place of the "can't show" row. In a group it also hands this phone its sender key again.
 *
 * Limits: asking is at most [AsksPerMinute] a minute and only for messages received in the last [AskWithinMillis];
 * answering is once per message and person, at most [AnswersPerMinute] a minute per person, only for my messages of
 * the last [AnswerWithinMillis], and only to someone still in that chat.
 */
class RetryReceipts(
    private val dao: MessagesDao,
    private val chats: ChatsDao,
    private val recovery: RecoveryDao,
    private val remote: MessagesRemote,
    private val sessions: SessionCipher,
    private val groupKeys: GroupKeys,
    private val json: Json,
    private val ownUserId: suspend () -> String?,
    private val now: () -> Long = ::nowMillis,
    private val newId: () -> String = ::newClientMessageId,
) {
    private val asking = Mutex()
    private val answering = Mutex()
    private val asked = ArrayDeque<Long>()
    private val answered = mutableMapOf<String, ArrayDeque<Long>>()
    private val restartedAt = mutableMapOf<String, Long>()

    // ---- asking

    /** Asks the senders of messages this phone couldn't open to send them again. Stops at the first network failure. */
    suspend fun sweep() = asking.withLock {
        val me = ownUserId() ?: return@withLock
        val due = recovery.unreadableToAsk(now() - AskWithinMillis, AsksPerMinute)
        for (row in due) {
            if (row.senderId == me) { record(row, RetryRole.Asked, row.senderId); continue }
            if (!take(asked, AsksPerMinute)) return@withLock // the rest wait for a later sweep
            when (val sent = ask(row)) {
                is AppResult.Success -> record(row, RetryRole.Asked, row.senderId)
                is AppResult.Failure -> if (retryable(sent.error)) return@withLock else record(row, RetryRole.Asked, row.senderId)
            }
        }
    }

    private suspend fun ask(row: MessageEntity): AppResult<*> {
        val chat = chats.chat(row.chatId) ?: return AppResult.Failure(AppError.Api("messaging.chat_not_found", 404, null, null, emptyMap()))
        val wire = Base64.encode(WireFormat.retryRequest(row.messageId!!))
        val id = newId()
        val request = SendChatMessageRequest(id, kindFor(chat.kind), recipients = listOf(RecipientDto(row.senderId, wire)), pushable = false)
        Diagnostics.note("retry: asking for a message again")
        return remote.send(row.chatId, request, id)
    }

    // ---- answering

    /** [requesterId] couldn't open my message named in [body] (a retry request) and asks for it again. */
    suspend fun onRequest(chatId: String, requesterId: String, body: ByteArray) = answering.withLock {
        val messageId = WireFormat.readRetryRequest(body) ?: return@withLock
        val me = ownUserId() ?: return@withLock
        if (requesterId == me) return@withLock
        val row = dao.byMessageId(messageId) ?: return@withLock
        if (!row.outgoing || row.chatId != chatId || row.status == MessageStatus.Deleted) return@withLock
        if (row.createdAt < now() - AnswerWithinMillis) return@withLock
        val chat = chats.chat(chatId) ?: return@withLock
        if (chats.members(chatId).none { it.userId == requesterId }) return@withLock
        if (recovery.retry(messageId, requesterId, RetryRole.Answered) != null) return@withLock
        val payload = row.toChatPayload(json)?.copy(resendOf = messageId) ?: return@withLock
        if (!take(answered.getOrPut(requesterId) { ArrayDeque() }, AnswersPerMinute)) return@withLock

        // A fresh session to their current identity, once a minute at most however many messages they ask for.
        val t = now()
        if (t - (restartedAt[requesterId] ?: Long.MIN_VALUE / 2) > RestartEveryMillis) {
            when (val restarted = sessions.restart(requesterId)) {
                is AppResult.Failure -> return@withLock // offline or no keys: they asked once; nothing recorded, so a repeat is answered
                is AppResult.Success -> {
                    restartedAt[requesterId] = t
                    if (restarted.value && chat.kind == MessagingWire.Direct) {
                        insertSystemRow(dao, chatId, requesterId, "${chats.members(chatId).first { it.userId == requesterId }.displayName}'s security code changed", t)
                    }
                }
            }
        }
        if (chat.kind != MessagingWire.Direct) {
            // Their phone didn't hold my sender key (a new phone counted as the old one): hand it over again.
            chats.deleteDelivery(chatId, requesterId)
            groupKeys.ensureDistributed(chatId, listOf(requesterId))
        }
        val sealed = when (val s = sessions.seal(requesterId, ChatPayload.encode(payload))) {
            is AppResult.Failure -> return@withLock
            is AppResult.Success -> s.value
        }
        val id = newId()
        val request = SendChatMessageRequest(id, kindFor(chat.kind), recipients = listOf(RecipientDto(requesterId, Base64.encode(sealed.wire))), pushable = false)
        if (remote.send(chatId, request, id) is AppResult.Success) {
            recovery.insertRetry(MessageRetryEntity(messageId, requesterId, RetryRole.Answered, chatId, now()))
            Diagnostics.note("retry: sent a message again")
        }
    }

    // ---- receiving what was sent again

    /**
     * [dto] carries [original] sent again. Returns the message as it should be saved: under the original's id and
     * time, with the "can't show" row it replaces (to delete once the new one is saved), or null to drop it (the
     * original opened after all, or it isn't theirs to replace).
     */
    suspend fun resent(dto: InboxMessageDto, original: String): Pair<InboxMessageDto, MessageEntity?>? {
        val target = dao.byMessageId(original)
        if (target == null) return dto.copy(messageId = original) to null
        if (target.status != MessageStatus.Undecryptable || target.outgoing || target.senderId != dto.senderId || target.chatId != dto.chatId) return null
        return dto.copy(messageId = original, sentAt = target.sentAt ?: dto.sentAt) to target
    }

    // ----

    private fun kindFor(chatKind: String) = if (chatKind == MessagingWire.Direct) MessagingWire.KindControl else MessagingWire.KindSenderKey

    private suspend fun record(row: MessageEntity, role: String, peerId: String) {
        recovery.insertRetry(MessageRetryEntity(row.messageId!!, peerId, role, row.chatId, now()))
    }

    /** A sliding minute: true (and counted) if one more fits under [max]. */
    private fun take(window: ArrayDeque<Long>, max: Int): Boolean {
        val t = now()
        while (window.isNotEmpty() && window.first() <= t - MinuteMillis) window.removeFirst()
        if (window.size >= max) return false
        window.addLast(t)
        return true
    }

    private fun retryable(error: AppError) =
        error is AppError.Network || error is AppError.Unexpected || (error as? AppError.Api)?.let { it.httpStatus >= 500 || it.httpStatus == 429 } == true

    companion object {
        const val AsksPerMinute = 5
        const val AnswersPerMinute = 10
        const val MinuteMillis = 60_000L
        const val RestartEveryMillis = 60_000L
        const val AskWithinMillis = 30L * 24 * 60 * 60 * 1000
        const val AnswerWithinMillis = 30L * 24 * 60 * 60 * 1000
    }
}
