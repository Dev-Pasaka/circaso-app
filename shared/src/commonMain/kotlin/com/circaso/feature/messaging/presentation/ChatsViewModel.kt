package com.circaso.feature.messaging.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ChatNotifications
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.core.sync.Outbox
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.CreateGroupUseCase
import com.circaso.feature.messaging.domain.GroupDraft
import com.circaso.feature.messaging.domain.GroupInvite
import com.circaso.feature.messaging.domain.GroupInvitesRepository
import com.circaso.feature.messaging.domain.ObserveChatsUseCase
import com.circaso.feature.messaging.domain.OpenDirectChatUseCase
import com.circaso.feature.messaging.domain.RefreshChatsUseCase
import com.circaso.core.network.newIdempotencyKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Design C1: the chat list, from the phone, refreshed quietly. Moments (2026-11-05, in place of Spaces) is a filter
 * like the others, but it swaps the chats for the Moments list on the same screen.
 */
enum class ChatFilter(val label: String) { All("All"), Unread("Unread"), Groups("Groups"), Moments("Moments") }

data class ChatsUiState(
    val chats: List<ChatSummary> = emptyList(),
    val loaded: Boolean = false,
    val query: String = "",
    val online: Boolean = true,
    val waitingToSend: Int = 0,
    val error: String? = null,
    /** Design A10: Asha introduces herself the first time the tab opens. */
    val meetAsha: Boolean = false,
    val firstName: String? = null,
    /** The wallet's rate to the person's currency, for "Bob sent KES 650" in the list. */
    val fiat: com.circaso.feature.wallet.domain.FiatValue? = null,
    /** Each held coin's value in the person's money, so a preview in EURC reads like USDC's. */
    val coinRates: Map<String, String> = emptyMap(),
    /** The chip under the search bar: every chat, the unread ones, groups, or the Moments list (as WhatsApp). */
    val filter: ChatFilter = ChatFilter.All,
    /** Group invites waiting for my answer (2026-11-09), newest first: cards at the top of the list. */
    val invites: List<GroupInvite> = emptyList(),
    /** The invites being answered right now (their buttons spin; the others stay usable). */
    val answering: Set<String> = emptySet(),
    /** The invite whose preview sheet is open. */
    val previewing: GroupInvite? = null,
    /** An answer that failed, said on its card: the invite's id and the words. */
    val inviteError: Pair<String, String>? = null,
    /** A group just joined: the screen opens its chat once, then clears it. */
    val joinedChatId: String? = null,
    /**
     * A one-off line for an answer whose card has gone (declined, or no longer open), shown as a toast so the card
     * leaving is never silent.
     */
    val notice: String? = null,
    /** People I've blocked (2026-11-10): their direct chats leave the list until they're unblocked. Groups stay. */
    val blocked: Set<String> = emptySet(),
) {
    /** Invites show on All and Groups, above the chats, while nothing is being searched. */
    val showInvites: Boolean get() = invites.isNotEmpty() && query.isBlank() && (filter == ChatFilter.All || filter == ChatFilter.Groups)

    val shown: List<ChatSummary> get() = chats
        .filter { s -> s.chat.peerId == null || s.chat.peerId !in blocked }
        .filter { s -> when (filter) { ChatFilter.All -> true; ChatFilter.Unread -> s.unread > 0; ChatFilter.Groups -> s.chat.kind == com.circaso.feature.messaging.domain.ChatKind.Group; ChatFilter.Moments -> true } }
        .let { list -> if (query.isBlank()) list else list.filter { it.chat.title.contains(query.trim(), ignoreCase = true) } }
}

