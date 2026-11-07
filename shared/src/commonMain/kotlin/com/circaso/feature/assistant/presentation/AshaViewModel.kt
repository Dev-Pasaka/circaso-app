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
import com.circaso.feature.assistant.domain.GetAshaUsageUseCase
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class VoiceState { Idle, Recording, Transcribing }

/**
 * A question that failed only because the connection dropped (no network, offline, a timeout, or the answer's stream
 * cut off). It stays in its bubble with a clock and is asked again by itself once the connection is back.
 * [partial] is what had arrived of the answer before the line dropped; it stays on screen until the new answer starts.
 */
data class WaitingQuestion(
    val question: String,
    val partial: String = "",
    /** The history's message ids when it was asked: a new copy of the question after these means the server answered it. */
    val knownIds: Set<String> = emptySet(),
    /** Automatic asks so far; after [AshaViewModel.MaxAsks] it goes back to the draft as before. */
    val tries: Int = 1,
)

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
    /** The menu sheet's page; null when it is closed. */
    val sheet: AshaSheet? = null,
    /** This period's usage, read each time the menu opens. */
    val usage: UsageUi = UsageUi.Loading,
    /** The conversation waiting for its delete to be confirmed (long-pressed in the recents). */
    val confirmDelete: String? = null,
    /** A question waiting for the connection (see [WaitingQuestion]). */
    val waiting: WaitingQuestion? = null,
    /** The reply that arrived before the line dropped, shown while the same question is asked again and nothing new has come. */
    val carried: String = "",
    /** The connection just came back: the offline line says "Back online" for a moment, then goes. */
    val backOnline: Boolean = false,
) {
    /** The open conversation's id: the one chosen, else the latest. */
    val shownConversation: String? get() = currentConversation ?: conversations.firstOrNull()?.id

    /** The open conversation's title, for the header; "New chat" until it has one. */
    val conversationTitle: String? get() = (currentConversation?.let { id -> conversations.firstOrNull { it.id == id } } ?: conversations.firstOrNull())?.title
    val sending get() = pendingQuestion != null
    val canSend get() = online && !sending && waiting == null && voice == VoiceState.Idle && draft.isNotBlank()
    /** Voice needs the server for both directions, so it is offered only online. */
    val canUseVoice get() = online && !sending && voice != VoiceState.Transcribing
}

