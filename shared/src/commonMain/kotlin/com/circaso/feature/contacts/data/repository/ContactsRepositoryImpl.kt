package com.circaso.feature.contacts.data.repository

import com.circaso.core.common.AppResult
import com.circaso.core.common.map
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.sync.Outbox
import com.circaso.feature.contacts.data.local.BlockedEntity
import com.circaso.feature.contacts.data.local.ContactEntity
import com.circaso.feature.contacts.data.local.ContactsDao
import com.circaso.feature.contacts.data.local.SyncedPhoneEntity
import com.circaso.feature.contacts.data.remote.ConnectRequest
import com.circaso.feature.contacts.data.remote.ContactsRemote
import com.circaso.feature.contacts.data.remote.PersonDto
import com.circaso.feature.contacts.data.remote.SyncRequest
import com.circaso.feature.contacts.domain.Connection
import com.circaso.feature.contacts.domain.ConnectVia
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.Invite
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.contacts.domain.SyncMatch
import com.circaso.feature.contacts.domain.SyncSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class ContactsRepositoryImpl(
    private val remote: ContactsRemote,
    private val dao: ContactsDao,
    private val outbox: Outbox,
    /** Names I saved for people: shown in place of theirs, theirs kept as [Person.profileName]. */
    private val names: com.circaso.feature.names.domain.PeopleNames = com.circaso.feature.names.domain.PeopleNames.None,
    private val kickOutbox: () -> Unit,
) : ContactsRepository {

    /** Saved contacts minus the people I've blocked (2026-11-10): a block hides them everywhere until it's lifted. */
    override val contacts: Flow<List<Contact>> =
        combine(dao.observeContacts(), dao.observeBlocked(), names.saved) { rows, blocked, saved ->
            val hidden = blocked.mapTo(HashSet()) { it.userId }
            rows.filter { it.userId !in hidden }.map { it.toDomain().let { c -> c.copy(person = c.person.named(saved)) } }
        }
    override val blocks: Flow<List<Person>> =
        combine(dao.observeBlocked(), names.saved) { rows, saved -> rows.map { it.toDomain().named(saved) } }

    override suspend fun refreshContacts(): AppResult<Unit> {
        val all = mutableListOf<com.circaso.feature.contacts.data.remote.ContactDto>()
        var cursor: String? = null
        var pages = 0
        do {
            when (val page = remote.contacts(cursor, PageSize)) {
                is AppResult.Failure -> return page // nothing is changed locally unless every page arrived
                is AppResult.Success -> { all += page.value.contacts; cursor = page.value.nextCursor }
            }
        } while (cursor != null && ++pages < MaxPages)
        // A contact removed on this phone but not yet confirmed by the server must not reappear.
        val removing = outbox.waitingPayloads(ContactRemoveHandler.Type).toSet()
        val rows = all.filter { it.user.userId !in removing }.map { it.toEntity() }
        if (rows.isEmpty()) dao.clearContacts() else {
            dao.upsertContacts(rows)
            dao.deleteContactsNotIn(rows.map { it.userId })
        }
        return AppResult.Success(Unit)
    }

    override suspend fun refreshBlocks(): AppResult<Unit> {
        // Block changes waiting to be sent are the truth on this phone until the server has them.
        if (outbox.waiting(BlockSetHandler.Type) + outbox.waiting(BlockClearHandler.Type) > 0) return AppResult.Success(Unit)
        return when (val result = remote.blocks()) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val rows = result.value.blocks.map { it.toBlocked() }
                if (rows.isEmpty()) dao.clearBlocked() else {
                    dao.upsertBlocked(rows)
                    dao.deleteBlockedNotIn(rows.map { it.userId })
                }
                AppResult.Success(Unit)
            }
        }
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    override suspend fun syncPhoneBook(phones: List<String>): AppResult<SyncSummary> {
        val valid = phones.filter { E164.matches(it) }.distinct()
        val known = dao.syncedPhones().toSet()
        val fresh = valid.filter { it !in known }
        if (fresh.isEmpty()) return AppResult.Success(SyncSummary(0, emptyList()))
        val syncId = kotlin.uuid.Uuid.random().toString()
        val batches = fresh.chunked(BatchSize)
        val matches = mutableListOf<SyncMatch>()
        batches.forEachIndexed { index, batch ->
            when (val result = remote.sync(SyncRequest(syncId, batch = index + 1, final = index == batches.lastIndex, phones = batch))) {
                is AppResult.Failure -> return result // incomplete: nothing is marked as synced, the next try resends
                is AppResult.Success -> matches += result.value.matches.map { SyncMatch(it.phone, it.user.toDomain()) }
            }
        }
        dao.markSynced(fresh.map { SyncedPhoneEntity(it) })
        refreshContacts()
        return AppResult.Success(SyncSummary(fresh.size, matches))
    }

    override suspend fun connect(via: ConnectVia, value: String): AppResult<Connection> {
        val result = remote.connect(ConnectRequest(via.apiName, value)).map { Connection(it.user.toDomain().named(names.saved.value), it.created) }
        if (result is AppResult.Success) refreshContacts()
        return result
    }

    override suspend fun createInvite(): AppResult<Invite> = remote.createInvite().map { Invite(it.token, it.url, it.qrPayload, it.expiresAt) }

    override suspend fun revokeInvite(token: String): AppResult<Unit> = remote.revokeInvite(token).map { }

    override suspend fun removeContact(userId: String) {
        dao.deleteContact(userId)
        outbox.enqueue(ContactRemoveHandler.Type, userId)
        kickOutbox()
    }

    override suspend fun block(person: Person) {
        dao.upsertBlocked(listOf(BlockedEntity(person.userId, person.profileName, person.handle, person.avatarUrl, person.about)))
        outbox.enqueue(BlockSetHandler.Type, person.userId)
        kickOutbox()
    }

    override suspend fun unblock(userId: String) {
        dao.deleteBlocked(userId)
        outbox.enqueue(BlockClearHandler.Type, userId)
        kickOutbox()
    }

    override suspend fun canReach(userId: String): AppResult<Boolean> = remote.reach(userId).map { it.reachable }

    private companion object {
        const val PageSize = 200
        const val MaxPages = 100
        const val BatchSize = 1_000
        val E164 = Regex("""^\+[1-9]\d{6,14}$""")
    }
}

private fun PersonDto.toDomain() = Person(userId, displayName, handle, avatarUrl, about)

/** The name I saved for them shown, their own kept as [Person.profileName]. */
internal fun Person.named(saved: Map<String, String>): Person =
    saved[userId]?.let { copy(displayName = it, profileName = profileName) } ?: this
private fun PersonDto.toBlocked() = BlockedEntity(userId, displayName, handle, avatarUrl, about)
private fun com.circaso.feature.contacts.data.remote.ContactDto.toEntity() =
    ContactEntity(user.userId, user.displayName, user.handle, user.avatarUrl, user.about, source, addedAt)
private fun ContactEntity.toDomain() = Contact(Person(userId, displayName, handle, avatarUrl, about), source, addedAt)
private fun BlockedEntity.toDomain() = Person(userId, displayName, handle, avatarUrl, about)
