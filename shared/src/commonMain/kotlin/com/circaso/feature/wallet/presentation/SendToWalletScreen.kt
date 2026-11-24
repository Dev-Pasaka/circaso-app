package com.circaso.feature.wallet.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.drawBehind
import com.circaso.designsystem.motion.CircasoSpring
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.SheetTone
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.ToastKind
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.DetailRow
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.NetworkIcon
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SkeletonBlock
import com.circaso.designsystem.components.SuggestionChip
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.ramp.presentation.AddMoneyPageFrame
import com.circaso.feature.ramp.presentation.AmountGap
import com.circaso.feature.ramp.presentation.CaretRoom
import com.circaso.feature.ramp.presentation.FooterNote
import com.circaso.feature.ramp.presentation.GroupedDecimal
import com.circaso.feature.ramp.presentation.Labelled
import com.circaso.feature.ramp.presentation.MessagePage
import com.circaso.feature.ramp.presentation.Reference
import com.circaso.feature.ramp.presentation.Reveal
import com.circaso.feature.ramp.presentation.lastOf
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.chainBaseName
import com.circaso.feature.wallet.domain.chainName
import com.circaso.feature.wallet.domain.isSolanaChain
import kotlinx.coroutines.launch

/**
 * Design E5, rebuilt (2026-11-24, Pasaka: "made in a hurry"; now in the family of Add Money and Cash Out): who to pay
 * (paste, scan, a recent address) and on which network; how much, with the balance and Max; a review where the payment
 * is the silver USDC card and the address is grouped in fours to check against the other wallet; the PIN; then the same
 * card going out and landing, with the explorer link. The steps slide in from their side; Review, the PIN and Sent
 * keep the card in place, so the payment reads as one object moving on.
 */
@Composable
fun SendToWalletScreen(viewModel: SendToWalletViewModel, onBack: () -> Unit, onOpenTransfer: (String) -> Unit, onSupport: () -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    SendToWalletContent(
        state, onBack, viewModel::onAddress, viewModel::onChain, viewModel::onAmount, viewModel::onReview, viewModel::onBackToForm, viewModel::onConfirm,
        viewModel::onErrorShown, onOpenTransfer, onScan = viewModel::onScan, onNote = viewModel::onNote, onRecent = viewModel::onRecent,
        onContinue = viewModel::onContinue, onBackToRecipient = viewModel::onBackToRecipient, onMax = viewModel::onMax, onSupport = onSupport,
    )
}

internal enum class SendScreenPage(val step: Boolean = true) { NotOwner(false), Recipient, Amount, Confirm }

internal fun sendPageOf(state: SendToWalletUiState): SendScreenPage = when {
    state.step == ExternalStep.Form && state.notOwner -> SendScreenPage.NotOwner
    state.step == ExternalStep.Form -> if (state.page == SendPage.Recipient) SendScreenPage.Recipient else SendScreenPage.Amount
    else -> SendScreenPage.Confirm
}

