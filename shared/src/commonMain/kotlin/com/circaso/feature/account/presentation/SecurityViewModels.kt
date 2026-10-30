package com.circaso.feature.account.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.account.domain.DeleteAccountUseCase
import com.circaso.feature.account.domain.LinkProviderState
import com.circaso.feature.account.domain.LinkProviderUseCase
import com.circaso.feature.account.domain.ObserveSecurityLogUseCase
import com.circaso.feature.account.domain.ObserveSecurityOverviewUseCase
import com.circaso.feature.account.domain.RefreshSecurityLogUseCase
import com.circaso.feature.account.domain.RefreshSecurityOverviewUseCase
import com.circaso.feature.account.domain.SecurityEvent
import com.circaso.feature.account.domain.SecurityOverview
import com.circaso.feature.account.domain.UnlinkProviderUseCase
import com.circaso.feature.auth.domain.ForgetThisPhoneUseCase
import com.circaso.feature.auth.domain.SignOutUseCase
import com.circaso.feature.auth.domain.SocialProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the person is being asked to confirm. */
sealed interface SecurityConfirm {
    data object SignOut : SecurityConfirm
    data object RemovePhone : SecurityConfirm
    data class Unlink(val provider: SocialProvider) : SecurityConfirm
    data object DeleteAccount : SecurityConfirm
}

data class SecurityUiState(
    val overview: SecurityOverview? = null,
    val online: Boolean = true,
    val busy: Boolean = false,
    val confirm: SecurityConfirm? = null,
    /** What is typed in the delete box; deletion only goes ahead when it is exactly `DELETE`. */
    val deleteText: String = "",
    val message: String? = null,
    /** ID-28b: the server refused deletion because the wallet still holds money (`wallet.funds_remaining`). */
    val deleteBlocked: Boolean = false,
    val walletBalance: String? = null,
) {
    val canDelete get() = online && !busy && deleteText == DeleteWord

    companion object { const val DeleteWord = "DELETE" }
}

/** Account and security (design ID-24 to ID-28, without the recovery key and PIN, which arrive with the wallet). */
class SecurityViewModel(
    observeOverview: ObserveSecurityOverviewUseCase,
    private val refreshOverview: RefreshSecurityOverviewUseCase,
    private val link: LinkProviderUseCase,
    private val unlink: UnlinkProviderUseCase,
    private val forgetPhone: ForgetThisPhoneUseCase,
    private val deleteAccount: DeleteAccountUseCase,
    private val signOut: SignOutUseCase,
    connection: ConnectionState,
    observeWallet: com.circaso.feature.wallet.domain.ObserveWalletUseCase? = null,
) : ViewModel() {
    private val local = MutableStateFlow(SecurityUiState())
    private val walletBalance = (observeWallet?.invoke() ?: kotlinx.coroutines.flow.flowOf(null)).map { w ->
        val usdc = w?.usdc ?: return@map null
        w.fiat?.let { f -> com.circaso.feature.wallet.domain.formatFiat(f.currency, f.amount) } ?: (com.circaso.feature.wallet.domain.MinorUnits.toDisplay(usdc.amountMinor, usdc.decimals) + " USDC")
    }

    val state: StateFlow<SecurityUiState> = combine(observeOverview(), local, connection.status, walletBalance) { overview, ui, status, balance ->
        ui.copy(overview = overview, online = status == ConnectionStatus.Online, walletBalance = balance)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityUiState())

    init { viewModelScope.launch { refreshOverview() } }

    fun onLink(provider: SocialProvider) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = when (val r = link(provider)) {
                LinkProviderState.Linked -> "${provider.name} is linked."
                LinkProviderState.Cancelled -> null
                LinkProviderState.Unavailable -> "Linking ${provider.name} isn't available in this version yet."
                is LinkProviderState.Failed -> r.error?.let { ErrorMessages.forError(it) } ?: "Couldn't reach ${provider.name}. Try again."
            }
            local.update { it.copy(busy = false, message = message) }
        }
    }

    fun onConfirmRequested(confirm: SecurityConfirm) = local.update { it.copy(confirm = confirm, deleteText = "", message = null) }
    fun onConfirmDismissed() = local.update { it.copy(confirm = null, deleteText = "") }
    fun onDeleteText(text: String) = local.update { it.copy(deleteText = text) }
    fun onDeleteBlockedDismissed() = local.update { it.copy(deleteBlocked = false) }

    fun onConfirmed() {
        val s = state.value
        val confirm = s.confirm ?: return
        if (confirm == SecurityConfirm.DeleteAccount && !s.canDelete) return
        local.update { it.copy(confirm = null, busy = true, message = null) }
        viewModelScope.launch {
            val failure = when (confirm) {
                SecurityConfirm.SignOut -> { signOut(); null } // the app returns to Welcome by itself
                SecurityConfirm.RemovePhone -> (forgetPhone() as? AppResult.Failure)?.error
                is SecurityConfirm.Unlink -> (unlink(confirm.provider) as? AppResult.Failure)?.error
                SecurityConfirm.DeleteAccount -> (deleteAccount() as? AppResult.Failure)?.error.also { if (it == null) signOut() }
            }
            val blocked = (failure as? com.circaso.core.common.AppError.Api)?.code == "wallet.funds_remaining"
            local.update { it.copy(busy = false, deleteText = "", deleteBlocked = blocked, message = if (blocked) null else failure?.let { e -> ErrorMessages.forError(e) }) }
        }
    }
}

data class SecurityLogUiState(val events: List<SecurityEvent> = emptyList(), val loaded: Boolean = false)

class SecurityLogViewModel(
    observeLog: ObserveSecurityLogUseCase,
    private val refreshLog: RefreshSecurityLogUseCase,
) : ViewModel() {
    val state: StateFlow<SecurityLogUiState> = combine(observeLog(), MutableStateFlow(true)) { events, _ -> SecurityLogUiState(events, true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityLogUiState())

    init { viewModelScope.launch { refreshLog() } }

    companion object {
        /** Plain words for the event types the server sends; an unknown type is shown readable, never raw. */
        fun label(type: String): String = when (type.lowercase()) {
            "sign_in", "signed_in", "login" -> "Signed in"
            "sign_out", "signed_out", "logout" -> "Signed out"
            "device_added", "new_phone", "device_registered" -> "New phone"
            "device_removed", "device_forgotten" -> "Phone removed"
            "phone_verified", "number_verified" -> "Number verified"
            "provider_linked", "login_linked" -> "Sign-in account linked"
            "provider_unlinked", "login_unlinked" -> "Sign-in account removed"
            "account_deletion_requested", "account_deleted" -> "Account deletion requested"
            "recovery_requested" -> "Recovery requested"
            else -> type.replace('_', ' ').replace('.', ' ').trim().replaceFirstChar { it.uppercase() }
        }
    }
}
