package com.circaso.feature.support.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.sheetGroupColor
import com.circaso.designsystem.chrome.sheetGroupEdge
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.sp
import com.circaso.designsystem.theme.CircasoMotion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.chatListTime
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.AshaMark
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SectionHeader
import com.circaso.designsystem.components.SuggestionChip
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.support.Support

@Composable
fun HelpScreen(viewModel: HelpViewModel, onBack: () -> Unit, onOpenChat: (String) -> Unit, onAsha: () -> Unit, onCallChat: ((String) -> Unit)? = null) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.opened.collect(onOpenChat) }
    LaunchedEffect(onCallChat) { if (onCallChat != null) viewModel.call.collect(onCallChat) }
    HelpContent(
        state, onBack, viewModel::onTopic, viewModel::onChat, onAsha, if (onCallChat != null) viewModel::onCall else null, viewModel::onErrorShown,
        onSendLog = if (viewModel.canSendLog) viewModel::onSendLog else null, onSendLogConfirmed = viewModel::onSendLogConfirmed, onLogSheetClosed = viewModel::onLogSheetClosed,
    )
}

/** Design SP01. */
@Composable
fun HelpContent(
    state: HelpUiState,
    onBack: () -> Unit,
    onTopic: (Support.Topic) -> Unit,
    onChat: () -> Unit,
    onAsha: () -> Unit,
    onCall: (() -> Unit)?,
    onErrorShown: () -> Unit = {},
    /** "Send diagnostic log" (2026-11-13); null hides the row. */
    onSendLog: (() -> Unit)? = null,
    onSendLogConfirmed: () -> Unit = {},
    onLogSheetClosed: () -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    LaunchedEffect(state.error) { state.error?.let { toast.show(it, com.circaso.designsystem.chrome.ToastKind.Problem); onErrorShown() } }
    val now = nowMillis()
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = "Help", onBack = onBack) },
        toast = { ToastHost(toast) },
        overlay = {
            // Kept while it closes, so the sheet slides away with what it said rather than going blank.
            val shown = rememberLast(state.logSheet) ?: LogSheet.Confirm
            // Held open while the log is on its way: the answer (a code, or why not) lands in it.
            CircasoSheet(state.logSheet != null, onLogSheetClosed, label = "Send diagnostic log", dismissible = state.logSheet != LogSheet.Sending) {
                LogSheetContent(shown, state.online, onSendLogConfirmed, onLogSheetClosed, onChat = { onLogSheetClosed(); onChat() })
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2, bottom = padding.calculateBottomPadding() + CircasoSpacing.s6), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                    BasicText("How can we help?", style = t.title1.copy(color = c.ink), modifier = Modifier.semantics { heading() })
                    BasicText(Support.Hours, style = t.body.copy(color = c.inkSecondary))
                }
            }
            item {
                Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface)) {
                    ListRow(
                        "Chat with us", sub = if (state.opening) "Opening…" else if (state.online) "Replies in a few minutes" else "You need a connection",
                        leading = { Tile(CircasoIcon.Chat) }, onClick = if (state.online || state.conversation != null) onChat else null,
                    )
                    RowDivider(Modifier.padding(start = 72.dp))
                    // Design SP01's "Call us" is a free internet call to support; it arrives with calls (H1–H4).
                    ListRow("Call us", sub = when { onCall == null -> "Calls are coming soon"; !state.online -> "You need a connection"; else -> "Free over the internet" }, leading = { Tile(CircasoIcon.Phone) }, onClick = if (state.online) onCall else null)
                    RowDivider(Modifier.padding(start = 72.dp))
                    ListRow("Ask Asha", sub = "Answers right away, any time", leading = { AshaMark(size = CircasoSizes.avatarMd) }, onClick = onAsha)
                }
            }
            item {
                SectionHeader("What's it about?")
                Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface)) {
                    Support.Topic.entries.forEachIndexed { i, topic ->
                        ListRow(
                            topic.label, sub = topic.sub,
                            trailing = { com.circaso.designsystem.components.RowChevron() },
                            onClick = if (state.online && !state.opening) ({ onTopic(topic) }) else null,
                        )
                        if (i < Support.Topic.entries.lastIndex) RowDivider(Modifier.padding(start = CircasoSpacing.s4))
                    }
                }
            }
            state.conversation?.let { convo ->
                item {
                    SectionHeader("Your conversations")
                    val latest = convo.latest
                    val preview = when (latest) {
                        is Message.Text -> (if (latest.outgoing) "You: " else "") + latest.text
                        is Message.Payment -> "A payment"
                        else -> "Conversation with ${Support.Name}"
                    }
                    Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface)) {
                        ListRow(Support.Name, sub = preview, meta = latest?.let { chatListTime(it.orderAt, now) }, leading = { Avatar(Support.Name, size = CircasoSizes.avatarMd, imageUrl = convo.chat.avatarUrl) }, onClick = onChat)
                    }
                }
            }
            if (onSendLog != null) {
                item {
                    SectionHeader("Something not working?")
                    Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface)) {
                        ListRow(
                            "Send diagnostic log", sub = if (state.online) "Helps our team see what went wrong" else "You need a connection",
                            leading = { Tile(CircasoIcon.Document) }, onClick = if (state.online) onSendLog else null,
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.surface).padding(CircasoSpacing.s4), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.Top) {
                    CircasoIconImage(CircasoIcon.Warning, c.warning, Modifier.size(20.dp))
                    BasicText(Support.NeverAsk, style = t.footnote.copy(color = c.ink))
                }
            }
        }
    }
}

