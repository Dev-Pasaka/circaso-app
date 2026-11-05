package com.circaso.feature.spaces.presentation.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.circaso.core.navigation.SpaceMembersRoute
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.MemberFilter
import com.circaso.feature.spaces.domain.MuteDuration
import com.circaso.feature.spaces.domain.SpaceMember
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.presentation.DimBehind
import com.circaso.feature.spaces.presentation.PopoverConfirm
import com.circaso.feature.spaces.presentation.PopoverHead
import com.circaso.feature.spaces.presentation.PopoverItem
import com.circaso.feature.spaces.presentation.PopoverNote
import com.circaso.feature.spaces.presentation.SpacePopover
import com.circaso.feature.spaces.presentation.SpacesNav
import com.circaso.feature.spaces.presentation.countText
import com.circaso.feature.spaces.presentation.shortDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

class SpaceMembersActions(
    val onBack: () -> Unit = {},
    val onInvite: (() -> Unit)? = null,
    val onRoles: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onFilter: (MemberFilter) -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenMenu: (SpaceMember) -> Unit = {},
    val onDismissMenu: () -> Unit = {},
    val onMenuMode: (MemberMenuMode) -> Unit = {},
    val onMessage: (SpaceMember) -> Unit = {},
    val onRole: (SpaceMember, SpaceRole) -> Unit = { _, _ -> },
    val onMute: (SpaceMember, MuteDuration) -> Unit = { _, _ -> },
    val onUnmute: (SpaceMember) -> Unit = {},
    val onRemove: (SpaceMember) -> Unit = {},
    val onBan: (SpaceMember) -> Unit = {},
    val onUnban: (SpaceMember) -> Unit = {},
)

@Composable
fun SpaceMembersEntry(route: SpaceMembersRoute, nav: SpacesNav) {
    val vm = koinViewModel<SpaceMembersViewModel>(key = "space-members-" + route.spaceId, parameters = { parametersOf(route.spaceId) })
    val state by vm.state.collectAsState()
    val toast = rememberToastState()
    LaunchedEffect(vm) {
        vm.events.collect { e -> when (e) { is SpaceMembersEvent.OpenChat -> nav.openChat(e.chatId); is SpaceMembersEvent.Toast -> toast.show(e.text) } }
    }
    SpaceMembersContent(
        state,
        SpaceMembersActions(
            onBack = nav.back, onInvite = if (state.myRole?.isAdmin == true) ({ nav.invite(route.spaceId) }) else null, onRoles = nav.roles,
            onQuery = vm::onQuery, onFilter = vm::onFilter, onLoadMore = vm::onLoadMore, onRetry = vm::load,
            onOpenMenu = vm::onOpenMenu, onDismissMenu = vm::onDismissMenu, onMenuMode = vm::onMenuMode, onMessage = vm::onMessage,
            onRole = vm::onRole, onMute = vm::onMute, onUnmute = vm::onUnmute, onRemove = vm::onRemove, onBan = vm::onBan, onUnban = vm::onUnban,
        ),
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
    )
}

