package com.circaso.feature.home.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.sync.FailedChange
import com.circaso.core.sync.FailedChangeText
import com.circaso.core.sync.Outbox
import com.circaso.feature.account.domain.ObserveSecurityOverviewUseCase
import com.circaso.feature.account.domain.RefreshSecurityOverviewUseCase
import com.circaso.feature.auth.domain.SignOutUseCase
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.profile.domain.Profile
import com.circaso.feature.profile.domain.RefreshProfileUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class YouUiState(
    val profile: Profile? = null,
    val maskedPhone: String? = null,
    val confirmSignOut: Boolean = false,
    val signingOut: Boolean = false,
    /** Changes that could not be sent, waiting for the person to retry or discard. */
    val failed: List<FailedChange> = emptyList(),
    /** False until the outbox has been read once: an empty [failed] before then means "not known yet", not "none". */
    val failedLoaded: Boolean = false,
    /** The coins sound when money arrives (You → Money sound). */
    val moneySound: Boolean = true,
    /** Notification privacy (You → Notification privacy): off by default. */
    val notificationPrivacy: Boolean = false,
    /** Money › Verification: where the identity check stands on the wallet network in use (design F2's row). */
    val identity: com.circaso.feature.ramp.presentation.IdentityRowUi = com.circaso.feature.ramp.presentation.IdentityRowUi.Hidden,
)

/** The You tab (design F2). Reads from the phone, refreshes quietly in the background, never blocks on the network. */
class YouViewModel(
    observeProfile: ObserveProfileUseCase,
    observeOverview: ObserveSecurityOverviewUseCase,
    private val refreshProfile: RefreshProfileUseCase,
    private val refreshOverview: RefreshSecurityOverviewUseCase,
    private val signOut: SignOutUseCase,
    private val outbox: Outbox,
    private val kickOutbox: () -> Unit,
    private val prefs: com.circaso.core.datastore.AppPreferences? = null,
    /** Money › Verification; null leaves the row out (tests of other things). */
    private val identityRow: com.circaso.feature.ramp.presentation.IdentityRowModel? = null,
) : ViewModel() {
    init {
        // The phone's copy of the sound follows the setting (iOS keeps it as a file), from the first launch on.
        prefs?.let { p -> viewModelScope.launch { com.circaso.core.platform.setMoneySound(!p.flag(com.circaso.core.datastore.AppPreferences.MoneySoundOff).first()) } }
    }

    fun onMoneySound(on: Boolean) {
        val p = prefs ?: return
        viewModelScope.launch {
            p.setFlag(com.circaso.core.datastore.AppPreferences.MoneySoundOff, !on)
            com.circaso.core.platform.setMoneySound(on)
            // Turning it on plays it once, so the person hears what money will sound like.
            if (on) com.circaso.core.platform.playMoneySound()
        }
    }

    /** Notification privacy: money, chats and calls are worded without names, amounts or what was said. */
    fun onNotificationPrivacy(on: Boolean) {
        val p = prefs ?: return
        // The navigation mirrors it for iOS's notification extension whenever the flag changes.
        viewModelScope.launch { p.setFlag(com.circaso.core.datastore.AppPreferences.NotificationPrivacy, on) }
    }

    private val local = MutableStateFlow(YouUiState())

    private val soundOff = prefs?.flag(com.circaso.core.datastore.AppPreferences.MoneySoundOff) ?: kotlinx.coroutines.flow.flowOf(false)
    private val privacy = prefs?.flag(com.circaso.core.datastore.AppPreferences.NotificationPrivacy) ?: kotlinx.coroutines.flow.flowOf(false)
    private val notificationPrefs = combine(soundOff, privacy) { off, private -> off to private }

    /** The Verification row ([com.circaso.feature.ramp.presentation.IdentityRowModel]): hidden where there is none to show. */
    private val identity: kotlinx.coroutines.flow.Flow<com.circaso.feature.ramp.presentation.IdentityRowUi> =
        identityRow?.ui ?: kotlinx.coroutines.flow.flowOf(com.circaso.feature.ramp.presentation.IdentityRowUi.Hidden)

    val state: StateFlow<YouUiState> = combine(combine(observeProfile(), observeOverview(), local, outbox.failed(), notificationPrefs) { profile, overview, ui, failed, (off, private) ->
        ui.copy(profile = profile, maskedPhone = overview?.maskedPhone, failed = failed.map(FailedChangeText::from), failedLoaded = true, moneySound = !off, notificationPrivacy = private)
    }, identity) { ui, row -> ui.copy(identity = row) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), YouUiState())

    /** The profile being opened, or the app coming back to the front: the Verification row asks the server again. */
    fun refreshIdentity() { identityRow?.refresh() }

    init {
        // Best effort: what is saved on the phone is already on screen, so a failure is not worth interrupting anyone for.
        viewModelScope.launch { refreshProfile() }
        viewModelScope.launch { refreshOverview() }
        identityRow?.start(viewModelScope)
    }

    fun onRetry(change: FailedChange) {
        viewModelScope.launch { outbox.retry(change.id); kickOutbox() }
    }

    fun onDiscard(change: FailedChange) {
        viewModelScope.launch { outbox.discard(change.id) }
    }

    fun onSignOutRequested() = local.update { it.copy(confirmSignOut = true) }
    fun onSignOutCancelled() = local.update { it.copy(confirmSignOut = false) }

    fun onSignOutConfirmed() {
        local.update { it.copy(confirmSignOut = false, signingOut = true) }
        viewModelScope.launch { signOut() } // the session ends and the app returns to Welcome by itself
    }
}
