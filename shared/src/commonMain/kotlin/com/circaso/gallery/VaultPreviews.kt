package com.circaso.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.circaso.core.sync.nowMillis
import com.circaso.feature.vault.domain.SecurityAnswer
import com.circaso.feature.vault.domain.VaultContents
import com.circaso.feature.vault.presentation.AnswerDraft
import com.circaso.feature.vault.presentation.VaultActions
import com.circaso.feature.vault.presentation.VaultContent
import com.circaso.feature.vault.presentation.VaultStep
import com.circaso.feature.vault.presentation.VaultUiState
import com.circaso.feature.vault.presentation.VaultViewModel

/**
 * The PIN Vault's steps (`-preview vault-…`), one per state. `vault-live` is a working walk-through with no server:
 * type a PIN twice, add answers, save, open and hide.
 */
@Composable
internal fun VaultPreview(name: String): Boolean {
    if (!name.startsWith("vault-")) return false
    if (name == "vault-live") { LiveVault(); return true }
    val face = VaultUiState(unlock = "Face ID", updatedAt = "2026-11-11T09:30:00Z")
    val state = when (name) {
        "vault-loading" -> VaultUiState()
        "vault-unsupported" -> VaultUiState(step = VaultStep.Unsupported)
        "vault-failed" -> VaultUiState(step = VaultStep.LoadFailed, online = false, error = "You're offline. Check your connection.", errorRef = "req_7Hq2")
        "vault-intro" -> VaultUiState(step = VaultStep.Intro)
        "vault-intro-faceid" -> VaultUiState(step = VaultStep.Intro, unlock = "Face ID")
        "vault-intro-offline" -> VaultUiState(step = VaultStep.Intro, online = false)
        "vault-pin" -> VaultUiState(step = VaultStep.Edit, pin = "382")
        "vault-pin-again" -> VaultUiState(step = VaultStep.Edit, pin = "382910", confirmPin = "38")
        "vault-pin-mismatch" -> VaultUiState(step = VaultStep.Edit, pin = "382910", confirmPin = "382911")
        "vault-review" -> VaultUiState(step = VaultStep.Edit, pin = "382910", confirmPin = "382910")
        "vault-review-answers" -> VaultUiState(
            step = VaultStep.Edit, pin = "382910", confirmPin = "382910",
            answers = listOf(AnswerDraft(0, "What was the name of your first school?", "Kilimani Primary"), AnswerDraft(1, "Your mother's maiden name?", "")),
        )
        "vault-review-error" -> VaultUiState(step = VaultStep.Edit, pin = "382910", confirmPin = "382910", error = "Your passkey didn't answer. Try again.", errorRef = "TYPE_NO_CREDENTIAL")
        "vault-review-saving" -> VaultUiState(step = VaultStep.Edit, pin = "382910", confirmPin = "382910", busy = com.circaso.feature.vault.presentation.VaultBusy.Save)
        "vault-review-offline" -> VaultUiState(step = VaultStep.Edit, pin = "382910", confirmPin = "382910", online = false)
        // Replacing a saved vault: the page says the saved answers will be replaced.
        "vault-updating" -> face.copy(step = VaultStep.Edit, pin = "382910", confirmPin = "382910")
        "vault-saved" -> face.copy(step = VaultStep.Saved)
        "vault-saved-removing" -> face.copy(step = VaultStep.Saved, busy = com.circaso.feature.vault.presentation.VaultBusy.Remove)
        "vault-saved-fingerprint" -> face.copy(step = VaultStep.Saved, unlock = "Fingerprint")
        "vault-saved-offline" -> face.copy(step = VaultStep.Saved, online = false)
        "vault-saved-error" -> face.copy(step = VaultStep.Saved, error = "You can open your vault 5 times an hour. Try again a little later.")
        "vault-saved-opening" -> face.copy(step = VaultStep.Saved, busy = com.circaso.feature.vault.presentation.VaultBusy.Open)
        "vault-open-sheet" -> face.copy(step = VaultStep.Saved, confirmOpen = true)
        "vault-revealed" -> face.copy(
            step = VaultStep.Revealed, hidesAt = nowMillis() + VaultViewModel.RevealMillis,
            revealed = VaultContents("382910", listOf(SecurityAnswer("What was the name of your first school?", "Kilimani Primary"), SecurityAnswer("Your favourite food as a child?", "Mukimo with a lot of pumpkin leaves, the way my grandmother made it in Nyeri"))),
        )
        else -> return false
    }
    // Typing works on the PIN steps, so the passes can be walked through by hand.
    var s by remember(name) { mutableStateOf(state) }
    VaultContent(s, {}, VaultActions(onPin = { s = s.copy(pin = it) }, onConfirmPin = { s = s.copy(confirmPin = it) }))
    return true
}

/** The whole flow without a server: the view model's moves, done locally. */
@Composable
private fun LiveVault() {
    var s by remember { mutableStateOf(VaultUiState(step = VaultStep.Intro, unlock = "Face ID")) }
    var next by remember { mutableStateOf(0) }
    VaultContent(
        s, {},
        VaultActions(
            onStart = { s = s.copy(step = VaultStep.Edit, pin = "", confirmPin = "", answers = emptyList()) },
            onCancelEdit = { s = s.copy(step = if (s.updatedAt != null) VaultStep.Saved else VaultStep.Intro, pin = "", confirmPin = "", answers = emptyList()) },
            onPin = { s = s.copy(pin = it) },
            onConfirmPin = { s = s.copy(confirmPin = it) },
            onAddAnswer = { s = s.copy(answers = s.answers + AnswerDraft(next++)) },
            onRemoveAnswer = { id -> s = s.copy(answers = s.answers.filterNot { it.id == id }) },
            onQuestion = { id, v -> s = s.copy(answers = s.answers.map { if (it.id == id) it.copy(question = v) else it }) },
            onAnswer = { id, v -> s = s.copy(answers = s.answers.map { if (it.id == id) it.copy(answer = v) else it }) },
            onSave = {
                s = s.copy(
                    step = VaultStep.Saved, updatedAt = "2026-11-11T09:30:00Z", pin = "", confirmPin = "",
                    revealed = VaultContents(s.pin, s.answers.filter { it.question.isNotBlank() }.map { SecurityAnswer(it.question, it.answer) }), answers = emptyList(),
                )
            },
            onOpenRequested = { s = s.copy(confirmOpen = true) },
            onOpenDismissed = { s = s.copy(confirmOpen = false) },
            onOpen = { s = s.copy(confirmOpen = false, step = VaultStep.Revealed, hidesAt = nowMillis() + VaultViewModel.RevealMillis) },
            onHide = { if (s.step == VaultStep.Revealed) s = s.copy(step = VaultStep.Saved, hidesAt = null) },
            onRemoveRequested = { s = s.copy(confirmRemove = true) },
            onRemoveDismissed = { s = s.copy(confirmRemove = false) },
            onRemove = { s = VaultUiState(step = VaultStep.Intro, unlock = "Face ID") },
        ),
    )
}
