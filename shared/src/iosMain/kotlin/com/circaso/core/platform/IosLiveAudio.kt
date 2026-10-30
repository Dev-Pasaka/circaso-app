@file:OptIn(ExperimentalForeignApi::class)

package com.circaso.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.coroutines.resume

/**
 * Live audio on iOS runs on `AVAudioEngine` with Apple's Opus encoder, which is Swift's to drive: the app implements
 * this in `iosApp/iosApp/LiveAudioImpl.swift` and installs it at launch like the other bridges. Bytes cross as
 * `NSData` (Swift `Data`); 16-bit samples are little-endian mono.
 */
interface LiveAudioBridgeApi {
    /** Whether this phone can encode Opus (every iPhone that runs the app can; this guards the converter's creation). */
    val opusSupported: Boolean

    /** Opens the microphone and starts encoding. [completion] gets 0 started, 1 permission denied, 2 unsupported, 3 failed. */
    fun startMicrophone(onPacket: (NSData) -> Unit, onLevel: (Float) -> Unit, completion: (Int) -> Unit)
    fun setMicrophoneMuted(muted: Boolean)
    fun stopMicrophone()

    fun startSpeaker(sampleRate: Int, onPlaying: (Boolean) -> Unit, onLevel: (Float) -> Unit): Boolean
    fun playSamples(pcm16: NSData)
    fun flushSpeaker()
    fun stopSpeaker()
}

object LiveAudioBridge {
    var api: LiveAudioBridgeApi? = null
}

class IosLiveMicrophone : LiveMicrophone {
    private val api get() = LiveAudioBridge.api
    override val supported: Boolean get() = api?.opusSupported == true

    private val _packets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val packets: Flow<ByteArray> = _packets
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    override var muted: Boolean = false
        set(value) { field = value; api?.setMicrophoneMuted(value) }

    override suspend fun start(): LiveMicStart {
        val a = api ?: return LiveMicStart.Unsupported
        val code = suspendCancellableCoroutine { cont ->
            a.startMicrophone(
                onPacket = { data -> _packets.tryEmit(data.toByteArray()) },
                onLevel = { _level.value = it },
                completion = { if (cont.isActive) cont.resume(it) },
            )
        }
        if (code == 0) a.setMicrophoneMuted(muted)
        return when (code) { 0 -> LiveMicStart.Started; 1 -> LiveMicStart.PermissionDenied; 2 -> LiveMicStart.Unsupported; else -> LiveMicStart.Failed }
    }

    override fun stop() {
        api?.stopMicrophone()
        _level.value = 0f
    }
}

class IosLiveSpeaker : LiveSpeaker {
    private val api get() = LiveAudioBridge.api
    private val _playing = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()
    private var started = false

    override suspend fun start(sampleRate: Int) {
        started = api?.startSpeaker(sampleRate, onPlaying = { _playing.value = it }, onLevel = { _level.value = it }) == true
    }

    override fun play(pcm: ShortArray) {
        if (!started || pcm.isEmpty()) return
        api?.playSamples(pcm.toNSData())
    }

    override fun flush() {
        api?.flushSpeaker()
        _level.value = 0f
    }

    override fun stop() {
        if (!started) return
        started = false
        api?.stopSpeaker()
        _playing.value = false
        _level.value = 0f
    }
}

private fun ShortArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = (size * 2).convert()) }
