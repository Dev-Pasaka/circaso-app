package com.circaso.feature.messaging.presentation

import com.circaso.feature.messaging.domain.ObserveThreadUseCase
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.Message
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.messaging.domain.AddMembersUseCase
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.LeaveGroupUseCase
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.ObserveChatUseCase
import com.circaso.feature.messaging.domain.RemoveMemberUseCase
import com.circaso.feature.messaging.domain.RenameGroupUseCase
import com.circaso.feature.messaging.domain.SecurityCode
import com.circaso.feature.messaging.domain.SecurityCodeUseCase
import com.circaso.feature.messaging.domain.SetMembersCanAddUseCase
import com.circaso.feature.messaging.domain.SetRoleUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface InfoAction {
    data class Remove(val member: ChatMember) : InfoAction
    data class MakeAdmin(val member: ChatMember) : InfoAction
    data class MakeMember(val member: ChatMember) : InfoAction
    data class HandOver(val member: ChatMember) : InfoAction
    data object Leave : InfoAction
}

/** Group info's sheets for the group's money (GW7, CH9, §11.9 leaving, W8 Change PIN). */
enum class MoneySheet { TargetNeedsWallet, Approvals, LeaveBlocked, ChangePin }

/** Members and roles, the group's name and settings, and the safety number for a direct chat. Every change needs the server. */
data class ChatInfoUiState(
    val chat: Chat? = null,
    val online: Boolean = true,
    val busy: Boolean = false,
    val securityCode: SecurityCode? = null,
    /** The code this phone last marked as verified with the other person; [codeVerified] compares it to today's. */
    val verifiedDigits: String? = null,
    /** The code has been worked out (it may still be null: no session yet), so "appears after your first message" is true. */
    val securityCodeLoaded: Boolean = false,
    val selected: ChatMember? = null,
    val pending: InfoAction? = null,
    val renaming: Boolean = false,
    val nameDraft: String = "",
    /** The description being typed in the Edit group sheet (2026-11-14); blank removes it. */
    val descriptionDraft: String = "",
    /** The Edit group change is on its way: the sheet stays, its button busy, until the server answers. */
    val saving: Boolean = false,
    /** Why the last save didn't go through, said in the sheet beside the draft it kept. */
    val editError: String? = null,
    /** The group's join link is on (2026-11-14): its page shows the name, description and photo to anyone with it. */
    val linkOn: Boolean = false,
    val adding: Boolean = false,
    val contacts: List<Contact> = emptyList(),
    val toAdd: Set<String> = emptySet(),
    val message: String? = null,
    val left: Boolean = false,
    /** Photos, voice notes and places shared in this chat, newest first (WhatsApp's "Media, links and docs"). */
    val media: List<Message.Media> = emptyList(),
    /** Every message, for the profile's Search. */
    val messages: List<Message> = emptyList(),
    val searching: Boolean = false,
    val query: String = "",
    /** The group's money (group wallets, GW6–GW8); `enabled` false keeps the treasurer-model row. */
    val money: com.circaso.feature.chama.presentation.GroupInfoMoney = com.circaso.feature.chama.presentation.GroupInfoMoney(),
    val moneySheet: MoneySheet? = null,
    val policyChoice: com.circaso.feature.chama.domain.ApprovalPolicy = com.circaso.feature.chama.domain.ApprovalPolicy.TwoAdmins,
    val myUserId: String? = null,
    /** This phone can make group photos (the encoder is there and photos are wired). */
    val photosAvailable: Boolean = false,
    /** A new group photo is being prepared, sent or removed (M8). */
    val photoBusy: Boolean = false,
    /** The new photo while it is being sent: shown under the spinner at once, so the change is visible. */
    val pendingPhoto: ByteArray? = null,
    val photoChoice: com.circaso.feature.profile.presentation.PhotoChoiceState = com.circaso.feature.profile.presentation.PhotoChoiceState(),
    /**
     * The name sheet is open for a photo change, not a rename: this phone lost the group's key and can't read its
     * name, and a new key seals the name with the photo, so the admin types it.
     */
    val namingForPhoto: Boolean = false,
    /** With [namingForPhoto]: the change waiting for the name removes the photo rather than setting one. */
    val removingPhoto: Boolean = false,
    /** People invited and not yet answered (group invites, 2026-11-09), oldest first: group info's "Invited". */
    val invited: List<com.circaso.feature.messaging.domain.PendingInvite> = emptyList(),
    /** The invite whose Cancel Invite confirm is open. */
    val cancelling: com.circaso.feature.messaging.domain.PendingInvite? = null,
) {
    /** An admin, or whoever sent it, may take an invite back. */
    fun mayCancel(invite: com.circaso.feature.messaging.domain.PendingInvite) = amAdmin || invite.invitedBy.userId == myUserId
    /** The Edit group sheet has something to save: a name, and a name or description that differs from the group's. */
    val editChanged get() = nameDraft.trim().isNotEmpty() &&
        (nameDraft.trim() != chat?.title || descriptionDraft.trim() != chat.description.orEmpty() || nameUnknown)
    /** Over the limit: said under the field, and Save rests. */
    val descriptionTooLong get() = descriptionDraft.trim().length > com.circaso.feature.messaging.data.repository.MaxGroupDescriptionChars
    /** A group whose name this phone can't read: the name sheet starts empty and says why. */
    val nameUnknown get() = isGroup && chat?.nameKnown == false
    /** Admins change the group's photo (M8); it needs a connection, like every change to the group. */
    val canChangePhoto get() = isGroup && amAdmin && photosAvailable && online && !photoBusy && !busy
    val hasGroupPhoto get() = isGroup && chat?.avatarUrl != null
    /** §11.9: the signer may not leave while the group wallet holds money. */
    val leavingBlocked: Boolean get() {
        val m = money.money ?: return false
        if (!money.enabled || !m.isSigner || !m.hasWallet) return false
        val held = money.balance?.totals.orEmpty().any { !com.circaso.feature.wallet.domain.MinorUnits.isZero(it.amountMinor) }
        return held
    }
    val moneyBalanceText: String? get() = money.balance?.usdc?.let { com.circaso.feature.chama.presentation.amountText(it.amountMinor, money.balance.fiat) }
    /** Text messages containing the query, newest first. */
    val found: List<Message.Text>
        get() = if (query.isBlank()) emptyList()
        else messages.filterIsInstance<Message.Text>().filter { it.text.contains(query.trim(), ignoreCase = true) }.reversed()
    val isGroup get() = chat?.kind == ChatKind.Group
    /** Verified only for the very code it was checked against: a changed code is not verified. */
    val codeVerified get() = securityCode != null && securityCode.digits == verifiedDigits
    /** Checked once, and the code has changed since (they signed in again): the moment to check again. */
    val codeChangedSinceVerified get() = securityCode != null && verifiedDigits != null && securityCode.digits != verifiedDigits
    val amAdmin get() = chat?.myRole == MemberRole.Admin || chat?.myRole == MemberRole.Owner
    val amOwner get() = chat?.myRole == MemberRole.Owner
    val canAdd get() = online && !busy && (amAdmin || chat?.membersCanAdd == true)
    val canChange get() = online && !busy
    /** People in my contacts who are not in the group yet, and not already invited. */
    val addable get() = contacts.filter { c ->
        chat?.members?.none { it.userId == c.person.userId } == true && invited.none { it.person.userId == c.person.userId }
    }
}

