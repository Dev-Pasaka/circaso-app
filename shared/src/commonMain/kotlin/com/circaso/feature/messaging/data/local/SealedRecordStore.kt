package com.circaso.feature.messaging.data.local

import com.circaso.core.crypto.KyberPreKey
import com.circaso.core.crypto.OneTimePreKey
import com.circaso.core.crypto.OwnIdentity
import com.circaso.core.crypto.OwnKeys
import com.circaso.core.crypto.SignedPreKey
import com.circaso.core.platform.LocalSealer
import com.circaso.core.sync.nowMillis

/**
 * The Signal records as the crypto layer wants them (plain bytes, in memory only), over the sealed rows in Room.
 * Every private record is sealed under a label naming its row, so a blob moved to another row does not open.
 * Callers that change several records for one operation hold the per-peer or per-chat lock in the repository.
 */
class SealedRecordStore(private val dao: SignalDao, private val sealer: LocalSealer) {
    // ---- identity

    suspend fun identity(): OwnIdentity? = dao.identity()?.let { OwnIdentity(sealer.open(it.keyPair, "identity:1"), it.publicKey, it.registrationId) }

    suspend fun identityRow(): SignalIdentityEntity? = dao.identity()

    suspend fun saveIdentity(identity: OwnIdentity, now: Long = nowMillis()) {
        dao.upsertIdentity(SignalIdentityEntity(keyPair = sealer.seal(identity.keyPair, "identity:1"), publicKey = identity.publicKey, registrationId = identity.registrationId, createdAt = now, publishedAt = null))
    }

    suspend fun markPublished(now: Long = nowMillis()) = dao.markPublished(now)

    // ---- signed prekeys

    suspend fun saveSignedPreKey(key: SignedPreKey, now: Long = nowMillis()) =
        dao.upsertSignedPreKey(SignedPreKeyEntity(Ec, key.id, sealer.seal(key.record, "signed:$Ec:${key.id}"), key.publicKey, key.signature, now, null))

    suspend fun saveKyberPreKey(key: KyberPreKey, now: Long = nowMillis()) =
        dao.upsertSignedPreKey(SignedPreKeyEntity(Kyber, key.id, sealer.seal(key.record, "signed:$Kyber:${key.id}"), key.publicKey, key.signature, now, null))

    suspend fun currentSignedPreKey(): SignedPreKey? = dao.currentSignedPreKey(Ec)?.let { SignedPreKey(it.keyId, sealer.open(it.record, "signed:$Ec:${it.keyId}"), it.publicKey, it.signature) }
    suspend fun currentKyberPreKey(): KyberPreKey? = dao.currentSignedPreKey(Kyber)?.let { KyberPreKey(it.keyId, sealer.open(it.record, "signed:$Kyber:${it.keyId}"), it.publicKey, it.signature) }

    /** Marks every other key of both kinds retired; they stay for [RetiredKeepMillis] so messages in flight still open. */
    suspend fun retireOtherSignedPreKeys(exceptEc: Int, exceptKyber: Int, now: Long = nowMillis()) {
        dao.retireSignedPreKeys(Ec, exceptEc, now)
        dao.retireSignedPreKeys(Kyber, exceptKyber, now)
    }

    suspend fun deleteSignedPreKey(key: SignedPreKey) = dao.deleteSignedPreKey(Ec, key.id)
    suspend fun deleteKyberPreKey(key: KyberPreKey) = dao.deleteSignedPreKey(Kyber, key.id)
    suspend fun purgeRetired(now: Long = nowMillis()) = dao.deleteSignedPreKeysRetiredBefore(now - RetiredKeepMillis)

    // ---- one-time prekeys

    suspend fun savePreKeys(keys: List<OneTimePreKey>, now: Long = nowMillis()) =
        dao.insertPreKeys(keys.map { PreKeyEntity(it.id, sealer.seal(it.record, "prekey:${it.id}"), it.publicKey, now) })

    suspend fun preKeyIds(): List<Int> = dao.preKeys().map { it.keyId }
    suspend fun deletePreKeys(ids: List<Int>) { if (ids.isNotEmpty()) dao.deletePreKeys(ids) }
    suspend fun deletePreKeysCreatedBefore(at: Long) = dao.deletePreKeysCreatedBefore(at)

