package com.circaso.feature.assistant

import com.circaso.core.platform.AndroidVadEngine
import com.circaso.core.platform.AudioRoute
import com.circaso.core.platform.MicFrame
import com.circaso.feature.assistant.voice.BargeInDetector
import com.circaso.feature.assistant.voice.SileroVad
import com.circaso.feature.assistant.voice.VadModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Android ships Silero in ORT format (`silero_vad.ort`) on a reduced ONNX Runtime (`third_party/onnxruntime-minimal`),
 * where it used to ship the published `silero_vad.onnx` on the full runtime. This holds the two to the same answers:
 * the original model opened the way the app used to open it (every graph optimisation, at load) against the converted
 * one (optimised once, offline; ONNX Runtime applies no optimisation to an ORT-format model at load, full build or
 * reduced, so the host here runs the very graph the phone runs). Per window the probabilities agree within [Tolerance],
 * and the barge-in detector makes the same calls at the same moments, on both routes.
 */
class SileroVadOrtFormatTest {
    private val engine = AndroidVadEngine()
    private val original = assertNotNull(engine.load(File("src/iosMain/composeResources/files/silero_vad.onnx").readBytes()), "the ONNX model opens")
    private val shipped = assertNotNull(engine.load(File("src/androidMain/composeResources/files/silero_vad.ort").readBytes()), "the ORT model opens")

    @AfterTest fun close() {
        original.close()
        shipped.close()
    }

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

    private fun clip(v: Float) = v.coerceIn(-32768f, 32767f).toInt().toShort()

    /** Named test audio: the person reading, quieter and in noise, and the things that must stay not-speech. */
    private fun clips(): Map<String, ShortArray> {
        val speech = speech()
        val random = Random(7)
        fun noisy(gain: Float, noise: Float) = ShortArray(speech.size) { clip(speech[it] * gain + (random.nextFloat() * 2 - 1) * noise) }
        return mapOf(
            "speech" to speech,
            "speech -20 dB" to ShortArray(speech.size) { clip(speech[it] * 0.1f) },
            "speech + light hiss" to noisy(1f, 1_000f),
            "speech + heavy hiss" to noisy(1f, 6_000f),
            "quiet speech + hiss" to noisy(0.2f, 2_000f),
            "silence" to ShortArray(16_000 * 2),
            "hiss" to ShortArray(16_000 * 2) { clip((random.nextFloat() * 2 - 1) * 3_000f) },
            "hum" to ShortArray(16_000 * 2) { clip((sin(2 * PI * 100 * it / 16_000) * 4_000 + sin(2 * PI * 200 * it / 16_000) * 2_000).toFloat()) },
            "silence, speech, silence" to (ShortArray(16_000) + speech + ShortArray(16_000)),
        )
    }

    private fun probabilities(model: VadModel, pcm: ShortArray): List<Float> {
        val vad = SileroVad(model)
        val out = mutableListOf<Float>()
        pcm.toList().chunked(320).forEach { f -> vad.accept(f.toShortArray()) { p, _, _ -> out += p } }
        return out
    }

    private fun events(model: VadModel, pcm: ShortArray, route: AudioRoute): List<Triple<Any, Long, Long>> {
        val detector = BargeInDetector(SileroVad(model)).also { it.route = route }
        val out = mutableListOf<Triple<Any, Long, Long>>()
        var now = 0L
        pcm.toList().chunked(320).forEach { f -> now += 20; detector.onMic(MicFrame(f.toShortArray(), now)) { out += Triple(it.edge, it.at, it.speechFrom) } }
        return out
    }

    @Test fun theProbabilitiesAgreeWindowByWindow() {
        var worst = 0f
        clips().forEach { (name, pcm) ->
            val a = probabilities(original, pcm)
            val b = probabilities(shipped, pcm)
            assertEquals(a.size, b.size, name)
            val diff = a.indices.maxOf { abs(a[it] - b[it]) }
            worst = maxOf(worst, diff)
            println("ort format: $name, ${a.size} windows, max |Δp| = $diff")
            assertTrue(diff <= Tolerance, "$name: max |Δp| $diff")
            // the same speech / not-speech call at the usual bars, wherever a window isn't sitting on the bar itself
            for (bar in floatArrayOf(0.35f, 0.5f, 0.6f)) {
                a.indices.filter { abs(a[it] - bar) > Tolerance }.forEach { assertEquals(a[it] >= bar, b[it] >= bar, "$name window $it at $bar") }
            }
        }
        println("ort format: worst |Δp| over every clip = $worst")
    }

    @Test fun theDetectorMakesTheSameCallsAtTheSameMoments() {
        clips().forEach { (name, pcm) ->
            for (route in listOf(AudioRoute.Loudspeaker, AudioRoute.Headphones)) {
                val a = events(original, pcm, route)
                assertEquals(a, events(shipped, pcm, route), "$name on $route")
                println("ort format: $name on $route, ${a.size} edges, identical")
            }
        }
    }

    private companion object {
        /** Float rounding between graph rewrites done at load and offline; three orders below any bar we use. */
        const val Tolerance = 1e-4f
    }
}
