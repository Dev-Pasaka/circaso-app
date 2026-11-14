package com.circaso.feature.messaging.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.GroupLink
import com.circaso.feature.messaging.domain.GroupLinkErrors
import com.circaso.feature.messaging.domain.GroupLinkText
import com.circaso.feature.messaging.domain.GroupLinksRepository
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.ObserveChatUseCase
import com.circaso.feature.share.domain.ShareMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the link screen is doing with the server right now; one thing at a time. */
enum class GroupLinkWork { Creating, Resetting, TurningOff }

/** The confirm that is open: both end the current link, so both ask first. */
enum class GroupLinkConfirm { Reset, TurnOff }

/**
 * Invite via link (Pasaka, 2026-11-14), for a group's admins. The link shown comes from this phone's copy, so the
 * screen opens offline with the link, its QR code, Copy and Share; making, resetting and turning it off need the
 * server and say so rather than queueing.
 */
data class GroupLinkUiState(
    val chat: Chat? = null,
    val link: GroupLink? = null,
    /** The server has answered about this group's link since the screen opened (so "no link" is true, not unknown). */
    val checked: Boolean = false,
    /** The first load failed for a reason other than being offline; said with a Try Again. */
    val loadFailed: String? = null,
    val online: Boolean = true,
    val work: GroupLinkWork? = null,
    val confirm: GroupLinkConfirm? = null,
    /** A one-line note for the toast ("Link copied", "The link is off"). */
    val message: String? = null,
    /** Handed to the system share sheet once, then cleared. */
    val share: ShareMessage? = null,
) {
    val amAdmin get() = chat?.myRole == MemberRole.Admin || chat?.myRole == MemberRole.Owner
    val busy get() = work != null
    /** Making, resetting and turning off: online, and nothing else in flight. */
    val canChange get() = online && !busy && amAdmin
    /** This phone can't read the group's name (its key is lost), so it has nothing true to publish. */
    val nameUnknown get() = chat?.nameKnown == false
    /** Nothing on this phone yet and no answer from the server: the first load. */
    val loading get() = link == null && !checked && online && loadFailed == null
    /** Offline with no copy of the link on this phone: nothing true to show but that. */
    val unknownOffline get() = link == null && !checked && !online
}

class GroupLinkViewModel(
    private val chatId: String,
    observeChat: ObserveChatUseCase,
    private val links: GroupLinksRepository,
    connection: ConnectionState,
) : ViewModel() {
    private val _state = MutableStateFlow(GroupLinkUiState())
    val state: StateFlow<GroupLinkUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { observeChat(chatId).collect { chat -> _state.update { it.copy(chat = chat) } } }
        viewModelScope.launch { links.link(chatId).collect { link -> _state.update { it.copy(link = link) } } }
        viewModelScope.launch {
            var wasOnline: Boolean? = null
            connection.status.collect { s ->
                val online = s == ConnectionStatus.Online
                _state.update { it.copy(online = online) }
                // Back online with the link still unconfirmed: ask again without being asked.
                if (online && wasOnline == false && !_state.value.checked) load()
                wasOnline = online
            }
        }
        load()
    }

    /** M28, then the published preview is brought up to date if the group changed while nobody was looking. */
    fun load() {
        _state.update { it.copy(loadFailed = null) }
        viewModelScope.launch {
            when (val r = links.refresh(chatId)) {
                is AppResult.Success -> { _state.update { it.copy(checked = true) }; links.republishIfStale(chatId) }
                // Offline keeps what the phone has; anything else is said, with a way to try again.
                is AppResult.Failure -> if (r.error !is AppError.Network) _state.update { it.copy(loadFailed = words(r.error)) }
            }
        }
    }

    /** Create Link: the group's name, description and photo are published for the link's page, and only then. */
    fun onCreate() = change(GroupLinkWork.Creating, done = null) { links.create(chatId) }

    fun onConfirm(confirm: GroupLinkConfirm?) = _state.update { it.copy(confirm = confirm) }

    fun onConfirmed() {
        val confirm = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        when (confirm) {
            GroupLinkConfirm.Reset -> change(GroupLinkWork.Resetting, done = "New link made. The old one no longer works.") { links.reset(chatId) }
            GroupLinkConfirm.TurnOff -> change(GroupLinkWork.TurningOff, done = "The link is off.") { links.turnOff(chatId) }
        }
    }

    private fun change(work: GroupLinkWork, done: String?, call: suspend () -> AppResult<*>) {
        // The buttons rest offline; a tap racing the connection dropping still never fires blind.
        if (!_state.value.canChange) return
        _state.update { it.copy(work = work, message = null) }
        viewModelScope.launch {
            val r = call()
            _state.update { it.copy(work = null, checked = it.checked || r is AppResult.Success, message = if (r is AppResult.Failure) words(r.error) else done) }
        }
    }

    fun onShare() { val link = _state.value.link ?: return; _state.update { it.copy(share = ShareMessage(GroupLinkText.message(link), GroupLinkText.subject(link))) } }
    fun onShared() = _state.update { it.copy(share = null) }
    fun onCopied() = _state.update { it.copy(message = "Link copied") }
    fun onMessageShown() = _state.update { it.copy(message = null) }

    private fun words(error: AppError): String =
        ErrorMessages.forError(error, LinkWords) + (ErrorMessages.reference(error)?.let { " Ref $it" } ?: "")

    companion object {
        /** The link screens' own words for codes that read differently elsewhere. */
        internal val LinkWords = mapOf(
            "messaging.rate_limited" to "Too many tries. Please wait a moment.",
            "messaging.not_admin" to "Only the group's admins can manage its link.",
            GroupLinkErrors.NotFound to "This link no longer works. Ask an admin for a new one.",
        )
    }
}

/** What the link screen says is public, before the link is made and beside it after (the owner's words, 2026-11-14). */
internal const val LinkPublicNote = "Anyone with the link can see the group's name, description and photo, and how many members it has. Members, messages and money stay private."

/** What joining gives, in a group that may hold money: an ordinary member, nothing more. */
internal const val LinkJoinersNote = "People who join become members straight away. They see messages sent after they join and the group's money like any member, and can't move it."
