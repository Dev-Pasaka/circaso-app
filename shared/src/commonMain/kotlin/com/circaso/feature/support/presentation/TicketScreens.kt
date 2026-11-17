package com.circaso.feature.support.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.chatListTime
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.SheetBadge
import com.circaso.designsystem.chrome.SheetHead
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.ToastKind
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.AshaMark
import com.circaso.designsystem.components.BubbleMeta
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.ChatBubble
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.ComposerBar
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.MessageColumn
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.SkeletonBlock
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.components.SystemLine
import com.circaso.designsystem.components.TextBadge
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.support.Support
import com.circaso.feature.support.TicketCategory
import com.circaso.feature.support.TicketDetailResponse
import com.circaso.feature.support.TicketDto
import com.circaso.feature.support.TicketMessageDto
import com.circaso.feature.support.TicketRules
import com.circaso.feature.support.TicketStatus

/*
 * Support tickets (2026-11-17). There is no design frame for these: they follow Help (SP01) for the lists and the
 * support conversation (SP02, "Case CX-48213") for a ticket: its number in the bar, the thread under it.
 */

/** When a ticket or a reply happened, as the chat list writes it ("21:14", "Yesterday", "12 Oct"); empty if unreadable. */
internal fun ticketTime(iso: String, now: Long): String =
    runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrNull()?.let { chatListTime(it, now) }.orEmpty()

private fun ticketDate(iso: String): String? =
    runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrNull()?.let { formatLocalTime(it, LocalTimeStyle.ShortDate) }

/** One ticket in a list (Help's "Your tickets" and the full list): subject, number and status, when it last moved. */
@Composable
internal fun TicketRow(ticket: TicketDto, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListRow(
        ticket.subject, modifier,
        // With the badge on the title, the line under it doesn't say the same thing twice.
        sub = listOfNotNull(ticket.number, TicketStatus.label(ticket).takeUnless { ticket.replyNeeded }, "Opened by Asha".takeIf { ticket.openedByAsha }).joinToString(" · "),
        meta = ticketTime(ticket.lastMessageAt, now).ifEmpty { null },
        titleEnd = if (ticket.replyNeeded) ({ ReplyNeededBadge() }) else null,
        onClick = onClick,
    )
}

