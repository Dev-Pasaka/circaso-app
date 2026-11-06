package com.circaso.feature.wallet

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.wallet.domain.Amount
import com.circaso.feature.wallet.domain.LoadActivityPageUseCase
import com.circaso.feature.wallet.domain.ObserveTransfersUseCase
import com.circaso.feature.wallet.domain.ObserveWalletActivityUseCase
import com.circaso.feature.wallet.domain.ObserveWalletOfUseCase
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.WalletRepository
import com.circaso.feature.wallet.presentation.ActivityFilter
import com.circaso.feature.wallet.presentation.ActivityRange
import com.circaso.feature.wallet.presentation.ActivityViewModel
import com.circaso.feature.wallet.presentation.listRow
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private inline fun <reified T : Any> plainStub(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        when (method.name) {
            "toString" -> "stub"
            "hashCode" -> 0
            "equals" -> false
            else -> if (Flow::class.java.isAssignableFrom(method.returnType)) flowOf(null) else AppResult.Failure(AppError.Unexpected(null))
        }
    } as T

private fun payment(id: String, direction: TransferDirection, at: Long, toGroupId: String? = null, counterpartGroupId: String? = null, name: String? = null) =
    Transfer(id, direction, TransferState.Confirmed, "BASE-SEPOLIA", "0xfrom", null, "0xto", Amount("USDC", "1000000", "1"), null, null, null, null, name, null, at, at,
        toGroupId = toGroupId, counterpartGroupId = counterpartGroupId)

