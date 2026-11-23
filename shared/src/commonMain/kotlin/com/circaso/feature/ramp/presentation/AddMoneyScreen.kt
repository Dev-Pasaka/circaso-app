package com.circaso.feature.ramp.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.circaso.core.platform.openAppSettings
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.SheetMessage
import com.circaso.designsystem.chrome.SheetTone
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.ToastKind
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SkeletonBlock
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.presentation.TestnetStrip
import kotlinx.coroutines.launch

/**
 * Design E2 (Add money) and E3 (verify), on the ramp: identity verification once where the server asks for it, then
 * amount, price, the M-Pesa prompt, and the order until it lands.
 */
@Composable
fun AddMoneyScreen(viewModel: AddMoneyViewModel, onBack: () -> Unit, onSupport: () -> Unit, onSale: (String) -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    // The order this opened on is a sale (a push, or the open order): Cash Out takes it from here.
    LaunchedEffect(state.handOff) { state.handOff?.let(onSale) }
    // Back in front (from Settings, from Didit's hosted page, from anywhere): a check that may have moved is read again.
    LifecycleResumeEffect(viewModel) { viewModel.onResumed(); onPauseOrDispose { viewModel.onPaused() } }
    AddMoneyContent(
        state, onBack, onSupport,
        onVerify = viewModel::onVerify, onSettings = { viewModel.onSettingsOpened(); openAppSettings() },
        onRetry = viewModel::load, onAmount = viewModel::onAmount, onChain = viewModel::onChain, onContinue = viewModel::onContinue,
        onBackToAmount = viewModel::onBackToAmount, onPhone = viewModel::onPhone, onNewPrice = viewModel::onNewPrice, onConfirm = viewModel::onConfirm,
        onCancel = viewModel::onCancel, onAgain = viewModel::onAgain, onErrorShown = viewModel::onErrorShown,
    )
}

/** The flow's pages. The four in the middle are its own steps and move sideways; the rest fade. */
internal enum class AddMoneyPage(val step: Boolean = false) {
    Loading, LoadFailed,
    /** E3 itself: identity verification, never started. */
    Verify,
    /** Every state of the check after E3 (continue, checking, in review, verified, declined, …). */
    VerifyState,
    Gate, Amount(true), Review(true), Order(true),
}

internal fun pageOf(state: AddMoneyUiState): AddMoneyPage = when (state.step) {
    AddMoneyStep.Loading -> AddMoneyPage.Loading
    AddMoneyStep.LoadFailed -> AddMoneyPage.LoadFailed
    AddMoneyStep.Blocked -> when {
        state.block != RampBlock.Verify -> AddMoneyPage.Gate
        verifyLook(state.verify).kind == VerifyKind.Intro -> AddMoneyPage.Verify
        else -> AddMoneyPage.VerifyState
    }
    AddMoneyStep.Amount -> AddMoneyPage.Amount
    // A review with no price, or an order step with no order, has nothing to draw yet.
    AddMoneyStep.Review -> if (state.quote != null) AddMoneyPage.Review else AddMoneyPage.Loading
    AddMoneyStep.Order -> if (state.order != null) AddMoneyPage.Order else AddMoneyPage.Loading
}

