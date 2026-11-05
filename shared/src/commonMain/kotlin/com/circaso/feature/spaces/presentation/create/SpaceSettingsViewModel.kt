package com.circaso.feature.spaces.presentation.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ImagePicker
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.spaces.domain.PostingMode
import com.circaso.feature.spaces.domain.PricePeriod
import com.circaso.feature.spaces.domain.PrivateMessages
import com.circaso.feature.spaces.domain.Space
import com.circaso.feature.spaces.domain.SpaceChange
import com.circaso.feature.spaces.domain.SpacePrice
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.domain.SpaceTopic
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.domain.SpaceWalletStatus
import com.circaso.feature.spaces.domain.SpacesRepository
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.ObserveWalletOfUseCase
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.RefreshWalletOfUseCase
import com.circaso.feature.wallet.domain.Wallet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SpaceSettingsUiState(
    val space: Space? = null,
    val online: Boolean = true,
    /** SN8's counts: people waiting to be let in, open reports, the team. Null until read. */
    val waiting: Int? = null,
    val openReports: Int? = null,
    val admins: Int? = null,
    val moderators: Int? = null,
    /** The space wallet's last W1 (owner and admins). */
    val wallet: Wallet? = null,
    val busy: Boolean = false,
    /** A toast: a change saved, or why not (keyed on the code). */
    val message: String? = null,
    val deleted: Boolean = false,
    val failed: String? = null,
    val reference: String? = null,
) {
    val role: SpaceRole? get() = space?.me?.role
    val owner get() = role == SpaceRole.Owner
    val admin get() = role?.isAdmin == true
    val settingUp get() = space?.wallet?.status == SpaceWalletStatus.SettingUp || space?.wallet?.status == SpaceWalletStatus.None && space.state == com.circaso.feature.spaces.domain.SpaceState.SettingUp
}

/**
 * Space Settings (SN8): everything the owner changes later, in one list. Admins see the same without who can join,
 * private messages, the wallet's sending and Delete; moderators see Reports only. Every change is online-only and
 * disabled offline before the tap; the list reads from the phone.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpaceSettingsViewModel(
    private val spaceId: String,
    private val spaces: SpacesRepository,
    observeWalletOf: ObserveWalletOfUseCase,
    private val refreshWalletOf: RefreshWalletOfUseCase,
    connection: ConnectionState,
) : ViewModel() {
    private val _state = MutableStateFlow(SpaceSettingsUiState())
    val state: StateFlow<SpaceSettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { spaces.space(spaceId).collect { s -> _state.update { it.copy(space = s ?: it.space) } } }
        viewModelScope.launch { connection.status.collect { c -> _state.update { it.copy(online = c == ConnectionStatus.Online) } } }
        viewModelScope.launch {
            spaces.space(spaceId).map { it?.wallet?.walletId }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(null) else observeWalletOf(id) }
                .collect { w -> _state.update { it.copy(wallet = w) } }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val r = spaces.refreshSpace(spaceId)
            val space = (r as? AppResult.Success)?.value ?: _state.value.space
            if (r is AppResult.Failure && _state.value.space == null) _state.update { it.copy(failed = ErrorMessages.forError(r.error), reference = ErrorMessages.reference(r.error)) }
            else _state.update { it.copy(failed = null, reference = null) }
            space ?: return@launch
            if (space.me.isAdmin) {
                (spaces.spaceInvites(spaceId) as? AppResult.Success)?.let { inv -> _state.update { it.copy(waiting = inv.value.requests.size) } }
                (spaces.members(spaceId) as? AppResult.Success)?.let { m ->
                    _state.update { it.copy(admins = m.value.team.count { t -> t.role == SpaceRole.Admin }, moderators = m.value.team.count { t -> t.role == SpaceRole.Moderator }) }
                }
                space.wallet?.walletId?.let { refreshWalletOf(it) }
            }
            if (space.me.isModerator) (spaces.reports(spaceId, open = true) as? AppResult.Success)?.let { p -> _state.update { it.copy(openReports = p.value.items.size) } }
        }
    }

    /** Who can post (admins): Everyone or Admins only. */
    fun onPostingMode(mode: PostingMode) = change(SpaceChange(postingMode = mode)) { "Who can post: ${if (mode == PostingMode.Everyone) "everyone" else "admins only"}" }

    /** Private messages to the owner (owner only; an invite-only space is always members). */
    fun onPrivateMessages(pm: PrivateMessages) = change(SpaceChange(privateMessages = pm)) { "Private messages: ${if (pm == PrivateMessages.Anyone) "from anyone who can see the space" else "from members only"}" }

    private fun change(c: SpaceChange, done: () -> String) {
        if (_state.value.busy || !_state.value.online) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = spaces.update(spaceId, c)
            _state.update { it.copy(busy = false, message = if (r is AppResult.Success) done() else createErrorText((r as AppResult.Failure).error)) }
        }
    }

    /** SP6: the owner deletes the space. Its wallet must be empty first. */
    fun onDelete() {
        if (_state.value.busy || !_state.value.online) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = spaces.delete(spaceId)) {
                is AppResult.Success -> _state.update { it.copy(busy = false, deleted = true) }
                is AppResult.Failure -> _state.update { it.copy(busy = false, message = createErrorText(r.error)) }
            }
        }
    }

    fun onMessageShown() = _state.update { it.copy(message = null) }
}

