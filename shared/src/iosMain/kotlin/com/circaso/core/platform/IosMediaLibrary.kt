@file:OptIn(ExperimentalForeignApi::class)

package com.circaso.core.platform

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerLooper
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.CoreGraphics.CGRectZero
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSSortDescriptor
import platform.Foundation.NSURL
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHFetchOptions
import platform.Photos.PHImageContentModeAspectFill
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeOpportunistic
import platform.Photos.PHImageRequestOptionsResizeModeFast
import platform.Photos.PHPhotoLibrary
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIView
import kotlin.coroutines.resume

/**
 * Loading library items and the system pickers live on the Swift side (`MediaLibraryImpl.swift`): PHImageManager,
 * AVAssetExportSession, PHPickerViewController and UIDocumentPickerViewController. Installed at launch like the others.
 */
interface MediaLibraryProvider {
    /** [video] says which way to read the PHAsset [id]; answers null when it is gone or cannot be read. */
    fun load(id: String, video: Boolean, completion: (LibraryMedia?) -> Unit)
    /** [kind]: 0 photo (PHPicker), 1 video (PHPicker), 2 audio (Files). Null when the person cancels. */
    fun pick(kind: Int, completion: (LibraryMedia?) -> Unit)
}

/** One photo (JPEG), video (MP4) or audio file from the Swift side; [durationMs] 0 and sizes 0 mean unknown. */
class LibraryMedia(
    val data: NSData,
    val thumb: NSData?,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val path: String?,
    val mime: String,
)

object MediaLibraryBridge {
    var provider: MediaLibraryProvider? = null
}

/**
 * Moments' library on iPhone. Recents come straight from the Photos framework (with "Limited Access" only what the
 * person chose); iOS has no audio library an app can list, so audio comes only through the Files picker.
 */
class IosMediaLibrary : MediaLibrary {
    override suspend fun access(): LibraryAccess {
        val status = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)
        val answer = if (status == PHAuthorizationStatusNotDetermined) {
            suspendCancellableCoroutine { cont ->
                PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { if (cont.isActive) cont.resume(it) }
            }
        } else status
        return when (answer) {
            PHAuthorizationStatusAuthorized -> LibraryAccess.Granted
            PHAuthorizationStatusLimited -> LibraryAccess.Limited
            else -> LibraryAccess.Denied
        }
    }

    override suspend fun recent(kind: LibraryKind, limit: Int): List<LibraryItem> {
        if (kind == LibraryKind.Audio || limit <= 0) return emptyList()
        if (access() == LibraryAccess.Denied) return emptyList()
        return withContext(Dispatchers.Default) {
            val options = PHFetchOptions().apply {
                sortDescriptors = listOf(NSSortDescriptor.sortDescriptorWithKey("creationDate", ascending = false))
                fetchLimit = limit.toULong()
            }
            val video = kind == LibraryKind.Video
            val result = PHAsset.fetchAssetsWithMediaType(if (video) PHAssetMediaTypeVideo else PHAssetMediaTypeImage, options)
            (0 until result.count.toInt()).mapNotNull { i ->
                val asset = result.objectAtIndex(i.toULong()) as? PHAsset ?: return@mapNotNull null
                LibraryItem(id = asset.localIdentifier, kind = kind, durationMs = if (video) (asset.duration * 1000).toLong() else null)
            }
        }
    }

    override suspend fun load(item: LibraryItem): CapturedMedia? {
        if (item.kind == LibraryKind.Audio) return null
        val p = MediaLibraryBridge.provider ?: return null
        return suspendCancellableCoroutine<LibraryMedia?> { cont ->
            p.load(item.id, item.kind == LibraryKind.Video) { if (cont.isActive) cont.resume(it) }
        }?.toCaptured()
    }

    override suspend fun pick(kind: LibraryKind): CapturedMedia? {
        val p = MediaLibraryBridge.provider ?: return null
        val code = when (kind) { LibraryKind.Photo -> 0; LibraryKind.Video -> 1; LibraryKind.Audio -> 2 }
        return suspendCancellableCoroutine<LibraryMedia?> { cont -> p.pick(code) { if (cont.isActive) cont.resume(it) } }?.toCaptured()
    }

    private fun LibraryMedia.toCaptured() = CapturedMedia(
        bytes = data.toByteArray(),
        mime = mime,
        width = width.takeIf { it > 0 },
        height = height.takeIf { it > 0 },
        durationMs = durationMs.takeIf { it > 0 },
        thumb = thumb?.toByteArray(),
        path = path,
    )
}

/**
 * Asks Photos for the asset at the box's pixel size (aspect fill). Opportunistic delivery answers first with a quick,
 * soft picture and then the sharp one, so the strip fills at once; leaving the screen cancels the request.
 */
@Composable
actual fun LibraryThumbnail(item: LibraryItem, modifier: Modifier) {
    if (item.kind == LibraryKind.Audio) return
    var size by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.id, size) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val asset = withContext(Dispatchers.Default) {
            PHAsset.fetchAssetsWithLocalIdentifiers(listOf(item.id), null).firstObject as? PHAsset
        } ?: return@LaunchedEffect
        callbackFlow {
            val options = PHImageRequestOptions().apply {
                deliveryMode = PHImageRequestOptionsDeliveryModeOpportunistic
                resizeMode = PHImageRequestOptionsResizeModeFast
                networkAccessAllowed = true
            }
            val target = CGSizeMake(size.width.toDouble(), size.height.toDouble())
            val request = PHImageManager.defaultManager().requestImageForAsset(asset, target, PHImageContentModeAspectFill, options) { image, _ ->
                if (image != null) trySend(image)
            }
            awaitClose { PHImageManager.defaultManager().cancelImageRequest(request) }
        }.collectLatest { image ->
            val decoded = withContext(Dispatchers.Default) {
                UIImageJPEGRepresentation(image, 0.9)?.toByteArray()?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }
            }
            if (decoded != null) bitmap = decoded
        }
    }
    Box(modifier.onSizeChanged { size = it }.clipToBounds()) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** A plain view whose player layer follows its bounds (aspect fill), without implicit animations. */
private class MomentPlayerView : UIView(frame = CGRectZero.readValue()) {
    val playerLayer = AVPlayerLayer()

    init {
        backgroundColor = UIColor.blackColor
        clipsToBounds = true
        playerLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
        layer.addSublayer(playerLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = bounds
        CATransaction.commit()
    }
}

/** AVQueuePlayer in an aspect-filled layer, no controls; [loop] repeats through AVPlayerLooper. Released on dispose. */
@Composable
actual fun MomentVideo(path: String, playing: Boolean, muted: Boolean, modifier: Modifier, loop: Boolean) {
    val player = remember(path) { AVQueuePlayer() }
    DisposableEffect(player, loop) {
        val item = AVPlayerItem(uRL = NSURL.fileURLWithPath(path))
        val looper = if (loop) AVPlayerLooper.playerLooperWithPlayer(player, templateItem = item) else null
        if (!loop) player.insertItem(item, afterItem = null)
        onDispose {
            looper?.disableLooping()
            player.pause()
            player.removeAllItems()
        }
    }
    LaunchedEffect(player, muted) { player.muted = muted }
    LaunchedEffect(player, playing, loop) { if (playing) player.play() else player.pause() }
    UIKitView(
        factory = { MomentPlayerView().also { it.playerLayer.player = player } },
        modifier = modifier,
        onRelease = { it.playerLayer.player = null },
        properties = UIKitInteropProperties(interactionMode = null) // touches stay with Compose (hold to pause),
    )
}
