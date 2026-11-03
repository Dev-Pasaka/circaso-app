package com.circaso.feature.wallet.presentation

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.layout.layout
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.chatListTime
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.platform.shareText
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.ActionButton
import com.circaso.designsystem.components.AmountDisplay
import com.circaso.designsystem.components.AmountSize
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CoinDisc
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowAmount
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SegmentedControl
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.domain.chainName
import com.circaso.feature.wallet.domain.formatFiat
import com.circaso.feature.wallet.domain.shortAddress
import com.circaso.feature.wallet.domain.usdcToFiat

/** What the wallet tab can open. */
class WalletActions(
    val onPay: () -> Unit,
    val onSendToWallet: () -> Unit,
    val onTransfer: (transferId: String) -> Unit,
    val onActivity: () -> Unit,
    val onNetworks: () -> Unit,
    /** One asset's statement on one network. */
    val onAsset: (blockchain: String, symbol: String) -> Unit = { _, _ -> },
    /** See All under recent payments: the payments on the network whose card is in front. */
    val onNetworkActivity: (blockchain: String) -> Unit = {},
    /** Every asset on one network, searchable (the card shows two). */
    val onAssets: (blockchain: String) -> Unit = {},
)

/** The title bar's actions on the Wallet tab (design E1): hide balances, add money. */
@Composable
fun RowScope.WalletTitleActions(viewModel: WalletViewModel) {
    val state by viewModel.state.collectAsState()
    CircasoIconButton(if (state.hidden) CircasoIcon.EyeOff else CircasoIcon.Eye, if (state.hidden) "Show balances" else "Hide balances", viewModel::onToggleHidden)
    CircasoIconButton(CircasoIcon.Plus, "Add money", { viewModel.onComing(ComingFlow.AddMoney) })
}

@Composable
fun WalletTab(
    viewModel: WalletViewModel,
    bottomPadding: PaddingValues,
    actions: WalletActions,
    /** GW16: a group wallet chosen in the switcher, drawn by the app (it has its own view model per group). */
    groupWallet: (@Composable (chatId: String, bottomPadding: PaddingValues) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    // Fresh whenever the tab is in front: on coming back to the app, and quietly every 30 seconds while it stays open,
    // so money that arrives shows even when the server's "payment received" signal didn't reach the phone.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) { viewModel.refresh(); kotlinx.coroutines.delay(30_000) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (state.showSwitcher) WalletSwitcherTitle(state.selected, onClick = { viewModel.onSwitching(true) })
        val group = state.selectedGroupChat
        Box(Modifier.weight(1f)) {
            if (group != null && groupWallet != null) groupWallet(group, bottomPadding)
            else WalletContent(
                state, bottomPadding, actions,
                onCreate = viewModel::onCreate, onRefresh = { viewModel.refresh(fromPull = true) }, onReceive = viewModel::onReceive, onComing = viewModel::onComing, onErrorShown = viewModel::onErrorShown,
                sheets = false, onAddNetworks = viewModel::onAddMissingNetworks, onToggleHidden = viewModel::onToggleHidden,
            )
        }
    }
}

/**
 * GW16: the tab's title is the switcher ("Wallet ▾") for someone with other wallets; it truncates a long group name.
 * Tabs have no title bar otherwise (Pasaka, 2026-10-30), so it shows only when there is something to switch to.
 */
@Composable
private fun WalletSwitcherTitle(selected: NamedWallet, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2, bottom = CircasoSpacing.s1)
            .pressable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Wallet: ${if (selected.personal) "Personal" else selected.name}, change" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(if (selected.personal) "Wallet" else selected.name, Modifier.weight(1f, fill = false).semantics { heading() }, style = t.largeTitle.copy(color = c.ink), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        com.circaso.designsystem.components.CircasoIconImage(CircasoIcon.ChevronDown, c.ink, Modifier.padding(top = 4.dp).size(22.dp))
    }
}

