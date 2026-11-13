package com.circaso.testing

import com.circaso.core.crypto.CiphertextKind
import com.circaso.core.crypto.CiphertextNeeds
import com.circaso.core.crypto.Decrypted
import com.circaso.core.crypto.Encrypted
import com.circaso.core.crypto.GroupBytes
import com.circaso.core.crypto.GroupDecrypted
import com.circaso.core.crypto.GroupEncrypted
import com.circaso.core.crypto.KyberPreKey
import com.circaso.core.crypto.OneTimePreKey
import com.circaso.core.crypto.OwnIdentity
import com.circaso.core.crypto.OwnKeys
import com.circaso.core.crypto.PeerAddress
import com.circaso.core.crypto.PeerBundle
import com.circaso.core.crypto.SenderKeyDistribution
import com.circaso.core.crypto.SenderKeyRecord
import com.circaso.core.crypto.SessionStart
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.crypto.SignedPreKey
import com.circaso.core.platform.LocalSealer
import com.circaso.feature.messaging.data.local.PeerIdentityEntity
import com.circaso.feature.messaging.data.local.PreKeyEntity
import com.circaso.feature.messaging.data.local.SenderKeyEntity
import com.circaso.feature.messaging.data.local.SessionEntity
import com.circaso.feature.messaging.data.local.SignalCounterEntity
import com.circaso.feature.messaging.data.local.SignalDao
import com.circaso.feature.messaging.data.local.SignalIdentityEntity
import com.circaso.feature.messaging.data.local.SignedPreKeyEntity

/** In-memory [SignalDao], the shape Room gives the repository. */
class FakeSignalDao : SignalDao {
    var identityRow: SignalIdentityEntity? = null
    val signed = mutableMapOf<Pair<String, Int>, SignedPreKeyEntity>()
    val preKeys = mutableMapOf<Int, PreKeyEntity>()
    val sessions = mutableMapOf<String, SessionEntity>()
    val peers = mutableMapOf<String, PeerIdentityEntity>()
    val senderKeys = mutableMapOf<Triple<String, String, String>, SenderKeyEntity>()
    val counters = mutableMapOf<String, Int>()

    override suspend fun identity() = identityRow
    override suspend fun upsertIdentity(identity: SignalIdentityEntity) { identityRow = identity }
    override suspend fun markPublished(at: Long) { identityRow = identityRow?.copy(publishedAt = at) }
    override suspend fun signedPreKeys(kind: String) = signed.values.filter { it.kind == kind }.sortedByDescending { it.createdAt }
    override suspend fun currentSignedPreKey(kind: String) = signed.values.filter { it.kind == kind && it.retiredAt == null }.maxByOrNull { it.createdAt }
    override suspend fun upsertSignedPreKey(key: SignedPreKeyEntity) { signed[key.kind to key.keyId] = key }
    override suspend fun retireSignedPreKeys(kind: String, except: Int, at: Long) {
        // MutableMap.replaceAll is JVM-only; this runs on iOS too.
        signed.entries.forEach { e -> val k = e.value; if (k.kind == kind && k.keyId != except && k.retiredAt == null) e.setValue(k.copy(retiredAt = at)) }
    }
    override suspend fun deleteSignedPreKey(kind: String, keyId: Int) { signed.remove(kind to keyId) }
    override suspend fun deleteSignedPreKeysRetiredBefore(before: Long) { signed.values.removeAll { it.retiredAt != null && it.retiredAt < before } }
    override suspend fun preKeys() = preKeys.values.sortedBy { it.keyId }
    override suspend fun preKey(keyId: Int) = preKeys[keyId]
    override suspend fun insertPreKeys(keys: List<PreKeyEntity>) { keys.forEach { preKeys[it.keyId] = it } }
    override suspend fun deletePreKey(keyId: Int) { preKeys.remove(keyId) }
    override suspend fun deletePreKeys(keyIds: List<Int>) { keyIds.forEach { preKeys.remove(it) } }
    override suspend fun deletePreKeysCreatedBefore(before: Long) { preKeys.values.removeAll { it.createdAt < before } }
    override suspend fun session(userId: String) = sessions[userId]
    override suspend fun sessions(): List<SessionEntity> = sessions.values.toList()
    override suspend fun senderKeys(): List<SenderKeyEntity> = senderKeys.values.toList()
    override suspend fun upsertSession(session: SessionEntity) { sessions[session.userId] = session }
    override suspend fun deleteSession(userId: String) { sessions.remove(userId) }
    override suspend fun peerIdentity(userId: String) = peers[userId]
    override suspend fun upsertPeerIdentity(peer: PeerIdentityEntity) { peers[peer.userId] = peer }
    override suspend fun senderKey(chatId: String, senderId: String, distributionId: String) = senderKeys[Triple(chatId, senderId, distributionId)]
    override suspend fun latestSenderKey(chatId: String, senderId: String) = senderKeys.values.filter { it.chatId == chatId && it.senderId == senderId }.maxByOrNull { it.updatedAt }
    override suspend fun upsertSenderKey(key: SenderKeyEntity) { senderKeys[Triple(key.chatId, key.senderId, key.distributionId)] = key }
    override suspend fun deleteSenderKeys(chatId: String) { senderKeys.values.removeAll { it.chatId == chatId } }
    override suspend fun deleteOtherSenderKeys(chatId: String, senderId: String, keep: String) { senderKeys.values.removeAll { it.chatId == chatId && it.senderId == senderId && it.distributionId != keep } }
    override suspend fun counter(name: String) = counters[name]
    override suspend fun setCounter(counter: SignalCounterEntity) { counters[counter.name] = counter.value }
    override suspend fun clearIdentity() { identityRow = null }
    override suspend fun clearSignedPreKeys() = signed.clear()
    override suspend fun clearPreKeys() = preKeys.clear()
    override suspend fun clearSessions() = sessions.clear()
    override suspend fun clearPeerIdentities() = peers.clear()
    override suspend fun clearSenderKeys() = senderKeys.clear()
    override suspend fun clearCounters() = counters.clear()
}

