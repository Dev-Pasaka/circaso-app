package com.circaso.feature.vault.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.sync.ConnectionBanner
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.Section
import com.circaso.designsystem.components.SecretOnScreen
import com.circaso.designsystem.components.SkeletonBlock
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.platform.PlatformStyle
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.vault.domain.VaultContents
import com.circaso.feature.wallet.presentation.CardRatio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VaultScreen(viewModel: VaultViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    VaultContent(
        state, onBack,
        VaultActions(
            onStart = viewModel::onStart, onCancelEdit = viewModel::onCancelEdit, onPin = viewModel::onPin, onConfirmPin = viewModel::onConfirmPin,
            onAddAnswer = viewModel::onAddAnswer, onRemoveAnswer = viewModel::onRemoveAnswer, onQuestion = viewModel::onQuestion, onAnswer = viewModel::onAnswer,
            onSave = viewModel::onSave, onOpenRequested = viewModel::onOpenRequested, onOpenDismissed = viewModel::onOpenDismissed, onOpen = viewModel::onOpen,
            onHide = viewModel::onHide, onRemoveRequested = viewModel::onRemoveRequested, onRemoveDismissed = viewModel::onRemoveDismissed,
            onRemove = viewModel::onRemove, onRetry = viewModel::load,
        ),
    )
}

class VaultActions(
    val onStart: () -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val onPin: (String) -> Unit = {},
    val onConfirmPin: (String) -> Unit = {},
    val onAddAnswer: () -> Unit = {},
    val onRemoveAnswer: (Int) -> Unit = {},
    val onQuestion: (Int, String) -> Unit = { _, _ -> },
    val onAnswer: (Int, String) -> Unit = { _, _ -> },
    val onSave: () -> Unit = {},
    val onOpenRequested: () -> Unit = {},
    val onOpenDismissed: () -> Unit = {},
    val onOpen: () -> Unit = {},
    val onHide: () -> Unit = {},
    val onRemoveRequested: () -> Unit = {},
    val onRemoveDismissed: () -> Unit = {},
    val onRemove: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The PIN Vault (2026-11-11): back up the Circle wallet PIN under a passkey, and open it when the PIN is forgotten.
 * Redesigned the same day (Pasaka: "more professional, more premium"): a metal vault in the money cards' finish
 * carries the screen; the intro makes one promise and backs it with three points; backing up is a real PIN entry;
 * the shown PIN is a quiet moment with a countdown ring.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun VaultContent(state: VaultUiState, onBack: () -> Unit, actions: VaultActions) {
    val reduceMotion = CircasoTheme.reduceMotion
    val editing = state.step == VaultStep.Edit
    val rise = with(LocalDensity.current) { 12.dp.roundToPx() }
    // Back on the form steps back one pass (system back and the nav bar's alike), and waits while a save runs.
    val back = { editBack(state, actions) }
    BackHandler(enabled = editing, onBack = back)
    // The vault seals (its dots press in) only when a save has just finished: not on opening the screen, and not on
    // backing out of an update, which saved nothing.
    val lastBusy = remember { arrayOf(state.busy) }
    val justSaved = remember(state.step) { state.step == VaultStep.Saved && lastBusy[0] == VaultBusy.Save }
    SideEffect { lastBusy[0] = state.busy }
    CircasoScaffold(
        grouped = true,
        topBar = {
            CircasoNavBar(
                title = if (!editing) "PIN Vault" else if (state.updating) "Update Vault" else "Back Up PIN",
                onBack = if (editing) back else onBack,
            )
        },
        banner = { ConnectionBanner() },
        overlay = {
            ConfirmSheet(
                visible = state.confirmOpen,
                title = "Open your vault?",
                message = "Opening pauses sending from your wallet for 24 hours. Your PIN shows for one minute, and we'll let you know it was opened, in case it wasn't you.",
                confirmLabel = "Open With ${state.unlock}", icon = com.circaso.designsystem.components.CircasoIcon.Lock, tone = com.circaso.designsystem.chrome.SheetTone.Warning, onConfirm = actions.onOpen, onDismiss = actions.onOpenDismissed,
            )
            ConfirmSheet(
                visible = state.confirmRemove,
                title = "Remove your vault?",
                message = "Your backed-up PIN and answers are deleted. If you forget your PIN after this, only your wallet's security questions can reset it.",
                confirmLabel = "Remove Vault", destructive = true, icon = com.circaso.designsystem.components.CircasoIcon.Trash, onConfirm = actions.onRemove, onDismiss = actions.onRemoveDismissed,
            )
        },
    ) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                // A shown PIN is gone on the next frame, never mid-fade in an app-switcher snapshot.
                val exit = if (initialState == VaultStep.Revealed) ExitTransition.None else fadeOut(tween(CircasoMotion.ExitMs))
                if (reduceMotion) fadeIn(tween(CircasoMotion.QuickMs)) togetherWith exit
                else (fadeIn(tween(CircasoMotion.BaseMs)) + slideInVertically(tween(CircasoMotion.BaseMs)) { rise }) togetherWith exit
            },
            label = "vault step",
        ) { step ->
            if (step == VaultStep.Edit) EditVault(state, actions)
            else Page {
                when (step) {
                    VaultStep.Loading -> LoadingVault()
                    VaultStep.LoadFailed -> {
                        if (state.online) Hero(CircasoIcon.Warning, "Couldn't load your vault", "Try again in a moment.")
                        else Hero(CircasoIcon.Offline, "Couldn't load your vault", "It tries again by itself when you're back online.")
                        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                            CircasoButton("Try Again", actions.onRetry, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = state.online)
                            ErrorLine(state)
                        }
                    }
                    VaultStep.Unsupported -> Hero(
                        CircasoIcon.Shield, "Not on this phone",
                        "The PIN Vault locks your PIN with a passkey, and this phone's passkeys can't do that yet. It works on iPhones with iOS 18 or later and on Android phones with Google Password Manager.",
                    )
                    VaultStep.Intro -> Intro(state, actions)
                    VaultStep.Saved -> Saved(state, actions, justSaved)
                    VaultStep.Revealed -> state.revealed?.let { Revealed(it, state.hidesAt, actions.onHide) }
                    VaultStep.Edit -> Unit
                }
            }
        }
    }
}

