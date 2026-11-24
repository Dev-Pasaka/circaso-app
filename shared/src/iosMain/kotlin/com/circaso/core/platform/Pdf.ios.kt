@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.circaso.core.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.kCGInterpolationHigh
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFPage
import platform.PDFKit.kPDFDisplayBoxCropBox
import kotlin.math.roundToInt

/** PDFKit draws off the main thread here, one page at a time per phone (it is not made for many at once). */
@OptIn(ExperimentalCoroutinesApi::class)
private val PdfThread = Dispatchers.Default.limitedParallelism(1)

private class IosPdfDocument(private var document: PDFDocument?, override val pageSizes: List<PdfPageSize>) : PdfDocument {
    private val lock = Mutex()
    override val pageCount: Int get() = pageSizes.size

    override suspend fun render(page: Int, widthPx: Int): ImageBitmap? = lock.withLock {
        val doc = document ?: return@withLock null
        if (page !in pageSizes.indices) return@withLock null
        withContext(PdfThread) {
            runCatching {
                val p = doc.pageAtIndex(page.toULong()) ?: return@runCatching null
                val w = widthPx.coerceIn(1, MaxPagePx)
                val h = (w * pageSizes[page].aspect).roundToInt().coerceIn(1, MaxPagePx * 2)
                draw(p, w, h)
            }.getOrNull()
        }
    }

    override fun close() {
        document = null // PDFKit lets go of the file with the document
    }
}

private const val MaxPagePx = 4096

/**
 * A page drawn [w] × [h] pixels on white, straight into a bitmap by PDFKit (`drawWithBox:toContext:`, which applies
 * the page's rotation and crop), so text is as sharp as the pixels allow.
 */
private fun draw(page: PDFPage, w: Int, h: Int): ImageBitmap? = raster(page, w, h)?.toComposeImageBitmap()

private fun raster(page: PDFPage, w: Int, h: Int): Image? {
    val shown = sizeOf(page)
    if (shown.width <= 0f || shown.height <= 0f) return null
    val rowBytes = w * 4
    val bytes = ByteArray(rowBytes * h)
    val space = CGColorSpaceCreateDeviceRGB()
    try {
        bytes.usePinned { pinned ->
            val ctx = CGBitmapContextCreate(
                pinned.addressOf(0), w.toULong(), h.toULong(), 8u, rowBytes.toULong(), space,
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            ) ?: return null
            try {
                CGContextSetRGBFillColor(ctx, 1.0, 1.0, 1.0, 1.0)
                CGContextFillRect(ctx, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()))
                CGContextSetInterpolationQuality(ctx, kCGInterpolationHigh)
                CGContextScaleCTM(ctx, w / shown.width.toDouble(), h / shown.height.toDouble())
                page.drawWithBox(kPDFDisplayBoxCropBox, toContext = ctx)
            } finally {
                CGContextRelease(ctx)
            }
        }
    } finally {
        CGColorSpaceRelease(space)
    }
    val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    return Image.makeRaster(info, bytes, rowBytes)
}

/** A page's size in points as it shows: the crop box, turned when the page is rotated a quarter. */
private fun sizeOf(page: PDFPage): PdfPageSize {
    val (bw, bh) = page.boundsForBox(kPDFDisplayBoxCropBox).useContents { size.width.toFloat() to size.height.toFloat() }
    val quarter = ((page.rotation % 180L) + 180L) % 180L == 90L
    return if (quarter) PdfPageSize(bh, bw) else PdfPageSize(bw, bh)
}

actual suspend fun openPdf(path: String): PdfOpen = withContext(PdfThread) {
    val doc = runCatching { PDFDocument(uRL = NSURL.fileURLWithPath(path)) }.getOrNull() ?: return@withContext PdfOpen.Unreadable
    if (doc.isLocked()) return@withContext PdfOpen.Locked // password-protected
    val count = doc.pageCount.toInt()
    val sizes = (0 until count).mapNotNull { i -> doc.pageAtIndex(i.toULong())?.let(::sizeOf) }
    if (sizes.isEmpty() || sizes.size != count) PdfOpen.Unreadable else PdfOpen.Opened(IosPdfDocument(doc, sizes))
}

actual suspend fun pdfPreview(path: String): PdfPreview? = withContext(PdfThread) {
    runCatching {
        val doc = PDFDocument(uRL = NSURL.fileURLWithPath(path))
        if (doc.isLocked()) return@runCatching null
        val pages = doc.pageCount.toInt()
        val first = doc.pageAtIndex(0u) ?: return@runCatching null
        val size = sizeOf(first)
        for (width in PdfThumbWidths) {
            val image = raster(first, width, (width * size.aspect).roundToInt().coerceIn(1, width * 3)) ?: continue
            var quality = 70
            while (quality >= 40) {
                val bytes = image.encodeToData(EncodedImageFormat.JPEG, quality)?.bytes
                if (bytes != null && bytes.size <= PdfThumbMaxBytes) return@runCatching PdfPreview(bytes, pages)
                quality -= 10
            }
        }
        null
    }.getOrNull()
}

actual suspend fun shareFile(path: String, name: String, mime: String): Boolean = openFile(path, name, mime)
