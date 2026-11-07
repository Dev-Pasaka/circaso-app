package com.circaso.feature.assistant.voice

/**
 * When the person starts and stops talking, from the voice activity detector's probability, one 32 ms window at a time.
 * Pure: the caller gives each window's probability and whether it passed the echo guard.
 *
 * - **Onset.** Windows at or above [Bar.onset] that pass the guard count towards [Bar.onsetMillis] of speech; a window
 *   between [offset] and the onset bar, or one the guard held back, neither counts nor resets, unless [MaxMisses] of
 *   them come in a row; one below [offset] starts again. The first counting window is [Edge.Rising] (enough for the
 *   screen to react), and the bar reached is [Edge.Onset]; a run that dies before it is [Edge.Fell].
 * - **Offset.** Once talking, [offsetMillis] of windows below [offset] is [Edge.Offset]: longer than the gap between
 *   words.
 */
class SpeechOnset(
    var bar: Bar = Loudspeaker,
    private val offset: Float = OffsetProbability,
    private val offsetMillis: Int = OffsetMillis,
    private val windowMillis: Int = SileroVad.WindowMillis,
) {
    /** The onset bar: the probability a window needs, and how much speech at it. */
    data class Bar(val onset: Float, val onsetMillis: Int)

    enum class Edge { Rising, Fell, Onset, Offset }

    var talking = false
        private set

    /** Speech counted towards the onset so far, in milliseconds. */
    var counted = 0
        private set
    private var misses = 0
    private var quiet = 0

    fun update(probability: Float, passesGuard: Boolean): Edge? {
        if (talking) {
            if (probability < offset) {
                quiet += windowMillis
                if (quiet >= offsetMillis) { talking = false; quiet = 0; return Edge.Offset }
            } else {
                quiet = 0
            }
            return null
        }
        return when {
            probability >= bar.onset && passesGuard -> {
                val rising = counted == 0
                counted += windowMillis
                misses = 0
                when {
                    counted >= bar.onsetMillis -> { talking = true; counted = 0; quiet = 0; Edge.Onset }
                    rising -> Edge.Rising
                    else -> null
                }
            }
            counted > 0 && probability >= offset && ++misses <= MaxMisses -> null
            counted > 0 -> { counted = 0; misses = 0; Edge.Fell }
            else -> null
        }
    }

    fun reset() {
        talking = false
        counted = 0
        misses = 0
        quiet = 0
    }

    companion object {
        /** On the loudspeaker: a clear voice for about 200 ms (6 windows of 32 ms), past the echo guard. */
        val Loudspeaker = Bar(onset = 0.6f, onsetMillis = 190)
        /** On headphones nothing of her comes back: a lower bar, and a quicker one (4 windows). */
        val Headphones = Bar(onset = 0.5f, onsetMillis = 120)
        const val OffsetProbability = 0.35f
        const val OffsetMillis = 300
        /** Unsure windows in a row an onset may ride over (a breath between syllables). */
        const val MaxMisses = 2
    }
}
