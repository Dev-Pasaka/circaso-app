package com.circaso.core.platform

import com.circaso.core.crypto.toNSData
import platform.Foundation.writeToFile
import platform.UIKit.drawAtPoint
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow

actual fun shareText(text: String, subject: String?) {
    val top = topViewController() ?: return
    val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
    top.presentViewController(sheet, animated = true, completion = null)
}

private fun topViewController(): UIViewController? {
    val windows = UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>()
    var top = (windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull())?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun sharePdf(title: String, lines: List<String>) {
    val top = topViewController() ?: return
    // A4 in points; one line of text per entry, a new page when one fills.
    val page = platform.CoreGraphics.CGRectMake(0.0, 0.0, 595.0, 842.0)
    val renderer = platform.UIKit.UIGraphicsPDFRenderer(bounds = page)
    val titleAttrs = mapOf<Any?, Any?>(platform.UIKit.NSFontAttributeName to platform.UIKit.UIFont.boldSystemFontOfSize(18.0))
    val textAttrs = mapOf<Any?, Any?>(platform.UIKit.NSFontAttributeName to platform.UIKit.UIFont.systemFontOfSize(10.5))
    val data = renderer.PDFDataWithActions { context ->
        context?.beginPage()
        var y = 40.0
        (title as platform.Foundation.NSString).drawAtPoint(platform.CoreGraphics.CGPointMake(40.0, y), withAttributes = titleAttrs)
        y += 30.0
        lines.forEach { line ->
            if (y > 800.0) { context?.beginPage(); y = 40.0 }
            (line as platform.Foundation.NSString).drawAtPoint(platform.CoreGraphics.CGPointMake(40.0, y), withAttributes = textAttrs)
            y += 16.0
        }
    }
    val name = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Statement" } + ".pdf"
    val path = platform.Foundation.NSTemporaryDirectory() + name
    data.writeToFile(path, atomically = true)
    val url = platform.Foundation.NSURL.fileURLWithPath(path)
    val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
    top.presentViewController(sheet, animated = true, completion = null)
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
actual suspend fun openFile(path: String, name: String, mime: String): Boolean {
    val dir = platform.Foundation.NSTemporaryDirectory() + "open-" + platform.Foundation.NSUUID().UUIDString + "/"
    platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    val shown = dir + name.replace(Regex("[/\\\\:]"), "_").ifBlank { "File" }
    // Up to 100 MB: a file-system copy (a clone on APFS), never read into memory; only the sheet is shown from the main thread.
    val copied = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        platform.Foundation.NSFileManager.defaultManager.copyItemAtPath(path, toPath = shown, error = null)
    }
    if (!copied) return false
    val top = topViewController() ?: return false
    val url = platform.Foundation.NSURL.fileURLWithPath(shown)
    val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
    top.presentViewController(sheet, animated = true, completion = null)
    return true
}