class ChatsViewModel(
    observeChats: ObserveChatsUseCase,
    private val refreshChats: RefreshChatsUseCase,
    connection: ConnectionState,
    outbox: Outbox,
    notifications: ChatNotifications,
    private val preferences: com.circaso.core.datastore.AppPreferences,
    observeProfile: com.circaso.feature.profile.domain.ObserveProfileUseCase,
    observeWallet: com.circaso.feature.wallet.domain.ObserveWalletUseCase? = null,
    /** Group invites (2026-11-09); null in previews and tests that don't need them. */
    private val invites: GroupInvitesRepository? = null,
    /** Blocked people, whose direct chats are hidden; null in previews and tests that don't need them. */
    observeBlocks: com.circaso.feature.contacts.domain.ObserveBlocksUseCase? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatsUiState())
    val state: StateFlow<ChatsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { preferences.flag(MetAsha).collect { met -> _state.update { it.copy(meetAsha = !met) } } }
        observeWallet?.let { wallet -> viewModelScope.launch { wallet().collect { w -> _state.update { it.copy(fiat = w?.fiat, coinRates = com.circaso.feature.wallet.domain.coinUnitRates(w)) } } } }
        viewModelScope.launch { observeProfile().collect { p -> _state.update { it.copy(firstName = p?.displayName?.trim()?.substringBefore(' ')?.takeIf { n -> n.isNotEmpty() }) } } }
    }

    fun onMeetAshaDone() { viewModelScope.launch { preferences.setFlag(MetAsha, true) } }

    private companion object { const val MetAsha = "asha.met" }

    init {
        viewModelScope.launch { observeChats().collect { list -> _state.update { it.copy(chats = list, loaded = true) } } }
        observeBlocks?.let { blocks -> viewModelScope.launch { blocks().collect { people -> _state.update { it.copy(blocked = people.mapTo(HashSet()) { p -> p.userId }) } } } }
        viewModelScope.launch { connection.status.collect { s -> _state.update { it.copy(online = s == ConnectionStatus.Online) } } }
        viewModelScope.launch { outbox.waitingMessages.collect { n -> _state.update { it.copy(waitingToSend = n) } } }
        viewModelScope.launch { refreshChats() }
        viewModelScope.launch { notifications.ensurePermission() } // asked here, on the Chats tab, never at launch
    }

    fun onQuery(text: String) = _state.update { it.copy(query = text) }
    fun onFilter(filter: ChatFilter) = _state.update { it.copy(filter = filter) }

    fun refresh() {
        viewModelScope.launch { val r = refreshChats(); if (r is AppResult.Failure) _state.update { it.copy(error = ErrorMessages.forError(r.error)) } }
        invites?.let { repo -> viewModelScope.launch { repo.refresh() } }
    }

    // ---- group invites (2026-11-09): accept or decline, online only; nothing is queued or shown as done early

    init {
        invites?.let { repo ->
            viewModelScope.launch {
                repo.invites.collect { list ->
                    _state.update { s ->
                        // A preview whose invite went (cancelled, answered on another screen) closes with it.
                        val preview = s.previewing?.let { p -> list.firstOrNull { it.inviteId == p.inviteId } }
                        s.copy(invites = list, previewing = preview)
                    }
                }
            }
            viewModelScope.launch { repo.refresh() }
        }
    }

    fun onPreview(invite: GroupInvite?) = _state.update { it.copy(previewing = invite) }

    /** The invite cards' and preview's answers. */
    val inviteActions = InviteActions(onAccept = ::onAccept, onDecline = ::onDecline, onPreview = { onPreview(it) })

    fun onAccept(inviteId: String) = answer(inviteId, accept = true)

    fun onDecline(inviteId: String) = answer(inviteId, accept = false)

    private fun answer(inviteId: String, accept: Boolean) {
        val repo = invites ?: return
        val s = _state.value
        if (!s.online || inviteId in s.answering) return
        _state.update { it.copy(answering = it.answering + inviteId, inviteError = it.inviteError?.takeIf { e -> e.first != inviteId }) }
        viewModelScope.launch {
            val failure: com.circaso.core.common.AppError?
            var joined: String? = null
            if (accept) {
                when (val r = repo.accept(inviteId)) {
                    is AppResult.Success -> { failure = null; joined = r.value.chatId }
                    is AppResult.Failure -> failure = r.error
                }
            } else {
                failure = (repo.decline(inviteId) as? AppResult.Failure)?.error
            }
            // An invite no longer open has already left the list: its card can't carry the error, so a toast does.
            val gone = (failure as? com.circaso.core.common.AppError.Api)?.code == "messaging.invite_not_found"
            _state.update {
                it.copy(
                    answering = it.answering - inviteId,
                    inviteError = failure?.takeIf { !gone }?.let { e -> inviteId to inviteErrorText(e) },
                    previewing = if (failure == null || gone) null else it.previewing,
                    joinedChatId = joined ?: it.joinedChatId,
                    notice = when {
                        gone -> inviteErrorText(failure!!)
                        failure == null && !accept -> "Invite declined."
                        else -> it.notice
                    },
                )
            }
        }
    }

    /** The joined group's chat was opened, or the screen left before it could be (it never opens later, out of context). */
    fun onJoinedOpened() = _state.update { it.copy(joinedChatId = null) }

    fun onNoticeShown() = _state.update { it.copy(notice = null) }
}

/** An invite answer's failure in the person's words; a full group says so plainly. */
internal fun inviteErrorText(error: com.circaso.core.common.AppError): String = when ((error as? com.circaso.core.common.AppError.Api)?.code) {
    "messaging.group_full" -> "This group is full. Ask an admin to make room, then try again."
    "messaging.invite_not_found" -> "This invite is no longer open."
    else -> ErrorMessages.forError(error) + (ErrorMessages.reference(error)?.let { " Ref $it" } ?: "")
}
