package com.circaso.feature.contacts

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.NoBody
import com.circaso.core.sync.Outbox
import com.circaso.feature.contacts.data.local.BlockedEntity
import com.circaso.feature.contacts.data.local.ContactEntity
import com.circaso.feature.contacts.data.local.ContactsDao
import com.circaso.feature.contacts.data.local.SyncedPhoneEntity
import com.circaso.feature.contacts.data.remote.BlocksResponse
import com.circaso.feature.contacts.data.remote.ConnectRequest
import com.circaso.feature.contacts.data.remote.ConnectResponse
import com.circaso.feature.contacts.data.remote.ContactDto
import com.circaso.feature.contacts.data.remote.ContactsRemote
import com.circaso.feature.contacts.data.remote.ContactsResponse
import com.circaso.feature.contacts.data.remote.InviteResponse
import com.circaso.feature.contacts.data.remote.PersonDto
import com.circaso.feature.contacts.data.remote.ReachResponse
import com.circaso.feature.contacts.data.remote.RemovedResponse
import com.circaso.feature.contacts.data.remote.SyncMatchDto
import com.circaso.feature.contacts.data.remote.SyncRequest
import com.circaso.feature.contacts.data.remote.SyncResponse
import com.circaso.feature.contacts.data.repository.BlockClearHandler
import com.circaso.feature.contacts.data.repository.BlockSetHandler
import com.circaso.feature.contacts.data.repository.ContactRemoveHandler
import com.circaso.feature.contacts.data.repository.ContactsRepositoryImpl
import com.circaso.feature.contacts.domain.ConnectVia
import com.circaso.feature.contacts.domain.Person
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.runSuspending
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeContactsDao : ContactsDao {
    val contacts = MutableStateFlow<List<ContactEntity>>(emptyList())
    val blocked = MutableStateFlow<List<BlockedEntity>>(emptyList())
    val synced = mutableSetOf<String>()
    override fun observeContacts(): Flow<List<ContactEntity>> = contacts
    override suspend fun upsertContacts(items: List<ContactEntity>) { contacts.value = (contacts.value.filter { c -> items.none { it.userId == c.userId } } + items) }
    override suspend fun deleteContactsNotIn(keep: List<String>) { contacts.value = contacts.value.filter { it.userId in keep } }
    override suspend fun clearContacts() { contacts.value = emptyList() }
    override suspend fun deleteContact(userId: String) { contacts.value = contacts.value.filter { it.userId != userId } }
    override fun observeBlocked(): Flow<List<BlockedEntity>> = blocked
    override suspend fun upsertBlocked(items: List<BlockedEntity>) { blocked.value = (blocked.value.filter { b -> items.none { it.userId == b.userId } } + items) }
    override suspend fun deleteBlockedNotIn(keep: List<String>) { blocked.value = blocked.value.filter { it.userId in keep } }
    override suspend fun clearBlocked() { blocked.value = emptyList() }
    override suspend fun deleteBlocked(userId: String) { blocked.value = blocked.value.filter { it.userId != userId } }
    override suspend fun syncedPhones() = synced.toList()
    override suspend fun markSynced(phones: List<SyncedPhoneEntity>) { synced += phones.map { it.phone } }
    override suspend fun clearSynced() { synced.clear() }
}

private fun person(id: String) = PersonDto(id, "Person $id", "h$id")
private fun contact(id: String) = ContactDto(person(id), "sync", "2026-10-25T10:00:00Z")

private class FakeRemote : ContactsRemote {
    var pages: List<ContactsResponse> = listOf(ContactsResponse(listOf(contact("1"), contact("2")), null))
    var contactsFailure: AppResult.Failure? = null
    var syncFailAt: Int? = null
    var blocksAnswer: AppResult<BlocksResponse> = AppResult.Success(BlocksResponse(emptyList()))
    var removeAnswer: AppResult<RemovedResponse> = AppResult.Success(RemovedResponse(true))
    val syncRequests = mutableListOf<SyncRequest>()
    val cursors = mutableListOf<String?>()
    val removeCalls = mutableListOf<Pair<String, String?>>()
    val blockCalls = mutableListOf<String>()
    var blocksFetched = 0

