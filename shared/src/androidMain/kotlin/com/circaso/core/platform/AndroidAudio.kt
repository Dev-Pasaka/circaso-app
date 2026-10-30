package com.circaso.core.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Records AAC in an MP4 container at a low bit rate (about 4 KB a second, so 60 seconds is far under the 3 MB limit). */
class AndroidAudioRecorder(private val context: Context) : AudioRecorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    override suspend fun start(): RecordingStart {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ||
            PermissionBridge.request(Manifest.permission.RECORD_AUDIO)
        if (!granted) return RecordingStart.PermissionDenied
        return withContext(Dispatchers.IO) {
            try {
                cancelNow()
                val out = File.createTempFile("voice", ".m4a", context.cacheDir)
                @Suppress("DEPRECATION")
                val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(16_000)
                r.setAudioEncodingBitRate(32_000)
                r.setOutputFile(out.absolutePath)
                r.prepare()
                r.start()
                recorder = r; file = out; startedAt = SystemClock.elapsedRealtime()
                RecordingStart.Started
            } catch (e: Exception) {
                cancelNow()
                RecordingStart.Failed
            }
        }
    }

    override suspend fun stop(): Recording? = withContext(Dispatchers.IO) {
        val r = recorder ?: return@withContext null
        val out = file
        val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(1)
        recorder = null; file = null
        try { r.stop() } catch (e: RuntimeException) { r.release(); out?.delete(); return@withContext null } // stopped too soon: nothing was captured
        r.release()
        val bytes = out?.readBytes()
        out?.delete()
        if (bytes == null || bytes.isEmpty()) null else Recording(bytes, "audio/mp4", seconds)
    }

    override suspend fun cancel() = withContext(Dispatchers.IO) { cancelNow() }

    private fun cancelNow() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        file?.delete()
        recorder = null; file = null
    }
}

class AndroidAudioPlayer(private val context: Context) : AudioPlayer {
    private var player: MediaPlayer? = null
    private var finished: CompletableDeferred<Unit>? = null

    override suspend fun play(bytes: ByteArray, contentType: String) {
        stop()
        val done = CompletableDeferred<Unit>().also { finished = it }
        val temp = withContext(Dispatchers.IO) { File.createTempFile("reply", ".m4a", context.cacheDir).also { it.writeBytes(bytes) } }
        try {
            val p = MediaPlayer()
            player = p
            p.setDataSource(temp.absolutePath)
            p.setOnCompletionListener { done.complete(Unit) }
            p.setOnErrorListener { _, _, _ -> done.complete(Unit); true }
            p.prepare()
            p.start()
            done.await()
        } finally {
            stop()
            temp.delete()
        }
    }

    override fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        finished?.complete(Unit)
    }
}
