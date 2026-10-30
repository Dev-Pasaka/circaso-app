package com.circaso.feature.contacts.domain

import com.circaso.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Contacts and blocks are read from the phone and refreshed in the background. Removing a contact and blocking or
 * unblocking are queueable writes (saved at once, sent when online). Syncing the phone book, connecting, invites and
 * reach checks need the server. Phone-book names never leave the phone.
 */
interface ContactsRepository {
    val contacts: Flow<List<Contact>>
    val blocks: Flow<List<Person>>

    suspend fun refreshContacts(): AppResult<Unit>
    suspend fun refreshBlocks(): AppResult<Unit>

    /** Sends only the numbers that are new since the last successful sync, in batches of up to 1,000. */
    suspend fun syncPhoneBook(phones: List<String>): AppResult<SyncSummary>

    suspend fun connect(via: ConnectVia, value: String): AppResult<Connection>
    suspend fun createInvite(): AppResult<Invite>
    suspend fun revokeInvite(token: String): AppResult<Unit>

    suspend fun removeContact(userId: String)
    suspend fun block(person: Person)
    suspend fun unblock(userId: String)

    /** Whether you can reach that person. The server never says why not. */
    suspend fun canReach(userId: String): AppResult<Boolean>
}
