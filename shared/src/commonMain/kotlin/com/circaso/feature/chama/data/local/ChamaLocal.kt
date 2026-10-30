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
}
