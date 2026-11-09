package com.circaso.core.platform

import androidx.compose.ui.graphics.ImageBitmap

/** A picture of the app's own screen, ready to send: JPEG bytes and the size they decode to. */
class ScreenJpeg(val bytes: ByteArray, val width: Int, val height: Int)

/**
 * [image] scaled so its longest side is at most [maxSide] px, as a JPEG at [quality] (0–100); null if it could not be
 * encoded. Runs on the caller's dispatcher (call it off the main thread); the picture never touches the disk.
 */
expect fun screenJpeg(image: ImageBitmap, maxSide: Int, quality: Int): ScreenJpeg?
