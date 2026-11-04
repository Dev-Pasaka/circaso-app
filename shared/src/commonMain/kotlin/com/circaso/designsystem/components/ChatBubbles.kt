package com.circaso.designsystem.components

import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.minimumInteractiveComponentSize
import com.circaso.designsystem.theme.CircasoSpacing
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import circaso.shared.generated.resources.Res
import circaso.shared.generated.resources.asha_icon
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoTheme
import org.jetbrains.compose.resources.painterResource

/*
 * The design's chat pieces (`cx-thread`, `cx-bubble`, `cx-bubble-time`, `cx-composer`, `cx-badge`, `.chip`, the
 * search pill), used by the person-to-person threads and by Asha, so both look the same.
 */

/** Asha's mark: the silver disc with the ring and sparks. [size] `sm` 28 (headers), `md` 44 (rows), larger for empty states. */
@Composable
fun AshaMark(modifier: Modifier = Modifier, size: Dp = CircasoSizes.avatarMd) {
    Image(painterResource(Res.drawable.asha_icon), contentDescription = "Asha", modifier = modifier.size(size))
}

/** `.cx-bubble`: 75% max width, 9×14 padding, radius lg with the corner nearest the sender tightened to 8. */
@Composable
fun ChatBubble(
    text: String,
    outgoing: Boolean,
    modifier: Modifier = Modifier,
    faded: Boolean = false,
    quiet: Boolean = false,
    onClick: (() -> Unit)? = null,
    above: (@Composable ColumnScope.() -> Unit)? = null,
    /** A long press: the reaction bubble (WhatsApp-style). */
    onLongClick: (() -> Unit)? = null,
    /** Asha writes Markdown (bold, lists); people's messages are shown exactly as typed. */
    markdown: Boolean = false,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val shape = bubbleShape(outgoing)
    Column(
        modifier
            .alpha(if (faded) 0.6f else 1f)
            .clip(shape)
            .background(if (outgoing && !quiet) c.action else c.fill)
            .then(
                when {
                    onClick != null -> Modifier.pressable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)
                    // Long press only (their messages): no button look or role for a tap that does nothing.
                    onLongClick != null -> Modifier.pointerInput(onLongClick) {
                        detectTapGestures(onLongPress = { onLongClick() })
                    }
                    else -> Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        above?.invoke(this)
        val style = t.body.copy(color = when { quiet -> c.inkTertiary; outgoing -> c.onAction; else -> c.ink })
        if (markdown) BasicText(markdownToAnnotated(text, c.fillStrong), style = style) else BasicText(text, style = style)
    }
}

fun bubbleShape(outgoing: Boolean): RoundedCornerShape =
    if (outgoing) RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 8.dp, bottomStart = 22.dp)
    else RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 8.dp)

/** `.cx-bubble-time`: 12/16 under the bubble, on the bubble's side; an icon and a tone for waiting and failed states. */
@Composable
fun BubbleMeta(text: String, modifier: Modifier = Modifier, icon: CircasoIcon? = null, color: Color = CircasoTheme.colors.inkSecondary) {
    val t = CircasoTheme.typography
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) CircasoIconImage(icon, color, Modifier.size(12.dp))
        BasicText(text, style = t.caption1.copy(color = color))
    }
}

/** One message's column: the bubble and its meta line, aligned to the sender's side, at most 75% wide. */
@Composable
fun MessageColumn(outgoing: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 292.dp), horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

/** The day marker between runs of messages ("Today"): 12/16, semibold, secondary, centred. */
@Composable
fun DayLabel(text: String, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = t.caption1.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold))
    }
}

/** A line the phone itself says in a thread ("Amina's security code changed"). */
@Composable
fun SystemLine(text: String, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = t.caption1.copy(color = c.inkSecondary, textAlign = TextAlign.Center), modifier = Modifier.padding(horizontal = 24.dp))
    }
}

/**
 * `.cx-composer` on the glass layer: an optional strip above (offline note, suggestion chips), then the bar with an
 * optional attach button, the field and the action-filled send button.
 *
 * The field departs from `.cx-composer` (decision 2026-10-30, the user: "bigger, borderless"): `fill`, no outline,
 * radius 24, at least 48 dp on one line. The high-contrast themes keep the hairline edge so the field still has a
 * visible boundary.
 */
