package com.circaso.feature.wallet.presentation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.SheetHead
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.chama.presentation.GroupWalletUiState
import com.circaso.feature.chama.presentation.amountText
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletStatus

// The control's measures (Pasaka, 2026-11-06: the 232 x 44 dp bar was too big). The track is 32 dp to look at,
// like iOS's compact segmented control; the touch target around it is the full 44 dp, and both grow with Dynamic Type.
private val TouchHeight = CircasoSizes.controlMd
private val TrackHeight = 32.dp
private val TrackInset = 2.dp
private val SegmentPadding = 14.dp
// The narrowest a segment gets, so the two short words still read as a control and not as two words.
private val SegmentMinWidth = 76.dp

/** The overflow menu's actions that leave the tab: the group's chat and its info (members, approvals, its wallet). */
class GroupMenuActions(val onOpenChat: (chatId: String) -> Unit, val onGroupInfo: (chatId: String) -> Unit)

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
 * Personal | Groups at the top of the Wallet tab: a compact segmented control, centred, sized to its two words
 * (Pasaka, 2026-11-06). The track (`fill`) and the lifted side (`fillSelected`, with a hairline shadow in light
 * themes) are drawn behind the labels, so the lift slides by drawing alone and never re-measures anything; the touch
 * target is the full 44 dp row. Reduced motion: the lift moves at once. Right to left, Personal is on the right and
 * the lift follows. Each side reads out as "Wallet: Personal" / "Wallet: Groups", a tab, selected or not.
 */
@Composable
fun WalletModeControl(groups: Boolean, onMode: (WalletMode) -> Unit, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val still = CircasoTheme.reduceMotion
    // Critically damped: it glides to the side and stops, never past it; a second tap mid-slide turns it round.
    val at by animateFloatAsState(
        if (groups) 1f else 0f,
        if (still) snap() else spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
        label = "segment",
    )
    val density = LocalDensity.current
    val scale = density.fontScale.coerceIn(1f, 1.6f)
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val track = c.fill
    val lift = c.fillSelected
    val shadow = if (c.isDark || c.isHighContrast) null else c.ink.copy(alpha = 0.08f)
    val edge = if (c.isHighContrast) c.ink else null
    Row(
        modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).heightIn(min = TouchHeight * scale)
            .semantics { selectableGroup() }
            .drawBehind {
                val trackH = (TrackHeight * scale).toPx().coerceAtMost(size.height)
                val top = (size.height - trackH) / 2f
                val radius = androidx.compose.ui.geometry.CornerRadius(trackH / 2f)
                drawRoundRect(track, androidx.compose.ui.geometry.Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, trackH), radius)
                val inset = TrackInset.toPx()
                val half = size.width / 2f
                // Physical x: in RTL Personal (0) is the right half.
                val x = (if (rtl) 1f - at else at) * half + inset
                val liftSize = androidx.compose.ui.geometry.Size(half - inset * 2, trackH - inset * 2)
                val liftRadius = androidx.compose.ui.geometry.CornerRadius(liftSize.height / 2f)
                val liftAt = androidx.compose.ui.geometry.Offset(x, top + inset)
                shadow?.let { drawRoundRect(it, liftAt + androidx.compose.ui.geometry.Offset(0f, 1.dp.toPx()), liftSize, liftRadius) }
                drawRoundRect(lift, liftAt, liftSize, liftRadius)
                edge?.let { drawRoundRect(it, liftAt, liftSize, liftRadius, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())) }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(WalletMode.Personal to "Personal", WalletMode.Groups to "Groups").forEach { (mode, label) ->
            val on = (mode == WalletMode.Groups) == groups
            Box(
                Modifier.weight(1f).widthIn(min = SegmentMinWidth).heightIn(min = TouchHeight * scale)
                    // The lift sliding across is the feedback: no ripple or press scale over the 44 dp target.
                    .selectable(
                        selected = on, role = Role.Tab, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null, onClick = { if (!on) onMode(mode) },
                    )
                    .semantics(mergeDescendants = true) { contentDescription = "Wallet: $label" },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    label, Modifier.padding(horizontal = SegmentPadding),
                    style = t.footnote.copy(color = if (on) c.ink else c.inkSecondary, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                    maxLines = 1,
                )
            }
        }
    }
}

