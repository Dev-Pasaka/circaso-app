package com.circaso.feature.messaging.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.messaging.domain.GroupInvite

/**
 * Group invites (Pasaka, 2026-11-09: "They should see an inbox and into the group invite and choose to decline or
 * accept"). Not in the canvas: built from its parts, the glass invite card (`InviteCard`) for the row and the confirm
 * sheet's pattern for the preview. What an invite shows is only what the person is being asked into: the group's name
 * and photo, how many are in it and who asked; never its members or messages.
 */
class InviteActions(
    val onAccept: (inviteId: String) -> Unit,
    val onDecline: (inviteId: String) -> Unit,
    val onPreview: (GroupInvite) -> Unit,
)

/** The group's name, or "A group" when the inviter's phone couldn't seal its key for me (an older app). */
internal fun inviteTitle(invite: GroupInvite): String = invite.groupName ?: "A group"

/** "Amina invited you · 4 members": the inviter's first name, and the group's size. */
internal fun inviteLine(invite: GroupInvite): String {
    val who = invite.invitedBy.displayName.trim().substringBefore(' ').ifEmpty { "Someone" }
    val count = invite.memberCount
    val size = if (count == 1) "1 member" else "$count members"
    return "$who invited you · $size"
}

/** Said only in an invite's last week: "Expires today", "Expires tomorrow", "Expires in 3 days". Null before that. */
internal fun inviteExpiry(invite: GroupInvite, now: Long): String? {
    val left = (invite.expiresAt ?: return null) - now
    if (left <= 0 || left > ExpiryWarnMillis) return null
    val days = (left / DayMillis).toInt()
    return when (days) { 0 -> "Expires today"; 1 -> "Expires tomorrow"; else -> "Expires in $days days" }
}

/** Under two days left: the expiry line takes the warning colour. */
private fun expiresSoon(invite: GroupInvite, now: Long) = (invite.expiresAt ?: Long.MAX_VALUE) - now < 2 * DayMillis

private const val DayMillis = 24L * 60 * 60 * 1000
private const val ExpiryWarnMillis = 7 * DayMillis

/**
 * The group's picture: its photo, else its initials, else (a group whose name I can't read) the people glyph, never
 * initials made from "A group".
 */
@Composable
private fun InviteGroupAvatar(invite: GroupInvite, size: Dp) {
    if (invite.groupName != null || invite.groupPhoto != null) {
        Avatar(inviteTitle(invite), size = size, imageUrl = invite.groupPhoto)
        return
    }
    val c = CircasoTheme.colors
    Box(Modifier.size(size).clip(CircasoShapes.circle).background(c.fillStrong).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        CircasoIconImage(CircasoIcon.People, c.ink, Modifier.size(size * 0.45f))
    }
}

/**
 * One invite at the top of Chats: the group's photo and name, who asked, and Decline / Join side by side, Join the
 * main action, both full 44 dp targets with room between them. The card opens the preview. Offline the buttons rest
 * and the card says why; an answer in flight spins on its own button only; a failure is said on the card it belongs
 * to, and read out.
 */
@Composable
internal fun InviteCardRow(
    invite: GroupInvite,
    actions: InviteActions,
    online: Boolean,
    answering: Boolean,
    error: String?,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val title = inviteTitle(invite)
    val line = inviteLine(invite)
    val expiry = inviteExpiry(invite, now)
    Column(
        modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1).fillMaxWidth()
            .glass(CircasoShapes.lg)
            .padding(CircasoSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(CircasoShapes.md)
                .pressable(onClick = { actions.onPreview(invite) })
                .semantics(mergeDescendants = true) { contentDescription = "Invite to $title. $line" + (expiry?.let { ". $it" } ?: "") },
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InviteGroupAvatar(invite, CircasoSizes.avatarMd)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                BasicText(title, style = t.headline.copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(line, style = t.footnote.copy(color = c.inkSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                expiry?.let { BasicText(it, style = t.footnote.copy(color = if (expiresSoon(invite, now)) c.warning else c.inkTertiary), maxLines = 1) }
            }
        }
        InviteNotes(error, online)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            CircasoButton(
                "Decline", { actions.onDecline(invite.inviteId) }, Modifier.weight(1f),
                style = ButtonStyle.Secondary, size = ButtonSize.Medium, block = true, enabled = online && !answering,
            )
            CircasoButton(
                "Join", { actions.onAccept(invite.inviteId) }, Modifier.weight(1f),
                size = ButtonSize.Medium, block = true, enabled = online, busy = answering,
            )
        }
    }
}

/** An answer's failure and the offline reason, announced as they appear (focus stays on the button). */
@Composable
private fun InviteNotes(error: String?, online: Boolean) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    error?.let { BasicText(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = t.footnote.copy(color = c.critical)) }
    if (!online) BasicText(AnswerOffline, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = t.footnote.copy(color = c.inkTertiary))
}

/**
 * The invite opened: a small preview of the group (photo, name, size, who asked) and the two answers, Join first.
 * Joining shows only what is sent from then on, which the sheet says, so nobody expects the old messages. The last
 * invite is kept while the sheet slides away, so it never empties on the way out.
 */
@Composable
internal fun BoxScope.InvitePreviewSheet(
    invite: GroupInvite?,
    actions: InviteActions,
    online: Boolean,
    answering: Boolean,
    error: String?,
    now: Long,
    onDismiss: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    var shown by remember { mutableStateOf(invite) }
    if (invite != null) shown = invite
    CircasoSheet(visible = invite != null, onDismiss = onDismiss, label = shown?.let { "Invite to ${inviteTitle(it)}" } ?: "Group invite") {
        val last = shown
        if (last != null) {
            val title = inviteTitle(last)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                Box(Modifier.padding(bottom = CircasoSpacing.s1)) { InviteGroupAvatar(last, CircasoSizes.avatarXl) }
                BasicText(
                    title, Modifier.semantics { heading() },
                    style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    if (last.memberCount == 1) "1 member" else "${last.memberCount} members",
                    style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                )
                inviteExpiry(last, now)?.let {
                    BasicText(it, style = t.footnote.copy(color = if (expiresSoon(last, now)) c.warning else c.inkTertiary, textAlign = TextAlign.Center))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                Avatar(last.invitedBy.displayName, size = CircasoSizes.avatarSm, imageUrl = last.invitedBy.avatarUrl)
                BasicText("${last.invitedBy.displayName} invited you", Modifier.weight(1f), style = t.body.copy(color = c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            BasicText(JoinNote, style = t.footnote.copy(color = c.inkSecondary))
            InviteNotes(error, online)
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                CircasoButton("Join Group", { actions.onAccept(last.inviteId) }, size = ButtonSize.Large, block = true, enabled = online, busy = answering)
                CircasoButton("Decline", { actions.onDecline(last.inviteId) }, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true, enabled = online && !answering)
            }
        }
    }
}

/** What joining shows, said before it: the end-to-end promise means no history. */
internal const val JoinNote = "You'll see messages sent after you join. Everything in the group is end-to-end encrypted."

/** Answering an invite needs the server; offline the buttons rest and this says why. */
internal const val AnswerOffline = "Answering needs a connection."
