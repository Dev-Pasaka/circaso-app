package com.circaso.feature.spaces.presentation.page

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.core.navigation.SpaceRoute
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.ToastState
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ComposerBar
import com.circaso.designsystem.components.DayLabel
import com.circaso.designsystem.components.QrCode
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SegmentedControl
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.MembershipState
import com.circaso.feature.spaces.domain.NotifyLevel
import com.circaso.feature.spaces.domain.PostingMode
import com.circaso.feature.spaces.domain.PricePeriod
import com.circaso.feature.spaces.domain.Relation
import com.circaso.feature.spaces.domain.Space
import com.circaso.feature.spaces.domain.SpaceMe
import com.circaso.feature.spaces.domain.SpaceMember
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.domain.SpaceState
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.presentation.NotEndToEnd
import com.circaso.feature.spaces.presentation.NotEndToEndLong
import com.circaso.feature.spaces.presentation.PopoverConfirm
import com.circaso.feature.spaces.presentation.PopoverHead
import com.circaso.feature.spaces.presentation.PopoverItem
import com.circaso.feature.spaces.presentation.RoleBadge
import com.circaso.feature.spaces.presentation.SkeletonBlock
import com.circaso.feature.spaces.presentation.SpaceCover
import com.circaso.feature.spaces.presentation.SpacePopover
import com.circaso.feature.spaces.presentation.SpacePostItem
import com.circaso.feature.spaces.presentation.SpaceTile
import com.circaso.feature.spaces.presentation.SpacesNav
import com.circaso.feature.spaces.presentation.SysLine
import com.circaso.feature.spaces.presentation.ComposerNote
import com.circaso.feature.spaces.presentation.clockTime
import com.circaso.feature.spaces.presentation.countText
import com.circaso.feature.spaces.presentation.kindLine
import com.circaso.feature.spaces.presentation.priceLeadText
import com.circaso.feature.spaces.presentation.shortDate
import com.circaso.feature.spaces.presentation.usdcText
import com.circaso.feature.spaces.presentation.visibilityIcon
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.presentation.PayIntent
import com.circaso.feature.wallet.presentation.PayStep
import com.circaso.feature.wallet.presentation.PayUiState
import com.circaso.feature.wallet.presentation.PayViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** What the page's controls do. Every lambda is a ViewModel call or a [SpacesNav] hop. */
class SpacePageActions(
    val back: () -> Unit,
    val home: () -> Unit,
    val retry: () -> Unit,
    val tab: (SpaceTab) -> Unit,
    val follow: () -> Unit,
    val menu: (Boolean) -> Unit,
    val unfollow: () -> Unit,
    val undoUnfollow: () -> Unit,
    val leave: () -> Unit,
    val leaveCancel: () -> Unit,
    val notifications: (NotifyLevel) -> Unit,
    val notifyMenu: (Boolean) -> Unit,
    val more: (Boolean) -> Unit,
    val qr: (Boolean) -> Unit,
    val share: () -> Unit,
    val report: () -> Unit,
    val settings: () -> Unit,
    val invitePeople: () -> Unit,
    val message: () -> Unit,
    val join: () -> Unit,
    val closeJoin: () -> Unit,
    val pay: () -> Unit,
    val addMoney: () -> Unit,
    val receive: () -> Unit,
    val request: () -> Unit,
    val cancelRequest: () -> Unit,
    val accept: () -> Unit,
    val decline: () -> Unit,
    val undoDecline: () -> Unit,
    val draft: (String) -> Unit,
    val send: () -> Unit,
    val react: (com.circaso.feature.spaces.domain.SpacePost, String?) -> Unit,
    val retryPost: (com.circaso.feature.spaces.domain.SpacePost) -> Unit,
    val loadOlder: () -> Unit,
    val retryMembers: () -> Unit,
    val manageMembers: () -> Unit,
    val finishWallet: () -> Unit,
    val openChat: () -> Unit,
    val asha: () -> Unit,
) {
    companion object {
        val None = SpacePageActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
    }
}

