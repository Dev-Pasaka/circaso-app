package com.circaso.feature.messaging.presentation

import com.circaso.feature.names.presentation.EditName
import com.circaso.feature.names.presentation.EditNameSheet
import com.circaso.feature.names.presentation.ProfileNameLine
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.text.style.TextOverflow
import com.circaso.designsystem.theme.CircasoShapes
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
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
import com.circaso.feature.messaging.domain.ChatMember
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
    /** Invite via link (2026-11-14): the group's join link, for its admins. */
    onInviteLink: () -> Unit = {},
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
    /** The group wallet's owner (2026-11-06): pay from it, or send it to an outside address. */
    onPayFromWallet: ((walletId: String) -> Unit)? = null,
    onSendToWallet: ((walletId: String) -> Unit)? = null,
    /** Opened from a chat's "security code changed" notice: start at the security code card. */
    showSecurityCode: Boolean = false,
) {
    var viewing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ByteArray?>(null) }
    val state by viewModel.state.collectAsState()
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // Once the direct chat has loaded, and only on the first visit. The card is a direct chat's last item: keep it last,
    // or jump by its key's index here.
    var jumped by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(!showSecurityCode) }
    LaunchedEffect(jumped, state.chat != null) {
        if (jumped || state.chat == null || state.isGroup) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > 0 }
        list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
        jumped = true
    }
    val names = viewModel.editName
    val editing by (names?.state ?: androidx.compose.runtime.remember { kotlinx.coroutines.flow.MutableStateFlow<EditName?>(null) }).collectAsState()
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
        toast = { ToastHost(toast) },
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
            // The open member follows the chat: back from Edit name, their sheet shows the new name.
            val member = state.selected?.let { s -> chat?.members?.firstOrNull { it.userId == s.userId } ?: s }
            CircasoSheet(visible = member != null, onDismiss = { viewModel.onSelect(null) }, label = member?.displayName ?: "Member") {
                if (member != null) {
                    BasicText(member.displayName, style = t.title2.copy(color = c.ink))
                    // A name I saved: their own under it, WhatsApp-style.
                    if (member.renamed) ProfileNameLine(member.profileName, t.body.copy(color = c.inkSecondary))
                    member.handle?.let { BasicText("@$it", style = t.body.copy(color = c.inkSecondary)) }
                    if (names != null && EditName.allowedFor(member.userId)) {
                        CircasoButton(
                            "Edit Name",
                            { viewModel.onSelect(null); names.open(member.userId, member.profileName, member.displayName, returnTo = { viewModel.onSelect(member) }) },
                            style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true,
                        )
                    }
                    if (state.amAdmin && member.role != MemberRole.Owner) {
                        if (member.role == MemberRole.Member) CircasoButton("Make Admin", { viewModel.onAction(InfoAction.MakeAdmin(member)) }, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = state.canChange)
                        if (member.role == MemberRole.Admin) CircasoButton("Make Member", { viewModel.onAction(InfoAction.MakeMember(member)) }, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = state.canChange)
                        if (state.amOwner) CircasoButton("Hand Over Ownership", { viewModel.onAction(InfoAction.HandOver(member)) }, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = state.canChange)
                        CircasoButton("Remove from Group", { viewModel.onAction(InfoAction.Remove(member)) }, style = ButtonStyle.Destructive, size = ButtonSize.Large, block = true, enabled = state.canChange)
                    }
                    CircasoButton("Cancel", { viewModel.onSelect(null) }, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
                }
            }
            // The last one is kept while the sheet slides away, so its title never empties on the way out.
            var lastCancelling by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(state.cancelling) }
            state.cancelling?.let { lastCancelling = it }
            ConfirmSheet(
                visible = state.cancelling != null,
                title = lastCancelling?.let { "Cancel ${it.person.displayName}'s invite?" } ?: "",
                message = "They won't be able to join with it. You can invite them again later.",
                confirmLabel = "Cancel Invite",
                destructive = true,
                icon = com.circaso.designsystem.components.CircasoIcon.Mail,
                enabled = state.online,
                note = if (!state.online) "Cancelling an invite needs a connection." else null,
                cancelLabel = "Keep Invite",
                onConfirm = viewModel::onCancelInviteConfirm,
                onDismiss = { viewModel.onCancelInvite(null) },
            )
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
                    is InfoAction.Remove -> "They won't see anything sent after this; the group gets new keys."
                    is InfoAction.MakeAdmin -> "Admins can add and remove people and change the group."
                    is InfoAction.MakeMember -> "They can still chat, but no longer change the group."
                    is InfoAction.HandOver -> "You become an admin. Only ${action.member.displayName} can give it back."
                    InfoAction.Leave -> "You won't see new messages. If you own the group, it passes to the longest-serving admin."
                    null -> ""
                },
                confirmLabel = when (action) {
                    is InfoAction.Remove -> "Remove from Group"
                    InfoAction.Leave -> "Leave Group"
                    is InfoAction.MakeAdmin -> "Make Admin"
                    is InfoAction.MakeMember -> "Make Member"
                    is InfoAction.HandOver -> "Hand Over Ownership"
                    null -> "Confirm"
                },
                destructive = action is InfoAction.Remove || action == InfoAction.Leave,
                // What kind of step it is, at a glance: a person leaving, a role, the group passing on, me walking out.
                icon = when (action) {
                    is InfoAction.Remove -> com.circaso.designsystem.components.CircasoIcon.UserMinus
                    is InfoAction.MakeAdmin -> com.circaso.designsystem.components.CircasoIcon.Shield
                    is InfoAction.MakeMember -> com.circaso.designsystem.components.CircasoIcon.Person
                    is InfoAction.HandOver -> com.circaso.designsystem.components.CircasoIcon.Forward
                    InfoAction.Leave -> com.circaso.designsystem.components.CircasoIcon.SignOut
                    null -> com.circaso.designsystem.components.CircasoIcon.Info
                },
                // Handing over can't be taken back by me: amber, not the plain grey of a role change.
                tone = when (action) {
                    is InfoAction.Remove, InfoAction.Leave -> com.circaso.designsystem.chrome.SheetTone.Critical
                    is InfoAction.HandOver -> com.circaso.designsystem.chrome.SheetTone.Warning
                    else -> com.circaso.designsystem.chrome.SheetTone.Neutral
                },
                enabled = state.online,
                note = if (!state.online) (if (action == InfoAction.Leave) "Leaving needs a connection." else "Changing the group needs a connection.") else null,
                onConfirm = viewModel::onConfirm,
                onDismiss = { viewModel.onAction(null) },
            )
            EditNameSheet(
                editing, onDraft = { names?.onDraft(it) }, onSave = { names?.onSave() }, onUseTheirs = { names?.onUseTheirs() }, onDismiss = { names?.onDismiss() },
            )
            CircasoSheet(visible = state.renaming, onDismiss = { viewModel.onRename(false) }, label = if (state.namingForPhoto) "Name the group" else "Edit group", dismissible = !state.saving) {
                EditGroupContent(state, viewModel::onNameDraft, viewModel::onDescriptionDraft, viewModel::onRenameConfirm, onCancel = { viewModel.onRename(false) })
            }
            // Group wallets (GW7, CH9, §11.9, W8).
            val sheet = state.moneySheet
            CircasoSheet(visible = sheet == MoneySheet.TargetNeedsWallet, onDismiss = { viewModel.onMoneySheet(null) }, label = "A target needs a group wallet") {
                TargetNeedsWalletContent(chat, onSetUp = { viewModel.onMoneySheet(null); onSetUpWallet(true) }, onNotNow = { viewModel.onMoneySheet(null) })
            }
            CircasoSheet(visible = sheet == MoneySheet.Approvals, onDismiss = { if (!state.busy) viewModel.onMoneySheet(null) }, label = "Who approves payouts") {
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
                // People are invited, not put in (2026-11-09): said before they're chosen, so nobody expects them in at once.
                BasicText("Each person gets an invite and joins when they accept.", style = t.subhead.copy(color = c.inkSecondary))
                if (state.addable.isEmpty()) BasicText("Everyone from your contacts is already here or invited.", style = t.body.copy(color = c.inkSecondary))
                state.addable.take(30).forEach { contact ->
                    ListRow(
                        contact.person.displayName, sub = contact.person.handle?.let { "@$it" },
                        leading = { Avatar(contact.person.displayName, imageUrl = contact.person.avatarUrl) },
                        trailing = { if (contact.person.userId in state.toAdd) CircasoIconImage(CircasoIcon.Check, c.action, Modifier.size(20.dp)) },
                        onClick = { viewModel.onToggleAdd(contact.person.userId) },
                    )
                }
                CircasoButton("Invite ${state.toAdd.size.takeIf { it > 0 }?.let { "($it)" } ?: ""}".trim(), viewModel::onAddConfirm, size = ButtonSize.Large, block = true, enabled = state.canAdd && state.toAdd.isNotEmpty())
                CircasoButton("Cancel", { viewModel.onAdding(false) }, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + CircasoSpacing.s6)) {
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
                    else PeerNameLines(chat?.members?.firstOrNull { it.userId == chat.peerId })
                    // The group's description (2026-11-14), under its name: what the group is for, in the admins' words.
                    if (state.isGroup) chat?.description?.let { GroupDescription(it, Modifier.padding(horizontal = CircasoSpacing.s6, vertical = CircasoSpacing.s1)) }
                    if (state.isGroup && state.amAdmin && state.photosAvailable && !state.online) {
                        BasicText("Changing the group photo needs a connection.", style = t.footnote.copy(color = c.inkTertiary, textAlign = TextAlign.Center))
                    }
                }
            }
            if (!state.isGroup && chat != null) {
                item { ProfileActions(onCall = onCall, onPay = onPay, onSearch = { viewModel.onSearch(true) }, online = state.online) }
                val peer = chat?.members?.firstOrNull { it.userId == chat.peerId }
                if (names != null && peer != null && EditName.allowedFor(peer.userId)) {
                    // Saved names (2026-11-12): works offline, the name changes everywhere at once.
                    item(key = "edit-name") { EditNameRow { names.open(peer.userId, peer.profileName, peer.displayName) } }
                }
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
                                    onPay = onPayFromWallet, onSendToWallet = onSendToWallet,
                                ),
                            )
                        }
                }
            }
            if (state.isGroup) {
                item {
                    SectionHeader("Group")
                    if (state.amAdmin) {
                        ListRow("Edit group", sub = "Name and description", onClick = if (state.canChange) ({ viewModel.onRename(true) }) else null)
                        RowDivider()
                        ListRow("Members can add people", trailing = { RowValue(if (chat?.membersCanAdd == true) "On" else "Off") }, onClick = if (state.canChange) ({ viewModel.onMembersCanAdd(chat?.membersCanAdd != true) }) else null)
                        RowDivider()
                    }
                    ListRow("Add members", onClick = if (state.canAdd) ({ viewModel.onAdding(true) }) else null)
                    if (state.amAdmin) {
                        // Group join links (2026-11-14): opens offline too, on the link this phone has.
                        RowDivider()
                        ListRow("Invite via link", sub = "People join by themselves with a link", trailing = { RowValueChevron(if (state.linkOn) "On" else null) }, onClick = onInviteLink)
                    }
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
                        // Anyone but me opens their sheet: Edit name for everyone, roles for admins.
                        onClick = if (member.userId != state.myUserId && ((names != null && EditName.allowedFor(member.userId)) || (state.amAdmin && member.role != MemberRole.Owner))) ({ viewModel.onSelect(member) }) else null,
                    )
                    RowDivider(Modifier.padding(start = 76.dp))
                }
                // Group invites (2026-11-09): people asked and not yet answered. Not members: no role, not counted.
                if (state.invited.isNotEmpty()) {
                    item(key = "invited-header") { SectionHeader("Invited · ${state.invited.size}", Modifier.padding(top = CircasoSpacing.s4)) }
                    items(state.invited, key = { "invited:" + it.inviteId }) { invite ->
                        val mine = invite.invitedBy.userId == state.myUserId
                        ListRow(
                            title = invite.person.displayName,
                            sub = if (mine) "Invited by you" else "Invited by ${invite.invitedBy.displayName}",
                            leading = { Avatar(invite.person.displayName, imageUrl = invite.person.avatarUrl) },
                            // Online-only like every change here: offline the row rests and the banner says why.
                            onClick = if (state.mayCancel(invite) && state.canChange) ({ viewModel.onCancelInvite(invite) }) else null,
                        )
                        RowDivider(Modifier.padding(start = 76.dp))
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(CircasoSpacing.s4)) {
                        CircasoButton("Leave Group", { viewModel.onAction(InfoAction.Leave) }, style = ButtonStyle.Destructive, size = ButtonSize.Large, block = true, enabled = state.canChange)
                    }
                }
            } else if (chat != null) {
                item(key = SecurityCodeKey) {
                    SectionHeader("Encryption", Modifier.padding(top = CircasoSpacing.s4))
                    // The security code on the vault's metal (2026-11-13), with how to check it and Mark as Verified.
                    val me = chat?.members?.firstOrNull { it.userId == state.myUserId }
                    val peer = chat?.members?.firstOrNull { it.userId == chat.peerId }
                    SecurityCodeSection(
                        code = state.securityCode, verified = state.codeVerified, loaded = state.securityCodeLoaded,
                        changedSinceVerified = state.codeChangedSinceVerified,
                        myName = me?.displayName ?: "You", myPhoto = me?.avatarUrl,
                        peerName = chat.title.ifBlank { peer?.displayName.orEmpty() }, peerPhoto = chat.avatarUrl ?: peer?.avatarUrl,
                        onVerified = viewModel::onVerified,
                        modifier = Modifier.padding(top = CircasoSpacing.s1, bottom = CircasoSpacing.s3),
                    )
                }
            }
        }
    }
}

