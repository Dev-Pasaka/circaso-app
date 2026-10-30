package com.circaso.core.platform

/** One finished voice clip, ready to upload (AAC in an MP4 container, mono, small). */
class Recording(val bytes: ByteArray, val contentType: String, val durationSeconds: Int)

/** Why recording could not start. */
enum class RecordingStart { Started, PermissionDenied, Failed }

/** Records short voice questions. The microphone permission prompt is the platform's own, asked only when the person taps the mic. */
interface AudioRecorder {
    suspend fun start(): RecordingStart

    /** Stops and returns the clip, or null if nothing was recorded. */
    suspend fun stop(): Recording?

    /** Stops and throws the clip away. */
    suspend fun cancel()
}

/** Plays a spoken reply (the bytes the server sends back from `POST /v1/assistant/speech`). */
interface AudioPlayer {
    /** Plays to the end, or until [stop] is called. Returns when playback has finished. */
    suspend fun play(bytes: ByteArray, contentType: String)
    fun stop()
}

/** Voice clips are limited to 60 seconds and 3 MB by the server. */
const val MaxVoiceSeconds = 60
