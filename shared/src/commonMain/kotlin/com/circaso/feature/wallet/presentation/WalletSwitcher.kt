package com.circaso.feature.wallet.presentation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CornerSize
import com.circaso.designsystem.chrome.sheetGroupColor
import com.circaso.designsystem.chrome.sheetGroupEdge
import androidx.compose.ui.layout.onSizeChanged
import com.circaso.designsystem.components.NetworkIcon
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.SearchPill
import com.circaso.designsystem.components.SkeletonBlock
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.chainName
import com.circaso.feature.wallet.domain.formatFiat
import com.circaso.feature.wallet.domain.shortAddress
import androidx.compose.foundation.background
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
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
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
class GroupMenuActions(
    val onOpenChat: (chatId: String) -> Unit,
    val onGroupInfo: (chatId: String) -> Unit,
    /** The owner only (2026-11-06): pay anyone, themselves included, straight from the group wallet. */
    val onPay: ((walletId: String) -> Unit)? = null,
    /** The owner only: send from the group wallet to an outside address (design E5). */
    val onSendToWallet: ((walletId: String) -> Unit)? = null,
    /** One network of the group's wallet: its assets (the tab's See All for that card). Null leaves the rows still. */
    val onNetwork: ((walletId: String, blockchain: String) -> Unit)? = null,
)

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
    w.canSign -> if (com.circaso.core.flags.GroupPayoutApprovals.Enabled) "You send, after approval" else "Only you send from it"
    else -> if (com.circaso.core.flags.GroupPayoutApprovals.Enabled) "View only · payouts need approval" else "You add to it · the owner sends"
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
    val line = currentGroupLine(state)
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
        // One sheet for everything (Pasaka, 2026-11-07): this wallet's actions, then every group wallet, searchable.
        // A drop-down didn't scale past a handful of groups.
        com.circaso.designsystem.components.CircasoIconButton(CircasoIcon.More, "Group wallet options and other group wallets", { onAllGroups(true) })
    }
}


// ---- The Group Wallets sheet (Pasaka, 2026-11-08: "show the group itself, and the group's different wallets"). ----
//
// No frame in the canvas: built on the canvas's sheet and Asha's menu pattern. One grid for the whole sheet: every
// block is inset s3 inside a `md` group on [sheetGroupColor]; every picture sits in a 44 dp column (a group's photo,
// or a network's mark centred in it), so names, network names and other groups' names all start on one line. The
// group on screen comes first (its header, its networks, its actions); the other groups follow as one list.
//
// A group has one wallet (the server keeps one per group), on several networks: each network is its own address and
// card on the tab, which is what people read as "the group's wallets", so they are listed under the group.

/** The text size at which rows stack their balance under the name, and the action tiles go one to a row. */
private const val StackAtFontScale = 1.3f

/** The sheet lists at most this share of the window, so a long list scrolls inside it and never covers the top bar. */
private const val SheetListShare = 0.62f

/**
 * What the sheet needs around its list: the grabber, the title, the search field, the sheet's gaps and bottom inset,
 * and a gap above the sheet so it never reaches the status bar. The list gets what is left above the keyboard.
 */
private val SheetChrome = CircasoSpacing.s14 * 4
private val MinList = CircasoSizes.controlLg * 2

/** The search field shows once the person is in more groups than the old menu listed. */
internal fun showsGroupSearch(groups: List<NamedWallet>) = groups.size > MenuGroupLimit

/**
 * What the sheet lists. Without a search: the group on screen as [current], every other group in [others] (server
 * order). With one: no [current]; [others] are the matches among every group, the one on screen among them, ticked.
 */
internal class GroupSheetList(val current: NamedWallet?, val others: List<NamedWallet>, val searching: Boolean)

internal fun groupSheetList(groups: List<NamedWallet>, selectedId: String?, query: String): GroupSheetList {
    if (query.isNotBlank()) return GroupSheetList(null, filterGroups(groups, query), searching = true)
    val current = groups.firstOrNull { it.walletId == selectedId }
    return GroupSheetList(current, groups.filter { it.walletId != current?.walletId }, searching = false)
}

/** A group wallet's networks: from its last read balance (richest first), else from the list of wallets. */
internal fun networksOf(w: NamedWallet, wallet: Wallet?): List<String> =
    wallet?.chains?.takeIf { it.isNotEmpty() }?.let { byValue(it, wallet.fiat) }?.map { it.blockchain } ?: w.blockchains