/** What the edit screens change (SN8's Details and SN8b's Who Can Join). */
enum class SpaceEditMode { Details, Join }

data class SpaceEditUiState(
    val mode: SpaceEditMode = SpaceEditMode.Details,
    val space: Space? = null,
    /** The fields, filled from the space once it is read. */
    val loaded: Boolean = false,
    val photo: ByteArray? = null,
    /** Remove Photo was tapped: the space goes back to its initials (SP5 `removePhoto`). */
    val removePhoto: Boolean = false,
    val name: String = "",
    val handle: String = "",
    val handleState: HandleState = HandleState.Available,
    val about: String = "",
    val topic: SpaceTopic? = null,
    val visibility: SpaceVisibility = SpaceVisibility.Public,
    val priceText: String = "",
    val period: PricePeriod = PricePeriod.Monthly,
    val online: Boolean = true,
    val saving: Boolean = false,
    /** Saved: how many members SP5 told about the price (SN8b), or null when it was another change. */
    val saved: Boolean = false,
    val notified: Int? = null,
    val error: String? = null,
    val reference: String? = null,
    val fiat: FiatValue? = null,
) {
    val owner get() = space?.me?.isOwner == true
    val priceMinor: String? get() = MinorUnits.fromDisplay(priceText.trim(), 6)?.takeIf { !MinorUnits.isZero(it) }
    val priceProblem: String?
        get() {
            if (visibility != SpaceVisibility.MembersOnly) return null
            val m = priceMinor ?: return if (priceText.isBlank()) "Type a price in USDC." else "Type a price in USDC."
            return when {
                MinorUnits.lessThan(m, SpacePrice.MinMinor) -> "At least 0.10 USDC."
                MinorUnits.lessThan(SpacePrice.MaxMinor, m) -> "At most 10,000 USDC."
                else -> null
            }
        }
    /** The price or its period differs from today's (SN8b's note), on a space that is members-only already. */
    val priceChanged: Boolean
        get() {
            val old = space?.summary?.price ?: return false
            if (space.visibility != SpaceVisibility.MembersOnly || visibility != SpaceVisibility.MembersOnly) return false
            return priceMinor != null && (priceMinor != old.amountMinor || period != old.period)
        }
    /** Who is told today: the members (not counting the owner). */
    val membersToTell: Int get() = ((space?.summary?.memberCount ?: 1) - 1).coerceAtLeast(0)
    /** Becoming members-only keeps everyone in for free until they leave (contract SP5). */
    val becomingPaid: Boolean get() = space != null && space.visibility != SpaceVisibility.MembersOnly && visibility == SpaceVisibility.MembersOnly

    val change: SpaceChange
        get() {
            val s = space ?: return SpaceChange()
            return when (mode) {
                SpaceEditMode.Details -> SpaceChange(
                    name = name.trim().takeIf { it != s.name },
                    about = about.trim().takeIf { it != s.summary.about.orEmpty() },
                    topic = topic?.takeIf { it != s.summary.topic },
                    photo = photo,
                    removePhoto = removePhoto && photo == null && s.summary.photoUrl != null,
                    handle = handle.takeIf { owner && it != s.handle },
                )
                SpaceEditMode.Join -> {
                    val price = priceMinor?.takeIf { visibility == SpaceVisibility.MembersOnly }?.let { SpacePrice(it, period) }
                    SpaceChange(
                        visibility = visibility.takeIf { it != s.visibility },
                        price = price?.takeIf { it != s.summary.price || visibility != s.visibility },
                    )
                }
            }
        }
    val hasChanges: Boolean get() = change != SpaceChange()
    val canSave: Boolean get() = online && !saving && hasChanges && when (mode) {
        SpaceEditMode.Details -> name.trim().isNotEmpty() && about.length <= 300 && (handleState == HandleState.Available || !owner)
        SpaceEditMode.Join -> priceProblem == null
    }
}

