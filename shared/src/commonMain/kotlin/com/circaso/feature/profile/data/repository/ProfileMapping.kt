package com.circaso.feature.profile.data.repository

import com.circaso.feature.profile.data.local.ProfileEntity
import com.circaso.feature.profile.data.remote.HandleAvailabilityResponse
import com.circaso.feature.profile.data.remote.MyProfileResponse
import com.circaso.feature.profile.data.remote.PublicProfileDto
import com.circaso.feature.profile.domain.HandleAvailability
import com.circaso.feature.profile.domain.HandleStatus
import com.circaso.feature.profile.domain.Privacy
import com.circaso.feature.profile.domain.Profile
import com.circaso.feature.profile.domain.PublicProfile

internal fun MyProfileResponse.toEntity(pendingSync: Boolean) = ProfileEntity(
    userId = userId,
    displayName = displayName,
    about = about,
    handle = handle,
    handleChangeableAt = handleChangeableAt,
    avatarUrl = avatarUrl,
    privacyAvatar = privacy.avatar,
    privacyAbout = privacy.about,
    privacyHandleLookup = privacy.handleLookup,
    version = version,
    pendingSync = pendingSync,
)

internal fun ProfileEntity.toDomain() = Profile(
    userId = userId,
    displayName = displayName,
    about = about,
    handle = handle,
    handleChangeableAt = handleChangeableAt,
    avatarUrl = avatarUrl,
    privacy = Privacy(privacyAvatar, privacyAbout, privacyHandleLookup),
    version = version,
    pendingSync = pendingSync,
)

internal fun PublicProfileDto.toDomain() = PublicProfile(userId, displayName, handle, avatarUrl, about)

internal fun HandleAvailabilityResponse.toDomain() = HandleAvailability(
    handle = handle,
    available = available,
    status = when (status.lowercase()) {
        "available" -> HandleStatus.Available
        "taken" -> HandleStatus.Taken
        "invalid" -> HandleStatus.Invalid
        else -> HandleStatus.Unknown
    },
    suggestion = suggestion,
)
