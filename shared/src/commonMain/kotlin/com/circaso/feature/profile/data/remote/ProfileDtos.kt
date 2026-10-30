package com.circaso.feature.profile.data.remote

import kotlinx.serialization.Serializable

// Named exactly like the schemas in the integration guide (section 11).

@Serializable data class PrivacyDto(val avatar: String, val about: String, val handleLookup: Boolean)

@Serializable data class PrivacyChangeDto(val avatar: String? = null, val about: String? = null, val handleLookup: Boolean? = null)

@Serializable
data class MyProfileResponse(
    val userId: String,
    val displayName: String,
    val about: String? = null,
    val handle: String? = null,
    val handleChangeableAt: String? = null,
    val avatarUrl: String? = null,
    val privacy: PrivacyDto,
    val version: Int,
)

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val about: String? = null,
    val privacy: PrivacyChangeDto? = null,
    val version: Int,
)

@Serializable data class UploadUrlRequest(val contentType: String, val sizeBytes: Int)
@Serializable data class UploadUrlResponse(val uploadUrl: String, val objectKey: String, val expiresInSeconds: Int)
@Serializable data class SetAvatarRequest(val objectKey: String)

@Serializable
data class HandleAvailabilityResponse(val handle: String, val available: Boolean, val status: String, val suggestion: String? = null)

@Serializable data class ClaimHandleRequest(val handle: String)

@Serializable
data class PublicProfileDto(
    val userId: String,
    val displayName: String,
    val handle: String? = null,
    val avatarUrl: String? = null,
    val about: String? = null,
)
