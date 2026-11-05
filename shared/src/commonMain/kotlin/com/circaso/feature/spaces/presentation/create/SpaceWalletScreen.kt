package com.circaso.feature.spaces.presentation.create

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.navigation.SpaceWalletRoute
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.sync.ConnectionBanner
import com.circaso.core.sync.ConnectionState
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.feature.chama.presentation.GroupWalletActions
import com.circaso.feature.chama.presentation.GroupWalletHooks
import com.circaso.feature.chama.presentation.GroupWalletReader
import com.circaso.feature.chama.presentation.GroupWalletUiState
import com.circaso.feature.spaces.domain.SpacesRepository
import com.circaso.feature.wallet.domain.GetSpaceWalletUseCase
import com.circaso.feature.wallet.domain.WalletStatus
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.setValue
import com.circaso.feature.wallet.presentation.BalancePrivacy
import com.circaso.feature.wallet.presentation.WalletContent
import com.circaso.feature.wallet.presentation.WalletSheets
import com.circaso.feature.wallet.presentation.WalletUiState
import com.circaso.feature.spaces.presentation.SpacesNav
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The space wallet pushed from Space Settings (SW1, SW1c–SW1f): the Wallet tab's own design with that wallet on screen,
 * under a bar that names the space. The owner signs (Send); admins see it, Send and Cash Out off with the reason.
 * Reads from the phone's copy (W1, W6 `?walletId=`); the wallet's id comes from the space (SP3) or W19.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpaceWalletViewModel(
    private val spaceId: String,
    private val spaces: SpacesRepository,
    private val reader: GroupWalletReader,
    private val getSpaceWallet: GetSpaceWalletUseCase,
    connection: ConnectionState,
    private val privacy: BalancePrivacy = BalancePrivacy(),
    /** Names payments to or from other spaces' wallets. */
    private val spaceNames: com.circaso.feature.wallet.presentation.SpaceWalletNames = com.circaso.feature.wallet.presentation.SpaceWalletNames.None,
) : ViewModel() {
    private val local = MutableStateFlow(GroupWalletUiState(spaceId = spaceId))
    private val walletIdFromW19 = MutableStateFlow<String?>(null)

    val hidden: StateFlow<Boolean> = privacy.hidden
    fun onToggleHidden() = privacy.toggle()

    private val space = spaces.space(spaceId)
    private val walletId = combine(space.map { it?.wallet?.walletId }, walletIdFromW19) { a, b -> a ?: b }.distinctUntilChanged()

    val state: StateFlow<GroupWalletUiState> = combine(
        local, space, walletId.flatMapLatest { id -> if (id == null) flowOf(null) else reader.observeLoneWallet(id) }, connection.status, spaceNames.names(),
    ) { ui, s, d, c, names ->
        ui.copy(
            wallet = d?.wallet, activity = d?.activity.orEmpty(), online = c == ConnectionStatus.Online,
            nameHint = s?.name ?: ui.nameHint, spaceOwnerName = s?.owner?.displayName, spaceCanSign = s?.wallet?.canSign ?: s?.me?.isOwner ?: ui.spaceCanSign,
            spaceNames = names.mapValues { it.value.name },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init { refresh() }

    fun refresh() {
        local.update { it.copy(loading = true) }
        viewModelScope.launch {
            spaces.refreshSpace(spaceId)
            var id = spaces.space(spaceId).first()?.wallet?.walletId
            if (id == null) {
                when (val w = getSpaceWallet(spaceId)) {
                    is AppResult.Success -> { id = w.value.wallet?.walletId; walletIdFromW19.value = id }
                    is AppResult.Failure -> { local.update { it.copy(loading = false, error = ErrorMessages.forError(w.error), reference = ErrorMessages.reference(w.error)) }; return@launch }
                }
            }
            val f = id?.let { reader.refreshLoneWallet(it) as? AppResult.Failure }
            local.update { it.copy(loading = false, error = f?.let { e -> ErrorMessages.forError(e.error) }, reference = f?.let { e -> ErrorMessages.reference(e.error) }) }
        }
    }

    fun onReceive(open: Boolean) = local.update { it.copy(receiving = open) }
    fun onMessageShown() = local.update { it.copy(message = null) }
}

@Composable
fun SpaceWalletEntry(route: SpaceWalletRoute, nav: SpacesNav) {
    val vm = koinViewModel<SpaceWalletViewModel>(key = "space-wallet-" + route.spaceId, parameters = { parametersOf(route.spaceId) })
    val state by vm.state.collectAsState()
    val hidden by vm.hidden.collectAsState()
    SpaceWalletContent(
        state, hidden, onBack = nav.back, walletActions = nav.walletActions(state.walletId),
        onSend = { state.walletId?.let(nav.payFrom) }, onFinishSetup = { nav.walletSetup(route.spaceId, false) },
        onRefresh = vm::refresh, onReceive = vm::onReceive, onToggleHidden = vm::onToggleHidden, onMessageShown = vm::onMessageShown,
    )
}

/** Stateless for previews: the same WalletContent the tab draws, under a bar. */
@Composable
fun SpaceWalletContent(
    state: GroupWalletUiState,
    hidden: Boolean,
    onBack: () -> Unit,
    walletActions: com.circaso.feature.wallet.presentation.WalletActions,
    onSend: () -> Unit,
    onFinishSetup: () -> Unit,
    onRefresh: () -> Unit,
    onReceive: (Boolean) -> Unit,
    onToggleHidden: () -> Unit,
    onMessageShown: () -> Unit = {},
) {
    // Add money and Cash Out are the honest coming sheets (E2/E6) here too.
    var coming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.circaso.feature.wallet.presentation.ComingFlow?>(null) }
    val ui = WalletUiState(group = state, online = state.online, hidden = hidden, receiveSheet = state.receiving, coming = coming)
    val hooks = GroupWalletHooks(
        GroupWalletActions(onAdd = {}, onPayout = onSend, onPayRequest = {}, onOpenTransfer = walletActions.onTransfer, onTarget = {}, onFinishSetup = onFinishSetup),
        onDecide = { _, _ -> }, onRetry = onRefresh, onMessageShown = onMessageShown,
    )
    CircasoScaffold(
        topBar = { CircasoNavBar(title = state.name, onBack = onBack) },
        banner = { ConnectionBanner(showing = state.wallet?.let { "Showing the wallet as of " + formatLocalTime(it.fetchedAt, LocalTimeStyle.Clock) } ?: "Showing what is saved on this phone") },
        overlay = { WalletSheets(ui, walletActions, onReceive, { coming = it }) },
    ) { padding ->
        WalletContent(
            ui, padding, walletActions, onCreate = {}, onRefresh = onRefresh, onReceive = onReceive, onComing = { coming = it },
            sheets = false, onToggleHidden = onToggleHidden, group = hooks,
        )
    }
}
