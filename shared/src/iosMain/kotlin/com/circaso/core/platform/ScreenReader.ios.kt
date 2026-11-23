package com.circaso.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsVoiceOverRunning

@Composable
actual fun rememberScreenReaderOn(): Boolean = remember { UIAccessibilityIsVoiceOverRunning() }