/** A scrolling page with the screen margins. */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s6),
    ) { content() }
}

/** One promise, three points that back it, one button. */
@Composable
private fun Intro(state: VaultUiState, actions: VaultActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        VaultEmblem(88.dp, Modifier.padding(bottom = CircasoSpacing.s3))
        BasicText("Never lose your wallet PIN", Modifier.semantics { heading() }, style = t.title1.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(
            "Keep a copy of your PIN in a vault that only your ${state.unlockInSentence} opens.",
            style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
        )
    }
    Column(
        Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface).padding(CircasoSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5),
    ) {
        Benefit(CircasoIcon.Lock, "Sealed on your phone", "Your PIN is locked before it leaves this phone. Circaso can never read it.")
        Benefit(unlockIcon(state.unlock), "Opens with your ${state.unlockInSentence}", "Your passkey syncs with your ${if (CircasoTheme.platform == PlatformStyle.Ios) "Apple" else "Google"} account, so a new phone opens it too.")
        Benefit(CircasoIcon.Clock, "Sending pauses after opening", "For 24 hours, and we let you know, in case it wasn't you.")
    }
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        CircasoButton("Back Up My PIN", actions.onStart, size = ButtonSize.Large, block = true, enabled = state.online)
        OfflineLine(state, "Backing up needs a connection.")
        ErrorLine(state)
    }
}

