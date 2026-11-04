package com.circaso.feature.wallet.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.chama.presentation.amountText
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletOwnerType
import com.circaso.feature.wallet.domain.WalletStatus

// The popover's width: wide enough for "Kilimani Chama" and a balance on one line, narrow enough to read as a menu
// anchored to its corner rather than a sheet. No scale token is this size; named here.
private val PopoverMaxWidth = 300.dp
private val PopoverMinWidth = 248.dp
// Room around the popover inside its window, so the floating shadow isn't cut off at the window's edge (as
// ReactionPicker does with its bubble).
private val PopoverInset = CircasoSpacing.s4
// The label in the corner truncates past this, so a long group name never pushes into the page.
private val LabelMaxWidth = 132.dp

/**
 * The name the corner shows: the whole name when it is short, else its first two words, else its first word
 * ("Mama Mboga Savings Group" → "Mama Mboga"). The full name is on the menu and in the caption.
 */
internal fun shortWalletName(name: String): String {
    val limit = 16
    if (name.length <= limit) return name
    val words = name.trim().split(' ').filter { it.isNotEmpty() }
    val two = words.take(2).joinToString(" ")
    return if (two.length <= limit) two else words.firstOrNull() ?: name
}

/**
 * GW16 as Pasaka redrew it (2026-11-04): no title; a compact control in the top-right corner, the wallet glyph with the
 * wallet's short name beside it and a chevron, that opens a popover anchored to the corner listing every wallet the
 * person can see. Built like the bars' [com.circaso.designsystem.components.CircasoIconButton] (36 dp, `fill`), inside
 * a 44 dp target. Shown only when there is another wallet (with the group-wallets flag off there never is).
 */
@Composable
fun WalletSwitcher(
    wallets: List<NamedWallet>,
    selected: NamedWallet,
    open: Boolean,
    balances: Map<String?, Wallet?>,
    locked: Set<String>,
    hidden: Boolean,
    onOpen: (Boolean) -> Unit,
    onPick: (NamedWallet) -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val still = CircasoTheme.reduceMotion
    val label = if (selected.personal) NamedWallet.PersonalName else selected.name
    val turn by animateFloatAsState(if (open) 180f else 0f, if (still) tween(0) else tween(CircasoMotion.BaseMs), label = "chevron")
    Box(
        Modifier.heightIn(min = CircasoSizes.controlMd)
            .pressable(role = Role.Button, onClick = { onOpen(!open) })
            .semantics(mergeDescendants = true) {
                contentDescription = "Wallet: $label. Switch wallet"
                stateDescription = if (open) "Expanded" else "Collapsed"
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = CircasoSizes.iconButton).clip(CircasoShapes.pill).background(c.fill)
                .padding(start = CircasoSpacing.s2, end = CircasoSpacing.s3, top = CircasoSpacing.s1, bottom = CircasoSpacing.s1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1 + CircasoSpacing.s1 / 2),
        ) {
            CircasoIconImage(CircasoIcon.Wallet, c.ink, Modifier.size(18.dp))
            BasicText(shortWalletName(label), Modifier.widthIn(max = LabelMaxWidth), style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            CircasoIconImage(CircasoIcon.ChevronDown, c.inkSecondary, Modifier.size(14.dp).graphicsLayer { rotationZ = turn }, strokeWidth = 2.4f)
        }
        val window = with(LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() }
        WalletPopover(open, onDismiss = { onOpen(false) }) {
            WalletMenu(wallets, selected.walletId, balances, locked, hidden, onPick, windowWidth = window)
        }
    }
}

/**
 * Under the anchor, the card's right edge on the anchor's right edge, kept inside the window. The popup window is
 * [inset] bigger than the card on every side (room for its shadow), so the window is placed that much further out.
 */
