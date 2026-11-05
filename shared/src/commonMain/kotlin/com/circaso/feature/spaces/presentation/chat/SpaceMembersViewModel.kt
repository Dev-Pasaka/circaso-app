package com.circaso.feature.spaces.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.core.sync.nowMillis
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.spaces.domain.MemberFilter
import com.circaso.feature.spaces.domain.MuteDuration
import com.circaso.feature.spaces.domain.Space
import com.circaso.feature.spaces.domain.SpaceMember
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.domain.SpaceVisibility
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

/** A member's popover (SR1b): its items, the mute durations inline, or a destructive confirm inside it (SR1c). */
enum class MemberMenuMode { Items, Mute, ConfirmRemove, ConfirmBan }
data class MemberMenu(val userId: String, val mode: MemberMenuMode = MemberMenuMode.Items)

data class SpaceMembersUiState(
    val spaceId: String,
    val space: Space? = null,
    val myUserId: String? = null,
    val online: Boolean = true,
    val filter: MemberFilter = MemberFilter.All,
    val query: String = "",
    val team: List<SpaceMember> = emptyList(),
    val members: List<SpaceMember> = emptyList(),
    val count: Int = 0,
    val next: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    /** The list didn't load and there is nothing to show: plain words, Try Again, the reference. */
    val error: String? = null,
    val errorRef: String? = null,
    val menu: MemberMenu? = null,
    val busy: Boolean = false,
    val now: Long = nowMillis(),
) {
    val myRole: SpaceRole? get() = space?.me?.role
    val paidSpace: Boolean get() = space?.visibility == SpaceVisibility.MembersOnly
    val menuMember: SpaceMember? get() = menu?.let { m -> (team + members).firstOrNull { it.person.userId == m.userId } }

    private fun below(m: SpaceMember): Boolean {
        val mine = myRole ?: return false
        val theirs = m.role ?: SpaceRole.Member
        return m.person.userId != myUserId && theirs != SpaceRole.Owner && theirs.rank < mine.rank
    }
    fun isMe(m: SpaceMember) = m.person.userId == myUserId
    /** Mute: moderators and up, on someone below them. */
    fun canMute(m: SpaceMember) = myRole?.isModerator == true && below(m) && m.bannedAt == null
    /** Remove and ban: admins and up, on someone below them. */
    fun canRemove(m: SpaceMember) = myRole?.isAdmin == true && below(m) && m.bannedAt == null
    fun canUnban(m: SpaceMember) = myRole?.isAdmin == true && m.bannedAt != null
    /** The roles the caller may give this person: the owner any below owner; admins make or unmake moderators. */
    fun rolesFor(m: SpaceMember): List<SpaceRole> {
        if (!below(m) || m.bannedAt != null) return emptyList()
        val theirs = m.role ?: SpaceRole.Member
        val choices = when (myRole) {
            SpaceRole.Owner -> listOf(SpaceRole.Moderator, SpaceRole.Admin, SpaceRole.Member)
            SpaceRole.Admin -> listOf(SpaceRole.Moderator, SpaceRole.Member)
            else -> emptyList()
        }
        return choices.filter { it != theirs }
    }
    /** SR1d: a moderator sees only what they may do, and one line saying who can do the rest. */
    val limitedNote: String? get() = if (myRole == SpaceRole.Moderator) "Only admins can remove people or change roles." else null
}

sealed interface SpaceMembersEvent {
    data class OpenChat(val chatId: String) : SpaceMembersEvent
    data class Toast(val text: String) : SpaceMembersEvent
}

