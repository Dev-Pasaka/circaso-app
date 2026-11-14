package com.circaso.feature.contacts

import com.circaso.core.common.AppResult
import com.circaso.core.platform.ContactsReader
import com.circaso.feature.contacts.data.repository.PhoneBookAutoSync
import com.circaso.feature.contacts.domain.ConnectVia
import com.circaso.feature.contacts.domain.Connection
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.Invite
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.contacts.domain.SyncPhoneBookUseCase
import com.circaso.feature.contacts.domain.SyncSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Contacts on Circaso without asking: one prompt from Home, then syncs on Home and on every sign-in/online. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneBookAutoSyncTest {
    private class Reader(var access: Boolean = false, var grants: Boolean = true) : ContactsReader {
        var prompts = 0
        var book = mutableListOf("0712 345 678")
        val changed = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override suspend fun hasAccess() = access
        override suspend fun requestAccess(): Boolean { prompts++; access = grants; return access }
        override suspend fun readNumbers() = if (access) book.toList() else emptyList()
        override fun changes() = changed
    }

    private class Repo : ContactsRepository {
        val synced = mutableListOf<List<String>>()
        override val contacts: Flow<List<Contact>> = MutableStateFlow(emptyList())
        override val blocks: Flow<List<Person>> = MutableStateFlow(emptyList())
        override suspend fun refreshContacts() = AppResult.Success(Unit)
        override suspend fun refreshBlocks() = AppResult.Success(Unit)
        var answer: AppResult<SyncSummary>? = null
        override suspend fun syncPhoneBook(phones: List<String>): AppResult<SyncSummary> { synced += phones; return answer ?: AppResult.Success(SyncSummary(phones.size, emptyList())) }
        override suspend fun connect(via: ConnectVia, value: String): AppResult<Connection> = error("unused")
        override suspend fun createInvite(): AppResult<Invite> = error("unused")
        override suspend fun revokeInvite(token: String) = AppResult.Success(Unit)
        override suspend fun removeContact(userId: String) = Unit
        override suspend fun block(person: Person) = Unit
        override suspend fun unblock(userId: String) = Unit
        override suspend fun canReach(userId: String) = AppResult.Success(true)
    }

    private val ready = MutableStateFlow(false)
    private var asked = false
    private var clock = 0L

    private fun TestScope.autoSync(reader: Reader, repo: Repo) =
        PhoneBookAutoSync(reader, SyncPhoneBookUseCase(repo), ready, { asked }, { asked = true }, backgroundScope) { clock }
            .also { it.start() }

    @Test
    fun `the first Home asks once, and a yes syncs the phone book at once`() = runTest {
        val reader = Reader(); val repo = Repo()
        val sync = autoSync(reader, repo)

        sync.onHome()

        assertEquals(1, reader.prompts)
        assertEquals(1, repo.synced.size)
    }

    @Test
    fun `a no is respected - Home doesn't ask again and nothing syncs`() = runTest {
        val reader = Reader(grants = false); val repo = Repo()
        val sync = autoSync(reader, repo)

        sync.onHome()
        sync.onHome()
        ready.value = true; runCurrent()

        assertEquals(1, reader.prompts)
        assertEquals(0, repo.synced.size)
    }

    @Test
    fun `with access, signing in or coming online syncs, and an unchanged phone book sends nothing again`() = runTest {
        val reader = Reader(access = true); val repo = Repo()
        val sync = autoSync(reader, repo)

        ready.value = true; runCurrent()
        assertEquals(1, repo.synced.size)
        assertEquals(0, reader.prompts, "access was already allowed: no prompt")

        ready.value = false; runCurrent(); ready.value = true; runCurrent()
        sync.onForeground(); runCurrent()
        assertEquals(1, repo.synced.size, "nothing new in the phone book: no request")
    }

    @Test
    fun `a number saved in the phone book syncs on its own, spaced from the last request`() = runTest {
        val reader = Reader(access = true); val repo = Repo()
        autoSync(reader, repo)
        ready.value = true; runCurrent()
        assertEquals(1, repo.synced.size)

        clock += PhoneBookAutoSync.MinGapMillis
        reader.book += "0722 000 111"
        reader.changed.emit(Unit)
        advanceTimeBy(PhoneBookAutoSync.ChangeSettleMillis + 1); runCurrent()
        assertEquals(2, repo.synced.size)
        assertEquals("+254722000111", repo.synced.last().last())
    }

    @Test
    fun `a change inside the gap goes when the gap ends`() = runTest {
        val reader = Reader(access = true); val repo = Repo()
        val sync = autoSync(reader, repo)
        ready.value = true; runCurrent()

        reader.book += "0722 000 111"
        sync.onForeground(); runCurrent()
        assertEquals(1, repo.synced.size, "too soon after the last request")

        clock += PhoneBookAutoSync.MinGapMillis
        advanceTimeBy(PhoneBookAutoSync.MinGapMillis + 1); runCurrent()
        assertEquals(2, repo.synced.size)
    }

    @Test
    fun `too many requests waits for the time the server names`() = runTest {
        val reader = Reader(access = true); val repo = Repo()
        repo.answer = AppResult.Failure(com.circaso.core.common.AppError.Api("rate_limited", 429, null, 600, emptyMap()))
        val sync = autoSync(reader, repo)
        ready.value = true; runCurrent()
        assertEquals(1, repo.synced.size)

        repo.answer = null
        clock += PhoneBookAutoSync.MinGapMillis
        sync.onForeground(); runCurrent()
        assertEquals(1, repo.synced.size, "still inside the server's wait")

        clock += 600_000
        advanceTimeBy(600_001); runCurrent()
        assertEquals(2, repo.synced.size)
    }
}
