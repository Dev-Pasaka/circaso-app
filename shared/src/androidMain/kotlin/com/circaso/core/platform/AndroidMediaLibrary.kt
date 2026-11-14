package com.circaso.core.platform

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/** The system pickers for moments, registered by the activity like [ImagePickerBridge]; neither needs a permission. */
object MomentPickerBridge {
    private var visual: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var visualMany: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var audio: ActivityResultLauncher<String>? = null
    private var document: ActivityResultLauncher<Array<String>>? = null
    private var pending: CompletableDeferred<Uri?>? = null
    private var pendingMany: CompletableDeferred<List<Uri>>? = null
    private var cloud: ActivityResultLauncher<android.content.Intent>? = null

    fun register(activity: ComponentActivity) {
        cloud = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val uris = if (result.resultCode != android.app.Activity.RESULT_OK || data == null) emptyList() else buildList {
                val clip = data.clipData
                if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add)
                else data.data?.let(::add)
            }
            pendingMany?.complete(uris)
        }
        visual = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> pending?.complete(uri) }
        visualMany = activity.registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MomentPickMax)) { uris -> pendingMany?.complete(uris) }
        audio = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> pending?.complete(uri) }
        document = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pending?.complete(uri) }
    }

    /** The system's Files picker for any document (a chat's Document); no permission needed. */
    suspend fun pickDocument(): Uri? {
        val answer = CompletableDeferred<Uri?>().also { pending = it }
        document?.launch(arrayOf("*/*")) ?: return null
        return answer.await()
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

    /**
     * Google Photos' own picker (its sheet, with everything in the person's Google Photos, cloud included), several at
     * once; null when Google Photos isn't on this phone (the caller then uses the system's photo picker).
     */
    suspend fun pickGooglePhotos(): List<Uri>? {
        val launcher = cloud ?: return null
        val intent = googlePhotosIntent()
        val answer = CompletableDeferred<List<Uri>>().also { pendingMany = it }
        val started = runCatching { launcher.launch(intent) }.isSuccess
        if (!started) { pendingMany = null; return null }
        return answer.await()
    }

    /** Photos and videos, several at once, from Google Photos' own picker. */
    fun googlePhotosIntent(): android.content.Intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
        .setType("*/*")
        .putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        .putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        .addCategory(android.content.Intent.CATEGORY_OPENABLE)
        .setPackage(GooglePhotos)

    /** Google Photos' package, named in the manifest's `<queries>` so the app may see whether it's installed. */
    const val GooglePhotos = "com.google.android.apps.photos"

    /** Photos and videos together, up to [MomentPickMax], in the order they were ticked. */
    suspend fun pickMany(): List<Uri> {
        val answer = CompletableDeferred<List<Uri>>().also { pendingMany = it }
        visualMany?.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) ?: return emptyList()
        return answer.await()
    }
}

/**
 * MediaStore for the recents strip and the gallery (WhatsApp-style), the system pickers for the rest.
 *
 * Each kind has its own permission on Android 13+ (photos, videos, audio), so access to photos says nothing about
 * videos: each kind is asked the first time it shows. On Android 14 the person may allow only "selected photos and
 * videos" (READ_MEDIA_VISUAL_USER_SELECTED); that is [LibraryAccess.Limited] and Manage asks again, which shows the
 * system's own selection sheet. Whether a kind was asked is kept, so a limited choice isn't asked again on every open.
 */
class AndroidMediaLibrary(private val context: Context) : MediaLibrary {
    private val prefs by lazy { context.getSharedPreferences("moments_library", Context.MODE_PRIVATE) }

    private fun permissionFor(kind: LibraryKind) = if (Build.VERSION.SDK_INT >= 33) when (kind) {
        LibraryKind.Photo -> Manifest.permission.READ_MEDIA_IMAGES
        LibraryKind.Video -> Manifest.permission.READ_MEDIA_VIDEO
        LibraryKind.Audio -> Manifest.permission.READ_MEDIA_AUDIO
    } else Manifest.permission.READ_EXTERNAL_STORAGE