/** Stateless, so every state opens with sample data (`-preview=send-wallet…`). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SendToWalletContent(
    state: SendToWalletUiState,
    onBack: () -> Unit,
    onAddress: (String) -> Unit = {},
    onChain: (String) -> Unit = {},
    onAmount: (String) -> Unit = {},
    onReview: () -> Unit = {},
    onBackToForm: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onErrorShown: () -> Unit = {},
    onOpenTransfer: (String) -> Unit = {},
    onScan: () -> Unit = {},
    onNote: (String) -> Unit = {},
    onRecent: (RecentAddress) -> Unit = {},
    onContinue: () -> Unit = {},
    onBackToRecipient: () -> Unit = {},
    onMax: () -> Unit = {},
    onSupport: () -> Unit = {},
) {
    val toast = rememberToastState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val onCopy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        scope.launch { toast.show("Copied", ToastKind.Done) }
    }
    LaunchedEffect(state.error) { state.error?.let { toast.show(it, ToastKind.Problem); onErrorShown() } }
    val page = sendPageOf(state)
    val signing = state.step == ExternalStep.Signing
    // Nothing is undone by leaving while the PIN is up: Back waits for its answer.
    BackHandler(enabled = signing) {}
    BackHandler(enabled = page == SendScreenPage.Amount, onBack = onBackToRecipient)
    BackHandler(enabled = state.step == ExternalStep.Review, onBack = onBackToForm)
    CircasoScaffold(
        grouped = true,
        topBar = {
            CircasoNavBar(
                title = when {
                    state.step == ExternalStep.Done -> ""
                    page == SendScreenPage.Confirm -> "Review"
                    else -> "Send to a Wallet"
                },
                subtitle = state.walletName.takeIf { state.fromGroup && state.from != null && page != SendScreenPage.NotOwner && state.step != ExternalStep.Done },
                onBack = when {
                    signing -> null
                    state.step == ExternalStep.Review -> onBackToForm
                    page == SendScreenPage.Amount -> onBackToRecipient
                    else -> onBack
                },
                close = state.step == ExternalStep.Done,
            )
        },
        // From the network this send is pinned to, not from whatever is selected on the Wallet tab.
        strip = { TestnetStrip(show = state.testnet, onGrouped = true) },
        toast = { ToastHost(toast) },
    ) { padding ->
        val bottom = padding.calculateBottomPadding()
        val reduce = CircasoTheme.reduceMotion
        AnimatedContent(
            targetState = state,
            contentKey = { sendPageOf(it) },
            transitionSpec = { sendPageChange(reduce) },
            label = "send-to-wallet",
            modifier = Modifier.fillMaxSize(),
        ) { s ->
            when (sendPageOf(s)) {
                SendScreenPage.NotOwner -> MessagePage(
                    bottom, CircasoIcon.Lock, "Only the owner sends from here",
                    "Only the group’s owner can send from ${s.walletName}’s wallet. You can still add to it.",
                    "Done", onBack, tone = SheetTone.Neutral,
                )
                SendScreenPage.Recipient -> RecipientPage(s, bottom, onAddress, onChain, onScan, onRecent, onContinue, onEmptyPaste = { scope.launch { toast.show("Nothing to paste", ToastKind.Info) } })
                SendScreenPage.Amount -> AmountPage(s, bottom, onAmount, onMax, onNote, onReview, onBackToRecipient, onCopy)
                SendScreenPage.Confirm -> ConfirmPage(s, bottom, onConfirm, onBack, onOpenTransfer, onCopy, onSupport)
            }
        }
    }
}

/** As Add Money's: the flow's steps come in from their side over a short distance; anything else fades. */
private fun AnimatedContentTransitionScope<SendToWalletUiState>.sendPageChange(reduce: Boolean): ContentTransform {
    val from = sendPageOf(initialState)
    val to = sendPageOf(targetState)
    if (reduce) return fadeIn(tween(CircasoMotion.QuickMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs))
    if (!from.step || !to.step) return fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs))
    val forward = to.ordinal > from.ordinal
    val travel: (Int) -> Int = { width -> (width / 8) * if (forward) 1 else -1 }
    return (fadeIn(tween(CircasoMotion.BaseMs)) + slideInHorizontally(tween(CircasoMotion.BaseMs)) { travel(it) }) togetherWith
        (fadeOut(tween(CircasoMotion.QuickMs)) + slideOutHorizontally(tween(CircasoMotion.QuickMs)) { -travel(it) })
}

