package com.circaso.feature.messaging.presentation

import com.circaso.designsystem.motion.pressable
import com.circaso.feature.status.presentation.momentRing
import com.circaso.feature.status.presentation.momentsList
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
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

/**
 * Design C1: the chat list. The title bar and tab bar come from the home scaffold; this is the tab's content.
 * [moments]: the Moments chip's list and the rings on avatars (Spaces stopped 2026-11-05; Moments moved here).
 */
@Composable
fun ChatsTab(
    viewModel: ChatsViewModel, bottomPadding: PaddingValues, onAsha: () -> Unit, onVoice: () -> Unit = onAsha, onNewChat: () -> Unit = {}, selectedChatId: String? = null,
    moments: ChatsMoments? = null,
    onOpen: (chatId: String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val momentsVm = moments?.viewModel
    val momentsState = momentsVm?.state?.collectAsState()?.value
    // Always current (Pasaka, 2026-11-03): fetched when the chip is picked and each time the app comes back to the
    // front while it is. New moments posted meanwhile also arrive as status.posted frames; offline, the stored ones show.
    val onMoments = state.filter == ChatFilter.Moments
    androidx.lifecycle.compose.LifecycleResumeEffect(onMoments) {
        if (onMoments) momentsVm?.refresh()
        onPauseOrDispose { }
    }
    ChatsContent(
        state, bottomPadding, viewModel::onQuery, onAsha, onOpen, onNewChat = onNewChat, onFilter = viewModel::onFilter, selectedChatId = selectedChatId,
        moments = if (momentsState != null && moments != null) MomentsSlot(momentsState, moments.actions) else null,
    )
}

/** What Chats needs from Moments: its ViewModel and where its rows go. */
class ChatsMoments(val viewModel: com.circaso.feature.status.presentation.MomentsViewModel, val actions: com.circaso.feature.status.presentation.MomentsActions)

/** The Moments state as [ChatsContent] draws it (previews pass one directly). */
class MomentsSlot(val state: com.circaso.feature.status.presentation.MomentsUiState, val actions: com.circaso.feature.status.presentation.MomentsActions)

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
private fun NewChatButton(onClick: () -> Unit, modifier: Modifier, camera: Boolean = false) {
    val c = CircasoTheme.colors
    // With the Moments chip picked the same button is the camera (WhatsApp's Updates): a new moment, not a chat.
    Box(
        modifier.size(56.dp).shadow(8.dp, com.circaso.designsystem.theme.CircasoShapes.circle)
            .clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(c.action)
            .pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { contentDescription = if (camera) "New moment" else "New chat" },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.Crossfade(
            camera, label = "fab",
            animationSpec = if (CircasoTheme.reduceMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.QuickMs),
        ) { cam -> CircasoIconImage(if (cam) CircasoIcon.Camera else CircasoIcon.Plus, c.onAction, Modifier.size(24.dp)) }
    }
}

@Composable
fun ChatsBanner(state: ChatsUiState) {
    ConnectionBanner(
        showing = if (state.filter == ChatFilter.Moments) com.circaso.feature.status.presentation.MomentsOfflineLine else "Showing chats saved on this phone",
        waitingCount = state.waitingToSend,
    )
}

@Composable
fun ChatsContent(
    state: ChatsUiState, bottomPadding: PaddingValues, onQuery: (String) -> Unit, onAsha: () -> Unit, onOpen: (String) -> Unit, onNewChat: (() -> Unit)? = null,
    onFilter: (ChatFilter) -> Unit = {}, selectedChatId: String? = null,
    moments: MomentsSlot? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    // "12 minutes ago" and the chat times stay true while the list sits open: a tick once a minute.
    val now by androidx.compose.runtime.produceState(nowMillis()) {
        while (true) { kotlinx.coroutines.delay(60_000 - value % 60_000); value = nowMillis() }
    }
    // Rows fade as a chip swaps them (exits faster than entries) and settle with a spring; none with reduced motion.
    val still = CircasoTheme.reduceMotion
    val swap: @Composable androidx.compose.foundation.lazy.LazyItemScope.() -> Modifier = {
        if (still) Modifier else Modifier.animateItem(
            fadeInSpec = androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.BaseMs),
            placementSpec = androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
            fadeOutSpec = androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.QuickMs),
        )
    }
    val showMoments = moments != null && state.filter == ChatFilter.Moments
    var mutedOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val unseenPosters = androidx.compose.runtime.remember(moments?.state?.rings) { moments?.state?.unseenPosters.orEmpty() }
    Box(Modifier.fillMaxSize()) {
    // Room under the last chat for the floating button.
    val fabRoom = if (onNewChat != null) 56.dp + CircasoSpacing.s4 else 0.dp
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + CircasoSpacing.s4 + fabRoom)) {
        item(key = "brand", contentType = "brand") { ChatsBrand() }
        item(key = "search", contentType = "search") {
            SearchPill(state.query, onQuery, "Search", Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2, bottom = 6.dp))
        }
        item(key = "filters", contentType = "filters") {
            // All · Unread · Groups · Moments (as WhatsApp): one is always picked; Unread shows how many chats are
            // waiting. With large text the row scrolls sideways rather than clipping the last chip.
            val unread = state.chats.count { it.unread > 0 }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1),
                horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
            ) {
                ChatFilter.entries.filter { it != ChatFilter.Moments || moments != null }.forEach { f ->
                    // Moments carries a dot while someone I follow has posted something I haven't seen.
                    val dot = f == ChatFilter.Moments && moments?.state?.hasUnseen == true && state.filter != ChatFilter.Moments
                    com.circaso.designsystem.components.SuggestionChip(
                        f.label, { onFilter(f) },
                        modifier = Modifier.semantics {
                            if (f == ChatFilter.Unread && unread > 0) contentDescription = "Unread, $unread chats"
                            if (dot) contentDescription = "Moments, new moments"
                        },
                        selected = f == state.filter,
                        badge = if (f == ChatFilter.Unread) unread else 0,
                        dot = dot,
                    )
                }
            }
        }
        if (showMoments) {
            momentsList(moments.state, state.query, mutedOpen, { mutedOpen = !mutedOpen }, moments.actions, now, rowModifier = swap)
        }
        if (state.query.isBlank() && state.filter == ChatFilter.All) {
            // The design's first row (C1): Asha, always there, above the chats.
            item(key = "asha", contentType = "asha") {
                ListRow("Asha", sub = "Ask me anything about your money", leading = { AshaMark() }, onClick = onAsha, modifier = swap())
            }
        }
        if (!showMoments && state.loaded && state.shown.isEmpty()) {
            item(key = "chats-empty", contentType = "empty") {
                Column(swap().fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    BasicText(
                        when { state.query.isNotBlank() -> "No chats match"; state.filter == ChatFilter.Unread -> "You're all caught up"; state.filter == ChatFilter.Groups -> "No groups yet"; else -> "No chats yet" },
                        style = t.title3.copy(color = c.ink, textAlign = TextAlign.Center),
                    )
                    BasicText(
                        when {
                            state.query.isNotBlank() -> "Try another name."
                            state.filter == ChatFilter.Unread -> "No unread messages."
                            state.filter == ChatFilter.Groups -> "Start a group with the + button."
                            else -> "Start one with the + button. Everything you send is end-to-end encrypted."
                        },
                        style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                    )
                }
            }
        }
        if (!showMoments) items(state.shown, key = { it.chat.chatId }, contentType = { "chat" }) { summary ->
            // A person with a moment I haven't seen has a ring on their photo; the photo (not the row) opens it.
            val peer = summary.chat.peerId?.takeIf { summary.chat.kind != com.circaso.feature.messaging.domain.ChatKind.Group }
            val ringed = peer != null && moments != null && peer in unseenPosters
            ChatRow(
                summary, now, state.fiat, selected = summary.chat.chatId == selectedChatId,
                onAvatar = if (ringed) ({ moments.actions.onOpenRing(peer!!) }) else null,
                modifier = swap(),
            ) { onOpen(summary.chat.chatId) }
        }
    }
    if (onNewChat != null) {
        NewChatButton(
            if (showMoments) moments.actions.onCompose else onNewChat,
            Modifier.align(Alignment.BottomEnd).padding(end = CircasoSpacing.s4, bottom = bottomPadding.calculateBottomPadding() + CircasoSpacing.s4),
            camera = showMoments,
        )
    }
    }
}

