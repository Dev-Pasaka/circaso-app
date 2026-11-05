package com.circaso.feature.spaces.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.spaces.domain.MySpace
import com.circaso.feature.spaces.domain.SpaceSummary
import com.circaso.feature.spaces.domain.SpaceTopic
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.domain.SpacesRepository
import com.circaso.feature.wallet.domain.FiatValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A person search can show: a contact with a live moment I can see (SD2's People). Found on the phone only. */
data class SearchPerson(val userId: String, val name: String, val avatarUrl: String?, val handle: String?, val momentText: String?)

/** Design SD2 (SD2b by @handle, SD2c no results, SD2d offline). */
data class SpaceSearchUiState(
    val query: String = "",
    /** null is All. A `@` query searches handles only, so the chips step aside (SD2b). */
    val topic: SpaceTopic? = null,
    val exact: SpaceSummary? = null,
    val spaces: List<SpaceSummary> = emptyList(),
    val people: List<SearchPerson> = emptyList(),
    /** SD2d: offline, my spaces on the phone that match. */
    val mine: List<MySpace> = emptyList(),
    val searching: Boolean = false,
    /** The last query the server answered (results on screen belong to it). */
    val answered: String? = null,
    val failed: Boolean = false,
    val requestId: String? = null,
    val online: Boolean = true,
    val follow: Map<String, FollowState> = emptyMap(),
    val toast: String? = null,
    val fiat: FiatValue? = null,
    /** Spaces I'm in: their rows open them instead of offering Follow. */
    val joined: Set<String> = emptySet(),
) {
    val trimmed: String get() = query.trim()
    val byHandle: Boolean get() = trimmed.startsWith("@")
    /** SD2c: the server answered and nothing matched, spaces or people. */
    val empty: Boolean get() = online && answered == trimmed && trimmed.isNotEmpty() && exact == null && spaces.isEmpty() && people.isEmpty() && !failed
}

class SpaceSearchViewModel(
    private val spacesRepo: SpacesRepository,
    private val peopleSource: Flow<List<SearchPerson>>,
    online: Flow<Boolean>,
    fiat: Flow<FiatValue?> = flowOf(null),
    /** The pause after typing before searching (SD2: "after a short pause"). */
    private val debounceMs: Long = 300,
) : ViewModel() {
    private val _state = MutableStateFlow(SpaceSearchUiState())
    val state: StateFlow<SpaceSearchUiState> = _state.asStateFlow()

    private var allPeople: List<SearchPerson> = emptyList()
    private var allMine: List<MySpace> = emptyList()
    private var job: Job? = null

    init {
        viewModelScope.launch { peopleSource.collect { allPeople = it; refilterLocal() } }
        viewModelScope.launch {
            spacesRepo.mySpaces.collect { list ->
                allMine = list
                _state.update { it.copy(joined = list.filter { m -> m.me.isMember }.map { m -> m.space.spaceId }.toSet()) }
                refilterLocal()
            }
        }
        viewModelScope.launch { fiat.collect { f -> _state.update { it.copy(fiat = f) } } }
        viewModelScope.launch {
            online.collect { on ->
                val back = on && !_state.value.online
                _state.update { it.copy(online = on) }
                refilterLocal()
                if (back) search(now = true)
            }
        }
    }

    fun onQuery(text: String) {
        _state.update { it.copy(query = text.take(60)) }
        refilterLocal()
        search()
    }

    fun onTopic(topic: SpaceTopic?) {
        _state.update { it.copy(topic = topic) }
        search(now = true)
    }

    fun onRetry() = search(now = true)

    private fun refilterLocal() {
        val s = _state.value
        val q = s.trimmed.trimStart('@')
        _state.update {
            it.copy(
                people = if (q.isEmpty() || s.byHandle) emptyList() else allPeople.filter { p -> p.name.contains(q, true) || p.handle?.contains(q, true) == true || p.momentText?.contains(q, true) == true },
                mine = if (q.isEmpty()) emptyList() else allMine.filter { m -> m.space.name.contains(q, true) || m.space.handle.contains(q.lowercase()) },
            )
        }
    }

    private fun search(now: Boolean = false) {
        job?.cancel()
        val s = _state.value
        val q = s.trimmed
        if (q.isEmpty() || q == "@") {
            _state.update { it.copy(exact = null, spaces = emptyList(), searching = false, answered = null, failed = false) }
            return
        }
        // Offline, search looks only at my spaces on the phone (SD2d); new ones wait for the connection.
        if (!s.online) { _state.update { it.copy(searching = false) }; return }
        job = viewModelScope.launch {
            if (!now) delay(debounceMs)
            _state.update { it.copy(searching = true) }
            val topic = if (q.startsWith("@")) null else _state.value.topic
            when (val r = spacesRepo.search(q, topic)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        // Invite-only spaces never show in search (the server doesn't send them; the phone holds the line too).
                        exact = r.value.exact?.takeIf { e -> e.visibility != SpaceVisibility.InviteOnly },
                        spaces = r.value.spaces.filter { e -> e.visibility != SpaceVisibility.InviteOnly },
                        searching = false, answered = q, failed = false, requestId = null,
                    )
                }
                is AppResult.Failure -> _state.update {
                    // A dropped connection while the phone thinks it's online still says something (never a blank list).
                    it.copy(searching = false, failed = true, answered = q, requestId = com.circaso.core.common.ErrorMessages.reference(r.error))
                }
            }
        }
    }

    /** Follow in place, never optimistic (SS1b). */
    fun onFollow(space: SpaceSummary) {
        val s = _state.value
        if (!s.online || s.follow[space.spaceId] == FollowState.Busy) return
        _state.update { it.copy(follow = it.follow + (space.spaceId to FollowState.Busy)) }
        viewModelScope.launch {
            when (val r = spacesRepo.follow(space.spaceId)) {
                is AppResult.Success -> _state.update { it.copy(follow = it.follow + (space.spaceId to FollowState.Following), toast = "You follow ${space.name}") }
                is AppResult.Failure -> _state.update { it.copy(follow = it.follow - space.spaceId, toast = followError(r.error)) }
            }
        }
    }

    fun onToastShown() = _state.update { it.copy(toast = null) }
}
