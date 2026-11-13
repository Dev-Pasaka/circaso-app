package com.circaso.feature.status.data.local

import com.circaso.core.datastore.AppPreferences
import com.circaso.feature.status.domain.SeenReactions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * [SeenReactions] in the app's small preferences, as one JSON map of moment id to reaction keys. A moment lives a day
 * and a reaction key is a user id and an emoji, so this stays a few KB; moments that ended are dropped on each write.
 * Not secret (no names, no content); cleared on sign-out with the account's other settings.
 */
class PreferencesSeenReactions(private val prefs: AppPreferences, private val json: Json) : SeenReactions {
    private val lock = Mutex()
    private val serializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    private suspend fun read(): Map<String, List<String>> =
        prefs.text(Key).first()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    override suspend fun seen(momentId: String): Set<String> = read()[momentId].orEmpty().toSet()

    override suspend fun markSeen(momentId: String, keys: Set<String>, live: Set<String>) = lock.withLock {
        val all = read()
        val merged = (all[momentId].orEmpty() + keys).distinct()
        val next = all.filterKeys { it in live } + (momentId to merged)
        if (next != all) prefs.setText(Key, json.encodeToString(serializer, next))
    }

    companion object {
        const val Key = "moments.reactions_seen"
    }
}
