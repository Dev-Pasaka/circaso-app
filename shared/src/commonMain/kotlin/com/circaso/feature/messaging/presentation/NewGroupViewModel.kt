package com.circaso.feature.messaging.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.GroupPhotoEncoder
import com.circaso.core.platform.GroupPhotoImage
import com.circaso.core.platform.NoGroupPhotoEncoder
import com.circaso.core.platform.PickedImage
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.messaging.domain.CreateGroupUseCase
import com.circaso.feature.messaging.domain.GroupDraft
import com.circaso.feature.messaging.domain.SetGroupPhotoUseCase
import com.circaso.feature.profile.presentation.PhotoChoiceState
import com.circaso.feature.profile.presentation.PhotoChooser
import com.circaso.feature.profile.presentation.PhotoSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** New Group in two steps, as Messages and Telegram do: choose the people, then name the group and give it a photo. */
enum class NewGroupStep { Members, Details }

/** What the Create button is doing: making the group (M5), then sealing its photo into it (AT1–AT2, M8). */
enum class CreateStage { Group, Photo }

/**
 * Designs D1 and GW1: step 1 picks members (an avatar strip on top, a searchable list with checkmarks); step 2 has
 * the group photo, the name with its counter, the "Group wallet" switch (group wallets on) with an optional target and
 * who approves payouts, and the members. Creating is online-only. If the group was made but its photo didn't upload,
 * the form stays with the group's id: Try Again uploads only the photo, and the person can go on without it.
 */
data class NewGroupUiState(
    val step: NewGroupStep = NewGroupStep.Members,
    val name: String = "",
    val contacts: List<Contact> = emptyList(),
    /** Chosen user ids, in the order they were picked (the strip on top keeps that order). */
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val online: Boolean = true,
    val loaded: Boolean = false,
    val creating: Boolean = false,
    val stage: CreateStage? = null,
    val error: String? = null,
    val notAdded: List<String> = emptyList(),
    /** Group wallets are on (the flag, and a server that has them): GW1's switch shows. */
    val walletsAvailable: Boolean = false,
    /** GW1's "Group wallet" switch: off by default. */
    val walletOn: Boolean = false,
    /** The optional target, typed in the person's currency when a rate is known, else in USDC. */
    val targetText: String = "",
    val by: com.circaso.feature.chama.presentation.GoalDeadline = com.circaso.feature.chama.presentation.GoalDeadline.None,
    val approvalPolicy: com.circaso.feature.chama.domain.ApprovalPolicy = com.circaso.feature.chama.domain.ApprovalPolicy.TwoAdmins,
    val fiat: com.circaso.feature.wallet.domain.FiatValue? = null,
    /** GW1d: the create call failed after its retries. Nothing was made; Try Again reuses the same key. */
    val failed: Boolean = false,
    val reference: String? = null,
    /** The group photo, ready to seal (square, 1024 px, with its thumbnail); null for none. */
    val photo: GroupPhotoImage? = null,
    /** A picked photo is being squared and encoded. */
    val preparingPhoto: Boolean = false,
    /** This phone can make group photos (the platform encoder is there). */
    val photosAvailable: Boolean = false,
    val photoChoice: PhotoChoiceState = PhotoChoiceState(),
    /** The group exists but its photo didn't go up: Try Again sends only the photo; Skip goes on to the chat. */
    val createdChatId: String? = null,
) {
    val trimmedName get() = name.trim()
    val nameTooLong get() = name.length > MaxGroupName
    val canNext get() = selected.isNotEmpty()
    val canCreate get() = online && !creating && !preparingPhoto && trimmedName.isNotEmpty() && !nameTooLong && selected.isNotEmpty()
    val photoFailed get() = createdChatId != null

    /** The target in USDC minor units, null when none was typed (or it isn't an amount). */
    val targetMinor: String? get() {
        val text = targetText.trim().ifEmpty { return null }
        val minor = fiat?.let { com.circaso.feature.wallet.domain.fiatToUsdcMinor(text, it.rate) } ?: com.circaso.feature.wallet.domain.MinorUnits.fromDisplay(text, 6)
        return minor?.takeIf { !com.circaso.feature.wallet.domain.MinorUnits.isZero(it) }
    }

    /** Everyone matching the search (name or @handle), A to Z. */
    val shownContacts: List<Contact> get() {
        val q = query.trim().removePrefix("@")
        val all = contacts.sortedBy { it.person.displayName.lowercase() }
        return if (q.isEmpty()) all else all.filter { it.person.displayName.contains(q, true) || it.person.profileName.contains(q, true) || it.person.handle?.contains(q, true) == true }
    }

    /** The chosen people in the order they were picked. */
    val selectedContacts: List<Contact> get() {
        val byId = contacts.associateBy { it.person.userId }
        return selected.mapNotNull { byId[it] }
    }

    companion object {
        /** Long enough for "Kilimani Chama · Savings 2027", short enough for a chat list row and the sealed 4 KB. */
        const val MaxGroupName = 64
    }
}