    /** What one prompt for [kind] asks: on Android 14 the partial "selected" access goes with photos and videos. */
    private fun requestFor(kind: LibraryKind): Array<String> =
        if (Build.VERSION.SDK_INT >= 34 && kind != LibraryKind.Audio) arrayOf(permissionFor(kind), Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else arrayOf(permissionFor(kind))

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /**
     * Full access to this kind wins. Android 14 also reports the partial permission as granted alongside full access
     * to another kind, so "selected" counts only once this kind has been asked: before that it is unknown, not limited.
     */
    private fun accessFor(kind: LibraryKind): LibraryAccess = when {
        granted(permissionFor(kind)) -> LibraryAccess.Granted
        Build.VERSION.SDK_INT >= 34 && kind != LibraryKind.Audio && asked(kind) && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> LibraryAccess.Limited
        else -> LibraryAccess.Denied
    }

    private fun asked(kind: LibraryKind) = prefs.getBoolean("asked_${kind.name}", false)
    private fun markAsked(kind: LibraryKind) = prefs.edit().putBoolean("asked_${kind.name}", true).apply()

    override suspend fun access(): LibraryAccess = accessFor(LibraryKind.Photo)

    override suspend fun recents(kind: LibraryKind, limit: Int, ask: Boolean): LibraryRecents {
        var access = accessFor(kind)
        if (access == LibraryAccess.Denied && ask && !asked(kind)) {
            PermissionBridge.requestAll(requestFor(kind))
            markAsked(kind)
            access = accessFor(kind)
        }
        if (access == LibraryAccess.Denied) return LibraryRecents(emptyList(), access)
        return LibraryRecents(query(kind, limit), access)
    }

    /** One system prompt for photos and videos together (the gallery shows both), not two in a row. */
    override suspend fun recentsVisual(limit: Int, ask: Boolean): Pair<LibraryRecents, LibraryRecents> {
        val kinds = listOf(LibraryKind.Photo, LibraryKind.Video)
        val unasked = kinds.filter { accessFor(it) == LibraryAccess.Denied && !asked(it) }
        if (ask && unasked.isNotEmpty()) {
            PermissionBridge.requestAll(unasked.flatMap { requestFor(it).toList() }.distinct().toTypedArray())
            unasked.forEach(::markAsked)
        }
        return recents(LibraryKind.Photo, limit, ask = false) to recents(LibraryKind.Video, limit, ask = false)
    }

    override suspend fun manage(kind: LibraryKind): LibraryAccess {
        val before = accessFor(kind)
        if (before == LibraryAccess.Granted) return before
        // Limited, or never asked: the system prompt (on Android 14 its selection sheet). Denied after being asked:
        // Android won't prompt again, so the app's settings, where the person can allow it.
        val started = System.currentTimeMillis()
        if (before == LibraryAccess.Limited || !asked(kind)) {
            PermissionBridge.requestAll(requestFor(kind))
            markAsked(kind)
        }
        val after = accessFor(kind)
        // A refusal Android answers at once (no prompt shown, "don't ask again"): send the person to settings.
        if (after == LibraryAccess.Denied && System.currentTimeMillis() - started < 400) openAppSettings()
        return after
    }

    private suspend fun query(kind: LibraryKind, limit: Int, offset: Int = 0, strict: Boolean = false): List<LibraryItem> = withContext(Dispatchers.IO) {
        val collection = when (kind) {
            LibraryKind.Photo -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            LibraryKind.Video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            LibraryKind.Audio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val columns = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (kind != LibraryKind.Photo) add(MediaStore.MediaColumns.DURATION)
            if (kind == LibraryKind.Audio) add(MediaStore.MediaColumns.TITLE)
        }.toTypedArray()
        val items = mutableListOf<LibraryItem>()
        val read = runCatching {
            context.contentResolver.query(collection, columns, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                // A later page starts where the last one ended (the cursor's window moves there, nothing is read twice).
                if (offset > 0 && !c.moveToPosition(offset - 1)) return@use
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val addedCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val durCol = if (kind != LibraryKind.Photo) c.getColumnIndex(MediaStore.MediaColumns.DURATION) else -1
                val titleCol = if (kind == LibraryKind.Audio) c.getColumnIndex(MediaStore.MediaColumns.TITLE) else -1
                while (c.moveToNext() && items.size < limit) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                    items += LibraryItem(
                        uri.toString(), kind,
                        durationMs = if (durCol >= 0) c.getLong(durCol) else null,
                        title = if (titleCol >= 0) c.getString(titleCol) else null,
                        addedAt = if (addedCol >= 0) c.getLong(addedCol) * 1000 else 0,
                    )
                }
            } ?: if (strict) error("MediaStore gave no cursor") else Unit
        }
        if (strict) read.getOrThrow()
        items
    }

