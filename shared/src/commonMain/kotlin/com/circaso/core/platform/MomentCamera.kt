package com.circaso.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A photo or video the in-app camera took (Moments M1). [bytes] are ready to post: JPEG for a photo (long side at most
 * [MomentPhotoMaxSide]), MP4 (H.264/AAC) for a video. [thumb] is a tiny JPEG for the blurred placeholder viewers see
 * while the media loads (V5); [path] is the file on disk for a video player before it is posted.
 */
class CapturedMedia(
    val bytes: ByteArray,
    val mime: String,
    val width: Int?,
    val height: Int?,
    val durationMs: Long? = null,
    val thumb: ByteArray? = null,
    val path: String? = null,
)

enum class CameraStart { Started, PermissionDenied, Unavailable }

/**
 * The in-app camera for Moments: CameraX on Android, AVFoundation on iOS (approved 2026-11-03). The viewfinder
 * ([CameraViewfinder]) owns the session while it is on screen; this controller takes the picture and records.
 * Only while the camera screen is open is the camera used.
 */
interface MomentCamera {
    /** Asks for the camera (and, for video, the microphone) the first time; [CameraStart.PermissionDenied] after a refusal. */
    suspend fun open(): CameraStart
    suspend fun takePhoto(): CapturedMedia?
    /** Starts recording; stops by itself at [MomentVideoMaxMs]. */
    suspend fun startVideo(): Boolean

    /**
     * Starts recording within [limits]: a moment stops by itself at its minute; a chat has no length limit and stops by
     * itself just before its file would pass the chat's size limit (Pasaka, 2026-11-07). [recordingStatus] says how
     * far it has got and whether it stopped by itself.
     */
    suspend fun startVideo(limits: RecordingLimits): Boolean = startVideo()

    /** The recording in progress: bytes written so far and whether it stopped by itself at a limit; null if unknown. */
    fun recordingStatus(): RecordingStatus? = null
    suspend fun stopVideo(): CapturedMedia?
    fun flip()
    fun setFlash(on: Boolean)
    fun close()

    /**
     * The camera for a profile photo: the camera permission alone (never the microphone, there is no video), set up for
     * the sharpest still, starting on the front camera. Answers like [open].
     */
    suspend fun openForPhoto(): CameraStart = open()

    /**
     * A profile photo from the sensor at full size, not a moment's [MomentPhotoMaxSide]: cropped to the centre square
     * (what the round guide over a square viewfinder frames) and encoded with the avatar rules ([AvatarMaxSide],
     * [AvatarQualitySteps], [AvatarSideSteps], under [AvatarMaxBytes]). Null when the shot failed.
     */
    suspend fun takeProfilePhoto(): PickedImage? = null
}

/**
 * What a recording may reach before it stops by itself: [maxMs] long (null: any length) and [maxBytes] on disk (null:
 * any size). A moment is a minute; a chat is up to its attachment limit, whatever the length.
 */
data class RecordingLimits(val maxMs: Long?, val maxBytes: Long?) {
    companion object {
        val Moment = RecordingLimits(MomentVideoMaxMs, null)

        /**
         * A chat's: no length limit; it stops a little under [fileMaxBytes] (the container's closing index and the
         * last frames are written after the recorder stops, so the finished file still fits).
         */
        fun chat(fileMaxBytes: Long) = RecordingLimits(null, fileMaxBytes - fileMaxBytes / RecordingHeadroomDivisor)

        /** 1/50th (2%) of the limit is kept back for what the recorder writes as it finishes. */
        private const val RecordingHeadroomDivisor = 50
    }
}

/** How a recording is going: [bytes] written so far, and [stopped] once it stopped by itself at a limit. */
data class RecordingStatus(val bytes: Long, val stopped: Boolean)

/** The live camera picture, filling [modifier]. Draws nothing where there is no camera. */
@Composable
expect fun CameraViewfinder(camera: MomentCamera, modifier: Modifier)

/** A tiny blurred-placeholder JPEG for a photo the person picked from the gallery (camera shots come with one). */
expect fun momentThumbnail(jpeg: ByteArray): ByteArray?

/** Moments: a photo's long side, and a video's length (design M1b: up to 60 s). */
const val MomentPhotoMaxSide = 2048
const val MomentVideoMaxMs = 60_000L
/**
 * The preview's long side in pixels: blurred behind a loading moment, and sharp in a reply's quote (Pasaka, 2026-11-03),
 * so 96 px; as a JPEG it stays a few KB inside the encrypted body.
 */
const val MomentThumbSide = 96
/**
 * What the camera records a moment at: 1080p30's bit rate ([MomentVideoEncoding.BitRate1080p30]). About 30 s fits the
 * 32 MiB limit as recorded; a longer clip is re-encoded to fit before it is posted (Pasaka, 2026-11-07: 3.5 Mbit/s at
 * 720p was visibly softer than the phone's own camera).
 */
const val MomentVideoBitRate = MomentVideoEncoding.BitRate1080p30
/** The server's limit for a moment's encrypted media (ST1); checked before sharing so nobody waits for a refusal. */
const val MomentMediaMaxBytes = 32L * 1024 * 1024 - 64
/**
 * The largest video file a moment can be made from (Pasaka, 2026-11-14: post like WhatsApp). A video of a minute or
 * less over [MomentMediaMaxBytes] is accepted and made smaller after Post, in the background; a minute of anything a
 * phone's camera records (4K at 60 frames is about 400 to 800 MB) is under this. Past it (a professional format, a
 * file from a computer) it is refused on the preview, with its size said.
 */
const val MomentSourceMaxBytes = 1024L * 1024 * 1024
