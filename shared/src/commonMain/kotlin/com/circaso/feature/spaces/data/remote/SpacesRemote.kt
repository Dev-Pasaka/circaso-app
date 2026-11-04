package com.circaso.feature.spaces.data.remote

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.flags.SpacesFlag
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.call
import com.circaso.core.network.idempotencyKey
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Every SP route (references/spaces-api.md). Each write takes the `Idempotency-Key` its caller keeps for retries. */
interface SpacesRemote {
    // Spaces
    suspend fun handleAvailability(handle: String): AppResult<HandleAvailabilityDto>
    suspend fun create(request: CreateSpaceRequest, idempotencyKey: String): AppResult<SpaceDto>
    suspend fun space(spaceId: String): AppResult<SpaceDto>
    suspend fun byHandle(handle: String): AppResult<SpaceDto>
    suspend fun update(spaceId: String, request: UpdateSpaceRequest, idempotencyKey: String): AppResult<UpdatedSpaceDto>
    suspend fun delete(spaceId: String, idempotencyKey: String): AppResult<DeletedDto>
    suspend fun mine(cursor: String? = null, limit: Int = 200): AppResult<MySpacesPage>
    suspend fun suggested(topic: String?, cursor: String? = null, limit: Int = 20): AppResult<SuggestionsPage>
    suspend fun search(q: String, topic: String?, cursor: String? = null, limit: Int = 30): AppResult<SearchPage>

    // Joining
    suspend fun follow(spaceId: String, idempotencyKey: String): AppResult<MeDto>
    suspend fun leave(spaceId: String, idempotencyKey: String): AppResult<LeftDto>
    suspend fun notifications(spaceId: String, level: String, idempotencyKey: String): AppResult<MeDto>
    suspend fun membershipPayment(spaceId: String, idempotencyKey: String): AppResult<MembershipPaymentDto>

    // Invites and requests
    suspend fun request(spaceId: String, idempotencyKey: String): AppResult<MeDto>
    suspend fun cancelRequest(spaceId: String, idempotencyKey: String): AppResult<MeDto>
    suspend fun invites(spaceId: String): AppResult<InvitesDto>
    suspend fun answerRequest(spaceId: String, userId: String, accept: Boolean, idempotencyKey: String): AppResult<RequestAnswerDto>
    suspend fun invite(spaceId: String, userIds: List<String>, idempotencyKey: String): AppResult<InviteResultsDto>
    suspend fun cancelInvite(spaceId: String, inviteId: String, idempotencyKey: String): AppResult<CancelledDto>
    suspend fun myInvites(): AppResult<ReceivedInvitesDto>
    suspend fun acceptInvite(inviteId: String, idempotencyKey: String): AppResult<SpaceDto>
    suspend fun declineInvite(inviteId: String, idempotencyKey: String): AppResult<DeclinedDto>

    // Members, roles, moderation
    suspend fun members(spaceId: String, filter: String? = null, q: String? = null, cursor: String? = null, limit: Int = 50): AppResult<MembersPage>
    suspend fun setRole(spaceId: String, userId: String, role: String, idempotencyKey: String): AppResult<MemberDto>
    suspend fun mute(spaceId: String, userId: String, duration: String, idempotencyKey: String): AppResult<MemberDto>
    suspend fun unmute(spaceId: String, userId: String, idempotencyKey: String): AppResult<MemberDto>
    suspend fun remove(spaceId: String, userId: String, idempotencyKey: String): AppResult<RemovedDto>
    suspend fun ban(spaceId: String, userId: String, banned: Boolean, idempotencyKey: String): AppResult<BannedDto>

