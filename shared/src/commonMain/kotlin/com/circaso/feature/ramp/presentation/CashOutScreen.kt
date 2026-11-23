package com.circaso.feature.ramp.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.circaso.core.common.maskedForDisplay
import com.circaso.core.platform.openAppSettings
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.SheetTone
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.ToastKind
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.AmountDisplay
import com.circaso.designsystem.components.AmountSize
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.DetailRow
import com.circaso.designsystem.components.MpesaDetailRow
import com.circaso.designsystem.components.MpesaDisc
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SegmentedControl
import com.circaso.designsystem.components.SkeletonBlock
import com.circaso.designsystem.components.SuggestionChip
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.ramp.domain.RampMoney
import com.circaso.feature.ramp.domain.RampQuote
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.chainBaseName
import com.circaso.feature.wallet.domain.chainName
import com.circaso.feature.wallet.presentation.TestnetStrip
import kotlinx.coroutines.launch

/**
 * Cash out (design E6's place in the wallet, E2's shape): the gates and identity verification as Add Money has them,
 * the amount in USDC or shillings, a review with every fee, the sale, the USDC sent with the person's approval, and the
 * sale followed until the shillings are on their phone.
 */
@Composable
fun CashOutScreen(viewModel: CashOutViewModel, onBack: () -> Unit, onSupport: () -> Unit, onSendToExchange: () -> Unit = {}, onPurchase: (String) -> Unit = {}, onNetworks: () -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    LifecycleResumeEffect(viewModel) { viewModel.onResumed(); onPauseOrDispose { viewModel.onPaused() } }
    // The order this opened on is a purchase (a push, or the open order): Add Money takes it from here.
    LaunchedEffect(state.handOff) { state.handOff?.let(onPurchase) }
    CashOutContent(
        state, onBack, onSupport, onSendToExchange,
        onVerify = viewModel::onVerify, onSettings = { viewModel.onSettingsOpened(); openAppSettings() },
        onRetry = viewModel::load, onAmount = viewModel::onAmount, onUnit = viewModel::onUnit, onMax = viewModel::onMax, onChain = viewModel::onChain,
        onContinue = viewModel::onContinue, onBackToAmount = viewModel::onBackToAmount, onPhone = viewModel::onPhone, onNewPrice = viewModel::onNewPrice,
        onSell = viewModel::onSell, onSend = viewModel::onSend, onCancel = viewModel::onCancel, onAgain = viewModel::onAgain,
        onSellLess = viewModel::onSellLess, onNetworks = onNetworks, onLanded = viewModel::onLanded,
    )
}

internal enum class CashOutPage(val step: Boolean = false) {
    Loading, LoadFailed, Verify, VerifyState, Gate, Amount(true), Review(true), Order(true),
}

internal fun cashOutPageOf(state: CashOutUiState): CashOutPage = when (state.step) {
    CashOutStep.Loading -> CashOutPage.Loading
    CashOutStep.LoadFailed -> CashOutPage.LoadFailed
    CashOutStep.Blocked -> when {
        state.block != RampBlock.Verify -> CashOutPage.Gate
        verifyLook(state.verify, entry = VerifyEntry.CashOut).kind == VerifyKind.Intro -> CashOutPage.Verify
        else -> CashOutPage.VerifyState
    }
    CashOutStep.Amount -> CashOutPage.Amount
    CashOutStep.Review -> if (state.quote != null) CashOutPage.Review else CashOutPage.Loading
    CashOutStep.Order -> if (state.order != null) CashOutPage.Order else CashOutPage.Loading
}

internal fun CashOutUiState.verifyPage() = VerifyPageState(verify, online, testnet, canVerify, VerifyEntry.CashOut)

