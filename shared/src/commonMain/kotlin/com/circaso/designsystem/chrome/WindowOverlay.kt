package com.circaso.designsystem.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * What draws over the whole window rather than inside the screen that opened it: sheets and photo viewers. On a wide
 * window a screen may be the narrow list pane (Home beside a chat); a photo or a sheet opened there belongs over the
 * window, centred, as iPadOS presents them, never squeezed into the 360 dp pane (Pasaka, 2026-11-08). On a phone the
 * window and the screen are the same, so nothing changes there.
 */
class WindowOverlayHost internal constructor() {
    internal val layers = mutableStateListOf<Pair<Any, @Composable () -> Unit>>()
}

val LocalWindowOverlay = staticCompositionLocalOf<WindowOverlayHost?> { null }

/**
 * The window's overlay layer, above [content]. Put it once, at the root, around the navigation. [enabled] false
 * (debug only, `-window-overlay=off`) draws overlays in place again, to compare with the old behaviour.
 */
@Composable
fun WindowOverlayLayer(enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val host = remember { WindowOverlayHost() }
    CompositionLocalProvider(LocalWindowOverlay provides host) {
        Box(Modifier.fillMaxSize()) {
            content()
            // In the order they were opened: the newest on top.
            host.layers.forEach { (key, layer) -> androidx.compose.runtime.key(key) { layer() } }
        }
    }
}

/**
 * Draws [content] over the whole window, in a full-window box, while the caller is in the composition. Without a
 * window layer (previews, tests) it draws in place, filling the caller's box.
 */
@Composable
fun OverWindow(content: @Composable BoxScope.() -> Unit) {
    val host = LocalWindowOverlay.current
    val latest by rememberUpdatedState(content)
    if (host == null) {
        Box(Modifier.fillMaxSize()) { latest.invoke(this) }
        return
    }
    val key = remember { Any() }
    DisposableEffect(host, key) {
        val layer: @Composable () -> Unit = { Box(Modifier.fillMaxSize()) { latest.invoke(this) } }
        host.layers.add(key to layer)
        onDispose { host.layers.removeAll { it.first === key } }
    }
}
