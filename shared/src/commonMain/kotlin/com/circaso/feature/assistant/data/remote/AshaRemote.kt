package com.circaso.feature.assistant.data.remote

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.AccessTokenProvider
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.Envelope
import com.circaso.core.network.NoBody
import com.circaso.core.network.call
import com.circaso.core.network.idempotencyKey
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.session.AuthManager
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

interface AshaRemote {
    suspend fun history(cursor: String?, limit: Int): AppResult<ChatResponse>
    suspend fun deleteAll(idempotencyKey: String? = null): AppResult<NoBody>
    suspend fun deleteMessage(messageId: String, idempotencyKey: String? = null): AppResult<NoBody>
    suspend fun toolResults(turnId: String, request: ToolResultsRequest): AppResult<NoBody>
    suspend fun transcribe(audio: ByteArray, contentType: String, seconds: Int): AppResult<TranscriptResponse>
    suspend fun speech(request: SpeechRequest): AppResult<ByteArray>
    suspend fun voices(): AppResult<VoicesResponse>
    suspend fun conversations(): AppResult<ConversationsResponse> = AppResult.Success(ConversationsResponse())
    suspend fun createConversation(idempotencyKey: String): AppResult<ConversationDto> = AppResult.Failure(AppError.Network)
    suspend fun renameConversation(conversationId: String, title: String, idempotencyKey: String): AppResult<ConversationDto> = AppResult.Failure(AppError.Network)
    suspend fun deleteConversation(conversationId: String, idempotencyKey: String): AppResult<NoBody> = AppResult.Failure(AppError.Network)
    /** A13: a short sample of a voice (MP3), free of the allowance, for the voice picker. */
    suspend fun voiceSample(voiceId: String): AppResult<ByteArray>
    suspend fun usage(): AppResult<UsageResponse>
    /** A14: whether Asha can answer now (2026-11-07). */
    suspend fun status(): AppResult<AssistantStatusResponse> = AppResult.Success(AssistantStatusResponse())

    /** Posts the question and streams the answer. Failures before any text arrive as a single failed result. */
    fun stream(request: SendMessageRequest): Flow<AppResult<SseEvent>>
}

class KtorAshaRemote(
    private val api: CircasoApi,
    private val client: HttpClient,
    private val tokens: AccessTokenProvider,
    private val auth: AuthManager,
    private val json: Json,
) : AshaRemote {
    override suspend fun history(cursor: String?, limit: Int) = api.call<ChatResponse> {
        method = HttpMethod.Get
        url("/v1/assistant/chat")
        parameter("limit", limit)
        cursor?.let { parameter("cursor", it) }
    }

    override suspend fun deleteAll(idempotencyKey: String?) = api.call<NoBody> {
        method = HttpMethod.Delete
        url("/v1/assistant/chat")
        idempotencyKey?.let { idempotencyKey(it) }
    }

    override suspend fun deleteMessage(messageId: String, idempotencyKey: String?) = api.call<NoBody> {
        method = HttpMethod.Delete
        url("/v1/assistant/chat/messages/$messageId")
        idempotencyKey?.let { idempotencyKey(it) }
    }

    override suspend fun toolResults(turnId: String, request: ToolResultsRequest) = api.call<NoBody> {
        method = HttpMethod.Post
        url("/v1/assistant/chat/messages/$turnId/tool-results")
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun status() = api.call<AssistantStatusResponse> {
        method = HttpMethod.Get
        url("/v1/assistant/status")
    }

    override suspend fun transcribe(audio: ByteArray, contentType: String, seconds: Int) = api.call<TranscriptResponse> {
        method = HttpMethod.Post
        url("/v1/assistant/voice")
        header(HttpHeaders.ContentType, contentType)
        header("X-Duration-Seconds", seconds.toString())
        setBody(audio)
    }

    /** The answer is audio, not the envelope; an error is the envelope. */
    override suspend fun speech(request: SpeechRequest): AppResult<ByteArray> = try {
        val token = tokens.accessToken()
        val response = client.prepareRequest {
            method = HttpMethod.Post
            url("/v1/assistant/speech")
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            header("Idempotency-Key", newIdempotencyKey())
            contentType(ContentType.Application.Json)
            setBody(request)
        }.execute { r ->
            if (r.status.value == 200) AppResult.Success(r.body<ByteArray>())
            else AppResult.Failure(errorFrom(r.status.value, r.bodyAsText()))
        }
        response
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppResult.Failure(AppError.Network)
    }

    override suspend fun conversations() = api.call<ConversationsResponse> {
        method = HttpMethod.Get
        url("/v1/assistant/conversations")
        parameter("limit", 100)
    }

    override suspend fun createConversation(idempotencyKey: String) = api.call<ConversationDto> {
        method = HttpMethod.Post
        url("/v1/assistant/conversations")
        idempotencyKey(idempotencyKey)
        contentType(ContentType.Application.Json)
        setBody(ConversationRequest())
    }

    override suspend fun renameConversation(conversationId: String, title: String, idempotencyKey: String) = api.call<ConversationDto> {
        method = HttpMethod.Patch
        url("/v1/assistant/conversations/$conversationId")
        idempotencyKey(idempotencyKey)
        contentType(ContentType.Application.Json)
        setBody(ConversationRequest(title))
    }

    override suspend fun deleteConversation(conversationId: String, idempotencyKey: String) = api.call<NoBody> {
        method = HttpMethod.Delete
        url("/v1/assistant/conversations/$conversationId")
        idempotencyKey(idempotencyKey)
    }

    override suspend fun voices() = api.call<VoicesResponse> {
        method = HttpMethod.Get
        url("/v1/assistant/voices")
    }

    override suspend fun voiceSample(voiceId: String): AppResult<ByteArray> = try {
        val token = tokens.accessToken()
        client.prepareRequest {
            method = HttpMethod.Get
            url("/v1/assistant/voices/$voiceId/sample")
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }.execute { r ->
            if (r.status.value == 200) AppResult.Success(r.body<ByteArray>())
            else AppResult.Failure(errorFrom(r.status.value, r.bodyAsText()))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppResult.Failure(AppError.Network)
    }

    override suspend fun usage() = api.call<UsageResponse> {
        method = HttpMethod.Get
        url("/v1/assistant/usage")
    }

    override fun stream(request: SendMessageRequest): Flow<AppResult<SseEvent>> = flow {
        val token = tokens.accessToken()
        val agent = auth.agentToken()
        try {
            client.prepareRequest {
                method = HttpMethod.Post
                url("/v1/assistant/chat/messages")
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                agent?.let { header("X-Agent-Token", it) }
                header("Idempotency-Key", newIdempotencyKey())
                header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(request)
            }.execute { response ->
                if (response.status.value != 200) {
                    emit(AppResult.Failure(errorFrom(response.status.value, response.bodyAsText())))
                    return@execute
                }
                val parser = SseParser()
                val channel = response.bodyAsChannel()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    parser.feed(line)?.let { emit(AppResult.Success(it)) }
                }
                parser.finish()?.let { emit(AppResult.Success(it)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(AppResult.Failure(AppError.Network))
        }
    }

    private fun errorFrom(status: Int, body: String): AppError {
        val envelope = try { json.decodeFromString(Envelope.serializer(JsonElement.serializer()), body) } catch (e: Exception) { null }
            ?: return AppError.Unexpected(status)
        return AppError.Api(envelope.code ?: "request.unknown", status, envelope.requestId, envelope.details?.retryAfterSeconds, envelope.details?.fieldProblems().orEmpty())
    }
}
