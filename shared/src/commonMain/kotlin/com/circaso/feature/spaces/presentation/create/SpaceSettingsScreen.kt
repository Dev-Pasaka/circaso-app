package com.circaso.feature.spaces.presentation.create

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.circaso.core.navigation.SpaceDetailsRoute
import com.circaso.core.navigation.SpaceJoinSettingsRoute
import com.circaso.core.navigation.SpaceSettingsRoute
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.PostingMode
import com.circaso.feature.spaces.domain.PricePeriod
import com.circaso.feature.spaces.domain.PrivateMessages
import com.circaso.feature.spaces.domain.SpaceState
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.presentation.PopoverConfirm
import com.circaso.feature.spaces.presentation.PopoverHead
import com.circaso.feature.spaces.presentation.PopoverItem
import com.circaso.feature.spaces.presentation.SpacePopover
import com.circaso.feature.spaces.presentation.SpaceTile
import com.circaso.feature.spaces.presentation.SpacesNav
import com.circaso.feature.spaces.presentation.fiatText
import com.circaso.feature.spaces.presentation.usdcText
import com.circaso.feature.spaces.presentation.visibilityIcon
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// ---------------------------------------------------------------- SN8 Space Settings

@Composable
fun SpaceSettingsEntry(route: SpaceSettingsRoute, nav: SpacesNav) {
    val vm = koinViewModel<SpaceSettingsViewModel>(key = "space-settings-" + route.spaceId, parameters = { parametersOf(route.spaceId) })
    val state by vm.state.collectAsState()
    LaunchedEffect(state.deleted) { if (state.deleted) nav.home() }
    SpaceSettingsContent(
        state, SpaceSettingsActions(
            onBack = nav.back, onDetails = { nav.editDetails(route.spaceId) }, onJoin = { nav.joinSettings(route.spaceId) },
            onPostingMode = vm::onPostingMode, onPrivateMessages = vm::onPrivateMessages, onMembers = { nav.members(route.spaceId) },
            onInvites = { nav.invites(route.spaceId) }, onReports = { nav.reports(route.spaceId) }, onWallet = { nav.spaceWallet(route.spaceId) },
            onFinishSetup = { nav.walletSetup(route.spaceId, false) }, onDelete = vm::onDelete, onMessageShown = vm::onMessageShown, onRetry = vm::refresh,
        ),
    )
}

