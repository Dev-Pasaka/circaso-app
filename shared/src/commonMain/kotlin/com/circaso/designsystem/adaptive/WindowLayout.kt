package com.circaso.designsystem.adaptive

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.verticalScroll

/** Apple's compact/regular and Material's compact/medium/expanded, by the space a screen actually has. */
enum class WidthClass { Compact, Medium, Expanded }

/**
 * The space the current screen has: the whole window, or its pane when the window is split in two. Read it with
 * [LocalWindowLayout]; it follows rotation, split screen, Stage Manager and foldables because it is measured, not
 * guessed from the device.
 */
@Immutable
data class WindowLayout(val width: Dp, val height: Dp) {
    val widthClass: WidthClass
        get() = when {
            width < 600.dp -> WidthClass.Compact
            width < 840.dp -> WidthClass.Medium
            else -> WidthClass.Expanded
        }

    /** A phone on its side: too short for a hero above the content. */
    val short: Boolean get() = height < 480.dp

    /** Wide and tall enough for a list beside its detail (iPad, tablets, iPad split view at half). */
    val twoPane: Boolean get() = width >= TwoPaneMinWidth && !short

    /** Wide enough to put two columns side by side within one screen (a phone on its side, a tablet). */
    val wide: Boolean get() = width >= 600.dp

    companion object {
        val TwoPaneMinWidth = 700.dp
        /** The list pane's width beside a detail (iPad Messages and Mail use about this). */
        val ListPaneWidth = 380.dp
        /** Text and forms never run wider than this (Apple's readable content guide, Material's body width). */
        val ReadableWidth = 680.dp
    }
}

val LocalWindowLayout = staticCompositionLocalOf { WindowLayout(390.dp, 844.dp) }

/** Measures the space [content] gets and provides it as [LocalWindowLayout]. Used at the root and in each pane. */
@Composable
fun ProvideWindowLayout(modifier: Modifier = Modifier.fillMaxSize(), content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        CompositionLocalProvider(LocalWindowLayout provides WindowLayout(maxWidth, maxHeight)) { content() }
    }
}

/** Centres the content and keeps it within the readable width; on a phone in portrait it changes nothing. */
fun Modifier.readableWidth(max: Dp = WindowLayout.ReadableWidth): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max)

/**
 * Fills the screen when the content fits (so `weight` spacers still push the buttons to the bottom) and scrolls
 * when it doesn't: a phone on its side, the largest text sizes. On a tall screen nothing changes. Put it after the
 * fill modifier: `Modifier.fillMaxSize().fillOrScroll()`.
 */
fun Modifier.fillOrScroll(): Modifier = composed {
    val state = androidx.compose.foundation.rememberScrollState()
    // The height on offer before scrolling makes it unbounded, read in the same measure pass below.
    val viewport = androidx.compose.runtime.remember { IntArray(1) }
    this
        .layout { measurable, constraints ->
            viewport[0] = constraints.maxHeight
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        .verticalScroll(state)
        .layout { measurable, constraints ->
            val fill = if (viewport[0] == Constraints.Infinity) constraints.minHeight else viewport[0]
            val placeable = measurable.measure(constraints.copy(minHeight = fill.coerceAtLeast(constraints.minHeight)))
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
}
