package com.circaso.feature.vault.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.Biometrics
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.profile.domain.ProfileRepository
import com.circaso.feature.vault.data.VaultOutcome
import com.circaso.feature.vault.data.VaultProblem
import com.circaso.feature.vault.data.VaultRepository
import com.circaso.feature.vault.domain.SecurityAnswer
import com.circaso.feature.vault.domain.VaultContents
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which part of the vault screen shows. */
enum class VaultStep { Loading, Unsupported, Intro, Edit, Saved, Revealed, LoadFailed }

data class AnswerDraft(val question: String = "", val answer: String = "")

data class VaultUiState(
    val step: VaultStep = VaultStep.Loading,
    val online: Boolean = true,
    val busy: Boolean = false,
    /** "Face ID", "Touch ID" or "Fingerprint", for the words on screen. */
    val unlock: String = "Fingerprint",
    val updatedAt: String? = null,
    val pin: String = "",
    val confirmPin: String = "",
    val answers: List<AnswerDraft> = emptyList(),
    val revealed: VaultContents? = null,
    val confirmOpen: Boolean = false,
    val confirmRemove: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val pinValid get() = pin.length == PinLength && pin.all { it.isDigit() }
    val pinsMatch get() = pin == confirmPin
    val canSave get() = pinValid && pinsMatch && online && !busy && answers.all { (it.question.isBlank()) == (it.answer.isBlank()) }
    val unlockInSentence get() = if (unlock.endsWith(" ID")) unlock else unlock.lowercase()

    companion object { const val PinLength = 6 }
}

/**
 * The PIN Vault (2026-11-11). Circle's PIN screen is Circle's own, so Circaso never sees the PIN: the person types it
 * here once to back it up. Opening the vault later shows it for [RevealMillis], then hides it again; the server pauses
 * sending for 24 hours after every opening and tells the person, in case it wasn't them.
 */
