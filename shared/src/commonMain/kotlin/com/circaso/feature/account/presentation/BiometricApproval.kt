package com.circaso.feature.account.presentation

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
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.ListRow
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
) {
    /** For sentences: "Face ID" / "Touch ID" keep their capitals, "fingerprint" doesn't. */
    val inSentence get() = if (label.endsWith(" ID")) label else label.lowercase()
}

/**
 * Design ID-24 / ID-24c: approving payments with Face ID or the fingerprint instead of the PIN (Circle's biometric
 * PIN). Turning it on runs Circle's own setup (the PIN once); Circle keeps the key behind the phone's secure hardware,
 * so Circaso never sees the PIN. The switch is remembered on this phone and wiped with the rest of the local data on
 * sign-out or Remove this phone ([AppPreferences.clear]).
 */
class BiometricApprovalViewModel(
    private val wallet: WalletRepository,
    private val prefs: AppPreferences,
    connection: ConnectionState,
    biometrics: Biometrics,
) : ViewModel() {
    private val local = MutableStateFlow(BiometricApprovalState(label = biometrics.name, supported = biometrics.available()))
    val state: StateFlow<BiometricApprovalState> =
        combine(local, prefs.flag(AppPreferences.BiometricApproval), connection.status, wallet.wallet) { ui, on, status, w ->
            ui.copy(enabled = on, online = status == ConnectionStatus.Online, hasWallet = w?.status == WalletStatus.Active)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    fun onEnable() {
        val now = state.value
        if (now.busy || now.enabled || !now.online || !now.supported) return
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = wallet.enableBiometricApproval()) {
                is AppResult.Success -> { prefs.setFlag(AppPreferences.BiometricApproval, true); local.update { it.copy(busy = false) } }
                is AppResult.Failure -> {
                    // Backing out of Circle's PIN screen is a choice, not an error.
                    val cancelled = (r.error as? AppError.Api)?.code == "wallet.cancelled"
                    local.update { it.copy(busy = false, error = if (cancelled) null else ErrorMessages.forError(r.error)) }
                }
            }
        }
    }
}

@Composable
fun BiometricApprovalSection(viewModel: BiometricApprovalViewModel) {
    val state by viewModel.state.collectAsState()
    BiometricApprovalSection(state, viewModel::onEnable)
}

/** The Security screen's Payments section (ID-24 off, ID-24c on). Hidden until there is a wallet to approve for. */
@Composable
fun BiometricApprovalSection(state: BiometricApprovalState, onEnable: () -> Unit) {
    // A phone with nothing enrolled has nothing to turn on, unless it was on before (then it shows as on until Circle says otherwise).
    if (!state.hasWallet || (!state.supported && !state.enabled)) return
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column {
        Section("Payments") {
            // No manage screen yet (turning it off happens in Circle's settings), so "On" has no chevron.
            ListRow(
                state.label,
                sub = when {
                    state.enabled -> "Your PIN takes over if it fails"
                    !state.online -> "Turning this on needs a connection"
                    else -> "Approve without typing your PIN"
                },
                meta = if (state.enabled) "On" else null,
                trailing = if (state.enabled) null else {
                    { CircasoButton("Turn On", onEnable, style = ButtonStyle.Secondary, size = ButtonSize.Small, busy = state.busy, enabled = state.online) }
                },
            )
        }
        state.error?.let {
            BasicText(it, Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2), style = t.footnote.copy(color = c.critical))
        }
    }
}