@Composable
private fun Benefit(icon: CircasoIcon, title: String, body: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = t.headline.copy(color = c.ink))
            BasicText(body, style = t.footnote.copy(color = c.inkSecondary))
        }
    }
}

/** The vault card, the actions, and what opening costs. */
@Composable
private fun Saved(state: VaultUiState, actions: VaultActions, justSaved: Boolean) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    VaultCard(state.unlock, state.updatedAt?.let(::vaultDate), sealing = justSaved, modifier = Modifier.padding(top = CircasoSpacing.s4))
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        CircasoButton(
            "Open My Vault", actions.onOpenRequested, size = ButtonSize.Large, block = true, icon = unlockIcon(state.unlock),
            busy = state.busy == VaultBusy.Open, enabled = state.online && state.busy == null,
        )
        CircasoButton("Update PIN or Answers", actions.onStart, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = state.online && state.busy == null)
        OfflineLine(state, "Opening, changing or removing your vault needs a connection.")
        ErrorLine(state)
        Note(CircasoIcon.Info, "Opening shows your PIN for one minute and pauses sending for 24 hours.")
    }
    Section(null) {
        ListRow(
            "Remove vault", destructive = true,
            trailing = if (state.busy == VaultBusy.Remove) ({ Spinner(CircasoTheme.colors.inkSecondary) }) else null,
            onClick = if (state.online && state.busy == null) actions.onRemoveRequested else null,
        )
    }
}

/** A small icon and a footnote: a fact worth knowing, not a warning. */
@Composable
private fun Note(icon: CircasoIcon, text: String) {
    val c = CircasoTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s1), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        CircasoIconImage(icon, c.inkSecondary, Modifier.padding(top = 1.dp).size(16.dp))
        BasicText(text, style = CircasoTheme.typography.footnote.copy(color = c.inkSecondary))
    }
}

/** The saved page's shape (card, two buttons), so nothing moves when it arrives. */
@Composable
private fun LoadingVault() {
    Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            SkeletonBlock(Modifier.fillMaxWidth().height(maxWidth / CardRatio), shape = CircasoShapes.lg)
        }
        SkeletonBlock(Modifier.padding(top = CircasoSpacing.s3).fillMaxWidth().height(CircasoSizes.controlLg), shape = CircasoShapes.pill)
        SkeletonBlock(Modifier.fillMaxWidth().height(CircasoSizes.controlLg), shape = CircasoShapes.pill)
    }
}

/** The states with nothing to act on in the vault itself. */
@Composable
private fun Hero(icon: CircasoIcon, title: String, body: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s6), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        Box(Modifier.size(64.dp).background(c.fill, CircasoShapes.circle), contentAlignment = Alignment.Center) {
            CircasoIconImage(icon, c.inkSecondary, Modifier.size(28.dp))
        }
        BasicText(title, Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(body, style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
}

@Composable
internal fun OfflineLine(state: VaultUiState, text: String) {
    AnimatedVisibility(!state.online) { BasicText(text, style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary)) }
}

/** The error under the control that failed, with the request id to quote to support; and any notice. */
@Composable
internal fun ErrorLine(state: VaultUiState) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    AnimatedVisibility(state.notice != null) { BasicText(state.notice.orEmpty(), style = t.footnote.copy(color = c.inkSecondary)) }
    AnimatedVisibility(state.error != null) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(state.error.orEmpty(), style = t.footnote.copy(color = c.critical))
            state.errorRef?.let { BasicText("Ref $it", style = t.caption1.copy(color = c.inkTertiary)) }
        }
    }
}

/**
 * The shown PIN: a quiet page with the digits large in two threes, a ring that empties over the minute, and the
 * security answers in full under them. Never captured, never shown to Asha, and hidden as soon as the app leaves.
 */
