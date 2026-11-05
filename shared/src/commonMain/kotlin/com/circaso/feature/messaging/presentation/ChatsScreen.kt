package com.circaso.feature.messaging.presentation

import com.circaso.designsystem.motion.pressable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Row
import circaso.shared.generated.resources.Res
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import com.circaso.designsystem.theme.CircasoSizes
import org.jetbrains.compose.resources.painterResource
import circaso.shared.generated.resources.circaso_mark_black
import circaso.shared.generated.resources.circaso_mark_white
import androidx.compose.ui.semantics.contentDescription
import com.circaso.designsystem.components.CircasoIconImage
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.chatListTime
import com.circaso.core.sync.ConnectionBanner
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.components.AshaMark
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.CountBadge
import com.circaso.designsystem.components.SearchPill
import com.circaso.designsystem.components.TextBadge
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.Message

/** Design C1: the chat list. The title bar and tab bar come from the home scaffold; this is the tab's content. */
@Composable
fun ChatsTab(
    viewModel: ChatsViewModel, bottomPadding: PaddingValues, onAsha: () -> Unit, onVoice: () -> Unit = onAsha, onNewChat: () -> Unit = {}, selectedChatId: String? = null,
    /** Spaces core (SI2, SH2d): invite cards, private-chat labels and the Spaces chip; null while spaces are off. */
    spaces: com.circaso.feature.spaces.presentation.discover.ChatsSpaces? = null,
    onOpen: (chatId: String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(spaces != null) { spaces?.onShown?.invoke() }
    // The Spaces chip went away (spaces turned off, or nothing to gather): back to All, so the list never looks empty.
    androidx.compose.runtime.LaunchedEffect(spaces?.state?.showFilter) {
        if (state.filter == ChatFilter.Spaces && spaces?.state?.showFilter != true) viewModel.onFilter(ChatFilter.All)
    }
    ChatsContent(state, bottomPadding, viewModel::onQuery, onAsha, onOpen, onNewChat = onNewChat, onFilter = viewModel::onFilter, selectedChatId = selectedChatId, spaces = spaces)
}

/** The Chats tab's sheet (A10), for the home scaffold's overlay so it sits above the tab bar. */
@Composable
fun androidx.compose.foundation.layout.BoxScope.ChatsOverlay(viewModel: ChatsViewModel, onAsha: () -> Unit, onVoice: () -> Unit = onAsha) {
    val state by viewModel.state.collectAsState()
    MeetAshaSheet(visible = state.meetAsha && state.loaded, firstName = state.firstName, onTry = onAsha, onVoice = onVoice, onDismiss = viewModel::onMeetAshaDone)
}

/**
 * New chat: a floating button above the tab bar, where the thumb is (Pasaka, 2026-10-30; it replaces C1's two title
 * bar buttons). Asha keeps her row at the top of the list.
 */
@Composable
private fun NewChatButton(onClick: () -> Unit, modifier: Modifier) {
    val c = CircasoTheme.colors
    Box(
        modifier.size(56.dp).shadow(8.dp, com.circaso.designsystem.theme.CircasoShapes.circle)
            .clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(c.action)
            .pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { contentDescription = "New chat" },
        contentAlignment = Alignment.Center,
    ) { CircasoIconImage(CircasoIcon.Plus, c.onAction, Modifier.size(24.dp)) }
}

@Composable
fun ChatsBanner(state: ChatsUiState) {
    ConnectionBanner(showing = "Showing chats saved on this phone", waitingCount = state.waitingToSend)
}

@Composable
fun ChatsContent(
    state: ChatsUiState, bottomPadding: PaddingValues, onQuery: (String) -> Unit, onAsha: () -> Unit, onOpen: (String) -> Unit, onNewChat: (() -> Unit)? = null,
    onFilter: (ChatFilter) -> Unit = {}, selectedChatId: String? = null,
    spaces: com.circaso.feature.spaces.presentation.discover.ChatsSpaces? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val now = nowMillis()
    Box(Modifier.fillMaxSize()) {
    // Room under the last chat for the floating button.
    val fabRoom = if (onNewChat != null) 56.dp + CircasoSpacing.s4 else 0.dp
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + CircasoSpacing.s4 + fabRoom)) {
        item { ChatsBrand() }
        item {
            SearchPill(state.query, onQuery, "Search", Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2, bottom = 6.dp))
        }
        item {
            // All · Unread · Groups (as WhatsApp): one is always picked; Unread shows how many chats are waiting.
            val unread = state.chats.count { it.unread > 0 }
            Row(Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                ChatFilter.entries.filter { it != ChatFilter.Spaces || spaces?.state?.showFilter == true }.forEach { f ->
                    com.circaso.designsystem.components.SuggestionChip(
                        f.label, { onFilter(f) },
                        modifier = Modifier.semantics { if (f == ChatFilter.Unread && unread > 0) contentDescription = "Unread, $unread chats" },
                        selected = f == state.filter,
                        badge = if (f == ChatFilter.Unread) unread else 0,
                    )
                }
            }
        }
        // SI2: space invites at the top of the inbox, newest first; SI3b folds a declined one with Undo.
        if (spaces != null && state.query.isBlank() && state.filter == ChatFilter.All) {
            items(spaces.state.shownInvites, key = { "invite-" + it.inviteId }) { invite ->
                com.circaso.feature.spaces.presentation.discover.SpaceInviteCard(
                    invite, declining = invite.inviteId in spaces.state.declining, accepting = invite.inviteId in spaces.state.accepting,
                    online = spaces.state.online, now = spaces.now, spaces = spaces,
                )
            }
        }
        if (state.query.isBlank() && state.filter == ChatFilter.All) {
            // The design's first row (C1): Asha, always there, above the chats.
            item {
                ListRow("Asha", sub = "Ask me anything about your money", leading = { AshaMark() }, onClick = onAsha)
            }
        }
        val spaceRows = if (spaces != null && state.filter == ChatFilter.Spaces) spaces.state.mine.filter { it.me.isMember && (state.query.isBlank() || it.space.name.contains(state.query.trim(), true)) } else emptyList()
        if (state.loaded && state.shown.isEmpty() && spaceRows.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    BasicText(
                        when { state.query.isNotBlank() -> "No chats match"; state.filter == ChatFilter.Unread -> "You're all caught up"; state.filter == ChatFilter.Groups -> "No groups yet"; state.filter == ChatFilter.Spaces -> "No private chats from spaces yet"; else -> "No chats yet" },
                        style = t.title3.copy(color = c.ink, textAlign = TextAlign.Center),
                    )
                    BasicText(
                        when {
                            state.query.isNotBlank() -> "Try another name."
                            state.filter == ChatFilter.Unread -> "No unread messages."
                            state.filter == ChatFilter.Groups -> "Start a group with the + button."
                            state.filter == ChatFilter.Spaces -> "When someone messages you privately from a space, it shows here."
                            else -> "Start one with the + button. Everything you send is end-to-end encrypted."
                        },
                        style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                    )
                }
            }
        }
        items(state.shown, key = { it.chat.chatId }) { summary ->
            val label = summary.chat.viaSpaceId?.let { id -> spaces?.state?.spaceNames?.get(id) ?: "a space" }?.takeIf { spaces != null }
            ChatRow(summary, now, state.fiat, selected = summary.chat.chatId == selectedChatId, spaceLabel = label) { onOpen(summary.chat.chatId) }
        }
        // SH2d: under Spaces, my spaces' own chats sit in the list like groups.
        items(spaceRows, key = { "space-" + it.space.spaceId }) { m ->
            val last = m.lastPost?.let { p -> "${p.authorName}: " + (if (p.removed) "Removed post" else p.text ?: "Post") } ?: m.space.about.orEmpty()
            ListRow(
                title = m.space.name, sub = last,
                modifier = Modifier.padding(horizontal = CircasoSpacing.s2),
                leading = { com.circaso.feature.spaces.presentation.SpaceTile(m.space, CircasoSizes.avatarMd) },
                trailing = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (m.activityAt > 0) BasicText(chatListTime(m.activityAt, now), style = t.footnote.copy(color = c.inkSecondary))
                        if (m.unreadCount > 0) CountBadge(m.unreadCount)
                    }
                },
                onClick = { spaces?.onOpenSpace?.invoke(m) },
            )
        }
    }
    if (onNewChat != null) {
        NewChatButton(onNewChat, Modifier.align(Alignment.BottomEnd).padding(end = CircasoSpacing.s4, bottom = bottomPadding.calculateBottomPadding() + CircasoSpacing.s4))
    }
    }
}

