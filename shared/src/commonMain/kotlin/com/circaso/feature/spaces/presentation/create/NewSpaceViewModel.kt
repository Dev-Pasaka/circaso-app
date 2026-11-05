package com.circaso.feature.spaces.presentation.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ImagePicker
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.spaces.domain.NewSpace
import com.circaso.feature.spaces.domain.PostingMode
import com.circaso.feature.spaces.domain.PricePeriod
import com.circaso.feature.spaces.domain.SpacePerson
import com.circaso.feature.spaces.domain.SpacePrice
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.domain.SpaceTopic
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.domain.SpacesRepository
import com.circaso.feature.spaces.domain.TeamPick
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** SN1's live handle check. */
sealed interface HandleState {
    data object Empty : HandleState
    /** Breaks the local rule (too short, a bad character): said at once, nothing asked. */
    data class Invalid(val reason: String) : HandleState
    data object Checking : HandleState
    data object Available : HandleState
    data class Taken(val suggestions: List<String>) : HandleState
    /** The check couldn't reach the server (offline): the create call checks again. */
    data object Unknown : HandleState
}

/** Why creating stopped (SN7b, SN7c), keyed on the server's code. */
sealed interface CreateProblem {
    /** SN7c: the spam rule; [availableAt] is when this account may start one. */
    data class NotYet(val availableAt: Long?) : CreateProblem
    /** SN7b: refused or failed; nothing was made. */
    data class Failed(val message: String, val reference: String?) : CreateProblem
}

data class NewSpaceUiState(
    /** 1 details, 2 who can join, 3 chat and roles (SN1–SN3). */
    val step: Int = 1,
    val photo: ByteArray? = null,
    val name: String = "",
    val handle: String = "",
    val handleState: HandleState = HandleState.Empty,
    /** The person has edited the handle themselves, so the name no longer fills it in. */
    val handleEdited: Boolean = false,
    val about: String = "",
    val topic: SpaceTopic? = null,
    val visibility: SpaceVisibility = SpaceVisibility.Public,
    val priceText: String = "",
    val period: PricePeriod = PricePeriod.Monthly,
    val postingMode: PostingMode = PostingMode.Everyone,
    val team: List<TeamPick> = emptyList(),
    /** SN3's Add People: the contacts picker is on screen (a page of the flow, not a sheet). */
    val picking: Boolean = false,
    val contacts: List<SpacePerson> = emptyList(),
    val query: String = "",
    val online: Boolean = true,
    val creating: Boolean = false,
    val problem: CreateProblem? = null,
    /** The person's currency and rate (the personal wallet's), for "About KES 1,292 today". */
    val fiat: FiatValue? = null,
) {
    val priceMinor: String? get() = MinorUnits.fromDisplay(priceText.trim(), 6)?.takeIf { !MinorUnits.isZero(it) }
    val priceProblem: String?
        get() {
            if (visibility != SpaceVisibility.MembersOnly) return null
            val m = priceMinor ?: return if (priceText.isBlank()) null else "Type a price in USDC."
            return when {
                MinorUnits.lessThan(m, SpacePrice.MinMinor) -> "At least 0.10 USDC."
                MinorUnits.lessThan(SpacePrice.MaxMinor, m) -> "At most 10,000 USDC."
                else -> null
            }
        }
    val joinReady get() = visibility != SpaceVisibility.MembersOnly || (priceMinor != null && priceProblem == null)
    /** Next (or Create) is offered: online, and this step is complete. Offline it is disabled before the tap (SN7). */
    val canContinue: Boolean get() = online && !creating && when (step) {
        1 -> name.trim().isNotEmpty() && name.trim().length <= 60 && topic != null && about.length <= 300 &&
            (handleState == HandleState.Available || handleState == HandleState.Unknown)
        2 -> joinReady
        else -> true
    }
    val filteredContacts get() = contacts.filter { query.isBlank() || it.displayName.contains(query, true) || it.handle?.contains(query.trimStart('@'), true) == true }
}

/**
 * Create a space (SN1–SN3, SN7): three short steps, then SP2. Online-only and never queued: the server checks the
 * handle and makes the space. One `Idempotency-Key` per draft, so Try Again after a lost answer returns the space the
 * first attempt made (SN7b); a refusal the person fixes (a taken handle) gets a fresh key, since the gateway would
 * otherwise replay the refusal.
 */
