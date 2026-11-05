package com.circaso.feature.assistant.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.Diagnostics
import com.circaso.core.platform.LiveMicStart
import com.circaso.core.platform.LiveMicrophone
import com.circaso.core.platform.LiveSpeaker
import com.circaso.core.platform.Mulaw
import com.circaso.core.sync.nowMillis
import com.circaso.feature.assistant.domain.LiveVoiceEvent
import com.circaso.feature.assistant.domain.LiveVoiceSession
import com.circaso.feature.assistant.domain.LiveVoiceStart
import com.circaso.feature.assistant.domain.RefreshAshaHistoryUseCase
import com.circaso.feature.assistant.domain.TalkToAshaUseCase
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where the conversation is, as the animation and the status line show it. */
enum class VoicePhase {
    /** Opening the socket and the microphone. */
    Connecting,
    /** The microphone is open and Asha is waiting for the person to speak. */
    Listening,
    /** The person has finished a question and Asha has not started answering yet. */
    Thinking,
    /** Asha's voice is playing. */
    Speaking,
    /** The conversation is over: the person hung up, or the server closed it (see [VoiceUiState.ending]). */
    Ended,
}

/** How a finished conversation ended, which decides what the screen offers. */
sealed interface VoiceEnding {
    /** The person hung up, or the server said `ended`. */
    data object HungUp : VoiceEnding
    /** Thirty seconds with nobody speaking: Asha hung up; one tap starts again. */
    data object Quiet : VoiceEnding
    /** No connection, or the socket dropped. */
    data object Offline : VoiceEnding
    /** `assistant.quota_exceeded`: the voice allowance is used up. [resetsInSeconds] when the server said. */
    data class OutOfVoice(val resetsInSeconds: Long?) : VoiceEnding
    /** Live voice is not possible here (no Opus on this phone, or the language is not spoken live): voice clips work instead. */
    data object UseClips : VoiceEnding
    /** The microphone permission was refused. */
    data object NoMicrophone : VoiceEnding
    /** `assistant.model_unavailable`, or the microphone failed: try again, or type. */
    data class Failed(val message: String) : VoiceEnding
}

/**
 * One exchange in voice mode's transcript: the question Asha took (the person's bubble) and her answer as its sentences
 * arrive (hers). [done] once the answer is saved; [interrupted] when the person talked over her.
 */
data class VoiceTurn(
    val question: String,
    val reply: List<String> = emptyList(),
    val done: Boolean = false,
    val interrupted: Boolean = false,
) {
    val replyText get() = reply.joinToString(" ")
}

/** What the animated logo shows: the phase, with muted told apart from listening and a dropped line or failure (Error) from a plain end. */
enum class VoiceVisual { Connecting, Listening, Thinking, Speaking, Muted, Error, Ended }

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.Connecting,
    val ending: VoiceEnding? = null,
    /** What the person is saying, as the server hears it (grey until `final`). */
    val heard: String = "",
    val heardFinal: Boolean = false,
    /** This call's transcript, oldest first: every question and answer since the screen opened. */
    val turns: List<VoiceTurn> = emptyList(),
    /** "Reading your chats…" while the phone runs a tool. */
    val status: String? = null,
    val muted: Boolean = false,
    /** Loudness 0..1 of the person (while listening) or of Asha (while speaking), for the animation. */
    val micLevel: Float = 0f,
    val voiceLevel: Float = 0f,
    val voiceSecondsLeft: Long? = null,
    /** A passing problem (one answer failed); the conversation goes on. */
    val notice: String? = null,
    /** Debug builds only: the last turn's delays ("model 420 · voice 610 · sent 780 · heard 830 ms"). */
    val timing: String? = null,
) {
    val live get() = phase != VoicePhase.Connecting && phase != VoicePhase.Ended

    /** The question Asha is on (the latest turn's). */
    val question: String? get() = turns.lastOrNull()?.question
    /** The latest answer's sentences so far. */
    val reply: List<String> get() = turns.lastOrNull()?.reply.orEmpty()

    val visual: VoiceVisual
        get() = when (phase) {
            VoicePhase.Connecting -> VoiceVisual.Connecting
            VoicePhase.Listening -> if (muted) VoiceVisual.Muted else VoiceVisual.Listening
            VoicePhase.Thinking -> VoiceVisual.Thinking
            VoicePhase.Speaking -> VoiceVisual.Speaking
            VoicePhase.Ended -> when (ending) {
                VoiceEnding.Offline, is VoiceEnding.Failed, VoiceEnding.NoMicrophone -> VoiceVisual.Error
                else -> VoiceVisual.Ended
            }
        }

    /** The loudness the waves follow: the person's while listening, Asha's while she speaks, else none. */
    val waveLevel: Float get() = when (visual) {
        VoiceVisual.Speaking -> voiceLevel
        VoiceVisual.Listening -> micLevel
        else -> 0f
    }
}

