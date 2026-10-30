package com.circaso.feature.messaging.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.CiphertextKind
import com.circaso.core.crypto.GroupBytes
import com.circaso.core.crypto.OwnIdentity
import com.circaso.core.crypto.PeerAddress
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.crypto.SignalException
import com.circaso.core.crypto.WireFormat
import com.circaso.core.sync.nowMillis
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.local.GroupMetaKeyEntity
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.local.SenderKeyDeliveryEntity
import com.circaso.feature.messaging.data.remote.MessagesRemote
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.remote.RecipientDto
import com.circaso.feature.messaging.data.remote.SendChatMessageRequest
import com.circaso.feature.messaging.domain.KeysRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** What came out of a 1:1 decrypt. */
sealed interface Opened {
    class Plain(val bytes: ByteArray, val identityChanged: Boolean) : Opened
    data object Duplicate : Opened
    class Unreadable(val reason: String) : Opened
}

class Sealed(val wire: ByteArray, val identityChanged: Boolean)

/**
 * 1:1 sessions (PQXDH then double ratchet). One lock per peer: a session record is read, used and written back as
 * one step, never interleaved. Identity keys are trusted on first use; a change is reported, never silently accepted.
 */
class SessionCipher(
    private val store: SealedRecordStore,
    private val crypto: SignalCrypto,
    private val keys: KeysRepository,
    private val now: () -> Long = ::nowMillis,
) {
    private val locksGuard = Mutex()
    private val locks = mutableMapOf<String, Mutex>()

    private suspend fun lockFor(peerId: String) = locksGuard.withLock { locks.getOrPut(peerId) { Mutex() } }

    suspend fun identity(): OwnIdentity = store.identity() ?: crypto.generateIdentity().also { store.saveIdentity(it, now()) }

    /** Encrypts for one person, starting a session from their bundle when there is none. Returns the wire bytes. */
    suspend fun seal(peerId: String, plaintext: ByteArray): AppResult<Sealed> = lockFor(peerId).withLock {
        val identity = identity()
        val peer = PeerAddress(peerId)
        var identityChanged = false
        val session = store.session(peerId) ?: run {
            val fetched = when (val r = keys.peerBundle(peerId)) {
                is AppResult.Failure -> return r
                is AppResult.Success -> r.value
            }
            identityChanged = fetched.identityChanged
            try {
                crypto.startSession(identity, peer, fetched.bundle, null).session.also { store.saveSession(peerId, it, now()) }
            } catch (e: SignalException) {
                return AppResult.Failure(AppError.Unexpected(null))
            }
        }
        try {
            val out = crypto.encrypt(identity, peer, session, plaintext)
            store.saveSession(peerId, out.session, now())
            AppResult.Success(Sealed(WireFormat.encode(WireFormat.typeOf(out.kind), out.ciphertext), identityChanged))
        } catch (e: SignalException) {
            // The session cannot encrypt (corrupt or reset): drop it so the next try starts a fresh one.
            store.deleteSession(peerId)
            AppResult.Failure(AppError.Unexpected(null))
        }
    }

    /** Decrypts one message from a person; a used one-time prekey is deleted here, where it is known to be used. */
    suspend fun open(peerId: String, kind: CiphertextKind, ciphertext: ByteArray): Opened = lockFor(peerId).withLock {
        val identity = identity()
        val peer = PeerAddress(peerId)
        try {
            val result = crypto.decrypt(identity, peer, store.session(peerId), kind, ciphertext, store.ownKeys())
            store.saveSession(peerId, result.session, now())
            result.consumedPreKeyId?.let { store.consumePreKey(it) }
            Opened.Plain(result.plaintext, store.rememberPeerIdentity(peerId, result.peerIdentityKey, now()))
        } catch (e: SignalException.Duplicate) {
            Opened.Duplicate
        } catch (e: SignalException) {
            Opened.Unreadable(e.message ?: "invalid")
        }
    }

    /** `keys.changed`: their old session is dead; the next message to them starts a new one from their new bundle. */
    suspend fun reset(peerId: String) = lockFor(peerId).withLock { store.deleteSession(peerId) }

    suspend fun securityCode(myId: String, peerId: String): String? {
        val mine = store.identity() ?: return null
        val theirs = store.peerIdentity(peerId) ?: return null
        return runCatching { crypto.fingerprint(myId, mine.publicKey, peerId, theirs.identityKey) }.getOrNull()
    }
}

