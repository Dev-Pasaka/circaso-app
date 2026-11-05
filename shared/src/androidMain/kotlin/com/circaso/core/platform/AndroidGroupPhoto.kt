package com.circaso.core.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * The centre square of the photo at [GroupPhotoSide] px, and its thumbnail. The source is already a JPEG the pickers
 * or the camera made (rotation applied, metadata gone); re-encoding through a Bitmap keeps it that way.
 */
class AndroidGroupPhotoEncoder : GroupPhotoEncoder {
    override suspend fun encode(source: ByteArray): GroupPhotoImage? = withContext(Dispatchers.Default) {
        runCatching { encodeSquare(source) }.getOrNull()
    }

    private fun encodeSquare(source: ByteArray): GroupPhotoImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        val shortSide = min(bounds.outWidth, bounds.outHeight)
        if (shortSide <= 0) return null
        // Decode only as many pixels as the square needs (a 12 MP photo would otherwise be ~48 MB in memory).
        var sample = 1
        while (shortSide / (sample * 2) >= GroupPhotoSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = min(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        if (square !== decoded) decoded.recycle()
        try {
            val target = min(side, GroupPhotoSide)
            val full = if (side > target) Bitmap.createScaledBitmap(square, target, target, true) else square
            try {
                val fullJpeg = full.jpeg(GroupPhotoQuality)
                val thumb = thumbnail(full) ?: return null
                return GroupPhotoImage(fullJpeg, target, target, thumb)
            } finally {
                if (full !== square) full.recycle()
            }
        } finally {
            square.recycle()
        }
    }

    private fun thumbnail(square: Bitmap): ByteArray? {
        for (side in GroupThumbSides) {
            val small = Bitmap.createScaledBitmap(square, side, side, true)
            try {
                for (q in GroupThumbQualities) {
                    val bytes = small.jpeg(q)
                    if (bytes.size <= GroupThumbMaxBytes) return bytes
                }
            } finally {
                if (small !== square) small.recycle()
            }
        }
        return null
    }
}

private fun Bitmap.jpeg(quality: Int): ByteArray = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
