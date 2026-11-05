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
    val selected: ChatMember? = null,
    val pending: InfoAction? = null,
    val renaming: Boolean = false,
    val nameDraft: String = "",
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
    val photoChoice: com.circaso.feature.profile.presentation.PhotoChoiceState = com.circaso.feature.profile.presentation.PhotoChoiceState(),
) {
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
    val amAdmin get() = chat?.myRole == MemberRole.Admin || chat?.myRole == MemberRole.Owner
    val amOwner get() = chat?.myRole == MemberRole.Owner
    val canAdd get() = online && !busy && (amAdmin || chat?.membersCanAdd == true)
    val canChange get() = online && !busy
    /** People in my contacts who are not in the group yet. */
    val addable get() = contacts.filter { c -> chat?.members?.none { it.userId == c.person.userId } == true }
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
) : ViewModel() {
    private val _state = MutableStateFlow(
        ChatInfoUiState(myUserId = myUserId, photosAvailable = setGroupPhoto != null && sources != null && encoder !== com.circaso.core.platform.NoGroupPhotoEncoder),
    )
    val state: StateFlow<ChatInfoUiState> = _state.asStateFlow()

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
            val r = set(chatId, image)
            _state.update { it.copy(photoBusy = false, message = if (r is AppResult.Failure) withRef(r.error) else "The group photo is updated.") }
        }
    }

    /** Remove Photo: the group goes back to its initials for everyone. */
    fun onRemovePhoto() {
        photos?.dismiss()
        val set = setGroupPhoto ?: return
        if (!_state.value.canChangePhoto || !_state.value.hasGroupPhoto) return
        _state.update { it.copy(photoBusy = true, message = null) }
        viewModelScope.launch {
            val r = set(chatId, null)
            _state.update { it.copy(photoBusy = false, message = if (r is AppResult.Failure) withRef(r.error) else "The group photo is removed.") }
        }
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
                if (chat?.kind == ChatKind.Direct && peerId != null) _state.update { it.copy(securityCode = securityCodeFor(peerId)) }
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
    }

    fun onSelect(member: ChatMember?) = _state.update { it.copy(selected = member) }
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

    fun onRename(start: Boolean) = _state.update { it.copy(renaming = start, nameDraft = if (start) it.chat?.title.orEmpty() else "") }
    fun onNameDraft(text: String) = _state.update { it.copy(nameDraft = text) }
    fun onRenameConfirm() {
        val name = _state.value.nameDraft.trim()
        if (name.isEmpty() || !_state.value.canChange) return
        _state.update { it.copy(renaming = false, busy = true) }
        viewModelScope.launch {
            val r = rename(chatId, name)
            _state.update { it.copy(busy = false, message = (r as? AppResult.Failure)?.let { f -> ErrorMessages.forError(f.error) }) }
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
                is AppResult.Success -> if (r.value.notAdded.isNotEmpty()) "${r.value.notAdded.size} ${if (r.value.notAdded.size == 1) "person" else "people"} couldn't be added." else null
            }
            _state.update { it.copy(busy = false, message = note, toAdd = emptySet()) }
        }
    }

    fun onMessageShown() = _state.update { it.copy(message = null) }

    /** The error's words, with its reference for support. */
    private fun withRef(error: com.circaso.core.common.AppError) = ErrorMessages.forError(error) + (ErrorMessages.reference(error)?.let { " Ref $it" } ?: "")
}
