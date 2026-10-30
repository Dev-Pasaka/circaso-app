package com.circaso.core.crypto

/**
 * The Signal protocol (PQXDH, double ratchet, sender keys) as the shared code sees it. libsignal has no Kotlin
 * Multiplatform build: Android talks to its Java API, iOS to its Swift API through a bridge installed at launch.
 *
 * The bridge is **stateless**. Every call takes a snapshot of the records it needs and hands back the result plus the
 * records it changed; the shared code stores them (sealed, in Room) in one transaction per operation, under a lock per
 * peer or per chat. That keeps libsignal's blocking store callbacks out of the Swift <-> Kotlin boundary and keeps the
 * one source of truth for key material in common code. Records are libsignal's own serialisation, opaque here.
 *
 * Nothing in this file is logged, ever.
 */
interface SignalCrypto {
    // ---- keys this phone publishes (integration guide, encryption keys M1)

    fun generateIdentity(): OwnIdentity
    fun generateSignedPreKey(identity: OwnIdentity, id: Int, timestampMillis: Long): SignedPreKey
    fun generateKyberPreKey(identity: OwnIdentity, id: Int, timestampMillis: Long): KyberPreKey
    fun generatePreKeys(startId: Int, count: Int): List<OneTimePreKey>

    // ---- 1:1 sessions

    /** Starts a session from a fetched bundle (M3). The returned session replaces [current]. */
    fun startSession(identity: OwnIdentity, peer: PeerAddress, bundle: PeerBundle, current: ByteArray?): SessionStart

    fun encrypt(identity: OwnIdentity, peer: PeerAddress, session: ByteArray, plaintext: ByteArray): Encrypted

    /** Says what a received 1:1 ciphertext needs before it can be decrypted, without touching any state. */
    fun inspect(kind: CiphertextKind, ciphertext: ByteArray): CiphertextNeeds

    /**
     * Decrypts a 1:1 message. For a [CiphertextKind.PreKey] message [keys] must hold the records [inspect] asked for;
     * a used one-time prekey comes back in [Decrypted.consumedPreKeyId] so the caller deletes it.
     */
    fun decrypt(identity: OwnIdentity, peer: PeerAddress, session: ByteArray?, kind: CiphertextKind, ciphertext: ByteArray, keys: OwnKeys): Decrypted

    // ---- groups (sender keys)

    /** My sender key for a chat: a distribution message to hand to the members over 1:1 sessions, and the record to keep. */
    fun createSenderKeyDistribution(self: PeerAddress, distributionId: String, current: ByteArray?): SenderKeyDistribution

    /** Which sender key (its `distributionId`, a UUID string) a distribution message or a group ciphertext belongs to. */
    fun distributionIdOf(kind: GroupBytes, bytes: ByteArray): String

    /** Learns a member's sender key. [current] is the record already kept for that (sender, distributionId), if any. */
    fun processSenderKeyDistribution(sender: PeerAddress, distribution: ByteArray, current: ByteArray?): SenderKeyRecord
    fun groupEncrypt(self: PeerAddress, distributionId: String, record: ByteArray, plaintext: ByteArray): GroupEncrypted
    fun groupDecrypt(sender: PeerAddress, distributionId: String, record: ByteArray, ciphertext: ByteArray): GroupDecrypted

    // ---- helpers the protocol needs anyway (no second crypto library)

    /** The safety-number text two people compare (libsignal's numeric fingerprint, 60 digits). */
    fun fingerprint(localId: String, localIdentityKey: ByteArray, remoteId: String, remoteIdentityKey: ByteArray): String
    fun aesGcmSeal(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray
    fun aesGcmOpen(key: ByteArray, sealed: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray
    fun sha256(bytes: ByteArray): ByteArray
    fun randomBytes(count: Int): ByteArray
}

/** `(userId, deviceId)`: one phone per account, so the device id is always [PeerAddress.OnlyDevice]. */
data class PeerAddress(val userId: String, val deviceId: Int = OnlyDevice) {
    companion object { const val OnlyDevice = 1 }
}

/** This phone's long-term identity: the serialised key pair and the registration id libsignal wants on every message. */
class OwnIdentity(val keyPair: ByteArray, val publicKey: ByteArray, val registrationId: Int)

class SignedPreKey(val id: Int, val record: ByteArray, val publicKey: ByteArray, val signature: ByteArray)
class KyberPreKey(val id: Int, val record: ByteArray, val publicKey: ByteArray, val signature: ByteArray)
class OneTimePreKey(val id: Int, val record: ByteArray, val publicKey: ByteArray)

/** What `GET /v1/keys/{userId}` returns, decoded from base64. libsignal refuses a bundle without the Kyber prekey. */
class PeerBundle(
    val registrationId: Int,
    val identityKey: ByteArray,
    val signedPreKeyId: Int,
    val signedPreKey: ByteArray,
    val signedPreKeySignature: ByteArray,
    val kyberPreKeyId: Int,
    val kyberPreKey: ByteArray,
    val kyberPreKeySignature: ByteArray,
    val oneTimePreKeyId: Int?,
    val oneTimePreKey: ByteArray?,
)

class SessionStart(val session: ByteArray, val peerIdentityKey: ByteArray)

enum class CiphertextKind { PreKey, Whisper }

class Encrypted(val kind: CiphertextKind, val ciphertext: ByteArray, val session: ByteArray)

/**
 * The ids a PreKey message was built against; `null` means the message did not use that kind of key, or (for the
 * Kyber prekey, which libsignal's Java API does not expose) that every kept record must be passed to [SignalCrypto.decrypt].
 */
class CiphertextNeeds(val preKeyId: Int?, val signedPreKeyId: Int?, val kyberPreKeyId: Int?, val senderIdentityKey: ByteArray?)

/** The records a decrypt may need, loaded by id from the caller's store. Missing ones are simply absent. */
class OwnKeys(
    val preKeys: Map<Int, ByteArray> = emptyMap(),
    val signedPreKeys: Map<Int, ByteArray> = emptyMap(),
    val kyberPreKeys: Map<Int, ByteArray> = emptyMap(),
)

class Decrypted(
    val plaintext: ByteArray,
    val session: ByteArray,
    val peerIdentityKey: ByteArray,
    val consumedPreKeyId: Int?,
)

enum class GroupBytes { Distribution, Message }

class SenderKeyDistribution(val distribution: ByteArray, val record: ByteArray)
class SenderKeyRecord(val distributionId: String, val record: ByteArray)
class GroupEncrypted(val ciphertext: ByteArray, val record: ByteArray)
class GroupDecrypted(val plaintext: ByteArray, val record: ByteArray)

/** Why a cryptographic step failed, in terms the messaging layer can act on. Never carries key material. */
sealed class SignalException(message: String) : Exception(message) {
    /** The message was already decrypted once (a replay or a double delivery): ignore it. */
    class Duplicate : SignalException("duplicate message")
    /** No session with this peer, or the session cannot read this message: fetch a bundle and ask for a resend. */
    class NoSession : SignalException("no session")
    /** The peer's identity key is not the one we trust: handled through `keys.changed` (guide, section 7). */
    class UntrustedIdentity : SignalException("untrusted identity")
    /** A key the message needs is gone (a used one-time prekey, a retired signed prekey). */
    class MissingKey(val which: String) : SignalException("missing $which")
    /** Malformed bytes: not ours to guess at. */
    class Invalid : SignalException("invalid message")
}
