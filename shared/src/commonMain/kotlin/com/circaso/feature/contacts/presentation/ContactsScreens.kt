package com.circaso.feature.contacts.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.Person

@Composable
fun ContactsScreen(viewModel: ContactsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    ContactsContent(
        state, onBack, viewModel::onQuery, viewModel::onConnectInput, viewModel::onConnect, viewModel::onInvite, viewModel::onInviteDismissed,
        viewModel::onFindFriends, viewModel::onFindFriendsDismissed, viewModel::onAllowAccess, viewModel::onSelect, viewModel::onSelectedDismissed, viewModel::onRemoveRequested, viewModel::onBlockRequested, viewModel::onActionConfirmed, viewModel::onActionDismissed,
    )
}

/** People you know, plus ways to add someone: by @handle or with an invite link. Sharing through the system share sheet arrives with the platform pieces. */
@Composable
fun ContactsContent(
    state: ContactsUiState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onConnectInput: (String) -> Unit,
    onConnect: () -> Unit,
    onInvite: () -> Unit,
    onInviteDismissed: () -> Unit,
    onFindFriends: () -> Unit,
    onFindFriendsDismissed: () -> Unit,
    onAllowAccess: () -> Unit,
    onSelect: (Contact) -> Unit,
    onSelectedDismissed: () -> Unit,
    onRemove: (Contact) -> Unit,
    onBlock: (Contact) -> Unit,
    onActionConfirmed: () -> Unit,
    onActionDismissed: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val clipboard = LocalClipboardManager.current
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = "Contacts", onBack = onBack) },
        banner = { ConnectionBanner() },
        overlay = {
            val action = state.pendingAction
            ConfirmSheet(
                visible = action != null,
                title = when (action) { is ContactAction.Remove -> "Remove ${action.contact.person.displayName}?"; is ContactAction.Block -> "Block ${action.contact.person.displayName}?"; null -> "" },
                message = when (action) {
                    is ContactAction.Remove -> "They won't be in your contacts. A phone sync won't add them back."
                    is ContactAction.Block -> "They can't reach you, and blocking overrides every other way of reaching you. You can unblock them any time."
                    null -> ""
                },
                confirmLabel = if (action is ContactAction.Block) "Block" else "Remove",
                destructive = true,
                onConfirm = onActionConfirmed,
                onDismiss = onActionDismissed,
            )
            val selected = state.selected
            CircasoSheet(visible = selected != null, onDismiss = onSelectedDismissed, label = selected?.person?.displayName ?: "Contact") {
                BasicText(selected?.person?.displayName.orEmpty(), style = t.title2.copy(color = c.ink))
                selected?.person?.handle?.let { BasicText("@$it", style = t.body.copy(color = c.inkSecondary)) }
                CircasoButton("Block", { selected?.let(onBlock) }, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
                CircasoButton("Remove from Contacts", { selected?.let(onRemove) }, style = ButtonStyle.Destructive, size = ButtonSize.Large, block = true)
                CircasoButton("Cancel", onSelectedDismissed, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
            }
            CircasoSheet(visible = state.showFindFriends, onDismiss = onFindFriendsDismissed, label = "Find your people") {
                BasicText("See who's already on Circaso", style = t.title2.copy(color = c.ink))
                BasicText("We check your contacts' numbers against Circaso so you can send money in a tap.", style = t.body.copy(color = c.inkSecondary))
                BasicText("Only phone numbers are sent. Names never leave your phone, and the numbers are matched securely, not kept as your contact list.", style = t.footnote.copy(color = c.inkSecondary))
                CircasoButton("Allow Access", onAllowAccess, size = ButtonSize.Large, block = true)
                CircasoButton("Not Now", onFindFriendsDismissed, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
            }
            CircasoSheet(visible = state.invite != null, onDismiss = onInviteDismissed, label = "Invite a friend") {
                BasicText("Invite a friend", style = t.title2.copy(color = c.ink))
                BasicText("Anyone with this link can connect with you for 7 days.", style = t.body.copy(color = c.inkSecondary))
                BasicText(state.invite?.url.orEmpty(), style = t.subhead.copy(color = c.ink))
                CircasoButton("Copy Link", { state.invite?.url?.let { clipboard.setText(AnnotatedString(it)) } }, size = ButtonSize.Large, block = true)
                CircasoButton("Done", onInviteDismissed, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.Bottom) {
                    CircasoTextField(state.connectInput, onConnectInput, label = "Add by @handle", placeholder = "@handle", modifier = Modifier.weight(1f))
                    CircasoButton("Connect", onConnect, enabled = state.online && state.connectInput.isNotBlank(), busy = state.connecting)
                }
                CircasoButton("Invite a Friend", onInvite, style = ButtonStyle.Secondary, enabled = state.online, busy = state.creatingInvite, block = true)
                CircasoButton("Find Friends from Your Contacts", onFindFriends, style = ButtonStyle.Secondary, enabled = state.online, busy = state.syncing, block = true)
                if (!state.online) BasicText("Adding people needs a connection.", style = t.footnote.copy(color = c.inkSecondary))
                state.message?.let { BasicText(it, style = t.subhead.copy(color = c.ink)) }
            }
            if (state.contacts.isNotEmpty()) CircasoTextField(state.query, onQuery, label = "Search", placeholder = "Name or @handle")
            if (state.contacts.isEmpty()) {
                BasicText(
                    "No contacts yet. Connect with someone by @handle, or invite a friend.",
                    Modifier.fillMaxWidth().padding(vertical = CircasoSpacing.s6),
                    style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                )
            } else {
                ListGroup {
                    state.visible.forEachIndexed { index, contact ->
                        if (index > 0) RowDivider()
                        ContactRow(contact, onClick = { onSelect(contact) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    ListRow(
        title = contact.person.displayName,
        sub = contact.person.handle?.let { "@$it" },
        leading = { Avatar(contact.person.displayName, imageUrl = contact.person.avatarUrl) },
        trailing = { RowChevron() },
        onClick = onClick,
    )
}

@Composable
fun BlockedScreen(viewModel: BlockedViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    BlockedContent(state, onBack, viewModel::onUnblock)
}

@Composable
fun BlockedContent(state: BlockedUiState, onBack: () -> Unit, onUnblock: (Person) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = "Blocked people", onBack = onBack) },
        banner = { ConnectionBanner() },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
        ) {
            if (state.blocked.isEmpty()) {
                BasicText("No one is blocked.", Modifier.fillMaxWidth().padding(vertical = CircasoSpacing.s6), style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
            } else {
                ListGroup {
                    state.blocked.forEachIndexed { index, person ->
                        if (index > 0) RowDivider()
                        ListRow(
                            title = person.displayName,
                            sub = person.handle?.let { "@$it" },
                            leading = { Avatar(person.displayName, imageUrl = person.avatarUrl) },
                            trailing = { CircasoButton("Unblock", { onUnblock(person) }, style = ButtonStyle.Secondary, size = ButtonSize.Small) },
                        )
                    }
                }
            }
        }
    }
}