/** SS1–SS6e, SM1–SM6, SI2b: the space page, by id or by @handle from a link. */
@Composable
fun SpacePageEntry(route: SpaceRoute, nav: SpacesNav) {
    val vm = koinViewModel<SpacePageViewModel>(key = "space-${route.spaceId}-${route.handle}", parameters = { parametersOf(route.spaceId, route.handle, route.join) })
    val s by vm.state.collectAsState()
    val toast = rememberToastState()
    LaunchedEffect(vm) { vm.toasts.collect { toast.show(it) } }
    LaunchedEffect(vm) { vm.openChat.collect { nav.openChat(it) } }
    // The money is PayViewModel's, keyed per SP13 quote (one engine for chats and the page, D-M3).
    val quote = (s.join as? JoinStep.Quoted)?.payment ?: (s.join as? JoinStep.Joining)?.payment
    val pay = quote?.let { q ->
        koinViewModel<PayViewModel>(
            key = "join-${q.paymentId}",
            parameters = { parametersOf(PayIntent(spaceId = q.spaceId, spacePaymentId = q.paymentId, amount = MinorUnits.toDisplay(q.amountMinor, 6).replace(",", ""), displayName = s.space?.name, inChat = true)) },
        )
    }
    val payState = pay?.state?.collectAsState()?.value
    LaunchedEffect(payState?.step, payState?.result?.transferId) {
        val done = payState?.result
        if (payState?.step == PayStep.Done && done != null) vm.onMembershipSigned(done)
    }
    val id = s.spaceId
    val actions = SpacePageActions(
        back = nav.back, home = nav.home, retry = vm::onRetry, tab = vm::onTab, follow = vm::onFollow, menu = vm::onMenu,
        unfollow = vm::onUnfollow, undoUnfollow = vm::onUndoUnfollow, leave = vm::onLeave, leaveCancel = vm::onLeaveCancel,
        notifications = vm::onNotifications, notifyMenu = vm::onNotifyMenu, more = vm::onMore, qr = vm::onQr,
        share = { s.space?.let { sp -> com.circaso.core.platform.shareText("https://${sp.summary.link}", sp.name) } },
        report = { id?.let { nav.report(it, null) } }, settings = { id?.let(nav.settings) }, invitePeople = { id?.let(nav.invite) },
        message = vm::onMessage, join = vm::onJoin, closeJoin = vm::onCloseJoin, pay = { pay?.onPay() },
        addMoney = nav.receive, receive = nav.receive,
        request = vm::onRequest, cancelRequest = vm::onCancelRequest, accept = vm::onAcceptInvite,
        decline = { vm.onDeclineInvite(nav.back) }, undoDecline = vm::onUndoDecline,
        draft = vm::onDraft, send = vm::onSend, react = vm::onReact, retryPost = vm::onRetryPost, loadOlder = vm::onLoadOlder,
        retryMembers = vm::onRetryMembers, manageMembers = { id?.let(nav.members) }, finishWallet = { id?.let { nav.walletSetup(it, false) } },
        openChat = { id?.let(nav.openSpaceChat) }, asha = nav.asha,
    )
    SpacePageContent(s, payState, actions, toast)
}

/** The page as a pure function of its state (previews draw it with samples). */
@Composable
fun SpacePageContent(s: SpacePageUiState, pay: PayUiState?, a: SpacePageActions, toast: ToastState = rememberToastState()) {
    CircasoScaffold(toast = {
        Box(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s14), contentAlignment = Alignment.TopCenter) {
            ToastHost(toast)
            if (s.undoUnfollow) UndoPill("You unfollowed ${s.space?.name ?: "the space"}", a.undoUnfollow, enabled = s.online)
        }
    }) { _ ->
        val problem = s.problem
        val space = s.space
        when {
            problem != null -> ProblemView(problem, a)
            space == null -> LoadingView(a.back)
            else -> LoadedPage(space, s, pay, a)
        }
    }
}

@Composable
private fun LoadedPage(space: Space, s: SpacePageUiState, pay: PayUiState?, a: SpacePageActions) {
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val room = maxHeight
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = CircasoSpacing.s6)) {
                item(key = "head") { Head(space, s, a) }
                pageBody(space, s, a)
            }
            if (s.join != JoinStep.Closed) {
                JoinCard(
                    space, s.join, pay, s.fiat, s.now,
                    onPay = a.pay, onTryAgain = a.join, onClose = a.closeJoin, onAddMoney = a.addMoney, onReceive = a.receive,
                    modifier = Modifier.align(Alignment.BottomCenter), maxHeight = room - CircasoSpacing.s4,
                )
            }
        }
        BottomBar(space, s, a)
    }
}

// ---------------------------------------------------------------- the head

