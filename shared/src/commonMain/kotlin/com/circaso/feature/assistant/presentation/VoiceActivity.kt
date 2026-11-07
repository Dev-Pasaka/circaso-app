package com.circaso.feature.assistant.presentation

/**
 * Whether the person is talking, from the microphone's level after the platform's echo canceller (0..1, the shared
 * `AudioLevel` scale: about -50 dBFS is 0, -10 dBFS is 1). A simple energy detector, enough to duck Asha's voice the
 * moment the person talks over her; the server, not this, decides whether it was a real interruption.
 *
 * - **Noise floor.** It follows the quietest the room has been: down at once, up slowly ([FloorRise] per update, about
 *   two seconds to catch up; four times slower while it is loud), and never above [MaxFloor], so a steady hum or her
 *   leftover echo raises the bar after a few seconds instead of ducking her for good, while a person's voice (short
 *   and much louder) barely moves it.
 * - **Voice.** The level stays [Margin] above the floor (and at least [MinLevel]) for [AttackMillis]: one loud click
 *   doesn't count. It ends after [ReleaseMillis] below the bar, so the gaps between words don't end it.
 *
 * Pure and driven by the caller's clock, so it is tested without audio.
 */
class VoiceActivity(
    private val attackMillis: Long = AttackMillis,
    private val releaseMillis: Long = ReleaseMillis,
) {
    var floor = StartFloor
        private set
    var active = false
        private set
    private var aboveSince = -1L
    private var belowSince = -1L

    /** Takes the level at [now] (milliseconds) and says whether the person is talking. */
    fun update(level: Float, now: Long): Boolean {
        val loud = level >= maxOf(MinLevel, floor + Margin)
        // a voice is short and loud, so it barely moves the floor; a steady sound (a hum, her leftover echo) raises it
        if (level < floor) floor = level
        else floor = minOf(MaxFloor, floor + (level - floor) * if (loud) FloorRise / 4 else FloorRise)
        if (loud) {
            belowSince = -1
            if (aboveSince < 0) aboveSince = now
            if (!active && now - aboveSince >= attackMillis) active = true
        } else {
            aboveSince = -1
            if (belowSince < 0) belowSince = now
            if (active && now - belowSince >= releaseMillis) active = false
        }
        return active
    }

    fun reset() {
        floor = StartFloor
        active = false
        aboveSince = -1
        belowSince = -1
    }

    companion object {
        /** Sustained voice before it counts (~150 ms): a voice, not a tap on the phone. */
        const val AttackMillis = 150L
        /** Below the bar this long and the voice has stopped: longer than the gap between two words. */
        const val ReleaseMillis = 250L
        /** 0.12 on the level scale is about 5 dB over the room. */
        const val Margin = 0.12f
        /** Never a voice below about -38 dBFS after echo cancellation, however quiet the room. */
        const val MinLevel = 0.3f
        const val MaxFloor = 0.45f
        const val StartFloor = 0.1f
        /** Per microphone packet (20 ms): the floor closes 1% of its gap to a louder room each time. */
        const val FloorRise = 0.01f
    }
}