class SpaceSettingsActions(
    val onBack: () -> Unit = {},
    val onDetails: () -> Unit = {},
    val onJoin: () -> Unit = {},
    val onPostingMode: (PostingMode) -> Unit = {},
    val onPrivateMessages: (PrivateMessages) -> Unit = {},
    val onMembers: () -> Unit = {},
    val onInvites: () -> Unit = {},
    val onReports: () -> Unit = {},
    val onWallet: () -> Unit = {},
    val onFinishSetup: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

@Composable
fun SpaceSettingsContent(state: SpaceSettingsUiState, a: SpaceSettingsActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    LaunchedEffect(state.message) { state.message?.let { toast.show(it); a.onMessageShown() } }
    val space = state.space
    CircasoScaffold(grouped = true, topBar = { CircasoNavBar("Space Settings", a.onBack) }, toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter)) }) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4).padding(top = 10.dp, bottom = padding.calculateBottomPadding() + CircasoSpacing.s6),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!state.online) Banner("You’re offline. Changes need a connection.", CircasoIcon.Offline)
            if (space == null) {
                if (state.failed != null) {
                    Banner(state.failed + (state.reference?.let { " Ref $it" } ?: ""), CircasoIcon.Warning, tone = BannerTone.Critical)
                    CircasoButton("Try Again", a.onRetry, style = ButtonStyle.Secondary, enabled = state.online)
                }
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.CenterVertically) {
                SpaceTile(space.summary, 52.dp)
                Column {
                    BasicText(space.name, Modifier.semantics { heading() }, style = t.headline.copy(color = c.ink))
                    BasicText("@${space.handle} · ${space.visibility.label}", style = t.footnote.copy(color = c.inkSecondary))
                }
            }
            val online = state.online && !state.busy
            // SN5b's row: a space whose wallet isn't finished stays hidden; its owner finishes it here.
            if (state.owner && (space.state == SpaceState.SettingUp || state.settingUp)) {
                ListGroup {
                    ListRow("Finish Wallet Setup", sub = "${space.name} goes live once its wallet is set up", leading = { RowIcon(CircasoIcon.Wallet) }, trailing = { RowChevron() }, onClick = a.onFinishSetup)
                }
            }
            if (state.admin) {
                ListGroup {
                    ListRow("Details", sub = "Name, photo, @handle, about, topic", leading = { RowIcon(CircasoIcon.Pencil) }, trailing = { RowChevron() }, onClick = a.onDetails)
                    if (state.owner) {
                        RowDivider()
                        val price = space.summary.price
                        val joinSub = when (space.visibility) {
                            SpaceVisibility.MembersOnly -> "Members only" + (price?.let { " · ${usdcText(it.amountMinor)}" + if (it.period == PricePeriod.OneTime) "" else " ${it.period.per}" } ?: "")
                            else -> space.visibility.label
                        }
                        ListRow("Who can join", sub = joinSub, leading = { RowIcon(visibilityIcon(space.visibility)) }, trailing = { RowChevron() }, onClick = a.onJoin)
                    }
                    RowDivider()
                    ChoiceRow(
                        "Who can post", if (space.postingMode == PostingMode.Everyone) "Everyone" else "Admins only", CircasoIcon.Chat, online,
                        listOf("Everyone" to (space.postingMode == PostingMode.Everyone), "Admins only" to (space.postingMode == PostingMode.AdminsOnly)),
                    ) { i -> a.onPostingMode(if (i == 0) PostingMode.Everyone else PostingMode.AdminsOnly) }
                    if (state.owner) {
                        RowDivider()
                        val invite = space.visibility == SpaceVisibility.InviteOnly
                        ChoiceRow(
                            "Private messages",
                            if (invite || space.privateMessages == PrivateMessages.Members) "From members only" else "From anyone who can see the space",
                            CircasoIcon.Lock, online && !invite,
                            listOf("From anyone who can see the space" to (space.privateMessages == PrivateMessages.Anyone), "From members only" to (space.privateMessages == PrivateMessages.Members)),
                            note = if (invite) "An invite-only space’s owner is reachable by members only." else null,
                        ) { i -> a.onPrivateMessages(if (i == 0) PrivateMessages.Anyone else PrivateMessages.Members) }
                    }
                }
            }
            ListGroup {
                if (state.admin) {
                    val team = listOfNotNull(
                        state.admins?.let { "$it ${if (it == 1) "admin" else "admins"}" },
                        state.moderators?.let { "$it ${if (it == 1) "moderator" else "moderators"}" },
                    ).joinToString(", ")
                    ListRow("Members and roles", sub = listOf(space.summary.memberCount.toString(), team).filter { it.isNotEmpty() }.joinToString(" · "), leading = { RowIcon(CircasoIcon.People) }, trailing = { RowChevron() }, onClick = a.onMembers)
                    RowDivider()
                    val waiting = state.waiting ?: 0
                    ListRow("Invites and requests", sub = if (waiting > 0) "$waiting waiting" else "None waiting", leading = { RowIcon(CircasoIcon.Mail) }, trailing = {
                        if (waiting > 0) com.circaso.designsystem.components.TextBadge(waiting.toString()) else RowChevron()
                    }, onClick = a.onInvites)
                    RowDivider()
                }
                val open = state.openReports ?: 0
                ListRow("Reports", sub = if (open > 0) "$open open" else "None open", leading = { RowIcon(CircasoIcon.Flag) }, trailing = { RowChevron() }, onClick = a.onReports)
                if (state.admin && space.wallet?.walletId != null) {
                    RowDivider()
                    val w = state.wallet
                    val money = w?.usdc?.let { u -> fiatText(u.amountMinor, w.fiat) ?: usdcText(u.amountMinor) }
                    val who = if (state.owner) "only you send" else "view only"
                    ListRow("Space wallet", sub = listOfNotNull(money, who).joinToString(" · "), leading = { RowIcon(CircasoIcon.Wallet) }, trailing = { RowChevron() }, onClick = a.onWallet)
                }
            }
            if (state.owner) DeleteSpace(space.name, online, a.onDelete)
        }
    }
}