/** The last non-null [value], so a closing sheet keeps showing what it said. */
@Composable
private fun <T : Any> rememberLast(value: T?): T? {
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    @Suppress("UNCHECKED_CAST")
    return last[0] as T?
}

/**
 * The "Send diagnostic log" sheet. Not in the design canvas (2026-11-13): it follows the confirm pattern (ID-26), then
 * shows the code the way a case number reads in support (SP02's "Case CX-48213"), large and copyable. Its states
 * cross-fade and the sheet eases to the new height; asking and sending are one page, only the button changes.
 */
@Composable
private fun LogSheetContent(sheet: LogSheet, online: Boolean, onSend: () -> Unit, onClose: () -> Unit, onChat: () -> Unit) {
    val reduce = CircasoTheme.reduceMotion
    androidx.compose.animation.AnimatedContent(
        sheet,
        contentKey = { if (it == LogSheet.Sending) LogSheet.Confirm::class else it::class },
        transitionSpec = {
            val enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(if (reduce) CircasoMotion.QuickMs else CircasoMotion.BaseMs))
            val exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(CircasoMotion.QuickMs))
            (enter togetherWith exit).using(androidx.compose.animation.SizeTransform(clip = false))
        },
        label = "log sheet",
    ) { page ->
        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) { LogSheetPage(page, online, onSend, onClose, onChat) }
    }
}

@Composable
private fun LogSheetPage(sheet: LogSheet, online: Boolean, onSend: () -> Unit, onClose: () -> Unit, onChat: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember(sheet) { mutableStateOf(false) }
    val announce = Modifier.semantics { heading(); liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
    when (sheet) {
        LogSheet.Confirm, LogSheet.Sending -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("Send a diagnostic log?", style = t.title2.copy(color = c.ink), modifier = Modifier.semantics { heading() })
                BasicText(
                    "It's a record of what the app did over the last two days, like calls connecting and dropping. It never includes your messages, money details, phone number or PIN. We keep it for 30 days.",
                    style = t.body.copy(color = c.inkSecondary),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                val sending = sheet == LogSheet.Sending
                CircasoButton(if (sending) "Sending…" else "Send Log", onSend, size = ButtonSize.Large, block = true, busy = sending, enabled = online || sending)
                if (!online && !sending) BasicText("You need a connection", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.align(Alignment.CenterHorizontally))
                CircasoButton("Cancel", onClose, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true, enabled = !sending)
            }
        }
        is LogSheet.Sent -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("Log sent", style = t.title2.copy(color = c.ink), modifier = announce)
                BasicText("Give this code to our team when you chat or call, so they can find your log.", style = t.body.copy(color = c.inkSecondary))
            }
            Column(
                Modifier.fillMaxWidth().clip(CircasoShapes.md).background(sheetGroupColor()).sheetGroupEdge(CircasoShapes.md).padding(vertical = CircasoSpacing.s5),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    sheet.code,
                    style = t.title1.copy(color = c.ink, fontFeatureSettings = "tnum", letterSpacing = 1.sp),
                    modifier = Modifier.semantics { contentDescription = "Code " + sheet.code.toList().joinToString(" ") },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                // The code is for support: chatting is the next step, with the code already copied to paste in.
                CircasoButton("Chat with Us", { clipboard.setText(androidx.compose.ui.text.AnnotatedString(sheet.code)); onChat() }, size = ButtonSize.Large, block = true, enabled = online)
                CircasoButton(
                    if (copied) "Copied" else "Copy Code", { clipboard.setText(androidx.compose.ui.text.AnnotatedString(sheet.code)); copied = true },
                    style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, icon = if (copied) CircasoIcon.Check else CircasoIcon.Copy,
                    modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                )
                CircasoButton("Done", onClose, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
            }
        }
        is LogSheet.Failed -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("Couldn't send the log", style = t.title2.copy(color = c.ink), modifier = announce)
                BasicText(sheet.message, style = t.body.copy(color = c.inkSecondary))
                sheet.reference?.let { BasicText(it, style = t.footnote.copy(color = c.inkTertiary)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                CircasoButton("Try Again", onSend, size = ButtonSize.Large, block = true, enabled = online)
                if (!online) BasicText("You need a connection", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.align(Alignment.CenterHorizontally))
                CircasoButton("Cancel", onClose, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
            }
        }
    }
}

@Composable
private fun Tile(icon: CircasoIcon) {
    val c = CircasoTheme.colors
    androidx.compose.foundation.layout.Box(Modifier.size(CircasoSizes.avatarMd).clip(CircasoShapes.md).background(c.fill), contentAlignment = Alignment.Center) {
        CircasoIconImage(icon, c.action, Modifier.size(20.dp))
    }
}
