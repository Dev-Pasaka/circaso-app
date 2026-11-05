package com.circaso.feature.spaces.presentation.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.feature.spaces.domain.Relation
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.domain.SpacesRepository
import com.circaso.feature.spaces.presentation.SpaceTile
import org.koin.compose.koinInject

/**
 * The private chat with a space's owner (SH2), as the thread draws it: an ordinary end-to-end encrypted direct chat
 * that came through a space. Both sides read "Private · via {space}"; the payer's `#` pays the space (SH2b), and a
 * visitor who isn't a member gets the reason and the way in instead of Pay (SH2e).
 */
data class ViaSpace(
    val spaceId: String,
    val spaceName: String,
    val photoUrl: String?,
    /** The owner's first name, for the first line ("Only Baraka, the owner, can read this chat"). */
    val ownerFirst: String,
    /** I own the space: the other side is a member (no paying the space from here). */
    val iAmOwner: Boolean,
    /** SH2e: why I can't pay the space yet, or null when I can. */
    val payBlocked: String? = null,
    /** SH2e: "Follow to Pay" or "Join to Pay". */
    val payBlockedAction: String? = null,
) {
    val subtitle: String get() = "Private · via $spaceName"
    val firstLine: String
        get() = if (iAmOwner) "End-to-end encrypted. Only you and this member can read this chat. Your admins never see it."
        else "End-to-end encrypted. Only $ownerFirst, the owner, can read this chat. Other members and admins never see it."
}

/** The space behind a private chat, from the phone (read once from the server if it isn't there yet). Null otherwise. */
@Composable
fun rememberViaSpace(viaSpaceId: String?, myUserId: String?): ViaSpace? {
    if (viaSpaceId == null) return null
    val spaces: SpacesRepository = koinInject()
    val flow = remember(viaSpaceId) { spaces.space(viaSpaceId) }
    val space by flow.collectAsState(null)
    LaunchedEffect(viaSpaceId) { spaces.refreshSpace(viaSpaceId) }
    val s = space ?: return ViaSpace(viaSpaceId, "a space", null, "the owner", iAmOwner = false)
    val mine = s.me.isOwner || (myUserId != null && s.owner?.userId == myUserId)
    val member = s.me.relation == Relation.Member
    return ViaSpace(
        spaceId = s.spaceId, spaceName = s.name, photoUrl = s.summary.photoUrl,
        ownerFirst = s.owner?.firstName ?: "the owner", iAmOwner = mine,
        payBlocked = if (mine || member) null else if (s.visibility == SpaceVisibility.MembersOnly) "Only members of ${s.name} can pay it. Join first." else "Only people who follow ${s.name} can pay it. Following is free.",
        payBlockedAction = if (mine || member) null else if (s.visibility == SpaceVisibility.MembersOnly) "Join to Pay" else "Follow to Pay",
    )
}

/** The header's avatar with the space's small tile on its corner (canvas `private_bar`). */
@Composable
fun ViaSpaceAvatar(name: String, avatarUrl: String?, via: ViaSpace) {
    Box {
        Avatar(name, size = CircasoSizes.avatarMd, imageUrl = avatarUrl)
        // The space's small tile on the photo's corner, on a ring of the page's background so it reads as a badge.
        Box(
            Modifier.align(Alignment.BottomEnd).offset(x = 5.dp, y = 4.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .background(com.circaso.designsystem.theme.CircasoTheme.colors.bg).padding(1.5.dp),
        ) { SpaceTile(via.spaceName, via.photoUrl, 18.dp) }
    }
}
