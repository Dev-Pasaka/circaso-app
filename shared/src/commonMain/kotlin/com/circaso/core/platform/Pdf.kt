package com.circaso.core.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * PDFs in chats (2026-11-24): a document's pages drawn on the phone itself, from its decrypted copy in Circaso's private
 * files (never a shared folder). Android draws with `android.graphics.pdf.PdfRenderer`, iPhone with PDFKit. Drawing is
 * off the main thread, one page at a time per document (Android's renderer allows no more).
 */
interface PdfDocument {
    val pageCount: Int

    /** Each page's size in points, as it shows (rotation applied). */
    val pageSizes: List<PdfPageSize>

    /** Page [page] (from 0) drawn [widthPx] wide on white, or null when it can't be (closed, or the page is damaged). */
    suspend fun render(page: Int, widthPx: Int): ImageBitmap?

    /** Lets go of the file. Safe to call more than once, and while a page is being drawn (it finishes first). */
    fun close()
}

class PdfPageSize(val width: Float, val height: Float) {
    /** Height over width, for laying the page out before it is drawn; A4 when the page has no sensible size. */
    val aspect: Float get() = if (width > 0f && height > 0f) height / width else 1.4142f
}

/** What opening a PDF came to. */
sealed interface PdfOpen {
    class Opened(val document: PdfDocument) : PdfOpen
    /** It needs a password: this viewer doesn't ask for one; another app can. */
    data object Locked : PdfOpen
    /** Not a PDF this phone can read. */
    data object Unreadable : PdfOpen
}

/** Opens the PDF at [path] (a file of Circaso's). */
expect suspend fun openPdf(path: String): PdfOpen

/**
 * What a PDF's message carries so the chat shows it before anyone downloads it (WhatsApp-style): its first page as a
 * small JPEG ([PdfThumbMaxBytes] at most, like a video's poster) and how many pages it has.
 */
class PdfPreview(val thumb: ByteArray, val pages: Int)

/** The first page and the page count of the PDF at [path]; null when it can't be read (or needs a password). */
expect suspend fun pdfPreview(path: String): PdfPreview?

/** A PDF's first page in its message: about as large as a video's poster, so the message stays well under 64 KB. */
const val PdfThumbMaxBytes = 14_000

/** The widths tried in turn for the first page's JPEG (largest first), each from quality 70 down, until it fits. */
internal val PdfThumbWidths = listOf(420, 360, 300, 240)

/** Hands a chat's file to another app or to Files (Android's share sheet; iPhone's, with Save to Files). */
expect suspend fun shareFile(path: String, name: String, mime: String): Boolean

/** A file is a PDF by its type, or by its name when the sender's phone gave no type. */
fun isPdf(name: String?, mime: String?): Boolean =
    mime?.lowercase()?.substringBefore(';')?.trim() == "application/pdf" ||
        (mime.isNullOrBlank() || mime == "application/octet-stream") && name?.lowercase()?.endsWith(".pdf") == true
