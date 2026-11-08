package com.circaso.core.platform

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.circaso.core.common.Diagnostics
import com.circaso.feature.assistant.voice.SileroVad
import com.circaso.feature.assistant.voice.VadEngine
import com.circaso.feature.assistant.voice.VadModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Silero VAD on ONNX Runtime for Android: one session per call screen, one thread, built from the model's bytes. A
 * window takes well under a millisecond on a phone.
 *
 * The runtime is the reduced build in `third_party/onnxruntime-minimal` (only Silero's operators, ORT-format models
 * only), so the model is `silero_vad.ort`, its graph optimised once, offline. ONNX Runtime applies no optimisation to
 * an ORT-format model at load, so the level set here changes nothing, and the host tests (full `onnxruntime-jvm`, the
 * same model through this same class) run the very graph the phone runs.
 */
class AndroidVadEngine : VadEngine {
    override fun load(model: ByteArray): VadModel? = try {
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        OrtVadModel(env, env.createSession(model, options)).also { options.close() }
    } catch (e: Throwable) {
        // a missing native library is an Error, not an Exception: the call goes on with the energy detector
        Diagnostics.note("voice: vad failed to load (${e::class.simpleName})")
        null
    }
}

/** One open Silero session. The input and state buffers are direct and reused, so a window copies 3 KB and allocates two tensor handles. */
internal class OrtVadModel(private val env: OrtEnvironment, private val session: OrtSession) : VadModel {
    private val input: FloatBuffer = direct(SileroVad.InputSize)
    private val state: FloatBuffer = direct(SileroVad.StateSize)
    private val sampleRate = OnnxTensor.createTensor(env, SileroVad.SampleRate.toLong())

    override fun run(input: FloatArray, state: FloatArray): Float {
        this.input.rewind(); this.input.put(input); this.input.rewind()
        this.state.rewind(); this.state.put(state); this.state.rewind()
        val x = OnnxTensor.createTensor(env, this.input, InputShape)
        val h = OnnxTensor.createTensor(env, this.state, StateShape)
        try {
            session.run(mapOf("input" to x, "state" to h, "sr" to sampleRate)).use { result ->
                val p = (result.get(0) as OnnxTensor).floatBuffer.get(0)
                (result.get(1) as OnnxTensor).floatBuffer.get(state)
                return p
            }
        } finally {
            x.close()
            h.close()
        }
    }

    override fun close() {
        runCatching { sampleRate.close() }
        runCatching { session.close() }
    }

    private companion object {
        val InputShape = longArrayOf(1, SileroVad.InputSize.toLong())
        val StateShape = longArrayOf(2, 1, 128)
        fun direct(floats: Int): FloatBuffer = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}