@Composable
/** [selected]: the chat open in the detail pane beside the list (wide windows), shown as iPad Messages does. */
private fun ChatRow(summary: ChatSummary, now: Long, fiat: com.circaso.feature.wallet.domain.FiatValue? = null, selected: Boolean = false, spaceLabel: String? = null, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val latest = summary.latest
    val preview = when (latest) {
        null -> "Say hello"
        is Message.Text -> when {
            latest.state == DeliveryState.Waiting || latest.state == DeliveryState.Sending -> "Waiting to send: ${latest.text}"
            latest.state == DeliveryState.Failed -> "Not sent: ${latest.text}"
            latest.outgoing -> "You: ${latest.text}"
            summary.chat.kind == com.circaso.feature.messaging.domain.ChatKind.Group -> "${latest.senderName}: ${latest.text}"
            else -> latest.text
        }
        is Message.Call -> callLabel(latest)
        is Message.Media -> when {
            latest.state == DeliveryState.Waiting || latest.state == DeliveryState.Sending -> "Waiting to send: "
            latest.state == DeliveryState.Failed -> "Not sent: "
            latest.outgoing -> "You: "
            summary.chat.kind == com.circaso.feature.messaging.domain.ChatKind.Group -> "${latest.senderName}: "
            else -> ""
        } + mediaLabel(latest)
        // In the person's currency, as on the card itself; a coin with no rate shows as itself.
        is Message.Payment -> (if (latest.outgoing) "You sent" else "${latest.senderName} sent") + " " + run {
            val units = com.circaso.feature.wallet.domain.MinorUnits
            fiat?.takeIf { units.isDollar(latest.asset) }?.let { f -> com.circaso.feature.wallet.domain.usdcToFiat(latest.amountMinor, f.rate)?.let { com.circaso.feature.wallet.domain.formatFiat(f.currency, it) } }
                ?: units.coinText(latest.amountMinor, latest.asset)
        }
        is Message.Deleted -> "This message was deleted"
        is Message.Unreadable -> "A message this phone can't show"
        is Message.System -> latest.text
    }
    // The design's end column (`cx-row-end`): the time above the unread count, both right-aligned.
    ListRow(
        title = summary.chat.title,
        // Always inset and rounded so selecting doesn't move the row; only the fill changes, softly.
        modifier = Modifier.padding(horizontal = CircasoSpacing.s2).clip(com.circaso.designsystem.theme.CircasoShapes.md)
            .background(androidx.compose.animation.animateColorAsState(if (selected) c.fill else androidx.compose.ui.graphics.Color.Transparent, label = "selected").value)
            .semantics { this.selected = selected },
        sub = preview,
        leading = { Avatar(summary.chat.title, imageUrl = summary.chat.avatarUrl) },
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                latest?.let { BasicText(chatListTime(it.orderAt, now), style = t.footnote.copy(color = c.inkSecondary)) }
                when {
                    latest is Message.Text && (latest.state == DeliveryState.Waiting || latest.state == DeliveryState.Sending) -> TextBadge("Waiting")
                    summary.unread > 0 -> CountBadge(summary.unread)
                }
            }
        },
        onClick = onClick,
        titleEnd = spaceLabel?.let { name -> { com.circaso.feature.spaces.presentation.discover.PrivateSpaceLabel(name) } },
    )
}

/**
 * The Chats tab's masthead: the Circaso mark (the wallet cards' mark, same size) and the name in ink. The design kit
 * has no wordmark drawable yet, so the name is set in type as a stand-in for it.
 */
@Composable
private fun ChatsBrand() {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4).padding(top = CircasoSpacing.s3, bottom = CircasoSpacing.s1)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        Image(
            painterResource(if (c.isDark) Res.drawable.circaso_mark_white else Res.drawable.circaso_mark_black),
            contentDescription = null, modifier = Modifier.size(CircasoSizes.brandMarkSm),
        )
        BasicText("Circaso", style = t.title1.copy(color = c.ink, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}