/** SR1: members and roles for admins and moderators (search, filters, a popover per row). Every action is live. */
class SpaceMembersViewModel(
    private val spaceId: String,
    private val spaces: SpacesRepository,
    private val chats: ChatsRepository,
    connection: ConnectionState,
    private val ownUserId: suspend () -> String?,
) : ViewModel() {
    private val local = MutableStateFlow(SpaceMembersUiState(spaceId))
    private val _events = Channel<SpaceMembersEvent>(Channel.BUFFERED)
    val events: Flow<SpaceMembersEvent> = _events.receiveAsFlow()
    private var searchJob: Job? = null

    val state: StateFlow<SpaceMembersUiState> = combine(local, spaces.space(spaceId), connection.status) { ui, space, c ->
        ui.copy(space = space, online = c == ConnectionStatus.Online, now = nowMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch { local.update { it.copy(myUserId = ownUserId()) } }
        viewModelScope.launch { spaces.refreshSpace(spaceId) }
        load()
    }

    fun load() {
        val s = local.value
        local.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = spaces.members(spaceId, s.filter, s.query.trim().trimStart('@').takeIf { it.isNotEmpty() })) {
                is AppResult.Success -> local.update {
                    it.copy(loading = false, team = r.value.team, members = r.value.members, count = r.value.count, next = r.value.next)
                }
                is AppResult.Failure -> local.update {
                    val keep = it.team.isNotEmpty() || it.members.isNotEmpty()
                    it.copy(loading = false, error = if (keep && r.error is AppError.Network) null else if (r.error is AppError.Network) ErrorMessages.Offline else spacesErrorText(r.error), errorRef = ErrorMessages.reference(r.error))
                }
            }
        }
    }

    fun onLoadMore() {
        val s = local.value
        val cursor = s.next ?: return
        if (s.loadingMore) return
        local.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val r = spaces.members(spaceId, s.filter, s.query.trim().trimStart('@').takeIf { it.isNotEmpty() }, cursor)
            local.update { st ->
                if (r is AppResult.Success) st.copy(loadingMore = false, members = (st.members + r.value.members).distinctBy { it.person.userId }, next = r.value.next)
                else st.copy(loadingMore = false)
            }
        }
    }

    /** Searching waits for a short pause in typing, so each letter isn't a request. */
    fun onQuery(text: String) {
        local.update { it.copy(query = text.take(60)) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(300); load() }
    }

    fun onFilter(filter: MemberFilter) {
        if (filter == local.value.filter) return
        local.update { it.copy(filter = filter, members = emptyList(), next = null) }
        load()
    }

    fun onOpenMenu(m: SpaceMember) = local.update { it.copy(menu = MemberMenu(m.person.userId)) }
    fun onDismissMenu() = local.update { it.copy(menu = null) }
    fun onMenuMode(mode: MemberMenuMode) = local.update { it.copy(menu = it.menu?.copy(mode = mode)) }

    fun onRole(m: SpaceMember, role: SpaceRole) = act("${m.person.firstName} is now ${role.article()}") { spaces.setRole(spaceId, m.person.userId, role) }
    fun onMute(m: SpaceMember, d: MuteDuration) = act("${m.person.firstName} is muted ${if (d == MuteDuration.UntilUnmuted) "until you unmute them" else "for ${d.label}"}") { spaces.mute(spaceId, m.person.userId, d) }
    fun onUnmute(m: SpaceMember) = act("${m.person.firstName} can post again") { spaces.unmute(spaceId, m.person.userId) }
    fun onRemove(m: SpaceMember) = act("${m.person.firstName} was removed") { spaces.remove(spaceId, m.person.userId) }
    fun onBan(m: SpaceMember) = act("${m.person.firstName} is banned") { spaces.ban(spaceId, m.person.userId, true) }
    fun onUnban(m: SpaceMember) = act("${m.person.firstName} can follow again") { spaces.ban(spaceId, m.person.userId, false) }

    /** The owner talks to a member in the space's private chat; admins and moderators in their own direct chat. */
    fun onMessage(m: SpaceMember) {
        local.update { it.copy(menu = null) }
        if (!state.value.online) return
        viewModelScope.launch {
            val r = if (state.value.myRole == SpaceRole.Owner) chats.openViaSpace(m.person.userId, spaceId) else chats.openDirect(m.person.userId)
            when (r) {
                is AppResult.Success -> _events.send(SpaceMembersEvent.OpenChat(r.value.chatId))
                is AppResult.Failure -> _events.send(SpaceMembersEvent.Toast(spacesErrorText(r.error)))
            }
        }
    }

    private fun act(done: String, call: suspend () -> AppResult<*>) {
        if (!state.value.online || local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = call()
            local.update { it.copy(busy = false, menu = null) }
            if (r is AppResult.Success) { _events.send(SpaceMembersEvent.Toast(done)); load() }
            else _events.send(SpaceMembersEvent.Toast(spacesErrorText((r as AppResult.Failure).error)))
        }
    }
}

internal fun SpaceRole.article(): String = when (this) {
    SpaceRole.Admin -> "an admin"
    SpaceRole.Moderator -> "a moderator"
    SpaceRole.Owner -> "the owner"
    SpaceRole.Member -> "a member"
}