/** Support replied and it's the person's turn: drawn like an unread count, in words. */
@Composable
private fun ReplyNeededBadge() {
    val c = CircasoTheme.colors
    Box(Modifier.heightIn(min = 20.dp).clip(CircasoShapes.pill).background(c.action).padding(horizontal = CircasoSpacing.s2), contentAlignment = Alignment.Center) {
        BasicText("Reply needed", style = CircasoTheme.typography.caption1.copy(color = c.onAction, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

// ---- Your tickets ----

@Composable
fun TicketsScreen(viewModel: TicketsViewModel, onBack: () -> Unit, onOpenTicket: (String) -> Unit, onNewTicket: () -> Unit) {
    val state by viewModel.state.collectAsState()
    TicketsContent(state, onBack, onOpenTicket, onNewTicket, viewModel::onRefresh, viewModel::onLoadMore, viewModel::onErrorShown)
}

@Composable
fun TicketsContent(
    state: TicketsUiState,
    onBack: () -> Unit,
    onOpenTicket: (String) -> Unit,
    onNewTicket: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onErrorShown: () -> Unit = {},
) {
    val c = CircasoTheme.colors
    val toast = rememberToastState()
    // With tickets on screen a failed refresh is a passing note; without any it is the screen itself (below).
    LaunchedEffect(state.error, state.loaded) { if (state.loaded) state.error?.let { toast.show(it, ToastKind.Problem); onErrorShown() } }
    val now = nowMillis()
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = "Your tickets", onBack = onBack, trailing = { CircasoIconButton(CircasoIcon.Plus, "New ticket", onNewTicket) }) },
        toast = { ToastHost(toast) },
    ) { padding ->
        val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        // The pull indicator is the wallet activity's (TransferScreens): the one place the app borrows it.
        val pulling = state.refreshing && state.loaded
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = pulling, onRefresh = onRefresh, state = pull, modifier = Modifier.fillMaxSize(),
            indicator = {
                androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                    state = pull, isRefreshing = pulling, modifier = Modifier.align(Alignment.TopCenter), containerColor = c.surfaceRaised, color = c.ink,
                )
            },
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2, bottom = padding.calculateBottomPadding() + CircasoSpacing.s6),
            ) {
                when {
                    state.showSkeleton -> item(key = "skeleton") { TicketsSkeleton() }
                    !state.loaded -> item(key = "error") {
                        StateMessage(
                            Modifier.fillParentMaxHeight(0.8f), CircasoIcon.Warning, "Couldn't load your tickets", state.error.orEmpty(),
                            if (state.refreshing) "Loading…" else "Try Again", onRefresh, busy = state.refreshing,
                        )
                    }
                    state.empty -> item(key = "empty") {
                        StateMessage(
                            Modifier.fillParentMaxHeight(0.8f), CircasoIcon.Ticket, "No tickets yet",
                            "When you write to us, your ticket and our replies show here.", "Write to Us", onNewTicket,
                        )
                    }
                    else -> {
                        if (!state.online) item(key = "offline") {
                            BasicText(
                                "You're offline. These are your tickets as last loaded.",
                                Modifier.fillMaxWidth().padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, bottom = CircasoSpacing.s3).semantics { liveRegion = LiveRegionMode.Polite },
                                style = CircasoTheme.typography.footnote.copy(color = c.inkSecondary),
                            )
                        }
                        itemsIndexed(state.tickets, key = { _, t -> t.ticketId }) { i, ticket ->
                            Column(Modifier.fillMaxWidth().clip(groupShape(i == 0, i == state.tickets.lastIndex)).background(c.surface)) {
                                TicketRow(ticket, now, { onOpenTicket(ticket.ticketId) })
                                if (i < state.tickets.lastIndex) RowDivider()
                            }
                        }
                        if (state.hasMore) item(key = "more") {
                            // Reaching the end asks for the next page; the button is there if that try failed.
                            LaunchedEffect(state.tickets.size) { onLoadMore() }
                            Box(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s3).heightIn(min = CircasoSizes.controlMd), contentAlignment = Alignment.Center) {
                                if (state.loadingMore) Spinner(c.inkSecondary, Modifier.size(20.dp).semantics { contentDescription = "Loading more tickets" })
                                else CircasoButton("Show More", onLoadMore, style = ButtonStyle.Plain)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A grouped list's rounded ends when its rows are separate lazy items: the first row's top, the last row's bottom. */
private fun groupShape(first: Boolean, last: Boolean): Shape {
    val flat = CornerSize(0)
    val full = CircasoShapes.lg
    return when {
        first && last -> full
        first -> full.copy(bottomStart = flat, bottomEnd = flat)
        last -> full.copy(topStart = flat, topEnd = flat)
        else -> full.copy(all = flat)
    }
}

@Composable
private fun TicketsSkeleton() {
    val c = CircasoTheme.colors
    Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface).semantics { contentDescription = "Loading your tickets" }) {
        repeat(5) { i ->
            Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                SkeletonBlock(Modifier.fillMaxWidth(listOf(0.62f, 0.48f, 0.7f, 0.54f, 0.4f)[i]).height(15.dp))
                SkeletonBlock(Modifier.fillMaxWidth(listOf(0.4f, 0.36f, 0.44f, 0.34f, 0.38f)[i]).height(12.dp))
            }
            if (i < 4) RowDivider()
        }
    }
}

/** A screen with nothing to list: what happened, and the one thing to do about it. */
@Composable
private fun StateMessage(modifier: Modifier, icon: CircasoIcon, title: String, text: String, action: String, onAction: () -> Unit, busy: Boolean = false) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2, Alignment.CenterVertically),
    ) {
        SheetBadge(icon)
        BasicText(title, style = t.title3.copy(color = c.ink, textAlign = TextAlign.Center), modifier = Modifier.padding(top = CircasoSpacing.s2).semantics { heading(); liveRegion = LiveRegionMode.Polite })
        if (text.isNotEmpty()) BasicText(text, style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        CircasoButton(action, onAction, modifier = Modifier.padding(top = CircasoSpacing.s3), busy = busy)
    }
}

