package com.circaso.feature.status.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.CiphertextKind
import com.circaso.core.crypto.GroupBytes
import com.circaso.core.crypto.PeerAddress
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.crypto.SignalException
import com.circaso.core.crypto.WireFormat
import com.circaso.core.sync.nowMillis
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.local.SenderKeyDeliveryEntity
import com.circaso.feature.messaging.data.repository.Opened
import com.circaso.feature.messaging.data.repository.SessionCipher
import com.circaso.feature.status.data.remote.KeyCopyDto
import com.circaso.feature.status.data.remote.MomentKeyCopyDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** What sealing a moment produced: the body under my moments sender key, and the 1:1 key copies still needed. */
class SealedMoment(val ciphertext: String, val keyCopies: List<KeyCopyDto>, val distributionId: String, val copiedTo: List<String>)

sealed interface OpenedMoment {
    class Plain(val bytes: ByteArray) : OpenedMoment
    /** The poster's key hasn't reached this phone yet: keep the ciphertext and try when it does. */
    data object NoKey : OpenedMoment
    data object Unreadable : OpenedMoment
}

/**
 * Moments use libsignal sender keys the way WhatsApp's status does: one sender key of mine for all my moments (kept
 * under the scope [Scope] in the same sealed store chats use), the body encrypted once, and a key copy over the 1:1
 * session to each audience member who doesn't hold the current key yet. When someone leaves my audience, the key is
 * rotated so they can't read what comes next.
 */
