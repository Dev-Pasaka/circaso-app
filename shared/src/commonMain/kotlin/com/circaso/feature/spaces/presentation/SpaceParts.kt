package com.circaso.feature.spaces.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.PricePeriod
import com.circaso.feature.spaces.domain.SpacePrice
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.domain.SpaceSummary
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.usdcToFiat

// The parts every space screen shares (phase 0b frames SD–SW): the space tile, the default cover, role badges, the
// anchored popover (spaces never use bottom sheets for menus), system lines, skeletons and the money words.

/**
 * A space's picture: a rounded square (people are circles, decision "space photos are rounded squares"), the photo over
 * its initials so a failed or offline load still looks right. 14 dp corners below 60 dp, 22 dp above (canvas `.sp-tile`).
 */
@Composable
fun SpaceTile(name: String, photoUrl: String?, size: Dp, modifier: Modifier = Modifier, ring: Boolean = false) {
    val c = CircasoTheme.colors
    val shape = RoundedCornerShape(if (size < 60.dp) (size.value * 0.27f).dp.coerceAtMost(14.dp) else 22.dp)
    val initials = name.split(' ').filter { it.isNotBlank() }.let { w -> (w.firstOrNull()?.take(1).orEmpty() + (if (w.size > 1) w.last().take(1) else "")).uppercase() }
    Box(
        modifier.size(size).then(if (ring) Modifier.border(4.dp, c.bg, shape).padding(0.dp) else Modifier).clip(shape).background(c.fillStrong)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(initials, style = CircasoTheme.typography.headline.copy(color = c.ink, fontSize = (size.value * 0.34f).sp, fontWeight = FontWeight.SemiBold))
        if (photoUrl != null) {
            val key = photoUrl.substringBefore('?')
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current).data(photoUrl).memoryCacheKey("$key@${size.value.toInt()}").diskCacheKey(key).crossfade(true).build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size),
            )
        }
    }
}

@Composable
fun SpaceTile(space: SpaceSummary, size: Dp, modifier: Modifier = Modifier, ring: Boolean = false) = SpaceTile(space.name, space.photoUrl, size, modifier, ring)

/** The cover's height on the space page (canvas `.cover`). */
val SpaceCoverHeight = 178.dp

/**
 * The default cover drawn from tokens until the owner adds one (decision): the brand's rings on a quiet fill, so a new
 * space never looks broken. A cover photo, when there is one, is drawn over it.
 */
@Composable
fun SpaceCover(coverUrl: String?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val c = CircasoTheme.colors
    Box(modifier.fillMaxWidth().height(SpaceCoverHeight).background(c.fill)) {
        Canvas(Modifier.matchParentSize()) {
            val ring = c.fillStrong
            val w = 2.dp.toPx()
            drawCircle(ring, radius = 48.dp.toPx(), center = Offset(size.width * 0.84f, size.height * 0.34f), style = Stroke(w))
            drawCircle(ring, radius = 79.5.dp.toPx(), center = Offset(size.width * 0.84f, size.height * 0.34f), style = Stroke(1.dp.toPx()))
            drawCircle(ring, radius = 71.5.dp.toPx(), center = Offset(size.width * 0.12f, size.height * 1.10f), style = Stroke(w))
        }
        if (coverUrl != null) {
            val key = coverUrl.substringBefore('?')
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current).data(coverUrl).diskCacheKey(key).memoryCacheKey("$key@cover").crossfade(true).build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(),
            )
        }
        content()
    }
}

/** The small role badge next to a name (canvas `.role`): Owner, Admin, Moderator. Members get none. */
@Composable
fun RoleBadge(text: String, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    BasicText(
        text,
        modifier.clip(CircasoShapes.pill).background(c.fill).heightIn(min = 18.dp).padding(horizontal = 6.dp, vertical = 2.dp),
        style = CircasoTheme.typography.caption2.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        maxLines = 1,
    )
}

@Composable
fun RoleBadge(role: SpaceRole?, modifier: Modifier = Modifier) {
    if (role == null || role == SpaceRole.Member) return
    RoleBadge(role.label, modifier)
}

/** The kind icon for a visibility: globe public, ticket members only, lock invite only. */
fun visibilityIcon(v: SpaceVisibility): CircasoIcon = when (v) {
    SpaceVisibility.Public -> CircasoIcon.Globe
    SpaceVisibility.MembersOnly -> CircasoIcon.Ticket
    SpaceVisibility.InviteOnly -> CircasoIcon.Lock
}

/** "1.2k", "4.8k", "910", "1,204" (canvas counts). [exact] keeps every digit (the chat header's "1,204 members"). */
fun countText(n: Int, exact: Boolean = false): String = when {
    exact || n < 1000 -> MinorUnits.toDisplay(n.toString(), 0, 0)
    n < 10_000 -> ((n / 100) / 10.0).toString().removeSuffix(".0") + "k"
    n < 1_000_000 -> (n / 1000).toString() + "k"
    else -> ((n / 100_000) / 10.0).toString().removeSuffix(".0") + "m"
}

