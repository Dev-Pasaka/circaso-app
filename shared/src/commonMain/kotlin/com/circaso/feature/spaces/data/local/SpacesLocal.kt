package com.circaso.feature.spaces.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Spaces on the phone (DB v18): the server's answers kept as JSON (SpaceDto, MySpaceDto, PostDto, ReceivedInviteDto),
// so a new field reaches the screens without a migration, plus the columns the queries sort and filter on.

/** SP3 for each space opened, so it opens offline as of [savedAt] (SS6b). */
@Entity(tableName = "space")
data class SpaceEntity(@PrimaryKey val spaceId: String, val handle: String, val json: String, val savedAt: Long)

/** SP7, one row per space in "Your spaces". */
@Entity(tableName = "space_mine")
data class MySpaceEntity(@PrimaryKey val spaceId: String, val json: String, val activityAt: Long)

/**
 * A space post. [localKey] is my `clientPostId` for my own posts and the server's `postId` for everyone else's, so a
 * post I queued offline turns into the server's copy in place. [json] is a `PostDto` (my waiting posts have one made on
 * the phone). [state]: `sent`, `waiting` or `failed`; [pendingEmoji] is my reaction waiting to send ("" takes it back).
 */
@Entity(tableName = "space_post", indices = [Index("spaceId", "createdAt")])
data class SpacePostEntity(
    @PrimaryKey val localKey: String,
    val spaceId: String,
    val postId: String?,
    val createdAt: Long,
    val json: String,
    val mine: Boolean,
    val state: String,
    val error: String?,
    val pendingEmoji: String?,
)

/** SP20, the invite cards at the top of Chats. */
@Entity(tableName = "space_invite")
data class SpaceInviteEntity(@PrimaryKey val inviteId: String, val spaceId: String, val json: String, val invitedAt: Long)

/** A membership payment I signed: its receipt goes to the private chat with the owner once it confirms (SM2). */
@Entity(tableName = "space_receipt")
data class SpaceReceiptEntity(@PrimaryKey val spaceId: String, val transferId: String, val amountMinor: String, val blockchain: String, val note: String, val at: Long)

/** Small markers: when "Your spaces" was read ([key] `mine`). */
@Entity(tableName = "space_meta")
data class SpaceMetaEntity(@PrimaryKey val key: String, val value: Long)

@Dao
interface SpacesDao {
    @Query("SELECT * FROM space WHERE spaceId = :spaceId")
    fun observeSpace(spaceId: String): Flow<SpaceEntity?>

    @Query("SELECT * FROM space WHERE spaceId = :spaceId")
    suspend fun space(spaceId: String): SpaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSpace(row: SpaceEntity)

    @Query("DELETE FROM space WHERE spaceId = :spaceId")
    suspend fun deleteSpace(spaceId: String)

    @Query("SELECT * FROM space_mine ORDER BY activityAt DESC")
    fun observeMine(): Flow<List<MySpaceEntity>>

    @Query("SELECT * FROM space_mine WHERE spaceId = :spaceId")
    suspend fun mine(spaceId: String): MySpaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMine(rows: List<MySpaceEntity>)

    @Query("DELETE FROM space_mine WHERE spaceId NOT IN (:keep)")
    suspend fun keepMine(keep: List<String>)

    @Query("DELETE FROM space_mine")
    suspend fun clearMine()

    @Query("DELETE FROM space_mine WHERE spaceId = :spaceId")
    suspend fun deleteMine(spaceId: String)

    @Query("SELECT * FROM space_post WHERE spaceId = :spaceId ORDER BY createdAt")
    fun observePosts(spaceId: String): Flow<List<SpacePostEntity>>

    @Query("SELECT * FROM space_post WHERE localKey = :localKey")
    suspend fun post(localKey: String): SpacePostEntity?

    @Query("SELECT * FROM space_post WHERE spaceId = :spaceId AND postId = :postId")
    suspend fun postById(spaceId: String, postId: String): SpacePostEntity?

    @Query("SELECT * FROM space_post WHERE spaceId = :spaceId AND postId IS NOT NULL ORDER BY createdAt DESC LIMIT 1")
    suspend fun newestSent(spaceId: String): SpacePostEntity?

    @Query("SELECT * FROM space_post WHERE spaceId = :spaceId AND postId IS NOT NULL ORDER BY createdAt ASC LIMIT 1")
    suspend fun oldestSent(spaceId: String): SpacePostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePosts(rows: List<SpacePostEntity>)

    @Query("UPDATE space_post SET state = :state, error = :error WHERE localKey = :localKey")
    suspend fun setPostState(localKey: String, state: String, error: String?)

    @Query("UPDATE space_post SET pendingEmoji = :emoji WHERE localKey = :localKey")
    suspend fun setPendingEmoji(localKey: String, emoji: String?)

    @Query("DELETE FROM space_post WHERE localKey = :localKey")
    suspend fun deletePost(localKey: String)

    @Query("DELETE FROM space_post WHERE spaceId = :spaceId AND state = 'sent'")
    suspend fun clearSentPosts(spaceId: String)

    /** Keeps the newest [keep] sent posts per space, so a busy space doesn't grow the phone's copy without end. */
    @Query("DELETE FROM space_post WHERE spaceId = :spaceId AND state = 'sent' AND localKey NOT IN (SELECT localKey FROM space_post WHERE spaceId = :spaceId AND state = 'sent' ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun trimPosts(spaceId: String, keep: Int)

    @Query("SELECT * FROM space_invite ORDER BY invitedAt DESC")
    fun observeInvites(): Flow<List<SpaceInviteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveInvites(rows: List<SpaceInviteEntity>)

    @Query("DELETE FROM space_invite")
    suspend fun clearInvites()

    @Query("DELETE FROM space_invite WHERE inviteId = :inviteId")
    suspend fun deleteInvite(inviteId: String)

    @Query("DELETE FROM space_invite WHERE spaceId = :spaceId")
    suspend fun deleteInvitesFor(spaceId: String)

    @Query("SELECT * FROM space_receipt WHERE spaceId = :spaceId")
    suspend fun receipt(spaceId: String): SpaceReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveReceipt(row: SpaceReceiptEntity)

    @Query("DELETE FROM space_receipt WHERE spaceId = :spaceId")
    suspend fun deleteReceipt(spaceId: String)

    @Query("SELECT value FROM space_meta WHERE `key` = :key")
    fun observeMeta(key: String): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMeta(row: SpaceMetaEntity)

    @Query("DELETE FROM space")
    suspend fun clearSpaces()

    @Query("DELETE FROM space_post")
    suspend fun clearPosts()

    @Query("DELETE FROM space_receipt")
    suspend fun clearReceipts()

    @Query("DELETE FROM space_meta")
    suspend fun clearMeta()
}
