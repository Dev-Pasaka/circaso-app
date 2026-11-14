package com.circaso.feature.status

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.status.domain.LockState
import com.circaso.feature.status.domain.LockedMoment
import com.circaso.feature.status.domain.MomentKind
import com.circaso.feature.status.domain.PendingMoment
import com.circaso.feature.status.domain.PendingState
import com.circaso.feature.status.presentation.MyRing
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

    @Test fun myRingFillsAcrossABatchFromGettingReadyThroughSendingAndNeverRunsBack() = runTest(dispatcher) {
        fun p(id: String, state: PendingState, progress: Float?) = PendingMoment(id, MomentKind.Video, state, null, 0, progress)
        val vm = vm()
        val shown = mutableListOf<Float>()
        fun step(vararg rows: PendingMoment) { repo.pendingList.value = rows.toList(); vm.state.value.postingProgress?.let { shown += it } }
        // Two videos picked together: Post has just closed the composer.
        step(p("a", PendingState.Preparing, null), p("b", PendingState.Preparing, null))
        assertEquals(MyRing.Posting, vm.state.value.myRing)
        assertEquals(0f, shown.single(), "an arc from nothing, not a spinner")
        step(p("a", PendingState.Preparing, 0.3f), p("b", PendingState.Preparing, null))   // the encoder's own progress
        step(p("a", PendingState.Posting, 0.5f), p("b", PendingState.Preparing, null))     // ready: handed to the queue
        step(p("a", PendingState.Posting, 0.8f), p("b", PendingState.Preparing, 0.2f))     // sending, the next one being got ready
        step(p("a", PendingState.Posting, null), p("b", PendingState.Preparing, 0.2f))     // the upload starts again: held, not back
        step(p("a", PendingState.Posting, 0.95f), p("b", PendingState.Posting, 0.5f))
        step(p("b", PendingState.Posting, 0.7f))                                           // the first has posted: counts in full
        step(p("b", PendingState.Posting, 0.95f))
        assertEquals(shown.sorted(), shown, "only ever forward: $shown")
        assertEquals(0.975f, shown.last())
        // Gone from the queue: the batch is over and the next one starts from nothing.
        step()
        assertEquals(0, vm.state.value.postingBatch)
        step(p("c", PendingState.Preparing, null))
        assertEquals(0f, vm.state.value.postingProgress)
    }

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

    @Test fun aNewPhoneShowsTheRingWaitingForTheirPhoneWithNoDot() = runTest(dispatcher) {
        // Signed in on a new phone: Wanjiru's moments are listed but none opens here yet.
        repo.list.value = emptyList()
        repo.lockedList.value = listOf(LockedMoment("m1", "w", 10, Long.MAX_VALUE, LockState.Waiting), LockedMoment("m2", "w", 20, Long.MAX_VALUE, LockState.Waiting))
        val s = vm().state.value

        val ring = s.recent.single()
        assertEquals("w", ring.posterId)
        assertTrue(ring.allLocked)
        assertEquals(2, ring.waiting)
        assertEquals(20, ring.latestAt)
        assertFalse(s.empty, "not the empty state: someone has moments")
        assertFalse(s.hasUnseen, "no dot for something that can't be opened yet")
        assertTrue(s.unseenPosters.isEmpty())
    }

    @Test fun onceTheKeysComeTheRingIsAnOrdinaryOne() = runTest(dispatcher) {
        repo.list.value = emptyList()
        repo.lockedList.value = listOf(LockedMoment("m1", "w", 1, Long.MAX_VALUE, LockState.Waiting))
        val vm = vm()
        assertTrue(vm.state.value.recent.single().allLocked)

        repo.lockedList.value = emptyList()
        repo.list.value = listOf(moment("m1").copy(viewed = false))
        val ring = vm.state.value.recent.single()
        assertFalse(ring.allLocked)
        assertEquals(listOf("m1"), ring.moments.map { it.momentId })
        assertTrue(vm.state.value.hasUnseen)
    }

    @Test fun unavailableMomentsAloneSitWithTheViewedOnes() = runTest(dispatcher) {
        repo.list.value = emptyList()
        repo.lockedList.value = listOf(LockedMoment("m1", "w", 1, Long.MAX_VALUE, LockState.Unavailable))
        val s = vm().state.value
        assertTrue(s.recent.isEmpty())
        assertEquals(0, s.viewed.single().waiting)
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
