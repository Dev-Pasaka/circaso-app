package com.circaso.feature.account.presentation

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.platform.Biometrics
import com.circaso.core.sync.ConnectionState
import com.circaso.core.platform.ConnectionStatus
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.Section
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.domain.WalletRepository
import com.circaso.feature.wallet.domain.WalletStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BiometricApprovalState(
    val hasWallet: Boolean = false,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val online: Boolean = true,
    val error: String? = null,
    /** What the phone calls it: "Face ID", "Touch ID" or "Fingerprint". */
    val label: String = "Fingerprint",
    /** The phone has biometrics set up; without them there is nothing to offer. */
    val supported: Boolean = true,
    /** Circle said it stopped working for the personal wallet (new fingerprints or face, or turned off in its page). */
    val lost: Boolean = false,
    /** The "Turn off?" sheet is up. */
    val confirmOff: Boolean = false,
) {
    /** For sentences: "Face ID" / "Touch ID" keep their capitals, "fingerprint" doesn't. */
    val inSentence get() = if (label.endsWith(" ID")) label else label.lowercase()
}

/**
 * Design ID-24 / ID-24c: approving payments with Face ID or the fingerprint instead of the PIN (Circle's biometric
 * PIN). Turning it on runs Circle's own setup (the PIN once); Circle keeps the key behind the phone's secure hardware,
 * so Circaso never sees the PIN. Turning it off needs no server: the wallet's challenges simply run with Circle's
 * biometrics switched off on this phone. The switch is remembered on this phone and wiped with the rest of the local
 * data on sign-out or Remove this phone ([AppPreferences.clear]).
 */
class BiometricApprovalViewModel(
    private val wallet: WalletRepository,
    private val prefs: AppPreferences,
    connection: ConnectionState,
    biometrics: Biometrics,
) : ViewModel() {
    private val local = MutableStateFlow(BiometricApprovalState(label = biometrics.name, supported = biometrics.available()))
    private val lostFlag = AppPreferences.biometricLostFlag(null)
    val state: StateFlow<BiometricApprovalState> =
        combine(local, prefs.flag(AppPreferences.BiometricApproval), prefs.flag(lostFlag), connection.status, wallet.wallet) { ui, on, lost, status, w ->
            ui.copy(enabled = on, lost = lost && !on, online = status == ConnectionStatus.Online, hasWallet = w?.status == WalletStatus.Active)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    fun onEnable() {
        val now = state.value
        if (now.busy || now.enabled || !now.online || !now.supported) return
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = wallet.enableBiometricApproval()) {
                is AppResult.Success -> {
                    prefs.setWalletBiometrics(null, true)
                    prefs.setFlag(lostFlag, false)
                    // Turned on, here or in the offer: the offer after a payment has nothing left to ask.
                    prefs.setFlag(AppPreferences.biometricOfferedFlag(null), true)
                    local.update { it.copy(busy = false) }
                }
                is AppResult.Failure -> {
                    // Backing out of Circle's PIN screen is a choice, not an error.
                    val cancelled = (r.error as? AppError.Api)?.code == "wallet.cancelled"
                    local.update { it.copy(busy = false, error = if (cancelled) null else ErrorMessages.forError(r.error)) }
                }
            }
        }
    }

    /** The "On" row: asks first, since turning it back on takes the PIN again. */
    fun onTurnOffAsked() { if (state.value.enabled) local.update { it.copy(confirmOff = true) } }
    fun onTurnOffDismissed() = local.update { it.copy(confirmOff = false) }

    /** Back to the PIN on this phone. Works offline: nothing leaves the phone. */
    fun onTurnOff() {
        local.update { it.copy(confirmOff = false, error = null) }
        viewModelScope.launch { prefs.setWalletBiometrics(null, false) }
    }

    /** The one-time offer (after the wallet is made, or after a PIN approval) was shown and answered. */
    fun onOffered() { viewModelScope.launch { prefs.setFlag(AppPreferences.biometricOfferedFlag(null), true) } }
}

@Composable
fun BiometricApprovalSection(viewModel: BiometricApprovalViewModel) {
    val state by viewModel.state.collectAsState()
    BiometricApprovalSection(state, viewModel::onEnable, viewModel::onTurnOffAsked)
}

/** The Security screen's sheet for turning biometric approval off; drawn in the screen's overlay. */
@Composable
fun BoxScope.BiometricTurnOffSheet(viewModel: BiometricApprovalViewModel) {
    val state by viewModel.state.collectAsState()
    BiometricTurnOffSheet(state, viewModel::onTurnOff, viewModel::onTurnOffDismissed)
}

@Composable
fun BoxScope.BiometricTurnOffSheet(state: BiometricApprovalState, onTurnOff: () -> Unit, onDismiss: () -> Unit) {
    ConfirmSheet(
        visible = state.confirmOff,
        // About payments, not the phone's own unlock, which stays as it is.
        title = "Stop approving with ${state.inSentence}?",
        message = "Payments will ask for your PIN again. Turning ${state.inSentence} back on needs a connection and your PIN once.",
        confirmLabel = "Use My PIN",
        onConfirm = onTurnOff,
        onDismiss = onDismiss,
        cancelLabel = "Keep It On",
    )
}

/**
 * The Security screen's Payments section (ID-24 off, ID-24c on). Hidden until there is a wallet to approve for.
 * On: "On" opens the turn-off sheet. Off: Turn On runs Circle's set-up. Lost: says why it's off and offers it again.
 */
@Composable
fun BiometricApprovalSection(state: BiometricApprovalState, onEnable: () -> Unit, onTurnOff: () -> Unit = {}) {
    // A phone with nothing enrolled has nothing to turn on, unless it was on before (then it shows as on until Circle says otherwise).
    if (!state.hasWallet || (!state.supported && !state.enabled)) return
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column {
        Section("Payments") {
            ListRow(
                state.label,
                sub = when {
                    state.enabled -> "Your PIN takes over if it fails"
                    // Short enough for two lines beside the button: the button says how to fix it.
                    state.lost && !state.online -> "Stopped working. Needs a connection to fix"
                    state.lost -> "Stopped working. Your PIN approves for now"
                    !state.online -> "Turning this on needs a connection"
                    else -> "Approve without typing your PIN"
                },
                meta = if (state.enabled) "On" else null,
                trailing = if (state.enabled) {
                    { RowChevron() }
                } else {
                    { CircasoButton(if (state.lost) "Turn On Again" else "Turn On", onEnable, style = ButtonStyle.Secondary, size = ButtonSize.Small, busy = state.busy, enabled = state.online) }
                },
                onClick = if (state.enabled) onTurnOff else null,
                onClickLabel = if (state.enabled) "Stop approving with ${state.inSentence}" else null,
            )
        }
        state.error?.let {
            BasicText(it, Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2), style = t.footnote.copy(color = c.critical))
        }
    }
}
