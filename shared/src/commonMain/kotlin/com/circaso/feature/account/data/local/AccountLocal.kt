package com.circaso.feature.account.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The security overview kept for offline reading. One row (`id = 1`); linked logins are stored as one text field. */
@Entity(tableName = "security_overview")
data class SecurityOverviewEntity(
    @PrimaryKey val id: Int = 1,
    val maskedPhone: String?,
    /** `provider`, `maskedEmail`, `linkedAt` joined by a unit separator (U+001F), one login per line. */
    val providers: String,
    val deviceModel: String?,
    val deviceSince: String?,
    val verification: String,
)

@Entity(tableName = "security_event")
data class SecurityEventEntity(
    @PrimaryKey val eventId: String,
    val type: String,
    val deviceModel: String?,
    val detail: String?,
    val at: String,
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM security_overview WHERE id = 1")
    fun observeOverview(): Flow<SecurityOverviewEntity?>

    @Upsert
    suspend fun upsertOverview(row: SecurityOverviewEntity)

    @Query("DELETE FROM security_overview")
    suspend fun clearOverview()

    @Query("SELECT * FROM security_event ORDER BY at DESC")
    fun observeEvents(): Flow<List<SecurityEventEntity>>

    @Upsert
    suspend fun upsertEvents(rows: List<SecurityEventEntity>)

    @Query("DELETE FROM security_event WHERE eventId NOT IN (:keep)")
    suspend fun deleteEventsNotIn(keep: List<String>)

    @Query("DELETE FROM security_event")
    suspend fun clearEvents()
}
