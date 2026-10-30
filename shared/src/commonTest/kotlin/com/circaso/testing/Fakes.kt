package com.circaso.testing

import com.circaso.core.sync.OutboxDao
import com.circaso.core.sync.OutboxEntity
import com.circaso.core.sync.OutboxState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

import com.circaso.core.platform.ClientInfo
import com.circaso.core.platform.DeviceKeyException
import com.circaso.core.platform.DeviceKeyStore
import com.circaso.core.platform.SecureStorage

class MemoryStorage : SecureStorage {
    val values = mutableMapOf<String, String>()
    override suspend fun get(key: String) = values[key]
    override suspend fun put(key: String, value: String) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
}

/** Keys that "sign" by wrapping the payload, so tests can see exactly what was signed. */
class FakeKeys(var present: Boolean = true) : DeviceKeyStore {
    override suspend fun hasKey() = present
    override suspend fun getOrCreatePublicKey(): String { present = true; return "PUBLIC" }
    override suspend fun sign(payload: String): String {
        if (!present) throw DeviceKeyException("no key")
        return "sig<$payload>"
    }
    override suspend fun delete() { present = false }
}

class FakeClientInfo : ClientInfo {
    override val platform = "android"
    override val appVersion = "1.0.0"
    override val model = "Pixel 8"
    override val osVersion = "15"
}

/** In-memory stand-in for the Room DAO. */
class FakeOutboxDao : OutboxDao {
    val rows = MutableStateFlow<List<OutboxEntity>>(emptyList())
    private var nextId = 1L
    override suspend fun insert(item: OutboxEntity): Long { val id = nextId++; rows.value += item.copy(id = id); return id }
    override fun observeWaiting(type: String): Flow<Int> = rows.map { r -> r.count { it.type == type && it.state in setOf("pending", "sending") } }
    override suspend fun pendingOfType(type: String, ownerId: String) = rows.value.firstOrNull { it.type == type && it.state == OutboxState.Pending && it.ownerId == ownerId }
    override suspend fun waitingCount(type: String) = rows.value.count { it.type == type && it.state in setOf("pending", "sending") }
    override fun observeFailed(ownerId: String): Flow<List<OutboxEntity>> = rows.map { r -> r.filter { it.state == OutboxState.Failed && it.ownerId == ownerId } }
    override suspend fun retry(id: Long) { rows.value = rows.value.map { if (it.id == id && it.state == OutboxState.Failed) it.copy(state = OutboxState.Pending, lastErrorCode = null) else it } }
    override suspend fun delete(id: Long) { rows.value = rows.value.filter { it.id != id } }
    override suspend fun waitingPayloads(type: String) = rows.value.filter { it.type == type && it.state in setOf("pending", "sending") }.map { it.payload }
    override suspend fun updatePayload(id: Long, payload: String) { rows.value = rows.value.map { if (it.id == id) it.copy(payload = payload) else it } }
    override suspend fun nextPending(ownerId: String) = rows.value.firstOrNull { it.state == OutboxState.Pending && it.ownerId == ownerId }
    override suspend fun all() = rows.value
    override suspend fun update(id: Long, state: String, attemptsAdded: Int, errorCode: String?) {
        rows.value = rows.value.map { if (it.id == id) it.copy(state = state, attempts = it.attempts + attemptsAdded, lastErrorCode = errorCode) else it }
    }
    override suspend fun requeueInterrupted() { rows.value = rows.value.map { if (it.state == OutboxState.Sending) it.copy(state = OutboxState.Pending) else it } }
    override suspend fun clearDone() { rows.value = rows.value.filter { it.state != OutboxState.Done } }
    override suspend fun clearAll() { rows.value = emptyList() }
}