// ---- New ticket ----

@Composable
fun NewTicketScreen(viewModel: NewTicketViewModel, onBack: () -> Unit, onOpened: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.opened.collect(onOpened) }
    NewTicketContent(state, onBack, viewModel::onCategory, viewModel::onSubject, viewModel::onDescription, viewModel::onSend)
}

@Composable
fun NewTicketContent(
    state: NewTicketUiState,
    onBack: () -> Unit,
    onCategory: (TicketCategory) -> Unit,
    onSubject: (String) -> Unit,
    onDescription: (String) -> Unit,
    onSend: () -> Unit,
    /** The category sheet starts open (previews). */
    pickingCategory: Boolean = false,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    var picking by remember { mutableStateOf(pickingCategory) }
    CircasoScaffold(
        topBar = { CircasoNavBar(title = "New ticket", onBack = onBack) },
        overlay = {
            CircasoSheet(picking, { picking = false }, label = "What's it about?") {
                Column(Modifier.semantics { selectableGroup() }) {
                    SheetHead("What's it about?", { picking = false })
                    TicketCategory.entries.forEach { category ->
                        val on = category == state.category
                        ListRow(
                            category.label, Modifier.semantics { selected = on },
                            trailing = if (on) ({ CircasoIconImage(CircasoIcon.Check, c.ink, Modifier.size(18.dp)) }) else null,
                            onClick = { onCategory(category); picking = false },
                            // The sheet already insets its content; rows line up with its title.
                            horizontalPadding = 0.dp, role = Role.RadioButton,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = CircasoSpacing.s5).padding(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), modifier = Modifier.padding(top = CircasoSpacing.s2)) {
                BasicText("Write to us", style = t.title1.copy(color = c.ink), modifier = Modifier.semantics { heading() })
                BasicText("Tell us what happened. We reply here and notify you.", style = t.body.copy(color = c.inkSecondary))
            }
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                // On `fill`, as the fields are: this screen isn't grouped, and in light a `surface` card is the page's white.
                Column(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.fill)) {
                    ListRow("What's it about?", sub = state.category?.label ?: "Choose one", trailing = { RowChevron() }, onClick = if (state.busy) null else ({ picking = true }))
                }
                state.categoryProblem?.let { FieldNote(it, problem = true) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                CircasoTextField(
                    state.subject, onSubject, label = "Subject", placeholder = "Payment to Baraka not received", enabled = !state.busy,
                    error = state.subjectProblem,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                )
                FieldNote(state.subjectCount, end = true, spoken = "${state.subject.trim().length} of ${TicketRules.SubjectMax} characters")
            }
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                CircasoTextField(
                    state.description, onDescription, label = "What happened?", placeholder = "What you were doing, what you expected and what you saw", enabled = !state.busy,
                    error = state.descriptionProblem, singleLine = false, minLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                FieldNote(state.descriptionCount, end = true, spoken = "${state.description.trim().length} of ${TicketRules.DescriptionMax} characters")
            }
            Row(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.fill).padding(CircasoSpacing.s4), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                CircasoIconImage(CircasoIcon.Warning, c.warning, Modifier.size(20.dp))
                BasicText(Support.NeverAsk, style = t.footnote.copy(color = c.ink))
            }
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                state.error?.let { BasicText(it, style = t.footnote.copy(color = c.critical), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                // Stays tappable while something is missing, so a tap says what (the notes under the fields).
                CircasoButton(if (state.busy) "Sending…" else "Send", onSend, size = ButtonSize.Large, block = true, busy = state.busy, enabled = state.online || state.busy)
                if (!state.online && !state.busy) {
                    BasicText(
                        "You're offline. Keep this screen open and send when you're back online.",
                        style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

/** The small line under a field: its counter (at the end) or what is missing. */
@Composable
private fun FieldNote(text: String, end: Boolean = false, problem: Boolean = false, spoken: String? = null) {
    val c = CircasoTheme.colors
    BasicText(
        text,
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s1).then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier),
        style = CircasoTheme.typography.caption1.copy(color = if (problem) c.critical else c.inkSecondary, textAlign = if (end) TextAlign.End else TextAlign.Start),
    )
}

// ---- One ticket ----

@Composable
fun TicketScreen(viewModel: TicketViewModel, onBack: () -> Unit, onNewTicket: (String?) -> Unit) {
    val state by viewModel.state.collectAsState()
    TicketContent(state, onBack, viewModel::onDraft, viewModel::onSend, viewModel::onRetry, { onNewTicket(state.detail?.ticket?.category) })
}

@Composable
fun TicketContent(
    state: TicketUiState,
    onBack: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    onNewTicket: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val detail = state.detail
    CircasoScaffold(
        topBar = { CircasoNavBar(title = detail?.ticket?.number ?: "Ticket", onBack = onBack, subtitle = detail?.ticket?.let { TicketStatus.label(it) }) },
    ) { padding ->
        val bottom = padding.calculateBottomPadding()
        if (detail == null) {
            if (state.showSkeleton) TicketSkeleton()
            else StateMessage(
                Modifier.fillMaxSize().padding(bottom = bottom), CircasoIcon.Warning, "Couldn't open this ticket", state.loadError.orEmpty(),
                if (state.loading) "Loading…" else "Try Again", onRetry, busy = state.loading,
            )
            return@CircasoScaffold
        }
        Column(Modifier.fillMaxSize()) {
            // Laid out from the bottom, as a chat is: it opens at the newest reply and stays there when the keyboard
            // comes up. A new reply comes into view only while the person is at the end, never while they read back.
            val list = rememberLazyListState()
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val still = CircasoTheme.reduceMotion
            val now = nowMillis()
            val follow = com.circaso.feature.assistant.presentation.rememberFollowLatest(list) { detail.messages.lastOrNull()?.messageId to state.pending }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxSize(), state = list, reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
                    verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                ) {
                    // Newest first here; on screen that is the bottom.
                    when {
                        state.closed -> item(key = "closed") { SystemLine("This ticket is closed", icon = CircasoIcon.Lock) }
                        state.resolved -> item(key = "resolved") { SystemLine("Marked as resolved", icon = CircasoIcon.Check) }
                    }
                    state.pending?.let { body ->
                        item(key = "pending") {
                            MessageColumn(outgoing = true) {
                                ChatBubble(body, outgoing = true, faded = true)
                                BubbleMeta("Sending…", icon = CircasoIcon.Clock)
                            }
                        }
                    }
                    val replies = detail.messages.asReversed()
                    items(replies.size, key = { replies[it].messageId }) { i -> ReplyBubble(replies[i], now) }
                    item(key = "description") {
                        MessageColumn(outgoing = true, Modifier.padding(top = CircasoSpacing.s2)) {
                            ChatBubble(detail.description, outgoing = true)
                            ticketTime(detail.ticket.createdAt, now).takeIf { it.isNotEmpty() }?.let { BubbleMeta(it) }
                        }
                    }
                    item(key = "head") { TicketHead(detail) }
                }
                com.circaso.feature.assistant.presentation.JumpToLatest(
                    !follow.following, { scope.launch { follow.jump(!still) } },
                    Modifier.align(Alignment.BottomEnd).padding(end = CircasoSpacing.s4, bottom = CircasoSpacing.s3),
                )
            }
            if (state.closed) {
                state.sendError?.let { BasicText(it, Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1), style = t.footnote.copy(color = c.critical)) }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4).padding(top = CircasoSpacing.s2, bottom = bottom + CircasoSpacing.s4),
                    verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText("Closed tickets can't take more replies. If you still need help, open a new one.", style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                    CircasoButton("Open a New Ticket", onNewTicket, size = ButtonSize.Large, block = true)
                }
            } else {
                // One line above the field at a time, the most pressing first.
                val line: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = when {
                    state.sendError != null -> ({ ComposerNote(state.sendError, problem = true) })
                    !state.online -> ({ ComposerNote("You're offline. You can reply when you're back online.") })
                    // Offline says it already, and the ticket is read again on reconnect.
                    state.loadError != null -> ({
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
                            BasicText("Couldn't check for new replies. ${state.loadError}", Modifier.weight(1f).padding(start = CircasoSpacing.s4), style = t.footnote.copy(color = c.inkSecondary))
                            CircasoButton("Try Again", onRetry, style = ButtonStyle.Plain, size = ButtonSize.Small, busy = state.loading)
                        }
                    })
                    state.resolved -> ({ ComposerNote("Still need help? Replying opens this ticket again.") })
                    else -> null
                }
                ComposerBar(
                    value = state.draft, onValueChange = onDraft,
                    placeholder = if (state.resolved) "Reply to open it again" else "Write a reply",
                    // The field stays live while a reply is on its way (the keyboard stays up); only the arrow waits.
                    canSend = state.canSend, onSend = { scope.launch { follow.jump(!still) }; onSend() }, sendBusy = state.sending,
                    above = line, sendLabel = "Send reply", fieldLabel = "Reply to ${Support.Name}",
                )
            }
        }
    }
}

