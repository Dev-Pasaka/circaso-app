package com.circaso.feature.contacts.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ContactsReader
import com.circaso.core.platform.PhoneBookNumbers
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.BlockUseCase
import com.circaso.feature.contacts.domain.ConnectUseCase
import com.circaso.feature.contacts.domain.ConnectVia
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.CreateInviteUseCase
import com.circaso.feature.contacts.domain.Invite
import com.circaso.feature.contacts.domain.ObserveBlocksUseCase
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.contacts.domain.RefreshBlocksUseCase
import com.circaso.feature.contacts.domain.RefreshContactsUseCase
import com.circaso.feature.contacts.domain.RemoveContactUseCase
import com.circaso.feature.contacts.domain.SyncPhoneBookUseCase
import com.circaso.feature.contacts.domain.UnblockUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the person is being asked to confirm about a contact. */
sealed interface ContactAction {
    data class Remove(val contact: Contact) : ContactAction
    data class Block(val contact: Contact) : ContactAction
}

data class ContactsUiState(
    val contacts: List<Contact> = emptyList(),
    val query: String = "",
    val connectInput: String = "",
    val connecting: Boolean = false,
    val creatingInvite: Boolean = false,
    /** The invite's text for the system share sheet; the screen opens it once and clears it. */
    val share: String? = null,
    /** The "Add by Username" sheet is open. */
    val adding: Boolean = false,
    /** The contact whose actions sheet is open. */
    val selected: Contact? = null,
    /** The explainer shown before the system permission prompt (design A9). */
    val showFindFriends: Boolean = false,
    val syncing: Boolean = false,
    val pendingAction: ContactAction? = null,
    val online: Boolean = true,
    val loaded: Boolean = false,
    val message: String? = null,
) {
    val visible: List<Contact>
        get() = (if (query.isBlank()) contacts else contacts.filter {
            it.person.displayName.contains(query.trim(), ignoreCase = true) || it.person.handle?.contains(query.trim().removePrefix("@"), ignoreCase = true) == true
        }).sortedBy { it.person.displayName.lowercase() }

    /** [visible] in letter sections (A, B, …, then # for names that don't start with a letter). */
    val sections: List<Pair<String, List<Contact>>>
        get() = visible.groupBy { c -> c.person.displayName.trim().firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#" }
            .toList().sortedWith(compareBy({ it.first == "#" }, { it.first }))

    /** What is typed looks like a handle: 3 or more letters, digits, dots or underscores (after an optional @). */
    val connectReady: Boolean get() = connectInput.trim().removePrefix("@").let { it.length >= 3 && it.all { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '_' } }
}

/** Your people. Removing and blocking are saved at once and sent when online; connecting and invites need the server. */
class ContactsViewModel(
    observeContacts: ObserveContactsUseCase,
    private val refreshContacts: RefreshContactsUseCase,
    private val connect: ConnectUseCase,
    private val createInvite: CreateInviteUseCase,
    private val removeContact: RemoveContactUseCase,
    private val block: BlockUseCase,
    connection: ConnectionState,
    private val reader: ContactsReader,
    private val syncPhoneBook: SyncPhoneBookUseCase,
) : ViewModel() {
    private val local = MutableStateFlow(ContactsUiState())
    val state: StateFlow<ContactsUiState> = combine(observeContacts(), local, connection.status) { contacts, ui, status ->
        ui.copy(contacts = contacts, online = status == ConnectionStatus.Online, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactsUiState())

    init { viewModelScope.launch { refreshContacts() } }

    fun onQuery(text: String) = local.update { it.copy(query = text) }
    fun onConnectInput(text: String) = local.update { it.copy(connectInput = text, message = null) }

    fun onConnect() {
        val handle = local.value.connectInput.trim().removePrefix("@")
        if (handle.isEmpty() || local.value.connecting) return
        local.update { it.copy(connecting = true, message = null) }
        viewModelScope.launch {
            when (val r = connect(ConnectVia.Handle, handle)) {
                is AppResult.Success -> local.update {
                    it.copy(connecting = false, connectInput = "", adding = false, message = "Connected with ${r.value.person.displayName}." + if (r.value.created) "" else " They were already in your contacts.")
                }
                is AppResult.Failure -> local.update { it.copy(connecting = false, message = withRef(r.error)) }
            }
        }
    }

    fun onAddOpen() = local.update { it.copy(adding = true, message = null) }
    fun onAddDismissed() = local.update { it.copy(adding = false, connectInput = "") }

    /** Invite a Friend: a fresh link (7 days) straight into the system share sheet, with a line that says what it is. */
    fun onInvite() {
        if (local.value.creatingInvite) return
        local.update { it.copy(creatingInvite = true, message = null) }
        viewModelScope.launch {
            when (val r = createInvite()) {
                is AppResult.Success -> local.update { it.copy(creatingInvite = false, share = com.circaso.feature.messaging.presentation.NewChatViewModel.inviteText(r.value.url)) }
                is AppResult.Failure -> local.update { it.copy(creatingInvite = false, message = withRef(r.error)) }
            }
        }
    }

    fun onShared() = local.update { it.copy(share = null) }
    fun onMessageShown() = local.update { it.copy(message = null) }

    private fun withRef(error: AppError) = ErrorMessages.forError(error) + (ErrorMessages.reference(error)?.let { " Ref $it" } ?: "")

    fun onFindFriends() = local.update { it.copy(showFindFriends = true, message = null) }
    fun onFindFriendsDismissed() = local.update { it.copy(showFindFriends = false) }

    /** "Allow Access": the system prompt, then read the numbers (never names) and send only the new ones. */
    fun onAllowAccess() {
        if (local.value.syncing) return
        local.update { it.copy(showFindFriends = false, syncing = true, message = null) }
        viewModelScope.launch {
            val message: String = if (!reader.requestAccess()) {
                "Circaso can't see your contacts. You can allow it in your phone's settings."
            } else when (val r = syncPhoneBook(PhoneBookNumbers.normalise(reader.readNumbers()))) {
                is AppResult.Success -> when (val n = r.value.matches.size) {
                    0 -> if (r.value.numbersSent == 0) "You're up to date." else "No one from your contacts is on Circaso yet."
                    1 -> "Found 1 friend on Circaso."
                    else -> "Found $n friends on Circaso."
                }
                is AppResult.Failure -> (r.error as? AppError.Api)?.takeIf { it.code == "request.rate_limited" }?.let { "You can look for friends 5 times an hour. Try again a little later." }
                    ?: withRef(r.error)
            }
            local.update { it.copy(syncing = false, message = message) }
        }
    }

    fun onSelect(contact: Contact) = local.update { it.copy(selected = contact) }
    fun onSelectedDismissed() = local.update { it.copy(selected = null) }
    fun onRemoveRequested(contact: Contact) = local.update { it.copy(selected = null, pendingAction = ContactAction.Remove(contact)) }
    fun onBlockRequested(contact: Contact) = local.update { it.copy(selected = null, pendingAction = ContactAction.Block(contact)) }
    fun onActionDismissed() = local.update { it.copy(pendingAction = null) }

    fun onActionConfirmed() {
        val action = local.value.pendingAction ?: return
        local.update { it.copy(pendingAction = null) }
        viewModelScope.launch {
            when (action) {
                is ContactAction.Remove -> removeContact(action.contact.person.userId)
                is ContactAction.Block -> { block(action.contact.person); removeContact(action.contact.person.userId) }
            }
        }
    }
}

data class BlockedUiState(val blocked: List<Person> = emptyList(), val message: String? = null)

class BlockedViewModel(
    observeBlocks: ObserveBlocksUseCase,
    private val refreshBlocks: RefreshBlocksUseCase,
    private val unblock: UnblockUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(BlockedUiState())
    val state: StateFlow<BlockedUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { observeBlocks().collect { list -> _state.update { it.copy(blocked = list) } } }
        viewModelScope.launch { refreshBlocks() }
    }

    fun onUnblock(person: Person) {
        viewModelScope.launch { unblock(person.userId) }
    }
}
