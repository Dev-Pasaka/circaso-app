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

    /** Recent: people with a moment I haven't seen, newest first (a coloured ring). Muted people never show here. */
    val recent: List<MomentRing> get() = rings.filter { it.unseen && !it.muted }.sortedByDescending { it.latestAt }
    /** Viewed: everything of theirs seen, newest first (a quiet ring). */
    val viewed: List<MomentRing> get() = rings.filter { !it.unseen && !it.muted }.sortedByDescending { it.latestAt }
    /** Muted: at the end, folded away until opened. */
    val mutedRings: List<MomentRing> get() = rings.filter { it.muted }.sortedByDescending { it.latestAt }
    /** The Moments chip's dot: someone I follow has posted something I haven't seen. */
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
) : ViewModel() {
    private val _state = MutableStateFlow(MomentsUiState())
    val state: StateFlow<MomentsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(moments.moments, observeContacts(), moments.muted, observeProfile()) { all, contacts, muted, me ->
                Triple(all, contacts, muted) to me
            }.collect { (data, me) ->
                val (all, contacts, muted) = data
                _state.update {
                    it.copy(
                        myName = me?.displayName.orEmpty(), myAvatarUrl = me?.avatarUrl,
                        myMoments = all.filter { m -> m.mine }.sortedBy { m -> m.postedAt },
                        rings = rings(all.filterNot { m -> m.mine }, contacts, muted),
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

    /** Only people in my contacts (both ways: they chose me as audience, I have them saved); muted people last. */
    private fun rings(others: List<Moment>, contacts: List<Contact>, muted: Set<String>): List<MomentRing> {
        val byId = contacts.associateBy { it.person.userId }
        val now = nowMillis()
        return others.filter { it.expiresAt > now }.groupBy { it.posterId }.mapNotNull { (posterId, list) ->
            val person = byId[posterId]?.person ?: return@mapNotNull null
            MomentRing(posterId, person.displayName, person.avatarUrl, list.sortedBy { it.postedAt }, muted = posterId in muted)
        }.sortedWith(compareBy<MomentRing> { it.muted }.thenBy { !it.unseen }.thenByDescending { it.latestAt })
    }
}
