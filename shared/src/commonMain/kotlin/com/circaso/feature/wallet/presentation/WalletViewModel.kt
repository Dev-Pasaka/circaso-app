package com.circaso.feature.wallet.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.wallet.data.repository.WalletRepositoryImpl
import com.circaso.feature.wallet.domain.CreateWalletUseCase
import com.circaso.feature.wallet.domain.ObserveTransfersUseCase
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.RefreshWalletUseCase
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A sheet that says a designed flow has no backend yet (E2 Add money, E6 Cash out). */
enum class ComingFlow { AddMoney, CashOut }

data class WalletUiState(
    /** Null until the first read; `status` None means "Create wallet". */
    val wallet: Wallet? = null,
    val recent: List<Transfer> = emptyList(),
    val online: Boolean = true,
    val loading: Boolean = false,
    /** The Circle SDK is on screen, or the wallet is being made on chain. */
    val creating: Boolean = false,
    val hidden: Boolean = false,
    val handle: String? = null,
    val error: String? = null,
    /** Who asked this person to set up a wallet (`setup.requested`), until a wallet exists. */
    val setupRequestedBy: String? = null,
    val receiveSheet: Boolean = false,
    val coming: ComingFlow? = null,
    /** Supported networks this wallet isn't on yet (everyone should hold all of them; decision 2026-10-30). */
    val missingNetworks: List<String> = emptyList(),
    val addingNetworks: Boolean = false,
    /** The wallet was just made on this phone: offer Face ID / fingerprint approval once, while the PIN is fresh. */
    val offerBiometrics: Boolean = false,
) {
    val status get() = wallet?.status ?: WalletStatus.None
    val active get() = status == WalletStatus.Active
    val canSend get() = active && online && wallet?.holdUntil == null
}

/** The Wallet tab (design E1): balance, the four actions, coins, recent payments; create and set-up states before that. */
class WalletViewModel(
    observeWallet: ObserveWalletUseCase,
    private val refreshWallet: RefreshWalletUseCase,
    private val createWallet: CreateWalletUseCase,
    observeTransfers: ObserveTransfersUseCase,
    connection: ConnectionState,
    observeProfile: ObserveProfileUseCase,
    repository: WalletRepositoryImpl,
    private val getChains: com.circaso.feature.wallet.domain.GetChainsUseCase? = null,
    private val addMissingChains: com.circaso.feature.wallet.domain.AddMissingChainsUseCase? = null,
) : ViewModel() {
    private val local = MutableStateFlow(WalletUiState())

    val state: StateFlow<WalletUiState> = combine(local, observeWallet(), observeTransfers(), connection.status, observeProfile(), repository.setupRequestedBy) { values ->
        @Suppress("UNCHECKED_CAST")
        val ui = values[0] as WalletUiState
        ui.copy(
            wallet = values[1] as Wallet?,
            // Enough for every network's five: the wallet tab shows the five of the card in front.
            recent = (values[2] as List<Transfer>).take(60),
            online = values[3] == ConnectionStatus.Online,
            handle = (values[4] as com.circaso.feature.profile.domain.Profile?)?.handle,
            setupRequestedBy = values[5] as String?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalletUiState())

    init { refresh() }

    /** [fromPull]: the person asked, so a failure is said out loud; the quiet background refreshes stay quiet. */
    fun refresh(fromPull: Boolean = false) {
        if (local.value.loading) return
        local.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = refreshWallet()
            loadMissingNetworks()
            local.update { it.copy(loading = false, error = (result as? AppResult.Failure)?.let { f -> ErrorMessages.forError(f.error) }?.takeIf { _ -> fromPull || state.value.wallet == null }) }
        }
    }

    /** W2 then the Circle SDK: the person sets a PIN and security questions, Circle makes the wallet on Base. */
    fun onCreate() {
        if (local.value.creating || !state.value.online) return
        local.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            when (val r = createWallet()) {
                is AppResult.Success -> local.update {
                    val ready = r.value == WalletStatus.Active
                    it.copy(creating = false, offerBiometrics = ready, error = if (ready) null else "Your wallet is on its way. It will be ready in a moment.")
                }
                is AppResult.Failure -> local.update { it.copy(creating = false, error = ErrorMessages.forError(r.error)) }
            }
        }
    }

    private suspend fun loadMissingNetworks() {
        val chains = (getChains?.invoke() as? AppResult.Success)?.value ?: return
        val missing = chains.chains.filter { it.status == com.circaso.feature.wallet.domain.ChainStatus.Available && it.receiveEnabled }.map { it.blockchain }
        local.update { it.copy(missingNetworks = missing) }
    }

    /** One tap (and the PIN) puts the wallet on every network it's missing. */
    fun onAddMissingNetworks() {
        val add = addMissingChains ?: return
        if (local.value.addingNetworks || !state.value.online) return
        local.update { it.copy(addingNetworks = true, error = null) }
        viewModelScope.launch {
            val r = add()
            local.update { it.copy(addingNetworks = false, error = (r as? AppResult.Failure)?.let { f -> ErrorMessages.forError(f.error) }) }
            loadMissingNetworks()
        }
    }

    fun onToggleHidden() = local.update { it.copy(hidden = !it.hidden) }
    fun onReceive(open: Boolean) = local.update { it.copy(receiveSheet = open) }
    fun onComing(flow: ComingFlow?) = local.update { it.copy(coming = flow) }
    fun onErrorShown() = local.update { it.copy(error = null) }
    fun onBiometricsOffered() = local.update { it.copy(offerBiometrics = false) }
}
