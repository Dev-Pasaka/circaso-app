package com.circaso.feature.assistant.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.sheetGroupEdge
import com.circaso.designsystem.components.AshaMark
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.assistant.domain.AshaConversation
import com.circaso.feature.assistant.domain.AshaUsage
import com.circaso.feature.assistant.domain.UsageMeter

/** What Asha's menu sheet can do; the screen wires each to the ViewModel or the navigation. */
data class AshaMenuActions(
    val onClose: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onPage: (AshaSheet) -> Unit = {},
    val onNewChat: () -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onRequestDelete: (String) -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onRetryUsage: () -> Unit = {},
    /** Asha's voice picker (A12/A13), pushed inside Asha; null hides the row. */
    val onVoiceSettings: (() -> Unit)? = null,
    val onDeleteAll: () -> Unit = {},
)

/**
 * The content of Asha's menu sheet (opened from the header's ⋮ / ellipsis): three pages in one sheet.
 * - Menu: New chat, the latest chats, usage at a glance, settings.
 * - Recents: every conversation; tap to open, long-press (or the screen reader's Delete action) to delete.
 * - Usage: the plan, this period's messages and voice time against the allowance, when it resets.
 * Back inside a sub-page returns to the menu; the scrim, a drag down or Close shuts the sheet.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ColumnScope.AshaMenuSheetContent(state: AshaUiState, page: AshaSheet, actions: AshaMenuActions) {
    BackHandler(enabled = page != AshaSheet.Menu, onBack = actions.onBack)
    val still = CircasoTheme.reduceMotion
    AnimatedContent(
        page,
        transitionSpec = {
            if (still) fadeIn(tween(CircasoMotion.QuickMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs))
            else {
                val forward = targetState != AshaSheet.Menu
                (slideInHorizontally(tween(CircasoMotion.BaseMs)) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(tween(CircasoMotion.BaseMs))) togetherWith
                    (slideOutHorizontally(tween(CircasoMotion.BaseMs)) { w -> if (forward) -w / 4 else w / 4 } + fadeOut(tween(CircasoMotion.QuickMs)))
            }
        },
        label = "asha-menu",
    ) { shown ->
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
            when (shown) {
                AshaSheet.Menu -> MenuPage(state, actions)
                AshaSheet.Recents -> RecentsPage(state, actions)
                AshaSheet.Usage -> UsagePage(state, actions)
            }
        }
    }
}

/** Rows inside a sheet sit on a group a step off the sheet's own surface (grey on white, lighter on dark). */
@Composable
private fun groupColor(): Color = com.circaso.designsystem.chrome.sheetGroupColor()

@Composable
private fun SheetGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(groupColor()).sheetGroupEdge(CircasoShapes.md), content = content)
}

@Composable
private fun GroupDivider(inset: Boolean = true) {
    Box(Modifier.fillMaxWidth().padding(start = if (inset) 52.dp else CircasoSpacing.s4).height(0.5.dp).background(CircasoTheme.colors.separator))
}

/** Small grey label above a group, with an optional action on the right ("See all"). */
@Composable
private fun SheetSection(title: String, action: String? = null, onAction: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s1), verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, Modifier.weight(1f).semantics { heading() }, style = t.footnote.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold))
            if (action != null && onAction != null) {
                BasicText(
                    action,
                    // A full 44 dp target round a small label.
                    Modifier.defaultMinSize(minHeight = CircasoSizes.controlMd).clip(CircasoShapes.xs).pressable(role = Role.Button, onClick = onAction)
                        .padding(horizontal = CircasoSpacing.s2, vertical = CircasoSpacing.s3),
                    style = t.footnote.copy(color = c.ink, fontWeight = FontWeight.SemiBold),
                )
            }
        }
        SheetGroup(content)
    }
}

