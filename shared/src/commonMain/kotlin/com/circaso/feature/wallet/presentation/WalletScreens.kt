package com.circaso.feature.wallet.presentation

import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.animation.togetherWith
import com.circaso.feature.chama.presentation.GroupReceiveContent
import com.circaso.core.platform.chatListTime
import com.circaso.core.platform.formatLocalTime
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
import com.circaso.designsystem.components.LetterDisc
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
    /** View Transactions under the wallet: every payment of the wallet on screen, with its filters. */
    val onActivity: () -> Unit,
    val onNetworks: () -> Unit,
    /** One asset's statement on one network. */
    val onAsset: (blockchain: String, symbol: String) -> Unit = { _, _ -> },
    /** One network's payments. */
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

/**
 * The Wallet tab. [actions] are the links for one wallet ([walletId] null: the personal one), so See All and the
 * asset screens open the wallet on screen; [groupActions] are a group wallet's own (contribute, payout, target).
 */
@Composable
fun WalletTab(
    viewModel: WalletViewModel,
    bottomPadding: PaddingValues,
    actions: (walletId: String?) -> WalletActions,
    groupActions: ((chatId: String, group: com.circaso.feature.chama.presentation.GroupWalletUiState) -> com.circaso.feature.chama.presentation.GroupWalletActions)? = null,
    /** The Groups menu's way out of the tab: the group's chat and its info. */
    groupMenu: GroupMenuActions? = null,
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
    WalletTabContent(
        state, bottomPadding, actions,
        groupHooks = { chatId, g ->
            groupActions?.let { make ->
                com.circaso.feature.chama.presentation.GroupWalletHooks(make(chatId, g), viewModel::onDecide, { viewModel.refresh(fromPull = true) }, viewModel::onGroupMessageShown)
            }
        },
        onSwitching = viewModel::onSwitching, onSelectWallet = viewModel::onSelectWallet,
        onMode = viewModel::onMode, onAllGroups = viewModel::onAllGroups, groupMenu = groupMenu,
        onCreate = viewModel::onCreate, onRefresh = { viewModel.refresh(fromPull = true) }, onReceive = viewModel::onReceive, onComing = viewModel::onComing,
        onErrorShown = viewModel::onErrorShown, onAddNetworks = viewModel::onAddMissingNetworks, onToggleHidden = viewModel::onToggleHidden,
    )
}

/**
 * The tab without its view model (previews). With group wallets to show: a compact Personal | Groups control at the
 * top (Pasaka, 2026-11-06; it was a full-width bar); under Groups the group on screen as the title with the
 * platform's overflow menu (switch group, its chat, its info). Someone with no group wallet sees no control at all,
 * just their wallet. The picked wallet is drawn in the one wallet design; switching crossfades the body.
 */
