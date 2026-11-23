package com.circaso.core.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.SafariServices.SFSafariViewController
import platform.SafariServices.SFSafariViewControllerDelegateProtocol
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIModalPresentationPageSheet
import platform.UIKit.UIPresentationController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.presentationController
import platform.darwin.NSObject
import kotlin.coroutines.resume

/** Told once when Safari's sheet is gone, by Done or by a swipe down. Held by [openInSystemBrowserAndWait] while it is up. */
private class SafariClosed(private val onClosed: () -> Unit) : NSObject(), SFSafariViewControllerDelegateProtocol, UIAdaptivePresentationControllerDelegateProtocol {
    private var told = false
    private fun tell() { if (!told) { told = true; onClosed() } }
    override fun safariViewControllerDidFinish(controller: SFSafariViewController) = tell()
    override fun presentationControllerDidDismiss(presentationController: UIPresentationController) = tell()
}

// The delegate is weak on Safari's side: kept here for as long as its sheet is up.
private var open: SafariClosed? = null

actual suspend fun openInSystemBrowserAndWait(url: String): Boolean = withContext(Dispatchers.Main) {
    val link = NSURL.URLWithString(url.trim())?.takeIf { it.scheme == "https" } ?: return@withContext false
    val windows = UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>()
    var top: UIViewController? = (windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull())?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    val presenter = top ?: return@withContext false
    suspendCancellableCoroutine { continuation ->
        val safari = SFSafariViewController(uRL = link)
        val closed = SafariClosed { open = null; if (continuation.isActive) continuation.resume(true) }
        open = closed
        safari.delegate = closed
        safari.modalPresentationStyle = UIModalPresentationPageSheet
        safari.presentationController?.delegate = closed
        continuation.invokeOnCancellation { open = null; safari.dismissViewControllerAnimated(true, completion = null) }
        presenter.presentViewController(safari, animated = true, completion = null)
    }
}