    override suspend fun load(item: LibraryItem): CapturedMedia? = load(Uri.parse(item.id), item.kind)

    /** The profile photo's own encoder (full size up to AvatarMaxSide), not a moment's 2048 px. */
    override suspend fun loadAvatar(item: LibraryItem): PickedImage? {
        if (item.kind != LibraryKind.Photo) return null
        return withContext(Dispatchers.IO) { runCatching { encodeForUpload(context, Uri.parse(item.id)) }.getOrNull() }
    }

    override suspend fun pick(kind: LibraryKind): CapturedMedia? {
        val uri = MomentPickerBridge.pick(kind) ?: return null
        return load(uri, kind)
    }

    /** Google Photos can open its picker here (installed and enabled): resolved for the exact intent it gets. */
    private fun canOpenGooglePhotos(): Boolean = runCatching {
        context.packageManager.resolveActivity(MomentPickerBridge.googlePhotosIntent(), 0) != null
    }.getOrDefault(false)

    override suspend fun cloudLabel(): String = withContext(Dispatchers.IO) { if (canOpenGooglePhotos()) "Google Photos" else "Photos" }

    /** Google Photos' picker when it can open, else the system's photo picker (which shows cloud items too). */
    override suspend fun pickCloud(max: Int): CloudPicks {
        val uris = (if (withContext(Dispatchers.IO) { canOpenGooglePhotos() }) MomentPickerBridge.pickGooglePhotos() else null)
            ?: return CloudPicks(pickMany(max))
        val media = uris.take(max).mapNotNull { uri ->
            val video = withContext(Dispatchers.IO) { context.contentResolver.getType(uri) }?.startsWith("video") == true
            load(uri, if (video) LibraryKind.Video else LibraryKind.Photo)
        }
        return CloudPicks(media, more = (uris.size - max).coerceAtLeast(0))
    }

    /** The next page straight from MediaStore, from [offset] on; a read that fails throws (the gallery keeps its page). */
    override suspend fun page(kind: LibraryKind, offset: Int, limit: Int): List<LibraryItem> {
        if (accessFor(kind) == LibraryAccess.Denied) return emptyList()
        return query(kind, limit, offset, strict = true)
    }

    override suspend fun pickMany(max: Int): List<CapturedMedia> {
        val uris = MomentPickerBridge.pickMany().take(max)
        return uris.mapNotNull { uri ->
            val video = withContext(Dispatchers.IO) { context.contentResolver.getType(uri) }?.startsWith("video") == true
            load(uri, if (video) LibraryKind.Video else LibraryKind.Photo)
        }
    }

    /**
     * A video is copied to the cache and handed over by [CapturedMedia.path] with empty bytes: ten picked videos held
     * in memory at once would not fit. [read] brings each one in only when it is posted.
     */
    private suspend fun load(uri: Uri, kind: LibraryKind): CapturedMedia? = withContext(Dispatchers.IO) {
        runCatching {
            when (kind) {
                LibraryKind.Photo -> {
                    val bitmap = decodeScaled(context, uri, MomentPhotoMaxSide) ?: return@runCatching null
                    CapturedMedia(bitmap.toJpeg(90), "image/jpeg", bitmap.width, bitmap.height, thumb = bitmap.thumb())
                }
                LibraryKind.Video, LibraryKind.Audio -> {
                    val ext = if (kind == LibraryKind.Video) "mp4" else "m4a"
                    // A copy in the cache, so the review player and the trimmer can read it as a file.
                    val file = File(context.cacheDir, "moment-pick-${System.nanoTime()}.$ext")
                    context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
                    val mime = context.contentResolver.getType(uri) ?: if (kind == LibraryKind.Video) "video/mp4" else "audio/mpeg"
                    if (kind == LibraryKind.Audio) {
                        val duration = metadata(file.absolutePath)?.durationMs ?: 0L
                        CapturedMedia(file.readBytes(), mime, null, null, durationMs = duration, path = file.absolutePath)
                    } else {
                        val meta = metadata(file.absolutePath)
                        CapturedMedia(ByteArray(0), mime, meta?.width, meta?.height, durationMs = meta?.durationMs ?: 0L, thumb = meta?.frame?.thumb(), path = file.absolutePath)
                    }
                }
            }
        }.getOrNull()
    }

