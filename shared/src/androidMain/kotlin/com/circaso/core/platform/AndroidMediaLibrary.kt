package com.circaso.core.platform

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** The system pickers for moments, registered by the activity like [ImagePickerBridge]; neither needs a permission. */
object MomentPickerBridge {
    private var visual: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var audio: ActivityResultLauncher<String>? = null
    private var pending: CompletableDeferred<Uri?>? = null

    fun register(activity: ComponentActivity) {
        visual = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> pending?.complete(uri) }
        audio = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> pending?.complete(uri) }
    }

    suspend fun pick(kind: LibraryKind): Uri? {
        val answer = CompletableDeferred<Uri?>().also { pending = it }
        when (kind) {
            LibraryKind.Photo -> visual?.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) ?: return null
            LibraryKind.Video -> visual?.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) ?: return null
            LibraryKind.Audio -> audio?.launch("audio/*") ?: return null
        }
        return answer.await()
    }
}

/** MediaStore for the recents strip (WhatsApp-style), the system pickers for the rest. */
class AndroidMediaLibrary(private val context: Context) : MediaLibrary {

    private fun permissionFor(kind: LibraryKind) = if (Build.VERSION.SDK_INT >= 33) when (kind) {
        LibraryKind.Photo -> Manifest.permission.READ_MEDIA_IMAGES
        LibraryKind.Video -> Manifest.permission.READ_MEDIA_VIDEO
        LibraryKind.Audio -> Manifest.permission.READ_MEDIA_AUDIO
    } else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    override suspend fun access(): LibraryAccess = when {
        granted(permissionFor(LibraryKind.Photo)) -> LibraryAccess.Granted
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> LibraryAccess.Limited
        else -> LibraryAccess.Denied
    }

    override suspend fun recent(kind: LibraryKind, limit: Int): List<LibraryItem> {
        val permission = permissionFor(kind)
        val partial = Build.VERSION.SDK_INT >= 34 && kind != LibraryKind.Audio && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        if (!granted(permission) && !partial && !PermissionBridge.request(permission)) return emptyList()
        return withContext(Dispatchers.IO) {
            val collection = when (kind) {
                LibraryKind.Photo -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                LibraryKind.Video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                LibraryKind.Audio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val columns = buildList {
                add(MediaStore.MediaColumns._ID)
                if (kind != LibraryKind.Photo) add(MediaStore.MediaColumns.DURATION)
                if (kind == LibraryKind.Audio) add(MediaStore.MediaColumns.TITLE)
            }.toTypedArray()
            val items = mutableListOf<LibraryItem>()
            runCatching {
                context.contentResolver.query(collection, columns, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val durCol = if (kind != LibraryKind.Photo) c.getColumnIndex(MediaStore.MediaColumns.DURATION) else -1
                    val titleCol = if (kind == LibraryKind.Audio) c.getColumnIndex(MediaStore.MediaColumns.TITLE) else -1
                    while (c.moveToNext() && items.size < limit) {
                        val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                        items += LibraryItem(uri.toString(), kind, durationMs = if (durCol >= 0) c.getLong(durCol) else null, title = if (titleCol >= 0) c.getString(titleCol) else null)
                    }
                }
            }
            items
        }
    }

    override suspend fun load(item: LibraryItem): CapturedMedia? = load(Uri.parse(item.id), item.kind)

    override suspend fun pick(kind: LibraryKind): CapturedMedia? {
        val uri = MomentPickerBridge.pick(kind) ?: return null
        return load(uri, kind)
    }

    private suspend fun load(uri: Uri, kind: LibraryKind): CapturedMedia? = withContext(Dispatchers.IO) {
        runCatching {
            when (kind) {
                LibraryKind.Photo -> {
                    val bitmap = decodeScaled(context, uri, MomentPhotoMaxSide) ?: return@runCatching null
                    CapturedMedia(bitmap.toJpeg(90), "image/jpeg", bitmap.width, bitmap.height, thumb = bitmap.thumb())
                }
                LibraryKind.Video, LibraryKind.Audio -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val mime = context.contentResolver.getType(uri) ?: if (kind == LibraryKind.Video) "video/mp4" else "audio/mpeg"
                        // A copy in the cache, so the review player can read it as a file.
                        val ext = if (kind == LibraryKind.Video) "mp4" else "m4a"
                        val file = File(context.cacheDir, "moment-pick-${System.currentTimeMillis()}.$ext")
                        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
                        val frame = if (kind == LibraryKind.Video) retriever.getFrameAtTime(0) else null
                        CapturedMedia(file.readBytes(), mime, frame?.width, frame?.height, durationMs = duration, thumb = frame?.thumb(), path = file.absolutePath)
                    } finally {
                        retriever.release()
                    }
                }
            }
        }.getOrNull()
    }
}

private fun Bitmap.toJpeg(quality: Int): ByteArray = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

private fun Bitmap.thumb(): ByteArray {
    val f = MomentThumbSide.toFloat() / max(width, height)
    return Bitmap.createScaledBitmap(this, (width * f).roundToInt().coerceAtLeast(1), (height * f).roundToInt().coerceAtLeast(1), true).toJpeg(50)
}

@Composable
actual fun LibraryThumbnail(item: LibraryItem, modifier: Modifier) {
    if (item.kind == LibraryKind.Audio) return
    val context = LocalContext.current
    // The media store's own thumbnail (a video's frame too, which an image loader can't give), small and cached by Android.
    val bitmap = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(item.id)
                if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, android.util.Size(256, 256), null)
                else if (item.kind == LibraryKind.Video) MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, ContentUris.parseId(uri), MediaStore.Video.Thumbnails.MINI_KIND, null)
                else MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, ContentUris.parseId(uri), MediaStore.Images.Thumbnails.MINI_KIND, null)
            }.getOrNull()?.asImageBitmap()
        }
    }.value
    if (bitmap != null) androidx.compose.foundation.Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

@Composable
actual fun MomentVideo(path: String, playing: Boolean, muted: Boolean, modifier: Modifier, loop: Boolean) {
    val context = LocalContext.current
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    LaunchedEffect(player, playing) { player.playWhenReady = playing }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    DisposableEffect(player) { onDispose { player.release() } }
    // A bare TextureView, no PlayerView: nothing of Media3's own UI can ever show (Pasaka, 2026-11-03). The video
    // is scaled to fill and cropped, centred, as a short-form clip should be.
    AndroidView(
        factory = { ctx ->
            android.view.TextureView(ctx).also { view ->
                player.setVideoTextureView(view)
                fun crop(videoW: Int, videoH: Int) {
                    if (videoW == 0 || videoH == 0 || view.width == 0 || view.height == 0) return
                    val vw = view.width.toFloat(); val vh = view.height.toFloat()
                    val scale = max(vw / videoW, vh / videoH)
                    view.setTransform(android.graphics.Matrix().apply {
                        setScale(videoW * scale / vw, videoH * scale / vh)
                        postTranslate((vw - videoW * scale) / 2f, (vh - videoH * scale) / 2f)
                    })
                }
                player.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        val w = (videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt()
                        crop(w, videoSize.height)
                    }
                })
                view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    val v = player.videoSize; crop((v.width * v.pixelWidthHeightRatio).roundToInt(), v.height)
                }
            }
        },
        modifier = modifier,
    )
}
