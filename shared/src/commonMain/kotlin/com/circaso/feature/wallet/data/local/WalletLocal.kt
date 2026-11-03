package com.circaso.feature.wallet.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The last W1 answer as JSON, one row; the domain model is rebuilt from it so the schema never chases the API. */
@Entity(tableName = "wallet_snapshot")
data class WalletSnapshotEntity(@PrimaryKey val id: Int = 1, val json: String, val fetchedAt: Long)

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
)

/** The last W1 answer for a group wallet, one row per wallet; the personal wallet stays in `wallet_snapshot`. */
@Entity(tableName = "wallet_cache")
data class WalletCacheEntity(@PrimaryKey val walletId: String, val json: String, val fetchedAt: Long)

/** The last W16 answer (every wallet the person can see), as JSON in one row. */
@Entity(tableName = "wallet_list")
data class WalletListEntity(@PrimaryKey val id: Int = 1, val json: String, val fetchedAt: Long)

/**
 * A group wallet's history (W6 `?walletId=`), kept apart from the person's own payments: a contribution is one
 * transfer that is OUT of a personal wallet and IN to the group's, so the two lists can't share rows.
 */
@Entity(tableName = "wallet_activity", primaryKeys = ["walletId", "rowId"])
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
)

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallet_snapshot WHERE id = 1")
    fun observeSnapshot(): Flow<WalletSnapshotEntity?>

    @Query("SELECT * FROM wallet_snapshot WHERE id = 1")
    suspend fun snapshot(): WalletSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSnapshot(row: WalletSnapshotEntity)

    /** The person's own payments: a group wallet's live elsewhere. */
    @Query("SELECT * FROM transfer WHERE walletId IS NULL ORDER BY createdAt DESC")
    fun observeTransfers(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfer WHERE walletId = :walletId ORDER BY createdAt DESC")
    fun observeTransfersOf(walletId: String): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfer WHERE transferId = :id")
    fun observeTransfer(id: String): Flow<TransferEntity?>

    @Query("SELECT * FROM transfer WHERE transferId = :id")
    suspend fun transfer(id: String): TransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TransferEntity)

    @Query("SELECT * FROM wallet_cache WHERE walletId = :walletId")
    fun observeCached(walletId: String): Flow<WalletCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCached(row: WalletCacheEntity)

    @Query("SELECT * FROM wallet_list WHERE id = 1")
    fun observeList(): Flow<WalletListEntity?>

    @Query("SELECT * FROM wallet_list WHERE id = 1")
    suspend fun list(): WalletListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveList(row: WalletListEntity)

    @Query("SELECT * FROM wallet_activity WHERE walletId = :walletId ORDER BY occurredAt DESC")
    fun observeActivity(walletId: String): Flow<List<WalletActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveActivity(rows: List<WalletActivityEntity>)

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
