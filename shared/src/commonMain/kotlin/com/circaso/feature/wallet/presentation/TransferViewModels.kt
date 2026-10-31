package com.circaso.feature.wallet.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.wallet.domain.AddChainUseCase
import com.circaso.feature.wallet.domain.ChainStatus
import com.circaso.feature.wallet.domain.Chains
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.GetChainsUseCase
import com.circaso.feature.wallet.domain.LoadTransferUseCase
import com.circaso.feature.wallet.domain.ObserveTransferUseCase
import com.circaso.feature.wallet.domain.ObserveTransfersUseCase
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.TransferDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Design E7: every payment this phone knows, newest first, with the design's filter chips. */
enum class ActivityFilter(val label: String) { All("All"), Sent("Sent"), Received("Received") }

data class ActivityUiState(
    val transfers: List<Transfer> = emptyList(),
    val filter: ActivityFilter = ActivityFilter.All,
    val fiat: FiatValue? = null,
    /** An asset's statement: the network and coin it is for, and what the wallet holds of it there. */
    val blockchain: String? = null,
    val symbol: String? = null,
    val held: com.circaso.feature.wallet.domain.Asset? = null,
    val query: String = "",
) {
    val shown get() = when (filter) {
        ActivityFilter.All -> transfers
        ActivityFilter.Sent -> transfers.filter { it.direction == TransferDirection.Out }
        ActivityFilter.Received -> transfers.filter { it.direction == TransferDirection.In }
    }.filter { it.matches(query) }
}

/**
 * The search on Activity: a payment matches by the other person's name or @handle, its note, the coin, the
 * network, the amount as typed ("25", "25.5") or an address (start or end).
 */
internal fun Transfer.matches(query: String): Boolean {
    val q = query.trim().removePrefix("@")
    if (q.isEmpty()) return true
    val amountText = com.circaso.feature.wallet.domain.MinorUnits.toDisplay(amount.amountMinor, com.circaso.feature.wallet.domain.MinorUnits.decimalsOf(amount.asset))
    return listOfNotNull(
        counterpartName, counterpartHandle, note, amount.asset, com.circaso.feature.wallet.domain.chainName(blockchain), amountText, amount.amount,
        toAddress.takeIf { it.isNotEmpty() }, from.takeIf { it.isNotEmpty() }, txHash,
    ).any { it.contains(q, ignoreCase = true) }
}

class ActivityViewModel(
    observeTransfers: ObserveTransfersUseCase,
    observeWallet: ObserveWalletUseCase,
    /** One network's payments (blockchain alone), or one asset's statement on it (both); neither for everything. */
    private val blockchain: String? = null,
    private val symbol: String? = null,
) : ViewModel() {
    private val filter = MutableStateFlow(ActivityFilter.All)
    private val query = MutableStateFlow("")
    val state: StateFlow<ActivityUiState> = combine(observeTransfers(), filter, observeWallet(), query) { transfers, f, wallet, q ->
        val mine = transfers.filter { t ->
            (blockchain == null || t.blockchain == blockchain) && (symbol == null || t.amount.asset.equals(symbol, ignoreCase = true))
        }
        val held = wallet?.chains?.firstOrNull { it.blockchain == blockchain }?.assets?.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }
        ActivityUiState(mine, f, wallet?.fiat, blockchain, symbol, held, q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState(blockchain = blockchain, symbol = symbol))

    fun onFilter(f: ActivityFilter) { filter.value = f }
    fun onQuery(text: String) { query.value = text }
}

data class TransferUiState(val transfer: Transfer? = null, val fiat: FiatValue? = null, val online: Boolean = true, val error: String? = null)

/** Design C7: one payment, from the phone's copy, refreshed from W5 when it opens. */
class TransferViewModel(
    private val transferId: String,
    observeTransfer: ObserveTransferUseCase,
    private val loadTransfer: LoadTransferUseCase,
    observeWallet: ObserveWalletUseCase,
    connection: ConnectionState,
) : ViewModel() {
    private val local = MutableStateFlow(TransferUiState())
    val state: StateFlow<TransferUiState> = combine(local, observeTransfer(transferId), observeWallet(), connection.status) { ui, t, w, c ->
        ui.copy(transfer = t, fiat = w?.fiat, online = c == ConnectionStatus.Online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransferUiState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            // Money received has no transfer on the server (it is read from the history); only a sent one is fetched.
            if (state.value.transfer?.direction == TransferDirection.In) return@launch
            val r = loadTransfer(transferId)
            if (r is AppResult.Failure && state.value.transfer == null) local.update { it.copy(error = ErrorMessages.forError(r.error)) }
        }
    }
}

data class NetworksUiState(val chains: Chains? = null, val loading: Boolean = true, val adding: String? = null, val online: Boolean = true, val error: String? = null, val notice: String? = null)

/** W12: the chains the wallet is on and can join; adding one runs a challenge in the Circle SDK. */
class NetworksViewModel(private val getChains: GetChainsUseCase, private val addChain: AddChainUseCase, connection: ConnectionState) : ViewModel() {
    private val local = MutableStateFlow(NetworksUiState())
    val state: StateFlow<NetworksUiState> = combine(local, connection.status) { ui, c -> ui.copy(online = c == ConnectionStatus.Online) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworksUiState())

    init { load() }

    fun load() {
        local.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = getChains()) {
                is AppResult.Success -> local.update { it.copy(chains = r.value, loading = false) }
                is AppResult.Failure -> local.update { it.copy(loading = false, error = ErrorMessages.forError(r.error)) }
            }
        }
    }

    fun onAdd(blockchain: String) {
        if (local.value.adding != null || !state.value.online) return
        local.update { it.copy(adding = blockchain, error = null, notice = null) }
        viewModelScope.launch {
            when (val r = addChain(blockchain)) {
                is AppResult.Success -> {
                    local.update { it.copy(adding = null, notice = if (r.value == ChainStatus.Enabled) "Added. You can now receive on this network." else "Being added. It will show in a moment.") }
                    load()
                }
                is AppResult.Failure -> local.update { it.copy(adding = null, error = ErrorMessages.forError(r.error)) }
            }
        }
    }

    fun onNoticeShown() = local.update { it.copy(notice = null, error = null) }
}
