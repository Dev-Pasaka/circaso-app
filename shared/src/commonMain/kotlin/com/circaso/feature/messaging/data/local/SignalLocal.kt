package com.circaso.feature.messaging.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/*
 * The Signal protocol's state on this phone. Every `record` and the identity key pair are sealed with the
 * LocalSealer before they reach these rows (see SealedRecordStore); public keys are stored as they are.
 */

/** This phone's identity: one row. [publishedAt] is when the server last accepted a `PUT /v1/keys` from it. */
@Entity(tableName = "signal_identity")
data class SignalIdentityEntity(
    @PrimaryKey val id: Int = 1,
    val keyPair: ByteArray,
    val publicKey: ByteArray,
    val registrationId: Int,
    val createdAt: Long,
    val publishedAt: Long?,
)

/** Signed prekeys, EC (`kind` = "ec") and Kyber (`kind` = "kyber"). A retired one is kept 30 days for messages in flight. */
@Entity(tableName = "signal_signed_prekey", primaryKeys = ["kind", "keyId"])
data class SignedPreKeyEntity(
    val kind: String,
    val keyId: Int,
    val record: ByteArray,
    val publicKey: ByteArray,
    val signature: ByteArray,
    val createdAt: Long,
    val retiredAt: Long?,
)

/** One-time prekeys whose public half is on the server. The private half goes when a message uses it. */
@Entity(tableName = "signal_prekey")
data class PreKeyEntity(
    @PrimaryKey val keyId: Int,
    val record: ByteArray,
    val publicKey: ByteArray,
    val createdAt: Long,
)

@Entity(tableName = "signal_session")
data class SessionEntity(@PrimaryKey val userId: String, val record: ByteArray, val updatedAt: Long)

/** The identity key we trust for a person (trust on first use); [changedAt] is set when it was replaced. */
@Entity(tableName = "signal_peer_identity")
data class PeerIdentityEntity(@PrimaryKey val userId: String, val identityKey: ByteArray, val firstSeenAt: Long, val changedAt: Long?)

/** Sender keys for groups: mine (senderId = my id) and each member's, per distribution. */
@Entity(tableName = "signal_sender_key", primaryKeys = ["chatId", "senderId", "distributionId"])
data class SenderKeyEntity(val chatId: String, val senderId: String, val distributionId: String, val record: ByteArray, val updatedAt: Long)

/** Monotonic counters for key ids, so an id is never reused even after the key is gone. */
@Entity(tableName = "signal_counter")
data class SignalCounterEntity(@PrimaryKey val name: String, val value: Int)

@Dao
interface SignalDao {
    // ---- identity
    @Query("SELECT * FROM signal_identity WHERE id = 1")
    suspend fun identity(): SignalIdentityEntity?

    @Upsert
    suspend fun upsertIdentity(identity: SignalIdentityEntity)

    @Query("UPDATE signal_identity SET publishedAt = :at WHERE id = 1")
    suspend fun markPublished(at: Long)

    // ---- signed prekeys (EC and Kyber)
    @Query("SELECT * FROM signal_signed_prekey WHERE kind = :kind ORDER BY createdAt DESC")
    suspend fun signedPreKeys(kind: String): List<SignedPreKeyEntity>

    @Query("SELECT * FROM signal_signed_prekey WHERE kind = :kind AND retiredAt IS NULL ORDER BY createdAt DESC LIMIT 1")
    suspend fun currentSignedPreKey(kind: String): SignedPreKeyEntity?

    @Upsert
    suspend fun upsertSignedPreKey(key: SignedPreKeyEntity)

    @Query("UPDATE signal_signed_prekey SET retiredAt = :at WHERE kind = :kind AND keyId != :except AND retiredAt IS NULL")
    suspend fun retireSignedPreKeys(kind: String, except: Int, at: Long)

    @Query("DELETE FROM signal_signed_prekey WHERE kind = :kind AND keyId = :keyId")
    suspend fun deleteSignedPreKey(kind: String, keyId: Int)

