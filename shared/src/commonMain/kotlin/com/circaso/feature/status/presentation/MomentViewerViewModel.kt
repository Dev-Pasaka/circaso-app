package com.circaso.feature.status.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.status.domain.Moment
import com.circaso.feature.status.domain.MomentKind
import com.circaso.feature.status.domain.MomentMediaLoad
import com.circaso.feature.status.domain.MomentRing
import com.circaso.feature.status.domain.MomentViewer
import com.circaso.feature.status.domain.MomentsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The media of the moment on screen (V1, V5, V5b, V6). */
sealed interface ViewerMedia {
    data object None : ViewerMedia
    data object Loading : ViewerMedia
    class Ready(val bytes: ByteArray, val path: String?) : ViewerMedia
    data object NeedsConnection : ViewerMedia
    class Failed(val requestId: String?) : ViewerMedia
    /** V7: deleted or expired while open; the viewer moves on by itself. */
    data object Gone : ViewerMedia
}

/** One row of Seen by (V4). */
data class ViewerRow(val viewer: MomentViewer, val name: String, val avatarUrl: String?)

/** Design V1–V8: one person's ring at a time, then on to the next ring. */
data class MomentViewerUiState(
    val rings: List<MomentRing> = emptyList(),
    val ring: Int = 0,
    val index: Int = 0,
    val media: ViewerMedia = ViewerMedia.None,
    val paused: Boolean = false,
    /** Video sound off, kept for the rest of this viewing. */
    val muted: Boolean = false,
    /** The reaction picker above the react button is open. */
    val picking: Boolean = false,
    val viewersOpen: Boolean = false,
    val viewers: List<ViewerRow>? = null,
    val viewersError: String? = null,
    val confirmDelete: Boolean = false,
    val moreOpen: Boolean = false,
    val reply: String = "",
    val online: Boolean = true,
    val closed: Boolean = false,
    val notice: String? = null,
) {
    val current: MomentRing? get() = rings.getOrNull(ring)
    val moment: Moment? get() = current?.moments?.getOrNull(index)
    val mine: Boolean get() = moment?.mine == true
}

