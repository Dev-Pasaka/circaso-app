package com.circaso.feature.ramp.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.platform.localDayIndex
import com.circaso.core.platform.openAppSettings
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.SheetBadge
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
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.presentation.TestnetStrip
import kotlinx.coroutines.launch

/**
 * You › Verification: where the person's identity check stands on the wallet network in use, and the check itself
 * (design E3 and its states), without starting a top-up first. The pages of the check are the ones Add Money shows
 * ([VerifyIntroPage], [VerifyStatePage]); verified here stays, on its own calm page.
 */
@Composable
fun IdentityScreen(viewModel: IdentityViewModel, onBack: () -> Unit, onSupport: () -> Unit) {
    val state by viewModel.state.collectAsState()
    // Back in front (from Settings, from Didit's hosted page, from a notification): what is on screen is read again.
    LifecycleResumeEffect(viewModel) { viewModel.onResumed(); onPauseOrDispose { viewModel.onPaused() } }
    IdentityContent(
        state, onBack, onSupport,
        onVerify = viewModel::onVerify, onSettings = { viewModel.onSettingsOpened(); openAppSettings() }, onRetry = viewModel::read,
    )
}

/** Stateless, so every state can be opened with sample data (`-preview=identity…`). */
@Composable
fun IdentityContent(
    state: IdentityUiState,
    onBack: () -> Unit,
    onSupport: () -> Unit = {},
    onVerify: () -> Unit = {},
    onSettings: () -> Unit = {},
    onRetry: () -> Unit = {},
    /** The clock "Last checked" is worded against (previews pin it). */
    now: Long = nowMillis(),
) {
    val toast = rememberToastState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val onCopy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        scope.launch { toast.show("Reference copied", ToastKind.Done) }
    }
    val page = state.page
    // E3 alone is drawn on the plain ground, as its frame has it; every other page sits on the grouped one.
    val plain = page == IdentityPage.Intro
    CircasoScaffold(
        grouped = !plain,
        easeGround = true,
        topBar = { CircasoNavBar(title = IdentityRowTitle, onBack = onBack) },
        // The network this check is for: a Testnet check is a practice run, and the screen says so from its first paint.
        strip = { TestnetStrip(show = state.testnet, onGrouped = !plain) },
        toast = { ToastHost(toast) },
    ) { padding ->
        val still = CircasoTheme.reduceMotion
        val bottom = padding.calculateBottomPadding()
        // The page that is leaving keeps the last state it was drawn with; the states of the check change inside
        // their own page (its badge holds its place), so only a different kind of page fades.
        AnimatedContent(
            targetState = state,
            contentKey = { it.page },
            transitionSpec = { fadeIn(tween(if (still) CircasoMotion.QuickMs else CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs)) },
            label = "identity",
            modifier = Modifier.fillMaxSize(),
        ) { s ->
            when (s.page) {
                IdentityPage.Loading -> IdentityLoadingPage(bottom)
                IdentityPage.LoadFailed -> IdentityLoadFailedPage(s, bottom, onRetry, onCopy)
                IdentityPage.Unavailable -> IdentityMessagePage(
                    bottom, CircasoIcon.Clock, "Verification isn't available yet",
                    "Identity verification isn't open on your account yet. Nothing is needed from you. When it opens, you can verify here.",
                    onDone = onBack,
                )
                IdentityPage.Gate -> IdentityGatePage(s, bottom, onSupport, onBack)
                IdentityPage.NotNeeded -> IdentityMessagePage(
                    bottom, CircasoIcon.IdCard, "No verification needed",
                    "Adding money on ${s.network.label} doesn't ask for identity verification right now. If that changes, you can verify here.",
                    onDone = onBack, lastChecked = lastChecked(s, now),
                )
                IdentityPage.Intro -> VerifyIntroPage(s.verifyPage, bottom, onVerify, onBack, onCopy)
                IdentityPage.State -> VerifyStatePage(s.verifyPage, bottom, onVerify, onSettings, onSupport, onBack, onCopy, lastChecked = lastChecked(s, now))
                IdentityPage.Verified -> VerifiedPage(s, bottom, lastChecked(s, now))
            }
        }
    }
}

/**
 * "Showing your last known status, checked today, 14:05." when what shows is the phone's last known copy (offline, or the read got no answer);
 * null when the server has just said it.
 */
internal fun lastChecked(state: IdentityUiState, now: Long): String? {
    val at = state.checkedAt?.takeIf { state.lastKnown } ?: return null
    val clock = formatLocalTime(at, LocalTimeStyle.Clock)
    val day = when (localDayIndex(now) - localDayIndex(at)) {
        0L -> "today, $clock"
        1L -> "yesterday, $clock"
        else -> formatLocalTime(at, LocalTimeStyle.ShortDate) + ", $clock"
    }
    return if (state.online) "Couldn't check just now. Showing your last known status, checked $day." else "Showing your last known status, checked $day."
}

/**
 * Verified, and staying that way on screen: the sign, the words, then what it means in three plain rows (what it
 * unlocks, who holds the documents, which network it counts on). Nothing to tap: Back leaves. When it happened in
 * front of the person it is felt once. The contract gives no date for when someone was verified, so none is shown.
 */