// ---- who, and on which network ---------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipientPage(
    state: SendToWalletUiState, bottomInset: Dp, onAddress: (String) -> Unit, onChain: (String) -> Unit, onScan: () -> Unit,
    onRecent: (RecentAddress) -> Unit, onContinue: () -> Unit, onEmptyPaste: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val focus = remember { FocusRequester() }
    // The keyboard only once this phone's history is read: with recent addresses to offer, it stays down.
    LaunchedEffect(state.recentLoaded) { if (state.recentLoaded && state.address.isEmpty() && state.recent.isEmpty()) runCatching { focus.requestFocus() } }
    AddMoneyPageFrame(
        bottomInset,
        footer = {
            CircasoButton("Continue", { focusManager.clearFocus(); onContinue() }, size = ButtonSize.Large, block = true, enabled = state.canContinue)
            FooterNote(
                when {
                    !state.online -> "You can fill this in offline. Sending needs a connection."
                    state.blockchain != null && state.family != null -> "Send only to a wallet that takes USDC on ${chainBaseName(state.blockchain)}."
                    else -> "Paste the address, or scan the wallet's QR code."
                },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            CircasoTextField(
                state.address, onAddress, label = "Wallet address", placeholder = "0x… or a Solana address",
                modifier = Modifier.focusRequester(focus), singleLine = false,
                textStyle = CircasoTheme.typography.subhead.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = AddressGrouping,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                        CircasoIconButton(CircasoIcon.Clipboard, "Paste address", {
                            val pasted = clipboard.getText()?.text?.takeIf { it.isNotBlank() }
                            if (pasted == null) onEmptyPaste() else { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onAddress(pasted) }
                        }, raised = true)
                        if (state.canScan) CircasoIconButton(CircasoIcon.Scan, "Scan a QR code", { focusManager.clearFocus(); onScan() }, raised = true)
                    }
                },
            )
            // What the address is, once it is a whole one: the networks below follow from it.
            val kind = when (state.family) {
                com.circaso.core.qr.QrPayload.Family.Evm -> "An address on Base, Polygon or Ethereum"
                com.circaso.core.qr.QrPayload.Family.Solana -> "A Solana address"
                null -> null
            }
            val partial = kind == null && state.address.length >= PartialAddressLength
            Reveal(kind != null || partial) {
                val shown = lastOf(kind ?: if (partial) "That isn't a whole address yet." else null)
                Row(Modifier.padding(horizontal = CircasoSpacing.s1).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (kind != null) CircasoIconImage(CircasoIcon.Check, c.inkSecondary, Modifier.size(14.dp), strokeWidth = 2.4f)
                    BasicText(shown.orEmpty(), style = t.footnote.copy(color = c.inkSecondary))
                }
            }
            ScanNotes(state)
        }
        // Who was paid before: the quickest way to send again, until something is typed.
        Reveal(state.address.isBlank() && state.recent.isNotEmpty()) {
            Labelled("Recent") {
                ListGroup {
                    state.recent.forEachIndexed { i, r ->
                        if (i > 0) RowDivider()
                        ListRow(
                            shortGrouped(r.address),
                            sub = chainBaseName(r.blockchain) + " · " + com.circaso.feature.assistant.presentation.relativeTime(r.at, com.circaso.core.sync.nowMillis()),
                            leading = { NetworkIcon(r.blockchain, size = CircasoSizes.avatarMd) },
                            trailing = { RowChevron() },
                            onClick = { focusManager.clearFocus(); onRecent(r) },
                            onClickLabel = "Send to this address again",
                            modifier = Modifier.semantics { contentDescription = "${spokenAddress(r.address)}, on ${chainBaseName(r.blockchain)}" },
                        )
                    }
                }
            }
        }
        NetworkChoice(state, onChain)
    }
}

/** What a scanned or pasted code did: filled nothing and why (red), or filled in and left something out (calm). */
@Composable
private fun ScanNotes(state: SendToWalletUiState) {
    val problem = lastOf(state.scanProblem)
    Reveal(state.scanProblem != null) {
        if (problem != null) Banner(problem, CircasoIcon.Warning, tone = BannerTone.Critical, modifier = Modifier.padding(top = CircasoSpacing.s1))
    }
    val notice = lastOf(state.notice)
    Reveal(state.notice != null && state.scanProblem == null) {
        if (notice != null) Banner(notice, CircasoIcon.Info, tone = BannerTone.Neutral, modifier = Modifier.padding(top = CircasoSpacing.s1))
    }
}