    @Query("DELETE FROM signal_signed_prekey WHERE retiredAt IS NOT NULL AND retiredAt < :before")
    suspend fun deleteSignedPreKeysRetiredBefore(before: Long)

    // ---- one-time prekeys
    @Query("SELECT * FROM signal_prekey ORDER BY keyId")
    suspend fun preKeys(): List<PreKeyEntity>

    @Query("SELECT * FROM signal_prekey WHERE keyId = :keyId")
    suspend fun preKey(keyId: Int): PreKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreKeys(keys: List<PreKeyEntity>)

    @Query("DELETE FROM signal_prekey WHERE keyId = :keyId")
    suspend fun deletePreKey(keyId: Int)

    @Query("DELETE FROM signal_prekey WHERE keyId IN (:keyIds)")
    suspend fun deletePreKeys(keyIds: List<Int>)

    @Query("DELETE FROM signal_prekey WHERE createdAt < :before")
    suspend fun deletePreKeysCreatedBefore(before: Long)

    // ---- sessions and peers
    /** Every session, still sealed: iOS's notification snapshot copies them (NotificationSnapshot). */
    @Query("SELECT * FROM signal_session")
    suspend fun sessions(): List<SessionEntity>

    @Query("SELECT * FROM signal_sender_key")
    suspend fun senderKeys(): List<SenderKeyEntity>

    @Query("SELECT * FROM signal_session WHERE userId = :userId")
    suspend fun session(userId: String): SessionEntity?

    @Upsert
    suspend fun upsertSession(session: SessionEntity)

    @Query("DELETE FROM signal_session WHERE userId = :userId")
    suspend fun deleteSession(userId: String)

    @Query("SELECT * FROM signal_peer_identity WHERE userId = :userId")
    suspend fun peerIdentity(userId: String): PeerIdentityEntity?

    @Upsert
    suspend fun upsertPeerIdentity(peer: PeerIdentityEntity)

    // ---- sender keys
    @Query("SELECT * FROM signal_sender_key WHERE chatId = :chatId AND senderId = :senderId AND distributionId = :distributionId")
    suspend fun senderKey(chatId: String, senderId: String, distributionId: String): SenderKeyEntity?

    @Query("SELECT * FROM signal_sender_key WHERE chatId = :chatId AND senderId = :senderId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestSenderKey(chatId: String, senderId: String): SenderKeyEntity?

    @Upsert
    suspend fun upsertSenderKey(key: SenderKeyEntity)

    @Query("DELETE FROM signal_sender_key WHERE chatId = :chatId")
    suspend fun deleteSenderKeys(chatId: String)

    @Query("DELETE FROM signal_sender_key WHERE chatId = :chatId AND senderId = :senderId AND distributionId != :keep")
    suspend fun deleteOtherSenderKeys(chatId: String, senderId: String, keep: String)

    // ---- counters
    @Query("SELECT value FROM signal_counter WHERE name = :name")
    suspend fun counter(name: String): Int?

    @Upsert
    suspend fun setCounter(counter: SignalCounterEntity)

    // ---- everything (sign-out of a different person, or a reset)
    @Query("DELETE FROM signal_identity") suspend fun clearIdentity()
    @Query("DELETE FROM signal_signed_prekey") suspend fun clearSignedPreKeys()
    @Query("DELETE FROM signal_prekey") suspend fun clearPreKeys()
    @Query("DELETE FROM signal_session") suspend fun clearSessions()
    @Query("DELETE FROM signal_peer_identity") suspend fun clearPeerIdentities()
    @Query("DELETE FROM signal_sender_key") suspend fun clearSenderKeys()
    @Query("DELETE FROM signal_counter") suspend fun clearCounters()
}

/** Everything the Signal tables hold, gone. Counters go too: a new identity may start its ids again. */
suspend fun SignalDao.clearAll() {
    clearIdentity(); clearSignedPreKeys(); clearPreKeys(); clearSessions(); clearPeerIdentities(); clearSenderKeys(); clearCounters()
}
