package com.circaso.feature.status.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.sync.ConnectionState
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.nowMillis
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.status.domain.LockedMoment
import com.circaso.feature.status.domain.Moment
import com.circaso.feature.status.domain.MomentRing
import com.circaso.feature.status.domain.MomentsRepository
import com.circaso.feature.status.domain.PendingMoment
import com.circaso.feature.status.domain.PendingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** My own ring (M6): what it shows depends on my moments and any still on their way. */
enum class MyRing { Add, Seen, Posting, Waiting, Failed }

/**
 * The Moments list in Chats (the Moments chip; design S1's states: S1c empty, S1d loading, S1e offline, S1f error).
 * Spaces stopped on 2026-11-05 and Moments moved from its tab into Chats, shown as WhatsApp shows statuses.
 */
data class MomentsUiState(
    val myName: String = "",
    val myAvatarUrl: String? = null,
    val myMoments: List<Moment> = emptyList(),
    val pending: List<PendingMoment> = emptyList(),
    /** People in my contacts with live moments: new ones first, then seen; muted at the end. */
    val rings: List<MomentRing> = emptyList(),
    val hasContacts: Boolean = true,
    /** The phone has read its stored moments (they show at once; the server refresh follows). */
    val loaded: Boolean = false,
    /** The first refresh has answered, one way or the other. */
    val refreshed: Boolean = false,
    val online: Boolean = true,
    /** The newest refresh failed with nothing on the phone to show (S1f), and its reference. */
    val failed: Boolean = false,
    /** The check failed but stored moments are on screen: they stay, with the quiet line under them. */
    val checkFailedWithData: Boolean = false,
    /** The failed check's reference for support (`ErrorMessages.reference`: the request id, or the code). */
    val requestId: String? = null,
    /** A check is on its way (Try Again shows busy). */
    val refreshing: Boolean = false,
    /** How many moments the current run of posting started with (several picked at once fill one ring); 0 when none. */
    val postingBatch: Int = 0,
) {
    val myRing: MyRing
        get() = when {
            pending.any { it.state == PendingState.Failed } -> MyRing.Failed
            pending.any { it.state == PendingState.Waiting } || (!online && pending.isNotEmpty()) -> MyRing.Waiting
            pending.isNotEmpty() -> MyRing.Posting
            myMoments.isNotEmpty() -> MyRing.Seen
            else -> MyRing.Add
        }
    val failedPost: PendingMoment? get() = pending.firstOrNull { it.state == PendingState.Failed }

    /**
     * How far my moments on their way have got (0..1) over the whole batch: the ones already posted count in full,
     * so the ring only ever moves forward as several picked at once go up one after another. Null while nothing has
     * started uploading or posted yet (a text moment, or the queue just began), which shows the turning arc.
     */
    val postingProgress: Float?
        get() {
            val posting = pending.filter { it.state == PendingState.Posting }
            val batch = maxOf(postingBatch, posting.size)
            if (batch == 0) return null
            val done = batch - posting.size
            if (done == 0 && posting.none { it.progress != null }) return null
            return ((done + posting.sumOf { (it.progress ?: 0f).toDouble() }.toFloat()) / batch).coerceIn(0f, 1f)
        }
    /** S1d: skeleton rings only on the very first open, before anything is stored or answered. */
    val loading: Boolean get() = !refreshed && rings.isEmpty() && myMoments.isEmpty() && online
    val empty: Boolean get() = refreshed && rings.isEmpty() && (!failed || checkFailedWithData)

    /**
     * Recent: people with a moment I haven't seen, newest first (a coloured ring), and people whose moments are still
     * on their way to this phone (a new phone waiting for keys), so they show instead of nothing. Muted never here.
     */
    val recent: List<MomentRing> get() = rings.filter { it.isRecent() && !it.muted }
        // What can be watched first; a ring sorts by the newest moment it shows.
        .sortedWith(compareBy<MomentRing> { it.allLocked }.thenByDescending { it.shownAt() })
    /** Viewed: everything of theirs seen, newest first (a quiet ring). */
    val viewed: List<MomentRing> get() = rings.filter { !it.isRecent() && !it.muted }
        .sortedWith(compareBy<MomentRing> { it.allLocked }.thenByDescending { it.shownAt() })
    /** Muted: at the end, folded away until opened. */
    val mutedRings: List<MomentRing> get() = rings.filter { it.muted }.sortedByDescending { it.latestAt }
    private fun MomentRing.shownAt() = moments.maxOfOrNull { it.postedAt } ?: latestAt

    private fun MomentRing.isRecent() = unseen || (moments.isEmpty() && waiting > 0)

    /** The Moments chip's dot: someone I follow has posted something I haven't seen (and can open). */
    val hasUnseen: Boolean get() = rings.any { it.unseen && !it.muted }
    /** Whose avatar gets a ring in the chat list (unseen, not muted), by user id. */
    val unseenPosters: Set<String> get() = rings.filter { it.unseen && !it.muted }.mapTo(mutableSetOf()) { it.posterId }
    /** How many people have seen my moments: the most any one of them was seen (the same people see each in turn). */
    val myViews: Int get() = myMoments.maxOfOrNull { it.viewCount } ?: 0
    /** When my newest moment went up, for "My moment · 10:42"; null with none. */
    val myLatestAt: Long? get() = myMoments.maxOfOrNull { it.postedAt }
}

