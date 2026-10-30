package com.circaso.core.crypto

import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.InvalidSessionException
import org.signal.libsignal.protocol.InvalidVersionException
import org.signal.libsignal.protocol.LegacyMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord as SignalSenderKeyRecord
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SenderKeyMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [SignalCrypto] on libsignal's Java API. Every call builds libsignal's in-memory store from the records it was given,
 * runs one operation, and reads the changed records back out; nothing is kept between calls.
 */
class AndroidSignalCrypto : SignalCrypto {
    private val random = SecureRandom()

    // ---- keys

    override fun generateIdentity(): OwnIdentity {
        val pair = IdentityKeyPair.generate()
        return OwnIdentity(pair.serialize(), pair.publicKey.serialize(), KeyHelper.generateRegistrationId(false))
    }

    override fun generateSignedPreKey(identity: OwnIdentity, id: Int, timestampMillis: Long): SignedPreKey {
        val pair = ECKeyPair.generate()
        val signature = IdentityKeyPair(identity.keyPair).privateKey.calculateSignature(pair.publicKey.serialize())
        return SignedPreKey(id, SignedPreKeyRecord(id, timestampMillis, pair, signature).serialize(), pair.publicKey.serialize(), signature)
    }

    override fun generateKyberPreKey(identity: OwnIdentity, id: Int, timestampMillis: Long): KyberPreKey {
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = IdentityKeyPair(identity.keyPair).privateKey.calculateSignature(pair.publicKey.serialize())
        return KyberPreKey(id, KyberPreKeyRecord(id, timestampMillis, pair, signature).serialize(), pair.publicKey.serialize(), signature)
    }

    override fun generatePreKeys(startId: Int, count: Int): List<OneTimePreKey> = List(count) { i ->
        val id = startId + i
        val pair = ECKeyPair.generate()
        OneTimePreKey(id, PreKeyRecord(id, pair).serialize(), pair.publicKey.serialize())
    }

    // ---- 1:1

    override fun startSession(identity: OwnIdentity, peer: PeerAddress, bundle: PeerBundle, current: ByteArray?): SessionStart = guarded {
        val store = store(identity)
        val remote = peer.address()
        current?.let { store.storeSession(remote, SessionRecord(it)) }
        val signalBundle = PreKeyBundle(
            bundle.registrationId,
            peer.deviceId,
            bundle.oneTimePreKeyId ?: NoPreKeyId,
            bundle.oneTimePreKey?.let { ECPublicKey(it) },
            bundle.signedPreKeyId,
            ECPublicKey(bundle.signedPreKey),
            bundle.signedPreKeySignature,
            IdentityKey(bundle.identityKey),
            bundle.kyberPreKeyId,
            KEMPublicKey(bundle.kyberPreKey),
            bundle.kyberPreKeySignature,
        )
        SessionBuilder(store, remote, identity.selfAddress()).process(signalBundle)
        SessionStart(store.loadSession(remote).serialize(), bundle.identityKey)
    }

    override fun encrypt(identity: OwnIdentity, peer: PeerAddress, session: ByteArray, plaintext: ByteArray): Encrypted = guarded {
        val store = store(identity)
        val remote = peer.address()
        store.storeSession(remote, SessionRecord(session))
        val message = SessionCipher(store, identity.selfAddress(), remote).encrypt(plaintext)
        val kind = if (message.type == CiphertextMessage.PREKEY_TYPE) CiphertextKind.PreKey else CiphertextKind.Whisper
        Encrypted(kind, message.serialize(), store.loadSession(remote).serialize())
    }

    override fun inspect(kind: CiphertextKind, ciphertext: ByteArray): CiphertextNeeds = guarded {
        when (kind) {
            CiphertextKind.Whisper -> { SignalMessage(ciphertext); CiphertextNeeds(null, null, null, null) }
            CiphertextKind.PreKey -> {
                // libsignal 0.106 does not expose the Kyber prekey id of a message: the caller hands over every Kyber
                // record it still keeps (the current one and any retired within 30 days), which is a handful.
                val m = PreKeySignalMessage(ciphertext)
                CiphertextNeeds(m.preKeyId.orElse(null), m.signedPreKeyId, null, m.identityKey.serialize())
            }
        }
    }

    override fun decrypt(identity: OwnIdentity, peer: PeerAddress, session: ByteArray?, kind: CiphertextKind, ciphertext: ByteArray, keys: OwnKeys): Decrypted = guarded {
        val store = store(identity)
        val remote = peer.address()
        session?.let { store.storeSession(remote, SessionRecord(it)) }
        keys.preKeys.forEach { (id, bytes) -> store.storePreKey(id, PreKeyRecord(bytes)) }
        keys.signedPreKeys.forEach { (id, bytes) -> store.storeSignedPreKey(id, SignedPreKeyRecord(bytes)) }
        keys.kyberPreKeys.forEach { (id, bytes) -> store.storeKyberPreKey(id, KyberPreKeyRecord(bytes)) }
        val cipher = SessionCipher(store, identity.selfAddress(), remote)
        val (plaintext, consumed) = when (kind) {
            CiphertextKind.Whisper -> {
                if (session == null) throw SignalException.NoSession()
                cipher.decrypt(SignalMessage(ciphertext)) to null
            }
            CiphertextKind.PreKey -> {
                val m = PreKeySignalMessage(ciphertext)
                val plain = cipher.decrypt(m)
                // libsignal removes a one-time prekey from the store once it has been used.
                val used = m.preKeyId.orElse(null)?.takeIf { !store.containsPreKey(it) }
                plain to used
            }
        }
        val updated = store.loadSession(remote)
        Decrypted(plaintext, updated.serialize(), updated.remoteIdentityKey.serialize(), consumed)
    }

