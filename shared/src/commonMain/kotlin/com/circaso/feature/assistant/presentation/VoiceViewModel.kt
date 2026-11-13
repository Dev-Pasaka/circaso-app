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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.MonoClock
import com.circaso.feature.assistant.voice.BargeInDetector
import com.circaso.feature.assistant.voice.SileroVad
import com.circaso.feature.assistant.voice.SpeechOnset
import com.circaso.feature.assistant.voice.VadLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlin.concurrent.Volatile

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
    /**
     * The line dropped mid-call (not the person's doing): the microphone and her voice are stopped, the transcript
     * stays, and the call reopens in the same conversation as soon as the connection is back.
     */
    Reconnecting,
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
    /**
     * `assistant.unavailable`: Asha is down for maintenance (2026-11-07), said by her or already known when the call was
     * asked for. [toldWhenBack] when the server will push "Asha is back" to this person. Turns into [AshaBack].
     */
    data class Maintenance(val toldWhenBack: Boolean = true) : VoiceEnding
    /** She is back from maintenance while this screen was open: one tap talks again. */
    data object AshaBack : VoiceEnding
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
    /** The line dropped before the answer was finished: what arrived is shown as cut off, never as a complete answer. */
    val cutOff: Boolean = false,
) {
    val replyText get() = reply.joinToString(" ")
}

/** What the animated logo shows: the phase, with muted told apart from listening and a dropped line or failure (Error) from a plain end. */
enum class VoiceVisual { Connecting, Listening, Thinking, Speaking, Muted, Reconnecting, Error, Ended }

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
    /**
     * The person started talking over her and her voice is ducked while the server confirms it: the screen shows
     * listening at once, before `interrupted` (or, if it was only noise, she comes back up and carries on).
     */
    val ducked: Boolean = false,
) {
    val live get() = phase != VoicePhase.Connecting && phase != VoicePhase.Ended && phase != VoicePhase.Reconnecting

    /** The question Asha is on (the latest turn's). */
    val question: String? get() = turns.lastOrNull()?.question
    /** The latest answer's sentences so far. */
    val reply: List<String> get() = turns.lastOrNull()?.reply.orEmpty()

    val visual: VoiceVisual
        get() = when (phase) {
            VoicePhase.Connecting -> VoiceVisual.Connecting
            VoicePhase.Listening -> if (muted) VoiceVisual.Muted else VoiceVisual.Listening
            VoicePhase.Thinking -> VoiceVisual.Thinking
            VoicePhase.Speaking -> if (ducked && !muted) VoiceVisual.Listening else VoiceVisual.Speaking
            VoicePhase.Reconnecting -> VoiceVisual.Reconnecting
            VoicePhase.Ended -> when (ending) {
                VoiceEnding.Offline, is VoiceEnding.Failed, VoiceEnding.NoMicrophone -> VoiceVisual.Error
                else -> VoiceVisual.Ended
            }
        }

    /** The state a screen reader announces: [visual] without the short-lived duck, so a cough isn't read out. */
    val announcedVisual: VoiceVisual get() = if (ducked) copy(ducked = false).visual else visual

    /** The loudness the waves follow: the person's while listening, Asha's while she speaks, else none. */
    val waveLevel: Float get() = when (visual) {
        VoiceVisual.Speaking -> voiceLevel
        VoiceVisual.Listening -> micLevel
        else -> 0f
    }
}

/**
 * The voice screen's conversation (guide A11): one socket and the microphone open for as long as the screen is up.
 * The microphone streams the whole time, even while Asha speaks, which is how talking over her works.
 *
 * **Talking over her is the phone's call** (decision 2026-11-07, like a phone call or Claude's voice mode): Silero VAD
 * runs on the echo-cancelled microphone off the main thread ([BargeInDetector]; on the loudspeaker with an echo guard
 * against her own voice, on headphones with a lower bar). The first sure window of the person's voice ducks her (the
 * feel); about 200 ms of it stops her at once (flushed, not ducked), the screen listens, and an `interrupt` frame tells
 * the server, which cancels her answer and answers `interrupted`; her audio and sentences arriving in between are the
 * old reply's and are dropped. Without the detector (the model or ONNX Runtime unavailable) the energy detector
 * ([VoiceActivity]) only ducks her, and the server's word rule decides.
 *
 * The transcript, turn and reply frames build a chat-style transcript; the saved messages reach the Asha chat through
 * the normal history refresh.
 */