/** The wallet's whole worth (every coin, in the person's currency when there is a rate); dots when hidden. */
internal fun walletTotal(wallet: Wallet?, hidden: Boolean): String? {
    wallet ?: return null
    if (hidden) return "••••"
    wallet.fiat?.let { return formatFiat(it.currency, com.circaso.feature.chama.presentation.whole(it.amount)) }
    val usdc = wallet.usdc
    return MinorUnits.toDisplay(usdc?.amountMinor ?: "0", usdc?.decimals ?: 6) + " USDC"
}

/**
 * One network's worth in the sheet ("KES 30,912"), rounded like the total above it so the column reads as one;
 * else its USDC; dots when hidden.
 */
internal fun networkValue(chain: WalletChain, fiat: com.circaso.feature.wallet.domain.FiatValue?, hidden: Boolean): String {
    if (hidden) return "••••"
    val cents = chain.assets.mapNotNull { it.fiatAmount?.let(::centsOf) }.takeIf { it.isNotEmpty() }?.sum()
    if (fiat != null && cents != null) {
        return formatFiat(fiat.currency, com.circaso.feature.chama.presentation.whole("${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"))
    }
    val usdc = chain.usdc?.takeIf { !MinorUnits.isZero(it.amountMinor) }
    val held = chain.assets.count { !MinorUnits.isZero(it.amountMinor) }
    return when {
        usdc != null -> MinorUnits.toDisplay(usdc.amountMinor, usdc.decimals) + " USDC"
        // Coins with no price: say what is there rather than a false zero.
        held > 0 -> if (held == 1) "1 coin" else "$held coins"
        else -> fiat?.let { formatFiat(it.currency, "0") } ?: "0 USDC"
    }
}

/** What the sheet offers for the group on screen. [money] actions need a connection and are off offline. */
internal enum class GroupSheetActionKind { Pay, SendToWallet, Chat, Info }

internal class SheetAction(val kind: GroupSheetActionKind, val label: String, val icon: CircasoIcon, val run: () -> Unit) {
    val money get() = kind == GroupSheetActionKind.Pay || kind == GroupSheetActionKind.SendToWallet
}

/**
 * The group on screen's actions: Pay and Send to a Wallet for its owner (active, unlocked; the owner alone sends,
 * 2026-11-06), then its chat and info for everyone. So an owner gets a 2 × 2 grid and a member one row of two.
 */
internal fun groupSheetActions(state: WalletUiState, menu: GroupMenuActions?): List<SheetAction> {
    menu ?: return emptyList()
    val w = state.selected
    if (w.personal) return emptyList()
    val locked = w.walletId != null && w.walletId in state.lockedWallets || state.group?.locked == true
    val spend = w.walletId?.takeIf { w.canSign && w.status == WalletStatus.Active && !locked }
    val chat = w.chatId
    return listOfNotNull(
        spend?.let { id -> menu.onPay?.let { SheetAction(GroupSheetActionKind.Pay, "Pay", CircasoIcon.ArrowUp) { it(id) } } },
        spend?.let { id -> menu.onSendToWallet?.let { SheetAction(GroupSheetActionKind.SendToWallet, "Send to a Wallet", CircasoIcon.Forward) { it(id) } } },
        chat?.let { SheetAction(GroupSheetActionKind.Chat, "Group Chat", CircasoIcon.Chat) { menu.onOpenChat(it) } },
        chat?.let { SheetAction(GroupSheetActionKind.Info, "Group Info", CircasoIcon.Info) { menu.onGroupInfo(it) } },
    )
}

/** The line under the group's name in the header: the group's own caption once it is read, else the short line. */
internal fun currentGroupLine(state: WalletUiState): String {
    val w = state.selected
    val g = state.group
    val locked = w.walletId != null && w.walletId in state.lockedWallets || g?.locked == true
    return g?.takeIf { it.money != null }?.caption?.substringAfter(" · ", "")?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotEmpty() }
        ?: walletLine(w, locked)
}

/**
 * The Group Wallets sheet, from the overflow button on the Groups side of the Wallet tab:
 * - the group on screen: its picture, name, what the person can do with it and its whole balance; under it each
 *   network its wallet is on with that network's worth and address (a tap opens the network's assets); then its
 *   actions as equal tiles, two to a row;
 * - Other Groups: every other group wallet as a row with its picture, name, role, balance and the marks of its
 *   networks; a tap switches the tab to it;
 * - a search over every group once there are more than the old menu listed.
 * Offline it lists what the phone holds, as of when it was read, and the money tiles are off.
 */
