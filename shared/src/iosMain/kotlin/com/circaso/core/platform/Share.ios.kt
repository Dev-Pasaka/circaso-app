package com.circaso.core.platform

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
