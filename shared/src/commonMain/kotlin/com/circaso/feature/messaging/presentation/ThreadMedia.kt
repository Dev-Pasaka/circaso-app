package com.circaso.feature.messaging.presentation

import com.circaso.core.common.AppResult
import com.circaso.core.platform.AudioPlayer
import com.circaso.core.platform.AudioRecorder
import com.circaso.core.platform.GeoPoint
import com.circaso.core.platform.LocationProvider
import com.circaso.core.platform.RecordingStart
import com.circaso.feature.messaging.data.repository.MediaDetails
import com.circaso.feature.messaging.data.repository.MediaMessages

/**
 * What a thread can play and send besides text (WhatsApp-style): voice notes and the current location. Photos, videos
 * and documents are sent from ChatMediaComposer (the moment composer's screens).
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class ThreadMedia(
    private val media: MediaMessages,
    private val location: LocationProvider,
    private val recorder: AudioRecorder,
    private val player: AudioPlayer,
    private val maps: com.circaso.feature.messaging.data.repository.MapPictures,
    private val live: com.circaso.feature.messaging.data.repository.LiveLocations,
) {
    /** How far each outgoing file's upload has got, by row: the ring on its thumbnail. */
    val progress get() = media.progress

    /** How far each incoming file's download has got, by attachment id: the ring on its bubble or album tile. */
    val downloading get() = media.downloading

    /**
     * A video's (or document's) file to play or open, downloading and decrypting it first if needed, streamed to disk;
     * null with why when it can't be had.
     */
    suspend fun videoFile(details: MediaDetails) = media.file(details)
    val canShareLocation get() = location.available

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