    override suspend fun sync(request: SyncRequest): AppResult<SyncResponse> {
        syncRequests += request
        if (syncFailAt == request.batch) return AppResult.Failure(AppError.Network)
        return AppResult.Success(SyncResponse(request.phones.take(1).map { SyncMatchDto(it, person("m")) }))
    }
    override suspend fun contacts(cursor: String?, limit: Int): AppResult<ContactsResponse> {
        cursors += cursor
        contactsFailure?.let { return it }
        return AppResult.Success(pages[if (cursor == null) 0 else cursor.toInt()])
    }
    override suspend fun removeContact(userId: String, idempotencyKey: String?): AppResult<RemovedResponse> { removeCalls += userId to idempotencyKey; return removeAnswer }
    override suspend fun connect(request: ConnectRequest) = AppResult.Success(ConnectResponse(person("9"), true))
    override suspend fun createInvite() = AppResult.Success(InviteResponse("tok", "https://circaso.com/i/tok", "qr", "2026-11-01T00:00:00Z"))
    override suspend fun revokeInvite(token: String) = AppResult.Success(NoBody())
    override suspend fun blocks(): AppResult<BlocksResponse> { blocksFetched++; return blocksAnswer }
    override suspend fun block(userId: String, idempotencyKey: String?): AppResult<NoBody> { blockCalls += "set:$userId:$idempotencyKey"; return AppResult.Success(NoBody()) }
    override suspend fun unblock(userId: String, idempotencyKey: String?): AppResult<NoBody> { blockCalls += "clear:$userId:$idempotencyKey"; return AppResult.Success(NoBody()) }
    override suspend fun reach(userId: String) = AppResult.Success(ReachResponse(true))
}

class ContactsRepositoryTest {
    private val dao = FakeContactsDao()
    private val outboxDao = FakeOutboxDao()
    private val outbox = Outbox(outboxDao) { 1_000L }
    private val remote = FakeRemote()
    private var kicks = 0
    private val repo = ContactsRepositoryImpl(remote, dao, outbox) { kicks++ }

    private suspend fun ids() = repo.contacts.first().map { it.person.userId }.sorted()

    // ---- reading

    @Test fun refreshStoresEveryPageAndPassesTheCursorBackUnchanged() = runSuspending {
        remote.pages = listOf(ContactsResponse(listOf(contact("1")), "1"), ContactsResponse(listOf(contact("2")), null))
        assertIs<AppResult.Success<Unit>>(repo.refreshContacts())
        assertEquals(listOf(null, "1"), remote.cursors)
        assertEquals(listOf("1", "2"), ids())
    }

    @Test fun aFailedPageLeavesTheSavedListUntouched() = runSuspending {
        repo.refreshContacts()
        remote.pages = listOf(ContactsResponse(listOf(contact("3")), "1"))
        remote.contactsFailure = AppResult.Failure(AppError.Network)
        assertIs<AppResult.Failure>(repo.refreshContacts())
        assertEquals(listOf("1", "2"), ids())
    }

    @Test fun contactsTheServerNoLongerHasAreDroppedAndAnEmptyListClears() = runSuspending {
        repo.refreshContacts()
        remote.pages = listOf(ContactsResponse(listOf(contact("2")), null))
        repo.refreshContacts()
        assertEquals(listOf("2"), ids())
        remote.pages = listOf(ContactsResponse(emptyList(), null))
        repo.refreshContacts()
        assertEquals(emptyList(), ids())
    }

    // ---- queued removal

    @Test fun removingAContactHidesItAtOnceAndQueuesTheRemoval() = runSuspending {
        repo.refreshContacts()
        repo.removeContact("1")
        assertEquals(listOf("2"), ids())
        assertEquals("1", outboxDao.rows.value.single().payload)
        assertEquals(1, kicks)
    }

    @Test fun aContactWaitingToBeRemovedDoesNotComeBackOnRefresh() = runSuspending {
        repo.refreshContacts()
        repo.removeContact("1")
        repo.refreshContacts() // the server still lists it: the removal has not been sent yet
        assertEquals(listOf("2"), ids())
    }

    @Test fun theRemovalIsSentWithItsKeyAndAMissingContactCountsAsDone() = runSuspending {
        repo.refreshContacts(); repo.removeContact("1")
        val item = outboxDao.rows.value.single()
        val handler = ContactRemoveHandler(remote)
        assertIs<AppResult.Success<Unit>>(handler.send(item))
        assertEquals("1" to item.idempotencyKey, remote.removeCalls.single())
        remote.removeAnswer = AppResult.Failure(AppError.Api("users.contact_not_found", 404, null, null, emptyMap()))
        assertIs<AppResult.Success<Unit>>(handler.send(item))
        remote.removeAnswer = AppResult.Failure(AppError.Network)
        assertIs<AppResult.Failure>(handler.send(item))
    }

