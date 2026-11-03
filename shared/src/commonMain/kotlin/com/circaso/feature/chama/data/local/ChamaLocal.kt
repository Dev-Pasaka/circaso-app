package com.circaso.feature.chama.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The last CH1 answer per group chat, as JSON; the domain model is rebuilt from it. */
@Entity(tableName = "chama_snapshot")
data class ChamaSnapshotEntity(@PrimaryKey val chatId: String, val json: String, val fetchedAt: Long)

/** The last CH7 answer per group (its money, target or not; group wallets, phase 2), as JSON. */
@Entity(tableName = "group_money")
data class GroupMoneyEntity(@PrimaryKey val chatId: String, val json: String, val fetchedAt: Long)

@Dao
interface ChamaDao {
    @Query("SELECT * FROM chama_snapshot WHERE chatId = :chatId")
    fun observe(chatId: String): Flow<ChamaSnapshotEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: ChamaSnapshotEntity)

    @Query("DELETE FROM chama_snapshot WHERE chatId = :chatId")
    suspend fun delete(chatId: String)

    @Query("DELETE FROM chama_snapshot")
    suspend fun clearAll()

    @Query("SELECT * FROM group_money WHERE chatId = :chatId")
    fun observeMoney(chatId: String): Flow<GroupMoneyEntity?>

    @Query("SELECT * FROM group_money")
    fun observeAllMoney(): Flow<List<GroupMoneyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMoney(row: GroupMoneyEntity)

    @Query("DELETE FROM group_money")
    suspend fun clearMoney()
}
