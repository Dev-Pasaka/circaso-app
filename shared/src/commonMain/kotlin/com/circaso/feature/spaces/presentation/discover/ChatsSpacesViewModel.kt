package com.circaso.feature.spaces.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.feature.spaces.domain.MySpace
import com.circaso.feature.spaces.domain.ReceivedInvite
import com.circaso.feature.spaces.domain.SpacesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What Chats shows of spaces: the invite cards at the top (SI2), the Spaces filter chip and the "Private · {space}"
 * labels on private chats that came through a space (SH2d). Off (empty) while Spaces core is off.
 */
data class ChatsSpacesUiState(
    val enabled: Boolean = false,
    val invites: List<ReceivedInvite> = emptyList(),
    /** SI3b: a card folded to one line after Decline; it goes (and SP22 is sent) when Undo's few seconds end. */
    val declining: Set<String> = emptySet(),
    val accepting: Set<String> = emptySet(),
    /** A space's name for each private chat's label, by space id. */
    val spaceNames: Map<String, String> = emptyMap(),
    /** My spaces, for their own rows under the Spaces chip (the space's chat sits there like a group). */
    val mine: List<MySpace> = emptyList(),
    val online: Boolean = true,
    val toast: String? = null,
) {
    /** The Spaces chip is for people who own or admin a space (SH2d), or who have private chats from spaces. */
    val showFilter: Boolean get() = enabled && (mine.any { it.me.isAdmin } || spaceNames.isNotEmpty())
    val shownInvites: List<ReceivedInvite> get() = if (enabled) invites else emptyList()
}

/** Where an accepted invite goes: the space chat, with its welcome (SI3). */
data class InviteAccepted(val spaceId: String, val spaceName: String)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatsSpacesViewModel(
    private val spaces: SpacesRepository,
    /** The space ids of the private chats on this phone (`Chat.viaSpaceId`). */
    viaSpaceIds: Flow<Set<String>>,
    online: Flow<Boolean>,
    /** How long Undo stays after Decline before SP22 is sent (SI3b: "a few seconds"). */
    private val undoMs: Long = 5_000,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatsSpacesUiState())
    val state: StateFlow<ChatsSpacesUiState> = _state.asStateFlow()

    // A channel, so an accept answered while Chats is between screens still opens the space once it is collected.
    private val _accepted = kotlinx.coroutines.channels.Channel<InviteAccepted>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val accepted: Flow<InviteAccepted> = _accepted.receiveAsFlow()

    private val declineJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch { spaces.enabled.collect { on -> _state.update { it.copy(enabled = on) }; if (on) refresh() } }
        viewModelScope.launch { spaces.invites.collect { list -> _state.update { it.copy(invites = list) } } }
        viewModelScope.launch { spaces.mySpaces.collect { list -> _state.update { it.copy(mine = list) } } }
        viewModelScope.launch { online.collect { on -> _state.update { it.copy(online = on) } } }
        viewModelScope.launch {
            // Names come from my spaces, else the space as the phone last read it (a visitor's private chat).
            combine(viaSpaceIds.distinctUntilChanged(), spaces.mySpaces) { ids, mine -> ids to mine }
                .flatMapLatest { (ids, mine) ->
                    val known = mine.associate { it.space.spaceId to it.space.name }
                    val missing = ids.filter { it !in known }
                    if (missing.isEmpty()) flowOf(ids.associateWith { known.getValue(it) })
                    else combine(missing.map { id -> spaces.space(id).map { s -> id to s?.name } }) { pairs ->
                        ids.mapNotNull { id -> (known[id] ?: pairs.firstOrNull { it.first == id }?.second)?.let { id to it } }.toMap()
                    }
                }
                .collect { names -> _state.update { it.copy(spaceNames = names) } }
        }
    }

    /** On every Chats open: the invite cards are live only (not expired, cancelled or answered). */
    fun refresh() {
        if (!_state.value.enabled) return
        viewModelScope.launch { spaces.refreshInvites() }
    }

    /** SI3: online only, the server's answer decides; then the space chat opens. */
    fun onAccept(invite: ReceivedInvite) {
        if (!_state.value.online || invite.inviteId in _state.value.accepting) return
        _state.update { it.copy(accepting = it.accepting + invite.inviteId) }
        viewModelScope.launch {
            when (val r = spaces.acceptInvite(invite.inviteId)) {
                is AppResult.Success -> {
                    _state.update { it.copy(accepting = it.accepting - invite.inviteId, toast = "You joined ${r.value.name}") }
                    _accepted.trySend(InviteAccepted(r.value.spaceId, r.value.name))
                }
                is AppResult.Failure -> _state.update { it.copy(accepting = it.accepting - invite.inviteId, toast = acceptError(r.error)) }
            }
        }
    }

    /** SI3b: fold the card with Undo; nothing is sent for a few seconds, and nobody is told either way. */
    fun onDecline(invite: ReceivedInvite) {
        if (!_state.value.online) return
        val id = invite.inviteId
        _state.update { it.copy(declining = it.declining + id) }
        declineJobs[id]?.cancel()
        declineJobs[id] = viewModelScope.launch {
            delay(undoMs)
            val r = spaces.declineInvite(id)
            declineJobs.remove(id)
            _state.update { it.copy(declining = it.declining - id, toast = (r as? AppResult.Failure)?.let { f -> ErrorMessages.forError(f.error) }) }
        }
    }

    fun onUndo(inviteId: String) {
        declineJobs.remove(inviteId)?.cancel()
        _state.update { it.copy(declining = it.declining - inviteId) }
    }

    fun onToastShown() = _state.update { it.copy(toast = null) }

    /** Leaving Chats mid-Undo still sends the decline: the person said no, and the card mustn't come back. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onCleared() {
        val pending = declineJobs.keys.toList()
        declineJobs.values.forEach { it.cancel() }
        if (pending.isNotEmpty()) kotlinx.coroutines.GlobalScope.launch { pending.forEach { spaces.declineInvite(it) } }
        super.onCleared()
    }
}

private fun acceptError(error: AppError): String = when ((error as? AppError.Api)?.code) {
    "spaces.invite_not_found" -> "This invite has ended. Ask for a new one."
    "spaces.banned" -> "You can’t join this space."
    else -> ErrorMessages.forError(error)
}