@Composable
fun WalletTabContent(
    state: WalletUiState,
    bottomPadding: PaddingValues,
    actions: (walletId: String?) -> WalletActions,
    groupHooks: (chatId: String, group: com.circaso.feature.chama.presentation.GroupWalletUiState) -> com.circaso.feature.chama.presentation.GroupWalletHooks?,
    onSwitching: (Boolean) -> Unit,
    onSelectWallet: (NamedWallet) -> Unit,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
    onReceive: (Boolean) -> Unit,
    onComing: (ComingFlow?) -> Unit,
    onErrorShown: () -> Unit = {},
    onAddNetworks: () -> Unit = {},
    onToggleHidden: () -> Unit = {},
    onMode: (WalletMode) -> Unit = {},
    onAllGroups: (Boolean) -> Unit = {},
    groupMenu: GroupMenuActions? = null,
) {
    // The remembered side (Personal or Groups) and the saved list of group wallets take a few milliseconds to read:
    // the tab waits for both rather than drawing Personal and then sliding across to Groups, or drawing the control
    // a moment after the page.
    if (state.groupsEnabled && (!state.choiceRestored || !state.walletsLoaded)) { Box(Modifier.fillMaxSize()); return }
    Column(Modifier.fillMaxSize()) {
        // The Personal | Groups row. Already there when the tab opens with the flag known, it doesn't move anything;
        // when it arrives (or goes) after the page is up, it eases open or closed instead of the page jumping by its
        // height (a fade alone with reduced motion is still a resize, so it eases too).
        val row = remember { androidx.compose.animation.core.MutableTransitionState(state.showSwitcher) }
        row.targetState = state.showSwitcher
        androidx.compose.animation.AnimatedVisibility(
            row,
            enter = androidx.compose.animation.expandVertically(tween(CircasoMotion.BaseMs)) + androidx.compose.animation.fadeIn(tween(CircasoMotion.BaseMs)),
            exit = androidx.compose.animation.shrinkVertically(tween(CircasoMotion.QuickMs)) + androidx.compose.animation.fadeOut(tween(CircasoMotion.QuickMs)),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4), contentAlignment = Alignment.Center) {
                WalletModeControl(groups = state.showingGroups, onMode = onMode)
            }
        }
        // A group picked a moment ago may not be read yet: its own skeleton, named, until it is.
        val shown = state.selectedGroupKey?.let { chat ->
            state.copy(group = state.group ?: com.circaso.feature.chama.presentation.GroupWalletUiState(nameHint = state.selected.name, online = state.online)) to chat
        }
        androidx.compose.animation.AnimatedContent(
            targetState = shown?.first ?: state.copy(group = null),
            contentKey = { it.selectedWalletId ?: "personal" },
            transitionSpec = {
                androidx.compose.animation.fadeIn(tween(CircasoMotion.BaseMs)) togetherWith androidx.compose.animation.fadeOut(tween(CircasoMotion.QuickMs))
            },
            label = "wallet",
            modifier = Modifier.weight(1f),
        ) { s ->
            val chat = s.selectedGroupKey
            val g = s.group
            Column(Modifier.fillMaxSize()) {
                // The group on screen is the page's title; its menu switches group (the person's own wallet is Personal).
                // It is the page's first row, so it scrolls with the page (the personal wallet pins nothing either).
                WalletContent(
                    s, bottomPadding, actions(s.selectedWalletId),
                    onCreate = onCreate, onRefresh = onRefresh, onReceive = onReceive, onComing = onComing, onErrorShown = onErrorShown,
                    sheets = false, onAddNetworks = onAddNetworks, onToggleHidden = onToggleHidden,
                    group = if (chat != null && g != null) groupHooks(chat, g) else null,
                    header = if (s.showingGroups) ({ inList -> GroupWalletHeader(s, onSwitching, onSelectWallet, onAllGroups, groupMenu, inList = inList) }) else null,
                )
            }
        }
    }
}

