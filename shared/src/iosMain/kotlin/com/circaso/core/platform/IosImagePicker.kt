@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.circaso.core.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.math.max

/** The system photo picker (PHPicker): it hands over only the one picture the person taps, so no photo permission is asked. */
class IosImagePicker : ImagePicker {
    private var delegate: PickerDelegate? = null // the picker holds its delegate weakly

    override suspend fun pick(): PickedImage? = suspendCancellableCoroutine { continuation ->
        val presenter = topViewController()
        if (presenter == null) { continuation.resume(null); return@suspendCancellableCoroutine }
        val config = PHPickerConfiguration().apply { filter = PHPickerFilter.imagesFilter; selectionLimit = 1 }
        val picker = PHPickerViewController(config)
        val d = PickerDelegate { image -> delegate = null; if (continuation.isActive) continuation.resume(image) }
        delegate = d
        picker.delegate = d
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    private fun topViewController(): UIViewController? {
        val windows = UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>()
        var top = (windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull())?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }
}

private class PickerDelegate(private val done: (PickedImage?) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        if (result == null) { done(null); return }
        result.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ -> done(data?.let(::scaledJpeg)) }
    }
}

/**
 * The photo at full size (up to [AvatarMaxSide]) as a near-lossless JPEG; only one that would pass the upload limit is
 * taken down a step, quality first, then size. Drawing through UIKit applies the rotation and drops the metadata.
 */
internal fun scaledJpeg(data: NSData): PickedImage? = UIImage(data = data)?.let(::encodeForUpload)

internal fun encodeForUpload(image: UIImage): PickedImage? {
    val (w, h) = image.size.useContents { width to height }
    // Exactly that many pixels: the renderer otherwise multiplies by the screen's scale (3x on iPhones).
    val format = platform.UIKit.UIGraphicsImageRendererFormat.defaultFormat().apply { setScale(1.0) }
    for (side in AvatarSideSteps) {
        val scale = if (max(w, h) > side) side / max(w, h) else 1.0
        val drawn = UIGraphicsImageRenderer(size = CGSizeMake(w * scale, h * scale), format = format)
            .imageWithActions { image.drawInRect(CGRectMake(0.0, 0.0, w * scale, h * scale)) }
        for (quality in AvatarQualitySteps) {
            val jpeg = UIImageJPEGRepresentation(drawn, quality / 100.0) ?: return null
            if (jpeg.length.toInt() <= AvatarMaxBytes) return PickedImage(jpeg.toByteArray(), "image/jpeg", (w * scale).toInt(), (h * scale).toInt())
        }
    }
    return null
}

/**
 * The centre square of [image] at its own pixel size (UIKit applies the rotation while drawing): what the profile
 * camera's round guide framed. Null for an empty image.
 */
internal fun centreSquare(image: UIImage): UIImage? {
    val (w, h) = image.size.useContents { width to height }
    if (w <= 0.0 || h <= 0.0) return null
    val side = kotlin.math.min(w, h)
    val format = platform.UIKit.UIGraphicsImageRendererFormat.defaultFormat().apply { setScale(1.0) }
    return UIGraphicsImageRenderer(size = CGSizeMake(side, side), format = format)
        .imageWithActions { image.drawInRect(CGRectMake(-(w - side) / 2, -(h - side) / 2, w, h)) }
}
