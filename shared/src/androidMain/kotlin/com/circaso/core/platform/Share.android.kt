package com.circaso.core.platform

import android.content.Intent
import org.koin.mp.KoinPlatform

actual fun shareText(text: String, subject: String?) {
    val activity = runCatching { KoinPlatform.getKoin().get<CurrentActivity>().get() }.getOrNull() ?: return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }
    activity.startActivity(Intent.createChooser(send, subject))
}

actual fun sharePdf(title: String, lines: List<String>) {
    val activity = runCatching { KoinPlatform.getKoin().get<CurrentActivity>().get() }.getOrNull() ?: return
    runCatching {
        val doc = android.graphics.pdf.PdfDocument()
        val width = 595
        val height = 842 // A4 in points
        val titlePaint = android.graphics.Paint().apply { textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
        val textPaint = android.graphics.Paint().apply { textSize = 10.5f; isAntiAlias = true }
        var pageNo = 1
        var page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(width, height, pageNo).create())
        var y = 56f
        page.canvas.drawText(title, 40f, y, titlePaint)
        y += 28f
        lines.forEach { line ->
            if (y > height - 40) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(width, height, pageNo).create())
                y = 48f
            }
            page.canvas.drawText(line, 40f, y, textPaint)
            y += 16f
        }
        doc.finishPage(page)
        val dir = java.io.File(activity.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(dir, title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Statement" } + ".pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        val uri = androidx.core.content.FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, title))
    }
}

actual suspend fun openFile(bytes: ByteArray, name: String, mime: String): Boolean {
    val activity = runCatching { KoinPlatform.getKoin().get<CurrentActivity>().get() }.getOrNull() ?: return false
    // Up to 32 MB: written off the main thread; only the chooser is shown from it.
    val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val dir = java.io.File(activity.cacheDir, "shared/open-${System.nanoTime()}").apply { mkdirs() }
            java.io.File(dir, name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "File" }).also { it.writeBytes(bytes) }
        }.getOrNull()
    } ?: return false
    return runCatching {
        // The shared cache folder the FileProvider serves (res/xml/shared_files.xml); one folder per open, so two
        // files with the same name never overwrite each other while one is still open elsewhere.
        val uri = androidx.core.content.FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (view.resolveActivity(activity.packageManager) != null) activity.startActivity(Intent.createChooser(view, name))
        else {
            // Nothing opens this type: hand it to another app (Files, Drive, email) instead.
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime.ifBlank { "application/octet-stream" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, name))
        }
        true
    }.getOrDefault(false)
}
