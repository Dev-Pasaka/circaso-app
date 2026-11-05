package com.circaso.feature.spaces.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.sync.nowMillis
import com.circaso.feature.spaces.domain.MySpace
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.domain.SpacesRepository
import com.circaso.feature.spaces.domain.Suggestion
import com.circaso.feature.wallet.domain.FiatValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How a suggested space's button stands (SD1, SD3): Follow, following in progress, Following (in place, SD3). */
enum class FollowState { Idle, Busy, Following }

/**
 * Design SD1 and its states: Your spaces (SD1, SD1b empty, SD1c loading, SD1d offline) and Suggested for you (SD1e
 * error). Your spaces come from the phone; suggestions need the server and are never shown stale offline.
 */
data class SpacesHomeUiState(
    /** Spaces core is on (SpacesFlag): otherwise the tab keeps phase 1's "Spaces are coming" card. */
    val enabled: Boolean = false,
    val mine: List<MySpace> = emptyList(),
    /** The phone has read its stored spaces. */
    val mineLoaded: Boolean = false,
    /** When "Your spaces" was last read from the server; 0 before the first read. */
    val mineAsOf: Long = 0,
    val suggestions: List<Suggestion> = emptyList(),
    val suggestionsLoaded: Boolean = false,
    /** SD1e: the suggestions failed after retries; the reference for support. */
    val suggestionsFailed: Boolean = false,
    val requestId: String? = null,
    val online: Boolean = true,
    /** Follow buttons in progress or done, per space. */
    val follow: Map<String, FollowState> = emptyMap(),
    /** A one-off line for the toast ("You follow Nairobi Sneaker Drop", or why it didn't). */
    val toast: String? = null,
    /** The person's currency, for "1 USDC (KES 129) a month". */
    val fiat: FiatValue? = null,
    val now: Long = 0,
) {
    /** SD1c: skeletons only on the very first open, before anything is stored or answered. */
    val loading: Boolean get() = mineLoaded && mineAsOf == 0L && mine.isEmpty() && online && !suggestionsLoaded && !suggestionsFailed
    /** SD1b: not in any space yet (after the server said so, or with nothing stored offline). */
    val noSpaces: Boolean get() = mineLoaded && mine.isEmpty() && !loading
    /** SD1's suggestions: public and members-only only (invite-only never appears), those I don't follow yet. */
    val shownSuggestions: List<Suggestion> get() {
        val joined = mine.map { it.space.spaceId }.toSet()
        return suggestions.filter { it.space.visibility != SpaceVisibility.InviteOnly && (it.space.spaceId !in joined || follow[it.space.spaceId] == FollowState.Following) }
    }
}

class SpacesHomeViewModel(
    private val spaces: SpacesRepository,
    online: Flow<Boolean>,
    fiat: Flow<FiatValue?> = flowOf(null),
    private val now: () -> Long = ::nowMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(SpacesHomeUiState(now = now()))
    val state: StateFlow<SpacesHomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { spaces.enabled.collect { on -> _state.update { it.copy(enabled = on) }; if (on) refresh(force = true) } }
        viewModelScope.launch { spaces.mySpaces.collect { list -> _state.update { it.copy(mine = list, mineLoaded = true, now = now()) } } }
        viewModelScope.launch { spaces.mineAsOf.collect { at -> _state.update { it.copy(mineAsOf = at) } } }
        viewModelScope.launch { fiat.collect { f -> _state.update { it.copy(fiat = f) } } }
        viewModelScope.launch {
            online.collect { on ->
                val back = on && !_state.value.online
                _state.update { it.copy(online = on) }
                if (back) refresh(force = true) // the connection came back: catch up at once
            }
        }
    }

    private var lastRefresh = 0L

    /** On every tab show and resume (as Moments), at most once per two seconds. */
    fun refresh(force: Boolean = false) {
        if (!_state.value.enabled) return
        val at = now()
        if (!force && at - lastRefresh < 2_000) return
        lastRefresh = at
        viewModelScope.launch { spaces.refreshMine() }
        viewModelScope.launch { loadSuggestions() }
    }

    fun onRetrySuggestions() { viewModelScope.launch { loadSuggestions() } }

    private suspend fun loadSuggestions() {
        if (!_state.value.online) return
        when (val r = spaces.suggested(null)) {
            is AppResult.Success -> _state.update { it.copy(suggestions = r.value.items, suggestionsLoaded = true, suggestionsFailed = false, requestId = null) }
            // Offline is not an error: the section says it needs the connection (SD1d).
            is AppResult.Failure -> if (r.error !is AppError.Network) _state.update {
                it.copy(suggestionsFailed = it.suggestions.isEmpty(), requestId = (r.error as? AppError.Api)?.requestId, suggestionsLoaded = it.suggestions.isNotEmpty())
            }
        }
    }

    /** Follow acts in place, never optimistic: the button shows progress until the server answers (SS1b). */
    fun onFollow(spaceId: String) {
        val s = _state.value
        if (!s.online || s.follow[spaceId] == FollowState.Busy) return
        val name = s.suggestions.firstOrNull { it.space.spaceId == spaceId }?.space?.name ?: "the space"
        _state.update { it.copy(follow = it.follow + (spaceId to FollowState.Busy)) }
        viewModelScope.launch {
            when (val r = spaces.follow(spaceId)) {
                is AppResult.Success -> _state.update { it.copy(follow = it.follow + (spaceId to FollowState.Following), toast = "You follow $name") }
                is AppResult.Failure -> _state.update { it.copy(follow = it.follow - spaceId, toast = followError(r.error)) }
            }
        }
    }

    fun onToastShown() = _state.update { it.copy(toast = null) }
}

/** Copy for a follow the server refused, keyed on the code. */
internal fun followError(error: AppError): String = when ((error as? AppError.Api)?.code) {
    "spaces.banned" -> "You can’t follow this space."
    "spaces.payment_required" -> "This space is members only. Open it to join."
    "spaces.invite_required" -> "This space is invite only."
    "spaces.space_not_found", "spaces.space_unavailable" -> "This space isn’t available any more."
    "spaces.account_restricted" -> "Your account can’t follow spaces right now."
    else -> ErrorMessages.forError(error)
}