    // Space chat
    suspend fun posts(spaceId: String, before: String? = null, after: String? = null, limit: Int = 50): AppResult<PostsPage>
    suspend fun post(spaceId: String, request: NewPostRequest): AppResult<PostDto>
    suspend fun removePost(spaceId: String, postId: String, idempotencyKey: String): AppResult<PostDto>
    suspend fun react(spaceId: String, postId: String, emoji: String?, idempotencyKey: String): AppResult<ReactionAnswerDto>
    suspend fun read(spaceId: String, postId: String, idempotencyKey: String): AppResult<UnreadDto>
    suspend fun prepareMedia(request: SpaceMediaRequest, idempotencyKey: String): AppResult<SpaceMediaUploadDto>
    suspend fun completeMedia(mediaId: String, idempotencyKey: String): AppResult<SpaceMediaDoneDto>

    // Reports
    suspend fun report(spaceId: String, request: ReportRequest, idempotencyKey: String): AppResult<ReportIdDto>
    suspend fun reports(spaceId: String, state: String, cursor: String? = null): AppResult<ReportsPage>
    suspend fun resolve(spaceId: String, postId: String, request: ResolveRequest, idempotencyKey: String): AppResult<ReportGroupDto>
}

/**
 * The spaces routes over Ktor. Every answer teaches [flag] whether this server has the spaces module: `404
 * request.not_found` turns spaces off for the run (a server deployed before the module), anything else turns it on.
 * While the flag is off nothing is sent at all: the call answers [SpacesUnavailable].
 */
class KtorSpacesRemote(private val api: CircasoApi, private val flag: SpacesFlag) : SpacesRemote {
    private fun <T> refused(): AppResult<T> = AppResult.Failure(AppError.Api(SpacesUnavailable, 404, null, null, emptyMap()))

    private suspend inline fun <reified T> sp(noinline block: HttpRequestBuilder.() -> Unit): AppResult<T> {
        if (!flag.isOn) return refused()
        return api.call<T>(block).also(::learn)
    }

    private fun HttpRequestBuilder.write(m: HttpMethod, path: String, key: String) { method = m; url(path); idempotencyKey(key) }
    private fun HttpRequestBuilder.json(body: Any) { contentType(ContentType.Application.Json); setBody(body) }

    override suspend fun handleAvailability(handle: String) = sp<HandleAvailabilityDto> { method = HttpMethod.Get; url("/v1/spaces/handles/${handle.trimStart('@')}/availability") }
    override suspend fun create(request: CreateSpaceRequest, idempotencyKey: String) = sp<SpaceDto> { write(HttpMethod.Post, "/v1/spaces", idempotencyKey); json(request) }
    override suspend fun space(spaceId: String) = sp<SpaceDto> { method = HttpMethod.Get; url("/v1/spaces/$spaceId") }
    override suspend fun byHandle(handle: String) = sp<SpaceDto> { method = HttpMethod.Get; url("/v1/spaces/by-handle/${handle.trimStart('@')}") }
    override suspend fun update(spaceId: String, request: UpdateSpaceRequest, idempotencyKey: String) = sp<UpdatedSpaceDto> { write(HttpMethod.Patch, "/v1/spaces/$spaceId", idempotencyKey); json(request) }
    override suspend fun delete(spaceId: String, idempotencyKey: String) = sp<DeletedDto> { write(HttpMethod.Delete, "/v1/spaces/$spaceId", idempotencyKey) }
    override suspend fun mine(cursor: String?, limit: Int) = sp<MySpacesPage> { method = HttpMethod.Get; url("/v1/spaces/mine"); cursor?.let { parameter("cursor", it) }; parameter("limit", limit) }
    override suspend fun suggested(topic: String?, cursor: String?, limit: Int) = sp<SuggestionsPage> {
        method = HttpMethod.Get; url("/v1/spaces/suggested"); topic?.let { parameter("topic", it) }; cursor?.let { parameter("cursor", it) }; parameter("limit", limit)
    }
    override suspend fun search(q: String, topic: String?, cursor: String?, limit: Int) = sp<SearchPage> {
        method = HttpMethod.Get; url("/v1/spaces/search"); parameter("q", q); topic?.let { parameter("topic", it) }; cursor?.let { parameter("cursor", it) }; parameter("limit", limit)
    }

