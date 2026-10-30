package com.circaso.core.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(item: OutboxEntity): Long

    @Query("SELECT COUNT(*) FROM outbox WHERE type = :type AND state IN ('pending', 'sending')")
    fun observeWaiting(type: String): Flow<Int>

    @Query("SELECT * FROM outbox WHERE state = 'pending' AND ownerId = :ownerId ORDER BY id LIMIT 1")
    suspend fun nextPending(ownerId: String): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE type = :type AND state = 'pending' AND ownerId = :ownerId ORDER BY id LIMIT 1")
    suspend fun pendingOfType(type: String, ownerId: String): OutboxEntity?

    @Query("SELECT COUNT(*) FROM outbox WHERE type = :type AND state IN ('pending', 'sending')")
    suspend fun waitingCount(type: String): Int

    @Query("SELECT * FROM outbox WHERE state = 'failed' AND ownerId = :ownerId ORDER BY id")
    fun observeFailed(ownerId: String): Flow<List<OutboxEntity>>

    @Query("UPDATE outbox SET state = 'pending', lastErrorCode = NULL WHERE id = :id AND state = 'failed'")
    suspend fun retry(id: Long)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT payload FROM outbox WHERE type = :type AND state IN ('pending', 'sending')")
    suspend fun waitingPayloads(type: String): List<String>

    @Query("UPDATE outbox SET payload = :payload WHERE id = :id")
    suspend fun updatePayload(id: Long, payload: String)

    @Query("SELECT * FROM outbox ORDER BY id")
    suspend fun all(): List<OutboxEntity>

    @Query("UPDATE outbox SET state = :state, attempts = attempts + :attemptsAdded, lastErrorCode = :errorCode WHERE id = :id")
    suspend fun update(id: Long, state: String, attemptsAdded: Int, errorCode: String?)

    /** A crash can leave rows stuck in `sending`; on start they go back in line (their key makes the retry safe). */
    @Query("UPDATE outbox SET state = 'pending' WHERE state = 'sending'")
    suspend fun requeueInterrupted()

    @Query("DELETE FROM outbox WHERE state = 'done'")
    suspend fun clearDone()

    @Query("DELETE FROM outbox")
    suspend fun clearAll()

}
