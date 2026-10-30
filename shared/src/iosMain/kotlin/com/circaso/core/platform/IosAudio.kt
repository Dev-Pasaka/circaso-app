@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.circaso.core.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioApplication
import platform.AVFAudio.AVAudioApplicationRecordPermissionDenied
import platform.AVFAudio.AVAudioApplicationRecordPermissionGranted
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.posix.memcpy
import kotlin.coroutines.resume

/** Records AAC in an MPEG-4 file at a low bit rate, the same format the Android recorder makes. */
class IosAudioRecorder : AudioRecorder {
    private var recorder: AVAudioRecorder? = null
    private var url: NSURL? = null

    override suspend fun start(): RecordingStart {
        val granted = when (AVAudioApplication.sharedInstance.recordPermission) {
            AVAudioApplicationRecordPermissionGranted -> true
            AVAudioApplicationRecordPermissionDenied -> false
            else -> suspendCancellableCoroutine { continuation ->
                AVAudioApplication.requestRecordPermissionWithCompletionHandler { allowed -> if (continuation.isActive) continuation.resume(allowed) }
            }
        }
        if (!granted) return RecordingStart.PermissionDenied
        cancelNow()
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayAndRecord, mode = AVAudioSessionModeDefault, options = 0u, error = error.ptr)
            session.setActive(true, error.ptr)
            val file = NSURL.fileURLWithPath(NSTemporaryDirectory() + "voice-" + NSUUID().UUIDString + ".m4a")
            val settings = mapOf<Any?, Any?>(
                "AVFormatIDKey" to kAudioFormatMPEG4AAC.toInt(),
                "AVSampleRateKey" to 16_000.0,
                "AVNumberOfChannelsKey" to 1,
                "AVEncoderBitRateKey" to 32_000,
            )
            val r = AVAudioRecorder(uRL = file, settings = settings, error = error.ptr)
            if (!r.prepareToRecord() || !r.record()) {
                NSFileManager.defaultManager.removeItemAtURL(file, null)
                return@memScoped RecordingStart.Failed
            }
            recorder = r; url = file
            RecordingStart.Started
        }
    }

    override suspend fun stop(): Recording? {
        val r = recorder ?: return null
        val file = url ?: return null
        val seconds = r.currentTime.toInt().coerceAtLeast(1)
        r.stop()
        recorder = null; url = null
        val bytes = NSData.dataWithContentsOfURL(file)?.toByteArray()
        NSFileManager.defaultManager.removeItemAtURL(file, null)
        release()
        return if (bytes == null || bytes.isEmpty()) null else Recording(bytes, "audio/mp4", seconds)
    }

    override suspend fun cancel() = cancelNow()

    private fun cancelNow() {
        recorder?.stop()
        url?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
        recorder = null; url = null
        release()
    }

    private fun release() {
        AVAudioSession.sharedInstance().setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
    }
}

class IosAudioPlayer : AudioPlayer {
    private var player: AVAudioPlayer? = null

    override suspend fun play(bytes: ByteArray, contentType: String) {
        stop()
        if (bytes.isEmpty()) return
        val p = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error.ptr)
            AVAudioSession.sharedInstance().setActive(true, error.ptr)
            AVAudioPlayer(data = bytes.toNSData(), error = error.ptr)
        }
        player = p
        if (!p.prepareToPlay() || !p.play()) { stop(); return }
        try {
            while (p.playing) delay(100)
        } finally {
            if (player === p) stop()
        }
    }

    override fun stop() {
        player?.stop()
        player = null
        AVAudioSession.sharedInstance().setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
    }
}

internal fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