@Composable
fun ColumnScope.AllGroupWalletsContent(
    state: WalletUiState,
    onPick: (NamedWallet) -> Unit,
    onClose: () -> Unit,
    menu: GroupMenuActions? = null,
    /** Previews only: the sheet opened with this already typed. */
    initialQuery: String = "",
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val density = LocalDensity.current
    val stacked = density.fontScale > StackAtFontScale
    val still = CircasoTheme.reduceMotion
    var query by remember { mutableStateOf(initialQuery) }
    val groups = state.groupWallets
    val list = remember(groups, state.selectedWalletId, query) { groupSheetList(groups, state.selectedWalletId, query) }
    val actions = if (list.current != null) groupSheetActions(state, menu) else emptyList()
    // The list takes what is left of the window above the keyboard (or the navigation bar), after the status bar and
    // the sheet's own chrome, and never more than its share: so the title and search always stay on screen, with the
    // keyboard up and on a phone on its side.
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height
    val top = WindowInsets.statusBars.getTop(density)
    val bottom = maxOf(
        WindowInsets.ime.getBottom(density),
        WindowInsets.navigationBars.getBottom(density),
    )
    val maxList = with(density) {
        val room = window - top - bottom - SheetChrome.roundToPx()
        minOf(room, (window * SheetListShare).toInt()).coerceAtLeast(MinList.roundToPx()).toDp()
    }
    // While searching, the list keeps the height it had, so the sheet's top edge doesn't jump with every letter.
    var restingHeight by remember { mutableStateOf(0) }
    SheetHead("Group Wallets", onClose)
    if (showsGroupSearch(groups)) SearchPill(query, { query = it }, "Search groups")
    if (!state.online) {
        val shown = listOfNotNull(list.current) + list.others
        BasicText(offlineNote(shown.map { state.balances[it.walletId] ?: if (it.walletId == state.selectedWalletId) state.group?.wallet else null }), Modifier.fillMaxWidth(), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
    // Items fade and slide into their new places as a search narrows the list; leaving is quicker than arriving.
    fun Modifier.moves(scope: androidx.compose.foundation.lazy.LazyItemScope) = with(scope) {
        animateItem(
            fadeInSpec = tween(CircasoMotion.QuickMs),
            placementSpec = if (still) null else spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold),
            fadeOutSpec = tween(CircasoMotion.QuickMs * 2 / 3),
        )
    }
    val minList = if (list.searching) with(density) { restingHeight.toDp() }.coerceAtMost(maxList) else 0.dp
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(min = minList, max = maxList)
            .onSizeChanged { if (query.isEmpty()) restingHeight = it.height }
            .then(if (list.searching) Modifier.semantics { selectableGroup() } else Modifier),
    ) {
        list.current?.let { current ->
            item(key = "current", contentType = "current") { CurrentGroupCard(state, current, menu, onClose, stacked, Modifier.moves(this)) }
            if (actions.isNotEmpty()) item(key = "actions", contentType = "actions") {
                ActionTiles(actions, state.online, stacked, onClose, Modifier.moves(this).padding(top = CircasoSpacing.s2))
            }
        }
        if (list.others.isNotEmpty() && !list.searching) item(key = "others", contentType = "label") {
            SheetLabel("Other Groups", Modifier.moves(this).padding(top = if (list.current != null) CircasoSpacing.s6 else 0.dp, bottom = CircasoSpacing.s2))
        }
        itemsIndexed(list.others, key = { _, w -> w.walletId ?: "personal" }, contentType = { _, _ -> "group" }) { i, w ->
            OtherGroupRow(
                w, first = i == 0, last = i == list.others.lastIndex, searching = list.searching, ticked = list.searching && w.walletId == state.selectedWalletId,
                locked = w.walletId in state.lockedWallets, wallet = state.balances[w.walletId], hidden = state.hidden, stacked = stacked,
                modifier = Modifier.moves(this),
            ) { onPick(w) }
        }
        if (list.searching && list.others.isEmpty()) item(key = "none") {
            BasicText(
                "No group called “${query.trim()}”.", Modifier.moves(this).fillMaxWidth().padding(vertical = CircasoSpacing.s6),
                style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
            )
        }
    }
}

