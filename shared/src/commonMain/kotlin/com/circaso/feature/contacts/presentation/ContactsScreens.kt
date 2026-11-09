package com.circaso.feature.contacts.presentation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.shareText
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.ActionDisc
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.AvatarStack
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.InviteCard
import com.circaso.designsystem.components.ListCaption
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.PeopleDividerInset
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SearchPill
import com.circaso.designsystem.components.SkeletonPeopleRows
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.Person

/** What the Contacts screen can do; the defaults do nothing (previews). */
class ContactsActions(
    val onBack: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onConnectInput: (String) -> Unit = {},
    val onConnect: () -> Unit = {},
    val onAddOpen: () -> Unit = {},
    val onAddDismissed: () -> Unit = {},
    val onInvite: () -> Unit = {},
    val onFindFriends: () -> Unit = {},
    val onFindFriendsDismissed: () -> Unit = {},
    val onAllowAccess: () -> Unit = {},
    val onSelect: (Contact) -> Unit = {},
    val onSelectedDismissed: () -> Unit = {},
    val onRemove: (Contact) -> Unit = {},
    val onBlock: (Contact) -> Unit = {},
    val onActionConfirmed: () -> Unit = {},
    val onActionDismissed: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

@Composable
fun ContactsScreen(viewModel: ContactsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.share) { state.share?.let { shareText(it, com.circaso.feature.messaging.presentation.NewChatViewModel.InviteSubject); viewModel.onShared() } }
    ContactsContent(
        state,
        ContactsActions(
            onBack = onBack, onQuery = viewModel::onQuery, onConnectInput = viewModel::onConnectInput, onConnect = viewModel::onConnect,
            onAddOpen = viewModel::onAddOpen, onAddDismissed = viewModel::onAddDismissed, onInvite = viewModel::onInvite,
            onFindFriends = viewModel::onFindFriends, onFindFriendsDismissed = viewModel::onFindFriendsDismissed, onAllowAccess = viewModel::onAllowAccess,
            onSelect = viewModel::onSelect, onSelectedDismissed = viewModel::onSelectedDismissed, onRemove = viewModel::onRemoveRequested,
            onBlock = viewModel::onBlockRequested, onActionConfirmed = viewModel::onActionConfirmed, onActionDismissed = viewModel::onActionDismissed,
            onMessageShown = viewModel::onMessageShown,
        ),
    )
}

