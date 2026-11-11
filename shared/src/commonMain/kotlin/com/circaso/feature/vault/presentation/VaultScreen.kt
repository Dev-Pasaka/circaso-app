package com.circaso.feature.vault.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circaso.core.sync.ConnectionBanner
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
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.vault.domain.VaultContents

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
@Composable
fun VaultContent(state: VaultUiState, onBack: () -> Unit, actions: VaultActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = "PIN Vault", onBack = if (state.step == VaultStep.Edit) actions.onCancelEdit else onBack) },
        banner = { ConnectionBanner() },
        overlay = {
            ConfirmSheet(
                visible = state.confirmOpen,
                title = "Open your vault?",
                message = "Your PIN shows for a minute. Sending money then pauses for 24 hours, and we'll tell you the vault was opened, so your money stays safe if someone else has your phone.",
                confirmLabel = "Open With ${state.unlock}", onConfirm = actions.onOpen, onDismiss = actions.onOpenDismissed,
            )
            ConfirmSheet(
                visible = state.confirmRemove,
                title = "Remove your vault?",
                message = "Your backed-up PIN is deleted. If you forget your PIN after this, only your security questions can bring it back.",
                confirmLabel = "Remove Vault", destructive = true, onConfirm = actions.onRemove, onDismiss = actions.onRemoveDismissed,
            )
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s6),
        ) {
            when (state.step) {
                VaultStep.Loading -> Box(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s10), contentAlignment = Alignment.Center) { Spinner(c.inkSecondary) }
                VaultStep.LoadFailed -> Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                    BasicText(state.error ?: "Couldn't load your vault.", style = t.body.copy(color = c.inkSecondary))
                    CircasoButton("Try Again", actions.onRetry, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = state.online)
                }
                VaultStep.Unsupported -> Hero(
                    "Not on this phone",
                    "The PIN Vault locks your PIN with a passkey, and this phone's passkeys can't do that yet. It works on iPhones with iOS 18 or later and on Android phones with Google Password Manager.",
                )
                VaultStep.Intro -> {
                    Hero(
                        "Never lose your wallet PIN",
                        "Back up your PIN in a vault that only your ${state.unlockInSentence} opens. It's locked with a passkey that syncs with your Apple or Google account, so a new phone opens it too. Circaso keeps it locked and can never read it.",
                    )
                    Section(null) {
                        ListRow("Locked on this phone", sub = "Your PIN is sealed before it leaves the phone", leading = { RowLeading(CircasoIcon.Lock) })
                        RowDivider()
                        ListRow("Opens with ${state.unlock}", sub = "Or your phone's screen lock", leading = { RowLeading(if (state.unlock == "Face ID") CircasoIcon.FaceId else CircasoIcon.Fingerprint) })
                        RowDivider()
                        ListRow("Sending pauses after opening", sub = "24 hours, and we tell you, in case it wasn't you", leading = { RowLeading(CircasoIcon.Shield) })
                    }
                    CircasoButton("Back Up My PIN", actions.onStart, size = ButtonSize.Large, block = true, enabled = state.online)
                }
                VaultStep.Edit -> EditVault(state, actions)
                VaultStep.Saved -> {
                    Hero("Your PIN is in your vault", "Open it any time you forget your PIN. Your ${state.unlockInSentence} unlocks it.")
                    state.updatedAt?.let { Section(null) { DetailRow("Last saved", it.substringBefore('T')) } }
                    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                        CircasoButton("Open My Vault", actions.onOpenRequested, size = ButtonSize.Large, block = true, busy = state.busy, enabled = state.online)
                        CircasoButton("Update PIN or Answers", actions.onStart, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = state.online && !state.busy)
                    }
                    Section(null) { ListRow("Remove vault", destructive = true, onClick = if (state.online && !state.busy) actions.onRemoveRequested else null) }
                }
                VaultStep.Revealed -> state.revealed?.let { Revealed(it, actions.onHide) }
            }
            state.notice?.let { BasicText(it, style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.fillMaxWidth(), ) }
            state.error?.takeIf { state.step != VaultStep.LoadFailed }?.let { BasicText(it, style = t.footnote.copy(color = c.critical)) }
        }
    }
}

