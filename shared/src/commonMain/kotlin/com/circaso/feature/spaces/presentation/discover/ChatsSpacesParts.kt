package com.circaso.feature.spaces.presentation.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.MySpace
import com.circaso.feature.spaces.domain.ReceivedInvite
import com.circaso.feature.spaces.presentation.SpaceTile
import com.circaso.feature.spaces.presentation.visibilityIcon

/**
 * What the Chats tab gets from spaces (SI2, SH2d): the invite cards, the private chats' labels and the Spaces chip.
 * Built from [ChatsSpacesUiState] by the caller, so Chats stays free of spaces' ViewModels.
 */
class ChatsSpaces(
    val state: ChatsSpacesUiState,
    val onAccept: (ReceivedInvite) -> Unit,
    val onDecline: (ReceivedInvite) -> Unit,
    val onUndo: (inviteId: String) -> Unit,
    val onOpenInvite: (ReceivedInvite) -> Unit,
    val onOpenSpace: (MySpace) -> Unit,
    val now: Long,
    /** Chats came into view: the invite cards are re-read (they are live only). */
    val onShown: () -> Unit = {},
)

/**
 * SI2: an invite at the top of Chats with Accept and Decline in place; the card opens the space's preview (SI2b).
 * SI3b: after Decline it folds to one line with Undo, and the inviter is never told.
 */
@Composable
fun SpaceInviteCard(invite: ReceivedInvite, declining: Boolean, accepting: Boolean, online: Boolean, now: Long, spaces: ChatsSpaces) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val still = CircasoTheme.reduceMotion
    val inviter = invite.invitedBy.firstName
    AnimatedContent(
        declining,
        transitionSpec = { if (still) fadeIn(tween(0)) togetherWith fadeOut(tween(0)) else fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs)) },
        label = "invite",
    ) { folded ->
        Column(
            Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1).fillMaxWidth().clip(CircasoShapes.lg).background(c.surface)
                .border(1.dp, c.separator, CircasoShapes.lg).padding(horizontal = CircasoSpacing.s4, vertical = 14.dp)
                .semantics(mergeDescendants = folded) { if (folded) liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        ) {
            if (folded) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    BasicText("Invite declined. $inviter isn’t told.", Modifier.weight(1f), style = t.subhead.copy(color = c.inkSecondary))
                    CircasoButton("Undo", { spaces.onUndo(invite.inviteId) }, style = ButtonStyle.Plain, size = ButtonSize.Small)
                }
                return@Column
            }
            Row(
                Modifier.fillMaxWidth().pressable { spaces.onOpenInvite(invite) }
                    .semantics(mergeDescendants = true) { contentDescription = "Invite to ${invite.space.name}, ${invite.space.visibility.label}, ${invite.space.memberCount} members. Open" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
            ) {
                SpaceTile(invite.space, 44.dp)
                Column(Modifier.weight(1f)) {
                    BasicText(invite.space.name, style = t.callout.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CircasoIconImage(visibilityIcon(invite.space.visibility), c.inkSecondary, Modifier.size(11.dp), strokeWidth = 2.4f)
                        BasicText("${invite.space.visibility.label} · ${invite.space.memberCount} members", style = t.footnote.copy(color = c.inkSecondary))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                Avatar(invite.invitedBy.displayName, size = 20.dp, imageUrl = invite.invitedBy.avatarUrl)
                BasicText("$inviter invited you · ${expiresText(invite.expiresAt, now)}", style = t.subhead.copy(color = c.ink))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                CircasoButton("Accept", { spaces.onAccept(invite) }, Modifier.weight(1f), block = true, busy = accepting, enabled = online)
                CircasoButton("Decline", { spaces.onDecline(invite) }, Modifier.weight(1f), style = ButtonStyle.Secondary, block = true, enabled = online && !accepting)
            }
            if (!online) BasicText("Needs a connection", Modifier.fillMaxWidth(), style = t.footnote.copy(color = c.inkSecondary))
        }
    }
}

/** "expires in 14 days", "expires today". */
internal fun expiresText(expiresAt: Long, now: Long): String {
    if (expiresAt <= 0) return "expires in 14 days"
    val days = ((expiresAt - now) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "expires today"
        days == 1 -> "expires tomorrow"
        else -> "expires in $days days"
    }
}

/** SH2d: the small label on a private chat that came through a space ("Private · Sneaker Drop"). */
@Composable
fun PrivateSpaceLabel(spaceName: String) {
    val c = CircasoTheme.colors
    Row(
        Modifier.clip(CircasoShapes.pill).background(c.fill).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        CircasoIconImage(CircasoIcon.Lock, c.inkSecondary, Modifier.size(9.dp), strokeWidth = 2.4f)
        BasicText("Private · ${shortSpaceName(spaceName)}", style = CircasoTheme.typography.caption2.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Nairobi Sneaker Drop" → "Sneaker Drop" when a place word leads it (the frame's label), else the name. */
internal fun shortSpaceName(name: String): String {
    val words = name.split(' ').filter { it.isNotBlank() }
    return if (words.size >= 3) words.drop(1).joinToString(" ") else name
}
