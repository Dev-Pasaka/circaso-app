package com.circaso.feature.wallet.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** What a row from before wallet networks (DB v24) belongs to: everything then was Circle's testnet. */
const val LegacyNetwork = "TESTNET"

/**
 * The last W1 answer as JSON, one row per wallet network (DB v24; it was a single row); the domain model is rebuilt
 * from it so the schema never chases the API.
 */
@Entity(tableName = "wallet_snapshot")
data class WalletSnapshotEntity(@PrimaryKey @ColumnInfo(defaultValue = LegacyNetwork) val network: String, val json: String, val fetchedAt: Long)

/** A payment this phone knows: made here (W4), read (W5), or heard of (`transfer.updated`, `payment.received`). */
@Entity(tableName = "transfer")
data class TransferEntity(
    @PrimaryKey val transferId: String,
    val direction: String,
    val state: String,
    val blockchain: String,
    val fromAddress: String,
    val toUserId: String?,
    val toAddress: String,
    val asset: String,
    val amountMinor: String,
    val amount: String,
    val feeMinor: String?,
    val chatId: String?,
    val txHash: String?,
    val failureReason: String?,
    val counterpartName: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** The other person's @handle and photo when they're on Circaso (W6 `counterparty`). */
    @ColumnInfo(defaultValue = "NULL") val counterpartHandle: String? = null,
    @ColumnInfo(defaultValue = "NULL") val counterpartAvatar: String? = null,
    /** The group wallet it left (a payout signed on this phone) or arrived in; null for the personal wallet (DB v17). */
    @ColumnInfo(defaultValue = "NULL") val walletId: String? = null,
    /** W5: the group whose wallet it left, or the group it paid into (a contribution). */
    @ColumnInfo(defaultValue = "NULL") val fromGroupId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val toGroupId: String? = null,
    /** The approved payout request it pays. */
    @ColumnInfo(defaultValue = "NULL") val payoutRequestId: String? = null,
    /** The other person on Circaso (either direction), so the row shows the name I saved for them (DB v22). */
    @ColumnInfo(defaultValue = "NULL") val counterpartUserId: String? = null,
    /** The wallet network it moved on (DB v24): lists show one network's payments; a card finds its payment by id. */
    @ColumnInfo(defaultValue = LegacyNetwork) val network: String = LegacyNetwork,
    /**
     * The other person is verified (W6 `counterparty.verified`, DB v26), so a payee in no contact or chat still shows
     * the seal offline. Every row of one person carries the newest answer: see [WalletDao.updateTransfersVerified].
     */
    @ColumnInfo(defaultValue = "0") val counterpartVerified: Boolean = false,
)

/** The last W1 answer for a group wallet, one row per wallet; the personal wallet stays in `wallet_snapshot`. */
@Entity(tableName = "wallet_cache", primaryKeys = ["network", "walletId"])
data class WalletCacheEntity(val walletId: String, val json: String, val fetchedAt: Long, @ColumnInfo(defaultValue = LegacyNetwork) val network: String = LegacyNetwork)

/** The last W16 answer (every wallet the person can see), as JSON in one row per wallet network. */
@Entity(tableName = "wallet_list")
data class WalletListEntity(@PrimaryKey @ColumnInfo(defaultValue = LegacyNetwork) val network: String, val json: String, val fetchedAt: Long)

/**
 * A group wallet's history (W6 `?walletId=`), kept apart from the person's own payments: a contribution is one
 * transfer that is OUT of a personal wallet and IN to the group's, so the two lists can't share rows.
 */
