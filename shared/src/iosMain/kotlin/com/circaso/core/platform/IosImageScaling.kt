@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.circaso.core.platform

import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.math.max

/** Scales so the longest side is at most [maxSide]; drawing through UIKit also applies the photo's rotation. */
internal fun fit(image: UIImage, maxSide: Double): UIImage {
    val (w, h) = image.size.useContents { width to height }
    val scale = if (max(w, h) > maxSide) maxSide / max(w, h) else 1.0
    return UIGraphicsImageRenderer(size = CGSizeMake(w * scale, h * scale)).imageWithActions { image.drawInRect(CGRectMake(0.0, 0.0, w * scale, h * scale)) }
}

/** The bubble's placeholder frame travels inside the message, so it stays small (as on Android, about 240 px and 12 KB). */
internal fun tinyJpeg(image: UIImage): ByteArray? {
    val small = fit(image, 240.0)
    var quality = 0.7
    var bytes: ByteArray?
    do {
        bytes = UIImageJPEGRepresentation(small, quality)?.toByteArray()
        quality -= 0.15
    } while (bytes != null && bytes.size > 12_000 && quality > 0.2)
    return bytes
}
