package com.circaso.feature.share.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.ConnectUseCase
import com.circaso.feature.contacts.domain.ConnectVia
import com.circaso.feature.messaging.domain.OpenDirectChatUseCase
import com.circaso.feature.share.domain.ListShareLinksUseCase
import com.circaso.feature.share.domain.OpenShareLinkUseCase
import com.circaso.feature.share.domain.OpenTarget
import com.circaso.feature.share.domain.RevokeShareLinkUseCase
import com.circaso.feature.share.domain.ShareLink
import com.circaso.feature.share.domain.SharedLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a shared link's main button takes this person. */
sealed interface SharedLinkDestination {
    data class Transfer(val transferId: String) : SharedLinkDestination
    data class Chama(val chatId: String) : SharedLinkDestination
    data class Chat(val chatId: String) : SharedLinkDestination
    data class Pay(val userId: String, val displayName: String?) : SharedLinkDestination
    data class PayAddress(val address: String, val displayName: String?) : SharedLinkDestination
}

/** Loading, the link, gone (unknown, turned off or ended all look the same), offline, or another error. */
sealed interface SharedLinkLoad {
    data object Loading : SharedLinkLoad
    data class Loaded(val link: SharedLink) : SharedLinkLoad
    data object NotFound : SharedLinkLoad
    data object Offline : SharedLinkLoad
    data class Failed(val message: String) : SharedLinkLoad
}

data class SharedLinkUiState(
    val load: SharedLinkLoad = SharedLinkLoad.Loading,
    /** The main button is working (connecting through an invite, finding the person). */
    val opening: Boolean = false,
    val message: String? = null,
    val online: Boolean = true,
) {
    /** The main button would only fail offline: connecting through an invite or a @handle needs the server. */
    val actionNeedsConnection: Boolean
        get() = when (val o = (load as? SharedLinkLoad.Loaded)?.link?.open) {
            is OpenTarget.Invite -> true
            is OpenTarget.Pay -> o.handle != null
            else -> false
        }
}

/** A share link opened on this phone (`/s/{code}`): SH4, then the action the server says this person may take. */
class SharedLinkViewModel(
    private val code: String,
    private val openLink: OpenShareLinkUseCase,
    private val connection: ConnectionState,
    private val connect: ConnectUseCase,
    private val openDirect: OpenDirectChatUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(SharedLinkUiState())
    val state: StateFlow<SharedLinkUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            var wasOnline: Boolean? = null
            connection.status.collect { s ->
                val online = s == ConnectionStatus.Online
                _state.update { it.copy(online = online) }
                // Back online after it couldn't open: open it again, without being asked.
                val load = _state.value.load
                if (online && wasOnline == false && (load == SharedLinkLoad.Offline || load is SharedLinkLoad.Failed)) load()
                wasOnline = online
            }
        }
        load()
    }

    fun load() {
        _state.update { it.copy(load = SharedLinkLoad.Loading) }
        viewModelScope.launch {
            val r = openLink(code)
            _state.update {
                it.copy(
                    load = when (r) {
                        is AppResult.Success -> SharedLinkLoad.Loaded(r.value)
                        is AppResult.Failure -> failureLoad(r.error)
                    },
                )
            }
        }
    }

    fun onMessageShown() = _state.update { it.copy(message = null) }

    fun onOpen(go: (SharedLinkDestination) -> Unit) {
        val link = (_state.value.load as? SharedLinkLoad.Loaded)?.link ?: return
        val target = link.open ?: return
        if (_state.value.opening) return
        when (target) {
            is OpenTarget.Transfer -> go(SharedLinkDestination.Transfer(target.transferId))
            is OpenTarget.Chama -> go(SharedLinkDestination.Chama(target.chatId))
            is OpenTarget.PayAddress -> go(SharedLinkDestination.PayAddress(target.address, target.displayName ?: link.address?.name))
            is OpenTarget.Invite -> online { connectThen(ConnectVia.Invite, target.token) { userId, _ -> chat(userId, go) } }
            is OpenTarget.Pay -> when {
                // A handle connects one way, so the payment can reach them; then Pay opens with the id it answered.
                target.handle != null -> online { connectThen(ConnectVia.Handle, target.handle) { userId, name -> _state.update { it.copy(opening = false) }; go(SharedLinkDestination.Pay(userId, target.displayName ?: name)) } }
                target.userId != null -> go(SharedLinkDestination.Pay(target.userId, target.displayName))
            }
        }
    }

    private fun online(block: () -> Unit) {
        if (connection.status.value != ConnectionStatus.Online) {
            _state.update { it.copy(message = "That needs a connection. Try again when you're back online.") }
            return
        }
        block()
    }

    private fun connectThen(via: ConnectVia, value: String, then: suspend (userId: String, name: String) -> Unit) {
        _state.update { it.copy(opening = true, message = null) }
        viewModelScope.launch {
            when (val r = connect(via, value)) {
                is AppResult.Success -> then(r.value.person.userId, r.value.person.displayName)
                is AppResult.Failure -> _state.update { it.copy(opening = false, message = withRef(r.error)) }
            }
        }
    }

    private suspend fun chat(userId: String, go: (SharedLinkDestination) -> Unit) {
        when (val r = openDirect(userId)) {
            is AppResult.Success -> { _state.update { it.copy(opening = false) }; go(SharedLinkDestination.Chat(r.value.chatId)) }
            is AppResult.Failure -> _state.update { it.copy(opening = false, message = withRef(r.error)) }
        }
    }

    private fun withRef(error: AppError) = ErrorMessages.forError(error, InviteWords) + (ErrorMessages.reference(error)?.let { " Ref $it" } ?: "")

    private companion object {
        val InviteWords = mapOf("users.invite_invalid" to "This invite has ended. Ask them for a new link.")
    }
}