@Composable
private fun Head(space: Space, s: SpacePageUiState, a: SpacePageActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val me = space.me
    Column {
        SpaceCover(space.summary.coverUrl) {
            Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3), horizontalArrangement = Arrangement.SpaceBetween) {
                GlassButton(CircasoIcon.Back, "Back", a.back)
                Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    if (me.isAdmin) GlassButton(CircasoIcon.Gear, "Space Settings", a.settings)
                    if (space.visibility != SpaceVisibility.InviteOnly && me.relation != Relation.Banned) GlassButton(CircasoIcon.Share, "Share ${space.name}", a.share)
                    Box {
                        GlassButton(CircasoIcon.More, "More", { a.more(true) })
                        MorePopover(space, s, a)
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = CircasoSpacing.s4).offset(y = (-44).dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SpaceTile(space.summary, 88.dp, ring = true)
            BasicText(space.name, Modifier.padding(top = 6.dp).semantics { heading() }, style = t.title3.copy(color = c.ink, fontWeight = FontWeight.Bold))
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                BasicText("@${space.handle} ·", style = t.subhead.copy(color = c.inkSecondary))
                CircasoIconImage(visibilityIcon(space.visibility), c.inkSecondary, Modifier.size(15.dp), strokeWidth = 1.8f)
                BasicText(kindLine(space.summary), style = t.subhead.copy(color = c.inkSecondary))
            }
            val about = space.summary.about
            if (about != null && s.tab != SpaceTab.About && !(space.visibility == SpaceVisibility.InviteOnly && !me.isMember && me.relation != Relation.Invited)) {
                BasicText(about, Modifier.padding(top = 2.dp), style = t.subhead.copy(color = c.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Actions(space, s, a)
            s.actionError?.let { BasicText(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = t.footnote.copy(color = c.critical)) }
            val known = space.known.phrase()
            if (known != null && me.relation != Relation.Banned && !me.isMember) KnownLine(space, known)
        }
        Column(Modifier.offset(y = (-36).dp).padding(horizontal = CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            if (!s.online) {
                Banner("You’re offline · Showing this space as of ${clockTime(space.asOf)}", CircasoIcon.Offline)
                offlineReason(space)?.let { BasicText(it, Modifier.padding(horizontal = CircasoSpacing.s1), style = t.footnote.copy(color = c.inkSecondary)) }
            }
            if (!s.joining) MembershipBanner(space, s.now, a.join)
            if (space.state == SpaceState.SettingUp && me.isOwner) SettingUpCard(space, a.finishWallet, s.online)
        }
    }
}

/** What needs the server, said before the tap (SS6b). */
private fun offlineReason(space: Space): String? {
    val me = space.me
    return when {
        me.isOwner -> null
        me.isMember -> null
        space.visibility == SpaceVisibility.Public -> "Following needs a connection."
        space.visibility == SpaceVisibility.MembersOnly -> "Joining needs a connection."
        me.relation == Relation.Invited -> "Accepting needs a connection."
        else -> "Asking to join needs a connection."
    }
}

@Composable
private fun GlassButton(icon: CircasoIcon, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(CircasoSizes.iconButton).clip(CircasoShapes.circle).glass(CircasoShapes.circle).pressable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { CircasoIconImage(icon, CircasoTheme.colors.ink, Modifier.size(18.dp)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(space: Space, s: SpacePageUiState, a: SpacePageActions) {
    val me = space.me
    val busy = s.busy
    val offline = !s.online
    if (me.relation == Relation.Banned) return
    // Invite-only strangers and invited people act from the bottom of the page (SS3, SI2b).
    if (space.visibility == SpaceVisibility.InviteOnly && !me.isMember) return
    FlowRow(Modifier.padding(top = CircasoSpacing.s2), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        when {
            me.isOwner -> {
                // The owner doesn't follow their own space: their first action is bringing people in.
                if (space.visibility == SpaceVisibility.MembersOnly) CircasoButton("Share Link", a.share, style = ButtonStyle.Secondary, icon = CircasoIcon.Share)
                else CircasoButton("Invite People", a.invitePeople, style = ButtonStyle.Secondary, icon = CircasoIcon.UserPlus, enabled = !offline)
                if (space.state == SpaceState.Live) CircasoButton("Open Chat", a.openChat, style = ButtonStyle.Secondary, icon = CircasoIcon.Chat)
            }
            me.isMember && !s.ended -> Box {
                val paid = me.membership != null || space.visibility != SpaceVisibility.Public
                CircasoButton(
                    if (paid) "Member" else "Following", { a.menu(!s.menuOpen) }, style = ButtonStyle.Secondary,
                    icon = if (s.menuOpen) CircasoIcon.ChevronDown else CircasoIcon.Check,
                    busy = busy == PageBusy.Unfollow || busy == PageBusy.Leave || busy == PageBusy.Notify,
                )
                MemberPopover(space, s, a)
            }
            space.visibility == SpaceVisibility.Public -> CircasoButton("Follow", a.follow, icon = CircasoIcon.Plus, enabled = !offline, busy = busy == PageBusy.Follow)
            space.visibility == SpaceVisibility.MembersOnly -> {
                val price = space.summary.price
                val rejoin = s.ended || me.relation == Relation.Ended
                val label = (if (rejoin) "Rejoin" else "Join") + (price?.let { " · " + priceLeadText(it, s.fiat) } ?: "")
                CircasoButton(
                    if (s.joining) "Joining…" else label, a.join, busy = s.joining || s.join == JoinStep.Quoting,
                    enabled = space.state == SpaceState.Live,
                )
            }
        }
        if (space.canMessageOwner) CircasoButton("Message", a.message, style = ButtonStyle.Secondary, enabled = !offline, busy = busy == PageBusy.Message)
    }
}

/** SS1c / SM6: notifications and Unfollow or Leave, anchored to the button; the confirm happens inside it. */
@Composable
private fun MemberPopover(space: Space, s: SpacePageUiState, a: SpacePageActions) {
    val me = space.me
    val paid = me.membership != null
    SpacePopover(s.menuOpen, onDismiss = { a.menu(false) }, label = if (s.leaveConfirm) "Leave ${space.name}?" else "Notifications", width = 280.dp) {
        if (s.leaveConfirm) {
            PopoverConfirm(
                "Leave ${space.name}?", leaveMessage(space), "Leave", onConfirm = a.leave, onCancel = a.leaveCancel, cancel = "Stay",
                busy = s.busy == PageBusy.Leave, enabled = s.online,
            )
            if (!s.online) PopoverNoteLine("Leaving needs a connection.")
        } else {
            PopoverHead("Notifications")
            NotifyLevel.entries.forEach { level ->
                PopoverItem(level.label, { a.notifications(level) }, checked = me.notifications == level, enabled = s.online)
            }
            if (!me.isOwner) PopoverItem(if (paid || space.visibility != SpaceVisibility.Public) "Leave" else "Unfollow", a.unfollow, icon = CircasoIcon.UserMinus, critical = true, enabled = s.online, divider = true)
            if (!s.online) PopoverNoteLine("Changes need a connection.")
        }
    }
}

private fun leaveMessage(space: Space): String {
    val m = space.me.membership
    return when {
        m?.complimentary == true -> "You were kept in for free when ${space.name} became members only. Leaving ends that; coming back means paying."
        m != null && m.period != PricePeriod.OneTime && m.paidUntil != null ->
            "You paid until ${shortDate(m.paidUntil)}. Leaving ends your access now and doesn’t refund the rest of the ${if (m.period == PricePeriod.Yearly) "year" else "month"}. Rejoining means paying again."
        m != null -> "Leaving ends your access now. Rejoining means paying again."
        space.visibility == SpaceVisibility.InviteOnly -> "You’ll need a new invite, or an accepted request, to come back."
        else -> "You can follow it again any time."
    }
}

@Composable
private fun PopoverNoteLine(text: String) = com.circaso.feature.spaces.presentation.PopoverNote(text)

/** The cover's More: share, the QR code shown in place, report (never a sheet). */
@Composable
private fun MorePopover(space: Space, s: SpacePageUiState, a: SpacePageActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    SpacePopover(s.moreOpen || s.qrOpen, onDismiss = { a.more(false); a.qr(false) }, label = if (s.qrOpen) "QR code" else "More", alignEnd = true, width = 260.dp) {
        if (s.qrOpen) {
            Column(Modifier.fillMaxWidth().padding(CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                QrCode(space.summary.deepLink, "QR code for ${space.name}", size = 180.dp)
                BasicText(space.summary.link, style = t.subhead.copy(color = c.ink))
                BasicText(
                    if (space.visibility == SpaceVisibility.MembersOnly) "It opens ${space.name} with its price." else if (space.visibility == SpaceVisibility.InviteOnly) "People who open it can ask to join." else "It opens ${space.name} in Circaso.",
                    style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                )
                CircasoButton("Done", { a.qr(false) }, style = ButtonStyle.Secondary, size = ButtonSize.Small)
            }
        } else {
            if (space.me.relation != Relation.Banned) {
                PopoverItem("Share Link", { a.more(false); a.share() }, icon = CircasoIcon.Link)
                PopoverItem("QR Code", { a.qr(true) }, icon = CircasoIcon.Qr)
            }
            if (space.me.isAdmin) PopoverItem("Space Settings", { a.more(false); a.settings() }, icon = CircasoIcon.Gear)
            if (!space.me.isOwner) PopoverItem("Report Space", { a.more(false); a.report() }, icon = CircasoIcon.Flag, critical = true, divider = true)
        }
    }
}

@Composable
private fun KnownLine(space: Space, phrase: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val text = when {
        space.visibility == SpaceVisibility.Public -> "$phrase follow" + if (space.known.count == 1 && space.known.names.size == 1) "s" else ""
        space.known.count == 1 -> "$phrase is ${if (space.visibility == SpaceVisibility.MembersOnly) "here" else "a member"}"
        else -> "$phrase are ${if (space.visibility == SpaceVisibility.MembersOnly) "here" else "members"}"
    }
    Row(Modifier.padding(top = 4.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Row {
            space.known.names.take(3).forEachIndexed { i, n ->
                Avatar(n, Modifier.offset(x = (-8 * i).dp).border(2.dp, c.bg, CircasoShapes.circle), size = CircasoSizes.avatarSm)
            }
        }
        BasicText(text, Modifier.offset(x = (-(8 * (space.known.names.take(3).size - 1)).coerceAtLeast(0)).dp), style = t.footnote.copy(color = c.inkSecondary))
    }
}

/** SN5b, on the owner's page: the space is kept and hidden until its wallet exists. */
@Composable
private fun SettingUpCard(space: Space, onFinish: () -> Unit, online: Boolean) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.fill).padding(CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        BasicText("The space is saved. Its wallet isn’t ready yet.", Modifier.semantics { heading() }, style = t.headline.copy(color = c.ink))
        BasicText("${space.name} goes live once its wallet is set up. Until then nobody else can see it.", style = t.subhead.copy(color = c.inkSecondary))
        CircasoButton("Finish Wallet Setup", onFinish, size = ButtonSize.Small, enabled = online)
        if (!online) BasicText("Needs a connection", style = t.footnote.copy(color = c.inkSecondary))
    }
}

// ---------------------------------------------------------------- the body

private fun LazyListScope.pageBody(space: Space, s: SpacePageUiState, a: SpacePageActions) {
    val me = space.me
    when {
        me.relation == Relation.Banned -> item("banned") { Banned(space, a) }
        space.visibility == SpaceVisibility.InviteOnly && !me.isMember && me.relation == Relation.Invited -> item("invited") { InvitedPreview(space) }
        space.visibility == SpaceVisibility.InviteOnly && !me.isMember -> item("private") { PrivateNote() }
        s.ended && space.visibility == SpaceVisibility.MembersOnly -> item("ended") { Ended(space) }
        space.visibility == SpaceVisibility.MembersOnly && !me.isMember -> item("members-get") { MembersGet(space, s) }
        else -> {
            item("tabs") {
                Box(Modifier.offset(y = (-28).dp).padding(horizontal = CircasoSpacing.s4).semantics { contentDescription = "Space sections" }) {
                    SegmentedControl(SpaceTab.entries.map { it.label }, s.tab.ordinal, { a.tab(SpaceTab.entries[it]) })
                }
            }
            when (s.tab) {
                SpaceTab.Chat -> chat(space, s, a)
                SpaceTab.About -> item("about") { About(space, s, a) }
                SpaceTab.Members -> item("members") { Members(space, s, a) }
            }
        }
    }
}

private fun LazyListScope.chat(space: Space, s: SpacePageUiState, a: SpacePageActions) {
    val visitor = !space.me.isMember
    val shown = if (visitor) s.posts.takeLast(20) else s.posts
    if (shown.isEmpty()) {
        item("chat-empty") {
            val c = CircasoTheme.colors
            BasicText(
                if (space.me.isOwner) "Nothing posted yet. Say hello: the first post is yours." else "Nothing posted yet.",
                Modifier.fillMaxWidth().offset(y = (-16).dp).padding(horizontal = CircasoSpacing.s8, vertical = CircasoSpacing.s6),
                style = CircasoTheme.typography.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
            )
        }
    }
    if (!visitor && s.posts.size >= 20) item("older") {
        Box(Modifier.fillMaxWidth().offset(y = (-16).dp), contentAlignment = Alignment.Center) {
            CircasoButton("Show Older Posts", a.loadOlder, style = ButtonStyle.Plain, size = ButtonSize.Small, enabled = s.online)
        }
    }
    var lastDay = -1L
    shown.forEach { p ->
        val day = com.circaso.core.platform.localDayIndex(p.createdAt)
        if (day != lastDay) {
            lastDay = day
            item("day-$day") { DayLabel(com.circaso.core.platform.threadDayLabel(p.createdAt, s.now), Modifier.offset(y = (-16).dp)) }
        }
        item(p.localKey) {
            SpacePostItem(
                p, Modifier.offset(y = (-16).dp).padding(horizontal = CircasoSpacing.s4, vertical = 4.dp),
                time = clockTime(p.createdAt),
                onReact = if (visitor) null else { e -> a.react(p, e) },
                onRetry = { a.retryPost(p) },
            )
        }
    }
    s.welcome?.let { w -> item("welcome") { SysLine(w, CircasoIcon.Ticket, Modifier.offset(y = (-16).dp).padding(horizontal = CircasoSpacing.s4, vertical = 4.dp)) } }
}

@Composable
private fun About(space: Space, s: SpacePageUiState, a: SpacePageActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.offset(y = (-20).dp).padding(horizontal = CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        space.summary.about?.let { BasicText(it, style = t.subhead.copy(color = c.ink)) }
        Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface).border(1.dp, c.separator, CircasoShapes.lg)) {
            space.owner?.let { o -> InfoRow("Owner", o.displayName, leading = { Avatar(o.displayName, size = CircasoSizes.avatarSm, imageUrl = o.avatarUrl) }) }
            RowDivider()
            InfoRow("Topic", space.summary.topic.label, icon = CircasoIcon.Globe)
            if (space.me.relation != Relation.Banned) {
                RowDivider()
                InfoRow("Share", "${space.summary.link} · QR code", icon = CircasoIcon.Qr, onClick = { a.qr(true) })
            }
            if (space.me.isMember) {
                RowDivider()
                Box {
                    InfoRow("Notifications", space.me.notifications.label, icon = CircasoIcon.Bell, onClick = { a.notifyMenu(true) })
                    SpacePopover(s.notifyOpen, { a.notifyMenu(false) }, "Notifications", alignEnd = true) {
                        PopoverHead("Notifications")
                        NotifyLevel.entries.forEach { level -> PopoverItem(level.label, { a.notifications(level) }, checked = space.me.notifications == level, enabled = s.online) }
                        if (!s.online) PopoverNoteLine("Changes need a connection.")
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.Top) {
            CircasoIconImage(CircasoIcon.Info, c.inkSecondary, Modifier.padding(top = 2.dp).size(14.dp))
            BasicText(NotEndToEndLong, style = t.footnote.copy(color = c.inkSecondary))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s5)) {
            if (!space.me.isOwner) CircasoButton("Report Space", a.report, style = ButtonStyle.Destructive, size = ButtonSize.Small, icon = CircasoIcon.Flag)
            if (space.me.isMember && !space.me.isOwner) {
                val paid = space.me.membership != null || space.visibility != SpaceVisibility.Public
                Box {
                    CircasoButton(if (paid) "Leave" else "Unfollow", { a.menu(true); a.unfollow() }, style = ButtonStyle.Plain, size = ButtonSize.Small, enabled = s.online)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, sub: String, icon: CircasoIcon? = null, leading: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}.padding(horizontal = CircasoSpacing.s4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        when {
            leading != null -> leading()
            icon != null -> Box(Modifier.size(CircasoSizes.iconButton).clip(CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
                CircasoIconImage(icon, c.ink, Modifier.size(18.dp), strokeWidth = 1.8f)
            }
        }
        Column(Modifier.weight(1f)) {
            BasicText(title, style = t.body.copy(color = c.ink))
            BasicText(sub, style = t.subhead.copy(color = c.inkSecondary))
        }
        if (onClick != null) CircasoIconImage(CircasoIcon.ChevronRight, c.inkTertiary, Modifier.size(16.dp))
    }
}

@Composable
private fun Members(space: Space, s: SpacePageUiState, a: SpacePageActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.offset(y = (-20).dp).padding(horizontal = CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val m = s.members
        when {
            s.membersLocked -> BasicText("The member list is for members.", Modifier.padding(CircasoSpacing.s4), style = t.subhead.copy(color = c.inkSecondary))
            s.membersFailed != null -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                BasicText("Members didn’t load.", style = t.subhead.copy(color = c.inkSecondary))
                CircasoButton("Try Again", a.retryMembers, style = ButtonStyle.Secondary, size = ButtonSize.Small, enabled = s.online)
            }
            m == null -> repeat(3) { Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) { SkeletonBlock(44.dp, 44.dp, shape = CircasoShapes.circle); SkeletonBlock(160.dp, 14.dp) } }
            else -> {
                if (m.team.isNotEmpty()) {
                    Label("Team")
                    MemberGroup(m.team)
                }
                if (m.contacts.isNotEmpty()) {
                    Label("Your contacts here", Modifier.padding(top = CircasoSpacing.s2))
                    MemberGroup(m.contacts)
                }
                val more = m.count - m.team.size - m.contacts.size
                if (more > 0) BasicText("And ${countText(more, exact = true)} more", Modifier.padding(4.dp), style = t.footnote.copy(color = c.inkSecondary))
                if (space.me.isModerator) CircasoButton("Manage Members", a.manageMembers, style = ButtonStyle.Secondary, size = ButtonSize.Small, icon = CircasoIcon.People)
                BasicText("Member lists never show phone numbers.", Modifier.padding(4.dp), style = t.caption1.copy(color = c.inkTertiary))
            }
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier.padding(horizontal = 4.dp).semantics { heading() }, style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary))
}

@Composable
private fun MemberGroup(people: List<SpaceMember>) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface).border(1.dp, c.separator, CircasoShapes.lg)) {
        people.forEachIndexed { i, p ->
            if (i > 0) RowDivider(Modifier.padding(start = CircasoSizes.avatarMd + CircasoSpacing.s3))
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = listOfNotNull(p.person.displayName, p.role?.takeIf { it != SpaceRole.Member }?.label, p.person.handle?.let { "@$it" }).joinToString(", ") }
                    .padding(horizontal = CircasoSpacing.s4, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
            ) {
                Avatar(p.person.displayName, size = CircasoSizes.avatarMd, imageUrl = p.person.avatarUrl)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText(p.person.displayName, Modifier.weight(1f, fill = false), style = t.body.copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        RoleBadge(p.role)
                    }
                    p.person.handle?.let { BasicText("@$it", style = t.subhead.copy(color = c.inkSecondary)) }
                }
            }
        }
    }
}

/** SS2: everything public about a members-only space, and the shape of its chat behind a lock, never real posts. */
@Composable
private fun MembersGet(space: Space, s: SpacePageUiState) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.offset(y = (-24).dp).padding(horizontal = CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
        Column(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.fill).padding(horizontal = CircasoSpacing.s4, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            BasicText("Members get", Modifier.semantics { heading() }, style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
            val price = space.summary.price
            val lines = listOfNotNull(
                "The space chat and its history",
                "Private messages with the owner".takeIf { space.privateMessages == com.circaso.feature.spaces.domain.PrivateMessages.Members },
                price?.let { p ->
                    val local = com.circaso.feature.spaces.presentation.fiatText(p.amountMinor, s.fiat)?.let { ", about $it" }.orEmpty()
                    if (p.period == PricePeriod.OneTime) "${usdcText(p.amountMinor)} once$local" else "${usdcText(p.amountMinor)} ${p.period.per}$local. Never taken automatically"
                },
            )
            lines.forEach { l ->
                Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    CircasoIconImage(CircasoIcon.Check, c.ink, Modifier.padding(top = 2.dp).size(16.dp), strokeWidth = 2.4f)
                    BasicText(l, style = t.subhead.copy(color = c.ink))
                }
            }
        }
        LockedChat("The chat is for members")
    }
}

@Composable
private fun LockedChat(label: String, dim: Boolean = false) {
    val c = CircasoTheme.colors
    Box(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s1)) {
        Column(Modifier.fillMaxWidth().semantics { }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(0.7f to false, 0.52f to false, 0.64f to true).forEach { (w, end) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
                    Box(Modifier.fillMaxWidth(w).height(36.dp).clip(RoundedCornerShape(22.dp)).background(if (dim) c.fill.copy(alpha = 0.6f) else c.fill))
                }
            }
        }
        if (!dim) Row(
            Modifier.align(Alignment.Center).glass(CircasoShapes.pill, floating = true).padding(horizontal = 14.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircasoIconImage(CircasoIcon.Lock, c.ink, Modifier.size(16.dp))
            BasicText(label, style = CircasoTheme.typography.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
        }
    }
}

/** SM5: after the grace the chat is locked until paid again; own posts stay in the space. */
@Composable
private fun Ended(space: Space) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val m = space.me.membership
    Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s8), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        CircasoIconImage(CircasoIcon.Lock, c.inkSecondary, Modifier.size(28.dp), strokeWidth = 1.8f)
        BasicText("Your membership ended" + (m?.paidUntil?.let { " on ${shortDate(it)}" } ?: ""), Modifier.semantics { heading() }, style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(
            (m?.graceUntil?.let { "The 3 days’ grace ended on ${shortDate(it)}. " } ?: "") + "Rejoin to read the chat and take part again; your own posts are still there.",
            style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(CircasoSpacing.s4))
        LockedChat("", dim = true)
    }
}