/** Stateless, so every state can be opened with sample data (`-preview=cash-out…`). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CashOutContent(
    state: CashOutUiState,
    onBack: () -> Unit,
    onSupport: () -> Unit = {},
    onSendToExchange: () -> Unit = {},
    onVerify: () -> Unit = {},
    onSettings: () -> Unit = {},
    onRetry: () -> Unit = {},
    onAmount: (String) -> Unit = {},
    onUnit: (SaleUnit) -> Unit = {},
    onMax: () -> Unit = {},
    onChain: (String) -> Unit = {},
    onContinue: () -> Unit = {},
    onBackToAmount: () -> Unit = {},
    onPhone: (String) -> Unit = {},
    onNewPrice: () -> Unit = {},
    onSell: () -> Unit = {},
    onSend: () -> Unit = {},
    onCancel: () -> Unit = {},
    onAgain: () -> Unit = {},
    onSellLess: () -> Unit = {},
    onNetworks: () -> Unit = {},
    /** The sale's card settled on the M-Pesa card that landed ([CashOutUiState.celebrate]): the chime. */
    onLanded: () -> Unit = {},
    /** The gallery's: holds the sale's conversion at this point (0..1). */
    frozenAt: Float? = null,
) {
    val toast = rememberToastState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val onCopy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        scope.launch { toast.show("Copied", ToastKind.Done) }
    }
    val page = cashOutPageOf(state)
    val verify = page == CashOutPage.Verify || page == CashOutPage.VerifyState
    val plain = page == CashOutPage.Verify
    // Nothing is undone by leaving while the sale is being made or the approval is up: Back waits for the answer.
    val holding = state.placing || state.sending
    BackHandler(enabled = holding) {}
    BackHandler(enabled = page == CashOutPage.Review && !holding, onBack = onBackToAmount)
    CircasoScaffold(
        grouped = !plain,
        easeGround = true,
        topBar = {
            CircasoNavBar(
                title = when (page) {
                    CashOutPage.Verify, CashOutPage.VerifyState -> ""
                    CashOutPage.Review -> "Review"
                    else -> "Cash Out"
                },
                onBack = when {
                    holding -> null
                    page == CashOutPage.Review -> onBackToAmount
                    else -> onBack
                },
                close = verify || page == CashOutPage.Order,
            )
        },
        strip = { TestnetStrip(show = state.testnet, onGrouped = !plain) },
        toast = { ToastHost(toast) },
    ) { padding ->
        val reduce = CircasoTheme.reduceMotion
        val bottom = padding.calculateBottomPadding()
        AnimatedContent(
            targetState = state,
            contentKey = { cashOutPageOf(it) },
            transitionSpec = { cashOutPageChange(reduce) },
            label = "cash-out",
            modifier = Modifier.fillMaxSize(),
        ) { s ->
            when (cashOutPageOf(s)) {
                CashOutPage.Loading -> CashOutLoadingPage(s, bottom)
                CashOutPage.LoadFailed -> CashOutLoadFailedPage(s, bottom, onRetry, onBack, onCopy)
                CashOutPage.Verify -> VerifyIntroPage(s.verifyPage(), bottom, onVerify, onBack, onCopy)
                CashOutPage.VerifyState -> VerifyStatePage(s.verifyPage(), bottom, onVerify, onSettings, onSupport, onBack, onCopy)
                CashOutPage.Gate -> CashOutGatePage(s, bottom, onRetry, onBack, onSupport, onSendToExchange, onNetworks)
                CashOutPage.Amount -> SaleAmountPage(s, bottom, onAmount, onUnit, onMax, onChain, onContinue, onCopy)
                CashOutPage.Review -> SaleReviewPage(s, bottom, onPhone, onNewPrice, onSell, onCopy)
                CashOutPage.Order -> s.order?.let { SaleOrderPage(s, it, bottom, onBack, onSupport, onSend, onCancel, onAgain, onCopy, onSellLess, onLanded, frozenAt) }
            }
        }
    }
}

/** As Add Money's: the flow's own steps come in from their side over a short distance; everything else fades. */
private fun AnimatedContentTransitionScope<CashOutUiState>.cashOutPageChange(reduce: Boolean): ContentTransform {
    val from = cashOutPageOf(initialState)
    val to = cashOutPageOf(targetState)
    if (reduce) return fadeIn(tween(CircasoMotion.QuickMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs))
    if (!from.step || !to.step) return fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs))
    val forward = to.ordinal > from.ordinal
    val travel: (Int) -> Int = { width -> (width / 8) * if (forward) 1 else -1 }
    return (fadeIn(tween(CircasoMotion.BaseMs)) + slideInHorizontally(tween(CircasoMotion.BaseMs)) { travel(it) }) togetherWith
        (fadeOut(tween(CircasoMotion.QuickMs)) + slideOutHorizontally(tween(CircasoMotion.QuickMs)) { -travel(it) })
}

