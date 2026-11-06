package com.circaso.feature.messaging.presentation

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.circaso.designsystem.motion.pressable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import com.circaso.feature.messaging.domain.Message
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.RowValue
import com.circaso.designsystem.components.RowValueChevron
import com.circaso.designsystem.components.SectionHeader
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.chama.presentation.ApprovalPolicyContent
import com.circaso.feature.chama.presentation.ChangePinComingContent
import com.circaso.feature.chama.presentation.GroupMoneyRows
import com.circaso.feature.chama.presentation.LeaveBlockedContent
import com.circaso.feature.chama.presentation.TargetNeedsWalletContent
import com.circaso.feature.profile.presentation.ProfileCameraLayer

/** Members and roles (groups), or the safety number (direct chats). Not in the canvas yet; built from the D2 header's "Group info". */
@Composable
fun ChatInfoScreen(
    viewModel: ChatInfoViewModel,
    onBack: () -> Unit,
    onLeft: () -> Unit,
    onGroupWallet: () -> Unit = {},
    /** A direct chat's profile actions (WhatsApp's row): calls and, Circaso's own, Send money. */
    onCall: ((video: Boolean) -> Unit)? = null,
    onPay: (() -> Unit)? = null,
    onOpenLocation: (Message.Media) -> Unit = {},
    /** Back to the chat, at this message (a search result, a shared video). */
    onOpenMessage: (localId: Long) -> Unit = {},
    /** Group wallets: set up (or finish) the group's wallet (GW2–GW5); [thenTarget] ends on the target form (GW7). */
    onSetUpWallet: (thenTarget: Boolean) -> Unit = {},
    /** Group wallets: add or change the target (GW7b). */
    onTarget: () -> Unit = {},
) {
    var viewing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ByteArray?>(null) }
    val state by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    LaunchedEffect(state.message) { state.message?.let { toast.show(it); viewModel.onMessageShown() } }
    LaunchedEffect(state.left) { if (state.left) onLeft() }
    val chat = state.chat
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = if (state.isGroup) "Group Info" else "Contact Info", onBack = onBack) },
        banner = { ConnectionBanner(showing = "Changes need a connection") },
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
        overlay = {
            ImageViewer(viewing) { viewing = null }
            val photos = viewModel.photos
            CircasoSheet(visible = state.photoChoice.open, onDismiss = { photos?.dismiss() }, label = "Group photo") {
                com.circaso.feature.profile.presentation.PhotoChoiceSheetContent(
                    state.photoChoice.recents, state.photoChoice.access, hasPhoto = state.hasGroupPhoto, useAs = "group photo",
                    onUseRecent = { photos?.useRecent(it) }, onManage = { photos?.manage() }, onTakePhoto = { photos?.openCamera() },
                    onChoose = { photos?.choose() }, onRemove = viewModel::onRemovePhoto, onCancel = { photos?.dismiss() },
                )
            }
            ProfileCameraLayer(
                state.photoChoice.camera, state.online, { m -> photos?.let { com.circaso.core.platform.CameraViewfinder(it.cameraDevice, m) } },
                onClose = { photos?.closeCamera() }, onShutter = { photos?.shutter() }, onFlip = { photos?.flip() }, onRetake = { photos?.retake() },
                onUse = { photos?.useShot() }, onChooseInstead = { photos?.chooseInstead() }, words = com.circaso.feature.profile.presentation.CameraWords.Group,
            )
            // Search in the chat (WhatsApp's profile Search): matching messages, newest first.
            CircasoSheet(visible = state.searching, onDismiss = { viewModel.onSearch(false) }, label = "Search") {
                BasicText("Search", style = t.title2.copy(color = c.ink))
                com.circaso.designsystem.components.SearchPill(state.query, viewModel::onQuery, "Search messages")
                if (state.query.isNotBlank() && state.found.isEmpty()) BasicText("No messages match.", style = t.body.copy(color = c.inkSecondary))
                state.found.take(30).forEach { m ->
                    ListRow(
                        m.text, sub = (if (m.outgoing) "You" else m.senderName) + " · " + com.circaso.core.platform.chatListTime(m.orderAt, com.circaso.core.sync.nowMillis()),
                        onClick = { viewModel.onSearch(false); onOpenMessage(m.localId) },
                    )
                }
                CircasoButton("Done", { viewModel.onSearch(false) }, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
            }
            val member = state.selected
            CircasoSheet(visible = member != null, onDismiss = { viewModel.onSelect(null) }, label = member?.displayName ?: "Member") {
                if (member != null) {
                    BasicText(member.displayName, style = t.title2.copy(color = c.ink))
                    member.handle?.let { BasicText("@$it", style = t.body.copy(color = c.inkSecondary)) }
                    if (state.amAdmin && member.role != MemberRole.Owner) {
                        if (member.role == MemberRole.Member) CircasoButton("Make Admin", { viewModel.onAction(InfoAction.MakeAdmin(member)) }, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = state.canChange)
                        if (member.role == MemberRole.Admin) CircasoButton("Make Member", { viewModel.onAction(InfoAction.MakeMember(member)) }, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = state.canChange)
                        if (state.amOwner) CircasoButton("Hand Over Ownership", { viewModel.onAction(InfoAction.HandOver(member)) }, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = state.canChange)
                        CircasoButton("Remove from Group", { viewModel.onAction(InfoAction.Remove(member)) }, style = ButtonStyle.Destructive, size = ButtonSize.Large, block = true, enabled = state.canChange)
                    }
                    CircasoButton("Cancel", { viewModel.onSelect(null) }, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
                }
            }
            val action = state.pending
            ConfirmSheet(
                visible = action != null,
                title = when (action) {
                    is InfoAction.Remove -> "Remove ${action.member.displayName}?"
                    is InfoAction.MakeAdmin -> "Make ${action.member.displayName} an admin?"
                    is InfoAction.MakeMember -> "Make ${action.member.displayName} a member?"
                    is InfoAction.HandOver -> "Hand ownership to ${action.member.displayName}?"
                    InfoAction.Leave -> "Leave this group?"
                    null -> ""
                },
                message = when (action) {
                    is InfoAction.Remove -> "They won't see anything sent after this. Everyone's keys turn over."
                    is InfoAction.MakeAdmin -> "Admins can add and remove people and change the group."
                    is InfoAction.MakeMember -> "They can still chat, but no longer change the group."
                    is InfoAction.HandOver -> "You become an admin. Only the owner can hand the group on."
                    InfoAction.Leave -> "You won't see new messages. If you own the group, it passes to the longest-serving admin."
                    null -> ""
                },
                confirmLabel = when (action) { is InfoAction.Remove -> "Remove"; InfoAction.Leave -> "Leave"; else -> "Confirm" },
                destructive = action is InfoAction.Remove || action == InfoAction.Leave,
                onConfirm = viewModel::onConfirm,
                onDismiss = { viewModel.onAction(null) },
            )
            CircasoSheet(visible = state.renaming, onDismiss = { viewModel.onRename(false) }, label = if (state.namingForPhoto) "Name the group" else "Rename group") {
                BasicText(if (state.namingForPhoto) "Name the group" else "Group name", style = t.title2.copy(color = c.ink))
                // This phone can't read the name (its key is lost): say why it's asked for, and what a new key costs.
                val why = when {
                    state.namingForPhoto && state.removingPhoto -> "This phone lost the group's key, so it can't read the name. Type it to remove the photo."
                    state.namingForPhoto -> "This phone lost the group's key, so it can't read the name. Type it to save the new photo."
                    state.nameUnknown -> "This phone lost the group's key, so it can't read the name. If the group gets a new key, its photo is removed too; you can add it again after."
                    else -> null
                }
                why?.let { BasicText(it, style = t.body.copy(color = c.inkSecondary)) }
                CircasoTextField(value = state.nameDraft, onValueChange = viewModel::onNameDraft, label = "Group name")
                if (!state.online) BasicText("Changing the group needs a connection.", style = t.footnote.copy(color = c.inkTertiary))
                val save = when { !state.namingForPhoto -> "Save"; state.removingPhoto -> "Remove Photo"; else -> "Save Photo" }
                CircasoButton(save, viewModel::onRenameConfirm, size = ButtonSize.Large, block = true, enabled = state.canChange && state.nameDraft.isNotBlank())
                CircasoButton("Cancel", { viewModel.onRename(false) }, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
            }
            // Group wallets (GW7, CH9, §11.9, W8).
            val sheet = state.moneySheet
            CircasoSheet(visible = sheet == MoneySheet.TargetNeedsWallet, onDismiss = { viewModel.onMoneySheet(null) }, label = "A target needs a group wallet") {
                TargetNeedsWalletContent(chat, onSetUp = { viewModel.onMoneySheet(null); onSetUpWallet(true) }, onNotNow = { viewModel.onMoneySheet(null) })
            }
            CircasoSheet(visible = sheet == MoneySheet.Approvals, onDismiss = { viewModel.onMoneySheet(null) }, label = "Who approves payouts") {
                ApprovalPolicyContent(
                    current = state.money.money?.approvalPolicy ?: com.circaso.feature.chama.domain.ApprovalPolicy.TwoAdmins, choice = state.policyChoice,
                    busy = state.busy, online = state.online, onChoose = viewModel::onPolicyChoice, onSave = viewModel::onSavePolicy, onCancel = { viewModel.onMoneySheet(null) },
                )
            }
            CircasoSheet(visible = sheet == MoneySheet.LeaveBlocked, onDismiss = { viewModel.onMoneySheet(null) }, label = "You can't leave yet") {
                LeaveBlockedContent(chat, state.moneyBalanceText, onPayout = { viewModel.onMoneySheet(null); onGroupWallet() }, onClose = { viewModel.onMoneySheet(null) })
            }
            CircasoSheet(visible = sheet == MoneySheet.ChangePin, onDismiss = { viewModel.onMoneySheet(null) }, label = "Change PIN") {
                ChangePinComingContent(chat?.title ?: "the group wallet", onClose = { viewModel.onMoneySheet(null) })
            }
            CircasoSheet(visible = state.adding, onDismiss = { viewModel.onAdding(false) }, label = "Add members") {
                BasicText("Add members", style = t.title2.copy(color = c.ink))
                if (state.addable.isEmpty()) BasicText("Everyone from your contacts is already here.", style = t.body.copy(color = c.inkSecondary))
                state.addable.take(30).forEach { contact ->
                    ListRow(
                        contact.person.displayName, sub = contact.person.handle?.let { "@$it" },
                        leading = { Avatar(contact.person.displayName, imageUrl = contact.person.avatarUrl) },
                        trailing = { if (contact.person.userId in state.toAdd) CircasoIconImage(CircasoIcon.Check, c.action, Modifier.size(20.dp)) },
                        onClick = { viewModel.onToggleAdd(contact.person.userId) },
                    )
                }
                CircasoButton("Add ${state.toAdd.size.takeIf { it > 0 }?.let { "($it)" } ?: ""}".trim(), viewModel::onAddConfirm, size = ButtonSize.Large, block = true, enabled = state.canAdd && state.toAdd.isNotEmpty())
                CircasoButton("Cancel", { viewModel.onAdding(false) }, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + CircasoSpacing.s6)) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = CircasoSpacing.s6), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    if (state.isGroup && state.amAdmin && state.photosAvailable) {
                        // M8: admins change the group's photo from here (the profile photo's sheet and camera).
                        GroupPhotoButton(
                            name = chat?.title ?: "", bytes = state.pendingPhoto, imageUrl = chat?.avatarUrl, busy = state.photoBusy,
                            enabled = state.canChangePhoto, onClick = { viewModel.photos?.open() }, size = CircasoSizes.avatarXl,
                            available = state.canChangePhoto || state.hasGroupPhoto,
                        )
                    } else {
                        Avatar(chat?.title ?: "", size = CircasoSizes.avatarXl, imageUrl = chat?.avatarUrl)
                    }
                    BasicText(chat?.title ?: "", style = t.title1.copy(color = c.ink, textAlign = TextAlign.Center))
                    if (state.isGroup) BasicText("${chat?.members?.size ?: 0} members" + if (state.money.enabled && state.money.money?.hasWallet == true) " · Group wallet" else "", style = t.subhead.copy(color = c.inkSecondary))
                    else chat?.members?.firstOrNull { it.userId == chat.peerId }?.handle?.let { BasicText("@$it", style = t.body.copy(color = c.inkSecondary)) }
                    if (state.isGroup && state.amAdmin && state.photosAvailable && !state.online) {
                        BasicText("Changing the group photo needs a connection.", style = t.footnote.copy(color = c.inkTertiary, textAlign = TextAlign.Center))
                    }
                }
            }
            if (!state.isGroup) {
                item { ProfileActions(onCall = onCall, onPay = onPay, onSearch = { viewModel.onSearch(true) }, online = state.online) }
            }
            if (state.media.isNotEmpty()) {
                item {
                    SectionHeader("Media · ${state.media.size}", Modifier.padding(top = CircasoSpacing.s4))
                    SharedMediaStrip(state.media, viewModel.media, onPhoto = { viewing = it }, onLocation = onOpenLocation, onVideo = { onOpenMessage(it.localId) })
                }
            }
            // GW6–GW8: the group's money sits right under the header, before its settings and members.
            if (state.isGroup && state.money.enabled) {
                item {
                        // GW6–GW8b: the group's own wallet, its target and its security (group wallets, phase 2).
                        Column {
                            GroupMoneyRows(
                                state.money, chat, state.myUserId, state.online, state.busy,
                                com.circaso.feature.chama.presentation.GroupMoneyActions(
                                    onSetUpWallet = onSetUpWallet, onOpenWallet = onGroupWallet, onTarget = onTarget,
                                    onTargetNeedsWallet = { viewModel.onMoneySheet(MoneySheet.TargetNeedsWallet) },
                                    onBiometrics = viewModel::onWalletBiometrics, onChangePin = { viewModel.onMoneySheet(MoneySheet.ChangePin) },
                                    onApprovals = { viewModel.onMoneySheet(MoneySheet.Approvals) }, onRetry = viewModel::onRetryMoney,
                                ),
                            )
                        }
                }
            }
            if (state.isGroup) {
                item {
                    SectionHeader("Group")
                    if (state.amAdmin) {
                        ListRow("Rename group", onClick = if (state.canChange) ({ viewModel.onRename(true) }) else null)
                        RowDivider()
                        ListRow("Members can add people", trailing = { RowValue(if (chat?.membersCanAdd == true) "On" else "Off") }, onClick = if (state.canChange) ({ viewModel.onMembersCanAdd(chat?.membersCanAdd != true) }) else null)
                        RowDivider()
                    }
                    ListRow("Add members", onClick = if (state.canAdd) ({ viewModel.onAdding(true) }) else null)
                    if (!state.money.enabled) RowDivider()
                    if (!state.money.enabled) {
                        // Designs D4 and D1: the shared goal lives behind the group; admins set it there, everyone can read it.
                        ListRow("Group wallet", sub = if (state.amAdmin) "Goal, contributions and payments. Set or change the goal here." else "Goal, contributions and payments", trailing = { RowValueChevron(null) }, onClick = onGroupWallet)
                    }
                    SectionHeader("Members", Modifier.padding(top = CircasoSpacing.s4))
                }
                items(chat?.members.orEmpty(), key = { it.userId }) { member ->
                    ListRow(
                        title = member.displayName,
                        sub = member.handle?.let { "@$it" },
                        meta = when (member.role) { MemberRole.Owner -> "Owner"; MemberRole.Admin -> "Admin"; MemberRole.Member -> null },
                        leading = { Avatar(member.displayName, imageUrl = member.avatarUrl) },
                        onClick = if (state.amAdmin && member.role != MemberRole.Owner) ({ viewModel.onSelect(member) }) else null,
                    )
                    RowDivider(Modifier.padding(start = 76.dp))
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(CircasoSpacing.s4)) {
                        CircasoButton("Leave Group", { viewModel.onAction(InfoAction.Leave) }, style = ButtonStyle.Destructive, size = ButtonSize.Large, block = true, enabled = state.canChange)
                    }
                }
            } else {
                item {
                    SectionHeader("Encryption", Modifier.padding(top = CircasoSpacing.s4))
                    Column(Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                        val code = state.securityCode
                        if (code == null) {
                            BasicText("Appears after your first message to each other.", style = t.body.copy(color = c.inkSecondary))
                        } else {
                            code.groups.chunked(4).forEach { line ->
                                BasicText(line.joinToString("   "), style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
                            }
                            BasicText("Compare these numbers in person or on a call. If they match, your messages with ${chat?.title} are end-to-end encrypted and nobody is in between.", style = t.footnote.copy(color = c.inkSecondary))
                        }
                    }
                }
            }
        }
    }
}

