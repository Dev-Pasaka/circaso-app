package com.circaso.feature.messaging.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ContactsReader
import com.circaso.core.platform.PhoneBookNumbers
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.ConnectUseCase
import com.circaso.feature.contacts.domain.ConnectVia
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.contacts.domain.SyncPhoneBookUseCase
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.ObserveChatsUseCase
import com.circaso.feature.messaging.domain.OpenDirectChatUseCase
import com.circaso.feature.share.domain.CreateShareLinkUseCase
import com.circaso.feature.share.domain.ShareRequest
import com.circaso.feature.share.domain.ShareTarget
import com.circaso.feature.share.domain.ShareText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Someone to show in a people list: a contact, or the other person in a recent chat. */
data class PersonRow(val userId: String, val name: String, val handle: String?, val avatarUrl: String?, val about: String? = null) {
    /** The grey line under the name: the @handle, else their about line. */
    val sub: String? get() = handle?.let { "@$it" } ?: about?.takeIf { it.isNotBlank() }
}

/** A letter section of the people list ("A", "B", …, "#" for names that don't start with a letter). */
data class PeopleSection(val letter: String, val people: List<PersonRow>)

/** Phone-contacts matching from New Chat: not asked yet, matching now, done, or Circaso may not read the phone book. */
enum class PhoneBookState { Idle, Syncing, Done, Denied }

/**
 * Design C8: start a chat. A search field on top, then New Group, Add by @username and Invite a Friend, then the
 * people you chatted with lately and everyone on Circaso from your contacts in letter sections. Typing an @handle no
 * contact has offers to find that person on Circaso. Opening a chat, finding a handle and inviting need the server.
 */
