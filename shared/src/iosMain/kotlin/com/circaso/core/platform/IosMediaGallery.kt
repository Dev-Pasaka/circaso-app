@file:OptIn(ExperimentalForeignApi::class)

package com.circaso.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetExportPreset1280x720
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetExportSessionStatusCompleted
import platform.AVFoundation.AVFileTypeMPEG4
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSortDescriptor
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfURL
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHFetchOptions
import platform.Photos.PHImageContentModeAspectFill
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHImageRequestOptionsResizeModeFast
import platform.Photos.PHPhotoLibrary
import platform.Photos.PHVideoRequestOptions
import platform.Photos.PHVideoRequestOptionsDeliveryModeHighQualityFormat
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * The attach sheet's recent photos and videos on iPhone, read through the Photos framework. With "Limited Access"
 * only the photos the person chose show, as on Android 14. Videos leave as about-720p MP4 with a tiny first-frame JPEG.
 */
class IosMediaGallery : MediaGallery {
    override val available = true

    override suspend fun requestAccess(): Boolean = suspendCancellableCoroutine { continuation ->
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { status ->
            if (continuation.isActive) continuation.resume(status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited)
        }
    }

    override suspend fun recent(limit: Int): List<GalleryItem> {
        val options = PHFetchOptions().apply {
            sortDescriptors = listOf(NSSortDescriptor.sortDescriptorWithKey("creationDate", ascending = false))
            fetchLimit = (limit * 2).toULong() // room for the audio-only assets skipped below
        }
        val result = PHAsset.fetchAssetsWithOptions(options)
        val items = mutableListOf<GalleryItem>()
        for (i in 0 until result.count.toInt()) {
            if (items.size >= limit) break
            val asset = result.objectAtIndex(i.toULong()) as? PHAsset ?: continue
            val video = asset.mediaType == PHAssetMediaTypeVideo
            if (!video && asset.mediaType != PHAssetMediaTypeImage) continue
            items += GalleryItem(
                id = asset.localIdentifier, uri = "ph://${asset.localIdentifier}", video = video,
                durationMs = if (video) (asset.duration * 1000).toLong() else null,
                width = asset.pixelWidth.toInt().takeIf { it > 0 }, height = asset.pixelHeight.toInt().takeIf { it > 0 },
            )
        }
        return items
    }

    override suspend fun thumbnail(item: GalleryItem, sizePx: Int): ByteArray? {
        val image = image(asset(item) ?: return null, sizePx.toDouble()) ?: return null
        return UIImageJPEGRepresentation(image, 0.8)?.toByteArray()
    }

    override suspend fun loadImage(item: GalleryItem): PickedImage? {
        if (item.video) return null
        val image = image(asset(item) ?: return null, ChatPhotoMaxSide) ?: return null
        val drawn = fit(image, ChatPhotoMaxSide)
        val (w, h) = drawn.size.useContents { width to height }
        val jpeg = UIImageJPEGRepresentation(drawn, 0.85) ?: return null
        return PickedImage(jpeg.toByteArray(), "image/jpeg", w.toInt(), h.toInt())
    }

    override suspend fun loadVideo(item: GalleryItem): PreparedVideo? {
        if (!item.video) return null
        val asset = asset(item) ?: return null
        val session = exportSession(asset) ?: return null
        val out = NSURL.fileURLWithPath(NSTemporaryDirectory() + "circaso-${NSUUID().UUIDString}.mp4")
        session.outputURL = out
        session.outputFileType = AVFileTypeMPEG4
        session.shouldOptimizeForNetworkUse = true
        val ok = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { session.cancelExport() }
            session.exportAsynchronouslyWithCompletionHandler {
                if (continuation.isActive) continuation.resume(session.status == AVAssetExportSessionStatusCompleted)
            }
        }
        val bytes = if (ok) NSData.dataWithContentsOfURL(out)?.toByteArray() else null
        NSFileManager.defaultManager.removeItemAtURL(out, null)
        if (bytes == null || bytes.size > MaxVideoBytes) return null
        // The 1280x720 preset keeps the aspect: the long side to at most 1280, the short to at most 720.
        val w = asset.pixelWidth.toDouble()
        val h = asset.pixelHeight.toDouble()
        val scale = if (w > 0 && h > 0) min(1.0, min(1280.0 / max(w, h), 720.0 / min(w, h))) else 1.0
        return PreparedVideo(
            bytes, "video/mp4", (w * scale).toInt().takeIf { it > 0 }, (h * scale).toInt().takeIf { it > 0 },
            (asset.duration * 1000).toLong(), image(asset, ThumbSide)?.let(::tinyJpeg),
        )
    }

    private fun asset(item: GalleryItem): PHAsset? = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(item.id), null).firstObject as? PHAsset

    /** One high-quality image at about [side] points; iCloud-only photos are downloaded. */
    private suspend fun image(asset: PHAsset, side: Double): UIImage? = suspendCancellableCoroutine { continuation ->
        val options = PHImageRequestOptions().apply {
            deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
            resizeMode = PHImageRequestOptionsResizeModeFast
            networkAccessAllowed = true
        }
        PHImageManager.defaultManager().requestImageForAsset(asset, CGSizeMake(side, side), PHImageContentModeAspectFill, options) { image, _ ->
            if (continuation.isActive) continuation.resume(image)
        }
    }

    private suspend fun exportSession(asset: PHAsset): AVAssetExportSession? = suspendCancellableCoroutine { continuation ->
        val options = PHVideoRequestOptions().apply {
            deliveryMode = PHVideoRequestOptionsDeliveryModeHighQualityFormat
            networkAccessAllowed = true
        }
        PHImageManager.defaultManager().requestExportSessionForVideo(asset, options, AVAssetExportPreset1280x720) { session, _ ->
            if (continuation.isActive) continuation.resume(session)
        }
    }

    private companion object {
        /** Same as Android: sharp on any phone, a few hundred KB. */
        const val ChatPhotoMaxSide = 1600.0
        const val ThumbSide = 320.0
        const val MaxVideoBytes = 32L * 1024 * 1024
    }
}

/** Scales so the longest side is at most [maxSide]; drawing through UIKit also applies the photo's rotation. */
private fun fit(image: UIImage, maxSide: Double): UIImage {
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
