package com.circaso.core.platform

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** PdfRenderer draws one page at a time and isn't thread-safe: one thread for all of them, off the main one. */
@OptIn(ExperimentalCoroutinesApi::class)
private val PdfThread = Dispatchers.IO.limitedParallelism(1)

/** Where a document closed while a page was still being drawn lets go of its file. */
private val PdfCloser = CoroutineScope(SupervisorJob() + PdfThread)

private class AndroidPdfDocument(
    private val renderer: PdfRenderer,
    private val file: ParcelFileDescriptor,
    override val pageSizes: List<PdfPageSize>,
) : PdfDocument {
    private val lock = Mutex()
    private var closed = false
    override val pageCount: Int get() = pageSizes.size

    override suspend fun render(page: Int, widthPx: Int): ImageBitmap? = lock.withLock {
        if (closed || page !in pageSizes.indices) return@withLock null
        withContext(PdfThread) {
            runCatching {
                renderer.openPage(page).use { p ->
                    val w = widthPx.coerceIn(1, MaxPagePx)
                    val h = (w * p.height / p.width.toFloat().coerceAtLeast(1f)).roundToInt().coerceIn(1, MaxPagePx * 2)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE) // pages without a background of their own are white on paper
                    p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    override fun close() {
        PdfCloser.launch {
            withContext(NonCancellable) {
                lock.withLock {
                    if (closed) return@withLock
                    closed = true
                    runCatching { renderer.close() }
                    runCatching { file.close() }
                }
            }
        }
    }
}

/** The widest a page is drawn: past this a page's bitmap costs more memory than the screen can show. */
private const val MaxPagePx = 4096

actual suspend fun openPdf(path: String): PdfOpen = withContext(PdfThread) {
    val fd = runCatching { ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
        ?: return@withContext PdfOpen.Unreadable
    val renderer = try {
        PdfRenderer(fd)
    } catch (_: SecurityException) {
        runCatching { fd.close() }
        return@withContext PdfOpen.Locked // password-protected
    } catch (_: Throwable) {
        runCatching { fd.close() }
        return@withContext PdfOpen.Unreadable
    }
    val sizes = runCatching {
        (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { PdfPageSize(it.width.toFloat(), it.height.toFloat()) } }
    }.getOrNull()
    if (sizes.isNullOrEmpty()) {
        runCatching { renderer.close() }
        runCatching { fd.close() }
        PdfOpen.Unreadable
    } else {
        PdfOpen.Opened(AndroidPdfDocument(renderer, fd, sizes))
    }
}

actual suspend fun pdfPreview(path: String): PdfPreview? = withContext(PdfThread) {
    runCatching {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                val pages = renderer.pageCount
                if (pages <= 0) return@use null
                renderer.openPage(0).use { p ->
                    for (width in PdfThumbWidths) {
                        val h = (width * p.height / p.width.toFloat().coerceAtLeast(1f)).roundToInt().coerceIn(1, width * 3)
                        val bitmap = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        var quality = 70
                        while (quality >= 40) {
                            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                            if (bytes.size <= PdfThumbMaxBytes) { bitmap.recycle(); return@use PdfPreview(bytes, pages) }
                            quality -= 10
                        }
                        bitmap.recycle()
                    }
                    null
                }
            }
        }
    }.getOrNull()
}

actual suspend fun shareFile(path: String, name: String, mime: String): Boolean {
    val activity = runCatching { KoinPlatform.getKoin().get<CurrentActivity>().get() }.getOrNull() ?: return false
    // A copy under its own name in the folder the FileProvider serves (res/xml/shared_files.xml), off the main thread.
    val file = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(activity.cacheDir, "shared/send-${System.nanoTime()}").apply { mkdirs() }
            File(dir, name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "File" }).also { File(path).copyTo(it, overwrite = true, bufferSize = 256 * 1024) }
        }.getOrNull()
    } ?: return false
    return runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, name))
        true
    }.getOrDefault(false)
}