    override suspend fun loadFile(path: String, mime: String): CapturedMedia? = withContext(Dispatchers.IO) {
        runCatching {
            if (mime.startsWith("video")) {
                val meta = metadata(path)
                CapturedMedia(ByteArray(0), mime, meta?.width, meta?.height, durationMs = meta?.durationMs ?: 0L, thumb = meta?.frame?.thumb(), path = path)
            } else {
                val bitmap = decodeScaled(context, Uri.fromFile(File(path)), MomentPhotoMaxSide) ?: return@runCatching null
                CapturedMedia(bitmap.toJpeg(90), "image/jpeg", bitmap.width, bitmap.height, thumb = bitmap.thumb())
            }
        }.getOrNull()
    }

    /**
     * A document from Files: its own name and size from the provider, copied into this app's cache (the picker's grant
     * ends with the activity), read again when it is sent. Null when nothing was chosen or it can't be read.
     */
    override suspend fun pickFile(): PickedFile? {
        val uri = MomentPickerBridge.pickDocument() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                var name: String? = null
                var size = -1L
                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val shownName = name?.takeIf { it.isNotBlank() } ?: "File"
                // Over the limit by the provider's own count: refused at once, nothing is copied.
                if (size > ChatFileMaxBytes) return@runCatching PickedFile(shownName, mime, size)
                val dir = File(context.cacheDir, "picked").apply { mkdirs() }
                val out = File(dir, "doc-${System.nanoTime()}")
                // Stop reading past the attachment limit: the composer refuses it by its size anyway.
                val copied = resolver.openInputStream(uri)?.use { input -> out.outputStream().use { o -> copyCapped(input, o, ChatFileMaxBytes + 1) } } ?: return@runCatching null
                PickedFile(shownName, mime, if (size >= 0) maxOf(size, copied) else copied, path = out.absolutePath)
            }.getOrNull()
        }
    }

    private fun copyCapped(input: java.io.InputStream, output: java.io.OutputStream, cap: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (total < cap) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), cap - total).toInt())
            if (n < 0) break
            output.write(buffer, 0, n)
            total += n
        }
        return total
    }

    override suspend fun poster(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val frame = if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PosterSide, PosterSide)
            else retriever.getFrameAtTime(0)
            frame?.let { f ->
                val scale = PosterSide.toFloat() / max(f.width, f.height)
                val small = if (scale < 1f) Bitmap.createScaledBitmap(f, (f.width * scale).roundToInt().coerceAtLeast(1), (f.height * scale).roundToInt().coerceAtLeast(1), true) else f
                var quality = 70
                var bytes: ByteArray
                do { bytes = small.toJpeg(quality); quality -= 15 } while (bytes.size > 12_000 && quality > 20)
                bytes
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) { runCatching { File(path).takeIf { it.exists() }?.readBytes() }.getOrNull() }

    override suspend fun filmstrip(path: String, count: Int): List<ByteArray> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            if (durationUs <= 0 || count <= 0) return@withContext emptyList()
            (0 until count).mapNotNull { i ->
                val at = durationUs * (2 * i + 1) / (2 * count) // the middle of each slice
                val frame = if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
                else retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                frame?.toJpeg(60)
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Media3 Transformer (already in the app for chat videos): the clip from [startMs] to [endMs], re-encoded as H.264
     * (High profile where the encoder has it) with [MomentVideoEncoding.plan]: the source's size up to 1080 on the short
     * side, its frame rate, and a bit rate for that size that the 32 MiB limit allows for this length.
     */
    @OptIn(UnstableApi::class)
    override suspend fun trim(path: String, startMs: Long, endMs: Long, maxBytes: Long): CapturedMedia? {
        val out = File(context.cacheDir, "moment-trim-${System.nanoTime()}.mp4")
        val source = withContext(Dispatchers.IO) { probeVideo(path) }
        val plan = MomentVideoEncoding.plan(
            source?.width ?: 720, source?.height ?: 1280, source?.frameRate, source?.videoBitRate, (endMs - startMs).coerceAtLeast(1), maxBytes,
        )
        val item = MediaItem.Builder().setUri(Uri.fromFile(File(path)))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
            .build()
        val resize = source == null || plan.width != source.width || plan.height != source.height
        val edited = EditedMediaItem.Builder(item)
            .setEffects(if (resize) Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(plan.width, plan.height, Presentation.LAYOUT_SCALE_TO_FIT))) else Effects.EMPTY)
            .build()
        // A decoder that fails on this phone (the emulator's goldfish H.264 one, some phones' hardware ones) is left
        // out and the export starts again with the next, as the player does ([failedDecoders]). A hardware encoder that
        // overshoots its bit rate past the limit gets one more go at four fifths of it.
        var ok = false
        var bitRate = plan.bitRate
        var shrunk = false
        for (attempt in 0..3) {
            val encoder = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitRate).build())
                .build()
            val failed = export(edited, encoder, out)
            if (failed == null) {
                if (out.length() > maxBytes && !shrunk) {
                    Log.i("MomentVideo", "trim: ${out.length()} bytes is over the limit at ${bitRate / 1000} kbit/s, once more at 80 %")
                    bitRate = bitRate * 4 / 5; shrunk = true; continue
                }
                ok = true; break
            }
            if (failed.isEmpty() || !failedDecoders.add(failed)) break
            Log.i("MomentVideo", "trim: decoder $failed failed, trying the next")
        }
        if (!ok || !out.exists()) { out.delete(); return null }
        return withContext(Dispatchers.IO) {
            logEncode("trim ${startMs}-${endMs} ms", source, plan, probeVideo(out.absolutePath))
            val m = metadata(out.absolutePath)
            CapturedMedia(ByteArray(0), "video/mp4", m?.width, m?.height, durationMs = m?.durationMs ?: (endMs - startMs), thumb = m?.frame?.thumb(), path = out.absolutePath)
        }
    }

    /** One Transformer run: null when it worked, else the failed decoder's name ("" for any other failure). */
    @OptIn(UnstableApi::class)
    private suspend fun export(edited: EditedMediaItem, encoder: DefaultEncoderFactory, out: File): String? {
        out.delete()
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).filterNot { it.name in failedDecoders }
        }
        val decoders = DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).setMediaCodecSelector(selector).build()
        // Transformer runs on a thread with a Looper: the main one.
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoder)
                    .setAssetLoaderFactory(DefaultAssetLoaderFactory(
                        // The five-argument form: the same in the catalog's Media3 and the newer one the app resolves.
                        context, decoders, Clock.DEFAULT,
                        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context),
                        androidx.media3.datasource.DataSourceBitmapLoader(context),
                    ))
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) { if (cont.isActive) cont.resume(null) }
                        override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                            Log.w("MomentVideo", "trim failed: ${exception.errorCodeName} ${exception.codecInfo ?: ""}")
                            val decoder = exception.codecInfo?.takeIf { it.isDecoder }?.name.orEmpty()
                            if (cont.isActive) cont.resume(decoder)
                        }
                    })
                    .build()
                transformer.start(edited, out.absolutePath)
                cont.invokeOnCancellation { transformer.cancel(); out.delete() }
            }
        }
    }

    private class Meta(val width: Int?, val height: Int?, val durationMs: Long, val frame: Bitmap?)

    /** Size as shown (rotation applied), length and the first frame. */
    private fun metadata(path: String): Meta? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val sideways = rotation == 90 || rotation == 270
            val frame = if (w != null) r.getFrameAtTime(0) else null
            Meta(if (sideways) h else w, if (sideways) w else h, duration, frame)
        } finally {
            r.release()
        }
    }.getOrNull()
}

