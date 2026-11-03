package com.circaso.feature.status.presentation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.SheetHead
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.TextBadge
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.status.domain.MomentRing

/** What the Spaces tab can open. */
class SpacesActions(
    val onOpenRing: (posterId: String) -> Unit,
    val onOpenMine: () -> Unit,
    val onCompose: () -> Unit,
    val onFindPeople: () -> Unit,
)

@Composable
fun SpacesTab(viewModel: SpacesViewModel, bottomPadding: PaddingValues, actions: SpacesActions) {
    val state by viewModel.state.collectAsState()
    SpacesContent(state, bottomPadding, actions, onNew = { viewModel.onNew(true) }, onRetry = viewModel::refresh,
        onRetryPost = viewModel::onRetryPost, onDeletePost = viewModel::onDeletePost)
}

/** The tab's sheet (S2), in the home scaffold's overlay so it covers the tab bar. */
@Composable
fun BoxScope.SpacesOverlay(viewModel: SpacesViewModel, actions: SpacesActions) {
    val state by viewModel.state.collectAsState()
    NewSheet(state.newSheet, onDismiss = { viewModel.onNew(false) }, onMoment = { viewModel.onNew(false); actions.onCompose() })
}

@Composable
fun SpacesBanner() = com.circaso.core.sync.ConnectionBanner(showing = "Showing moments saved on this phone")

/** Design S1 (phase 1): the Moments row on top, then the honest "Spaces are coming" card until phase 3. */
@Composable
fun SpacesContent(
    state: SpacesUiState,
    bottomPadding: PaddingValues,
    actions: SpacesActions,
    onNew: () -> Unit,
    onRetry: () -> Unit,
    onRetryPost: (String) -> Unit = {},
    onDeletePost: (String) -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + CircasoSpacing.s4)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = CircasoSpacing.s4, end = CircasoSpacing.s3, top = CircasoSpacing.s3, bottom = CircasoSpacing.s1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText("Spaces", Modifier.weight(1f).semantics { heading() }, style = t.title1.copy(color = c.ink, fontWeight = FontWeight.Bold))
                    CircasoIconButton(CircasoIcon.Plus, "New moment or space", onNew)
                }
            }
            item { SectionLabel("Moments") }
            item { MomentsRow(state, actions) }
            // Nothing to show is never an error screen (Pasaka, 2026-11-03): the empty state stays, and a failed check
            // is one quiet line under it with Try Again and the reference for support.
            if (state.failed || (state.empty && !state.loading)) item { NoMoments(state.hasContacts, actions) }
            if (state.failed) item { CheckFailed(state.requestId, onRetry) }
            item {
                Column(Modifier.padding(top = CircasoSpacing.s5)) {
                    SectionLabel("Spaces")
                    ComingCard()
                }
            }
        }
        // M6: a moment that won't post says so, with Retry or Delete; nothing is lost silently.
        state.failedPost?.let { failed ->
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding.calculateBottomPadding() + CircasoSpacing.s3).padding(horizontal = CircasoSpacing.s4)
                    .fillMaxWidth().clip(CircasoShapes.md).background(c.surfaceRaised).border(1.dp, c.separator, CircasoShapes.md)
                    .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3)
                    .semantics(mergeDescendants = false) { },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
            ) {
                CircasoIconImage(CircasoIcon.Warning, c.critical, Modifier.size(18.dp))
                BasicText(postError(failed.error), Modifier.weight(1f), style = t.subhead.copy(color = c.ink))
                CircasoButton("Retry", { onRetryPost(failed.localId) }, style = ButtonStyle.Plain, size = ButtonSize.Small)
                CircasoButton("Delete", { onDeletePost(failed.localId) }, style = ButtonStyle.Destructive, size = ButtonSize.Small)
            }
        }
    }
}

private fun postError(code: String?) = when (code) {
    "status.audience_invalid" -> "None of your contacts can see moments yet, so it didn't post."
    "outbox.stale" -> "Your moment waited too long and didn't post."
    "status.media_invalid", "status.moment_too_large" -> "Your moment was too large to post. Try a shorter video."
    "status.too_many_moments" -> "You’ve shared a lot of moments today. Try again later."
    else -> "Your moment didn’t post."
}

