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
    suspend fun stopVideo(): CapturedMedia?
    fun flip()
    fun setFlash(on: Boolean)
    fun close()
}

/** The live camera picture, filling [modifier]. Draws nothing where there is no camera. */
@Composable
expect fun CameraViewfinder(camera: MomentCamera, modifier: Modifier)

/** A tiny blurred-placeholder JPEG for a photo the person picked from the gallery (camera shots come with one). */
expect fun momentThumbnail(jpeg: ByteArray): ByteArray?

/** Moments: a photo's long side, and a video's length (design M1b: up to 60 s). */
const val MomentPhotoMaxSide = 2048
const val MomentVideoMaxMs = 60_000L
/** The placeholder's long side in pixels; it is drawn blurred, so tiny is enough and keeps the body small. */
const val MomentThumbSide = 24
/** Video bit rate for moments, so 60 s fits the server's 32 MiB media limit with room for audio and encryption. */
const val MomentVideoBitRate = 3_500_000
/** The server's limit for a moment's encrypted media (ST1); checked before sharing so nobody waits for a refusal. */
const val MomentMediaMaxBytes = 32L * 1024 * 1024 - 64
