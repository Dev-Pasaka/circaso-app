package com.circaso.feature.support.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.support.OpenTicketRequest
import com.circaso.feature.support.SupportTickets
import com.circaso.feature.support.TicketCategory
import com.circaso.feature.support.TicketDetailResponse
import com.circaso.feature.support.TicketDto
import com.circaso.feature.support.TicketErrors
import com.circaso.feature.support.TicketRules
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---- Your tickets ----

data class TicketsUiState(
    val tickets: List<TicketDto> = emptyList(),
    /** False until the list has loaded once (this run of the app): the skeleton, or [error] with Try Again. */
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    /** Why the list couldn't load or refresh; with a list on screen it is a toast, without one it is the screen. */
    val error: String? = null,
    val online: Boolean = true,
) {
    val showSkeleton: Boolean get() = !loaded && error == null
    val empty: Boolean get() = loaded && tickets.isEmpty()
}

/** Every ticket the person has, newest first (SU2), paged. */
class TicketsViewModel(private val tickets: SupportTickets, connection: ConnectionState) : ViewModel() {
    private val local = MutableStateFlow(TicketsUiState())

    val state: StateFlow<TicketsUiState> = combine(local, tickets.list, connection.status) { ui, list, c ->
        ui.copy(tickets = list.tickets, loaded = list.loaded, hasMore = list.hasMore, online = c == ConnectionStatus.Online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value.copy(tickets = tickets.list.value.tickets, loaded = tickets.list.value.loaded, hasMore = tickets.list.value.hasMore))

    init {
        refresh()
        // Back online with nothing loaded: load it without being asked.
        viewModelScope.launch {
            connection.status.map { it == ConnectionStatus.Online }.distinctUntilChanged().drop(1).collect { online ->
                if (online && !tickets.list.value.loaded) refresh()
            }
        }
    }

    fun onRefresh() = refresh()

    private fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val r = tickets.refresh()
            local.update { it.copy(refreshing = false, error = (r as? AppResult.Failure)?.let { f -> TicketErrors.withReference(f.error) }) }
        }
    }

    /** The list reached its end: the next page, if there is one. */
    fun onLoadMore() {
        val s = local.value
        if (s.loadingMore || s.refreshing || !tickets.list.value.hasMore) return
        local.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val r = tickets.loadMore()
            local.update { it.copy(loadingMore = false, error = (r as? AppResult.Failure)?.let { f -> TicketErrors.withReference(f.error) }) }
        }
    }

    fun onErrorShown() = local.update { it.copy(error = null) }
}

// ---- New ticket ----

data class NewTicketUiState(
    val category: TicketCategory? = null,
    val subject: String = "",
    val description: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val online: Boolean = true,
    /** Send was tried: say what is missing, not only what is wrong. */
    val tried: Boolean = false,
) {
    val subjectOk: Boolean get() = TicketRules.subjectOk(subject)
    val descriptionOk: Boolean get() = TicketRules.descriptionOk(description)
    val valid: Boolean get() = category != null && subjectOk && descriptionOk
    val canSend: Boolean get() = valid && !busy && online

    /** Under the subject once it has been typed in (or Send was tried) and still can't be sent. */
    val subjectProblem: String? get() = when {
        subjectOk || (subject.isBlank() && !tried) -> null
        else -> "Use at least ${TicketRules.SubjectMin} characters."
    }
    val descriptionProblem: String? get() = when {
        descriptionOk || (description.isBlank() && !tried) -> null
        else -> "Use at least ${TicketRules.DescriptionMin} characters."
    }
    val categoryProblem: String? get() = if (category == null && tried) "Choose what it's about." else null
    val subjectCount: String get() = "${subject.trim().length}/${TicketRules.SubjectMax}"
    val descriptionCount: String get() = "${description.trim().length.grouped()}/${TicketRules.DescriptionMax.grouped()}"
}

/** 4000 → "4,000". */
internal fun Int.grouped(): String = toString().reversed().chunked(3).joinToString(",").reversed()

/** The new-ticket form (SU1): what it's about, a subject and what happened. Online only; nothing is queued. */
class NewTicketViewModel(category: String?, private val tickets: SupportTickets, connection: ConnectionState) : ViewModel() {
    private val local = MutableStateFlow(NewTicketUiState(category = TicketCategory.of(category)))
    private val _opened = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** The new ticket's id: its screen takes the form's place. */
    val opened: SharedFlow<String> = _opened

