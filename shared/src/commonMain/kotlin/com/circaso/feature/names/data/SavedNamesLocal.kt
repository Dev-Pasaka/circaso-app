package com.circaso.feature.names.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A name I saved for someone (2026-11-12), on this phone only and in my sealed backup. [name] null is a cleared name
 * kept as a tombstone, so clearing it on one phone wins over an older name from another when the backups merge.
 */
@Entity(tableName = "saved_name")
data class SavedNameEntity(@PrimaryKey val userId: String, val name: String?, val updatedAt: Long)

@Dao
interface SavedNamesDao {
    @Query("SELECT * FROM saved_name")
    fun observeAll(): Flow<List<SavedNameEntity>>

    @Query("SELECT * FROM saved_name")
    suspend fun all(): List<SavedNameEntity>

    @Query("SELECT * FROM saved_name WHERE userId = :userId")
    suspend fun get(userId: String): SavedNameEntity?

    @Upsert
    suspend fun upsert(rows: List<SavedNameEntity>)

    @Query("DELETE FROM saved_name")
    suspend fun clear()
}