/** Stateless, so every state can be opened with sample data (`-preview=add-money…`). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AddMoneyContent(
    state: AddMoneyUiState,
    onBack: () -> Unit,
    onSupport: () -> Unit = {},
    onVerify: () -> Unit = {},
    onSettings: () -> Unit = {},
    onRetry: () -> Unit = {},
    onAmount: (String) -> Unit = {},
    onChain: (String) -> Unit = {},
    onContinue: () -> Unit = {},
    onBackToAmount: () -> Unit = {},
    onPhone: (String) -> Unit = {},
    onNewPrice: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
    onAgain: () -> Unit = {},
    onErrorShown: () -> Unit = {},
) {
    val toast = rememberToastState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(state.error) { state.error?.let { toast.show(it, ToastKind.Problem); onErrorShown() } }
    val onCopy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        scope.launch { toast.show("Reference copied", ToastKind.Done) }
    }
    val page = pageOf(state)
    // E3 and the states of the check after it are their own pages with Close and no title; everything else carries
    // E2's bar. E3 alone is drawn on the plain ground, as its frame has it.
    val verify = page == AddMoneyPage.Verify || page == AddMoneyPage.VerifyState
    val plain = page == AddMoneyPage.Verify
    // The system's back does what the bar's does: from the review to the amount, and nowhere while a request is being sent.
    BackHandler(enabled = state.placing) {}
    BackHandler(enabled = page == AddMoneyPage.Review && !state.placing, onBack = onBackToAmount)
    CircasoScaffold(
        grouped = !plain,
        // E3 sits on the plain ground and every other page on the grouped one: the ground fades with the pages.
        easeGround = true,
        topBar = {
            CircasoNavBar(
                title = when (page) {
                    AddMoneyPage.Verify, AddMoneyPage.VerifyState -> ""
                    AddMoneyPage.Review -> "Review"
                    else -> "Add Money"
                },
                // While the request is being sent nothing can be undone by leaving: Back waits for the answer.
                onBack = when {
                    state.placing -> null
                    page == AddMoneyPage.Review -> onBackToAmount
                    else -> onBack
                },
                // An order's status is where the flow ends: leaving it goes back to the wallet, not to a step before it.
                close = verify || page == AddMoneyPage.Order,
            )
        },
        // From the network this top-up is pinned to, not from whatever is selected on the Wallet tab.
        strip = { TestnetStrip(show = state.testnet, onGrouped = !plain) },
        toast = { ToastHost(toast) },
    ) { padding ->
        val reduce = CircasoTheme.reduceMotion
        val bottom = padding.calculateBottomPadding()
        // The page that is leaving keeps the last state it was drawn with, so a review never loses its price mid-fade.
        AnimatedContent(
            targetState = state,
            contentKey = { pageOf(it) },
            transitionSpec = { pageChange(reduce) },
            label = "add-money",
            modifier = Modifier.fillMaxSize(),
        ) { s ->
            when (pageOf(s)) {
                AddMoneyPage.Loading -> LoadingPage(s, bottom)
                AddMoneyPage.LoadFailed -> LoadFailedPage(s, bottom, onRetry, onBack, onCopy)
                AddMoneyPage.Verify -> VerifyIntroPage(s.verifyPage(), bottom, onVerify, onBack, onCopy)
                AddMoneyPage.VerifyState -> VerifyStatePage(s.verifyPage(), bottom, onVerify, onSettings, onSupport, onBack, onCopy)
                AddMoneyPage.Gate -> GatePage(s, bottom, onRetry, onBack, onSupport)
                AddMoneyPage.Amount -> AmountPage(s, bottom, onAmount, onChain, onContinue, onCopy)
                AddMoneyPage.Review -> ReviewPage(s, bottom, onPhone, onNewPrice, onConfirm, onCopy)
                AddMoneyPage.Order -> s.order?.let { OrderPage(s, it, bottom, onBack, onSupport, onCancel, onAgain, onCopy) }
            }
        }
    }
}

/**
 * Between the flow's own steps the new page comes in from the side it lies on (forward from the right, back from the
 * left) over a short distance while the old one fades: the feel of a push without a full-width slide. Everything else
 * (loading into a page, a gate, an error) fades. Both can be interrupted and turn round mid-way. Reduce Motion: a fade.
 */
private fun AnimatedContentTransitionScope<AddMoneyUiState>.pageChange(reduce: Boolean): ContentTransform {
    val from = pageOf(initialState)
    val to = pageOf(targetState)
    if (reduce) return fadeIn(tween(CircasoMotion.QuickMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs))
    if (!from.step || !to.step) return fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs))
    val forward = to.ordinal > from.ordinal
    val travel: (Int) -> Int = { width -> (width / PageTravel) * if (forward) 1 else -1 }
    return (fadeIn(tween(CircasoMotion.BaseMs)) + slideInHorizontally(tween(CircasoMotion.BaseMs)) { travel(it) }) togetherWith
        (fadeOut(tween(CircasoMotion.QuickMs)) + slideOutHorizontally(tween(CircasoMotion.QuickMs)) { -travel(it) })
}

