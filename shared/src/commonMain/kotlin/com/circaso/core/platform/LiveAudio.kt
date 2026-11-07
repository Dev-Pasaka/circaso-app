package com.circaso.core.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Live voice with Asha (guide A11) streams the microphone as Opus packets and plays her voice as it arrives. These
 * two seams are the platform's part: capture, encode and play. Everything about the socket, the turns and the
 * screen is common code.
 */

/** Why the live microphone could not start. [Unsupported] means this phone cannot encode Opus: use voice clips instead. */
enum class LiveMicStart { Started, PermissionDenied, Unsupported, Failed }

/** Opus from the microphone: 16 kHz, mono, one packet (20 ms) per element, about 24 kbit/s, as the server wants it. */
interface LiveMicrophone {
    /** Whether this phone can encode Opus live at all; false means the screen offers voice clips instead. */
    val supported: Boolean

    /** Opens the microphone (asking permission the first time) and starts encoding; [packets] delivers from then on. */
    suspend fun start(): LiveMicStart

    /** Encoded packets, in order. Nothing arrives while [muted]. */
    val packets: Flow<ByteArray>

    /** How loud the person is right now, 0..1, a few times a second: drives the animation. */
    val level: StateFlow<Float>

    /** Keeps the microphone open (so the permission and the audio route stay as they are) but sends nothing. */
    var muted: Boolean

    /**
     * The same microphone after the platform's echo canceller, as raw frames for the voice activity detector: 16 kHz
     * mono, 20 ms each, stamped on [MonoClock] when captured. Emitted on the capture thread; muted or not (the caller
     * decides). Empty on a platform that can't give them: barge-in then falls back to the energy detector.
     */
    val frames: Flow<MicFrame> get() = emptyFlow()

    fun stop()
}

/** 320 samples (20 ms at 16 kHz) of the echo-cancelled microphone, captured at [atMillis] on [MonoClock]. */
class MicFrame(val pcm: ShortArray, val atMillis: Long)

/**
 * A slice of Asha's voice on its way out of the speaker: it starts playing at [atMillis] on [MonoClock] (the platform's
 * best estimate, from its own playback position), lasts [millis], at [db] dBFS (the samples as sent to the speaker,
 * after the playback gain). The echo guard's reference: the app knows exactly what it is playing.
 */
class PlaybackBlock(val atMillis: Long, val millis: Int, val db: Float)

/** Where the conversation's sound goes, which decides how carefully her echo must be told from the person. */
enum class AudioRoute {
    /** The phone's own loudspeaker: her voice comes back into the microphone; the echo guard is on. */
    Loudspeaker,
    /** Held to the ear: little comes back, but the guard stays on (it learns how little). */
    Earpiece,
    /** Wired, USB or Bluetooth headphones or a headset: nothing of her reaches the microphone; no guard, a lower bar. */
    Headphones,
}

/** One monotonic clock for the microphone's frames and the speaker's blocks, so the echo guard can line them up. */
object MonoClock {
    private val origin = kotlin.time.TimeSource.Monotonic.markNow()
    fun now(): Long = origin.elapsedNow().inWholeMilliseconds
}

/** Plays Asha's voice as it streams in: 16-bit PCM, mono, at the rate the session announced. */
interface LiveSpeaker {
    /** Prepares output at [sampleRate] (8 000 for mulaw from the server) and takes the audio route for a conversation. */
    suspend fun start(sampleRate: Int)

    /** Queues decoded samples to play after what is already queued. */
    fun play(pcm: ShortArray)

    /** Barge-in: drops everything queued and stops the sound at once. */
    fun flush()

    /**
     * Her volume, 0..1, applied at once to what is playing and what follows (the caller ramps it in small steps).
     * Ducking while the person may be talking over her. [start] begins at 1.
     */
    fun setVolume(volume: Float)

    /** True while there is sound queued or playing. */
    val playing: StateFlow<Boolean>

    /** How loud Asha is right now, 0..1: drives the animation. */
    val level: StateFlow<Float>

    /** Her voice as it is played ([PlaybackBlock]), about every 20 ms while she speaks; emitted on the audio thread. */
    val blocks: Flow<PlaybackBlock> get() = emptyFlow()

    /** Where the sound goes now; follows headphones being plugged in or connected mid-call. */
    val route: StateFlow<AudioRoute> get() = LoudspeakerOnly

    fun stop()
}

private val LoudspeakerOnly: StateFlow<AudioRoute> = kotlinx.coroutines.flow.MutableStateFlow(AudioRoute.Loudspeaker)

/** G.711 µ-law, what the server's voice arrives as (8 kHz, one byte a sample). */
object Mulaw {
    private const val Bias = 0x84

    fun decode(bytes: ByteArray): ShortArray = ShortArray(bytes.size) { i -> decodeSample(bytes[i].toInt() and 0xFF) }

    /** The standard expansion (ITU-T G.711): sign, 3 exponent bits, 4 mantissa bits, bias removed. */
    fun decodeSample(byte: Int): Short {
        val u = byte.inv() and 0xFF
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0F
        var sample = ((mantissa shl 3) + Bias) shl exponent
        sample -= Bias
        return (if (sign != 0) -sample else sample).toShort()
    }
}

/** Energy of samples in dBFS (full-scale sine about -3), floored at -100 for silence. */
object AudioDb {
    fun of(pcm: ShortArray, from: Int = 0, count: Int = pcm.size - from): Float {
        if (count <= 0) return -100f
        var sum = 0.0
        for (i in from until from + count) { val s = pcm[i].toDouble(); sum += s * s }
        return toDb(kotlin.math.sqrt(sum / count) / 32768.0)
    }

    fun of(samples: FloatArray, from: Int, count: Int): Float {
        if (count <= 0) return -100f
        var sum = 0.0
        for (i in from until from + count) { val s = samples[i].toDouble(); sum += s * s }
        return toDb(kotlin.math.sqrt(sum / count))
    }

    private fun toDb(rms: Double): Float = (20 * kotlin.math.log10(rms.coerceAtLeast(1e-5))).toFloat()
}

/** Loudness of a block of samples as the animation wants it: 0 for silence, about 1 for a loud voice. */
object AudioLevel {
    fun of(pcm: ShortArray, from: Int = 0, count: Int = pcm.size - from): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in from until from + count) { val s = pcm[i].toDouble(); sum += s * s }
        val rms = kotlin.math.sqrt(sum / count) / 32768.0
        // Speech sits around -30..-10 dBFS; map that band onto 0..1 so a normal voice fills the animation.
        val db = 20 * kotlin.math.log10(rms.coerceAtLeast(1e-6))
        return ((db + 50) / 40).coerceIn(0.0, 1.0).toFloat()
    }
}