/**
 * Design A9 and the people around it: an invite card with the Circaso mark, the two ways to add someone (by
 * @username, or matching the phone's contacts) as an inset list, then everyone in letter sections. Inviting goes
 * straight to the system share sheet. Adding and inviting need a connection; the list is read from the phone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsContent(state: ContactsUiState, actions: ContactsActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    LaunchedEffect(state.message) { state.message?.let { toast.show(it); actions.onMessageShown() } }
    CircasoScaffold(
        grouped = true,
        topBar = {
            CircasoNavBar(title = "Contacts", onBack = actions.onBack, trailing = {
                CircasoIconButton(CircasoIcon.UserPlus, "Add by username", actions.onAddOpen, enabled = state.online)
            })
        },
        banner = { ConnectionBanner(showing = "Adding people needs a connection") },
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
        overlay = { ContactsSheets(state, actions) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2, bottom = padding.calculateBottomPadding() + CircasoSpacing.s8),
        ) {
            if (state.contacts.size > 6) {
                item(key = "search") { SearchPill(state.query, actions.onQuery, "Search contacts", Modifier.padding(bottom = CircasoSpacing.s3)) }
            }
            if (state.query.isBlank()) {
                item(key = "invite") {
                    InviteCard(
                        "Invite friends to Circaso", "They join with your link and you're connected, ready to chat and pay.",
                        "Invite", actions.onInvite, enabled = state.online, busy = state.creatingInvite,
                    )
                }
                item(key = "add") {
                    Column(Modifier.padding(top = CircasoSpacing.s5)) {
                        ListGroup {
                            ListRow(
                                "Add by Username", sub = "Connect with anyone on Circaso",
                                leading = { ActionDisc(CircasoIcon.UserPlus, enabled = state.online) }, trailing = { RowChevron() },
                                onClick = if (state.online) actions.onAddOpen else null,
                            )
                            RowDivider(Modifier.padding(start = PeopleDividerInset - CircasoSpacing.s4))
                            ListRow(
                                "Find Friends from Contacts", sub = if (state.syncing) "Matching numbers…" else "Only phone numbers are matched",
                                leading = { ActionDisc(CircasoIcon.People, enabled = state.online) },
                                trailing = { if (state.syncing) Spinner(c.inkSecondary, Modifier.size(18.dp)) else RowChevron() },
                                onClick = if (state.online && !state.syncing) actions.onFindFriends else null,
                            )
                        }
                    }
                }
            }
            when {
                !state.loaded -> item(key = "loading") { Column(Modifier.padding(top = CircasoSpacing.s5)) { ListGroup { SkeletonPeopleRows(5) } } }
                state.contacts.isEmpty() -> item(key = "empty") { NoContactsYet() }
                state.visible.isEmpty() -> item(key = "no-match") {
                    BasicText(
                        "No one matches “${state.query.trim()}”.", Modifier.fillMaxWidth().padding(vertical = CircasoSpacing.s8),
                        style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                    )
                }
                else -> {
                    if (state.query.isBlank()) item(key = "count") {
                        ListCaption("${state.contacts.size} ${if (state.contacts.size == 1) "contact" else "contacts"}", Modifier.padding(top = CircasoSpacing.s2))
                    }
                    state.sections.forEach { (letter, people) ->
                        item(key = "sec-$letter") {
                            BasicText(
                                letter, Modifier.padding(start = CircasoSpacing.s4, top = CircasoSpacing.s3, bottom = 6.dp),
                                style = t.footnote.copy(color = c.inkSecondary, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                            )
                        }
                        // One lazy item per person (a real phone book scrolls smoothly); the inset group's corners are
                        // drawn on the first and last row of each letter.
                        val last = people.lastIndex
                        items(people.size, key = { "c-" + people[it].person.userId }) { i ->
                            val contact = people[i]
                            val r = GroupCorner
                            val shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                topStart = if (i == 0) r else 0.dp, topEnd = if (i == 0) r else 0.dp,
                                bottomStart = if (i == last) r else 0.dp, bottomEnd = if (i == last) r else 0.dp,
                            )
                            Column(
                                Modifier.animateItem(
                                    fadeInSpec = androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.BaseMs),
                                    fadeOutSpec = androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.QuickMs),
                                    placementSpec = if (CircasoTheme.reduceMotion) null else androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.BaseMs),
                                ).clip(shape).background(c.surface),
                            ) {
                                if (i > 0) RowDivider(Modifier.padding(start = PeopleDividerInset - CircasoSpacing.s4))
                                ContactRow(contact, onClick = { actions.onSelect(contact) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Nobody yet: the A9 promise in a few words, under faces that stand for the people to come. */