@Composable
private fun SectionLabel(text: String) {
    BasicText(
        text,
        Modifier.padding(horizontal = CircasoSpacing.s5, vertical = CircasoSpacing.s2).semantics { heading() },
        style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary),
    )
}

@Composable
private fun MomentsRow(state: SpacesUiState, actions: SpacesActions) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = CircasoSpacing.s3),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "me") { MyMomentItem(state, actions) }
        if (state.loading) {
            items(4) { SkeletonRing() }
        } else {
            items(state.rings, key = { it.posterId }) { ring -> RingItem(ring, state.online) { actions.onOpenRing(ring.posterId) } }
        }
    }
}

@Composable
private fun MyMomentItem(state: SpacesUiState, actions: SpacesActions) {
    val c = CircasoTheme.colors
    val ring = state.myRing
    val (label, name) = when (ring) {
        MyRing.Add -> "Add to my moment" to "My moment"
        MyRing.Seen -> "My moment, add to it" to "My moment"
        MyRing.Posting -> "My moment, posting" to "Posting…"
        MyRing.Waiting -> "My moment, waiting for a connection" to "Waiting"
        MyRing.Failed -> "My moment, not posted" to "Not posted"
    }
    MomentItem(
        name = name, label = label, ring = if (ring == MyRing.Seen) RingStyle.Seen else RingStyle.None,
        onClick = if (ring == MyRing.Seen) actions.onOpenMine else actions.onCompose,
    ) {
        Avatar(state.myName.ifBlank { "Me" }, size = CircasoSizes.avatarLg, imageUrl = state.myAvatarUrl)
        when (ring) {
            MyRing.Posting -> PostingArc()
            MyRing.Waiting -> Badge(c.fillStrong) { CircasoIconImage(CircasoIcon.Clock, c.ink, Modifier.size(13.dp)) }
            MyRing.Failed -> Badge(c.critical) { BasicText("!", style = CircasoTheme.typography.footnote.copy(color = c.bg, fontWeight = FontWeight.Bold)) }
            else -> Badge(c.action) { CircasoIconImage(CircasoIcon.Plus, c.onAction, Modifier.size(13.dp)) }
        }
    }
}

@Composable
private fun RingItem(ring: MomentRing, online: Boolean, onClick: () -> Unit) {
    val state = if (ring.unseen && !ring.muted) "new moment" else "seen"
    MomentItem(
        name = ring.name.substringBefore(' '), label = "${ring.name}, $state" + if (ring.muted) ", muted" else "",
        ring = if (ring.unseen && !ring.muted) RingStyle.Unseen else RingStyle.Seen, dimmed = ring.muted, onClick = onClick,
    ) {
        Avatar(ring.name, size = CircasoSizes.avatarLg, imageUrl = ring.avatarUrl)
    }
}

private enum class RingStyle { Unseen, Seen, None }

/** One ring and its name (design `.mo`): ink ring when there is something new, a quiet one when seen. */
@Composable
private fun MomentItem(
    name: String, label: String, ring: RingStyle, onClick: () -> Unit, dimmed: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.width(CircasoSizes.momentItem).pressable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ringColor = when (ring) { RingStyle.Unseen -> c.ink; RingStyle.Seen -> c.fillStrong; RingStyle.None -> androidx.compose.ui.graphics.Color.Transparent }
        Box(
            Modifier.size(CircasoSizes.momentRing).border(2.5.dp, ringColor, CircasoShapes.circle).padding(3.dp).alpha(if (dimmed) 0.5f else 1f),
            contentAlignment = Alignment.Center,
            content = content,
        )
        BasicText(
            name, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = t.caption1.copy(color = if (ring == RingStyle.Seen) c.inkSecondary else c.ink, textAlign = TextAlign.Center),
        )
    }
}

