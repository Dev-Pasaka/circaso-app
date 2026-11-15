package com.circaso.feature.messaging.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.messaging.domain.GroupLinkErrors
import com.circaso.feature.messaging.domain.GroupLinkPreview
import com.circaso.feature.messaging.domain.GroupLinksRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a join link stands on the join sheet. */
sealed interface GroupJoinStage {
    /** Asking the server what the link shows. */
    data object Loading : GroupJoinStage
    /**
     * No answer and nothing loaded. Really offline, it opens by itself when the phone is back online; when the phone
     * says it is online (a timeout, a dropped request) the sheet offers Try Again instead of a wait that may not end.
     */
    data object Offline : GroupJoinStage
    /** The preview, with Join Group. [problem] is why a join didn't go through (said on the sheet, by the button). */
    data class Ready(val preview: GroupLinkPreview, val problem: String? = null) : GroupJoinStage
    /** Reset, turned off, or never a link: "This link no longer works." */
    data object Gone : GroupJoinStage
    /** An admin removed this person, or a block stands in the way: an admin can still add them. */
    data object NotAllowed : GroupJoinStage
    /** Anything else, in the server's words for it, with Try Again. */
    data class Failed(val text: String) : GroupJoinStage
}

/**
 * A group join link opened on this phone (Pasaka, 2026-11-14): the sheet that shows the group's published preview
 * and Join Group. Looking the link up and joining are online-only; offline the sheet says so and opens by itself
 * when the connection is back. Someone already in the group goes straight to its chat.
 */
data class GroupJoinUiState(
    /** The link being opened; null when the sheet is closed. */
    val token: String? = null,
    val stage: GroupJoinStage = GroupJoinStage.Loading,
    val online: Boolean = true,
    val joining: Boolean = false,
    /** The chat to open (already a member, or just joined); the layer opens it once, then clears it. */
    val openChatId: String? = null,
) {
    val open get() = token != null
    val canJoin get() = online && !joining
}

class GroupJoinViewModel(
    private val links: GroupLinksRepository,
    connection: ConnectionState,
) : ViewModel() {
    private val _state = MutableStateFlow(GroupJoinUiState())
    val state: StateFlow<GroupJoinUiState> = _state.asStateFlow()
    private var loading: Job? = null

    init {
        viewModelScope.launch {
            var wasOnline: Boolean? = null
            connection.status.collect { s ->
                val online = s == ConnectionStatus.Online
                _state.update { it.copy(online = online) }
                // Back online while the sheet waits: look the link up again, without being asked.
                if (online && wasOnline == false && _state.value.open && _state.value.stage == GroupJoinStage.Offline) load()
                wasOnline = online
            }
        }
    }

    /** A join link was tapped (or scanned): the sheet opens on it. A newer link replaces one still being looked at. */
    fun open(token: String) {
        _state.update { it.copy(token = token, stage = GroupJoinStage.Loading, joining = false, openChatId = null) }
        load()
    }

    fun load() {
        val token = _state.value.token ?: return
        loading?.cancel()
        _state.update { it.copy(stage = GroupJoinStage.Loading) }
        loading = viewModelScope.launch {
            val r = links.preview(token)
            if (_state.value.token != token) return@launch
            when (r) {
                is AppResult.Success ->
                    // Already in the group: nothing to join, its chat opens.
                    if (r.value.member && r.value.chatId != null) _state.update { it.copy(token = null, openChatId = r.value.chatId) }
                    else _state.update { it.copy(stage = GroupJoinStage.Ready(r.value)) }
                is AppResult.Failure -> _state.update { it.copy(stage = failed(r.error)) }
            }
        }
    }

    /** Join Group (M32): a member at once; the chat opens. Never fired offline, never queued. */
    fun onJoin() {
        val s = _state.value
        val token = s.token ?: return
        val ready = s.stage as? GroupJoinStage.Ready ?: return
        if (!s.canJoin) return
        _state.update { it.copy(joining = true, stage = ready.copy(problem = null)) }
        viewModelScope.launch {
            val r = links.join(token, ready.preview)
            if (_state.value.token != token) return@launch
            when (r) {
                is AppResult.Success -> _state.update { it.copy(token = null, joining = false, openChatId = r.value.chatId) }
                is AppResult.Failure -> _state.update {
                    val stage = when ((r.error as? AppError.Api)?.code) {
                        GroupLinkErrors.NotFound -> GroupJoinStage.Gone
                        GroupLinkErrors.NotAllowed -> GroupJoinStage.NotAllowed
                        // A full group, a rate limit, no connection: the preview stays, with why, so they can try again.
                        else -> ready.copy(problem = words(r.error))
                    }
                    it.copy(joining = false, stage = stage)
                }
            }
        }
    }

    fun onDismiss() { loading?.cancel(); _state.update { it.copy(token = null, joining = false) } }
    fun onChatOpened() = _state.update { it.copy(openChatId = null) }

    private fun failed(error: AppError): GroupJoinStage = when {
        error is AppError.Network -> GroupJoinStage.Offline
        (error as? AppError.Api)?.code == GroupLinkErrors.NotFound -> GroupJoinStage.Gone
        (error as? AppError.Api)?.code == GroupLinkErrors.NotAllowed -> GroupJoinStage.NotAllowed
        else -> GroupJoinStage.Failed(words(error))
    }

    private fun words(error: AppError): String =
        if (error is AppError.Network) JoinUnreachable
        else ErrorMessages.forError(error, GroupLinkViewModel.LinkWords) + (ErrorMessages.reference(error)?.let { " Ref $it" } ?: "")
}

/** Joining needs the server; offline the button rests and this says why. */
internal const val JoinOffline = "Joining needs a connection."

/** A join that got no answer while the phone says it is online: nothing was joined, and they can try again. */
internal const val JoinUnreachable = "Couldn't reach Circaso. Try again."

/** "24 members", "1 member". */
internal fun memberCountText(count: Int): String = if (count == 1) "1 member" else "$count members"
