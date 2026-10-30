package com.circaso.core

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.sync.DrainResult
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxEntity
import com.circaso.core.sync.OutboxHandler
import com.circaso.core.sync.OutboxProcessor
import com.circaso.core.sync.OutboxState
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.runSuspending
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class ScriptedHandler(override val type: String, private val answers: MutableList<AppResult<Unit>>) : OutboxHandler {
    val sentKeys = mutableListOf<String>()
    override suspend fun send(item: OutboxEntity): AppResult<Unit> { sentKeys += item.idempotencyKey; return answers.removeFirst() }
}

class OutboxProcessorTest {
    private var now = 10_000_000L
    private val dao = FakeOutboxDao()
    private val outbox = Outbox(dao) { now }
    private val ok = AppResult.Success(Unit)
    private fun api(code: String, status: Int) = AppResult.Failure(AppError.Api(code, status, null, null, emptyMap()))

    private fun processor(handler: OutboxHandler) = OutboxProcessor(outbox, listOf(handler)) { now }
    private fun states() = dao.rows.value.map { it.state }

    @Test fun sendsEverythingInOrderAndMarksItDone() = runSuspending {
        val handler = ScriptedHandler("t", mutableListOf(ok, ok))
        outbox.enqueue("t", "a"); outbox.enqueue("t", "b")
        val result = processor(handler).drain()
        assertEquals(DrainResult.Drained(2), result)
        assertEquals(listOf(OutboxState.Done, OutboxState.Done), states())
    }

    @Test fun aNetworkFailureStopsTheRunAndKeepsTheOriginalKey() = runSuspending {
        val handler = ScriptedHandler("t", mutableListOf(AppResult.Failure(AppError.Network), ok, ok))
        val first = outbox.enqueue("t", "a"); outbox.enqueue("t", "b")
        val p = processor(handler)
        assertIs<DrainResult.Paused>(p.drain())
        assertEquals(listOf(OutboxState.Pending, OutboxState.Pending), states()) // nothing lost, nothing skipped
        p.drain() // connection back
        assertEquals(listOf(OutboxState.Done, OutboxState.Done), states())
        assertEquals(first.idempotencyKey, handler.sentKeys[0])
        assertEquals(first.idempotencyKey, handler.sentKeys[1]) // the retry reused the key
    }

    @Test fun serverTroubleAndRateLimitsPauseInsteadOfFailing() = runSuspending {
        for (failure in listOf(api("x.boom", 503), api("request.rate_limited", 429), AppResult.Failure(AppError.Unexpected(502)))) {
            dao.clearAll()
            outbox.enqueue("t", "a")
            assertIs<DrainResult.Paused>(processor(ScriptedHandler("t", mutableListOf(failure))).drain())
            assertEquals(listOf(OutboxState.Pending), states())
        }
    }

    @Test fun aRefusalFailsThatItemWithItsCodeAndTheRestStillGoOut() = runSuspending {
        val handler = ScriptedHandler("t", mutableListOf(api("users.version_conflict", 409), ok))
        outbox.enqueue("t", "a"); outbox.enqueue("t", "b")
        assertEquals(DrainResult.Drained(1), processor(handler).drain())
        assertEquals(listOf(OutboxState.Failed, OutboxState.Done), states())
        assertEquals("users.version_conflict", dao.rows.value.first().lastErrorCode)
    }

    @Test fun anItemOlderThanTheIdempotencyWindowIsNotReplayed() = runSuspending {
        val handler = ScriptedHandler("t", mutableListOf(ok))
        outbox.enqueue("t", "a")
        now += OutboxProcessor.StaleAfterMillis + 1_000
        processor(handler).drain()
        assertEquals(listOf(OutboxState.Failed), states())
        assertEquals(OutboxProcessor.Stale, dao.rows.value.single().lastErrorCode)
        assertEquals(emptyList(), handler.sentKeys)
    }

    @Test fun anUnknownTypeFailsInsteadOfBlockingTheQueue() = runSuspending {
        outbox.enqueue("mystery", "a"); outbox.enqueue("t", "b")
        assertEquals(DrainResult.Drained(1), processor(ScriptedHandler("t", mutableListOf(ok))).drain())
        assertEquals(listOf(OutboxState.Failed, OutboxState.Done), states())
    }
}
