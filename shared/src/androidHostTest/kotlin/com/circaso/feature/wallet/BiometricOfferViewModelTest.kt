package com.circaso.feature.wallet

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
import com.circaso.feature.wallet.domain.EnableWalletBiometricsUseCase
import com.circaso.feature.wallet.domain.PinApprovals
import com.circaso.feature.wallet.domain.SignSubject
import com.circaso.feature.wallet.domain.WalletRepository
import com.circaso.feature.wallet.presentation.BiometricOfferViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

private class OfferStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
}

private class OfferMonitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

/** The one-time "approve with fingerprint?" sheet after a PIN payment (Pasaka, 2026-11-10). */
@OptIn(ExperimentalCoroutinesApi::class)
class BiometricOfferViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val approvals = PinApprovals()
    private var enrolled = true
    private val biometrics = object : com.circaso.core.platform.Biometrics {
        override val name = "Fingerprint"
        override fun available() = enrolled
    }
    private val enabledFor = mutableListOf<SignSubject>()
    private var answer: AppResult<Unit> = AppResult.Success(Unit)
    private val wallets = object : WalletRepository by (Proxy.newProxyInstance(WalletRepository::class.java.classLoader, arrayOf(WalletRepository::class.java)) { _, m, _ -> error("not used: ${m.name}") } as WalletRepository) {
        override suspend fun enableBiometricApproval(subject: SignSubject): AppResult<Unit> { enabledFor += subject; return answer }
    }
    private lateinit var prefs: AppPreferences
    private lateinit var monitor: OfferMonitor
    private lateinit var vm: BiometricOfferViewModel

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        prefs = AppPreferences(OfferStore())
        monitor = OfferMonitor()
        vm = BiometricOfferViewModel(
            approvals, prefs, ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0), biometrics,
            EnableWalletBiometricsUseCase(wallets), settleMillis = 0,
        )
    }

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun aPinPaymentBringsTheOfferOnceAndOnlyOnce() = runTest(dispatcher) {
        approvals.report(SignSubject.Personal)
        advanceUntilIdle()
        assertEquals(SignSubject.Personal, vm.state.value.subject)
        assertTrue(prefs.flag(AppPreferences.biometricOfferedFlag(null)).first(), "made the moment it shows, so a closed app doesn't bring it back")
        vm.onNotNow()
        assertNull(vm.state.value.subject)
        approvals.report(SignSubject.Personal)
        advanceUntilIdle()
        assertNull(vm.state.value.subject, "never twice")
    }

    @Test fun turningItOnRunsCirclesSetUpForThatWallet() = runTest(dispatcher) {
        val group = SignSubject.group("gw-1", "Kilimani Chama")
        approvals.report(group)
        advanceUntilIdle()
        vm.onTurnOn()
        advanceUntilIdle()
        assertEquals("gw-1", enabledFor.single().walletId)
        assertTrue(prefs.walletBiometrics("gw-1").first())
        assertFalse(prefs.walletBiometrics(null).first(), "only that wallet")
        assertNull(vm.state.value.subject)
    }

    @Test fun backingOutOfThePinKeepsTheOfferUpWithoutAnError() = runTest(dispatcher) {
        answer = AppResult.Failure(AppError.Api("wallet.cancelled", 400, null, null, emptyMap()))
        approvals.report(SignSubject.Personal)
        advanceUntilIdle()
        vm.onTurnOn()
        advanceUntilIdle()
        assertNotNull(vm.state.value.subject)
        assertNull(vm.state.value.error)
        assertFalse(prefs.walletBiometrics(null).first())
    }

    @Test fun aFailureSaysWhatToDo() = runTest(dispatcher) {
        answer = AppResult.Failure(AppError.Api("wallet.biometrics_unavailable", 400, null, null, emptyMap()))
        approvals.report(SignSubject.Personal)
        advanceUntilIdle()
        vm.onTurnOn()
        advanceUntilIdle()
        assertNotNull(vm.state.value.error)
    }

    @Test fun noOfferWithoutEnrolledBiometricsOrWhenAlreadyOn() = runTest(dispatcher) {
        enrolled = false
        approvals.report(SignSubject.Personal)
        advanceUntilIdle()
        assertNull(vm.state.value.subject)
        assertFalse(prefs.flag(AppPreferences.biometricOfferedFlag(null)).first(), "kept for a phone that gets a fingerprint later")
        enrolled = true
        prefs.setWalletBiometrics(null, true)
        approvals.report(SignSubject.Personal)
        advanceUntilIdle()
        assertNull(vm.state.value.subject)
    }

    @Test fun itWaitsForTheReceiptAndCallsToClear() = runTest(dispatcher) {
        vm.hold(true)
        approvals.report(SignSubject.Personal)
        advanceUntilIdle()
        assertNull(vm.state.value.subject, "the pay screen's receipt or a call is still up")
        vm.hold(false)
        advanceUntilIdle()
        assertEquals(SignSubject.Personal, vm.state.value.subject)
    }

    @Test fun turningItOnSaysSo() = runTest(dispatcher) {
        val said = mutableListOf<String>()
        val job = kotlinx.coroutines.CoroutineScope(dispatcher).launch { vm.turnedOn.collect { said += it } }
        approvals.report(SignSubject.group("gw-1", "Kilimani Chama"))
        advanceUntilIdle()
        vm.onTurnOn()
        advanceUntilIdle()
        assertEquals(listOf("Fingerprint is on for Kilimani Chama payments."), said)
        job.cancel()
    }

    @Test fun offlineTheButtonWaits() = runTest(dispatcher) {
        approvals.report(SignSubject.Personal)
        advanceUntilIdle()
        monitor.osStatus.value = ConnectionStatus.Offline
        advanceUntilIdle()
        vm.onTurnOn()
        assertTrue(enabledFor.isEmpty())
    }
}
