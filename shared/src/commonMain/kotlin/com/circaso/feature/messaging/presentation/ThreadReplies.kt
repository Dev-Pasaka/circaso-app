package com.circaso.feature.messaging.presentation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.SheetHead
import com.circaso.designsystem.chrome.sheetGroupColor
import com.circaso.designsystem.chrome.sheetGroupEdge
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SuggestionChip
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import kotlin.math.roundToInt

/** How far a message must be pulled to reply on release, and the most it moves (it eases past the threshold). */
private val ReplyThreshold = 56.dp
private val ReplyMaxPull = 88.dp
/** The reply arrow behind a pulled message: a small disc with the icon. */
private val ReplyArrowDisc = 28.dp
private val ReplyArrowIcon = 16.dp
/** The accent bar down a quote's leading edge. */
private val QuoteBar = 3.dp

/**
 * Swipe to reply (WhatsApp, Pasaka 2026-11-11): pull a message to the right, yours or theirs. It tracks the finger
 * directly, eases past [ReplyThreshold] (where the arrow fills in, with a haptic tick) towards [ReplyMaxPull], and
 * springs back on release; let go past the threshold and [onReply] runs. Only horizontal drags are taken, so the thread
 * still scrolls and long-press still reacts. The gesture outlives recompositions (the reply action is read when used),
 * and [onReply] null only switches it off, so the message itself never rebuilds when it's sent.
 */
@Composable
fun SwipeToReply(onReply: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val threshold = with(density) { ReplyThreshold.toPx() }
    val maxPull = with(density) { ReplyMaxPull.toPx() }
    val haptics = LocalHapticFeedback.current
    val reply by rememberUpdatedState(onReply)
    val reduceMotion by rememberUpdatedState(CircasoTheme.reduceMotion)
    // The finger's travel (raw) and where the message is drawn (eased past the threshold).
    var travel by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    fun eased(raw: Float): Float = if (raw <= threshold) raw.coerceAtLeast(0f) else {
        // Past the threshold it slows smoothly towards the max instead of hitting a wall.
        val over = raw - threshold
        val room = maxPull - threshold
        threshold + room * (1f - 1f / (1f + over / room))
    }
    val dragState = rememberDraggableState { delta ->
        travel = (travel + delta).coerceAtLeast(0f)
        shown = eased(travel)
        val nowArmed = shown >= threshold
        if (nowArmed && !armed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        armed = nowArmed
    }
    Box(
        modifier
            .then(if (onReply != null) Modifier.semantics { customActions = listOf(CustomAccessibilityAction("Reply") { reply?.invoke(); true }) } else Modifier)
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = onReply != null,
                onDragStopped = {
                    if (armed) reply?.invoke()
                    armed = false
                    travel = 0f
                    val spec = if (reduceMotion) tween<Float>(CircasoMotion.QuickMs) else spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
                    animate(shown, 0f, animationSpec = spec) { value, _ -> shown = value }
                },
            ),
    ) {
        // The arrow waits behind the message's leading edge and fills in as it's pulled; only its layer changes.
        Box(
            Modifier.align(Alignment.CenterStart).padding(start = CircasoSpacing.s1).size(ReplyArrowDisc)
                .graphicsLayer {
                    val progress = (shown / threshold).coerceIn(0f, 1f)
                    alpha = progress
                    val scale = if (reduceMotion) 1f else 0.6f + 0.4f * progress
                    scaleX = scale; scaleY = scale
                }
                .clip(CircasoShapes.circle).background(CircasoTheme.colors.fill),
            contentAlignment = Alignment.Center,
        ) { CircasoIconImage(CircasoIcon.Reply, CircasoTheme.colors.ink, Modifier.size(ReplyArrowIcon)) }
        Box(Modifier.offset { IntOffset(shown.roundToInt(), 0) }) { content() }
    }
}

/** What a reply's quote can say about the answered message. */
sealed interface QuoteState {
    data class Found(val name: String, val preview: String) : QuoteState
    data object Deleted : QuoteState
    data object Unreadable : QuoteState
    /** This phone doesn't have it (it came before this phone's history). */
    data object Missing : QuoteState
}

/** A reply's quote as the bubble draws it, and how to jump to the original when it's in the thread. */
class QuoteUi(val state: QuoteState, val onOpen: (() -> Unit)?)

/**
 * The answered message inside a reply's bubble: an accent bar, who wrote it and its first lines; tap to jump to it.
 * Same surface and shape as the moment quote, so the two read as siblings on either bubble colour.
 */
