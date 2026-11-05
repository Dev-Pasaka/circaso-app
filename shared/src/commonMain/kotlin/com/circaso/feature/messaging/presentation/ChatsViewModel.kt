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
    /** The chip under the search bar: every chat, the unread ones, groups, or the Moments list (as WhatsApp). */
    val filter: ChatFilter = ChatFilter.All,
) {
    val shown: List<ChatSummary> get() = chats
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
) : ViewModel() {
    private val _state = MutableStateFlow(ChatsUiState())
    val state: StateFlow<ChatsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { preferences.flag(MetAsha).collect { met -> _state.update { it.copy(meetAsha = !met) } } }
        observeWallet?.let { wallet -> viewModelScope.launch { wallet().collect { w -> _state.update { it.copy(fiat = w?.fiat) } } } }
        viewModelScope.launch { observeProfile().collect { p -> _state.update { it.copy(firstName = p?.displayName?.trim()?.substringBefore(' ')?.takeIf { n -> n.isNotEmpty() }) } } }
    }

    fun onMeetAshaDone() { viewModelScope.launch { preferences.setFlag(MetAsha, true) } }

    private companion object { const val MetAsha = "asha.met" }

    init {
        viewModelScope.launch { observeChats().collect { list -> _state.update { it.copy(chats = list, loaded = true) } } }
        viewModelScope.launch { connection.status.collect { s -> _state.update { it.copy(online = s == ConnectionStatus.Online) } } }
        viewModelScope.launch { outbox.waitingMessages.collect { n -> _state.update { it.copy(waitingToSend = n) } } }
        viewModelScope.launch { refreshChats() }
        viewModelScope.launch { notifications.ensurePermission() } // asked here, on the Chats tab, never at launch
    }

    fun onQuery(text: String) = _state.update { it.copy(query = text) }
    fun onFilter(filter: ChatFilter) = _state.update { it.copy(filter = filter) }

    fun refresh() { viewModelScope.launch { val r = refreshChats(); if (r is AppResult.Failure) _state.update { it.copy(error = ErrorMessages.forError(r.error)) } } }
}
