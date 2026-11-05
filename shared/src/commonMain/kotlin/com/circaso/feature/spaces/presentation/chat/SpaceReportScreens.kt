package com.circaso.feature.spaces.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.navigation.SpaceReportRoute
import com.circaso.core.navigation.SpaceReportsRoute
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoSwitch
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SegmentedControl
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.ReportAction
import com.circaso.feature.spaces.domain.ReportGroup
import com.circaso.feature.spaces.domain.ReportReason
import com.circaso.feature.spaces.domain.SpacePost
import com.circaso.feature.spaces.presentation.PopoverConfirm
import com.circaso.feature.spaces.presentation.SkeletonBlock
import com.circaso.feature.spaces.presentation.SpacePopover
import com.circaso.feature.spaces.presentation.SpacesNav
import com.circaso.core.platform.chatListTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

class SpaceReportActions(
    val onCancel: () -> Unit = {},
    val onReason: (ReportReason) -> Unit = {},
    val onDetail: (String) -> Unit = {},
    val onAlsoBlock: (Boolean) -> Unit = {},
    val onSend: () -> Unit = {},
    val onDone: () -> Unit = {},
)

@Composable
fun SpaceReportEntry(route: SpaceReportRoute, nav: SpacesNav) {
    val vm = koinViewModel<SpaceReportViewModel>(key = "space-report-${route.spaceId}-${route.postId}", parameters = { parametersOf(route.spaceId, route.postId) })
    val state by vm.state.collectAsState()
    SpaceReportContent(state, SpaceReportActions(nav.back, vm::onReason, vm::onDetail, vm::onAlsoBlock, vm::onSend, nav.back))
}

/** SR3 (and Report Space): the post quoted, one reason, optional detail, Also block. SR3b once sent; SR3c offline. */
@Composable
fun SpaceReportContent(state: SpaceReportUiState, actions: SpaceReportActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    if (state.sent) {
        CircasoScaffold(topBar = { CircasoNavBar("", actions.onDone) }) { padding ->
            Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()), contentAlignment = Alignment.Center) {
                val space = state.space?.name ?: "The space"
                CenterState(
                    CircasoIcon.Check, "Thanks for your report",
                    if (state.postId == null) "Circaso’s team will look at $space. We’ll tell you what happened."
                    else "$space’s moderators will look at it. If it breaks Circaso’s rules, our team will act too. We’ll tell you what happened.",
                ) { CircasoButton("Done", actions.onDone) }
            }
        }
        return
    }
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(state.title, actions.onCancel) },
        banner = { if (!state.online) Banner("You’re offline. Reports need a connection.", CircasoIcon.Offline) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                state.post?.let { p -> item { QuotedPost(p) } }
                if (state.postId == null) item {
                    BasicText("Tell Circaso what’s wrong with ${state.space?.name ?: "this space"}. Space reports go to Circaso’s team only.", style = t.subhead.copy(color = c.inkSecondary))
                }
                item { SectionLabel(if (state.postId == null) "What’s wrong with it?" else "What’s wrong with it?") }
                item {
                    GroupedList {
                        ReportReason.entries.forEachIndexed { i, r ->
                            if (i > 0) RowDivider(Modifier.padding(start = CircasoSpacing.s4))
                            RadioRow(r.label, state.reason == r) { actions.onReason(r) }
                        }
                    }
                }
                item { CircasoTextField(state.detail, actions.onDetail, "Anything else (optional)", placeholder = "What happened", singleLine = false) }
                state.authorFirst?.takeIf { state.post?.mine == false }?.let { first ->
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s1), verticalAlignment = Alignment.CenterVertically) {
                            BasicText("Also block $first", Modifier.weight(1f), style = t.body.copy(color = c.ink))
                            CircasoSwitch(state.alsoBlock, actions.onAlsoBlock, Modifier.semantics { contentDescription = "Also block $first" })
                        }
                    }
                }
                state.error?.let { e ->
                    item { Banner(e + (state.errorRef?.let { " Ref $it" } ?: ""), CircasoIcon.Warning, tone = BannerTone.Critical) }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally) {
                CircasoButton("Send Report", actions.onSend, size = ButtonSize.Large, block = true, enabled = state.canSend, busy = state.sending)
                BasicText(
                    if (!state.online) "Needs a connection" else "The space’s team won’t see your name.",
                    style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                )
            }
        }
    }
}

@Composable
private fun QuotedPost(p: SpacePost) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.fill).padding(horizontal = CircasoSpacing.s3, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "The post: ${p.author.displayName}, ${p.text.orEmpty()}" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Avatar(p.author.displayName, size = CircasoSizes.avatarSm, imageUrl = p.author.avatarUrl)
        Column {
            BasicText(p.author.displayName.substringBefore(' ') + " · " + chatListTime(p.createdAt, com.circaso.core.sync.nowMillis()), style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
            BasicText(p.text ?: "Removed post", style = t.subhead.copy(color = c.ink))
        }
    }
}

// ---------------------------------------------------------------- SR4 reports queue

class SpaceReportsActions(
    val onBack: () -> Unit = {},
    val onSegment: (Boolean) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onAct: (ReportGroup, ReportAction) -> Unit = { _, _ -> },
    val onConfirmRemove: (String?) -> Unit = {},
)