class MomentsViewModel(
    private val moments: MomentsRepository,
    observeContacts: ObserveContactsUseCase,
    observeProfile: ObserveProfileUseCase,
    connection: ConnectionState,
    private val refreshContacts: com.circaso.feature.contacts.domain.RefreshContactsUseCase? = null,
    /** Blocked people (2026-11-10): their Moments never show; null in previews and tests that don't need them. */
    observeBlocks: com.circaso.feature.contacts.domain.ObserveBlocksUseCase? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(MomentsUiState())
    val state: StateFlow<MomentsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(moments.moments, observeContacts().withoutBlocked(observeBlocks), moments.muted, observeProfile(), moments.locked) { all, contacts, muted, me, locked ->
                Triple(all, contacts, muted) to (me to locked)
            }.collect { (data, extra) ->
                val (all, contacts, muted) = data
                val (me, locked) = extra
                _state.update {
                    it.copy(
                        myName = me?.displayName.orEmpty(), myAvatarUrl = me?.avatarUrl,
                        myMoments = all.filter { m -> m.mine }.sortedBy { m -> m.postedAt },
                        rings = rings(all.filterNot { m -> m.mine }, locked, contacts, muted),
                        hasContacts = contacts.isNotEmpty(), loaded = true,
                    )
                }
            }
        }
        viewModelScope.launch {
            moments.pending.collect { p ->
                val posting = p.count { it.state == PendingState.Posting }
                _state.update { it.copy(pending = p, postingBatch = if (posting == 0) 0 else maxOf(it.postingBatch, posting)) }
            }
        }
        viewModelScope.launch {
            connection.status.collect { s ->
                val back = s == ConnectionStatus.Online && !_state.value.online
                _state.update { it.copy(online = s == ConnectionStatus.Online) }
                if (back) refresh() // the connection came back: catch up at once
            }
        }
        refresh() // at launch, so the Moments chip's dot and the chat list's rings are right at once
    }

    private var lastRefresh = 0L

    /** [force]: Try Again, which always asks (the two-second guard is for tab switches and resumes only). */
    fun refresh(force: Boolean = false) {
        // Tab switches and resumes come close together: one fetch per two seconds is plenty.
        val now = com.circaso.core.sync.nowMillis()
        if (_state.value.refreshing || (!force && now - lastRefresh < 2_000)) return
        lastRefresh = now
        _state.update { it.copy(refreshing = true) }
        // Rings are named from my contacts, and only people in them are shown: keep them current too.
        refreshContacts?.let { r -> viewModelScope.launch { r() } }
        viewModelScope.launch {
            val r = moments.refresh()
            _state.update {
                val nothingShown = it.rings.isEmpty() && it.myMoments.isEmpty()
                when {
                    r is AppResult.Success -> it.copy(refreshed = true, refreshing = false, failed = false, checkFailedWithData = false, requestId = null)
                    // Offline is not an error: the banner says so and what is stored stays (S1e).
                    (r as AppResult.Failure).error is AppError.Network -> it.copy(refreshed = true, refreshing = false)
                    else -> it.copy(
                        refreshed = true, refreshing = false, failed = true, checkFailedWithData = !nothingShown,
                        requestId = com.circaso.core.common.ErrorMessages.reference(r.error),
                    )
                }
            }
        }
    }

    fun onRetryPost(localId: String) { viewModelScope.launch { moments.retry(localId) } }
    fun onDeletePost(localId: String) { viewModelScope.launch { moments.discard(localId) } }

    /**
     * Only people in my contacts (both ways: they chose me as audience, I have them saved); muted people last. A
     * person whose moments this phone can't open yet still gets a ring ([MomentRing.locked]), never silence.
     */
    private fun rings(others: List<Moment>, locked: List<LockedMoment>, contacts: List<Contact>, muted: Set<String>): List<MomentRing> {
        val byId = contacts.associateBy { it.person.userId }
        val now = nowMillis()
        val readable = others.filter { it.expiresAt > now }.groupBy { it.posterId }
        val closed = locked.filter { it.expiresAt > now }.groupBy { it.posterId }
        return (readable.keys + closed.keys).mapNotNull { posterId ->
            val person = byId[posterId]?.person ?: return@mapNotNull null
            MomentRing(
                posterId, person.displayName, person.avatarUrl, readable[posterId].orEmpty().sortedBy { it.postedAt },
                muted = posterId in muted, locked = closed[posterId].orEmpty().sortedBy { it.postedAt },
            )
        }.sortedWith(compareBy<MomentRing> { it.muted }.thenBy { !it.unseen }.thenByDescending { it.latestAt })
    }
}

/**
 * Contacts minus the people I've blocked (2026-11-10). A Moment only shows for a poster in my contacts, so this is
 * what keeps a blocked person's Moments out of the list, the chat list's rings and the viewer.
 */
internal fun kotlinx.coroutines.flow.Flow<List<Contact>>.withoutBlocked(
    blocks: com.circaso.feature.contacts.domain.ObserveBlocksUseCase?,
): kotlinx.coroutines.flow.Flow<List<Contact>> = if (blocks == null) this else combine(blocks()) { contacts, blocked ->
    if (blocked.isEmpty()) contacts else blocked.mapTo(HashSet()) { it.userId }.let { ids -> contacts.filterNot { it.person.userId in ids } }
}