/** The Wallet tab's sheets (E4, E2/E6), for the home scaffold's overlay so they sit above the tab bar. */
@Composable
fun androidx.compose.foundation.layout.BoxScope.WalletOverlay(
    viewModel: WalletViewModel,
    actions: WalletActions,
    biometrics: com.circaso.feature.account.presentation.BiometricApprovalViewModel? = null,
) {
    val state by viewModel.state.collectAsState()
    WalletSheets(state, actions, viewModel::onReceive, viewModel::onComing)
    // GW16: every wallet the person can see; a group's is read-only for its members, sent from by its signer.
    CircasoSheet(state.switching, { viewModel.onSwitching(false) }, label = "Wallets") {
        WalletPickContent(state.wallets, state.selectedWalletId, onPick = viewModel::onSelectWallet, onClose = { viewModel.onSwitching(false) })
    }
    if (biometrics != null) {
        val bio by biometrics.state.collectAsState()
        // Asked once, right after the wallet is made; later it lives in You → Security → Payments. The sheet stays up
        // through Circle's PIN screen so a failure is said here, and goes once it's on.
        androidx.compose.runtime.LaunchedEffect(bio.enabled) { if (bio.enabled) viewModel.onBiometricsOffered() }
        CircasoSheet(
            state.offerBiometrics && !bio.enabled && bio.supported,
            { if (!bio.busy) viewModel.onBiometricsOffered() },
            label = "Approve payments with ${bio.inSentence}?",
        ) {
            BiometricOfferContent(bio, onTurnOn = biometrics::onEnable, onNotNow = viewModel::onBiometricsOffered)
        }
    }
}

/** After wallet setup: approve payments with Face ID / Touch ID / fingerprint instead of typing the PIN. Stateless for previews. */
@Composable
fun BiometricOfferContent(bio: com.circaso.feature.account.presentation.BiometricApprovalState, onTurnOn: () -> Unit, onNotNow: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val what = bio.inSentence
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText("Approve payments with $what?", Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink))
        BasicText(
            "Confirm payments without typing your PIN. You'll enter your PIN once to set it up, and it's always there if $what doesn't work. Circaso never sees your PIN.",
            style = t.body.copy(color = c.inkSecondary),
        )
        if (!bio.online) BasicText("Turning this on needs a connection. You can do it later in You → Security.", style = t.footnote.copy(color = c.inkSecondary))
    }
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        CircasoButton("Turn On ${bio.label}", onTurnOn, size = ButtonSize.Large, block = true, enabled = bio.online, busy = bio.busy)
        CircasoButton("Not Now", onNotNow, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true, enabled = !bio.busy)
        bio.error?.let { BasicText(it, style = t.footnote.copy(color = c.critical)) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.WalletSheets(state: WalletUiState, actions: WalletActions, onReceive: (Boolean) -> Unit, onComing: (ComingFlow?) -> Unit) {
    CircasoSheet(state.receiveSheet, { onReceive(false) }, label = "Receive") { ReceiveSheetContent(state.wallet, state.handle, onClose = { onReceive(false) }) }
    CircasoSheet(state.coming != null, { onComing(null) }, label = "Coming soon") { state.coming?.let { ComingSheetContent(it, onSendToWallet = { onComing(null); actions.onSendToWallet() }) } }
}

/** Design E1, plus the states before a wallet exists. Stateless so it can be previewed. */
@Composable
fun WalletContent(
    state: WalletUiState,
    bottomPadding: PaddingValues,
    actions: WalletActions,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
    onReceive: (Boolean) -> Unit,
    onComing: (ComingFlow?) -> Unit,
    onErrorShown: () -> Unit = {},
    /** False inside the home tabs, which draw the sheets in their overlay above the tab bar. */
    sheets: Boolean = true,
    onAddNetworks: () -> Unit = {},
    onToggleHidden: () -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    // The create screen shows its error inline; an active wallet shows a passing toast instead.
    LaunchedEffect(state.error) { state.error?.let { if (state.active) { toast.show(it); onErrorShown() } } }
    val wallet = state.wallet
    val bottom = bottomPadding.calculateBottomPadding() + CircasoSpacing.s4
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        when {
            // The wallet's own shape while it opens (a card, the actions, two rows), so nothing jumps when it arrives.
            wallet == null && state.loading -> Column(
                Modifier.fillMaxSize().padding(horizontal = CircasoSpacing.s6, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Opening your wallet" },
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(1.586f).clip(com.circaso.designsystem.theme.CircasoShapes.lg).background(c.fill))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    repeat(4) { Box(Modifier.size(CircasoSizes.controlMd + 12.dp).clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(c.fill)) }
                }
                repeat(2) { Box(Modifier.fillMaxWidth().height(56.dp).clip(com.circaso.designsystem.theme.CircasoShapes.md).background(c.fill)) }
            }
            wallet == null -> Column(Modifier.fillMaxSize().padding(horizontal = CircasoSpacing.s6), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(state.error ?: "Couldn't open your wallet.", style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                CircasoButton("Try Again", onRefresh, style = ButtonStyle.Secondary, enabled = state.online)
            }
            wallet.status != WalletStatus.Active -> CreateWallet(state, bottom, onCreate)
            else -> ActiveWallet(state, wallet, bottom, actions, onReceive, onComing, onRefresh, onAddNetworks, onToggleHidden)
        }
        ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        if (sheets) WalletSheets(state, actions, onReceive, onComing)
    }
}