@Composable
private fun VerifiedPage(state: IdentityUiState, bottomInset: Dp, lastChecked: String?) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val haptics = LocalHapticFeedback.current
    var felt by remember { mutableStateOf(false) }
    LaunchedEffect(state.justVerified) {
        if (state.justVerified && !felt) { felt = true; haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    }
    val testnet = state.network == WalletNetwork.TESTNET
    AddMoneyPageFrame(bottomInset) {
        Column(
            Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4).semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetBadge(CircasoIcon.Check)
            Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4).padding(horizontal = CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                BasicText("You're verified", Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
                BasicText(
                    if (testnet) "Your identity is confirmed for Testnet. This was a practice check." else "Your identity is confirmed. There's nothing more to do.",
                    style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                )
            }
        }
        ListGroup {
            FactRow(CircasoIcon.Money, "Add money and cash out", "What being verified unlocks on Circaso")
            RowDivider(Modifier.padding(start = FactInset))
            FactRow(CircasoIcon.Lock, "Your documents stay with Didit", "Our verification partner holds your ID and selfie. Circaso keeps only the result.")
            RowDivider(Modifier.padding(start = FactInset))
            if (testnet) FactRow(CircasoIcon.Network, "Counts on Testnet only", "It used sample documents. Mainnet has its own check, with your real ID.")
            else FactRow(CircasoIcon.Network, "Counts on Mainnet", "Testnet has its own practice check, with sample documents.")
        }
        if (lastChecked != null) FooterNote(lastChecked)
    }
}

/** One thing being verified means: a sign in a quiet disc, a headline and a line under it (E3's own row, in a group). */
@Composable
private fun FactRow(icon: CircasoIcon, title: String, sub: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(CircasoSizes.avatarMd).clip(CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
            CircasoIconImage(icon, c.ink, Modifier.size(FactGlyph), strokeWidth = 1.8f)
        }
        Column(Modifier.weight(1f)) {
            BasicText(title, style = t.body.copy(color = c.ink))
            BasicText(sub, style = t.subhead.copy(color = c.inkSecondary))
        }
    }
}

private val FactGlyph = 20.dp
/** A fact row's divider starts under its text: past the inset, the disc and the gap. */
private val FactInset = CircasoSizes.avatarMd + CircasoSpacing.s3
/** Between a title and its paragraph, as the sheets have it (the canvas's 6 px). */
private val TitleGap = 6.dp

/** A whole-page message with nothing to decide: the sign, a title, one paragraph, and Done under it. */
@Composable
private fun IdentityMessagePage(
    bottomInset: Dp, icon: CircasoIcon, title: String, message: String, onDone: () -> Unit, tone: SheetTone = SheetTone.Neutral, lastChecked: String? = null,
) {
    AddMoneyPageFrame(
        bottomInset,
        footer = {
            CircasoButton("Done", onDone, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
            lastChecked?.let { FooterNote(it) }
        },
    ) {
        Spacer(Modifier.height(CircasoSpacing.s6))
        SheetMessage(title, message, icon, tone = tone)
    }
}

/** A bar on the account or its identity, or the account paused: said as it is, and Support is the way on. */
@Composable
private fun IdentityGatePage(state: IdentityUiState, bottomInset: Dp, onSupport: () -> Unit, onBack: () -> Unit) {
    val barred = state.gate == RampBlock.Barred
    AddMoneyPageFrame(
        bottomInset,
        footer = { CircasoButton("Contact Support", onSupport, size = ButtonSize.Large, block = true) },
    ) {
        Spacer(Modifier.height(CircasoSpacing.s6))
        SheetMessage(
            if (barred) BarredTitle else "Verification is paused on this account",
            if (barred) BarredMessage else "This account can't verify its identity right now. Write to Support and we'll look into it. Sending and receiving aren't affected by this.",
            CircasoIcon.Lock, tone = SheetTone.Warning,
        )
    }
}

/** Nothing on the phone and no answer from the server: why, and a way to ask again. Never a bare error. */
@Composable
private fun IdentityLoadFailedPage(state: IdentityUiState, bottomInset: Dp, onRetry: () -> Unit, onCopy: (String) -> Unit) {
    val offline = !state.online
    AddMoneyPageFrame(
        bottomInset,
        footer = {
            CircasoButton("Try Again", onRetry, size = ButtonSize.Large, block = true, enabled = !offline)
            if (offline) FooterNote("You're offline. This shows by itself when you're back.")
        },
    ) {
        Spacer(Modifier.height(CircasoSpacing.s6))
        SheetMessage(
            if (offline) "You're offline" else "Your verification didn't load",
            if (offline) "Where your verification stands shows here when you're back online. Verifying your identity needs a connection too."
            else state.failure?.message ?: IdentityViewModel.CouldNotLoad,
            if (offline) CircasoIcon.Offline else CircasoIcon.Warning, tone = if (offline) SheetTone.Neutral else SheetTone.Warning,
        )
        if (!offline) state.failure?.reference?.let { Reference(it, onCopy) }
    }
}

/** The first paint when nothing is known yet: the shape of a state's page (sign, title, two lines, a button), breathing. */
@Composable
private fun IdentityLoadingPage(bottomInset: Dp) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Box(Modifier.fillMaxSize().semantics(mergeDescendants = true) { contentDescription = "Checking where your verification stands" }) {
        AddMoneyPageFrame(
            bottomInset,
            footer = { SkeletonBlock(Modifier.fillMaxWidth().height(CircasoSizes.controlLg), CircasoShapes.pill, c.fillStrong) },
        ) {
            Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                SkeletonBlock(Modifier.size(CircasoSizes.sheetBadge), CircasoShapes.circle, c.fillStrong)
                SkeletonLine(t.title2, 0.6f)
                SkeletonLine(t.body, 0.85f)
                SkeletonLine(t.body, 0.7f)
            }
        }
    }
}