data class NewChatUiState(
    val contacts: List<Contact> = emptyList(),
    /** The latest direct chats, newest first (recents at the top when there are some). */
    val recentChats: List<ChatSummary> = emptyList(),
    val query: String = "",
    val online: Boolean = true,
    /** False until the contacts have been read from the phone: the list shows placeholder rows meanwhile. */
    val loaded: Boolean = false,
    /** The person (user id) or handle being opened; one at a time. */
    val opening: String? = null,
    val creatingInvite: Boolean = false,
    /** The text the share sheet should open with; the screen opens it once and clears it. */
    val share: String? = null,
    val phoneBook: PhoneBookState = PhoneBookState.Idle,
    /** One line for a toast: an error (with its reference), or what the phone-book match found. */
    val message: String? = null,
) {
    private val cleanQuery get() = query.trim().removePrefix("@")

    private val people: List<PersonRow> get() = contacts.map { PersonRow(it.person.userId, it.person.displayName, it.person.handle, it.person.avatarUrl, it.person.about) }

    /** The people you chatted with lately (up to 4), only while not searching. */
    val recent: List<PersonRow> get() {
        if (query.isNotBlank()) return emptyList()
        val byId = people.associateBy { it.userId }
        return recentChats.asSequence()
            .filter { it.chat.kind == ChatKind.Direct && it.chat.peerId != null && it.chat.viaSpaceId == null }
            .mapNotNull { s ->
                val id = s.chat.peerId!!
                byId[id] ?: s.chat.members.firstOrNull { it.userId == id }?.let { PersonRow(id, it.displayName, it.handle, it.avatarUrl) }
            }
            .distinctBy { it.userId }.take(RecentCount).toList()
    }

    /** Everyone matching the search, A to Z. */
    val matches: List<PersonRow> get() {
        val q = cleanQuery
        val all = people.sortedBy { it.name.lowercase() }
        if (q.isEmpty()) return all
        return all.filter { it.name.contains(q, ignoreCase = true) || it.handle?.contains(q, ignoreCase = true) == true }
    }

    /** [matches] in letter sections, as the system contacts app does. */
    val sections: List<PeopleSection> get() = matches
        .groupBy { p -> p.name.trim().firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#" }
        .toList().sortedWith(compareBy({ it.first == "#" }, { it.first })).map { (letter, list) -> PeopleSection(letter, list) }

    /**
     * The handle to look up on Circaso: what is typed looks like a handle (3+ letters, digits, dots, underscores) and no
     * contact already has exactly it.
     */
    val handleToFind: String? get() {
        val q = cleanQuery.lowercase()
        if (q.length < 3 || !HandlePattern.matches(q)) return null
        return q.takeIf { people.none { it.handle.equals(q, ignoreCase = true) } }
    }

    private companion object {
        const val RecentCount = 4
        val HandlePattern = Regex("^[a-z0-9._]+$")
    }
}

class NewChatViewModel(
    observeContacts: ObserveContactsUseCase,
    private val openDirect: OpenDirectChatUseCase,
    connection: ConnectionState,
    observeChats: ObserveChatsUseCase? = null,
    private val connect: ConnectUseCase? = null,
    private val createShareLink: CreateShareLinkUseCase? = null,
    private val reader: ContactsReader? = null,
    private val syncPhoneBook: SyncPhoneBookUseCase? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(NewChatUiState())
    val state: StateFlow<NewChatUiState> = _state.asStateFlow()

    /** Direct chats already on this phone, by the other person: they open at once, online or not. */
    private var existing: Map<String, String> = emptyMap()

    init {
        viewModelScope.launch { observeContacts().collect { list -> _state.update { it.copy(contacts = list, loaded = true) } } }
        viewModelScope.launch { connection.status.collect { s -> _state.update { it.copy(online = s == ConnectionStatus.Online) } } }
        observeChats?.let { chats ->
            viewModelScope.launch {
                chats().collect { list ->
                    val direct = list.filter { it.chat.kind == ChatKind.Direct && it.chat.viaSpaceId == null }
                    val recent = direct.sortedByDescending { it.latest?.orderAt ?: 0L }.filter { it.latest != null }
                    existing = direct.mapNotNull { s -> s.chat.peerId?.let { it to s.chat.chatId } }.toMap()
                    _state.update { it.copy(recentChats = recent) }
                }
            }
        }
    }

    fun onQuery(text: String) = _state.update { it.copy(query = text) }
    fun onMessageShown() = _state.update { it.copy(message = null) }
    fun onShared() = _state.update { it.copy(share = null) }

    /** Opens the direct chat: the one on this phone at once (offline too), else found or made on the server (M4). */
    fun onPick(userId: String, onOpened: (chatId: String) -> Unit) {
        val s = _state.value
        if (s.opening != null) return
        existing[userId]?.let { onOpened(it); return }
        if (!s.online) { _state.update { it.copy(message = "Starting a new chat needs a connection.") }; return }
        _state.update { it.copy(opening = userId, message = null) }
        viewModelScope.launch { open(userId, onOpened) }
    }

    /** "Find @handle on Circaso": connects with them (U11, so they become a contact), then opens the chat. */
    fun onFindHandle(onOpened: (chatId: String) -> Unit) {
        val s = _state.value
        val handle = s.handleToFind ?: return
        val connect = connect ?: return
        if (!s.online || s.opening != null) return
        _state.update { it.copy(opening = "@$handle", message = null) }
        viewModelScope.launch {
            when (val r = connect(ConnectVia.Handle, handle)) {
                is AppResult.Success -> open(r.value.person.userId, onOpened)
                is AppResult.Failure -> _state.update { it.copy(opening = null, message = withRef(r.error)) }
            }
        }
    }

    private suspend fun open(userId: String, onOpened: (chatId: String) -> Unit) {
        when (val r = openDirect(userId)) {
            is AppResult.Success -> { _state.update { it.copy(opening = null) }; onOpened(r.value.chatId) }
            is AppResult.Failure -> _state.update { it.copy(opening = null, message = withRef(r.error)) }
        }
    }

    /**
     * Invite a Friend: a share link (decision 2026-11-06) to a page with your name, carrying a fresh 7-day invite, into
     * the system share sheet with a line that says what it is.
     */
    fun onInvite() {
        val create = createShareLink ?: return
        if (_state.value.creatingInvite || !_state.value.online) return
        _state.update { it.copy(creatingInvite = true, message = null) }
        viewModelScope.launch {
            when (val r = create(ShareRequest.of(ShareTarget.Invite))) {
                is AppResult.Success -> _state.update { it.copy(creatingInvite = false, share = ShareText.of(r.value).text) }
                is AppResult.Failure -> _state.update { it.copy(creatingInvite = false, message = withRef(r.error)) }
            }
        }
    }

    /** Find friends from the phone's contacts: the system prompt, then only the numbers (never names) are matched. */
    fun onFindFromContacts() {
        val reader = reader ?: return
        val sync = syncPhoneBook ?: return
        if (_state.value.phoneBook == PhoneBookState.Syncing || !_state.value.online) return
        _state.update { it.copy(phoneBook = PhoneBookState.Syncing, message = null) }
        viewModelScope.launch {
            if (!reader.requestAccess()) { _state.update { it.copy(phoneBook = PhoneBookState.Denied) }; return@launch }
            when (val r = sync(PhoneBookNumbers.normalise(reader.readNumbers()))) {
                is AppResult.Success -> _state.update {
                    val n = r.value.matches.size
                    it.copy(phoneBook = PhoneBookState.Done, message = when { n == 0 -> "No one new from your contacts is on Circaso yet."; n == 1 -> "Found 1 friend on Circaso."; else -> "Found $n friends on Circaso." })
                }
                is AppResult.Failure -> _state.update {
                    val limited = (r.error as? AppError.Api)?.code == "request.rate_limited"
                    it.copy(phoneBook = PhoneBookState.Idle, message = if (limited) "You can look for friends 5 times an hour. Try again a little later." else withRef(r.error))
                }
            }
        }
    }

    private fun withRef(error: AppError) = ErrorMessages.forError(error) + (ErrorMessages.reference(error)?.let { " Ref $it" } ?: "")

    companion object {
        const val InviteSubject = ShareText.InviteSubject
    }
}
