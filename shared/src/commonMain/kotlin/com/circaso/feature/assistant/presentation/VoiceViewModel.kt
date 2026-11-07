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

    /** The loudness the waves follow: the person's while listening, Asha's while she speaks, else none. */
    val waveLevel: Float get() = when (visual) {
        VoiceVisual.Speaking -> voiceLevel
        VoiceVisual.Listening -> micLevel
        else -> 0f
    }
}

/**
 * The voice screen's conversation (guide A11): one socket and the microphone open for as long as the screen is up.
 * The microphone streams the whole time, even while Asha speaks, which is how talking over her works: the platform's
 * echo canceller takes her voice out and the server decides what is the person (one clear word; guide `interrupted`).
 * The phone answers first: the moment it hears the person over her ([VoiceActivity]), her voice ducks and the screen
 * shows listening; `interrupted` then stops her for good, and without one she comes back up after a moment. The
 * transcript, turn and reply frames build a chat-style transcript; the saved messages reach the Asha chat through the
 * normal history refresh.
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
) : ViewModel() {
    /** When this phone got the turn, and how long until its first sound played; for the debug timing line. */
    private var turnAt = 0L
    private var heardAfterMs: Long? = null
    private var serverTiming: LiveVoiceEvent.Timing? = null

    private val local = MutableStateFlow(VoiceUiState())
    private var session: LiveVoiceSession? = null
    private var conversation: Job? = null
    private var ended = false

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

    init {
        start()
    }

    /** Starts (or, after a quiet hang-up, restarts) the conversation. */
    fun start() {
        if (conversation?.isActive == true) return
        ended = false
        everLive = false
        reopenFailures = 0
        local.value = VoiceUiState()
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
            is LiveVoiceEvent.Audio -> if (local.value.phase != VoicePhase.Ended) {
                speaker.play(if (encoding == "linear16") pcm16(event.bytes) else Mulaw.decode(event.bytes))
                if (heardAfterMs == null && turnAt > 0) { heardAfterMs = nowMillis() - turnAt; showTiming() }
                local.update { if (it.phase == VoicePhase.Thinking || it.phase == VoicePhase.Listening) it.copy(phase = VoicePhase.Speaking) else it }
            }
            is LiveVoiceEvent.Transcript -> local.update { it.copy(heard = event.text, heardFinal = event.final, notice = null) }
            is LiveVoiceEvent.Turn -> Diagnostics.note("voice: turn").also {
                turnAt = nowMillis(); heardAfterMs = null; serverTiming = null
                resetDucking()
                local.update { it.copy(phase = VoicePhase.Thinking, turns = VoiceTranscript.question(it.turns, event.text), heard = "", heardFinal = false, status = null) } }
            is LiveVoiceEvent.Reply -> local.update { it.copy(phase = VoicePhase.Speaking, turns = VoiceTranscript.sentence(it.turns, event.text), status = null) }
            is LiveVoiceEvent.UsingTool -> local.update { it.copy(status = "Reading your chats…") }
            is LiveVoiceEvent.Status -> local.update { it.copy(status = event.label) }
            is LiveVoiceEvent.Interrupted -> {
                Diagnostics.note("voice: interrupted")
                speaker.flush()
                resetDucking()
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
            is LiveVoiceEvent.Closing -> { Diagnostics.note("voice: closing ${event.reason}"); finish(endingFor(event)) }
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
            // The server tells the person from her echo and from noise (guide `interrupted`); the phone only ducks.
            microphone.packets.collect { packet ->
                if (local.value.muted) return@collect
                duckForVoice(clock())
                s.sendAudio(packet)
            }
        }
    }

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

    /** Her turn is over (talked over, answered, or a new one began): full volume for the next reply, at once. */
    private fun resetDucking() {
        volumeRamp?.cancel()
        if (volume != 1f) { volume = 1f; speaker.setVolume(1f) }
        local.update { if (it.ducked) it.copy(ducked = false) else it }
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
        if (muted) { activity.reset(); resetDucking() }
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
        else -> VoiceEnding.Failed(c.message ?: "The conversation ended.")
    }

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
        val s = session
        microphone.stop()
        speaker.stop()
        // The scope is already cancelled: say goodbye off it, so the server frees the line at once.
        if (s != null && !ended) GlobalScope.launch { runCatching { withTimeoutOrNull(1_000) { s.end() } }; runCatching { s.close() } }
    }

    private companion object {
        /** Her volume while the person may be talking over her: low enough to hear yourself, not gone. */
        const val DuckedVolume = 0.25f
        /** Down fast (an answer to the person), back up gently (it was nothing). */
        const val DuckMillis = 80L
        const val UnduckMillis = 150L
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

    /** The line dropped mid-answer: the open exchange closes, marked cut off when part of an answer had arrived. */
    fun cutOff(turns: List<VoiceTurn>): List<VoiceTurn> = updateOpen(turns) { it.copy(done = true, cutOff = it.reply.isNotEmpty()) }

    private fun updateOpen(turns: List<VoiceTurn>, change: (VoiceTurn) -> VoiceTurn): List<VoiceTurn> {
        val last = turns.lastOrNull() ?: return turns
        return if (last.done) turns else turns.dropLast(1) + change(last)
    }
}
