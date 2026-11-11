package com.circaso.feature.vault.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.Biometrics
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.core.sync.nowMillis
import com.circaso.feature.profile.domain.ProfileRepository
import com.circaso.feature.vault.data.VaultOutcome
import com.circaso.feature.vault.data.VaultProblem
import com.circaso.feature.vault.data.VaultRepository
import com.circaso.feature.vault.domain.SecurityAnswer
import com.circaso.feature.vault.domain.VaultContents
import com.circaso.feature.wallet.domain.WalletRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which part of the vault screen shows. */
enum class VaultStep { Loading, Unsupported, Intro, Edit, Saved, Revealed, LoadFailed }

/** Which control is working, so only that one spins. */
enum class VaultBusy { Save, Open, Remove }

data class AnswerDraft(val id: Int, val question: String = "", val answer: String = "") {
    val incomplete get() = question.isBlank() != answer.isBlank()
}

data class VaultUiState(
    val step: VaultStep = VaultStep.Loading,
    val online: Boolean = true,
    val busy: VaultBusy? = null,
    /** "Face ID", "Touch ID", "Fingerprint", or "Screen Lock" when no biometrics are set up. */
    val unlock: String = "Fingerprint",
    val updatedAt: String? = null,
    val pin: String = "",
    val confirmPin: String = "",
    val answers: List<AnswerDraft> = emptyList(),
    val revealed: VaultContents? = null,
    /** When the shown PIN hides itself (epoch ms), for the countdown. */
    val hidesAt: Long? = null,
    val confirmOpen: Boolean = false,
    val confirmRemove: Boolean = false,
    val error: String? = null,
    /** The request id to quote to support, under [error]. */
    val errorRef: String? = null,
    val notice: String? = null,
) {
    val pinValid get() = pin.length == PinLength && pin.all { it.isDigit() }
    val pinsMatch get() = pin == confirmPin
    val canSave get() = pinValid && pinsMatch && online && busy == null && answers.none { it.incomplete }
    val unlockInSentence get() = if (unlock.endsWith(" ID")) unlock else unlock.lowercase()

    companion object { const val PinLength = 6 }
}

/**
 * The PIN Vault (2026-11-11). Circle's PIN screen is Circle's own, so Circaso never sees the PIN: the person types it
 * here once to back it up. Opening shows it for [RevealMillis] (and hides it when the app leaves the screen); the server
 * pauses sending for 24 hours after every opening and emails the person, in case it wasn't them. The last known status
 * comes from [VaultRepository.known], so the screen paints at once and the Security row agrees with it.
 */
