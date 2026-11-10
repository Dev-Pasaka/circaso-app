package com.circaso.feature.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.account.presentation.BiometricApprovalViewModel
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletRepository
import com.circaso.feature.wallet.domain.WalletStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
}

private class Monitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

@OptIn(ExperimentalCoroutinesApi::class)
class BiometricApprovalTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val wallet = MutableStateFlow<Wallet?>(Wallet(WalletStatus.Active, emptyList(), emptyList(), null, null, 0))
    private var calls = 0
    private var answer: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }

    private val repo = Proxy.newProxyInstance(WalletRepository::class.java.classLoader, arrayOf(WalletRepository::class.java)) { _, method, _ ->
        when (method.name) {
            "getWallet" -> wallet
            else -> error("not used: ${method.name}")
        }
    } as WalletRepository

    /** The proxy can't run suspend code, so the one suspend member is delegated by hand. */
    private val wallets = object : WalletRepository by repo {
        override suspend fun enableBiometricApproval(): AppResult<Unit> { calls++; return answer() }
    }

    private var enrolled = true
    private val biometrics = object : com.circaso.core.platform.Biometrics {
        override val name = "Fingerprint"
        override fun available() = enrolled
    }
    private lateinit var monitor: Monitor
    private lateinit var prefs: AppPreferences
    private lateinit var vm: BiometricApprovalViewModel

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        monitor = Monitor()
        prefs = AppPreferences(MemoryStore())
        vm = BiometricApprovalViewModel(wallets, prefs, ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0), biometrics)
    }

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun CoroutineScope.watch() = launch { vm.state.collect {} }

    @Test fun turningItOnRemembersItAndShowsOn() = runTest(dispatcher) {
        val job = watch()
        vm.onEnable()
        assertTrue(vm.state.value.enabled)
        assertTrue(prefs.flag(AppPreferences.BiometricApproval).first())
        job.cancel()
    }

    @Test fun aSecondTapWhileCircleIsOpenDoesNothing() = runTest(dispatcher) {
        val job = watch()
        val gate = CompletableDeferred<AppResult<Unit>>()
        answer = { gate.await() }
        vm.onEnable()
        assertTrue(vm.state.value.busy)
        vm.onEnable()
        assertEquals(1, calls)
        gate.complete(AppResult.Success(Unit))
        job.cancel()
    }

    @Test fun backingOutOfThePinShowsNoError() = runTest(dispatcher) {
        val job = watch()
        answer = { AppResult.Failure(AppError.Api("wallet.cancelled", 400, null, null, emptyMap())) }
        vm.onEnable()
        assertFalse(vm.state.value.enabled)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.busy)
        job.cancel()
    }

    @Test fun aRealFailureSaysSo() = runTest(dispatcher) {
        val job = watch()
        answer = { AppResult.Failure(AppError.Api("wallet.sdk_failed", 400, null, null, emptyMap())) }
        vm.onEnable()
        assertFalse(vm.state.value.enabled)
        assertNotNull(vm.state.value.error)
        job.cancel()
    }

    @Test fun offlineItWaits() = runTest(dispatcher) {
        val job = watch()
        monitor.osStatus.value = ConnectionStatus.Offline
        assertFalse(vm.state.value.online)
        vm.onEnable()
        assertEquals(0, calls)
        job.cancel()
    }

    @Test fun signingOutForgetsIt() = runTest(dispatcher) {
        prefs.setFlag(AppPreferences.BiometricApproval, true)
        prefs.clear()
        assertFalse(prefs.flag(AppPreferences.BiometricApproval).first())
    }

    @Test fun nothingEnrolledNothingToTurnOn() = runTest(dispatcher) {
        enrolled = false
        setUp()
        val job = watch()
        assertFalse(vm.state.value.supported)
        vm.onEnable()
        assertEquals(0, calls)
        job.cancel()
    }

    @Test fun hiddenWithoutAWallet() = runTest(dispatcher) {
        val job = watch()
        wallet.value = null
        assertFalse(vm.state.value.hasWallet)
        job.cancel()
    }

    @Test fun turningItOffAsksFirstThenGoesBackToThePinEvenOffline() = runTest(dispatcher) {
        val job = watch()
        prefs.setWalletBiometrics(null, true)
        monitor.osStatus.value = ConnectionStatus.Offline
        vm.onTurnOffAsked()
        assertTrue(vm.state.value.confirmOff)
        assertTrue(vm.state.value.enabled, "nothing changes until the person confirms")
        vm.onTurnOff()
        assertFalse(vm.state.value.confirmOff)
        assertFalse(vm.state.value.enabled)
        assertFalse(prefs.walletBiometrics(null).first())
        assertEquals(0, calls, "turning off needs no Circle call")
        job.cancel()
    }

    @Test fun keepingItOnChangesNothing() = runTest(dispatcher) {
        val job = watch()
        prefs.setWalletBiometrics(null, true)
        vm.onTurnOffAsked()
        vm.onTurnOffDismissed()
        assertFalse(vm.state.value.confirmOff)
        assertTrue(vm.state.value.enabled)
        job.cancel()
    }

    @Test fun lostSaysSoAndTurningOnAgainClearsIt() = runTest(dispatcher) {
        val job = watch()
        prefs.setFlag(AppPreferences.biometricLostFlag(null), true)
        assertTrue(vm.state.value.lost)
        vm.onEnable()
        assertTrue(vm.state.value.enabled)
        assertFalse(vm.state.value.lost)
        assertFalse(prefs.flag(AppPreferences.biometricLostFlag(null)).first())
        assertTrue(prefs.flag(AppPreferences.biometricOfferedFlag(null)).first(), "on: no offer after the next payment")
        job.cancel()
    }

    @Test fun answeringTheWalletOfferUsesUpTheOneOffer() = runTest(dispatcher) {
        vm.onOffered()
        assertTrue(prefs.flag(AppPreferences.biometricOfferedFlag(null)).first())
    }

    @Test fun signingOutForgetsTheOfferToo() = runTest(dispatcher) {
        prefs.setFlag(AppPreferences.biometricOfferedFlag("gw-1"), true)
        prefs.clear()
        assertFalse(prefs.flag(AppPreferences.biometricOfferedFlag("gw-1")).first())
    }
}