/** The Wallet tab's sheets (E4, E2/E6), for the home scaffold's overlay so they sit above the tab bar. */
@Composable
fun androidx.compose.foundation.layout.BoxScope.WalletOverlay(
    viewModel: WalletViewModel,
    actions: WalletActions,
    biometrics: com.circaso.feature.account.presentation.BiometricApprovalViewModel? = null,
    groupMenu: GroupMenuActions? = null,
) {
    val state by viewModel.state.collectAsState()
    WalletSheets(state, actions, viewModel::onReceive, viewModel::onComing)
    // Every group wallet, searchable, for someone in more groups than the Groups menu lists.
    CircasoSheet(state.allGroups, { viewModel.onAllGroups(false) }, label = "Group Wallets") {
        AllGroupWalletsContent(state, onPick = viewModel::onSelectWallet, onClose = { viewModel.onAllGroups(false) }, menu = groupMenu)
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
    val what = bio.inSentence
    com.circaso.designsystem.chrome.SheetMessage(
        "Approve payments with $what?",
        "Confirm payments without typing your PIN. You'll enter your PIN once to set it up, and it's always there if $what doesn't work. Circaso never sees your PIN.",
        if (bio.label.startsWith("Face")) CircasoIcon.FaceId else CircasoIcon.Fingerprint,
    )
    com.circaso.designsystem.chrome.SheetActions(
        "Turn On ${bio.label}", onTurnOn, "Not Now", onNotNow, enabled = bio.online, busy = bio.busy,
        note = bio.error ?: if (!bio.online) "Turning this on needs a connection. You can do it later in You → Security." else null,
        noteIsError = bio.error != null,
    )
}

/** Receive (E4, or the group's addresses) and the coming sheets, for whichever wallet is on screen. */
@Composable
fun androidx.compose.foundation.layout.BoxScope.WalletSheets(state: WalletUiState, actions: WalletActions, onReceive: (Boolean) -> Unit, onComing: (ComingFlow?) -> Unit) {
    val group = state.group
    CircasoSheet(state.receiveSheet, { onReceive(false) }, label = group?.let { "Receive into ${it.name}" } ?: "Receive") {
        if (group != null) GroupReceiveContent(group, onClose = { onReceive(false) })
        else ReceiveSheetContent(state.wallet, state.handle, onClose = { onReceive(false) })
    }
    // Hold the last flow while the sheet slides away, so its card doesn't empty to a grabber on the way out.
    val lastComing = remember { androidx.compose.runtime.mutableStateOf(state.coming) }
    state.coming?.let { lastComing.value = it }
    CircasoSheet(state.coming != null, { onComing(null) }, label = "Coming soon") {
        lastComing.value?.let {
            ComingSheetContent(
                it, onSendToWallet = { onComing(null); actions.onSendToWallet() }, onClose = { onComing(null) },
                onShowAddress = { onComing(null); onReceive(true) },
            )
        }
    }
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
    /**
     * A group wallet's own parts (with [WalletUiState.group] set): the same screen, with its money, its payments, its
     * caption, Add / Payout / Receive / Target, the target and the payout requests (Pasaka, 2026-11-04).
     */
    group: com.circaso.feature.chama.presentation.GroupWalletHooks? = null,
    /**
     * The Groups header (the group's name and its menu): the page's first row, so it scrolls away with the page as
     * the personal wallet's top does, never pinned over the card. Above the other states, which don't scroll.
     */
    header: (@Composable (inList: Boolean) -> Unit)? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    // Inside Home the tab's toast is Home's, placed by its scaffold; on its own (previews) a local one.
    val screenToast = com.circaso.designsystem.chrome.LocalScreenToast.current
    val toast = screenToast ?: com.circaso.designsystem.chrome.rememberToastState()
    val g = state.group
    // The create screen shows its error inline; an active wallet shows a passing toast instead.
    LaunchedEffect(state.error, g == null) { if (g == null) state.error?.let { if (state.active) { toast.show(it, com.circaso.designsystem.chrome.ToastKind.Problem); onErrorShown() } } }
    LaunchedEffect(g?.message) { g?.message?.let { toast.show(it); group?.onMessageShown?.invoke() } }
    val wallet = g?.wallet ?: state.wallet.takeIf { g == null }
    val bottom = bottomPadding.calculateBottomPadding() + CircasoSpacing.s4
    // The active wallet scrolls its header with it; every other state is still, with the header above it.
    val showsActive = wallet != null && wallet.status == WalletStatus.Active
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        if (header != null && !showsActive) header(false)
        Box(Modifier.weight(1f)) {
        when {
            // The wallet's own shape while it opens (a card, the actions, two rows), so nothing jumps when it arrives.
            wallet == null && (if (g != null) g.loading && g.money?.wallet != null || g.empty && g.loading else state.loading) ->
                WalletSkeleton(if (g != null) "Opening ${g.name}’s wallet" else "Opening your wallet", caption = g != null)
            g != null && wallet == null -> GroupWalletProblem(g, bottom, group?.onRetry ?: onRefresh)
            wallet == null -> Column(Modifier.fillMaxSize().padding(horizontal = CircasoSpacing.s6), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(state.error ?: "Couldn't open your wallet.", style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                CircasoButton("Try Again", onRefresh, style = ButtonStyle.Secondary, enabled = state.online)
            }
            g != null && wallet.status != WalletStatus.Active -> GroupWalletProblem(g, bottom, group?.onRetry ?: onRefresh)
            wallet.status != WalletStatus.Active -> CreateWallet(state, bottom, onCreate)
            else -> ActiveWallet(state, wallet, bottom, actions, onReceive, onComing, group?.onRetry ?: onRefresh, onAddNetworks, onToggleHidden, group, header)
        }
        }
      }
        if (screenToast == null) com.circaso.designsystem.chrome.ToastHost(toast, Modifier.align(Alignment.TopCenter).statusBarsPadding())
        if (sheets) WalletSheets(state, actions, onReceive, onComing)
    }
}

