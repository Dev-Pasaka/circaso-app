package com.circaso.core.sync

import com.circaso.core.network.newIdempotencyKey
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

/**
 * Queue of writes made while offline (or not yet confirmed by the server). The queue lives in Room, so it
 * survives the app being killed. Sending and retrying are added with the first feature that queues writes.
 */
class Outbox(
    private val dao: OutboxDao,
    /** Whose writes these are: the signed-in user's id. Null while nobody is signed in. */
    private val owner: () -> String? = { null },
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** How many chat messages are waiting to send (drives the offline banner, design G3b). */
    val waitingMessages: Flow<Int> = dao.observeWaiting(OutboxType.ChatMessage)

    /** Queues a write and mints the key every retry of it will reuse. */
    suspend fun enqueue(type: String, payload: String): OutboxEntity {
        val item = OutboxEntity(
            type = type,
            payload = payload,
            idempotencyKey = newIdempotencyKey(),
            createdAtMillis = nowMillis(),
            ownerId = owner().orEmpty(),
        )
        return item.copy(id = dao.insert(item))
    }

    /**
     * Queues a write, or folds it into one of the same type that has not started sending yet. Used where only the
     * latest intent matters (profile edits), so two quick edits become one request and never conflict with each other.
     */
    suspend fun enqueueOrMerge(type: String, payload: String, merge: (existing: String, new: String) -> String): OutboxEntity {
        val pending = dao.pendingOfType(type, owner().orEmpty()) ?: return enqueue(type, payload)
        val merged = merge(pending.payload, payload)
        dao.updatePayload(pending.id, merged)
        return pending.copy(payload = merged)
    }

    /** Writes the server refused or that waited too long: the person decides to retry or discard each. */
    fun failed(): Flow<List<OutboxEntity>> = dao.observeFailed(owner().orEmpty())

    suspend fun retry(id: Long) = dao.retry(id)
    suspend fun discard(id: Long) = dao.delete(id)

    suspend fun waiting(type: String): Int = dao.waitingCount(type)

    /** The payloads of writes of this type that are still waiting (for example which contacts are queued for removal). */
    suspend fun waitingPayloads(type: String): List<String> = dao.waitingPayloads(type)

    /** The next write for the signed-in person. Someone else's writes are never returned. */
    suspend fun next(): OutboxEntity? = dao.nextPending(owner().orEmpty())

    suspend fun markSending(id: Long) = dao.update(id, OutboxState.Sending, 1, null)
    /** Back in line after a failure a retry might fix; keeps the key. [errorCode] is only for diagnostics. */
    suspend fun markPending(id: Long, errorCode: String?) = dao.update(id, OutboxState.Pending, 0, errorCode)
    suspend fun markDone(id: Long) = dao.update(id, OutboxState.Done, 0, null)
    suspend fun markFailed(id: Long, errorCode: String) = dao.update(id, OutboxState.Failed, 0, errorCode)

    suspend fun requeueInterrupted() = dao.requeueInterrupted()
    suspend fun clearDone() = dao.clearDone()
    suspend fun discardAll() = dao.clearAll()
}