class VaultViewModel(
    private val vault: VaultRepository,
    private val profile: ProfileRepository,
    connection: ConnectionState,
    biometrics: Biometrics? = null,
    private val wallet: WalletRepository? = null,
) : ViewModel() {
    private val local = MutableStateFlow(
        VaultUiState(unlock = if (biometrics?.available() == false) "Screen Lock" else biometrics?.name ?: "Fingerprint"),
    )
    private var hideJob: Job? = null
    private var nextAnswerId = 0

    val state: StateFlow<VaultUiState> = combine(local, connection.status) { ui, status -> ui.copy(online = status == ConnectionStatus.Online) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        load()
        // A failed first load tries again by itself when the connection comes back.
        viewModelScope.launch {
            connection.status.map { it == ConnectionStatus.Online }.distinctUntilChanged().collect { online ->
                if (online && local.value.step == VaultStep.LoadFailed) load()
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            if (!vault.supported()) { local.update { it.copy(step = VaultStep.Unsupported) }; return@launch }
            vault.warm()
            // The last status seen paints at once; the server's answer replaces it.
            vault.known.value?.let { k -> local.update { if (it.step == VaultStep.Loading || it.step == VaultStep.LoadFailed) it.copy(step = if (k.exists) VaultStep.Saved else VaultStep.Intro, updatedAt = k.updatedAt) else it } }
            when (val r = vault.status()) {
                is AppResult.Success -> local.update {
                    if (it.step in Settled) it.copy(step = if (r.value.exists) VaultStep.Saved else VaultStep.Intro, updatedAt = r.value.updatedAt, error = null, errorRef = null) else it.copy(updatedAt = r.value.updatedAt)
                }
                is AppResult.Failure -> local.update { if (it.step == VaultStep.Loading || it.step == VaultStep.LoadFailed) it.copy(step = VaultStep.LoadFailed).withError(r.error) else it }
            }
        }
    }

    fun onStart() = local.update { it.copy(step = VaultStep.Edit, pin = "", confirmPin = "", answers = emptyList(), error = null, errorRef = null, notice = null) }
    fun onCancelEdit() = local.update { it.copy(step = if (it.updatedAt != null) VaultStep.Saved else VaultStep.Intro, pin = "", confirmPin = "", answers = emptyList(), error = null, errorRef = null) }
    fun onPin(value: String) = local.update { it.copy(pin = value.filter(Char::isDigit).take(VaultUiState.PinLength), error = null) }
    fun onConfirmPin(value: String) = local.update { it.copy(confirmPin = value.filter(Char::isDigit).take(VaultUiState.PinLength), error = null) }
    fun onAddAnswer() = local.update { if (it.answers.size >= MaxAnswers) it else it.copy(answers = it.answers + AnswerDraft(nextAnswerId++)) }
    fun onRemoveAnswer(id: Int) = local.update { it.copy(answers = it.answers.filterNot { a -> a.id == id }) }
    fun onQuestion(id: Int, value: String) = local.update { s -> s.copy(answers = s.answers.map { a -> if (a.id == id) a.copy(question = value.take(MaxText)) else a }) }
    fun onAnswer(id: Int, value: String) = local.update { s -> s.copy(answers = s.answers.map { a -> if (a.id == id) a.copy(answer = value.take(MaxText)) else a }) }

    fun onSave() {
        val now = state.value
        if (!now.canSave) return
        local.update { it.copy(busy = VaultBusy.Save, error = null, errorRef = null) }
        viewModelScope.launch {
            val me = profile.profile.first()
            val contents = VaultContents(now.pin, now.answers.filter { it.question.isNotBlank() }.map { SecurityAnswer(it.question.trim(), it.answer.trim()) })
            val label = me?.handle?.let { "@$it" } ?: me?.displayName ?: "Circaso"
            when (val r = vault.save(contents, handle = label, displayName = "${me?.displayName ?: "Circaso"} · PIN Vault")) {
                is VaultOutcome.Done -> local.update {
                    it.copy(busy = null, step = VaultStep.Saved, pin = "", confirmPin = "", answers = emptyList(), updatedAt = vault.known.value?.updatedAt ?: it.updatedAt)
                }
                is VaultOutcome.Problem -> local.update { it.copy(busy = null).withProblem(r.problem) }
            }
        }
    }

    fun onOpenRequested() = local.update { it.copy(confirmOpen = true, error = null, errorRef = null, notice = null) }
    fun onOpenDismissed() = local.update { it.copy(confirmOpen = false) }

    fun onOpen() {
        if (state.value.busy != null || !state.value.online) return
        local.update { it.copy(confirmOpen = false, busy = VaultBusy.Open, error = null, errorRef = null, notice = null) }
        viewModelScope.launch {
            val r = vault.open()
            // An opening pauses sending: the wallet shows it (the hold banner) without waiting for its next sync.
            if (r is VaultOutcome.Done || (r as? VaultOutcome.Problem)?.serverOpened == true) launch { wallet?.refresh() }
            when (r) {
                is VaultOutcome.Done -> {
                    local.update { it.copy(busy = null, step = VaultStep.Revealed, revealed = r.value, hidesAt = nowMillis() + RevealMillis) }
                    hideJob?.cancel()
                    hideJob = launch { delay(RevealMillis); onHide() }
                }
                is VaultOutcome.Problem -> local.update {
                    when {
                        r.problem == VaultProblem.NoVault -> it.copy(busy = null, step = VaultStep.Intro, updatedAt = null)
                        r.serverOpened -> it.copy(busy = null, notice = "Your vault was opened, so sending is paused for 24 hours, but your PIN couldn't be shown.").withProblem(r.problem)
                        else -> it.copy(busy = null).withProblem(r.problem)
                    }
                }
            }
        }
    }

    /** Hides the shown PIN: the timer ran out, the person tapped Hide, or the app left the screen. */
    fun onHide() {
        hideJob?.cancel()
        local.update { if (it.step == VaultStep.Revealed) it.copy(step = VaultStep.Saved, revealed = null, hidesAt = null) else it }
    }

    fun onRemoveRequested() = local.update { it.copy(confirmRemove = true, error = null, errorRef = null, notice = null) }
    fun onRemoveDismissed() = local.update { it.copy(confirmRemove = false) }

    fun onRemove() {
        local.update { it.copy(confirmRemove = false, busy = VaultBusy.Remove, error = null, errorRef = null) }
        viewModelScope.launch {
            when (val r = vault.remove()) {
                is AppResult.Success -> local.update { it.copy(busy = null, step = VaultStep.Intro, updatedAt = null) }
                is AppResult.Failure -> local.update { it.copy(busy = null).withError(r.error) }
            }
        }
    }

    override fun onCleared() {
        local.update { it.copy(revealed = null, pin = "", confirmPin = "") }
    }

    private fun VaultUiState.withError(error: AppError) = copy(error = ErrorMessages.forError(error), errorRef = ErrorMessages.reference(error))

    private fun VaultUiState.withProblem(problem: VaultProblem): VaultUiState = when (problem) {
        VaultProblem.Cancelled -> this
        VaultProblem.Unsupported -> copy(error = "This phone's passkeys can't lock a vault. Update the phone, or use Google Password Manager or iCloud Keychain.")
        VaultProblem.NoVault -> copy(error = "There's no vault on this account yet.")
        VaultProblem.WrongPasskey -> copy(error = "That passkey doesn't open this vault. Pick the Circaso PIN Vault passkey.")
        is VaultProblem.Passkey -> copy(
            error = if (problem.reason == "no-passkey") "No Circaso passkey on this phone. Sign in to the same Google or Apple account you saved it with."
            else "Your passkey didn't answer. Try again.",
            // What the passkey provider said, for support.
            errorRef = problem.reason,
        )
        is VaultProblem.Server -> if ((problem.error as? AppError.Api)?.code == "request.rate_limited") {
            copy(error = "You can open your vault 5 times an hour. Try again a little later.", errorRef = null)
        } else withError(problem.error)
    }

    companion object {
        const val RevealMillis = 60_000L
        const val MaxAnswers = 3
        private const val MaxText = 120
        /** Steps a fresh status may replace (never the edit form or a shown PIN). */
        private val Settled = setOf(VaultStep.Loading, VaultStep.LoadFailed, VaultStep.Intro, VaultStep.Saved)
    }
}