/** Before the wallet exists (E1's empty state) and while Circle makes it. */
@Composable
private fun CreateWallet(state: WalletUiState, bottom: androidx.compose.ui.unit.Dp, onCreate: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    // SETTING_UP means the PIN was never finished: W2 again gives a fresh challenge, so the button stays live.
    val halfway = state.status == WalletStatus.SettingUp
    Column(Modifier.fillMaxSize().padding(horizontal = CircasoSpacing.s6).padding(bottom = bottom), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        AmountDisplay("USDC", "0", sub = "Your money, held by you", size = AmountSize.Medium)
        Spacer(Modifier.height(CircasoSpacing.s2))
        BasicText(when { state.creating -> "Setting up your wallet"; halfway -> "Finish setting up your wallet"; else -> "Set up your wallet" }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(
            when {
                state.creating -> "Choose a 6-digit PIN and your security questions. Only you can move your money; Circaso never can."
                halfway -> "Your wallet is waiting for its PIN. Pick up where you left off."
                state.setupRequestedBy != null -> "${state.setupRequestedBy} wants to send you money. Set up your wallet and they can."
                else -> "Send and receive dollars (USDC) with the people you chat with. You set a PIN that only you know; Circaso never holds your money."
            },
            style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
        )
        state.error?.let { BasicText(it, style = t.footnote.copy(color = c.critical, textAlign = TextAlign.Center)) }
        Spacer(Modifier.height(CircasoSpacing.s2))
        CircasoButton(when { state.creating -> "Setting up…"; halfway -> "Continue Setup"; else -> "Create Wallet" }, onCreate, size = ButtonSize.Large, block = true, enabled = state.online && !state.creating, busy = state.creating)
        if (!state.online) BasicText("You need a connection to set up a wallet.", style = t.footnote.copy(color = c.inkSecondary))
    }
}

@Composable
private fun ActiveWallet(state: WalletUiState, wallet: Wallet, bottom: androidx.compose.ui.unit.Dp, actions: WalletActions, onReceive: (Boolean) -> Unit, onComing: (ComingFlow?) -> Unit, onRefresh: () -> Unit, onAddNetworks: () -> Unit = {}, onToggleHidden: () -> Unit = {}) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val hidden = state.hidden
    val fiat = wallet.fiat
    val now = com.circaso.core.sync.nowMillis()
    val usdcTotal = wallet.usdc
    // Pull down to check for new money now. The spinner shows only for a pull, not for the quiet background refreshes.
    var pulled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.loading) { if (!state.loading) pulled = false }
    val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = pulled,
        onRefresh = { pulled = true; onRefresh() },
        state = pull,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                state = pull, isRefreshing = pulled, modifier = Modifier.align(Alignment.TopCenter),
                containerColor = c.surfaceRaised, color = c.ink,
            )
        },
    ) {
    // The card in front picks the payments listed under it (the user, 2026-10-31): swipe to Ethereum, see Ethereum's.
    // Starts on the card shown first (the richest), so the list doesn't show every network for a frame and then shrink.
    var network by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(byValue(wallet.chains, fiat).firstOrNull()?.blockchain) }
    val recent = state.recent.filter { network == null || it.blockchain == network }.take(5)
    // Behind the cards, the front card's colour from under the status bar, fading out by the end of the cards.
    var glow by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.graphics.Color.Transparent) }
    var cardsBottom by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }
    var pageTop by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }
    val animatedGlow by androidx.compose.animation.animateColorAsState(glow, tween(CircasoMotion.BaseMs), label = "glow")
    val glowStrength = if (c.isDark) 0.7f else 0.4f
    androidx.compose.foundation.layout.Box(Modifier.matchParentSize().onGloballyPositioned { pageTop = it.positionInWindow().y })
    // Drawn by the outermost scaffold, edge to edge from the top of the screen, so it isn't boxed in by the frame.
    val glowHeight = with(androidx.compose.ui.platform.LocalDensity.current) { cardsBottom.toDp() }
    com.circaso.designsystem.chrome.HostedBackdrop {
        if (!c.isHighContrast && cardsBottom > 0f) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().height(glowHeight)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to animatedGlow.copy(alpha = glowStrength), 0.45f to animatedGlow.copy(alpha = glowStrength * 0.55f),
                            1f to animatedGlow.copy(alpha = 0f),
                        ),
                    ),
            )
        }
    }
    // A phone on its side (the user, 2026-10-31): the cards, actions and assets on the left, the payments on the
    // right, each scrolling on its own; upright, one list.
    val short = com.circaso.designsystem.adaptive.LocalWindowLayout.current.short
    val cardsBlock: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // One card per network, swiped sideways (cards reach the screen edges to peek); the actions, then the
                // focused card's assets, follow under them.
                androidx.compose.foundation.layout.Box(
                    Modifier.onGloballyPositioned { cardsBottom = it.boundsInWindow().bottom.takeIf { b -> b > pageTop + 1f } ?: 0f }.layout { measurable, constraints ->
                        // Upright the cards reach the screen edges to peek; on its side they stay in their column (the
                        // rail is beside them).
                        val bleed = if (short) 0 else (CircasoSpacing.s4 * 2).roundToPx()
                        val wide = constraints.maxWidth + bleed
                        val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
                        layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed / 2, 0) }
                    },
                ) {
                    ChainCards(byValue(wallet.chains, fiat), fiat, hidden, onToggleHidden, onFocus = { network = it }, onAsset = actions.onAsset, onAssets = actions.onAssets, onGlow = { glow = it }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s2), horizontalArrangement = Arrangement.SpaceBetween) {
                            ActionButton(CircasoIcon.Plus, "Add", { onComing(ComingFlow.AddMoney) })
                            ActionButton(CircasoIcon.ArrowUp, "Send", actions.onPay, enabled = state.canSend)
                            ActionButton(CircasoIcon.ArrowDown, "Receive", { onReceive(true) })
                            ActionButton(CircasoIcon.Bank, "Cash Out", { onComing(ComingFlow.CashOut) })
                        }
                    }
                }
                // Everyone holds every supported network (decision 2026-10-30): the ones missing are one tap and the PIN away.
                if (state.missingNetworks.isNotEmpty()) {
                    val names = state.missingNetworks.map { chainName(it).substringBefore(" (") }
                    val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
                    Row(
                        Modifier.fillMaxWidth().clip(com.circaso.designsystem.theme.CircasoShapes.md).background(c.surface).padding(CircasoSpacing.s4),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            BasicText("Add $list", style = t.callout.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
                            BasicText("Receive money on every network. You'll confirm with your PIN.", style = t.footnote.copy(color = c.inkSecondary))
                        }
                        CircasoButton("Add Networks", onAddNetworks, size = ButtonSize.Small, enabled = state.online && !state.addingNetworks, busy = state.addingNetworks)
                    }
                }
                // The rate credit lives on the Currency screen now (the user, 2026-10-31).
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Manage networks", style = t.footnote.copy(color = c.action, fontWeight = FontWeight.SemiBold), modifier = Modifier.clickableText(actions.onNetworks))
                }
            }
    }
    val statusItems: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        // No overall total (Pasaka, 2026-10-30): each network's card carries its own balance, the richest first.
        if (!state.online || wallet.holdUntil != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    if (!state.online) BasicText("Offline · balances as of ${formatLocalTime(wallet.fetchedAt, LocalTimeStyle.Clock)}", style = t.caption1.copy(color = c.inkSecondary))
                    wallet.holdUntil?.let { BasicText("Payments are paused for a short while after a restore.", style = t.footnote.copy(color = c.warning)) }
                }
            }
        }
    }
    val recentItems: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                BasicText("Recent", style = t.title3.copy(color = c.ink))
                if (recent.isNotEmpty()) BasicText("See All", style = t.callout.copy(color = c.action, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(bottom = 2.dp).clickableText { network?.let(actions.onNetworkActivity) ?: actions.onActivity() })
            }
        }
        if (recent.isEmpty()) {
            item(key = "recent-empty") {
                BasicText(
                    if (network != null) "Nothing on ${chainName(network!!).substringBefore(" (")} yet." else "Payments you send and receive will show here.",
                    style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(horizontal = 4.dp).animateItem(fadeInSpec = androidx.compose.animation.core.tween(CircasoMotion.BaseMs), fadeOutSpec = androidx.compose.animation.core.tween(CircasoMotion.QuickMs), placementSpec = if (CircasoTheme.reduceMotion) null else androidx.compose.animation.core.tween(CircasoMotion.BaseMs)),
                )
            }
        } else {
            items(recent, key = { it.transferId }) { transfer ->
                // Rows fade and slide as the card in front changes, instead of the list jumping.
                Box(Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(CircasoMotion.BaseMs), fadeOutSpec = androidx.compose.animation.core.tween(CircasoMotion.QuickMs), placementSpec = if (CircasoTheme.reduceMotion) null else androidx.compose.animation.core.tween(CircasoMotion.BaseMs))) {
                    TransferRow(transfer, fiat, hidden, meta = chatListTime(transfer.createdAt, now), onClick = { actions.onTransfer(transfer.transferId) })
                }
            }
        }
    }
    if (short) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(start = CircasoSpacing.s4, end = CircasoSpacing.s2, top = 10.dp, bottom = bottom),
            ) { cardsBlock() }
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(start = CircasoSpacing.s2, end = CircasoSpacing.s4, top = 10.dp, bottom = bottom),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                statusItems()
                recentItems()
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = 10.dp, bottom = bottom), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            statusItems()
            item { cardsBlock() }
            recentItems()
        }
    }
    }
}

