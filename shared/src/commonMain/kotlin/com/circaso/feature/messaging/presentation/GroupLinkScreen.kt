package com.circaso.feature.messaging.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.shareText
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.ToastKind
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.QrCode
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SectionHeader
import com.circaso.designsystem.components.SkeletonBlock
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.GroupLink
import com.circaso.feature.share.presentation.MessageBody

/**
 * Invite via link (Pasaka, 2026-11-14), opened from Group info by an admin. No frame in the canvas: built from the
 * wallet's Receive screen (the QR tile over what it stands for), grouped rows and the confirm sheet. Before a link
 * exists the screen says plainly what it will show to anyone who has it; with one, the link is there to scan, copy
 * and share, and the two ways to end it sit below, each behind a confirm.
 */
@Composable
fun GroupLinkScreen(viewModel: GroupLinkViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.share) { state.share?.let { shareText(it.text, it.subject); viewModel.onShared() } }
    GroupLinkContent(
        state, onBack,
        onCreate = viewModel::onCreate, onShare = viewModel::onShare, onCopied = viewModel::onCopied,
        onConfirm = viewModel::onConfirm, onConfirmed = viewModel::onConfirmed, onRetry = viewModel::load,
        onMessageShown = viewModel::onMessageShown,
    )
}

@Composable
fun GroupLinkContent(
    state: GroupLinkUiState,
    onBack: () -> Unit,
    onCreate: () -> Unit = {},
    onShare: () -> Unit = {},
    onCopied: () -> Unit = {},
    onConfirm: (GroupLinkConfirm?) -> Unit = {},
    onConfirmed: () -> Unit = {},
    onRetry: () -> Unit = {},
    onMessageShown: () -> Unit = {},
) {
    val toast = rememberToastState()
    LaunchedEffect(state.message) {
        state.message?.let { toast.show(it, if (it == "Link copied") ToastKind.Done else ToastKind.Info); onMessageShown() }
    }
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = "Invite via link", onBack = onBack) },
        banner = { ConnectionBanner(showing = if (state.link != null) "Showing the link saved on this phone" else "The link needs a connection") },
        toast = { ToastHost(toast) },
        overlay = {
            ConfirmSheet(
                visible = state.confirm == GroupLinkConfirm.Reset,
                title = "Reset the link?",
                message = "The current link stops working at once, everywhere it was shared, and a new one takes its place. People who already joined stay.",
                confirmLabel = "Reset Link",
                onConfirm = onConfirmed,
                onDismiss = { onConfirm(null) },
                destructive = true,
                icon = CircasoIcon.Link,
            )
            ConfirmSheet(
                visible = state.confirm == GroupLinkConfirm.TurnOff,
                title = "Turn off the link?",
                message = "Nobody new can join with it, and its page stops showing the group's name, description and photo. People who already joined stay.",
                confirmLabel = "Turn Off",
                onConfirm = onConfirmed,
                onDismiss = { onConfirm(null) },
                destructive = true,
                icon = CircasoIcon.Link,
            )
        },
    ) { padding ->
        val link = state.link
        when {
            state.loading -> LinkLoading()
            state.unknownOffline -> MessageBody(
                CircasoIcon.Offline, "You're offline",
                "This phone hasn't loaded the group's link yet. It shows here when you're back online.", null, {},
            )
            link == null && state.loadFailed != null -> MessageBody(CircasoIcon.Warning, "Couldn't load the link", state.loadFailed, "Try Again", onRetry)
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = CircasoSpacing.s4).padding(top = CircasoSpacing.s4, bottom = padding.calculateBottomPadding() + CircasoSpacing.s6),
                verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5),
            ) {
                if (link == null) LinkIntro(state, onCreate) else LinkOn(state, link, onShare, onCopied, onConfirm)
            }
        }
    }
}

/** The group the link is for: its photo, its name, and one quiet line under it. */
@Composable
private fun LinkGroupHead(chat: Chat?, line: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Avatar(chat?.avatarLabel ?: "", size = CircasoSizes.avatarXl, imageUrl = chat?.avatarUrl)
        BasicText(
            chat?.title ?: "", Modifier.semantics { heading() },
            style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        BasicText(line, style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
}

/** No link yet: what one does, what it shows to anyone who has it (said before it exists), and Create Link. */
@Composable
private fun LinkIntro(state: GroupLinkUiState, onCreate: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    LinkGroupHead(state.chat, memberCountText(state.chat?.members?.size ?: 0))
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        BasicText("One link, and people join by themselves", Modifier.semantics { heading() }, style = t.title3.copy(color = c.ink))
        BasicText(
            "Share it in WhatsApp or anywhere else. Whoever opens it can join this group, so you don't have to add people one by one.",
            style = t.body.copy(color = c.inkSecondary),
        )
    }
    Column {
        SectionHeader("Before you make it")
        ListGroup {
            LinkFact(CircasoIcon.Eye, "Anyone with the link can see", "The group's name, description and photo, and how many members it has.")
            RowDivider(Modifier.padding(start = FactInset))
            LinkFact(CircasoIcon.Lock, "Stays private", "Members, messages and money. Chats stay end-to-end encrypted.")
            RowDivider(Modifier.padding(start = FactInset))
            LinkFact(CircasoIcon.UserPlus, "People who join", "Become members straight away. They see messages sent after they join, and can't move the group's money.")
        }
    }
    if (state.nameUnknown) {
        Banner("This phone can't read the group's name yet, so it can't make the link. It can once a member's phone sends it the group's key.", CircasoIcon.Lock, tone = BannerTone.Warning)
    }
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally) {
        CircasoButton(
            "Create Link", onCreate, size = ButtonSize.Large, block = true,
            enabled = state.canChange && !state.nameUnknown, busy = state.work == GroupLinkWork.Creating,
        )
        BasicText(
            if (!state.online) "Making a link needs a connection." else "You can reset it or turn it off at any time.",
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = t.footnote.copy(color = c.inkTertiary, textAlign = TextAlign.Center),
        )
    }
}