/** SS3: an invite-only space opened from a link shows its outside only. */
@Composable
private fun PrivateNote() {
    BasicText(
        "This space is private. Its posts and member list are only for people an admin lets in.",
        Modifier.offset(y = (-24).dp).padding(horizontal = CircasoSpacing.s4),
        style = CircasoTheme.typography.subhead.copy(color = CircasoTheme.colors.inkSecondary),
    )
}

/** SI2b: the invited person sees more than a stranger: about, who invited them, the trust line. */
@Composable
private fun InvitedPreview(space: Space) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val invite = space.me.invite
    Column(Modifier.offset(y = (-24).dp).padding(horizontal = CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        invite?.let { inv ->
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                Avatar(inv.invitedBy.displayName, size = CircasoSizes.avatarSm, imageUrl = inv.invitedBy.avatarUrl)
                BasicText("${inv.invitedBy.firstName} invited you on ${shortDate(inv.invitedAt)}", style = t.subhead.copy(color = c.ink))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            CircasoIconImage(CircasoIcon.Info, c.inkSecondary, Modifier.padding(top = 2.dp).size(14.dp))
            BasicText(NotEndToEnd, style = t.footnote.copy(color = c.inkSecondary))
        }
    }
}

/** SS6e: banned. Who did it isn't said; private messages to the owner stop too. */
@Composable
private fun Banned(space: Space, a: SpacePageActions) {
    CenterMessage(
        CircasoIcon.Ban, "You can’t join ${space.name}",
        "An admin banned you from this space. You can’t follow, post, message its owner or be invited again. If you think this is a mistake, tell Circaso through Asha.",
    ) { CircasoButton("Ask Asha", a.asha, style = ButtonStyle.Secondary, size = ButtonSize.Small) }
}

