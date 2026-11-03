package com.circaso.feature.profile.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.map
import com.circaso.core.sync.Outbox
import com.circaso.feature.profile.data.local.ProfileDao
import com.circaso.feature.profile.data.remote.AvatarUploader
import com.circaso.feature.profile.data.remote.ClaimHandleRequest
import com.circaso.feature.profile.data.remote.MyProfileResponse
import com.circaso.feature.profile.data.remote.ProfileRemote
import com.circaso.feature.profile.data.remote.SetAvatarRequest
import com.circaso.feature.profile.data.remote.UploadUrlRequest
import com.circaso.feature.profile.domain.AvatarImage
import com.circaso.feature.profile.domain.HandleAvailability
import com.circaso.feature.profile.domain.Profile
import com.circaso.feature.profile.domain.ProfileChange
import com.circaso.feature.profile.domain.ProfileRepository
import com.circaso.feature.profile.domain.PublicProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class ProfileRepositoryImpl(
    private val remote: ProfileRemote,
    private val dao: ProfileDao,
    private val outbox: Outbox,
    private val uploader: AvatarUploader,
    private val json: Json,
    /** Asks the outbox to send now (does nothing if offline). */
    private val kickOutbox: () -> Unit,
    /** Keeps a new profile photo going when the person leaves Circaso mid-upload. */
    private val uploads: com.circaso.core.platform.BackgroundUploads = com.circaso.core.platform.NoBackgroundUploads,
) : ProfileRepository {

    override val profile: Flow<Profile?> = dao.observe().map { it?.toDomain() }

    override suspend fun refresh(): AppResult<Unit> = when (val result = remote.profile()) {
        is AppResult.Failure -> result
        is AppResult.Success -> {
            val current = dao.current()
            if (current != null && current.pendingSync && current.userId == result.value.userId) {
                // An edit is waiting to be sent: keep it. Only the picture link (which expires) is refreshed; a clash
                // with another device shows up as a version conflict when the edit is sent, never as a silent overwrite.
                dao.upsert(current.copy(avatarUrl = result.value.avatarUrl))
            } else {
                dao.upsert(result.value.toEntity(pendingSync = false))
            }
            AppResult.Success(Unit)
        }
    }

    override suspend fun update(change: ProfileChange): Boolean {
        if (change.isEmpty) return false
        val current = dao.current() ?: return false
        dao.upsert(
            current.copy(
                displayName = change.displayName ?: current.displayName,
                about = change.about?.ifEmpty { null } ?: if (change.about == "") null else current.about,
                privacyAvatar = change.avatarPrivacy ?: current.privacyAvatar,
                privacyAbout = change.aboutPrivacy ?: current.privacyAbout,
                privacyHandleLookup = change.handleLookup ?: current.privacyHandleLookup,
                pendingSync = true,
            ),
        )
        val payload = json.encodeToString(ProfileEditPayload.serializer(), ProfileEditPayload.from(change))
        outbox.enqueueOrMerge(ProfileUpdateHandler.Type, payload) { old, new ->
            json.encodeToString(
                ProfileEditPayload.serializer(),
                json.decodeFromString(ProfileEditPayload.serializer(), old).mergedWith(json.decodeFromString(ProfileEditPayload.serializer(), new)),
            )
        }
        kickOutbox()
        return true
    }

    override suspend fun checkHandle(handle: String): AppResult<HandleAvailability> =
        remote.handleAvailability(handle).map { it.toDomain() }

    override suspend fun claimHandle(handle: String): AppResult<Unit> = saved(remote.claimHandle(ClaimHandleRequest(handle)))

    override suspend fun uploadAvatar(image: AvatarImage): AppResult<Unit> =
        uploads.keep(com.circaso.core.platform.UploadKind.ProfilePhoto) { putAvatar(image) }.also { result ->
            // Told only if it can't go by itself: a lost connection is the screen's to show when they're back.
            val error = (result as? AppResult.Failure)?.error
            if (error is AppError.Api && error.httpStatus < 500 && error.httpStatus != 429) uploads.failed(com.circaso.core.platform.UploadKind.ProfilePhoto)
        }

    private suspend fun putAvatar(image: AvatarImage): AppResult<Unit> {
        if (image.contentType !in AllowedImageTypes || image.bytes.size > MaxAvatarBytes || image.bytes.isEmpty()) {
            return AppResult.Failure(AppError.Api("users.avatar_invalid", 400, null, null, emptyMap()))
        }
        val target = when (val r = remote.avatarUploadUrl(UploadUrlRequest(image.contentType, image.bytes.size))) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return r
        }
        when (val put = uploader.put(target.uploadUrl, image.bytes, image.contentType)) {
            is AppResult.Failure -> return put
            is AppResult.Success -> Unit
        }
        return saved(remote.setAvatar(SetAvatarRequest(target.objectKey)))
    }

    override suspend fun removeAvatar(): AppResult<Unit> = saved(remote.removeAvatar())

    override suspend fun findByHandle(handle: String): AppResult<PublicProfile> = remote.byHandle(handle).map { it.toDomain() }

    override suspend fun publicProfile(userId: String): AppResult<PublicProfile> = remote.user(userId).map { it.toDomain() }

    /** Online-only changes answer with the whole profile: store it as the confirmed truth. */
    private suspend fun saved(result: AppResult<MyProfileResponse>): AppResult<Unit> = when (result) {
        is AppResult.Failure -> result
        is AppResult.Success -> {
            val pending = dao.current()?.pendingSync == true
            dao.upsert(result.value.toEntity(pendingSync = pending))
            AppResult.Success(Unit)
        }
    }

    private companion object {
        val AllowedImageTypes = setOf("image/jpeg", "image/png", "image/webp")
        const val MaxAvatarBytes = 5 * 1024 * 1024
    }
}