/** A row in a sheet group: a 28 dp icon tile, the title (and an optional line), and an end slot. */
@Composable
private fun MenuRow(
    title: String,
    icon: CircasoIcon,
    onClick: (() -> Unit)?,
    sub: String? = null,
    destructive: Boolean = false,
    emphasised: Boolean = false,
    enabled: Boolean = true,
    end: (@Composable () -> Unit)? = { RowChevron() },
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val ink = when { !enabled -> c.inkTertiary; destructive -> c.critical; else -> c.ink }
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = CircasoSpacing.s3, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Box(
            Modifier.size(CircasoSizes.avatarSm).clip(CircasoShapes.sm).background(if (emphasised && enabled) c.action else c.fillStrong),
            contentAlignment = Alignment.Center,
        ) { CircasoIconImage(icon, if (emphasised && enabled) c.onAction else ink, Modifier.size(16.dp)) }
        Column(Modifier.weight(1f)) {
            BasicText(title, style = t.body.copy(color = ink, fontWeight = if (emphasised) FontWeight.SemiBold else null), maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { BasicText(it, style = t.footnote.copy(color = c.inkSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        end?.invoke()
    }
}

/** The sheet's title line: Asha's mark and name on the first page, Back and the page's name on the others. */
@Composable
private fun PageHead(title: String, onBack: (() -> Unit)?, onClose: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        if (onBack != null) CircasoIconButton(CircasoIcon.Back, "Back to the menu", onBack) else AshaMark(size = CircasoSizes.iconButton)
        BasicText(title, Modifier.weight(1f).semantics { heading() }, style = t.title3.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
        CircasoIconButton(CircasoIcon.Close, "Close", onClose)
    }
}

@Composable
private fun ColumnScope.MenuPage(state: AshaUiState, a: AshaMenuActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    PageHead("Asha", onBack = null, onClose = a.onClose)
    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5)) {
        SheetGroup {
            MenuRow(
                "New chat", CircasoIcon.Plus, a.onNewChat,
                sub = if (state.online) "Starts fresh, without this chat's context" else "Needs a connection",
                emphasised = true, enabled = state.online && !state.sending, end = null,
            )
        }
        if (state.conversations.isNotEmpty()) {
            val more = state.conversations.size > MenuRecentCount
            SheetSection("Recent chats", action = if (more) "See All" else null, onAction = { a.onPage(AshaSheet.Recents) }) {
                state.conversations.take(MenuRecentCount).forEachIndexed { i, conv ->
                    if (i > 0) GroupDivider(inset = false)
                    ConversationRow(conv, current = conv.id == state.shownConversation, confirming = conv.id == state.confirmDelete, online = state.online, actions = a)
                }
            }
        }
        SheetSection("Usage & credits") {
            UsageSummaryRow(state.usage) { a.onPage(AshaSheet.Usage) }
        }
        SheetSection("Settings") {
            a.onVoiceSettings?.let { open ->
                MenuRow("Voice", CircasoIcon.Speaker, open, sub = "How Asha sounds when she talks")
                GroupDivider()
            }
            MenuRow(
                "Delete all conversations", CircasoIcon.Trash, a.onDeleteAll, destructive = true,
                enabled = state.conversations.isNotEmpty() || state.messages.isNotEmpty(), end = null,
            )
        }
        BasicText(
            "Asha reads your activity to answer. She can't move money.",
            Modifier.padding(horizontal = CircasoSpacing.s1),
            style = t.caption1.copy(color = c.inkTertiary),
        )
    }
}

@Composable
private fun ColumnScope.RecentsPage(state: AshaUiState, a: AshaMenuActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    PageHead("Recent chats", onBack = a.onBack, onClose = a.onClose, trailing = {
        CircasoIconButton(CircasoIcon.Plus, "New chat", a.onNewChat, enabled = state.online && !state.sending)
    })
    if (state.conversations.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = CircasoSpacing.s8), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            CircasoIconImage(CircasoIcon.Chat, c.inkTertiary, Modifier.size(28.dp))
            BasicText("No chats yet", style = t.headline.copy(color = c.ink))
            BasicText("Your conversations with Asha will show here.", style = t.footnote.copy(color = c.inkSecondary))
        }
        return
    }
    BasicText(
        if (state.online) "Touch and hold a chat to delete it." else "Deleting a chat needs a connection.",
        Modifier.padding(horizontal = CircasoSpacing.s1),
        style = t.footnote.copy(color = c.inkSecondary),
    )
    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        SheetGroup {
            state.conversations.forEachIndexed { i, conv ->
                if (i > 0) GroupDivider(inset = false)
                ConversationRow(conv, current = conv.id == state.shownConversation, confirming = conv.id == state.confirmDelete, online = state.online, actions = a)
            }
        }
    }
}

/**
 * One recent chat: its title and when it was last active; the open one is marked. A long-press turns the row into
 * its own delete confirmation, so nothing is deleted by one gesture and no second sheet stacks on this one.
 */