/** A step travels an eighth of the page's width as it changes. */
private const val PageTravel = 8

internal const val OfflineNote = "Adding money needs a connection."

/**
 * One page of the flow, as E2 and E3 draw it: what there is to read scrolls, and the main button with its footnote is
 * pinned under it. The scaffold keeps the whole page above the keyboard and the home indicator, so the button always
 * sits on top of the keyboard, never under it. What scrolls fades out as it nears the buttons.
 */
@Composable
internal fun AddMoneyPageFrame(
    bottomInset: Dp,
    grouped: Boolean = true,
    side: Dp = CircasoSpacing.s4,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = CircasoTheme.colors
    val ground = if (grouped) c.bgGrouped else c.bg
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = side).padding(top = CircasoSpacing.s4, bottom = CircasoSpacing.s5),
                verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5),
                content = content,
            )
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(CircasoSpacing.s4).background(Brush.verticalGradient(listOf(ground.copy(alpha = 0f), ground))))
        }
        if (footer != null) Column(
            Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4).padding(top = CircasoSpacing.s1, bottom = bottomInset + CircasoSpacing.s3),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
            content = footer,
        )
    }
}

/** The small centred line under a page's main button: what happens next, or why the button waits. */
@Composable
internal fun FooterNote(text: String, modifier: Modifier = Modifier, color: Color = CircasoTheme.colors.inkSecondary) {
    BasicText(text, modifier.fillMaxWidth(), style = CircasoTheme.typography.footnote.copy(color = color, textAlign = TextAlign.Center))
}

/** Something that comes and goes inside a page without a jump: it opens the room it needs, and gives it back. */
@Composable
internal fun Reveal(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val still = CircasoTheme.reduceMotion
    AnimatedVisibility(
        visible, modifier,
        enter = if (still) fadeIn(tween(CircasoMotion.QuickMs)) else expandVertically(tween(CircasoMotion.BaseMs)) + fadeIn(tween(CircasoMotion.BaseMs)),
        exit = if (still) fadeOut(tween(CircasoMotion.QuickMs)) else shrinkVertically(tween(CircasoMotion.QuickMs)) + fadeOut(tween(CircasoMotion.QuickMs)),
    ) { content() }
}

/** [value], or the last one there was: what something on its way out still shows while it leaves. */
@Composable
internal fun <T : Any> lastOf(value: T?): T? {
    val kept = remember { arrayOfNulls<Any>(1) }
    if (value != null) kept[0] = value
    @Suppress("UNCHECKED_CAST")
    return value ?: kept[0] as T?
}

/** What went wrong, in place, with what to quote to Support under it. It opens and closes without moving the page abruptly. */
@Composable
internal fun FailureNote(failure: AddMoneyFailure?, onCopy: (String) -> Unit) {
    val shown = lastOf(failure)
    Reveal(failure != null) {
        if (shown != null) Column(Modifier.padding(top = CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
            // Something that failed is red; something that only changes how the person carries on is said calmly.
            if (shown.calm) Banner(shown.message, CircasoIcon.Info, tone = BannerTone.Neutral) else Banner(shown.message, CircasoIcon.Warning, tone = BannerTone.Critical)
            shown.reference?.let { Reference(it, onCopy) }
        }
    }
}

/**
 * What Support needs to find this, on every error and every order that didn't go through. A tap copies it, so nobody
 * has to read a request id out letter by letter.
 */
@Composable
internal fun Reference(text: String, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(CircasoShapes.pill)
                .pressable(role = Role.Button, onClickLabel = "Copy the reference") { onCopy(text) }
                .heightIn(min = CircasoSizes.controlMd).padding(horizontal = CircasoSpacing.s3)
                .semantics(mergeDescendants = true) { contentDescription = "Reference $text" },
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("Reference: $text", Modifier.weight(1f, fill = false), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center, fontFeatureSettings = "tnum"))
            CircasoIconImage(CircasoIcon.Copy, c.inkSecondary, Modifier.size(CopyGlyph))
        }
    }
}