class NewSpaceViewModel(
    initialName: String?,
    private val spaces: SpacesRepository,
    observeContacts: ObserveContactsUseCase,
    observeWallet: ObserveWalletUseCase,
    connection: ConnectionState,
    private val picker: ImagePicker?,
    /** The handle check waits this long after the last keystroke. */
    private val debounceMillis: Long = 400,
) : ViewModel() {
    private val _state = MutableStateFlow(NewSpaceUiState(name = initialName.orEmpty().take(60)))
    val state: StateFlow<NewSpaceUiState> = _state.asStateFlow()

    private val _created = MutableStateFlow<String?>(null)
    /** The new space's id once SP2 answered: the screen moves on to the wallet's PIN (SN4). */
    val created: StateFlow<String?> = _created.asStateFlow()

    private var key = newIdempotencyKey()
    private var checkJob: Job? = null

    init {
        viewModelScope.launch { connection.status.collect { s -> _state.update { it.copy(online = s == ConnectionStatus.Online) } } }
        viewModelScope.launch { observeContacts().collect { list -> _state.update { it.copy(contacts = list.map { c -> SpacePerson(c.person.userId, c.person.displayName, c.person.handle, c.person.avatarUrl) }) } } }
        viewModelScope.launch { observeWallet().collect { w -> _state.update { it.copy(fiat = w?.fiat) } } }
        if (!initialName.isNullOrBlank()) suggestHandle(initialName)
    }

    // ---- step 1

    fun onPickPhoto() {
        val p = picker ?: return
        viewModelScope.launch { p.pick()?.let { img -> edited { it.copy(photo = img.bytes) } } }
    }

    fun onRemovePhoto() = edited { it.copy(photo = null) }

    fun onName(text: String) {
        edited { it.copy(name = text.take(60)) }
        // Until the person types a handle themselves, the name suggests one (SD2c "Create “Mitumba Thika”").
        if (!_state.value.handleEdited) suggestHandle(text)
    }

    private fun suggestHandle(name: String) {
        val h = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(30)
        setHandle(h, edited = false)
    }

    fun onHandle(text: String) = setHandle(cleanHandle(text), edited = true)

    private fun setHandle(h: String, edited: Boolean) {
        checkJob?.cancel()
        val local = handleProblem(h)
        val next = when {
            h.isEmpty() -> HandleState.Empty
            local != null -> HandleState.Invalid(local)
            else -> HandleState.Checking
        }
        edited { it.copy(handle = h, handleState = next, handleEdited = it.handleEdited || edited) }
        if (next != HandleState.Checking) return
        checkJob = viewModelScope.launch {
            delay(debounceMillis)
            val r = spaces.checkHandle(h)
            if (_state.value.handle != h) return@launch
            val result = when (r) {
                is AppResult.Success -> if (r.value.available) HandleState.Available else HandleState.Taken(r.value.suggestions.take(3))
                is AppResult.Failure -> when ((r.error as? AppError.Api)?.code) {
                    "spaces.handle_invalid" -> HandleState.Invalid("Letters, numbers, dots and underscores only, starting with a letter.")
                    else -> HandleState.Unknown
                }
            }
            _state.update { it.copy(handleState = result) }
        }
    }

    fun onSuggestion(handle: String) = setHandle(cleanHandle(handle), edited = true)

    fun onAbout(text: String) = edited { it.copy(about = text.take(300)) }
    fun onTopic(topic: SpaceTopic) = edited { it.copy(topic = topic) }

    // ---- step 2

    fun onVisibility(v: SpaceVisibility) = edited { it.copy(visibility = v) }
    fun onPrice(text: String) {
        val clean = text.filter { it.isDigit() || it == '.' }
        if (clean.count { it == '.' } > 1 || clean.substringAfter('.', "").length > 2) return
        edited { it.copy(priceText = clean) }
    }
    fun onPeriod(p: PricePeriod) = edited { it.copy(period = p) }

    // ---- step 3

    fun onPostingMode(m: PostingMode) = edited { it.copy(postingMode = m) }
    fun onPicking(open: Boolean) = _state.update { it.copy(picking = open, query = "") }
    fun onQuery(q: String) = _state.update { it.copy(query = q) }

    /** Picked from contacts: a moderator to begin with (the least power); the chip changes it. At most 20. */
    fun onToggle(person: SpacePerson) = edited { s ->
        if (s.team.any { it.person.userId == person.userId }) s.copy(team = s.team.filterNot { it.person.userId == person.userId })
        else if (s.team.size >= 20) s else s.copy(team = s.team + TeamPick(person, SpaceRole.Moderator))
    }

    fun onRole(userId: String, role: SpaceRole?) = edited { s ->
        if (role == null) s.copy(team = s.team.filterNot { it.person.userId == userId })
        else s.copy(team = s.team.map { if (it.person.userId == userId) it.copy(role = role) else it })
    }

    // ---- moving through the steps

    /** Back from a step: the one before, or false to leave (Cancel on the first). */
    fun onBack(): Boolean {
        val s = _state.value
        if (s.picking) { onPicking(false); return true }
        if (s.step > 1 && s.problem !is CreateProblem.NotYet) { _state.update { it.copy(step = it.step - 1, problem = null) }; return true }
        return false
    }

    fun onNext() {
        val s = _state.value
        if (!s.canContinue) return
        if (s.step < 3) { _state.update { it.copy(step = it.step + 1, problem = null) }; return }
        create()
    }

    /** SN7b's Try Again: the same request with the same key. */
    fun onTryAgain() { if (_state.value.online) create() }

    private fun create() {
        val s = _state.value
        if (s.creating) return
        val topic = s.topic ?: return
        _state.update { it.copy(creating = true, problem = null) }
        viewModelScope.launch {
            val draft = NewSpace(
                name = s.name.trim(), handle = s.handle, about = s.about.trim().takeIf { it.isNotEmpty() }, topic = topic, photo = s.photo,
                visibility = s.visibility, price = s.priceMinor?.takeIf { s.visibility == SpaceVisibility.MembersOnly }?.let { SpacePrice(it, s.period) },
                postingMode = s.postingMode, team = s.team,
            )
            when (val r = spaces.create(draft, key)) {
                is AppResult.Success -> { _state.update { it.copy(creating = false) }; _created.value = r.value.spaceId }
                is AppResult.Failure -> onCreateFailed(r.error)
            }
        }
    }

    private fun onCreateFailed(error: AppError) {
        val api = error as? AppError.Api
        // A refusal the server will repeat for this body: the person changes something, and that is a new request.
        if (api != null && api.httpStatus in 400..499 && api.code != "request.in_progress") key = newIdempotencyKey()
        when (api?.code) {
            "spaces.account_too_new" -> _state.update {
                it.copy(creating = false, problem = CreateProblem.NotYet(api.fields["availableAt"]?.let { iso -> runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrNull() }))
            }
            "spaces.handle_taken" -> _state.update {
                val suggestions = api.fields["suggestions"]?.split(',')?.map { h -> h.trim() }?.filter { h -> h.isNotEmpty() }.orEmpty().take(3)
                it.copy(creating = false, step = 1, handleState = HandleState.Taken(suggestions))
            }
            "spaces.handle_invalid" -> _state.update { it.copy(creating = false, step = 1, handleState = HandleState.Invalid("Letters, numbers, dots and underscores only, starting with a letter.")) }
            "spaces.price_invalid" -> _state.update { it.copy(creating = false, step = 2, problem = CreateProblem.Failed(createErrorText(error), ErrorMessages.reference(error))) }
            "spaces.details_invalid", "spaces.media_not_found", "spaces.media_invalid" -> _state.update {
                it.copy(creating = false, step = 1, problem = CreateProblem.Failed(createErrorText(error), ErrorMessages.reference(error)))
            }
            else -> _state.update { it.copy(creating = false, problem = CreateProblem.Failed(if (error == AppError.Network) "The space wasn’t created. Nothing was saved and no wallet was made." else createErrorText(error), ErrorMessages.reference(error))) }
        }
    }

    fun onCreatedHandled() { _created.value = null }

    /** Any change to the draft after a refusal is a new request: a fresh key (a lost answer keeps the old one). */
    private fun edited(change: (NewSpaceUiState) -> NewSpaceUiState) {
        _state.update { s ->
            val next = change(s)
            if (s.problem != null && next != s) next.copy(problem = (s.problem as? CreateProblem.NotYet)) else next
        }
    }
}
