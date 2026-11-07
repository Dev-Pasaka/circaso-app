package com.circaso.feature.messaging.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ChatNotifications
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.core.sync.nowMillis
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.DeleteForEveryoneUseCase
import com.circaso.feature.messaging.domain.DiscardMessageUseCase
import com.circaso.feature.messaging.domain.MarkReadUseCase
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.messaging.domain.ObserveChatUseCase
import com.circaso.feature.messaging.domain.ObserveThreadUseCase
import com.circaso.feature.messaging.domain.ObserveTypingUseCase
import com.circaso.feature.messaging.domain.RetryMessageUseCase
import com.circaso.feature.messaging.domain.SendMessageUseCase
import com.circaso.feature.messaging.domain.SetTypingUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.circaso.feature.chama.domain.Chama
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.presentation.ThreadChama
import com.circaso.feature.wallet.domain.FiatValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Designs C2 (direct) and D2 (group), with the offline states of G2/G3. */
data class ThreadUiState(
    val chat: Chat? = null,
    /** A direct chat with someone I haven't saved: offer to save them (needed both ways for moments, Pasaka 2026-11-03). */
    val peerUnsaved: Boolean = false,
    /** messageId -> (userId, emoji); and who I am, to mark my own. */
    val reactions: Map<String, List<Pair<String, String>>> = emptyMap(),
    val myId: String? = null,
    val savingPeer: Boolean = false,
    val messages: List<Message> = emptyList(),
    val typing: List<String> = emptyList(),
    /** The other person's presence in a direct chat; null until the server says (never assumed online). */
    val presence: com.circaso.feature.messaging.domain.Presence? = null,
    val draft: String = "",
    val online: Boolean = true,
    val selected: Message.Text? = null,
    val message: String? = null,
    /** The group's chama, when it has a goal (designs D2–D6); null in direct chats and groups without one. */
    val chama: Chama? = null,
    val fiat: FiatValue? = null,
    val showIntro: Boolean = false,
    val myUserId: String? = null,
    val chamaBusy: Boolean = false,
    /** Group wallets (GW9a2, GW9): the group's money; a wallet that's active turns `#` into a contribution. */
    val groupMoney: com.circaso.feature.chama.domain.GroupMoney? = null,
    /** GW14c: approved payouts that wait for the group wallet's daily limit, by request id, and when they fit (ISO). */
    val payoutWaits: Map<String, String> = emptyMap(),
    /** SP05: the person said that's all; ask whether it was sorted. */
    val supportEnding: Boolean = false,
    /** A voice note is being recorded (the mic is held). */
    /** A video being fetched to play (its bubble shows a spinner), and the file now playing full screen. */
    val videoLoading: Long? = null,
    val videoPath: String? = null,
    val recording: Boolean = false,
    /** When this recording started (for the live counter), epoch millis. */
    val recordingSince: Long? = null,
    /** "Getting your location…" then the place to confirm before it's shared. */
    val locating: Boolean = false,
    val pendingLocation: com.circaso.core.platform.GeoPoint? = null,
    /** Each money card's payment as this phone knows it (transferId → state): Sending, Sent or Not sent. */
    val paymentStates: Map<String, com.circaso.feature.wallet.domain.TransferState> = emptyMap(),
    /** Cards whose payment the server won't show this phone (403/404): shown as they were, never left "on its way". */
    val paymentsUnreadable: Set<String> = emptySet(),
    /** The voice note playing now, by local id. */
    val playing: Long? = null,
    /** When that note's sound started, epoch millis (null while it loads): drives the progress and the count-up. */
    val playingSince: Long? = null,
) {
    /** A chat with Circaso's support account (designs SP02–SP05). */
    val isSupport get() = com.circaso.feature.support.Support.isSupportChat(chat, myUserId)
    val isGroup get() = chat?.kind == ChatKind.Group
    /** The open requests (D5) sit under the messages; the treasurer can't contribute to themselves. */
    val openRequests get() = groupMoney?.openRequests ?: chama?.openRequests.orEmpty()
    /** Group wallets: every member pays into the group's own wallet (its signer too). Treasurer model: not the treasurer. */
    val canContribute get() = if (groupMoney != null) groupMoney.walletActive else chama != null && chama.goal.treasurerUserId != myUserId
    /** Who may decide on requests and who pays them: the group's admins, and its wallet's signer (or the treasurer). */
    val requestsAdmin get() = groupMoney?.isAdmin ?: (chama?.isAdmin == true)
    val requestsPayer get() = groupMoney?.isSigner ?: (chama?.isTreasurer == true)
    fun memberName(userId: String?) = chat?.members?.firstOrNull { it.userId == userId }?.displayName ?: "Someone"
    val canSend get() = draft.isNotBlank() && chat != null
    /** The header's second line: typing first, then the connection, then the member count (design C2/D2 and G3). */
    val subtitle: String
        get() = when {
            typing.size == 1 -> "${typing.first()} is typing…"
            typing.size > 1 -> "${typing.size} people are typing…"
            !online -> "Waiting for network"
            isSupport -> "Verified Circaso team"
            isGroup -> "${chat?.members?.size ?: 0} members"
            presence?.online == true -> "Online"
            presence?.lastSeenAt != null -> lastSeenText(presence.lastSeenAt)
            else -> ""
        }
    /**
     * A money card that can't say Sent / Received yet: its payment is on its way, or this phone hasn't read it yet and
     * the card is recent (a new card is never shown as arrived before the chain has it).
     */
    fun paymentPending(m: Message.Payment, now: Long = nowMillis()): Boolean =
        paymentStates[m.transferId].let { it?.inFlight == true || (it == null && m.transferId !in paymentsUnreadable && now - m.orderAt < PaymentFreshMillis) }

    /** Delete for everyone: my own, sent, within 48 hours (guide, M14). The control is hidden otherwise, never failed after. */
    fun canDeleteForEveryone(m: Message, now: Long): Boolean {
        val (outgoing, messageId) = when (m) { is Message.Text -> m.outgoing to m.messageId; is Message.Media -> m.outgoing to m.messageId; else -> return false }
        return outgoing && messageId != null && now - m.orderAt < 48L * 60 * 60 * 1000
    }
}