@Composable
private fun CashOutLoadFailedPage(state: CashOutUiState, bottomInset: Dp, onRetry: () -> Unit, onBack: () -> Unit, onCopy: (String) -> Unit) {
    val offline = !state.online
    MessagePage(
        bottomInset,
        if (offline) CircasoIcon.Offline else CircasoIcon.Warning,
        if (offline) "You're offline" else if (state.orderUnread) "This sale didn't load" else "Cash Out didn't open",
        when {
            state.orderUnread -> if (offline) "This sale shows when you're back online. It isn't affected: if you sent the USDC, the sale carries on." else state.failure?.message ?: "We couldn't load this sale just now. It isn't affected."
            offline -> "Cashing out needs a connection: Circaso and M-Pesa have to talk while you sell."
            else -> state.failure?.message ?: CashOutViewModel.CouldNotOpen
        },
        "Try Again", onRetry, "Close", onBack, reference = state.failure?.reference, onCopy = onCopy, tone = if (offline) SheetTone.Neutral else SheetTone.Warning,
    )
}

/**
 * R1's gates other than E3, in E6's honest voice: closed, barred, paused, nobody buying, or no wallet on the chain.
 * E6's "Notify Me When It's Ready" is left out: there is no route for it, so the closed page offers only E6's other way
 * on, sending to an exchange.
 */
@Composable
private fun CashOutGatePage(state: CashOutUiState, bottomInset: Dp, onRetry: () -> Unit, onBack: () -> Unit, onSupport: () -> Unit, onSendToExchange: () -> Unit, onNetworks: () -> Unit) {
    when (state.block) {
        RampBlock.Barred -> MessagePage(bottomInset, CircasoIcon.Lock, CashOutBarredTitle, BarredMessage, "Contact Support", onSupport, "Close", onBack, tone = SheetTone.Warning)
        RampBlock.Restricted -> MessagePage(
            bottomInset, CircasoIcon.Lock, "Cashing out is paused on this account",
            "This account can't cash out right now. Write to Support and we'll look into it. Sending and receiving aren't affected by this.",
            "Contact Support", onSupport, "Close", onBack, tone = SheetTone.Warning,
        )
        RampBlock.NoMerchant -> MessagePage(
            bottomInset, CircasoIcon.Clock, "No one can buy USDC right now",
            "Circaso's M-Pesa partners are offline for the moment. Try again soon.",
            "Try Again", onRetry, "Close", onBack, primaryEnabled = state.online, note = CashOutOfflineNote.takeIf { !state.online },
        )
        RampBlock.NoWallet -> MessagePage(
            bottomInset, CircasoIcon.Network, "Your wallet isn't on this network yet",
            "Cashing out sends USDC from your wallet on this network. Add the network to your wallet, then try again.",
            "Manage Networks", onNetworks, "Close", onBack,
        )
        // Closed to this person (or a server from before selling): E6's way on, sending to an exchange.
        else -> MessagePage(
            bottomInset, CircasoIcon.Bank, "Cash out isn't open to you yet",
            "Cashing out to M-Pesa isn't available on your account yet. Until it is, you can send your USDC to an exchange and withdraw from there.",
            "Send to an Exchange", onSendToExchange, "Close", onBack,
        )
    }
}

@Composable
private fun CashOutLoadingPage(state: CashOutUiState, bottomInset: Dp) {
    val label = if (state.loadingOrder) "Loading your sale" else "Checking whether you can cash out"
    Box(Modifier.fillMaxSize().semantics(mergeDescendants = true) { contentDescription = label }) {
        AddMoneyPageFrame(
            bottomInset,
            footer = {
                SkeletonBlock(Modifier.fillMaxWidth().height(CircasoSizes.controlLg), CircasoShapes.pill, CircasoTheme.colors.fillStrong)
                SkeletonLine(CircasoTheme.typography.footnote, 0.6f)
            },
        ) {
            if (state.loadingOrder) OrderSkeleton() else SaleAmountSkeleton()
        }
    }
}