/** "Public · 1.2k", "Members only · 214", "Invite only · 48" (the page's kind line). */
fun kindLine(space: SpaceSummary): String = "${space.visibility.label} · ${countText(space.memberCount)}"

/** A USDC amount for people: "5.00 USDC". */
fun usdcText(minor: String): String = MinorUnits.toDisplay(minor, 6) + " USDC"

/** The amount in the person's currency ("KES 646"), whole when it is whole, or null without a rate. */
fun fiatText(minor: String, fiat: FiatValue?): String? {
    val f = fiat ?: return null
    val v = usdcToFiat(minor, f.rate) ?: return null
    val whole = v.substringBefore('.')
    val cents = v.substringAfter('.', "00")
    val shown = if (cents.trimEnd('0').isEmpty()) MinorUnits.toDisplay(whole, 0, 0) else MinorUnits.toDisplay(whole + cents.padEnd(2, '0'), 2)
    return "${f.currency} $shown"
}

/**
 * A members-only price as a row says it: "5 USDC (KES 646) a month" (decision: USDC with KES beside it); one-time
 * prices drop the period. Without a rate, USDC alone.
 */
fun priceRowText(price: SpacePrice, fiat: FiatValue?): String {
    val usdc = MinorUnits.toDisplay(price.amountMinor, 6, 0) + " USDC"
    val local = fiatText(price.amountMinor, fiat)?.let { " ($it)" }.orEmpty()
    return usdc + local + if (price.period == PricePeriod.OneTime) "" else " ${price.period.per}"
}

/** The join button and card lead with the payer's currency: "KES 646 a month" (SS2). */
fun priceLeadText(price: SpacePrice, fiat: FiatValue?): String {
    val lead = fiatText(price.amountMinor, fiat) ?: usdcText(price.amountMinor)
    return lead + if (price.period == PricePeriod.OneTime) "" else " ${price.period.per}"
}

/** A date as the frames write it: "4 Dec". */
fun shortDate(millis: Long): String = com.circaso.core.platform.formatLocalTime(millis, com.circaso.core.platform.LocalTimeStyle.ShortDate)

/** A clock time: "20:00". */
fun clockTime(millis: Long): String = com.circaso.core.platform.formatLocalTime(millis, com.circaso.core.platform.LocalTimeStyle.Clock)

/** A line the chat shows to me only (canvas `.sysline`): centred, small, with an icon. */
@Composable
fun SysLine(text: String, icon: CircasoIcon? = null, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp).semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Top,
    ) {
        Row(Modifier.widthIn(max = 300.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (icon != null) CircasoIconImage(icon, c.inkSecondary, Modifier.padding(top = 2.dp).size(13.dp))
            BasicText(text, style = CircasoTheme.typography.caption1.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        }
    }
}

/** A skeleton block the size of what will come (no spinner on first loads). */
@Composable
fun SkeletonBlock(width: Dp, height: Dp, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(6.dp)) {
    Box(modifier.size(width, height).clip(shape).background(CircasoTheme.colors.fill))
}

/** The trust line in plain words (SS4, SH1, SI2b). */
const val NotEndToEnd = "Chats in spaces are not end-to-end encrypted."
const val NotEndToEndLong = "Chats in spaces are not end-to-end encrypted. Circaso and the space’s admins can read them to keep the space safe. Private messages to the owner are end-to-end encrypted."

// ---------------------------------------------------------------- the popover

/**
 * An anchored popover (canvas `.pop`): grows from the corner nearest what opened it, closes on a tap outside, Back or a
 * pick. Spaces never use bottom sheets for menus (decision); destructive confirms happen inside it ([PopoverConfirm]).
 * Call it inside the anchor's layout; it positions itself below the anchor, or above when there is no room.
 */
@Composable
fun SpacePopover(
    open: Boolean,
    onDismiss: () -> Unit,
    label: String,
    alignEnd: Boolean = false,
    width: Dp = 250.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = open
    if (!shown.currentState && !shown.targetState) return
    val density = LocalDensity.current
    val provider = remember(alignEnd, density) { with(density) { AnchorBelow(alignEnd, CircasoSpacing.s1.roundToPx(), CircasoSpacing.s4.roundToPx()) } }
    val dismiss by rememberUpdatedState(onDismiss)
    val still = CircasoTheme.reduceMotion
    val origin = TransformOrigin(if (alignEnd) 1f else 0f, 0f)
    val c = CircasoTheme.colors
    Popup(popupPositionProvider = provider, onDismissRequest = { dismiss() }, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            shown,
            enter = if (still) fadeIn(tween(CircasoMotion.BaseMs))
            else fadeIn(tween(CircasoMotion.BaseMs)) + scaleIn(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.88f, transformOrigin = origin),
            exit = if (still) fadeOut(tween(CircasoMotion.QuickMs))
            else fadeOut(tween(CircasoMotion.QuickMs)) + scaleOut(tween(CircasoMotion.QuickMs), targetScale = 0.94f, transformOrigin = origin),
        ) {
            val fontScale = density.fontScale.coerceAtLeast(1f)
            Column(
                Modifier.widthIn(min = minOf(width, 320.dp), max = (width * fontScale).coerceAtMost(360.dp))
                    .clip(CircasoShapes.md).background(c.surfaceRaised).border(1.dp, c.separator, CircasoShapes.md)
                    .verticalScroll(rememberScrollState())
                    .semantics { paneTitle = label },
                content = content,
            )
        }
    }
}

