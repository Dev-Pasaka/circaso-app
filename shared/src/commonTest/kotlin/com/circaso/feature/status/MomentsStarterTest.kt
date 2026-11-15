package com.circaso.feature.status

import com.circaso.feature.status.data.repository.MomentsStarter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contacts' moments are fetched (and keys asked for) at launch and sign-in, whatever screen shows (Pasaka,
 * 2026-11-08), not when the Moments chip is first picked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MomentsStarterTest {
    private val repo = ViewerFakeMoments()
    private val ready = MutableStateFlow(false)
    private val socket = MutableStateFlow(false)

    @Test fun aColdLaunchSignedInAndOnlineFetchesAtOnce() = runTest(UnconfinedTestDispatcher()) {
        ready.value = true
        val job = backgroundScope
        MomentsStarter(ready, socket, { repo }, job).start()
        assertEquals(1, repo.refreshes)
    }

    @Test fun aFreshSignInFetchesTheMomentTheSessionTurnsActive() = runTest(UnconfinedTestDispatcher()) {
        MomentsStarter(ready, socket, { repo }, backgroundScope).start()
        assertEquals(0, repo.refreshes, "signed out: nothing to fetch")
        ready.value = true // registered, online
        assertEquals(1, repo.refreshes)
    }

    @Test fun signedInWithOrWithoutAConnectionPicksUpMomentsLeftBeingGotReady() = runTest(UnconfinedTestDispatcher()) {
        val signedIn = MutableStateFlow(false)
        MomentsStarter(ready, socket, { repo }, backgroundScope, signedIn = signedIn).start()
        assertEquals(0, repo.resumes, "nobody signed in: nothing of theirs to carry on")
        signedIn.value = true // the app started again, offline
        assertEquals(1, repo.resumes)
        assertEquals(0, repo.refreshes, "offline: nothing is fetched, but a video part-way made smaller carries on")
    }

    @Test fun eachSocketConnectionAndReconnectFetchesAgain() = runTest(UnconfinedTestDispatcher()) {
        ready.value = true
        MomentsStarter(ready, socket, { repo }, backgroundScope).start()
        socket.value = true
        assertEquals(2, repo.refreshes, "the socket's first connection may follow frames missed")
        socket.value = false
        ready.value = false
        ready.value = true
        socket.value = true
        assertEquals(4, repo.refreshes)
    }
}
