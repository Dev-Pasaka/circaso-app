package com.circaso.feature.account.data.repository

import com.circaso.core.common.AppResult
import com.circaso.core.sync.OutboxEntity
import com.circaso.core.sync.OutboxHandler
import com.circaso.feature.account.data.remote.AccountRemote
import com.circaso.feature.account.data.remote.PushTokenRequest

/** Stores this phone's push token on the server (the server keeps it but does not send pushes yet). */
class PushTokenHandler(private val remote: AccountRemote) : OutboxHandler {
    override val type = Type
    override suspend fun send(item: OutboxEntity): AppResult<Unit> =
        when (val result = remote.pushToken(PushTokenRequest(item.payload), item.idempotencyKey)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> result
        }

    companion object { const val Type = "device.push_token" }
}
