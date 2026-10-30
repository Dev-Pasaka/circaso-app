package com.circaso.feature.profile.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The signed-in person's own profile, kept so the app opens with it and works offline. One row. */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val about: String?,
    val handle: String?,
    val handleChangeableAt: String?,
    val avatarUrl: String?,
    val privacyAvatar: String,
    val privacyAbout: String,
    val privacyHandleLookup: Boolean,
    val version: Int,
    /** An edit is saved here but not yet confirmed by the server. */
    val pendingSync: Boolean,
)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile LIMIT 1")
    fun observe(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile LIMIT 1")
    suspend fun current(): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profile")
    suspend fun clear()
}
