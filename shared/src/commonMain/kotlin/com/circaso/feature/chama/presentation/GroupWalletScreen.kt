package com.circaso.feature.chama.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.session.AuthManager
import com.circaso.core.sync.ConnectionBanner
import com.circaso.core.sync.ConnectionState
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.SheetHead
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.domain.DecideChamaRequestUseCase
import com.circaso.feature.chama.domain.GroupMoney
import com.circaso.feature.chama.domain.ObserveGroupMoneyUseCase
import com.circaso.feature.chama.domain.RefreshGroupMoneyUseCase
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ObserveChatUseCase
import com.circaso.feature.wallet.domain.ObserveWalletActivityUseCase
import com.circaso.feature.wallet.domain.ObserveWalletOfUseCase
import com.circaso.feature.wallet.domain.RefreshWalletActivityUseCase
import com.circaso.feature.wallet.domain.RefreshWalletOfUseCase
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletOwnerType
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.domain.chainName
import com.circaso.feature.wallet.domain.shortAddress
import com.circaso.feature.wallet.presentation.WalletActions
import com.circaso.feature.wallet.presentation.WalletContent
import com.circaso.feature.wallet.presentation.WalletSheets
import com.circaso.feature.wallet.presentation.WalletUiState
import kotlinx.coroutines.flow.Flow
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

data class GroupWalletUiState(
    val chat: Chat? = null,
    val money: GroupMoney? = null,
    val wallet: Wallet? = null,
    val activity: List<Transfer> = emptyList(),
    val online: Boolean = true,
    val loading: Boolean = true,
    /** The last refresh failed: its words and reference (shown when there is nothing on the phone). */
    val error: String? = null,
    val reference: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val receiving: Boolean = false,
    val myUserId: String? = null,
    /** The wallet's name before its chat is read (W16 named from the chat list, in the Wallet tab's switcher). */
    val nameHint: String? = null,
) {
    val name get() = chat?.title ?: nameHint ?: "Group wallet"
    val walletId get() = money?.wallet?.walletId ?: wallet?.walletId
    val locked get() = signerLeft(chat, money)
    val signerName get() = chat?.members?.firstOrNull { it.userId == money?.wallet?.signerUserId }?.displayName?.substringBefore(' ') ?: "the signer"
    fun memberName(id: String?) = chat?.members?.firstOrNull { it.userId == id }?.displayName ?: "Someone"
    /** Nothing on the phone and nothing from the server yet. */
    val empty get() = money == null && wallet == null
    val active get() = money?.walletActive ?: (wallet?.status == WalletStatus.Active)
    val settingUp get() = money?.settingUp == true || wallet?.status == WalletStatus.SettingUp
    /** GW16's caption: whose money it is and who can move it ("Group wallet · …", under a bar that names it). */
    val caption: String get() = captionAfter("Group wallet")

    /** The caption with the wallet's full name leading ("Kilimani Chama · …"). */
    val namedCaption: String get() = captionAfter(name)

    private fun captionAfter(lead: String): String {
        val after = ", after approval"
        return when {
            settingUp -> "$lead · being set up"
            locked -> "$lead · locked: $signerName signs for it and has left the group"
            money?.isSigner == true -> "$lead · only you can send$after"
            money != null -> "$lead · only $signerName can send$after"
            else -> lead
        }
    }

    /** Payments as the group wallet lists them (GW16): who, and whether it was a contribution or a payout. */
    val rows: List<Pair<Transfer, String>> get() = activity.map { tr ->
        val who = tr.counterpartName ?: tr.counterpartGroupChat?.let { "A group" }
            ?: shortAddress(if (tr.outgoing) tr.toAddress else tr.from).ifEmpty { "Someone" }
        val kind = if (tr.outgoing) "Payout" else "Contribution"
        tr.copy(counterpartName = who) to kind
    }
}