@Composable
fun SpaceReportsEntry(route: SpaceReportsRoute, nav: SpacesNav) {
    val vm = koinViewModel<SpaceReportsViewModel>(key = "space-reports-" + route.spaceId, parameters = { parametersOf(route.spaceId) })
    val state by vm.state.collectAsState()
    val toast = rememberToastState()
    LaunchedEffect(vm) { vm.toasts.collect { toast.show(it) } }
    SpaceReportsContent(state, SpaceReportsActions(nav.back, vm::onSegment, vm::load, vm::onAct, vm::onConfirmRemove), toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) })
}

/** SR4–SR4d: open reports most reported first, with one-tap actions; Done keeps the record of who did what. */
@Composable
fun SpaceReportsContent(state: SpaceReportsUiState, actions: SpaceReportsActions, toast: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar("Reports", actions.onBack) },
        banner = { if (!state.online && state.reports.isNotEmpty()) Banner("You’re offline. Acting on reports needs a connection.", CircasoIcon.Offline) },
        toast = toast,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s1, bottom = padding.calculateBottomPadding() + CircasoSpacing.s6), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SegmentedControl(listOf(state.openCount?.takeIf { it > 0 }?.let { "Open · $it" } ?: "Open", "Done"), if (state.open) 0 else 1, { actions.onSegment(it == 0) }, Modifier.padding(bottom = 2.dp))
            }
            val error = state.error
            when {
                state.loading -> items(2) { SkeletonReport() }
                error != null -> item { Box(Modifier.padding(top = 90.dp)) { ErrorState("Reports didn’t load", error, state.errorRef, actions.onRetry, enabled = state.online) } }
                state.reports.isEmpty() -> item {
                    Box(Modifier.padding(top = 150.dp)) {
                        CenterState(
                            CircasoIcon.Shield, if (state.open) "No open reports" else "Nothing handled yet",
                            "When someone reports a post in ${state.space?.name ?: "this space"}, it shows here for your moderators and admins.",
                        )
                    }
                }
                else -> items(state.reports, key = { it.post.localKey }) { r -> ReportCard(r, state, actions) }
            }
        }
    }
}

@Composable
private fun ReportCard(r: ReportGroup, state: SpaceReportsUiState, actions: SpaceReportsActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val now = com.circaso.core.sync.nowMillis()
    val busy = state.acting == r.post.postId
    val name = r.post.author.displayName.substringBefore(' ')
    Column(
        Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface).padding(horizontal = CircasoSpacing.s4, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
            BasicText("${r.reason.label} · ${r.count} report${if (r.count == 1) "" else "s"}", Modifier.weight(1f), style = t.footnote.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold))
            (r.lastAt ?: r.firstAt)?.let { BasicText(chatListTime(it, now), style = t.footnote.copy(color = c.inkSecondary)) }
        }
        Row(Modifier.semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(r.post.author.displayName, size = CircasoSizes.avatarSm, imageUrl = r.post.author.avatarUrl)
            Column {
                BasicText(name, style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
                BasicText(r.post.text ?: if (r.post.removed != null) "Removed post" else "", style = t.subhead.copy(color = c.ink))
            }
        }
        if (r.open) {
            // Wraps on narrow phones and large text, as the canvas's flex-wrap.
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                val enabled = state.online && state.acting == null
                CircasoButton("Keep", { actions.onAct(r, ReportAction.Keep) }, style = ButtonStyle.Secondary, size = ButtonSize.Small, enabled = enabled)
                if (r.post.removed == null) CircasoButton("Remove Post", { actions.onAct(r, ReportAction.RemovePost) }, style = ButtonStyle.Secondary, size = ButtonSize.Small, enabled = enabled, busy = busy)
                CircasoButton("Mute", { actions.onAct(r, ReportAction.Mute) }, style = ButtonStyle.Secondary, size = ButtonSize.Small, enabled = enabled)
                if (state.canRemoveMember) Box {
                    CircasoButton("Remove $name", { actions.onConfirmRemove(r.post.postId) }, style = ButtonStyle.Destructive, size = ButtonSize.Small, enabled = enabled)
                    SpacePopover(state.confirmRemove == r.post.postId && r.post.postId != null, { actions.onConfirmRemove(null) }, "Remove $name?", width = 280.dp) {
                        PopoverConfirm("Remove $name?", "$name leaves the space and can follow it again. The report closes and the reporters are told.", "Remove",
                            onConfirm = { actions.onAct(r, ReportAction.RemoveMember) }, onCancel = { actions.onConfirmRemove(null) }, enabled = state.online)
                    }
                }
            }
        } else r.resolution?.let { res ->
            val what = when (res.action) { ReportAction.Keep -> "Kept"; ReportAction.RemovePost -> "Post removed"; ReportAction.Mute -> "$name muted"; ReportAction.RemoveMember -> "$name removed" }
            BasicText(listOfNotNull(what, res.by?.displayName?.let { "by $it" }, res.at?.let { chatListTime(it, now) }).joinToString(" · "), style = t.footnote.copy(color = c.inkSecondary))
        }
    }
}

@Composable
private fun SkeletonReport() {
    Column(
        Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(CircasoTheme.colors.surface).padding(horizontal = CircasoSpacing.s4, vertical = 14.dp)
            .semantics { contentDescription = "Loading reports" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SkeletonBlock(150.dp, 12.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonBlock(28.dp, 28.dp, shape = CircasoShapes.circle)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { SkeletonBlock(90.dp, 12.dp); SkeletonBlock(220.dp, 12.dp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) { SkeletonBlock(60.dp, 34.dp, shape = CircasoShapes.pill); SkeletonBlock(110.dp, 34.dp, shape = CircasoShapes.pill) }
    }
}