@Composable
private fun Revealed(contents: VaultContents, hidesAt: Long?, onHide: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val still = CircasoTheme.reduceMotion
    NoScreenCapture()
    SecretOnScreen() // and never shown to Asha
    // Leaving the screen or the app hides it, so it isn't left in the app switcher's snapshot.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) onHide()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); onHide() }
    }
    val secondsLeft by produceState(initialValue = secondsUntil(hidesAt), hidesAt) {
        while (true) { value = secondsUntil(hidesAt); delay(1_000) }
    }
    // The digits settle in one after another, quickly; all at once under reduced motion.
    val shown = remember { List(contents.pin.length) { Animatable(if (still) 1f else 0f) } }
    if (!still) LaunchedEffect(Unit) {
        shown.forEachIndexed { i, a -> launch { delay(i * DigitStaggerMs); a.animateTo(1f, tween(CircasoMotion.BaseMs)) } }
    }
    Column(
        Modifier.fillMaxWidth().padding(top = CircasoSpacing.s2).clip(CircasoShapes.lg).background(c.surface)
            .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s8),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5),
    ) {
        // Read once, digit by digit, as one item: the label and the six digits are a single node for screen readers.
        Column(
            Modifier.clearAndSetSemantics { contentDescription = "Your wallet PIN: " + contents.pin.toList().joinToString(" ") },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5),
        ) {
            BasicText("Your wallet PIN", style = t.subhead.copy(color = c.inkSecondary))
            // At the largest text sizes the two threes stack rather than run off a narrow phone.
            val stacked = LocalDensity.current.fontScale > 1.5f
            val groups: @Composable () -> Unit = {
                contents.pin.chunked(3).forEachIndexed { g, group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                        group.forEachIndexed { j, d ->
                            val a = shown[g * 3 + j]
                            BasicText(
                                d.toString(),
                                Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 6.dp.toPx() },
                                style = t.amountLarge.copy(color = c.ink),
                            )
                        }
                    }
                }
            }
            if (stacked) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) { groups() }
            else Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s6)) { groups() }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            CountdownRing(hidesAt, VaultViewModel.RevealMillis)
            BasicText("Hides in 0:${secondsLeft.toString().padStart(2, '0')}", style = t.footnote.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"))
        }
    }
    Note(CircasoIcon.Clock, "Sending is paused for 24 hours, because your vault was opened.")
    if (contents.answers.isNotEmpty()) {
        Section("Security answers") {
            contents.answers.forEachIndexed { i, a ->
                if (i > 0) RowDivider()
                // Never clipped: this screen's whole job is to show them.
                Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(a.question, style = t.subhead.copy(color = c.inkSecondary))
                    BasicText(a.answer, style = t.body.copy(color = c.ink))
                }
            }
        }
    }
    CircasoButton("Hide Now", onHide, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, icon = CircasoIcon.EyeOff)
}

private const val DigitStaggerMs = 35L

private fun secondsUntil(at: Long?): Int = at?.let { ((it - nowMillis()).coerceAtLeast(0) / 1_000).toInt().coerceAtMost(59) } ?: 0

/**
 * The Security screen's "Wallet PIN" section, under Payments: shown only with an active wallet on a phone with PRF
 * passkeys, from the vault's last known status (so it matches the vault screen and doesn't jump while loading).
 */
@Composable
fun VaultRow(repository: com.circaso.feature.vault.data.VaultRepository, hasWallet: Boolean, unlock: String, onOpen: () -> Unit) {
    val supported by produceState<Boolean?>(null) { value = repository.supported() }
    val known by repository.known.collectAsState()
    LaunchedEffect(repository, supported) { if (supported == true) { repository.warm(); repository.status() } }
    if (supported != true || !hasWallet) return
    Section("Wallet PIN") {
        ListRow(
            "PIN Vault",
            sub = when (known?.exists) {
                true -> "Backed up · $unlock opens it"
                else -> "Back up your PIN with $unlock"
            },
            meta = if (known?.exists == true) "On" else null,
            trailing = { RowChevron() },
            onClick = onOpen,
        )
    }
}