class ChatInfoViewModel(
    private val chatId: String,
    observeChat: ObserveChatUseCase,
    observeContacts: ObserveContactsUseCase,
    private val securityCodeFor: SecurityCodeUseCase,
    private val rename: RenameGroupUseCase,
    private val setMembersCanAdd: SetMembersCanAddUseCase,
    private val addMembers: AddMembersUseCase,
    private val removeMember: RemoveMemberUseCase,
    private val leaveGroup: LeaveGroupUseCase,
    private val setRole: SetRoleUseCase,
    connection: ConnectionState,
    observeThread: ObserveThreadUseCase? = null,
    /** Loads shared photos for the profile's media strip; null in previews. */
    val media: ThreadMedia? = null,
    /** The group's money (group wallets on); null in previews and direct chats' tests. */
    private val groupMoney: com.circaso.feature.chama.presentation.GroupInfoMoneySource? = null,
    myUserId: String? = null,
    private val setGroupPhoto: com.circaso.feature.messaging.domain.SetGroupPhotoUseCase? = null,
    private val encoder: com.circaso.core.platform.GroupPhotoEncoder = com.circaso.core.platform.NoGroupPhotoEncoder,
    sources: com.circaso.feature.profile.presentation.PhotoSources? = null,
    /** Group invites (2026-11-09): the Invited list and Cancel Invite; null in previews. */
    private val invites: com.circaso.feature.messaging.domain.GroupInvitesRepository? = null,
    /** Edit name (saved names, 2026-11-12): for the other person in a direct chat, or any member; null in previews. */
    val editName: com.circaso.feature.names.presentation.EditNameController? = null,
    /** Which security codes this phone has checked (2026-11-13); null in previews and tests. */
    private val verifiedCodes: com.circaso.feature.messaging.domain.VerifiedCodes? = null,
    /** The name and description together (2026-11-14); null in older tests, which rename only. */
    private val editGroup: com.circaso.feature.messaging.domain.EditGroupUseCase? = null,
    /** Group join links (2026-11-14): whether this group's link is on, from this phone's copy; null in previews. */
    private val links: com.circaso.feature.messaging.domain.GroupLinksRepository? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ChatInfoUiState(myUserId = myUserId, photosAvailable = setGroupPhoto != null && sources != null && encoder !== com.circaso.core.platform.NoGroupPhotoEncoder),
    )
    val state: StateFlow<ChatInfoUiState> = _state.asStateFlow()

    init { editName?.onProblem = { text -> _state.update { it.copy(message = text) } } }

    /** The group photo's sheet and camera (the profile photo's pattern); null in previews. */
    val photos: com.circaso.feature.profile.presentation.PhotoChooser? =
        sources?.let { com.circaso.feature.profile.presentation.PhotoChooser(viewModelScope, it, ::onPhotoPicked) }

    /** A photo from the sheet or the camera: squared and encoded here, then sealed into the group (M8). */
    fun onPhotoPicked(picked: com.circaso.core.platform.PickedImage?, unreadable: String?) {
        if (picked == null) { if (unreadable != null) _state.update { it.copy(message = unreadable) }; return }
        val set = setGroupPhoto ?: return
        if (!_state.value.canChangePhoto) return
        _state.update { it.copy(photoBusy = true, message = null) }
        viewModelScope.launch {
            val image = try { encoder.encode(picked.bytes) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            if (image == null) { _state.update { it.copy(photoBusy = false, message = "Couldn't use that photo. Try another one.") }; return@launch }
            _state.update { it.copy(pendingPhoto = image.full) }
            sendPhoto(set, PhotoChange(image), name = null)
        }
    }

    /** A photo change waiting for the group's name (see [ChatInfoUiState.namingForPhoto]); [image] null removes it. */
    private class PhotoChange(val image: com.circaso.core.platform.GroupPhotoImage?)
    private var awaitingName: PhotoChange? = null

    private suspend fun sendPhoto(set: com.circaso.feature.messaging.domain.SetGroupPhotoUseCase, change: PhotoChange, name: String?) {
        val r = set(chatId, change.image, name)
        val code = ((r as? AppResult.Failure)?.error as? com.circaso.core.common.AppError.Api)?.code
        if (code == com.circaso.feature.messaging.domain.GroupKeyErrors.NameNeeded) {
            // The photo stays in hand while the admin types the name; nothing was uploaded yet.
            awaitingName = change
            _state.update { it.copy(photoBusy = false, pendingPhoto = null, renaming = true, namingForPhoto = true, removingPhoto = change.image == null, nameDraft = "") }
            return
        }
        awaitingName = null
        val done = if (change.image == null) "The group photo is removed." else "The group photo is updated."
        _state.update { it.copy(photoBusy = false, pendingPhoto = null, message = if (r is AppResult.Failure) withRef(r.error) else done) }
    }

    /** Remove Photo: the group goes back to its initials for everyone. */
    fun onRemovePhoto() {
        photos?.dismiss()
        val set = setGroupPhoto ?: return
        if (!_state.value.canChangePhoto || !_state.value.hasGroupPhoto) return
        _state.update { it.copy(photoBusy = true, message = null) }
        viewModelScope.launch { sendPhoto(set, PhotoChange(null), name = null) }
    }

    override fun onCleared() { photos?.closeCamera() }

    /** CH9 keeps one key per choice, so a slow first answer can't be applied twice. */
    private var policyKey = com.circaso.core.network.newIdempotencyKey()

    init {
        photos?.let { p -> viewModelScope.launch { p.state.collect { choice -> _state.update { it.copy(photoChoice = choice) } } } }
        groupMoney?.let { source ->
            viewModelScope.launch { source.observe(chatId).collect { m -> _state.update { it.copy(money = m) } } }
            viewModelScope.launch {
                // Only for groups: a direct chat has no money of its own.
                val chat = observeChat(chatId).first { it != null }
                if (chat?.kind == ChatKind.Group) source.refresh(chatId)
            }
        }
        viewModelScope.launch {
            observeChat(chatId).collect { chat ->
                _state.update { it.copy(chat = chat) }
                val peerId = chat?.peerId
                if (chat?.kind == ChatKind.Direct && peerId != null) { val code = securityCodeFor(peerId); _state.update { it.copy(securityCode = code, securityCodeLoaded = true) } }
            }
        }
        verifiedCodes?.let { store ->
            viewModelScope.launch {
                val peerId = observeChat(chatId).first { it != null }?.takeIf { it.kind == ChatKind.Direct }?.peerId ?: return@launch
                store.verifiedCode(peerId).collect { digits -> _state.update { it.copy(verifiedDigits = digits) } }
            }
        }
        viewModelScope.launch { observeContacts().collect { list -> _state.update { it.copy(contacts = list) } } }
        observeThread?.let { thread ->
            viewModelScope.launch {
                thread(chatId).collect { list ->
                    val shared = list.filterIsInstance<Message.Media>().filter { it.state != DeliveryState.Failed }.reversed()
                    _state.update { it.copy(media = shared, messages = list) }
                }
            }
        }
        viewModelScope.launch { connection.status.collect { s -> _state.update { it.copy(online = s == ConnectionStatus.Online) } } }
        // A link that is on makes the name, description and photo public: Group info says so, from this phone's copy.
        links?.let { repo -> viewModelScope.launch { repo.link(chatId).collect { link -> _state.update { it.copy(linkOn = link != null) } } } }
        invites?.let { repo ->
            viewModelScope.launch {
                // Only a group has invites; loaded once it is known to be one, then again whenever the server says so.
                val chat = observeChat(chatId).first { it != null }
                if (chat?.kind != ChatKind.Group) return@launch
                loadInvited()
                repo.changed.collect { changed -> if (changed == chatId) loadInvited() }
            }
        }
    }

    /** The Invited list from the server (M26). Offline it keeps what it showed; nothing is made up. */
    private suspend fun loadInvited() {
        val repo = invites ?: return
        val r = repo.pending(chatId)
        if (r is AppResult.Success) _state.update { it.copy(invited = r.value) }
    }

    fun onCancelInvite(invite: com.circaso.feature.messaging.domain.PendingInvite?) = _state.update { it.copy(cancelling = invite) }

    /** Cancel Invite (M27): online-only; the row goes once the server took it back. */
    fun onCancelInviteConfirm() {
        val invite = _state.value.cancelling ?: return
        val repo = invites ?: return
        // The sheet closes whatever happens, so it can never sit there doing nothing.
        if (!_state.value.canChange) { _state.update { it.copy(cancelling = null, message = "Cancelling an invite needs a connection.") }; return }
        _state.update { it.copy(cancelling = null, busy = true, message = null) }
        viewModelScope.launch {
            val r = repo.cancel(chatId, invite.person.userId)
            val gone = r is AppResult.Success || ((r as? AppResult.Failure)?.error as? com.circaso.core.common.AppError.Api)?.code == "messaging.invite_not_found"
            _state.update {
                it.copy(
                    busy = false,
                    invited = if (gone) it.invited.filterNot { p -> p.inviteId == invite.inviteId } else it.invited,
                    message = if (r is AppResult.Failure && !gone) withRef(r.error) else "Invite cancelled.",
                )
            }
        }
    }

    fun onSelect(member: ChatMember?) = _state.update { it.copy(selected = member) }
    /** Mark as Verified / Clear Verification: this phone's note, so it works offline. */
    fun onVerified(verified: Boolean) {
        val store = verifiedCodes ?: return
        val peerId = _state.value.chat?.peerId ?: return
        val digits = _state.value.securityCode?.digits ?: return
        _state.update { it.copy(verifiedDigits = if (verified) digits else null) }
        viewModelScope.launch { store.setVerifiedCode(peerId, if (verified) digits else null) }
    }

    fun onSearch(open: Boolean) = _state.update { it.copy(searching = open, query = if (open) it.query else "") }
    fun onQuery(text: String) = _state.update { it.copy(query = text) }
    fun onAction(action: InfoAction?) {
        // §11.9: leaving would lock the group's money; say so and offer the way out instead of a confirm to tap through.
        if (action == InfoAction.Leave && _state.value.leavingBlocked) { _state.update { it.copy(moneySheet = MoneySheet.LeaveBlocked, selected = null) }; return }
        _state.update { it.copy(pending = action, selected = null) }
    }

    fun onMoneySheet(sheet: MoneySheet?) = _state.update {
        it.copy(moneySheet = sheet, policyChoice = if (sheet == MoneySheet.Approvals) it.money.money?.approvalPolicy ?: it.policyChoice else it.policyChoice)
    }

    fun onRetryMoney() { val source = groupMoney ?: return; viewModelScope.launch { source.refresh(chatId) } }

    fun onPolicyChoice(policy: com.circaso.feature.chama.domain.ApprovalPolicy) { if (policy != _state.value.policyChoice) policyKey = com.circaso.core.network.newIdempotencyKey(); _state.update { it.copy(policyChoice = policy) } }

    /** CH9 (the owner): online-only, announced to every member. */
    fun onSavePolicy() {
        val source = groupMoney ?: return
        val s = _state.value
        if (!s.canChange) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = source.setApprovalPolicy(chatId, s.policyChoice, policyKey)
            _state.update { it.copy(busy = false, moneySheet = if (r is AppResult.Success) null else it.moneySheet, message = (r as? AppResult.Failure)?.let { f -> withRef(f.error) }) }
            if (r is AppResult.Success) policyKey = com.circaso.core.network.newIdempotencyKey()
        }
    }

    /** GW8: this wallet's fingerprint / Face ID: on through Circle with the wallet's own PIN; off back to its PIN. */
    fun onWalletBiometrics(on: Boolean) {
        val source = groupMoney ?: return
        val s = _state.value
        val walletId = s.money.money?.wallet?.walletId ?: return
        if (!on) { viewModelScope.launch { source.turnOffBiometrics(walletId) }; return }
        if (!s.canChange) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = source.turnOnBiometrics(walletId, s.chat?.title ?: "the group")
            val cancelled = ((r as? AppResult.Failure)?.error as? com.circaso.core.common.AppError.Api)?.code == "wallet.cancelled"
            _state.update { it.copy(busy = false, message = (r as? AppResult.Failure)?.takeIf { !cancelled }?.let { f -> withRef(f.error) }) }
        }
    }

    fun onConfirm() {
        val action = _state.value.pending ?: return
        // The sheet greys its button offline; a tap racing the connection dropping still never fires blind.
        if (!_state.value.canChange) return
        _state.update { it.copy(pending = null, busy = true, message = null) }
        viewModelScope.launch {
            val result: AppResult<*> = when (action) {
                is InfoAction.Remove -> removeMember(chatId, action.member.userId)
                is InfoAction.MakeAdmin -> setRole(chatId, action.member.userId, MemberRole.Admin)
                is InfoAction.MakeMember -> setRole(chatId, action.member.userId, MemberRole.Member)
                is InfoAction.HandOver -> setRole(chatId, action.member.userId, MemberRole.Owner)
                InfoAction.Leave -> leaveGroup(chatId)
            }
            _state.update { it.copy(busy = false, message = (result as? AppResult.Failure)?.let { f -> ErrorMessages.forError(f.error) }, left = action == InfoAction.Leave && result is AppResult.Success) }
        }
    }

    fun onRename(start: Boolean) {
        // A save on its way finishes first: the sheet closes by itself when the server answers.
        if (!start && _state.value.saving) return
        if (!start) awaitingName = null
        // A name this phone can't read is not prefilled: "Group" is only the placeholder, not the group's name.
        _state.update {
            it.copy(
                renaming = start, namingForPhoto = false, removingPhoto = false, editError = null,
                nameDraft = if (start && !it.nameUnknown) it.chat?.title.orEmpty() else "",
                descriptionDraft = if (start) it.chat?.description.orEmpty() else "",
            )
        }
    }
    fun onNameDraft(text: String) = _state.update { it.copy(nameDraft = text, editError = null) }
    fun onDescriptionDraft(text: String) = _state.update { it.copy(descriptionDraft = text, editError = null) }
    fun onRenameConfirm() {
        val name = _state.value.nameDraft.trim()
        if (name.isEmpty() || !_state.value.canChange) return
        val change = awaitingName
        val set = setGroupPhoto
        if (_state.value.namingForPhoto && change != null && set != null) {
            _state.update { it.copy(renaming = false, namingForPhoto = false, removingPhoto = false, photoBusy = true, pendingPhoto = change.image?.full, message = null) }
            viewModelScope.launch { sendPhoto(set, change, name) }
            return
        }
        if (_state.value.descriptionTooLong) return
        // A phone that can't read the group's name can't read its description either: the one it has is kept, not
        // replaced by the empty field it couldn't fill.
        val description = if (_state.value.nameUnknown) _state.value.chat?.description.orEmpty() else _state.value.descriptionDraft
        // The sheet stays open, busy, until the server answers: a failure keeps what was typed and says why there.
        _state.update { it.copy(saving = true, busy = true, editError = null) }
        viewModelScope.launch {
            val r = editGroup?.invoke(chatId, name, description) ?: rename(chatId, name)
            _state.update {
                if (r is AppResult.Failure) it.copy(saving = false, busy = false, editError = withRef(r.error))
                else it.copy(saving = false, busy = false, renaming = false, namingForPhoto = false)
            }
        }
    }

    fun onMembersCanAdd(allowed: Boolean) {
        if (!_state.value.canChange) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = setMembersCanAdd(chatId, allowed)
            _state.update { it.copy(busy = false, message = (r as? AppResult.Failure)?.let { f -> ErrorMessages.forError(f.error) }) }
        }
    }

    fun onAdding(start: Boolean) = _state.update { it.copy(adding = start, toAdd = emptySet()) }
    fun onToggleAdd(userId: String) = _state.update { it.copy(toAdd = if (userId in it.toAdd) it.toAdd - userId else it.toAdd + userId) }
    fun onAddConfirm() {
        val ids = _state.value.toAdd.toList()
        if (ids.isEmpty() || !_state.value.canAdd) return
        _state.update { it.copy(adding = false, busy = true) }
        viewModelScope.launch {
            val r = addMembers(chatId, ids)
            val note = when (r) {
                is AppResult.Failure -> ErrorMessages.forError(r.error)
                is AppResult.Success -> addedNote(r.value.invited.size, r.value.notAdded.size)
            }
            _state.update { it.copy(busy = false, message = note, toAdd = emptySet()) }
            if (r is AppResult.Success) loadInvited()
        }
    }

    fun onMessageShown() = _state.update { it.copy(message = null) }

    /** The error's words, with its reference for support. */
    private fun withRef(error: com.circaso.core.common.AppError) = ErrorMessages.forError(error) + (ErrorMessages.reference(error)?.let { " Ref $it" } ?: "")
}

/**
 * What an add says (group invites, 2026-11-09): people are invited and join when they accept; anyone who couldn't be
 * reached is counted, with no reason (guide, M9). Null when there's nothing to say.
 */
internal fun addedNote(invited: Int, notAdded: Int): String? {
    fun people(n: Int) = if (n == 1) "1 person" else "$n people"
    val sent = if (invited > 0) "Invited ${people(invited)}. They'll join when they accept." else null
    val missed = if (notAdded > 0) "${people(notAdded)} couldn't be invited." else null
    return listOfNotNull(sent, missed).joinToString(" ").ifEmpty { null }
}