/**
 * WhatsApp's row under the name: Call, Video, Search, and Circaso's own Pay. Each is a rounded tile with its icon and
 * word, so the row reads at a glance and every target is large.
 */
@Composable
private fun ProfileActions(onCall: ((Boolean) -> Unit)?, onPay: (() -> Unit)?, onSearch: () -> Unit, online: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        if (onCall != null) {
            ActionTile(CircasoIcon.Phone, "Call", Modifier.weight(1f), enabled = online) { onCall(false) }
            ActionTile(CircasoIcon.Video, "Video", Modifier.weight(1f), enabled = online) { onCall(true) }
        }
        if (onPay != null) ActionTile(CircasoIcon.Money, "Pay", Modifier.weight(1f), enabled = online, onClick = onPay)
        ActionTile(CircasoIcon.Search, "Search", Modifier.weight(1f), onClick = onSearch)
    }
}

@Composable
private fun ActionTile(icon: CircasoIcon, label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        modifier.clip(com.circaso.designsystem.theme.CircasoShapes.md).background(c.surface)
            .pressable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(vertical = CircasoSpacing.s3)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CircasoIconImage(icon, if (enabled) c.ink else c.inkTertiary, Modifier.size(22.dp))
        BasicText(label, style = t.footnote.copy(color = if (enabled) c.ink else c.inkTertiary))
    }
}

