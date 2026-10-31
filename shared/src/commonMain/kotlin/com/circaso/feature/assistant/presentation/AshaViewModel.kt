package com.circaso.feature.assistant.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.ErrorMessages
import com.circaso.core.common.AppResult
import com.circaso.core.platform.AudioPlayer
import com.circaso.core.platform.AudioRecorder
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.MaxVoiceSeconds
import com.circaso.core.platform.RecordingStart
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.assistant.domain.AskAshaUseCase
import com.circaso.feature.assistant.domain.AshaEvent
import com.circaso.feature.assistant.domain.AshaMessage
import com.circaso.feature.assistant.domain.DeleteAshaHistoryUseCase
import com.circaso.feature.assistant.domain.ObserveAshaHistoryUseCase
import com.circaso.feature.assistant.domain.RefreshAshaHistoryUseCase
import com.circaso.feature.assistant.domain.SpeakUseCase
import com.circaso.feature.assistant.domain.TranscribeVoiceUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VoiceState { Idle, Recording, Transcribing }

data class AshaUiState(
    val messages: List<AshaMessage> = emptyList(),
    val draft: String = "",
    /** The question being answered right now, shown at once; it joins the history when the reply is saved. */
    val pendingQuestion: String? = null,
    /** The reply as it streams in. */
    val streaming: String = "",
    /** "Reading your chats…" while the phone runs a tool for Asha. */
    val status: String? = null,
    val online: Boolean = true,
    val error: String? = null,
    val confirmClear: Boolean = false,
    val voice: VoiceState = VoiceState.Idle,
    val recordedSeconds: Int = 0,
    /** The reply being read aloud (its text), so its speaker button can show "stop". */
    val speakingText: String? = null,
    /** Asha's conversations, newest first, and the one on screen (C1). */
    val conversations: List<com.circaso.feature.assistant.domain.AshaConversation> = emptyList(),
    val currentConversation: String? = null,
    val showHistory: Boolean = false,
) {
    /** The open conversation's title, for the header; "New chat" until it has one. */
    val conversationTitle: String? get() = (currentConversation?.let { id -> conversations.firstOrNull { it.id == id } } ?: conversations.firstOrNull())?.title
    val sending get() = pendingQuestion != null
    val canSend get() = online && !sending && voice == VoiceState.Idle && draft.isNotBlank()
    /** Voice needs the server for both directions, so it is offered only online. */
    val canUseVoice get() = online && !sending && voice != VoiceState.Transcribing
}

