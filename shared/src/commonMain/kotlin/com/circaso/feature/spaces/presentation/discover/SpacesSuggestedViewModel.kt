package com.circaso.feature.spaces.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.spaces.domain.SpaceTopic
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.domain.SpacesRepository
import com.circaso.feature.spaces.domain.Suggestion
import com.circaso.feature.wallet.domain.FiatValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Design SD3: Suggested in full, filtered by topic chips; Follow flips to Following in place. */
data class SpacesSuggestedUiState(
    val topic: SpaceTopic? = null,
    val items: List<Suggestion> = emptyList(),
    val next: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val failed: Boolean = false,
    val requestId: String? = null,
    val online: Boolean = true,
    val follow: Map<String, FollowState> = emptyMap(),
    val toast: String? = null,
    val fiat: FiatValue? = null,
)

class SpacesSuggestedViewModel(
    private val spaces: SpacesRepository,
    online: Flow<Boolean>,
    fiat: Flow<FiatValue?> = flowOf(null),
) : ViewModel() {
    private val _state = MutableStateFlow(SpacesSuggestedUiState())
    val state: StateFlow<SpacesSuggestedUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch { fiat.collect { f -> _state.update { it.copy(fiat = f) } } }
        viewModelScope.launch {
            online.collect { on ->
                val back = on && !_state.value.online
                _state.update { it.copy(online = on) }
                if (back || (on && _state.value.items.isEmpty() && job == null)) load()
            }
        }
    }

    fun onTopic(topic: SpaceTopic?) {
        if (topic == _state.value.topic) return
        _state.update { it.copy(topic = topic, items = emptyList(), next = null, loading = true) }
        load()
    }

    fun onRetry() = load()

    private fun load() {
        job?.cancel()
        val topic = _state.value.topic
        job = viewModelScope.launch {
            _state.update { it.copy(loading = it.items.isEmpty(), failed = false) }
            when (val r = spaces.suggested(topic)) {
                is AppResult.Success -> _state.update { it.copy(items = r.value.items.visible(), next = r.value.next, loading = false) }
                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, failed = r.error !is AppError.Network && it.items.isEmpty(), requestId = (r.error as? AppError.Api)?.requestId)
                }
            }
        }
    }

    /** The next page, when the list nears its end. */
    fun onLoadMore() {
        val s = _state.value
        val cursor = s.next ?: return
        if (s.loadingMore || !s.online) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            when (val r = spaces.suggested(s.topic, cursor)) {
                is AppResult.Success -> _state.update { st -> st.copy(items = (st.items + r.value.items.visible()).distinctBy { it.space.spaceId }, next = r.value.next, loadingMore = false) }
                is AppResult.Failure -> _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    fun onFollow(spaceId: String) {
        val s = _state.value
        if (!s.online || s.follow[spaceId] == FollowState.Busy) return
        val name = s.items.firstOrNull { it.space.spaceId == spaceId }?.space?.name ?: "the space"
        _state.update { it.copy(follow = it.follow + (spaceId to FollowState.Busy)) }
        viewModelScope.launch {
            when (val r = spaces.follow(spaceId)) {
                is AppResult.Success -> _state.update { it.copy(follow = it.follow + (spaceId to FollowState.Following), toast = "You follow $name") }
                is AppResult.Failure -> _state.update { it.copy(follow = it.follow - spaceId, toast = followError(r.error)) }
            }
        }
    }

    fun onToastShown() = _state.update { it.copy(toast = null) }

    private fun List<Suggestion>.visible() = filter { it.space.visibility != SpaceVisibility.InviteOnly }
}
