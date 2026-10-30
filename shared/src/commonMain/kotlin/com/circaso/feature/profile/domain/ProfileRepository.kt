package com.circaso.feature.profile.domain

import com.circaso.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Offline-first: the profile is read from the phone and refreshed in the background. Edits are saved at once and sent
 * when the connection allows (queueable writes). Handle changes and pictures need the server (online-only).
 */
interface ProfileRepository {
    /** The saved profile; null before the first load. */
    val profile: Flow<Profile?>

    suspend fun refresh(): AppResult<Unit>

    /** Saves the change on this phone and queues it. Returns false when there is nothing to change. */
    suspend fun update(change: ProfileChange): Boolean

    suspend fun checkHandle(handle: String): AppResult<HandleAvailability>
    suspend fun claimHandle(handle: String): AppResult<Unit>
    suspend fun uploadAvatar(image: AvatarImage): AppResult<Unit>
    suspend fun removeAvatar(): AppResult<Unit>
    suspend fun findByHandle(handle: String): AppResult<PublicProfile>
    suspend fun publicProfile(userId: String): AppResult<PublicProfile>
}