/** SR1: Space Settings → Members and roles. The team first with their roles, then members; each row opens its popover. */
@Composable
fun SpaceMembersContent(state: SpaceMembersUiState, actions: SpaceMembersActions, toast: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        grouped = true,
        topBar = {
            CircasoNavBar("Members", actions.onBack, trailing = {
                if (actions.onInvite != null) CircasoIconButton(CircasoIcon.UserPlus, "Invite people", actions.onInvite, enabled = state.online)
            })
        },
        banner = { if (!state.online) Banner("You’re offline. Changing members needs a connection.", CircasoIcon.Offline) },
        toast = toast,
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + CircasoSpacing.s6)) {
                item { SpaceSearchField(state.query, actions.onQuery, "Search members") }
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                        FilterChip("All", state.filter == MemberFilter.All) { actions.onFilter(MemberFilter.All) }
                        FilterChip("Muted", state.filter == MemberFilter.Muted) { actions.onFilter(MemberFilter.Muted) }
                        // Banned people are not members; only admins see them (SP23).
                        if (state.myRole?.isAdmin == true) FilterChip("Banned", state.filter == MemberFilter.Banned) { actions.onFilter(MemberFilter.Banned) }
                    }
                }
                val error = state.error
                when {
                    state.loading && state.team.isEmpty() && state.members.isEmpty() -> items(5) { SkeletonPerson() }
                    error != null -> item { Box(Modifier.padding(top = 80.dp)) { ErrorState("Members didn’t load", error, state.errorRef, actions.onRetry, enabled = state.online) } }
                    else -> {
                        if (state.filter == MemberFilter.All && state.team.isNotEmpty() && state.query.isBlank()) {
                            item { SectionLabel("Team", Modifier.padding(start = CircasoSpacing.s4, bottom = 6.dp)) }
                            item { MembersGroup(state.team, state, actions) }
                        }
                        val label = when (state.filter) {
                            MemberFilter.All -> "Members · ${countText(state.count, exact = true)}"
                            MemberFilter.Muted -> "Muted"
                            MemberFilter.Banned -> "Banned"
                        }
                        item { SectionLabel(label, Modifier.padding(start = CircasoSpacing.s4, top = 14.dp, bottom = 6.dp)) }
                        if (state.members.isEmpty()) item {
                            BasicText(
                                when { state.query.isNotBlank() -> "Nobody matches “${state.query}”."; state.filter == MemberFilter.Muted -> "Nobody is muted."; state.filter == MemberFilter.Banned -> "Nobody is banned."; else -> "No members yet." },
                                Modifier.padding(horizontal = CircasoSpacing.s5, vertical = CircasoSpacing.s2), style = t.subhead.copy(color = c.inkSecondary),
                            )
                        } else item { MembersGroup(state.members, state, actions) }
                        if (state.next != null) item { LaunchedEffect(state.members.size) { actions.onLoadMore() }; SkeletonPerson() }
                    }
                }
            }
            DimBehind(state.menu != null)
        }
    }
}

@Composable
private fun MembersGroup(list: List<SpaceMember>, state: SpaceMembersUiState, actions: SpaceMembersActions) {
    GroupedList(Modifier.padding(horizontal = CircasoSpacing.s4)) {
        list.forEachIndexed { i, m ->
            if (i > 0) RowDivider(Modifier.padding(start = 72.dp))
            MemberRow(m, state, actions)
        }
    }
}

/** A member's row and its popover (SR1b), anchored to the row's more button. */
@Composable
private fun MemberRow(m: SpaceMember, state: SpaceMembersUiState, actions: SpaceMembersActions) {
    val c = CircasoTheme.colors
    val sub = listOfNotNull(
        m.person.handle?.let { "@$it" },
        m.mutedUntil?.takeIf { it > state.now }?.let { "muted " + mutedUntilText(it, state.now).removePrefix("until ").let { w -> if (w.startsWith("an admin")) "until unmuted" else "until $w" } },
        m.bannedAt?.let { "banned on ${shortDate(it)}" },
    ).joinToString(" · ").ifEmpty { null }
    val hasActions = !state.isMe(m) && m.role != SpaceRole.Owner
    PersonRow(m.person, sub, m.role, onClick = if (hasActions) ({ actions.onOpenMenu(m) }) else null) {
        if (hasActions) Box {
            CircasoIconButton(CircasoIcon.More, "Actions for ${m.person.displayName}", { actions.onOpenMenu(m) })
            MemberPopover(m, state, actions)
        }
    }
}