/** The popover's small heading ("Notifications", "Faith Chebet · member"). */
@Composable
fun PopoverHead(text: String) {
    BasicText(
        text, Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s3, bottom = CircasoSpacing.s1).semantics { heading() },
        style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary, fontWeight = FontWeight.SemiBold),
    )
}

/** One item (44 dp tall at least); [critical] items are red and come last. [checked] draws the tick of a choice. */
@Composable
fun PopoverItem(
    text: String,
    onClick: () -> Unit,
    icon: CircasoIcon? = null,
    critical: Boolean = false,
    enabled: Boolean = true,
    checked: Boolean? = null,
    divider: Boolean = false,
) {
    val c = CircasoTheme.colors
    if (divider) RowDivider()
    val tint = when { !enabled -> c.inkTertiary; critical -> c.critical; else -> c.ink }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .pressable(enabled = enabled, role = if (checked != null) Role.RadioButton else Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { if (checked != null) this.selected = checked }
            .padding(horizontal = CircasoSpacing.s4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        BasicText(text, Modifier.weight(1f), style = CircasoTheme.typography.body.copy(color = tint))
        when {
            checked == true -> CircasoIconImage(CircasoIcon.Check, c.ink, Modifier.size(18.dp), strokeWidth = 2.4f)
            checked == false -> Spacer(Modifier.width(18.dp))
            icon != null -> CircasoIconImage(icon, if (critical) c.critical else c.inkSecondary, Modifier.size(18.dp), strokeWidth = 1.8f)
        }
    }
}

/** A quiet line under the items ("Only admins can remove people or change roles."). */
@Composable
fun PopoverNote(text: String) {
    RowDivider()
    BasicText(text, Modifier.padding(horizontal = CircasoSpacing.s4, vertical = 10.dp), style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary))
}

/** A destructive confirm inside the popover (SM6, SR1c): title, what happens, Cancel and the red action. */
@Composable
fun ColumnScope.PopoverConfirm(title: String, message: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit, cancel: String = "Cancel", busy: Boolean = false, enabled: Boolean = true) {
    val t = CircasoTheme.typography
    val c = CircasoTheme.colors
    Column(Modifier.padding(horizontal = CircasoSpacing.s4, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(title, Modifier.semantics { heading() }, style = t.headline.copy(color = c.ink))
        BasicText(message, style = t.subhead.copy(color = c.inkSecondary))
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            CircasoButton(cancel, onCancel, style = ButtonStyle.Secondary, size = ButtonSize.Small)
            CircasoButton(confirm, onConfirm, style = ButtonStyle.Destructive, size = ButtonSize.Small, busy = busy, enabled = enabled)
        }
    }
}

/** Below the anchor (start or end aligned, kept inside the window), or above it when there is no room below. */
private class AnchorBelow(private val alignEnd: Boolean, private val gap: Int, private val inset: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val end = alignEnd != rtl
        val rawX = if (end) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val x = rawX.coerceIn(inset, (windowSize.width - popupContentSize.width - inset).coerceAtLeast(inset))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height - inset) below
        else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(inset)
        return IntOffset(x, y)
    }
}

/** The gentle dim behind an open popover (canvas `.dim-behind`): the page stays readable under it. */
@Composable
fun BoxScope.DimBehind(visible: Boolean) {
    if (!visible) return
    Box(Modifier.matchParentSize().background(CircasoTheme.colors.bg.copy(alpha = 0.4f)))
}

/** A glass bar along the bottom of a chat (canvas `composer_note`): one line and an optional action. */
@Composable
fun ComposerNote(text: String, action: String? = null, onAction: () -> Unit = {}, modifier: Modifier = Modifier, actionEnabled: Boolean = true) {
    val c = CircasoTheme.colors
    Row(
        modifier.fillMaxWidth().glass(RoundedCornerShape(0.dp)).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        BasicText(text, Modifier.weight(1f), style = CircasoTheme.typography.subhead.copy(color = c.inkSecondary))
        if (action != null) CircasoButton(action, onAction, style = ButtonStyle.Secondary, size = ButtonSize.Small, enabled = actionEnabled)
    }
}