/** The copy glyph beside a reference: the size of the footnote it sits with. */
internal val CopyGlyph = 14.dp

/** A whole-page message in the sheets' own pattern: badge, title, one paragraph; the main button and the way out pinned below. */
@Composable
internal fun MessagePage(
    bottomInset: Dp,
    icon: CircasoIcon,
    title: String,
    message: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    reference: String? = null,
    onCopy: (String) -> Unit = {},
    tone: SheetTone = SheetTone.Neutral,
    primaryEnabled: Boolean = true,
    note: String? = null,
) {
    AddMoneyPageFrame(
        bottomInset,
        footer = {
            CircasoButton(primary, onPrimary, size = ButtonSize.Large, block = true, enabled = primaryEnabled)
            note?.let { FooterNote(it) }
            if (secondary != null) CircasoButton(secondary, onSecondary, style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
        },
    ) {
        Spacer(Modifier.height(CircasoSpacing.s6))
        SheetMessage(title, message, icon, tone = tone)
        reference?.let { Reference(it, onCopy) }
    }
}

@Composable
private fun LoadFailedPage(state: AddMoneyUiState, bottomInset: Dp, onRetry: () -> Unit, onBack: () -> Unit, onCopy: (String) -> Unit) {
    val offline = !state.online
    MessagePage(
        bottomInset,
        if (offline) CircasoIcon.Offline else CircasoIcon.Warning,
        if (offline) "You're offline" else if (state.orderUnread) "This top-up didn't load" else "Add Money didn't open",
        when {
            // An order that exists may already be paid: only what is known is said.
            state.orderUnread -> if (offline) "This top-up shows when you're back online. It isn't affected: if you approved the M-Pesa request, your USDC still arrives." else state.failure?.message ?: "We couldn't load this top-up just now. It isn't affected."
            offline -> "Adding money needs a connection: M-Pesa and Circaso have to talk while you pay. No M-Pesa request was sent."
            else -> state.failure?.message ?: AddMoneyViewModel.CouldNotOpen
        },
        "Try Again", onRetry, "Close", onBack, reference = state.failure?.reference, onCopy = onCopy, tone = if (offline) SheetTone.Neutral else SheetTone.Warning,
    )
}

/**
 * R1's gates other than E3: the account or its identity is barred, the account is paused, nobody is selling right now,
 * or the ramp isn't open to this person.
 */
@Composable
private fun GatePage(state: AddMoneyUiState, bottomInset: Dp, onRetry: () -> Unit, onBack: () -> Unit, onSupport: () -> Unit) {
    when (state.block) {
        // R1's `blocked`, or `ramp.blocked` from any route: said as it is, and verifying is never offered behind it.
        RampBlock.Barred -> MessagePage(bottomInset, CircasoIcon.Lock, BarredTitle, BarredMessage, "Contact Support", onSupport, "Close", onBack, tone = SheetTone.Warning)
        RampBlock.Restricted -> MessagePage(
            bottomInset, CircasoIcon.Lock, "Adding money is paused on this account",
            "This account can't add money right now. Write to Support and we'll look into it. Sending and receiving aren't affected by this.",
            "Contact Support", onSupport, "Close", onBack, tone = SheetTone.Warning,
        )
        RampBlock.NoMerchant -> MessagePage(
            bottomInset, CircasoIcon.Clock, "Adding money is unavailable right now",
            "Circaso's M-Pesa partners are offline for the moment. No M-Pesa request was sent. Try again soon.",
            "Try Again", onRetry, "Close", onBack, primaryEnabled = state.online, note = OfflineNote.takeIf { !state.online },
        )
        // `ramp.unavailable`: off, or a preview this person isn't in. Said as the wallet always said it.
        else -> MessagePage(
            bottomInset, CircasoIcon.Lock, "Adding money isn't open to you yet",
            "Topping up from M-Pesa is opening to people a few at a time" + (if (state.testnet) ", and on Testnet it is for Circaso's own testing" else "") +
                ". Until then, send USDC to your address from any wallet or exchange.",
            "Close", onBack, null, {},
        )
    }
}

/**
 * The first paint: the shape of the page that is about to arrive (the amount, or an order's status when the flow opens
 * on one), breathing, so what arrives lands where its placeholder was. Never a bare spinner.
 */
@Composable
private fun LoadingPage(state: AddMoneyUiState, bottomInset: Dp) {
    val label = if (state.loadingOrder) "Loading your top-up" else "Checking whether you can add money"
    Box(Modifier.fillMaxSize().semantics(mergeDescendants = true) { contentDescription = label }) {
        AddMoneyPageFrame(
            bottomInset,
            footer = {
                SkeletonBlock(Modifier.fillMaxWidth().height(CircasoSizes.controlLg), CircasoShapes.pill, CircasoTheme.colors.fillStrong)
                SkeletonLine(CircasoTheme.typography.footnote, 0.6f)
            },
        ) {
            if (state.loadingOrder) OrderSkeleton() else AmountSkeleton()
        }
    }
}

/** A bar as tall as one line of [style] at the person's text size, [fraction] of the width. */
@Composable
internal fun SkeletonLine(style: TextStyle, fraction: Float, modifier: Modifier = Modifier, strong: Boolean = true) {
    val line = with(LocalDensity.current) { style.fontSize.toDp() }
    val lead = with(LocalDensity.current) { (style.lineHeight.toDp() - line) / 2 }
    SkeletonBlock(modifier.padding(vertical = lead).fillMaxWidth(fraction).height(line), color = if (strong) CircasoTheme.colors.fillStrong else CircasoTheme.colors.fill)
}

@Composable
internal fun SkeletonRows(count: Int) {
    val t = CircasoTheme.typography
    ListGroup {
        repeat(count) { i ->
            if (i > 0) RowDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.weight(1f)) { SkeletonLine(t.subhead, listOf(0.4f, 0.3f, 0.5f)[i % 3], strong = false) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { SkeletonLine(t.subhead, listOf(0.7f, 0.5f, 0.6f)[i % 3], strong = false) }
            }
        }
    }
}

