@file:OptIn(ExperimentalForeignApi::class)

package com.circaso.core.platform

import com.circaso.core.crypto.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.math.min

/**
 * The centre square of the photo at [GroupPhotoSide] px, and its thumbnail. Drawing through UIKit applies the
 * rotation, and a fresh JPEG carries no metadata (no place, no camera).
 */
class IosGroupPhotoEncoder : GroupPhotoEncoder {
    override suspend fun encode(source: ByteArray): GroupPhotoImage? = withContext(Dispatchers.IO) {
        val image = UIImage(data = source.toNSData()) ?: return@withContext null
        val (w, h) = image.size.useContents { width to height }
        val shortSide = min(w, h)
        if (shortSide <= 0.0) return@withContext null
        val target = min(shortSide, GroupPhotoSide.toDouble())
        val full = square(image, w, h, target)
        val fullJpeg = UIImageJPEGRepresentation(full, GroupPhotoQuality / 100.0)?.toByteArray() ?: return@withContext null
        val thumb = thumbnail(full) ?: return@withContext null
        GroupPhotoImage(fullJpeg, target.toInt(), target.toInt(), thumb)
    }

    override suspend fun preview(source: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val image = UIImage(data = source.toNSData()) ?: return@withContext null
        val (w, h) = image.size.useContents { width to height }
        val shortSide = min(w, h)
        if (shortSide <= 0.0) return@withContext null
        val small = square(image, w, h, min(shortSide, LinkPhotoSide.toDouble()))
        LinkPhotoQualities.firstNotNullOfOrNull { q ->
            UIImageJPEGRepresentation(small, q / 100.0)?.toByteArray()?.takeIf { it.size <= LinkPhotoMaxBytes }
        }
    }

    private fun thumbnail(square: UIImage): ByteArray? {
        val (w, h) = square.size.useContents { width to height }
        for (side in GroupThumbSides) {
            val small = square(square, w, h, side.toDouble())
            for (q in GroupThumbQualities) {
                val bytes = UIImageJPEGRepresentation(small, q / 100.0)?.toByteArray() ?: return null
                if (bytes.size <= GroupThumbMaxBytes) return bytes
            }
        }
        return null
    }

    /** The centre square of [image] drawn at [side] × [side] pixels (scale 1, not the screen's 3x). */
    private fun square(image: UIImage, w: Double, h: Double, side: Double): UIImage {
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply { setScale(1.0) }
        val short = min(w, h)
        val k = side / short
        return UIGraphicsImageRenderer(size = CGSizeMake(side, side), format = format).imageWithActions {
            image.drawInRect(CGRectMake(-(w - short) / 2 * k, -(h - short) / 2 * k, w * k, h * k))
        }
    }
}
