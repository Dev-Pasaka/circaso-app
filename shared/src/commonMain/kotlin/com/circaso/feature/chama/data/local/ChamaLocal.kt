package com.circaso.feature.chama.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.ColumnInfo
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The last CH1 answer per group chat and wallet network, as JSON; the domain model is rebuilt from it. A group's
 * money is per network (DB v24): rows from before are Testnet's.
 */
@Entity(tableName = "chama_snapshot", primaryKeys = ["network", "chatId"])
data class ChamaSnapshotEntity(val chatId: String, val json: String, val fetchedAt: Long, @ColumnInfo(defaultValue = "TESTNET") val network: String = "TESTNET")

/** The last CH7 answer per group and wallet network (its money, target or not; group wallets, phase 2), as JSON. */
@Entity(tableName = "group_money", primaryKeys = ["network", "chatId"])
data class GroupMoneyEntity(val chatId: String, val json: String, val fetchedAt: Long, @ColumnInfo(defaultValue = "TESTNET") val network: String = "TESTNET")

@Dao
interface ChamaDao {
    @Query("SELECT * FROM chama_snapshot WHERE chatId = :chatId AND network = :network")
    fun observe(chatId: String, network: String): Flow<ChamaSnapshotEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: ChamaSnapshotEntity)

    @Query("DELETE FROM chama_snapshot WHERE chatId = :chatId AND network = :network")
    suspend fun delete(chatId: String, network: String)

    @Query("DELETE FROM chama_snapshot")
    suspend fun clearAll()

    @Query("SELECT * FROM group_money WHERE chatId = :chatId AND network = :network")
    fun observeMoney(chatId: String, network: String): Flow<GroupMoneyEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMoney(row: GroupMoneyEntity)

    @Query("DELETE FROM group_money")
    suspend fun clearMoney()
}
