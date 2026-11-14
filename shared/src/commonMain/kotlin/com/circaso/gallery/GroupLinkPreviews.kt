package com.circaso.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SectionHeader
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.GroupLink
import com.circaso.feature.messaging.domain.GroupLinkPreview
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.presentation.ChatInfoUiState
import com.circaso.feature.messaging.presentation.EditGroupContent
import com.circaso.feature.messaging.presentation.GroupDescription
import com.circaso.feature.messaging.presentation.GroupJoinSheet
import com.circaso.feature.messaging.presentation.GroupJoinStage
import com.circaso.feature.messaging.presentation.GroupJoinUiState
import com.circaso.feature.messaging.presentation.GroupLinkConfirm
import com.circaso.feature.messaging.presentation.GroupLinkContent
import com.circaso.feature.messaging.presentation.GroupLinkUiState
import com.circaso.feature.messaging.presentation.GroupLinkWork

private const val Description = "Saturday five-a-side at Kilimani grounds, 7 am sharp.\nKES 300 each for the pitch; bring both kits."

private val LongDescription = "Saturday five-a-side at Kilimani grounds, 7 am sharp. KES 300 each for the pitch; bring both kits. " +
    "If you can't make it, say so by Friday evening so we can find someone. Rain or shine we play, and afterwards it's breakfast at Mama Oliech's. " +
    "New here? Say hello, tell us who invited you and which position you like. Keep it friendly: no politics, no forwards, no selling. " +
    "The group wallet pays for the pitch, the bibs and the end-of-year trip."

private fun linkMember(id: String, name: String, role: MemberRole = MemberRole.Member) = ChatMember(id, name, null, DemoPhotos.of(name), role, "")

private val linkGroup = Chat(
    "g7", ChatKind.Group, "Kilimani Five-a-side", null,
    listOf(linkMember("me", "Amina Wanjiru", MemberRole.Owner), linkMember("2", "Otieno Ouma", MemberRole.Admin), linkMember("3", "Wanjiru Kamau"), linkMember("4", "Juma Kariuki"), linkMember("5", "Baraka Otieno")),
    false, 4, 2, MemberRole.Owner, null, description = Description,
)

private val link = GroupLink("g7", "h3Xq9LmT2vKc8RzYw4Nd6B", "https://links.circaso.com/s/h3Xq9LmT2vKc8RzYw4Nd6B", "Kilimani Five-a-side", Description, hasPhoto = false)

private fun joinPreview(description: String? = Description, photo: Boolean = true, name: String = "Kilimani Five-a-side") =
    GroupLinkPreview(link.token, name, description, if (photo) DemoPhotos.Juma else null, 24, member = false, chatId = null)

/**
 * Group join links and the group description (2026-11-14), `-preview=`: the admins' link screen before and after a
 * link exists (and offline, working, failed), the join sheet in each of its states, Group info with a description
 * and the Edit group sheet.
 */
