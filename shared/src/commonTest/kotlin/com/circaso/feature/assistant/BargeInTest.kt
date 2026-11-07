package com.circaso.feature.assistant

import com.circaso.core.platform.AudioRoute
import com.circaso.core.platform.MicFrame
import com.circaso.core.platform.PlaybackBlock
import com.circaso.feature.assistant.voice.BargeInDetector
import com.circaso.feature.assistant.voice.EchoGuard
import com.circaso.feature.assistant.voice.SileroVad
import com.circaso.feature.assistant.voice.SpeechOnset
import com.circaso.feature.assistant.voice.SpeechOnset.Edge
import com.circaso.feature.assistant.voice.VadModel
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A stand-in for Silero that hears anything loud as speech: the worst case for the echo guard, since the real model
 * also calls her echo speech (it is speech). Probability from the window's level: over -45 dBFS is 0.95.
 */
internal class LoudIsSpeech(private val threshold: Float = -45f) : VadModel {
    var runs = 0
    var closed = false
    override fun run(input: FloatArray, state: FloatArray): Float {
        runs++
        state[0] += 1f // the state is carried, as the real one is
        val db = com.circaso.core.platform.AudioDb.of(input, SileroVad.Context, SileroVad.Window)
        return if (db > threshold) 0.95f else 0.02f
    }
    override fun close() { closed = true }
}

/** 20 ms of a tone at [db] dBFS (0 is silence: [db] null). */
internal fun tone(db: Float?, phase: Int = 0): ShortArray = ShortArray(320) { i ->
    if (db == null) 0 else (32_767 * 10f.pow(db / 20) * 1.414f * sin(2 * PI * 220 * (phase * 320 + i) / 16_000).toFloat()).toInt().toShort()
}

/** Two signals played together (her echo and the person), clipped. */
internal fun mix(a: ShortArray, b: ShortArray) = ShortArray(a.size) { (a[it] + b[it]).coerceIn(-32_768, 32_767).toShort() }

class SpeechOnsetTest {
    private fun SpeechOnset.feed(p: Float, windows: Int, guard: Boolean = true): List<Edge> = (1..windows).mapNotNull { update(p, guard) }

    @Test fun aClearVoiceForAbout200MsIsAnOnsetAndTheFirstWindowRises() {
        val s = SpeechOnset()
        assertEquals(listOf(Edge.Rising), s.feed(0.9f, 5), "rises at once, not yet an onset (160 ms)")
        assertEquals(listOf(Edge.Onset), s.feed(0.9f, 1), "the sixth window, 192 ms of speech")
        assertTrue(s.talking)
        assertEquals(emptyList(), s.feed(0.2f, 9), "a gap between words (288 ms) doesn't end it")
        assertEquals(listOf(Edge.Offset), s.feed(0.2f, 1), "300 ms below the offset bar does")
        assertFalse(s.talking)
    }

    @Test fun aShortBurstFallsAndAnUnsureWindowDoesNotReset() {
        val s = SpeechOnset()
        assertEquals(listOf(Edge.Rising), s.feed(0.9f, 3))
        assertEquals(emptyList(), s.feed(0.5f, 2), "a breath between syllables: neither counted nor reset")
        assertEquals(listOf(Edge.Onset), s.feed(0.9f, 3), "3 + 3 windows")
        val t = SpeechOnset()
        t.feed(0.9f, 3)
        assertEquals(listOf(Edge.Fell), t.feed(0.1f, 1), "a cough: silence after it, gone")
        assertEquals(listOf(Edge.Rising), t.feed(0.9f, 5), "and the count starts again")
    }

    @Test fun windowsTheEchoGuardHoldsBackNeverAddUpToAnOnset() {
        val s = SpeechOnset()
        assertEquals(listOf(Edge.Rising), s.feed(0.9f, 1))
        assertEquals(listOf(Edge.Fell), s.feed(0.9f, 30, guard = false), "her echo: high probability, held back, dropped")
        assertFalse(s.talking)
    }

    @Test fun headphonesLowerTheBar() {
        val s = SpeechOnset(SpeechOnset.Headphones)
        assertEquals(emptyList(), s.feed(0.55f, 0))
        assertEquals(listOf(Edge.Rising, Edge.Onset), s.feed(0.55f, 4), "0.55 for 128 ms is enough on headphones")
        val l = SpeechOnset(SpeechOnset.Loudspeaker)
        assertEquals(emptyList(), l.feed(0.55f, 10), "but not on the loudspeaker")
    }
}

class EchoGuardTest {
    /** Her voice at [db] dBFS from [from] for [millis], in 20 ms blocks. */
    private fun EchoGuard.plays(from: Long, millis: Int, db: (Int) -> Float) {
        var t = 0
        while (t < millis) { addPlayback(PlaybackBlock(from + t, 20, db(t))); t += 20 }
    }

