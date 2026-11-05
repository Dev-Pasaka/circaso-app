package com.circaso.feature.spaces.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.core.navigation.SpaceChatRoute
import com.circaso.core.platform.localDayIndex
import com.circaso.core.platform.threadDayLabel
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ComposerBar
import com.circaso.designsystem.components.DayLabel
import com.circaso.designsystem.components.ReactionBubble
import com.circaso.designsystem.components.ThreadHeader
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.MuteDuration
import com.circaso.feature.spaces.domain.PostSendState
import com.circaso.feature.spaces.domain.SpacePost
import com.circaso.feature.spaces.presentation.ComposerNote
import com.circaso.feature.spaces.presentation.DimBehind
import com.circaso.feature.spaces.presentation.NotEndToEnd
import com.circaso.feature.spaces.presentation.PopoverConfirm
import com.circaso.feature.spaces.presentation.PopoverHead
import com.circaso.feature.spaces.presentation.PopoverItem
import com.circaso.feature.spaces.presentation.SkeletonBlock
import com.circaso.feature.spaces.presentation.SpacePopover
import com.circaso.feature.spaces.presentation.SpacePostItem
import com.circaso.feature.spaces.presentation.SpaceTile
import com.circaso.feature.spaces.presentation.SpacesNav
import com.circaso.feature.spaces.presentation.SysLine
import com.circaso.feature.spaces.presentation.clockTime
import com.circaso.feature.spaces.presentation.countText
import com.circaso.feature.spaces.presentation.page.MembershipBanner
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** What the chat screen can do, so previews draw it without a ViewModel. */
class SpaceChatActions(
    val onBack: () -> Unit = {},
    val onOpenSpace: () -> Unit = {},
    val onRenew: () -> Unit = {},
    val onDraft: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onLongPress: (SpacePost) -> Unit = {},
    val onDismissMenu: () -> Unit = {},
    val onReply: (SpacePost) -> Unit = {},
    val onCancelReply: () -> Unit = {},
    val onReact: (SpacePost, String?) -> Unit = { _, _ -> },
    val onReport: (SpacePost) -> Unit = {},
    val onRemovePost: (SpacePost) -> Unit = {},
    val onMuteChoices: () -> Unit = {},
    val onMute: (SpacePost, MuteDuration) -> Unit = { _, _ -> },
    val onConfirmRemoveMember: () -> Unit = {},
    val onRemoveMember: (SpacePost) -> Unit = {},
    val onRetry: (SpacePost) -> Unit = {},
    val onDiscard: (SpacePost) -> Unit = {},
    val onLoadOlder: () -> Unit = {},
    val onMessagePrivately: () -> Unit = {},
    val onFollow: () -> Unit = {},
    val onRejoin: () -> Unit = {},
)

@Composable
fun SpaceChatEntry(route: SpaceChatRoute, nav: SpacesNav) {
    val vm = koinViewModel<SpaceChatViewModel>(key = "space-chat-" + route.spaceId, parameters = { parametersOf(route.spaceId) })
    val state by vm.state.collectAsState()
    val toast = rememberToastState()
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is SpaceChatEvent.OpenChat -> nav.openChat(e.chatId)
                is SpaceChatEvent.Toast -> toast.show(e.text)
            }
        }
    }
    SpaceChatContent(
        state,
        SpaceChatActions(
            onBack = nav.back, onOpenSpace = { nav.openSpace(route.spaceId) }, onRenew = { nav.joinSpace(route.spaceId) },
            onDraft = vm::onDraft, onSend = vm::onSend, onLongPress = vm::onLongPress, onDismissMenu = vm::onDismissMenu,
            onReply = vm::onReply, onCancelReply = vm::onCancelReply, onReact = vm::onReact,
            onReport = { p -> vm.onDismissMenu(); nav.report(route.spaceId, p.postId) },
            onRemovePost = vm::onRemovePost, onMuteChoices = vm::onMuteChoices, onMute = vm::onMute,
            onConfirmRemoveMember = vm::onConfirmRemoveMember, onRemoveMember = vm::onRemoveMember,
            onRetry = vm::onRetry, onDiscard = vm::onDiscard, onLoadOlder = vm::onLoadOlder,
            onMessagePrivately = vm::onMessagePrivately,
            onFollow = { nav.openSpace(route.spaceId) }, onRejoin = { nav.joinSpace(route.spaceId) },
        ),
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
    )
}

/**
 * SH1: a space chat, which is not a group chat. The header is the space (its tile and member count); the first line says
 * it isn't end-to-end encrypted; every post names its sender and role; reactions are counted; no read receipts or typing
 * names (a big room); `#` is plain text (money to a space goes through Message privately).
 */