@Composable
private fun MemberPopover(m: SpaceMember, state: SpaceMembersUiState, actions: SpaceMembersActions) {
    val open = state.menu?.userId == m.person.userId
    val first = m.person.firstName
    SpacePopover(open, actions.onDismissMenu, label = "${m.person.displayName}, actions", alignEnd = true, width = 260.dp) {
        when (state.menu?.mode ?: MemberMenuMode.Items) {
            MemberMenuMode.ConfirmRemove -> PopoverConfirm(
                "Remove $first?",
                "$first leaves the space and can follow it again. Their posts stay." +
                    if (state.paidSpace) " If they paid for this period, removing doesn’t refund it. You can pay them back from your private chat." else "",
                "Remove", onConfirm = { actions.onRemove(m) }, onCancel = actions.onDismissMenu, busy = state.busy, enabled = state.online,
            )
            MemberMenuMode.ConfirmBan -> PopoverConfirm(
                "Ban $first?",
                "$first leaves and can’t follow, join, ask, be invited or message the owner. Their posts stay. They aren’t told who did it.",
                "Ban", onConfirm = { actions.onBan(m) }, onCancel = actions.onDismissMenu, busy = state.busy, enabled = state.online,
            )
            MemberMenuMode.Mute -> {
                PopoverHead("Mute $first for")
                MuteDuration.entries.forEach { d -> PopoverItem(d.label, { actions.onMute(m, d) }, enabled = state.online && !state.busy) }
            }
            MemberMenuMode.Items -> {
                PopoverHead("${m.person.displayName} · ${(m.role ?: SpaceRole.Member).label.lowercase()}")
                if (m.bannedAt == null) PopoverItem("Message $first", { actions.onMessage(m) }, enabled = state.online)
                state.rolesFor(m).forEach { r ->
                    val text = when (r) { SpaceRole.Admin -> "Make Admin"; SpaceRole.Moderator -> "Make Moderator"; else -> "Remove ${(m.role ?: SpaceRole.Member).label} Role" }
                    PopoverItem(text, { actions.onRole(m, r) }, icon = CircasoIcon.Shield, enabled = state.online && !state.busy)
                }
                val muted = m.mutedUntil?.let { it > state.now } == true
                val canMute = state.canMute(m)
                val canRemove = state.canRemove(m)
                if (canMute && muted) PopoverItem("Unmute", { actions.onUnmute(m) }, icon = CircasoIcon.Mute, enabled = state.online, divider = true)
                else if (canMute) PopoverItem("Mute…", { actions.onMenuMode(MemberMenuMode.Mute) }, icon = CircasoIcon.Mute, enabled = state.online, divider = true)
                if (canRemove) {
                    PopoverItem("Remove from Space", { actions.onMenuMode(MemberMenuMode.ConfirmRemove) }, icon = CircasoIcon.UserMinus, critical = true, enabled = state.online, divider = !canMute)
                    PopoverItem("Ban", { actions.onMenuMode(MemberMenuMode.ConfirmBan) }, icon = CircasoIcon.Ban, critical = true, enabled = state.online)
                }
                if (state.canUnban(m)) PopoverItem("Unban", { actions.onUnban(m) }, icon = CircasoIcon.Ban, enabled = state.online, divider = true)
                state.limitedNote?.let { PopoverNote(it) }
                if (!state.online) PopoverNote("Needs a connection.")
            }
        }
    }
}

@Composable
internal fun SkeletonPerson() {
    Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s8, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.CenterVertically) {
        com.circaso.feature.spaces.presentation.SkeletonBlock(44.dp, 44.dp, shape = com.circaso.designsystem.theme.CircasoShapes.circle)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            com.circaso.feature.spaces.presentation.SkeletonBlock(140.dp, 14.dp)
            com.circaso.feature.spaces.presentation.SkeletonBlock(90.dp, 12.dp)
        }
    }
}

/** SR2: what each role can do, each adding to the one below it. */
@Composable
fun SpaceRolesContent(onBack: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(grouped = true, topBar = { CircasoNavBar("Roles", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2, bottom = padding.calculateBottomPadding() + CircasoSpacing.s6), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            items(RoleLists) { (title, badge, lines) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel(title)
                        badge?.let { com.circaso.feature.spaces.presentation.RoleBadge(it) }
                    }
                    GroupedList {
                        Column(Modifier.padding(horizontal = CircasoSpacing.s4, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            lines.forEach { line ->
                                Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                                    CircasoIconImage(CircasoIcon.Check, c.ink, Modifier.padding(top = 3.dp).size(15.dp), strokeWidth = 2.4f)
                                    BasicText(line, style = t.subhead.copy(color = c.ink))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val RoleLists = listOf(
    Triple("Owner", "One per space", listOf("Everything admins can do", "Send money from the space wallet, with its PIN", "Price and who can join, appoint admins", "Delete the space")),
    Triple("Admins", null, listOf("Everything moderators can do", "Remove and ban members, invite, accept requests", "Edit details and who can post", "See the space wallet (not send)")),
    Triple("Moderators", null, listOf("Remove posts, mute members", "Handle reports")),
    Triple("Members", null, listOf("Post (if everyone can), react, report", "Message the owner privately, pay the space")),
)

@Composable
fun SpaceRolesEntry(nav: SpacesNav) = SpaceRolesContent(nav.back)