/** The one line above the reply field: why it can't send, or what sending will do. */
@Composable
private fun ComposerNote(text: String, problem: Boolean = false) {
    val c = CircasoTheme.colors
    BasicText(
        text,
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1).semantics { liveRegion = LiveRegionMode.Polite },
        style = CircasoTheme.typography.footnote.copy(color = if (problem) c.critical else c.inkSecondary),
    )
}

/** What the ticket is: its subject, what it's about and when it was opened. */
@Composable
private fun TicketHead(detail: TicketDetailResponse) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val ticket = detail.ticket
    // On `fill`: the thread isn't a grouped screen, and in light a `surface` card is the page's white.
    Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.fill).padding(CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        BasicText(ticket.subject, style = t.title3.copy(color = c.ink), modifier = Modifier.semantics { heading() })
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1), itemVerticalAlignment = Alignment.CenterVertically) {
            TicketCategory.of(ticket.category)?.let { TextBadge(it.label, warning = false) }
            ticketDate(ticket.createdAt)?.let { BasicText("Opened $it", style = t.footnote.copy(color = c.inkSecondary)) }
        }
        if (ticket.openedByAsha) {
            Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
                AshaMark(size = CircasoSizes.avatarSm)
                BasicText("Opened by Asha for you", style = t.footnote.copy(color = c.inkSecondary))
            }
        }
    }
}