/** One thing the link does or doesn't show: a glyph, a short title and the plain words under it. */
@Composable
private fun LinkFact(icon: CircasoIcon, title: String, text: String) {
    val c = CircasoTheme.colors
    ListRow(title = title, sub = text, leading = { CircasoIconImage(icon, c.inkSecondary, Modifier.size(22.dp)) })
}

/** Where a fact row's text starts (the 16 dp inset, the 22 dp glyph and the row's gap), so dividers line up with it. */
private val FactInset = 38.dp

/** The link is on: scan it, copy it, share it; then the two ways to end it, and what stays public while it's on. */
@Composable
private fun LinkOn(
    state: GroupLinkUiState,
    link: GroupLink,
    onShare: () -> Unit,
    onCopied: () -> Unit,
    onConfirm: (GroupLinkConfirm?) -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val clipboard = LocalClipboardManager.current
    val copy = { clipboard.setText(AnnotatedString(link.url)); onCopied() }
    LinkGroupHead(state.chat, "Scan or share to join")
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        QrCode(link.url, "QR code to join ${state.chat?.title ?: link.name}")
    }
    ListGroup {
        ListRow(
            title = link.url.removePrefix("https://"),
            sub = "Tap to copy",
            leading = { CircasoIconImage(CircasoIcon.Link, c.inkSecondary, Modifier.size(22.dp)) },
            trailing = { CircasoIconImage(CircasoIcon.Copy, c.inkSecondary, Modifier.size(20.dp)) },
            onClick = copy,
            modifier = Modifier.semantics { contentDescription = "Group link. Tap to copy." },
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        CircasoButton("Copy", copy, Modifier.weight(1f), style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, icon = CircasoIcon.Copy)
        CircasoButton("Share", onShare, Modifier.weight(1f), size = ButtonSize.Large, block = true, icon = CircasoIcon.Share)
    }
    Column {
        SectionHeader("Manage link")
        ListGroup {
            ListRow(
                "Reset Link", sub = "Makes a new link. The old one stops working.",
                trailing = if (state.work == GroupLinkWork.Resetting) ({ Spinner(c.inkSecondary) }) else null,
                onClick = if (state.canChange) ({ onConfirm(GroupLinkConfirm.Reset) }) else null,
            )
            RowDivider()
            ListRow(
                "Turn Off Link", sub = "Nobody new can join with it. Members stay.", destructive = true,
                trailing = if (state.work == GroupLinkWork.TurningOff) ({ Spinner(c.inkSecondary) }) else null,
                onClick = if (state.canChange) ({ onConfirm(GroupLinkConfirm.TurnOff) }) else null,
            )
        }
        if (!state.online) {
            BasicText(
                "Resetting or turning off the link needs a connection.",
                Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2).semantics { liveRegion = LiveRegionMode.Polite },
                style = t.footnote.copy(color = c.inkTertiary),
            )
        }
    }
    BasicText(
        "$LinkPublicNote $LinkJoinersNote",
        Modifier.padding(horizontal = CircasoSpacing.s4),
        style = t.footnote.copy(color = c.inkSecondary),
    )
}

/** The first load: the screen's own shapes, so it settles into place instead of jumping. */
@Composable
private fun LinkLoading() {
    Column(
        Modifier.fillMaxSize().padding(CircasoSpacing.s4).semantics { contentDescription = "Loading the group's link" },
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SkeletonBlock(Modifier.size(CircasoSizes.avatarXl), CircasoShapes.circle)
        SkeletonBlock(Modifier.fillMaxWidth(0.5f).height(24.dp), CircasoShapes.sm)
        SkeletonBlock(Modifier.fillMaxWidth().height(180.dp), CircasoShapes.lg)
        SkeletonBlock(Modifier.fillMaxWidth().height(50.dp), CircasoShapes.lg)
    }
}
