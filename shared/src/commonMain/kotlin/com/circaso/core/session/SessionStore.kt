package com.circaso.core.session

import com.circaso.core.platform.SecureStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** Keeps the session in the secure store and a copy in memory. Every change is written before it is used. */
class SessionStore(private val storage: SecureStorage, private val json: Json) {
    private val _session = MutableStateFlow(StoredSession())
    val session: StateFlow<StoredSession> = _session.asStateFlow()

    suspend fun load(): StoredSession {
        val stored = storage.get(Key)?.let { runCatching { json.decodeFromString<StoredSession>(it) }.getOrNull() } ?: StoredSession()
        _session.value = stored
        return stored
    }

    suspend fun save(session: StoredSession) {
        storage.put(Key, json.encodeToString(StoredSession.serializer(), session)) // durable first
        _session.value = session
    }

    suspend fun clear() {
        storage.remove(Key)
        _session.value = StoredSession()
    }

    /** The user id whose data is cached on this phone, kept across sign-outs so a different person never inherits it. */
    suspend fun dataOwner(): String? = storage.get(OwnerKey)
    suspend fun setDataOwner(userId: String?) { if (userId == null) storage.remove(OwnerKey) else storage.put(OwnerKey, userId) }

    private companion object {
        const val Key = "session.v1"
        const val OwnerKey = "data.owner"
    }
}