/**
 * The wallet's own shape while it opens, built from the same measures as the page: a group's caption line, the
 * card where the pager puts it (s6 from the screen edge, the card ratio), the action buttons as ActionButton draws
 * them (the circle and its label), and the asset rows' card. So nothing jumps when the wallet arrives.
 */
@Composable
private fun WalletSkeleton(label: String, caption: Boolean = false) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val lineHeight = with(androidx.compose.ui.platform.LocalDensity.current) { t.footnote.lineHeight.toDp() }
    Column(
        Modifier.fillMaxSize().padding(horizontal = CircasoSpacing.s4).padding(top = WalletTopGap)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(WalletItemGap),
    ) {
        if (caption) Box(Modifier.padding(horizontal = 4.dp).fillMaxWidth(0.7f).height(lineHeight).clip(com.circaso.designsystem.theme.CircasoShapes.xs).background(c.fill))
        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            Box(Modifier.padding(horizontal = CircasoSpacing.s6 - CircasoSpacing.s4).fillMaxWidth().aspectRatio(CardRatio).clip(com.circaso.designsystem.theme.CircasoShapes.lg).background(c.fill))
            Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4 + CircasoSpacing.s2, vertical = CircasoSpacing.s2), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(4) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(com.circaso.designsystem.components.ActionButtonSize).clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(c.fill))
                        Box(Modifier.size(width = CircasoSpacing.s10, height = lineHeight).clip(com.circaso.designsystem.theme.CircasoShapes.xs).background(c.fill))
                    }
                }
            }
        }
        // The assets' card (one row), then View Transactions.
        Box(Modifier.fillMaxWidth().height(CircasoSizes.avatarMd + CircasoSpacing.s3 * 2).clip(com.circaso.designsystem.theme.CircasoShapes.md).background(c.fill))
        // The real row (it needs no data), not tappable yet: the same height at every text size, so nothing jumps.
        ViewTransactionsRow(onClick = null)
    }
}

/** The list's top inset and the space between its items, shared by the page and its skeleton. */
private val WalletTopGap = 10.dp
private val WalletItemGap = 22.dp
/** View Transactions' least height (a list row's) and its clock's size, shared by the row and the skeleton. */
private val ViewTransactionsMinHeight = 56.dp
private val ViewTransactionsIcon = 18.dp

/**
 * A group wallet with nothing to draw yet, said plainly in the personal wallet's error shape: offline before it was
 * ever read, a failed read (with its reference), a group without a wallet, or a wallet its signer hasn't finished.
 */
