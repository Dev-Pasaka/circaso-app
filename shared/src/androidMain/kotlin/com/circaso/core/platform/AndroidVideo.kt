package com.circaso.core.platform

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Shrinks a video for a chat attachment with Media3 Transformer: H.264 at 720p (the short side) and about 1.8 Mbit/s,
 * AAC sound, so two minutes stay under the 32 MB limit. Longer, it is refused rather than cut.
 */
@OptIn(UnstableApi::class)
internal suspend fun prepareVideo(context: Context, uri: Uri): PreparedVideo? {
    val out = File(context.cacheDir, "send-${System.nanoTime()}.mp4")
    val encoder = DefaultEncoderFactory.Builder(context)
        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(VideoBitrate).build())
        .build()
    // 720 on the short side, whichever way the phone was held (Media3 1.4 sizes by width and height).
    val (inW, inH) = withContext(Dispatchers.IO) { displaySize(context, uri) } ?: (1280 to 720)
    val scale = 720f / minOf(inW, inH).coerceAtLeast(1)
    val (outW, outH) = if (scale >= 1f) inW to inH else ((inW * scale).toInt() / 2 * 2) to ((inH * scale).toInt() / 2 * 2)
    val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
        .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))))
        .build()
    // Transformer runs on a thread with a Looper: the main one.
    val ok = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) { if (cont.isActive) cont.resume(true) }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) { if (cont.isActive) cont.resume(false) }
                })
                .build()
            transformer.start(edited, out.absolutePath)
            cont.invokeOnCancellation { transformer.cancel() }
        }
    }
    return withContext(Dispatchers.IO) {
        try {
            if (!ok || !out.exists() || out.length() > MaxVideoBytes) return@withContext null
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(out.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val frame = retriever.getFrameAtTime(0)
            retriever.release()
            val sideways = rotation == 90 || rotation == 270
            PreparedVideo(out.readBytes(), "video/mp4", if (sideways) h else w, if (sideways) w else h, duration, frame?.let(::thumbJpeg))
        } finally {
            out.delete()
        }
    }
}

/** The video's size as shown (rotation applied), or null when it can't be read. */
private fun displaySize(context: Context, uri: Uri): Pair<Int, Int>? = runCatching {
    val r = MediaMetadataRetriever()
    r.setDataSource(context, uri)
    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
    val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    r.release()
    if (w == null || h == null) null else if (rotation == 90 || rotation == 270) h to w else w to h
}.getOrNull()

/** The first frame, about 240 px on its long side and under ~12 KB, to travel inside the encrypted message. */
private fun thumbJpeg(frame: Bitmap): ByteArray {
    val scale = 240f / max(frame.width, frame.height)
    val small = Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
    var quality = 70
    var bytes: ByteArray
    do {
        bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        quality -= 15
    } while (bytes.size > 12_000 && quality > 20)
    return bytes
}

private const val VideoBitrate = 1_800_000
private const val MaxVideoBytes = 32L * 1024 * 1024

@OptIn(UnstableApi::class)
@Composable
actual fun VideoPlayer(path: String, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = modifier)
}
