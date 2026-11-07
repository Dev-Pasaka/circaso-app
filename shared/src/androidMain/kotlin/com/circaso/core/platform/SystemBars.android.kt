package com.circaso.core.platform

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun StatusBarIcons(light: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, light) {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val window = (context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val before = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = !light
        onDispose { controller.isAppearanceLightStatusBars = before }
    }
}
