package com.circaso.designsystem.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import com.circaso.designsystem.adaptive.WindowLayout
import com.circaso.designsystem.adaptive.readableWidth
import com.circaso.designsystem.theme.CircasoTheme

/**
 * Screen frame. Order, top to bottom: top bar, banner (the connection banner), content.
 * The floating [bottomBar] sits over the content; [content] receives the bottom padding it needs so
 * nothing hides behind it. [overlay] (sheets) draws above everything but the [toast].
 */
@Composable
fun CircasoScaffold(
    modifier: Modifier = Modifier,
    grouped: Boolean = false,
    topBar: @Composable () -> Unit = {},
    banner: @Composable () -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    /** In place of [bottomBar] on a wide or short screen: the tabs float down the left side. */
    sideBar: (@Composable () -> Unit)? = null,
    toast: @Composable BoxScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    /** Drawn behind everything from the very top of the screen, under the status bar (the You tab's photo glow). */
    backdrop: (@Composable BoxScope.() -> Unit)? = null,
    /**
     * Keeps the content and the floating bar within the readable width, centred, when the screen is wide (a phone
     * on its side, a tablet). Off for screens that are the picture themselves: maps, calls, the voice orb.
     */
    readable: Boolean = true,
    /** The frame's width: [WindowLayout.ReadableWidth] for one column, [WindowLayout.WideContentWidth] for two. */
    maxContentWidth: androidx.compose.ui.unit.Dp = WindowLayout.ReadableWidth,
    content: @Composable (PaddingValues) -> Unit,
) {
    val c = CircasoTheme.colors
    val density = LocalDensity.current
    // Content is always framed, never stretched to the screen (the user, 2026-10-31: everything intentional);
    // backgrounds still reach every edge.
    val keepReadable = readable
    var bottomBarHeight by remember { mutableStateOf(0.dp) }
    var sideBarWidth by remember { mutableStateOf(0.dp) }
    val sideInsets = WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)
    // Some devices report no navigation inset; keep the floating bar off the screen edge anyway.
    val navInset = maxOf(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), 16.dp)
    // Backdrops always reach the screen's edges, even from a screen framed inside another (a tab inside Home): the
    // outermost scaffold draws them, from the top of the screen, behind everything.
    val outer = LocalBackdropHost.current
    val host = outer ?: remember { BackdropHost() }
    if (outer != null && backdrop != null) HostedBackdrop(backdrop)
    // Inside another scaffold (a tab inside Home) the outer one paints the page and the backdrop, edge to edge;
    // painting here too would box the backdrop in.
    Box(modifier.fillMaxSize().let { if (outer == null) it.background(if (grouped) c.bgGrouped else c.bg) else it }) {
        if (outer == null) {
            backdrop?.invoke(this)
            host.content?.invoke(this)
        }
        Column(
            // Backgrounds reach every edge; content keeps clear of the camera cut-out and of a navigation bar at the
            // side (landscape), as it keeps clear of the status bar at the top.
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(sideInsets)
                .padding(start = if (sideBar != null) sideBarWidth else 0.dp).let {
                // With a tab bar the bar itself clears the home indicator; without one the content must.
                if (bottomBar == null) it.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)) else it.imePadding()
            },
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalBackdropHost provides host) {
            topBar()
            banner()
            Box(Modifier.weight(1f).let { if (keepReadable) it.readableWidth(maxContentWidth) else it }) { content(PaddingValues(bottom = if (bottomBar != null) bottomBarHeight + navInset else 0.dp)) }
            }
        }
        if (bottomBar != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .let { if (keepReadable) it.widthIn(max = WindowLayout.ReadableWidth) else it }
                    .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal))
                    .padding(start = 16.dp, end = 16.dp, bottom = navInset)
                    .onSizeChanged { bottomBarHeight = with(density) { it.height.toDp() } + 8.dp },
            ) { bottomBar() }
        }
        if (sideBar != null) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .windowInsetsPadding(sideInsets)
                    .padding(start = 12.dp)
                    .onSizeChanged { sideBarWidth = with(density) { it.width.toDp() } + 12.dp + 8.dp },
            ) { sideBar() }
        }
        overlay()
        toast()
    }
}

/** Where a screen's full-bleed backdrop is drawn: the outermost scaffold, from the top-left of the screen. */
class BackdropHost {
    var content: (@Composable BoxScope.() -> Unit)? by mutableStateOf(null)
}

val LocalBackdropHost = androidx.compose.runtime.staticCompositionLocalOf<BackdropHost?> { null }

/**
 * Hands [content] to the outermost scaffold to draw behind everything, edge to edge, from the top of the screen
 * (y = 0 is the screen's top, under the status bar). One backdrop at a time: the screen on show.
 */
@Composable
fun HostedBackdrop(content: @Composable BoxScope.() -> Unit) {
    val host = LocalBackdropHost.current ?: return
    androidx.compose.runtime.SideEffect { host.content = content }
    androidx.compose.runtime.DisposableEffect(host) { onDispose { if (host.content === content) host.content = null } }
}
