package com.circaso.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Reads the `sub` (user id) claim of an access token. Only for telling whose data this is; the server does the verifying. */
object Jwt {
    @OptIn(ExperimentalEncodingApi::class)
    fun subject(token: String?): String? {
        val payload = token?.split('.')?.getOrNull(1) ?: return null
        return try {
            val json = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload).decodeToString()
            (Json.parseToJsonElement(json) as? JsonObject)?.get("sub")?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