@Composable
private fun BoxScope.Badge(color: androidx.compose.ui.graphics.Color, content: @Composable BoxScope.() -> Unit) {
    val c = CircasoTheme.colors
    Box(
        Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(22.dp).border(2.5.dp, c.bg, CircasoShapes.circle).padding(2.dp)
            .clip(CircasoShapes.circle).background(color),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Upload progress around my ring while a moment posts; it turns slowly so it never looks stuck. */
@Composable
private fun PostingArc() {
    val c = CircasoTheme.colors
    val reduce = CircasoTheme.reduceMotion
    val angle = if (reduce) 0f else rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart)).value
    Canvas(Modifier.size(CircasoSizes.momentRing).semantics { contentDescription = "Posting" }) {
        val w = 2.5.dp.toPx()
        drawArc(c.fillStrong, 0f, 360f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
        drawArc(c.ink, angle - 90f, 220f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
    }
}

@Composable
private fun SkeletonRing() {
    val c = CircasoTheme.colors
    Column(Modifier.width(CircasoSizes.momentItem), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(CircasoSizes.momentRing).clip(CircasoShapes.circle).background(c.fill))
        Box(Modifier.size(48.dp, 10.dp).clip(RoundedCornerShape(5.dp)).background(c.fill))
    }
}

/** S1c: nobody has posted; the line says who mine will reach. */
@Composable
private fun NoMoments(hasContacts: Boolean, actions: SpacesActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        CircasoIconImage(CircasoIcon.Spaces, c.inkTertiary, Modifier.size(44.dp))
        BasicText(if (hasContacts) "No moments yet" else "Find your people to see their moments", style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(
            "When your contacts share a moment, it shows here for 24 hours. Yours go to people who have your number saved, and whose number you have saved.",
            style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
        )
        if (hasContacts) CircasoButton("Share a Moment", actions.onCompose, icon = CircasoIcon.Camera)
        else CircasoButton("Find People", actions.onFindPeople)
    }
}

/** S1f, quiet form: the check for new moments failed; the empty state above stays as it is. */
@Composable
private fun CheckFailed(requestId: String?, onRetry: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
            BasicText("Couldn’t check for new moments.", style = t.footnote.copy(color = c.inkSecondary))
            CircasoButton("Try Again", onRetry, style = ButtonStyle.Plain, size = ButtonSize.Small)
        }
        requestId?.let { BasicText("Ref $it", style = t.caption2.copy(color = c.inkTertiary)) }
    }
}

/** Phase 1: spaces aren't built yet, said plainly (design S1). */
@Composable
private fun ComingCard() {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.padding(horizontal = CircasoSpacing.s4).fillMaxWidth().clip(CircasoShapes.lg).background(c.surface)
            .border(1.dp, c.separator, CircasoShapes.lg).padding(horizontal = CircasoSpacing.s4, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircasoIconImage(CircasoIcon.Spaces, c.ink, Modifier.size(22.dp))
            BasicText("Spaces are coming", style = t.headline.copy(color = c.ink))
        }
        BasicText("Communities and small shops, each with its own chat, shop and wallet. Moments are here now.", style = t.subhead.copy(color = c.inkSecondary))
    }
}

/** S2: the + button. Moment goes to the camera; Space is coming in phase 3. */
@Composable
private fun BoxScope.NewSheet(visible: Boolean, onDismiss: () -> Unit, onMoment: () -> Unit) {
    val c = CircasoTheme.colors
    CircasoSheet(visible, onDismiss, label = "New") {
        SheetHead("New", onDismiss)
        ListGroup {
            ListRow("Moment", sub = "Text, photo, video or voice for 24 hours", leading = { IconDisc(CircasoIcon.Camera) }, trailing = { RowChevron() }, onClick = onMoment)
            ListRow("Space", sub = "A community or shop with a chat and a wallet", leading = { IconDisc(CircasoIcon.Spaces) }, trailing = { TextBadge("Soon") })
        }
        Box(Modifier.size(1.dp).background(c.bg))
    }
}

@Composable
private fun IconDisc(icon: CircasoIcon) {
    val c = CircasoTheme.colors
    Box(Modifier.size(CircasoSizes.iconButton).clip(CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
        CircasoIconImage(icon, c.ink, Modifier.size(18.dp))
    }
}
