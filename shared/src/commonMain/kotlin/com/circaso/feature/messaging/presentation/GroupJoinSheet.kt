package com.circaso.feature.messaging.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.SheetActions
import com.circaso.designsystem.chrome.SheetMessage
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.SkeletonBlock
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.messaging.domain.GroupLinkPreview

/**
 * The join sheet's place over the whole app (the navigation's top box, beside the share-link layer): a group join
 * link opened from anywhere lands here, and the chat opens once the person is in (or already was).
 */
@Composable
fun BoxScope.GroupJoinLayer(viewModel: GroupJoinViewModel, onOpenChat: (chatId: String) -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.openChatId) { state.openChatId?.let { onOpenChat(it); viewModel.onChatOpened() } }
    GroupJoinSheet(state, onJoin = viewModel::onJoin, onRetry = viewModel::load, onDismiss = viewModel::onDismiss)
}

/**
 * A group join link opened on this phone (Pasaka, 2026-11-14). No frame in the canvas: it is the group-invite
 * preview sheet (photo, name, size, Join Group) with the description the link published, and the message-sheet
 * pattern for a link that no longer works. Only what the group's admin published is shown; joining is one tap, and
 * the sheet says what joining shows before it. The last stage is kept while the sheet slides away, so it never
 * empties on the way out.
 */
@Composable
fun BoxScope.GroupJoinSheet(
    state: GroupJoinUiState,
    onJoin: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf(state.stage) }
    if (state.open) shown = state.stage
    val label = (shown as? GroupJoinStage.Ready)?.let { "Join ${it.preview.name}" } ?: "Group link"
    // While a join is on its way the sheet stays: closing it then would leave them in a group they never saw open.
    CircasoSheet(visible = state.open, onDismiss = onDismiss, label = label, dismissible = !state.joining) {
        when (val stage = shown) {
            GroupJoinStage.Loading -> JoinLoading()
            is GroupJoinStage.Ready -> JoinPreview(stage.preview, stage.problem, state.online, state.joining, onJoin, onDismiss)
            GroupJoinStage.Gone -> {
                SheetMessage("This link no longer works", "It was reset or turned off. Ask an admin of the group for a new one.", CircasoIcon.Link)
                CircasoButton("OK", onDismiss, size = ButtonSize.Large, block = true)
            }
            GroupJoinStage.NotAllowed -> {
                SheetMessage("You can't join with this link", "Ask an admin of the group to add you.", CircasoIcon.Ban)
                CircasoButton("OK", onDismiss, size = ButtonSize.Large, block = true)
            }
            GroupJoinStage.Offline -> {
                SheetMessage("You're offline", "The group shows here by itself when you're back online.", CircasoIcon.Offline)
                CircasoButton("Close", onDismiss, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
            }
            is GroupJoinStage.Failed -> {
                SheetMessage("Couldn't open the link", stage.text, CircasoIcon.Warning)
                SheetActions("Try Again", onRetry, "Close", onDismiss)
            }
        }
    }
}

/** The group as its link shows it: photo, name, size, description; then what joining gives, and the one action. */
@Composable
private fun JoinPreview(
    preview: GroupLinkPreview,
    problem: String?,
    online: Boolean,
    joining: Boolean,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Box(Modifier.padding(bottom = CircasoSpacing.s1)) { Avatar(preview.name, size = CircasoSizes.avatarXl, imageUrl = preview.photoUrl) }
        BasicText(
            preview.name, Modifier.semantics { heading() },
            style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        BasicText("Group · ${memberCountText(preview.memberCount)}", style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
    preview.description?.let {
        BasicText(it, Modifier.fillMaxWidth(), style = t.body.copy(color = c.ink), maxLines = DescriptionLines, overflow = TextOverflow.Ellipsis)
    }
    BasicText(JoinNote, style = t.footnote.copy(color = c.inkSecondary))
    problem?.let { BasicText(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = t.footnote.copy(color = c.critical)) }
    if (!online) BasicText(JoinOffline, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = t.footnote.copy(color = c.inkTertiary))
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        CircasoButton("Join Group", onJoin, size = ButtonSize.Large, block = true, enabled = online, busy = joining)
        CircasoButton("Not Now", onDismiss, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true, enabled = !joining)
    }
}

/** While the link is looked up: the preview's own shapes, so the group settles into them. */
@Composable
private fun JoinLoading() {
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = "Opening the group link" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        SkeletonBlock(Modifier.size(CircasoSizes.avatarXl), CircasoShapes.circle, color = CircasoTheme.colors.fillStrong)
        SkeletonBlock(Modifier.fillMaxWidth(0.55f).height(24.dp), CircasoShapes.sm, color = CircasoTheme.colors.fillStrong)
        SkeletonBlock(Modifier.fillMaxWidth(0.3f).height(16.dp), CircasoShapes.sm, color = CircasoTheme.colors.fillStrong)
        SkeletonBlock(Modifier.fillMaxWidth().height(50.dp), CircasoShapes.lg, color = CircasoTheme.colors.fillStrong)
    }
}

/** A long description is cut on the sheet (it reads whole in Group info once they're in). */
private const val DescriptionLines = 6