    // ---- blocks

    @Test fun blockingAndUnblockingTakeEffectLocallyAtOnceAndAreQueued() = runSuspending {
        repo.block(Person("7", "Kevin", "kevin", null, null))
        assertEquals(listOf("7"), repo.blocks.first().map { it.userId })
        repo.unblock("7")
        assertEquals(emptyList(), repo.blocks.first())
        assertEquals(listOf("block.set", "block.clear"), outboxDao.rows.value.map { it.type })
    }

    @Test fun blockHandlersSendTheRightCallWithTheItemsKey() = runSuspending {
        repo.block(Person("7", "Kevin", null, null, null)); repo.unblock("7")
        val (set, clear) = outboxDao.rows.value
        BlockSetHandler(remote).send(set); BlockClearHandler(remote).send(clear)
        assertEquals(listOf("set:7:${set.idempotencyKey}", "clear:7:${clear.idempotencyKey}"), remote.blockCalls)
    }

    @Test fun refreshingBlocksWaitsWhileBlockChangesAreQueued() = runSuspending {
        repo.block(Person("7", "Kevin", null, null, null))
        repo.refreshBlocks()
        assertEquals(0, remote.blocksFetched)
        assertEquals(listOf("7"), repo.blocks.first().map { it.userId })
    }

    // ---- phone-book sync

    private fun numbers(count: Int, from: Int = 0) = (from until from + count).map { "+2547${it.toString().padStart(8, '0')}" }

    @Test fun syncSendsBatchesOfAtMostAThousandWithOneSyncIdAndTheFinalFlagOnTheLast() = runSuspending {
        val summary = (repo.syncPhoneBook(numbers(2_500)) as AppResult.Success).value
        assertEquals(2_500, summary.numbersSent)
        assertEquals(listOf(1_000, 1_000, 500), remote.syncRequests.map { it.phones.size })
        assertEquals(listOf(1, 2, 3), remote.syncRequests.map { it.batch })
        assertEquals(listOf(false, false, true), remote.syncRequests.map { it.final })
        assertEquals(1, remote.syncRequests.map { it.syncId }.toSet().size)
    }

    @Test fun aLaterSyncSendsOnlyTheNewNumbers() = runSuspending {
        repo.syncPhoneBook(numbers(5))
        remote.syncRequests.clear()
        repo.syncPhoneBook(numbers(7))
        assertEquals(numbers(2, from = 5), remote.syncRequests.single().phones)
        remote.syncRequests.clear()
        repo.syncPhoneBook(numbers(7))
        assertTrue(remote.syncRequests.isEmpty())
    }

    @Test fun onlyE164NumbersAreSentAndDuplicatesAreDropped() = runSuspending {
        repo.syncPhoneBook(listOf("0712345678", "+254712345678", "+254712345678", "+254 712 345 678", "abc", ""))
        assertEquals(listOf("+254712345678"), remote.syncRequests.single().phones)
    }

    @Test fun anIncompleteSyncMarksNothingAsSentSoTheNextTryResendsEverything() = runSuspending {
        remote.syncFailAt = 2
        assertIs<AppResult.Failure>(repo.syncPhoneBook(numbers(1_500)))
        assertTrue(dao.synced.isEmpty())
        remote.syncFailAt = null
        remote.syncRequests.clear()
        repo.syncPhoneBook(numbers(1_500))
        assertEquals(1_500, remote.syncRequests.sumOf { it.phones.size })
    }

    // ---- the rest

    @Test fun connectingRefreshesTheContactList() = runSuspending {
        val connection = (repo.connect(ConnectVia.Qr, "payload") as AppResult.Success).value
        assertEquals("9", connection.person.userId)
        assertEquals(listOf("1", "2"), ids())
    }

    @Test fun invitesAndReachPassThrough() = runSuspending {
        assertEquals("tok", (repo.createInvite() as AppResult.Success).value.token)
        assertEquals(true, (repo.canReach("1") as AppResult.Success).value)
        assertIs<AppResult.Success<Unit>>(repo.revokeInvite("tok"))
    }
}