internal fun failureLoad(error: AppError): SharedLinkLoad = when {
    error == AppError.Network -> SharedLinkLoad.Offline
    error is AppError.Api && error.code == "share.link_not_found" -> SharedLinkLoad.NotFound
    else -> SharedLinkLoad.Failed(ErrorMessages.forError(error) + (ErrorMessages.reference(error)?.let { " Ref $it" } ?: ""))
}

data class SharedLinksUiState(
    val links: List<ShareLink> = emptyList(),
    val loading: Boolean = true,
    /** The list couldn't be read: offline, or an error with its reference. Null once it loaded. */
    val failure: SharedLinkLoad? = null,
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    /** The next page failed: the footer offers Try Again instead of spinning. */
    val moreFailed: Boolean = false,
    /** The link being turned off, one at a time. */
    val revoking: String? = null,
    val online: Boolean = true,
    val message: String? = null,
)

/** You → Shared links: the links you made that still work, each one can be turned off (SH2, SH3). */
class SharedLinksViewModel(
    private val list: ListShareLinksUseCase,
    private val revoke: RevokeShareLinkUseCase,
    connection: ConnectionState,
) : ViewModel() {
    private val _state = MutableStateFlow(SharedLinksUiState())
    val state: StateFlow<SharedLinksUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            var wasOnline: Boolean? = null
            connection.status.collect { s ->
                val online = s == ConnectionStatus.Online
                _state.update { it.copy(online = online) }
                // Back online after the list failed: read it again, without being asked.
                if (online && wasOnline == false && _state.value.failure != null) refresh()
                wasOnline = online
            }
        }
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            when (val r = list(null)) {
                is AppResult.Success -> _state.update { it.copy(loading = false, links = r.value.links, nextCursor = r.value.nextCursor) }
                is AppResult.Failure -> _state.update { it.copy(loading = false, failure = failureLoad(r.error)) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true, moreFailed = false) }
        viewModelScope.launch {
            when (val r = list(cursor)) {
                is AppResult.Success -> _state.update { st ->
                    st.copy(loadingMore = false, links = (st.links + r.value.links).distinctBy { it.code }, nextCursor = r.value.nextCursor)
                }
                is AppResult.Failure -> _state.update {
                    it.copy(loadingMore = false, moreFailed = true, message = ErrorMessages.forError(r.error) + (ErrorMessages.reference(r.error)?.let { ref -> " Ref $ref" } ?: ""))
                }
            }
        }
    }

    fun onRevoke(code: String) {
        val s = _state.value
        if (s.revoking != null) return
        if (!s.online) { _state.update { it.copy(message = "Turning a link off needs a connection.") }; return }
        _state.update { it.copy(revoking = code, message = null) }
        viewModelScope.launch {
            when (val r = revoke(code)) {
                is AppResult.Success -> _state.update { st -> st.copy(revoking = null, links = st.links.filterNot { it.code == code }, message = "Link turned off. It no longer opens.") }
                is AppResult.Failure -> {
                    val gone = (r.error as? AppError.Api)?.code == "share.link_not_found"
                    _state.update { st ->
                        // Already off (or ended): it goes from the list either way.
                        if (gone) st.copy(revoking = null, links = st.links.filterNot { it.code == code }, message = "This link had already ended.")
                        else st.copy(revoking = null, message = ErrorMessages.forError(r.error) + (ErrorMessages.reference(r.error)?.let { " Ref $it" } ?: ""))
                    }
                }
            }
        }
    }

    fun onMessageShown() = _state.update { it.copy(message = null) }
}
