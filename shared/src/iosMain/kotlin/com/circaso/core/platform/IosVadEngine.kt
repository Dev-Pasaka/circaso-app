@file:OptIn(ExperimentalForeignApi::class)

package com.circaso.core.platform

import com.circaso.core.common.Diagnostics
import com.circaso.feature.assistant.voice.SileroVad
import com.circaso.feature.assistant.voice.VadEngine
import com.circaso.feature.assistant.voice.VadModel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * Silero VAD on iOS runs on ONNX Runtime's Objective-C API (the `onnxruntime-objc` pod), which is Swift's to drive:
 * the app implements this in `iosApp/iosApp/VadImpl.swift` and installs it at launch like the other bridges. Floats
 * cross as `NSData` (Float32, little-endian).
 */
interface VadBridgeApi {
    /** One session on [model] (the ONNX file's bytes), one thread; null when ONNX Runtime isn't in this build or it fails. */
    fun open(model: NSData): VadSessionApi?
}

interface VadSessionApi {
    /**
     * One window: [input] is 576 floats (64 of context, then 512 samples), [state] 256. Returns 257 floats, the
     * speech probability then the new state, or null if the run failed.
     */
    fun run(input: NSData, state: NSData): NSData?
    fun close()
}

object VadBridge {
    var api: VadBridgeApi? = null
}

class IosVadEngine : VadEngine {
    override fun load(model: ByteArray): VadModel? {
        val session = VadBridge.api?.open(model.usePinned { NSData.create(bytes = it.addressOf(0), length = model.size.convert()) })
        if (session == null) Diagnostics.note("voice: vad not available in this build")
        return session?.let { IosVadModel(it) }
    }
}

private class IosVadModel(private val session: VadSessionApi) : VadModel {
    private val answer = FloatArray(1 + SileroVad.StateSize)

    override fun run(input: FloatArray, state: FloatArray): Float {
        val out = session.run(input.toNSData(), state.toNSData()) ?: return 0f
        if (out.length.toInt() != answer.size * 4) return 0f
        answer.usePinned { platform.posix.memcpy(it.addressOf(0), out.bytes, out.length) }
        answer.copyInto(state, destinationOffset = 0, startIndex = 1)
        return answer[0]
    }

    override fun close() = session.close()

    private fun FloatArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = (size * 4).convert()) }
}
