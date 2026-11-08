package com.circaso.designsystem.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.circaso.designsystem.adaptive.LocalWindowLayout
import com.circaso.designsystem.adaptive.WindowLayout

/**
 * What draws over every screen rather than inside the one that opened it: sheets and photo viewers. On a wide window a
 * screen may be the narrow list pane (Home beside a chat); a photo or a sheet opened there belongs over the window,
 * centred, as iPadOS presents them, never squeezed into the 360 dp pane (Pasaka, 2026-11-08). On a phone the window
 * and the screen are the same, so nothing changes there.
 *
 * The layer sits over the navigation's screens only: the share sheet, toasts and an incoming call stay above it. What
 * draws in it keeps everything its opener had (the theme of a dark screen, view models, share links, `/asha`); only
 * [LocalWindowLayout] becomes the window's.
 */
class WindowOverlayHost internal constructor(internal val window: WindowLayout) {
    internal val layers = mutableStateListOf<Pair<Any, @Composable () -> Unit>>()
}

val LocalWindowOverlay = staticCompositionLocalOf<WindowOverlayHost?> { null }

/**
 * The overlay layer, above [content]. [enabled] false (debug only, `-window-overlay=off`) draws overlays in place
 * again, to compare with the old behaviour.
 */
@Composable
fun WindowOverlayLayer(enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val window = LocalWindowLayout.current
    // A new size (rotation, split screen) is a new host; each open layer moves to it (its effect is keyed on the host).
    val host = remember(window) { WindowOverlayHost(window) }
    CompositionLocalProvider(LocalWindowOverlay provides host) {
        Box(Modifier.fillMaxSize()) {
            content()
            OverlayLayers(host)
        }
    }
}

/** Separate, so a layer coming or going never runs the screens under it again. */
@Composable
private fun OverlayLayers(host: WindowOverlayHost) {
    // In the order their openers first appeared: in practice the newest screen's on top.
    host.layers.forEach { (key, layer) -> androidx.compose.runtime.key(key) { layer() } }
}

/**
 * Draws [content] over the whole window while the caller is in the composition, with the caller's composition locals
 * (only the window size swapped in). Without a layer (previews, tests) it draws in place, over the caller's box.
 * Back handling stays with the caller: register it there, not in [content].
 */
@Composable
fun BoxScope.OverWindow(content: @Composable BoxScope.() -> Unit) {
    val host = LocalWindowOverlay.current
    val latest by rememberUpdatedState(content)
    if (host == null) {
        Box(Modifier.matchParentSize()) { latest.invoke(this) }
        return
    }
    val locals by rememberUpdatedState(currentCompositionLocalContext)
    val key = remember { Any() }
    DisposableEffect(host, key) {
        val layer: @Composable () -> Unit = {
            CompositionLocalProvider(locals) {
                CompositionLocalProvider(LocalWindowLayout provides host.window) {
                    Box(Modifier.fillMaxSize()) { latest.invoke(this) }
                }
            }
        }
        host.layers.add(key to layer)
        onDispose { host.layers.removeAll { it.first === key } }
    }
}