/** Seals by reversing the bytes and prefixing the label's length, enough to prove rows are sealed and labelled. */
class FakeSealer : LocalSealer {
    override suspend fun seal(plain: ByteArray, label: String) = byteArrayOf(label.length.toByte()) + plain.reversedArray()
    override suspend fun open(sealed: ByteArray, label: String): ByteArray {
        check(sealed.isNotEmpty() && sealed[0].toInt() == label.length) { "wrong label" }
        return sealed.copyOfRange(1, sealed.size).reversedArray()
    }
}

/** Deterministic stand-in for libsignal: keys are tagged byte arrays, ciphertext is plaintext with a prefix. */
class FakeSignalCrypto : SignalCrypto {
    var identities = 0
    private fun tag(what: String, id: Int) = "$what:$id".encodeToByteArray()

    override fun generateIdentity() = OwnIdentity(tag("idpriv", ++identities), tag("idpub", identities), 1000 + identities)
    override fun generateSignedPreKey(identity: OwnIdentity, id: Int, timestampMillis: Long) = SignedPreKey(id, tag("spk", id), tag("spkpub", id), tag("sig", id))
    override fun generateKyberPreKey(identity: OwnIdentity, id: Int, timestampMillis: Long) = KyberPreKey(id, tag("kpk", id), tag("kpkpub", id), tag("ksig", id))
    override fun generatePreKeys(startId: Int, count: Int) = List(count) { OneTimePreKey(startId + it, tag("opk", startId + it), tag("opkpub", startId + it)) }
    override fun startSession(identity: OwnIdentity, peer: PeerAddress, bundle: PeerBundle, current: ByteArray?) = SessionStart(tag("session", 1), bundle.identityKey)
    override fun encrypt(identity: OwnIdentity, peer: PeerAddress, session: ByteArray, plaintext: ByteArray) = Encrypted(CiphertextKind.Whisper, "ct:".encodeToByteArray() + plaintext, session)
    override fun inspect(kind: CiphertextKind, ciphertext: ByteArray) = CiphertextNeeds(null, null, null, null)
    override fun decrypt(identity: OwnIdentity, peer: PeerAddress, session: ByteArray?, kind: CiphertextKind, ciphertext: ByteArray, keys: OwnKeys) =
        Decrypted(ciphertext.copyOfRange(3, ciphertext.size), session ?: tag("session", 1), tag("idpub", 2), null)
    override fun createSenderKeyDistribution(self: PeerAddress, distributionId: String, current: ByteArray?) = SenderKeyDistribution(tag("dist", 1), tag("skr", 1))
    override fun distributionIdOf(kind: GroupBytes, bytes: ByteArray) = "11111111-2222-4333-8444-555555555555"
    override fun processSenderKeyDistribution(sender: PeerAddress, distribution: ByteArray, current: ByteArray?) = SenderKeyRecord(distributionIdOf(GroupBytes.Distribution, distribution), tag("skr", 2))
    override fun groupEncrypt(self: PeerAddress, distributionId: String, record: ByteArray, plaintext: ByteArray) = GroupEncrypted("gct:".encodeToByteArray() + plaintext, record)
    override fun groupDecrypt(sender: PeerAddress, distributionId: String, record: ByteArray, ciphertext: ByteArray) = GroupDecrypted(ciphertext.copyOfRange(4, ciphertext.size), record)
    override fun fingerprint(localId: String, localIdentityKey: ByteArray, remoteId: String, remoteIdentityKey: ByteArray) = "0".repeat(60)
    override fun aesGcmSeal(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray) = plaintext.reversedArray()
    override fun aesGcmOpen(key: ByteArray, sealed: ByteArray, associatedData: ByteArray) = sealed.reversedArray()
    override fun sha256(bytes: ByteArray) = ByteArray(32) { (bytes.size + it).toByte() }
    override fun randomBytes(count: Int) = ByteArray(count) { it.toByte() }
}
