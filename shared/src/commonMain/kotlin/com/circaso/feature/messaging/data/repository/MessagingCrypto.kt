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
import com.circaso.feature.messaging.data.remote.InviteKeyDto
import com.circaso.feature.messaging.data.remote.MessagesRemote
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.remote.RecipientDto
import com.circaso.feature.messaging.data.remote.SendChatMessageRequest
import com.circaso.feature.messaging.domain.KeysRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    /**
     * Someone's identity changed (a new phone): whatever I handed their old identity is gone with it. Moments use it
     * to forget that they hold my moments key, so my next moment carries a fresh copy.
     */
    private val identityChanged: suspend (peerId: String) -> Unit = {},
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
            if (identityChanged) identityChanged(peerId)
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
            val changed = store.rememberPeerIdentity(peerId, result.peerIdentityKey, now())
            if (changed) identityChanged(peerId)
            Opened.Plain(result.plaintext, changed)
        } catch (e: SignalException.Duplicate) {
            Opened.Duplicate
        } catch (e: SignalException) {
            Opened.Unreadable(e.message ?: "invalid")
        }
    }

    /** `keys.changed`: their old session is dead; the next message to them starts a new one from their new bundle. */
    suspend fun reset(peerId: String) = lockFor(peerId).withLock { store.deleteSession(peerId); identityChanged(peerId) }

    /**
     * Makes sure my session with [peerId] is with their **current** identity, for when they say they can't read me
     * (a moments key request) and `keys.changed` may not have reached this phone. With no session there is nothing
     * to do: the next [seal] starts one from their bundle. Otherwise their bundle is fetched; if the identity in it
     * is new, the old session is dropped and a new one started from that bundle. True when it changed.
     */
    suspend fun refresh(peerId: String): AppResult<Boolean> = lockFor(peerId).withLock {
        if (store.session(peerId) == null) return AppResult.Success(false)
        val fetched = when (val r = keys.peerBundle(peerId)) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.value
        }
        if (!fetched.identityChanged) return AppResult.Success(false)
        store.deleteSession(peerId)
        identityChanged(peerId)
        try {
            store.saveSession(peerId, crypto.startSession(identity(), PeerAddress(peerId), fetched.bundle, null).session, now())
        } catch (e: SignalException) {
            // No session saved: the next seal fetches a bundle and tries again.
        }
        AppResult.Success(true)
    }

    /**
     * Throws my session with [peerId] away and starts a new one from their **current** bundle, whatever the identity
     * in it: for when they say they couldn't open one of my messages (a retry receipt). [refresh] keeps a session whose
     * identity is unchanged, but a session can break without an identity change (their phone lost its half, a bad mac),
     * and resending on it would fail the same way. True when their identity changed. A failed fetch keeps the old one.
     */
    suspend fun restart(peerId: String): AppResult<Boolean> = lockFor(peerId).withLock {
        val fetched = when (val r = keys.peerBundle(peerId)) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.value
        }
        store.deleteSession(peerId)
        if (fetched.identityChanged) identityChanged(peerId)
        try {
            store.saveSession(peerId, crypto.startSession(identity(), PeerAddress(peerId), fetched.bundle, null).session, now())
        } catch (e: SignalException) {
            // No session saved: the next seal fetches a bundle and tries again.
        }
        AppResult.Success(fetched.identityChanged)
    }

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

/**
 * The group's sealed name and photo, under the group metadata key. [avatar] is an older, never-used field kept so the
 * shape stays readable both ways; [photo] points at the encrypted photo and carries its thumbnail ([GroupPhotos]).
 */
@Serializable
internal data class GroupMeta(val name: String, val avatar: String? = null, val photo: GroupPhotoRef? = null)

