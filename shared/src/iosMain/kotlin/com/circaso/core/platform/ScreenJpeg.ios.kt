package com.circaso.core.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.max
import kotlin.math.roundToInt

actual fun screenJpeg(image: ImageBitmap, maxSide: Int, quality: Int): ScreenJpeg? = runCatching {
    val source = Image.makeFromBitmap(image.asSkiaBitmap())
    val scale = minOf(1f, maxSide.toFloat() / max(source.width, source.height))
    val w = max(1, (source.width * scale).roundToInt())
    val h = max(1, (source.height * scale).roundToInt())
    val surface = Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(source, Rect.makeWH(w.toFloat(), h.toFloat()))
    val bytes = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, quality)?.bytes ?: return@runCatching null
    ScreenJpeg(bytes, w, h)
}.getOrNull()