    override suspend fun follow(spaceId: String, idempotencyKey: String) = sp<MeDto> { write(HttpMethod.Post, "/v1/spaces/$spaceId/follow", idempotencyKey) }
    override suspend fun leave(spaceId: String, idempotencyKey: String) = sp<LeftDto> { write(HttpMethod.Delete, "/v1/spaces/$spaceId/membership", idempotencyKey) }
    override suspend fun notifications(spaceId: String, level: String, idempotencyKey: String) = sp<MeDto> { write(HttpMethod.Put, "/v1/spaces/$spaceId/notifications", idempotencyKey); json(NotificationsRequest(level)) }
    override suspend fun membershipPayment(spaceId: String, idempotencyKey: String) = sp<MembershipPaymentDto> { write(HttpMethod.Post, "/v1/spaces/$spaceId/membership/payment", idempotencyKey) }

    override suspend fun request(spaceId: String, idempotencyKey: String) = sp<MeDto> { write(HttpMethod.Post, "/v1/spaces/$spaceId/requests", idempotencyKey) }
    override suspend fun cancelRequest(spaceId: String, idempotencyKey: String) = sp<MeDto> { write(HttpMethod.Delete, "/v1/spaces/$spaceId/requests/mine", idempotencyKey) }
    override suspend fun invites(spaceId: String) = sp<InvitesDto> { method = HttpMethod.Get; url("/v1/spaces/$spaceId/invites") }
    override suspend fun answerRequest(spaceId: String, userId: String, accept: Boolean, idempotencyKey: String) =
        sp<RequestAnswerDto> { write(HttpMethod.Post, "/v1/spaces/$spaceId/requests/$userId/${if (accept) "accept" else "decline"}", idempotencyKey) }
    override suspend fun invite(spaceId: String, userIds: List<String>, idempotencyKey: String) = sp<InviteResultsDto> { write(HttpMethod.Post, "/v1/spaces/$spaceId/invites", idempotencyKey); json(InviteRequest(userIds)) }
    override suspend fun cancelInvite(spaceId: String, inviteId: String, idempotencyKey: String) = sp<CancelledDto> { write(HttpMethod.Delete, "/v1/spaces/$spaceId/invites/$inviteId", idempotencyKey) }
    override suspend fun myInvites() = sp<ReceivedInvitesDto> { method = HttpMethod.Get; url("/v1/spaces/invites/mine") }
    override suspend fun acceptInvite(inviteId: String, idempotencyKey: String) = sp<SpaceDto> { write(HttpMethod.Post, "/v1/spaces/invites/$inviteId/accept", idempotencyKey) }
    override suspend fun declineInvite(inviteId: String, idempotencyKey: String) = sp<DeclinedDto> { write(HttpMethod.Post, "/v1/spaces/invites/$inviteId/decline", idempotencyKey) }

    override suspend fun members(spaceId: String, filter: String?, q: String?, cursor: String?, limit: Int) = sp<MembersPage> {
        method = HttpMethod.Get; url("/v1/spaces/$spaceId/members")
        filter?.let { parameter("filter", it) }; q?.takeIf { it.isNotBlank() }?.let { parameter("q", it) }; cursor?.let { parameter("cursor", it) }; parameter("limit", limit)
    }
    override suspend fun setRole(spaceId: String, userId: String, role: String, idempotencyKey: String) = sp<MemberDto> { write(HttpMethod.Put, "/v1/spaces/$spaceId/members/$userId/role", idempotencyKey); json(RoleRequest(role)) }
    override suspend fun mute(spaceId: String, userId: String, duration: String, idempotencyKey: String) = sp<MemberDto> { write(HttpMethod.Put, "/v1/spaces/$spaceId/members/$userId/mute", idempotencyKey); json(MuteRequest(duration)) }
    override suspend fun unmute(spaceId: String, userId: String, idempotencyKey: String) = sp<MemberDto> { write(HttpMethod.Delete, "/v1/spaces/$spaceId/members/$userId/mute", idempotencyKey) }
    override suspend fun remove(spaceId: String, userId: String, idempotencyKey: String) = sp<RemovedDto> { write(HttpMethod.Delete, "/v1/spaces/$spaceId/members/$userId", idempotencyKey) }
    override suspend fun ban(spaceId: String, userId: String, banned: Boolean, idempotencyKey: String) =
        sp<BannedDto> { write(if (banned) HttpMethod.Put else HttpMethod.Delete, "/v1/spaces/$spaceId/bans/$userId", idempotencyKey) }