@Composable
fun ComposerBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    canSend: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onAttach: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    above: (@Composable ColumnScope.() -> Unit)? = null,
    /** False while [trailing] stands in for Send (WhatsApp's mic while the field is empty). */
    showSend: Boolean = true,
    /**
     * Design C3: the recognised money token ("#500") as a chip at the start of the field; [value] is then the note.
     * Tapping the chip turns it back into text to edit; backspace in an empty note removes it.
     */
    chip: String? = null,
    onChipEdit: () -> Unit = {},
    /**
     * Floats over the conversation just above the bar, without taking room from it (the quick-pay card): the thread
     * keeps its size and the card sits on top of the last messages.
     */
    floating: (@Composable ColumnScope.() -> Unit)? = null,
    /** Lets the screen put the cursor in the field (a payment started from the goal strip or the profile). */
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    /** What the arrow does, for a screen reader ("Send KES 500 to Mum" while a payment is in the field). */
    sendLabel: String = "Send",
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    // Gesture-navigation phones report almost no bottom inset, and the field would sit in the screen's rounded corners.
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier.fillMaxWidth()) {
        if (floating != null) {
            Column(
                Modifier.fillMaxWidth().layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
                },
                content = floating,
            )
        }
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(0.dp), edge = false).padding(bottom = maxOf(navInset, 16.dp)).imePadding()) {
            above?.invoke(this)
            Row(
                Modifier.fillMaxWidth().padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2, bottom = CircasoSpacing.s1),
                horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                verticalAlignment = Alignment.Bottom,
            ) {
                // The buttons sit centred on a one-line field and stay at the bottom as it grows (WhatsApp).
                val buttonLift = Modifier.padding(bottom = (ComposerFieldMin - CircasoSizes.iconButton) / 2)
                if (onAttach != null) Box(buttonLift) { CircasoIconButton(CircasoIcon.Plus, "Attach", onAttach) }
                Box(
                    Modifier.weight(1f).defaultMinSize(minHeight = ComposerFieldMin).clip(ComposerFieldShape).background(c.fill)
                        .then(if (c.isHighContrast) Modifier.border(1.dp, c.glassEdge, ComposerFieldShape) else Modifier)
                        .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                        if (chip != null) {
                            BasicText(
                                chip,
                                style = t.amountSmall.copy(color = c.money, fontSize = t.body.fontSize, lineHeight = t.body.lineHeight),
                                modifier = Modifier.clip(CircasoShapes.pill).background(c.moneySurface)
                                    .pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = onChipEdit)
                                    .semantics { contentDescription = "Amount $chip. Tap to change" }
                                    // No vertical padding: the pill sits on the text line, so the field doesn't grow.
                                    .padding(horizontal = 10.dp),
                            )
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            // The field keeps its own cursor; when the text is set from outside (a picked coin, a chip
                            // turned back into text, a cleared draft) the cursor goes to the end, ready to keep typing.
                            var field by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
                            if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
                            BasicTextField(
                                value = field, onValueChange = { field = it; if (it.text != value) onValueChange(it.text) }, enabled = enabled, maxLines = 5,
                                textStyle = t.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                                modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).onPreviewKeyEvent { e ->
                                    (chip != null && value.isEmpty() && e.key == androidx.compose.ui.input.key.Key.Backspace && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown)
                                        .also { if (it) onChipEdit() }
                                },
                            )
                            if (value.isEmpty()) BasicText(if (chip != null) "Add a note" else placeholder, style = t.body.copy(color = c.inkSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(buttonLift, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    trailing?.invoke(this)
                    if (showSend) CircasoIconButton(CircasoIcon.Send, sendLabel, onSend, emphasised = canSend)
                }
            }
        }
    }
}

private val ComposerFieldMin = 48.dp
private val ComposerFieldShape = RoundedCornerShape(24.dp)

/** The 36 dp search pill on list screens (`fill`, icon, 17 pt placeholder). Tapping it focuses a real field below it. */
@Composable
fun SearchPill(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        modifier.fillMaxWidth().height(36.dp).clip(CircasoShapes.pill).background(c.fill).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircasoIconImage(CircasoIcon.Search, c.inkSecondary, Modifier.size(18.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(value = value, onValueChange = onValueChange, singleLine = true, textStyle = t.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink), modifier = Modifier.fillMaxWidth())
            if (value.isEmpty()) BasicText(placeholder, style = t.body.copy(color = c.inkSecondary))
        }
    }
}

/** `.cx-badge-count`: unread count, action on on-action, 20 dp tall. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Box(
        modifier.heightIn(min = 20.dp).widthIn(min = 20.dp).clip(CircasoShapes.pill).background(c.action).padding(horizontal = 6.dp)
            .semantics { contentDescription = "$count unread" },
        contentAlignment = Alignment.Center,
    ) { BasicText(count.toString(), style = t.caption1.copy(color = c.onAction, fontWeight = FontWeight.SemiBold)) }
}

/**
 * A small text badge in the row's end column ("Waiting"). `warning` is the design's `.cx-badge-warning`: no pill, the
 * text in the warning colour with an optional icon before it, for something that still needs someone's attention.
 */