@Composable
/** [selected]: the chat open in the detail pane beside the list (wide windows), shown as iPad Messages does. */
private fun ChatRow(
    summary: ChatSummary, now: Long, fiat: com.circaso.feature.wallet.domain.FiatValue? = null, selected: Boolean = false,
    /** Set when the person has an unseen moment: their photo has a ring, and tapping it opens the moment. */
    onAvatar: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
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
        modifier = modifier.padding(horizontal = CircasoSpacing.s2).clip(com.circaso.designsystem.theme.CircasoShapes.md)
            .background(androidx.compose.animation.animateColorAsState(if (selected) c.fill else androidx.compose.ui.graphics.Color.Transparent, label = "selected").value)
            .semantics { this.selected = selected },
        sub = preview,
        leading = {
            if (onAvatar == null) Avatar(summary.chat.title, imageUrl = summary.chat.avatarUrl)
            else Avatar(
                summary.chat.title,
                // Round press feedback, and the ring drawn inside the pressable so it moves with the photo on iOS.
                Modifier.clip(com.circaso.designsystem.theme.CircasoShapes.circle)
                    .pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = onAvatar)
                    .momentRing(com.circaso.feature.status.presentation.RingStyle.Unseen, c)
                    .semantics { contentDescription = "View ${summary.chat.title}'s moment" },
                imageUrl = summary.chat.avatarUrl,
            )
        },
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