/** Edit a space's details (SN8 → Details) or who can join and its price (SN8b). Online-only; the confirmation is inline. */
class SpaceEditViewModel(
    private val spaceId: String,
    mode: SpaceEditMode,
    private val spaces: SpacesRepository,
    observeWallet: ObserveWalletUseCase,
    connection: ConnectionState,
    private val picker: ImagePicker?,
    private val debounceMillis: Long = 400,
) : ViewModel() {
    private val _state = MutableStateFlow(SpaceEditUiState(mode = mode))
    val state: StateFlow<SpaceEditUiState> = _state.asStateFlow()
    private var checkJob: Job? = null

    init {
        viewModelScope.launch { connection.status.collect { c -> _state.update { it.copy(online = c == ConnectionStatus.Online) } } }
        viewModelScope.launch { observeWallet().collect { w -> _state.update { it.copy(fiat = w?.fiat) } } }
        viewModelScope.launch {
            spaces.space(spaceId).filterNotNull().collect { s ->
                _state.update { st ->
                    if (st.loaded) st.copy(space = s)
                    else st.copy(
                        space = s, loaded = true, name = s.name, handle = s.handle, about = s.summary.about.orEmpty(), topic = s.summary.topic,
                        visibility = s.visibility, priceText = s.summary.price?.let { MinorUnits.toDisplay(it.amountMinor, 6).replace(",", "") }.orEmpty(),
                        period = s.summary.price?.period ?: PricePeriod.Monthly,
                    )
                }
            }
        }
        viewModelScope.launch { if (spaces.space(spaceId).first() == null) spaces.refreshSpace(spaceId) }
    }

    fun onPickPhoto() { val p = picker ?: return; viewModelScope.launch { p.pick()?.let { img -> _state.update { it.copy(photo = img.bytes, removePhoto = false, saved = false) } } } }

    /** Remove Photo: a picked one is dropped; the space's own one is taken away on Save. */
    fun onRemovePhoto() = _state.update { if (it.photo != null) it.copy(photo = null) else it.copy(removePhoto = true, saved = false) }
    fun onName(text: String) = _state.update { it.copy(name = text.take(60), saved = false) }
    fun onAbout(text: String) = _state.update { it.copy(about = text.take(300), saved = false) }
    fun onTopic(t: SpaceTopic) = _state.update { it.copy(topic = t, saved = false) }
    fun onVisibility(v: SpaceVisibility) = _state.update { it.copy(visibility = v, saved = false) }
    fun onPeriod(p: PricePeriod) = _state.update { it.copy(period = p, saved = false) }
    fun onPrice(text: String) {
        val clean = text.filter { it.isDigit() || it == '.' }
        if (clean.count { it == '.' } > 1 || clean.substringAfter('.', "").length > 2) return
        _state.update { it.copy(priceText = clean, saved = false) }
    }

    /** The owner may change the handle (it is the link): checked live like SN1, unchanged counts as fine. */
    fun onHandle(text: String) {
        checkJob?.cancel()
        val h = cleanHandle(text)
        val same = h == _state.value.space?.handle
        val local = handleProblem(h)
        val next = when { same -> HandleState.Available; h.isEmpty() -> HandleState.Empty; local != null -> HandleState.Invalid(local); else -> HandleState.Checking }
        _state.update { it.copy(handle = h, handleState = next, saved = false) }
        if (next != HandleState.Checking) return
        checkJob = viewModelScope.launch {
            delay(debounceMillis)
            val r = spaces.checkHandle(h)
            if (_state.value.handle != h) return@launch
            _state.update {
                it.copy(handleState = when (r) {
                    is AppResult.Success -> if (r.value.available) HandleState.Available else HandleState.Taken(r.value.suggestions.take(3))
                    is AppResult.Failure -> HandleState.Unknown
                })
            }
        }
    }

    fun onSave() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true, error = null, reference = null) }
        viewModelScope.launch {
            when (val r = spaces.update(spaceId, s.change)) {
                is AppResult.Success -> _state.update {
                    it.copy(saving = false, saved = true, photo = null, removePhoto = false, notified = r.value.notified.takeIf { _ -> s.change.price != null && s.space?.visibility == SpaceVisibility.MembersOnly })
                }
                is AppResult.Failure -> {
                    val code = (r.error as? AppError.Api)?.code
                    _state.update {
                        it.copy(
                            saving = false, error = createErrorText(r.error), reference = ErrorMessages.reference(r.error),
                            handleState = if (code == "spaces.handle_taken") HandleState.Taken((r.error as AppError.Api).fields["suggestions"]?.split(',')?.map { h -> h.trim() }.orEmpty().take(3)) else it.handleState,
                        )
                    }
                }
            }
        }
    }
}
