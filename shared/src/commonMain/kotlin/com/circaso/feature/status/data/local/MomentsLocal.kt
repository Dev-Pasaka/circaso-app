package com.circaso.feature.status.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A moment as the server listed it. [body] is the decrypted body (JSON, `MomentBody`), null while the poster's
 * sender key hasn't arrived; [ciphertext] and [keyCopy] are kept until it is readable, then dropped.
 */
@Entity(tableName = "moment")
data class MomentEntity(
    @PrimaryKey val momentId: String,
    val posterId: String,
    val body: String?,
    val ciphertext: String?,
    val keyCopy: String?,
    val postedAt: Long,
    val expiresAt: Long,
    val mine: Boolean,
    val viewed: Boolean,
    val reacted: Boolean,
    val viewCount: Int,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val reaction: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val reactionCount: Int = 0,
)

/**
 * My moment on its way out (M6). [draft] is the composer's choice as JSON; the plain media sits in the phone's media
 * files under [localId] until it is posted. [mediaId] and the key are filled once the upload is done, so a retry
 * doesn't upload twice. [state] is `posting`, `waiting` or `failed`.
 */
@Entity(tableName = "moment_pending")
data class PendingMomentEntity(
    @PrimaryKey val localId: String,
    val kind: String,
    val draft: String,
    val mediaId: String?,
    val mediaKey: String?,
    val mediaDigest: String?,
    val state: String,
    val error: String?,
    val createdAt: Long,
)

@Entity(tableName = "moment_muted")
data class MutedPosterEntity(@PrimaryKey val userId: String, val at: Long)

/** The one privacy setting (P1), as JSON. Kept on the phone only. */
@Entity(tableName = "moment_privacy")
data class MomentPrivacyEntity(@PrimaryKey val id: Int = 0, val json: String)

@Dao
interface MomentsDao {
    @Query("SELECT * FROM moment WHERE expiresAt > :now ORDER BY postedAt")
    fun observe(now: Long): Flow<List<MomentEntity>>

    @Query("SELECT * FROM moment WHERE momentId = :id")
    suspend fun byId(id: String): MomentEntity?

    @Query("SELECT * FROM moment WHERE body IS NULL AND posterId = :posterId")
    suspend fun unreadableFrom(posterId: String): List<MomentEntity>

    /** My live moments whose body this phone holds (the ones posted here), for a key request. */
    @Query("SELECT * FROM moment WHERE mine = 1 AND body IS NOT NULL AND expiresAt > :now ORDER BY postedAt")
    suspend fun mineReadable(now: Long): List<MomentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<MomentEntity>)

    @Query("UPDATE moment SET viewed = 1, reacted = :reacted WHERE momentId = :id")
    suspend fun setViewed(id: String, reacted: Boolean)

    @Query("UPDATE moment SET reaction = :emoji, reacted = :emoji IS NOT NULL WHERE momentId = :id")
    suspend fun setReaction(id: String, emoji: String?)

    @Query("UPDATE moment SET viewCount = :count WHERE momentId = :id")
    suspend fun setViewCount(id: String, count: Int)

    @Query("DELETE FROM moment WHERE momentId = :id")
    suspend fun delete(id: String)

    /** Gone from the server's list: deleted, or no longer visible to me. */
    @Query("DELETE FROM moment WHERE momentId NOT IN (:keep)")
    suspend fun deleteAllExcept(keep: List<String>)

    @Query("DELETE FROM moment WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("SELECT * FROM moment_pending ORDER BY createdAt")
    fun observePending(): Flow<List<PendingMomentEntity>>

    @Query("SELECT * FROM moment_pending WHERE localId = :localId")
    suspend fun pending(localId: String): PendingMomentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePending(row: PendingMomentEntity)

    @Query("UPDATE moment_pending SET state = :state, error = :error WHERE localId = :localId")
    suspend fun setPendingState(localId: String, state: String, error: String?)

    @Query("DELETE FROM moment_pending WHERE localId = :localId")
    suspend fun deletePending(localId: String)

    @Query("SELECT userId FROM moment_muted")
    fun observeMuted(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun mute(row: MutedPosterEntity)

    @Query("DELETE FROM moment_muted WHERE userId = :userId")
    suspend fun unmute(userId: String)

    @Query("SELECT * FROM moment_privacy WHERE id = 0")
    fun observePrivacy(): Flow<MomentPrivacyEntity?>

    @Query("SELECT * FROM moment_privacy WHERE id = 0")
    suspend fun privacy(): MomentPrivacyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePrivacy(row: MomentPrivacyEntity)

    @Query("DELETE FROM moment")
    suspend fun clearMoments()

    @Query("DELETE FROM moment_pending")
    suspend fun clearPending()
}
