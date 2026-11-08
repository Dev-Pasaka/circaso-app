package com.circaso.designsystem.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.widthIn
import com.circaso.designsystem.theme.CircasoSpacing
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.motion.CircasoSpring
import com.circaso.designsystem.platform.PlatformStyle
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Modal bottom sheet (design `cx-sheet`): scrim, 34 dp top corners, grabber. Place it in a scaffold's
 * `overlay` slot so it covers the tab bar. Dismisses on scrim tap, back, or a downward drag.
 * [label] is read by screen readers as the dialog name.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BoxScope.CircasoSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    label: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = CircasoTheme.colors
    val ios = CircasoTheme.platform == PlatformStyle.Ios
    BackHandler(enabled = visible, onBack = onDismiss)
    // Over the whole window, not the pane that opened it: on an iPad a sheet from the list pane is still a centred
    // card over everything (WindowOverlay.kt).
    OverWindow {
        AnimatedVisibility(visible, Modifier.matchParentSize(), enter = fadeIn(tween(CircasoMotion.BaseMs)), exit = fadeOut(tween(CircasoMotion.QuickMs))) {
            Box(Modifier.fillMaxSize().background(c.scrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
        }
        // On a tablet (regular width and height) a sheet is a centred card with every corner rounded, as iPadOS presents a
        // form sheet; a strip across a 13" screen would look stretched. Phones, either way up, keep the bottom sheet.
        val layout = com.circaso.designsystem.adaptive.LocalWindowLayout.current
        val floating = layout.widthClass != com.circaso.designsystem.adaptive.WidthClass.Compact && !layout.short
        val halfScreen = with(androidx.compose.ui.platform.LocalDensity.current) { (layout.height / 2).roundToPx() }
        AnimatedVisibility(
            visible,
            // Sheets live in the overlay layer, which the scaffold does not inset: lift the sheet above the keyboard so a
            // text field or button inside it (for example the typed word that confirms a deletion) is never covered.
            Modifier.align(if (floating) Alignment.Center else Alignment.BottomCenter).imePadding()
                .let { if (floating) it.windowInsetsPadding(WindowInsets.safeDrawing).padding(CircasoSpacing.s6) else it },
            // A centred card still rises from the bottom edge of the screen, as a form sheet does, not from mid-screen.
            enter = if (CircasoTheme.reduceMotion) fadeIn(tween(CircasoMotion.QuickMs)) else slideInVertically(if (ios) CircasoSpring.sheet() else tween(CircasoMotion.BaseMs)) { if (floating) it / 2 + halfScreen else it },
            exit = if (CircasoTheme.reduceMotion) fadeOut(tween(CircasoMotion.QuickMs)) else slideOutVertically(tween(CircasoMotion.BaseMs)) { if (floating) it / 2 + halfScreen else it },
        ) {
            val drag = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val dismissAt = with(androidx.compose.ui.platform.LocalDensity.current) { 100.dp.toPx() }
            val shape = if (floating) CircasoShapes.sheetFloating else CircasoShapes.sheet
            Column(
                Modifier
                    // A tablet's sheet is a centred card; a phone on its side keeps the bottom sheet, never a full-width strip.
                    .widthIn(max = if (floating) com.circaso.designsystem.adaptive.WindowLayout.SheetWidth else 600.dp)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal))
                    .offset { IntOffset(0, drag.value.roundToInt()) }
                    .shadow(if (floating) FloatingSheetShadow else 14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.18f))
                    .clip(shape)
                    .background(c.surface)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (drag.value > dismissAt) onDismiss() else drag.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                }
                            },
                            onDragCancel = { scope.launch { drag.animateTo(0f) } },
                        ) { _, delta -> scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) } }
                    }
                    .semantics { contentDescription = label }
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = if (floating) 24.dp else maxOf(bottomInset, 24.dp)),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // iPadOS form sheets have no grabber: the card closes with its Close, the scrim or Back (dragging still works).
                if (!floating) Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(5.dp).clip(CircasoShapes.pill).background(c.fillStrong))
                // Taller than the room it has (the largest text, a keyboard up on a tablet): the content scrolls inside the
                // card instead of losing its top or its button off the screen. Sheets with their own list (Group Wallets)
                // size that list to fit, so this never scrolls for them.
                // Only scrollable when it overflows, so a sheet that fits still follows a downward drag to close.
                val scroll = rememberScrollState()
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scroll, enabled = scroll.maxValue > 0),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content,
                )
            }
        }
    }
}


/** A floating card's shadow: deeper than a bottom sheet's, since it sits over the screen rather than at its edge. */
private val FloatingSheetShadow = 28.dp

/**
 * Rows and tiles inside a sheet sit on a group a step off the sheet's own surface (grey on white, lighter on dark):
 * Asha's menu and the Group Wallets sheet.
 */
@Composable
fun sheetGroupColor(): Color = CircasoTheme.colors.let { if (it.isDark) it.fill else it.bgGrouped }

/** In high contrast a sheet's grey groups get a hairline edge: grey on white alone is nearly invisible there. */
@Composable
fun Modifier.sheetGroupEdge(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val c = CircasoTheme.colors
    return if (c.isHighContrast) this.border(1.dp, c.separator, shape) else this
}

/** The design's `.cx-sheet-head`: the sheet's title with a Close on the right. */
@Composable
fun SheetHead(title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    androidx.compose.foundation.layout.Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.text.BasicText(title, style = t.title2.copy(color = c.ink), modifier = Modifier.weight(1f).semantics { heading() })
        com.circaso.designsystem.components.CircasoIconButton(com.circaso.designsystem.components.CircasoIcon.Close, "Close", onClose)
    }
}

/** The design's confirm pattern (ID-26 "Sign out of Circaso?"): title, one line of explanation, a main button and Cancel. */
@Composable
fun BoxScope.ConfirmSheet(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    cancelLabel: String = "Cancel",
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoSheet(visible, onDismiss, label = title) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(title, style = t.title2.copy(color = c.ink))
            BasicText(message, style = t.body.copy(color = c.inkSecondary))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircasoButton(confirmLabel, onConfirm, style = if (destructive) ButtonStyle.Destructive else ButtonStyle.Primary, size = ButtonSize.Large, block = true)
            CircasoButton(cancelLabel, onDismiss, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
        }
    }
}