/** What came out of a group decrypt. */
sealed interface OpenedGroup {
    class Plain(val bytes: ByteArray) : OpenedGroup
    data object Duplicate : OpenedGroup
    /** The sender's key for this distribution has not arrived: keep the message and try again when it does. */
    data object NoSenderKey : OpenedGroup
    class Unreadable(val reason: String) : OpenedGroup
}

/** The group's sealed name and avatar, under the group metadata key. */
@Serializable
internal data class GroupMeta(val name: String, val avatar: String? = null)

/**
 * Groups: sender keys (one per member per chat, rotated when someone leaves) and the metadata key the name is sealed
 * under. My sender key and the current metadata key travel to each member over their 1:1 session as a `SENDER_KEY`
 * message; which members already have which key is remembered, so each is sent once.
 */
class GroupKeys(
    private val store: SealedRecordStore,
    private val crypto: SignalCrypto,
    private val chats: ChatsDao,
    private val sessions: SessionCipher,
    private val remote: MessagesRemote,
    private val json: Json,
    private val ownUserId: suspend () -> String?,
    private val now: () -> Long = ::nowMillis,
) {
    private val lock = Mutex()

    // ---- metadata key

    suspend fun newMetaKey(): ByteArray = crypto.randomBytes(32)

    suspend fun rememberMetaKey(chatId: String, metaVersion: Int, key: ByteArray) =
        chats.upsertMetaKey(GroupMetaKeyEntity(chatId, metaVersion, store.sealBytes(key, "meta:$chatId:$metaVersion"), now()))

    suspend fun latestMetaKey(chatId: String): ByteArray? = chats.latestMetaKey(chatId)?.let { store.openBytes(it.key, "meta:$chatId:${it.metaVersion}") }

    fun sealMeta(key: ByteArray, name: String): String =
        Base64.encode(crypto.aesGcmSeal(key, json.encodeToString(GroupMeta.serializer(), GroupMeta(name)).encodeToByteArray(), MetaAad))

    /** The group's name, or null when this phone has no key for that version yet (it arrives with the sender key). */
    suspend fun openMeta(chatId: String, sealedMeta: String?, metaVersion: Int): String? {
        if (sealedMeta == null) return null
        val row = chats.metaKey(chatId, metaVersion) ?: chats.latestMetaKey(chatId) ?: return null
        val key = store.openBytes(row.key, "meta:$chatId:${row.metaVersion}")
        return runCatching { json.decodeFromString(GroupMeta.serializer(), crypto.aesGcmOpen(key, Base64.decode(sealedMeta), MetaAad).decodeToString()).name }.getOrNull()
    }

    // ---- my sender key

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun mySenderKey(chatId: String, me: String): Pair<String, ByteArray> =
        store.latestSenderKey(chatId, me) ?: run {
            val distributionId = Uuid.random().toString()
            val created = crypto.createSenderKeyDistribution(PeerAddress(me), distributionId, null)
            store.saveSenderKey(chatId, me, distributionId, created.record, now())
            distributionId to created.record
        }

    /** After someone leaves or is removed: a new sender key, which the next send hands to the remaining members. */
    suspend fun rotateMySenderKey(chatId: String) = lock.withLock {
        val me = ownUserId() ?: return@withLock
        @OptIn(ExperimentalUuidApi::class)
        val distributionId = Uuid.random().toString()
        val created = crypto.createSenderKeyDistribution(PeerAddress(me), distributionId, null)
        store.saveSenderKey(chatId, me, distributionId, created.record, now())
        store.deleteOtherSenderKeys(chatId, me, distributionId)
        chats.deleteDeliveries(chatId)
    }

    /**
     * Makes sure every member in [memberIds] holds my current sender key and the latest metadata key. Sends one
     * `SENDER_KEY` message with a 1:1 copy per member still missing it. Online-only; a failure is returned, not queued.
     */
    suspend fun ensureDistributed(chatId: String, memberIds: List<String>): AppResult<Unit> = lock.withLock {
        val me = ownUserId() ?: return@withLock AppResult.Failure(AppError.Unexpected(null))
        val (distributionId, record) = mySenderKey(chatId, me)
        val have = chats.deliveredTo(chatId, distributionId).toSet()
        val missing = memberIds.filter { it != me && it !in have }
        if (missing.isEmpty()) return@withLock AppResult.Success(Unit)
        val distribution = crypto.createSenderKeyDistribution(PeerAddress(me), distributionId, record)
        store.saveSenderKey(chatId, me, distributionId, distribution.record, now())
        val metaRow = chats.latestMetaKey(chatId)
        val payload = ChatPayload(
            type = ChatPayload.SenderKey,
            senderKey = Base64.encode(distribution.distribution),
            metaKey = metaRow?.let { Base64.encode(store.openBytes(it.key, "meta:$chatId:${it.metaVersion}")) },
            metaVersion = metaRow?.metaVersion,
        )
        val plaintext = ChatPayload.encode(payload)
        val recipients = mutableListOf<RecipientDto>()
        val reached = mutableListOf<String>()
        for (member in missing) {
            when (val sealed = sessions.seal(member, plaintext)) {
                is AppResult.Success -> { recipients += RecipientDto(member, Base64.encode(sealed.value.wire)); reached += member }
                is AppResult.Failure -> if (sealed.error is AppError.Network) return@withLock sealed // someone unreachable or without keys is skipped; they get it on the next send
            }
        }
        if (recipients.isEmpty()) return@withLock AppResult.Success(Unit)
        @OptIn(ExperimentalUuidApi::class)
        val request = SendChatMessageRequest(clientMessageId = Uuid.random().toString(), kind = MessagingWire.KindSenderKey, recipients = recipients, pushable = false)
        when (val sent = remote.send(chatId, request, request.clientMessageId)) {
            is AppResult.Success -> { chats.insertDeliveries(reached.map { SenderKeyDeliveryEntity(chatId, it, distributionId, now()) }); AppResult.Success(Unit) }
            is AppResult.Failure -> sent
        }
    }

    suspend fun encryptForGroup(chatId: String, plaintext: ByteArray): AppResult<ByteArray> = lock.withLock {
        val me = ownUserId() ?: return@withLock AppResult.Failure(AppError.Unexpected(null))
        val (distributionId, record) = mySenderKey(chatId, me)
        try {
            val out = crypto.groupEncrypt(PeerAddress(me), distributionId, record, plaintext)
            store.saveSenderKey(chatId, me, distributionId, out.record, now())
            AppResult.Success(WireFormat.encode(WireFormat.Type.SenderKeyMessage, out.ciphertext))
        } catch (e: SignalException) {
            AppResult.Failure(AppError.Unexpected(null))
        }
    }

    // ---- their keys

    /** A member's sender key (and the metadata key) that arrived over the 1:1 session. */
    suspend fun processDistribution(chatId: String, senderId: String, payload: ChatPayload) = lock.withLock {
        payload.senderKey?.let { encoded ->
            val distribution = Base64.decode(encoded)
            val distributionId = crypto.distributionIdOf(GroupBytes.Distribution, distribution)
            val current = store.senderKey(chatId, senderId, distributionId)
            val learned = crypto.processSenderKeyDistribution(PeerAddress(senderId), distribution, current)
            store.saveSenderKey(chatId, senderId, learned.distributionId, learned.record, now())
        }
        val metaKey = payload.metaKey
        val metaVersion = payload.metaVersion
        if (metaKey != null && metaVersion != null) rememberMetaKey(chatId, metaVersion, Base64.decode(metaKey))
    }

    suspend fun openFromGroup(chatId: String, senderId: String, ciphertext: ByteArray): OpenedGroup = lock.withLock {
        try {
            val distributionId = crypto.distributionIdOf(GroupBytes.Message, ciphertext)
            val record = store.senderKey(chatId, senderId, distributionId) ?: return@withLock OpenedGroup.NoSenderKey
            val out = crypto.groupDecrypt(PeerAddress(senderId), distributionId, record, ciphertext)
            store.saveSenderKey(chatId, senderId, distributionId, out.record, now())
            OpenedGroup.Plain(out.plaintext)
        } catch (e: SignalException.Duplicate) {
            OpenedGroup.Duplicate
        } catch (e: SignalException.NoSession) {
            OpenedGroup.NoSenderKey
        } catch (e: SignalException) {
            OpenedGroup.Unreadable(e.message ?: "invalid")
        }
    }

    suspend fun forget(chatId: String) { store.deleteSenderKeys(chatId); chats.deleteDeliveries(chatId) }

    private companion object {
        val MetaAad = "circaso.group-meta.v1".encodeToByteArray()
    }
}
