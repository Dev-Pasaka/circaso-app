@file:OptIn(ExperimentalForeignApi::class)

package com.circaso.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.circaso.core.crypto.toByteArray
import com.circaso.core.crypto.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIView
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * AVFoundation lives on the Swift side (`MomentCameraImpl.swift`), installed at launch like the other bridges.
 * Everything a shot carries crosses in one [MomentShot] so the bridge stays a handful of callbacks.
 */
interface MomentCameraProvider {
    /** [completion]: 0 started, 1 permission refused, 2 no camera (the simulator). */
    fun open(completion: (Int) -> Unit)
    fun takePhoto(completion: (MomentShot?) -> Unit)
    fun startVideo(completion: (Boolean) -> Unit)
    /** Also answers with the clip after a recording stopped by itself at [MomentVideoMaxMs]. */
    fun stopVideo(completion: (MomentShot?) -> Unit)
    fun flip()
    fun setFlash(on: Boolean)
    fun close()
    /** The live picture, aspect-filled and resizing with its bounds. */
    fun previewView(): UIView
}

/** One photo (JPEG) or video (MP4, [path] set, [durationMs] > 0) from the Swift side; [thumb] is the tiny JPEG. */
class MomentShot(
    val data: NSData,
    val thumb: NSData?,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val path: String?,
)

object MomentCameraBridge {
    var provider: MomentCameraProvider? = null
}

class IosMomentCamera : MomentCamera {
    override suspend fun open(): CameraStart {
        val p = MomentCameraBridge.provider ?: return CameraStart.Unavailable
        return suspendCancellableCoroutine { cont ->
            p.open { code ->
                if (cont.isActive) cont.resume(when (code) { 0 -> CameraStart.Started; 1 -> CameraStart.PermissionDenied; else -> CameraStart.Unavailable })
            }
        }
    }

    override suspend fun takePhoto(): CapturedMedia? {
        val p = MomentCameraBridge.provider ?: return null
        return suspendCancellableCoroutine<MomentShot?> { cont -> p.takePhoto { if (cont.isActive) cont.resume(it) } }?.toMedia("image/jpeg")
    }

    override suspend fun startVideo(): Boolean {
        val p = MomentCameraBridge.provider ?: return false
        return suspendCancellableCoroutine { cont -> p.startVideo { if (cont.isActive) cont.resume(it) } }
    }

    override suspend fun stopVideo(): CapturedMedia? {
        val p = MomentCameraBridge.provider ?: return null
        return suspendCancellableCoroutine<MomentShot?> { cont -> p.stopVideo { if (cont.isActive) cont.resume(it) } }?.toMedia("video/mp4")
    }

    override fun flip() { MomentCameraBridge.provider?.flip() }
    override fun setFlash(on: Boolean) { MomentCameraBridge.provider?.setFlash(on) }
    override fun close() { MomentCameraBridge.provider?.close() }

    private fun MomentShot.toMedia(mime: String) = CapturedMedia(
        bytes = data.toByteArray(),
        mime = mime,
        width = width,
        height = height,
        durationMs = durationMs.takeIf { it > 0 },
        thumb = thumb?.toByteArray(),
        path = path,
    )
}

@Composable
actual fun CameraViewfinder(camera: MomentCamera, modifier: Modifier) {
    val provider = MomentCameraBridge.provider ?: return
    UIKitView(factory = { provider.previewView() }, modifier = modifier)
}

/** Draws the picture at [MomentThumbSide] on its long side (UIKit applies the rotation) and saves a small JPEG. */
actual fun momentThumbnail(jpeg: ByteArray): ByteArray? {
    if (jpeg.isEmpty()) return null
    val image = UIImage(data = jpeg.toNSData()) ?: return null
    val (w, h) = image.size.useContents { width to height }
    if (w <= 0.0 || h <= 0.0) return null
    val scale = MomentThumbSide / max(w, h)
    val tw = max(1, (w * scale).roundToInt()).toDouble()
    val th = max(1, (h * scale).roundToInt()).toDouble()
    val format = UIGraphicsImageRendererFormat.defaultFormat().apply { setScale(1.0) }
    val drawn = UIGraphicsImageRenderer(size = CGSizeMake(tw, th), format = format).imageWithActions { image.drawInRect(CGRectMake(0.0, 0.0, tw, th)) }
    return UIImageJPEGRepresentation(drawn, 0.5)?.toByteArray()
}
