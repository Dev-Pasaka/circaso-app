package com.circaso.feature.wallet.data.local

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
)

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallet_snapshot WHERE id = 1")
    fun observeSnapshot(): Flow<WalletSnapshotEntity?>

    @Query("SELECT * FROM wallet_snapshot WHERE id = 1")
    suspend fun snapshot(): WalletSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSnapshot(row: WalletSnapshotEntity)

    @Query("SELECT * FROM transfer ORDER BY createdAt DESC")
    fun observeTransfers(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfer WHERE transferId = :id")
    fun observeTransfer(id: String): Flow<TransferEntity?>

    @Query("SELECT * FROM transfer WHERE transferId = :id")
    suspend fun transfer(id: String): TransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TransferEntity)

    @Query("DELETE FROM wallet_snapshot")
    suspend fun clearSnapshot()

    @Query("DELETE FROM transfer")
    suspend fun clearTransfers()
}
