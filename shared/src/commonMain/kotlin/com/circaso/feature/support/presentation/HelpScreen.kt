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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
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
    HelpContent(state, onBack, viewModel::onTopic, viewModel::onChat, onAsha, if (onCallChat != null) viewModel::onCall else null, viewModel::onErrorShown)
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
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    LaunchedEffect(state.error) { state.error?.let { toast.show(it); onErrorShown() } }
    val now = nowMillis()
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = "Help", onBack = onBack) },
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
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
            item {
                Row(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.surface).padding(CircasoSpacing.s4), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.Top) {
                    CircasoIconImage(CircasoIcon.Warning, c.warning, Modifier.size(20.dp))
                    BasicText(Support.NeverAsk, style = t.footnote.copy(color = c.ink))
                }
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