/** The amount page's shape: the number, its line, the unit switch, "Receive with" over M-Pesa, the details. */
@Composable
private fun SaleAmountSkeleton() {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val hero = with(LocalDensity.current) { t.amountHero.lineHeight.toDp() }
    Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        SkeletonBlock(Modifier.fillMaxWidth(0.5f).height(hero), CircasoShapes.md, c.fillStrong)
        SkeletonLine(t.footnote, 0.45f)
    }
    SkeletonBlock(Modifier.fillMaxWidth().height(CircasoSizes.controlSm), CircasoShapes.pill, c.fillStrong)
    Column(verticalArrangement = Arrangement.spacedBy(AmountGap)) {
        SkeletonLine(t.footnote, 0.25f, Modifier.padding(horizontal = CircasoSpacing.s1))
        ListGroup {
            Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBlock(Modifier.size(CircasoSizes.avatarMd), CircasoShapes.circle)
                Column(Modifier.weight(1f)) {
                    SkeletonLine(t.body, 0.3f, strong = false)
                    SkeletonLine(t.subhead, 0.5f, strong = false)
                }
            }
        }
    }
    SkeletonRows(3)
}

// ---- the amount ----------------------------------------------------------------------------------------------------

/**
 * How much to sell: typed in USDC or in the shillings wanted on M-Pesa (a switch above the number), the balance and Max
 * beside it, a live price under it, M-Pesa as where it goes; Continue pinned below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SaleAmountPage(
    state: CashOutUiState, bottomInset: Dp, onAmount: (String) -> Unit, onUnit: (SaleUnit) -> Unit, onMax: () -> Unit, onChain: (String) -> Unit,
    onContinue: () -> Unit, onCopy: (String) -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val limits = state.limits
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) { if (state.amountText.isEmpty()) runCatching { focus.requestFocus() } }
    AddMoneyPageFrame(
        bottomInset,
        footer = {
            CircasoButton("Continue", { focusManager.clearFocus(); onContinue() }, size = ButtonSize.Large, block = true, enabled = state.canQuote || state.quoting, busy = state.quoting)
            FooterNote(if (!state.online) CashOutOfflineNote else "You'll see every fee before anything leaves your wallet.")
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
            SaleAmountEntry(state, onAmount, focus, onDone = { focusManager.clearFocus() })
            val error = state.amountError
            val live = state.liveEstimate
            val line = when {
                error != null -> error
                live != null && state.unit == SaleUnit.Usdc -> "You receive about ${live.receive.text}"
                live != null -> "You send about ${live.pay.text}"
                // An amount is typed and its price isn't in yet: said at once, never the limits flickering in between.
                state.estimating || (state.askKey != null && state.online && state.estimateFailedFor != state.askKey) -> "Getting a price…"
                state.askKey != null && state.estimateFailedFor == state.askKey -> "No price just now. Continue tries again."
                limits != null -> "${limits.minOrder.text} to ${limits.maxOrder.text} to M-Pesa"
                else -> " "
            }
            val still = CircasoTheme.reduceMotion
            AnimatedContent(
                line to (error != null), contentKey = { it.first.substringBefore("about") },
                transitionSpec = { fadeIn(tween(if (still) 0 else CircasoMotion.QuickMs)) togetherWith fadeOut(tween(if (still) 0 else CircasoMotion.ExitMs)) },
                label = "amount line", modifier = if (error != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
            ) { (text, wrong) ->
                BasicText(text, style = t.footnote.copy(color = if (wrong) c.critical else c.inkSecondary, textAlign = TextAlign.Center, fontFeatureSettings = "tnum"))
            }
        }
        // What the number is in, and how much there is to sell.
        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            SegmentedControl(
                listOf(RampMoney.Coin, RampMoney.Fiat), if (state.unit == SaleUnit.Usdc) 0 else 1,
                { i -> val u = if (i == 0) SaleUnit.Usdc else SaleUnit.Kes; if (u != state.unit) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onUnit(u) } },
                Modifier.semantics { contentDescription = if (state.unit == SaleUnit.Usdc) "Typing the USDC to sell. Switch to shillings to receive." else "Typing the shillings to receive. Switch to USDC to sell." },
            )
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                BasicText(state.available ?: "Checking your balance…", Modifier.align(Alignment.CenterVertically), style = t.footnote.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"))
                if (state.canMax) SuggestionChip("Max", { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); focusManager.clearFocus(); onMax() }, Modifier.align(Alignment.CenterVertically).semantics { contentDescription = "Sell everything: ${state.available}" })
            }
        }
        Labelled("Receive with") {
            ListGroup {
                ListRow(
                    "M-Pesa", sub = state.maskedPhone?.maskedForDisplay() ?: "Your verified number",
                    leading = { MpesaDisc() },
                    trailing = { CircasoIconImage(CircasoIcon.Check, c.ink, Modifier.size(SelectedGlyph).semantics { contentDescription = "Selected" }, strokeWidth = 2.4f) },
                )
            }
        }
        val chains = state.status?.blockchains.orEmpty()
        if (chains.size > 1) Labelled("Sell from") {
            SegmentedControl(chains.map { chainBaseName(it) }, chains.indexOf(state.blockchain).coerceAtLeast(0), { onChain(chains[it]) })
        }
        Column {
            ListGroup {
                DetailRow("You sell", RampMoney.Coin + (state.blockchain?.let { " on " + chainName(it) } ?: ""))
                RowDivider()
                DetailRow("Fees", "Shown before you sell")
                RowDivider()
                DetailRow("Network fee", "Before you approve")
                if (limits != null) {
                    RowDivider(); FigureRow("At a time", "${limits.minOrder.text} to ${limits.maxOrder.text}")
                    RowDivider(); FigureRow("Daily limit", limits.daily.text)
                }
            }
            FailureNote(state.failure, onCopy)
        }
    }
}

/** The amount, large and calm: its unit on the number's baseline, the number stepping down a size when it wouldn't fit. */
@Composable
private fun SaleAmountEntry(state: CashOutUiState, onAmount: (String) -> Unit, focus: FocusRequester, onDone: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val line = with(density) { t.amountHero.lineHeight.toDp() }
    val unit = if (state.unit == SaleUnit.Usdc) RampMoney.Coin else RampMoney.Fiat
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = line), contentAlignment = Alignment.Center) {
        val shown = grouped(state.amountText).ifEmpty { "0" }
        val room = constraints.maxWidth
        val sizes = listOf(t.amountHero to t.title2, t.amountLarge to t.title2, t.amountMedium to t.title3)
        val (number, currency) = remember(shown, unit, room, density, t) {
            val spare = with(density) { (AmountGap + CaretRoom).roundToPx() }
            sizes.firstOrNull { (n, cur) ->
                measurer.measure(shown, n, maxLines = 1).size.width + measurer.measure(unit, cur.copy(fontWeight = FontWeight.SemiBold), maxLines = 1).size.width + spare <= room
            } ?: sizes.last()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AmountGap)) {
            BasicText(unit, style = currency.copy(fontWeight = FontWeight.SemiBold, color = c.inkSecondary), modifier = Modifier.alignByBaseline())
            BasicTextField(
                value = state.amountText, visualTransformation = if (state.unit == SaleUnit.Kes) GroupedDigits else GroupedDecimal,
                onValueChange = onAmount, singleLine = true, enabled = !state.quoting,
                textStyle = number.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                keyboardOptions = KeyboardOptions(keyboardType = if (state.unit == SaleUnit.Kes) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.alignByBaseline().focusRequester(focus).width(IntrinsicSize.Min).defaultMinSize(minWidth = CircasoSpacing.s8)
                    .semantics { contentDescription = if (state.unit == SaleUnit.Kes) "Shillings to receive on M-Pesa" else "USDC to sell" },
                decorationBox = { field ->
                    Box {
                        if (state.amountText.isEmpty()) BasicText("0", style = number.copy(color = c.inkTertiary))
                        field()
                    }
                },
            )
        }
    }
}

