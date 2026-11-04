package com.circaso.feature.spaces.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.BubbleMeta
import com.circaso.designsystem.components.ChatBubble
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.MessageColumn
import com.circaso.designsystem.components.bubbleShape
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.PostSendState
import com.circaso.feature.spaces.domain.RemovedBy
import com.circaso.feature.spaces.domain.SpacePost

/**
 * One space post (SH1): someone else's carries their photo, name and role badge above a quiet bubble; mine is the
 * action bubble on the right with its time, or Waiting with a clock while it is queued (SH1d), or Not sent. A removed
 * post is a tombstone ("Removed by a moderator", the moderator never named). Reactions are counted chips under it; a
 * tap on a chip reacts with that emoji (or takes mine back), a long press on the bubble opens the post's menu.
 */
@Composable
fun SpacePostItem(
    post: SpacePost,
    modifier: Modifier = Modifier,
    /** The time under my own posts ("18:04"). */
    time: String? = null,
    onReact: ((emoji: String?) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val removed = post.removed != null
    if (post.mine && !removed) {
        MessageColumn(outgoing = true, modifier = modifier) {
            ChatBubble(
                post.text.orEmpty(), outgoing = true, faded = post.sendState == PostSendState.Waiting,
                above = post.replyTo?.let { q -> { Quote(q.authorName, if (q.removed) "Removed post" else q.text.orEmpty(), outgoing = true) } },
                onLongClick = onLongPress,
            )
            when (post.sendState) {
                PostSendState.Waiting -> BubbleMeta("Waiting", icon = CircasoIcon.Clock, modifier = Modifier.semantics { contentDescription = "Waiting to send" })
                PostSendState.Failed -> Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BubbleMeta(failedText(post.error), icon = CircasoIcon.Warning, color = c.critical)
                    if (onRetry != null) BasicText("Retry", Modifier.pressable(onClick = onRetry).padding(4.dp), style = t.caption1.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
                }
                PostSendState.Sent -> time?.let { BubbleMeta(it) }
            }
            Reactions(post, onReact, alignEnd = true)
        }
        return
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.Top) {
        Avatar(post.author.displayName, size = CircasoSizes.avatarSm, imageUrl = post.author.avatarUrl)
        Column(Modifier.widthIn(max = 292.dp)) {
            Row(Modifier.padding(start = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(post.author.displayName.substringBefore(' ').ifBlank { post.author.displayName }, style = t.footnote.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                RoleBadge(post.author.role)
            }
            if (removed) {
                Row(
                    Modifier.clip(bubbleShape(false)).border(1.dp, c.separator, bubbleShape(false)).padding(horizontal = 14.dp, vertical = 9.dp)
                        .semantics(mergeDescendants = true) { },
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircasoIconImage(CircasoIcon.Ban, c.inkSecondary, Modifier.size(15.dp), strokeWidth = 1.8f)
                    BasicText(if (post.removed == RemovedBy.Author) "Removed by its author" else "Removed by a moderator", style = t.body.copy(color = c.inkSecondary, fontStyle = FontStyle.Italic))
                }
            } else {
                ChatBubble(
                    post.text.orEmpty(), outgoing = false,
                    above = post.replyTo?.let { q -> { Quote(q.authorName, if (q.removed) "Removed post" else q.text.orEmpty(), outgoing = false) } },
                    onLongClick = onLongPress,
                )
                Reactions(post, onReact, alignEnd = false)
            }
        }
    }
}

@Composable
private fun Quote(name: String, text: String, outgoing: Boolean) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(if (outgoing) c.onAction.copy(alpha = 0.16f) else c.fillStrong)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        BasicText(name, style = t.footnote.copy(color = if (outgoing) c.onAction else c.ink, fontWeight = FontWeight.SemiBold))
        BasicText(text, style = t.footnote.copy(color = if (outgoing) c.onAction else c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Counted reaction chips (canvas `.reacts`): mine has an ink edge; each says its count and whether it is mine. */
@Composable
private fun Reactions(post: SpacePost, onReact: ((String?) -> Unit)?, alignEnd: Boolean) {
    if (post.reactions.isEmpty()) return
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.padding(start = if (alignEnd) 0.dp else 4.dp, top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, if (alignEnd) Alignment.End else Alignment.Start),
    ) {
        post.reactions.take(6).forEach { r ->
            val mine = r.emoji == post.myReaction
            Row(
                Modifier.heightIn(min = 24.dp).clip(CircasoShapes.pill).background(c.surface)
                    .border(if (mine) 1.5.dp else 1.dp, if (mine) c.ink else c.separator, CircasoShapes.pill)
                    .then(if (onReact != null && post.postId != null) Modifier.pressable(role = Role.Button) { onReact(if (mine) null else r.emoji) } else Modifier)
                    .semantics(mergeDescendants = true) {
                        selected = mine
                        contentDescription = "${r.emoji} ${r.count}" + if (mine) ", including you" else ""
                    }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                BasicText(r.emoji, style = t.footnote)
                BasicText(countText(r.count), style = t.caption1.copy(color = c.ink, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"))
                if (mine && post.reactionWaiting) { Spacer(Modifier.width(1.dp)); CircasoIconImage(CircasoIcon.Clock, c.inkSecondary, Modifier.size(10.dp)) }
            }
        }
    }
}

/** Copy for a post the server refused, keyed on its code (never the message). */
fun failedText(code: String?): String = when (code) {
    "spaces.muted" -> "Not sent. You’re muted here"
    "spaces.posting_restricted" -> "Not sent. Only admins post here"
    "spaces.membership_ended" -> "Not sent. Your membership ended"
    "spaces.not_member", "spaces.banned" -> "Not sent. You’re not in this space"
    "spaces.too_many_posts" -> "Not sent. Too many posts, try later"
    "outbox.stale" -> "Not sent. It waited too long"
    else -> "Not sent"
}
