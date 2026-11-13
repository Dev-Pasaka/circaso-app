package com.circaso.feature.support.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.ObserveChatsUseCase
import com.circaso.feature.messaging.domain.OpenDirectChatUseCase
import com.circaso.feature.messaging.domain.SendMessageUseCase
import com.circaso.feature.profile.domain.FindByHandleUseCase
import com.circaso.feature.support.Support
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HelpUiState(
    val topic: Support.Topic? = null,
    /** The existing chat with support, newest message first, for "Your conversations". */
    val conversation: ChatSummary? = null,
    val online: Boolean = true,
    val opening: Boolean = false,
    val error: String? = null,
    /** "Send diagnostic log" (2026-11-13): its sheet, while open. */
    val logSheet: LogSheet? = null,
)

/** The "Send diagnostic log" sheet: asking first, sending, the code to give support, or what went wrong. */
sealed interface LogSheet {
    data object Confirm : LogSheet
    data object Sending : LogSheet
    data class Sent(val code: String) : LogSheet
    /** [reference]: the request id (and an unknown code) for support, on its own line. */
    data class Failed(val message: String, val reference: String? = null) : LogSheet
}

/** Design SP01: chat with a person (calls arrive with calls), what it's about, and the conversation so far. */
class HelpViewModel(
    observeChats: ObserveChatsUseCase,
    private val findHandle: FindByHandleUseCase,
    private val openDirect: OpenDirectChatUseCase,
    private val send: SendMessageUseCase,
    connection: ConnectionState,
    /** The signed-in person. */
    private val myUserId: () -> String?,
    /** Uploads the diagnostic log and answers its code; null hides the row. */
    private val sendLog: (suspend () -> AppResult<com.circaso.feature.support.DiagnosticLogReceipt>)? = null,
) : ViewModel() {
    val canSendLog: Boolean get() = sendLog != null
    private val local = MutableStateFlow(HelpUiState())
    private val _opened = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** The support chat to open. */
    val opened: SharedFlow<String> = _opened
    private val _call = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** The support chat to call (SP03): a free internet call to the support account. */
    val call: SharedFlow<String> = _call

    /** SP01's "Call us": the support chat, opened if needed, then a voice call to it. */
    fun onCall() {
        if (!state.value.online || state.value.opening) return
        val existing = state.value.conversation?.chat?.chatId
        if (existing != null) { _call.tryEmit(existing); return }
        val supportId = Support.UserId ?: run { local.update { it.copy(error = "Support isn't set up yet. Ask Asha, or try again later.") }; return }
        local.update { it.copy(opening = true, error = null) }
        viewModelScope.launch {
            when (val r = openDirect(supportId)) {
                is AppResult.Success -> { local.update { it.copy(opening = false) }; _call.tryEmit(r.value.chatId) }
                is AppResult.Failure -> local.update { it.copy(opening = false, error = ErrorMessages.forError(r.error) + (ErrorMessages.reference(r.error)?.let { ref -> " $ref" } ?: "")) }
            }
        }
    }

    val state: StateFlow<HelpUiState> = combine(local, observeChats(), connection.status) { ui, chats, c ->
        ui.copy(conversation = chats.firstOrNull { Support.isSupportChat(it.chat, myUserId()) }, online = c == ConnectionStatus.Online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    /** SP01's topic rows: the chat opens with that topic as the person's first message. */
    fun onTopic(topic: Support.Topic) {
        local.update { it.copy(topic = topic) }
        onChat()
    }

    /** Opens (or reopens) the chat with support; a chosen topic goes first, as the person's own words. */
    fun onChat() {
        val s = state.value
        s.conversation?.let { existing ->
            if (s.topic == null) { _opened.tryEmit(existing.chat.chatId); return }
        }
        if (!s.online || s.opening) return
        local.update { it.copy(opening = true, error = null) }
        viewModelScope.launch {
            val supportId = Support.UserId
            if (supportId == null) { local.update { it.copy(opening = false, error = "Support isn't set up yet. Ask Asha, or try again later.") }; return@launch }
            val chatId = s.conversation?.chat?.chatId ?: when (val chat = openDirect(supportId)) {
                is AppResult.Success -> chat.value.chatId
                is AppResult.Failure -> { local.update { it.copy(opening = false, error = ErrorMessages.forError(chat.error) + (ErrorMessages.reference(chat.error)?.let { r -> " $r" } ?: "")) }; return@launch }
            }
            s.topic?.let { send(chatId, it.opener) }
            local.update { it.copy(opening = false, topic = null) }
            _opened.tryEmit(chatId)
        }
    }

    fun onErrorShown() = local.update { it.copy(error = null) }

    /** You → Help → "Send diagnostic log": asks first, so nothing leaves the phone on a stray tap. */
    fun onSendLog() { if (sendLog != null) local.update { it.copy(logSheet = LogSheet.Confirm) } }

    fun onSendLogConfirmed() {
        val upload = sendLog ?: return
        if (state.value.logSheet == LogSheet.Sending) return
        // The buttons are off without a connection; this only backs them up.
        if (!state.value.online) return
        local.update { it.copy(logSheet = LogSheet.Sending) }
        viewModelScope.launch {
            val next = when (val r = upload()) {
                is AppResult.Success -> LogSheet.Sent(r.value.code)
                is AppResult.Failure -> LogSheet.Failed(
                    ErrorMessages.forError(r.error),
                    ErrorMessages.reference(r.error)?.let { "Ref $it" },
                )
            }
            local.update { if (it.logSheet == LogSheet.Sending) it.copy(logSheet = next) else it }
        }
    }

    fun onLogSheetClosed() = local.update { if (it.logSheet == LogSheet.Sending) it else it.copy(logSheet = null) }
}