/** The newest shared photos, voice notes and places, as square tiles; a photo opens full screen, a place its map. */
@Composable
private fun SharedMediaStrip(media: List<Message.Media>, loader: ThreadMedia?, onPhoto: (ByteArray) -> Unit, onLocation: (Message.Media) -> Unit, onVideo: (Message.Media) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = CircasoSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        items(media.take(30), key = { it.localId }) { m ->
            val tile = Modifier.size(84.dp).clip(com.circaso.designsystem.theme.CircasoShapes.md).background(c.fill)
            when {
                m.isImage -> {
                    val bytes by androidx.compose.runtime.produceState<ByteArray?>(null, m.localId) {
                        value = (loader?.load(m.details) as? com.circaso.feature.messaging.data.repository.MediaLoad.Loaded)?.bytes
                    }
                    Box(tile.pressable(role = androidx.compose.ui.semantics.Role.Image, onClick = { bytes?.let(onPhoto) }).semantics { contentDescription = "Photo" }) {
                        bytes?.let { coil3.compose.AsyncImage(model = it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
                m.isVideo -> {
                    val thumb = androidx.compose.runtime.remember(m.details.thumb) {
                        m.details.thumb?.let { runCatching { @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class) kotlin.io.encoding.Base64.decode(it) }.getOrNull() }
                    }
                    Box(tile.pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = { onVideo(m) }).semantics { contentDescription = "Video" }) {
                        thumb?.let { coil3.compose.AsyncImage(model = it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        // A scrim under the length so it reads on any frame.
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, c.scrim))))
                        Row(Modifier.align(Alignment.BottomStart).padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            CircasoIconImage(CircasoIcon.Play, androidx.compose.ui.graphics.Color.White, Modifier.size(11.dp))
                            m.details.durationMs?.let { d -> BasicText("${d / 60_000}:${((d / 1000) % 60).toString().padStart(2, '0')}", style = t.caption2.copy(color = androidx.compose.ui.graphics.Color.White, fontFeatureSettings = "tnum")) }
                        }
                    }
                }
                m.isVoice -> Column(tile.semantics(mergeDescendants = true) { contentDescription = "Voice message" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircasoIconImage(CircasoIcon.Mic, c.ink, Modifier.size(22.dp))
                    m.details.durationMs?.let { d -> BasicText("${d / 60_000}:${((d / 1000) % 60).toString().padStart(2, '0')}", style = t.caption1.copy(color = c.inkSecondary)) }
                }
                else -> Column(
                    tile.pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = { onLocation(m) }).semantics(mergeDescendants = true) { contentDescription = if (m.details.isLive) "Live location" else "Location" },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    CircasoIconImage(CircasoIcon.Pin, c.ink, Modifier.size(22.dp))
                    BasicText(if (m.details.isLive) "Live" else "Place", style = t.caption1.copy(color = c.inkSecondary))
                }
            }
        }
    }
}
