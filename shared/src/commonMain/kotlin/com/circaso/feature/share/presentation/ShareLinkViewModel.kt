package com.circaso.feature.share.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.share.domain.CreateShareLinkUseCase
import com.circaso.feature.share.domain.LinkEnds
import com.circaso.feature.share.domain.ShareMessage
import com.circaso.feature.share.domain.ShareRequest
import com.circaso.feature.share.domain.ShareTarget
import com.circaso.feature.share.domain.ShareText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareLinkUiState(
    /** What is being shared; the options sheet shows while it is set and [sheetOpen]. */
    val target: ShareTarget? = null,
    val sheetOpen: Boolean = false,
    val showNames: Boolean = true,
    val ends: LinkEnds = LinkEnds.Never,
    val creating: Boolean = false,
    /** In the sheet, under the button: what went wrong, keyed on the code. */
    val error: String? = null,
    val online: Boolean = true,
    /** The share sheet to open, once: the screen opens it and calls [ShareLinkViewModel.onShared]. */
    val share: ShareMessage? = null,
    /** A toast for a share that had no sheet (an invite, pay me) and failed. */
    val notice: String? = null,
)

/**
 * Every share in the app goes through here (decision 2026-11-06): the options sheet for a receipt, a statement or an
 * address (show names, when the link ends), then SH1, then the system share sheet with the URL and one line. Making a
 * link needs the server, so offline it says so instead of pretending.
 */
class ShareLinkViewModel(
    private val create: CreateShareLinkUseCase,
    connection: ConnectionState,
) : ViewModel() {
    private val _state = MutableStateFlow(ShareLinkUiState())
    val state: StateFlow<ShareLinkUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { connection.status.collect { s -> _state.update { it.copy(online = s == ConnectionStatus.Online) } } }
    }

    fun start(target: ShareTarget) {
        if (_state.value.creating) return
        if (target.hasOptions) {
            _state.update { it.copy(target = target, sheetOpen = true, showNames = true, ends = LinkEnds.Never, error = null) }
        } else {
            _state.update { it.copy(target = target, sheetOpen = false, error = null) }
            make(target, ShareRequest.of(target), inSheet = false)
        }
    }

    fun onShowNames(on: Boolean) = _state.update { it.copy(showNames = on) }
    fun onEnds(ends: LinkEnds) = _state.update { it.copy(ends = ends) }

    fun onConfirm() {
        val s = _state.value
        val target = s.target ?: return
        if (s.creating) return
        make(target, ShareRequest.of(target, s.showNames, s.ends), inSheet = true)
    }

    /** Closes the sheet even while the link is being made: it finishes behind, and the share sheet still opens. */
    fun onDismiss() {
        _state.update { it.copy(sheetOpen = false, error = null) }
    }

    fun onShared() = _state.update { it.copy(share = null) }
    fun onNoticeShown() = _state.update { it.copy(notice = null) }

    private fun make(target: ShareTarget, request: ShareRequest, inSheet: Boolean) {
        if (!_state.value.online) {
            _state.update { if (inSheet) it.copy(error = OfflineText) else it.copy(notice = OfflineText) }
            return
        }
        _state.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            when (val r = create(request)) {
                is AppResult.Success -> _state.update {
                    // The target stays: the sheet keeps its content while it slides out; the next start() replaces it.
                    it.copy(creating = false, sheetOpen = false, share = ShareText.of(r.value))
                }
                is AppResult.Failure -> {
                    val text = messageFor(target, r.error)
                    _state.update { if (inSheet && it.sheetOpen) it.copy(creating = false, error = text) else it.copy(creating = false, notice = text) }
                }
            }
        }
    }

    companion object {
        const val OfflineText = "Sharing makes a link, which needs a connection. Try again when you're back online."

        /** The kind's own words where the generic ones would be vague. */
        internal fun messageFor(target: ShareTarget, error: AppError): String {
            val override = when (target) {
                is ShareTarget.Receipt -> mapOf("share.not_shareable" to "This payment can be shared once it's signed and sent.")
                is ShareTarget.Statement -> mapOf("share.not_shareable" to "This group needs a target before its statement can be shared.")
                ShareTarget.PayMe -> mapOf("share.not_shareable" to "Set your @handle first, from the You tab.")
                else -> emptyMap()
            } + mapOf("request.rate_limited" to "You've made a lot of links in the last hour. Try again a little later.")
            return ErrorMessages.forError(error, override) + (ErrorMessages.reference(error)?.let { " Ref $it" } ?: "")
        }
    }
}
