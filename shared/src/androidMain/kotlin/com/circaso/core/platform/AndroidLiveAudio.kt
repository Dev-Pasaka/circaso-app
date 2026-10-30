package com.circaso.core.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.core.content.ContextCompat
import com.circaso.core.common.Diagnostics
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

private const val MicRate = 16_000
private const val FrameSamples = MicRate / 50 // 20 ms, one Opus packet
private const val OpusBitRate = 24_000
private const val OpusMime = MediaFormat.MIMETYPE_AUDIO_OPUS

/**
 * The microphone through Android's own Opus encoder (`MediaCodec`, on every phone from Android 10). Capture uses the
 * voice-call source so the platform's echo canceller runs: Asha's voice from the speaker must not come back as the
 * person talking over her. Phones without the encoder report [supported] false and keep the clip flow (A3).
 */
class AndroidLiveMicrophone(private val context: Context) : LiveMicrophone {
    override val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasOpusEncoder()

    private val _packets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val packets: Flow<ByteArray> = _packets
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()
    @Volatile override var muted: Boolean = false

    private var worker: Thread? = null
    private val running = AtomicBoolean(false)

    override suspend fun start(): LiveMicStart {
        if (!supported) return LiveMicStart.Unsupported
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ||
            PermissionBridge.request(Manifest.permission.RECORD_AUDIO)
        if (!granted) return LiveMicStart.PermissionDenied
        stop()
        val minBuffer = AudioRecord.getMinBufferSize(MicRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return LiveMicStart.Failed
        // The call mode must be on before the recorder exists, or the HAL may not run its echo canceller on it.
        VoiceRoute.acquire(context)
        val record = try {
            @Suppress("MissingPermission")
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MicRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FrameSamples * 2 * 8))
        } catch (e: Exception) { VoiceRoute.release(context); return LiveMicStart.Failed }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); VoiceRoute.release(context); return LiveMicStart.Failed }
        val codec = try {
            MediaCodec.createEncoderByType(OpusMime).apply {
                val format = MediaFormat.createAudioFormat(OpusMime, MicRate, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, OpusBitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FrameSamples * 2)
                }
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } catch (e: Exception) { record.release(); VoiceRoute.release(context); return LiveMicStart.Failed }
        Diagnostics.note("voice: mic aec=${AcousticEchoCanceler.isAvailable()} ns=${NoiseSuppressor.isAvailable()}")
        val effects = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true } }.getOrNull() else null,
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true } }.getOrNull() else null,
        )
        try {
            codec.start()
            record.startRecording()
        } catch (e: Exception) {
            effects.forEach { runCatching { it.release() } }
            runCatching { codec.release() }; record.release(); VoiceRoute.release(context)
            return LiveMicStart.Failed
        }
        running.set(true)
        worker = Thread({
            try { pump(record, codec) } catch (e: Exception) { Diagnostics.note("voice: microphone stopped (${e::class.simpleName})") } finally {
                runCatching { record.stop() }; record.release()
                runCatching { codec.stop() }; runCatching { codec.release() }
                effects.forEach { runCatching { it.release() } }
                VoiceRoute.release(context)
                _level.value = 0f
            }
        }, "asha-mic").also { it.start() }
        return LiveMicStart.Started
    }

    /** Reads 20 ms at a time, feeds the encoder and hands out each packet. One thread, synchronous codec API. */
    private fun pump(record: AudioRecord, codec: MediaCodec) {
        val pcm = ShortArray(FrameSamples)
        val info = MediaCodec.BufferInfo()
        var presentationUs = 0L
        var sinceLevel = 0
        while (running.get()) {
            var read = 0
            while (read < FrameSamples && running.get()) {
                val n = record.read(pcm, read, FrameSamples - read)
                if (n < 0) return
                read += n
            }
            if (!running.get()) return
            if (++sinceLevel >= 3) { _level.value = AudioLevel.of(pcm); sinceLevel = 0 } // ~16 updates a second is plenty for the halo
            val inIndex = codec.dequeueInputBuffer(20_000)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex) ?: continue
                buffer.clear()
                buffer.asShortBuffer().put(pcm)
                codec.queueInputBuffer(inIndex, 0, FrameSamples * 2, presentationUs, 0)
                presentationUs += 20_000
            }
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(info, 0)
                if (outIndex < 0) break
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!isConfig && info.size > 0 && !muted) {
                    val out = codec.getOutputBuffer(outIndex)
                    if (out != null) {
                        val packet = ByteArray(info.size)
                        out.position(info.offset); out.get(packet)
                        _packets.tryEmit(packet)
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
            }
        }
    }

    override fun stop() {
        running.set(false)
        worker = null
    }

    private fun hasOpusEncoder(): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { it.isEncoder && it.supportedTypes.any { t -> t.equals(OpusMime, ignoreCase = true) } }
    } catch (e: Exception) { false }
}