class NewGroupViewModel(
    observeContacts: ObserveContactsUseCase,
    private val createGroup: CreateGroupUseCase,
    connection: ConnectionState,
    groupWallets: com.circaso.core.flags.GroupWalletsFlag = com.circaso.core.flags.GroupWalletsFlag.off(),
    observeWallet: com.circaso.feature.wallet.domain.ObserveWalletUseCase? = null,
    private val setGroupPhoto: SetGroupPhotoUseCase? = null,
    private val encoder: GroupPhotoEncoder = NoGroupPhotoEncoder,
    sources: PhotoSources? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(NewGroupUiState(photosAvailable = setGroupPhoto != null && encoder !== NoGroupPhotoEncoder))
    val state: StateFlow<NewGroupUiState> = _state.asStateFlow()

    /** One key per attempt: a retry after a timeout carries on with the same group (guide, M5). */
    private var attemptKey: String? = null

    /** The photo sheet and camera (the profile photo's pattern); null in previews and tests without photos. */
    val photos: PhotoChooser? = sources?.let { PhotoChooser(viewModelScope, it, ::onPhotoPicked) }

    init {
        viewModelScope.launch { observeContacts().collect { list -> _state.update { it.copy(contacts = list, loaded = true) } } }
        viewModelScope.launch { connection.status.collect { s -> _state.update { it.copy(online = s == ConnectionStatus.Online) } } }
        viewModelScope.launch { groupWallets.enabled.collect { on -> _state.update { it.copy(walletsAvailable = on, walletOn = it.walletOn && on) } } }
        observeWallet?.let { w -> viewModelScope.launch { w().collect { wallet -> _state.update { it.copy(fiat = wallet?.fiat) } } } }
        photos?.let { p -> viewModelScope.launch { p.state.collect { choice -> _state.update { it.copy(photoChoice = choice) } } } }
    }

    // ---- step 1: members

    fun onQuery(text: String) = _state.update { it.copy(query = text) }
    fun onToggle(userId: String) {
        if (_state.value.creating || _state.value.createdChatId != null) return
        _state.update { it.copy(selected = if (userId in it.selected) it.selected - userId else it.selected + userId, error = null) }
        attemptKey = null
    }

    fun onNext() { if (_state.value.canNext) _state.update { it.copy(step = NewGroupStep.Details, query = "") } }

    /** Back from the details goes to the people (nothing is lost); from the people it leaves. True when it was handled here. */
    fun onBack(): Boolean {
        val s = _state.value
        // While the group or its photo is being sent, back waits (leaving would cancel the upload half way).
        if (s.creating) return true
        if (s.step == NewGroupStep.Details && s.createdChatId == null) { _state.update { it.copy(step = NewGroupStep.Members) }; return true }
        return false
    }

    // ---- step 2: details

    fun onName(text: String) {
        // Typing past the limit is kept out rather than cut off later; a paste is trimmed to fit.
        val next = if (text.length > NewGroupUiState.MaxGroupName && text.length > _state.value.name.length) text.take(NewGroupUiState.MaxGroupName) else text
        _state.update { it.copy(name = next, error = null) }
        attemptKey = null
    }

    fun onWalletSwitch(on: Boolean) = _state.update { it.copy(walletOn = on && it.walletsAvailable && it.online) }
    fun onTarget(text: String) {
        val clean = text.filter { it.isDigit() || it == '.' }
        if (clean.count { it == '.' } > 1) return
        _state.update { it.copy(targetText = clean) }
    }
    fun onBy(by: com.circaso.feature.chama.presentation.GoalDeadline) = _state.update { it.copy(by = by) }
    fun onApprovalPolicy(policy: com.circaso.feature.chama.domain.ApprovalPolicy) = _state.update { it.copy(approvalPolicy = policy) }

    /** What the set-up carries on with once the group exists (GW2–GW5), or null without a wallet. */
    fun walletPlan(): com.circaso.feature.chama.presentation.GroupWalletPlan? {
        val s = _state.value
        if (!s.walletOn) return null
        return com.circaso.feature.chama.presentation.GroupWalletPlan(
            approvalPolicy = s.approvalPolicy, targetMinor = s.targetMinor, targetName = s.trimmedName.takeIf { s.targetMinor != null },
            deadline = if (s.targetMinor != null) com.circaso.feature.chama.presentation.deadlineIso(s.by) else null,
        )
    }

    /** A photo from the sheet or the camera: squared and encoded on this phone; nothing leaves it until Create. */
    fun onPhotoPicked(picked: PickedImage?, unreadable: String?) {
        if (picked == null) { if (unreadable != null) _state.update { it.copy(error = unreadable) }; return }
        _state.update { it.copy(preparingPhoto = true, error = null) }
        viewModelScope.launch {
            val image = try { encoder.encode(picked.bytes) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            _state.update { it.copy(preparingPhoto = false, photo = image ?: it.photo, error = if (image == null) "Couldn't use that photo. Try another one." else null) }
        }
    }

    fun onRemovePhoto() { photos?.dismiss(); _state.update { it.copy(photo = null) } }

    fun onCreate(onCreated: (chatId: String) -> Unit) {
        val s = _state.value
        s.createdChatId?.let { id -> if (s.online && !s.creating) uploadPhoto(id, onCreated); return }
        if (!s.canCreate) return
        val key = attemptKey ?: newIdempotencyKey().also { attemptKey = it }
        _state.update { it.copy(creating = true, stage = CreateStage.Group, error = null, failed = false, reference = null) }
        viewModelScope.launch {
            when (val r = createGroup(GroupDraft(s.trimmedName, s.selected.toList()), key)) {
                is AppResult.Success -> {
                    attemptKey = null
                    _state.update { it.copy(notAdded = r.value.notAdded) }
                    if (s.photo != null && setGroupPhoto != null) uploadPhoto(r.value.chat.chatId, onCreated)
                    else { _state.update { it.copy(creating = false, stage = null) }; onCreated(r.value.chat.chatId) }
                }
                // GW1d: the form is kept and the key too, so Try Again can't make a second group.
                is AppResult.Failure -> _state.update { it.copy(creating = false, stage = null, error = ErrorMessages.forError(r.error), failed = true, reference = ErrorMessages.reference(r.error)) }
            }
        }
    }

    /** The group exists and its photo isn't in: go on to the chat without it (it can be added from Group Info). */
    fun onSkipPhoto(onCreated: (chatId: String) -> Unit) {
        val id = _state.value.createdChatId ?: return
        if (_state.value.creating) return
        _state.update { it.copy(createdChatId = null, error = null, reference = null) }
        onCreated(id)
    }

    private fun uploadPhoto(chatId: String, onCreated: (chatId: String) -> Unit) {
        val photo = _state.value.photo
        val set = setGroupPhoto
        if (photo == null || set == null) { _state.update { it.copy(creating = false, stage = null, createdChatId = null) }; onCreated(chatId); return }
        _state.update { it.copy(creating = true, stage = CreateStage.Photo, error = null, reference = null) }
        viewModelScope.launch {
            when (val r = set(chatId, photo)) {
                is AppResult.Success -> { _state.update { it.copy(creating = false, stage = null, createdChatId = null) }; onCreated(chatId) }
                is AppResult.Failure -> _state.update {
                    it.copy(creating = false, stage = null, createdChatId = chatId, error = photoError(r.error), reference = ErrorMessages.reference(r.error))
                }
            }
        }
    }

    private fun photoError(error: AppError) = when (error) {
        AppError.Network -> "The group is made, but its photo didn't upload. You're offline; try again when you're back."
        // The server refused this photo: trying it again won't help, another one (or none) will.
        is AppError.Api if error.code == "messaging.avatar_invalid" -> "The group is made, but we couldn't use that photo. Try another one, or go on without it."
        else -> "The group is made, but its photo didn't upload. " + ErrorMessages.forError(error)
    }

    override fun onCleared() { photos?.closeCamera() }
}