@Composable
private fun RowIcon(icon: CircasoIcon) {
    val c = CircasoTheme.colors
    Box(Modifier.size(CircasoSizes.iconButton).clip(CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
        CircasoIconImage(icon, c.ink, Modifier.size(17.dp), strokeWidth = 1.8f)
    }
}

/** A row whose choice opens a popover anchored to it (spaces never use bottom sheets for menus). */
@Composable
private fun ChoiceRow(title: String, sub: String, icon: CircasoIcon, enabled: Boolean, options: List<Pair<String, Boolean>>, note: String? = null, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListRow(title, sub = sub, leading = { RowIcon(icon) }, trailing = { RowChevron() }, onClick = if (enabled) ({ open = true }) else null)
        SpacePopover(open, { open = false }, label = title, alignEnd = true, width = 280.dp) {
            PopoverHead(title)
            options.forEachIndexed { i, (label, on) -> PopoverItem(label, { open = false; if (!on) onPick(i) }, checked = on) }
            note?.let { com.circaso.feature.spaces.presentation.PopoverNote(it) }
        }
    }
}

/** Delete Space, last and quiet; the confirm is inside its popover and says what happens (SN8). */
@Composable
private fun DeleteSpace(name: String, enabled: Boolean, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        CircasoButton("Delete Space", { open = true }, style = ButtonStyle.Destructive, size = ButtonSize.Small, icon = CircasoIcon.Trash, enabled = enabled)
        SpacePopover(open, { open = false }, label = "Delete $name?", width = 290.dp) {
            PopoverConfirm(
                "Delete $name?",
                "Its chat and members go for everyone and it can’t be undone. The space wallet must be empty first. Payments already made stay in everyone’s wallet history.",
                "Delete", onConfirm = { open = false; onDelete() }, onCancel = { open = false }, enabled = enabled,
            )
        }
    }
}

// ---------------------------------------------------------------- Details and Who Can Join (SN8b)

@Composable
fun SpaceDetailsEntry(route: SpaceDetailsRoute, nav: SpacesNav) {
    val vm = koinViewModel<SpaceEditViewModel>(key = "space-details-" + route.spaceId, parameters = { parametersOf(route.spaceId, SpaceEditMode.Details) })
    val state by vm.state.collectAsState()
    LaunchedEffect(state.saved) { if (state.saved) nav.back() }
    SpaceEditContent(state, SpaceEditActions(nav.back, vm::onSave, vm::onPickPhoto, vm::onName, vm::onHandle, vm::onAbout, vm::onTopic, vm::onVisibility, vm::onPrice, vm::onPeriod, vm::onRemovePhoto))
}

@Composable
fun SpaceJoinSettingsEntry(route: SpaceJoinSettingsRoute, nav: SpacesNav) {
    val vm = koinViewModel<SpaceEditViewModel>(key = "space-join-" + route.spaceId, parameters = { parametersOf(route.spaceId, SpaceEditMode.Join) })
    val state by vm.state.collectAsState()
    SpaceEditContent(state, SpaceEditActions(nav.back, vm::onSave, vm::onPickPhoto, vm::onName, vm::onHandle, vm::onAbout, vm::onTopic, vm::onVisibility, vm::onPrice, vm::onPeriod, vm::onRemovePhoto))
}

class SpaceEditActions(
    val onBack: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onPickPhoto: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onHandle: (String) -> Unit = {},
    val onAbout: (String) -> Unit = {},
    val onTopic: (com.circaso.feature.spaces.domain.SpaceTopic) -> Unit = {},
    val onVisibility: (SpaceVisibility) -> Unit = {},
    val onPrice: (String) -> Unit = {},
    val onPeriod: (PricePeriod) -> Unit = {},
    val onRemovePhoto: () -> Unit = {},
)