/** The networks that can pay this address, each with what's on it; the chosen one ticked. */
@Composable
private fun NetworkChoice(state: SendToWalletUiState, onChain: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val haptics = LocalHapticFeedback.current
    Labelled("Network") {
        val wallet = state.wallet
        when {
            wallet == null || state.fromLoading -> ListGroup {
                repeat(2) { i ->
                    if (i > 0) RowDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.CenterVertically) {
                        SkeletonBlock(Modifier.size(CircasoSizes.avatarMd), CircasoShapes.circle)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SkeletonBlock(Modifier.fillMaxWidth(0.3f).height(14.dp))
                            SkeletonBlock(Modifier.fillMaxWidth(0.5f).height(12.dp))
                        }
                    }
                }
            }
            state.chains.isEmpty() -> Banner(
                if (state.family == com.circaso.core.qr.QrPayload.Family.Solana) "${if (state.fromGroup) state.walletName + "’s wallet isn't" else "Your wallet isn't"} on Solana. Add it under Wallet → Networks to pay this address."
                else "${if (state.fromGroup) state.walletName + "’s wallet isn't" else "Your wallet isn't"} on a network that can pay this address. Add one under Wallet → Networks.",
                CircasoIcon.Network, tone = BannerTone.Neutral,
            )
            else -> ListGroup(Modifier.selectableGroup().animateContentSize(CircasoSpring.resize())) {
                state.chains.forEachIndexed { i, chain ->
                    if (i > 0) RowDivider()
                    val chosen = chain.blockchain == state.blockchain
                    val balance = chain.usdc?.amountMinor ?: "0"
                    ListRow(
                        chainBaseName(chain.blockchain),
                        sub = MinorUnits.toDisplay(balance, 6) + " USDC" + (if (isSolanaChain(chain.blockchain)) " · network fee in SOL" else ""),
                        leading = { NetworkIcon(chain.blockchain, size = CircasoSizes.avatarMd) },
                        trailing = {
                            Box(Modifier.size(SelectedMark), contentAlignment = Alignment.Center) {
                                if (chosen) CircasoIconImage(CircasoIcon.Check, c.ink, Modifier.size(SelectedMark), strokeWidth = 2.4f)
                            }
                        },
                        role = Role.RadioButton,
                        onClick = if (state.chains.size > 1) ({ if (!chosen) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onChain(chain.blockchain) } }) else null,
                        modifier = Modifier.semantics { contentDescription = "${chainName(chain.blockchain)}, ${MinorUnits.toDisplay(balance, 6)} USDC"; selected = chosen },
                    )
                }
            }
        }
    }
    if (state.wallet != null && state.chains.size > 1) BasicText(
        "USDC on one network can't be received on another. Pick the one the other wallet uses.",
        Modifier.padding(horizontal = CircasoSpacing.s1), style = t.footnote.copy(color = c.inkSecondary),
    )
}