@Composable
private fun ConversationRow(conv: AshaConversation, current: Boolean, confirming: Boolean, online: Boolean, actions: AshaMenuActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    if (confirming) {
        Row(
            Modifier.fillMaxWidth().background(c.critical.copy(alpha = 0.08f)).padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2)
                .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
        ) {
            BasicText("Delete “${conv.title}”?", Modifier.weight(1f), style = t.subhead.copy(color = c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
            CircasoButton("Cancel", actions.onCancelDelete, style = ButtonStyle.Plain, size = ButtonSize.Small)
            CircasoButton("Delete", actions.onConfirmDelete, style = ButtonStyle.Destructive, size = ButtonSize.Small)
        }
        return
    }
    val time = conv.lastMessageAt?.let { at -> runCatching { kotlin.time.Instant.parse(at).toEpochMilliseconds() }.getOrNull() }?.let { relativeTime(it, nowMillis()) }
    Row(
        Modifier.fillMaxWidth()
            .pressable(role = Role.Button, onLongClick = if (online) ({ actions.onRequestDelete(conv.id) }) else null, onClick = { actions.onOpen(conv.id) })
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(conv.title, time, if (current) "open now" else null).joinToString(", ")
                selected = current
                if (online) customActions = listOf(CustomAccessibilityAction("Delete") { actions.onRequestDelete(conv.id); true })
            }
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = CircasoSpacing.s3, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        // The open chat carries a small ink bar, the way a selected row reads in a list.
        Box(Modifier.width(3.dp).height(20.dp).clip(CircasoShapes.pill).background(if (current) c.ink else Color.Transparent))
        BasicText(conv.title, Modifier.weight(1f), style = t.body.copy(color = c.ink, fontWeight = if (current) FontWeight.SemiBold else null), maxLines = 1, overflow = TextOverflow.Ellipsis)
        time?.let { BasicText(it, style = t.footnote.copy(color = c.inkTertiary), maxLines = 1) }
    }
}