@Composable
private fun NoContactsYet() {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s6, vertical = CircasoSpacing.s10),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        AvatarStack(listOf("A" to null, "O" to null, "W" to null, "J" to null), size = CircasoSizes.avatarMd)
        BasicText("Find your people", style = t.title3.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText("Add friends by @username, see who from your phone is here, or send an invite.", style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    val p = contact.person
    ListRow(
        title = p.displayName,
        sub = p.handle?.let { "@$it" } ?: p.about?.takeIf { it.isNotBlank() },
        leading = { Avatar(p.displayName, imageUrl = p.avatarUrl) },
        trailing = { RowChevron() },
        onClick = onClick,
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ContactsSheets(state: ContactsUiState, actions: ContactsActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
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
        onConfirm = actions.onActionConfirmed,
        onDismiss = actions.onActionDismissed,
    )
    // A contact: their face and name first, then what can be done, as an inset list (not a stack of buttons).
    val selected = state.selected
    CircasoSheet(visible = selected != null, onDismiss = actions.onSelectedDismissed, label = selected?.person?.displayName ?: "Contact") {
        if (selected != null) {
            val p = selected.person
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                Avatar(p.displayName, size = CircasoSizes.avatarLg + 8.dp, imageUrl = p.avatarUrl)
                BasicText(p.displayName, Modifier.padding(top = CircasoSpacing.s2), style = t.title3.copy(color = c.ink, textAlign = TextAlign.Center))
                p.handle?.let { BasicText("@$it", style = t.subhead.copy(color = c.inkSecondary)) }
                p.about?.takeIf { it.isNotBlank() }?.let { BasicText(it, style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center)) }
            }
            Box(Modifier.clip(CircasoShapes.lg).background(c.fill)) {
                Column {
                    ListRow("Block", leading = { CircasoIconImage(CircasoIcon.Ban, c.ink, Modifier.size(20.dp)) }, onClick = { actions.onBlock(selected) })
                    RowDivider()
                    ListRow("Remove from Contacts", destructive = true, leading = { CircasoIconImage(CircasoIcon.UserMinus, c.critical, Modifier.size(20.dp)) }, onClick = { actions.onRemove(selected) })
                }
            }
            CircasoButton("Done", actions.onSelectedDismissed, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
        }
    }
    // A9: the explainer before the system prompt.
    CircasoSheet(visible = state.showFindFriends, onDismiss = actions.onFindFriendsDismissed, label = "Find your people") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            AvatarStack(state.contacts.take(4).map { it.person.displayName to it.person.avatarUrl }.ifEmpty { listOf("A" to null, "O" to null, "W" to null, "J" to null) })
            BasicText("See who's already on Circaso", style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
            BasicText("We check your contacts' numbers against Circaso so you can send money in a tap.", style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        }
        Row(
            Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.fill).padding(horizontal = CircasoSpacing.s4, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        ) {
            CircasoIconImage(CircasoIcon.Lock, c.inkSecondary, Modifier.padding(top = 1.dp).size(20.dp), strokeWidth = 1.8f)
            BasicText("Numbers are matched securely and never stored as your contact list. Names never leave your phone.", style = t.subhead.copy(color = c.inkSecondary))
        }
        CircasoButton("Allow Access", actions.onAllowAccess, size = ButtonSize.Large, block = true)
        CircasoButton("Not Now", actions.onFindFriendsDismissed, style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
    }
    // Add by Username: the @ is part of the field, the button wakes once it looks like a username.
    CircasoSheet(visible = state.adding, onDismiss = actions.onAddDismissed, label = "Add by username") {
        val focus = remember { FocusRequester() }
        LaunchedEffect(state.adding) { if (state.adding) runCatching { focus.requestFocus() } }
        BasicText("Add by Username", style = t.title2.copy(color = c.ink))
        BasicText("Type their Circaso @username. You'll be in each other's contacts.", style = t.subhead.copy(color = c.inkSecondary))
        Row(
            Modifier.fillMaxWidth().heightIn(min = CircasoSizes.controlLg).clip(CircasoShapes.md).background(c.fill).padding(horizontal = CircasoSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("@", style = t.body.copy(color = c.inkSecondary))
            Box(Modifier.weight(1f).padding(start = 2.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = state.connectInput.removePrefix("@"), onValueChange = com.circaso.designsystem.components.ashaAware(state.connectInput.removePrefix("@"), actions.onConnectInput), singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (state.connectReady && state.online) actions.onConnect() }),
                    textStyle = t.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Username" },
                )
                if (state.connectInput.removePrefix("@").isEmpty()) BasicText("username", style = t.body.copy(color = c.inkTertiary))
            }
        }
        CircasoButton("Add", actions.onConnect, size = ButtonSize.Large, block = true, enabled = state.online && state.connectReady, busy = state.connecting)
        if (!state.online) BasicText("Adding people needs a connection.", style = t.footnote.copy(color = c.inkSecondary))
        CircasoButton("Cancel", actions.onAddDismissed, style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
    }
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
                        if (index > 0) RowDivider(Modifier.padding(start = PeopleDividerInset - CircasoSpacing.s4))
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

/** The inset group's corner (ListGroup draws CircasoShapes.lg, radius-lg); rows drawn one by one need it as a size. */
private val GroupCorner = 22.dp
