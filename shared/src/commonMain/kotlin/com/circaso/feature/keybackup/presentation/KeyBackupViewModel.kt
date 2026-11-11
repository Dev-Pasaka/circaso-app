package com.circaso.feature.keybackup.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
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
    val restoredJustNow: Boolean = false,
) {
    /** Chats shows "Restore group names": backup is on for the account, this phone lacks it, and names are missing. */
    val offerRestore get() = supported && onServer == true && !onPhone && unreadableGroups > 0 && !restoredJustNow
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
        chats.chats.map { list -> list.count { it.chat.kind == ChatKind.Group && it.chat.nameState != GroupNameState.Known } },
    ) { ui, b, status, missing ->
        ui.copy(onServer = b.onServer, onPhone = b.onPhone, online = status == ConnectionStatus.Online, unreadableGroups = missing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            val supported = backup.supported()
            local.update { it.copy(supported = supported) }
            if (supported) backup.refresh()
        }
    }

    fun onRestore() = act {
        backup.restore().also { if (it == null) local.update { s -> s.copy(restoredJustNow = true) } }
    }

    fun onTurnOn() = act {
        val me = profile.profile.first()
        backup.turnOn(handle = me?.handle?.let { "@$it" } ?: me?.displayName ?: "Circaso", displayName = "${me?.displayName ?: "Circaso"} · Circaso key")
    }

    fun onTurnOff() {
        if (local.value.busy) return
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val r = backup.turnOff()
            local.update { it.copy(busy = false, error = (r as? com.circaso.core.common.AppResult.Failure)?.let { f -> ErrorMessages.forError(f.error) }) }
        }
    }

    private fun act(block: suspend () -> KeyBackupProblem?) {
        if (local.value.busy || !state.value.online) return
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val problem = block()
            local.update { it.copy(busy = false, error = problem?.let(::message)) }
        }
    }

    private fun message(p: KeyBackupProblem): String? = when (p) {
        KeyBackupProblem.Cancelled -> null
        KeyBackupProblem.Unsupported -> "This phone's passkeys can't do this. Update the phone, or use Google Password Manager or iCloud Keychain."
        KeyBackupProblem.WrongPasskey -> "That passkey doesn't open your backup. Pick your Circaso passkey."
        is KeyBackupProblem.Passkey -> if (p.reason == "no-passkey") "No Circaso passkey on this phone. Sign in to the Google or Apple account you saved it with." else "Your passkey didn't answer. Try again."
        is KeyBackupProblem.Server -> ErrorMessages.forError(p.error as AppError)
    }
}
