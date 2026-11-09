package com.circaso.feature.assistant.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import com.circaso.core.platform.screenJpeg
import org.jetbrains.compose.resources.decodeToImageBitmap
import com.circaso.feature.assistant.domain.AshaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A picture of the screen for Asha: what is sent, and what the person sees as its thumbnail first. */
class ScreenShot(val image: AshaImage, val preview: ImageBitmap)

/**
 * "Show Asha my screen" (2026-11-09): a picture of the app's own screens, drawn from Compose (no system screen
 * recording, no permission). Only what [capturable] wraps is in it: the screens and their sheets, never Asha's bubble,
 * a call or a toast, which sit outside it. The content is drawn through the layer only while a picture is being
 * taken ([armed]), so the app pays nothing for it the rest of the time.
 */
@Stable
class ScreenCapture internal constructor(internal val layer: GraphicsLayer) {
    internal var armed by mutableStateOf(false)
        private set

    /** Takes the picture over the next frames and scales it for Asha; null if it could not be made. */
    suspend fun take(): ScreenShot? {
        armed = true
        return try {
            // One frame to draw through the layer, one more so it holds that frame.
            withFrameNanos { }
            withFrameNanos { }
            val bitmap = layer.toImageBitmap()
            if (bitmap.width <= 1 || bitmap.height <= 1) return null
            withContext(Dispatchers.Default) {
                screenJpeg(bitmap, AshaImage.MaxSide, ScreenJpegQuality)
                    ?.takeIf { it.bytes.size <= AshaImage.MaxBytes }
                    ?.let { ScreenShot(AshaImage(it.bytes, it.width, it.height), thumbnailOf(it.bytes) ?: bitmap) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            armed = false
        }
    }

    private companion object {
        /** Sharp enough for Asha to read the screen's text, small enough to send in a moment (about 100–250 KB). */
        const val ScreenJpegQuality = 80
    }
}

/** A small copy for the thumbnail, decoded from the JPEG, so the full-window picture isn't kept in memory. */
private fun thumbnailOf(jpeg: ByteArray): ImageBitmap? = runCatching {
    jpeg.decodeToImageBitmap()
}.getOrNull()

@Composable
fun rememberScreenCapture(): ScreenCapture {
    val layer = rememberGraphicsLayer()
    return remember(layer) { ScreenCapture(layer) }
}

/** Draws the content through [capture]'s layer while a picture is being taken; otherwise draws it as usual. */
fun Modifier.capturable(capture: ScreenCapture): Modifier = drawWithContent {
    if (capture.armed) {
        capture.layer.record { this@drawWithContent.drawContent() }
        drawLayer(capture.layer)
    } else drawContent()
}