@Composable
fun SpaceEditContent(state: SpaceEditUiState, a: SpaceEditActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val title = if (state.mode == SpaceEditMode.Details) "Details" else "Who Can Join"
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title, a.onBack, trailing = { CircasoButton("Save", a.onSave, style = ButtonStyle.Plain, size = ButtonSize.Small, enabled = state.canSave, busy = state.saving) }) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4).padding(top = 14.dp, bottom = padding.calculateBottomPadding() + CircasoSpacing.s6),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
        ) {
            if (!state.online) Banner("You’re offline. Saving needs a connection.", CircasoIcon.Offline)
            state.error?.let { Banner(it + (state.reference?.let { r -> " Ref $r" } ?: ""), CircasoIcon.Warning, tone = BannerTone.Critical) }
            if (state.mode == SpaceEditMode.Details) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    Box(
                        Modifier.pressable(onClick = a.onPickPhoto).semantics(mergeDescendants = true) { contentDescription = "Change the space photo" },
                    ) {
                        SpaceTile(state.name.ifBlank { "Space" }, state.space?.summary?.photoUrl.takeIf { state.photo == null && !state.removePhoto }, 72.dp)
                        Box(Modifier.align(Alignment.BottomEnd).size(26.dp).clip(CircasoShapes.circle).background(c.action), contentAlignment = Alignment.Center) {
                            CircasoIconImage(if (state.photo != null) CircasoIcon.Check else CircasoIcon.Camera, c.onAction, Modifier.size(14.dp))
                        }
                    }
                    CircasoTextField(state.name, a.onName, "Space name", Modifier.weight(1f), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
                }
                // SP5 `removePhoto`: back to the space's initials (a new or picked photo can be dropped the same way).
                if (state.photo != null || (state.space?.summary?.photoUrl != null && !state.removePhoto)) {
                    CircasoButton("Remove Photo", a.onRemovePhoto, style = ButtonStyle.Plain, size = ButtonSize.Small, icon = CircasoIcon.Trash, enabled = !state.saving)
                } else if (state.removePhoto) {
                    BasicText("The photo goes when you save.", Modifier.padding(horizontal = 4.dp), style = t.footnote.copy(color = c.inkSecondary))
                }
                if (state.owner) {
                    HandleField(state.handle, state.handleState, a.onHandle, a.onHandle)
                    if (state.handle != state.space?.handle) BasicText("The old link stops working once you save.", Modifier.padding(horizontal = 4.dp), style = t.footnote.copy(color = c.warning))
                } else BasicText("@${state.handle} · only the owner changes the handle", Modifier.padding(horizontal = 4.dp), style = t.footnote.copy(color = c.inkSecondary))
                AboutField(state.about, a.onAbout)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldLabel("Topic")
                    TopicChips(state.topic, a.onTopic)
                }
            } else {
                val old = state.space?.summary?.price
                JoinOptions(state.visibility, a.onVisibility, enabled = !state.saving) {
                    PriceFields(
                        state.priceText, state.priceProblem.takeIf { state.priceText.isNotBlank() }, state.period, state.fiat, a.onPrice, a.onPeriod,
                        hintPrefix = old?.takeIf { state.priceChanged }?.let { "Was ${usdcText(it.amountMinor)}." },
                    )
                }
                // SN8b: inline, not a sheet. Nobody pays a price they haven't seen: it applies from each member's next period.
                if (state.priceChanged && old != null) {
                    val n = state.membersToTell
                    val per = state.period.per.ifEmpty { "period" }.removePrefix("a ")
                    NoteBox(
                        if (state.saved) "${state.notified ?: n} ${if ((state.notified ?: n) == 1) "member was" else "members were"} told" else "$n ${if (n == 1) "member" else "members"} will be told today",
                        "They keep ${usdcText(old.amountMinor)} until their current $per ends, then renew at ${state.priceMinor?.let { usdcText(it) } ?: "the new price"}. New members pay the new price from now.",
                    )
                } else if (state.becomingPaid) {
                    NoteBox("Everyone already in stays a member", "They keep their place for free until they leave. New members pay the price to join.")
                } else if (state.saved && state.notified != null) {
                    NoteBox("${state.notified} ${if (state.notified == 1) "member was" else "members were"} told", "The new price applies from each member’s next period.")
                }
            }
        }
    }
}

@Composable
private fun NoteBox(title: String, text: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.fill).padding(CircasoSpacing.s4).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        CircasoIconImage(CircasoIcon.Bell, c.inkSecondary, Modifier.padding(top = 2.dp).size(18.dp), strokeWidth = 1.8f)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
            BasicText(text, style = t.footnote.copy(color = c.inkSecondary))
        }
    }
}
