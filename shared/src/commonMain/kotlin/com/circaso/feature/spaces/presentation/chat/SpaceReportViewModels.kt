package com.circaso.feature.spaces.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.BlockUseCase
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.spaces.domain.MuteDuration
import com.circaso.feature.spaces.domain.ReportAction
import com.circaso.feature.spaces.domain.ReportGroup
import com.circaso.feature.spaces.domain.ReportReason
import com.circaso.feature.spaces.domain.Space
import com.circaso.feature.spaces.domain.SpaceEvent
import com.circaso.feature.spaces.domain.SpacePost
import com.circaso.feature.spaces.domain.SpacesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SpaceReportUiState(
    val spaceId: String,
    val postId: String?,
    val space: Space? = null,
    val post: SpacePost? = null,
    val online: Boolean = true,
    /** The first reason is chosen to start (the frame), so one tap and Send is enough. */
    val reason: ReportReason = ReportReason.SpamScam,
    val detail: String = "",
    val alsoBlock: Boolean = false,
    val sending: Boolean = false,
    /** SR3b: the report reached the server. */
    val sent: Boolean = false,
    val error: String? = null,
    val errorRef: String? = null,
) {
    val title: String get() = if (postId == null) "Report Space" else "Report Post"
    /** Reports are online-only (SR3c): Send is off before the tap with the reason. */
    val canSend: Boolean get() = online && !sending && (postId == null || post != null)
    val authorFirst: String? get() = post?.author?.displayName?.substringBefore(' ')
}

/** SR3: report a post (or the whole space). The space's team never sees the reporter's name. Online-only. */
class SpaceReportViewModel(
    private val spaceId: String,
    private val postId: String?,
    private val spaces: SpacesRepository,
    private val block: BlockUseCase,
    connection: ConnectionState,
) : ViewModel() {
    private val local = MutableStateFlow(SpaceReportUiState(spaceId, postId))

    val state: StateFlow<SpaceReportUiState> = combine(
        local, spaces.space(spaceId),
        if (postId == null) flowOf(null) else spaces.posts(spaceId).map { list -> list.firstOrNull { it.postId == postId } },
        connection.status,
    ) { ui, space, post, c -> ui.copy(space = space, post = post, online = c == ConnectionStatus.Online) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    fun onReason(r: ReportReason) = local.update { it.copy(reason = r) }
    fun onDetail(text: String) = local.update { it.copy(detail = text.take(500)) }
    fun onAlsoBlock(on: Boolean) = local.update { it.copy(alsoBlock = on) }

    fun onSend() {
        // What was just chosen counts, even before the shared state has caught up with it.
        val s = local.value.let { l -> state.value.copy(reason = l.reason, detail = l.detail, alsoBlock = l.alsoBlock, sending = l.sending, sent = l.sent) }
        if (!s.canSend || s.sent) return
        local.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            when (val r = spaces.report(spaceId, postId, s.reason, s.detail.takeIf { it.isNotBlank() })) {
                is AppResult.Success -> {
                    // "Also block" is the users' block, done after the report has landed (never instead of it).
                    s.post?.takeIf { s.alsoBlock && !it.mine }?.author?.let { a -> block(Person(a.userId, a.displayName, a.handle, a.avatarUrl, null)) }
                    local.update { it.copy(sending = false, sent = true) }
                }
                is AppResult.Failure -> local.update { it.copy(sending = false, error = spacesErrorText(r.error), errorRef = ErrorMessages.reference(r.error)) }
            }
        }
    }
}

data class SpaceReportsUiState(
    val spaceId: String,
    val space: Space? = null,
    val online: Boolean = true,
    val open: Boolean = true,
    val reports: List<ReportGroup> = emptyList(),
    val next: String? = null,
    /** Before anything is on screen: skeletons in the cards' own shape (SR4c). */
    val loading: Boolean = true,
    val error: String? = null,
    val errorRef: String? = null,
    /** The report being acted on (its buttons show progress). */
    val acting: String? = null,
    /** Remove {name} asks once more inside a popover on that card. */
    val confirmRemove: String? = null,
    val openCount: Int? = null,
) {
    val canRemoveMember: Boolean get() = space?.me?.isAdmin == true
}

/** SR4: the reports queue for moderators and up, with one-tap actions; acting closes the report and tells the reporters. */
class SpaceReportsViewModel(
    private val spaceId: String,
    private val spaces: SpacesRepository,
    connection: ConnectionState,
) : ViewModel() {
    private val local = MutableStateFlow(SpaceReportsUiState(spaceId))
    private val _toast = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val toasts: kotlinx.coroutines.flow.Flow<String> = _toast.receiveAsFlow()

    val state: StateFlow<SpaceReportsUiState> = combine(local, spaces.space(spaceId), connection.status) { ui, space, c ->
        ui.copy(space = space, online = c == ConnectionStatus.Online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        load()
        // A new report on this space (space.report_new) shows without a pull.
        viewModelScope.launch { spaces.events.collect { e -> if (e is SpaceEvent.ReportNew && e.spaceId == spaceId && local.value.open) load() } }
    }

    fun load() {
        val open = local.value.open
        local.update { it.copy(loading = it.reports.isEmpty(), error = null) }
        viewModelScope.launch {
            when (val r = spaces.reports(spaceId, open)) {
                is AppResult.Success -> local.update {
                    if (it.open != open) it else it.copy(loading = false, reports = r.value.items, next = r.value.next, openCount = if (open) r.value.items.size else it.openCount)
                }
                is AppResult.Failure -> local.update {
                    if (it.reports.isNotEmpty()) it.copy(loading = false)
                    else it.copy(loading = false, error = if (r.error is AppError.Network) "You’re offline. Reports show when you’re back online." else "Something went wrong on our side. No report is lost.", errorRef = ErrorMessages.reference(r.error))
                }
            }
        }
    }

    fun onSegment(open: Boolean) {
        if (open == local.value.open) return
        local.update { it.copy(open = open, reports = emptyList(), next = null, loading = true) }
        load()
    }

    fun onConfirmRemove(postId: String?) = local.update { it.copy(confirmRemove = postId) }

    fun onAct(report: ReportGroup, action: ReportAction) {
        val postId = report.post.postId ?: return
        if (!state.value.online || local.value.acting != null) return
        local.update { it.copy(acting = postId, confirmRemove = null) }
        viewModelScope.launch {
            // SR4's Mute is one tap: 24 hours, the common case; longer mutes are on the member's row (SR1b).
            val r = spaces.resolve(spaceId, postId, action, MuteDuration.Day.takeIf { action == ReportAction.Mute })
            local.update { st ->
                if (r is AppResult.Success) st.copy(acting = null, reports = st.reports.filterNot { it.post.postId == postId }, openCount = st.openCount?.minus(1)?.coerceAtLeast(0))
                else st.copy(acting = null)
            }
            val name = report.post.author.displayName.substringBefore(' ')
            _toast.send(
                if (r is AppResult.Success) when (action) {
                    ReportAction.Keep -> "Kept. The reporters are told."
                    ReportAction.RemovePost -> "Post removed. The reporters are told."
                    ReportAction.Mute -> "$name is muted for 24 hours"
                    ReportAction.RemoveMember -> "$name was removed"
                } else spacesErrorText((r as AppResult.Failure).error),
            )
        }
    }
}

