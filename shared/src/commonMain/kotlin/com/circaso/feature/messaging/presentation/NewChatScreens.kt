package com.circaso.feature.messaging.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.circaso.designsystem.components.CircasoSwitch
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

/**
 * Designs D1 and GW1–GW1d: a name, the people and, with group wallets, the "Group wallet" switch (off by default) with
 * an optional target and who approves payouts. Creating is online-only: offline the switch and the button are
 * disabled before the tap, with the reason (GW1c); a failure keeps the form and its key (GW1d).
 */
@Composable
fun NewGroupScreen(viewModel: NewGroupViewModel, onBack: () -> Unit, onCreated: (chatId: String, plan: com.circaso.feature.chama.presentation.GroupWalletPlan?) -> Unit) {
    val state by viewModel.state.collectAsState()
    NewGroupContent(
        state, onBack, viewModel::onName, viewModel::onQuery, viewModel::onToggle, viewModel::onWalletSwitch, viewModel::onTarget, viewModel::onBy, viewModel::onApprovalPolicy,
        onCreate = { viewModel.onCreate { id -> onCreated(id, viewModel.walletPlan()) } },
    )
}

@Composable
fun NewGroupContent(
    state: NewGroupUiState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onQuery: (String) -> Unit,
    onToggle: (String) -> Unit,
    onWalletSwitch: (Boolean) -> Unit,
    onTarget: (String) -> Unit,
    onBy: (com.circaso.feature.chama.presentation.GoalDeadline) -> Unit,
    onApprovalPolicy: (com.circaso.feature.chama.domain.ApprovalPolicy) -> Unit,
    onCreate: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = "New Group", onBack = onBack) },
        banner = { ConnectionBanner(showing = if (state.walletOn) "Creating a group and its wallet needs a connection" else "Creating a group needs a connection") },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(c.bgGrouped).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                CircasoButton(
                    when { state.failed -> "Try Again"; state.walletOn -> "Create Group and Set Up Wallet"; else -> "Create Group" },
                    onCreate, size = ButtonSize.Large, block = true, enabled = state.canCreate, busy = state.creating,
                )
                if (!state.online) BasicText("Needs a connection", style = t.footnote.copy(color = c.inkSecondary))
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4)) {
            item {
                Column(Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
                    if (state.failed) {
                        com.circaso.designsystem.chrome.Banner(
                            "The group wasn’t created. Nothing was saved." + (state.reference?.let { " Ref $it" } ?: ""),
                            CircasoIcon.Warning, tone = com.circaso.designsystem.chrome.BannerTone.Critical,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Avatar(state.name.ifBlank { "Group" }, size = CircasoSizes.avatarLg)
                        CircasoTextField(value = state.name, onValueChange = onName, label = "Group name", placeholder = "Kilimani Chama", modifier = Modifier.weight(1f))
                    }
                    val n = state.selected.size
                    BasicText(if (n == 0) "Add members" else "$n ${if (n == 1) "member" else "members"} + you", style = t.footnote.copy(color = c.inkSecondary))
                    if (state.selectedContacts.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                            items(state.selectedContacts, key = { it.person.userId }) { contact ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).pressable(role = Role.Button, onClick = { onToggle(contact.person.userId) })) {
                                    Avatar(contact.person.displayName, imageUrl = contact.person.avatarUrl)
                                    BasicText(contact.person.displayName.substringBefore(' '), style = t.caption1.copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (state.walletsAvailable) GroupWalletCard(state, onWalletSwitch, onTarget, onBy, onApprovalPolicy)
                    CircasoTextField(
                        value = state.query, onValueChange = onQuery, label = "Search", placeholder = "Search contacts",
                        leading = { CircasoIconImage(CircasoIcon.Search, c.inkTertiary, Modifier.size(18.dp)) },
                    )
                    state.error?.takeIf { !state.failed }?.let { BasicText(it, style = t.footnote.copy(color = c.critical)) }
                }
            }
            items(state.shownContacts, key = { it.person.userId }) { contact ->
                val picked = contact.person.userId in state.selected
                ListRow(
                    title = contact.person.displayName,
                    sub = contact.person.handle?.let { "@$it" },
                    leading = { Avatar(contact.person.displayName, imageUrl = contact.person.avatarUrl) },
                    trailing = { if (picked) CircasoIconImage(CircasoIcon.Check, c.action, Modifier.size(20.dp)) },
                    onClick = { onToggle(contact.person.userId) },
                )
                RowDivider(Modifier.padding(start = 76.dp))
            }
        }
    }
}

/**
 * GW1's card: the switch, and when it is on the optional target, who approves payouts, and the trust line that says
 * plainly who can move the money and after whose approval. (The daily cap line joins it with phase 2F.)
 */
@Composable
private fun GroupWalletCard(
    state: NewGroupUiState,
    onWalletSwitch: (Boolean) -> Unit,
    onTarget: (String) -> Unit,
    onBy: (com.circaso.feature.chama.presentation.GoalDeadline) -> Unit,
    onApprovalPolicy: (com.circaso.feature.chama.domain.ApprovalPolicy) -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.surface).border(1.dp, c.separator, CircasoShapes.md)
            .padding(horizontal = CircasoSpacing.s4, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            Column(Modifier.weight(1f)) {
                BasicText("Group wallet", style = t.headline.copy(color = c.ink))
                BasicText(
                    if (state.walletOn) "Members pay into it. It’s separate from everyone’s personal money, with its own PIN."
                    else "For chamas, savings and small businesses. You can add it later from group info.",
                    style = t.footnote.copy(color = c.inkSecondary),
                )
            }
            CircasoSwitch(state.walletOn, onWalletSwitch, enabled = state.online && !state.creating, modifier = Modifier.semantics { contentDescription = "Group wallet" })
        }
        androidx.compose.animation.AnimatedVisibility(state.walletOn) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CircasoTextField(
                    value = state.targetText, onValueChange = onTarget, label = "Target (optional)",
                    placeholder = if (state.fiat != null) "50,000" else "400",
                    leading = { BasicText(state.fiat?.currency ?: "USDC", style = t.body.copy(color = c.inkSecondary)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
                if (state.targetText.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText("By (optional)", style = t.footnote.copy(color = c.inkSecondary))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                            items(com.circaso.feature.chama.presentation.GoalDeadline.entries) { d ->
                                com.circaso.designsystem.components.SuggestionChip(d.label, { onBy(d) }, selected = d == state.by)
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText("Who approves payouts", style = t.footnote.copy(color = c.inkSecondary))
                    val policies = com.circaso.feature.chama.domain.ApprovalPolicy.entries
                    com.circaso.designsystem.components.SegmentedControl(listOf("2 admins", "Any admin"), policies.indexOf(state.approvalPolicy), { onApprovalPolicy(policies[it]) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircasoIconImage(CircasoIcon.Lock, c.inkSecondary, Modifier.padding(top = 2.dp).size(14.dp))
                    BasicText(
                        if (state.approvalPolicy == com.circaso.feature.chama.domain.ApprovalPolicy.AnyAdmin)
                            "Only you can send from this wallet, and only after an admin approves each payout (an admin’s own request counts). Everyone sees every payment."
                        else "Only you can send from this wallet, and only after 2 admins approve each payout (the admin who asks counts as one). Everyone sees every payment.",
                        style = t.footnote.copy(color = c.inkSecondary),
                    )
                }
            }
        }
    }
}
