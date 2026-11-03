package com.circaso.feature.chama.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.SheetHead
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.ActionButton
import com.circaso.designsystem.components.AmountDisplay
import com.circaso.designsystem.components.AmountSize
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.GoalProgress
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.domain.DecideChamaRequestUseCase
import com.circaso.feature.chama.domain.GroupMoney
import com.circaso.feature.chama.domain.ObserveGroupMoneyUseCase
import com.circaso.feature.chama.domain.RefreshGroupMoneyUseCase
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ObserveChatUseCase
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.ObserveWalletActivityUseCase
import com.circaso.feature.wallet.domain.ObserveWalletOfUseCase
import com.circaso.feature.wallet.domain.RefreshWalletActivityUseCase
import com.circaso.feature.wallet.domain.RefreshWalletOfUseCase
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.chainName
import com.circaso.feature.wallet.domain.shortAddress
import com.circaso.feature.wallet.presentation.TransferRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
) {
    val name get() = chat?.title ?: "Group wallet"
    val walletId get() = money?.wallet?.walletId
    val locked get() = signerLeft(chat, money)
    val signerName get() = chat?.members?.firstOrNull { it.userId == money?.wallet?.signerUserId }?.displayName?.substringBefore(' ') ?: "the signer"
    fun memberName(id: String?) = chat?.members?.firstOrNull { it.userId == id }?.displayName ?: "Someone"
    /** Nothing on the phone and nothing from the server yet. */
    val empty get() = money == null && wallet == null
}

/**
 * A group's wallet (GW16's structure, opened from group info, the goal strip or the Wallet tab): its balance and who
 * can send, Add (contribute), Payout (a request), Receive, the target, open requests and its payments. Reads come
 * from the phone's copy (CH7, W1 and W6 `?walletId=`); approving and paying are online-only.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GroupWalletViewModel(
    private val chatId: String,
    observeChat: ObserveChatUseCase,
    observeMoney: ObserveGroupMoneyUseCase,
    private val refreshMoney: RefreshGroupMoneyUseCase,
    private val observeWallet: ObserveWalletOfUseCase,
    private val refreshWallet: RefreshWalletOfUseCase,
    private val observeActivity: ObserveWalletActivityUseCase,
    private val refreshActivity: RefreshWalletActivityUseCase,
    private val decide: DecideChamaRequestUseCase,
    connection: ConnectionState,
    auth: AuthManager?,
) : ViewModel() {
    private val local = MutableStateFlow(GroupWalletUiState(myUserId = auth?.userId))

    private val money = observeMoney(chatId)
    private val walletSide = money.flatMapLatest { m ->
        val id = m?.wallet?.walletId
        if (id == null) flowOf(null to emptyList()) else combine(observeWallet(id), observeActivity(id)) { w, a -> w to a }
    }

    val state: StateFlow<GroupWalletUiState> = combine(local, observeChat(chatId), money, walletSide, connection.status) { ui, chat, m, side, c ->
        ui.copy(chat = chat, money = m, wallet = side.first, activity = side.second, online = c == ConnectionStatus.Online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            when (val r = refreshMoney(chatId)) {
                is AppResult.Success -> {
                    local.update { it.copy(error = null, reference = null) }
                    r.value.wallet?.walletId?.let { id -> refreshWallet(id); refreshActivity(id) }
                }
                is AppResult.Failure -> local.update { it.copy(error = ErrorMessages.forError(r.error), reference = ErrorMessages.reference(r.error)) }
            }
            local.update { it.copy(loading = false) }
        }
    }

    /** CH5, online-only. */
    fun onDecide(request: ChamaRequest, approve: Boolean) {
        if (local.value.busy || !state.value.online) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = decide(request.requestId, approve)
            local.update { it.copy(busy = false, message = (r as? AppResult.Failure)?.let { f -> ErrorMessages.forError(f.error) }) }
        }
    }

    fun onReceive(open: Boolean) = local.update { it.copy(receiving = open) }
    fun onMessageShown() = local.update { it.copy(message = null) }
}

/** What the group wallet asks of the app's navigation. */
class GroupWalletActions(
    val onAdd: () -> Unit,
    val onPayout: () -> Unit,
    val onPayRequest: (ChamaRequest) -> Unit,
    val onOpenTransfer: (String) -> Unit,
    val onTarget: () -> Unit,
)

@Composable
fun GroupWalletScreen(viewModel: GroupWalletViewModel, onBack: () -> Unit, actions: GroupWalletActions) {
    val state by viewModel.state.collectAsState()
    val toast = rememberToastState()
    LaunchedEffect(state.message) { state.message?.let { toast.show(it); viewModel.onMessageShown() } }
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = state.name, onBack = onBack) },
        banner = { ConnectionBanner(showing = state.wallet?.let { "Showing the wallet as of " + formatLocalTime(it.fetchedAt, LocalTimeStyle.Clock) } ?: "Showing what is saved on this phone") },
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
        overlay = {
            CircasoSheet(visible = state.receiving, onDismiss = { viewModel.onReceive(false) }, label = "Receive into ${state.name}") {
                GroupReceiveContent(state, onClose = { viewModel.onReceive(false) })
            }
        },
    ) { padding ->
        GroupWalletContent(state, PaddingValues(bottom = padding.calculateBottomPadding()), actions, viewModel::onDecide, { viewModel.onReceive(true) }, viewModel::refresh)
    }
}