/** What a group wallet lets this person do, in a few words (the menu's and the full list's second line). */
internal fun walletLine(w: NamedWallet, locked: Boolean): String = when {
    w.personal -> "Your own money"
    locked -> "Locked · its signer left"
    w.status == WalletStatus.SettingUp -> "Being set up"
    w.canSign -> "You send, after approval"
    else -> "View only · payouts need approval"
}

/** A wallet's last balance as the menu shows it, or null when the phone hasn't read it. */
internal fun balanceLine(balances: Map<String?, Wallet?>, walletId: String?, hidden: Boolean): String? =
    balances[walletId]?.let { b -> b.usdc?.let { if (hidden) "••••" else amountText(it.amountMinor, b.fiat) } }

/**
 * Under Groups: the group on screen as the page's title (its picture, its name, who can move its money) and the
 * platform's overflow button, whose menu switches group and opens the group's chat or info. Built from what the
 * phone holds, so it draws at once; the line under the name sharpens once the group's details are read.
 */
@Composable
fun GroupWalletHeader(
    state: WalletUiState,
    onSwitching: (Boolean) -> Unit,
    onSelectWallet: (NamedWallet) -> Unit,
    onAllGroups: (Boolean) -> Unit,
    menu: GroupMenuActions?,
    modifier: Modifier = Modifier,
    /** Inside the page's list, which already insets its rows; else it insets itself like the page. */
    inList: Boolean = false,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val w = state.selected
    val g = state.group
    val locked = w.walletId != null && w.walletId in state.lockedWallets || g?.locked == true
    // The group's own caption once it is read ("Only Otieno can send, after approval"), else the short line.
    val line = g?.takeIf { it.money != null }?.caption?.substringAfter(" · ", "")?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotEmpty() }
        ?: walletLine(w, locked)
    Row(
        modifier.fillMaxWidth().padding(start = if (inList) CircasoSpacing.s1 else CircasoSpacing.s4, end = if (inList) 0.dp else CircasoSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Row(
            Modifier.weight(1f).semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        ) {
            WalletAvatar(w.name, w.avatarUrl, group = true, size = CircasoSizes.avatarMd)
            Column(Modifier.weight(1f)) {
                BasicText(w.name, style = t.headline.copy(color = c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText(line, style = t.footnote.copy(color = if (locked) c.warning else c.inkSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        val groups = state.groupWallets
        val listed = menuGroups(groups, w.walletId)
        val wallets = listed.map { gw ->
            val isLocked = gw.walletId in state.lockedWallets
            val balance = balanceLine(state.balances, gw.walletId, state.hidden)
            val sub = listOfNotNull(balance, walletLine(gw, isLocked)).joinToString(" · ")
            NativeMenuItem(
                id = "wallet:${gw.walletId}", title = gw.name, subtitle = sub, checked = gw.walletId == w.walletId,
                avatarName = gw.name, avatarUrl = gw.avatarUrl,
                accessibilityLabel = listOfNotNull(gw.name, walletLine(gw, isLocked), balance?.takeIf { !state.hidden }).joinToString(", "),
            )
        } + (if (groups.size > listed.size) listOf(NativeMenuItem("all", "All Group Wallets…", icon = NativeMenuIcon.List)) else emptyList())
        val chat = w.chatId
        val actions = if (menu != null && chat != null) listOf(
            NativeMenuItem("chat", "Open Group Chat", icon = NativeMenuIcon.Chat),
            NativeMenuItem("info", "Group Info", icon = NativeMenuIcon.Info),
        ) else emptyList()
        NativeMoreMenu(
            title = "Group Wallets",
            sections = listOf(wallets, actions).filter { it.isNotEmpty() },
            expanded = state.switching,
            onExpandedChange = onSwitching,
            onSelect = { id ->
                onSwitching(false)
                when {
                    id.startsWith("wallet:") -> groups.firstOrNull { "wallet:${it.walletId}" == id }?.let(onSelectWallet)
                    id == "all" -> onAllGroups(true)
                    id == "chat" -> chat?.let { menu?.onOpenChat?.invoke(it) }
                    id == "info" -> chat?.let { menu?.onGroupInfo?.invoke(it) }
                }
            },
            contentDescription = "More group wallet options",
        )
    }
}

/**
 * Every group wallet, for someone in more groups than the menu lists: a search field, then each group's picture,
 * name, what they can do with it and its last balance; the one on screen is ticked. Offline it lists what the phone
 * holds, as of when it was read.
 */
@Composable
fun ColumnScope.AllGroupWalletsContent(state: WalletUiState, onPick: (NamedWallet) -> Unit, onClose: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    var query by remember { mutableStateOf("") }
    val shown = filterGroups(state.groupWallets, query)
    SheetHead("Group Wallets", onClose)
    CircasoTextField(
        query, { query = it }, label = "Search", placeholder = "Search groups",
        leading = { CircasoIconImage(CircasoIcon.Search, c.inkSecondary, Modifier.size(18.dp)) },
    )
    if (shown.isEmpty()) {
        BasicText(
            "No group called “${query.trim()}”.", Modifier.fillMaxWidth().padding(vertical = CircasoSpacing.s6),
            style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
        )
    } else {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(CircasoShapes.lg).background(c.surfaceRaised).border(1.dp, c.separator, CircasoShapes.lg)
                .semantics { selectableGroup() },
        ) {
            itemsIndexed(shown, key = { _, w -> w.walletId ?: "personal" }) { i, w ->
                if (i > 0) RowDivider(Modifier.padding(start = CircasoSpacing.s4 + CircasoSizes.avatarSm + CircasoSpacing.s3))
                WalletChoiceRow(w, on = w.walletId == state.selectedWalletId, locked = w.walletId in state.lockedWallets, balance = balanceLine(state.balances, w.walletId, state.hidden), hidden = state.hidden) { onPick(w) }
            }
        }
    }
    if (!state.online) BasicText("Offline · balances as last read on this phone.", Modifier.fillMaxWidth(), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
}

/** One wallet in the full list: its picture, name, what the person can do with it, its last balance, and a tick. */
@Composable
private fun WalletChoiceRow(w: NamedWallet, on: Boolean, locked: Boolean, balance: String?, hidden: Boolean, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val sub = walletLine(w, locked)
    // Dynamic Type: at the largest sizes the balance moves under the name so the name keeps its room.
    val large = LocalDensity.current.fontScale > 1.3f
    Row(
        Modifier.fillMaxWidth().heightIn(min = CircasoSizes.controlMd)
            .pressable(role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                selected = on
                contentDescription = listOfNotNull(w.name, sub, balance?.takeIf { !hidden }).joinToString(", ")
            }
            .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        WalletGlyph(w, CircasoSizes.avatarSm)
        Column(Modifier.weight(1f)) {
            BasicText(w.name, style = t.body.copy(color = c.ink, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText(sub, style = t.footnote.copy(color = if (locked) c.warning else c.inkSecondary), maxLines = 2)
            if (large && balance != null) BasicText(balance, style = t.footnote.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"), maxLines = 1)
        }
        if (!large && balance != null) BasicText(balance, style = t.footnote.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"), maxLines = 1)
        // The tick keeps its place on every row, so the balances line up whichever wallet is chosen.
        if (on) CircasoIconImage(CircasoIcon.Check, c.action, Modifier.size(18.dp), strokeWidth = 2.4f)
        else Spacer(Modifier.width(18.dp))
    }
}