private fun Modifier.clickableText(onClick: () -> Unit) = this.pressable(role = Role.Button, onClick = onClick)

/** "KES 32,180 / 249.46 USDC", or "USDC 249.46" with no sub line when there is no rate; dots when hidden. */
internal fun balanceLine(wallet: Wallet, hidden: Boolean): Triple<String, String, String?> {
    val usdc = wallet.usdc
    val usdcText = usdc?.let { MinorUnits.toDisplay(it.amountMinor, it.decimals) } ?: "0.00"
    val fiat = wallet.fiat
    return when {
        hidden -> Triple(fiat?.currency ?: "USDC", "••••", null)
        fiat != null -> Triple(fiat.currency, MinorUnits.group(fiat.amount.substringBefore('.').ifEmpty { "0" }), "$usdcText USDC")
        else -> Triple("USDC", usdcText, null)
    }
}

/** A payment as the wallet lists it (design `cx-row` with `cx-row-amount`). */
@Composable
fun TransferRow(transfer: Transfer, fiat: FiatValue?, hidden: Boolean, meta: String?, onClick: () -> Unit) {
    val incoming = transfer.direction == TransferDirection.In
    val name = transfer.counterpartName ?: if (transfer.toUserId == null && transfer.toAddress.isNotEmpty()) shortAddress(transfer.toAddress) else if (incoming) "Received" else "Sent"
    val sub = buildString {
        append(if (incoming) "Received" else "Sent")
        // Between two Circaso people the @handle tells same-named contacts apart.
        transfer.counterpartHandle?.takeIf { transfer.counterpartName != null }?.let { append(" · @").append(it) }
        transfer.note?.let { append(" · ").append(it) }
        when (transfer.state) {
            TransferState.AwaitingSignature -> append(" · not signed")
            TransferState.Submitted -> append(" · on its way")
            TransferState.Failed -> append(" · failed")
            TransferState.Expired -> append(" · expired")
            TransferState.Confirmed -> Unit
        }
    }
    ListRow(
        title = name, sub = sub,
        leading = { if (transfer.counterpartName != null) Avatar(transfer.counterpartName, size = CircasoSizes.avatarMd, imageUrl = transfer.counterpartAvatar) else CoinDisc(if (incoming) "IN" else "OUT") },
        trailing = { RowAmount(if (hidden) "••••" else amountLine(transfer, fiat), meta, incoming = incoming && transfer.state == TransferState.Confirmed) },
        onClick = onClick,
    )
}

