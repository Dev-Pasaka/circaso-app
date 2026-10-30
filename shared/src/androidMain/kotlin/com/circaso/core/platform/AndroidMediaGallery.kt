package com.circaso.core.platform

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The library through MediaStore: images and videos, newest first. Access is Android's own prompt ("Allow all" or,
 * from Android 14, "Select photos"); with a selection only the chosen items come back. Photos are scaled to
 * [ChatPhotoMaxSide] and re-encoded as JPEG before they're sent, like the picker's.
 */
class AndroidMediaGallery(private val context: Context) : MediaGallery {
    override val available = true

    private fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun granted(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            has(Manifest.permission.READ_MEDIA_IMAGES) || has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> has(Manifest.permission.READ_MEDIA_IMAGES)
        else -> has(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    override suspend fun requestAccess(): Boolean {
        if (granted() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(Manifest.permission.READ_MEDIA_VIDEO) || has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Images and videos are one permission group: the second is granted with the first, without a prompt.
            PermissionBridge.request(Manifest.permission.READ_MEDIA_IMAGES)
            PermissionBridge.request(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            PermissionBridge.request(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return granted()
    }

    override suspend fun recent(limit: Int): List<GalleryItem> = withContext(Dispatchers.IO) {
        if (!granted()) return@withContext emptyList()
        val files = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT, MediaStore.Video.VideoColumns.DURATION,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val items = mutableListOf<GalleryItem>()
        runCatching {
            context.contentResolver.query(files, projection, selection, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val type = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val w = c.getColumnIndex(MediaStore.Files.FileColumns.WIDTH)
                val h = c.getColumnIndex(MediaStore.Files.FileColumns.HEIGHT)
                val d = c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
                while (c.moveToNext() && items.size < limit) {
                    val video = c.getInt(type) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val uri = ContentUris.withAppendedId(base, c.getLong(id))
                    items += GalleryItem(
                        id = c.getLong(id).toString(), uri = uri.toString(), video = video,
                        durationMs = if (video && d >= 0) c.getLong(d).takeIf { it > 0 } else null,
                        width = if (w >= 0) c.getInt(w).takeIf { it > 0 } else null,
                        height = if (h >= 0) c.getInt(h).takeIf { it > 0 } else null,
                    )
                }
            }
        }
        items
    }

    override suspend fun thumbnail(item: GalleryItem, sizePx: Int): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(item.uri)
            val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
            } else if (item.video) {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, item.id.toLong(), MediaStore.Video.Thumbnails.MINI_KIND, null)
            } else {
                decodeScaled(context, uri, sizePx)
            }
            bitmap?.let { b -> ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray() }
        }.getOrNull()
    }

    override suspend fun loadImage(item: GalleryItem): PickedImage? = withContext(Dispatchers.IO) {
        if (item.video) return@withContext null
        val bitmap = decodeScaled(context, Uri.parse(item.uri), ChatPhotoMaxSide) ?: return@withContext null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        PickedImage(out.toByteArray(), "image/jpeg", bitmap.width, bitmap.height).also { bitmap.recycle() }
    }

    override suspend fun loadVideo(item: GalleryItem): PreparedVideo? =
        if (item.video) runCatching { prepareVideo(context, Uri.parse(item.uri)) }.getOrNull() else null

    private companion object {
        /** A chat photo's longest side: sharp on any phone, a few hundred KB. */
        const val ChatPhotoMaxSide = 1600
    }
}
