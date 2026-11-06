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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Design E7: every payment this phone knows, newest first, with the design's filter chips. Groups is the person's
 * money into and out of group wallets; E7's Added waits for adding money (E2), which has no backend yet.
 */
enum class ActivityFilter(val label: String) { All("All"), Sent("Sent"), Received("Received"), Groups("Groups") }

/**
 * E7's date filter (the nav bar's calendar button), counted in the person's own calendar days. Rolling ranges rather
 * than a date picker: the list holds a few months of payments at most, and "last 30 days" is what people look for.
 */
enum class ActivityRange(val label: String, val days: Int?) {
    Any("Any Time", null), Today("Today", 1), Week("Last 7 Days", 7), Month("Last 30 Days", 30), Quarter("Last 90 Days", 90),
}

data class ActivityUiState(
    val transfers: List<Transfer> = emptyList(),
    val filter: ActivityFilter = ActivityFilter.All,
    val fiat: FiatValue? = null,
    /** An asset's statement: the network and coin it is for, and what the wallet holds of it there. */
    val blockchain: String? = null,
    val symbol: String? = null,
    val held: com.circaso.feature.wallet.domain.Asset? = null,
    val query: String = "",
    /**
     * The list is known: the phone's copy has rows, or this page's own read of W6 has finished (or can't run,
     * offline). Until then the page shows the list's shape, never "nothing yet".
     */
    val loaded: Boolean = false,
    /** A group wallet's payments: its rows say Contribution and Payout, and there is no Groups filter. */
    val groupWallet: Boolean = false,
    val hidden: Boolean = false,
    val online: Boolean = true,
    /** This page's read of W6 failed: with nothing saved, the page says so with Try Again instead of "nothing yet". */
    val error: String? = null,
    val range: ActivityRange = ActivityRange.Any,
    /** An older page is being read (the footer's skeleton row), and whether there is one to read. */
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /** A pull to refresh is running. */
    val refreshing: Boolean = false,
    /** The failed read's reference for support (request id), shown under its message. */
    val errorRef: String? = null,
    /** The last older page failed: the footer says so with Try Again, and nothing reads again until it is tapped. */
    val moreFailed: Boolean = false,
    /** Today, as a local day number, for [range]. */
    val today: Long = 0,
) {
    /** The chips this list offers: Groups only for the person's own wallet, where it means money to and from groups. */
    val filters get() = ActivityFilter.entries.filter { it != ActivityFilter.Groups || !groupWallet }
    val shown get() = when (filter) {
        ActivityFilter.All -> transfers
        ActivityFilter.Sent -> transfers.filter { it.direction == TransferDirection.Out }
        ActivityFilter.Received -> transfers.filter { it.direction == TransferDirection.In }
        ActivityFilter.Groups -> transfers.filter { it.withGroup }
    }.filter { it.matches(query) && it.within(range, today) }
}

/** Money to or from a group wallet (archived Spaces are not groups). */
internal val Transfer.withGroup: Boolean
    get() = counterpartGroupChat != null || fromGroupId?.takeIf { !it.startsWith("space:") } != null || toGroupId?.takeIf { !it.startsWith("space:") } != null

internal fun Transfer.within(range: ActivityRange, today: Long): Boolean {
    val days = range.days ?: return true
    return today - com.circaso.core.platform.localDayIndex(createdAt) < days
}

/**
 * How a payment reads in a list (Pasaka, 2026-11-06, the tab's GW16 naming moved here so the tab's group wallet and
 * Transactions agree): a group wallet's rows are Contribution (in) and Payout (out), named by the person, "A group",
 * or the other side's short address; the person's own payments to and from a group are Contribution and Payout too
 * (E7: "Holiday Fund · Contribution"); anything else keeps Sent / Received.
 */