/** Under a direct chat's name in its info: their own name when I saved one ("~ John Kamau"), then their @username. */
/**
 * The Edit group sheet's content (the name, and since 2026-11-14 the description), also the "Name the group" sheet a
 * photo change opens on a phone that lost the group's key. Both fields are sealed on the phone before they leave it.
 */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.EditGroupContent(
    state: ChatInfoUiState,
    onName: (String) -> Unit,
    onDescription: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    // This phone can't read the name (its key is lost): say why it's asked for, and what a new key costs.
    val why = when {
        state.namingForPhoto && state.removingPhoto -> "This phone lost the group's key, so it can't read the name. Type it to remove the photo."
        state.namingForPhoto -> "This phone lost the group's key, so it can't read the name. Type it to save the new photo."
        state.nameUnknown -> "This phone lost the group's key, so it can't read the name. If the group gets a new key, its photo is removed too; you can add it again after."
        else -> null
    }
    com.circaso.designsystem.chrome.SheetMessage(
        if (state.namingForPhoto || state.nameUnknown) "Name the group" else "Edit group",
        // With the group's link on, a change here is published to the link's page too: said before Save, not after.
        why ?: if (state.linkOn) "Everyone in the group sees the name and description. Anyone with the group's link sees them too."
        else "Everyone in the group sees the name and description. They are end-to-end encrypted, like your messages.",
        if (state.nameUnknown || state.namingForPhoto) com.circaso.designsystem.components.CircasoIcon.Lock else com.circaso.designsystem.components.CircasoIcon.Pencil,
        tone = if (state.nameUnknown || state.namingForPhoto) com.circaso.designsystem.chrome.SheetTone.Warning else com.circaso.designsystem.chrome.SheetTone.Neutral,
    )
    CircasoTextField(value = state.nameDraft, onValueChange = onName, label = "Group name")
    if (!state.namingForPhoto && !state.nameUnknown) {
        // The description (2026-11-14) is edited where the name is. A photo change that only needs the name doesn't
        // ask for it, and a phone that can't read the name can't read the description: it keeps the one there is.
        val limit = com.circaso.feature.messaging.data.repository.MaxGroupDescriptionChars
        val length = state.descriptionDraft.trim().length
        CircasoTextField(
            value = state.descriptionDraft, onValueChange = onDescription, label = "Description",
            placeholder = "What the group is for (optional)", singleLine = false, enabled = !state.saving,
            error = if (state.descriptionTooLong) "A description can be up to $limit characters. This one is $length." else null,
        )
        // Near the limit the count shows, so the end never comes as a surprise.
        if (length >= DescriptionCountFrom && !state.descriptionTooLong) {
            BasicText("$length/$limit", style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkTertiary))
        }
    }
    val save = when { !state.namingForPhoto -> "Save"; state.removingPhoto -> "Remove Photo"; else -> "Save Photo" }
    com.circaso.designsystem.chrome.SheetActions(
        save, onSave, "Cancel", onCancel,
        enabled = (state.canChange || state.saving) && state.nameDraft.isNotBlank() && (state.namingForPhoto || (state.editChanged && !state.descriptionTooLong)),
        busy = state.saving,
        note = state.editError ?: if (!state.online) "Changing the group needs a connection." else null,
        noteIsError = state.editError != null,
    )
}