/** The gap between a section's label and its group on the amount page (E2: 6 px). */
private val SkeletonLabelGap = 6.dp

@Composable
private fun AmountSkeleton() {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val hero = with(LocalDensity.current) { t.amountHero.lineHeight.toDp() }
    Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        SkeletonBlock(Modifier.fillMaxWidth(0.5f).height(hero), CircasoShapes.md, c.fillStrong)
        SkeletonLine(t.footnote, 0.45f)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2, Alignment.CenterHorizontally)) {
        repeat(3) { SkeletonBlock(Modifier.width(CircasoSizes.avatarXl).height(CircasoSizes.controlSm), CircasoShapes.pill, c.fillStrong) }
    }
    // "Pay with" over the M-Pesa row, as the page has it.
    Column(verticalArrangement = Arrangement.spacedBy(SkeletonLabelGap)) {
        SkeletonLine(t.footnote, 0.2f, Modifier.padding(horizontal = CircasoSpacing.s1))
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

@Composable
internal fun OrderSkeleton() {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s6), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        SkeletonBlock(Modifier.size(CircasoSizes.sheetBadge), CircasoShapes.circle, c.fillStrong)
        SkeletonLine(t.title2, 0.6f)
        SkeletonLine(t.body, 0.85f)
        SkeletonLine(t.body, 0.7f)
    }
    SkeletonRows(4)
}
