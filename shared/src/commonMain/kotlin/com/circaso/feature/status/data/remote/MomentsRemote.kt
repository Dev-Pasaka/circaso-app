package com.circaso.feature.status.data.remote

import com.circaso.core.common.AppResult
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.call
import com.circaso.core.network.idempotencyKey
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Moments (backend `status` module), ST1–ST8: references/moments-api.md until the guide has its section.

@Serializable data class PrepareMomentMediaRequest(val sizeBytes: Long, val kind: String)
@Serializable data class MomentUploadHeaders(@SerialName("Content-Type") val contentType: String)
@Serializable data class MomentMediaUploadDto(val mediaId: String, val uploadUrl: String, val uploadHeaders: MomentUploadHeaders, val expiresAt: String)
@Serializable data class MomentMediaCompletedDto(val mediaId: String, val sizeBytes: Long)
@Serializable data class MomentMediaDownloadDto(val downloadUrl: String, val expiresAt: String, val sizeBytes: Long)

@Serializable data class KeyCopyDto(val userId: String, val ciphertext: String)
@Serializable data class PostMomentRequest(
    val clientMomentId: String,
    val ciphertext: String,
    val audience: List<String>,
    val keyCopies: List<KeyCopyDto> = emptyList(),
    val mediaIds: List<String> = emptyList(),
)
@Serializable data class PostedMomentDto(val momentId: String, val postedAt: String, val expiresAt: String)

@Serializable data class MomentDto(
    val momentId: String,
    val posterId: String,
    val ciphertext: String,
    val keyCopy: String? = null,
    val mediaIds: List<String> = emptyList(),
    val postedAt: String,
    val expiresAt: String,
    val mine: Boolean = false,
    val viewed: Boolean = false,
    val reacted: Boolean = false,
    /** My emoji on someone else's moment. */
    val reaction: String? = null,
    /** Only on my own moments; null (or absent) on everyone else's. */
    val viewCount: Int? = null,
    val reactionCount: Int? = null,
)
@Serializable data class MomentsPageDto(val moments: List<MomentDto> = emptyList(), val next: String? = null)

/** `reaction` sets my emoji; `reacted = false` takes it back (nulls are left out of the JSON). */
@Serializable data class ViewMomentRequest(val reacted: Boolean? = null, val reaction: String? = null)
@Serializable data class ViewedDto(val viewed: Boolean)
@Serializable data class MomentViewDto(val userId: String, val viewedAt: String, val reacted: Boolean = false, val reaction: String? = null)
@Serializable data class MomentViewsDto(val views: List<MomentViewDto> = emptyList())
@Serializable data class MomentDeletedDto(val deleted: Boolean)

interface MomentsRemote {
    suspend fun prepareMedia(sizeBytes: Long, kind: String, idempotencyKey: String): AppResult<MomentMediaUploadDto>
    suspend fun completeMedia(mediaId: String, idempotencyKey: String): AppResult<MomentMediaCompletedDto>
    suspend fun post(request: PostMomentRequest): AppResult<PostedMomentDto>
    suspend fun page(after: String?, limit: Int = 200): AppResult<MomentsPageDto>
    suspend fun view(momentId: String, reacted: Boolean?, idempotencyKey: String, reaction: String? = null): AppResult<ViewedDto>
    suspend fun views(momentId: String): AppResult<MomentViewsDto>
    suspend fun delete(momentId: String, idempotencyKey: String): AppResult<MomentDeletedDto>
    suspend fun download(mediaId: String): AppResult<MomentMediaDownloadDto>
}

class KtorMomentsRemote(private val api: CircasoApi) : MomentsRemote {
    override suspend fun prepareMedia(sizeBytes: Long, kind: String, idempotencyKey: String) = api.call<MomentMediaUploadDto> {
        method = HttpMethod.Post; url("/v1/moments/media"); idempotencyKey(idempotencyKey)
        contentType(ContentType.Application.Json); setBody(PrepareMomentMediaRequest(sizeBytes, kind))
    }

    override suspend fun completeMedia(mediaId: String, idempotencyKey: String) = api.call<MomentMediaCompletedDto> {
        method = HttpMethod.Post; url("/v1/moments/media/$mediaId/complete"); idempotencyKey(idempotencyKey)
    }

    // The client id makes a retry return the first answer, so it is also the idempotency key.
    override suspend fun post(request: PostMomentRequest) = api.call<PostedMomentDto> {
        method = HttpMethod.Post; url("/v1/moments"); idempotencyKey(request.clientMomentId)
        contentType(ContentType.Application.Json); setBody(request)
    }

    override suspend fun page(after: String?, limit: Int) = api.call<MomentsPageDto> {
        method = HttpMethod.Get; url("/v1/moments"); after?.let { parameter("after", it) }; parameter("limit", limit)
    }

    override suspend fun view(momentId: String, reacted: Boolean?, idempotencyKey: String, reaction: String?) = api.call<ViewedDto> {
        method = HttpMethod.Post; url("/v1/moments/$momentId/view"); idempotencyKey(idempotencyKey)
        contentType(ContentType.Application.Json); setBody(ViewMomentRequest(reacted, reaction))
    }

    override suspend fun views(momentId: String) = api.call<MomentViewsDto> { method = HttpMethod.Get; url("/v1/moments/$momentId/views") }

    override suspend fun delete(momentId: String, idempotencyKey: String) = api.call<MomentDeletedDto> {
        method = HttpMethod.Delete; url("/v1/moments/$momentId"); idempotencyKey(idempotencyKey)
    }

    override suspend fun download(mediaId: String) = api.call<MomentMediaDownloadDto> { method = HttpMethod.Get; url("/v1/moments/media/$mediaId") }
}
