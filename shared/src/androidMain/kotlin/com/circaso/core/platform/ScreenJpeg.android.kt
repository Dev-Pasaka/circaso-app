package com.circaso.core.platform

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

actual fun screenJpeg(image: ImageBitmap, maxSide: Int, quality: Int): ScreenJpeg? = runCatching {
    // A graphics layer's picture can be a hardware bitmap, which can't be scaled or compressed in software: copy it first.
    val source = image.asAndroidBitmap().let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    val scale = minOf(1f, maxSide.toFloat() / max(source.width, source.height))
    val w = max(1, (source.width * scale).roundToInt())
    val h = max(1, (source.height * scale).roundToInt())
    val scaled = if (w == source.width && h == source.height) source else Bitmap.createScaledBitmap(source, w, h, true)
    val out = ByteArrayOutputStream()
    if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) return@runCatching null
    ScreenJpeg(out.toByteArray(), w, h)
}.getOrNull()