// ---- how much --------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AmountPage(
    state: SendToWalletUiState, bottomInset: Dp, onAmount: (String) -> Unit, onMax: () -> Unit, onNote: (String) -> Unit,
    onReview: () -> Unit, onBackToRecipient: () -> Unit, onCopy: (String) -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) { if (state.amountText.isEmpty()) runCatching { focus.requestFocus() } }
    val chain = state.blockchain
    AddMoneyPageFrame(
        bottomInset,
        footer = {
            CircasoButton("Review", { focusManager.clearFocus(); onReview() }, size = ButtonSize.Large, block = true, enabled = state.canReview || state.checking, busy = state.checking)
            FooterNote(if (!state.online) "You need a connection to send money." else "Nothing leaves ${if (state.fromGroup) state.walletName else "your wallet"} until you approve it.")
        },
    ) {
        // Who it's for, as a chip: a tap goes back to change it.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                Modifier.clip(CircasoShapes.pill).background(c.surface).pressable(enabled = !state.checking, onClickLabel = "Change the address") { onBackToRecipient() }
                    .heightIn(min = CircasoSizes.controlMd).padding(start = CircasoSpacing.s2, end = CircasoSpacing.s3)
                    .semantics(mergeDescendants = true) { contentDescription = "To ${spokenAddress(state.address)}" + (chain?.let { ", on ${chainBaseName(it)}" } ?: "") },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
            ) {
                chain?.let { NetworkIcon(it, size = CircasoSizes.avatarSm) }
                BasicText("To ", style = t.subhead.copy(color = c.inkSecondary))
                BasicText(shortGrouped(state.address), style = t.subhead.copy(color = c.ink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold), maxLines = 1)
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
            UsdcAmountEntry(state, onAmount, focus, onDone = { focusManager.clearFocus() })
            val line = when {
                state.overBalance -> (if (state.fromGroup) "${state.walletName} has" else "You have") + " ${MinorUnits.toDisplay(state.balanceMinor, 6)} USDC on ${chain?.let { chainBaseName(it) } ?: "this network"}"
                state.fiatHint != null -> state.fiatHint
                else -> " "
            }
            BasicText(
                line.orEmpty(), Modifier.semantics { if (state.overBalance) liveRegion = LiveRegionMode.Polite },
                style = t.footnote.copy(color = if (state.overBalance) c.critical else c.inkSecondary, textAlign = TextAlign.Center, fontFeatureSettings = "tnum"),
            )
        }
        // A group wallet's balance only once its name and balance have loaded: never "has 0 USDC" for a moment.
        if (!state.fromLoading && state.wallet != null) FlowRow(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
        ) {
            BasicText(
                "Available ${MinorUnits.toDisplay(state.balanceMinor, 6)} USDC", Modifier.align(Alignment.CenterVertically),
                style = t.footnote.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"),
            )
            if (state.canMax) SuggestionChip("Max", { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); focusManager.clearFocus(); onMax() }, Modifier.align(Alignment.CenterVertically).semantics { contentDescription = "Send everything on this network" })
        }
        ScanNotes(state)
        CircasoTextField(state.note, onNote, label = "Note", placeholder = if (state.fromGroup) "What's it for? The group sees it" else "What's it for? Only you see it")
        Banner(
            "Make sure the receiving wallet supports USDC on ${chain?.let { chainBaseName(it) } ?: "this network"}. Crypto sent to the wrong network can't be recovered.",
            CircasoIcon.Warning, tone = BannerTone.Warning,
        )
        // The check or the fee couldn't be had: said here, with what Support needs, until something changes.
        val problem = lastOf(state.problem)
        Reveal(state.problem != null) {
            if (problem != null) Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                Banner(problem.message, CircasoIcon.Warning, tone = BannerTone.Critical)
                problem.reference?.let { Reference(it, onCopy) }
            }
        }
        ListGroup {
            // The one Circaso fee, network cost included, is worked out for exactly this payment on Review; on Solana the
            // network's fee is paid in SOL, which is said here.
            if (isSolanaChain(chain)) { DetailRow("Network fee", SolanaFeeLine); RowDivider() }
            DetailRow("They receive", state.amountMinor?.takeIf { !MinorUnits.isZero(it) }?.let { MinorUnits.toDisplay(it, 6) + " USDC" } ?: "—")
        }
        // Solana and no SOL for its fee: said here, where it stays, with the address to add SOL to.
        if (state.gasShort) SolanaGasNote(state.walletName.takeIf { state.fromGroup }, state.chainAddress)
    }
}

/** The amount, large and calm: USDC on the number's baseline, the number stepping down a size when it wouldn't fit. */
@Composable
private fun UsdcAmountEntry(state: SendToWalletUiState, onAmount: (String) -> Unit, focus: FocusRequester, onDone: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val line = with(density) { t.amountHero.lineHeight.toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = line), contentAlignment = Alignment.Center) {
        val shown = state.amountText.ifEmpty { "0" }
        val room = constraints.maxWidth
        val sizes = listOf(t.amountHero to t.title2, t.amountLarge to t.title2, t.amountMedium to t.title3)
        val (number, currency) = remember(shown, room, density, t) {
            val spare = with(density) { (AmountGap + CaretRoom).roundToPx() }
            sizes.firstOrNull { (n, cur) ->
                measurer.measure(shown, n, maxLines = 1).size.width + measurer.measure("USDC", cur.copy(fontWeight = FontWeight.SemiBold), maxLines = 1).size.width + spare <= room
            } ?: sizes.last()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AmountGap)) {
            BasicTextField(
                value = state.amountText, visualTransformation = GroupedDecimal,
                onValueChange = onAmount, singleLine = true, enabled = !state.checking,
                textStyle = number.copy(color = if (state.overBalance) c.critical else c.ink), cursorBrush = SolidColor(c.ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.alignByBaseline().focusRequester(focus).width(IntrinsicSize.Min).defaultMinSize(minWidth = CircasoSpacing.s8)
                    .semantics { contentDescription = "USDC to send" },
                decorationBox = { field ->
                    Box {
                        if (state.amountText.isEmpty()) BasicText("0", style = number.copy(color = c.inkTertiary))
                        field()
                    }
                },
            )
            BasicText("USDC", style = currency.copy(fontWeight = FontWeight.SemiBold, color = c.inkSecondary), modifier = Modifier.alignByBaseline())
        }
    }
}

