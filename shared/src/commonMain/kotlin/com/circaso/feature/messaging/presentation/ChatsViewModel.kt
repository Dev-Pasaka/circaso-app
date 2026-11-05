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

/** Design C8: pick a person to chat with, or start a group. Opening a chat needs the server (online-only). */
data class NewChatUiState(
    val contacts: List<Contact> = emptyList(),
    val query: String = "",
    val online: Boolean = true,
    val opening: String? = null,
    val error: String? = null,
) {
    val shown: List<Contact>
        get() = if (query.isBlank()) contacts else contacts.filter { c -> c.person.displayName.contains(query.trim(), true) || c.person.handle?.contains(query.trim().removePrefix("@"), true) == true }
}

class NewChatViewModel(
    observeContacts: ObserveContactsUseCase,
    private val openDirect: OpenDirectChatUseCase,
    connection: ConnectionState,
) : ViewModel() {
    private val _state = MutableStateFlow(NewChatUiState())
    val state: StateFlow<NewChatUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { observeContacts().collect { list -> _state.update { it.copy(contacts = list) } } }
        viewModelScope.launch { connection.status.collect { s -> _state.update { it.copy(online = s == ConnectionStatus.Online) } } }
    }

    fun onQuery(text: String) = _state.update { it.copy(query = text, error = null) }

    /** Opens (or finds) the direct chat and hands its id to [onOpened]. */
    fun onPick(contact: Contact, onOpened: (chatId: String) -> Unit) {
        val s = _state.value
        if (!s.online || s.opening != null) return
        _state.update { it.copy(opening = contact.person.userId, error = null) }
        viewModelScope.launch {
            when (val r = openDirect(contact.person.userId)) {
                is AppResult.Success -> { _state.update { it.copy(opening = null) }; onOpened(r.value.chatId) }
                is AppResult.Failure -> _state.update { it.copy(opening = null, error = ErrorMessages.forError(r.error)) }
            }
        }
    }
}

/**
 * Designs D1 and GW1: a name, the people, and (group wallets on) the "Group wallet" switch with an optional target and
 * who approves payouts. Creating is online-only; the wallet is set up right after (GW2–GW5).
 */
data class NewGroupUiState(
    val name: String = "",
    val contacts: List<Contact> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val online: Boolean = true,
    val creating: Boolean = false,
    val error: String? = null,
    val notAdded: List<String> = emptyList(),
    /** Group wallets are on (the flag, and a server that has them): GW1's switch shows. */
    val walletsAvailable: Boolean = false,
    /** GW1's "Group wallet" switch: off by default. */
    val walletOn: Boolean = false,
    /** The optional target, typed in the person's currency when a rate is known, else in USDC. */
    val targetText: String = "",
    val by: com.circaso.feature.chama.presentation.GoalDeadline = com.circaso.feature.chama.presentation.GoalDeadline.None,
    val approvalPolicy: com.circaso.feature.chama.domain.ApprovalPolicy = com.circaso.feature.chama.domain.ApprovalPolicy.TwoAdmins,
    val fiat: com.circaso.feature.wallet.domain.FiatValue? = null,
    /** GW1d: the create call failed after its retries. Nothing was made; Try Again reuses the same key. */
    val failed: Boolean = false,
    val reference: String? = null,
) {
    val canCreate get() = online && !creating && name.trim().isNotEmpty() && selected.isNotEmpty()

    /** The target in USDC minor units, null when none was typed (or it isn't an amount). */
    val targetMinor: String? get() {
        val text = targetText.trim().ifEmpty { return null }
        val minor = fiat?.let { com.circaso.feature.wallet.domain.fiatToUsdcMinor(text, it.rate) } ?: com.circaso.feature.wallet.domain.MinorUnits.fromDisplay(text, 6)
        return minor?.takeIf { !com.circaso.feature.wallet.domain.MinorUnits.isZero(it) }
    }
    val shownContacts get() = if (query.isBlank()) contacts else contacts.filter { it.person.displayName.contains(query.trim(), true) }
    val selectedContacts get() = contacts.filter { it.person.userId in selected }
}

class NewGroupViewModel(
    observeContacts: ObserveContactsUseCase,
    private val createGroup: CreateGroupUseCase,
    connection: ConnectionState,
    groupWallets: com.circaso.core.flags.GroupWalletsFlag = com.circaso.core.flags.GroupWalletsFlag.off(),
    observeWallet: com.circaso.feature.wallet.domain.ObserveWalletUseCase? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(NewGroupUiState())
    val state: StateFlow<NewGroupUiState> = _state.asStateFlow()

    /** One key per attempt: a retry after a timeout carries on with the same group (guide, M5). */
    private var attemptKey: String? = null

    init {
        viewModelScope.launch { observeContacts().collect { list -> _state.update { it.copy(contacts = list) } } }
        viewModelScope.launch { connection.status.collect { s -> _state.update { it.copy(online = s == ConnectionStatus.Online) } } }
        viewModelScope.launch { groupWallets.enabled.collect { on -> _state.update { it.copy(walletsAvailable = on, walletOn = it.walletOn && on) } } }
        observeWallet?.let { w -> viewModelScope.launch { w().collect { wallet -> _state.update { it.copy(fiat = wallet?.fiat) } } } }
    }

    fun onWalletSwitch(on: Boolean) = _state.update { it.copy(walletOn = on && it.walletsAvailable && it.online) }
    fun onTarget(text: String) {
        val clean = text.filter { it.isDigit() || it == '.' }
        if (clean.count { it == '.' } > 1) return
        _state.update { it.copy(targetText = clean) }
    }
    fun onBy(by: com.circaso.feature.chama.presentation.GoalDeadline) = _state.update { it.copy(by = by) }
    fun onApprovalPolicy(policy: com.circaso.feature.chama.domain.ApprovalPolicy) = _state.update { it.copy(approvalPolicy = policy) }

    /** What the set-up carries on with once the group exists (GW2–GW5), or null without a wallet. */
    fun walletPlan(): com.circaso.feature.chama.presentation.GroupWalletPlan? {
        val s = _state.value
        if (!s.walletOn) return null
        return com.circaso.feature.chama.presentation.GroupWalletPlan(
            approvalPolicy = s.approvalPolicy, targetMinor = s.targetMinor, targetName = s.name.trim().takeIf { s.targetMinor != null },
            deadline = if (s.targetMinor != null) com.circaso.feature.chama.presentation.deadlineIso(s.by) else null,
        )
    }

    fun onName(text: String) { _state.update { it.copy(name = text, error = null) }; attemptKey = null }
    fun onQuery(text: String) = _state.update { it.copy(query = text) }
    fun onToggle(userId: String) { _state.update { it.copy(selected = if (userId in it.selected) it.selected - userId else it.selected + userId, error = null) }; attemptKey = null }

    fun onCreate(onCreated: (chatId: String) -> Unit) {
        val s = _state.value
        if (!s.canCreate) return
        val key = attemptKey ?: newIdempotencyKey().also { attemptKey = it }
        _state.update { it.copy(creating = true, error = null, failed = false, reference = null) }
        viewModelScope.launch {
            when (val r = createGroup(GroupDraft(s.name.trim(), s.selected.toList()), key)) {
                is AppResult.Success -> { _state.update { it.copy(creating = false, notAdded = r.value.notAdded) }; attemptKey = null; onCreated(r.value.chat.chatId) }
                // GW1d: the form is kept and the key too, so Try Again can't make a second group.
                is AppResult.Failure -> _state.update { it.copy(creating = false, error = ErrorMessages.forError(r.error), failed = true, reference = ErrorMessages.reference(r.error)) }
            }
        }
    }
}