/** "+KES 2,000" when a rate is known, else "+12.50 USDC". */
internal fun amountLine(transfer: Transfer, fiat: FiatValue?): String {
    val sign = if (transfer.direction == TransferDirection.In) "+" else "−"
    val fiatText = fiat?.takeIf { MinorUnits.isDollar(transfer.amount.asset) }?.let { usdcToFiat(transfer.amount.amountMinor, it.rate) }?.let { formatFiat(fiat.currency, it) }
    val coin = transfer.amount.amount.takeIf { it.isNotEmpty() }?.let { "$it ${transfer.amount.asset}" } ?: MinorUnits.coinText(transfer.amount.amountMinor, transfer.amount.asset)
    return sign + (fiatText ?: coin)
}

/** Design E4: @handle for people on Circaso, the address per chain for anyone else. */
@Composable
private fun ReceiveSheetContent(wallet: Wallet?, handle: String?, onClose: () -> Unit = {}) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val clipboard = LocalClipboardManager.current
    val chains = wallet?.chainsReceiving.orEmpty()
    var tab by remember { mutableStateOf(0) }
    var chainIndex by remember { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
        com.circaso.designsystem.chrome.SheetHead("Receive", onClose)
        if (chains.isNotEmpty()) SegmentedControl(listOf("On Circaso", "From a wallet"), tab, { tab = it })
        if (tab == 0 || chains.isEmpty()) {
            val h = handle?.let { "@$it" }
            // Design E4: the code, then the handle and who can use it, then Share and Copy side by side.
            if (handle != null) com.circaso.designsystem.components.QrCode(com.circaso.core.qr.QrPayload.forHandle(handle), "QR code to pay $h", Modifier.align(Alignment.CenterHorizontally))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                BasicText(h ?: "Set your @handle first", style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
                BasicText(if (h != null) "Anyone on Circaso can scan this or pay $h" else "People find you by your @handle. Set one from the You tab.", style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
            }
            if (h != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                CircasoButton("Share", { shareText("Pay me on Circaso: $h", "My Circaso handle") }, modifier = Modifier.weight(1f), size = ButtonSize.Large, block = true)
                CircasoButton("Copy Handle", { clipboard.setText(AnnotatedString(h)) }, modifier = Modifier.weight(1f), style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
            }
        } else {
            val chain = chains[chainIndex.coerceIn(0, chains.lastIndex)]
            if (chains.size > 1) SegmentedControl(chains.map { chainName(it.blockchain).substringBefore(" (") }, chainIndex, { chainIndex = it })
            com.circaso.designsystem.components.QrCode(com.circaso.core.qr.QrPayload.forAddress(chain.address), "QR code of your USDC address on ${chainName(chain.blockchain)}", Modifier.align(Alignment.CenterHorizontally))
            BasicText("USDC on ${chainName(chain.blockchain)}", style = t.headline.copy(color = c.ink))
            BasicText(chain.address, style = t.callout.copy(color = c.ink), modifier = Modifier.fillMaxWidth())
            BasicText("Only send USDC on ${chainName(chain.blockchain)} to this address. Other coins or networks can't be recovered.", style = t.footnote.copy(color = c.warning))
            CircasoButton("Copy Address", { clipboard.setText(AnnotatedString(chain.address)) }, size = ButtonSize.Large, block = true)
            CircasoButton("Share", { shareText(chain.address, "My USDC address on ${chainName(chain.blockchain)}") }, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
        }
    }
}

/** Designs E2 and E6, honestly: the flows exist on paper, the rails don't yet. */
@Composable
private fun ComingSheetContent(flow: ComingFlow, onSendToWallet: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        when (flow) {
            ComingFlow.AddMoney -> {
                BasicText("Adding money is coming", style = t.title2.copy(color = c.ink))
                BasicText("Topping up from M-Pesa or a card needs a licensed partner, and we'd rather do it properly than quickly. Until then, send USDC to your address from any wallet or exchange.", style = t.body.copy(color = c.inkSecondary))
            }
            ComingFlow.CashOut -> {
                BasicText("Cash out is coming", style = t.title2.copy(color = c.ink))
                BasicText("We're waiting on regulatory approval to let you move money from Circaso straight to M-Pesa or your bank. Until then, you can send your USDC to an exchange and withdraw from there.", style = t.body.copy(color = c.inkSecondary))
                CircasoButton("Send to an Exchange", onSendToWallet, size = ButtonSize.Large, block = true)
            }
        }
    }
}
