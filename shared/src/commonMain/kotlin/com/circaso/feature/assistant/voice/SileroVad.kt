package com.circaso.feature.assistant.voice

import circaso.shared.generated.resources.Res
import com.circaso.core.common.Diagnostics
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Silero VAD v5 (MIT, `files/silero_vad.onnx`, 16 kHz) on this platform's ONNX Runtime: the one platform piece of the
 * voice activity detector. The graph takes `input` [1, 576] (64 samples of context, then a 512-sample window, floats
 * in -1..1), `state` [2, 1, 128] and `sr` (16000); it gives `output` [1, 1] (the chance the window is speech) and
 * `stateN`. Everything around it (framing, context, state) is [SileroVad], in common code.
 */
interface VadModel {
    /** Runs one window: [input] is [SileroVad.InputSize] floats, [state] is read and overwritten with the new state. */
    fun run(input: FloatArray, state: FloatArray): Float

    fun close()
}

/** Opens [VadModel]s on this platform: ONNX Runtime with one session, one thread. Null when it can't. */
fun interface VadEngine {
    fun load(model: ByteArray): VadModel?
}

/** Reads the bundled model once per call and opens it; null (logged) when the model or the runtime is unavailable. */
@OptIn(ExperimentalResourceApi::class)
class VadLoader(
    private val engine: VadEngine,
    /** The model's bytes: the bundled resource; a stand-in in tests. */
    private val modelBytes: suspend () -> ByteArray = { Res.readBytes(ModelFile) },
) {
    suspend fun open(): VadModel? = try {
        engine.load(modelBytes())
    } catch (e: Exception) {
        Diagnostics.note("voice: vad unavailable (${e::class.simpleName})")
        null
    }

    companion object {
        const val ModelFile = "files/silero_vad.onnx"
    }
}

/**
 * Silero VAD's streaming wrapper, as its own Python `OnnxWrapper` does it: 16 kHz samples are cut into 512-sample
 * windows (32 ms), each run with the last 64 samples of the window before it as context and the recurrent state the
 * previous run returned. One instance per stream, used from one thread; [reset] between streams.
 */
class SileroVad(private val model: VadModel) {
    private val input = FloatArray(InputSize)
    private val state = FloatArray(StateSize)
    private var filled = 0

    /**
     * Takes [pcm] (16 kHz mono) and calls [onWindow] with each complete window's speech probability, loudness (dBFS)
     * and where in [pcm] it ended (samples).
     */
    fun accept(pcm: ShortArray, onWindow: (probability: Float, db: Float, endIndex: Int) -> Unit) {
        var at = 0
        while (at < pcm.size) {
            val n = minOf(Window - filled, pcm.size - at)
            for (i in 0 until n) input[Context + filled + i] = pcm[at + i] / 32768f
            filled += n
            at += n
            if (filled == Window) {
                val db = com.circaso.core.platform.AudioDb.of(input, Context, Window)
                val p = model.run(input, state)
                // the window's last 64 samples are the next window's context
                input.copyInto(input, destinationOffset = 0, startIndex = InputSize - Context, endIndex = InputSize)
                filled = 0
                onWindow(p, db, at)
            }
        }
    }

    fun reset() {
        input.fill(0f)
        state.fill(0f)
        filled = 0
    }

    fun close() = model.close()

    companion object {
        const val SampleRate = 16_000
        const val Window = 512
        const val Context = 64
        const val InputSize = Context + Window
        const val StateSize = 2 * 1 * 128
        const val WindowMillis = Window * 1_000 / SampleRate // 32
    }
}