private class CornerPosition(private val gap: Int, private val inset: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val wanted = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width + inset else anchorBounds.left - inset
        val x = wanted.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val y = minOf(anchorBounds.bottom + gap - inset, windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/**
 * The popover: grows out of the corner it hangs from with a soft spring and fades away faster than it came (reduced
 * motion: a fade). It stays composed while it closes, so the exit plays; back, a tap outside or a pick closes it.
 */
@Composable
private fun WalletPopover(open: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = open
    if (!shown.currentState && !shown.targetState) return
    val density = LocalDensity.current
    val provider = remember(density) { with(density) { CornerPosition(CircasoSpacing.s1.roundToPx(), PopoverInset.roundToPx()) } }
    val dismiss by rememberUpdatedState(onDismiss)
    val still = CircasoTheme.reduceMotion
    val origin = TransformOrigin(1f, 0f)
    Popup(popupPositionProvider = provider, onDismissRequest = { dismiss() }, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            shown,
            enter = if (still) fadeIn(tween(CircasoMotion.BaseMs))
            else fadeIn(tween(CircasoMotion.BaseMs)) + scaleIn(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.88f, transformOrigin = origin),
            exit = if (still) fadeOut(tween(CircasoMotion.QuickMs))
            else fadeOut(tween(CircasoMotion.QuickMs)) + scaleOut(tween(CircasoMotion.QuickMs), targetScale = 0.94f, transformOrigin = origin),
        ) { content() }
    }
}

/**
 * The popover's list: each wallet's picture (a group's photo or initials; Personal is the wallet glyph), its name,
 * what the person can do with it, and the last balance the phone read, small. The one on screen carries a tick.
 */
@Composable
internal fun WalletMenu(
    wallets: List<NamedWallet>,
    selectedId: String?,
    balances: Map<String?, Wallet?>,
    locked: Set<String>,
    hidden: Boolean,
    onPick: (NamedWallet) -> Unit,
    /** The app window's width, read outside the popup (inside it, the window is the popup's own). */
    windowWidth: androidx.compose.ui.unit.Dp = PopoverMaxWidth + CircasoSpacing.s4 * 2,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    // Dynamic Type: the menu widens with the text (up to the window, less a margin), and at the largest sizes the
    // balance moves under the name so the name keeps its room.
    val density = LocalDensity.current
    val maxWidth = minOf(PopoverMaxWidth * density.fontScale.coerceAtLeast(1f), windowWidth - CircasoSpacing.s4 * 2)
    val large = density.fontScale > 1.3f
    Column(
        Modifier.padding(PopoverInset).widthIn(min = minOf(PopoverMinWidth, maxWidth), max = maxWidth).glass(CircasoShapes.lg, floating = true).clip(CircasoShapes.lg)
            .verticalScroll(rememberScrollState())
            .semantics { paneTitle = "Wallets" }
            .padding(vertical = CircasoSpacing.s1),
    ) {
        BasicText(
            "Wallets", Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2, bottom = CircasoSpacing.s1).semantics { heading() },
            style = t.footnote.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold),
        )
        wallets.forEachIndexed { i, w ->
            if (i > 0) RowDivider(Modifier.padding(start = CircasoSizes.avatarSm + CircasoSpacing.s3))
            val on = w.walletId == selectedId
            val isLocked = w.walletId != null && w.walletId in locked
            val balance = balances[w.walletId]?.let { b -> b.usdc?.let { if (hidden) "••••" else amountText(it.amountMinor, b.fiat) } }
            val sub = when {
                w.personal -> "Your own money"
                isLocked -> "Locked · its signer left"
                w.status == WalletStatus.SettingUp -> "Being set up"
                w.kind == WalletOwnerType.Space -> if (w.canSign) "Space wallet · you send" else "Space wallet · view only"
                w.canSign -> "You send, after approval"
                else -> "View only · payouts need approval"
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = CircasoSizes.controlMd)
                    .pressable(role = Role.RadioButton, onClick = { onPick(w) })
                    .semantics(mergeDescendants = true) {
                        this.selected = on
                        contentDescription = listOfNotNull(w.name, sub, balance?.takeIf { !hidden }).joinToString(", ")
                    }
                    .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
            ) {
                if (w.personal) {
                    Box(Modifier.size(CircasoSizes.avatarSm).clip(CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
                        CircasoIconImage(CircasoIcon.Wallet, c.ink, Modifier.size(16.dp))
                    }
                } else Avatar(w.name, size = CircasoSizes.avatarSm, imageUrl = w.avatarUrl)
                Column(Modifier.weight(1f)) {
                    BasicText(w.name, style = t.body.copy(color = c.ink, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), maxLines = if (large) 2 else 1, overflow = TextOverflow.Ellipsis)
                    BasicText(sub, style = t.footnote.copy(color = if (isLocked) c.warning else c.inkSecondary), maxLines = 2)
                    if (large && balance != null) BasicText(balance, style = t.footnote.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"), maxLines = 1)
                }
                if (!large && balance != null) BasicText(balance, style = t.footnote.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"), maxLines = 1)
                // The tick keeps its place on every row, so the balances line up whichever wallet is chosen.
                if (on) CircasoIconImage(CircasoIcon.Check, c.action, Modifier.size(18.dp), strokeWidth = 2.4f)
                else Spacer(Modifier.width(18.dp))
            }
        }
    }
}