@Composable
private fun GroupWalletProblem(g: com.circaso.feature.chama.presentation.GroupWalletUiState, bottom: androidx.compose.ui.unit.Dp, onRetry: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val noWallet = g.money != null && g.money.wallet == null
    val (title, line) = when {
        noWallet -> "No wallet yet" to "${g.name} doesn’t have a wallet. An admin can add one from group info."
        g.settingUp -> "Being set up" to (if (g.money?.isSigner == true) "Finish setting up ${g.name}’s wallet from group info." else "${g.signerName.replaceFirstChar { it.uppercase() }} is still setting up ${g.name}’s wallet.")
        !g.online -> "You’re offline" to "${g.name}’s wallet shows here once it has been read with a connection."
        else -> "Couldn’t open the wallet" to (g.error ?: com.circaso.core.common.ErrorMessages.SomethingWrong)
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = CircasoSpacing.s6).padding(bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(title, Modifier.semantics { heading() }, style = t.title3.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(line, style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        if (!noWallet && !g.settingUp) g.reference?.let { BasicText("Ref $it", style = t.caption1.copy(color = c.inkTertiary)) }
        if (!noWallet && !g.settingUp && g.online) CircasoButton("Try Again", onRetry, style = ButtonStyle.Secondary, busy = g.loading)
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
private fun ActiveWallet(
    state: WalletUiState,
    wallet: Wallet,
    bottom: androidx.compose.ui.unit.Dp,
    actions: WalletActions,
    onReceive: (Boolean) -> Unit,
    onComing: (ComingFlow?) -> Unit,
    onRefresh: () -> Unit,
    onAddNetworks: () -> Unit = {},
    onToggleHidden: () -> Unit = {},
    group: com.circaso.feature.chama.presentation.GroupWalletHooks? = null,
    header: (@Composable (inList: Boolean) -> Unit)? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val hidden = state.hidden
    val fiat = wallet.fiat
    // A group's wallet in the same design: its own target, requests and actions.
    val g = state.group
    val loading = g?.loading ?: state.loading
    // Pull down to check for new money now. The spinner shows only for a pull, not for the quiet background refreshes.
    var pulled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(loading) { if (!loading) pulled = false }
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
    // Behind the cards, the front card's colour from under the status bar, fading out by the end of the cards.
    var glow by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.graphics.Color.Transparent) }
    var cardsBottom by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }
    var pageTop by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }
    // A micro animation, never a flash (Pasaka, 2026-11-07): the colour and its strength move apart. Arriving on the
    // tab, the glow breathes in from nothing over GlowInMs; swiping cards crossfades one network colour into the next
    // (never through transparent black). Softer than before, so it tints the page rather than lighting it.
    val glowKnown = glow != androidx.compose.ui.graphics.Color.Transparent
    var glowColour by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.graphics.Color.Transparent) }
    if (glowKnown) glowColour = glow
    val still = CircasoTheme.reduceMotion
    val animatedGlow by androidx.compose.animation.animateColorAsState(
        glowColour, if (still) androidx.compose.animation.core.snap() else tween(GlowSwapMs, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "glow",
    )
    val glowIn by androidx.compose.animation.core.animateFloatAsState(
        if (glowKnown) 1f else 0f,
        if (still) androidx.compose.animation.core.snap() else tween(GlowInMs, easing = androidx.compose.animation.core.LinearOutSlowInEasing), label = "glowIn",
    )
    val glowStrength = (if (c.isDark) 0.45f else 0.26f) * glowIn
    androidx.compose.foundation.layout.Box(Modifier.matchParentSize().onGloballyPositioned { pageTop = it.positionInWindow().y })
    // Drawn by the outermost scaffold, edge to edge from the top of the screen, so it isn't boxed in by the frame.
    val glowHeight = with(androidx.compose.ui.platform.LocalDensity.current) { cardsBottom.toDp() }
    com.circaso.designsystem.chrome.HostedBackdrop {
        if (!c.isHighContrast && cardsBottom > 0f) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().height(glowHeight)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to animatedGlow.copy(alpha = glowStrength), 0.45f to animatedGlow.copy(alpha = glowStrength * 0.5f),
                            1f to animatedGlow.copy(alpha = 0f),
                        ),
                    ),
            )
        }
    }
    // A phone on its side (the user, 2026-10-31): the cards, actions and assets on the left, the payments on the
    // right, each scrolling on its own; upright, one list.
    val short = com.circaso.designsystem.adaptive.LocalWindowLayout.current.short
    val roomy = !short && com.circaso.designsystem.adaptive.LocalWindowLayout.current.widthClass != com.circaso.designsystem.adaptive.WidthClass.Compact
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
                    ChainCards(byValue(wallet.chains, fiat), fiat, hidden, onToggleHidden, onAsset = actions.onAsset, onAssets = actions.onAssets, onGlow = { glow = it }) {
                        if (g != null && group != null) GroupActionsRow(g, group.actions, onReceive = { onReceive(true) })
                        else Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s2), horizontalArrangement = Arrangement.SpaceBetween) {
                            ActionButton(CircasoIcon.Plus, "Add", { onComing(ComingFlow.AddMoney) })
                            ActionButton(CircasoIcon.ArrowUp, "Send", actions.onPay, enabled = state.canSend)
                            ActionButton(CircasoIcon.ArrowDown, "Receive", { onReceive(true) })
                            ActionButton(CircasoIcon.Bank, "Cash Out", { onComing(ComingFlow.CashOut) })
                        }
                    }
                }
                // Everyone holds every supported network (decision 2026-10-30): the ones missing are one tap and the PIN away.
                if (g == null && state.missingNetworks.isNotEmpty()) {
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
                // The rate credit lives on the Currency screen now (the user, 2026-10-31). Networks are the person's own
                // (W12 has no walletId), so a group wallet's row is left out rather than opening the wrong wallet's.
                if (g == null) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Manage networks", style = t.footnote.copy(color = c.action, fontWeight = FontWeight.SemiBold), modifier = Modifier.clickableText(actions.onNetworks))
                }
            }
    }
    val statusItems: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        // No overall total (Pasaka, 2026-10-30): each network's card carries its own balance, the richest first.
        if (!state.online || wallet.holdUntil != null || g != null && !state.showSwitcher) {
            item(key = "status") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    // GW16's caption: whose money it is and who can move it (warning when its signer has left). In the
                    // tab the group's header above the card says it already; the pushed screen says it here.
                    val inTab = state.showSwitcher
                    if (g != null && !inTab) BasicText(g.caption, style = t.subhead.copy(color = if (g.locked) c.warning else c.inkSecondary))
                    // Offline: why the money buttons are dim. The pushed screen's banner already gives the time.
                    if (!state.online) {
                        val asOf = if (g == null || inTab) "Offline · balances as of ${formatLocalTime(wallet.fetchedAt, LocalTimeStyle.Clock)}. " else ""
                        BasicText(asOf + if (g != null) "Adding money and payouts need a connection." else "Sending needs a connection.", style = t.caption1.copy(color = c.inkSecondary))
                    }
                    wallet.holdUntil?.let { BasicText("Payments are paused for a short while after a restore.", style = t.footnote.copy(color = c.warning)) }
                }
            }
        }
    }
    // A group's target and its payout requests sit between the wallet and its payments (GW16 / D4).
    val groupItems: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        val money = g?.money
        if (g != null && group != null && money != null) {
            money.goal?.let { goal ->
                item(key = "goal") {
                    Column(Modifier.fillMaxWidth().clip(com.circaso.designsystem.theme.CircasoShapes.md).background(c.surface).padding(CircasoSpacing.s4)) {
                        com.circaso.designsystem.components.GoalProgress(
                            if (hidden) "••••" else com.circaso.feature.chama.presentation.amountText(money.contributedMinor, fiat),
                            if (hidden) "••••" else com.circaso.feature.chama.presentation.amountNumber(goal.targetMinor, fiat), money.progress,
                            meta = goal.name, label = "${goal.name} progress",
                        )
                    }
                }
            }
            if (money.openRequests.isNotEmpty()) {
                item(key = "requests-head") { BasicText("Payout requests", Modifier.padding(horizontal = 4.dp).semantics { heading() }, style = t.title3.copy(color = c.ink)) }
                items(money.openRequests, key = { "req-" + it.requestId }) { r ->
                    com.circaso.feature.chama.presentation.RequestCard(
                        r, fiat, nameOf = g::memberName, isAdmin = money.isAdmin, isTreasurer = money.isSigner && !g.locked, myUserId = g.myUserId,
                        busy = g.busy, online = state.online, onApprove = { group.onDecide(r, true) }, onDecline = { group.onDecide(r, false) },
                        onPay = { group.actions.onPayRequest(r) }, inGroupWallet = true,
                    )
                }
            }
        }
    }
    // No payments preview on the tab (Pasaka, 2026-11-06): people see their payments in their chats. One row opens
    // the wallet's full list, with its search and filters. Always there for an active wallet, so it never appears
    // late or flashes an empty "nothing yet" while payments are read.
    val transactionsItem: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        item(key = "transactions") { ViewTransactionsRow(actions.onActivity) }
    }
    // Both layouts' scroll positions live here, above the switch, so turning the phone and back returns each to where
    // it was rather than to the top.
    val sideCardsScroll = rememberScrollState()
    val sideList = androidx.compose.foundation.lazy.rememberLazyListState()
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    if (short) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(sideCardsScroll)
                    .padding(start = CircasoSpacing.s4, end = CircasoSpacing.s2, top = 10.dp, bottom = bottom),
            ) {
                header?.let { it(true); Spacer(Modifier.height(CircasoSpacing.s3)) }
                cardsBlock()
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                state = sideList,
                contentPadding = PaddingValues(start = CircasoSpacing.s2, end = CircasoSpacing.s4, top = 10.dp, bottom = bottom),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                statusItems()
                groupItems()
                transactionsItem()
            }
        }
    } else {
        // On a tablet the whole wallet is one centred column a card's width (iPad Wallet): a 680 dp card would be a
        // placard, and a narrower card over wider rows reads as three sizes stacked. The list itself stays full width,
        // so a swipe in the margins scrolls it too.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = if (roomy) maxOf(CircasoSpacing.s4, (maxWidth - CardMaxWidth) / 2) else CircasoSpacing.s4
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = side, end = side, top = 10.dp, bottom = bottom), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            header?.let { h -> item(key = "header") { h(true) } }
            statusItems()
            item(key = "cards") { cardsBlock() }
            groupItems()
            transactionsItem()
        }
        }
    }
    }
}