@Composable
fun SpaceChatContent(
    state: SpaceChatUiState,
    actions: SpaceChatActions,
    toast: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val space = state.space
    val name = space?.name ?: "Space"
    val listState = rememberLazyListState()
    // Near the oldest post shown: ask for the page before it (the list is reversed, so that is its far end).
    val nearTop by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
    LaunchedEffect(nearTop, state.posts.size) { if (nearTop && state.posts.size >= 20) actions.onLoadOlder() }
    CircasoScaffold(
        topBar = {
            ThreadHeader(
                title = name,
                subtitle = "${countText(state.memberCount, exact = true)} ${if (state.memberCount == 1) "member" else "members"}",
                onBack = actions.onBack,
                avatar = { SpaceTile(name, space?.summary?.photoUrl, CircasoSizes.avatarSm) },
                onTitle = actions.onOpenSpace,
                trailing = { CircasoIconButton(CircasoIcon.More, "Space info", actions.onOpenSpace) },
            )
        },
        banner = {
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                if (!state.online) Banner("You’re offline. Posts send when you reconnect.", CircasoIcon.Offline, tone = BannerTone.Neutral)
                if (space != null) MembershipBanner(space, state.now, actions.onRenew)
            }
        },
        toast = toast,
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()).imePadding()) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2, bottom = CircasoSpacing.s3),
                    verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2, Alignment.Bottom),
                ) {
                    val posts = state.posts
                    itemsIndexed(posts.asReversed(), key = { _, p -> p.localKey }) { reversedIndex, post ->
                        val index = posts.lastIndex - reversedIndex
                        val previous = posts.getOrNull(index - 1)
                        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                            if (previous == null || localDayIndex(previous.createdAt) != localDayIndex(post.createdAt)) DayLabel(threadDayLabel(post.createdAt, state.now))
                            PostWithMenu(post, state, actions)
                        }
                    }
                    if (state.loading) items(3) { SkeletonPost(it) }
                    // SH1f: an older page that didn't load says so in place; the rest of the chat keeps working.
                    if (state.olderFailed) item(key = "older-failed") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) { }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            BasicText("Older posts didn’t load.", style = t.footnote.copy(color = c.inkSecondary))
                            CircasoButton("Try Again", actions.onLoadOlder, style = ButtonStyle.Secondary, size = ButtonSize.Small, enabled = state.online)
                            state.olderRef?.let { BasicText("Ref $it", style = t.caption2.copy(color = c.inkTertiary)) }
                        }
                    }
                    if (state.atStart || state.posts.size < 20) item(key = "e2e") {
                        // The trust line comes first, in plain words; Learn more opens the space's About.
                        Row(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s1), horizontalArrangement = Arrangement.Center) {
                            Row(
                                Modifier.pressable(role = Role.Button, onClick = actions.onOpenSpace).semantics(mergeDescendants = true) { contentDescription = "$NotEndToEnd Learn more" }.padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Top,
                            ) {
                                CircasoIconImage(CircasoIcon.Info, c.inkSecondary, Modifier.padding(top = 2.dp).size(13.dp))
                                BasicText(
                                    if (space?.postingMode == com.circaso.feature.spaces.domain.PostingMode.AdminsOnly) "Only admins post here. $NotEndToEnd" else NotEndToEnd,
                                    style = t.caption1.copy(color = c.inkSecondary),
                                )
                                BasicText("Learn more", style = t.caption1.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
                            }
                        }
                    }
                }
                Foot(state, actions)
            }
            DimBehind(state.menu != null)
        }
    }
}

@Composable
private fun SkeletonPost(i: Int) {
    Row(Modifier.fillMaxWidth().semantics { contentDescription = "Loading posts" }, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        SkeletonBlock(CircasoSizes.avatarSm, CircasoSizes.avatarSm, shape = CircasoShapes.circle)
        SkeletonBlock(listOf(220, 160, 190)[i % 3].dp, 38.dp, shape = CircasoShapes.lg)
    }
}