/** "1234.5" drawn as "1,234.5": the whole part grouped, the decimals as typed. */
private fun grouped(text: String): String {
    if (text.isEmpty()) return text
    val whole = text.substringBefore('.')
    val rest = if (text.contains('.')) "." + text.substringAfter('.') else ""
    return MinorUnits.group(whole) + rest
}

/** [GroupedDigits] for a decimal: commas in the whole part only, the caret mapped across them. */
internal val GroupedDecimal = androidx.compose.ui.text.input.VisualTransformation { text ->
    val raw = text.text
    val out = grouped(raw)
    val whole = raw.substringBefore('.').length
    fun commasBefore(o: Int): Int { val upto = minOf(o, whole); return if (upto <= 0) 0 else ((whole - 1) / 3) - ((whole - upto) / 3) }
    androidx.compose.ui.text.input.TransformedText(
        AnnotatedString(out),
        object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = (offset + commasBefore(offset)).coerceIn(0, out.length)
            override fun transformedToOriginal(offset: Int): Int = out.take(offset.coerceIn(0, out.length)).count { it != ',' }
        },
    )
}

// ---- the review ----------------------------------------------------------------------------------------------------

/**
 * The sale as it will happen, before anything is done: what leaves the wallet first and largest, then what reaches
 * M-Pesa with every fee named (the payout provider's too), then where it goes. The price's minute runs in the footer;
 * one primary button, "Sell".
 */
