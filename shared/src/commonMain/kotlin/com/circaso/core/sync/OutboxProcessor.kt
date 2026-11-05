package com.circaso.core.sync

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** Sends one kind of queued write. It must send with the item's own `idempotencyKey` so a retry can never apply twice. */
interface OutboxHandler {
    val type: String
    suspend fun send(item: OutboxEntity): AppResult<Unit>
}

sealed interface DrainResult {
    /** Everything waiting was handled. */
    data class Drained(val sent: Int) : DrainResult

    /** A failure that a retry may fix (offline, server trouble, rate limit): the rest stays queued, in order. */
    data class Paused(val sent: Int, val reason: String) : DrainResult
}

/**
 * Sends the queue in order. Rules (see `offline-first.md`):
 * - A network or server problem stops the run and leaves the item pending with its original key.
 * - A refusal the server will repeat (bad request, conflict, not found) fails that item with its error code and moves on,
 *   so one bad item never blocks the rest. The screen offers retry or discard.
 * - The server keeps an idempotent answer for 24 hours, so an item older than [StaleAfterMillis] is not replayed
 *   blindly: it is failed as `outbox.stale` and the person decides.
 */
class OutboxProcessor(
    private val outbox: Outbox,
    handlers: List<OutboxHandler>,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val byType = handlers.associateBy { it.type }
    private val lock = Mutex()

    /** True while a drain is sending (in this process): a second runner has nothing to add. */
    val busy: Boolean get() = lock.isLocked

    suspend fun drain(): DrainResult = lock.withLock {
        // Under the lock nothing in this process is sending, so a row still marked `sending` was cut off (the
        // process died mid-upload, or the runner was stopped): back in line with its own key, before anything else.
        outbox.requeueInterrupted()
        var sent = 0
        while (true) {
            val item = outbox.next() ?: return DrainResult.Drained(sent)
            val handler = byType[item.type]
            if (handler == null) {
                outbox.markFailed(item.id, UnknownType)
                continue
            }
            if (nowMillis() - item.createdAtMillis > StaleAfterMillis) {
                outbox.markFailed(item.id, Stale)
                continue
            }
            outbox.markSending(item.id)
            when (val result = handler.send(item)) {
                is AppResult.Success -> { outbox.markDone(item.id); sent++ }
                is AppResult.Failure -> {
                    val error = result.error
                    if (error.isRetryable()) {
                        outbox.markPending(item.id, error.describe())
                        return DrainResult.Paused(sent, error.describe())
                    }
                    outbox.markFailed(item.id, error.describe())
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private fun AppError.isRetryable(): Boolean = when (this) {
        AppError.Network -> true
        is AppError.Unexpected -> true
        is AppError.Api -> httpStatus >= 500 || httpStatus == 429 || code == "request.in_progress"
    }

    private fun AppError.describe(): String = when (this) {
        AppError.Network -> "network"
        is AppError.Unexpected -> "unexpected.${httpStatus ?: 0}"
        is AppError.Api -> code
    }

    companion object {
        /** Under the server's 24 hour idempotency window, with margin. */
        const val StaleAfterMillis = 20 * 60 * 60 * 1000L
        const val Stale = "outbox.stale"
        const val UnknownType = "outbox.unknown_type"
    }
}