    override suspend fun posts(spaceId: String, before: String?, after: String?, limit: Int) = sp<PostsPage> {
        method = HttpMethod.Get; url("/v1/spaces/$spaceId/posts"); before?.let { parameter("before", it) }; after?.let { parameter("after", it) }; parameter("limit", limit)
    }
    // The client id makes a retry return the first answer, so it is also the idempotency key (as moments and messages).
    override suspend fun post(spaceId: String, request: NewPostRequest) = sp<PostDto> { write(HttpMethod.Post, "/v1/spaces/$spaceId/posts", request.clientPostId); json(request) }
    override suspend fun removePost(spaceId: String, postId: String, idempotencyKey: String) = sp<PostDto> { write(HttpMethod.Delete, "/v1/spaces/$spaceId/posts/$postId", idempotencyKey) }
    override suspend fun react(spaceId: String, postId: String, emoji: String?, idempotencyKey: String) = sp<ReactionAnswerDto> {
        write(HttpMethod.Put, "/v1/spaces/$spaceId/posts/$postId/reaction", idempotencyKey)
        // `null` takes the reaction back; the app's Json leaves nulls out, so the body is built by hand.
        json(buildJsonObject { put("emoji", emoji?.let { JsonPrimitive(it) } ?: JsonNull) })
    }
    override suspend fun read(spaceId: String, postId: String, idempotencyKey: String) = sp<UnreadDto> { write(HttpMethod.Post, "/v1/spaces/$spaceId/read", idempotencyKey); json(ReadRequest(postId)) }
    override suspend fun prepareMedia(request: SpaceMediaRequest, idempotencyKey: String) = sp<SpaceMediaUploadDto> { write(HttpMethod.Post, "/v1/spaces/media", idempotencyKey); json(request) }
    override suspend fun completeMedia(mediaId: String, idempotencyKey: String) = sp<SpaceMediaDoneDto> { write(HttpMethod.Post, "/v1/spaces/media/$mediaId/complete", idempotencyKey) }

    override suspend fun report(spaceId: String, request: ReportRequest, idempotencyKey: String) = sp<ReportIdDto> { write(HttpMethod.Post, "/v1/spaces/$spaceId/reports", idempotencyKey); json(request) }
    override suspend fun reports(spaceId: String, state: String, cursor: String?) = sp<ReportsPage> {
        method = HttpMethod.Get; url("/v1/spaces/$spaceId/reports"); parameter("state", state); cursor?.let { parameter("cursor", it) }
    }
    override suspend fun resolve(spaceId: String, postId: String, request: ResolveRequest, idempotencyKey: String) =
        sp<ReportGroupDto> { write(HttpMethod.Post, "/v1/spaces/$spaceId/reports/$postId/resolve", idempotencyKey); json(request) }

    /** What an SP route's answer says about the server. */
    private fun learn(result: AppResult<*>) {
        when (result) {
            is AppResult.Success -> flag.onServerAnswered()
            is AppResult.Failure -> {
                val e = result.error as? AppError.Api ?: return
                if (e.code == "request.not_found" && e.httpStatus == 404) flag.onServerMissing()
                else if (e.code.startsWith("spaces.")) flag.onServerAnswered()
            }
        }
    }

    companion object {
        /** Nothing was sent: this server has no spaces module (or spaces are off on this phone). */
        const val SpacesUnavailable = "spaces.unavailable"
    }
}
