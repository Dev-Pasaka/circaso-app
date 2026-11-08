package com.circaso.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Debug only: `-frame=1366x1024` (iOS) or `--es frame 1366x1024` (Android) draws the whole app as if the window were
 * that many dp, scaled down to fit the real screen. Simulators can't be turned on their side from a script, so this is
 * how an iPad in landscape, a phone on its side or an Android tablet is checked from the command line
 * (docs/adaptive-layout-plan.md). Safe-area insets stay the real device's.
 */
@Composable
internal fun DebugFrame(content: @Composable () -> Unit) {
    val size = remember { launchValue("-frame")?.split('x')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 } }
    if (size == null) { content(); return }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF3A3A3C)), contentAlignment = Alignment.Center) {
        val w = size[0].dp
        val h = size[1].dp
        val scale = minOf(maxWidth / w, maxHeight / h, 1f)
        Box(Modifier.requiredSize(w, h).graphicsLayer { scaleX = scale; scaleY = scale }) { content() }
    }
}
