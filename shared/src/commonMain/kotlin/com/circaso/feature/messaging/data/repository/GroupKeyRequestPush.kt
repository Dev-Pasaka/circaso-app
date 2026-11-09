package com.circaso.feature.messaging.data.repository

import kotlinx.coroutines.withTimeoutOrNull

/**
 * The silent push the server sends a group member whose phone has no socket when another member's phone (a new one)
 * asks for the group's metadata key (`kind` `chat.meta_key_requested`, `chatId`, `userId` the asker; backend
 * 2026-11-09). Without it the request waited in the inbox until the member opened Circaso, and the new phone showed
 * the group nameless and without its photo. Shows nothing: pulling the inbox answers the request (with the key, or
 * that this phone has none).
 */
object GroupKeyRequestPush {
    const val Kind = "chat.meta_key_requested"

    /** Pulls the inbox with [sync] within [windowMillis], the time the platform gives a push. True when [data] was this push. */
    suspend fun handle(data: Map<String, String>, sync: suspend () -> Unit, windowMillis: Long): Boolean {
        if (data["kind"] != Kind) return false
        withTimeoutOrNull(windowMillis) { sync() }
        return true
    }
}
