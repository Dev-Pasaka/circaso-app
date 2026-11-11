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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Contacts on Circaso without asking: one prompt from Home, then syncs on Home and on every sign-in/online. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneBookAutoSyncTest {
    private class Reader(var access: Boolean = false, var grants: Boolean = true) : ContactsReader {
        var prompts = 0
        override suspend fun hasAccess() = access
        override suspend fun requestAccess(): Boolean { prompts++; access = grants; return access }
        override suspend fun readNumbers() = if (access) listOf("0712 345 678") else emptyList()
    }

    private class Repo : ContactsRepository {
        val synced = mutableListOf<List<String>>()
        override val contacts: Flow<List<Contact>> = MutableStateFlow(emptyList())
        override val blocks: Flow<List<Person>> = MutableStateFlow(emptyList())
        override suspend fun refreshContacts() = AppResult.Success(Unit)
        override suspend fun refreshBlocks() = AppResult.Success(Unit)
        override suspend fun syncPhoneBook(phones: List<String>): AppResult<SyncSummary> { synced += phones; return AppResult.Success(SyncSummary(phones.size, emptyList())) }
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
    fun `with access, signing in or coming online syncs, at most once an hour`() = runTest {
        val reader = Reader(access = true); val repo = Repo()
        autoSync(reader, repo)

        ready.value = true; runCurrent()
        assertEquals(1, repo.synced.size)
        assertEquals(0, reader.prompts, "access was already allowed: no prompt")

        ready.value = false; runCurrent(); ready.value = true; runCurrent()
        assertEquals(1, repo.synced.size, "again within the hour: skipped")

        clock += PhoneBookAutoSync.MinGapMillis
        ready.value = false; runCurrent(); ready.value = true; runCurrent()
        assertEquals(2, repo.synced.size)
    }
}