/**
 * View Transactions: one row on the same surface as the asset rows above it, a clock in a soft circle, the label and
 * the disclosure chevron (mirrored right to left). [ViewTransactionsMinHeight] at the least, taller with Dynamic
 * Type. With no [onClick] (the skeleton) it draws the same and isn't a button yet.
 */
@Composable
private fun ViewTransactionsRow(onClick: (() -> Unit)?) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Row(
        // Pressed, the whole row scales, its surface with it (the press sits before the background).
        Modifier.fillMaxWidth().clip(com.circaso.designsystem.theme.CircasoShapes.md)
            .then(if (onClick != null) Modifier.pressable(role = Role.Button, onClick = onClick) else Modifier).background(c.surface)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = ViewTransactionsMinHeight).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Box(Modifier.size(CircasoSizes.avatarSm).clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
            com.circaso.designsystem.components.CircasoIconImage(CircasoIcon.Clock, c.ink, Modifier.size(ViewTransactionsIcon))
        }
        BasicText("View Transactions", Modifier.weight(1f), style = t.body.copy(color = c.ink))
        com.circaso.designsystem.components.CircasoIconImage(
            CircasoIcon.ChevronRight, c.inkTertiary, Modifier.size(16.dp).graphicsLayer { if (rtl) scaleX = -1f },
        )
    }
}

