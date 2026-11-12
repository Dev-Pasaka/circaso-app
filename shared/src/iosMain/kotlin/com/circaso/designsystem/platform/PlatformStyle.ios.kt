package com.circaso.designsystem.platform

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibilityDarkerSystemColorsEnabled
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

actual fun currentPlatformStyle(): PlatformStyle = PlatformStyle.Ios

@Composable
actual fun rememberIsHighContrast(): Boolean = UIAccessibilityDarkerSystemColorsEnabled()

@Composable
actual fun rememberReduceMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()

actual val avatarPlatformTextStyle: androidx.compose.ui.text.PlatformTextStyle? = null
