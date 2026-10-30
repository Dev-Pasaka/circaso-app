package com.circaso.core.network

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.util.AttributeKey
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val IdempotencyKeyHeader = "Idempotency-Key"

private val SkipAuth = AttributeKey<Boolean>("circaso.skipAuth")

/** For routes whose scope is `none`: send no bearer token. */
fun HttpRequestBuilder.withoutAuth() = attributes.put(SkipAuth, true)

/** True when the request was built with [withoutAuth]. */
internal fun HttpRequestBuilder.skipsAuth() = attributes.contains(SkipAuth)

/** Use a specific token (for example the onboarding token) instead of the session's. */
fun HttpRequestBuilder.bearer(token: String) = bearerAuth(token)

/** Pass the key stored with a queued action, so every retry of the same action reuses it. */
fun HttpRequestBuilder.idempotencyKey(key: String) = header(IdempotencyKeyHeader, key)

@OptIn(ExperimentalUuidApi::class)
fun newIdempotencyKey(): String = Uuid.random().toString()

internal val ReadMethods = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

/**
 * Adds `Authorization` (unless skipped or already set) and a fresh `Idempotency-Key` on every
 * POST, PUT, PATCH and DELETE that does not carry one. A caller retrying an action passes its
 * own key with [idempotencyKey]; this plugin never replaces it.
 */
internal fun requestHeaders(tokens: AccessTokenProvider) = createClientPlugin("CircasoRequestHeaders") {
    onRequest { request, _ ->
        if (request.method !in ReadMethods && !request.headers.contains(IdempotencyKeyHeader)) {
            request.header(IdempotencyKeyHeader, newIdempotencyKey())
        }
        if (!request.attributes.contains(SkipAuth) && !request.headers.contains(HttpHeaders.Authorization)) {
            tokens.accessToken()?.let { request.bearerAuth(it) }
        }
    }
}
