package com.circaso.designsystem.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Which platform's conventions the UI follows. Components read this for look-and-feel
 * differences (press feedback, sheet style, nav bar style). Anything that needs a native
 * API goes through the expect/actual functions in this package instead.
 */
enum class PlatformStyle { Ios, Android }

val LocalPlatformStyle = staticCompositionLocalOf { PlatformStyle.Android }

/** The style of the platform the app is running on. */
expect fun currentPlatformStyle(): PlatformStyle

/** True when the user asked the OS for more contrast (iOS Increase Contrast, Android 14+ contrast level). */
@Composable
expect fun rememberIsHighContrast(): Boolean

/** True when the person asked the OS for less motion (iOS Reduce Motion, Android "Remove animations"). */
@Composable
expect fun rememberReduceMotion(): Boolean

/** Read in the theme; motion that slides or scales falls back to a plain fade when it is on. */
val LocalReduceMotion = androidx.compose.runtime.staticCompositionLocalOf { false }