/** Plays Asha's voice through an `AudioTrack` in streaming mode from a small queue, so the socket reader never blocks on the speaker. */
class AndroidLiveSpeaker(private val context: Context) : LiveSpeaker {
    private val _playing = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    private var track: AudioTrack? = null
    private val queue = ArrayDeque<ShortArray>()
    private val lock = Object()
    private var generation = 0
    private var worker: Thread? = null

    override suspend fun start(sampleRate: Int) {
        stop()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        VoiceRoute.acquire(context) // before the track exists, so it is born on the call path with the loudspeaker
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * 2 / 5)) // 200 ms of room
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); VoiceRoute.release(context); Diagnostics.note("voice: speaker failed"); return }
        track = t
        t.play()
        val gen = ++generation
        worker = Thread({ drain(t, gen, sampleRate) }, "asha-voice").also { it.start() }
    }

    private fun drain(t: AudioTrack, gen: Int, sampleRate: Int) {
        while (true) {
            val chunk: ShortArray
            synchronized(lock) {
                while (queue.isEmpty() && gen == generation) {
                    _playing.value = false
                    _level.value = 0f
                    lock.wait()
                }
                if (gen != generation) return
                chunk = queue.removeFirst()
            }
            _playing.value = true
            // The sound is written a little ahead of being heard: report the level per 20 ms as it is written.
            var at = 0
            val step = sampleRate / 50
            while (at < chunk.size && gen == generation) {
                val n = minOf(step, chunk.size - at)
                _level.value = AudioLevel.of(chunk, at, n)
                val written = t.write(chunk, at, n, AudioTrack.WRITE_BLOCKING)
                if (written < 0) return
                at += n
            }
        }
    }

    override fun play(pcm: ShortArray) {
        if (track == null || pcm.isEmpty()) return
        synchronized(lock) { queue.addLast(pcm); _playing.value = true; lock.notifyAll() }
    }

    override fun flush() {
        val t = track ?: return
        synchronized(lock) { queue.clear(); lock.notifyAll() }
        runCatching { t.pause(); t.flush(); t.play() }
        _level.value = 0f
    }

    override fun stop() {
        val t = track ?: return
        track = null
        synchronized(lock) { generation++; queue.clear(); lock.notifyAll() }
        worker = null
        runCatching { t.pause(); t.flush(); t.release() }
        VoiceRoute.release(context)
        _playing.value = false
        _level.value = 0f
    }
}

/**
 * Puts the phone in a call: the voice-communication mode (which turns the echo canceller on) with the sound on the
 * loudspeaker, and gives everything back when the last user lets go. Shared by the microphone and the speaker.
 */
private object VoiceRoute {
    private var users = 0
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false

    @Synchronized fun acquire(context: Context) {
        if (users++ > 0) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        previousMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            Diagnostics.note("voice: route speaker=${speaker?.let { am.setCommunicationDevice(it) }}")
        } else {
            @Suppress("DEPRECATION")
            previousSpeaker = am.isSpeakerphoneOn
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        }
    }

    @Synchronized fun release(context: Context) {
        if (users == 0 || --users > 0) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice() else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = previousSpeaker
        }
        am.mode = previousMode
    }
}