/** The server's limit for `sealedMeta`, decoded (guide M5: at most 4 KB). */
internal const val MaxSealedMetaBytes = 4 * 1024

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
    /**
     * Makes sure this phone's own encryption keys are on the server (publishing them if not) before members are asked
     * for a group's key. A member answers over the 1:1 session, sealed for whatever bundle the server has; right after
     * a sign-in on a new phone that may still be the old phone's, which this one could never open (2026-11-09: the
     * first ask on Pasaka's new phone left 0.8 s before its keys did).
     */
    private val keysPublished: suspend () -> Boolean = { true },
) {
    private val lock = Mutex()

    // ---- metadata key

    suspend fun newMetaKey(): ByteArray = crypto.randomBytes(32)

    suspend fun rememberMetaKey(chatId: String, metaVersion: Int, key: ByteArray) =
        chats.upsertMetaKey(GroupMetaKeyEntity(chatId, metaVersion, store.sealBytes(key, "meta:$chatId:$metaVersion"), now()))

    suspend fun latestMetaKey(chatId: String): ByteArray? = chats.latestMetaKey(chatId)?.let { store.openBytes(it.key, "meta:$chatId:${it.metaVersion}") }

    fun sealMeta(key: ByteArray, name: String, photo: GroupPhotoRef? = null): String =
        Base64.encode(crypto.aesGcmSeal(key, json.encodeToString(GroupMeta.serializer(), GroupMeta(name, photo = photo)).encodeToByteArray(), MetaAad))

    /**
     * [sealMeta] kept within the server's 4 KB: with the photo's thumbnail when it fits, else without it (the photo
     * still loads, just not instantly). Null when even the name alone is too long.
     */
    fun sealMetaWithin(key: ByteArray, name: String, photo: GroupPhotoRef?): String? {
        fun fits(sealed: String) = Base64.decode(sealed).size <= MaxSealedMetaBytes
        sealMeta(key, name, photo).takeIf(::fits)?.let { return it }
        if (photo?.thumb != null) sealMeta(key, name, photo.copy(thumb = null)).takeIf(::fits)?.let { return it }
        return sealMeta(key, name).takeIf(::fits)
    }

    /** The group's name, or null when this phone has no key for that version yet (it arrives with the sender key). */
    suspend fun openMeta(chatId: String, sealedMeta: String?, metaVersion: Int): String? = openGroupMeta(chatId, sealedMeta, metaVersion)?.name

    private val opened = mutableMapOf<String, GroupMeta>()
    private val openedLock = Mutex()

    /**
     * The whole sealed metadata (name and photo), remembered per sealed value so a busy chat list doesn't decrypt it
     * on every redraw. A failure (no key yet) is not remembered: it is tried again once the key arrives.
     */
    internal suspend fun openGroupMeta(chatId: String, sealedMeta: String?, metaVersion: Int): GroupMeta? {
        if (sealedMeta == null) return null
        val cacheKey = "$chatId:$metaVersion:${sealedMeta.hashCode()}:${sealedMeta.length}"
        openedLock.withLock { opened[cacheKey] }?.let { return it }
        val rows = listOfNotNull(chats.metaKey(chatId, metaVersion), chats.latestMetaKey(chatId)).distinctBy { it.metaVersion }
        for (row in rows) {
            val key = store.openBytes(row.key, "meta:$chatId:${row.metaVersion}")
            val meta = runCatching { json.decodeFromString(GroupMeta.serializer(), crypto.aesGcmOpen(key, Base64.decode(sealedMeta), MetaAad).decodeToString()) }.getOrNull()
            if (meta != null) {
                openedLock.withLock { if (opened.size > 512) opened.clear(); opened[cacheKey] = meta }
                return meta
            }
        }
        return null
    }

    /** True when [key] opens [sealedMeta]: a key that doesn't is never kept as that version's. */
    private fun opens(key: ByteArray, sealedMeta: String): Boolean =
        runCatching { crypto.aesGcmOpen(key, Base64.decode(sealedMeta), MetaAad) }.isSuccess

    // ---- a group being created: its key is on disk before the request leaves

    /**
     * The key for the create attempt [idempotencyKey]: the one already kept for it (a retry must seal under the same
     * key, since the server answers a retry with the group the first attempt made), else a new one, kept before it is
     * used. Kept on disk, not in memory: a create whose answer is lost (a timeout, a dropped connection, the screen
     * closed, the app killed) still made the group, and without this the creator's phone could never read its name.
     */
    suspend fun keyForCreate(idempotencyKey: String): ByteArray {
        val chatId = PendingPrefix + idempotencyKey
        chats.metaKey(chatId, 0)?.let { return store.openBytes(it.key, "meta:$chatId:0") }
        chats.deletePendingMetaKeysBefore(now() - PendingKeepMillis)
        val key = newMetaKey()
        chats.upsertMetaKey(GroupMetaKeyEntity(chatId, 0, store.sealBytes(key, "meta:$chatId:0"), now()))
        return key
    }

    /** The create [idempotencyKey] made [chatId]: its key becomes the group's own. */
    suspend fun createdWith(idempotencyKey: String, chatId: String, metaVersion: Int, key: ByteArray) {
        rememberMetaKey(chatId, metaVersion, key)
        chats.deleteMetaKeys(PendingPrefix + idempotencyKey)
    }

    /**
     * A group whose name this phone can't open may be one it created without hearing back: tries the keys of creates
     * still waiting, and keeps the one that opens it as the group's. True when one did.
     */
    suspend fun adoptPendingKey(chatId: String, sealedMeta: String?, metaVersion: Int): Boolean {
        if (sealedMeta == null) return false
        for (row in chats.pendingMetaKeys()) {
            val key = store.openBytes(row.key, "meta:${row.chatId}:0")
            if (!opens(key, sealedMeta)) continue
            rememberMetaKey(chatId, metaVersion, key)
            chats.deleteMetaKeys(row.chatId)
            return true
        }
        return false
    }

    // ---- a lost metadata key: ask the members, who hand it back over the 1:1 session

    /** [reached]: the members whose copy the server took; [noKey]: those who answered that they have none either. */
    private class Ask(val first: Long, var last: Long, val reached: MutableSet<String> = mutableSetOf(), val noKey: MutableSet<String> = mutableSetOf())
    private val asks = mutableMapOf<String, Ask>()
    private val answered = mutableMapOf<String, Long>()
    private val askLock = Mutex()

    private val _unavailable = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Groups whose key no member this phone asked has either: every one answered with [ChatPayload.MetaKeyNone]. Their
     * name stays unknown here until an admin names the group again (a new key). Groups not in it are still waiting.
     */
    val unavailable: StateFlow<Set<String>> = _unavailable.asStateFlow()

    /** When this phone first asked for [chatId]'s key (this run), or null if it hasn't reached anyone yet. */
    suspend fun askedSince(chatId: String): Long? = askLock.withLock { asks[chatId]?.first }

    /**
     * Asks the other members of [chatId] for the metadata key, as Moments' new phone asks posters for theirs: one
     * `SENDER_KEY` message with a 1:1 copy for each ([members] pairs a user id with whether they are an admin; admins
     * are asked first, at most [AskAtMost] people), marked as a key request so the server wakes an offline member's
     * phone with a silent push. At most once every [AskEveryMillis] per group, and only once this phone's own keys are
     * on the server ([keysPublished]; until then a [AppError.Network] failure, so the caller tries again later).
     * Online-only: a failure is returned and not counted as an ask.
     */
    suspend fun askForMetaKey(chatId: String, members: List<Pair<String, Boolean>>, metaVersion: Int): AppResult<Unit> = askLock.withLock {
        val me = ownUserId() ?: return@withLock AppResult.Failure(AppError.Unexpected(null))
        val t = now()
        asks[chatId]?.let { if (t - it.last < AskEveryMillis) return@withLock AppResult.Success(Unit) }
        if (!keysPublished()) return@withLock AppResult.Failure(AppError.Network)
        val others = members.filter { it.first != me }.sortedByDescending { it.second }.map { it.first }.take(AskAtMost)
        val plaintext = ChatPayload.encode(ChatPayload(type = ChatPayload.MetaKeyRequest, metaVersion = metaVersion))
        val reached = when (val sent = sendOneToOne(chatId, others, plaintext, keyRequest = true)) {
            is AppResult.Failure -> return@withLock sent
            is AppResult.Success -> sent.value
        }
        // Asked (or there is no one who could answer): the wait before an admin may give the group a new key starts now.
        val ask = asks[chatId]?.also { it.last = t } ?: Ask(t, t).also { asks[chatId] = it }
        ask.reached += reached
        AppResult.Success(Unit)
    }

    /**
     * [requesterId] has no key for [chatId]'s name and asked me. Answered 1:1 with my newest key when they are still a
     * member and I hold one, or with [ChatPayload.MetaKeyNone] when I hold none (my phone is new too), so their phone
     * stops waiting on mine; at most once a minute per person and group. Nothing is answered to me or a stranger.
     */
    suspend fun answerMetaKeyRequest(chatId: String, requesterId: String) = askLock.withLock {
        val me = ownUserId() ?: return@withLock
        if (requesterId == me) return@withLock
        val chat = chats.chat(chatId) ?: return@withLock
        if (chat.left || chat.kind != MessagingWire.Group) return@withLock
        if (chats.members(chatId).none { it.userId == requesterId }) return@withLock
        val t = now()
        val seen = "$chatId:$requesterId"
        answered[seen]?.let { if (t - it < AnswerEveryMillis) return@withLock }
        val row = chats.latestMetaKey(chatId)
        val payload = if (row != null) metaKeyPayload(chatId, row) else ChatPayload(type = ChatPayload.MetaKeyNone, metaVersion = chat.metaVersion)
        if (sendOneToOne(chatId, listOf(requesterId), ChatPayload.encode(payload)) is AppResult.Success) answered[seen] = t
    }

    /**
     * [senderId] answered my ask for [chatId]'s key: their phone has none either. Once every member the ask reached
     * has said so, the group is [unavailable]. An answer to an ask this run never made is ignored.
     */
    suspend fun onNoKey(chatId: String, senderId: String) = askLock.withLock {
        val ask = asks[chatId] ?: return@withLock
        if (senderId !in ask.reached) return@withLock
        ask.noKey += senderId
        if (ask.noKey.containsAll(ask.reached)) _unavailable.update { it + chatId }
    }

    /**
     * Hands the newest metadata key to [memberIds] (except me), whether or not they already hold my sender key: after a
     * new key replaced one that was lost, every member needs it, and [ensureDistributed] only reaches those missing my
     * sender key. Best effort: a member it misses can ask, like any phone without the key.
     */
    suspend fun distributeMetaKey(chatId: String, memberIds: List<String>): AppResult<Unit> {
        val me = ownUserId() ?: return AppResult.Failure(AppError.Unexpected(null))
        val row = chats.latestMetaKey(chatId) ?: return AppResult.Failure(AppError.Unexpected(null))
        return when (val sent = sendOneToOne(chatId, memberIds.filter { it != me }, ChatPayload.encode(metaKeyPayload(chatId, row)))) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> sent
        }
    }

    // ---- group invites: the name and photo shown before someone joins (backend PR #83)

    /** The group's newest metadata key and its version, for sealing it into invites (M9). */
    suspend fun latestMetaKeyVersioned(chatId: String): Pair<Int, ByteArray>? =
        chats.latestMetaKey(chatId)?.let { it.metaVersion to store.openBytes(it.key, "meta:$chatId:${it.metaVersion}") }

    /**
     * [key] (the group's metadata key at [metaVersion]) sealed 1:1 for each of [userIds] as a `metaKey` payload, the
     * same one a member hands a new phone, so the invite each receives can show the group's name and photo. Best
     * effort and bounded: someone it can't seal for (no keys yet, or offline) is left out and sees "a group" until
     * they join; at most [InviteKeysAtMost] people, so a big add doesn't wait on dozens of bundle fetches.
     */
    suspend fun sealInviteKeys(key: ByteArray, metaVersion: Int, userIds: List<String>): List<InviteKeyDto> {
        val me = ownUserId()
        val plaintext = ChatPayload.encode(ChatPayload(type = ChatPayload.MetaKey, metaKey = Base64.encode(key), metaVersion = metaVersion))
        val sealed = mutableListOf<InviteKeyDto>()
        for (userId in userIds.filter { it != me }.distinct().take(InviteKeysAtMost)) {
            when (val s = sessions.seal(userId, plaintext)) {
                is AppResult.Success -> sealed += InviteKeyDto(userId, Base64.encode(s.value.wire))
                is AppResult.Failure -> if (s.error is AppError.Network) break
            }
        }
        return sealed
    }

    /**
     * Opens the key [inviterId] sealed for me in an invite to [chatId] and keeps it as that group's, when it opens the
     * invite's [sealedMeta] (a key that doesn't is never kept). Once kept, the group's name and photo open like any
     * group's, and a second look at the same invite (a duplicate to the session) needs nothing more. True when this
     * phone now holds a key that opens [sealedMeta].
     */
    suspend fun openInviteKey(chatId: String, inviterId: String, sealedKey: String?, sealedMeta: String?, metaVersion: Int): Boolean {
        if (sealedMeta == null) return false
        if (openGroupMeta(chatId, sealedMeta, metaVersion) != null) return true
        val wire = sealedKey?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return false
        val frame = WireFormat.decode(wire) ?: return false
        val kind = when (frame.type) {
            WireFormat.Type.PreKeyMessage -> com.circaso.core.crypto.CiphertextKind.PreKey
            WireFormat.Type.WhisperMessage -> com.circaso.core.crypto.CiphertextKind.Whisper
            else -> return false
        }
        val opened = sessions.open(inviterId, kind, frame.body) as? Opened.Plain ?: return false
        val payload = ChatPayload.decode(opened.bytes) ?: return false
        val key = payload.metaKey?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return false
        // Kept under the version it opens now: the group may have been named again since the invite was sealed.
        if (!opens(key, sealedMeta)) return false
        rememberMetaKey(chatId, metaVersion, key)
        return true
    }

    private suspend fun metaKeyPayload(chatId: String, row: GroupMetaKeyEntity) = ChatPayload(
        type = ChatPayload.MetaKey,
        metaKey = Base64.encode(store.openBytes(row.key, "meta:$chatId:${row.metaVersion}")),
        metaVersion = row.metaVersion,
    )

    /**
     * [plaintext] to each of [userIds] over their 1:1 session, as one `SENDER_KEY` message (the kind the server lets
     * go to some members of a group); [keyRequest] when it asks for the group's key. Someone without keys is skipped;
     * a network failure stops it. The members it reached.
     */
    private suspend fun sendOneToOne(chatId: String, userIds: List<String>, plaintext: ByteArray, keyRequest: Boolean = false): AppResult<List<String>> {
        val recipients = mutableListOf<RecipientDto>()
        for (member in userIds) {
            when (val sealed = sessions.seal(member, plaintext)) {
                is AppResult.Success -> recipients += RecipientDto(member, Base64.encode(sealed.value.wire))
                is AppResult.Failure -> if (sealed.error is AppError.Network) return sealed
            }
        }
        if (recipients.isEmpty()) return AppResult.Success(emptyList())
        @OptIn(ExperimentalUuidApi::class)
        val request = SendChatMessageRequest(clientMessageId = Uuid.random().toString(), kind = MessagingWire.KindSenderKey, recipients = recipients, pushable = false, keyRequest = keyRequest)
        return when (val sent = remote.send(chatId, request, request.clientMessageId)) {
            is AppResult.Success -> AppResult.Success(recipients.map { it.userId })
            is AppResult.Failure -> sent
        }
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
        if (metaKey != null && metaVersion != null) {
            val key = Base64.decode(metaKey)
            // A key for the version this phone holds must open it; one for a version not seen yet can't be checked here.
            val chat = chats.chat(chatId)
            val sealed = chat?.sealedMeta
            if (sealed != null && chat.metaVersion == metaVersion && !opens(key, sealed)) return@withLock
            rememberMetaKey(chatId, metaVersion, key)
            // A key came after all (an admin named the group again, or a member who had it answered late).
            _unavailable.update { it - chatId }
        }
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

    companion object {
        private val MetaAad = "circaso.group-meta.v1".encodeToByteArray()
        /** Where a create attempt's key waits (`group_meta_key.chatId`) until the group it made is known. */
        const val PendingPrefix = "pending:"
        /** A create attempt's key is kept this long; a group it made turns up in the list long before. */
        const val PendingKeepMillis = 30L * 24 * 60 * 60 * 1000
        /** One ask per group this often at most. */
        const val AskEveryMillis = 10L * 60 * 1000
        /** How many members are asked at once (admins first): enough that someone online answers. */
        const val AskAtMost = 20
        /** One answer per person and group this often at most. */
        const val AnswerEveryMillis = 60_000L
        /** The most people one create or add seals the group's key for (see [sealInviteKeys]). */
        const val InviteKeysAtMost = 64
    }
}
