package com.circaso.designsystem.platform

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

actual fun currentPlatformStyle(): PlatformStyle = PlatformStyle.Android

@Composable
actual fun rememberIsHighContrast(): Boolean {
    if (Build.VERSION.SDK_INT < 34) return false
    val uiMode = LocalContext.current.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return (uiMode?.contrast ?: 0f) >= 0.5f
}

@Composable
actual fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
