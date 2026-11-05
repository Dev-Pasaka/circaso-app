package com.circaso.feature.spaces.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.profile.domain.FindByHandleUseCase
import com.circaso.feature.spaces.domain.InviteOutcome
import com.circaso.feature.spaces.domain.InviteState
import com.circaso.feature.spaces.domain.JoinRequest
import com.circaso.feature.spaces.domain.SentInvite
import com.circaso.feature.spaces.domain.Space
import com.circaso.feature.spaces.domain.SpaceEvent
import com.circaso.feature.spaces.domain.SpacePerson
import com.circaso.feature.spaces.domain.SpacesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where one person stands in SI1: Invite, going, Invited, already a Member, or can't be reached. */
enum class InviteRow { Invite, Busy, Invited, Member, Unreachable }

data class SpaceInviteUiState(
    val spaceId: String,
    val space: Space? = null,
    val online: Boolean = true,
    val query: String = "",
    val contacts: List<SpacePerson> = emptyList(),
    val rows: Map<String, InviteRow> = emptyMap(),
    /** SI1b: typing @ looks the person up by username. */
    val searching: Boolean = false,
    val found: SpacePerson? = null,
    val notFound: Boolean = false,
) {
    val handleQuery: String? get() = query.trim().takeIf { it.startsWith("@") && it.length > 1 }?.drop(1)
    /** Contacts matching what's typed; for an @ query these are SI1b's close matches (by handle or name). */
    val shown: List<SpacePerson>
        get() {
            val q = query.trim().trimStart('@')
            if (q.isEmpty()) return contacts
            return contacts.filter { it.displayName.contains(q, true) || it.handle?.contains(q, true) == true }
        }
    fun row(userId: String): InviteRow = rows[userId] ?: InviteRow.Invite
}

