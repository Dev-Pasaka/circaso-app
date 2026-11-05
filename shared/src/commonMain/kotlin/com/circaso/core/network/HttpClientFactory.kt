package com.circaso.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Wire format: camelCase JSON, unknown fields ignored, nulls omitted. Defaults ARE sent: the server needs fields like
 * `keyAlgorithm: "ES256"` even though our code gives them a default value.
 */
fun circasoJson() = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** One client for the whole app. The engine (OkHttp or Darwin) is the one on each platform's classpath. */
fun createHttpClient(config: ApiConfig, tokens: AccessTokenProvider, json: Json): HttpClient = HttpClient {
    expectSuccess = false
    install(ContentNegotiation) { json(json) }
    // Transport pings as well as the server's heartbeat: a dead connection fails within seconds, not never.
    install(WebSockets) { pingIntervalMillis = 20_000 }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
        socketTimeoutMillis = 20_000
    }
    defaultRequest {
        url.takeFrom(config.baseUrl)
        header("X-Client-Version", config.clientVersion)
        config.userAgent?.let { header(io.ktor.http.HttpHeaders.UserAgent, it) }
    }
    install(requestHeaders(tokens))
}

/**
 * A client with no base URL, no `Authorization` and no Circaso headers, for talking to other hosts (the avatar
 * upload goes to storage). The Circaso bearer token must never be sent to a third party.
 */
fun createPlainHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
    }
}
