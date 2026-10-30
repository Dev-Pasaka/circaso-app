package com.circaso.core.network

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

const val ClientOutdatedCode = "request.client_outdated"
const val ServiceUnavailableCode = "service.unavailable"

/** Waits before each retry of a `503` `service.unavailable` (the server was briefly out of database connections). */
private val UnavailableBackoffMillis = listOf(1_000L, 2_000L)
private const val MaxUnavailableWaitMillis = 5_000L

/** The server did not accept the access token: refresh once, then retry the call once. */
private val TokenRejectedCodes = setOf("auth.token_invalid", "auth.token_missing")

/** Gets a new access token after the server rejected [rejectedAccessToken]. True when a retry is worth making. */
fun interface SessionRefresher {
    suspend fun refreshAfterRejection(rejectedAccessToken: String?): Boolean
}

/** Runs one call and turns the answer into an [AppResult]. Repositories use this, never the raw client. */
class CircasoApi(
    private val client: HttpClient,
    private val json: Json,
    private val health: NetworkHealth,
    private val events: NetworkEvents,
    private val refresher: Lazy<SessionRefresher>? = null,
) {
    suspend fun <T> call(serializer: KSerializer<T>, block: HttpRequestBuilder.() -> Unit): AppResult<T> {
        val first = callRetryingUnavailable(serializer, block)
        val rejected = first.result as? AppResult.Failure
        val code = (rejected?.error as? AppError.Api)?.code
        val usesSession = !HttpRequestBuilder().apply(block).skipsAuth()
        if (code in TokenRejectedCodes && usesSession && refresher != null) {
            // One refresh, one retry. A new Idempotency-Key is fine: the first attempt was refused before it ran.
            if (refresher.value.refreshAfterRejection(first.bearer)) return callRetryingUnavailable(serializer, block).result
        }
        return first.result
    }

    /**
     * A read that answers a file itself (MAP1's map picture) rather than the envelope. Failures still come as the
     * envelope and read like any other call; a rejected token is refreshed once, as in [call].
     */
    suspend fun bytes(block: HttpRequestBuilder.() -> Unit): AppResult<ByteArray> {
        val first = bytesOnce(block)
        val code = ((first.result as? AppResult.Failure)?.error as? AppError.Api)?.code
        if (code in TokenRejectedCodes && refresher != null && refresher.value.refreshAfterRejection(first.bearer)) {
            return bytesOnce(block).result
        }
        return first.result
    }

    private suspend fun bytesOnce(block: HttpRequestBuilder.() -> Unit): Attempt<ByteArray> {
        val response = try {
            client.request(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            health.onNetworkFailure()
            return Attempt(AppResult.Failure(AppError.Network), null)
        }
        val bearer = response.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
        try {
            if (response.status.isSuccess() && response.contentType()?.match(ContentType.Application.Json) != true) {
                val bytes = response.readRawBytes()
                health.onSuccess()
                return Attempt(AppResult.Success(bytes), bearer)
            }
            val body = response.bodyAsText()
            health.onSuccess()
            val envelope = try {
                json.decodeFromString(Envelope.serializer(JsonElement.serializer()), body)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            val result = mapEnvelope(response.status.value, envelope, response.headers[HttpHeaders.RetryAfter]?.toIntOrNull())
            return Attempt(result as? AppResult.Failure ?: AppResult.Failure(AppError.Unexpected(response.status.value)), bearer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            health.onNetworkFailure()
            return Attempt(AppResult.Failure(AppError.Network), bearer)
        }
    }

    private class Attempt<T>(val result: AppResult<T>, val bearer: String?)

    /**
     * Repeats a call the server answered with `service.unavailable`, a couple of times with backoff. Every repeat
     * carries the same `Idempotency-Key` (fixed here when the caller did not pass one), so the action runs at most once.
     */
    private suspend fun <T> callRetryingUnavailable(serializer: KSerializer<T>, block: HttpRequestBuilder.() -> Unit): Attempt<T> {
        val probe = HttpRequestBuilder().apply(block)
        val keyed: HttpRequestBuilder.() -> Unit =
            if (probe.method !in ReadMethods && !probe.headers.contains(IdempotencyKeyHeader)) {
                val key = newIdempotencyKey()
                ({ block(); idempotencyKey(key) })
            } else block
        var attempt = callOnce(serializer, keyed)
        for (wait in UnavailableBackoffMillis) {
            val error = (attempt.result as? AppResult.Failure)?.error as? AppError.Api
            if (error?.code != ServiceUnavailableCode) break
            delay((error.retryAfterSeconds?.times(1_000L) ?: wait).coerceAtMost(MaxUnavailableWaitMillis))
            attempt = callOnce(serializer, keyed)
        }
        return attempt
    }

    private suspend fun <T> callOnce(serializer: KSerializer<T>, block: HttpRequestBuilder.() -> Unit): Attempt<T> {
        val response = try {
            client.request(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            health.onNetworkFailure()
            return Attempt(AppResult.Failure(AppError.Network), null)
        }
        val bearer = response.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
        val body = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            health.onNetworkFailure()
            return Attempt(AppResult.Failure(AppError.Network), bearer)
        }
        health.onSuccess()
        val envelope = try {
            json.decodeFromString(Envelope.serializer(serializer), body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        val result = mapEnvelope(response.status.value, envelope, response.headers[HttpHeaders.RetryAfter]?.toIntOrNull())
        if (result is AppResult.Failure && (result.error as? AppError.Api)?.code == ClientOutdatedCode) {
            events.onClientOutdated()
        }
        return Attempt(result, bearer)
    }
}

suspend inline fun <reified T> CircasoApi.call(noinline block: HttpRequestBuilder.() -> Unit): AppResult<T> =
    call(serializer<T>(), block)

/** The rules for reading an answer. Kept free of I/O so they can be tested directly. */
internal fun <T> mapEnvelope(httpStatus: Int, envelope: Envelope<T>?, retryAfterHeader: Int?): AppResult<T> {
    if (envelope == null) return AppResult.Failure(AppError.Unexpected(httpStatus))
    if (envelope.status) {
        val data = envelope.data ?: return AppResult.Failure(AppError.Unexpected(httpStatus))
        return AppResult.Success(data)
    }
    return AppResult.Failure(
        AppError.Api(
            code = envelope.code ?: "request.unknown",
            httpStatus = httpStatus,
            requestId = envelope.requestId,
            retryAfterSeconds = envelope.details?.retryAfterSeconds ?: retryAfterHeader,
            fields = envelope.details?.fieldProblems().orEmpty(),
        ),
    )
}
