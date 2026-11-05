package com.circaso.feature.spaces.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.circaso.core.navigation.SpaceInviteRoute
import com.circaso.core.navigation.SpaceInvitesRoute
import com.circaso.core.platform.chatListTime
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.InviteState
import com.circaso.feature.spaces.domain.JoinRequest
import com.circaso.feature.spaces.domain.SentInvite
import com.circaso.feature.spaces.domain.SpacePerson
import com.circaso.feature.spaces.presentation.SpacesNav
import com.circaso.feature.spaces.presentation.shortDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

class SpaceInviteActions(
    val onClose: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onInvite: (SpacePerson) -> Unit = {},
    val onShareLink: () -> Unit = {},
)

@Composable
fun SpaceInviteEntry(route: SpaceInviteRoute, nav: SpacesNav) {
    val vm = koinViewModel<SpaceInviteViewModel>(key = "space-invite-" + route.spaceId, parameters = { parametersOf(route.spaceId) })
    val state by vm.state.collectAsState()
    val toast = rememberToastState()
    LaunchedEffect(vm) { vm.toasts.collect { toast.show(it) } }
    SpaceInviteContent(
        state,
        SpaceInviteActions(nav.back, vm::onQuery, vm::onInvite, onShareLink = {
            state.space?.let { s -> com.circaso.core.platform.shareText("Ask to join ${s.name} on Circaso: https://${s.summary.link}", s.name) }
        }),
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
    )
}

/** SI1, SI1b, SI1c: invite from contacts or by @username; each row invites in place and turns to Invited. */
@Composable
fun SpaceInviteContent(state: SpaceInviteUiState, actions: SpaceInviteActions, toast: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        topBar = { CircasoNavBar("Invite to ${state.space?.name ?: "the space"}", actions.onClose) },
        banner = { if (!state.online) Banner("You’re offline. Inviting needs a connection.", CircasoIcon.Offline) },
        toast = toast,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + CircasoSpacing.s6)) {
            item { SpaceSearchField(state.query, actions.onQuery, "Name or @username") }
            if (state.query.isBlank()) item {
                Box(Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2)) {
                    GroupedList(Modifier.background(c.surface)) {
                        ListRow(
                            "Share Link", sub = "People who open it can ask to join",
                            leading = { IconDisc(CircasoIcon.Link) }, trailing = { RowChevron() }, onClick = actions.onShareLink,
                        )
                    }
                }
            }
            val handle = state.handleQuery
            if (handle != null) {
                when {
                    state.searching -> item { BasicText("Looking for @$handle…", Modifier.padding(CircasoSpacing.s5), style = t.subhead.copy(color = c.inkSecondary)) }
                    state.found != null -> {
                        item { SectionLabel("On Circaso", Modifier.padding(start = CircasoSpacing.s5, top = CircasoSpacing.s2)) }
                        item { InviteRowItem(state.found, state) { actions.onInvite(state.found) } }
                    }
                    state.notFound -> item {
                        Box(Modifier.padding(top = 40.dp)) {
                            CenterState(CircasoIcon.Search, "No one is @$handle on Circaso", "Check the spelling, or invite them from your contacts. People join Circaso with their phone number, then choose a username.")
                        }
                    }
                    !state.online -> item { BasicText("Looking up a username needs a connection.", Modifier.padding(CircasoSpacing.s5), style = t.subhead.copy(color = c.inkSecondary)) }
                }
            }
            val shown = state.shown.filter { it.userId != state.found?.userId }
            if (shown.isNotEmpty()) {
                item { SectionLabel(if (handle != null) "Close matches" else "Your contacts", Modifier.padding(start = CircasoSpacing.s5, top = 14.dp, bottom = 4.dp)) }
                items(shown, key = { it.userId }) { p -> InviteRowItem(p, state) { actions.onInvite(p) } }
            } else if (handle == null && state.query.isNotBlank()) item {
                BasicText("No contacts match “${state.query}”. Type @ and their username to find them.", Modifier.padding(CircasoSpacing.s5), style = t.subhead.copy(color = c.inkSecondary))
            }
        }
    }
}

@Composable
private fun InviteRowItem(p: SpacePerson, state: SpaceInviteUiState, onInvite: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    PersonRow(p, p.handle?.let { "@$it" }) {
        when (state.row(p.userId)) {
            InviteRow.Invite, InviteRow.Busy -> CircasoButton("Invite", onInvite, style = ButtonStyle.Secondary, size = ButtonSize.Small, enabled = state.online, busy = state.row(p.userId) == InviteRow.Busy)
            InviteRow.Invited -> BasicText("Invited", style = t.subhead.copy(color = c.inkSecondary))
            InviteRow.Member -> BasicText("Member", style = t.subhead.copy(color = c.inkSecondary))
            InviteRow.Unreachable -> BasicText("Can’t invite", style = t.subhead.copy(color = c.inkSecondary))
        }
    }
}