/**
 * The offline line under the title: the balances shown are as old as the oldest one read ("as of 14:02", or its date
 * when that wasn't today); nothing read at all says when they will show.
 */
internal fun offlineNote(read: List<Wallet?>, now: Long = com.circaso.core.sync.nowMillis()): String {
    val oldest = read.filterNotNull().minOfOrNull { it.fetchedAt } ?: return "Offline · balances show when you’re back online."
    val today = com.circaso.core.platform.localDayIndex(oldest) == com.circaso.core.platform.localDayIndex(now)
    val style = if (today) com.circaso.core.platform.LocalTimeStyle.Clock else com.circaso.core.platform.LocalTimeStyle.ShortDate
    return "Offline · balances as of " + com.circaso.core.platform.formatLocalTime(oldest, style) + "."
}

/** A small grey label over a block of the sheet (`cap`), on the same inset as the rows' content. */
@Composable
private fun SheetLabel(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text, modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s1).semantics { heading() },
        style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary, fontWeight = FontWeight.SemiBold),
    )
}

/** The picture column every row shares, so text always starts on the same line. */
private val PictureColumn = CircasoSizes.avatarMd

/** Where a row's text starts, for the hairlines between rows. */
private val TextInset = CircasoSpacing.s3 + PictureColumn + CircasoSpacing.s3

@Composable
private fun InsetDivider() {
    Box(Modifier.fillMaxWidth().padding(start = TextInset).height(0.5.dp).background(CircasoTheme.colors.separator))
}

