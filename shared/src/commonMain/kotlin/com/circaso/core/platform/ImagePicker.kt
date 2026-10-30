package com.circaso.core.platform

/** A picture the person chose, already scaled down and re-encoded (JPEG) so it is far under the 5 MB upload limit. */
/** The picked photo, re-encoded; [width] and [height] are its pixel size after scaling (null if unknown). */
class PickedImage(val bytes: ByteArray, val contentType: String, val width: Int? = null, val height: Int? = null)

/**
 * Opens the system's own photo picker. It reads nothing but the one picture the person taps, so the app needs no
 * photo-library permission. Returns null when the person closes the picker without choosing.
 */
interface ImagePicker {
    suspend fun pick(): PickedImage?
}

/** The longest side of a profile picture we upload, in pixels. */
const val AvatarMaxSide = 1024
