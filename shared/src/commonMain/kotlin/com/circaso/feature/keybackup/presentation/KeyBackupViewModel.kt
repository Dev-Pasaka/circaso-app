package com.circaso.feature.keybackup.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.keybackup.data.KeyBackupProblem
import com.circaso.feature.keybackup.data.KeyBackupRepository
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.GroupNameState
import com.circaso.feature.profile.domain.ProfileRepository
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

data class KeyBackupUi(
    val supported: Boolean = false,
    val onServer: Boolean? = null,
    val onPhone: Boolean = false,
    /** Groups whose names this phone can't read yet. */
    val unreadableGroups: Int = 0,
    val online: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    /** The request id to quote to support, after [error]. */
    val errorRef: String? = null,
    /** A restore finished this session (the card then says what's left, if anything, instead of offering again). */
    val restored: Boolean = false,
    /** "Not Now" on the Chats card, for this session. */
    val dismissed: Boolean = false,
    val confirmOff: Boolean = false,
) {
    /** Backup is on for the account, as far as this phone knows (the server, or this phone holding the root). */
    val on get() = onServer == true || onPhone
    /** Not known yet (no server answer and nothing on this phone): the row says "Checking…". */
    val unknown get() = onServer == null && !onPhone

    /** Chats shows "Restore your group names": backup is on for the account and some names are missing here. */
    val offerRestore get() = supported && onServer == true && unreadableGroups > 0 && !restored && !dismissed
    /** After a restore, groups the backup didn't have (their names come from a member's phone instead). */
    val leftAfterRestore get() = if (restored && !dismissed) unreadableGroups else 0
}

/** The group key backup's Chats card (restore) and Security row (on/off). */
class KeyBackupViewModel(
    private val backup: KeyBackupRepository,
    chats: ChatsRepository,
    private val profile: ProfileRepository,
    connection: ConnectionState,
) : ViewModel() {
    private val local = MutableStateFlow(KeyBackupUi())

    val state: StateFlow<KeyBackupUi> = combine(
        local, backup.status, connection.status,
        chats.chats.map { list -> list.count { it.chat.kind == ChatKind.Group && it.chat.nameState != GroupNameState.Known } }.distinctUntilChanged(),
    ) { ui, b, status, missing ->
        ui.copy(onServer = b.onServer, onPhone = b.onPhone, online = status == ConnectionStatus.Online, unreadableGroups = missing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            val supported = backup.supported()
            local.update { it.copy(supported = supported) }
            if (!supported) return@launch
            // Asked again each time the connection comes back, so a sign-in that started offline still finds the backup.
            connection.status.map { it == ConnectionStatus.Online }.distinctUntilChanged().collect { online -> if (online) backup.refresh() }
        }
    }

    fun onRestore() = act {
        backup.restore().also { if (it == null) local.update { s -> s.copy(restored = true) } }
    }

    fun onNotNow() = local.update { it.copy(dismissed = true) }

    fun onTurnOn() = act {
        val me = profile.profile.first()
        backup.turnOn(handle = me?.handle?.let { "@$it" } ?: me?.displayName ?: "Circaso", displayName = "${me?.displayName ?: "Circaso"} · Circaso key")
    }

    fun onTurnOffRequested() = local.update { it.copy(confirmOff = true, error = null, errorRef = null) }
    fun onTurnOffDismissed() = local.update { it.copy(confirmOff = false) }

    fun onTurnOff() {
        if (local.value.busy || !state.value.online) return
        local.update { it.copy(confirmOff = false, busy = true, error = null, errorRef = null) }
        viewModelScope.launch {
            when (val r = backup.turnOff()) {
                is AppResult.Success -> local.update { it.copy(busy = false) }
                is AppResult.Failure -> local.update { it.copy(busy = false, error = ErrorMessages.forError(r.error), errorRef = ErrorMessages.reference(r.error)) }
            }
        }
    }

    private fun act(block: suspend () -> KeyBackupProblem?) {
        if (local.value.busy || !state.value.online) return
        local.update { it.copy(busy = true, error = null, errorRef = null) }
        viewModelScope.launch {
            val problem = block()
            local.update { it.copy(busy = false).withProblem(problem) }
        }
    }

    private fun KeyBackupUi.withProblem(p: KeyBackupProblem?): KeyBackupUi = when (p) {
        null, KeyBackupProblem.Cancelled -> this
        KeyBackupProblem.Unsupported -> copy(error = "This phone's passkeys can't do this. Update the phone, or use Google Password Manager or iCloud Keychain.")
        KeyBackupProblem.WrongPasskey -> copy(error = "That passkey doesn't open your backup. Pick your Circaso passkey.")
        KeyBackupProblem.TurnedOff -> copy(error = "Your backup was turned off on another phone.")
        is KeyBackupProblem.Passkey -> copy(
            error = if (p.reason == "no-passkey") "No Circaso passkey on this phone. Sign in to the Google or Apple account you saved it with." else "Your passkey didn't answer. Try again.",
        )
        is KeyBackupProblem.Server -> copy(error = ErrorMessages.forError(p.error), errorRef = ErrorMessages.reference(p.error))
    }
}
