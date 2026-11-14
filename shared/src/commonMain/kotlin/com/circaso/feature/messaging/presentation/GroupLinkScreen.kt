package com.circaso.feature.messaging.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import androidx.compose.foundation.background
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
        // A failure reads as one (red, on screen longer); a change the server confirmed, or a copy, as done.
        state.message?.let { toast.show(it, if (state.messageIsProblem) ToastKind.Problem else ToastKind.Done); onMessageShown() }
    }
    val link = state.link
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = "Invite via link", onBack = onBack) },
        // With no copy of the link the body itself says the phone is offline; the banner needn't say it twice.
        banner = { if (!state.unknownOffline) ConnectionBanner(showing = if (link != null) "Showing the link saved on this phone" else "Making a link needs a connection") },
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
                confirmLabel = "Turn Off Link",
                onConfirm = onConfirmed,
                onDismiss = { onConfirm(null) },
                destructive = true,
                icon = CircasoIcon.Link,
            )
        },
    ) { padding ->
        when {
            state.loading -> LinkLoading()
            state.unknownOffline -> MessageBody(
                CircasoIcon.Offline, "You're offline",
                "This phone hasn't loaded the group's link yet. It shows here when you're back online.", null, {},
            )
            // Online as far as the phone knows, but no answer came and there is nothing saved to show.
            link == null && state.unreachable -> MessageBody(CircasoIcon.Offline, "Couldn't reach Circaso", "Check your connection and try again.", "Try Again", onRetry)
            link == null && state.loadFailed != null -> MessageBody(CircasoIcon.Warning, "Couldn't load the link", state.loadFailed, "Try Again", onRetry)
            // Making the link (or turning it off) changes the whole screen: the two cross-fade, each from its own top,
            // so the new QR code is in view rather than wherever the Create button had been scrolled to.
            else -> AnimatedContent(
                targetState = link != null,
                transitionSpec = { fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs)) },
                label = "group link",
            ) { on ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = CircasoSpacing.s4).padding(top = CircasoSpacing.s4, bottom = padding.calculateBottomPadding() + CircasoSpacing.s6),
                    verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5),
                ) {
                    // `link` can already be gone while the "on" side fades out: it then draws nothing.
                    if (!on) LinkIntro(state, onCreate) else if (link != null) LinkOn(state, link, onShare, onCopied, onConfirm)
                }
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

/** This phone can't read the group's name (its key is lost), so it has nothing true to publish for a link. */
@Composable
private fun NameUnknownBanner() =
    Banner("This phone can't read the group's name yet, so it can't make a link. It will be able to once another member's phone is online.", CircasoIcon.Lock, tone = BannerTone.Warning)

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
            LinkFact(CircasoIcon.Lock, "Hidden until someone joins", "Who is in the group, its messages and its money.")
            RowDivider(Modifier.padding(start = FactInset))
            LinkFact(CircasoIcon.UserPlus, "People who join", "Become members straight away. They see new messages and the group's wallet, like any member. They can't move its money.")
        }
    }
    if (state.nameUnknown) NameUnknownBanner()
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally) {
        CircasoButton(
            "Create Link", onCreate, size = ButtonSize.Large, block = true,
            enabled = state.canChange && !state.nameUnknown, busy = state.work == GroupLinkWork.Creating,
        )
        BasicText(
            when {
                !state.amAdmin -> "Only the group's admins can manage its link."
                !state.online -> "Making a link needs a connection."
                else -> "You can reset it or turn it off at any time."
            },
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = t.footnote.copy(color = c.inkTertiary, textAlign = TextAlign.Center),
        )
    }
}

/**
 * One thing the link does or doesn't show: a glyph, a short title and the plain words under it, however many lines
 * they take (a list row would cut them at two, and this is the one text that must be read whole).
 */
@Composable
private fun LinkFact(icon: CircasoIcon, title: String, text: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        CircasoIconImage(icon, c.inkSecondary, Modifier.size(FactGlyph))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
            BasicText(title, style = t.body.copy(color = c.ink))
            BasicText(text, style = t.subhead.copy(color = c.inkSecondary))
        }
    }
}

private val FactGlyph = 22.dp

