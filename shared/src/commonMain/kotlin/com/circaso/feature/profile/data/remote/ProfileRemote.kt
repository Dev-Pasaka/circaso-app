package com.circaso.feature.profile.data.remote

import com.circaso.core.common.AppResult
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.call
import com.circaso.core.network.idempotencyKey
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

/** The profile, handle and avatar routes. Writes take the key to send, so a queued retry reuses it. */
interface ProfileRemote {
    suspend fun profile(): AppResult<MyProfileResponse>
    suspend fun updateProfile(request: UpdateProfileRequest, idempotencyKey: String? = null): AppResult<MyProfileResponse>
    suspend fun avatarUploadUrl(request: UploadUrlRequest): AppResult<UploadUrlResponse>
    suspend fun setAvatar(request: SetAvatarRequest): AppResult<MyProfileResponse>
    suspend fun removeAvatar(): AppResult<MyProfileResponse>
    suspend fun handleAvailability(handle: String): AppResult<HandleAvailabilityResponse>
    suspend fun claimHandle(request: ClaimHandleRequest): AppResult<MyProfileResponse>
    suspend fun byHandle(handle: String): AppResult<PublicProfileDto>
    suspend fun user(userId: String): AppResult<PublicProfileDto>
}

class KtorProfileRemote(private val api: CircasoApi) : ProfileRemote {
    override suspend fun profile() = api.call<MyProfileResponse> {
        method = HttpMethod.Get
        url("/v1/me/profile")
    }

    override suspend fun updateProfile(request: UpdateProfileRequest, idempotencyKey: String?) = api.call<MyProfileResponse> {
        method = HttpMethod.Patch
        url("/v1/me/profile")
        idempotencyKey?.let { idempotencyKey(it) }
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun avatarUploadUrl(request: UploadUrlRequest) = api.call<UploadUrlResponse> {
        method = HttpMethod.Post
        url("/v1/me/avatar/upload-url")
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun setAvatar(request: SetAvatarRequest) = api.call<MyProfileResponse> {
        method = HttpMethod.Post
        url("/v1/me/avatar")
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun removeAvatar() = api.call<MyProfileResponse> {
        method = HttpMethod.Delete
        url("/v1/me/avatar")
    }

    override suspend fun handleAvailability(handle: String) = api.call<HandleAvailabilityResponse> {
        method = HttpMethod.Get
        url("/v1/handles/$handle/availability")
    }

    override suspend fun claimHandle(request: ClaimHandleRequest) = api.call<MyProfileResponse> {
        method = HttpMethod.Put
        url("/v1/me/handle")
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun byHandle(handle: String) = api.call<PublicProfileDto> {
        method = HttpMethod.Get
        url("/v1/users/by-handle/$handle")
    }

    override suspend fun user(userId: String) = api.call<PublicProfileDto> {
        method = HttpMethod.Get
        url("/v1/users/$userId")
    }
}