/** The group wallet's body: also the Wallet tab's view when a group wallet is chosen in its switcher (GW16). */
@Composable
fun GroupWalletContent(state: GroupWalletUiState, padding: PaddingValues, actions: GroupWalletActions, onDecide: (ChamaRequest, Boolean) -> Unit, onReceive: () -> Unit, onRetry: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val money = state.money
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s3, bottom = padding.calculateBottomPadding() + CircasoSpacing.s6),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5),
    ) {
        when {
            state.empty && state.loading -> item {
                Column(Modifier.fillMaxWidth().padding(vertical = 80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                    Spinner(c.inkSecondary)
                    BasicText("Loading ${state.name}’s wallet…", style = t.subhead.copy(color = c.inkSecondary))
                }
            }
            state.empty -> item {
                Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                    BasicText(if (state.online) "Couldn’t load the wallet" else "You’re offline", Modifier.semantics { heading() }, style = t.title3.copy(color = c.ink))
                    BasicText(
                        if (state.online) (state.error ?: ErrorMessages.SomethingWrong) else "The wallet shows here once it has been read with a connection.",
                        style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                    )
                    state.reference?.let { BasicText("Ref $it", style = t.caption1.copy(color = c.inkTertiary)) }
                    if (state.online) CircasoButton("Try Again", onRetry, style = ButtonStyle.Secondary)
                }
            }
            money?.wallet == null && money != null -> item {
                // A group without a wallet shows no money anywhere (GW6): only reachable through an old link.
                BasicText("${state.name} doesn’t have a wallet.", style = t.body.copy(color = c.inkSecondary))
            }
            else -> {
                item { Header(state, actions, onReceive) }
                money?.goal?.let { g ->
                    item {
                        Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface).padding(CircasoSpacing.s4)) {
                            GoalProgress(
                                amountText(money.contributedMinor, state.wallet?.fiat), amountNumber(g.targetMinor, state.wallet?.fiat), money.progress,
                                meta = g.name, label = "${g.name} progress",
                            )
                        }
                    }
                }
                if (money != null && money.openRequests.isNotEmpty()) {
                    item { BasicText("Payout requests", Modifier.semantics { heading() }, style = t.title3.copy(color = c.ink)) }
                    items(money.openRequests, key = { "req-" + it.requestId }) { r ->
                        RequestCard(
                            r, state.wallet?.fiat, nameOf = state::memberName, isAdmin = money.isAdmin, isTreasurer = money.isSigner && !state.locked, myUserId = state.myUserId,
                            busy = state.busy, online = state.online, onApprove = { onDecide(r, true) }, onDecline = { onDecide(r, false) }, onPay = { actions.onPayRequest(r) },
                            inGroupWallet = true,
                        )
                    }
                }
                item { BasicText("Recent", Modifier.semantics { heading() }, style = t.title3.copy(color = c.ink)) }
                if (state.activity.isEmpty()) {
                    item { BasicText("No payments yet. Members add money with # in the group chat.", style = t.subhead.copy(color = c.inkSecondary)) }
                } else item {
                    Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface)) {
                        state.activity.take(20).forEachIndexed { i, tr ->
                            if (i > 0) RowDivider(Modifier.padding(start = 72.dp))
                            val who = tr.counterpartName ?: tr.counterpartGroupId?.let { "A group" } ?: shortAddress(if (tr.outgoing) tr.toAddress else tr.from).ifEmpty { "Someone" }
                            val kind = if (tr.outgoing) "Payout" else "Contribution"
                            TransferRow(tr.copy(counterpartName = who), state.wallet?.fiat, hidden = false, meta = kind, onClick = { actions.onOpenTransfer(tr.transferId) })
                        }
                    }
                }
            }
        }
    }
}

/** GW16's top: whose money it is, the balance, and Add / Payout / Receive (a group's Send is a request). */
@Composable
private fun Header(state: GroupWalletUiState, actions: GroupWalletActions, onReceive: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val money = state.money
    val usdc = state.wallet?.usdc
    val fiat = state.wallet?.fiat
    val active = money?.walletActive == true
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
        val caption = when {
            money?.settingUp == true -> "Group wallet · being set up"
            state.locked -> "Group wallet · locked: ${state.signerName} signs for it and has left the group"
            money?.isSigner == true -> "Group wallet · only you can send, after approval"
            else -> "Group wallet · only ${state.signerName} can send, after approval"
        }
        val held = usdc?.let { amountText(it.amountMinor, fiat) } ?: "—"
        AmountDisplay(
            currency = held.substringBefore(' ', ""), number = held.substringAfter(' '),
            sub = listOfNotNull(caption, usdc?.takeIf { fiat != null }?.let { MinorUnits.toDisplay(it.amountMinor, 6) + " USDC" }).joinToString("\n"),
            size = AmountSize.Hero, centred = false,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s6), horizontalArrangement = Arrangement.SpaceAround) {
            ActionButton(CircasoIcon.Plus, "Add", actions.onAdd, enabled = active && state.online)
            ActionButton(CircasoIcon.Send, "Payout", actions.onPayout, enabled = active && state.online && !state.locked)
            ActionButton(CircasoIcon.ArrowDown, "Receive", onReceive, enabled = active)
        }
        if (!state.online) BasicText("Money moves only while you’re connected.", style = t.footnote.copy(color = c.inkSecondary))
    }
}

/** Receiving into the group wallet from outside Circaso: its address on each network. Members pay with # in the chat. */
@Composable
private fun ColumnScope.GroupReceiveContent(state: GroupWalletUiState, onClose: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val clipboard = LocalClipboardManager.current
    SheetHead("Receive into ${state.name}", onClose)
    BasicText("Members pay in with # in the group chat. From outside Circaso, send USDC to the group wallet’s address on the same network.", style = t.subhead.copy(color = c.inkSecondary))
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