/** Where a fact's text starts past the divider's own inset (the glyph and the row's gap), so dividers line up with it. */
private val FactInset = FactGlyph + CircasoSpacing.s3

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
    LinkGroupHead(state.chat, "Scan with a phone's camera, or share")
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        QrCode(link.url, "QR code to join ${state.chat?.title ?: link.name}")
    }
    // The link itself, whole (two lines if it needs them): the address is what people check before they trust it.
    ListGroup {
        Row(
            Modifier.fillMaxWidth().pressable(onClickLabel = "Copy link", onClick = copy)
                .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3)
                .semantics(mergeDescendants = true) { contentDescription = "Group link, ${link.url}" },
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.CenterVertically,
        ) {
            CircasoIconImage(CircasoIcon.Link, c.inkSecondary, Modifier.size(FactGlyph))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                BasicText(link.url.removePrefix("https://"), style = t.callout.copy(color = c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText("Tap to copy", style = t.footnote.copy(color = c.inkSecondary))
            }
            CircasoIconImage(CircasoIcon.Copy, c.inkSecondary, Modifier.size(20.dp))
        }
    }
    // As on Receive: Share first, then Copy.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        CircasoButton("Share", onShare, Modifier.weight(1f), size = ButtonSize.Large, block = true, icon = CircasoIcon.Share)
        CircasoButton("Copy", copy, Modifier.weight(1f), style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, icon = CircasoIcon.Copy)
    }
    // What the link's page shows right now, as it was published from a phone: the admin sees it, not a promise of it.
    Column {
        SectionHeader("On the link's page")
        ListGroup {
            Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                BasicText(link.name, style = t.body.copy(color = c.ink))
                BasicText(link.description ?: "No description", style = t.subhead.copy(color = c.inkSecondary))
                BasicText(
                    when {
                        link.hasPhoto -> "With the group's photo and how many members it has."
                        state.chat?.avatarUrl != null -> "The photo isn't on the page yet. It's added when this phone is online with the photo."
                        else -> "With how many members it has. The group has no photo."
                    },
                    style = t.footnote.copy(color = c.inkSecondary),
                )
            }
        }
    }
    if (state.nameUnknown) NameUnknownBanner()
    Column {
        SectionHeader("Manage link")
        ListGroup {
            // A row that can't be used right now is dimmed, and the line under the group says why.
            ListRow(
                "Reset link", sub = "Makes a new link. The old one stops working.",
                modifier = Modifier.alpha(if (state.canReset || state.work == GroupLinkWork.Resetting) 1f else DisabledAlpha),
                trailing = if (state.work == GroupLinkWork.Resetting) ({ Spinner(c.inkSecondary) }) else null,
                onClick = if (state.canReset) ({ onConfirm(GroupLinkConfirm.Reset) }) else null,
            )
            RowDivider()
            ListRow(
                "Turn off link", sub = "Nobody new can join with it. Members stay.", destructive = true,
                modifier = Modifier.alpha(if (state.canChange || state.work == GroupLinkWork.TurningOff) 1f else DisabledAlpha),
                trailing = if (state.work == GroupLinkWork.TurningOff) ({ Spinner(c.inkSecondary) }) else null,
                onClick = if (state.canChange) ({ onConfirm(GroupLinkConfirm.TurnOff) }) else null,
            )
        }
        val why = when {
            !state.amAdmin -> "Only the group's admins can manage its link."
            !state.online -> "Resetting or turning off the link needs a connection."
            else -> null
        }
        why?.let {
            BasicText(
                it,
                Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2).semantics { liveRegion = LiveRegionMode.Polite },
                style = t.footnote.copy(color = c.inkTertiary),
            )
        }
    }
    Column(Modifier.padding(horizontal = CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        BasicText(LinkPublicNote, style = t.footnote.copy(color = c.inkSecondary))
        BasicText(LinkJoinersNote, style = t.footnote.copy(color = c.inkSecondary))
    }
}

/** A row that rests (offline, or not this person's to use). */
private const val DisabledAlpha = 0.45f

/** The first load, which mostly ends on the intro: its shapes (photo, name, the facts, the button), so it settles in. */
@Composable
private fun LinkLoading() {
    Column(
        Modifier.fillMaxSize().padding(CircasoSpacing.s4).semantics { contentDescription = "Loading the group's link" },
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SkeletonBlock(Modifier.size(CircasoSizes.avatarXl), CircasoShapes.circle)
        SkeletonBlock(Modifier.fillMaxWidth(0.5f).height(SkeletonTitle), CircasoShapes.sm)
        SkeletonBlock(Modifier.fillMaxWidth().height(SkeletonFacts), CircasoShapes.lg)
        SkeletonBlock(Modifier.fillMaxWidth().height(CircasoSizes.controlLg), CircasoShapes.md)
    }
}

private val SkeletonTitle = 24.dp
private val SkeletonFacts = 196.dp