// ---- review, the PIN, and sent ---------------------------------------------------------------------------------------

/** The card's phase from the step and what the chain has said since. */
internal fun sendPhaseOf(state: SendToWalletUiState): SendPhase = when (state.step) {
    ExternalStep.Form, ExternalStep.Review -> SendPhase.Ready
    ExternalStep.Signing -> SendPhase.Approving
    ExternalStep.Done -> when (state.result?.state) {
        TransferState.Confirmed -> SendPhase.Delivered
        TransferState.Failed, TransferState.Expired -> SendPhase.Failed
        else -> SendPhase.Sending
    }
}

@Composable
private fun ConfirmPage(
    state: SendToWalletUiState, bottomInset: Dp, onConfirm: () -> Unit, onBack: () -> Unit, onOpenTransfer: (String) -> Unit, onCopy: (String) -> Unit,
    onSupport: () -> Unit,
) {
    val done = state.step == ExternalStep.Done
    val result = state.result
    val address = (if (done) result?.toAddress?.takeIf { it.isNotBlank() } else null) ?: state.checkedAddress ?: state.address
    val chain = (if (done) result?.blockchain else null) ?: state.blockchain
    val amountMinor = (if (done) result?.amount?.amountMinor else null) ?: state.amountMinor ?: "0"
    val phase = sendPhaseOf(state)
    AddMoneyPageFrame(
        bottomInset,
        footer = {
            if (done) {
                CircasoButton("Done", onBack, size = ButtonSize.Large, block = true)
                if (phase == SendPhase.Failed) CircasoButton("Contact Support", onSupport, style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
                else result?.let { CircasoButton("View Payment", { onOpenTransfer(it.transferId) }, style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true) }
                if (phase == SendPhase.Sending && !state.online) FooterNote("You're offline. This updates when you're back.")
            } else {
                val who = shortWalletName(state.walletName)
                CircasoButton(
                    when {
                        state.step == ExternalStep.Signing && state.fromGroup -> "Waiting for $who’s PIN…"
                        state.step == ExternalStep.Signing -> "Waiting for your PIN…"
                        state.failure != null -> "Try Again"
                        state.fromGroup -> "Confirm with $who’s PIN"
                        else -> "Send ${MinorUnits.toDisplay(amountMinor, 6)} USDC"
                    },
                    onConfirm, size = ButtonSize.Large, block = true,
                    enabled = state.step == ExternalStep.Review && state.online && !state.notOwner && !state.fromLoading, busy = state.step == ExternalStep.Signing,
                )
                FooterNote(
                    when {
                        !state.online -> "You need a connection to send money."
                        // GW11: the approval names its wallet; behind Circle's PIN this stays on screen.
                        state.fromGroup -> "${state.walletName}’s PIN, not your personal one. Everyone in ${state.walletName} sees this payment in the group chat."
                        else -> "You'll approve it with your PIN."
                    },
                )
            }
        },
    ) {
        Spacer(Modifier.height(CircasoSpacing.s1))
        SendCard(MinorUnits.toDisplay(amountMinor, 6) + " USDC", chain?.let { chainName(it) } ?: "", address, phase)
        val still = CircasoTheme.reduceMotion
        AnimatedContent(
            done, label = "send-confirm",
            transitionSpec = {
                if (still) fadeIn(tween(CircasoMotion.QuickMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs))
                else (fadeIn(tween(CircasoMotion.BaseMs)) + slideInVertically(tween(CircasoMotion.BaseMs)) { it / 10 }) togetherWith fadeOut(tween(CircasoMotion.QuickMs))
            },
        ) { sent ->
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5)) {
                if (sent) SentDetails(state, address, chain, onCopy) else ReviewDetails(state, address, chain, onCopy)
            }
        }
    }
}