/** Asha (design F1). Reading the history works offline; asking needs the server, and says so before the person types. */
class AshaViewModel(
    private val observeHistory: ObserveAshaHistoryUseCase,
    private val refreshHistory: RefreshAshaHistoryUseCase,
    private val ask: AskAshaUseCase,
    private val clearHistory: DeleteAshaHistoryUseCase,
    private val connection: ConnectionState,
    private val recorder: AudioRecorder,
    private val player: AudioPlayer,
    private val transcribe: TranscribeVoiceUseCase,
    private val speak: SpeakUseCase,
    /** The conversation list (C1–C4); absent in previews and tests that don't need it. */
    private val chats: com.circaso.feature.assistant.domain.AshaRepository? = null,
    /** This period's usage (A7) for the menu; absent in tests that don't need it. */
    private val usage: GetAshaUsageUseCase? = null,
) : ViewModel() {
    // `online` lives here, set by the same collector that sets `backOnline`, so the two change in one frame.
    private val local = MutableStateFlow(AshaUiState(online = connection.status.value == ConnectionStatus.Online))
    private var job: Job? = null
    private var timer: Job? = null
    private var speaking: Job? = null

    private val conversationsFlow = chats?.let { r -> combine(r.conversations, r.current) { list, cur -> list to cur } } ?: kotlinx.coroutines.flow.flowOf(emptyList<com.circaso.feature.assistant.domain.AshaConversation>() to null)

    val state: StateFlow<AshaUiState> = combine(observeHistory(), local, conversationsFlow) { messages, ui, conv ->
        ui.copy(messages = messages, conversations = conv.first, currentConversation = conv.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AshaUiState())

    private var usageJob: Job? = null

    /** The header's menu button: opens the sheet on its first page (and reads the usage afresh), or closes it. */
    fun onMenu(open: Boolean) {
        local.update { it.copy(sheet = if (open) AshaSheet.Menu else null, confirmDelete = null) }
        if (open) loadUsage()
    }

    /** Moves between the sheet's pages (Menu, Recents, Usage). */
    fun onSheet(page: AshaSheet) {
        local.update { it.copy(sheet = page, confirmDelete = null) }
        if (page == AshaSheet.Usage && local.value.usage !is UsageUi.Loaded) loadUsage()
    }

    /** Back inside the sheet: a sub-page returns to the menu; the menu closes. */
    fun onSheetBack() = local.update { s -> s.copy(sheet = if (s.sheet == AshaSheet.Menu) null else AshaSheet.Menu, confirmDelete = null) }

    fun onRetryUsage() = loadUsage()

    private fun loadUsage() {
        val u = usage ?: return
        if (connection.status.value != ConnectionStatus.Online) {
            local.update { it.copy(usage = if (it.usage is UsageUi.Loaded) it.usage else UsageUi.Offline) }
            return
        }
        if (usageJob?.isActive == true) return
        // A figure already on screen stays while the fresh one loads, so the sheet doesn't flicker.
        local.update { if (it.usage is UsageUi.Loaded) it else it.copy(usage = UsageUi.Loading) }
        usageJob = viewModelScope.launch {
            val next = when (val r = u()) {
                is AppResult.Success -> UsageUi.Loaded(r.value)
                is AppResult.Failure -> UsageUi.from(r.error)
            }
            local.update { it.copy(usage = if (next is UsageUi.Offline && it.usage is UsageUi.Loaded) it.usage else next) }
        }
    }

    /** A brand-new conversation: Asha starts with no context (cheaper, and the reason to start one). */
    fun onNewChat() {
        val r = chats ?: return
        if (!state.value.online || state.value.sending) return
        stopSpeaking()
        releaseWaiting()
        local.update { it.copy(sheet = null, error = null, confirmDelete = null) }
        viewModelScope.launch {
            (r.newConversation() as? AppResult.Failure)?.let { f -> local.update { it.copy(error = message(f.error)) } }
        }
    }

    /** Opens a recent chat. A tap while a delete is being confirmed only cancels that. */
    fun onOpenConversation(id: String) {
        if (local.value.confirmDelete != null) { local.update { it.copy(confirmDelete = null) }; return }
        stopSpeaking()
        releaseWaiting()
        chats?.open(id)
        local.update { it.copy(sheet = null) }
    }

    /** A long-press on a recent chat asks before deleting it (online only: the server deletes it). */
    fun onRequestDelete(id: String) {
        if (!state.value.online) return
        local.update { it.copy(confirmDelete = id) }
    }

    fun onCancelDelete() = local.update { it.copy(confirmDelete = null) }

    fun onConfirmDelete() {
        val id = local.value.confirmDelete ?: return
        val r = chats ?: return
        if (!state.value.online) { local.update { it.copy(confirmDelete = null) }; return }
        local.update { it.copy(confirmDelete = null) }
        viewModelScope.launch { (r.deleteConversation(id) as? AppResult.Failure)?.let { f -> local.update { it.copy(error = message(f.error)) } } }
    }

    private var waitingJob: Job? = null
    private var backOnlineJob: Job? = null

    init {
        viewModelScope.launch { refreshHistory() }
        // The one signal that the connection is back: the app's ConnectionState, never a poll.
        viewModelScope.launch {
            var last = connection.status.value
            connection.status.collect { now ->
                val back = last == ConnectionStatus.Offline && now == ConnectionStatus.Online
                last = now
                local.update { it.copy(online = now == ConnectionStatus.Online, backOnline = if (back) true else it.backOnline && now == ConnectionStatus.Online) }
                if (back) onBackOnline()
            }
        }
    }

    /** Back online while Asha is open: say so briefly, and quietly reload what showed offline. */
    private fun onBackOnline() {
        backOnlineJob?.cancel()
        backOnlineJob = viewModelScope.launch {
            delay(BackOnlineMillis)
            local.update { it.copy(backOnline = false) }
        }
        // The waiting question refreshes the history itself before it asks again.
        if (local.value.waiting == null) viewModelScope.launch { refreshHistory() }
        if (local.value.usage is UsageUi.Offline || local.value.usage is UsageUi.Failed) loadUsage()
    }

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
        local.update { it.copy(draft = "") }
        askNow(question, carried = "", tries = 1)
    }

    /**
     * Asks [question] and streams the answer. A failure caused only by the connection keeps the question in its bubble
     * as waiting ([WaitingQuestion]); any other failure gives it back to the draft with the error, as before.
     */
    private fun askNow(question: String, carried: String, tries: Int) {
        val knownIds = state.value.messages.mapTo(HashSet()) { it.messageId }
        local.update { it.copy(pendingQuestion = question, streaming = "", carried = carried, status = null, error = null, waiting = null) }
        job = viewModelScope.launch {
            var failed: AppError? = null
            var done = false
            ask(question).collect { event ->
                when (event) {
                    is AshaEvent.Delta -> local.update { it.copy(streaming = it.streaming + event.text, carried = "", status = null) }
                    is AshaEvent.UsingTool -> local.update { it.copy(status = "Reading your chats…") }
                    is AshaEvent.Status -> local.update { it.copy(status = event.label) }
                    is AshaEvent.Done -> done = true
                    is AshaEvent.Failed -> failed = event.error
                    is AshaEvent.ToolRequest -> Unit
                }
            }
            // The stream ended with neither an answer nor an error: the line was cut mid-answer.
            if (!done && failed == null) failed = AppError.Network
            val error = failed
            if (error != null && isConnectionDrop(error) && tries < MaxAsks) {
                local.update {
                    it.copy(
                        pendingQuestion = null, streaming = "", status = null, carried = "",
                        waiting = WaitingQuestion(question, partial = it.streaming.ifEmpty { it.carried }, knownIds = knownIds, tries = tries),
                    )
                }
                awaitConnection()
                return@launch
            }
            local.update {
                it.copy(
                    pendingQuestion = null, streaming = "", status = null, carried = "",
                    // Nothing was answered: give the question back so it is not lost.
                    draft = if (error != null) question else it.draft,
                    error = error?.let { e -> message(e) },
                )
            }
        }
    }

    /**
     * Asks the waiting question again once the connection is back. It first lets the app's connection state settle (a
     * timeout on a live line may not have flipped it to offline yet), then waits for Online, then asks: once.
     */
    private fun awaitConnection() {
        waitingJob?.cancel()
        waitingJob = viewModelScope.launch {
            delay(SettleMillis)
            connection.status.first { it == ConnectionStatus.Online }
            askWaiting()
        }
    }

    /** Moving to another conversation: a waiting question would land there, so it goes back to the draft instead. */
    private fun releaseWaiting() {
        val w = local.value.waiting ?: return
        waitingJob?.cancel()
        local.update { it.copy(waiting = null, draft = listOf(it.draft.trim(), w.question).filter(String::isNotEmpty).joinToString(" ")) }
    }

    private suspend fun askWaiting() {
        val w = local.value.waiting ?: return
        if (local.value.sending) return
        // If the answer was saved before the line dropped, the history now holds the question again: don't ask twice.
        refreshHistory()
        val answered = observeHistory().first().any { it.isFromUser && it.messageId !in w.knownIds && it.text.trim() == w.question }
        if (local.value.waiting != w) return
        if (answered) { local.update { it.copy(waiting = null) }; return }
        askNow(w.question, carried = w.partial, tries = w.tries + 1)
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

    companion object {
        /** How long "Back online" shows (design G4: about 3 s). */
        const val BackOnlineMillis = 3_000L
        /** Time for the app's connection state to notice a drop before a waiting question checks it. */
        const val SettleMillis = 3_000L
        /** Asks of one question in all (the person's, then automatic ones) before it goes back to the draft. */
        const val MaxAsks = 3
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        timer?.cancel()
        player.stop()
        // The scope is already cancelled here, and the microphone must be released.
        if (local.value.voice == VoiceState.Recording) GlobalScope.launch { recorder.cancel() }
    }

    /** Only a lost connection waits for the network; quota, auth and the server's refusals are told as before. */
    private fun isConnectionDrop(error: AppError) = error is AppError.Network

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
