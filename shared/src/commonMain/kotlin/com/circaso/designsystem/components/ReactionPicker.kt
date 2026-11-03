package com.circaso.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** The quick reactions (WhatsApp's six) and more behind "+". Any emoji is a valid reaction; these are the offer. */
val QuickReactions: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
val MoreReactions: List<String> = listOf(
    "🔥", "🎉", "👏", "😍", "🥰", "😁", "🤣", "😅", "😊", "😎", "🤔", "😴", "😡", "🙌", "💯", "✅", "💪", "👀", "🤝", "🙈",
    "😭", "😇", "🤩", "😬", "🥳", "🤗", "😏", "🙄", "😱", "🥺", "💔", "💚", "💙", "💜", "⭐", "🌟", "💰", "💸", "🙏🏽", "👍🏽",
)

// The bubble's own look (Pasaka's chats review, 2026-11-03). No scale token fits these yet; named here so they can
// move into CircasoShapes / an elevation token once one is agreed.
private val BubbleRadius = 26.dp
private val BubbleInset = 6.dp
private val BubbleElevation = 10.dp

/** Where the picker sits against the thing it reacts to. */
enum class ReactionAnchor {
    /** Just under the anchor (a chat message); it slides up only as far as it must near the bottom. */
    Below,
    /** Just above the anchor (controls at the bottom of the screen, like the Moments viewer). */
    Above,
}

/** Places the reaction bubble next to its anchor, on the anchor's [end] or start side, inside the window. */
private class ReactionPickerPosition(
    private val end: Boolean,
    private val anchor: ReactionAnchor,
    private val gap: Int,
    private val margin: Int,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val wanted = if (end) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val x = wanted.coerceIn(margin, maxOf(margin, windowSize.width - popupContentSize.width - margin))
        val y = when (anchor) {
            // Under the anchor; near the bottom it slides up only as far as it must, so it never jumps away when it grows.
            ReactionAnchor.Below -> minOf(anchorBounds.bottom + gap, windowSize.height - popupContentSize.height - margin)
            // Above the anchor; it grows upwards, its bottom edge staying put.
            ReactionAnchor.Above -> anchorBounds.top - gap - popupContentSize.height
        }.coerceAtLeast(margin)
        return IntOffset(x, y)
    }
}

/**
 * The WhatsApp-style reaction bubble, floating by its anchor (call it inside the anchor's layout): the six quick
 * reactions, + for the rest (it grows in place), and ⋯ for [onOptions] when given. Tapping [current] (my reaction)
 * takes it back: [onPick] gets null. A reaction picked from the long list stays in the row so it can be taken back.
 *
 * It closes itself when its anchor leaves the screen ([onDismiss] on dispose), so it never comes back by itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionPicker(
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onOptions: (() -> Unit)? = null,
    optionsLabel: String = "Message options",
    anchor: ReactionAnchor = ReactionAnchor.Below,
    /** Line up with the anchor's end edge (an outgoing message) rather than its start. */
    alignEnd: Boolean = false,
    gap: Dp = 6.dp,
    /** Opens with the full list showing (previews). */
    initiallyExpanded: Boolean = false,
) {
    val density = LocalDensity.current
    val provider = remember(alignEnd, anchor, gap, density) {
        with(density) { ReactionPickerPosition(alignEnd, anchor, gap.roundToPx(), CircasoSpacing.s2.roundToPx()) }
    }
    val dismiss by rememberUpdatedState(onDismiss)
    // The anchor scrolled away or closed: close the picker too.
    DisposableEffect(Unit) { onDispose { dismiss() } }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
        AnimatedVisibility(
            shown,
            enter = if (CircasoTheme.reduceMotion) fadeIn(tween(CircasoMotion.QuickMs))
            else fadeIn(tween(CircasoMotion.QuickMs)) + scaleIn(
                spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                initialScale = 0.6f,
                // Grows out of the corner nearest the anchor.
                transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, if (anchor == ReactionAnchor.Above) 1f else 0f),
            ),
        ) {
            ReactionBubble(current, onPick, modifier, onOptions, optionsLabel, initiallyExpanded)
        }
    }
}

/** The bubble itself, without the popup (the picker's content, and what previews draw in place). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionBubble(
    current: String?,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onOptions: (() -> Unit)? = null,
    optionsLabel: String = "Message options",
    initiallyExpanded: Boolean = false,
) {
    val c = CircasoTheme.colors
    val density = LocalDensity.current
    var more by remember { mutableStateOf(initiallyExpanded) }
    val extra = current?.takeIf { it !in QuickReactions }
    val cellsInRow = QuickReactions.size + 1 + (if (extra != null) 1 else 0) + (if (onOptions != null) 1 else 0)
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    // As big as a comfortable target, smaller only when the screen is too narrow for the whole row (the window margins
    // and the bubble's own inset and padding come off first).
    val cell = minOf(CircasoSizes.controlMd, (windowWidth - CircasoSpacing.s2 * 2 - (BubbleInset + CircasoSpacing.s1) * 2) / maxOf(cellsInRow, 8))
    val emoji = TextStyle(fontSize = with(density) { (cell * 0.58f).toSp() })
    val pick: (String) -> Unit = { e -> onPick(if (e == current) null else e) }
    val still = CircasoTheme.reduceMotion
    val shape = RoundedCornerShape(BubbleRadius)
    Column(
        modifier.padding(BubbleInset)
            .shadow(BubbleElevation, shape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
            .clip(shape).background(c.surfaceRaised)
            .then(if (c.isDark || c.isHighContrast) Modifier.border(1.dp, c.separator, shape) else Modifier)
            .animateContentSize(tween(if (still) 0 else CircasoMotion.BaseMs))
            .padding(CircasoSpacing.s1),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuickReactions.forEach { e -> ReactionCell(e, e == current, cell, emoji) { pick(e) } }
            // Mine from the long list stays visible in the row, so it can be taken back without opening it.
            extra?.let { e -> ReactionCell(e, true, cell, emoji) { pick(e) } }
            PickerIcon(if (more) CircasoIcon.ChevronDown else CircasoIcon.Plus, if (more) "Fewer reactions" else "More reactions", cell) { more = !more }
            onOptions?.let { PickerIcon(CircasoIcon.More, optionsLabel, cell, it) }
        }
        if (more) {
            FlowRow(Modifier.width(cell * 8).padding(bottom = 2.dp), maxItemsInEachRow = 8) {
                MoreReactions.forEach { e -> ReactionCell(e, e == current, cell, emoji) { pick(e) } }
            }
        }
    }
}

@Composable
private fun ReactionCell(emoji: String, on: Boolean, cell: Dp, style: TextStyle, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    Box(
        Modifier.size(cell).clip(CircasoShapes.circle)
            .background(if (on) c.fillStrong else Color.Transparent)
            .pressable(role = Role.Button, pressedScale = 0.85f, onClick = onClick)
            .semantics { contentDescription = if (on) "Remove reaction $emoji" else "React $emoji" },
        contentAlignment = Alignment.Center,
    ) { BasicText(emoji, style = style) }
}

@Composable
private fun PickerIcon(icon: CircasoIcon, label: String, cell: Dp, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    Box(
        // The whole cell takes the tap; the grey disc inside it is the look.
        Modifier.size(cell).pressable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(CircasoSpacing.s1).clip(CircasoShapes.circle).background(c.fill),
        contentAlignment = Alignment.Center,
    ) { CircasoIconImage(icon, c.inkSecondary, Modifier.size(18.dp)) }
}
