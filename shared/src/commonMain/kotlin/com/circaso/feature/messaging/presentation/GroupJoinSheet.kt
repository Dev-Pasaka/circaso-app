package com.circaso.feature.messaging.presentation

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import com.circaso.designsystem.theme.CircasoMotion
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
    // What the sheet showed last is held while it slides away: its stage, and whether it was joining or offline, so
    // the button never snaps back to "Join Group" on the way out.
    var shown by remember { mutableStateOf(state) }
    if (state.open) shown = state
    val label = (shown.stage as? GroupJoinStage.Ready)?.let { "Join ${it.preview.name}" } ?: "Group link"
    // While a join is on its way the sheet stays: closing it then would leave them in a group they never saw open.
    CircasoSheet(visible = state.open, onDismiss = onDismiss, label = label, dismissible = !state.joining) {
        // The group settles into the loading shapes' place: the sheet grows to it rather than jumping.
        Column(
            if (CircasoTheme.reduceMotion) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().animateContentSize(tween(CircasoMotion.BaseMs)),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
        ) {
            when (val stage = shown.stage) {
                GroupJoinStage.Loading -> JoinLoading()
                is GroupJoinStage.Ready -> JoinPreview(stage.preview, stage.problem, shown.online, shown.joining, onJoin, onDismiss)
                GroupJoinStage.Gone -> {
                    SheetMessage("This link no longer works", "It was reset or turned off. Ask an admin of the group for a new one.", CircasoIcon.Link)
                    CircasoButton("OK", onDismiss, size = ButtonSize.Large, block = true)
                }
                GroupJoinStage.NotAllowed -> {
                    SheetMessage("You can't join with this link", "Ask an admin of the group to add you.", CircasoIcon.Ban)
                    CircasoButton("OK", onDismiss, size = ButtonSize.Large, block = true)
                }
                GroupJoinStage.Offline -> if (shown.online) {
                    // The phone says it is online, so nothing will retry by itself: the person can.
                    SheetMessage("Couldn't reach Circaso", "Check your connection and try again.", CircasoIcon.Offline)
                    SheetActions("Try Again", onRetry, "Close", onDismiss)
                } else {
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
    // The whole description is one tap away: it is what they are deciding on.
    preview.description?.let { GroupDescription(it, Modifier.fillMaxWidth(), lines = DescriptionLines) }
    BasicText(JoinNote, style = t.footnote.copy(color = c.inkSecondary))
    // Offline, the one line that matters is that joining needs a connection; a failed try isn't said on top of it.
    if (!online) BasicText(JoinOffline, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = t.footnote.copy(color = c.inkTertiary))
    else problem?.let { BasicText(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = t.footnote.copy(color = c.critical)) }
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        CircasoButton("Join Group", onJoin, size = ButtonSize.Large, block = true, enabled = online, busy = joining)
        CircasoButton("Not Now", onDismiss, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true, enabled = !joining)
    }
}

/** While the link is looked up: the preview's own shapes (photo, name, size, two lines, two buttons), so the group settles into them. */
@Composable
private fun JoinLoading() {
    val fill = CircasoTheme.colors.fillStrong
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = "Opening the group link" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        SkeletonBlock(Modifier.size(CircasoSizes.avatarXl), CircasoShapes.circle, color = fill)
        SkeletonBlock(Modifier.fillMaxWidth(0.55f).height(SkeletonTitleLine), CircasoShapes.sm, color = fill)
        SkeletonBlock(Modifier.fillMaxWidth(0.3f).height(SkeletonTextLine), CircasoShapes.sm, color = fill)
        SkeletonBlock(Modifier.fillMaxWidth().height(SkeletonTextLine), CircasoShapes.sm, color = fill)
        SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(SkeletonTextLine), CircasoShapes.sm, color = fill)
        SkeletonBlock(Modifier.fillMaxWidth().height(CircasoSizes.controlLg), CircasoShapes.md, color = fill)
        SkeletonBlock(Modifier.fillMaxWidth().height(CircasoSizes.controlLg), CircasoShapes.md, color = fill)
    }
}

private val SkeletonTitleLine = 24.dp
private val SkeletonTextLine = 16.dp

/** How much of a description the sheet shows before "Show all". */
private const val DescriptionLines = 4