@Entity(tableName = "wallet_activity", primaryKeys = ["network", "walletId", "rowId"])
data class WalletActivityEntity(
    val walletId: String,
    val rowId: String,
    val transferId: String?,
    val direction: String,
    val state: String,
    val blockchain: String,
    val asset: String,
    val amountMinor: String,
    val amount: String,
    val counterpartName: String?,
    val counterpartHandle: String?,
    val counterpartAvatar: String?,
    val counterpartUserId: String?,
    val counterpartAddress: String?,
    val counterpartGroupId: String?,
    val chatId: String?,
    val txHash: String?,
    val occurredAt: Long,
    @ColumnInfo(defaultValue = LegacyNetwork) val network: String = LegacyNetwork,
    /** The other person is verified (W6 `counterparty.verified`, DB v26), as on [TransferEntity.counterpartVerified]. */
    @ColumnInfo(defaultValue = "0") val counterpartVerified: Boolean = false,
)

/**
 * Every list and copy is one wallet network's ([network] is `MAINNET` or `TESTNET`), so switching shows the other
 * network's copy at once and never mixes the two. A single payment is found by its id alone: ids are unique.
 */
@Dao
interface WalletDao {
    @Query("SELECT * FROM wallet_snapshot WHERE network = :network")
    fun observeSnapshot(network: String): Flow<WalletSnapshotEntity?>

    @Query("SELECT * FROM wallet_snapshot WHERE network = :network")
    suspend fun snapshot(network: String): WalletSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSnapshot(row: WalletSnapshotEntity)

    /** The person's own payments on one network: a group wallet's live elsewhere. */
    @Query("SELECT * FROM transfer WHERE walletId IS NULL AND network = :network ORDER BY createdAt DESC")
    fun observeTransfers(network: String): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfer WHERE walletId = :walletId AND network = :network ORDER BY createdAt DESC")
    fun observeTransfersOf(walletId: String, network: String): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfer WHERE transferId = :id")
    fun observeTransfer(id: String): Flow<TransferEntity?>

    @Query("SELECT * FROM transfer WHERE transferId = :id")
    suspend fun transfer(id: String): TransferEntity?

    /** The payments a thread's money cards point at, for their Sending / Sent / Not sent line. */
    @Query("SELECT * FROM transfer WHERE transferId IN (:ids)")
    fun observeTransfersIn(ids: List<String>): Flow<List<TransferEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TransferEntity)

    /** Money in kept under its history row id before the server named its transfer; now kept under the transfer. */
    @Query("DELETE FROM transfer WHERE transferId = :id AND direction = 'In'")
    suspend fun deleteIncoming(id: String)

    @Query("SELECT * FROM wallet_cache WHERE walletId = :walletId AND network = :network")
    fun observeCached(walletId: String, network: String): Flow<WalletCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCached(row: WalletCacheEntity)

    @Query("SELECT * FROM wallet_list WHERE network = :network")
    fun observeList(network: String): Flow<WalletListEntity?>

    @Query("SELECT * FROM wallet_list WHERE network = :network")
    suspend fun list(network: String): WalletListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveList(row: WalletListEntity)

    @Query("SELECT * FROM wallet_activity WHERE walletId = :walletId AND network = :network ORDER BY occurredAt DESC")
    fun observeActivity(walletId: String, network: String): Flow<List<WalletActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveActivity(rows: List<WalletActivityEntity>)

    /**
     * The verified badge (2026-11-24): [userId]'s badge as the server says now (a fresh W6 page or their profile read
     * again), on every payment with them, so an older row can't keep a seal that was taken back.
     */
    @Query("UPDATE transfer SET counterpartVerified = :verified WHERE COALESCE(counterpartUserId, toUserId) = :userId")
    suspend fun updateTransfersVerified(userId: String, verified: Boolean)

    /** As [updateTransfersVerified], for group wallets' history. */
    @Query("UPDATE wallet_activity SET counterpartVerified = :verified WHERE counterpartUserId = :userId")
    suspend fun updateActivityVerified(userId: String, verified: Boolean)

    @Query("DELETE FROM wallet_snapshot")
    suspend fun clearSnapshot()

    @Query("DELETE FROM wallet_cache")
    suspend fun clearCached()

    @Query("DELETE FROM wallet_list")
    suspend fun clearList()

    @Query("DELETE FROM wallet_activity")
    suspend fun clearActivity()

    @Query("DELETE FROM transfer")
    suspend fun clearTransfers()
}
