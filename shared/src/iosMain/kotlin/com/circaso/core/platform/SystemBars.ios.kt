package com.circaso.core.platform

import androidx.compose.runtime.Composable

/**
 * Not yet on iOS: the status bar's style belongs to the SwiftUI host, which doesn't ask the Compose controller, and
 * drawing the window dark would flip the app's own theme behind the screen (the theme follows the system). Doing it
 * properly needs a UIKit root controller that answers `preferredStatusBarStyle` from a flag set here.
 */
@Composable
actual fun StatusBarIcons(light: Boolean) = Unit