/** Asha (design F1). Reading the history works offline; asking needs the server, and says so before the person types. */
class AshaViewModel(
    observeHistory: ObserveAshaHistoryUseCase,
    private val refreshHistory: RefreshAshaHistoryUseCase,
    private val ask: AskAshaUseCase,
    private val clearHistory: DeleteAshaHistoryUseCase,
    connection: ConnectionState,
    private val recorder: AudioRecorder,
    private val player: AudioPlayer,
    private val transcribe: TranscribeVoiceUseCase,
    private val speak: SpeakUseCase,
    /** The conversation list (C1–C4); absent in previews and tests that don't need it. */
    private val chats: com.circaso.feature.assistant.domain.AshaRepository? = null,
) : ViewModel() {
    private val local = MutableStateFlow(AshaUiState())
    private var job: Job? = null
    private var timer: Job? = null
    private var speaking: Job? = null

    private val conversationsFlow = chats?.let { r -> combine(r.conversations, r.current) { list, cur -> list to cur } } ?: kotlinx.coroutines.flow.flowOf(emptyList<com.circaso.feature.assistant.domain.AshaConversation>() to null)

    val state: StateFlow<AshaUiState> = combine(observeHistory(), local, connection.status, conversationsFlow) { messages, ui, status, conv ->
        ui.copy(messages = messages, online = status == ConnectionStatus.Online, conversations = conv.first, currentConversation = conv.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AshaUiState())

    fun onHistory(open: Boolean) = local.update { it.copy(showHistory = open) }

    /** A brand-new conversation: Asha starts with no context (cheaper, and the reason to start one). */
    fun onNewChat() {
        val r = chats ?: return
        if (!state.value.online || state.value.sending) return
        local.update { it.copy(showHistory = false, error = null) }
        viewModelScope.launch {
            (r.newConversation() as? AppResult.Failure)?.let { f -> local.update { it.copy(error = message(f.error)) } }
        }
    }

    fun onOpenConversation(id: String) {
        stopSpeaking()
        chats?.open(id)
        local.update { it.copy(showHistory = false) }
    }

    fun onDeleteConversation(id: String) {
        val r = chats ?: return
        viewModelScope.launch { (r.deleteConversation(id) as? AppResult.Failure)?.let { f -> local.update { it.copy(error = message(f.error)) } } }
    }

    init { viewModelScope.launch { refreshHistory() } }

    fun onDraft(text: String) = local.update { it.copy(draft = text, error = null) }

    /** A suggestion chip: asked at once, as if typed and sent. */
    fun onSuggestion(text: String) {
        if (!state.value.online || state.value.sending) return
        local.update { it.copy(draft = text, error = null) }
        onSend()
    }

    fun onSend() {
        val s = state.value
        if (!s.canSend) return
        val question = s.draft.trim()
        local.update { it.copy(draft = "", pendingQuestion = question, streaming = "", status = null, error = null) }
        job = viewModelScope.launch {
            var failed: AppError? = null
            ask(question).collect { event ->
                when (event) {
                    is AshaEvent.Delta -> local.update { it.copy(streaming = it.streaming + event.text, status = null) }
                    is AshaEvent.UsingTool -> local.update { it.copy(status = "Reading your chats…") }
                    is AshaEvent.Status -> local.update { it.copy(status = event.label) }
                    is AshaEvent.Done -> Unit
                    is AshaEvent.Failed -> failed = event.error
                    is AshaEvent.ToolRequest -> Unit
                }
            }
            local.update {
                it.copy(
                    pendingQuestion = null, streaming = "", status = null,
                    // Nothing was answered: give the question back so it is not lost.
                    draft = if (failed != null) question else it.draft,
                    error = failed?.let { e -> message(e) },
                )
            }
        }
    }

    /** Tap to start recording, tap again to stop. The words land in the draft so the person can read them before sending. */
    fun onMic() {
        val s = state.value
        when (s.voice) {
            VoiceState.Recording -> finishRecording()
            VoiceState.Idle -> if (s.canUseVoice) startRecording()
            VoiceState.Transcribing -> Unit
        }
    }

    private fun startRecording() {
        stopSpeaking()
        local.update { it.copy(voice = VoiceState.Transcribing, error = null) } // blocks a second tap while the microphone opens
        viewModelScope.launch {
            when (recorder.start()) {
                RecordingStart.Started -> {
                    local.update { it.copy(voice = VoiceState.Recording, recordedSeconds = 0) }
                    timer = launch {
                        repeat(MaxVoiceSeconds) { delay(1_000); local.update { s -> s.copy(recordedSeconds = s.recordedSeconds + 1) } }
                        finishRecording()
                    }
                }
                RecordingStart.PermissionDenied -> local.update { it.copy(voice = VoiceState.Idle, error = "Allow the microphone in Settings to ask Asha by voice.") }
                RecordingStart.Failed -> local.update { it.copy(voice = VoiceState.Idle, error = "Couldn't start the microphone. Try again.") }
            }
        }
    }

    private fun finishRecording() {
        if (local.value.voice != VoiceState.Recording) return
        local.update { it.copy(voice = VoiceState.Transcribing) }
        timer?.cancel()
        viewModelScope.launch {
            val clip = recorder.stop()
            if (clip == null) {
                local.update { it.copy(voice = VoiceState.Idle, error = "Nothing was recorded. Hold on a little longer and try again.") }
                return@launch
            }
            when (val result = transcribe(clip.bytes, clip.contentType, clip.durationSeconds)) {
                is AppResult.Success -> local.update {
                    it.copy(voice = VoiceState.Idle, draft = listOf(it.draft.trim(), result.value.trim()).filter(String::isNotEmpty).joinToString(" "))
                }
                is AppResult.Failure -> local.update { it.copy(voice = VoiceState.Idle, error = message(result.error)) }
            }
        }
    }

    /** Throws the recording away (leaving the screen, or going offline). */
    fun onCancelRecording() {
        if (local.value.voice != VoiceState.Recording) return
        timer?.cancel()
        local.update { it.copy(voice = VoiceState.Idle) }
        viewModelScope.launch { recorder.cancel() }
    }

    /** Reads a reply aloud; tapping the same reply again stops it. */
    fun onSpeak(text: String) {
        val wasSpeaking = local.value.speakingText == text
        stopSpeaking()
        if (wasSpeaking || !state.value.online) return
        local.update { it.copy(speakingText = text, error = null) }
        speaking = viewModelScope.launch {
            try {
                when (val audio = speak(text)) {
                    is AppResult.Success -> player.play(audio.value, "audio/mpeg")
                    is AppResult.Failure -> local.update { it.copy(error = message(audio.error)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(error = "Couldn't play that. Try again.") }
            } finally {
                local.update { if (it.speakingText == text) it.copy(speakingText = null) else it }
            }
        }
    }

    private fun stopSpeaking() {
        speaking?.cancel()
        player.stop()
        local.update { it.copy(speakingText = null) }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        timer?.cancel()
        player.stop()
        // The scope is already cancelled here, and the microphone must be released.
        if (local.value.voice == VoiceState.Recording) GlobalScope.launch { recorder.cancel() }
    }

    private fun message(error: AppError): String =
        if (error is AppError.Api && error.code == "assistant.quota_exceeded" && error.retryAfterSeconds != null) {
            val hours = (error.retryAfterSeconds + 3599) / 3600
            "You've used today's questions for Asha. They reset in about $hours ${if (hours == 1) "hour" else "hours"}."
        } else ErrorMessages.forError(error)

    fun onClearRequested() = local.update { it.copy(confirmClear = true) }
    fun onClearDismissed() = local.update { it.copy(confirmClear = false) }
    fun onClearConfirmed() {
        if (!local.value.confirmClear) return // a deletion only ever runs behind its confirmation
        local.update { it.copy(confirmClear = false) }
        viewModelScope.launch { clearHistory() }
    }
}
