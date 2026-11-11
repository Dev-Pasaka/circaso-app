package com.circaso.feature.vault.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
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
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.DetailRow
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.Section
import com.circaso.designsystem.components.SecretOnScreen
import com.circaso.designsystem.components.SkeletonBlock
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.vault.domain.VaultContents
import kotlinx.coroutines.delay

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

/** The PIN Vault (2026-11-11): back up the Circle wallet PIN under a passkey, and open it when the PIN is forgotten. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun VaultContent(state: VaultUiState, onBack: () -> Unit, actions: VaultActions) {
    val reduceMotion = CircasoTheme.reduceMotion
    // System back on the form returns to the vault, as the nav bar's back does.
    BackHandler(enabled = state.step == VaultStep.Edit, onBack = actions.onCancelEdit)
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = "PIN Vault", onBack = if (state.step == VaultStep.Edit) actions.onCancelEdit else onBack) },
        banner = { ConnectionBanner() },
        overlay = {
            ConfirmSheet(
                visible = state.confirmOpen,
                title = "Open your vault?",
                message = "Your PIN shows for a minute. Sending then pauses for 24 hours, and we'll tell you, in case it isn't you.",
                confirmLabel = "Open With ${state.unlock}", onConfirm = actions.onOpen, onDismiss = actions.onOpenDismissed,
            )
            ConfirmSheet(
                visible = state.confirmRemove,
                title = "Remove your vault?",
                message = "Your backed-up PIN and answers are deleted. If you forget your PIN after this, only your wallet's security questions can reset it.",
                confirmLabel = "Remove Vault", destructive = true, onConfirm = actions.onRemove, onDismiss = actions.onRemoveDismissed,
            )
        },
    ) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                if (reduceMotion) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else (fadeIn(tween(CircasoMotion.BaseMs)) + slideInVertically(tween(CircasoMotion.BaseMs)) { 8 }) togetherWith fadeOut(tween(CircasoMotion.ExitMs))
            },
            label = "vault step",
        ) { step ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
                verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s6),
            ) {
                when (step) {
                    VaultStep.Loading -> LoadingVault()
                    VaultStep.LoadFailed -> Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                        Hero(CircasoIcon.Offline, "Couldn't load your vault", "It tries again by itself when you're back online.", dim = true)
                        CircasoButton("Try Again", actions.onRetry, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = state.online)
                        ErrorLine(state)
                    }
                    VaultStep.Unsupported -> Hero(
                        CircasoIcon.Shield, "Not on this phone",
                        "The PIN Vault locks your PIN with a passkey, and this phone's passkeys can't do that yet. It works on iPhones with iOS 18 or later and on Android phones with Google Password Manager.",
                        dim = true,
                    )
                    VaultStep.Intro -> {
                        Hero(
                            CircasoIcon.Shield, "Never lose your wallet PIN",
                            "Back up your PIN in a vault that opens with your ${state.unlockInSentence}. It's locked with a passkey that syncs with your Apple or Google account, so a new phone opens it too. Circaso can never read it.",
                        )
                        Section(null) {
                            ListRow("Locked on this phone", sub = "Your PIN is sealed before it leaves the phone", leading = { RowLeading(CircasoIcon.Lock) })
                            RowDivider()
                            ListRow("Opens with ${state.unlock}", sub = "Or your phone's screen lock", leading = { RowLeading(if (state.unlock == "Face ID") CircasoIcon.FaceId else CircasoIcon.Fingerprint) })
                            RowDivider()
                            ListRow("Sending pauses after opening", sub = "For 24 hours, and we tell you, in case it wasn't you", leading = { RowLeading(CircasoIcon.Clock) })
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                            CircasoButton("Back Up My PIN", actions.onStart, size = ButtonSize.Large, block = true, enabled = state.online)
                            OfflineLine(state, "Backing up needs a connection.")
                            ErrorLine(state)
                        }
                    }
                    VaultStep.Edit -> EditVault(state, actions)
                    VaultStep.Saved -> {
                        Hero(CircasoIcon.Lock, "Your PIN is in your vault", "Open it any time you forget your PIN. Your ${state.unlockInSentence} unlocks it.")
                        state.updatedAt?.let { Section(null) { DetailRow("Last saved", it.substringBefore('T')) } }
                        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                            CircasoButton("Open My Vault", actions.onOpenRequested, size = ButtonSize.Large, block = true, busy = state.busy == VaultBusy.Open, enabled = state.online && state.busy == null)
                            CircasoButton("Update PIN or Answers", actions.onStart, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = state.online && state.busy == null)
                            OfflineLine(state, "Opening or changing your vault needs a connection.")
                            ErrorLine(state)
                        }
                        Section(null) {
                            ListRow(
                                "Remove vault", destructive = true,
                                trailing = if (state.busy == VaultBusy.Remove) ({ Spinner(CircasoTheme.colors.inkSecondary) }) else null,
                                onClick = if (state.online && state.busy == null) actions.onRemoveRequested else null,
                            )
                        }
                    }
                    VaultStep.Revealed -> state.revealed?.let { Revealed(it, state.hidesAt, actions.onHide) }
                }
            }
        }
    }
}

@Composable
private fun LoadingVault() {
    Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        SkeletonBlock(Modifier.size(56.dp), shape = CircasoShapes.circle)
        SkeletonBlock(Modifier.fillMaxWidth(0.6f).height(24.dp))
        SkeletonBlock(Modifier.fillMaxWidth().height(64.dp))
    }
}

@Composable
private fun Hero(icon: CircasoIcon, title: String, body: String, dim: Boolean = false) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        Box(Modifier.size(56.dp).background(c.fill, CircasoShapes.circle), contentAlignment = Alignment.Center) {
            CircasoIconImage(icon, if (dim) c.inkTertiary else c.action, Modifier.size(28.dp))
        }
        BasicText(title, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(body, style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
}

@Composable
private fun RowLeading(icon: CircasoIcon) = CircasoIconImage(icon, CircasoTheme.colors.ink, Modifier.size(22.dp))

@Composable
private fun OfflineLine(state: VaultUiState, text: String) {
    AnimatedVisibility(!state.online) { BasicText(text, style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary)) }
}

/** The error under the control that failed, with the request id to quote to support; and any notice. */
@Composable
private fun ErrorLine(state: VaultUiState) {
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

@Composable
private fun EditVault(state: VaultUiState, actions: VaultActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val digits = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
        BasicText("Type the 6-digit PIN you set for your wallet. We can't check it against your wallet, so take care.", style = t.body.copy(color = c.inkSecondary))
        CircasoTextField(state.pin, actions.onPin, label = "Wallet PIN", keyboardOptions = digits, secret = true)
        CircasoTextField(
            state.confirmPin, actions.onConfirmPin, label = "Confirm PIN", keyboardOptions = digits, secret = true,
            error = if (state.confirmPin.length == VaultUiState.PinLength && !state.pinsMatch) "The two PINs don't match." else null,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        BasicText("Security answers (optional)", style = t.headline.copy(color = c.ink))
        BasicText("Add the questions and answers you gave when you set up your wallet, so you never need to remember them either.", style = t.footnote.copy(color = c.inkSecondary))
        state.answers.forEachIndexed { index, draft ->
            key(draft.id) {
                Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    CircasoTextField(draft.question, { actions.onQuestion(draft.id, it) }, label = "Question ${index + 1}", placeholder = "What was the name of your first school?")
                    // Not masked, so it can be checked; the PIN fields above already keep this screen from Asha.
                    CircasoTextField(
                        draft.answer, { actions.onAnswer(draft.id, it) }, label = "Answer",
                        error = if (draft.incomplete) (if (draft.answer.isBlank()) "Add the answer, or clear the question." else "Add the question, or clear the answer.") else null,
                    )
                    CircasoButton("Remove Answer ${index + 1}", { actions.onRemoveAnswer(draft.id) }, style = ButtonStyle.Plain, size = ButtonSize.Medium)
                }
            }
        }
        AnimatedVisibility(state.answers.size < VaultViewModel.MaxAnswers, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            CircasoButton("Add an Answer", actions.onAddAnswer, style = ButtonStyle.Secondary, size = ButtonSize.Medium)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        CircasoButton("Save With ${state.unlock}", actions.onSave, size = ButtonSize.Large, block = true, busy = state.busy == VaultBusy.Save, enabled = state.canSave)
        OfflineLine(state, "Backing up needs a connection.")
        ErrorLine(state)
    }
}

@Composable
private fun Revealed(contents: VaultContents, hidesAt: Long?, onHide: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
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
    Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        BasicText("Your wallet PIN", style = t.subhead.copy(color = c.inkSecondary))
        Row(
            Modifier.semantics(mergeDescendants = true) { contentDescription = "Your wallet PIN: " + contents.pin.toList().joinToString(" ") },
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
        ) {
            contents.pin.chunked(3).forEach { group ->
                Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    group.forEach { d -> BasicText(d.toString(), style = t.amountLarge.copy(color = c.ink)) }
                }
            }
        }
        BasicText(
            "Hides in 0:${secondsLeft.toString().padStart(2, '0')}. Sending is paused for 24 hours.",
            style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center, fontFeatureSettings = "tnum"),
        )
    }
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
    CircasoButton("Hide", onHide, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
}

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
