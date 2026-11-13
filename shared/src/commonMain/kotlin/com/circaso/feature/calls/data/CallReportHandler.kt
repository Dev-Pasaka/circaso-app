package com.circaso.feature.calls.data

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.sync.OutboxEntity
import com.circaso.core.sync.OutboxHandler
import com.circaso.feature.calls.QueuedCallReport
import kotlinx.serialization.json.Json

/**
 * Sends a finished call's report (CL10, 2026-11-13). Queued so a call that dropped with the network still reports once
 * it's back. Best effort: a report the server turns down, or one rate-limited, is dropped, never shown as a failed
 * change and never left blocking the queue.
 */
class CallReportHandler(private val remote: CallsRemote) : OutboxHandler {
    private val json = Json { ignoreUnknownKeys = true }
    override val type = Type
    override val bestEffort = true

    override suspend fun send(item: OutboxEntity): AppResult<Unit> {
        val queued = runCatching { json.decodeFromString(QueuedCallReport.serializer(), item.payload) }.getOrNull()
            ?: return AppResult.Success(Unit)
        return when (val r = remote.report(queued.callId, queued.report, item.idempotencyKey)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> {
                val e = r.error
                if (e is AppError.Api && e.httpStatus < 500) AppResult.Success(Unit) else r
            }
        }
    }

    companion object { const val Type = "calls.report" }
}
