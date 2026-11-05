package com.circaso.feature.spaces.presentation.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.core.sync.nowMillis
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.spaces.domain.MembersPageResult
import com.circaso.feature.spaces.domain.MembershipPayment
import com.circaso.feature.spaces.domain.MembershipPaymentKind
import com.circaso.feature.spaces.domain.MembershipState
import com.circaso.feature.spaces.domain.NotifyLevel
import com.circaso.feature.spaces.domain.Relation
import com.circaso.feature.spaces.domain.Space
import com.circaso.feature.spaces.domain.SpaceEvent
import com.circaso.feature.spaces.domain.SpacePost
import com.circaso.feature.spaces.domain.SpaceState
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.domain.SpacesRepository
import com.circaso.feature.spaces.presentation.shortDate
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.Wallet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SpaceTab(val label: String) { Chat("Chat"), About("About"), Members("Members") }

/** The online-only action in progress (each button shows its own progress; nothing is optimistic). */
enum class PageBusy { Follow, Unfollow, Request, CancelRequest, Accept, Notify, Message, Quote, Leave }

/** Why the page can't show the space (SS6c, SS6d). */
sealed interface PageProblem {
    /** SS6c: failed and nothing on the phone. */
    data class Failed(val message: String, val reference: String?) : PageProblem
    /** SS6d: deleted by its owner, or removed by Circaso; [name] when the server says it. */
    data class Unavailable(val removed: Boolean, val name: String?) : PageProblem
    /** No such space, or not one I may see (a wrong link). */
    data object NotFound : PageProblem
}

/** The join card (SM1–SM6) as the page drives it; the money itself is `PayViewModel`'s. */
sealed interface JoinStep {
    data object Closed : JoinStep
    /** SP13 is being asked for the price held 15 minutes. */
    data object Quoting : JoinStep
    /** The quote is in: the card is the review, Pay goes straight to the PIN. */
    data class Quoted(val payment: MembershipPayment) : JoinStep
    /** SP13 refused or failed (the card shows Not sent with the reference; Try Again asks again). */
    data class QuoteFailed(val message: String, val reference: String?) : JoinStep
    /** Offline: the card still opens with the space's price so it is clear, Pay off (SM1f). */
    data object Offline : JoinStep
    /** Signed; membership only once the transfer is confirmed (SM1c), never from the tap. */
    data class Joining(val payment: MembershipPayment, val transferId: String) : JoinStep
}

data class SpacePageUiState(
    val spaceId: String? = null,
    val space: Space? = null,
    /** Nothing on the phone yet and the first read is running (SS6 skeleton). */
    val loading: Boolean = true,
    val problem: PageProblem? = null,
    val online: Boolean = true,
    val posts: List<SpacePost> = emptyList(),
    val tab: SpaceTab = SpaceTab.Chat,
    /** The Following / Member popover (SS1c, SM6) and its Leave confirm. */
    val menuOpen: Boolean = false,
    val leaveConfirm: Boolean = false,
    val busy: PageBusy? = null,
    /** An action's refusal, in plain words, under the buttons. */
    val actionError: String? = null,
    /** After Unfollow: a few seconds of Undo (SS1c). */
    val undoUnfollow: Boolean = false,
    /** SI3b on the preview: declined, Undo for a few seconds before it is sent. */
    val declining: Boolean = false,
    val members: MembersPageResult? = null,
    val membersFailed: String? = null,
    val membersLocked: Boolean = false,
    val draft: String = "",
    val join: JoinStep = JoinStep.Closed,
    /** SM1d / SI3: a system line seen only by me ("You joined · member until 4 Dec"). */
    val welcome: String? = null,
    /** The personal wallet's rate and balance, for prices in the payer's currency. */
    val fiat: FiatValue? = null,
    val balanceMinor: String? = null,
    val now: Long = 0,
    val qrOpen: Boolean = false,
    val moreOpen: Boolean = false,
    val notifyOpen: Boolean = false,
) {
    val me get() = space?.me
    val isMember get() = space?.me?.isMember == true
    /** The members-only membership has ended: the chat is locked until paid again (SM5). */
    val ended get() = space?.me?.relation == Relation.Ended || space?.me?.membership?.state == MembershipState.Ended
    val canSeeChat get() = space?.let { s -> (isMember && !ended) || (s.visibility == SpaceVisibility.Public && s.me.relation != Relation.Banned) } == true
    val showTabs get() = space?.let { s -> isMember || s.visibility == SpaceVisibility.Public } == true && !ended && space?.me?.relation != Relation.Banned
    val canPost get() = space?.canPost(now) == true
    val joining get() = join is JoinStep.Joining
}