    /** The private half of a used one-time prekey is deleted the moment a message has used it. */
    suspend fun consumePreKey(id: Int) = dao.deletePreKey(id)

    /** Every private record a PreKey message might need: all one-time prekeys and all kept signed and Kyber prekeys. */
    suspend fun ownKeys(): OwnKeys = OwnKeys(
        preKeys = dao.preKeys().associate { it.keyId to sealer.open(it.record, "prekey:${it.keyId}") },
        signedPreKeys = dao.signedPreKeys(Ec).associate { it.keyId to sealer.open(it.record, "signed:$Ec:${it.keyId}") },
        kyberPreKeys = dao.signedPreKeys(Kyber).associate { it.keyId to sealer.open(it.record, "signed:$Kyber:${it.keyId}") },
    )

    // ---- counters

    /** The next [count] ids for [name], never handed out twice. */
    suspend fun nextIds(name: String, count: Int): IntRange {
        val start = (dao.counter(name) ?: 0) + 1
        dao.setCounter(SignalCounterEntity(name, start + count - 1))
        return start until start + count
    }

    // ---- sessions and peers

    suspend fun session(userId: String): ByteArray? = dao.session(userId)?.let { sealer.open(it.record, "session:$userId") }
    suspend fun saveSession(userId: String, record: ByteArray, now: Long = nowMillis()) = dao.upsertSession(SessionEntity(userId, sealer.seal(record, "session:$userId"), now))
    suspend fun deleteSession(userId: String) = dao.deleteSession(userId)

    suspend fun peerIdentity(userId: String): PeerIdentityEntity? = dao.peerIdentity(userId)

    /**
     * Trust on first use. Returns true when the person's identity key is not the one we knew: the caller shows the
     * "security code changed" notice and drops the old session. The new key is trusted from here on.
     */
    suspend fun rememberPeerIdentity(userId: String, identityKey: ByteArray, now: Long = nowMillis()): Boolean {
        val known = dao.peerIdentity(userId)
        return when {
            known == null -> { dao.upsertPeerIdentity(PeerIdentityEntity(userId, identityKey, now, null)); false }
            known.identityKey.contentEquals(identityKey) -> false
            else -> { dao.upsertPeerIdentity(known.copy(identityKey = identityKey, changedAt = now)); true }
        }
    }

    // ---- sender keys

    suspend fun senderKey(chatId: String, senderId: String, distributionId: String): ByteArray? =
        dao.senderKey(chatId, senderId, distributionId)?.let { sealer.open(it.record, "sender:$chatId:$senderId:$distributionId") }

    suspend fun latestSenderKey(chatId: String, senderId: String): Pair<String, ByteArray>? =
        dao.latestSenderKey(chatId, senderId)?.let { it.distributionId to sealer.open(it.record, "sender:$chatId:$senderId:${it.distributionId}") }

    suspend fun saveSenderKey(chatId: String, senderId: String, distributionId: String, record: ByteArray, now: Long = nowMillis()) =
        dao.upsertSenderKey(SenderKeyEntity(chatId, senderId, distributionId, sealer.seal(record, "sender:$chatId:$senderId:$distributionId"), now))

    suspend fun deleteSenderKeys(chatId: String) = dao.deleteSenderKeys(chatId)
    suspend fun deleteOtherSenderKeys(chatId: String, senderId: String, keep: String) = dao.deleteOtherSenderKeys(chatId, senderId, keep)

    // ---- any other private bytes a feature keeps (group metadata keys)

    suspend fun sealBytes(plain: ByteArray, label: String): ByteArray = sealer.seal(plain, label)
    suspend fun openBytes(sealed: ByteArray, label: String): ByteArray = sealer.open(sealed, label)

    companion object {
        const val Ec = "ec"
        const val Kyber = "kyber"
        const val RetiredKeepMillis = 30L * 24 * 60 * 60 * 1000
        const val SignedCounter = "signed"
        const val PreKeyCounter = "prekey"
    }
}
