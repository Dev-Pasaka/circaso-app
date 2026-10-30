package com.circaso.core.network

/** Where the API lives and how this build identifies itself (`X-Client-Version`: `android/1.0.0`). */
data class ApiConfig(val baseUrl: String, val clientVersion: String) {
    companion object {
        /** Dev server. Production (`https://api.circaso.com`) is not live yet. */
        const val DEV_BASE_URL = "https://sandbox.circaso.com"
    }
}

/** Supplies the bearer token for the next call, or null when there is no session. */
fun interface AccessTokenProvider {
    suspend fun accessToken(): String?
}