/** The menu's usage line: plan and reset on top, two thin meters under it; opens the Usage page. */
@Composable
private fun UsageSummaryRow(usage: UsageUi, onOpen: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.fillMaxWidth().pressable(role = Role.Button, onClick = onOpen).padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            Box(Modifier.size(CircasoSizes.avatarSm).clip(CircasoShapes.sm).background(c.fillStrong), contentAlignment = Alignment.Center) {
                CircasoIconImage(CircasoIcon.Target, c.ink, Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f)) {
                when (usage) {
                    is UsageUi.Loaded -> {
                        BasicText("${planName(usage.usage.plan)} plan", style = t.body.copy(color = c.ink))
                        resetsLabel(usage.usage.resetsAt, nowMillis())?.let { BasicText(it, style = t.footnote.copy(color = c.inkSecondary)) }
                    }
                    UsageUi.Loading -> BasicText("Checking your usage…", style = t.body.copy(color = c.inkSecondary))
                    UsageUi.Unavailable -> {
                        BasicText("Usage isn't available yet", style = t.body.copy(color = c.ink))
                        BasicText("Asha works as usual meanwhile.", style = t.footnote.copy(color = c.inkSecondary))
                    }
                    UsageUi.Offline -> {
                        BasicText("Usage", style = t.body.copy(color = c.ink))
                        BasicText("Needs a connection", style = t.footnote.copy(color = c.inkSecondary))
                    }
                    is UsageUi.Failed -> {
                        BasicText("Usage", style = t.body.copy(color = c.ink))
                        BasicText("Couldn't load it. Tap to try again.", style = t.footnote.copy(color = c.inkSecondary))
                    }
                }
            }
            if (usage == UsageUi.Loading) Spinner(c.inkTertiary) else RowChevron()
        }
        if (usage is UsageUi.Loaded) {
            Row(Modifier.fillMaxWidth().padding(start = CircasoSizes.avatarSm + CircasoSpacing.s3), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
                MiniMeter("Messages", messagesLine(usage.usage.messages), usage.usage.messages, Modifier.weight(1f))
                MiniMeter("Voice", voiceLine(usage.usage.voiceSeconds), usage.usage.voiceSeconds, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MiniMeter(name: String, line: String, meter: UsageMeter, modifier: Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MeterBar(meter, Modifier.fillMaxWidth().height(4.dp), "$name: $line")
        BasicText(line, style = t.caption1.copy(color = c.inkSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A thin track filled to the share used. Near the end of the allowance it turns to the warning colour; at the end, critical. */
@Composable
private fun MeterBar(meter: UsageMeter, modifier: Modifier, label: String) {
    val c = CircasoTheme.colors
    val share = meter.fraction ?: 0f
    val shown by animateFloatAsState(share, tween(if (CircasoTheme.reduceMotion) 0 else CircasoMotion.BaseMs), label = "meter")
    val fillColor = when { share >= 1f -> c.critical; share >= 0.85f -> c.warning; else -> c.ink }
    Box(
        modifier.clip(CircasoShapes.pill).background(c.fillStrong).semantics {
            contentDescription = label
            if (meter.fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(share, 0f..1f)
        },
    ) {
        if (meter.limit != null) Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(CircasoShapes.pill).background(fillColor))
    }
}

@Composable
private fun ColumnScope.UsagePage(state: AshaUiState, a: AshaMenuActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    PageHead("Usage & credits", onBack = a.onBack, onClose = a.onClose)
    when (val u = state.usage) {
        UsageUi.Loading -> Row(Modifier.fillMaxWidth().padding(vertical = CircasoSpacing.s8), horizontalArrangement = Arrangement.Center) { Spinner(c.inkSecondary) }
        UsageUi.Unavailable -> UsageNote(CircasoIcon.Info, "Usage isn't available yet", "Asha will show your plan and allowance here soon. She works as usual meanwhile.")
        UsageUi.Offline -> UsageNote(CircasoIcon.Offline, "Usage needs a connection", "Your plan and allowance show here once you're back online.")
        is UsageUi.Failed -> {
            UsageNote(CircasoIcon.Warning, "Couldn't load your usage", u.message)
            CircasoButton("Try Again", a.onRetryUsage, style = ButtonStyle.Secondary, size = ButtonSize.Medium, block = true, enabled = state.online)
        }
        // Scrolls, so large text or a phone on its side never cuts the meters off.
        is UsageUi.Loaded -> Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) { UsageDetail(u.usage) }
    }
}

@Composable
private fun UsageNote(icon: CircasoIcon, title: String, line: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.fillMaxWidth().clip(CircasoShapes.md).background(groupColor()).padding(CircasoSpacing.s5).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        CircasoIconImage(icon, c.inkSecondary, Modifier.size(24.dp).clearAndSetSemantics {})
        BasicText(title, style = t.headline.copy(color = c.ink))
        BasicText(line, style = t.footnote.copy(color = c.inkSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
    }
}

@Composable
private fun UsageDetail(u: AshaUsage) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val now = nowMillis()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
        // The plan and its period, like the top of a statement.
        Column(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(groupColor()).padding(CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("Plan", style = t.footnote.copy(color = c.inkSecondary))
            BasicText(planName(u.plan), style = t.title2.copy(color = c.ink))
            val period = periodLabel(u.periodStart, u.periodEnd)
            val resets = resetsLabel(u.resetsAt, now)
            listOfNotNull(period, resets).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { BasicText(it, style = t.footnote.copy(color = c.inkSecondary)) }
        }
        SheetGroup {
            MeterRow("Messages", messagesLine(u.messages), u.messages.left?.let { "$it left" } ?: "No limit on this plan", u.messages)
            GroupDivider(inset = false)
            MeterRow("Voice", voiceLine(u.voiceSeconds), u.voiceSeconds.left?.let { "${it / 60} min left" } ?: "No limit on this plan", u.voiceSeconds)
        }
        BasicText(
            "Messages count each question you send. Voice counts the minutes you talk with Asha. Both start again when the allowance resets.",
            Modifier.padding(horizontal = CircasoSpacing.s1),
            style = t.caption1.copy(color = c.inkTertiary),
        )
    }
}

@Composable
private fun MeterRow(name: String, line: String, left: String, meter: UsageMeter) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth().padding(CircasoSpacing.s4).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(name, Modifier.weight(1f), style = t.body.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
            BasicText(line, style = t.subhead.copy(color = c.inkSecondary))
        }
        MeterBar(meter, Modifier.fillMaxWidth().height(6.dp), "$name: $line, $left")
        BasicText(left, style = t.caption1.copy(color = if ((meter.fraction ?: 0f) >= 1f) c.critical else c.inkSecondary))
    }
}

/** "1 Nov – 30 Nov" from the period's ISO instants; null without both. */
private fun periodLabel(start: String?, end: String?): String? {
    fun ms(s: String?) = s?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
    val a = ms(start) ?: return null
    val b = ms(end) ?: return null
    // The end is exclusive (the next period's first moment), so the last day shown is the one before it.
    val last = (b - 1).coerceAtLeast(a)
    return com.circaso.core.platform.formatLocalTime(a, com.circaso.core.platform.LocalTimeStyle.ShortDate) + " – " +
        com.circaso.core.platform.formatLocalTime(last, com.circaso.core.platform.LocalTimeStyle.ShortDate)
}
