package com.circaso.feature.messaging.data.repository

import com.circaso.core.datastore.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Read receipts that couldn't go out (2026-11-13). `read` is a socket frame only (guide, section 8), so a chat marked
 * read with the socket down (Mark as read or Reply on a notification, with Circaso closed) kept its read marker here
 * and never told the server. Each chat's newest one waits here, per person, and goes when the socket next connects.
 */
class PendingReads(private val prefs: AppPreferences) {
    private val lock = Mutex()
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    /** Remembers that [userId] read [chatId] up to [messageId]; a later one replaces it. */
    suspend fun put(userId: String, chatId: String, messageId: String) = edit(userId) { it + (chatId to messageId) }

    /** The socket took a newer receipt for [chatId]: the waiting one is not needed. */
    suspend fun remove(userId: String, chatId: String) = edit(userId) { it - chatId }

    /** Sends each waiting receipt with [send]; the ones it took are forgotten, the rest wait for the next connection. */
    suspend fun flush(userId: String, send: suspend (chatId: String, messageId: String) -> Boolean) {
        val waiting = lock.withLock { read(userId) }
        if (waiting.isEmpty()) return
        val sent = waiting.filter { (chatId, messageId) -> send(chatId, messageId) }.keys
        if (sent.isNotEmpty()) edit(userId) { current -> current.filterNot { (chatId, messageId) -> chatId in sent && waiting[chatId] == messageId } }
    }

    private suspend fun edit(userId: String, change: (Map<String, String>) -> Map<String, String>) = lock.withLock {
        val before = read(userId)
        val after = change(before)
        if (after != before) prefs.setText(key(userId), if (after.isEmpty()) null else json.encodeToString(serializer, after))
    }

    private suspend fun read(userId: String): Map<String, String> =
        prefs.text(key(userId)).first()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun key(userId: String) = "messaging.pendingReads.$userId"
}