/** What the phone holds about one group's wallet: its chat, CH7, W1 and W6 `?walletId=`. */
data class GroupWalletData(val chat: Chat?, val money: GroupMoney?, val wallet: Wallet?, val activity: List<Transfer>)

/**
 * Reads one group's wallet for every screen that shows it: the Wallet tab (a group picked in its switcher) and the
 * pushed group wallet (group info, the goal strip). Reads come from the phone's copy; approving is online-only.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GroupWalletReader(
    private val observeChat: ObserveChatUseCase,
    private val observeMoney: ObserveGroupMoneyUseCase,
    private val refreshMoney: RefreshGroupMoneyUseCase,
    private val observeWallet: ObserveWalletOfUseCase,
    private val refreshWallet: RefreshWalletOfUseCase,
    private val observeActivity: ObserveWalletActivityUseCase,
    private val refreshActivity: RefreshWalletActivityUseCase,
    private val decideRequest: DecideChamaRequestUseCase,
    auth: AuthManager?,
) {
    val myUserId: String? = auth?.userId

    /** [walletId] from W16 when the caller knows it; otherwise the one CH7 names. */
    fun observe(chatId: String, walletId: String? = null): Flow<GroupWalletData> {
        val money = observeMoney(chatId)
        val id: Flow<String?> = if (walletId != null) flowOf(walletId) else money.map { it?.wallet?.walletId }.distinctUntilChanged()
        val side = id.flatMapLatest { w -> if (w == null) flowOf(null to emptyList()) else combine(observeWallet(w), observeActivity(w)) { a, b -> a to b } }
        return combine(observeChat(chatId), money, side) { chat, m, s -> GroupWalletData(chat, m, s.first, s.second) }
    }

    /** CH7, then the wallet's W1 and W6. The wallet is read even when CH7 fails, so its balance stays fresh. */
    suspend fun refresh(chatId: String, walletId: String? = null): AppResult<Unit> {
        val m = refreshMoney(chatId)
        val id = walletId ?: (m as? AppResult.Success)?.value?.wallet?.walletId
        if (id != null) { refreshWallet(id); refreshActivity(id) }
        return when (m) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> m
        }
    }

    /** CH5, online-only. */
    suspend fun decide(request: ChamaRequest, approve: Boolean) = decideRequest(request.requestId, approve)
}