class MomentViewerViewModel(
    private val posterId: String?,
    private val repository: MomentsRepository,
    private val observeContacts: ObserveContactsUseCase,
    private val observeProfile: ObserveProfileUseCase,
    connection: ConnectionState,
    /** Opens (or finds) the 1:1 chat with someone and sends a text there quoting a moment (V8). */
    private val replyInChat: suspend (posterId: String, text: String, moment: Moment) -> AppResult<String>,
    /** Open at this moment (a reply's quote in a chat was tapped); gone already means V7. */
    private val startAt: String? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(MomentViewerUiState())
    val state: StateFlow<MomentViewerUiState> = _state.asStateFlow()
    private var load: Job? = null
    private var started = false

    init {
        viewModelScope.launch {
            combine(repository.moments, observeContacts(), repository.muted) { all, contacts, muted -> Triple(all, contacts, muted) }.collect { (all, contacts, muted) ->
                val me = observeProfile().first()
                val rings = if (posterId == null) {
                    val mine = all.filter { it.mine }.sortedBy { it.postedAt }
                    if (mine.isEmpty()) emptyList() else listOf(MomentRing(me?.userId ?: "me", "My moment", me?.avatarUrl, mine, muted = false))
                } else {
                    val byId = contacts.associateBy { it.person.userId }
                    all.filterNot { it.mine }.groupBy { it.posterId }.mapNotNull { (id, list) ->
                        val p = byId[id]?.person ?: return@mapNotNull null
                        MomentRing(id, p.displayName, p.avatarUrl, list.sortedBy { it.postedAt }, muted = id in muted)
                    }.sortedWith(compareBy<MomentRing> { it.muted }.thenBy { !it.unseen }.thenByDescending { it.latestAt })
                }
                _state.update { s ->
                    if (!started && rings.isNotEmpty()) {
                        started = true
                        // Open at the tapped person, at their first moment I haven't seen (WhatsApp does the same).
                        val r = posterId?.let { id -> rings.indexOfFirst { it.posterId == id } }?.takeIf { it >= 0 } ?: 0
                        val i = startAt?.let { id -> rings[r].moments.indexOfFirst { it.momentId == id } }?.takeIf { it >= 0 }
                            ?: rings[r].moments.indexOfFirst { !it.viewed }.takeIf { it >= 0 } ?: 0
                        s.copy(rings = rings, ring = r, index = i)
                    } else {
                        // A moment that expired or was deleted while open: stay on the same person if they still have one.
                        val keep = s.current?.posterId?.let { id -> rings.indexOfFirst { it.posterId == id } } ?: -1
                        if (keep < 0) s.copy(rings = rings, ring = s.ring.coerceAtMost((rings.size - 1).coerceAtLeast(0)), index = 0)
                        else s.copy(rings = rings, ring = keep, index = s.index.coerceAtMost(rings[keep].moments.size - 1))
                    }.let {
                        when {
                            // Opened from a reply's quote but that moment has ended: say so, then close.
                            startAt != null && !started -> it.copy(closed = true, notice = "This moment is no longer available")
                            rings.isEmpty() || (posterId != null && !started) -> it.copy(closed = true)
                            else -> it
                        }
                    }
                }
                showCurrent()
            }
        }
        viewModelScope.launch { connection.status.collect { st -> _state.update { it.copy(online = st == ConnectionStatus.Online) } } }
    }

    private var shownId: String? = null

    /** Loads the media and records the view once per moment. */
    private fun showCurrent() {
        val m = _state.value.moment ?: return
        if (shownId == m.momentId) return
        shownId = m.momentId
        // A half-typed reply belongs to the moment it was typed on, never the next one.
        _state.update { it.copy(reply = "", paused = false, picking = false) }
        load?.cancel()
        if (m.media == null || m.kind == MomentKind.Text) {
            _state.update { it.copy(media = ViewerMedia.None) }
        } else {
            _state.update { it.copy(media = ViewerMedia.Loading) }
            load = viewModelScope.launch {
                val loaded = when (val r = repository.media(m)) {
                    is MomentMediaLoad.Loaded -> ViewerMedia.Ready(r.bytes, r.path)
                    MomentMediaLoad.NeedsConnection -> ViewerMedia.NeedsConnection
                    is MomentMediaLoad.Failed -> if (r.gone) ViewerMedia.Gone else ViewerMedia.Failed(r.requestId)
                }
                _state.update { it.copy(media = loaded) }
            }
        }
        if (!m.mine && !m.viewed) viewModelScope.launch { repository.markViewed(m.momentId) }
        // Fetch and decrypt the next one while this plays, so tapping on rarely stops at Loading.
        upNext()?.takeIf { it.media != null && _state.value.online }?.let { next -> viewModelScope.launch { repository.media(next) } }
    }

    private fun upNext(): Moment? {
        val s = _state.value
        val ring = s.current ?: return null
        return ring.moments.getOrNull(s.index + 1) ?: s.rings.getOrNull(s.ring + 1)?.moments?.firstOrNull()
    }

    fun next() {
        val s = _state.value
        val ring = s.current ?: return
        when {
            s.index < ring.moments.size - 1 -> _state.update { it.copy(index = it.index + 1) }
            s.ring < s.rings.size - 1 -> _state.update { it.copy(ring = it.ring + 1, index = 0) }
            else -> _state.update { it.copy(closed = true) }
        }
        showCurrent()
    }

    fun previous() {
        val s = _state.value
        when {
            s.index > 0 -> _state.update { it.copy(index = it.index - 1) }
            s.ring > 0 -> _state.update { it.copy(ring = it.ring - 1, index = 0) }
            else -> return
        }
        showCurrent()
    }

    /** Swipe left: the next person's ring. */
    fun nextRing() {
        val s = _state.value
        if (s.ring < s.rings.size - 1) { _state.update { it.copy(ring = it.ring + 1, index = 0) }; showCurrent() } else _state.update { it.copy(closed = true) }
    }

    fun retryMedia() { shownId = null; showCurrent() }
    fun setPaused(paused: Boolean) = _state.update { it.copy(paused = paused) }
    fun toggleMute() = _state.update { it.copy(muted = !it.muted) }
    fun onReply(text: String) = _state.update { it.copy(reply = text, paused = text.isNotEmpty()) }

    /** Opens the reaction picker; the moment waits while it is open. Offline it says so instead of opening. */
    fun openPicker() {
        if (_state.value.moment?.mine != false) return
        if (!_state.value.online) { _state.update { it.copy(notice = "Reactions need a connection.") }; return }
        _state.update { it.copy(picking = true, paused = true) }
    }

    /** The picker closed without a pick (tap outside, back, the moment moved on): the moment carries on. */
    fun closePicker() {
        if (!_state.value.picking) return
        _state.update { it.copy(picking = false, paused = false) }
    }

    /**
     * React with any [emoji]; null, or the one already chosen, takes it back. Online-only, said before the tap does
     * anything. The phone shows it at once and puts it back if the server doesn't take it.
     */
    fun react(emoji: String?) {
        val m = _state.value.moment ?: return
        if (m.mine) return
        _state.update { it.copy(picking = false, paused = false) }
        val next = emoji?.takeIf { it != m.reaction }
        // Taking back a reaction I don't have: nothing to tell the server.
        if (next == null && m.reaction == null) return
        if (!_state.value.online) { _state.update { it.copy(notice = "Reactions need a connection.") }; return }
        viewModelScope.launch {
            val r = repository.react(m.momentId, next)
            if (r is AppResult.Failure) _state.update { it.copy(notice = if (r.error is AppError.Network) "You’re offline. Try again when you’re connected." else "That reaction didn’t go through. Try again.") }
        }
    }

    fun sendReply() {
        val s = _state.value
        val m = s.moment ?: return
        val text = s.reply.trim().ifEmpty { return }
        _state.update { it.copy(reply = "", paused = false) }
        viewModelScope.launch {
            // An ordinary chat message: it waits and sends offline like any other (G3).
            val r = replyInChat(m.posterId, text, m)
            val name = s.current?.name?.substringBefore(' ')
            _state.update {
                it.copy(notice = when {
                    r !is AppResult.Success -> if ((r as AppResult.Failure).error is AppError.Network) "You’re offline. Reply to $name when you’re back online." else "The reply couldn’t be saved to send. Try again."
                    it.online -> "Reply on its way to $name"
                    else -> "Reply saved. It sends when you’re back online."
                })
            }
        }
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }
    /** A line for the toast (e.g. after forwarding). */
    fun notice(text: String) = _state.update { it.copy(notice = text) }
    fun openMore(open: Boolean) = _state.update { it.copy(moreOpen = open, paused = open) }

    fun mute() {
        val ring = _state.value.current ?: return
        viewModelScope.launch { repository.setMuted(ring.posterId, true) }
        _state.update { it.copy(moreOpen = false, notice = "${ring.name.substringBefore(' ')}’s moments are muted") }
        nextRing()
    }

    // ---- my own moments (V4)

    fun openViewers(open: Boolean) {
        _state.update { it.copy(viewersOpen = open, paused = open, viewers = if (open) it.viewers else null, viewersError = null) }
        val m = _state.value.moment ?: return
        if (!open) return
        viewModelScope.launch {
            when (val r = repository.viewers(m.momentId)) {
                is AppResult.Success -> {
                    val people = observeContacts().first().associateBy { it.person.userId }
                    _state.update { it.copy(viewers = r.value.map { v -> ViewerRow(v, people[v.userId]?.person?.displayName ?: "Someone not in your contacts", people[v.userId]?.person?.avatarUrl) }) }
                }
                is AppResult.Failure -> _state.update { it.copy(viewersError = if (r.error is AppError.Network) "Viewers show when you’re online." else "Viewers didn’t load.") }
            }
        }
    }

    fun askDelete(open: Boolean) {
        if (open && !_state.value.online) { _state.update { it.copy(notice = "Deleting a moment needs a connection.") }; return }
        _state.update { it.copy(confirmDelete = open, paused = open) }
    }

    /** The reply field took or lost the keyboard: the moment waits while someone writes. */
    fun onReplyFocus(focused: Boolean) = _state.update { it.copy(paused = focused || it.reply.isNotEmpty()) }

    fun delete() {
        val m = _state.value.moment ?: return
        _state.update { it.copy(confirmDelete = false) }
        viewModelScope.launch {
            when (val r = repository.delete(m.momentId)) {
                is AppResult.Success -> _state.update { it.copy(notice = "Moment deleted") }
                is AppResult.Failure -> _state.update { it.copy(notice = if (r.error is AppError.Network) "You’re offline. Delete it when you’re connected." else "The moment couldn’t be deleted. Try again.") }
            }
        }
    }
}
