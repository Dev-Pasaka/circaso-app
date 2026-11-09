package com.circaso.feature.messaging.presentation

import com.circaso.feature.status.presentation.arcs
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
import androidx.compose.foundation.layout.offset
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
    // Each poster's arcs, for the ring on their photo in the chat list (one arc per moment, as in Moments).
    val ringArcs = androidx.compose.runtime.remember(moments?.state?.rings) {
        moments?.state?.rings.orEmpty().associate { it.posterId to it.arcs() }
    }
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
            // The row starts and ends on the screen's 16 dp line, like the logo and search above it. Chips that would
            // overflow the column (a tablet's list pane, a 360 dp phone) take tighter sides so the last one still ends
            // on that line, decided in the same layout pass so nothing flashes; only when even that doesn't fit (the
            // largest text) does the row scroll, its padding inside the scroll so the last chip stops 16 dp in.
            val filters = ChatFilter.entries.filter { it != ChatFilter.Moments || moments != null }
            val chips: @Composable (Boolean) -> Unit = { compact ->
                filters.forEach { f ->
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
                        compact = compact,
                    )
                }
            }
            val scroll = androidx.compose.foundation.rememberScrollState()
            androidx.compose.ui.layout.SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
                // Measured once at the regular size, off screen, to see whether it fits.
                val inset = CircasoSpacing.s4.roundToPx() * 2
                val gap = CircasoSpacing.s2.roundToPx()
                val natural = subcompose("measure") { chips(false) }
                    .map { it.maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity) }
                val needed = natural.sum() + gap * (natural.size - 1).coerceAtLeast(0) + inset
                val compact = needed > constraints.maxWidth
                val row = subcompose("row") {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1),
                        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                    ) { chips(compact) }
                }.map { it.measure(constraints.copy(minHeight = 0)) }
                layout(constraints.maxWidth, row.maxOf { it.height }) { row.forEach { it.place(0, 0) } }
            }
        }
        if (showMoments) {
            momentsList(moments.state, state.query, mutedOpen, { mutedOpen = !mutedOpen }, moments.actions, now, rowModifier = swap)
        }
        if (state.query.isBlank() && state.filter == ChatFilter.All) {
            // The design's first row (C1): Asha, always there, above the chats.
            item(key = "asha", contentType = "asha") {
                // The chat rows' inset, so her mark lines up with every photo below (Pasaka, 2026-11-07).
                ListRow(
                    "Asha", sub = "Ask me anything about your money", leading = { AshaMark() }, onClick = onAsha,
                    modifier = swap().padding(horizontal = CircasoSpacing.s2).clip(com.circaso.designsystem.theme.CircasoShapes.md),
                    horizontalPadding = ChatRowInnerInset,
                )
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
                summary, now, state.fiat, selected = summary.chat.chatId == selectedChatId, coinRates = state.coinRates, online = state.online,
                call = state.calls[summary.chat.chatId],
                onAvatar = if (ringed) ({ moments.actions.onOpenRing(peer!!) }) else null,
                ringArcs = peer?.let { ringArcs[it] },
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
    coinRates: Map<String, String> = emptyMap(),
    /** Set when the person has an unseen moment: their photo has a ring, and tapping it opens the moment. */
    onAvatar: (() -> Unit)? = null,
    /** The ring's arcs on the photo: one per moment, watched or not (null: no ring). */
    ringArcs: List<Boolean>? = null,
    /** Offline, a group still waiting for its name says it waits for a connection (as a waiting moment does). */
    online: Boolean = true,
    /** A call going on in this chat: the second line says so, in place of the last message. */
    call: com.circaso.feature.calls.LiveChatCall? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val latest = summary.latest
    // A group this phone can't name yet (a new phone): the row says whose phone its name and photo are coming from,
    // in place of the last message, until a member's phone sends the key; then the name and photo swap in.
    // Unavailable keeps the last message (the messages still open; only the name can't): the lock badge says it.
    val waitingLine = groupNameLine(summary.chat, online)?.takeIf { summary.chat.nameState == com.circaso.feature.messaging.domain.GroupNameState.Waiting || latest == null }
    val preview = waitingLine ?: when (latest) {
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
            com.circaso.feature.wallet.domain.coinFiatText(latest.amountMinor, latest.asset, fiat, coinRates)
                ?: units.coinText(latest.amountMinor, latest.asset)
        }
        is Message.Deleted -> "This message was deleted"
        is Message.Unreadable -> "A message this phone can't show"
        is Message.System -> latest.text
    }
    // The design's end column (`cx-row-end`): the time above the unread count, both right-aligned.
    ListRow(
        title = summary.chat.title,
        horizontalPadding = ChatRowInnerInset,
        // Always inset and rounded so selecting doesn't move the row; only the fill changes, softly.
        modifier = modifier.padding(horizontal = CircasoSpacing.s2).clip(com.circaso.designsystem.theme.CircasoShapes.md)
            .background(androidx.compose.animation.animateColorAsState(if (selected) c.fill else androidx.compose.ui.graphics.Color.Transparent, label = "selected").value)
            .semantics { this.selected = selected },
        sub = preview,
        subContent = call?.let { live -> { CallInProgressLine(live) } },
        leading = {
            if (!summary.chat.nameKnown && summary.chat.kind == com.circaso.feature.messaging.domain.ChatKind.Group) Box {
                Avatar(summary.chat.avatarLabel, imageUrl = summary.chat.avatarUrl)
                GroupNameBadge(summary.chat.nameState)
            }
            else if (onAvatar == null) Avatar(summary.chat.title, imageUrl = summary.chat.avatarUrl)
            else Avatar(
                summary.chat.title,
                // Round press feedback, and the ring drawn inside the pressable so it moves with the photo on iOS.
                Modifier.clip(com.circaso.designsystem.theme.CircasoShapes.circle)
                    .pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = onAvatar)
                    .momentRing(ringArcs ?: listOf(false), c)
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
 * A chat's second line while a call goes on in it (2026-11-09; no frame): the call's icon and "Call in progress" in ink
 * and semibold, so it stands out from last-message previews in secondary ink without a colour the palette doesn't
 * have; the icon tells voice from video, and a group adds how many are on it (the bar's count). Tapping the row opens the chat as usual, where the bar joins.
 */
@Composable
private fun CallInProgressLine(call: com.circaso.feature.calls.LiveChatCall) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val words = "Call in progress" + if (call.joined.size > 1) " · ${call.joined.size} people" else ""
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CircasoIconImage(if (call.video) CircasoIcon.Video else CircasoIcon.Phone, c.ink, Modifier.size(14.dp))
        BasicText(words, style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/**
 * The second line of a group whose name this phone can't read yet (2026-11-09; no frame, the Moments waiting row's
 * words): "Waiting for Amina’s phone" (or "a member’s phone" when there are several), "Waiting for a connection"
 * offline, and once every member asked said they have no key either, that it can't be shown here. Null for a named
 * group or a direct chat.
 */
internal fun groupNameLine(chat: com.circaso.feature.messaging.domain.Chat, online: Boolean): String? = when {
    chat.kind != com.circaso.feature.messaging.domain.ChatKind.Group || chat.nameKnown -> null
    chat.nameState == com.circaso.feature.messaging.domain.GroupNameState.Unavailable ->
        if (chat.myRole != com.circaso.feature.messaging.domain.MemberRole.Member) "Name the group again to show it here" else "Name can’t be shown on this phone"
    !online -> "Waiting for a connection"
    else -> "Waiting for ${chat.waitingFor?.let { "$it’s" } ?: "a member’s"} phone"
}

/** The clock (waiting) or lock (unavailable) on a nameless group's photo, at its lower right like the Moments badge. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.GroupNameBadge(state: com.circaso.feature.messaging.domain.GroupNameState) {
    val c = CircasoTheme.colors
    Box(
        Modifier.align(Alignment.BottomEnd).offset(GroupBadgeOffset, GroupBadgeOffset).size(GroupBadgeDisc).background(c.bg, com.circaso.designsystem.theme.CircasoShapes.circle)
            .padding(GroupBadgeEdge).clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(c.fillStrong),
        contentAlignment = Alignment.Center,
    ) {
        val icon = if (state == com.circaso.feature.messaging.domain.GroupNameState.Unavailable) CircasoIcon.Lock else CircasoIcon.Clock
        CircasoIconImage(icon, c.ink, Modifier.size(GroupBadgeGlyph))
    }
}

private val GroupBadgeDisc = 18.dp
private val GroupBadgeEdge = 2.dp
private val GroupBadgeGlyph = 10.dp
/** The disc's centre on the photo's circle at the lower right (45°), half over it, as the Moments badge sits. */
private val GroupBadgeOffset = CircasoSizes.avatarMd / 2 * (kotlin.math.sqrt(2f) / 2) - (CircasoSizes.avatarMd - GroupBadgeDisc) / 2

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

/**
 * A chat row's content inset inside its rounded, inset row (8 dp from the screen, for the selection fill): together
 * 16 dp, the same line as the logo, search and chips above (Pasaka, 2026-11-07).
 */
internal val ChatRowInnerInset = CircasoSpacing.s2
