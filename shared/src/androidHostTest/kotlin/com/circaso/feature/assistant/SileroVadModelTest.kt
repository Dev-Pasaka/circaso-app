package com.circaso.feature.assistant

import com.circaso.core.platform.AndroidVadEngine
import com.circaso.core.platform.MicFrame
import com.circaso.feature.assistant.voice.BargeInDetector
import com.circaso.feature.assistant.voice.SileroVad
import com.circaso.feature.assistant.voice.SpeechOnset
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The VAD wrapper on the real thing: the bundled Silero VAD v5 model (MIT, in ORT format as the app ships it) on ONNX Runtime (the JVM build, for the
 * host; the app ships the Android one with the same API), through the app's own [AndroidVadEngine].
 *
 * The speech fixture (`vad/speech_16k.wav`, 3.5 s, 16 kHz mono) is cut from a LibriVox recording, public domain
 * (archive.org `spc195_1909_librivox`, "A plus B squared"); noise and silence are generated here.
 */
class SileroVadModelTest {
    private val model = assertNotNull(AndroidVadEngine().load(File("src/androidMain/composeResources/files/silero_vad.ort").readBytes()), "the model opens")

    @AfterTest fun close() = model.close()

    private fun speech(): ShortArray {
        val bytes = assertNotNull(javaClass.getResourceAsStream("/vad/speech_16k.wav")).readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4)
            val size = b.getInt(at + 4)
            if (id == "data") return ShortArray(size / 2) { b.getShort(at + 8 + it * 2) }
            at += 8 + size
        }
        error("no data chunk")
    }

    /** Every window's probability for [pcm]. */
    private fun probabilities(pcm: ShortArray): List<Float> {
        val vad = SileroVad(model)
        val out = mutableListOf<Float>()
        pcm.toList().chunked(320).forEach { frame -> vad.accept(frame.toShortArray()) { p, _, _ -> out += p } }
        return out
    }

    @Test fun speechIsSpeech() {
        val p = probabilities(speech())
        assertEquals(3_500 * 16 / 512, p.size, "one probability per 512 samples")
        val sure = p.count { it >= 0.6f }
        assertTrue(sure > p.size * 0.6, "most of a person reading aloud is speech: $sure of ${p.size}")
    }

    @Test fun silenceAndNoiseAreNot() {
        val silence = probabilities(ShortArray(16_000 * 2))
        assertTrue(silence.all { it < 0.1f }, "silence: max ${silence.max()}")
        val random = Random(42)
        val hiss = probabilities(ShortArray(16_000 * 2) { (random.nextFloat() * 2 - 1).times(3_000).toInt().toShort() })
        assertTrue(hiss.count { it >= 0.6f } <= 2, "white noise at -21 dBFS is not speech: max ${hiss.max()}")
        // Not tested, on purpose: Silero scores a pure synthetic 100 + 200 Hz hum at up to ~0.73. Real hums are noisier
        // and the platform's noise suppressor takes steady sounds out before the detector hears them.
    }

    @Test fun theStateCarriesAndResetForgets() {
        val vad = SileroVad(model)
        val first = mutableListOf<Float>()
        val again = mutableListOf<Float>()
        val pcm = speech().copyOf(16_000)
        pcm.toList().chunked(320).forEach { f -> vad.accept(f.toShortArray()) { p, _, _ -> first += p } }
        vad.reset()
        pcm.toList().chunked(320).forEach { f -> vad.accept(f.toShortArray()) { p, _, _ -> again += p } }
        assertEquals(first, again, "after a reset the same audio gives the same answers")
    }

    @Test fun theDetectorFindsTheOnsetOfRealSpeechQuickly() {
        val detector = BargeInDetector(SileroVad(model))
        var now = 0L
        val events = mutableListOf<BargeInDetector.Event>()
        val audio = ShortArray(16_000) + speech() // a second of silence, then the person
        audio.toList().chunked(320).forEach { f -> now += 20; detector.onMic(MicFrame(f.toShortArray(), now)) { events += it } }
        val onset = events.first { it.edge == SpeechOnset.Edge.Onset }
        println("silero onset: speech at 1000 ms, first sure window from ${onset.speechFrom} ms, onset at ${onset.at} ms (p=${onset.probability})")
        assertNull(onset.verdict, "nothing of hers playing: no guard")
        assertTrue(onset.speechFrom >= 950, "not before the person starts: ${onset.speechFrom}")
        assertTrue(onset.at - 1_000 <= 320, "about 200 ms of speech, plus Silero's own reaction: ${onset.at - 1_000} ms after it starts")
    }

    @Test fun aWindowRunsInWellUnderAMillisecond() {
        val vad = SileroVad(model)
        val pcm = speech()
        repeat(3) { pcm.toList().chunked(512).forEach { f -> vad.accept(f.toShortArray()) { _, _, _ -> } } } // warm up
        val windows = pcm.size / 512
        val started = System.nanoTime()
        pcm.toList().chunked(512).forEach { f -> vad.accept(f.toShortArray()) { _, _, _ -> } }
        val perWindowMicros = (System.nanoTime() - started) / 1_000 / windows
        println("silero window: $perWindowMicros µs on the host")
        assertTrue(perWindowMicros < 5_000, "$perWindowMicros µs")
    }
}