/**
 * A group's description: under its name in Group info, and on the join sheet before someone joins. [lines] lines
 * until tapped, then whole, with "Show all" / "Show less" under it when there is more, so the rest is never out of
 * reach. The height change settles rather than snapping (not under Reduce Motion).
 */
@Composable
internal fun GroupDescription(text: String, modifier: Modifier = Modifier, lines: Int = GroupDescriptionLines) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    var expanded by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    val more = overflows || expanded
    Column(
        modifier
            .then(if (more) Modifier.clip(CircasoShapes.sm).pressable(onClick = { expanded = !expanded }, onClickLabel = if (expanded) "Show less" else "Show all") else Modifier)
            .then(if (CircasoTheme.reduceMotion) Modifier else Modifier.animateContentSize(androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.BaseMs))),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1),
    ) {
        BasicText(
            text,
            style = t.body.copy(color = c.ink, textAlign = TextAlign.Center),
            maxLines = if (expanded) Int.MAX_VALUE else lines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (more) BasicText(if (expanded) "Show less" else "Show all", style = t.footnote.copy(color = c.inkSecondary))
    }
}

private const val GroupDescriptionLines = 4

/** The description's count appears from here on. */
private const val DescriptionCountFrom = 400

@Composable
internal fun PeerNameLines(peer: ChatMember?) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    if (peer?.renamed == true) ProfileNameLine(peer.profileName, t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    peer?.handle?.let { BasicText("@$it", style = t.body.copy(color = c.inkSecondary)) }
}

/** Contact info's Edit name row (saved names, 2026-11-12): opens a sheet, so no chevron, like Rename group. */
@Composable
internal fun EditNameRow(onClick: () -> Unit) {
    ListRow(
        "Edit name", sub = "Only you see this name",
        leading = { CircasoIconImage(CircasoIcon.Pencil, CircasoTheme.colors.ink, Modifier.size(20.dp)) },
        onClick = onClick,
        modifier = Modifier.padding(top = CircasoSpacing.s4),
    )
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

/** The security code card's item, which a "security code changed" notice opens at. */
private const val SecurityCodeKey = "security-code"
