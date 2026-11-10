package com.circaso.feature.wallet.presentation

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.platform.Biometrics
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.feature.account.presentation.BiometricApprovalState
import com.circaso.feature.wallet.domain.BiometricOfferRule
import com.circaso.feature.wallet.domain.EnableWalletBiometricsUseCase
import com.circaso.feature.wallet.domain.PinApprovals
import com.circaso.feature.wallet.domain.SignPurpose
import com.circaso.feature.wallet.domain.SignSubject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The one-time offer after a PIN approval: [subject] is the wallet it is for, null when no offer is up. */
data class BiometricOfferState(
    val subject: SignSubject? = null,
    val busy: Boolean = false,
    val online: Boolean = true,
    val error: String? = null,
    val label: String = "Fingerprint",
) {
    /** The shared offer card's state (the same card as after a wallet is made). */
    val card get() = BiometricApprovalState(hasWallet = true, busy = busy, online = online, error = error, label = label)
}

/**
 * Pasaka, 2026-11-10: after a payment approved with the PIN, offer fingerprint / Face ID once, in a calm sheet over
 * wherever the person is (a chat, a call, the pay screen). Once per wallet per phone ([BiometricOfferRule]); the offer is
 * recorded as made the moment it is decided, so closing the app mid-offer doesn't bring it back. Turning it on runs
 * Circle's set-up (the PIN once); afterwards payments open with the system prompt and fall back to Circle's PIN.
 */
class BiometricOfferViewModel(
    approvals: PinApprovals,
    private val prefs: AppPreferences,
    connection: ConnectionState,
    private val biometrics: Biometrics,
    private val enable: EnableWalletBiometricsUseCase,
    /** Lets the payment's own "Sent" moment land before the sheet rises. */
    private val settleMillis: Long = 1_200,
) : ViewModel() {
    private val local = MutableStateFlow(BiometricOfferState(label = biometrics.name))
    val state: StateFlow<BiometricOfferState> = combine(local, connection.status) { ui, status -> ui.copy(online = status == ConnectionStatus.Online) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    private val confirmations = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** "Fingerprint is on for payments.": a calm toast once Circle's set-up is done. */
    val turnedOn: kotlinx.coroutines.flow.SharedFlow<String> = confirmations

    /** An offer decided while the person is still somewhere it mustn't cover; it rises once [hold] clears. */
    private var pending: SignSubject? = null
    private val held = MutableStateFlow(false)

    init { viewModelScope.launch { approvals.approved.collect { consider(it) } } }

    /**
     * The payment's own receipt (the pay screen's Done step, which can say "don't pay it again") and a live call must
     * stay readable and in reach: while either is up, the offer waits.
     */
    fun hold(on: Boolean) {
        held.value = on
        if (!on) pending?.let { subject -> pending = null; viewModelScope.launch { rise(subject) } }
    }

    private suspend fun consider(subject: SignSubject) {
        if (local.value.subject != null || pending != null) return
        val walletId = subject.walletId
        val offer = BiometricOfferRule.shouldOffer(
            supported = biometrics.available(),
            enabled = prefs.walletBiometrics(walletId).first(),
            alreadyOffered = prefs.flag(AppPreferences.biometricOfferedFlag(walletId)).first(),
            lost = prefs.flag(AppPreferences.biometricLostFlag(walletId)).first(),
        )
        if (!offer) return
        prefs.setFlag(AppPreferences.biometricOfferedFlag(walletId), true)
        if (held.value) pending = subject else rise(subject)
    }

    private suspend fun rise(subject: SignSubject) {
        delay(settleMillis)
        // Held again during the settle (a call rang): wait for that to clear too.
        if (held.value) { pending = subject; return }
        local.update { it.copy(subject = subject, error = null, busy = false) }
    }

    fun onTurnOn() {
        val now = state.value
        val subject = now.subject ?: return
        if (now.busy || !now.online) return
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = enable(subject.copy(purpose = SignPurpose.Approve))) {
                is AppResult.Success -> {
                    prefs.setWalletBiometrics(subject.walletId, true)
                    prefs.setFlag(AppPreferences.biometricLostFlag(subject.walletId), false)
                    local.update { it.copy(subject = null, busy = false) }
                    // The sheet can be over any screen, with no switch in sight: say that it took.
                    val label = state.value.label.let { if (it.endsWith(" ID")) it else it.replaceFirstChar { c -> c.uppercase() } }
                    val name = subject.takeIf { !it.personal }?.name
                    confirmations.tryEmit(if (name != null) "$label is on for $name payments." else "$label is on for payments.")
                }
                is AppResult.Failure -> {
                    // Backing out of Circle's PIN screen leaves the offer up, without an error, to answer either way.
                    val cancelled = (r.error as? AppError.Api)?.code == "wallet.cancelled"
                    local.update { it.copy(busy = false, error = if (cancelled) null else ErrorMessages.forError(r.error)) }
                }
            }
        }
    }

    fun onNotNow() { if (!local.value.busy) local.update { it.copy(subject = null, error = null) } }
}

/**
 * App-wide, above every screen: the offer after a PIN approval (see [BiometricOfferViewModel]). [hold]: a call or the
 * payment's receipt is on screen, so the offer waits until it's gone.
 */
@Composable
fun BoxScope.BiometricOfferLayer(viewModel: BiometricOfferViewModel, hold: Boolean, onTurnedOn: (String) -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(hold) { viewModel.hold(hold) }
    androidx.compose.runtime.LaunchedEffect(viewModel) { viewModel.turnedOn.collect { onTurnedOn(it) } }
    BiometricOfferLayer(state, viewModel::onTurnOn, viewModel::onNotNow)
}

@Composable
fun BoxScope.BiometricOfferLayer(state: BiometricOfferState, onTurnOn: () -> Unit, onNotNow: () -> Unit) {
    // A group wallet is named so it's clear which wallet; the personal one never is ("Personal payments" reads oddly).
    val name = state.subject?.takeIf { !it.personal }?.name
    val card = state.card
    CircasoSheet(state.subject != null, { if (!state.busy) onNotNow() }, label = offerTitle(card, name)) {
        BiometricOfferContent(card, onTurnOn = onTurnOn, onNotNow = onNotNow, walletName = name)
    }
}

/** "Approve payments with fingerprint?", or for a group wallet "Approve Kilimani Chama payments with fingerprint?". */
internal fun offerTitle(bio: BiometricApprovalState, walletName: String?): String =
    if (walletName != null) "Approve $walletName payments with ${bio.inSentence}?" else "Approve payments with ${bio.inSentence}?"