/** One post with its long-press menu (SH1c): the reactions on top, then what the person may do, destructive last. */
@Composable
private fun PostWithMenu(post: SpacePost, state: SpaceChatUiState, actions: SpaceChatActions) {
    val open = state.menu?.localKey == post.localKey
    val clipboard = LocalClipboardManager.current
    Box {
        SpacePostItem(
            post,
            time = if (post.mine && post.sendState == PostSendState.Sent) clockTime(post.createdAt) else null,
            onReact = if (state.canReact) ({ e -> actions.onReact(post, e) }) else null,
            onLongPress = if (post.removed == null) ({ actions.onLongPress(post) }) else null,
            onRetry = { actions.onRetry(post) },
        )
        SpacePopover(open, actions.onDismissMenu, label = "Post options", alignEnd = post.mine, width = 260.dp) {
            val menu = state.menu
            if (post.sendState != PostSendState.Sent) {
                // A post still on its way (or refused): send it again or let it go; nothing else applies yet.
                if (post.sendState == PostSendState.Failed) PopoverItem("Send Again", { actions.onRetry(post) }, icon = CircasoIcon.Send)
                PopoverItem("Discard", { actions.onDiscard(post) }, icon = CircasoIcon.Trash, critical = true)
                return@SpacePopover
            }
            if (menu?.confirmRemoveMember == true) {
                val first = post.author.displayName.substringBefore(' ')
                PopoverConfirm(
                    "Remove $first?", "$first leaves the space and can follow it again. Their posts stay.", "Remove",
                    onConfirm = { actions.onRemoveMember(post) }, onCancel = actions.onDismissMenu, busy = state.busy, enabled = state.online,
                )
                return@SpacePopover
            }
            if (menu?.muting == true) {
                PopoverHead("Mute ${post.author.displayName.substringBefore(' ')} for")
                MuteDuration.entries.forEach { d -> PopoverItem(d.label, { actions.onMute(post, d) }, enabled = state.online && !state.busy) }
                if (!state.online) com.circaso.feature.spaces.presentation.PopoverNote("Muting needs a connection.")
                return@SpacePopover
            }
            if (state.canReact) {
                ReactionBubble(post.myReaction, { e -> actions.onReact(post, e) }, Modifier.padding(horizontal = CircasoSpacing.s2, vertical = CircasoSpacing.s2))
            }
            state.myRole?.takeIf { it.isModerator }?.let { PopoverHead(it.label) }
            if (state.foot == ChatFoot.Composer) PopoverItem("Reply", { actions.onReply(post) }, icon = CircasoIcon.Reply)
            post.text?.let { text -> PopoverItem("Copy", { clipboard.setText(AnnotatedString(text)); actions.onDismissMenu() }, icon = CircasoIcon.Copy) }
            if (!post.mine) PopoverItem("Report", { actions.onReport(post) }, icon = CircasoIcon.Flag)
            val removable = state.canRemovePost(post)
            val mutable = state.canModerate(post)
            val removeMember = state.canRemoveMember(post)
            if (removable || mutable || removeMember) {
                var first = true
                fun div(): Boolean = first.also { first = false }
                if (removable) PopoverItem("Remove Post", { actions.onRemovePost(post) }, icon = CircasoIcon.Trash, critical = true, enabled = state.online && !state.busy, divider = div())
                if (mutable) PopoverItem("Mute ${post.author.displayName.substringBefore(' ')}", actions.onMuteChoices, icon = CircasoIcon.Mute, critical = true, enabled = state.online, divider = div())
                if (removeMember) PopoverItem("Remove from Space", actions.onConfirmRemoveMember, icon = CircasoIcon.UserMinus, critical = true, enabled = state.online, divider = div())
                if (!state.online) com.circaso.feature.spaces.presentation.PopoverNote("Moderating needs a connection.")
            }
        }
    }
}

/** The composer, or the one line that says why there is none, with the way to talk anyway (SH1b, SH1e). */
@Composable
private fun Foot(state: SpaceChatUiState, actions: SpaceChatActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val privately = if (state.canMessageOwner) "Message Privately" else null
    when (val foot = state.foot) {
        ChatFoot.Composer -> ComposerBar(
            value = state.draft, onValueChange = actions.onDraft,
            placeholder = "Message ${state.space?.name ?: "the space"}",
            canSend = state.canSend, onSend = actions.onSend,
            above = state.replyTo?.let { r ->
                {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2).clip(CircasoShapes.md).background(c.fill)
                            .padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                    ) {
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "Replying to ${r.author.displayName}: ${r.text.orEmpty()}" }) {
                            BasicText("Replying to ${r.author.displayName.substringBefore(' ')}", style = t.footnote.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
                            BasicText(r.text.orEmpty(), style = t.footnote.copy(color = c.inkSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        CircasoIconButton(CircasoIcon.Close, "Cancel reply", actions.onCancelReply)
                    }
                }
            },
        )
        ChatFoot.AdminsOnly -> ComposerNote("Only admins post in this space.", privately, actions.onMessagePrivately, actionEnabled = state.online)
        is ChatFoot.Muted -> ComposerNote("You can’t post here ${mutedUntilText(foot.until, state.now)}. An admin muted you.", privately, actions.onMessagePrivately, actionEnabled = state.online)
        ChatFoot.Visitor -> ComposerNote("Follow to post and react.", "Open Space", actions.onFollow)
        ChatFoot.Ended -> ComposerNote("Your membership ended. Rejoin to take part again.", "Rejoin", actions.onRejoin, actionEnabled = state.online)
        ChatFoot.Closed -> ComposerNote(if (state.space?.me?.relation == com.circaso.feature.spaces.domain.Relation.Banned) "You can’t take part in this space." else "This space isn’t available any more.")
    }
}