@Composable
fun TextBadge(text: String, modifier: Modifier = Modifier, warning: Boolean = false, icon: CircasoIcon? = null) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    if (warning) {
        Row(modifier.heightIn(min = 20.dp), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) CircasoIconImage(icon, c.warning, Modifier.size(14.dp))
            BasicText(text, style = t.caption1.copy(color = c.warning, fontWeight = FontWeight.SemiBold))
        }
        return
    }
    Box(modifier.heightIn(min = 20.dp).clip(CircasoShapes.pill).background(c.fill).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = t.caption1.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold))
    }
}

/** `.chip`: a suggestion the person can tap to ask (Asha), pill on `fill`, 15 pt. */
@Composable
fun SuggestionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    /** A count in a small circle after the text (Unread 3); hidden at zero, and it pops as it changes. */
    badge: Int = 0,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    // A chip can also be one of a set of choices (who to pay, the chat filters): then it is a radio button, and the
    // picked one is filled in ink so it reads as chosen at a glance (the user, 2026-10-31).
    val on = selected == true
    // 34 dp to look at, 44 dp to hit (the minimum touch target).
    Box(
        modifier.minimumInteractiveComponentSize().heightIn(min = 34.dp).clip(CircasoShapes.pill).background(if (on) c.ink else c.fill)
            .pressable(role = if (selected != null) Role.RadioButton else Role.Button, onClick = onClick)
            .then(if (selected != null) Modifier.semantics { this.selected = on } else Modifier)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(text, style = t.subhead.copy(color = if (on) c.bg else c.ink, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
            androidx.compose.animation.AnimatedVisibility(
                badge > 0,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
            ) {
                // Inverted against the chip, so it reads on the filled (picked) chip as well as the plain one.
                Box(
                    Modifier.heightIn(min = 20.dp).widthIn(min = 20.dp).clip(CircasoShapes.pill).background(if (on) c.bg else c.ink).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.animation.AnimatedContent(badge, label = "count") { n ->
                        BasicText(n.toString(), style = t.caption1.copy(color = if (on) c.ink else c.bg, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}

/**
 * The thread header (C2, D2, F1): back, a centred stack of avatar, name and status line, trailing actions. Centred on
 * both platforms, because the avatar sits above the name.
 */
@Composable
fun ThreadHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    avatar: @Composable () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
    /** Tapping the photo or name opens who this is (WhatsApp's contact info). */
    onTitle: (() -> Unit)? = null,
    /** How many buttons [trailing] draws; the leading side is padded to match so the name stays centred. */
    trailingCount: Int = 1,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    if (com.circaso.designsystem.adaptive.LocalTopBarOnSide.current) {
        // Down the left side on a phone on its side: Back, who this is, then the call buttons.
        Column(modifier.fillMaxWidth().padding(start = CircasoSpacing.s4, end = CircasoSpacing.s2, top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!com.circaso.core.navigation.LocalIsDetailRoot.current) CircasoIconButton(CircasoIcon.Back, "Back", onBack)
            Column(
                if (onTitle != null) Modifier.pressable(role = androidx.compose.ui.semantics.Role.Button, pressedScale = 0.97f, onClick = onTitle)
                    .semantics(mergeDescendants = true) { contentDescription = "$title. $subtitle. View profile" } else Modifier,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                avatar()
                BasicText(title, style = t.body.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText(subtitle, style = t.caption1.copy(color = c.inkSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = trailing)
        }
        return
    }
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = CircasoSpacing.s4, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // The first screen beside a list has nothing to go back to (iPad): no Back there, its space kept.
        if (com.circaso.core.navigation.LocalIsDetailRoot.current) androidx.compose.foundation.layout.Spacer(Modifier.size(CircasoSizes.iconButton))
        else CircasoIconButton(CircasoIcon.Back, "Back", onBack)
        repeat((trailingCount - 1).coerceAtLeast(0)) { HeaderSpacer() }
        Column(
            Modifier.weight(1f).then(
                if (onTitle != null) Modifier.pressable(role = androidx.compose.ui.semantics.Role.Button, pressedScale = 0.97f, onClick = onTitle)
                    .semantics(mergeDescendants = true) { contentDescription = "$title. $subtitle. View profile" } else Modifier,
            ),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            avatar()
            BasicText(title, style = t.body.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            BasicText(subtitle, style = t.caption1.copy(color = c.inkSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Keeps the header balanced when there is no trailing action (the design leaves a 36 dp gap). */
@Composable
fun RowScope.HeaderSpacer() { Box(Modifier.width(CircasoSizes.iconButton)) }