@Composable
private fun ReviewDetails(state: SendToWalletUiState, address: String, chain: String?, onCopy: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Labelled("To") { AddressCard(address, onCopy) }
    ListGroup {
        if (state.fromGroup) { DetailRow("Paying from", state.walletName); RowDivider() }
        DetailRow("Network", chain?.let { chainName(it) } ?: "—")
        RowDivider()
        DetailRow("Fee", state.feeText)
        state.totalMinor?.takeIf { state.feeMinor?.let { f -> !MinorUnits.isZero(f) } == true }?.let {
            RowDivider(); DetailRow("Total", MinorUnits.toDisplay(it, 6) + " USDC")
        }
        RowDivider()
        DetailRow("They receive", state.amountMinor?.let { MinorUnits.toDisplay(it, 6) + " USDC" } ?: "—")
        state.note.trim().takeIf { it.isNotEmpty() }?.let { RowDivider(); DetailRow("Note", it) }
    }
    // GW11: what the fee is, said once.
    if (state.feeMinor?.let { !MinorUnits.isZero(it) } == true) BasicText("One Circaso fee, network cost included. It's added to what they receive.", Modifier.padding(horizontal = CircasoSpacing.s1), style = t.footnote.copy(color = c.inkSecondary))
    ScanNotes(state)
    // G2: the payment didn't go. It stays here, with its reference, until it's tried again.
    val failure = lastOf(state.failure)
    Reveal(state.failure != null) {
        if (failure != null) Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
            Banner(
                "Not sent. Nothing was taken from ${failure.wallet}." +
                    (failure.message.takeIf { it != com.circaso.core.common.ErrorMessages.SomethingWrong && !it.contains("Nothing was sent") && !it.contains("Nothing was taken") }?.let { " $it" } ?: ""),
                CircasoIcon.Warning, tone = BannerTone.Critical,
            )
            failure.reference?.let { Reference(it, onCopy) }
        }
    }
    if (state.failure == null) Banner("Payments on chain can't be reversed. Check the first and last groups against the other wallet.", CircasoIcon.Warning, tone = BannerTone.Warning)
}