class ThreadViewModel(
    private val chatId: String,
    observeChat: ObserveChatUseCase,
    observeThread: ObserveThreadUseCase,
    observeTyping: ObserveTypingUseCase,
    private val sendMessage: SendMessageUseCase,
    private val retryMessage: RetryMessageUseCase,
    private val discardMessage: DiscardMessageUseCase,
    private val deleteForEveryone: DeleteForEveryoneUseCase,
    private val markRead: MarkReadUseCase,
    private val setTyping: SetTypingUseCase,
    connection: ConnectionState,
    private val notifications: ChatNotifications,
    private val chama: ThreadChama? = null,
    val media: ThreadMedia? = null,
    /** Presence of the person on the other side ("Online" / "last seen"). */
    private val messagesRepository: com.circaso.feature.messaging.domain.MessagesRepository? = null,
    private val contacts: com.circaso.feature.contacts.domain.ContactsRepository? = null,
    /** The money cards' payment states (Sending → Sent once confirmed); absent in previews and older tests. */
    private val payments: ThreadPayments? = null,
    private val checkEveryMillis: Long = PaymentCheckEveryMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(ThreadUiState())

    init {
        messagesRepository?.let { repo ->
            viewModelScope.launch { val me = repo.myId(); _state.update { it.copy(myId = me) } }
            viewModelScope.launch { repo.reactions(chatId).collect { r -> _state.update { it.copy(reactions = r) } } }
        }
        contacts?.let { repo ->
            viewModelScope.launch {
                kotlinx.coroutines.flow.combine(observeChat(chatId), repo.contacts) { chat, saved ->
                    val peer = chat?.peerId
                    chat != null && chat.kind == com.circaso.feature.messaging.domain.ChatKind.Direct && peer != null && saved.none { it.person.userId == peer }
                }.collect { unsaved -> _state.update { it.copy(peerUnsaved = unsaved) } }
            }
        }
    }

    /** What the payout limit checks last asked about: each approved request, at the group money's read it saw. */
    private val payoutsChecked = mutableSetOf<String>()

    /**
     * GW14c: every approved payout not yet paid is asked about once per read of the group's money (a payment that
     * confirms reads it again), so its card can say "waits for the limit" and when it fits. Online only; nothing is
     * shown on a failure or offline, rather than a guess.
     */
    private fun checkPayoutWaits(chama: ThreadChama, money: com.circaso.feature.chama.domain.GroupMoney) {
        if (money.wallet?.active != true) return
        val approved = money.openRequests.filter { it.state == com.circaso.feature.chama.domain.RequestState.Approved && it.transferId == null }
        _state.update { s -> s.copy(payoutWaits = s.payoutWaits.filterKeys { id -> approved.any { it.requestId == id } }) }
        // Offline nothing is asked, and nothing is marked asked: the next read once back online asks.
        if (!_state.value.online) return
        for (request in approved) {
            if (!payoutsChecked.add("${request.requestId}@${money.fetchedAt}")) continue
            viewModelScope.launch {
                // GW14c: "the button wakes up at the time the server gives": wait for it, then ask again.
                while (true) {
                    val until = chama.payoutWaitsUntil(money, request)
                    _state.update { s -> s.copy(payoutWaits = if (until != null) s.payoutWaits + (request.requestId to until) else s.payoutWaits - request.requestId) }
                    val wait = until?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() - com.circaso.core.sync.nowMillis() }.getOrNull() } ?: break
                    kotlinx.coroutines.delay(wait.coerceAtLeast(0) + 1_000)
                    _state.update { s -> s.copy(payoutWaits = s.payoutWaits - request.requestId) }
                    if (!_state.value.online) break
                }
            }
        }
    }

    /** React to a message (WhatsApp-style); null takes my reaction back. Queued like any message. */
    fun onReactTo(messageId: String, emoji: String?) { viewModelScope.launch { messagesRepository?.react(chatId, messageId, emoji) } }

    /** Saves the other person as my contact (their @handle), so we see each other's moments and they can find me. */
    fun onSavePeer() {
        val chat = _state.value.chat ?: return
        val handle = chat.members.firstOrNull { it.userId == chat.peerId }?.handle ?: return
        val repo = contacts ?: return
        _state.update { it.copy(savingPeer = true) }
        viewModelScope.launch {
            val r = repo.connect(com.circaso.feature.contacts.domain.ConnectVia.Handle, handle)
            if (r is com.circaso.core.common.AppResult.Success) repo.refreshContacts()
            _state.update { it.copy(savingPeer = false, message = if (r is com.circaso.core.common.AppResult.Success) "${chat.title.substringBefore(' ')} is in your contacts" else "Couldn’t save ${chat.title.substringBefore(' ')}. Try again when you’re online.") }
        }
    }
    val state: StateFlow<ThreadUiState> = _state.asStateFlow()
    private var typingSentAt = 0L
    private var typingStop: Job? = null
    private var chamaRefreshed = false
    // Declared before init: the collector below starts at once (Main.immediate) and would read it unset.
    private val window = MutableStateFlow(ThreadPage)

    init {
        notifications.activeChatId = chatId
        notifications.clear(chatId)
        viewModelScope.launch {
            observeChat(chatId).collect { chat ->
                _state.update { it.copy(chat = chat) }
                // The phone's copy shows at once; the server's view (CH1) refreshes behind it, once per open.
                if (chat?.kind == ChatKind.Group && chama != null && !chamaRefreshed) { chamaRefreshed = true; launch { chama.refresh(chatId) } }
            }
        }
        if (chama != null) viewModelScope.launch {
            chama.observe(chatId).collect { s ->
                _state.update { it.copy(chama = s.chama, fiat = s.fiat, showIntro = s.showIntro, myUserId = s.myUserId, groupMoney = s.groupMoney) }
                s.groupMoney?.let { checkPayoutWaits(chama, it) }
            }
        }
        viewModelScope.launch {
            // A window of the newest messages that grows by a page as the person scrolls up (onLoadOlder).
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            window.flatMapLatest { limit -> observeThread(chatId, limit) }.collect { list ->
                _state.update { it.copy(messages = list) }
                markRead(chatId) // the thread is on screen: everything in it has been seen
            }
        }
        viewModelScope.launch { observeTyping(chatId).collect { names -> _state.update { it.copy(typing = names) } } }
        // Presence of the person on the other side, kept fresh while the chat is open.
        messagesRepository?.let { repo ->
            viewModelScope.launch { repo.presence(chatId).collect { p -> _state.update { it.copy(presence = p) } } }
            viewModelScope.launch {
                while (true) { repo.watchPresence(chatId); kotlinx.coroutines.delay(com.circaso.feature.messaging.domain.PresenceWatchEveryMillis) }
            }
        }
        viewModelScope.launch { connection.status.collect { s -> _state.update { it.copy(online = s == ConnectionStatus.Online) } } }
        payments?.let { p ->
            viewModelScope.launch {
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                _state.map { s -> s.messages.mapNotNull { (it as? Message.Payment)?.transferId }.toSet() }
                    .distinctUntilChanged()
                    .flatMapLatest { ids -> if (ids.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyMap()) else p.states(ids) }
                    .collect { states -> _state.update { it.copy(paymentStates = states) } }
            }
            // A card still on its way while the chat is on screen: read W5 at once and now and then, so a missed frame
            // never leaves it Sending. Not after the server says this phone may not read it.
            viewModelScope.launch {
                // Once the thread has loaded; then while someone is looking at it (the chat is on screen).
                _state.first { it.messages.isNotEmpty() }
                while (true) {
                    _state.subscriptionCount.first { it > 0 }
                    val s = _state.value
                    if (s.online) {
                        s.messages.filterIsInstance<Message.Payment>()
                            .filter { m -> s.paymentPending(m) }
                            .takeLast(PaymentChecksAtOnce)
                            .forEach { m -> if (!p.check(m.transferId)) _state.update { it.copy(paymentsUnreadable = it.paymentsUnreadable + m.transferId) } }
                    }
                    delay(checkEveryMillis)
                }
            }
        }
    }

    /** Typing goes out at most every few seconds and stops a moment after the last keystroke (guide, section 8). */

    /** The person is near the oldest message shown: bring in the next page, if there might be one. */
    fun onLoadOlder() {
        if (_state.value.messages.size >= window.value) window.value += ThreadPage
    }

    fun onDraft(text: String) {
        _state.update { it.copy(draft = text) }
        if (text.isEmpty()) { stopTyping(); return }
        val now = nowMillis()
        if (now - typingSentAt > TypingEveryMillis) { typingSentAt = now; viewModelScope.launch { setTyping(chatId, true) } }
        typingStop?.cancel()
        typingStop = viewModelScope.launch { delay(TypingStopAfterMillis); stopTyping() }
    }

    private fun stopTyping() {
        typingStop?.cancel()
        if (typingSentAt == 0L) return
        typingSentAt = 0L
        viewModelScope.launch { setTyping(chatId, false) }
    }

    fun onSend() {
        val s = _state.value
        if (!s.canSend) return
        val text = s.draft.trim()
        _state.update { it.copy(draft = "") }
        stopTyping()
        viewModelScope.launch { sendMessage(chatId, text) }
    }

    /** A chip under a money card (design C6): sent as a message of its own. */
    fun onQuickReply(text: String) {
        if (_state.value.chat == null) return
        viewModelScope.launch { sendMessage(chatId, text) }
    }

    fun onSelect(m: Message.Text?) = _state.update { it.copy(selected = m, message = null) }

    fun onRetry(m: Message) { onSelect(null); viewModelScope.launch { retryMessage(m.localId) } }
    fun onDiscard(m: Message) { onSelect(null); viewModelScope.launch { discardMessage(m.localId) } }

    fun onDeleteForEveryone(m: Message) {
        onSelect(null)
        viewModelScope.launch {
            val r = deleteForEveryone(m.localId)
            if (r is AppResult.Failure) _state.update { it.copy(message = ErrorMessages.forError(r.error)) }
        }
    }

    fun onMessageShown() = _state.update { it.copy(message = null) }

    /** CH5, from a request card in the thread (design D5). The card updates when the server's frame lands. */
    fun onDecide(request: ChamaRequest, approve: Boolean) {
        val chama = chama ?: return
        if (_state.value.chamaBusy) return
        _state.update { it.copy(chamaBusy = true) }
        viewModelScope.launch {
            val r = chama.decide(request, approve)
            _state.update { it.copy(chamaBusy = false, message = (r as? AppResult.Failure)?.let { f -> ErrorMessages.forError(f.error) }) }
        }
    }

    /** Media (WhatsApp-style): current location, hold-to-record voice notes, playback. Photos, videos and documents go through ChatMediaComposer. */
    /** Step one of sharing a location: find it, then show it for the person to confirm (nothing is sent yet). */
    fun onSendLocation() {
        val m = media ?: return
        if (_state.value.locating) return
        _state.update { it.copy(locating = true, pendingLocation = null) }
        viewModelScope.launch {
            val here = m.locate()
            _state.update {
                if (!it.locating) it // cancelled while it was looking
                else if (here == null) it.copy(locating = false, message = "Circaso couldn't get your location. Check that location is on and allowed for Circaso.")
                else it.copy(pendingLocation = here)
            }
        }
    }
    fun onConfirmLocation() {
        val m = media ?: return
        val point = _state.value.pendingLocation ?: return
        _state.update { it.copy(locating = false, pendingLocation = null) }
        viewModelScope.launch { m.sendLocation(chatId, point) }
    }
    fun onCancelLocation() = _state.update { it.copy(locating = false, pendingLocation = null) }

    /** WhatsApp's live location from the same sheet: the confirmed place, then updates for [durationMs]. */
    fun onShareLive(durationMs: Long) {
        val m = media ?: return
        val point = _state.value.pendingLocation ?: return
        _state.update { it.copy(locating = false, pendingLocation = null) }
        viewModelScope.launch { m.shareLive(chatId, point, durationMs)?.let { e -> _state.update { it.copy(message = e) } } }
    }

    fun onStopLive(message: Message.Media) {
        val m = media ?: return
        viewModelScope.launch { m.stopLive(message.localId) }
    }

    private val voice = media?.let { m ->
        VoiceHold(viewModelScope, m::startVoice, { m.finishVoice(chatId) }, m::cancelVoice) { recording, message ->
            _state.update {
                it.copy(recording = recording, recordingSince = if (recording) nowMillis() else null, message = message ?: it.message)
            }
        }
    }
    fun onVoiceStart() { voice?.press() }
    fun onVoiceEnd(send: Boolean) { voice?.release(send) }
    fun onPlay(message: Message.Media) {
        val m = media ?: return
        if (_state.value.playing == message.localId) { m.stopPlaying(); _state.update { it.copy(playing = null, playingSince = null) }; return }
        m.stopPlaying()
        _state.update { it.copy(playing = message.localId, playingSince = null) }
        viewModelScope.launch {
            val result = m.play(message.details) { _state.update { s -> if (s.playing == message.localId) s.copy(playingSince = nowMillis()) else s } }
            _state.update { s ->
                val done = if (s.playing == message.localId) s.copy(playing = null, playingSince = null) else s
                when (result) {
                    is com.circaso.feature.messaging.data.repository.MediaLoad.Loaded -> done
                    com.circaso.feature.messaging.data.repository.MediaLoad.Offline -> done.copy(message = "This voice message plays when you're back online.")
                    com.circaso.feature.messaging.data.repository.MediaLoad.Gone -> done.copy(message = "This voice message is no longer available.")
                    com.circaso.feature.messaging.data.repository.MediaLoad.Damaged -> done.copy(message = "This voice message couldn't be opened safely.")
                    com.circaso.feature.messaging.data.repository.MediaLoad.Failed -> done.copy(message = "This voice message couldn't load. Try again.")
                }
            }
        }
    }
    /** Opens a video full screen: its file is fetched and decrypted first (a spinner on the bubble meanwhile). */
    fun onPlayVideo(message: Message.Media) {
        val m = media ?: return
        _state.update { it.copy(videoLoading = message.localId) }
        viewModelScope.launch {
            val (path, result) = m.videoFile(message.details)
            _state.update { s ->
                val done = s.copy(videoLoading = null)
                when {
                    path != null -> done.copy(videoPath = path)
                    result == com.circaso.feature.messaging.data.repository.MediaLoad.Offline -> done.copy(message = "This video plays when you're back online.")
                    result == com.circaso.feature.messaging.data.repository.MediaLoad.Gone -> done.copy(message = "This video is no longer available.")
                    result == com.circaso.feature.messaging.data.repository.MediaLoad.Damaged -> done.copy(message = "This video couldn't be opened safely.")
                    else -> done.copy(message = "This video couldn't load. Try again.")
                }
            }
        }
    }

    fun onCloseVideo() = _state.update { it.copy(videoPath = null) }

    fun onRetryMedia(message: Message.Media) { val m = media ?: return; viewModelScope.launch { m.retry(message.localId) } }

    /** SP02's "That's all": it goes as a message, then SP05 asks how it went. */
    fun onSupportDone() {
        onQuickReply("That's all, thank you.")
        _state.update { it.copy(supportEnding = true) }
    }

    /** SP05's answer goes to support as a message too, so the team sees it in the same conversation. */
    fun onSupportVerdict(sorted: Boolean?) {
        if (sorted != null) onQuickReply(if (sorted) "Yes, it's sorted. Thanks!" else "Not really, it isn't sorted yet.")
        _state.update { it.copy(supportEnding = false) }
    }

    /** D6 was read; it doesn't come back for this group. */
    fun onIntroSeen() {
        val chama = chama ?: return
        _state.update { it.copy(showIntro = false) }
        viewModelScope.launch { chama.introSeen(chatId) }
    }

    override fun onCleared() {
        stopTyping()
        if (notifications.activeChatId == chatId) notifications.activeChatId = null
    }

    private companion object {
        const val PaymentCheckEveryMillis = 8_000L
        const val PaymentChecksAtOnce = 3
        const val TypingEveryMillis = 4_000L
        const val TypingStopAfterMillis = 5_000L
    }
}


/** Cards younger than this with no state yet count as on their way; older ones settled long ago. */
internal const val PaymentFreshMillis = 30L * 60 * 1000

/** Messages per page of a thread: what opens, and what each scroll up adds. */
private const val ThreadPage = 100

/** "last seen today at 10:32", "last seen yesterday at 21:05", "last seen Mon at 08:10", else the date. */
internal fun lastSeenText(at: Long, now: Long = com.circaso.core.sync.nowMillis()): String {
    val clock = com.circaso.core.platform.formatLocalTime(at, com.circaso.core.platform.LocalTimeStyle.Clock)
    val days = com.circaso.core.platform.localDayIndex(now) - com.circaso.core.platform.localDayIndex(at)
    return when {
        days <= 0 -> "last seen today at $clock"
        days == 1L -> "last seen yesterday at $clock"
        days < 7 -> "last seen ${com.circaso.core.platform.formatLocalTime(at, com.circaso.core.platform.LocalTimeStyle.Weekday)} at $clock"
        else -> "last seen ${com.circaso.core.platform.formatLocalTime(at, com.circaso.core.platform.LocalTimeStyle.ShortDate)}"
    }
}