/** The group on screen: its header, then each network its wallet is on. */
@Composable
private fun CurrentGroupCard(state: WalletUiState, w: NamedWallet, menu: GroupMenuActions?, onClose: () -> Unit, stacked: Boolean, modifier: Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val locked = w.walletId != null && w.walletId in state.lockedWallets || state.group?.locked == true
    val wallet = state.balances[w.walletId] ?: state.group?.wallet
    val total = walletTotal(wallet, state.hidden)
    // Still being read (online, nothing on the phone yet): placeholders where the numbers will be. A read that failed
    // says so in words, never a placeholder that pulses forever.
    val g = state.group
    val reading = wallet == null && state.online && w.status == WalletStatus.Active && (g == null || g.loading && g.error == null)
    val failed = wallet == null && state.online && !reading && g?.error != null
    val line = currentGroupLine(state)
    val chains = wallet?.chains?.let { byValue(it, wallet.fiat) }.orEmpty()
    Column(modifier.fillMaxWidth().clip(CircasoShapes.md).background(sheetGroupColor()).sheetGroupEdge(CircasoShapes.md)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = CircasoSizes.avatarMd + CircasoSpacing.s3 * 2)
                .semantics(mergeDescendants = true) {
                    heading()
                    contentDescription = listOfNotNull(w.name, line, spokenBalance(total, state.hidden), if (failed) "balance didn't load" else null).joinToString(", ")
                }
                .padding(CircasoSpacing.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        ) {
            WalletGlyph(w, PictureColumn)
            Column(Modifier.weight(1f)) {
                BasicText(w.name, style = t.headline.copy(color = c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText(line, style = t.footnote.copy(color = if (locked) c.warning else c.inkSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (stacked) Total(total, reading, failed, Modifier.padding(top = CircasoSpacing.s1))
            }
            if (!stacked) Total(total, reading, failed)
        }
        val open = menu?.onNetwork
        chains.forEach { chain ->
            InsetDivider()
            NetworkRow(chain, wallet?.fiat, state.hidden, onClick = w.walletId?.let { id -> open?.let { { onClose(); it(id, chain.blockchain) } } })
        }
        if (chains.isEmpty() && reading) repeat(maxOf(1, w.blockchains.size)) {
            InsetDivider()
            NetworkRowPlaceholder()
        }
    }
}

/** The group's whole balance at the header's end (or under its name at large text); a placeholder while it's read. */
@Composable
private fun Total(total: String?, reading: Boolean, failed: Boolean, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    when {
        total != null -> BasicText(total, modifier, style = t.amountSmall.copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
        reading -> SkeletonBlock(modifier.width(SkeletonShort).height(CircasoSpacing.s4), color = c.fillStrong)
        failed -> BasicText("Balance didn’t load", modifier, style = t.footnote.copy(color = c.inkSecondary), maxLines = 2)
    }
}

/** A placeholder's widths: a short word and a longer line. */
private val SkeletonShort = CircasoSizes.controlLg + CircasoSpacing.s6
private val SkeletonLong = SkeletonShort * 1.5f

/** What a screen reader says for a balance: the amount, or that it is hidden (never silence). */
private fun spokenBalance(total: String?, hidden: Boolean): String? = when {
    total == null -> null
    hidden -> "balance hidden"
    else -> "balance $total"
}

/** One network of the group's wallet: its mark, its name and short address, its worth; opens its assets. */
@Composable
private fun NetworkRow(chain: WalletChain, fiat: com.circaso.feature.wallet.domain.FiatValue?, hidden: Boolean, onClick: (() -> Unit)?) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val full = chainName(chain.blockchain)
    val name = full.substringBefore(" (")
    val testnet = full != name
    val value = networkValue(chain, fiat, hidden)
    val sub = (if (testnet) "Testnet · " else "") + shortAddress(chain.address)
    Row(
        Modifier.fillMaxWidth().heightIn(min = CircasoSizes.controlLg)
            .then(if (onClick != null) Modifier.pressable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull("$name network", if (testnet) "testnet" else null, if (hidden) "balance hidden" else value).joinToString(", ")
            }
            .padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Box(Modifier.width(PictureColumn), contentAlignment = Alignment.Center) { NetworkIcon(chain.blockchain, size = CircasoSizes.avatarSm) }
        Column(Modifier.weight(1f)) {
            BasicText(name, style = t.body.copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(sub, style = t.footnote.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicText(value, style = t.subhead.copy(color = c.ink, fontFeatureSettings = "tnum"), maxLines = 1)
        if (onClick != null) RowChevron()
    }
}

@Composable
private fun NetworkRowPlaceholder() {
    Row(
        Modifier.fillMaxWidth().heightIn(min = CircasoSizes.controlLg).padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Box(Modifier.width(PictureColumn), contentAlignment = Alignment.Center) { SkeletonBlock(Modifier.size(CircasoSizes.avatarSm), CircasoShapes.circle, CircasoTheme.colors.fillStrong) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
            SkeletonBlock(Modifier.width(SkeletonShort).height(CircasoSpacing.s3), color = CircasoTheme.colors.fillStrong)
            SkeletonBlock(Modifier.width(SkeletonLong).height(CircasoSpacing.s3), color = CircasoTheme.colors.fillStrong)
        }
    }
}

/**
 * The actions as equal tiles: two to a row, every row the same height, glyphs on one line and labels on one baseline;
 * an odd last one takes the whole row. At the largest text sizes each is a full-width row (glyph, then label), the
 * menu-row look, so four actions don't become four towers.
 */
@Composable
private fun ActionTiles(actions: List<SheetAction>, online: Boolean, stacked: Boolean, onClose: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        actions.chunked(if (stacked) 1 else 2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                row.forEach { a ->
                    ActionTile(a, enabled = online || !a.money, stacked, Modifier.weight(1f).fillMaxHeight()) { onClose(); a.run() }
                }
            }
        }
    }
}

/** The glyph inside an action's disc and the tick on a row (the sheet's one small-icon size). */
private val GlyphSize = CircasoSpacing.s4 + 2.dp

/**
 * A tile: the glyph in a 36 dp disc over its label, top-aligned so a row's labels share a baseline. Pay is filled.
 * Offline a money tile is off and says why under its label, in the same place on every tile of the row. The whole
 * rounded tile is the pressed thing (scale and dim on iOS, a clipped ripple on Android).
 */
@Composable
private fun ActionTile(a: SheetAction, enabled: Boolean, stacked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val primary = a.kind == GroupSheetActionKind.Pay && enabled
    val ink = if (enabled) c.ink else c.inkTertiary
    val surface = modifier.clip(CircasoShapes.md)
        .pressable(enabled = enabled, role = Role.Button, onClick = onClick)
        .background(sheetGroupColor(), CircasoShapes.md)
        .sheetGroupEdge(CircasoShapes.md)
        .semantics(mergeDescendants = true) { contentDescription = if (enabled) a.label else "${a.label}, needs a connection" }
    val disc: @Composable () -> Unit = {
        Box(Modifier.size(CircasoSizes.iconButton).clip(CircasoShapes.circle).background(if (primary) c.action else c.fillStrong), contentAlignment = Alignment.Center) {
            CircasoIconImage(a.icon, if (primary) c.onAction else ink, Modifier.size(GlyphSize))
        }
    }
    val why: @Composable () -> Unit = { if (!enabled) BasicText("Needs a connection", style = t.footnote.copy(color = c.inkSecondary, textAlign = if (stacked) TextAlign.Start else TextAlign.Center)) }
    if (stacked) Row(
        surface.heightIn(min = CircasoSizes.controlLg).padding(CircasoSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        disc()
        Column(Modifier.weight(1f)) {
            BasicText(a.label, style = t.body.copy(color = ink, fontWeight = FontWeight.SemiBold))
            why()
        }
    } else Column(
        surface.padding(CircasoSpacing.s3),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        disc()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(a.label, style = t.subhead.copy(color = ink, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis)
            why()
        }
    }
}

/** Another group: its picture, name and role, its balance and its networks' marks. One list, rounded at its ends. */
@Composable
private fun OtherGroupRow(
    w: NamedWallet,
    first: Boolean,
    last: Boolean,
    searching: Boolean,
    ticked: Boolean,
    locked: Boolean,
    wallet: Wallet?,
    hidden: Boolean,
    stacked: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val sub = walletLine(w, locked)
    val total = walletTotal(wallet, hidden)
    val networks = networksOf(w, wallet)
    val none = CornerSize(0)
    val shape = when {
        first && last -> CircasoShapes.md
        first -> CircasoShapes.md.copy(bottomStart = none, bottomEnd = none)
        last -> CircasoShapes.md.copy(topStart = none, topEnd = none)
        else -> androidx.compose.ui.graphics.RectangleShape
    }
    Column(modifier.fillMaxWidth().clip(shape).background(sheetGroupColor()).sheetGroupEdge(shape)) {
        if (!first) InsetDivider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = CircasoSizes.avatarMd + CircasoSpacing.s3 * 2)
                // Search results are a choice of one (the one on screen ticked); the list without a search switches.
                .pressable(role = if (searching) Role.RadioButton else Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    if (searching) selected = ticked
                    contentDescription = listOfNotNull(
                        w.name, sub, spokenBalance(total, hidden),
                        networks.takeIf { it.isNotEmpty() }?.joinToString(" and ", prefix = "on ") { chainName(it).substringBefore(" (") },
                    ).joinToString(", ")
                }
                .padding(CircasoSpacing.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        ) {
            WalletGlyph(w, PictureColumn)
            Column(Modifier.weight(1f)) {
                BasicText(w.name, style = t.body.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = if (stacked) 2 else 1, overflow = TextOverflow.Ellipsis)
                BasicText(sub, style = t.footnote.copy(color = if (locked) c.warning else c.inkSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (stacked) Row(Modifier.padding(top = CircasoSpacing.s1), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    total?.let { BasicText(it, style = t.subhead.copy(color = c.ink, fontFeatureSettings = "tnum"), maxLines = 1) }
                    NetworkMarks(networks)
                }
            }
            if (!stacked && (total != null || networks.isNotEmpty())) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                total?.let { BasicText(it, style = t.subhead.copy(color = c.ink, fontFeatureSettings = "tnum"), maxLines = 1) }
                NetworkMarks(networks)
            }
            // While searching the tick keeps its place on every row, so the balances line up whichever is ticked.
            if (ticked) CircasoIconImage(CircasoIcon.Check, c.action, Modifier.size(GlyphSize), strokeWidth = 2.4f)
            else if (searching) Spacer(Modifier.width(GlyphSize))
        }
    }
}

/** Up to three networks' marks, overlapping like a stack of coins, then "+2" for the rest. Decorative: the row says them. */
@Composable
private fun NetworkMarks(networks: List<String>) {
    if (networks.isEmpty()) return
    val c = CircasoTheme.colors
    val shown = networks.take(3)
    val mark = CircasoSpacing.s5
    val step = CircasoSpacing.s3
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
        Box(Modifier.width(mark + step * (shown.size - 1)).height(mark)) {
            shown.forEachIndexed { i, b ->
                // A ring in the row's colour keeps each mark whole where it overlaps the one before.
                Box(Modifier.padding(start = step * i).size(mark).clip(CircasoShapes.circle).background(sheetGroupColor()), contentAlignment = Alignment.Center) {
                    NetworkIcon(b, size = mark - CircasoSpacing.s1 / 2)
                }
            }
        }
        if (networks.size > shown.size) BasicText("+${networks.size - shown.size}", style = CircasoTheme.typography.caption1.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"))
    }
}
