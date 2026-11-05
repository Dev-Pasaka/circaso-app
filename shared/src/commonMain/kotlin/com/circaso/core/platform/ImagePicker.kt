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

/**
 * The longest side of a profile picture we upload, in pixels: a phone's own 12 MP photo keeps its full size (Pasaka,
 * 2026-11-05: no visible compression). Only a larger one (50 MP) is brought down to this.
 */
const val AvatarMaxSide = 4096

/**
 * JPEG quality for a profile photo: visually lossless. It is still re-encoded once, because that applies the photo's
 * rotation and drops its metadata (the place it was taken would otherwise be on a public profile picture).
 */
const val AvatarJpegQuality = 95

/** Under the server's 5 MB limit for a profile photo, with room to spare. */
const val AvatarMaxBytes = 4_900_000

/** Qualities tried in turn, then sizes, until the photo fits [AvatarMaxBytes]: most photos stop at the first. */
val AvatarQualitySteps = listOf(AvatarJpegQuality, 92, 88)
val AvatarSideSteps = listOf(AvatarMaxSide, 3072, 2560)