/**
 * The voice screen's conversation (guide A11): one socket and the microphone open for as long as the screen is up.
 * The microphone streams the whole time, even while Asha speaks, which is how talking over her works; `interrupted`
 * from the server stops her voice at once. The transcript, turn and reply frames build a chat-style transcript; the saved
 * messages reach the Asha chat through the normal history refresh.
 */
class VoiceViewModel(
    private val talk: TalkToAshaUseCase,
    private val refreshHistory: RefreshAshaHistoryUseCase,
    private val microphone: LiveMicrophone,
    private val speaker: LiveSpeaker,
    private val client: com.circaso.core.platform.ClientInfo? = null,
) : ViewModel() {
    /** When this phone got the turn, and how long until its first sound played; for the debug timing line. */
    private var turnAt = 0L
    private var heardAfterMs: Long? = null
    private var serverTiming: LiveVoiceEvent.Timing? = null

    private val local = MutableStateFlow(VoiceUiState())
    private var session: LiveVoiceSession? = null
    private var conversation: Job? = null
    private var ended = false

    // The server's `reply_done` ends the speaking phase, not a gap between sentences, so the phase is never read off the player.
    val state: StateFlow<VoiceUiState> = combine(local, microphone.level, speaker.level) { ui, mic, voice ->
        ui.copy(micLevel = if (ui.muted || ui.phase != VoicePhase.Listening) 0f else mic, voiceLevel = if (ui.phase == VoicePhase.Speaking) voice else 0f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceUiState())

    init {
        start()
    }

    /** Starts (or, after a quiet hang-up, restarts) the conversation. */
    fun start() {
        if (conversation?.isActive == true) return
        ended = false
        local.value = VoiceUiState()
        conversation = viewModelScope.launch { converse() }
    }

    private suspend fun converse() {
        if (!microphone.supported) return finish(VoiceEnding.UseClips)
        val opened = when (val start = talk()) {
            is LiveVoiceStart.Opened -> start.session
            LiveVoiceStart.Offline -> return finish(VoiceEnding.Offline)
            LiveVoiceStart.SignedOut -> return finish(VoiceEnding.Offline)
        }
        session = opened
        try {
            opened.events.collect { event -> handle(event, opened) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Diagnostics.note("voice: conversation failed (${e::class.simpleName})")
        } finally {
            session = null
            microphone.stop()
            speaker.stop()
            if (!ended) finishNow(VoiceEnding.Offline)
        }
    }

    private suspend fun handle(event: LiveVoiceEvent, s: LiveVoiceSession) {
        when (event) {
            is LiveVoiceEvent.Ready -> onReady(event, s)
            is LiveVoiceEvent.Audio -> if (local.value.phase != VoicePhase.Ended) {
                speaker.play(if (encoding == "linear16") pcm16(event.bytes) else Mulaw.decode(event.bytes))
                if (heardAfterMs == null && turnAt > 0) { heardAfterMs = nowMillis() - turnAt; showTiming() }
                local.update { if (it.phase == VoicePhase.Thinking || it.phase == VoicePhase.Listening) it.copy(phase = VoicePhase.Speaking) else it }
            }
            is LiveVoiceEvent.Transcript -> local.update { it.copy(heard = event.text, heardFinal = event.final, notice = null) }
            is LiveVoiceEvent.Turn -> Diagnostics.note("voice: turn").also {
                turnAt = nowMillis(); heardAfterMs = null; serverTiming = null
                local.update { it.copy(phase = VoicePhase.Thinking, turns = VoiceTranscript.question(it.turns, event.text), heard = "", heardFinal = false, status = null) } }
            is LiveVoiceEvent.Reply -> local.update { it.copy(phase = VoicePhase.Speaking, turns = VoiceTranscript.sentence(it.turns, event.text), status = null) }
            is LiveVoiceEvent.UsingTool -> local.update { it.copy(status = "Reading your chats…") }
            is LiveVoiceEvent.Status -> local.update { it.copy(status = event.label) }
            is LiveVoiceEvent.Interrupted -> { Diagnostics.note("voice: interrupted"); speaker.flush(); local.update { it.copy(phase = VoicePhase.Listening, turns = VoiceTranscript.interrupted(it.turns)) } }
            is LiveVoiceEvent.ReplyDone -> {
                Diagnostics.note("voice: reply done, spoken=${event.spoken}")
                local.update { it.copy(phase = VoicePhase.Listening, status = null, turns = VoiceTranscript.done(it.turns, event.message.text, event.spoken)) }
                viewModelScope.launch { refreshHistory() }
            }
            is LiveVoiceEvent.Error -> Diagnostics.note("voice: error ${event.code}").also { local.update { it.copy(phase = VoicePhase.Listening, status = null, notice = noticeFor(event.code), turns = VoiceTranscript.failed(it.turns)) } }
            is LiveVoiceEvent.Closing -> { Diagnostics.note("voice: closing ${event.reason}"); finish(endingFor(event)) }
            is LiveVoiceEvent.Closed -> if (!ended) finish(closedEnding(event))
            is LiveVoiceEvent.ToolRequest -> Unit // answered inside the use case
            is LiveVoiceEvent.Timing -> {
                Diagnostics.note("voice: timing model=${event.modelFirstTextMs} sentence=${event.firstSentenceMs} audio=${event.firstAudioMs}")
                serverTiming = event
                showTiming()
            }
        }
    }

    /** What the server announced in `ready`; mulaw unless configured otherwise. */
    private var encoding = "mulaw"

    private suspend fun onReady(ready: LiveVoiceEvent.Ready, s: LiveVoiceSession) {
        Diagnostics.note("voice: ready ${ready.encoding} ${ready.sampleRate} Hz, ${ready.voiceSecondsLeft} s left")
        encoding = ready.encoding
        speaker.start(ready.sampleRate)
        when (microphone.start()) {
            LiveMicStart.Started -> Unit
            LiveMicStart.PermissionDenied -> { s.end(); return finish(VoiceEnding.NoMicrophone) }
            LiveMicStart.Unsupported -> { s.end(); return finish(VoiceEnding.UseClips) }
            LiveMicStart.Failed -> { s.end(); return finish(VoiceEnding.Failed("Couldn't start the microphone. Try again.")) }
        }
        microphone.muted = local.value.muted
        local.update { it.copy(phase = VoicePhase.Listening, voiceSecondsLeft = ready.voiceSecondsLeft) }
        streaming?.cancel()
        streaming = viewModelScope.launch {
            // While her voice plays, the loudspeaker leaks into the microphone. The echo canceller takes most of it;
            // what is left stays on the phone unless the person is clearly louder than it (then it is their barge-in),
            // and once they are heard the stream stays open for a moment so their words are not chopped.
            var openUntil = 0L
            microphone.packets.collect { packet ->
                if (speaker.playing.value) {
                    val now = nowMillis()
                    if (microphone.level.value >= BargeInLevel) openUntil = now + BargeInHoldMillis
                    if (now > openUntil) return@collect
                }
                s.sendAudio(packet)
            }
        }
    }

    /** The microphone's packets on their way to the socket; one per conversation. */
    private var streaming: Job? = null

    fun onMute() {
        val muted = !local.value.muted
        microphone.muted = muted
        local.update { it.copy(muted = muted) }
    }

    /** Hangs up. The screen leaves at once; the `end` frame is best effort. */
    fun onEnd() {
        if (ended) return
        val s = session
        finishNow(VoiceEnding.HungUp)
        viewModelScope.launch {
            withTimeoutOrNull(1_500) { s?.end() }
            s?.close()
            conversation?.cancel()
        }
    }

    fun onNoticeShown() = local.update { it.copy(notice = null) }

    private suspend fun finish(ending: VoiceEnding) {
        finishNow(ending)
        session?.close()
    }

    private fun finishNow(ending: VoiceEnding) {
        if (ended) return
        ended = true
        streaming?.cancel()
        microphone.stop()
        speaker.stop()
        local.update { it.copy(phase = VoicePhase.Ended, ending = ending, status = null) }
    }

    private fun endingFor(c: LiveVoiceEvent.Closing): VoiceEnding = when (c.reason) {
        "ended" -> VoiceEnding.HungUp
        "silence" -> VoiceEnding.Quiet
        "assistant.quota_exceeded" -> VoiceEnding.OutOfVoice(c.retryAfterSeconds)
        "assistant.language_not_supported" -> VoiceEnding.UseClips
        "assistant.model_unavailable" -> VoiceEnding.Failed("Asha's voice isn't available right now. Try again in a moment, or type.")
        else -> VoiceEnding.Failed(c.message ?: "The conversation ended.")
    }

    private fun closedEnding(c: LiveVoiceEvent.Closed): VoiceEnding {
        Diagnostics.note("voice: closed ${c.code} ${c.reason ?: ""}")
        return when (c.reason) {
            "unauthorized", "auth required" -> VoiceEnding.Failed("Please sign in again to talk to Asha.")
            else -> VoiceEnding.Offline
        }
    }

    private fun noticeFor(code: String) = when (code) {
        "assistant.model_unavailable" -> "Asha couldn't answer that one. Ask again."
        "assistant.turn_not_found" -> "That took too long. Ask again."
        else -> "Something went wrong with that answer. Ask again."
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        val s = session
        microphone.stop()
        speaker.stop()
        // The scope is already cancelled: say goodbye off it, so the server frees the line at once.
        if (s != null && !ended) GlobalScope.launch { runCatching { withTimeoutOrNull(1_000) { s.end() } }; runCatching { s.close() } }
    }

    private companion object {
        /** A voice at the phone sits at 0.5 and above on the level scale; echo after cancellation stays well under. */
        const val BargeInLevel = 0.5f
        const val BargeInHoldMillis = 600L

        /** 16-bit little-endian samples, for a server configured to send `linear16` instead of mulaw. */
        fun pcm16(bytes: ByteArray): ShortArray = ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
    }

    /** The debug line: the server's steps, then when the first sound played here (the target is under 800 ms). */
    private fun showTiming() {
        if (client?.debugBuild != true) return
        val server = serverTiming
        val parts = listOfNotNull(
            server?.modelFirstTextMs?.let { "model $it" },
            server?.firstSentenceMs?.let { "voice $it" },
            server?.firstAudioMs?.let { "sent $it" },
            heardAfterMs?.let { "heard $it" },
        )
        local.update { it.copy(timing = if (parts.isEmpty()) null else parts.joinToString(" · ") + " ms") }
    }
}

/**
 * Builds voice mode's transcript from the socket's frames. Pure, so the order rules are tested without a socket:
 * `turn` opens an exchange with the person's question, each `reply` adds a sentence to the latest one, `interrupted`
 * marks it talked over, `reply_done` closes it (with the saved text when no sentence arrived), and an `error` closes
 * it as it stands. The transcript keeps the latest [MaxTurns] exchanges.
 */
object VoiceTranscript {
    const val MaxTurns = 40

    fun question(turns: List<VoiceTurn>, text: String): List<VoiceTurn> = (turns + VoiceTurn(text.trim())).takeLast(MaxTurns)

    fun sentence(turns: List<VoiceTurn>, text: String): List<VoiceTurn> {
        val last = turns.lastOrNull()
        // A sentence with no open exchange (the server skipped `turn`) still shows, as an answer on its own.
        if (last == null || last.done) return (turns + VoiceTurn("", listOf(text))).takeLast(MaxTurns)
        return turns.dropLast(1) + last.copy(reply = last.reply + text)
    }

    fun interrupted(turns: List<VoiceTurn>): List<VoiceTurn> = updateOpen(turns) { it.copy(interrupted = it.reply.isNotEmpty()) }

    fun done(turns: List<VoiceTurn>, savedText: String, spoken: Boolean): List<VoiceTurn> {
        val last = turns.lastOrNull()
        if (last == null || last.done) return if (savedText.isBlank()) turns else (turns + VoiceTurn("", listOf(savedText), done = true)).takeLast(MaxTurns)
        return turns.dropLast(1) + last.copy(
            reply = last.reply.ifEmpty { listOfNotNull(savedText.takeIf { it.isNotBlank() }) },
            done = true,
            interrupted = last.interrupted || !spoken,
        )
    }

    fun failed(turns: List<VoiceTurn>): List<VoiceTurn> = updateOpen(turns) { it.copy(done = true) }

    private fun updateOpen(turns: List<VoiceTurn>, change: (VoiceTurn) -> VoiceTurn): List<VoiceTurn> {
        val last = turns.lastOrNull() ?: return turns
        return if (last.done) turns else turns.dropLast(1) + change(last)
    }
}
