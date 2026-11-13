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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import com.circaso.designsystem.adaptive.WindowLayout
import com.circaso.designsystem.adaptive.readableWidth
import com.circaso.designsystem.theme.CircasoTheme

/**
 * Screen frame. Order, top to bottom: top bar, [strip] (bars that belong to the screen: an ongoing call, a chama goal),
 * content. [banner] (the connection banner and other notices that come and go) floats over the content just under the
 * top bar, so nothing below it moves when it appears (Pasaka, 2026-11-13).
 * The floating [bottomBar] sits over the content; [content] receives the bottom padding it needs so
 * nothing hides behind it. [overlay] (sheets) draws above everything but the [toast].
 */
@Composable
fun CircasoScaffold(
    modifier: Modifier = Modifier,
    grouped: Boolean = false,
    /** Across the top; down the left side when the phone is on its side, with everything in it stacked. */
    topBar: (@Composable () -> Unit)? = null,
    /** Floats over the content under the top bar; appearing never pushes the screen down. */
    banner: @Composable () -> Unit = {},
    /** Part of the layout under the top bar: bars that stay while something is on (an ongoing call, a goal). */
    strip: @Composable () -> Unit = {},
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
    val topBarOnSide = topBar != null && com.circaso.designsystem.adaptive.LocalWindowLayout.current.short
    var bottomBarHeight by remember { mutableStateOf(0.dp) }
    var sideBarWidth by remember { mutableStateOf(0.dp) }
    // Where the content starts inside this scaffold (under the top bar and the strip): toasts drop in from there.
    // Read only while placing the toast (layout phase), never in composition: the keyboard resizing the content
    // re-places the toast without recomposing the screen. Pixels; written only when they change.
    var frame by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val contentLeft = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val contentTop = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val contentWidth = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // The floating banner's height: a toast stacks under it instead of landing on it, and moves up as it collapses.
    var bannerHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val toastShift = androidx.compose.animation.core.animateIntAsState(bannerHeight, com.circaso.designsystem.motion.CircasoSpring.notice(), label = "toast under banner")
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
    Box(modifier.fillMaxSize().onPlaced { frame = it }.let { if (outer == null) it.background(if (grouped) c.bgGrouped else c.bg) else it }) {
        if (outer == null) {
            backdrop?.invoke(this)
            host.content?.invoke(this)
        }
        Column(
            // Backgrounds reach every edge; content keeps clear of the camera cut-out and of a navigation bar at the
            // side (landscape), as it keeps clear of the status bar at the top.
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(sideInsets)
                .padding(start = (if (sideBar != null) sideBarWidth else 0.dp) + (if (topBarOnSide) WindowLayout.SideTopBarWidth else 0.dp)).let {
                // With a tab bar the bar itself clears the home indicator; without one the content must.
                if (bottomBar == null) it.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)) else it.imePadding()
            },
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalBackdropHost provides host) {
            if (!topBarOnSide) topBar?.invoke()
            strip()
            Box(
                Modifier.weight(1f).let { if (keepReadable) it.readableWidth(maxContentWidth) else it }
                    .onPlaced { placed ->
                        frame?.let { f ->
                            val at = f.localPositionOf(placed, androidx.compose.ui.geometry.Offset.Zero)
                            contentLeft.intValue = at.x.toInt()
                            contentTop.intValue = at.y.toInt()
                            contentWidth.intValue = placed.size.width
                        }
                    },
            ) {
                content(PaddingValues(bottom = if (bottomBar != null) bottomBarHeight + navInset else 0.dp))
                // Notices float at the top of the content, under the top bar and the strip, centred in the same
                // readable frame: the content stays put when one comes or goes (Pasaka, 2026-11-13).
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { bannerHeight = it.height },
                    contentAlignment = Alignment.TopCenter,
                ) { banner() }
            }
            }
        }
        if (bottomBar != null && topBarOnSide) {
            // A phone on its side has little height: the content scrolls under the floating bar, so the page's colour
            // fades in behind the bar (UIKit's scroll edge) and nothing reads through it half covered.
            val page = if (grouped) c.bgGrouped else c.bg
            Box(
                // bottomBarHeight is the bar itself (measured inside its inset padding) plus 8; the inset is added once.
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomBarHeight + navInset + com.circaso.designsystem.theme.CircasoSpacing.s4)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to page.copy(alpha = 0f), 0.5f to page.copy(alpha = 0.85f), 1f to page)),
            )
        }
        if (bottomBar != null) {
            // Centred over the content, not the whole screen: with the top bar or the tabs down the left side (a phone
            // on its side) the bar would otherwise slide under them.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal))
                    .padding(start = (if (sideBar != null) sideBarWidth else 0.dp) + (if (topBarOnSide) WindowLayout.SideTopBarWidth else 0.dp)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier
                        .let { if (keepReadable) it.widthIn(max = WindowLayout.ReadableWidth) else it }
                        .padding(start = 16.dp, end = 16.dp, bottom = navInset)
                        .onSizeChanged { bottomBarHeight = with(density) { it.height.toDp() } + 8.dp },
                ) { bottomBar() }
            }
        }
        if (topBarOnSide) {
            Box(
                // Its own width on top of the notch or side bar inset, so the title always has room.
                Modifier.align(Alignment.TopStart).fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(sideInsets).width(WindowLayout.SideTopBarWidth),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(com.circaso.designsystem.adaptive.LocalTopBarOnSide provides true) { topBar?.invoke() }
            }
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
        // Toasts drop in where the content starts, above any sheet, so every screen places them the same way.
        // Centred on the content column, not the window, so a side rail or a side top bar never pulls it off centre.
        Box(
            Modifier.layout { measurable, constraints ->
                val width = contentWidth.intValue.takeIf { it > 0 } ?: constraints.maxWidth
                val top = contentTop.intValue + toastShift.value
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints(maxWidth = width, maxHeight = (constraints.maxHeight - top).coerceAtLeast(0)))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(contentLeft.intValue, top) }
            },
        ) { toast() }
    }
}

/** Where a screen's full-bleed backdrop is drawn: the outermost scaffold, from the top-left of the screen. */
class BackdropHost {
    var content: (@Composable BoxScope.() -> Unit)? by mutableStateOf(null)
    /** Which screen put [content] there, so only that screen takes it away again. */
    var owner: Any? = null
}

val LocalBackdropHost = androidx.compose.runtime.staticCompositionLocalOf<BackdropHost?> { null }

/**
 * Hands [content] to the outermost scaffold to draw behind everything, edge to edge, from the top of the screen
 * (y = 0 is the screen's top, under the status bar). One backdrop at a time: the screen on show.
 */
@Composable
fun HostedBackdrop(content: @Composable BoxScope.() -> Unit) {
    val host = LocalBackdropHost.current ?: return
    val front = LocalInFront.current
    val owner = androidx.compose.runtime.remember { Any() }
    // Tabs stay alive behind the one in front (2026-11-07): only the front one may hold the backdrop, and a tab that
    // goes behind gives it up, so the Wallet's glow never shows under You.
    androidx.compose.runtime.SideEffect {
        if (front) { host.content = content; host.owner = owner }
        else if (host.owner === owner) { host.content = null; host.owner = null }
    }
    androidx.compose.runtime.DisposableEffect(host) { onDispose { if (host.owner === owner) { host.content = null; host.owner = null } } }
}

/** False inside a tab that is alive but behind the one in front: it keeps its state but mustn't draw chrome. */
val LocalInFront = androidx.compose.runtime.compositionLocalOf { true }