/**
 * A group wallet's actions in the personal wallet's row: Add (contribute), Send (its owner only, straight from the
 * group's money, 2026-11-06; Payout, a request, while approvals are on), Receive and, for admins, Target. Money never moves offline. On a locked wallet nothing can leave, so Add and Payout are off; Receive
 * stays to show the addresses, and its sheet warns not to send money in.
 *
 * A space's wallet says Payout too for now: its Send would be a payout request today (Pay turns any group
 * `fromWalletId` into one), so "Send" would promise what doesn't happen. It becomes Send when spaces send directly.
 */
@Composable
private fun GroupActionsRow(g: com.circaso.feature.chama.presentation.GroupWalletUiState, actions: com.circaso.feature.chama.presentation.GroupWalletActions, onReceive: () -> Unit) {
    val live = g.active && g.online && !g.locked
    val admin = g.money?.isAdmin == true
    val approvals = com.circaso.core.flags.GroupPayoutApprovals.Enabled
    // Only the owner moves the group's money; members add to it (and, with approvals on, ask for a payout).
    val sends = approvals || g.money?.isSigner == true
    Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s2), horizontalArrangement = if (admin && sends) Arrangement.SpaceBetween else Arrangement.SpaceAround) {
        ActionButton(CircasoIcon.Plus, "Add", actions.onAdd, enabled = live)
        if (sends) ActionButton(CircasoIcon.ArrowUp, if (approvals) "Payout" else "Pay", actions.onPayout, enabled = live)
        ActionButton(CircasoIcon.ArrowDown, "Receive", onReceive, enabled = g.active)
        if (admin) ActionButton(CircasoIcon.Target, "Target", actions.onTarget, enabled = g.online)
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
fun TransferRow(
    transfer: Transfer,
    fiat: FiatValue?,
    hidden: Boolean,
    meta: String?,
    onClick: () -> Unit,
    /** What it was to a group wallet ("Contribution", "Payout"), in place of Sent / Received. */
    kind: String? = null,
) {
    val incoming = transfer.direction == TransferDirection.In
    val name = transfer.counterpartName ?: if (transfer.toUserId == null && transfer.toAddress.isNotEmpty()) shortAddress(transfer.toAddress) else if (incoming) "Received" else "Sent"
    val sub = buildString {
        append(kind ?: if (incoming) "Received" else "Sent")
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
        leading = { if (transfer.counterpartName != null) Avatar(transfer.counterpartName, size = CircasoSizes.avatarMd, imageUrl = transfer.counterpartAvatar) else LetterDisc(if (incoming) "IN" else "OUT") },
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
    val shareLinks = com.circaso.feature.share.presentation.LocalShareLinks.current
    val shareStatus = com.circaso.feature.share.presentation.LocalShareLinksStatus.current
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
                CircasoButton(
                    "Share", { shareLinks?.share(com.circaso.feature.share.domain.ShareTarget.PayMe) }, modifier = Modifier.weight(1f), size = ButtonSize.Large, block = true,
                    busy = shareStatus.busy == com.circaso.feature.share.domain.ShareKind.PayMe, enabled = shareStatus.online,
                )
                CircasoButton("Copy Handle", { clipboard.setText(AnnotatedString(h)) }, modifier = Modifier.weight(1f), style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
            }
            // Sharing makes a link on the server: say so before the tap rather than fail after it.
            if (h != null && !shareStatus.online) BasicText("Sharing needs a connection. Copy Handle works offline.", style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
        } else {
            val chain = chains[chainIndex.coerceIn(0, chains.lastIndex)]
            if (chains.size > 1) SegmentedControl(chains.map { chainName(it.blockchain).substringBefore(" (") }, chainIndex, { chainIndex = it })
            com.circaso.designsystem.components.QrCode(com.circaso.core.qr.QrPayload.forAddress(chain.address), "QR code of your USDC address on ${chainName(chain.blockchain)}", Modifier.align(Alignment.CenterHorizontally))
            BasicText("USDC on ${chainName(chain.blockchain)}", style = t.headline.copy(color = c.ink))
            BasicText(chain.address, style = t.callout.copy(color = c.ink), modifier = Modifier.fillMaxWidth())
            BasicText("Only send USDC on ${chainName(chain.blockchain)} to this address. Other coins or networks can't be recovered.", style = t.footnote.copy(color = c.warning))
            CircasoButton("Copy Address", { clipboard.setText(AnnotatedString(chain.address)) }, size = ButtonSize.Large, block = true)
            CircasoButton("Share", { onClose(); shareLinks?.share(com.circaso.feature.share.domain.ShareTarget.Address(chain.blockchain, "Your USDC address on ${chainName(chain.blockchain)}, with a warning to send only USDC on that network")) }, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
        }
    }
}

/** Designs E2 and E6, honestly: the flows exist on paper, the rails don't yet. */
@Composable
private fun ComingSheetContent(flow: ComingFlow, onSendToWallet: () -> Unit, onClose: () -> Unit, onShowAddress: () -> Unit) {
    // E2/E6 as a sheet: the canvas's round icon (the bank for cash out, money arriving for add money), the honest
    // "coming" line, and a clear way out, so it reads as a considered answer rather than a dead end.
    when (flow) {
        ComingFlow.AddMoney -> {
            com.circaso.designsystem.chrome.SheetMessage(
                "Adding money is coming",
                "Topping up from M-Pesa or a card needs a licensed partner, and we'd rather do it properly than quickly. Until then, send USDC to your address from any wallet or exchange.",
                CircasoIcon.ArrowDown,
            )
            com.circaso.designsystem.chrome.SheetActions("Show My Address", onShowAddress, "Not Now", onClose)
        }
        ComingFlow.CashOut -> {
            com.circaso.designsystem.chrome.SheetMessage(
                "Cash out is coming",
                "We're waiting on regulatory approval to let you move money from Circaso straight to M-Pesa or your bank. Until then, you can send your USDC to an exchange and withdraw from there.",
                CircasoIcon.Bank,
            )
            com.circaso.designsystem.chrome.SheetActions("Send to an Exchange", onSendToWallet, "Not Now", onClose)
        }
    }
}

/** The Wallet tab's glow arriving: slow enough to read as light settling, not a flash. */
private const val GlowInMs = 700
/** One network's glow handing over to the next as the cards are swiped. */
private const val GlowSwapMs = 450

/** A wallet card's widest on a tablet: about a real card held up to the screen, centred in the readable column. */
private val CardMaxWidth = 440.dp
