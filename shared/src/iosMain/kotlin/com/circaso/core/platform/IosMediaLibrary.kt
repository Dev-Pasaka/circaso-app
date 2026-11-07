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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import com.circaso.core.crypto.toNSData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.presentationSize
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.kCMTimeZero
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerLooper
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.CoreGraphics.CGRectZero
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.AVFoundation.currentItem
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
import platform.UIKit.UIBlurEffect
import platform.UIKit.UIBlurEffectStyle
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode
import platform.UIKit.UIVisualEffectView
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
    /** PHPicker for photos and videos, up to [max] in the order ticked; empty when the person cancels. */
    fun pickMany(max: Int, completion: (List<LibraryMedia>) -> Unit)
    /** Limited library: the system's picker to choose more photos (iOS 15+), then [completion]. */
    fun manageLimited(completion: () -> Unit)
    /** [count] small JPEGs spread through the video at [path] (AVAssetImageGenerator), for the trim bar. */
    fun filmstrip(path: String, count: Int, completion: (List<NSData>) -> Unit)
    /** The video at [path] from [startMs] to [endMs] as MP4 (AVAssetExportSession), within the moment size limit; null if it can't be. */
    fun trim(path: String, startMs: Long, endMs: Long, completion: (LibraryMedia?) -> Unit)
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
    override suspend fun access(): LibraryAccess = access(ask = true)

    private suspend fun access(ask: Boolean): LibraryAccess {
        val status = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)
        val answer = if (status == PHAuthorizationStatusNotDetermined && ask) {
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

    /** Photos and videos share one permission on iPhone; audio has no library an app can list (Files only). */
    override suspend fun recents(kind: LibraryKind, limit: Int, ask: Boolean): LibraryRecents {
        if (kind == LibraryKind.Audio) return LibraryRecents(emptyList(), LibraryAccess.Granted)
        val access = access(ask)
        if (access == LibraryAccess.Denied || limit <= 0) return LibraryRecents(emptyList(), access)
        return LibraryRecents(fetch(kind, limit), access)
    }

    override suspend fun manage(kind: LibraryKind): LibraryAccess {
        when (access(ask = true)) {
            LibraryAccess.Granted -> Unit
            LibraryAccess.Limited -> MediaLibraryBridge.provider?.let { p ->
                suspendCancellableCoroutine<Unit> { cont -> p.manageLimited { if (cont.isActive) cont.resume(Unit) } }
            }
            LibraryAccess.Denied -> openAppSettings()
        }
        return access(ask = false)
    }

    private suspend fun fetch(kind: LibraryKind, limit: Int): List<LibraryItem> {
        return withContext(Dispatchers.Default) {
            val options = PHFetchOptions().apply {
                sortDescriptors = listOf(NSSortDescriptor.sortDescriptorWithKey("creationDate", ascending = false))
                fetchLimit = limit.toULong()
            }
            val video = kind == LibraryKind.Video
            val result = PHAsset.fetchAssetsWithMediaType(if (video) PHAssetMediaTypeVideo else PHAssetMediaTypeImage, options)
            (0 until result.count.toInt()).mapNotNull { i ->
                val asset = result.objectAtIndex(i.toULong()) as? PHAsset ?: return@mapNotNull null
                LibraryItem(
                    id = asset.localIdentifier, kind = kind, durationMs = if (video) (asset.duration * 1000).toLong() else null,
                    addedAt = asset.creationDate?.let { (it.timeIntervalSince1970 * 1000).toLong() } ?: 0,
                )
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

    /**
     * The original image data (iCloud too) through Photos, then the profile photo's own encoder: full size up to
     * AvatarMaxSide, not a moment's 2048 px. Leaving the sheet cancels the request.
     */
    override suspend fun loadAvatar(item: LibraryItem): PickedImage? {
        if (item.kind != LibraryKind.Photo) return null
        val asset = withContext(Dispatchers.Default) {
            PHAsset.fetchAssetsWithLocalIdentifiers(listOf(item.id), null).firstObject as? PHAsset
        } ?: return null
        val data = suspendCancellableCoroutine<NSData?> { cont ->
            val options = PHImageRequestOptions().apply {
                networkAccessAllowed = true
                deliveryMode = platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
                version = platform.Photos.PHImageRequestOptionsVersionCurrent
            }
            val request = PHImageManager.defaultManager().requestImageDataAndOrientationForAsset(asset, options) { data, _, _, _ ->
                if (cont.isActive) cont.resume(data)
            }
            cont.invokeOnCancellation { PHImageManager.defaultManager().cancelImageRequest(request) }
        } ?: return null
        return withContext(Dispatchers.Default) { scaledJpeg(data) }
    }

    override suspend fun pick(kind: LibraryKind): CapturedMedia? {
        val p = MediaLibraryBridge.provider ?: return null
        val code = when (kind) { LibraryKind.Photo -> 0; LibraryKind.Video -> 1; LibraryKind.Audio -> 2 }
        return suspendCancellableCoroutine<LibraryMedia?> { cont -> p.pick(code) { if (cont.isActive) cont.resume(it) } }?.toCaptured()
    }

    override suspend fun pickMany(max: Int): List<CapturedMedia> {
        val p = MediaLibraryBridge.provider ?: return emptyList()
        return suspendCancellableCoroutine<List<LibraryMedia>> { cont -> p.pickMany(max) { if (cont.isActive) cont.resume(it) } }.map { it.toCaptured() }
    }

    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.Default) { NSData.dataWithContentsOfFile(path)?.toByteArray() }

    override suspend fun filmstrip(path: String, count: Int): List<ByteArray> {
        val p = MediaLibraryBridge.provider ?: return emptyList()
        return suspendCancellableCoroutine<List<NSData>> { cont -> p.filmstrip(path, count) { if (cont.isActive) cont.resume(it) } }.map { it.toByteArray() }
    }

    override suspend fun trim(path: String, startMs: Long, endMs: Long): CapturedMedia? {
        val p = MediaLibraryBridge.provider ?: return null
        return suspendCancellableCoroutine<LibraryMedia?> { cont -> p.trim(path, startMs, endMs) { if (cont.isActive) cont.resume(it) } }?.toCaptured()
    }

    /** A video stays on disk (empty bytes, [CapturedMedia.path]); [read] brings it in when it is posted. */
    private fun LibraryMedia.toCaptured() = CapturedMedia(
        bytes = if (mime.startsWith("video") && path != null) ByteArray(0) else data.toByteArray(),
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

/**
 * A plain view whose player layer follows its bounds, without implicit animations. A clip shaped like the screen fills
 * it (aspect fill, cropped); one that would be cropped and blown up far past fitting (landscape on an upright phone)
 * fits, letterboxed over its own poster, blurred ([MomentVideoEncoding.fills], Pasaka 2026-11-07).
 */
private class MomentPlayerView : UIView(frame = CGRectZero.readValue()) {
    val playerLayer = AVPlayerLayer()
    private val backdrop = UIImageView(frame = CGRectZero.readValue())
    private val blur = UIVisualEffectView(effect = UIBlurEffect.effectWithStyle(UIBlurEffectStyle.UIBlurEffectStyleDark))

    init {
        backgroundColor = UIColor.blackColor
        clipsToBounds = true
        backdrop.contentMode = UIViewContentMode.UIViewContentModeScaleAspectFill
        backdrop.setHidden(true)
        blur.setHidden(true)
        addSubview(backdrop)
        addSubview(blur)
        playerLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
        layer.addSublayer(playerLayer)
    }

    fun setPoster(jpeg: ByteArray?) {
        backdrop.image = jpeg?.let { UIImage.imageWithData(it.toNSData()) }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = bounds
        backdrop.setFrame(bounds)
        blur.setFrame(bounds)
        val video = playerLayer.player?.currentItem?.presentationSize?.useContents { width.toInt() to height.toInt() }
        val view = bounds.useContents { size.width.toInt() to size.height.toInt() }
        val fills = video == null || MomentVideoEncoding.fills(video.first, video.second, view.first, view.second)
        playerLayer.videoGravity = if (fills) AVLayerVideoGravityResizeAspectFill else AVLayerVideoGravityResizeAspect
        backdrop.setHidden(fills || backdrop.image == null)
        blur.setHidden(backdrop.isHidden())
        CATransaction.commit()
    }
}

/**
 * AVQueuePlayer in an aspect-filled layer, no controls; [loop] repeats through AVPlayerLooper. Released on dispose.
 * The poster covers the layer until it has a frame to show (`readyForDisplay`), and stays if the item fails.
 */
@Composable
actual fun MomentVideo(path: String, playing: Boolean, muted: Boolean, modifier: Modifier, loop: Boolean, poster: ByteArray?, onUnplayable: () -> Unit, positionMs: Long?) {
    val player = remember(path) { AVQueuePlayer() }
    val view = remember(path) { MomentPlayerView().also { it.playerLayer.player = player } }
    LaunchedEffect(view, poster) { view.setPoster(poster); view.setNeedsLayout() }
    var shown by remember(path) { mutableStateOf(false) }
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
    LaunchedEffect(player, playing, loop, positionMs) { if (playing && positionMs == null) player.play() else player.pause() }
    // Scrubbing: the exact frame, at full resolution (the trimmer's stage follows the edge being dragged).
    LaunchedEffect(player, positionMs) {
        val at = positionMs ?: return@LaunchedEffect
        // As on Android: the nearest frame within half a second while the finger moves, the exact one once it rests.
        val near = CMTimeMake(value = 500, timescale = 1000)
        player.seekToTime(CMTimeMake(value = at, timescale = 1000), toleranceBefore = near, toleranceAfter = near)
        kotlinx.coroutines.delay(120)
        player.seekToTime(CMTimeMake(value = at, timescale = 1000), toleranceBefore = kCMTimeZero.readValue(), toleranceAfter = kCMTimeZero.readValue())
    }
    // No KVO from Compose: a light check until the first frame is ready, or the item has failed.
    LaunchedEffect(view) {
        while (!shown) {
            if (view.playerLayer.readyForDisplay) { shown = true; view.setNeedsLayout(); break }
            if (player.currentItem?.status == AVPlayerItemStatusFailed) { onUnplayable(); break }
            kotlinx.coroutines.delay(80)
        }
    }
    val posterImage = remember(poster) { poster?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() } }
    Box(modifier) {
        UIKitView(
            factory = { view },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.playerLayer.player = null },
            properties = UIKitInteropProperties(interactionMode = null), // touches stay with Compose (hold to pause)
        )
        // The same blur as the viewer's loading placeholder (V5), fading out as the first frame lands.
        if (posterImage != null) {
            androidx.compose.animation.AnimatedVisibility(
                !shown, Modifier.fillMaxSize(),
                enter = androidx.compose.animation.EnterTransition.None,
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.QuickMs)),
            ) {
                Image(posterImage, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(24.dp).graphicsLayer { scaleX = 1.2f; scaleY = 1.2f })
            }
        }
    }
}
