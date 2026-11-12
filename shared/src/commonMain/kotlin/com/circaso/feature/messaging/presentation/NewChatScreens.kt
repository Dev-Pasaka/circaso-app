package com.circaso.feature.messaging.presentation

import com.circaso.designsystem.components.SearchField
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.openAppSettings
import com.circaso.core.platform.shareText
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.ActionDisc
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.AvatarStack
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.InviteCard
import com.circaso.designsystem.components.LetterHeader
import com.circaso.designsystem.components.ListCaption
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.PeopleDividerInset
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SkeletonPeopleRows
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** What New Chat can do; the defaults do nothing (previews). */
class NewChatActions(
    val onQuery: (String) -> Unit = {},
    val onPick: (userId: String) -> Unit = {},
    val onFindHandle: () -> Unit = {},
    val onNewGroup: () -> Unit = {},
    val onInvite: () -> Unit = {},
    val onFindFromContacts: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/** Design C8: a person from your contacts, someone by @username, a new group, or an invite. */
@Composable
fun NewChatScreen(viewModel: NewChatViewModel, onBack: () -> Unit, onOpened: (chatId: String) -> Unit, onNewGroup: () -> Unit) {
    val state by viewModel.state.collectAsState()
    // The invite goes out through the system share sheet (Messages, WhatsApp, mail…), once per link.
    LaunchedEffect(state.share) { state.share?.let { shareText(it, NewChatViewModel.InviteSubject); viewModel.onShared() } }
    NewChatContent(
        state,
        NewChatActions(
            onQuery = viewModel::onQuery, onPick = { id -> viewModel.onPick(id, onOpened) }, onFindHandle = { viewModel.onFindHandle(onOpened) },
            onNewGroup = onNewGroup, onInvite = viewModel::onInvite, onFindFromContacts = viewModel::onFindFromContacts, onBack = onBack,
        ),
        onMessageShown = viewModel::onMessageShown,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NewChatContent(state: NewChatUiState, actions: NewChatActions, onMessageShown: () -> Unit = {}) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    LaunchedEffect(state.message) { state.message?.let { toast.show(it); onMessageShown() } }
    val search = remember { FocusRequester() }
    val browsing = state.query.isBlank()
    CircasoScaffold(
        topBar = { CircasoNavBar(title = "New Chat", onBack = actions.onBack) },
        banner = { ConnectionBanner(showing = "You can start a chat when you're back online") },
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + CircasoSpacing.s6)) {
            item(key = "search") {
                SearchField(
                    state.query, actions.onQuery, "Name or @username", search,
                    Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
                )
            }
            if (browsing) {
                item(key = "actions") {
                    Column(Modifier.padding(top = CircasoSpacing.s1)) {
                        ListRow("New Group", sub = "Chat and save together", leading = { ActionDisc(CircasoIcon.People) }, trailing = { RowChevron() }, onClick = actions.onNewGroup)
                        RowDivider(Modifier.padding(start = PeopleDividerInset - CircasoSpacing.s4))
                        ListRow(
                            "Find by Username", sub = "Anyone on Circaso, by their @username",
                            leading = { ActionDisc(CircasoIcon.UserPlus) }, trailing = { RowChevron() },
                            onClick = { actions.onQuery("@"); runCatching { search.requestFocus() } },
                        )
                        RowDivider(Modifier.padding(start = PeopleDividerInset - CircasoSpacing.s4))
                        ListRow(
                            "Invite a Friend", sub = if (state.online) "Share your invite link" else "Inviting needs a connection",
                            leading = { ActionDisc(CircasoIcon.Share, enabled = state.online) },
                            trailing = { if (state.creatingInvite) Spinner(c.inkSecondary, Modifier.size(18.dp)) else RowChevron() },
                            onClick = if (state.online && !state.creatingInvite) actions.onInvite else null,
                        )
                    }
                }
            }

            // Someone typed an @username nobody in the contacts has: find them on Circaso.
            state.handleToFind?.let { handle ->
                item(key = "find-handle") {
                    ListRow(
                        "Find @$handle", sub = if (state.online) "Look them up on Circaso and start a chat" else "Finding people needs a connection",
                        leading = { ActionDisc(CircasoIcon.Search, enabled = state.online) },
                        trailing = { if (state.opening == "@$handle") Spinner(c.inkSecondary, Modifier.size(18.dp)) else RowChevron() },
                        onClick = if (state.online && state.opening == null) actions.onFindHandle else null,
                    )
                }
            }

            when {
                !state.loaded -> item(key = "loading") { SkeletonPeopleRows(6, Modifier.padding(top = CircasoSpacing.s4)) }
                state.contacts.isEmpty() && browsing -> item(key = "empty") { NoPeopleYet(state, actions) }
                else -> {
                    if (state.recent.isNotEmpty()) {
                        item(key = "recent") {
                            ListCaption("Recent")
                            RecentStrip(state.recent, state.opening, state.online, actions.onPick)
                        }
                    }
                    if (browsing) item(key = "on-circaso") { ListCaption("On Circaso") }
                    state.sections.forEach { section ->
                        stickyHeader(key = "letter-${section.letter}") { LetterHeader(section.letter) }
                        items(section.people, key = { "p-" + it.userId }) { person ->
                            Column(Modifier.animateItem(fadeInSpec = tween(CircasoMotion.BaseMs), fadeOutSpec = tween(CircasoMotion.QuickMs), placementSpec = if (CircasoTheme.reduceMotion) null else tween(CircasoMotion.BaseMs))) {
                                ListRow(
                                    title = person.name, sub = person.sub,
                                    leading = { Avatar(person.name, imageUrl = person.avatarUrl) },
                                    trailing = { if (state.opening == person.userId) Spinner(c.inkSecondary, Modifier.size(18.dp)) },
                                    onClick = if (state.opening == null) ({ actions.onPick(person.userId) }) else null,
                                )
                                RowDivider(Modifier.padding(start = PeopleDividerInset - CircasoSpacing.s4))
                            }
                        }
                    }
                    if (state.matches.isEmpty() && !browsing) {
                        item(key = "no-match") {
                            BasicText(
                                if (state.handleToFind != null) "No one in your contacts matches. Look them up above." else "No one in your contacts matches “${state.query.trim()}”.",
                                Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s8, vertical = CircasoSpacing.s6),
                                style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                            )
                        }
                    }
                    if (browsing) {
                        item(key = "invite") {
                            ListCaption("Invite to Circaso")
                            InviteCard(
                                "Bring your people", "Send a link. When they join, you're connected and can chat and pay.",
                                "Invite", actions.onInvite, Modifier.padding(horizontal = CircasoSpacing.s4),
                                enabled = state.online, busy = state.creatingInvite,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Nobody from the contacts on Circaso yet: say so warmly, and offer the two ways forward (match the phone book, invite). */
@Composable
private fun NoPeopleYet(state: NewChatUiState, actions: NewChatActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s8, vertical = CircasoSpacing.s8),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
    ) {
        AvatarStack(listOf("A" to null, "J" to null, "W" to null), size = CircasoSizes.avatarMd)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText("Your people show up here", style = t.title3.copy(color = c.ink, textAlign = TextAlign.Center))
            BasicText(
                if (state.phoneBook == PhoneBookState.Denied) "Circaso can't see your contacts. You can allow it in Settings, or find people by @username."
                else "See who from your contacts is already on Circaso, or invite them.",
                style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
            )
        }
        if (state.phoneBook == PhoneBookState.Denied) {
            CircasoButton("Open Settings", { openAppSettings() }, style = ButtonStyle.Secondary, size = ButtonSize.Medium)
        } else {
            CircasoButton(
                "Find Friends", actions.onFindFromContacts, size = ButtonSize.Medium, icon = CircasoIcon.People,
                enabled = state.online, busy = state.phoneBook == PhoneBookState.Syncing,
            )
        }
        CircasoButton("Invite a Friend", actions.onInvite, style = ButtonStyle.Plain, size = ButtonSize.Medium, enabled = state.online, busy = state.creatingInvite)
        BasicText(
            "Only phone numbers are matched. Names never leave your phone.",
            style = t.footnote.copy(color = c.inkTertiary, textAlign = TextAlign.Center),
        )
    }
}

/** The people you chatted with lately, as faces in a row: one tap opens the chat (Telegram's frequent contacts). */
@Composable
private fun RecentStrip(recent: List<PersonRow>, opening: String?, online: Boolean, onPick: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    LazyRow(contentPadding = PaddingValues(horizontal = CircasoSpacing.s4), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        items(recent, key = { it.userId }) { p ->
            Column(
                Modifier.width(68.dp).clip(CircasoShapes.md)
                    .pressable(enabled = opening == null, role = Role.Button, onClick = { onPick(p.userId) })
                    .padding(vertical = CircasoSpacing.s1)
                    .semantics(mergeDescendants = true) { contentDescription = "Chat with ${p.name}" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Avatar(p.name, size = CircasoSizes.avatarLg - 8.dp, imageUrl = p.avatarUrl)
                    if (opening == p.userId) {
                        Box(Modifier.size(CircasoSizes.avatarLg - 8.dp).clip(CircasoShapes.circle).background(c.scrim), contentAlignment = Alignment.Center) {
                            Spinner(c.onAction, Modifier.size(18.dp))
                        }
                    }
                }
                BasicText(p.name.trim().substringBefore(' '), style = t.caption1.copy(color = c.ink, textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