@Composable
private fun Hero(title: String, body: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { CircasoIconImage(CircasoIcon.Shield, c.action, Modifier.size(40.dp)) }
        BasicText(title, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(body, style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
}

@Composable
private fun RowLeading(icon: CircasoIcon) = CircasoIconImage(icon, CircasoTheme.colors.ink, Modifier.size(22.dp))

@Composable
private fun EditVault(state: VaultUiState, actions: VaultActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val digits = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
        BasicText("Type the 6-digit PIN you set for your wallet. We can't check it against your wallet, so take care.", style = t.body.copy(color = c.inkSecondary))
        CircasoTextField(state.pin, actions.onPin, label = "Wallet PIN", keyboardOptions = digits, visualTransformation = PasswordVisualTransformation(), secret = true)
        CircasoTextField(
            state.confirmPin, actions.onConfirmPin, label = "Type it again", keyboardOptions = digits, visualTransformation = PasswordVisualTransformation(), secret = true,
            error = if (state.confirmPin.length == VaultUiState.PinLength && !state.pinsMatch) "The two PINs don't match." else null,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        BasicText("Security answers (optional)", style = t.headline.copy(color = c.ink))
        BasicText("Add the questions and answers you gave when you set up your wallet, so you never need to remember them either.", style = t.footnote.copy(color = c.inkSecondary))
        state.answers.forEachIndexed { index, draft ->
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                CircasoTextField(draft.question, { actions.onQuestion(index, it) }, label = "Question ${index + 1}", placeholder = "What was the name of your first school?")
                CircasoTextField(draft.answer, { actions.onAnswer(index, it) }, label = "Answer", secret = true)
                CircasoButton("Remove", { actions.onRemoveAnswer(index) }, style = ButtonStyle.Plain, size = ButtonSize.Small)
            }
        }
        if (state.answers.size < VaultViewModel.MaxAnswers) CircasoButton("Add an Answer", actions.onAddAnswer, style = ButtonStyle.Secondary, size = ButtonSize.Small)
    }
    CircasoButton("Save With ${state.unlock}", actions.onSave, size = ButtonSize.Large, block = true, busy = state.busy, enabled = state.canSave)
}

@Composable
private fun Revealed(contents: VaultContents, onHide: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    NoScreenCapture()
    Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        BasicText("Your wallet PIN", style = t.subhead.copy(color = c.inkSecondary))
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            contents.pin.forEach { d -> BasicText(d.toString(), style = t.largeTitle.copy(color = c.ink, fontFamily = FontFamily.Monospace, fontSize = 40.sp)) }
        }
        BasicText("Hides itself in a minute. Sending is paused for 24 hours.", style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
    if (contents.answers.isNotEmpty()) {
        Section("Security answers") {
            contents.answers.forEachIndexed { i, a ->
                if (i > 0) RowDivider()
                ListRow(a.question, sub = a.answer)
            }
        }
    }
    CircasoButton("Hide", onHide, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
}

/** The Security screen's row for the vault, under Payments; hidden on phones without PRF passkeys. */
@Composable
fun VaultRow(supported: Boolean, saved: Boolean?, unlock: String, onOpen: () -> Unit) {
    if (!supported) return
    Section("PIN backup") {
        ListRow(
            "PIN Vault",
            sub = when (saved) {
                true -> "Backed up · $unlock opens it"
                false -> "Back up your wallet PIN with $unlock"
                null -> "Back up your wallet PIN"
            },
            meta = if (saved == true) "On" else null,
            leading = { RowLeading(CircasoIcon.Shield) },
            trailing = { RowChevron() },
            onClick = onOpen,
        )
    }
}