/** A chat video's poster: the long side in pixels (as the chat's own first-frame thumb has been). */
private const val PosterSide = 240

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
    if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

/**
 * Decoders that failed on this phone while playing a moment. The emulator's `c2.goldfish.h264.decoder` (API 37)
 * accepts a file and then fails on its first buffer (Codec2 "Failed to send buffer with BufferPool", 2026-11-03); some
 * phones' hardware decoders refuse odd sizes. A player that hits one starts again without it (the software decoder),
 * and every later player in this run skips it from the start.
 */
private val failedDecoders: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

@OptIn(UnstableApi::class)
@Composable
actual fun MomentVideo(path: String, playing: Boolean, muted: Boolean, modifier: Modifier, loop: Boolean, poster: ByteArray?, onUnplayable: () -> Unit, positionMs: Long?) {
    val context = LocalContext.current
    val unplayableNow by rememberUpdatedState(onUnplayable)
    var attempt by remember(path) { mutableIntStateOf(0) }
    var firstFrame by remember(path) { mutableStateOf(false) }
    // Filled (cropped) or letterboxed: MomentVideoEncoding.fills, decided once the video's size and the view's are known.
    var fills by remember(path) { mutableStateOf(true) }
    val player = remember(path, attempt) {
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).filterNot { it.name in failedDecoders }
        }
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true).setMediaCodecSelector(selector)
        ExoPlayer.Builder(context, renderers).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onPlayerError(error: PlaybackException) {
                val failed = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<MediaCodecDecoderException>().firstOrNull()?.codecInfo?.name
                if (failed != null && failedDecoders.add(failed) && attempt < 3) attempt++ // again, without that decoder
                else unplayableNow()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player, playing, positionMs) { player.playWhenReady = playing && positionMs == null }
    // Scrubbing: the exact frame while the finger rests, the nearest key frame while it moves fast (no queue of seeks).
    LaunchedEffect(player, positionMs) {
        val at = positionMs ?: return@LaunchedEffect
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
        player.seekTo(at)
        kotlinx.coroutines.delay(120)
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        player.seekTo(at)
    }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    val posterImage = remember(poster) { poster?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
    Box(modifier) {
        // Behind a letterboxed video: its own poster, blurred, so the bars are the clip's colours and never a void.
        // Darkened like iOS's dark blur; before Android 12 there is no blur, so no picture there, only the dark stage.
        if (!fills) {
            if (posterImage != null && Build.VERSION.SDK_INT >= 31) {
                Image(posterImage, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(24.dp).graphicsLayer { scaleX = 1.2f; scaleY = 1.2f })
            }
            Box(Modifier.fillMaxSize().background(com.circaso.designsystem.theme.CircasoTheme.colors.scrim))
        }
        // A bare TextureView, no PlayerView: nothing of Media3's own UI can ever show (Pasaka, 2026-11-03). A clip
        // shaped like the screen fills it and is cropped, centred, as short-form video should be; one that would be
        // cropped and blown up far past fitting (a landscape clip on a phone held upright) fits, letterboxed, so it
        // stays as sharp as it was shot (Pasaka, 2026-11-07). A new player gets a new view.
        key(player) {
            AndroidView(
                factory = { ctx ->
                    android.view.TextureView(ctx).also { view ->
                        view.isOpaque = false // a letterboxed clip shows what is behind it, not undefined pixels
                        player.setVideoTextureView(view)
                        fun crop(videoW: Int, videoH: Int) {
                            if (videoW == 0 || videoH == 0 || view.width == 0 || view.height == 0) return
                            val vw = view.width.toFloat(); val vh = view.height.toFloat()
                            val fill = MomentVideoEncoding.fills(videoW, videoH, view.width, view.height)
                            if (fill != fills) fills = fill
                            val scale = if (fill) max(vw / videoW, vh / videoH) else kotlin.math.min(vw / videoW, vh / videoH)
                            view.setTransform(android.graphics.Matrix().apply {
                                setScale(videoW * scale / vw, videoH * scale / vh)
                                postTranslate((vw - videoW * scale) / 2f, (vh - videoH * scale) / 2f)
                            })
                        }
                        player.addListener(object : Player.Listener {
                            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                                crop((videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt(), videoSize.height)
                            }
                        })
                        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            val v = player.videoSize; crop((v.width * v.pixelWidthHeightRatio).roundToInt(), v.height)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Until the first frame is on screen (or for good, if this phone can't play it): the poster, never black.
        // The same blur as the viewer's loading placeholder (V5), fading out as the first frame lands.
        if (posterImage != null) {
            androidx.compose.animation.AnimatedVisibility(
                !firstFrame, Modifier.fillMaxSize(),
                enter = androidx.compose.animation.EnterTransition.None,
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.QuickMs)),
            ) {
                Image(posterImage, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(24.dp).graphicsLayer { scaleX = 1.2f; scaleY = 1.2f })
            }
        }
    }
}
