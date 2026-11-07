package com.circaso.feature.assistant.voice

import com.circaso.core.platform.PlaybackBlock
import kotlin.math.sqrt

/**
 * Tells the person from Asha's own voice coming back from the loudspeaker (double talk). The platform's echo
 * canceller takes most of her out of the microphone, not all of it, and the voice activity detector hears what is left
 * as speech (it is speech). But the app knows exactly what it is playing ([PlaybackBlock]s), so it can predict how loud
 * her leftover echo is at the microphone and only count a window that is clearly louder than that.
 *
 * - **Reference.** Her playback's loudness over the last couple of seconds, on the shared monotonic clock.
 * - **Delay.** The echo reaches the microphone some time after the platform says a block plays (the output and input
 *   buffers, the room). Each candidate delay from 0 to [MaxDelayMillis] keeps a running mean and variance of
 *   `mic dB - reference dB` over windows where only she is talking; the true delay is the one where that difference is
 *   steadiest (the echo follows her loudness), chosen once [MinLearnedWindows] windows are in.
 * - **Coupling.** At that delay, the echo is about `reference + mean` dB, rarely above `reference + mean +`
 *   [SpreadFactor]` x deviation`: that is the predicted echo. Until learned, [DefaultCouplingDb].
 * - **Verdict.** A window passes when nothing of hers can be in it (no reference near the delayed window), or when the
 *   microphone is at least [MarginDb] over the predicted echo.
 *
 * Pure and single-threaded: the caller feeds blocks and windows from one thread, with their own timestamps.
 */
class EchoGuard(
    private val marginDb: Float = MarginDb,
) {
    /** What the guard said about one window, for the decision and the tuning log. */
    data class Verdict(val passes: Boolean, val echoDb: Float?, val delayMillis: Int, val couplingDb: Float, val learned: Boolean)

    private val refAt = LongArray(RefBlocks)
    private val refEnd = LongArray(RefBlocks)
    private val refDb = FloatArray(RefBlocks)
    private var refCount = 0
    private var refNext = 0

    private val mean = FloatArray(Delays)
    private val variance = FloatArray(Delays)
    /** Windows each candidate delay has learned from: only those where it points at some of her voice. */
    private val seen = IntArray(Delays)
    private var learnedWindows = 0

    fun addPlayback(block: PlaybackBlock) {
        refAt[refNext] = block.atMillis
        refEnd[refNext] = block.atMillis + block.millis
        refDb[refNext] = block.db
        refNext = (refNext + 1) % RefBlocks
        if (refCount < RefBlocks) refCount++
    }

    /** Her voice was cut off (flushed): what was scheduled after [now] will never play. */
    fun cutPlayback(now: Long) {
        for (i in 0 until refCount) if (refAt[i] >= now) refEnd[i] = refAt[i] else if (refEnd[i] > now) refEnd[i] = now
    }

    /** Loudest of her voice overlapping [from, to), or null when none of it does. */
    private fun reference(from: Long, to: Long): Float? {
        var best: Float? = null
        for (i in 0 until refCount) {
            if (refEnd[i] <= refAt[i] || refEnd[i] <= from || refAt[i] >= to) continue
            val db = refDb[i]
            if (best == null || db > best) best = db
        }
        return best
    }

    /** Whether any of her voice may still be in the microphone for a window ending at [end]. */
    fun hearsHer(end: Long): Boolean = reference(end - SileroVad.WindowMillis - MaxDelayMillis - TailMillis, end) != null

    /** The delay that best explains the echo so far. */
    val delayMillis: Int get() {
        if (learnedWindows < MinLearnedWindows) return DefaultDelayMillis
        var best = -1
        for (d in 0 until Delays) {
            if (seen[d] < MinLearnedWindows) continue
            if (best < 0 || variance[d] < variance[best]) best = d
        }
        return if (best < 0) DefaultDelayMillis else best * DelayStepMillis
    }

    val couplingDb: Float get() {
        val d = delayMillis / DelayStepMillis
        if (learnedWindows < MinLearnedWindows || seen[d] < MinLearnedWindows) return DefaultCouplingDb
        return mean[d] + SpreadFactor * sqrt(variance[d].coerceAtLeast(0f))
    }

    /** The verdict for a microphone window of [micDb] ending at [end]. */
    fun check(micDb: Float, end: Long): Verdict {
        val delay = delayMillis
        val coupling = couplingDb
        val learned = learnedWindows >= MinLearnedWindows
        val ref = reference(end - SileroVad.WindowMillis - delay - SlackMillis, end - delay + SlackMillis)
            ?: return Verdict(true, null, delay, coupling, learned)
        val echo = ref + coupling
        return Verdict(micDb >= echo + marginDb, echo, delay, coupling, learned)
    }

    /**
     * Learns from a window of [micDb] ending at [end] where only she is talking (the detector heard no person): her
     * echo alone, at whatever delay explains it best.
     */
    fun learn(micDb: Float, end: Long) {
        if (reference(end - SileroVad.WindowMillis - MaxDelayMillis, end) == null) return
        for (d in 0 until Delays) {
            val shift = d * DelayStepMillis
            // a delay that points before she started (or after she stopped) says nothing about her echo
            val ref = reference(end - SileroVad.WindowMillis - shift, end - shift) ?: continue
            val alpha = if (seen[d] < MinLearnedWindows) 1f / (seen[d] + 1) else Smoothing
            seen[d]++
            val diff = micDb - ref
            val delta = diff - mean[d]
            mean[d] += alpha * delta
            variance[d] = (1 - alpha) * (variance[d] + alpha * delta * delta)
        }
        learnedWindows++
    }

    fun reset() {
        refCount = 0
        refNext = 0
        mean.fill(0f)
        variance.fill(0f)
        seen.fill(0)
        learnedWindows = 0
    }

    companion object {
        /** The person must be this much louder than her predicted echo (dB). */
        const val MarginDb = 6f
        const val MaxDelayMillis = 300
        const val DelayStepMillis = 20
        const val Delays = MaxDelayMillis / DelayStepMillis + 1
        const val DefaultDelayMillis = 120
        /** Her echo after the canceller, relative to her playback, until the guard has measured it (dB). */
        const val DefaultCouplingDb = -15f
        /** Windows of her talking alone before the measured delay and coupling replace the defaults (~0.5 s). */
        const val MinLearnedWindows = 15
        /** Each new window's weight once learned: about the last 1.5 s of her voice. */
        const val Smoothing = 0.02f
        /** The predicted echo sits this many deviations over the mean: covers nearly all of her echo. */
        const val SpreadFactor = 1.5f
        /** Reverberation: a little either side of the delayed window counts as hers. */
        const val SlackMillis = 20
        const val TailMillis = 200
        /**
         * About 20 s of 20 ms blocks: iOS schedules whole sentences as they arrive, well ahead of playback, so the
         * blocks being heard now must not be pushed out by those still to come.
         */
        const val RefBlocks = 1_024
    }
}