/** The whole address grouped in fours, the ends in ink, on its own card; a tap copies it. */
@Composable
private fun AddressCard(address: String, onCopy: (String) -> Unit) {
    val c = CircasoTheme.colors
    ListGroup {
        Row(
            Modifier.fillMaxWidth().pressable(onClickLabel = "Copy the address") { onCopy(address) }.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupedAddress(address, Modifier.weight(1f))
            CircasoIconImage(CircasoIcon.Copy, c.inkSecondary, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SentDetails(state: SendToWalletUiState, address: String, chain: String?, onCopy: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val result = state.result
    val phase = sendPhaseOf(state)
    val where = chain?.let { chainBaseName(it) } ?: "the network"
    val (title, line) = when (phase) {
        SendPhase.Delivered -> (if (state.fromGroup) "Sent from ${state.walletName}" else "Delivered") to "It's in the other wallet on $where. Payments on chain are final."
        SendPhase.Failed -> "This payment didn't go through" to "$where didn't take it. If anything left ${if (state.fromGroup) state.walletName else "your wallet"}, it shows under Activity; Support can look it up with the reference below."
        else -> (if (state.fromGroup) "On its way from ${state.walletName}" else "On its way") to
            if (state.fromGroup) "It settles on $where in a moment. Everyone in ${state.walletName} sees it in the group chat." else "It settles on $where in a moment. You can leave this screen: it carries on."
    }
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1),
    ) {
        BasicText(title, Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(line, style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
    if (phase != SendPhase.Failed) SendSteps(reached = when { phase == SendPhase.Delivered -> 3; result?.txHash != null -> 2; else -> 1 })
    if (phase == SendPhase.Failed) result?.transferId?.let { Reference(it, onCopy) }
    ListGroup {
        Column(Modifier.fillMaxWidth().pressable(onClickLabel = "Copy the address") { onCopy(address) }.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText("To", style = t.footnote.copy(color = c.inkSecondary))
            GroupedAddress(address)
        }
        RowDivider()
        DetailRow("Network", chain?.let { chainName(it) } ?: "—")
        result?.fee?.amountMinor?.takeIf { !MinorUnits.isZero(it) }?.let { RowDivider(); DetailRow("Fee", MinorUnits.toDisplay(it, 6) + " USDC") }

        val url = result?.let { explorerUrl(it.blockchain, it.txHash) }
        if (url != null) {
            RowDivider()
            ListRow("View on Explorer", sub = result?.txHash?.let { shortGrouped(it) }, trailing = { RowChevron() }, onClick = { com.circaso.core.platform.openUrl(url) }, onClickLabel = "Open the transaction on the block explorer")
        } else if (phase == SendPhase.Sending) {
            RowDivider()
            DetailRow("Explorer link", "When the network has it")
        }
    }
}

/** Approved · On the network · Delivered: three marks joined by a line that fills as the payment moves. */
@Composable
private fun SendSteps(reached: Int) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val labels = listOf("Approved", "On the network", "Delivered")
    val still = CircasoTheme.reduceMotion
    // 0..2 along the line; the line runs between the marks' centres.
    val along by androidx.compose.animation.core.animateFloatAsState(
        (reached - 1).toFloat(), if (still) tween(0) else CircasoSpring.progress(), label = "send-steps",
    )
    Box(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Step $reached of 3: ${labels[reached - 1]}" },
    ) {
        // The track and its fill, behind the marks: from the first mark's centre to the last's (a sixth in from each side).
        Box(Modifier.fillMaxWidth().padding(top = (StepMark - StepLine) / 2).height(StepLine).drawBehind {
            val from = size.width / 6f
            val to = size.width * 5f / 6f
            val y = size.height / 2f
            drawLine(c.fillStrong, androidx.compose.ui.geometry.Offset(from, y), androidx.compose.ui.geometry.Offset(to, y), strokeWidth = size.height)
            drawLine(c.ink, androidx.compose.ui.geometry.Offset(from, y), androidx.compose.ui.geometry.Offset(from + (to - from) * (along / 2f), y), strokeWidth = size.height)
        })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            labels.forEachIndexed { i, label ->
                val on = i < reached
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    AnimatedContent(on, transitionSpec = { fadeIn(tween(if (still) 0 else CircasoMotion.BaseMs)) togetherWith fadeOut(tween(if (still) 0 else CircasoMotion.QuickMs)) }, label = "step-mark") { lit ->
                        Box(Modifier.size(StepMark).clip(CircasoShapes.circle).background(if (lit) c.ink else c.fillStrong), contentAlignment = Alignment.Center) {
                            if (lit) CircasoIconImage(CircasoIcon.Check, c.bg, Modifier.size(StepGlyph), strokeWidth = 2.8f)
                        }
                    }
                    BasicText(label, style = t.caption1.copy(color = if (on) c.ink else c.inkSecondary, textAlign = TextAlign.Center, fontWeight = if (i == reached - 1) FontWeight.SemiBold else FontWeight.Normal))
                }
            }
        }
    }
}

/**
 * The address field's grouping as it is typed (`0x7a3F 0000 0000 …`): a space after every four characters past the
 * `0x`, never stored, with the caret carried across the spaces.
 */
internal val AddressGrouping = VisualTransformation { text ->
    val raw = text.text
    val start = if (raw.startsWith("0x") || raw.startsWith("0X")) 2 else 0
    // A space before original index p for p = start + 4k (k ≥ 1), p < length.
    fun spacesBefore(o: Int): Int = if (o <= start || raw.length <= start) 0 else minOf((o - start) / 4, (raw.length - 1 - start) / 4)
    val out = buildString {
        raw.forEachIndexed { i, ch ->
            if (i > start && (i - start) % 4 == 0) append(' ')
            append(ch)
        }
    }
    TransformedText(
        AnnotatedString(out),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = (offset + spacesBefore(offset)).coerceIn(0, out.length)
            override fun transformedToOriginal(offset: Int): Int {
                var spaces = 0
                for (i in 0 until minOf(offset, out.length)) if (out[i] == ' ') spaces++
                return (offset - spaces).coerceIn(0, raw.length)
            }
        },
    )
}

/** Past this many characters with no whole address yet, the field says so. */
private const val PartialAddressLength = 30

/** The tick beside the chosen network: the same size as Cash Out's "Receive with". */
private val SelectedMark = 22.dp

/** The steps' marks, their tick, and the line between them. */
private val StepMark = 22.dp
private val StepGlyph = 12.dp
private val StepLine = 2.dp