@Composable
private fun SaleReviewPage(state: CashOutUiState, bottomInset: Dp, onPhone: (String) -> Unit, onNewPrice: () -> Unit, onSell: () -> Unit, onCopy: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val quote = state.quote ?: return
    val expired = state.quoteExpired
    val focusManager = LocalFocusManager.current
    AddMoneyPageFrame(
        bottomInset,
        footer = {
            PriceClock(state.secondsLeft, expired)
            CircasoButton(
                when {
                    state.placing -> "Selling…"
                    expired -> "Get a New Price"
                    state.failure != null -> "Try Again"
                    else -> "Sell"
                },
                { focusManager.clearFocus(); if (expired) onNewPrice() else onSell() }, size = ButtonSize.Large, block = true,
                enabled = if (expired) state.online else state.canConfirm || state.placing || state.quoting, busy = state.placing || state.quoting,
            )
            FooterNote(if (!state.online) CashOutOfflineNote else "Next, you'll approve sending ${quote.pay.text}, plus a network fee if your wallet charges one. You'll see it first.")
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s2).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
            BasicText("You sell", style = t.subhead.copy(color = c.inkSecondary))
            AmountDisplay(quote.pay.unit, quote.pay.number, size = AmountSize.Large)
        }
        Column {
            SaleQuoteDetails(quote, state.maskedPhone)
            Reveal(state.repriced && !expired) {
                Banner("The price was updated while you were here. Check it, then sell.", CircasoIcon.Info, modifier = Modifier.padding(top = CircasoSpacing.s4), tone = BannerTone.Neutral)
            }
            FailureNote(state.failure, onCopy)
        }
        if (state.needsPhone) Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            val ending = state.maskedPhone?.filter { it.isDigit() }?.takeLast(3)?.takeIf { it.length == 3 }
            CircasoTextField(
                state.phoneText, onPhone, label = "Your M-Pesa number", placeholder = "0712 345 678", error = state.phoneError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done), enabled = !state.placing,
            )
            BasicText(
                "Type the number your account is verified with" + (ending?.let { ", ending in $it" } ?: "") + ". The shillings can only go to that number, and this phone asks for it once.",
                style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(horizontal = CircasoSpacing.s1),
            )
        }
    }
}

/** What reaches M-Pesa and how it is worked out (the value, then each fee), then where it goes and where it leaves from. */
@Composable
private fun SaleQuoteDetails(quote: RampQuote, maskedPhone: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        // Worked out in the order it happens: the rate, what the USDC is worth, each fee taken out, what reaches M-Pesa.
        ListGroup {
            FigureRow("Rate", rateLine(quote.rate))
            quote.worth?.let { RowDivider(); FigureRow("Worth at this rate", it.text) }
            RowDivider()
            FigureRow("Circaso fee", saleFee(quote.fee))
            RowDivider()
            FigureRow("M-Pesa payout fee", saleFee(quote.providerFee))
            RowDivider()
            FigureRow("You receive", quote.receive.text, strong = true)
        }
        ListGroup {
            MpesaDetailRow("To", maskedPhone)
            RowDivider()
            DetailRow("From", "Your wallet on " + chainName(quote.blockchain))
        }
    }
}