// ---------------------------------------------------------------- the bottom

@Composable
private fun BottomBar(space: Space, s: SpacePageUiState, a: SpacePageActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val me = space.me
    when {
        me.relation == Relation.Banned -> Unit
        // SI2b / SI3b: Accept and Decline; a decline waits with Undo before it goes.
        space.visibility == SpaceVisibility.InviteOnly && me.relation == Relation.Invited -> Column(Modifier.fillMaxWidth().padding(CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            if (s.declining) {
                Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Invite declined. ${me.invite?.invitedBy?.firstName ?: "They"} isn’t told.", Modifier.weight(1f), style = t.subhead.copy(color = c.inkSecondary))
                    CircasoButton("Undo", a.undoDecline, style = ButtonStyle.Plain, size = ButtonSize.Small)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    CircasoButton("Decline", a.decline, Modifier.weight(1f), style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = s.online && s.busy == null)
                    CircasoButton("Accept", a.accept, Modifier.weight(1f), size = ButtonSize.Large, block = true, enabled = s.online, busy = s.busy == PageBusy.Accept)
                }
                if (!s.online) BasicText("Accepting needs a connection.", Modifier.fillMaxWidth(), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
            }
        }
        // SS3 / SS3b: Request to Join, or the quiet Requested state with Cancel Request.
        space.visibility == SpaceVisibility.InviteOnly && !me.isMember -> Column(Modifier.fillMaxWidth().padding(CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            val request = me.request
            val again = request?.canAskAgainAt?.takeIf { request.declined && it > s.now }
            when {
                me.relation == Relation.Requested && request != null && !request.declined -> {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = CircasoSizes.controlLg).clip(CircasoShapes.pill).background(c.fill).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircasoIconImage(CircasoIcon.Clock, c.inkSecondary, Modifier.size(18.dp))
                        BasicText("Requested ${com.circaso.core.platform.threadDayLabel(request.at, s.now).lowercase()}, ${clockTime(request.at)}", style = t.body.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold))
                    }
                    CircasoButton("Cancel Request", a.cancelRequest, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true, enabled = s.online, busy = s.busy == PageBusy.CancelRequest)
                }
                again != null -> {
                    CircasoButton("Request to Join", a.request, size = ButtonSize.Large, block = true, enabled = false)
                    BasicText("You can ask again on ${shortDate(again)}.", Modifier.fillMaxWidth(), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                }
                else -> {
                    CircasoButton("Request to Join", a.request, size = ButtonSize.Large, block = true, enabled = s.online, busy = s.busy == PageBusy.Request)
                    BasicText(if (s.online) "The admins see your name, photo and @handle." else "Asking to join needs a connection.", Modifier.fillMaxWidth(), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                }
            }
        }
        s.showTabs && s.tab == SpaceTab.Chat && s.join == JoinStep.Closed -> when {
            s.canPost -> ComposerBar(s.draft, a.draft, "Message ${space.name}", canSend = s.draft.isNotBlank(), onSend = a.send)
            !me.isMember -> ComposerNote("Follow to post and react.")
            space.state != SpaceState.Live -> ComposerNote("The chat opens once the space is live.")
            me.mutedAt(s.now) -> ComposerNote(mutedText(me), if (space.canMessageOwner) "Message Privately" else null, a.message, actionEnabled = s.online)
            space.postingMode == PostingMode.AdminsOnly -> ComposerNote("Only admins post in this space.", if (space.canMessageOwner) "Message Privately" else null, a.message, actionEnabled = s.online)
            else -> Unit
        }
        else -> Unit
    }
}

private fun mutedText(me: SpaceMe): String {
    val until = me.mutedUntil ?: return "An admin muted you."
    if (until >= SpaceMe.UntilUnmuted) return "You can’t post here until an admin unmutes you."
    return "You can’t post here until ${clockTime(until)}${if (com.circaso.core.platform.localDayIndex(until) == com.circaso.core.platform.localDayIndex(com.circaso.core.sync.nowMillis())) " today" else " on ${shortDate(until)}"}. An admin muted you."
}

// ---------------------------------------------------------------- whole-page states

/** SS6: the page's own shape in skeletons, no spinner. */
@Composable
private fun LoadingView(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().semantics { contentDescription = "Opening the space" }) {
        SpaceCover(null) {
            Box(Modifier.padding(CircasoSpacing.s4)) { GlassButton(CircasoIcon.Back, "Back", onBack) }
        }
        Column(Modifier.padding(horizontal = CircasoSpacing.s4).offset(y = (-44).dp), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            SkeletonBlock(88.dp, 88.dp, shape = CircasoShapes.lg)
            SkeletonBlock(220.dp, 22.dp)
            SkeletonBlock(150.dp, 14.dp)
            SkeletonBlock(300.dp, 14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) { SkeletonBlock(110.dp, 44.dp, shape = CircasoShapes.pill); SkeletonBlock(110.dp, 44.dp, shape = CircasoShapes.pill) }
            Spacer(Modifier.height(CircasoSpacing.s3))
            SkeletonBlock(340.dp, 32.dp, shape = CircasoShapes.pill)
            Spacer(Modifier.height(CircasoSpacing.s2))
            SkeletonBlock(240.dp, 36.dp, shape = RoundedCornerShape(22.dp))
            SkeletonBlock(180.dp, 36.dp, shape = RoundedCornerShape(22.dp))
            SkeletonBlock(220.dp, 36.dp, shape = RoundedCornerShape(22.dp))
        }
    }
}

