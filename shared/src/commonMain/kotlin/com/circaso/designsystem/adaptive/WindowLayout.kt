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

    /**
     * Wide and tall enough for a list beside a detail that still has a phone's width or more: Material's expanded
     * width (iPads and tablets in landscape, 12.9" iPads either way). Narrower tablets stay single-pane.
     */
    val twoPane: Boolean get() = width >= TwoPaneMinWidth && !short

    /** Wide enough to put two columns side by side within one screen (a phone on its side, a tablet). */
    val wide: Boolean get() = width >= 600.dp

    companion object {
        val TwoPaneMinWidth = 840.dp
        /** The list pane's width beside a detail (iPad Messages and Mail use about this). */
        val ListPaneWidth = 360.dp
        /** Text and forms never run wider than this (Apple's readable content guide, Material's body width). */
        val ReadableWidth = 680.dp
        /** Buttons and short forms on a tablet (sign-in, a single field and its button): about iPadOS's sheet content. */
        val FormWidth = 440.dp
        /** A layout designed in two columns (a phone on its side): wider, still framed, never the whole screen. */
        val WideContentWidth = 900.dp
        /** The top bar's column down the left side on a phone on its side. */
        val SideTopBarWidth = 136.dp
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

/**
 * Fills the width up to [max] and centres it there: the readable column on a tablet, the whole width on a phone in
 * portrait (where it changes nothing).
 */
fun Modifier.readableWidth(max: Dp = WindowLayout.ReadableWidth): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()

/**
 * Fills the screen when the content fits and scrolls when it doesn't: a phone on its side, the largest text sizes.
 * Put it after the fill modifier (`Modifier.fillMaxSize().fillOrScroll()`): the scroll keeps the screen's height as
 * its minimum, so `Spacer(Modifier.weight(1f))` still pushes the buttons down when there is room and shrinks to
 * nothing when there isn't. Never put the content itself in a weighted child: it would be cut to what is left.
 */
fun Modifier.fillOrScroll(): Modifier = composed { verticalScroll(androidx.compose.foundation.rememberScrollState()) }

/**
 * True inside a screen's top bar when it is drawn as a column down the left side (a phone on its side, the user,
 * 2026-10-31): the bar's back button, title and actions stack vertically there, within thumb reach.
 */
val LocalTopBarOnSide = androidx.compose.runtime.compositionLocalOf { false }
