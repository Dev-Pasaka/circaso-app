package com.circaso.feature.status

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.status.presentation.MomentsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Moments chip's list in Chats: how a refresh's answer becomes what the list says (S1d, S1e, S1f). */
@OptIn(ExperimentalCoroutinesApi::class)
class MomentsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val monitor = object : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }
    private lateinit var connection: ConnectionState
    private val repo = ViewerFakeMoments()

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm() = MomentsViewModel(repo, ObserveContactsUseCase(FakeContacts()), ObserveProfileUseCase(FakeProfile()), connection)

    @Test fun aWorkingCheckShowsTheRingsWithNoErrorLine() = runTest(dispatcher) {
        val vm = vm()
        val s = vm.state.value
        assertTrue(s.refreshed)
        assertFalse(s.failed)
        assertFalse(s.refreshing)
        // Wanjiru (a contact) has a moment I've seen: Viewed, no dot.
        assertEquals(listOf("w"), s.viewed.map { it.posterId })
        assertFalse(s.hasUnseen)
    }

    @Test fun offlineIsNotAnError() = runTest(dispatcher) {
        repo.refreshResult = AppResult.Failure(AppError.Network)
        val s = vm().state.value
        assertTrue(s.refreshed)
        assertFalse(s.failed)
        assertNull(s.requestId)
    }

    @Test fun aFailedCheckWithMomentsOnScreenKeepsThemAndSaysSoWithTheReference() = runTest(dispatcher) {
        repo.refreshResult = AppResult.Failure(AppError.Api("server.error", 500, "req_1", null, emptyMap()))
        val s = vm().state.value
        assertTrue(s.failed)
        assertTrue(s.checkFailedWithData, "the stored rings stay on screen")
        assertTrue(s.requestId.orEmpty().startsWith("req_1"))
        assertEquals(1, s.viewed.size)
    }

    @Test fun aFailedCheckWithNothingStoredIsTheEmptyStateWithTheLine() = runTest(dispatcher) {
        repo.list.value = emptyList()
        repo.refreshResult = AppResult.Failure(AppError.Api("server.error", 500, "req_2", null, emptyMap()))
        val s = vm().state.value
        assertTrue(s.failed)
        assertFalse(s.checkFailedWithData)
    }

    @Test fun tryAgainAlwaysAsksEvenRightAfterACheck() = runTest(dispatcher) {
        val vm = vm()
        val first = repo.refreshes
        vm.refresh() // a tab switch straight after: held back
        assertEquals(first, repo.refreshes)
        repo.refreshResult = AppResult.Success(Unit)
        vm.refresh(force = true) // Try Again
        assertEquals(first + 1, repo.refreshes)
    }
}
