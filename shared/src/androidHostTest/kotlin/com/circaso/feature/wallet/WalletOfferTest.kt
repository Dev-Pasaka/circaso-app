package com.circaso.feature.wallet

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.profile.domain.ProfileRepository
import com.circaso.feature.wallet.data.local.WalletDao
import com.circaso.feature.wallet.data.remote.WalletRemote
import com.circaso.feature.wallet.data.repository.WalletRepositoryImpl
import com.circaso.feature.wallet.domain.CreateWalletUseCase
import com.circaso.feature.wallet.domain.ObserveTransfersUseCase
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.RefreshWalletUseCase
import com.circaso.feature.wallet.domain.WalletRepository
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.presentation.WalletViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every member answers "nothing": flows are empty, suspend calls fail. Only the ones a test names matter. */
private inline fun <reified T : Any> quiet(noinline answer: (String) -> Any? = { null }): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        when {
            method.name == "toString" -> "quiet ${T::class.simpleName}"
            method.name == "hashCode" -> 0
            method.name == "equals" -> false
            else -> answer(method.name) ?: when {
                Flow::class.java.isAssignableFrom(method.returnType) -> flowOf(null)
                else -> AppResult.Failure(AppError.Unexpected(null))
            }
        }
    } as T

private class Online : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

/** The one-time Face ID / fingerprint offer right after a wallet is made (WalletOverlay). */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletOfferTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private var created: AppResult<WalletStatus> = AppResult.Success(WalletStatus.Active)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): WalletViewModel {
        val wallets = quiet<WalletRepository> { name -> when (name) { "create" -> created; "getTransfers" -> flowOf(emptyList<Any>()); else -> null } }
        val profiles = quiet<ProfileRepository>()
        val impl = WalletRepositoryImpl(quiet<WalletRemote>(), quiet<WalletDao>(), signer = { error("unused") }, currency = { null }, json = Json)
        return WalletViewModel(
            ObserveWalletUseCase(wallets), RefreshWalletUseCase(wallets), CreateWalletUseCase(wallets), ObserveTransfersUseCase(wallets),
            ConnectionState(Online(), NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0), ObserveProfileUseCase(profiles), impl,
        )
    }

    @Test fun aWalletThatIsReadyOffersBiometricsOnce() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        vm.onCreate()
        assertTrue(vm.state.value.offerBiometrics)
        vm.onBiometricsOffered()
        assertFalse(vm.state.value.offerBiometrics)
        job.cancel()
    }

    @Test fun aWalletStillOnItsWayDoesNotOfferYet() = runTest(dispatcher) {
        created = AppResult.Success(WalletStatus.SettingUp)
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        vm.onCreate()
        assertFalse(vm.state.value.offerBiometrics)
        job.cancel()
    }

    @Test fun aFailedSetupDoesNotOffer() = runTest(dispatcher) {
        created = AppResult.Failure(AppError.Unexpected(null))
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        vm.onCreate()
        assertFalse(vm.state.value.offerBiometrics)
        job.cancel()
    }
}