/** SS6c (failed, Try Again, Ref) and SS6d (deleted or removed, calm, Back to Spaces). */
@Composable
private fun ProblemView(problem: PageProblem, a: SpacePageActions) {
    Column(Modifier.fillMaxSize()) {
        SpaceCover(null) { Box(Modifier.padding(CircasoSpacing.s4)) { GlassButton(CircasoIcon.Back, "Back", a.back) } }
        when (problem) {
            is PageProblem.Failed -> CenterMessage(CircasoIcon.Warning, "This space didn’t open", problem.message, reference = problem.reference) {
                CircasoButton("Try Again", a.retry, style = ButtonStyle.Secondary, size = ButtonSize.Small)
            }
            is PageProblem.Unavailable -> CenterMessage(
                CircasoIcon.Ban, "This space isn’t available",
                (if (problem.removed) "Circaso removed ${problem.name ?: "this space"} for breaking the community rules."
                else "Its owner deleted ${problem.name ?: "this space"}.") + " Your payments to it are still in your wallet history.",
            ) { CircasoButton("Back to Spaces", a.home, style = ButtonStyle.Secondary, size = ButtonSize.Small) }
            PageProblem.NotFound -> CenterMessage(CircasoIcon.Search, "This space isn’t available", "The link may be wrong, or the space is private. Ask someone in it for an invite.") {
                CircasoButton("Back to Spaces", a.home, style = ButtonStyle.Secondary, size = ButtonSize.Small)
            }
        }
    }
}

@Composable
private fun CenterMessage(icon: CircasoIcon, title: String, text: String, reference: String? = null, action: @Composable () -> Unit = {}) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s8, vertical = CircasoSpacing.s8), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CircasoIconImage(icon, c.inkTertiary, Modifier.size(40.dp), strokeWidth = 1.6f)
        BasicText(title, Modifier.semantics { heading() }, style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(text, Modifier.widthIn(max = 300.dp), style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        action()
        reference?.let { BasicText("Ref $it", style = t.caption2.copy(color = c.inkTertiary)) }
    }
}

/** SS1c: Unfollow acts at once; Undo for a few seconds. */
@Composable
private fun UndoPill(text: String, onUndo: () -> Unit, enabled: Boolean) {
    val c = CircasoTheme.colors
    Row(
        Modifier.glass(CircasoShapes.pill, floating = true).padding(start = CircasoSpacing.s4, end = CircasoSpacing.s1, top = 2.dp, bottom = 2.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1),
    ) {
        BasicText(text, Modifier.widthIn(max = 240.dp), style = CircasoTheme.typography.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        CircasoButton("Undo", onUndo, style = ButtonStyle.Plain, size = ButtonSize.Small, enabled = enabled)
    }
}

