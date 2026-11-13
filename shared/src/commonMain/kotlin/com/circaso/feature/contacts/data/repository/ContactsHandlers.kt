package com.circaso.feature.contacts.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.sync.OutboxEntity
import com.circaso.core.sync.OutboxHandler
import com.circaso.feature.contacts.data.remote.ContactsRemote

/** Sends a queued contact removal. A contact the server no longer has counts as done: the person's wish is met. */
class ContactRemoveHandler(private val remote: ContactsRemote) : OutboxHandler {
    override val type = Type
    override suspend fun send(item: OutboxEntity): AppResult<Unit> =
        when (val result = remote.removeContact(item.payload, item.idempotencyKey)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> if ((result.error as? AppError.Api)?.code == "users.contact_not_found") AppResult.Success(Unit) else result
        }

    companion object { const val Type = "contact.remove" }
}

class BlockSetHandler(private val remote: ContactsRemote) : OutboxHandler {
    override val type = Type
    override suspend fun send(item: OutboxEntity): AppResult<Unit> =
        when (val result = remote.block(item.payload, item.idempotencyKey)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> result
        }

    companion object { const val Type = "block.set" }
}

/**
 * Sends a queued unblock. The server gives the contact back on unblock (2026-11-10), so once it's done the contact
 * list is fetched again and the person reappears without a pull to refresh.
 */
class BlockClearHandler(private val remote: ContactsRemote, private val onCleared: suspend () -> Unit = {}) : OutboxHandler {
    override val type = Type
    override suspend fun send(item: OutboxEntity): AppResult<Unit> =
        when (val result = remote.unblock(item.payload, item.idempotencyKey)) {
            is AppResult.Success -> { onCleared(); AppResult.Success(Unit) }
            is AppResult.Failure -> result
        }

    companion object { const val Type = "block.clear" }
}