/** The pushed group wallet (GroupWalletRoute): one group's wallet, in the Wallet tab's own design. */
class GroupWalletViewModel(
    private val chatId: String,
    private val reader: GroupWalletReader,
    connection: ConnectionState,
    private val privacy: com.circaso.feature.wallet.presentation.BalancePrivacy = com.circaso.feature.wallet.presentation.BalancePrivacy(),
) : ViewModel() {
    private val local = MutableStateFlow(GroupWalletUiState(myUserId = reader.myUserId))

    /** Hide balances, shared with the Wallet tab. */
    val hidden: StateFlow<Boolean> = privacy.hidden
    fun onToggleHidden() = privacy.toggle()

    val state: StateFlow<GroupWalletUiState> = combine(local, reader.observe(chatId), connection.status) { ui, d, c ->
        ui.copy(chat = d.chat, money = d.money, wallet = d.wallet, activity = d.activity, online = c == ConnectionStatus.Online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init { refresh() }

    fun refresh() {
        local.update { it.copy(loading = true) }
        viewModelScope.launch {
            val f = reader.refresh(chatId) as? AppResult.Failure
            local.update { it.copy(loading = false, error = f?.let { e -> ErrorMessages.forError(e.error) }, reference = f?.let { e -> ErrorMessages.reference(e.error) }) }
        }
    }

    /** CH5, online-only. */
    fun onDecide(request: ChamaRequest, approve: Boolean) {
        if (local.value.busy || !state.value.online) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = reader.decide(request, approve)
            local.update { it.copy(busy = false, message = (r as? AppResult.Failure)?.let(::decideFailed)) }
        }
    }

    fun onReceive(open: Boolean) = local.update { it.copy(receiving = open) }
    fun onMessageShown() = local.update { it.copy(message = null) }
}

/** A failed approval, said with its reference. */
internal fun decideFailed(f: AppResult.Failure) = ErrorMessages.forError(f.error) + (ErrorMessages.reference(f.error)?.let { " Ref $it" } ?: "")

/** What a group wallet asks of the app's navigation beyond the wallet's own links (See All, assets). */
class GroupWalletActions(
    val onAdd: () -> Unit,
    val onPayout: () -> Unit,
    val onPayRequest: (ChamaRequest) -> Unit,
    val onOpenTransfer: (String) -> Unit,
    val onTarget: () -> Unit,
)

/** The group-only parts of the one wallet design: its actions, approving payout requests, retrying, its messages. */
class GroupWalletHooks(
    val actions: GroupWalletActions,
    val onDecide: (ChamaRequest, Boolean) -> Unit,
    val onRetry: () -> Unit,
    val onMessageShown: () -> Unit = {},
)

/**
 * The group wallet pushed from group info or the goal strip: the same Wallet screen as the tab (Pasaka, 2026-11-04),
 * under a bar with Back and the group's name. No switcher here: the person came for this group's wallet.
 */
@Composable
fun GroupWalletScreen(viewModel: GroupWalletViewModel, onBack: () -> Unit, actions: GroupWalletActions, walletActions: WalletActions) {
    val state by viewModel.state.collectAsState()
    val hidden by viewModel.hidden.collectAsState()
    val ui =WalletUiState(group = state, online = state.online, hidden = hidden, receiveSheet = state.receiving)
    val hooks = GroupWalletHooks(actions, viewModel::onDecide, viewModel::refresh, viewModel::onMessageShown)
    CircasoScaffold(
        topBar = { CircasoNavBar(title = state.name, onBack = onBack) },
        banner = { ConnectionBanner(showing = state.wallet?.let { "Showing the wallet as of " + formatLocalTime(it.fetchedAt, LocalTimeStyle.Clock) } ?: "Showing what is saved on this phone") },
        overlay = { WalletSheets(ui, walletActions, viewModel::onReceive, {}) },
    ) { padding ->
        WalletContent(
            ui, padding, walletActions, onCreate = {}, onRefresh = viewModel::refresh, onReceive = viewModel::onReceive, onComing = {},
            sheets = false, onToggleHidden = viewModel::onToggleHidden, group = hooks,
        )
    }
}

/** Receiving into the group wallet from outside Circaso: its address on each network. Members pay with # in the chat. */
@Composable
fun ColumnScope.GroupReceiveContent(state: GroupWalletUiState, onClose: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val clipboard = LocalClipboardManager.current
    SheetHead("Receive into ${state.name}", onClose)
    BasicText(
        "Members pay in with # in the group chat. From outside Circaso, send USDC to the group wallet’s address on the same network.",
        style = t.subhead.copy(color = c.inkSecondary),
    )
    if (state.locked) BasicText("${state.signerName} signs for this wallet and has left the group, so nothing can leave it for now. Don’t send money in until it’s sorted.", style = t.footnote.copy(color = c.warning))
    val chains = state.wallet?.chainsReceiving.orEmpty()
    if (chains.isEmpty()) BasicText("The wallet’s addresses appear here once it’s on a network.", style = t.subhead.copy(color = c.inkSecondary))
    Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surfaceRaised)) {
        chains.forEachIndexed { i, chain ->
            if (i > 0) RowDivider()
            ListRow(chainName(chain.blockchain), sub = shortAddress(chain.address), trailing = {
                CircasoButton("Copy", { clipboard.setText(AnnotatedString(chain.address)) }, style = ButtonStyle.Secondary, size = ButtonSize.Small)
            })
        }
    }
    BasicText("Payments on chain can’t be reversed. Check the network before sending.", style = t.footnote.copy(color = c.inkSecondary))
}