fun Transfer.listRow(groupWallet: Boolean): Pair<Transfer, String?> {
    if (groupWallet) {
        val who = counterpartName ?: counterpartGroupChat?.let { "A group" }
            ?: com.circaso.feature.wallet.domain.shortAddress(if (outgoing) toAddress else from).ifEmpty { "Someone" }
        return copy(counterpartName = who) to (if (outgoing) "Payout" else "Contribution")
    }
    if (!withGroup) return this to null
    return copy(counterpartName = counterpartName ?: "A group") to (if (outgoing) "Contribution" else "Payout")
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

/** Design E7 for one wallet (or one network, or one asset on it), from the phone's copy, read fresh as it opens. */
class ActivityViewModel(
    observeTransfers: ObserveTransfersUseCase,
    observeWallet: ObserveWalletUseCase,
    /** One network's payments (blockchain alone), or one asset's statement on it (both); neither for everything. */
    private val blockchain: String? = null,
    private val symbol: String? = null,
    /** A group wallet's payments (its W6 copy) instead of the person's own; null for the personal wallet. */
    private val walletId: String? = null,
    observeWalletOf: com.circaso.feature.wallet.domain.ObserveWalletOfUseCase? = null,
    observeActivity: com.circaso.feature.wallet.domain.ObserveWalletActivityUseCase? = null,
    /** Hide balances, as the Wallet tab has it. */
    privacy: BalancePrivacy = BalancePrivacy(),
    /** W6 a page at a time; null (previews, tests) reads nothing and the phone's copy is all there is. */
    private val loadPage: com.circaso.feature.wallet.domain.LoadActivityPageUseCase? = null,
    connection: ConnectionState? = null,
) : ViewModel() {
    private data class Paging(
        val read: Boolean = false, val error: String? = null, val errorRef: String? = null, val cursor: String? = null,
        val loadingMore: Boolean = false, val refreshing: Boolean = false, val moreFailed: Boolean = false,
    )
    /** The latest page's read in flight: one at a time, so a late one never writes an older cursor. */
    private var latest: kotlinx.coroutines.Job? = null
    private var isOnline = true
    private val filter = MutableStateFlow(ActivityFilter.All)
    private val query = MutableStateFlow("")
    private val range = MutableStateFlow(ActivityRange.Any)
    private val paging = MutableStateFlow(Paging(read = loadPage == null))
    private val payments = if (walletId != null && observeActivity != null) observeActivity(walletId) else observeTransfers()
    private val held = if (walletId != null && observeWalletOf != null) observeWalletOf(walletId) else observeWallet()
    private val group = walletId != null && observeActivity != null
    private val online = connection?.status?.let { s -> s.map { it == ConnectionStatus.Online } } ?: kotlinx.coroutines.flow.flowOf(true)
    private val choices = combine(filter, query, range) { f, q, r -> Triple(f, q, r) }

    val state: StateFlow<ActivityUiState> = combine(payments, choices, held, privacy.hidden, combine(paging, online) { p, o -> p to o }) { transfers, (f, q, r), wallet, hidden, (p, isOnline) ->
        val mine = transfers.filter { t ->
            (blockchain == null || t.blockchain == blockchain) && (symbol == null || t.amount.asset.equals(symbol, ignoreCase = true))
        }
        val heldAsset = wallet?.chains?.firstOrNull { it.blockchain == blockchain }?.assets?.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }
        ActivityUiState(
            mine, f, wallet?.fiat, blockchain, symbol, heldAsset, q,
            // Known: rows on the phone, this page's read finished, or no read can run (offline).
            loaded = transfers.isNotEmpty() || p.read || !isOnline,
            groupWallet = group, hidden = hidden, online = isOnline, error = p.error, range = r,
            loadingMore = p.loadingMore, hasMore = p.cursor != null, refreshing = p.refreshing,
            errorRef = p.errorRef, moreFailed = p.moreFailed,
            today = com.circaso.core.platform.localDayIndex(com.circaso.core.sync.nowMillis()),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState(blockchain = blockchain, symbol = symbol, groupWallet = group))

    init {
        refresh()
        // Back online: an older page that failed may be tried again (the scroll position asks for it).
        viewModelScope.launch {
            online.distinctUntilChanged().collect { o -> isOnline = o; if (o) paging.update { it.copy(moreFailed = false) } }
        }
    }

    fun onFilter(f: ActivityFilter) { filter.value = f }
    fun onQuery(text: String) { query.value = text }
    fun onRange(r: ActivityRange) { range.value = r }

    /** The latest page again (opening the page, a pull, Try Again). */
    fun refresh(fromPull: Boolean = false) {
        val load = loadPage ?: return
        // A read already running answers this one too: a pull only shows its spinner until it lands.
        if (latest?.isActive == true) { if (fromPull) paging.update { it.copy(refreshing = true) }; return }
        paging.update { it.copy(refreshing = fromPull) }
        latest = viewModelScope.launch {
            when (val r = load(walletId, null)) {
                is AppResult.Success -> paging.update { it.copy(read = true, refreshing = false, error = null, errorRef = null, cursor = r.value, moreFailed = false) }
                is AppResult.Failure -> paging.update {
                    it.copy(read = true, refreshing = false, error = ErrorMessages.forError(r.error), errorRef = ErrorMessages.reference(r.error))
                }
            }
        }
    }

    /**
     * The next older page, as the list nears its end. Offline it waits. A failure keeps the cursor and stops: the
     * footer offers Try Again ([retryMore]), so a list whose end stays in view never reads in a loop.
     */
    fun loadMore() {
        val load = loadPage ?: return
        val p = paging.value
        val cursor = p.cursor ?: return
        if (p.loadingMore || p.moreFailed || !isOnline) return
        paging.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val r = load(walletId, cursor)
            paging.update {
                if (r is AppResult.Success) it.copy(loadingMore = false, cursor = r.value) else it.copy(loadingMore = false, moreFailed = true)
            }
        }
    }

    /** The footer's Try Again after an older page failed. */
    fun retryMore() { paging.update { it.copy(moreFailed = false) }; loadMore() }
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
