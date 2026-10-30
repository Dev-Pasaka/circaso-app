package com.circaso.feature.messaging.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SectionHeader
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** Design C8: a person from your contacts, a new group, or an invite. Opening a chat needs the server. */
@Composable
fun NewChatScreen(viewModel: NewChatViewModel, onBack: () -> Unit, onOpened: (chatId: String) -> Unit, onNewGroup: () -> Unit, onInvite: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        topBar = { CircasoNavBar(title = "New Chat", onBack = onBack) },
        banner = { ConnectionBanner(showing = "You can start a chat when you're back online") },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4)) {
            item {
                CircasoTextField(
                    value = state.query, onValueChange = viewModel::onQuery, label = "Search", placeholder = "Name or @handle",
                    modifier = Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
                    leading = { CircasoIconImage(CircasoIcon.Search, c.inkTertiary, Modifier.size(18.dp)) },
                )
                state.error?.let { BasicText(it, style = t.footnote.copy(color = c.critical), modifier = Modifier.padding(horizontal = CircasoSpacing.s4)) }
            }
            if (state.query.isBlank()) {
                item {
                    ListRow("New Group", sub = "Chat and save together", leading = { IconDisc(CircasoIcon.Person) }, trailing = { RowChevron() }, onClick = onNewGroup)
                    RowDivider(Modifier.padding(start = 76.dp))
                    ListRow("Invite a Friend", sub = "Share your invite link", leading = { IconDisc(CircasoIcon.Plus) }, trailing = { RowChevron() }, onClick = onInvite)
                    SectionHeader("On Circaso", Modifier.padding(top = CircasoSpacing.s4))
                }
            }
            if (state.shown.isEmpty()) {
                item {
                    BasicText(
                        if (state.contacts.isEmpty()) "Nobody from your contacts is on Circaso yet. Invite a friend to start." else "Nobody matches.",
                        style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
                    )
                }
            }
            items(state.shown, key = { it.person.userId }) { contact ->
                ListRow(
                    title = contact.person.displayName,
                    sub = contact.person.handle?.let { "@$it" },
                    leading = { Avatar(contact.person.displayName, imageUrl = contact.person.avatarUrl) },
                    trailing = { if (state.opening == contact.person.userId) Spinner(c.inkSecondary) },
                    onClick = if (state.online && state.opening == null) ({ viewModel.onPick(contact, onOpened) }) else null,
                )
                RowDivider(Modifier.padding(start = 76.dp))
            }
        }
    }
}

@Composable
private fun IconDisc(icon: CircasoIcon) {
    val c = CircasoTheme.colors
    Box(Modifier.size(CircasoSizes.avatarMd).background(c.action, CircasoShapes.circle), contentAlignment = Alignment.Center) {
        CircasoIconImage(icon, c.onAction, Modifier.size(20.dp))
    }
}

/** Design D1, the chat part: a name and the people. The shared wallet and goal arrive with the wallet. */
@Composable
fun NewGroupScreen(viewModel: NewGroupViewModel, onBack: () -> Unit, onCreated: (chatId: String) -> Unit) {
    val state by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        topBar = { CircasoNavBar(title = "New Group", onBack = onBack) },
        banner = { ConnectionBanner(showing = "You can create the group when you're back online") },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4)) {
            item {
                Column(Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                    CircasoTextField(value = state.name, onValueChange = viewModel::onName, label = "Group name", placeholder = "Holiday Fund")
                    val n = state.selected.size
                    BasicText(if (n == 0) "Add members" else "$n ${if (n == 1) "member" else "members"} + you", style = t.subhead.copy(color = c.inkSecondary))
                    if (state.selectedContacts.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                            items(state.selectedContacts, key = { it.person.userId }) { contact ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).pressable(role = Role.Button, onClick = { viewModel.onToggle(contact.person.userId) })) {
                                    Avatar(contact.person.displayName, imageUrl = contact.person.avatarUrl)
                                    BasicText(contact.person.displayName.substringBefore(' '), style = t.caption1.copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    CircasoTextField(
                        value = state.query, onValueChange = viewModel::onQuery, label = "Search", placeholder = "Search contacts",
                        leading = { CircasoIconImage(CircasoIcon.Search, c.inkTertiary, Modifier.size(18.dp)) },
                    )
                    state.error?.let { BasicText(it, style = t.footnote.copy(color = c.critical)) }
                }
            }
            items(state.shownContacts, key = { it.person.userId }) { contact ->
                val picked = contact.person.userId in state.selected
                ListRow(
                    title = contact.person.displayName,
                    sub = contact.person.handle?.let { "@$it" },
                    leading = { Avatar(contact.person.displayName, imageUrl = contact.person.avatarUrl) },
                    trailing = { if (picked) CircasoIconImage(CircasoIcon.Check, c.action, Modifier.size(20.dp)) },
                    onClick = { viewModel.onToggle(contact.person.userId) },
                )
                RowDivider(Modifier.padding(start = 76.dp))
            }
            item {
                Row(Modifier.fillMaxWidth().padding(CircasoSpacing.s4)) {
                    CircasoButton("Create Group", { viewModel.onCreate(onCreated) }, size = ButtonSize.Large, block = true, enabled = state.canCreate, busy = state.creating)
                }
            }
        }
    }
}