/**
 * The space page (SS1–SS6e), joining (SM1–SM6) and the invited preview (SI2b). The space comes from Room
 * ([SpacesRepository.space]) so a membership frame changes the page live; every action is online-only and waits for the
 * server's answer. The money is `PayViewModel`'s (the screen owns it); this keeps the SP13 quote and says Joining until
 * the confirmed membership arrives.
 */
class SpacePageViewModel(
    private val initialSpaceId: String?,
    private val handle: String?,
    private val openJoin: Boolean,
    private val spaces: SpacesRepository,
    private val chats: ChatsRepository,
    connection: ConnectionState,
    private val wallet: Flow<Wallet?> = flowOf(null),
    private val now: () -> Long = ::nowMillis,
    /** How often a confirmation is looked for over HTTP while joining, in case the socket is down. */
    private val joinPollMs: Long = 8_000,
) : ViewModel() {
    private val _state = MutableStateFlow(SpacePageUiState(spaceId = initialSpaceId, now = now()))
    val state: StateFlow<SpacePageUiState> = _state.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toasts: SharedFlow<String> = _toasts

    private val _openChat = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** The private chat with the owner to open (SH2). */
    val openChat: SharedFlow<String> = _openChat

    private var undoJob: Job? = null
    private var declineJob: Job? = null
    private var pollJob: Job? = null
    private var observed = false
    /** What membership looked like when the PIN was done, to tell the confirmed one from the old one. */
    private var paidUntilBefore: Long? = null
    private var memberBefore = false

    init {
        viewModelScope.launch {
            connection.status.collect { s ->
                val back = s == ConnectionStatus.Online && !_state.value.online
                _state.update { it.copy(online = s == ConnectionStatus.Online, join = if (s != ConnectionStatus.Online && it.join is JoinStep.Quoted) JoinStep.Offline else it.join) }
                if (back) _state.value.spaceId?.let { refresh(it) }
            }
        }
        viewModelScope.launch {
            wallet.collect { w -> _state.update { it.copy(fiat = w?.fiat, balanceMinor = w?.usdc?.amountMinor) } }
        }
        viewModelScope.launch {
            spaces.events.collect { e ->
                if (e is SpaceEvent.Membership && e.spaceId == _state.value.spaceId) _state.value.space?.let { checkJoined(it) }
            }
        }
        viewModelScope.launch {
            val id = initialSpaceId ?: resolveHandle() ?: return@launch
            start(id)
        }
    }

    private suspend fun resolveHandle(): String? {
        val h = handle ?: run { _state.update { it.copy(loading = false, problem = PageProblem.NotFound) }; return null }
        return when (val r = spaces.spaceByHandle(h)) {
            is AppResult.Success -> r.value.spaceId.also { id -> _state.update { it.copy(spaceId = id) } }
            is AppResult.Failure -> { failed(r.error, nothingShown = true); null }
        }
    }

    private fun start(id: String) {
        if (observed) return
        observed = true
        viewModelScope.launch {
            spaces.space(id).collect { s ->
                if (s == null) return@collect
                _state.update { it.copy(space = s, loading = false, problem = null, now = now()) }
                checkJoined(s)
            }
        }
        viewModelScope.launch { spaces.posts(id).collect { p -> _state.update { it.copy(posts = p) } } }
        viewModelScope.launch {
            refresh(id)
            if (openJoin) onJoin()
        }
    }

    private suspend fun refresh(id: String) {
        when (val r = spaces.refreshSpace(id)) {
            is AppResult.Success -> {
                val s = r.value
                if (s.me.isMember || s.visibility == SpaceVisibility.Public) spaces.refreshPosts(id)
            }
            is AppResult.Failure -> failed(r.error, nothingShown = _state.value.space == null)
        }
    }

    private fun failed(error: AppError, nothingShown: Boolean) {
        val api = error as? AppError.Api
        when (api?.code) {
            "spaces.space_unavailable" -> _state.update {
                it.copy(loading = false, problem = PageProblem.Unavailable(api.fields["reason"] == "REMOVED", api.fields["name"] ?: it.space?.name), space = null)
            }
            "spaces.space_not_found" -> _state.update { it.copy(loading = false, problem = PageProblem.NotFound, space = null) }
            else -> _state.update {
                when {
                    // A failure with the space on the phone: it stays, as of when it was read (SS6b).
                    !nothingShown || it.space != null -> it.copy(loading = false)
                    error is AppError.Network -> it.copy(loading = false, problem = PageProblem.Failed("You’re offline, and this space isn’t on your phone yet.", null))
                    else -> it.copy(loading = false, problem = PageProblem.Failed(ErrorMessages.forError(error), ErrorMessages.reference(error)))
                }
            }
        }
    }

    fun onRetry() {
        _state.update { it.copy(loading = true, problem = null) }
        viewModelScope.launch {
            val id = _state.value.spaceId ?: resolveHandle() ?: return@launch
            if (!observed) start(id) else refresh(id)
        }
    }

    fun onTab(tab: SpaceTab) {
        _state.update { it.copy(tab = tab) }
        if (tab == SpaceTab.Members && _state.value.members == null) loadMembers()
    }

    private fun loadMembers() {
        val id = _state.value.spaceId ?: return
        viewModelScope.launch {
            when (val r = spaces.members(id)) {
                is AppResult.Success -> _state.update { it.copy(members = r.value, membersFailed = null, membersLocked = false) }
                is AppResult.Failure -> {
                    val code = (r.error as? AppError.Api)?.code
                    if (code == "spaces.members_only" || code == "spaces.membership_ended") _state.update { it.copy(membersLocked = true) }
                    else _state.update { it.copy(membersFailed = ErrorMessages.forError(r.error, spaceCopy)) }
                }
            }
        }
    }

    fun onRetryMembers() = loadMembers()

    // ---------------------------------------------------------------- joining without money

    fun onFollow() = act(PageBusy.Follow) { id -> spaces.follow(id).also { r -> if (r is AppResult.Success) toast("You follow ${name()}") } }

    fun onMenu(open: Boolean) = _state.update { it.copy(menuOpen = open, leaveConfirm = false, actionError = null) }
    fun onMore(open: Boolean) = _state.update { it.copy(moreOpen = open) }
    fun onQr(open: Boolean) = _state.update { it.copy(qrOpen = open, moreOpen = false) }
    fun onNotifyMenu(open: Boolean) = _state.update { it.copy(notifyOpen = open) }

    /**
     * Unfollow a free public space: at once, with Undo for a few seconds (SS1c). A paid membership and an invite-only
     * space confirm first instead (SM6; leaving an invite-only space needs a new invite to come back).
     */
    fun onUnfollow() {
        val s = _state.value.space ?: return
        if (s.visibility != SpaceVisibility.Public || s.me.membership != null) { _state.update { it.copy(leaveConfirm = true) }; return }
        act(PageBusy.Unfollow) { id ->
            spaces.leave(id).also { r ->
                if (r is AppResult.Success) {
                    _state.update { it.copy(menuOpen = false, undoUnfollow = true) }
                    undoJob?.cancel()
                    undoJob = viewModelScope.launch { delay(UndoMs); _state.update { it.copy(undoUnfollow = false) } }
                }
            }
        }
    }

    fun onUndoUnfollow() {
        undoJob?.cancel()
        _state.update { it.copy(undoUnfollow = false) }
        act(PageBusy.Follow) { id -> spaces.follow(id) }
    }

    fun onLeaveCancel() = _state.update { it.copy(leaveConfirm = false, menuOpen = false) }

    fun onLeave() = act(PageBusy.Leave) { id ->
        spaces.leave(id).also { r -> if (r is AppResult.Success) { _state.update { it.copy(menuOpen = false, leaveConfirm = false) }; toast("You left ${name()}") } }
    }

    fun onNotifications(level: NotifyLevel) {
        _state.update { it.copy(menuOpen = false, notifyOpen = false) }
        act(PageBusy.Notify) { id -> spaces.setNotifications(id, level) }
    }

    fun onRequest() = act(PageBusy.Request) { id -> spaces.request(id) }
    fun onCancelRequest() = act(PageBusy.CancelRequest) { id -> spaces.cancelRequest(id) }

    fun onAcceptInvite() {
        val invite = _state.value.space?.me?.invite ?: return
        act(PageBusy.Accept) { _ ->
            spaces.acceptInvite(invite.inviteId).also { r ->
                if (r is AppResult.Success) {
                    _state.update { it.copy(welcome = "You joined · ${invite.invitedBy.firstName} invited you") }
                    toast("You joined ${name()}")
                }
            }
        }
    }

    /** SI3b: the decline waits a few seconds with Undo, then goes; nobody is told. */
    fun onDeclineInvite(onGone: () -> Unit) {
        val invite = _state.value.space?.me?.invite ?: return
        _state.update { it.copy(declining = true) }
        declineJob?.cancel()
        declineJob = viewModelScope.launch {
            delay(UndoMs)
            spaces.declineInvite(invite.inviteId)
            _state.update { it.copy(declining = false) }
            onGone()
        }
    }

    fun onUndoDecline() { declineJob?.cancel(); _state.update { it.copy(declining = false) } }

    /** SH2: the private chat with the owner (end-to-end encrypted, its own chat apart from any ordinary one). */
    fun onMessage() {
        val s = _state.value.space ?: return
        val owner = s.owner?.userId ?: return
        act(PageBusy.Message) { id -> chats.openViaSpace(owner, id).also { r -> if (r is AppResult.Success) _openChat.tryEmit(r.value.chatId) } }
    }

    fun onDraft(text: String) = _state.update { it.copy(draft = text.take(4000)) }

    fun onSend() {
        val id = _state.value.spaceId ?: return
        val text = _state.value.draft.trim()
        if (text.isEmpty() || !_state.value.canPost) return
        _state.update { it.copy(draft = "") }
        viewModelScope.launch { spaces.sendPost(id, text) }
    }

    fun onReact(post: SpacePost, emoji: String?) {
        val id = _state.value.spaceId ?: return
        if (!_state.value.isMember) return
        viewModelScope.launch { spaces.react(id, post.localKey, emoji) }
    }

    fun onRetryPost(post: SpacePost) { viewModelScope.launch { spaces.retryPost(post.localKey) } }

    /** Older posts on reaching the top (members; a visitor of a public space reads the latest 20 only). */
    fun onLoadOlder() {
        val id = _state.value.spaceId ?: return
        if (!_state.value.isMember) return
        viewModelScope.launch { spaces.loadOlder(id) }
    }

    // ---------------------------------------------------------------- paid entry (SM)

    /** Join, Renew or Rejoin: the card opens with a fresh SP13 quote; offline it opens with the price, Pay off. */
    fun onJoin() {
        if (_state.value.joining) return
        if (!_state.value.online) { _state.update { it.copy(join = JoinStep.Offline) }; return }
        val id = _state.value.spaceId ?: return
        _state.update { it.copy(join = JoinStep.Quoting, actionError = null) }
        viewModelScope.launch {
            when (val r = spaces.membershipPayment(id)) {
                is AppResult.Success -> _state.update { it.copy(join = JoinStep.Quoted(r.value)) }
                is AppResult.Failure -> {
                    val api = r.error as? AppError.Api
                    if (api?.code == "spaces.already_member") {
                        // Already paid for this period (or a one-time membership): nothing to pay; say until when.
                        val from = api.fields["renewFrom"]?.let { com.circaso.feature.spaces.data.repository.iso(it) }
                        _state.update { it.copy(join = JoinStep.Closed, actionError = from?.let { f -> "You’re a member. Renew opens on ${shortDate(f)}." } ?: "You’re already a member.") }
                        refresh(id)
                    } else _state.update { it.copy(join = JoinStep.QuoteFailed(ErrorMessages.forError(r.error, spaceCopy), ErrorMessages.reference(r.error))) }
                }
            }
        }
    }

    fun onCloseJoin() { if (!_state.value.joining) _state.update { it.copy(join = JoinStep.Closed) } }

    /**
     * The PIN is done and W4 has the transfer: keep it for the receipt (SM2) and wait for the server's confirmed
     * membership (SM1c). Nothing says member until [Space.me] does.
     */
    fun onMembershipSigned(transfer: Transfer) {
        val step = _state.value.join as? JoinStep.Quoted ?: return
        val s = _state.value.space
        memberBefore = s?.me?.isMember == true && s.me.membership?.state != MembershipState.Ended
        paidUntilBefore = s?.me?.membership?.paidUntil
        _state.update { it.copy(join = JoinStep.Joining(step.payment, transfer.transferId)) }
        viewModelScope.launch {
            val note = "Membership · ${step.payment.period.label.lowercase()}"
            spaces.rememberMembershipPayment(step.payment.spaceId, transfer.transferId, transfer.amount.amountMinor, transfer.blockchain, note)
        }
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (_state.value.joining) {
                delay(joinPollMs)
                _state.value.spaceId?.let { spaces.refreshSpace(it) }
            }
        }
    }

    private fun checkJoined(s: Space) {
        val step = _state.value.join as? JoinStep.Joining ?: return
        val m = s.me.membership
        val confirmed = s.me.isMember && m != null && m.state != MembershipState.Ended &&
            (!memberBefore || (m.paidUntil != null && (paidUntilBefore == null || m.paidUntil > paidUntilBefore!!)) || m.period == com.circaso.feature.spaces.domain.PricePeriod.OneTime)
        if (!confirmed) return
        pollJob?.cancel()
        val until = m?.paidUntil?.let { " · member until ${shortDate(it)}" }.orEmpty()
        val renewal = step.payment.kind == MembershipPaymentKind.Renewal
        _state.update { it.copy(join = JoinStep.Closed, welcome = (if (renewal) "You renewed" else "You joined") + until) }
        toast(if (renewal) "Renewed ${s.name}" else "Welcome to ${s.name}")
    }

    // ---------------------------------------------------------------- helpers

    private fun name() = _state.value.space?.name ?: "the space"
    private fun toast(text: String) { _toasts.tryEmit(text) }

    /** One online-only action: refused offline before it starts, its own progress, the server's answer on the page. */
    private fun <T> act(busy: PageBusy, block: suspend (spaceId: String) -> AppResult<T>) {
        val id = _state.value.spaceId ?: return
        if (!_state.value.online || _state.value.busy != null) return
        _state.update { it.copy(busy = busy, actionError = null) }
        viewModelScope.launch {
            val r = block(id)
            _state.update { it.copy(busy = null, actionError = (r as? AppResult.Failure)?.let { f -> ErrorMessages.forError(f.error, spaceCopy) }) }
            if (r is AppResult.Failure && (r.error as? AppError.Api)?.code in setOf("spaces.banned", "spaces.space_unavailable", "spaces.space_not_found")) refresh(id)
        }
    }

    override fun onCleared() { pollJob?.cancel(); super.onCleared() }

    companion object {
        const val UndoMs = 4_000L
    }
}

/** What the page says for each `spaces.*` refusal (keyed on the code, never the message). */
internal val spaceCopy = mapOf(
    "spaces.banned" to "An admin banned you from this space.",
    "spaces.payment_required" to "Joining this space is paid. Use Join.",
    "spaces.invite_required" to "This space is invite only. Ask to join instead.",
    "spaces.account_restricted" to "Your account can’t take part in spaces right now.",
    "spaces.owner_cannot_leave" to "You own this space, so you can’t leave it. Delete it in Space Settings instead.",
    "spaces.not_member" to "You’re not in this space any more.",
    "spaces.request_too_soon" to "You asked recently. You can ask again 30 days after a decline.",
    "spaces.already_member" to "You’re already a member.",
    "spaces.not_invite_only" to "Anyone can follow this space.",
    "spaces.invite_not_found" to "This invite has expired or was cancelled.",
    "spaces.wallet_not_ready" to "This space can’t take payments yet. Try again later.",
    "spaces.not_paid_space" to "This space is free to join.",
    "spaces.members_only" to "Only members can see this.",
    "spaces.membership_ended" to "Your membership has ended.",
    "spaces.unavailable" to "Spaces aren’t available yet.",
    "messaging.not_reachable" to "You can’t message this space’s owner.",
)
