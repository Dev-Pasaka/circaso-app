package com.circaso.core.network

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NetworkRulesTest {
    private val json = circasoJson()

    @Serializable
    data class Nonce(val nonce: String, val expiresInSeconds: Int)

    private fun <T> parse(body: String, s: kotlinx.serialization.KSerializer<T>) =
        json.decodeFromString(Envelope.serializer(s), body)

    @Test
    fun successEnvelopeGivesData() {
        val env = parse("""{"httpStatusCode":200,"status":true,"message":"Success","requestId":"r1","data":{"nonce":"abc","expiresInSeconds":300,"extra":1}}""", Nonce.serializer())
        val result = mapEnvelope(200, env, null)
        assertEquals(AppResult.Success(Nonce("abc", 300)), result)
    }

    @Test
    fun failureEnvelopeKeepsCodeAndRetryAfter() {
        val env = parse("""{"httpStatusCode":429,"status":false,"code":"request.rate_limited","message":"x","requestId":"r2","details":{"retryAfterSeconds":42,"fields":[{"field":"phone","problem":"bad"}]},"data":null}""", Nonce.serializer())
        val error = assertIs<AppResult.Failure>(mapEnvelope(429, env, 5)).error
        val api = assertIs<AppError.Api>(error)
        assertEquals("request.rate_limited", api.code)
        assertEquals(42, api.retryAfterSeconds)
        assertEquals("r2", api.requestId)
        assertEquals(mapOf("phone" to "bad"), api.fields)
    }

    @Test
    fun retryAfterHeaderIsUsedWhenBodyHasNone() {
        val env = parse("""{"httpStatusCode":429,"status":false,"code":"request.rate_limited"}""", Nonce.serializer())
        val api = assertIs<AppError.Api>(assertIs<AppResult.Failure>(mapEnvelope(429, env, 7)).error)
        assertEquals(7, api.retryAfterSeconds)
    }

    @Test
    fun answerThatIsNotAnEnvelopeIsUnexpected() {
        val error = assertIs<AppResult.Failure>(mapEnvelope<Nonce>(502, null, null)).error
        assertEquals(AppError.Unexpected(502), error)
    }

    @Test
    fun successWithoutDataIsUnexpected() {
        val env = parse("""{"httpStatusCode":200,"status":true}""", Nonce.serializer())
        assertIs<AppError.Unexpected>(assertIs<AppResult.Failure>(mapEnvelope(200, env, null)).error)
    }

    @Test
    fun noBodyRoutesParse() {
        val env = parse("""{"httpStatusCode":200,"status":true,"data":{}}""", NoBody.serializer())
        assertIs<AppResult.Success<NoBody>>(mapEnvelope(200, env, null))
    }

    @Test
    fun unreachableAfterThreeNetworkFailuresAndRecoversOnSuccess() {
        val health = NetworkHealth()
        health.onNetworkFailure(); health.onNetworkFailure()
        assertTrue(health.reachable.value)
        health.onNetworkFailure()
        assertFalse(health.reachable.value)
        health.onSuccess()
        assertTrue(health.reachable.value)
    }

    @Test
    fun aSuccessBetweenFailuresResetsTheCount() {
        val health = NetworkHealth()
        health.onNetworkFailure(); health.onNetworkFailure(); health.onSuccess()
        health.onNetworkFailure(); health.onNetworkFailure()
        assertTrue(health.reachable.value)
    }

    @Test
    fun idempotencyKeysAreUnique() {
        assertNotEquals(newIdempotencyKey(), newIdempotencyKey())
    }
}