/** SI1: invite from contacts or by @username, in place (online-only). Share Link is for people to ask to join (SS3). */
class SpaceInviteViewModel(
    private val spaceId: String,
    private val spaces: SpacesRepository,
    observeContacts: ObserveContactsUseCase,
    private val findByHandle: FindByHandleUseCase,
    connection: ConnectionState,
) : ViewModel() {
    private val local = MutableStateFlow(SpaceInviteUiState(spaceId))
    private val _toast = Channel<String>(Channel.BUFFERED)
    val toasts: Flow<String> = _toast.receiveAsFlow()
    private var lookup: Job? = null

    val state: StateFlow<SpaceInviteUiState> = combine(local, spaces.space(spaceId), observeContacts(), connection.status) { ui, space, contacts, c ->
        ui.copy(
            space = space, online = c == ConnectionStatus.Online,
            contacts = contacts.map { SpacePerson(it.person.userId, it.person.displayName, it.person.handle, it.person.avatarUrl) }.sortedBy { it.displayName.lowercase() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch { spaces.refreshSpace(spaceId) }
        // Contacts already in the space show as Member (SP23's contacts list), so nobody is invited twice.
        viewModelScope.launch {
            val r = spaces.members(spaceId)
            if (r is AppResult.Success) {
                val inside = (r.value.contacts + r.value.team).map { it.person.userId }
                local.update { st -> st.copy(rows = st.rows + inside.associateWith { InviteRow.Member }) }
            }
        }
        // Invites already sent show as Invited (SP16), for admins.
        viewModelScope.launch {
            val r = spaces.spaceInvites(spaceId)
            if (r is AppResult.Success) {
                val invited = r.value.invites.filter { it.state == InviteState.Invited }.map { it.user.userId }
                local.update { st -> st.copy(rows = invited.associateWith { InviteRow.Invited } + st.rows) }
            }
        }
    }

    fun onQuery(text: String) {
        local.update { it.copy(query = text.take(60), found = null, notFound = false) }
        lookup?.cancel()
        val handle = local.value.handleQuery ?: return
        lookup = viewModelScope.launch {
            delay(400)
            if (!state.value.online) return@launch
            local.update { it.copy(searching = true) }
            when (val r = findByHandle(handle)) {
                is AppResult.Success -> local.update { it.copy(searching = false, found = SpacePerson(r.value.userId, r.value.displayName, r.value.handle, r.value.avatarUrl)) }
                is AppResult.Failure -> local.update { it.copy(searching = false, notFound = r.error !is AppError.Network) }
            }
        }
    }

    fun onInvite(person: SpacePerson) {
        val s = state.value
        if (!s.online || s.row(person.userId) != InviteRow.Invite) return
        local.update { it.copy(rows = it.rows + (person.userId to InviteRow.Busy)) }
        viewModelScope.launch {
            when (val r = spaces.invite(spaceId, listOf(person.userId))) {
                is AppResult.Success -> {
                    val outcome = r.value[person.userId] ?: InviteOutcome.Invited
                    val row = when (outcome) { InviteOutcome.Invited -> InviteRow.Invited; InviteOutcome.AlreadyMember -> InviteRow.Member; else -> InviteRow.Unreachable }
                    local.update { it.copy(rows = it.rows + (person.userId to row)) }
                    if (outcome == InviteOutcome.Banned) _toast.send("${person.firstName} is banned from this space. Unban them first.")
                    if (outcome == InviteOutcome.Unreachable) _toast.send("${person.firstName} can’t be invited.")
                }
                is AppResult.Failure -> {
                    local.update { it.copy(rows = it.rows - person.userId) }
                    _toast.send(spacesErrorText(r.error))
                }
            }
        }
    }
}

data class SpaceInvitesUiState(
    val spaceId: String,
    val space: Space? = null,
    val online: Boolean = true,
    val requests: List<JoinRequest> = emptyList(),
    val invites: List<SentInvite> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val errorRef: String? = null,
    /** Rows with a call going (their buttons show progress): user ids for requests, invite ids for invites. */
    val busy: Set<String> = emptySet(),
) {
    val empty: Boolean get() = !loading && error == null && requests.isEmpty() && invites.isEmpty()
}

/** SI4: people asking to join (Accept / Decline in place) and the invites sent with their state. Admins; online-only. */
class SpaceInvitesViewModel(
    private val spaceId: String,
    private val spaces: SpacesRepository,
    connection: ConnectionState,
) : ViewModel() {
    private val local = MutableStateFlow(SpaceInvitesUiState(spaceId))
    private val _toast = Channel<String>(Channel.BUFFERED)
    val toasts: Flow<String> = _toast.receiveAsFlow()

    val state: StateFlow<SpaceInvitesUiState> = combine(local, spaces.space(spaceId), connection.status) { ui, space, c ->
        ui.copy(space = space, online = c == ConnectionStatus.Online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        load()
        // A new request (space.request_new) or an accepted invite shows without a pull.
        viewModelScope.launch {
            spaces.events.collect { e -> if (e.spaceId == spaceId && (e is SpaceEvent.RequestNew || e is SpaceEvent.InviteAccepted)) load() }
        }
    }

    fun load() {
        local.update { it.copy(loading = it.requests.isEmpty() && it.invites.isEmpty(), error = null) }
        viewModelScope.launch {
            when (val r = spaces.spaceInvites(spaceId)) {
                is AppResult.Success -> local.update { it.copy(loading = false, requests = r.value.requests, invites = r.value.invites) }
                is AppResult.Failure -> local.update {
                    if (it.requests.isNotEmpty() || it.invites.isNotEmpty()) it.copy(loading = false)
                    else it.copy(loading = false, error = if (r.error is AppError.Network) "You’re offline. Invites and requests show when you’re back online." else spacesErrorText(r.error), errorRef = ErrorMessages.reference(r.error))
                }
            }
        }
    }

    fun onAnswer(request: JoinRequest, accept: Boolean) {
        val id = request.user.userId
        if (!state.value.online || id in local.value.busy) return
        local.update { it.copy(busy = it.busy + id) }
        viewModelScope.launch {
            val r = spaces.answerRequest(spaceId, id, accept)
            local.update { st -> st.copy(busy = st.busy - id, requests = if (r is AppResult.Success) st.requests.filterNot { it.user.userId == id } else st.requests) }
            _toast.send(if (r is AppResult.Success) (if (accept) "${request.user.firstName} is in" else "Declined. ${request.user.firstName} can ask again in 30 days.") else spacesErrorText((r as AppResult.Failure).error))
        }
    }

    fun onCancel(invite: SentInvite) {
        if (!state.value.online || invite.inviteId in local.value.busy) return
        local.update { it.copy(busy = it.busy + invite.inviteId) }
        viewModelScope.launch {
            val r = spaces.cancelInvite(spaceId, invite.inviteId)
            local.update { st -> st.copy(busy = st.busy - invite.inviteId, invites = if (r is AppResult.Success) st.invites.filterNot { it.inviteId == invite.inviteId } else st.invites) }
            if (r is AppResult.Failure) _toast.send(spacesErrorText(r.error))
        }
    }

    /** Invite Again on an expired invite: a fresh one, 14 more days. */
    fun onInviteAgain(invite: SentInvite) {
        if (!state.value.online || invite.inviteId in local.value.busy) return
        local.update { it.copy(busy = it.busy + invite.inviteId) }
        viewModelScope.launch {
            val r = spaces.invite(spaceId, listOf(invite.user.userId))
            local.update { it.copy(busy = it.busy - invite.inviteId) }
            if (r is AppResult.Success) load() else _toast.send(spacesErrorText((r as AppResult.Failure).error))
        }
    }
}