@Composable
private fun IconDisc(icon: CircasoIcon) {
    Box(Modifier.size(CircasoSizes.iconButton).clip(CircasoShapes.circle).background(CircasoTheme.colors.fill), contentAlignment = Alignment.Center) {
        CircasoIconImage(icon, CircasoTheme.colors.ink, Modifier.size(18.dp), strokeWidth = 1.8f)
    }
}

// ---------------------------------------------------------------- SI4

class SpaceInvitesActions(
    val onBack: () -> Unit = {},
    val onInvitePeople: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onAnswer: (JoinRequest, Boolean) -> Unit = { _, _ -> },
    val onCancel: (SentInvite) -> Unit = {},
    val onInviteAgain: (SentInvite) -> Unit = {},
)

@Composable
fun SpaceInvitesEntry(route: SpaceInvitesRoute, nav: SpacesNav) {
    val vm = koinViewModel<SpaceInvitesViewModel>(key = "space-invites-" + route.spaceId, parameters = { parametersOf(route.spaceId) })
    val state by vm.state.collectAsState()
    val toast = rememberToastState()
    LaunchedEffect(vm) { vm.toasts.collect { toast.show(it) } }
    SpaceInvitesContent(
        state, SpaceInvitesActions(nav.back, { nav.invite(route.spaceId) }, vm::load, vm::onAnswer, vm::onCancel, vm::onInviteAgain),
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
    )
}

/** SI4 and SI4b: requests first (Accept and Decline in place), then invites sent: Joined, Invited (days left), Expired. */
@Composable
fun SpaceInvitesContent(state: SpaceInvitesUiState, actions: SpaceInvitesActions, toast: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}, now: Long = com.circaso.core.sync.nowMillis()) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        grouped = true,
        topBar = {
            CircasoNavBar("Invites and Requests", actions.onBack, trailing = {
                if (!state.empty) CircasoIconButton(CircasoIcon.UserPlus, "Invite people", actions.onInvitePeople)
            })
        },
        banner = { if (!state.online && !state.loading) Banner("You’re offline. Answering requests needs a connection.", CircasoIcon.Offline) },
        toast = toast,
    ) { padding ->
        val error = state.error
        when {
            state.loading -> Column(Modifier.padding(top = CircasoSpacing.s4)) { repeat(4) { SkeletonPerson() } }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorState("Invites didn’t load", error, state.errorRef, actions.onRetry, enabled = state.online) }
            state.empty -> Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()), contentAlignment = Alignment.Center) {
                CenterState(CircasoIcon.Mail, "No invites or requests yet", "Invite people from your contacts or by @username, or share the link so they can ask.") {
                    CircasoButton("Invite People", actions.onInvitePeople, icon = CircasoIcon.UserPlus)
                }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2, bottom = padding.calculateBottomPadding() + CircasoSpacing.s6),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.requests.isNotEmpty()) {
                    item { SectionLabel("Asking to join") }
                    item {
                        GroupedList {
                            state.requests.forEachIndexed { i, r ->
                                if (i > 0) RowDivider(Modifier.padding(start = 72.dp))
                                val busy = r.user.userId in state.busy
                                PersonRow(r.user, "Asked to join · ${chatListTime(r.requestedAt, now).let { w -> if (w.contains(':')) "today, $w" else w.lowercase() }}") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        CircasoButton("Decline", { actions.onAnswer(r, false) }, style = ButtonStyle.Secondary, size = ButtonSize.Small, enabled = state.online && !busy)
                                        CircasoButton("Accept", { actions.onAnswer(r, true) }, size = ButtonSize.Small, enabled = state.online, busy = busy)
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.invites.isNotEmpty()) {
                    item { SectionLabel("Invited", Modifier.padding(top = 10.dp)) }
                    item {
                        GroupedList {
                            state.invites.forEachIndexed { i, inv ->
                                if (i > 0) RowDivider(Modifier.padding(start = 72.dp))
                                val busy = inv.inviteId in state.busy
                                val sub = when (inv.state) {
                                    InviteState.Joined -> "Joined " + (inv.joinedAt?.let { chatListTime(it, now).let { w -> if (w.contains(':')) "today" else w.lowercase().let { d -> if (d == "yesterday") d else "on $d" } } } ?: "")
                                    InviteState.Invited -> "Invited · " + (inv.expiresAt?.let { daysLeft(it, now) } ?: "waiting")
                                    InviteState.Expired -> "Expired" + (inv.expiresAt?.let { " on ${shortDate(it)}" } ?: "")
                                }
                                PersonRow(inv.user, sub.trim()) {
                                    when (inv.state) {
                                        InviteState.Invited -> CircasoButton("Cancel", { actions.onCancel(inv) }, style = ButtonStyle.Plain, size = ButtonSize.Small, enabled = state.online, busy = busy)
                                        InviteState.Expired -> CircasoButton("Invite Again", { actions.onInviteAgain(inv) }, style = ButtonStyle.Secondary, size = ButtonSize.Small, enabled = state.online, busy = busy)
                                        InviteState.Joined -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun daysLeft(until: Long, now: Long): String {
    val days = ((until - now) / 86_400_000L).toInt()
    return when { days <= 0 -> "expires today"; days == 1 -> "1 day left"; else -> "$days days left" }
}
