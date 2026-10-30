package com.circaso.feature.assistant.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.sync.OutboxEntity
import com.circaso.core.sync.OutboxHandler
import com.circaso.feature.assistant.data.remote.AshaRemote

/** A message the server no longer has counts as deleted: the person's wish is met. */
class AshaDeleteMessageHandler(private val remote: AshaRemote) : OutboxHandler {
    override val type = Type
    override suspend fun send(item: OutboxEntity): AppResult<Unit> =
        when (val result = remote.deleteMessage(item.payload, item.idempotencyKey)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> if ((result.error as? AppError.Api)?.httpStatus == 404) AppResult.Success(Unit) else result
        }

    companion object { const val Type = "asha.delete_message" }
}

class AshaDeleteAllHandler(private val remote: AshaRemote) : OutboxHandler {
    override val type = Type
    override suspend fun send(item: OutboxEntity): AppResult<Unit> =
        when (val result = remote.deleteAll(item.idempotencyKey)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> result
        }

    companion object { const val Type = "asha.delete_all" }
}
