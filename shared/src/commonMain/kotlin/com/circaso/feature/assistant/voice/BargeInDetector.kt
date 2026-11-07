package com.circaso.feature.assistant.voice

import com.circaso.core.platform.AudioRoute
import com.circaso.core.platform.MicFrame
import com.circaso.core.platform.PlaybackBlock

/**
 * The phone's own barge-in detector (decision 2026-11-07, "like a phone call"): Silero VAD on the echo-cancelled
 * microphone, the [SpeechOnset] bars, and on the loudspeaker the [EchoGuard] against her own voice. It only says what
 * it heard ([Event]); the voice screen decides what to do with it. One thread at a time (the caller's single-threaded
 * dispatcher), no allocation per window beyond the event.
 */
class BargeInDetector(
    private val vad: SileroVad,
    private val guard: EchoGuard = EchoGuard(),
    private val onset: SpeechOnset = SpeechOnset(),
) {
    /** What one window did. [at] is the end of the window that decided it, on the shared monotonic clock. */
    data class Event(
        val edge: SpeechOnset.Edge,
        val at: Long,
        /** When the speech that made the onset began (its first counting window's start). */
        val speechFrom: Long,
        val probability: Float,
        val micDb: Float,
        val verdict: EchoGuard.Verdict?,
        val route: AudioRoute,
    )

    var route: AudioRoute = AudioRoute.Loudspeaker
        set(value) {
            field = value
            onset.bar = if (value == AudioRoute.Headphones) SpeechOnset.Headphones else SpeechOnset.Loudspeaker
        }

    /** The latest window's probability, for the debug overlay and tuning. */
    var probability = 0f
        private set

    /** The room's level under everything (dBFS): down at once, up slowly. For the tuning log. */
    var floorDb = -60f
        private set

    private var speechFrom = -1L

    fun onPlayback(block: PlaybackBlock) = guard.addPlayback(block)

    /** Her voice was flushed at [now]: nothing scheduled after it will play, so it can't come back as echo. */
    fun onFlushed(now: Long) = guard.cutPlayback(now)

    /** One microphone frame; [emit] gets each edge it causes. */
    fun onMic(frame: MicFrame, emit: (Event) -> Unit) {
        vad.accept(frame.pcm) { p, db, endIndex ->
            // the frame is stamped at its last sample; a window may end part way through it
            window(p, db, frame.atMillis - (frame.pcm.size - endIndex) * 1_000L / SileroVad.SampleRate, emit)
        }
    }

    private fun window(p: Float, db: Float, end: Long, emit: (Event) -> Unit) {
        probability = p
        floorDb = if (db < floorDb) db else floorDb + (db - floorDb) * FloorRise
        val guarded = route != AudioRoute.Headphones && guard.hearsHer(end)
        val verdict = if (guarded) guard.check(db, end) else null
        val edge = onset.update(p, verdict?.passes ?: true)
        // a window that counts towards nobody's speech is her echo (or the room) alone, and teaches the guard. Not the
        // probability: Silero hears her echo as speech too (it is speech); what the guard held back is hers.
        if (guarded && !onset.talking && onset.counted == 0) guard.learn(db, end)
        edge ?: return
        when (edge) {
            SpeechOnset.Edge.Rising -> speechFrom = end - SileroVad.WindowMillis
            SpeechOnset.Edge.Fell, SpeechOnset.Edge.Offset -> Unit
            SpeechOnset.Edge.Onset -> if (speechFrom < 0) speechFrom = end - onset.bar.onsetMillis
        }
        emit(Event(edge, end, speechFrom, p, db, verdict, route))
        if (edge != SpeechOnset.Edge.Rising) speechFrom = -1
    }

    /** A new call, or the person muted: forget the speech in progress (the guard keeps what it learned of the room). */
    fun reset() {
        vad.reset()
        onset.reset()
        speechFrom = -1
        probability = 0f
    }

    fun close() = vad.close()

    private companion object {
        /** Per window: about 3 s to follow a louder room. */
        const val FloorRise = 0.01f
    }
}
