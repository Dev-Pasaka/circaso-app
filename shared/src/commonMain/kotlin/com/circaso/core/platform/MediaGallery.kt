package com.circaso.core.platform

/** One photo or video in the phone's library, for the chat's attach sheet (WhatsApp-style recent grid). */
data class GalleryItem(val id: String, val uri: String, val video: Boolean, val durationMs: Long?, val width: Int?, val height: Int?)

/**
 * The phone's recent photos and videos, read in the app (the attach sheet's grid) rather than through the system
 * picker. Asks for library access the first time; on Android 14 and later "Select photos" is respected, and only the
 * chosen items show.
 */
interface MediaGallery {
    val available: Boolean

    /** True when the app may read the library (all of it, or what the person selected). */
    suspend fun requestAccess(): Boolean

    /** Newest first. */
    suspend fun recent(limit: Int = 90): List<GalleryItem>

    /** A small JPEG for the grid; null when it can't be made. */
    suspend fun thumbnail(item: GalleryItem, sizePx: Int): ByteArray?

    /** A photo ready to send: scaled down and re-encoded like the picker's. */
    suspend fun loadImage(item: GalleryItem): PickedImage?

    /** A video ready to send: shrunk to fit a chat attachment, with a small first-frame thumbnail; null if it can't be. */
    suspend fun loadVideo(item: GalleryItem): PreparedVideo? = null
}

/** A video re-encoded for sending (MP4, about 720p), its size and length, and a small JPEG of its first frame. */
class PreparedVideo(val bytes: ByteArray, val contentType: String, val width: Int?, val height: Int?, val durationMs: Long?, val thumbJpeg: ByteArray?)

/** Plays a video file full size, with the platform's own controls (ExoPlayer on Android, AVKit on iOS). */
@androidx.compose.runtime.Composable
expect fun VideoPlayer(path: String, modifier: androidx.compose.ui.Modifier)

object NoMediaGallery : MediaGallery {
    override val available = false
    override suspend fun requestAccess() = false
    override suspend fun recent(limit: Int) = emptyList<GalleryItem>()
    override suspend fun thumbnail(item: GalleryItem, sizePx: Int): ByteArray? = null
    override suspend fun loadImage(item: GalleryItem): PickedImage? = null
}