class VoiceViewModel(
    private val talk: TalkToAshaUseCase,
    private val refreshHistory: RefreshAshaHistoryUseCase,
    private val microphone: LiveMicrophone,
    private val speaker: LiveSpeaker,
    private val client: com.circaso.core.platform.ClientInfo? = null,
    /**
     * The app's one answer to "are we online?" ([com.circaso.core.sync.ConnectionState.status]): a dropped call waits
     * on it before reopening. Null (previews) reopens on the backoff alone.
     */
    private val connection: Flow<ConnectionStatus>? = null,
    /** Milliseconds, for the microphone gate; a test clock in tests. */
    private val clock: () -> Long = { nowMillis() },
    /** Opens Silero VAD for the phone's own barge-in; null (previews, a platform without it) keeps the energy detector. */
    private val vadLoader: VadLoader? = null,
    /** Where the detector runs: one thread, never the main one. The test dispatcher in tests. */
    private val vadDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    /** The clock the microphone's frames and the speaker's blocks are stamped on, to time a barge-in. */
    private val monoClock: () -> Long = { MonoClock.now() },
    /** Whether Asha is down for maintenance (A14); absent in previews and tests that don't need it. */
    private val availability: com.circaso.feature.assistant.domain.AshaAvailability? = null,
) : ViewModel() {
    private val availabilityWatch = com.circaso.feature.assistant.domain.AvailabilityWatch(availability)

    /** When this phone got the turn, and how long until its first sound played; for the debug timing line. */
    private var turnAt = 0L
    private var heardAfterMs: Long? = null
    private var serverTiming: LiveVoiceEvent.Timing? = null

    private val local = MutableStateFlow(VoiceUiState())
    private var session: LiveVoiceSession? = null
    private var conversation: Job? = null
    /** Read on the detector's thread too. */
    @Volatile private var ended = false

    /** This call got as far as `ready` at least once: only a call that was live is worth reconnecting. */
    private var everLive = false
    /** Reopen attempts that failed since the line dropped (reset when a reopened call is live again). */
    private var reopenFailures = 0
    /** Ends the call as Offline when reconnecting takes longer than [ReconnectWindowMillis]. */
    private var giveUp: Job? = null
    /** The Asha conversation this call's turns went to (from the saved answers), so a reopened call carries on there. */
    private var conversationId: String? = null

    // The server's `reply_done` ends the speaking phase, not a gap between sentences, so the phase is never read off the player.
    val state: StateFlow<VoiceUiState> = combine(local, microphone.level, speaker.level) { ui, mic, voice ->
        ui.copy(micLevel = if (ui.visual == VoiceVisual.Listening) mic else 0f, voiceLevel = if (ui.visual == VoiceVisual.Speaking) voice else 0f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceUiState())

    /** Starts (or, after a quiet hang-up, restarts) the conversation. */
    fun start() {
        if (conversation?.isActive == true) return
        ended = false
        everLive = false
        reopenFailures = 0
        local.value = VoiceUiState()
        // Already known to be down: no microphone, no call that can only apologise. The same calm ending instead.
        val a = availability
        if (a != null && a.isDown.value) {
            finishNow(VoiceEnding.Maintenance(a.willTellWhenBack))
            awaitBack(a)
            return
        }
        conversation = viewModelScope.launch { converse() }
    }

    private suspend fun converse() {
        if (!microphone.supported) return finish(VoiceEnding.UseClips)
        while (true) {
            val reconnecting = local.value.phase == VoicePhase.Reconnecting
            if (reconnecting) {
                // Nothing is tried while the app knows it is offline; the moment it is back, a short pause, then reopen.
                connection?.first { it == ConnectionStatus.Online }
                delay(ReopenBackoffMillis[reopenFailures.coerceAtMost(ReopenBackoffMillis.lastIndex)])
                if (ended) return
            }
            val opened = when (val start = talk(conversationId = conversationId)) {
                is LiveVoiceStart.Opened -> start.session
                LiveVoiceStart.Offline -> if (!reconnecting) return finish(VoiceEnding.Offline) else {
                    if (++reopenFailures >= MaxReopenFailures) return finish(VoiceEnding.Offline)
                    continue
                }
                // No session on the phone any more: reconnecting can't fix that.
                LiveVoiceStart.SignedOut -> return finish(if (reconnecting) VoiceEnding.Failed("Please sign in again to talk to Asha.") else VoiceEnding.Offline)
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
                streaming?.cancel()
                detecting?.cancel()
                microphone.stop()
                speaker.stop()
            }
            if (ended) return
            // The line dropped without anyone hanging up.
            when {
                // It never got going: nothing to carry on, so end as before.
                !everLive -> return finishNow(VoiceEnding.Offline)
                // A reopened call dropped again before it was live: one more failed attempt.
                local.value.phase == VoicePhase.Reconnecting -> if (++reopenFailures >= MaxReopenFailures) return finishNow(VoiceEnding.Offline)
                else -> startReconnecting()
            }
        }
    }

    /** The call stays on screen, quiet: microphone and voice stopped, the cut-off answer closed, until the line is back. */
    private fun startReconnecting() {
        Diagnostics.note("voice: line dropped, reconnecting")
        reopenFailures = 0
        local.update { it.copy(phase = VoicePhase.Reconnecting, heard = "", heardFinal = false, status = null, turns = VoiceTranscript.cutOff(it.turns)) }
        giveUp?.cancel()
        giveUp = viewModelScope.launch {
            delay(ReconnectWindowMillis)
            if (local.value.phase != VoicePhase.Reconnecting) return@launch
            Diagnostics.note("voice: still offline, giving up")
            val s = session
            finishNow(VoiceEnding.Offline)
            conversation?.cancel()
            s?.close()
        }
    }

    private suspend fun handle(event: LiveVoiceEvent, s: LiveVoiceSession) {
        when (event) {
            is LiveVoiceEvent.Ready -> onReady(event, s)
            // after the phone's own barge-in, her audio and sentences are the old reply's until the server says so
            is LiveVoiceEvent.Audio -> if (local.value.phase != VoicePhase.Ended && !dropStale) {
                speaker.play(if (encoding == "linear16") pcm16(event.bytes) else Mulaw.decode(event.bytes))
                if (heardAfterMs == null && turnAt > 0) { heardAfterMs = nowMillis() - turnAt; showTiming() }
                local.update { if (it.phase == VoicePhase.Thinking || it.phase == VoicePhase.Listening) it.copy(phase = VoicePhase.Speaking) else it }
            }
            is LiveVoiceEvent.Transcript -> local.update { it.copy(heard = event.text, heardFinal = event.final, notice = null) }
            is LiveVoiceEvent.Turn -> Diagnostics.note("voice: turn").also {
                turnAt = nowMillis(); heardAfterMs = null; serverTiming = null
                dropStale = false
                resetDucking()
                local.update { it.copy(phase = VoicePhase.Thinking, turns = VoiceTranscript.question(it.turns, event.text), heard = "", heardFinal = false, status = null) } }
            is LiveVoiceEvent.Reply -> if (!dropStale) local.update { it.copy(phase = VoicePhase.Speaking, turns = VoiceTranscript.sentence(it.turns, event.text), status = null) }
            is LiveVoiceEvent.UsingTool -> local.update { it.copy(status = "Reading your chats…") }
            is LiveVoiceEvent.Status -> local.update { it.copy(status = event.label) }
            is LiveVoiceEvent.Interrupted -> {
                Diagnostics.note("voice: interrupted")
                dropStale = false
                speaker.flush()
                resetDucking(flushed = true)
                local.update { it.copy(phase = VoicePhase.Listening, turns = VoiceTranscript.interrupted(it.turns)) }
            }
            is LiveVoiceEvent.ReplyDone -> {
                Diagnostics.note("voice: reply done, spoken=${event.spoken}")
                event.message.conversationId?.let { conversationId = it }
                resetDucking()
                local.update { it.copy(phase = VoicePhase.Listening, status = null, turns = VoiceTranscript.done(it.turns, event.message.text, event.spoken)) }
                viewModelScope.launch { refreshHistory() }
            }
            is LiveVoiceEvent.Error -> Diagnostics.note("voice: error ${event.code}").also { resetDucking(); local.update { it.copy(phase = VoicePhase.Listening, status = null, notice = noticeFor(event.code), turns = VoiceTranscript.failed(it.turns)) } }
            is LiveVoiceEvent.Closing -> { Diagnostics.note("voice: closing ${event.reason}"); if (event.reason == com.circaso.feature.assistant.domain.AshaAvailability.UnavailableCode) signedOff(); finish(endingFor(event)) }
            // A refused sign-in ends the call; any other close is the line dropping, which [converse] reconnects.
            is LiveVoiceEvent.Closed -> if (!ended) closedEnding(event)?.let { finish(it) }
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
        speaker.start(ready.sampleRate) // at full volume: a new call never starts ducked
        volumeRamp?.cancel()
        volume = 1f
        dropStale = false
        activity.reset()
        when (microphone.start()) {
            LiveMicStart.Started -> Unit
            LiveMicStart.PermissionDenied -> { s.end(); return finish(VoiceEnding.NoMicrophone) }
            LiveMicStart.Unsupported -> { s.end(); return finish(VoiceEnding.UseClips) }
            LiveMicStart.Failed -> { s.end(); return finish(VoiceEnding.Failed("Couldn't start the microphone. Try again.")) }
        }
        microphone.muted = local.value.muted
        everLive = true
        reopenFailures = 0
        giveUp?.cancel()
        local.update { it.copy(phase = VoicePhase.Listening, voiceSecondsLeft = ready.voiceSecondsLeft) }
        streaming?.cancel()
        streaming = viewModelScope.launch {
            // Everything streams, her turn included: no loudness gate. With the echo canceller on, a normal voice
            // talking over her is quiet on the microphone, and a gate that kept her echo out kept the person out too.
            // The detector (or, without it, the server) tells the person from her echo and from noise.
            microphone.packets.collect { packet ->
                if (local.value.muted) return@collect
                if (!vadActive) duckForVoice(clock())
                s.sendAudio(packet)
            }
        }
        detect(s)
    }

    // ---- the phone's own barge-in: Silero VAD on the echo-cancelled microphone ---------------------------------

    /** The detector, opened once per screen on [vadDispatcher]; null when it can't be. */
    private val detectorLoad: Deferred<BargeInDetector?>? = vadLoader?.let { loader ->
        viewModelScope.async(vadDispatcher) {
            val model = loader.open()
            // the screen went while the model was opening: close it here, no one else will
            if (!isActive) { model?.close(); null } else model?.let { BargeInDetector(SileroVad(it)) }
        }
    }
    private var detecting: Job? = null
    /** The detector is running for this call: it, not the energy detector, ducks her. */
    @Volatile private var vadActive = false
    /** Set the moment the phone stops her itself, until `interrupted` or the next `turn`: her frames in between are stale. */
    @Volatile private var dropStale = false
    /** Debug: how long the last barge-in took from the person's first word to her silence, for the timing line. */
    @Volatile private var lastStopMs: Long? = null
    /** When the detector last dipped her, and the pending return to full volume after a cough. */
    @Volatile private var duckedAt = 0L
    private var unduck: Job? = null

    private fun detect(s: LiveVoiceSession) {
        detecting?.cancel()
        vadActive = false
        val load = detectorLoad ?: return
        detecting = viewModelScope.launch(vadDispatcher) {
            val detector = load.await() ?: return@launch
            detector.reset()
            vadActive = true
            // a dip the energy detector made before the model was ready would otherwise never be undone
            if (local.value.ducked) viewModelScope.launch { undo() }
            try {
                launch { speaker.blocks.collect { detector.onPlayback(it) } }
                launch { speaker.route.collect { detector.route = it; Diagnostics.note("voice: route $it") } }
                microphone.frames.collect { frame ->
                    if (ended || local.value.muted) return@collect
                    detector.onMic(frame) { onSpeech(it, detector, s) }
                }
            } finally {
                vadActive = false
            }
        }
    }

    /** One edge from the detector, on [vadDispatcher]. The stop itself happens here, not after a hop to the main thread. */
    private fun onSpeech(e: BargeInDetector.Event, detector: BargeInDetector, s: LiveVoiceSession) {
        val ui = local.value
        when (e.edge) {
            SpeechOnset.Edge.Rising -> if (ui.phase == VoicePhase.Speaking && !dropStale) viewModelScope.launch {
                // the feel: she dips the moment the person is heard, and stops once it is sure (~200 ms)
                unduck?.cancel()
                if (local.value.phase != VoicePhase.Speaking || local.value.ducked) return@launch
                duckedAt = monoClock()
                rampVolume(DuckedVolume, DuckMillis)
                local.update { it.copy(ducked = true) }
            }
            // a cough: she comes back up, but the screen holds listening a moment so the logo doesn't flicker
            SpeechOnset.Edge.Fell -> if (ui.ducked) viewModelScope.launch {
                unduck?.cancel()
                unduck = viewModelScope.launch {
                    delay((duckedAt + MinDuckMillis - monoClock()).coerceAtLeast(0))
                    if (local.value.phase == VoicePhase.Speaking) undo()
                }
            }
            SpeechOnset.Edge.Onset -> {
                val herTurn = ui.phase == VoicePhase.Thinking || ui.phase == VoicePhase.Speaking
                if (!herTurn && !speaker.playing.value) { tune("listening", e, detector, null); return }
                if (herTurn) dropStale = true
                speaker.flush()
                // release builds too: a reply cut short by the phone itself must show in the diagnostic log
                Diagnostics.note("voice: phone stopped her (${if (herTurn) "barge-in" else "tail"}, p=${e.probability.fmt()})")
                val stoppedAt = monoClock()
                detector.onFlushed(stoppedAt)
                tune(if (herTurn) "barge-in" else "tail", e, detector, stoppedAt)
                if (herTurn) viewModelScope.launch { bargedIn(s) } else viewModelScope.launch { resetDucking(flushed = true) }
            }
            SpeechOnset.Edge.Offset -> Unit
        }
    }

    private fun undo() {
        if (!local.value.ducked) return
        rampVolume(1f, UnduckMillis)
        local.update { it.copy(ducked = false) }
    }

    /**
     * Her answer is cut off by the phone itself: listening at once, and the server is told. Her turn closes as it
     * stood (marked talked over when she had started), so the answer she was still writing is never shown as heard.
     */
    private suspend fun bargedIn(s: LiveVoiceSession) {
        if (ended) return
        unduck?.cancel()
        resetDucking(flushed = true)
        local.update {
            if (it.phase != VoicePhase.Thinking && it.phase != VoicePhase.Speaking) it
            else it.copy(phase = VoicePhase.Listening, status = null, turns = VoiceTranscript.stopped(it.turns))
        }
        showTiming()
        s.sendInterrupt(kotlin.time.Clock.System.now().toString())
    }

    /** Debug builds only: one line per onset with what the tuning needs. */
    private fun tune(what: String, e: BargeInDetector.Event, detector: BargeInDetector, stoppedAt: Long?) {
        if (client?.debugBuild != true) return
        val v = e.verdict
        stoppedAt?.let { lastStopMs = it - e.speechFrom }
        Diagnostics.note(
            "voice: vad $what p=${e.probability.fmt()} mic=${e.micDb.fmt()}dB floor=${detector.floorDb.fmt()}dB route=${e.route}" +
                (if (v != null) " echo=${v.echoDb?.fmt() ?: "-"}dB delay=${v.delayMillis}ms coupling=${v.couplingDb.fmt()}dB learned=${v.learned}" else " guard=off") +
                (if (stoppedAt != null) " speech→stop=${stoppedAt - e.speechFrom}ms detect→stop=${stoppedAt - e.at}ms" else ""),
        )
    }

    private fun Float.fmt(): String = ((this * 100).toInt() / 100f).toString()

    // ---- ducking: her voice dips the moment the person talks over her, before the server confirms it ----------

    private val activity = VoiceActivity()
    private var volumeRamp: Job? = null
    private var volume = 1f
    /** When the person's voice was last heard over hers while she is ducked; restored [DuckHoldMillis] after it. */
    private var voiceLastAt = 0L

    /** Called for every microphone packet (every 20 ms) with the clock. */
    private fun duckForVoice(now: Long) {
        val voice = activity.update(microphone.level.value, now)
        val ui = local.value
        if (!ui.ducked) {
            if (voice && ui.phase == VoicePhase.Speaking) {
                Diagnostics.note("voice: ducking")
                voiceLastAt = now
                rampVolume(DuckedVolume, DuckMillis)
                local.update { it.copy(ducked = true) }
            }
            return
        }
        if (voice) { voiceLastAt = now; return }
        // No `interrupted` after the person's voice stopped: it was a cough, the TV, a word to someone else.
        if (now - voiceLastAt >= DuckHoldMillis) {
            Diagnostics.note("voice: unducking")
            rampVolume(1f, UnduckMillis)
            local.update { it.copy(ducked = false) }
        }
    }

    /**
     * Her turn is over (talked over, answered, failed, or a new one began): full volume for the next reply. At once
     * when nothing of hers is left to play (after a flush); otherwise what is still buffered finishes ducked, so the
     * tail of her answer never jumps back up over a person mid-sentence.
     */
    private fun resetDucking(flushed: Boolean = false) {
        volumeRamp?.cancel()
        local.update { if (it.ducked) it.copy(ducked = false) else it }
        if (volume == 1f) return
        if (flushed || !speaker.playing.value) { volume = 1f; speaker.setVolume(1f); return }
        volumeRamp = viewModelScope.launch {
            speaker.playing.first { !it }
            volume = 1f
            speaker.setVolume(1f)
        }
    }

    /** Moves her volume to [to] in [millis], easing out, a step per frame. */
    private fun rampVolume(to: Float, millis: Long) {
        volumeRamp?.cancel()
        val from = volume
        volumeRamp = viewModelScope.launch {
            val steps = (millis / RampStepMillis).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                volume = from + (to - from) * (1 - (1 - t) * (1 - t))
                speaker.setVolume(volume)
                if (i < steps) delay(millis / steps)
            }
            volume = to
        }
    }

    /** The microphone's packets on their way to the socket; one per conversation. */
    private var streaming: Job? = null

    fun onMute() {
        val muted = !local.value.muted
        microphone.muted = muted
        // muted, nothing of the person reaches anyone, so nothing of theirs ducks her either
        // (she comes back up gently: the person has stepped out of the conversation, she hasn't stopped)
        if (muted) {
            activity.reset()
            detectorLoad?.let { load -> viewModelScope.launch(vadDispatcher) { load.await()?.reset() } }
            if (local.value.ducked) { rampVolume(1f, UnduckMillis); local.update { it.copy(ducked = false) } }
        }
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
        giveUp?.cancel()
        streaming?.cancel()
        detecting?.cancel()
        dropStale = false
        volumeRamp?.cancel()
        microphone.stop()
        speaker.stop()
        local.update { it.copy(phase = VoicePhase.Ended, ending = ending, status = null, ducked = false) }
    }

    private fun endingFor(c: LiveVoiceEvent.Closing): VoiceEnding = when (c.reason) {
        "ended" -> VoiceEnding.HungUp
        "silence" -> VoiceEnding.Quiet
        "assistant.quota_exceeded" -> VoiceEnding.OutOfVoice(c.retryAfterSeconds)
        "assistant.language_not_supported" -> VoiceEnding.UseClips
        "assistant.model_unavailable" -> VoiceEnding.Failed("Asha's voice isn't available right now. Try again in a moment, or type.")
        com.circaso.feature.assistant.domain.AshaAvailability.UnavailableCode -> VoiceEnding.Maintenance()
        else -> VoiceEnding.Failed(c.message ?: "The conversation ended.")
    }

    /**
     * Asha has just said she is down for maintenance: let her line finish playing (it is the whole message), tell the
     * app she is down, and once she is back turn the ending into [VoiceEnding.AshaBack].
     */
    private suspend fun signedOff() {
        withTimeoutOrNull(SignOffMillis) { speaker.playing.first { !it } }
        val a = availability ?: return
        a.reportDown()
        awaitBack(a)
    }

    /** Turns a maintenance ending into [VoiceEnding.AshaBack] once she is back (the store polls while this is visible). */
    private fun awaitBack(a: com.circaso.feature.assistant.domain.AshaAvailability) {
        viewModelScope.launch {
            a.isDown.first { !it }
            local.update { if (it.ending is VoiceEnding.Maintenance) it.copy(ending = VoiceEnding.AshaBack) else it }
        }
    }

    /** The voice screen is visible or not: while it is and Asha is down, the app checks whether she is back. */
    fun onVisible(visible: Boolean) = availabilityWatch.set(visible)

    /** The ending a close means, or null when it is only the line dropping (reconnected, or Offline if it never got going). */
    private fun closedEnding(c: LiveVoiceEvent.Closed): VoiceEnding? {
        Diagnostics.note("voice: closed ${c.code} ${c.reason ?: ""}")
        return when (c.reason) {
            "unauthorized", "auth required" -> VoiceEnding.Failed("Please sign in again to talk to Asha.")
            else -> null
        }
    }

    private fun noticeFor(code: String) = when (code) {
        "assistant.model_unavailable" -> "Asha couldn't answer that one. Ask again."
        "assistant.turn_not_found" -> "That took too long. Ask again."
        else -> "Something went wrong with that answer. Ask again."
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        availabilityWatch.set(false)
        val s = session
        microphone.stop()
        speaker.stop()
        // The scope is already cancelled: say goodbye off it, so the server frees the line at once.
        if (s != null && !ended) GlobalScope.launch { runCatching { withTimeoutOrNull(1_000) { s.end() } }; runCatching { s.close() } }
        // after the window being run (the dispatcher runs one thing at a time), the ONNX session is closed
        detectorLoad?.let { load -> GlobalScope.launch(vadDispatcher) { runCatching { load.await()?.close() } } }
    }

    private companion object {
        /** The longest the maintenance line may keep playing before the call ends anyway. */
        const val SignOffMillis = 6_000L
        /** Her volume while the person may be talking over her: low enough to hear yourself, not gone. */
        const val DuckedVolume = 0.25f
        /** Down fast (an answer to the person), back up gently (it was nothing). */
        const val DuckMillis = 80L
        const val UnduckMillis = 150L
        /** A duck from the detector shows listening at least this long, so a cough doesn't flicker the logo. */
        const val MinDuckMillis = 300L
        /** How long after the person's voice stops a ducked reply waits for the server's `interrupted`. */
        const val DuckHoldMillis = 900L
        const val RampStepMillis = 16L

        /** A dropped call that can't reopen within this ends as Offline, with Talk Again. */
        const val ReconnectWindowMillis = 30_000L
        /** Reopen attempts that may fail (refused, or dropped again before `ready`) before the call ends. */
        const val MaxReopenFailures = 3
        /** The pause before each reopen once the connection is back: short, growing a little per failure. */
        val ReopenBackoffMillis = longArrayOf(500, 1_500, 3_000)

        /** 16-bit little-endian samples, for a server configured to send `linear16` instead of mulaw. */
        fun pcm16(bytes: ByteArray): ShortArray = ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
    }

    /**
     * The debug line: the server's steps, then when the first sound played here (the target is under 800 ms), and how
     * long the last barge-in took from the person's first word to her silence (the target is under 300 ms).
     */
    private fun showTiming() {
        if (client?.debugBuild != true) return
        val server = serverTiming
        val parts = listOfNotNull(
            server?.modelFirstTextMs?.let { "model $it" },
            server?.firstSentenceMs?.let { "voice $it" },
            server?.firstAudioMs?.let { "sent $it" },
            heardAfterMs?.let { "heard $it" },
            lastStopMs?.let { "stop $it" },
        )
        local.update { it.copy(timing = if (parts.isEmpty()) null else parts.joinToString(" · ") + " ms") }
    }

    // last, so every property above is set before the conversation starts
    init {
        start()
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

    /** The phone stopped her itself: the open exchange closes as it stood, talked over if she had begun. */
    fun stopped(turns: List<VoiceTurn>): List<VoiceTurn> = updateOpen(turns) { it.copy(done = true, interrupted = it.reply.isNotEmpty()) }

    fun done(turns: List<VoiceTurn>, savedText: String, spoken: Boolean): List<VoiceTurn> {
        val last = turns.lastOrNull()
        // an unspoken answer whose exchange is already closed (the phone stopped her): never shown as heard
        if (!spoken && (last == null || last.done)) return turns
        if (last == null || last.done) return if (savedText.isBlank()) turns else (turns + VoiceTurn("", listOf(savedText), done = true)).takeLast(MaxTurns)
        return turns.dropLast(1) + last.copy(
            reply = last.reply.ifEmpty { listOfNotNull(savedText.takeIf { it.isNotBlank() }) },
            done = true,
            interrupted = last.interrupted || !spoken,
        )
    }

    fun failed(turns: List<VoiceTurn>): List<VoiceTurn> = updateOpen(turns) { it.copy(done = true) }

    /** The line dropped mid-answer: the open exchange closes, marked cut off when part of an answer had arrived. */
    fun cutOff(turns: List<VoiceTurn>): List<VoiceTurn> = updateOpen(turns) { it.copy(done = true, cutOff = it.reply.isNotEmpty()) }

    private fun updateOpen(turns: List<VoiceTurn>, change: (VoiceTurn) -> VoiceTurn): List<VoiceTurn> {
        val last = turns.lastOrNull() ?: return turns
        return if (last.done) turns else turns.dropLast(1) + change(last)
    }
}
