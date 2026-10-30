package com.circaso.core

import com.circaso.core.network.circasoJson
import com.circaso.core.session.AuthManager
import com.circaso.core.session.DeviceIdentity
import com.circaso.core.session.Jwt
import com.circaso.core.session.SessionDataCleaner
import com.circaso.core.session.SessionDto
import com.circaso.core.session.SessionStore
import com.circaso.core.sync.Outbox
import com.circaso.testing.FakeKeys
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.MemoryStorage
import com.circaso.testing.runSuspending
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalEncodingApi::class)
fun fakeJwt(subject: String): String =
    "header." + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("""{"sub":"$subject","exp":9999999999}""".encodeToByteArray()) + ".signature"

class JwtTest {
    @Test fun readsTheSubject() = assertEquals("user-123", Jwt.subject(fakeJwt("user-123")))
    @Test fun notATokenGivesNull() { assertNull(Jwt.subject("abc")); assertNull(Jwt.subject(null)); assertNull(Jwt.subject("a.@@@.c")) }
    @Test fun noSubjectGivesNull() = assertNull(Jwt.subject("h." + Base64.UrlSafe.encode("""{"exp":1}""".encodeToByteArray()) + ".s"))
}

class OwnershipTest {
    private var user: String? = "alice"
    private val dao = FakeOutboxDao()
    private val outbox = Outbox(dao, owner = { user }) { 1_000L }

    @Test fun aWriteIsOnlyEverHandedOutToItsOwner() = runSuspending {
        outbox.enqueue("t", "mine")
        assertEquals("mine", outbox.next()?.payload)
        user = "bob"
        assertNull(outbox.next()) // alice's write is never sent as bob
        user = "alice"
        assertEquals("mine", outbox.next()?.payload)
    }

    @Test fun editsAreNotMergedAcrossPeople() = runSuspending {
        outbox.enqueueOrMerge("t", "a") { _, new -> new }
        user = "bob"
        outbox.enqueueOrMerge("t", "b") { _, new -> new }
        assertEquals(listOf("alice", "bob"), dao.rows.value.map { it.ownerId })
    }

    // ---- who clears what

    private var cleared = 0
    private val storage = MemoryStorage()
    private val store = SessionStore(storage, circasoJson())
    private val auth = AuthManager(store, DeviceIdentity(storage, FakeKeys()), { error("no refresh") }, { 1_000L }, SessionDataCleaner { cleared++ })
    private fun session(userId: String) = SessionDto(fakeJwt(userId), "refresh", 900, "app")

    @Test fun theSameUserSigningBackInKeepsTheirData() = runSuspending {
        auth.startSession(session("alice"), null)
        auth.signOutLocally(endedByServer = true)
        auth.startSession(session("alice"), null)
        assertEquals(0, cleared)
    }

    @Test fun aDifferentUserNeverInheritsTheirData() = runSuspending {
        auth.startSession(session("alice"), null)
        auth.signOutLocally(endedByServer = true) // the server ended it: alice's data stays for now
        assertEquals(0, cleared)
        auth.startSession(session("bob"), null)
        assertEquals(1, cleared)
    }

    @Test fun chosingToSignOutClearsEverything() = runSuspending {
        auth.startSession(session("alice"), null)
        auth.signOutLocally()
        assertEquals(1, cleared)
        assertNull(store.dataOwner())
    }

    @Test fun theFirstEverSignInClearsNothing() = runSuspending {
        auth.startSession(session("alice"), null)
        assertEquals(0, cleared)
        assertEquals("alice", store.dataOwner())
        assertEquals("alice", auth.userId)
    }
}