class VaultViewModel(
    private val vault: VaultRepository,
    private val profile: ProfileRepository,
    connection: ConnectionState,
    biometrics: Biometrics? = null,
) : ViewModel() {
    private val local = MutableStateFlow(VaultUiState(unlock = biometrics?.name ?: "Fingerprint"))
    private var hideJob: Job? = null

    val state: StateFlow<VaultUiState> = combine(local, connection.status) { ui, status -> ui.copy(online = status == ConnectionStatus.Online) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init { load() }

    fun load() {
        local.update { it.copy(step = VaultStep.Loading, error = null) }
        viewModelScope.launch {
            if (!vault.supported()) { local.update { it.copy(step = VaultStep.Unsupported) }; return@launch }
            when (val r = vault.status()) {
                is AppResult.Success -> local.update { it.copy(step = if (r.value.exists) VaultStep.Saved else VaultStep.Intro, updatedAt = r.value.updatedAt) }
                is AppResult.Failure -> local.update { it.copy(step = VaultStep.LoadFailed, error = ErrorMessages.forError(r.error)) }
            }
        }
    }

    fun onStart() = local.update { it.copy(step = VaultStep.Edit, pin = "", confirmPin = "", answers = emptyList(), error = null, notice = null) }
    fun onCancelEdit() = local.update { it.copy(step = if (it.updatedAt != null) VaultStep.Saved else VaultStep.Intro, pin = "", confirmPin = "", answers = emptyList(), error = null) }
    fun onPin(value: String) = local.update { it.copy(pin = value.filter(Char::isDigit).take(VaultUiState.PinLength), error = null) }
    fun onConfirmPin(value: String) = local.update { it.copy(confirmPin = value.filter(Char::isDigit).take(VaultUiState.PinLength), error = null) }
    fun onAddAnswer() = local.update { if (it.answers.size >= MaxAnswers) it else it.copy(answers = it.answers + AnswerDraft()) }
    fun onRemoveAnswer(index: Int) = local.update { it.copy(answers = it.answers.filterIndexed { i, _ -> i != index }) }
    fun onQuestion(index: Int, value: String) = local.update { s -> s.copy(answers = s.answers.mapIndexed { i, a -> if (i == index) a.copy(question = value.take(MaxText)) else a }) }
    fun onAnswer(index: Int, value: String) = local.update { s -> s.copy(answers = s.answers.mapIndexed { i, a -> if (i == index) a.copy(answer = value.take(MaxText)) else a }) }

    fun onSave() {
        val now = state.value
        if (!now.canSave) {
            if (now.pinValid && !now.pinsMatch) local.update { it.copy(error = "The two PINs don't match.") }
            return
        }
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val me = profile.profile.first()
            val contents = VaultContents(now.pin, now.answers.filter { it.question.isNotBlank() }.map { SecurityAnswer(it.question.trim(), it.answer.trim()) })
            val label = me?.handle?.let { "@$it" } ?: me?.displayName ?: "Circaso"
            when (val r = vault.save(contents, handle = label, displayName = "${me?.displayName ?: "Circaso"} · PIN Vault")) {
                is VaultOutcome.Done -> {
                    local.update { it.copy(busy = false, step = VaultStep.Saved, pin = "", confirmPin = "", answers = emptyList(), notice = "Your PIN is in your vault.") }
                    refreshStatus()
                }
                is VaultOutcome.Problem -> local.update { it.copy(busy = false, error = message(r.problem)) }
            }
        }
    }

    fun onOpenRequested() = local.update { it.copy(confirmOpen = true, error = null, notice = null) }
    fun onOpenDismissed() = local.update { it.copy(confirmOpen = false) }

    fun onOpen() {
        if (state.value.busy || !state.value.online) return
        local.update { it.copy(confirmOpen = false, busy = true, error = null) }
        viewModelScope.launch {
            when (val r = vault.open()) {
                is VaultOutcome.Done -> {
                    local.update { it.copy(busy = false, step = VaultStep.Revealed, revealed = r.value) }
                    hideJob?.cancel()
                    hideJob = launch { delay(RevealMillis); onHide() }
                }
                is VaultOutcome.Problem -> local.update {
                    if (r.problem == VaultProblem.NoVault) it.copy(busy = false, step = VaultStep.Intro, updatedAt = null) else it.copy(busy = false, error = message(r.problem))
                }
            }
        }
    }

    fun onHide() {
        hideJob?.cancel()
        local.update { it.copy(step = VaultStep.Saved, revealed = null) }
    }

    fun onRemoveRequested() = local.update { it.copy(confirmRemove = true, error = null, notice = null) }
    fun onRemoveDismissed() = local.update { it.copy(confirmRemove = false) }

    fun onRemove() {
        local.update { it.copy(confirmRemove = false, busy = true, error = null) }
        viewModelScope.launch {
            when (val r = vault.remove()) {
                is AppResult.Success -> local.update { it.copy(busy = false, step = VaultStep.Intro, updatedAt = null, notice = "Your vault is removed.") }
                is AppResult.Failure -> local.update { it.copy(busy = false, error = ErrorMessages.forError(r.error)) }
            }
        }
    }

    override fun onCleared() {
        local.update { it.copy(revealed = null, pin = "", confirmPin = "") }
    }

    private suspend fun refreshStatus() {
        (vault.status() as? AppResult.Success)?.value?.let { s -> local.update { it.copy(updatedAt = s.updatedAt) } }
    }

    private fun message(problem: VaultProblem): String? = when (problem) {
        VaultProblem.Cancelled -> null
        VaultProblem.Unsupported -> "This phone's passkeys can't lock a vault. Update the phone, or use Google Password Manager or iCloud Keychain."
        VaultProblem.NoVault -> "There's no vault on this account yet."
        VaultProblem.WrongPasskey -> "That passkey doesn't open this vault. Pick the Circaso PIN Vault passkey."
        is VaultProblem.Passkey -> if (problem.reason == "no-passkey") "No Circaso passkey on this phone. Sign in to the same Google or Apple account you saved it with." else "Your passkey didn't answer. Try again."
        is VaultProblem.Server -> {
            val limited = (problem.error as? AppError.Api)?.code == "request.rate_limited"
            if (limited) "You can open your vault a few times an hour. Try again a little later." else ErrorMessages.forError(problem.error)
        }
    }

    companion object {
        const val RevealMillis = 60_000L
        const val MaxAnswers = 3
        private const val MaxText = 120
    }
}
