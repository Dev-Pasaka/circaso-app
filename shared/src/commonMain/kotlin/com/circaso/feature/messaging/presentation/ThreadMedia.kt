package com.circaso.feature.messaging.presentation

import com.circaso.core.common.AppResult
import com.circaso.core.platform.AudioPlayer
import com.circaso.core.platform.AudioRecorder
import com.circaso.core.platform.GeoPoint
import com.circaso.core.platform.ImagePicker
import com.circaso.core.platform.LocationProvider
import com.circaso.core.platform.PickedImage
import com.circaso.core.platform.RecordingStart
import com.circaso.feature.messaging.data.repository.MediaDetails
import com.circaso.feature.messaging.data.repository.MediaMessages

/** What a thread can send and play besides text (WhatsApp-style): photos, voice notes, the current location. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class ThreadMedia(
    private val media: MediaMessages,
    private val picker: ImagePicker,
    private val location: LocationProvider,
    private val recorder: AudioRecorder,
    private val player: AudioPlayer,
    private val maps: com.circaso.feature.messaging.data.repository.MapPictures,
    private val live: com.circaso.feature.messaging.data.repository.LiveLocations,
    private val gallery: com.circaso.core.platform.MediaGallery = com.circaso.core.platform.NoMediaGallery,
    /** Shrinking a video runs as background work too, so leaving the thread mid-way doesn't stop it (BackgroundUploads). */
    private val uploads: com.circaso.core.platform.BackgroundUploads = com.circaso.core.platform.NoBackgroundUploads,
) {
    val hasGallery get() = gallery.available

    /** The recent grid (asks for library access the first time); empty when refused. */
    suspend fun recentMedia(): List<com.circaso.core.platform.GalleryItem> = if (gallery.requestAccess()) gallery.recent() else emptyList()

    suspend fun galleryThumbnail(item: com.circaso.core.platform.GalleryItem, sizePx: Int) = gallery.thumbnail(item, sizePx)

    /**
     * Each chosen photo or video goes into the queue in the order it was ticked, the caption on the first. A video is
     * shrunk first (a few seconds a minute of video). Null = all queued fine.
     */
    suspend fun sendGalleryMedia(chatId: String, items: List<com.circaso.core.platform.GalleryItem>, caption: String?): String? {
        items.forEachIndexed { i, item ->
            val note = caption.takeIf { i == 0 }
            if (item.video) {
                val video = uploads.keep(com.circaso.core.platform.UploadKind.Video) { gallery.loadVideo(item) } ?: return "That video couldn't be prepared. Videos up to about 2 minutes can be sent."
                val thumb = video.thumbJpeg?.let { kotlin.io.encoding.Base64.encode(it) }
                (media.sendVideo(chatId, video.bytes, video.contentType, video.width, video.height, video.durationMs, thumb, note) as? AppResult.Failure)
                    ?.let { return "The video couldn't be saved to send. Try again." }
            } else {
                val picked = gallery.loadImage(item) ?: return "A photo couldn't be opened. Try another."
                sendPhoto(chatId, picked, note)?.let { return it }
            }
        }
        return null
    }

    /** A video's file to play, downloading and decrypting it first if needed; null with why when it can't be had. */
    suspend fun videoFile(details: MediaDetails) = media.file(details)
    val canShareLocation get() = location.available

    /** The system photo picker; null when nothing was chosen. */
    suspend fun pickPhoto(): PickedImage? = picker.pick()

    /** The chosen photo, with its caption, goes into the queue (encrypted and uploaded in order). Null = queued fine. */
    suspend fun sendPhoto(chatId: String, picked: PickedImage, caption: String?): String? =
        (media.sendImage(chatId, picked.bytes, picked.contentType, picked.width, picked.height, caption) as? AppResult.Failure)?.let { "The photo couldn't be saved to send. Try again." }

    /** Where the phone is now (asked only when the person taps Location); null = refused or no fix. */
    suspend fun locate(): GeoPoint? = location.current()

    /** Sends only after the person has seen and confirmed the place (a precise location is personal). */
    suspend fun sendLocation(chatId: String, point: GeoPoint) = media.sendLocation(chatId, point, null)

    suspend fun startVoice(): String? = when (recorder.start()) {
        RecordingStart.Started -> null
        RecordingStart.PermissionDenied -> "Circaso needs the microphone for voice messages. Turn it on in Settings."
        RecordingStart.Failed -> "The microphone isn't available right now."
    }

    /** Sends what was recorded (a voice note under half a second is a slip, not a message). */
    suspend fun finishVoice(chatId: String): String? {
        val clip = recorder.stop() ?: return null
        if (clip.durationSeconds < 1 && clip.bytes.size < 4_000) return HoldToRecord
        return (media.sendVoice(chatId, clip.bytes, clip.contentType, clip.durationSeconds * 1000L) as? AppResult.Failure)?.let { "The voice message couldn't be saved to send. Try again." }
    }

    suspend fun cancelVoice() = recorder.cancel()

    suspend fun load(details: MediaDetails): com.circaso.feature.messaging.data.repository.MediaLoad = media.load(details)

    /** Loads (downloading if needed), calls [started] as sound begins, plays to the end or until stopped; why not when it couldn't load. */
    suspend fun play(details: MediaDetails, started: () -> Unit = {}): com.circaso.feature.messaging.data.repository.MediaLoad {
        val loaded = media.load(details)
        val bytes = (loaded as? com.circaso.feature.messaging.data.repository.MediaLoad.Loaded)?.bytes ?: return loaded
        started()
        player.play(bytes, details.mime ?: "audio/mp4")
        return loaded
    }

    fun stopPlaying() = player.stop()

    suspend fun retry(localId: Long) = media.retry(localId)

    /** Starts sharing live location here for [durationMs] from [point] (the place the person just confirmed). */
    suspend fun shareLive(chatId: String, point: GeoPoint, durationMs: Long): String? =
        (live.start(chatId, point, durationMs) as? AppResult.Failure)?.let { "Live location couldn't start. Try again." }

    suspend fun stopLive(localId: Long) = live.stop(localId)

    /** The map picture for a location at [zoom]; the caller places its pins at the same zoom. */
    suspend fun mapPicture(lat: Double, lng: Double, widthDp: Int, heightDp: Int, zoom: Int, palette: com.circaso.feature.messaging.data.repository.MapPalette) =
        maps.picture(lat, lng, widthDp, heightDp, zoom = zoom, palette = palette)

    companion object {
        const val HoldToRecord = "Hold to record, release to send."
    }
}