@Composable
fun ReplyQuoteCard(quote: QuoteUi, outgoing: Boolean) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val ink = if (outgoing) c.onAction else c.ink
    val accent = if (outgoing) c.onAction else c.action
    val shape = CircasoShapes.md
    val (name, line) = when (val s = quote.state) {
        is QuoteState.Found -> s.name.ifBlank { null } to s.preview
        QuoteState.Deleted -> null to "Original message was deleted"
        QuoteState.Unreadable -> null to "A message this phone can't show"
        QuoteState.Missing -> null to "Original message isn't on this phone"
    }
    Row(
        Modifier.padding(bottom = CircasoSpacing.s1).fillMaxWidth().height(IntrinsicSize.Min).clip(shape)
            .background(ink.copy(alpha = 0.12f))
            .then(quote.onOpen?.let { open -> Modifier.pressable(role = Role.Button, pressedScale = 0.98f, onClick = open) } ?: Modifier)
            .semantics(mergeDescendants = true) { contentDescription = "Reply to " + (name?.let { "$it: " } ?: "") + line },
    ) {
        Box(Modifier.width(QuoteBar).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = CircasoSpacing.s2, vertical = CircasoSpacing.s1)) {
            name?.let { BasicText(it, style = t.caption1.copy(color = accent), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            BasicText(line, style = t.footnote.copy(color = ink.copy(alpha = if (name == null) 0.7f else 0.9f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Above the composer while replying: who and what is being answered (tap to see it), with a × to stop. */
@Composable
fun ReplyBar(target: ReplyTarget, onCancel: () -> Unit, onOpen: (() -> Unit)? = null) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().padding(start = CircasoSpacing.s4, end = CircasoSpacing.s2, top = CircasoSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).height(IntrinsicSize.Min).clip(CircasoShapes.md).background(c.fill)
                .then(onOpen?.let { Modifier.pressable(role = Role.Button, pressedScale = 0.98f, onClick = it) } ?: Modifier),
        ) {
            Box(Modifier.width(QuoteBar).fillMaxHeight().background(c.action))
            Column(Modifier.padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2)) {
                BasicText(
                    if (target.outgoing) "Replying to your message" else "Replying to ${target.senderName}",
                    style = t.caption1.copy(color = c.action), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                BasicText(target.preview, style = t.footnote.copy(color = c.inkSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            Modifier.size(CircasoSizes.controlMd).clip(CircasoShapes.circle).pressable(role = Role.Button, onClick = onCancel)
                .semantics { contentDescription = "Cancel reply" },
            contentAlignment = Alignment.Center,
        ) { CircasoIconImage(CircasoIcon.Close, c.inkSecondary, Modifier.size(18.dp)) }
    }
}

/** Who reacted to one message in a group: one person per row, mine first, filtered by emoji. */
data class Reactor(val userId: String, val name: String, val avatarUrl: String?, val emoji: String, val mine: Boolean)

/**
 * The reactions sheet for a group message (WhatsApp, Pasaka 2026-11-11): "All N" then a chip per emoji with its count;
 * below, each person and their emoji. My own row says "Tap to remove".
 */
@Composable
fun ColumnScope.ReactorsSheetContent(reactors: List<Reactor>, onRemoveMine: () -> Unit, onClose: () -> Unit) {
    val t = CircasoTheme.typography
    var picked by remember { mutableStateOf<String?>(null) }
    val counts = reactors.groupingBy { it.emoji }.eachCount().entries.sortedByDescending { it.value }
    // Someone took back the emoji being filtered on: show everyone again.
    val filter = picked?.takeIf { p -> counts.any { it.key == p } }
    SheetHead("Reactions", onClose)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        SuggestionChip("All ${reactors.size}", { picked = null }, selected = filter == null)
        counts.forEach { (emoji, n) -> SuggestionChip("$emoji $n", { picked = emoji }, selected = filter == emoji) }
    }
    val shown = reactors.filter { filter == null || it.emoji == filter }.sortedByDescending { it.mine }
    val shape = CircasoShapes.md
    Column(Modifier.fillMaxWidth().clip(shape).background(sheetGroupColor()).sheetGroupEdge(shape)) {
        shown.forEachIndexed { i, r ->
            if (i > 0) RowDivider()
            ListRow(
                if (r.mine) "You" else r.name,
                sub = if (r.mine) "Tap to remove" else null,
                leading = { Avatar(r.name, size = CircasoSizes.avatarSm, imageUrl = r.avatarUrl) },
                trailing = { BasicText(r.emoji, style = t.title3) },
                onClick = if (r.mine) ({ onRemoveMine(); onClose() }) else null,
            )
        }
    }
    Spacer(Modifier.height(CircasoSpacing.s2))
}

/** The server's id of a message, when it has one (only those can be replied to or jumped to). */
fun com.circaso.feature.messaging.domain.Message.serverId(): String? = when (this) {
    is com.circaso.feature.messaging.domain.Message.Text -> messageId
    is com.circaso.feature.messaging.domain.Message.Media -> messageId
    is com.circaso.feature.messaging.domain.Message.Payment -> messageId
    else -> null
}

fun com.circaso.feature.messaging.domain.Message.isOutgoing(): Boolean = when (this) {
    is com.circaso.feature.messaging.domain.Message.Text -> outgoing
    is com.circaso.feature.messaging.domain.Message.Media -> outgoing
    is com.circaso.feature.messaging.domain.Message.Payment -> outgoing
    is com.circaso.feature.messaging.domain.Message.Deleted -> outgoing
    else -> false
}

/** Who wrote a message, for a quote ("You" for mine). */
fun com.circaso.feature.messaging.domain.Message.quoteName(): String = when {
    isOutgoing() -> "You"
    this is com.circaso.feature.messaging.domain.Message.Text -> senderName
    this is com.circaso.feature.messaging.domain.Message.Media -> senderName
    this is com.circaso.feature.messaging.domain.Message.Payment -> senderName
    else -> "Message"
}