/** A reply: the person's own on their side; support's and Asha's on the other, under the name. */
@Composable
private fun ReplyBubble(message: TicketMessageDto, now: Long) {
    val c = CircasoTheme.colors
    val asha = message.author == "ASHA"
    MessageColumn(outgoing = message.mine) {
        if (!message.mine) {
            BasicText(
                message.authorName ?: if (asha) "Asha" else Support.Name,
                Modifier.padding(start = CircasoSpacing.s1),
                style = CircasoTheme.typography.caption1.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold),
            )
        }
        // Asha writes Markdown; people's words are shown exactly as typed.
        ChatBubble(message.body, outgoing = message.mine, markdown = asha)
        ticketTime(message.createdAt, now).takeIf { it.isNotEmpty() }?.let { BubbleMeta(it) }
    }
}

@Composable
private fun TicketSkeleton() {
    Column(
        Modifier.fillMaxSize().padding(CircasoSpacing.s4).semantics { contentDescription = "Loading the ticket" },
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        SkeletonBlock(Modifier.fillMaxWidth().height(96.dp), CircasoShapes.lg)
        SkeletonBlock(Modifier.fillMaxWidth(0.7f).height(72.dp).align(Alignment.End), CircasoShapes.lg)
        SkeletonBlock(Modifier.fillMaxWidth(0.6f).height(56.dp), CircasoShapes.lg)
        SkeletonBlock(Modifier.fillMaxWidth(0.5f).height(44.dp).align(Alignment.End), CircasoShapes.lg)
    }
}
