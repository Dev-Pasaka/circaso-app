package com.circaso.feature.calls.data

import com.circaso.core.common.AppResult
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.call
import com.circaso.core.network.idempotencyKey
import com.circaso.core.network.newIdempotencyKey
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Guide CL1–CL8 (backend `calls` module): names as the wire has them.
@Serializable data class IceServerDto(val urls: List<String>, val username: String? = null, val credential: String? = null)
@Serializable data class CallTrackDto(val trackName: String, val kind: String)
@Serializable data class ParticipantDto(
    val userId: String, val displayName: String = "", val state: String, val audio: Boolean = true, val video: Boolean = false,
    val tracks: List<CallTrackDto> = emptyList(), val joinedAt: String? = null,
    /** Changes on every (re)join: pull this person again when it does. */
    val connection: String? = null,
)
@Serializable data class CallDto(
    val callId: String, val chatId: String, val kind: String, val state: String, val startedBy: String, val createdAt: String,
    val startedAt: String? = null, val endedAt: String? = null, val endReason: String? = null,
    val participants: List<ParticipantDto> = emptyList(), val iceServers: List<IceServerDto> = emptyList(),
)
@Serializable data class StartCallRequest(val chatId: String, val video: Boolean)
@Serializable data class LocalTrackDto(val mid: String, val trackName: String, val kind: String)
@Serializable data class JoinCallRequest(val offer: String, val tracks: List<LocalTrackDto>)
@Serializable data class JoinCallResponse(val sessionId: String, val answer: String, val call: CallDto)
@Serializable data class PullTrackRequest(val userId: String, val trackName: String)
@Serializable data class PullRequest(val sessionId: String, val tracks: List<PullTrackRequest>)
@Serializable data class PulledTrackDto(val userId: String, val trackName: String, val mid: String? = null)
@Serializable data class PullResponse(val offer: String? = null, val requiresRenegotiation: Boolean = false, val tracks: List<PulledTrackDto> = emptyList())
@Serializable data class RenegotiateRequest(val sessionId: String, val answer: String)
@Serializable data class MediaRequest(val audio: Boolean, val video: Boolean)
@Serializable data class RingRequest(val userIds: List<String> = emptyList())

interface CallsRemote {
    suspend fun start(chatId: String, video: Boolean, key: String): AppResult<CallDto>
    suspend fun get(callId: String): AppResult<CallDto>
    suspend fun forChat(chatId: String): AppResult<CallDto>
    suspend fun join(callId: String, request: JoinCallRequest): AppResult<JoinCallResponse>
    suspend fun pull(callId: String, request: PullRequest): AppResult<PullResponse>
    suspend fun renegotiate(callId: String, request: RenegotiateRequest): AppResult<JsonObject>
    suspend fun decline(callId: String): AppResult<CallDto>
    suspend fun leave(callId: String): AppResult<CallDto>
    suspend fun media(callId: String, audio: Boolean, video: Boolean): AppResult<JsonObject>
    /** CL9: ring members who aren't on a group call again; empty is everyone not on it. */
    suspend fun ring(callId: String, userIds: List<String>): AppResult<CallDto>
}

class KtorCallsRemote(private val api: CircasoApi) : CallsRemote {
    override suspend fun start(chatId: String, video: Boolean, key: String) = api.call<CallDto> {
        method = HttpMethod.Post; url("/v1/calls"); idempotencyKey(key); contentType(ContentType.Application.Json); setBody(StartCallRequest(chatId, video))
    }
    override suspend fun get(callId: String) = api.call<CallDto> { method = HttpMethod.Get; url("/v1/calls/$callId") }
    override suspend fun forChat(chatId: String) = api.call<CallDto> { method = HttpMethod.Get; url("/v1/chats/$chatId/call") }
    override suspend fun join(callId: String, request: JoinCallRequest) = api.call<JoinCallResponse> {
        method = HttpMethod.Post; url("/v1/calls/$callId/join"); idempotencyKey(newIdempotencyKey()); contentType(ContentType.Application.Json); setBody(request)
    }
    override suspend fun pull(callId: String, request: PullRequest) = api.call<PullResponse> {
        method = HttpMethod.Post; url("/v1/calls/$callId/pull"); idempotencyKey(newIdempotencyKey()); contentType(ContentType.Application.Json); setBody(request)
    }
    override suspend fun renegotiate(callId: String, request: RenegotiateRequest) = api.call<JsonObject> {
        method = HttpMethod.Post; url("/v1/calls/$callId/renegotiate"); idempotencyKey(newIdempotencyKey()); contentType(ContentType.Application.Json); setBody(request)
    }
    override suspend fun decline(callId: String) = api.call<CallDto> { method = HttpMethod.Post; url("/v1/calls/$callId/decline"); idempotencyKey(newIdempotencyKey()) }
    override suspend fun leave(callId: String) = api.call<CallDto> { method = HttpMethod.Post; url("/v1/calls/$callId/leave"); idempotencyKey(newIdempotencyKey()) }
    override suspend fun ring(callId: String, userIds: List<String>) = api.call<CallDto> {
        method = HttpMethod.Post; url("/v1/calls/$callId/ring"); idempotencyKey(newIdempotencyKey()); contentType(ContentType.Application.Json); setBody(RingRequest(userIds))
    }
    override suspend fun media(callId: String, audio: Boolean, video: Boolean) = api.call<JsonObject> {
        method = HttpMethod.Post; url("/v1/calls/$callId/media"); idempotencyKey(newIdempotencyKey()); contentType(ContentType.Application.Json); setBody(MediaRequest(audio, video))
    }
}