    val state: StateFlow<NewTicketUiState> = combine(local, connection.status) { ui, c -> ui.copy(online = c == ConnectionStatus.Online) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    fun onCategory(category: TicketCategory) = local.update { it.copy(category = category, error = null) }

    /** One line, at most 120 characters: more can't be typed, so the server never has to refuse it. */
    fun onSubject(value: String) = local.update { it.copy(subject = value.replace('\n', ' ').replace('\r', ' ').take(TicketRules.SubjectMax), error = null) }

    fun onDescription(value: String) = local.update { it.copy(description = value.take(TicketRules.DescriptionMax), error = null) }

    fun onSend() {
        val s = state.value
        // The form's own copy, so a double tap never sends twice whatever the screen has caught up with.
        if (local.value.busy) return
        if (!s.valid) { local.update { it.copy(tried = true) }; return }
        if (!s.online) return
        val request = OpenTicketRequest(s.subject.trim(), s.description.trim(), s.category!!.wire)
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = tickets.open(request)) {
                // Stays busy: the ticket's screen takes this one's place, so "Send" never flashes back first.
                is AppResult.Success -> _opened.tryEmit(r.value.ticket.ticketId)
                is AppResult.Failure -> local.update { it.copy(busy = false, error = TicketErrors.withReference(r.error)) }
            }
        }
    }
}

// ---- One ticket ----

data class TicketUiState(
    val detail: TicketDetailResponse? = null,
    val loading: Boolean = false,
    /** Why the ticket couldn't be loaded; with a copy on screen it is a line above the composer. */
    val loadError: String? = null,
    val draft: String = "",
    /** A reply on its way: shown in the thread at once, taken back if it fails. */
    val pending: String? = null,
    val sendError: String? = null,
    val online: Boolean = true,
) {
    val closed: Boolean get() = detail?.ticket?.closed == true
    val resolved: Boolean get() = detail?.ticket?.resolved == true
    val sending: Boolean get() = pending != null
    val canSend: Boolean get() = detail != null && !closed && !sending && online && TicketRules.replyOk(draft)
    val showSkeleton: Boolean get() = detail == null && loadError == null
}

/** One ticket and its replies (SU3), with the reply composer (SU4). */
class TicketViewModel(private val ticketId: String, private val tickets: SupportTickets, connection: ConnectionState) : ViewModel() {
    private val local = MutableStateFlow(TicketUiState())

    val state: StateFlow<TicketUiState> = combine(local, tickets.details, connection.status) { ui, all, c ->
        ui.copy(detail = all[ticketId], online = c == ConnectionStatus.Online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value.copy(detail = tickets.details.value[ticketId]))

    init {
        // While this screen is up, a frame about the ticket reads it again.
        tickets.watch(ticketId)
        load()
        viewModelScope.launch {
            connection.status.map { it == ConnectionStatus.Online }.distinctUntilChanged().drop(1).collect { online -> if (online) load() }
        }
    }

    override fun onCleared() { tickets.unwatch(ticketId) }

    fun onRetry() = load()

    private fun load() {
        if (local.value.loading) return
        local.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            val r = tickets.load(ticketId)
            local.update { it.copy(loading = false, loadError = (r as? AppResult.Failure)?.let { f -> TicketErrors.withReference(f.error) }) }
        }
    }

    fun onDraft(value: String) = local.update { it.copy(draft = value.take(TicketRules.ReplyMax), sendError = null) }

    /**
     * Sends the reply. It shows in the thread at once; if it can't be sent it comes back into the field with the
     * reason, so nothing typed is lost and nothing looks sent that wasn't.
     */
    fun onSend() {
        val s = state.value
        if (local.value.pending != null || !s.canSend) return
        val body = s.draft.trim()
        local.update { it.copy(draft = "", pending = body, sendError = null) }
        viewModelScope.launch {
            when (val r = tickets.reply(ticketId, body)) {
                is AppResult.Success -> local.update { it.copy(pending = null) }
                is AppResult.Failure -> {
                    local.update { it.copy(pending = null, draft = it.draft.ifEmpty { body }, sendError = TicketErrors.withReference(r.error)) }
                    // Closed meanwhile: read it again so the composer gives way to "Open a new ticket".
                    if ((r.error as? AppError.Api)?.code == "support.ticket_closed") tickets.load(ticketId)
                }
            }
        }
    }
}
