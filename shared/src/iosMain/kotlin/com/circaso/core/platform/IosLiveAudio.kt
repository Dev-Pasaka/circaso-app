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

    /**
     * The echo-cancelled microphone for the voice activity detector: each 20 ms frame (320 samples, 16 kHz, 16-bit
     * little-endian) as it is captured, muted or not; null stops it.
     */
    fun setMicrophoneFrames(onFrame: ((NSData) -> Unit)?)

    /**
     * Her voice as it is scheduled: [onBlock] with the dBFS of each 20 ms of it (after the volume), when it will be
     * heard (milliseconds from now) and how long it lasts; [onRoute] with where the sound goes now (0 the loudspeaker,
     * 1 the receiver, 2 headphones or a headset: wired, USB or Bluetooth), at once and on every route change.
     */
    fun observeSpeaker(onBlock: (Float, Int, Int) -> Unit, onRoute: (Int) -> Unit)

    fun startSpeaker(sampleRate: Int, onPlaying: (Boolean) -> Unit, onLevel: (Float) -> Unit): Boolean
    fun playSamples(pcm16: NSData)
    fun flushSpeaker()
    /** Her volume 0..1 on the player, at once; `startSpeaker` begins at 1. */
    fun setSpeakerVolume(volume: Float)
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

    private val _frames = MutableSharedFlow<MicFrame>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val frames: Flow<MicFrame> = _frames

    override suspend fun start(): LiveMicStart {
        val a = api ?: return LiveMicStart.Unsupported
        val code = suspendCancellableCoroutine { cont ->
            a.startMicrophone(
                onPacket = { data -> _packets.tryEmit(data.toByteArray()) },
                onLevel = { _level.value = it },
                completion = { if (cont.isActive) cont.resume(it) },
            )
        }
        if (code == 0) {
            a.setMicrophoneMuted(muted)
            a.setMicrophoneFrames { data -> if (_frames.subscriptionCount.value > 0) _frames.tryEmit(MicFrame(data.toShortArray(), MonoClock.now())) }
        }
        return when (code) { 0 -> LiveMicStart.Started; 1 -> LiveMicStart.PermissionDenied; 2 -> LiveMicStart.Unsupported; else -> LiveMicStart.Failed }
    }

    override fun stop() {
        api?.setMicrophoneFrames(null)
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
    private val _blocks = MutableSharedFlow<PlaybackBlock>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val blocks: Flow<PlaybackBlock> = _blocks
    private val _route = MutableStateFlow(AudioRoute.Loudspeaker)
    override val route: StateFlow<AudioRoute> = _route.asStateFlow()

    override suspend fun start(sampleRate: Int) {
        started = api?.startSpeaker(sampleRate, onPlaying = { _playing.value = it }, onLevel = { _level.value = it }) == true
        if (started) api?.observeSpeaker(
            onBlock = { db, inMillis, millis -> if (_blocks.subscriptionCount.value > 0) _blocks.tryEmit(PlaybackBlock(MonoClock.now() + inMillis, millis, db)) },
            onRoute = { _route.value = when (it) { 2 -> AudioRoute.Headphones; 1 -> AudioRoute.Earpiece; else -> AudioRoute.Loudspeaker } },
        )
    }

    override fun play(pcm: ShortArray) {
        if (!started || pcm.isEmpty()) return
        api?.playSamples(pcm.toNSData())
    }

    override fun setVolume(volume: Float) {
        if (started) api?.setSpeakerVolume(volume.coerceIn(0f, 1f))
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

/** 16-bit little-endian samples from Swift (iOS is little-endian, so a straight copy). */
private fun NSData.toShortArray(): ShortArray {
    val out = ShortArray((length / 2u).toInt())
    if (out.isNotEmpty()) out.usePinned { platform.posix.memcpy(it.addressOf(0), bytes, (out.size * 2).convert()) }
    return out
}

private fun ShortArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = (size * 2).convert()) }
