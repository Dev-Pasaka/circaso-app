package com.circaso.feature.assistant.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.Diagnostics
import com.circaso.core.platform.LiveMicStart
import com.circaso.core.platform.LiveMicrophone
import com.circaso.core.platform.LiveSpeaker
import com.circaso.core.platform.Mulaw
import com.circaso.core.sync.nowMillis
import com.circaso.feature.assistant.domain.AshaActivity
import com.circaso.feature.assistant.domain.AshaAvatarState
import com.circaso.feature.assistant.domain.AshaLook
import com.circaso.feature.assistant.domain.AshaLookSource
import com.circaso.feature.assistant.domain.AshaMascotContext
import com.circaso.feature.assistant.domain.AshaMascotRules
import com.circaso.feature.assistant.domain.AshaMascotTags
import com.circaso.feature.assistant.domain.AshaReaction
import com.circaso.feature.assistant.domain.LiveVoiceEvent
import com.circaso.feature.assistant.domain.LiveVoiceSession
import com.circaso.feature.assistant.domain.LiveVoiceStart
import com.circaso.feature.assistant.domain.RefreshAshaHistoryUseCase
import com.circaso.feature.assistant.domain.TalkToAshaUseCase
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.Connecting,
    val ending: VoiceEnding? = null,
    /** What the person is saying, as the server hears it (grey until `final`). */
    val heard: String = "",
    val heardFinal: Boolean = false,
    /** The question Asha took, once a turn starts. */
    val question: String? = null,
    /** The sentences of the answer so far. */
    val reply: List<String> = emptyList(),
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
    /** What the server's tags have said so far (scene, mood, activity). */
    val mascotTags: AshaMascotTags = AshaMascotTags(),
    val look: AshaLook = AshaLook.Asha,
    /** Debug builds only: the showcase tour's current pose (long-press the stage), shown instead of the live one. */
    val showcase: AshaAvatarState? = null,
) {
    val live get() = phase != VoicePhase.Connecting && phase != VoicePhase.Ended

    /**
     * The mascot on the stage: listening while the person talks, the server's activity (or thinking) while she works,
     * speaking with her voice's loudness while she talks, a small wave at the end. A dropped connection is the
     * offline pose.
     */
    val mascot: AshaAvatarState
        get() = showcase ?: AshaMascotRules.resolve(
            look = look,
            tags = mascotTags,
            context = AshaMascotContext(online = ending != VoiceEnding.Offline, lateNight = AshaMascotRules.isLateNight(com.circaso.core.platform.localHour(kotlin.time.Clock.System.now().toEpochMilliseconds()))),
            appActivity = when (phase) {
                VoicePhase.Connecting -> AshaActivity.Idle
                VoicePhase.Listening -> AshaActivity.Listening
                VoicePhase.Thinking -> AshaActivity.Thinking
                VoicePhase.Speaking -> AshaActivity.Speaking
                VoicePhase.Ended -> AshaActivity.Done
            },
            voiceLevel = voiceLevel,
        )
}

/**
 * The voice screen's conversation (guide A11): one socket and the microphone open for as long as the screen is up.
 * The microphone streams the whole time, even while Asha speaks, which is how talking over her works; `interrupted`
 * from the server stops her voice at once. The transcript, turn and reply frames are shown as captions; the saved
 * messages reach the Asha chat through the normal history refresh.
 */
class VoiceViewModel(
    private val talk: TalkToAshaUseCase,
    private val refreshHistory: RefreshAshaHistoryUseCase,
    private val microphone: LiveMicrophone,
    private val speaker: LiveSpeaker,
    private val client: com.circaso.core.platform.ClientInfo? = null,
    /** The mascot's look from the chosen voice; absent in previews and tests, which show Asha. */
    private val look: AshaLookSource? = null,
) : ViewModel() {
    private val _reactions = MutableSharedFlow<AshaReaction>(extraBufferCapacity = 4)
    /** One-shot mascot reactions the server asked for. */
    val reactions: Flow<AshaReaction> = _reactions

    /** When this phone got the turn, and how long until its first sound played; for the debug timing line. */
    private var turnAt = 0L
    private var heardAfterMs: Long? = null
    private var serverTiming: LiveVoiceEvent.Timing? = null

    private val local = MutableStateFlow(VoiceUiState())
    private var session: LiveVoiceSession? = null
    private var conversation: Job? = null
    private var ended = false

    // The server's `reply_done` ends the speaking phase, not a gap between sentences, so the phase is never read off the player.
    val state: StateFlow<VoiceUiState> = combine(local, microphone.level, speaker.level, look?.look ?: flowOf(AshaLook.Asha)) { ui, mic, voice, shownLook ->
        ui.copy(micLevel = if (ui.muted || ui.phase != VoicePhase.Listening) 0f else mic, voiceLevel = if (ui.phase == VoicePhase.Speaking) voice else 0f, look = shownLook)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceUiState())

    init {
        start()
        look?.let { l -> viewModelScope.launch { l.refresh() } }
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
                local.update { it.copy(phase = VoicePhase.Thinking, question = event.text, heard = "", heardFinal = false, reply = emptyList(), status = null, mascotTags = it.mascotTags.turnDone()) } }
            is LiveVoiceEvent.Reply -> local.update { it.copy(phase = VoicePhase.Speaking, reply = it.reply + event.text, status = null) }
            is LiveVoiceEvent.UsingTool -> local.update { it.copy(status = "Reading your chats…") }
            is LiveVoiceEvent.Status -> {
                local.update { it.copy(status = event.label, mascotTags = it.mascotTags.merge(event.tags)) }
                event.tags?.reaction?.let { r -> if (AshaMascotRules.allowReaction(r, AshaMascotContext(online = true))) _reactions.tryEmit(r) }
            }
            is LiveVoiceEvent.Interrupted -> { Diagnostics.note("voice: interrupted"); speaker.flush(); local.update { it.copy(phase = VoicePhase.Listening, mascotTags = it.mascotTags.turnDone()) } }
            is LiveVoiceEvent.ReplyDone -> {
                Diagnostics.note("voice: reply done, spoken=${event.spoken}")
                local.update { it.copy(phase = VoicePhase.Listening, status = null, reply = if (it.reply.isEmpty()) listOf(event.message.text) else it.reply, mascotTags = it.mascotTags.turnDone()) }
                viewModelScope.launch { refreshHistory() }
            }
            is LiveVoiceEvent.Error -> Diagnostics.note("voice: error ${event.code}").also { local.update { it.copy(phase = VoicePhase.Listening, status = null, notice = noticeFor(event.code), mascotTags = it.mascotTags.turnDone()) } }
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
    private var showcaseJob: Job? = null

    /** Debug builds only: long-press the stage to tour every look, scene, mood and activity; again to stop. */
    fun onToggleShowcase() {
        if (client?.debugBuild != true) return
        showcaseJob?.let { it.cancel(); showcaseJob = null; local.update { s -> s.copy(showcase = null) }; return }
        showcaseJob = viewModelScope.launch {
            while (true) {
                for (step in com.circaso.feature.assistant.domain.AshaShowcase.steps) {
                    local.update { it.copy(showcase = step.state) }
                    kotlinx.coroutines.delay(600)
                    step.reaction?.let { _reactions.tryEmit(it) }
                    kotlinx.coroutines.delay(3400)
                }
            }
        }
    }

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
