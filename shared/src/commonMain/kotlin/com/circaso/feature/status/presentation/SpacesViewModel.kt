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

/** Design S1 and its states (S1c empty, S1d loading, S1e offline, S1f error). */
data class SpacesUiState(
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
    val requestId: String? = null,
    val newSheet: Boolean = false,
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
    /** S1d: skeleton rings only on the very first open, before anything is stored or answered. */
    val loading: Boolean get() = !refreshed && rings.isEmpty() && myMoments.isEmpty() && online
    val empty: Boolean get() = refreshed && rings.isEmpty() && !failed
}

class SpacesViewModel(
    private val moments: MomentsRepository,
    observeContacts: ObserveContactsUseCase,
    observeProfile: ObserveProfileUseCase,
    connection: ConnectionState,
) : ViewModel() {
    private val _state = MutableStateFlow(SpacesUiState())
    val state: StateFlow<SpacesUiState> = _state.asStateFlow()

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
        viewModelScope.launch { moments.pending.collect { p -> _state.update { it.copy(pending = p) } } }
        viewModelScope.launch { connection.status.collect { s -> _state.update { it.copy(online = s == ConnectionStatus.Online) } } }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val r = moments.refresh()
            _state.update {
                val nothingShown = it.rings.isEmpty() && it.myMoments.isEmpty()
                when {
                    r is AppResult.Success -> it.copy(refreshed = true, failed = false, requestId = null)
                    // Offline is not an error: the banner says so and what is stored stays (S1e).
                    (r as AppResult.Failure).error is AppError.Network -> it.copy(refreshed = true)
                    else -> it.copy(refreshed = true, failed = nothingShown, requestId = (r.error as? AppError.Api)?.requestId)
                }
            }
        }
    }

    fun onNew(open: Boolean) = _state.update { it.copy(newSheet = open) }
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
