package com.circaso.feature.profile.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxEntity
import com.circaso.core.sync.OutboxHandler
import com.circaso.feature.profile.data.local.ProfileDao
import com.circaso.feature.profile.data.remote.PrivacyChangeDto
import com.circaso.feature.profile.data.remote.ProfileRemote
import com.circaso.feature.profile.data.remote.UpdateProfileRequest
import com.circaso.feature.profile.domain.ProfileChange
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a queued profile edit carries: only the fields being changed. `about = ""` clears it. */
@Serializable
data class ProfileEditPayload(
    val displayName: String? = null,
    val about: String? = null,
    val avatarPrivacy: String? = null,
    val aboutPrivacy: String? = null,
    val handleLookup: Boolean? = null,
) {
    /** The newer edit wins field by field. */
    fun mergedWith(newer: ProfileEditPayload) = ProfileEditPayload(
        displayName = newer.displayName ?: displayName,
        about = newer.about ?: about,
        avatarPrivacy = newer.avatarPrivacy ?: avatarPrivacy,
        aboutPrivacy = newer.aboutPrivacy ?: aboutPrivacy,
        handleLookup = newer.handleLookup ?: handleLookup,
    )

    companion object {
        fun from(change: ProfileChange) = ProfileEditPayload(change.displayName, change.about, change.avatarPrivacy, change.aboutPrivacy, change.handleLookup)
    }
}

/**
 * Sends a queued profile edit with the key it was queued with. The version sent is the one the phone last saw from the
 * server, so a change made elsewhere meanwhile comes back as `users.version_conflict` instead of being overwritten.
 */
class ProfileUpdateHandler(
    private val remote: ProfileRemote,
    private val dao: ProfileDao,
    private val outbox: Outbox,
    private val json: Json,
) : OutboxHandler {
    override val type = Type

    override suspend fun send(item: OutboxEntity): AppResult<Unit> {
        val edit = json.decodeFromString(ProfileEditPayload.serializer(), item.payload)
        val current = dao.current() ?: return AppResult.Failure(AppError.Unexpected(null))
        val privacy = if (edit.avatarPrivacy != null || edit.aboutPrivacy != null || edit.handleLookup != null) {
            PrivacyChangeDto(edit.avatarPrivacy, edit.aboutPrivacy, edit.handleLookup)
        } else null
        val request = UpdateProfileRequest(displayName = edit.displayName, about = edit.about, privacy = privacy, version = current.version)
        return when (val result = remote.updateProfile(request, item.idempotencyKey)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                // This item is still counted as waiting until it is marked done, so "others waiting" means more than one.
                val othersWaiting = outbox.waiting(Type) > 1
                dao.upsert(result.value.toEntity(pendingSync = othersWaiting))
                AppResult.Success(Unit)
            }
        }
    }

    companion object {
        const val Type = "profile.update"
    }
}