    @Test fun herEchoAloneNeverPassesAndThePersonOverItDoes() {
        val g = EchoGuard()
        val random = Random(7)
        // a sentence of hers, loudness moving like speech
        val herDb = { t: Long -> -20f - 15f * ((t.coerceAtLeast(0) / 100) % 3) }
        g.plays(0, 4_000) { herDb(it.toLong()) }
        // her echo arrives 100 ms later, 25 dB down, give or take a couple of dB
        // (the loudest of her 20 ms blocks heard in the window, 100 ms back)
        val echo = { end: Long ->
            ((end - 132) / 20 * 20 until end - 100 step 20).maxOf { herDb(it) } - 25f + random.nextFloat() * 4 - 2
        }
        var end = 32L
        var passedLearning = 0
        var passedLearned = 0
        while (end < 3_000) {
            val db = echo(end)
            val v = g.check(db, end)
            if (v.passes) { if (v.learned) passedLearned++ else passedLearning++ }
            g.learn(db, end)
            end += 32
        }
        assertEquals(0, passedLearned, "her echo alone never passes once learned")
        assertTrue(passedLearning <= 3, "and hardly ever before ($passedLearning)")
        val v = g.check(echo(3_200), 3_200)
        assertFalse(v.passes)
        assertTrue(v.learned)
        assertEquals(100.0, v.delayMillis.toDouble(), 20.0, "the delay is learned")
        assertTrue(v.couplingDb in -27f..-20f, "the coupling is learned: ${v.couplingDb}")
        // the person, at a normal level after the canceller, over her echo
        assertTrue(g.check(-22f, 3_232).passes, "the person over her echo passes")
    }

    @Test fun withNothingOfHersPlayingEverythingPasses() {
        val g = EchoGuard()
        assertTrue(g.check(-60f, 1_000).passes)
        g.plays(0, 200) { -20f }
        assertFalse(g.hearsHer(1_000), "her last words are long gone")
        assertTrue(g.check(-60f, 1_000).passes)
        assertNull(g.check(-60f, 1_000).echoDb)
    }

    @Test fun aFlushCutsWhatWasScheduledAfterIt() {
        val g = EchoGuard()
        g.plays(0, 2_000) { -20f }
        g.cutPlayback(500)
        assertTrue(g.hearsHer(600))
        assertFalse(g.hearsHer(1_500), "what was flushed will never play")
    }
}

class BargeInDetectorTest {
    private var now = 0L

    /** Feeds [frames] of 20 ms to [d], each stamped as it is captured; the edges they cause. */
    private fun feed(d: BargeInDetector, frames: Int, frame: (Int) -> ShortArray): List<BargeInDetector.Event> {
        val out = mutableListOf<BargeInDetector.Event>()
        repeat(frames) { i -> now += 20; d.onMic(MicFrame(frame(i), now)) { out += it } }
        return out
    }

    /** Her voice playing on the loudspeaker for [millis] from now, at -12 dBFS. */
    private fun BargeInDetector.herVoice(millis: Int) {
        var t = 0
        while (t < millis) { onPlayback(PlaybackBlock(now + t, 20, -12f)); t += 20 }
    }

    @Test fun herEchoAloneDoesNotInterruptButThePersonOverItDoes() {
        val model = LoudIsSpeech()
        val d = BargeInDetector(SileroVad(model))
        d.herVoice(5_000)
        // 2 s of her echo after the canceller (~30 dB under her), which the detector hears as speech
        val echoOnly = feed(d, 100) { tone(-40f, it) }
        assertTrue(echoOnly.none { it.edge == Edge.Onset }, "her echo alone never stops her")
        assertTrue(model.runs >= 60)
        // the person talks over her: much louder than her predicted echo
        val over = feed(d, 20) { mix(tone(-40f, it), tone(-20f, it + 7)) }
        val onset = over.single { it.edge == Edge.Onset }
        assertTrue(onset.verdict!!.passes)
        assertTrue(onset.at - onset.speechFrom in 150..260, "about 200 ms of speech: ${onset.at - onset.speechFrom}")
        assertEquals(AudioRoute.Loudspeaker, onset.route)
    }

    @Test fun onHeadphonesThereIsNoGuardAndALowerBar() {
        val d = BargeInDetector(SileroVad(LoudIsSpeech()))
        d.route = AudioRoute.Headphones
        d.herVoice(5_000) // plays in the ears: nothing of it reaches the microphone
        val events = feed(d, 10) { tone(-40f, it) }
        val onset = events.single { it.edge == Edge.Onset }
        assertNull(onset.verdict, "no echo guard on headphones")
        assertTrue(onset.at - onset.speechFrom <= 140, "and a quicker onset: ${onset.at - onset.speechFrom}")
    }

    @Test fun silenceAndAQuietRoomAreNotSpeech() {
        val d = BargeInDetector(SileroVad(LoudIsSpeech()))
        assertTrue(feed(d, 100) { tone(null) }.isEmpty())
        assertTrue(feed(d, 100) { tone(-60f, it) }.isEmpty())
    }
}