/** Transactions (E7): never "nothing yet" before the list is known, its own read, paging, filters and dates. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val rows = MutableStateFlow<List<Transfer>>(emptyList())
    private var gate = CompletableDeferred<AppResult<String?>>()
    private val cursors = mutableListOf<String?>()

    private val repo = object : WalletRepository by plainStub<WalletRepository>() {
        override val transfers: Flow<List<Transfer>> get() = rows
        override val wallet: Flow<com.circaso.feature.wallet.domain.Wallet?> get() = flowOf(null)
        override fun activity(walletId: String): Flow<List<Transfer>> = rows
        override fun walletOf(walletId: String?): Flow<com.circaso.feature.wallet.domain.Wallet?> = flowOf(null)
        override suspend fun loadActivityPage(walletId: String?, cursor: String?): AppResult<String?> {
            cursors += cursor
            return gate.await()
        }
    }

    private fun vm(walletId: String? = null) = ActivityViewModel(
        ObserveTransfersUseCase(repo), ObserveWalletUseCase(repo), walletId = walletId,
        observeWalletOf = ObserveWalletOfUseCase(repo), observeActivity = ObserveWalletActivityUseCase(repo), loadPage = LoadActivityPageUseCase(repo),
    )

    private fun TestScope.keep(vm: ActivityViewModel) { backgroundScope.launch(dispatcher) { vm.state.collect { } } }

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun anEmptyPhoneCopyIsNotNothingUntilThePageHasBeenRead() = runTest(dispatcher) {
        val vm = vm()
        keep(vm)
        assertFalse(vm.state.value.loaded, "rows' shape while W6 is read, never 'No transactions yet'")
        gate.complete(AppResult.Success(null))
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.hasMore)
    }

    @Test fun savedRowsShowAtOnce() = runTest(dispatcher) {
        rows.value = listOf(payment("a", TransferDirection.Out, 1))
        val vm = vm()
        keep(vm)
        assertTrue(vm.state.value.loaded)
    }

    @Test fun aFailedReadWithNothingSavedSaysSoAndTryAgainReadsAgain() = runTest(dispatcher) {
        val vm = vm()
        keep(vm)
        gate.complete(AppResult.Failure(AppError.Unexpected(null)))
        assertTrue(vm.state.value.loaded)
        assertNotNull(vm.state.value.error)
        gate = CompletableDeferred(AppResult.Success(null))
        vm.refresh()
        assertNull(vm.state.value.error)
        assertEquals(listOf<String?>(null, null), cursors)
    }

    @Test fun olderPagesFollowTheCursor() = runTest(dispatcher) {
        rows.value = listOf(payment("a", TransferDirection.Out, 1))
        val vm = vm()
        keep(vm)
        gate.complete(AppResult.Success("c2"))
        assertTrue(vm.state.value.hasMore)
        gate = CompletableDeferred(AppResult.Success(null))
        vm.loadMore()
        assertEquals(listOf(null, "c2"), cursors)
        assertFalse(vm.state.value.hasMore)
    }

    @Test fun aFailedOlderPageIsReadOnceThenWaitsForTryAgain() = runTest(dispatcher) {
        rows.value = listOf(payment("a", TransferDirection.Out, 1))
        val vm = vm()
        keep(vm)
        gate.complete(AppResult.Success("c2"))
        gate = CompletableDeferred(AppResult.Failure(AppError.Unexpected(null)))
        vm.loadMore()
        vm.loadMore()
        vm.loadMore()
        assertEquals(listOf(null, "c2"), cursors, "a failed page is not read again in a loop")
        assertTrue(vm.state.value.moreFailed)
        assertTrue(vm.state.value.hasMore, "the cursor is kept")
        gate = CompletableDeferred(AppResult.Success(null))
        vm.retryMore()
        assertEquals(listOf(null, "c2", "c2"), cursors)
        assertFalse(vm.state.value.moreFailed)
        assertFalse(vm.state.value.hasMore)
    }

    @Test fun aFailedRefreshKeepsTheSavedRowsAndSaysSo() = runTest(dispatcher) {
        rows.value = listOf(payment("a", TransferDirection.Out, 1))
        val vm = vm()
        keep(vm)
        gate.complete(AppResult.Failure(AppError.Unexpected(null)))
        assertTrue(vm.state.value.loaded)
        assertNotNull(vm.state.value.error, "the list says it couldn't refresh")
        assertEquals(1, vm.state.value.shown.size)
    }

    @Test fun aSecondRefreshWhileOneRunsDoesNotReadTwice() = runTest(dispatcher) {
        val vm = vm()
        keep(vm)
        vm.refresh(fromPull = true)
        assertTrue(vm.state.value.refreshing)
        gate.complete(AppResult.Success(null))
        assertEquals(listOf<String?>(null), cursors)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun aGroupWalletHasNoGroupsChipAndItsRowsAreContributionsAndPayouts() = runTest(dispatcher) {
        val vm = vm("gw-1")
        keep(vm)
        assertFalse(ActivityFilter.Groups in vm.state.value.filters)
        val (named, kind) = payment("a", TransferDirection.In, 1).listRow(groupWallet = true)
        assertEquals("Contribution", kind)
        assertEquals("0xfrom", named.counterpartName, "an outside sender by address, never 'Received'")
        assertEquals("Payout", payment("b", TransferDirection.Out, 1).listRow(groupWallet = true).second)
    }

    @Test fun groupsFilterAndRowsForThePersonalWallet() = runTest(dispatcher) {
        rows.value = listOf(
            payment("a", TransferDirection.Out, 2, toGroupId = "g1", name = "Holiday Fund"),
            payment("b", TransferDirection.Out, 1, toGroupId = "space:old"),
            payment("c", TransferDirection.In, 0),
        )
        val vm = vm()
        keep(vm)
        assertTrue(ActivityFilter.Groups in vm.state.value.filters)
        vm.onFilter(ActivityFilter.Groups)
        assertEquals(listOf("a"), vm.state.value.shown.map { it.transferId }, "an archived Space is not a group")
        assertEquals("Contribution", vm.state.value.shown.single().listRow(groupWallet = false).second)
        assertNull(rows.value[2].listRow(groupWallet = false).second)
    }

    @Test fun theDateRangeKeepsOnlyRecentPayments() = runTest(dispatcher) {
        val now = com.circaso.core.sync.nowMillis()
        rows.value = listOf(payment("new", TransferDirection.Out, now), payment("old", TransferDirection.Out, now - 40L * 24 * 3600 * 1000))
        val vm = vm()
        keep(vm)
        vm.onRange(ActivityRange.Month)
        assertEquals(listOf("new"), vm.state.value.shown.map { it.transferId })
        vm.onRange(ActivityRange.Any)
        assertEquals(2, vm.state.value.shown.size)
    }
}
