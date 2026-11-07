package com.circaso.core.platform

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/**
 * What a video file really holds, read from its container: size as shown (rotation applied), frame rate, the video
 * track's bit rate, codec and profile. Used to plan a moment's encode ([MomentVideoEncoding.plan]) and to log what an
 * encode produced, so quality is measured rather than guessed.
 */
internal data class VideoFacts(
    val width: Int,
    val height: Int,
    val frameRate: Float?,
    val videoBitRate: Long?,
    val durationMs: Long,
    val mime: String?,
    val profile: Int?,
    val fileBytes: Long,
) {
    override fun toString() = "${width}x$height ${frameRate?.let { "%.2f".format(it) } ?: "?"} fps, video ${videoBitRate?.let { it / 1000 } ?: "?"} kbit/s, " +
        "$mime profile ${profile ?: "?"}, ${durationMs} ms, $fileBytes bytes"
}

internal fun probeVideo(path: String): VideoFacts? = runCatching {
    val file = File(path)
    val r = MediaMetadataRetriever()
    val (w, h, durationMs, frames) = try {
        r.setDataSource(path)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return@runCatching null
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return@runCatching null
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val frames = if (android.os.Build.VERSION.SDK_INT >= 28) r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() else null
        val sideways = rotation == 90 || rotation == 270
        listOf(if (sideways) h else w, if (sideways) w else h, duration, frames ?: 0L).map { it.toLong() }
    } finally {
        runCatching { r.release() }
    }
    val extractor = MediaExtractor()
    var fps: Float? = null
    var videoRate: Long? = null
    var audioRate = 0L
    var mime: String? = null
    var profile: Int? = null
    var videoBytes = 0L
    try {
        extractor.setDataSource(path)
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("video/") && mime == null) {
                mime = m
                if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrElse { runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull() }
                if (f.containsKey(MediaFormat.KEY_BIT_RATE)) videoRate = f.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                if (f.containsKey(MediaFormat.KEY_PROFILE)) profile = f.getInteger(MediaFormat.KEY_PROFILE)
                // The real average: the video samples' bytes over the length (MP4s rarely state a bit rate).
                extractor.selectTrack(i)
                while (true) {
                    val size = extractor.sampleSize
                    if (size < 0) break
                    videoBytes += size
                    extractor.advance()
                }
                extractor.unselectTrack(i)
            } else if (m.startsWith("audio/") && f.containsKey(MediaFormat.KEY_BIT_RATE)) {
                audioRate = f.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
            }
        }
    } finally {
        extractor.release()
    }
    if (durationMs > 0 && videoBytes > 0) videoRate = videoBytes * 8 * 1000 / durationMs
    if (videoRate == null && durationMs > 0) videoRate = (file.length() * 8 * 1000 / durationMs - audioRate).coerceAtLeast(0)
    if (fps == null && frames > 0 && durationMs > 0) fps = frames * 1000f / durationMs
    VideoFacts(w.toInt(), h.toInt(), fps, videoRate, durationMs, mime, profile, file.length())
}.getOrNull()

/** One line per encode in logcat (tag `MomentVideo`): sizes, rates and bytes only, nothing about the person. */
internal fun logEncode(what: String, source: VideoFacts?, plan: VideoPlan?, out: VideoFacts?) {
    Log.i("MomentVideo", "$what: source [$source] plan [${plan?.let { "${it.width}x${it.height} ${it.bitRate / 1000} kbit/s" }}] output [$out]")
}