    // ---- groups

    override fun createSenderKeyDistribution(self: PeerAddress, distributionId: String, current: ByteArray?): SenderKeyDistribution = guarded {
        val store = InMemorySenderKeys()
        val address = self.address()
        val uuid = UUID.fromString(distributionId)
        current?.let { store.storeSenderKey(address, uuid, SignalSenderKeyRecord(it)) }
        val distribution = GroupSessionBuilder(store).create(address, uuid)
        SenderKeyDistribution(distribution.serialize(), store.loadSenderKey(address, uuid)!!.serialize())
    }

    override fun distributionIdOf(kind: GroupBytes, bytes: ByteArray): String = guarded {
        when (kind) {
            GroupBytes.Distribution -> SenderKeyDistributionMessage(bytes).distributionId.toString()
            GroupBytes.Message -> SenderKeyMessage(bytes).distributionId.toString()
        }
    }

    override fun processSenderKeyDistribution(sender: PeerAddress, distribution: ByteArray, current: ByteArray?): SenderKeyRecord = guarded {
        val store = InMemorySenderKeys()
        val address = sender.address()
        val message = SenderKeyDistributionMessage(distribution)
        current?.let { store.storeSenderKey(address, message.distributionId, SignalSenderKeyRecord(it)) }
        GroupSessionBuilder(store).process(address, message)
        SenderKeyRecord(message.distributionId.toString(), store.loadSenderKey(address, message.distributionId)!!.serialize())
    }

    override fun groupEncrypt(self: PeerAddress, distributionId: String, record: ByteArray, plaintext: ByteArray): GroupEncrypted = guarded {
        val store = InMemorySenderKeys()
        val address = self.address()
        val uuid = UUID.fromString(distributionId)
        store.storeSenderKey(address, uuid, SignalSenderKeyRecord(record))
        val message = GroupCipher(store, address).encrypt(uuid, plaintext)
        GroupEncrypted(message.serialize(), store.loadSenderKey(address, uuid)!!.serialize())
    }

    override fun groupDecrypt(sender: PeerAddress, distributionId: String, record: ByteArray, ciphertext: ByteArray): GroupDecrypted = guarded {
        val store = InMemorySenderKeys()
        val address = sender.address()
        val uuid = UUID.fromString(distributionId)
        store.storeSenderKey(address, uuid, SignalSenderKeyRecord(record))
        val plaintext = GroupCipher(store, address).decrypt(ciphertext)
        GroupDecrypted(plaintext, store.loadSenderKey(address, uuid)!!.serialize())
    }

    // ---- helpers

    override fun fingerprint(localId: String, localIdentityKey: ByteArray, remoteId: String, remoteIdentityKey: ByteArray): String =
        NumericFingerprintGenerator(FingerprintIterations)
            .createFor(FingerprintVersion, localId.encodeToByteArray(), IdentityKey(localIdentityKey), remoteId.encodeToByteArray(), IdentityKey(remoteIdentityKey))
            .displayableFingerprint.displayText

    /** `nonce(12) || ciphertext || tag(16)`, the same layout CryptoKit's `AES.GCM.SealedBox.combined` uses on iOS. */
    override fun aesGcmSeal(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val nonce = randomBytes(GcmNonceSize)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GcmTagBits, nonce))
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        return nonce + cipher.doFinal(plaintext)
    }

    override fun aesGcmOpen(key: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray {
        if (sealed.size < GcmNonceSize + GcmTagBits / 8) throw SignalException.Invalid()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GcmTagBits, sealed, 0, GcmNonceSize))
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        return try { cipher.doFinal(sealed, GcmNonceSize, sealed.size - GcmNonceSize) } catch (e: javax.crypto.AEADBadTagException) { throw SignalException.Invalid() }
    }

    override fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    // ---- plumbing

    private fun store(identity: OwnIdentity) = InMemorySignalProtocolStore(IdentityKeyPair(identity.keyPair), identity.registrationId)
    private fun PeerAddress.address() = SignalProtocolAddress(userId, deviceId)

    /** libsignal wants a local address on every cipher; with one device per account it only needs to differ from the peer's. */
    private fun OwnIdentity.selfAddress() = SignalProtocolAddress(SelfName, PeerAddress.OnlyDevice)

    /** The sender-key store on its own: group operations never touch sessions or identities. */
    private class InMemorySenderKeys : org.signal.libsignal.protocol.groups.state.InMemorySenderKeyStore()

    /** Turns libsignal's exceptions into [SignalException]s the messaging layer understands. Nothing else escapes. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: SignalException) {
        throw e
    } catch (e: DuplicateMessageException) {
        throw SignalException.Duplicate()
    } catch (e: NoSessionException) {
        throw SignalException.NoSession()
    } catch (e: InvalidSessionException) {
        throw SignalException.NoSession()
    } catch (e: UntrustedIdentityException) {
        throw SignalException.UntrustedIdentity()
    } catch (e: InvalidKeyIdException) {
        throw SignalException.MissingKey(e.message ?: "key")
    } catch (e: InvalidMessageException) {
        throw SignalException.Invalid()
    } catch (e: InvalidVersionException) {
        throw SignalException.Invalid()
    } catch (e: LegacyMessageException) {
        throw SignalException.Invalid()
    } catch (e: InvalidKeyException) {
        throw SignalException.Invalid()
    } catch (e: IllegalArgumentException) {
        throw SignalException.Invalid()
    }

    private companion object {
        const val NoPreKeyId = -1
        const val FingerprintIterations = 5200
        const val FingerprintVersion = 2
        const val GcmNonceSize = 12
        const val GcmTagBits = 128
        /** Our own address name; the server-side user id is not known to the crypto layer and must not be needed by it. */
        const val SelfName = "self"
    }
}
