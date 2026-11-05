package com.circaso.core.network

/** Where the API lives and how this build identifies itself (`X-Client-Version`: `android/1.0.0`). */
data class ApiConfig(
    val baseUrl: String,
    val clientVersion: String,
    /** `Circaso/1.0.0 (Android 15; Xiaomi 23129RAA4G)`: the server logs the device each request came from. */
    val userAgent: String? = null,
) {
    companion object {
        /** Dev server. Production (`https://api.circaso.com`) is not live yet. */
        const val DEV_BASE_URL = "https://sandbox.circaso.com"
    }
}

/** Supplies the bearer token for the next call, or null when there is no session. */
fun interface AccessTokenProvider {
    suspend fun accessToken(): String?
}

/** `Circaso/1.0.0 (Android 15; Xiaomi 23129RAA4G)`, `Circaso/1.0.0 (iOS 26.0; Apple iPhone)`. ASCII only: it is a header. */
fun circasoUserAgent(info: com.circaso.core.platform.ClientInfo): String {
    val os = if (info.platform == "ios") "iOS" else "Android"
    val device = listOf(info.manufacturer, info.model).filter { it.isNotBlank() }.distinct().joinToString(" ")
    val text = "Circaso/${info.appVersion} ($os ${info.osVersion}" + (if (device.isNotEmpty()) "; $device" else "") + ")"
    return text.filter { it in ' '..'~' }
}
