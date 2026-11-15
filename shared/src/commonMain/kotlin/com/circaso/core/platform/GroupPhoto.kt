package com.circaso.core.platform

/**
 * A group photo ready to seal: the centre square at up to [GroupPhotoSide] px as a JPEG with no metadata ([full]),
 * and a tiny JPEG of the same square ([thumb], at most [GroupThumbMaxBytes]) that rides inside the group's sealed
 * metadata so every list can draw the photo at once, before the full one is fetched.
 */
class GroupPhotoImage(val full: ByteArray, val width: Int, val height: Int, val thumb: ByteArray)

/** Turns a picked or shot photo (already rotated and stripped by the pickers) into a [GroupPhotoImage]. */
interface GroupPhotoEncoder {
    suspend fun encode(source: ByteArray): GroupPhotoImage?

    /**
     * A small copy of a group photo for its join link's public preview (2026-11-14): the square at up to
     * [LinkPhotoSide] px as a JPEG of at most [LinkPhotoMaxBytes], or null when it can't be made. The only picture of
     * a group that ever leaves its encryption, and only when an admin makes a link.
     */
    suspend fun preview(source: ByteArray): ByteArray? = null
}

/** A join link's published photo: sharp at the page's 96 pt avatar and in a chat app's link preview. */
const val LinkPhotoSide = 256

/** The server takes at most 64 KB for a link's photo; qualities are tried from [LinkPhotoQualities] until one fits. */
const val LinkPhotoMaxBytes = 60_000
val LinkPhotoQualities = listOf(85, 70, 55, 40)

/** Previews and platforms without an encoder: no photo can be made, so the photo control is not offered. */
object NoGroupPhotoEncoder : GroupPhotoEncoder {
    override suspend fun encode(source: ByteArray): GroupPhotoImage? = null
}

/** The full group photo's side: sharp on the largest avatar (Group Info) and small enough to fetch in a moment. */
const val GroupPhotoSide = 1024

/** JPEG quality for the full group photo: no visible loss at avatar sizes. */
const val GroupPhotoQuality = 92

/**
 * The thumbnail's budget. The sealed metadata is at most 4 KB decoded (guide M5), and the thumbnail goes in it as
 * base64 next to the name and the photo's key, so it stays well under that.
 */
const val GroupThumbMaxBytes = 1_500

/** Thumbnail sides tried in turn (largest first), each from quality 70 down, until one fits [GroupThumbMaxBytes]. */
val GroupThumbSides = listOf(48, 40, 32, 24)
val GroupThumbQualities = listOf(70, 55, 40, 30)