class MomentKeys(
    private val store: SealedRecordStore,
    private val crypto: SignalCrypto,
    private val deliveries: ChatsDao,
    private val sessions: SessionCipher,
    private val ownUserId: suspend () -> String?,
    private val now: () -> Long = ::nowMillis,
) {
    private val lock = Mutex()

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun fresh(me: String): Pair<String, ByteArray> {
        val distributionId = Uuid.random().toString()
        val created = crypto.createSenderKeyDistribution(PeerAddress(me), distributionId, null)
        store.saveSenderKey(Scope, me, distributionId, created.record, now())
        store.deleteOtherSenderKeys(Scope, me, distributionId)
        deliveries.deleteDeliveries(Scope)
        return distributionId to created.record
    }

    /**
     * Seals [plaintext] for [audience]. A key copy goes to every member who lacks my current key; if anyone who had it
     * is no longer in the audience, the key is replaced first and everyone gets the new one. Online-only (sessions
     * may need a key bundle); a network failure is returned so the outbox retries.
     */
    suspend fun seal(audience: List<String>, plaintext: ByteArray): AppResult<SealedMoment> = lock.withLock {
        val me = ownUserId() ?: return@withLock AppResult.Failure(AppError.Unexpected(null))
        var (distributionId, record) = store.latestSenderKey(Scope, me) ?: fresh(me)
        val had = deliveries.deliveredTo(Scope, distributionId).toSet()
        if (had.any { it !in audience }) fresh(me).also { distributionId = it.first; record = it.second }
        val have = deliveries.deliveredTo(Scope, distributionId).toSet()
        val missing = audience.filter { it != me && it !in have }

        val copies = mutableListOf<KeyCopyDto>()
        val reached = mutableListOf<String>()
        if (missing.isNotEmpty()) {
            val distribution = crypto.createSenderKeyDistribution(PeerAddress(me), distributionId, record)
            record = distribution.record
            store.saveSenderKey(Scope, me, distributionId, record, now())
            val payload = ChatPayload.encode(ChatPayload(type = ChatPayload.SenderKey, senderKey = Base64.encode(distribution.distribution)))
            for (member in missing) {
                when (val sealed = sessions.seal(member, payload)) {
                    is AppResult.Success -> { copies += KeyCopyDto(member, Base64.encode(sealed.value.wire)); reached += member }
                    // Someone without keys is skipped; they get a copy with the next moment.
                    is AppResult.Failure -> if (sealed.error is AppError.Network) return@withLock sealed
                }
            }
        }
        try {
            val out = crypto.groupEncrypt(PeerAddress(me), distributionId, record, plaintext)
            store.saveSenderKey(Scope, me, distributionId, out.record, now())
            AppResult.Success(SealedMoment(Base64.encode(WireFormat.encode(WireFormat.Type.SenderKeyMessage, out.ciphertext)), copies, distributionId, reached))
        } catch (e: SignalException) {
            AppResult.Failure(AppError.Unexpected(null))
        }
    }

    /** After the server took the moment: these people now hold the current key. */
    suspend fun delivered(distributionId: String, userIds: List<String>) =
        deliveries.insertDeliveries(userIds.map { SenderKeyDeliveryEntity(Scope, it, distributionId, now()) })

    /** My privacy changed so someone is out: the next moment goes under a new key. */
    suspend fun rotate() = lock.withLock { ownUserId()?.let { fresh(it) } }

    /**
     * A key copy from [posterId] on [momentId] (their 1:1 message). Usually their moments key, which is learned; after
     * a key request it is that moment's body sealed for me alone, which is returned (moments-api.md, key request).
     * Already seen copies are harmless.
     */
    suspend fun learn(posterId: String, keyCopy: String, momentId: String? = null): ByteArray? = lock.withLock {
        val frame = runCatching { WireFormat.decode(Base64.decode(keyCopy)) }.getOrNull() ?: return@withLock null
        val kind = when (frame.type) {
            WireFormat.Type.PreKeyMessage -> CiphertextKind.PreKey
            WireFormat.Type.WhisperMessage -> CiphertextKind.Whisper
            else -> return@withLock null
        }
        val opened = sessions.open(posterId, kind, frame.body) as? Opened.Plain ?: return@withLock null
        val payload = ChatPayload.decode(opened.bytes) ?: return@withLock null
        if (payload.type == ChatPayload.MomentBody) {
            // Only for the moment it names, so a copy can never be shown as another moment.
            return@withLock payload.text?.takeIf { momentId != null && payload.momentId == momentId }?.encodeToByteArray()
        }
        val encoded = payload.senderKey ?: return@withLock null
        runCatching {
            val distribution = Base64.decode(encoded)
            val distributionId = crypto.distributionIdOf(GroupBytes.Distribution, distribution)
            val current = store.senderKey(Scope, posterId, distributionId)
            val learned = crypto.processSenderKeyDistribution(PeerAddress(posterId), distribution, current)
            store.saveSenderKey(Scope, posterId, learned.distributionId, learned.record, now())
        }
        null
    }

    /**
     * [userId] asked for keys (a new phone). A sender-key copy made now would only open my later moments (the chain
     * moves forward), so each live moment's [bodies] (momentId to the body I wrote) is sealed again for them over a
     * session with their current identity, and they no longer count as holding my key, so the next moment carries
     * one. Online-only; a network failure is returned so the outbox retries.
     */
    suspend fun reseal(userId: String, bodies: List<Pair<String, ByteArray>>): AppResult<List<MomentKeyCopyDto>> = lock.withLock {
        (sessions.refresh(userId) as? AppResult.Failure)?.let { return@withLock it }
        deliveries.deleteDelivery(Scope, userId)
        val copies = mutableListOf<MomentKeyCopyDto>()
        for ((momentId, body) in bodies) {
            val payload = ChatPayload.encode(ChatPayload(type = ChatPayload.MomentBody, momentId = momentId, text = body.decodeToString()))
            when (val sealed = sessions.seal(userId, payload)) {
                is AppResult.Success -> copies += MomentKeyCopyDto(momentId, userId, Base64.encode(sealed.value.wire))
                is AppResult.Failure -> return@withLock sealed
            }
        }
        AppResult.Success(copies)
    }

    /** [userId] has a new identity: they don't hold my moments key any more, so my next moment carries a copy. */
    suspend fun forgetHolder(userId: String) = deliveries.deleteDelivery(Scope, userId)

    suspend fun open(posterId: String, ciphertext: String): OpenedMoment = lock.withLock {
        val frame = runCatching { WireFormat.decode(Base64.decode(ciphertext)) }.getOrNull()
        if (frame == null || frame.type != WireFormat.Type.SenderKeyMessage) return@withLock OpenedMoment.Unreadable
        try {
            val distributionId = crypto.distributionIdOf(GroupBytes.Message, frame.body)
            val record = store.senderKey(Scope, posterId, distributionId) ?: return@withLock OpenedMoment.NoKey
            val out = crypto.groupDecrypt(PeerAddress(posterId), distributionId, record, frame.body)
            store.saveSenderKey(Scope, posterId, distributionId, out.record, now())
            OpenedMoment.Plain(out.plaintext)
        } catch (e: SignalException.NoSession) {
            OpenedMoment.NoKey
        } catch (e: SignalException) {
            OpenedMoment.Unreadable
        }
    }

    suspend fun forget() { store.deleteSenderKeys(Scope); deliveries.deleteDeliveries(Scope) }

    companion object {
        /** The sender-key scope for moments, next to the chats' ids in the same tables (no chat has this id). */
        const val Scope = "moments"
    }
}