@Composable
internal fun GroupLinkPreview(name: String): Boolean {
    val linkState = GroupLinkUiState(chat = linkGroup, checked = true)
    when (name) {
        "group-link-intro" -> GroupLinkContent(linkState, {})
        "group-link-intro-offline" -> GroupLinkContent(linkState.copy(online = false), {})
        "group-link-intro-no-name" -> GroupLinkContent(linkState.copy(chat = linkGroup.copy(title = "Otieno, Wanjiru and 3 others", nameKnown = false)), {})
        "group-link-creating" -> GroupLinkContent(linkState.copy(work = GroupLinkWork.Creating), {})
        "group-link-on" -> GroupLinkContent(linkState.copy(link = link), {})
        "group-link-on-offline" -> GroupLinkContent(linkState.copy(link = link, online = false, checked = false), {})
        "group-link-resetting" -> GroupLinkContent(linkState.copy(link = link, work = GroupLinkWork.Resetting), {})
        "group-link-reset" -> GroupLinkContent(linkState.copy(link = link, confirm = GroupLinkConfirm.Reset), {})
        "group-link-turn-off" -> GroupLinkContent(linkState.copy(link = link, confirm = GroupLinkConfirm.TurnOff), {})
        "group-link-loading" -> GroupLinkContent(GroupLinkUiState(chat = linkGroup), {})
        "group-link-unknown-offline" -> GroupLinkContent(GroupLinkUiState(chat = linkGroup, online = false), {})
        "group-link-failed" -> GroupLinkContent(GroupLinkUiState(chat = linkGroup, loadFailed = "Circaso is busy right now. Please try again in a moment. Ref req_K2a9"), {})

        "group-join" -> JoinPreview(GroupJoinStage.Ready(joinPreview()))
        "group-join-long" -> JoinPreview(GroupJoinStage.Ready(joinPreview(LongDescription, name = "Kilimani Saturday Morning Five-a-side and Breakfast Club")))
        "group-join-plain" -> JoinPreview(GroupJoinStage.Ready(joinPreview(description = null, photo = false)))
        "group-join-loading" -> JoinPreview(GroupJoinStage.Loading)
        "group-join-joining" -> JoinPreview(GroupJoinStage.Ready(joinPreview()), joining = true)
        "group-join-offline" -> JoinPreview(GroupJoinStage.Ready(joinPreview()), online = false)
        "group-join-full" -> JoinPreview(GroupJoinStage.Ready(joinPreview(), problem = "This group is full."))
        "group-join-gone" -> JoinPreview(GroupJoinStage.Gone)
        "group-join-not-allowed" -> JoinPreview(GroupJoinStage.NotAllowed)
        "group-join-no-connection" -> JoinPreview(GroupJoinStage.Offline, online = false)
        "group-join-failed" -> JoinPreview(GroupJoinStage.Failed("Circaso is busy right now. Please try again in a moment. Ref req_K2a9"))

        "group-info-description" -> GroupInfoDescriptionPreview(linkGroup)
        "group-info-description-long" -> GroupInfoDescriptionPreview(linkGroup.copy(description = LongDescription))
        "group-edit" -> GroupInfoDescriptionPreview(linkGroup) {
            EditGroupContent(ChatInfoUiState(chat = linkGroup, renaming = true, nameDraft = linkGroup.title, descriptionDraft = Description + "\nSubs welcome."), {}, {}, {}, {})
        }
        "group-edit-too-long" -> GroupInfoDescriptionPreview(linkGroup) {
            EditGroupContent(ChatInfoUiState(chat = linkGroup, renaming = true, nameDraft = linkGroup.title, descriptionDraft = LongDescription + " " + LongDescription), {}, {}, {}, {})
        }
        "group-edit-offline" -> GroupInfoDescriptionPreview(linkGroup) {
            EditGroupContent(ChatInfoUiState(chat = linkGroup, online = false, renaming = true, nameDraft = linkGroup.title, descriptionDraft = "Subs welcome."), {}, {}, {}, {})
        }
        else -> return false
    }
    return true
}

/** The join sheet over the Chats tab, where a tapped link lands. */
@Composable
private fun JoinPreview(stage: GroupJoinStage, online: Boolean = true, joining: Boolean = false) {
    Box(Modifier.fillMaxSize()) {
        PreviewScreens("home")
        GroupJoinSheet(GroupJoinUiState(token = link.token, stage = stage, online = online, joining = joining), onJoin = {}, onRetry = {}, onDismiss = {})
    }
}

/**
 * Group info's header with the description under the name, and the Group rows an admin sees (Edit group, Invite via
 * link), composed from the screen's own pieces; [sheet] shows the Edit group sheet over it.
 */
@Composable
private fun GroupInfoDescriptionPreview(chat: Chat, sheet: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = "Group Info", onBack = {}) },
        overlay = { if (sheet != null) CircasoSheet(true, {}, label = "Edit group", content = sheet) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4).padding(bottom = padding.calculateBottomPadding())) {
            Column(Modifier.fillMaxWidth().padding(vertical = CircasoSpacing.s6), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                Avatar(chat.title, size = CircasoSizes.avatarXl, imageUrl = DemoPhotos.Juma)
                BasicText(chat.title, style = t.title1.copy(color = c.ink, textAlign = TextAlign.Center))
                BasicText("${chat.members.size} members", style = t.subhead.copy(color = c.inkSecondary))
                chat.description?.let { GroupDescription(it) }
            }
            SectionHeader("Group")
            // As the screen has them: rows straight on the grouped ground under their header.
            ListRow("Edit group", sub = "Name and description", onClick = {})
            RowDivider()
            ListRow("Members can add people", trailing = { BasicText("Off", style = t.body.copy(color = c.inkSecondary)) }, onClick = {})
            RowDivider()
            ListRow("Add members", onClick = {})
            RowDivider()
            ListRow("Invite via link", sub = "People join by themselves with a link", onClick = {})
        }
    }
}
