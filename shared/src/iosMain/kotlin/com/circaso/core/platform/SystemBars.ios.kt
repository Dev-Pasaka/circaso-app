package com.circaso.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.uikit.LocalUIViewController
import platform.UIKit.UIScreen
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController

/**
 * The status bar's style belongs to the SwiftUI host, and it follows the host's light or dark appearance. So while a
 * dark stage is on screen we set the window's appearance (dark for light icons, light for dark icons) and pin the
 * Compose controller to the system's real appearance, read from the screen, which a window override doesn't touch.
 * The app's theme behind the stage stays as it was. Calls nest: the newest one wins, and when the last one leaves,
 * both overrides are cleared.
 */
@Composable
actual fun StatusBarIcons(light: Boolean) {
    val controller = LocalUIViewController.current
    DisposableEffect(controller, light) {
        val entry = IosStatusBar.Entry(light)
        IosStatusBar.push(controller, entry)
        onDispose { IosStatusBar.pop(controller, entry) }
    }
}

private object IosStatusBar {
    class Entry(val light: Boolean)

    private val stack = mutableListOf<Entry>()

    fun push(controller: UIViewController, entry: Entry) {
        stack += entry
        apply(controller)
    }

    fun pop(controller: UIViewController, entry: Entry) {
        stack.remove(entry)
        apply(controller)
    }

    private fun apply(controller: UIViewController) {
        val window = controller.view.window ?: return
        val top = stack.lastOrNull()
        if (top == null) {
            window.overrideUserInterfaceStyle = UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
            controller.overrideUserInterfaceStyle = UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
        } else {
            val system = UIScreen.mainScreen.traitCollection.userInterfaceStyle
            controller.overrideUserInterfaceStyle =
                if (system == UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified) {
                    UIUserInterfaceStyle.UIUserInterfaceStyleLight
                } else {
                    system
                }
            window.overrideUserInterfaceStyle =
                if (top.light) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
        }
        window.rootViewController?.setNeedsStatusBarAppearanceUpdate()
    }
}
