package com.circaso.feature.messaging.presentation

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import com.circaso.designsystem.components.HeaderSpacer
import com.circaso.designsystem.chrome.glass
import androidx.compose.foundation.gestures.calculateCentroid
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.height
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.components.CircasoIconImage
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.feature.chama.domain.Chama
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.chama.presentation.GoalStrip
import com.circaso.feature.chama.presentation.GroupIntroContent
import com.circaso.feature.chama.presentation.RequestCard
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.circaso.core.common.AppError
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.platform.localDayIndex
import com.circaso.core.platform.threadDayLabel
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.BubbleMeta
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.ChatBubble
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.ComposerBar
import com.circaso.designsystem.components.DayLabel
import com.circaso.designsystem.components.MessageColumn
import com.circaso.designsystem.components.MoneyCard
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.presentation.PayFromSheetContent
import com.circaso.designsystem.components.SystemLine
import com.circaso.designsystem.components.ThreadHeader
import com.circaso.designsystem.components.textWithoutAshaCommand
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.Message

/**
 * Designs C2 (direct) and D2 (group), with the offline states of G3 and the failed state of G2. The header's calls and
 * money actions wait for their backends; "Group info" / "Chat info" is the one trailing action for now.
 */
@Composable
fun ThreadScreen(
    viewModel: ThreadViewModel,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onAsha: (() -> Unit)?,
    onPay: ((amount: String?) -> Unit)? = null,
    onOpenTransfer: (String) -> Unit = {},
    onOpenGoal: (() -> Unit)? = null,
    onContribute: ((amount: String?) -> Unit)? = null,
    onPayRequest: ((ChamaRequest) -> Unit)? = null,
    onCallSupport: (() -> Unit)? = null,
    onCall: ((video: Boolean) -> Unit)? = null,
    /** A call in this chat that's on but minimised: "Ongoing call · Tap to return" (WhatsApp-style). */
    ongoingCall: String? = null,
    onReturnToCall: () -> Unit = {},
    onOpenLocation: (Message.Media) -> Unit = {},
    /**
     * Money starts and ends in the chat (C3, GW9a/GW9a2; Pasaka 2026-11-04): `#500` pays the other person, or in a group
     * with a wallet adds to it; the chat's money card is the review and Pay goes straight to the Circle PIN.
     */
    quickPay: com.circaso.feature.wallet.presentation.PayViewModel? = null,
    /** An approved request's Pay (D5/GW9), on the same card over the thread; [onPayoutClose] puts it away. */
    payout: com.circaso.feature.wallet.presentation.PayViewModel? = null,
    onPayoutClose: () -> Unit = {},
    /** Changes when something outside the chat (its profile's Pay) asks to start a payment here: `#` in the composer. */
    moneyPrompt: Long? = null,
    /** The prompt was acted on: forget it, so coming back to the chat doesn't start another. */
    onMoneyPromptHandled: () -> Unit = {},
    focusLocalId: Long? = null,
    onOpenMoment: (posterId: String?, momentId: String) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsState()
    val pay = quickPay?.let { vm ->
        val payState by vm.state.collectAsState()
        QuickPay(payState, vm::onAmount, vm::onNote, vm::onPay, vm::onQuickReset, vm::onErrorShown, vm::onAssetTag, vm::onChoosing, vm::onPayFrom, vm::onRetryExact, vm::onRetryQuote, vm::onUseMax)
    }
    // Opening a request's Pay again: its card starts over only if its last payment didn't go (the approval still stands).
    // If that payment is on its way or confirmed, it isn't paid twice: say so and put the card away.
    val threadState by viewModel.state.collectAsState()
    var payoutBusy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(payout) {
        val done = payout?.state?.value?.takeIf { it.step == com.circaso.feature.wallet.presentation.PayStep.Done } ?: return@LaunchedEffect
        val last = done.result?.transferId?.let { threadState.paymentStates[it] }
        if (done.result == null || last?.notSent == true) payout.onQuickReset() else { payoutBusy = true; onPayoutClose() }
    }
    val paying = payout?.let { vm ->
        val payState by vm.state.collectAsState()
        QuickPay(payState, vm::onAmount, vm::onNote, vm::onPay, vm::onQuickReset, vm::onErrorShown, vm::onAssetTag, vm::onChoosing, vm::onPayFrom, vm::onRetryExact, vm::onRetryQuote, vm::onUseMax)
    }
    ThreadContent(
        state, onBack, onInfo, onAsha, viewModel::onDraft, viewModel::onSend, viewModel::onSelect, viewModel::onRetry, viewModel::onDiscard, viewModel::onDeleteForEveryone, viewModel::onMessageShown,
        onPay = onPay, onOpenTransfer = onOpenTransfer, onQuickReply = viewModel::onQuickReply,
        onOpenGoal = onOpenGoal, onContribute = onContribute, onPayRequest = onPayRequest, onDecide = viewModel::onDecide, onIntroSeen = viewModel::onIntroSeen,
        onSupportDone = viewModel::onSupportDone, onSupportVerdict = viewModel::onSupportVerdict, onCallSupport = onCallSupport, onCall = onCall,
        ongoingCall = ongoingCall, onReturnToCall = onReturnToCall, onOpenLocation = onOpenLocation, quickPay = pay, focusLocalId = focusLocalId,
        payout = paying, onPayoutClose = onPayoutClose, moneyPrompt = moneyPrompt, onMoneyPromptHandled = onMoneyPromptHandled,
        notice = if (payoutBusy) "This payout is already on its way." else null, onNoticeShown = { payoutBusy = false },
        onLoadOlder = viewModel::onLoadOlder, onSavePeer = viewModel::onSavePeer, onOpenMoment = onOpenMoment, onReactTo = viewModel::onReactTo,
        media = viewModel.media?.let { m ->
            ThreadMediaActions(
                canShareLocation = m.canShareLocation, onPhoto = viewModel::onSendPhoto, onLocation = viewModel::onSendLocation,
                onVoiceStart = viewModel::onVoiceStart, onVoiceEnd = viewModel::onVoiceEnd, load = { d -> m.load(d) }, mapPicture = m::mapPicture,
                onShareLive = viewModel::onShareLive, onStopLive = viewModel::onStopLive,
                onPlayVideo = viewModel::onPlayVideo, onCloseVideo = viewModel::onCloseVideo,
                onGalleryOpen = viewModel::onGalleryOpen, onGalleryToggle = viewModel::onGalleryToggle, onGallerySend = viewModel::onGallerySend,
                galleryThumb = if (m.hasGallery) m::galleryThumbnail else null,
                onPlay = viewModel::onPlay, onRetry = viewModel::onRetryMedia,
                onConfirmLocation = viewModel::onConfirmLocation, onCancelLocation = viewModel::onCancelLocation,
                onPhotoCaption = viewModel::onPhotoCaption, onConfirmPhoto = viewModel::onConfirmPhoto, onCancelPhoto = viewModel::onCancelPhoto,
            )
        },
    )
}

@Composable
fun ThreadContent(
    state: ThreadUiState,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onAsha: (() -> Unit)?,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onSelect: (Message.Text?) -> Unit,
    onRetry: (Message) -> Unit,
    onDiscard: (Message) -> Unit,
    onDeleteForEveryone: (Message) -> Unit,
    onMessageShown: () -> Unit,
    /** Send money to the other person (direct chats, design C2's header action and the `#amount` shortcut, C3). */
    onPay: ((amount: String?) -> Unit)? = null,
    onOpenTransfer: (transferId: String) -> Unit = {},
    onQuickReply: (String) -> Unit = {},
    /** The group's goal (designs D2–D6): open the group wallet, add to the goal, pay an approved request, decide one. */
    onOpenGoal: (() -> Unit)? = null,
    onContribute: ((amount: String?) -> Unit)? = null,
    onPayRequest: ((ChamaRequest) -> Unit)? = null,
    onDecide: (ChamaRequest, Boolean) -> Unit = { _, _ -> },
    onIntroSeen: () -> Unit = {},
    /** Support (SP02, SP05): "That's all" and how it went; a call to support when calls exist. */
    onSupportDone: () -> Unit = {},
    onSupportVerdict: (Boolean?) -> Unit = {},
    onCallSupport: (() -> Unit)? = null,
    /** Designs C2/D2's call buttons (H1–H4): a voice or video call with this chat. */
    onCall: ((video: Boolean) -> Unit)? = null,
    ongoingCall: String? = null,
    onReturnToCall: () -> Unit = {},
    /** Photos, location and voice notes (WhatsApp-style); null hides the attach and mic buttons. */
    media: ThreadMediaActions? = null,
    /** A location bubble was tapped: its map, with everyone sharing live in this chat. */
    onOpenLocation: (Message.Media) -> Unit = {},
    quickPay: QuickPay? = null,
    /** An approved request being paid from the chat (D5/GW9): its money card replaces the composer's while open. */
    payout: QuickPay? = null,
    onPayoutClose: () -> Unit = {},
    /** Put `#` in the composer and focus it when this changes (a Pay from the chat's profile). */
    moneyPrompt: Long? = null,
    onMoneyPromptHandled: () -> Unit = {},
    /** A one-off line for the toast (a payout already on its way). */
    notice: String? = null,
    onNoticeShown: () -> Unit = {},
    focusLocalId: Long? = null,
    /** Near the oldest message shown: load the next page up. */
    onLoadOlder: () -> Unit = {},
    /** Save the other person of a direct chat as a contact. */
    onSavePeer: () -> Unit = {},
    /** React to a message (null takes mine back). */
    /** React to any message by id (photos, voice notes, payments). */
    onReactTo: (messageId: String, emoji: String?) -> Unit = { _, _ -> },
    /** A reply's quoted moment was tapped: open it ([posterId] null for my own). */
    onOpenMoment: (posterId: String?, momentId: String) -> Unit = { _, _ -> },
) {
    val c = CircasoTheme.colors
    var toastPayError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Design C3: "#500 " becomes a money chip; what follows is the note. Null when there is no chip.
    var payChip by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val chipHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun clearPay() { payChip = null; onDraft(""); quickPay?.onReset?.invoke() }
    // Navigation hands the chat a quick pay only where `#` means money (a direct chat, a group taking contributions).
    val payable = quickPay != null && !state.isSupport
    val composerFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    // "Add" on the goal strip, or Pay on the profile: the payment starts in the composer, as `#` (Pasaka's rule).
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun focusComposer() { runCatching { composerFocus.requestFocus() }; keyboard?.show() }
    fun startMoney() {
        if (!payable) return
        // Something typed already stays: the money can wait for it to be sent or cleared.
        if (state.draft.isBlank()) { payChip = null; quickPay?.onReset?.invoke(); onDraft("#") }
        focusComposer()
    }
    LaunchedEffect(moneyPrompt) { if (moneyPrompt != null) { startMoney(); onMoneyPromptHandled() } }
    // A card the chain refused: Try Again puts the same payment back on the money card.
    fun retryCard(m: Message.Payment) {
        val q = quickPay ?: return
        val decimals = q.state.wallet?.chains.orEmpty().flatMap { it.assets }.firstOrNull { it.symbol.equals(m.asset, true) }?.decimals ?: 6
        val rest = listOfNotNull(m.asset.lowercase().takeIf { it != "usdc" }?.let { "@$it" }, com.circaso.feature.wallet.presentation.cardNoteTyped(m.note).takeIf { it.isNotEmpty() }).joinToString(" ")
        q.onReset()
        // Exactly the coin amount it was, so no rounding or rate move changes what is sent.
        payChip = "#${MinorUnits.toDisplay(m.amountMinor, decimals)} ${m.asset}"; onDraft(rest)
        q.onRetryExact(m.amountMinor, m.asset, m.note)
        focusComposer()
    }
    val typingAmount = if (payChip == null && payable) typingAmount(state.draft) else null
    LaunchedEffect(typingAmount) {
        if (typingAmount != null) quickPay?.onAmount?.invoke(typingAmount) else if (payChip == null) quickPay?.onReset?.invoke()
    }
    // Editing the amount turns the chip back into text ("#500 @usdc lunch"): the preview stays, with its coin and note.
    LaunchedEffect(state.draft, typingAmount) {
        if (typingAmount != null) {
            val (tag, note) = splitAssetTag(afterTypedAmount(state.draft))
            quickPay?.onAssetTag?.invoke(tag)
            quickPay?.onNote?.invoke(note)
        }
    }
    val paying = (payChip != null || typingAmount != null) && payout == null
    // The room above the composer: the money card never grows past it (large text, small phone, keyboard up).
    // It changes on every frame the keyboard slides, so only the card reads it (inside its own lambda): read here, it
    // would recompose the whole conversation each frame and the keyboard would come up in jerks.
    val threadHeightPx = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    fun cardMax() = threadHeightPx.intValue.let { h -> if (h > 0) with(density) { h.toDp() } - CircasoSpacing.s2 else androidx.compose.ui.unit.Dp.Unspecified }
    val cardMotion = if (CircasoTheme.reduceMotion) Modifier else Modifier.animateContentSize(tween(CircasoMotion.BaseMs))
    // "@" after the amount: the coins this wallet holds, the most valuable first, narrowing as the person types.
    val coinQuery = if (payChip != null || typingAmount != null) coinQuery(state.draft) else null
    val coinChoices = coinQuery?.let { q -> heldCoins(quickPay?.state?.wallet).filter { it.symbol.startsWith(q, ignoreCase = true) }.take(4) }.orEmpty()
    // Sent (the SDK came back and the card is in the chat): the composer is ready for the next message.
    LaunchedEffect(quickPay?.state?.step) { if (quickPay?.state?.step == com.circaso.feature.wallet.presentation.PayStep.Done) clearPay() }
    // GW9c from a chat: nothing moved, so there is no card here; say where the request went.
    LaunchedEffect(quickPay?.state?.requested) { quickPay?.state?.requested?.let { toastPayError = "Asked the admins of ${quickPay.state.walletName}. The request is in the group chat." } }
    LaunchedEffect(notice) { notice?.let { toastPayError = it; onNoticeShown() } }
    LaunchedEffect(quickPay?.state?.error) { quickPay?.state?.error?.let { e -> quickPay.onErrorShown(); toastPayError = e } }
    // The payout is signed (its card is in the chat, the request turns Paid when confirmed), or requested: put it away.
    LaunchedEffect(payout?.state?.step) { if (payout?.state?.step == com.circaso.feature.wallet.presentation.PayStep.Done) onPayoutClose() }
    LaunchedEffect(payout?.state?.error) { payout?.state?.error?.let { e -> payout.onErrorShown(); toastPayError = e } }
    // Live shares end on the clock: "Live until 5:30 PM" turns into "ended" without a new message.
    val soonestEnd = state.messages.mapNotNull { (it as? Message.Media)?.details?.liveUntil }.filter { it > nowMillis() }.minOrNull()
    val liveNow by androidx.compose.runtime.produceState(nowMillis(), soonestEnd) {
        // Wake at the next share's end (so Stop disappears on time), or every 30 seconds.
        while (true) { kotlinx.coroutines.delay(soonestEnd?.let { (it - nowMillis() + 500).coerceIn(1_000, 30_000) } ?: 30_000); value = nowMillis() }
    }
    val myLiveShare = state.messages.lastOrNull { it is Message.Media && it.outgoing && it.details.liveNow(liveNow) } as? Message.Media
    val loadMedia: suspend (com.circaso.feature.messaging.data.repository.MediaDetails) -> com.circaso.feature.messaging.data.repository.MediaLoad =
        media?.load ?: { com.circaso.feature.messaging.data.repository.MediaLoad.Failed }
    val onPlay: (Message.Media) -> Unit = media?.onPlay ?: {}
    val onRetryMedia: (Message.Media) -> Unit = media?.onRetry ?: {}
    var attaching by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var viewing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ByteArray?>(null) }
    var selectedMedia by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Message.Media?>(null) }
    var reactingTo by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // A new message moves the list under the open reaction bubble: close it rather than leave it beside nothing.
    LaunchedEffect(state.messages.lastOrNull()?.localId) { reactingTo = null }
    // One message's reactions and its reaction bubble; a message without a server id (not sent yet) can't take one.
    fun reactUi(messageId: String?, options: (() -> Unit)? = null) = ReactUi(
        reactions = messageId?.let { state.reactions[it] }.orEmpty(),
        myId = state.myId,
        picking = messageId != null && reactingTo == messageId,
        onOpen = messageId?.let { id -> { reactingTo = id } },
        onPick = { emoji -> reactingTo = null; messageId?.let { onReactTo(it, emoji) } },
        onDismiss = { reactingTo = null },
        onOptions = options?.let { o -> { reactingTo = null; o() } },
    )
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    val quoteScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(state.message) { state.message?.let { toast.show(it); onMessageShown() } }
    LaunchedEffect(toastPayError) { toastPayError?.let { toast.show(it); toastPayError = null } }
    val listState = rememberLazyListState()
    val rows = state.messages.size + state.openRequests.size
    // Opened at one message (from search): scroll there once and mark it briefly; otherwise follow the newest.
    var focused by androidx.compose.runtime.remember(focusLocalId) { androidx.compose.runtime.mutableStateOf(focusLocalId == null) }
    var highlight by androidx.compose.runtime.remember(focusLocalId) { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    // The list is drawn bottom up (newest at index 0, as WhatsApp), so it opens on the latest without scrolling and
    // stays there as messages arrive. Below the messages (lower indexes) sit the open requests and support replies.
    val lastMessage = state.messages.lastOrNull()
    val supportReplies = state.isSupport && lastMessage is Message.Text && !lastMessage.outgoing && state.online
    val tail = state.openRequests.size + if (supportReplies) 1 else 0
    LaunchedEffect(rows) {
        if (rows == 0) return@LaunchedEffect
        if (!focused) {
            val at = state.messages.indexOfFirst { it.localId == focusLocalId }
            if (at >= 0) {
                focused = true
                listState.scrollToItem(tail + state.messages.lastIndex - at)
                highlight = focusLocalId
                kotlinx.coroutines.delay(1_600)
                highlight = null
            }
            return@LaunchedEffect
        }
        // A new message while the person is at the bottom keeps them there; reading older ones, it leaves them be.
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }
    // Older messages load before the top is reached: 30 rows from the oldest shown, the next page comes in.
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 30
        }.collect { nearTop -> if (nearTop) onLoadOlder() }
    }
    val awayFromLatest by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 3 } }
    val listScope = androidx.compose.runtime.rememberCoroutineScope()
    val now = nowMillis()
    val chama = state.chama
    val lastOutgoingPayment = state.messages.lastOrNull { it is Message.Payment && it.outgoing }?.localId

    // Location tiles draw the server's map picture when there is one (MAP1).
    androidx.compose.runtime.CompositionLocalProvider(LocalMapPicture provides media?.mapPicture) {
    CircasoScaffold(
        banner = {
            // Someone I chat with but haven't saved: one tap saves them (moments need both sides saved).
            if (state.peerUnsaved && state.chat?.members?.any { it.userId == state.chat.peerId && it.handle != null } == true) {
                Row(
                    Modifier.fillMaxWidth().background(c.fill).padding(start = CircasoSpacing.s4, end = CircasoSpacing.s2, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                ) {
                    BasicText("${state.chat.title.substringBefore(' ')} isn’t in your contacts. Save them to see each other’s moments.", Modifier.weight(1f), style = t.footnote.copy(color = c.ink))
                    com.circaso.designsystem.components.CircasoButton("Save", onSavePeer, style = com.circaso.designsystem.components.ButtonStyle.Secondary, size = com.circaso.designsystem.components.ButtonSize.Small, busy = state.savingPeer)
                }
            }
            // A call in this chat that's on but minimised: one tap goes back to it.
            AnimatedVisibility(ongoingCall != null, enter = fadeIn(tween(CircasoMotion.BaseMs)), exit = fadeOut(tween(CircasoMotion.QuickMs))) {
                Row(
                    Modifier.fillMaxWidth().background(c.money).pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = onReturnToCall)
                        .semantics { contentDescription = (ongoingCall ?: "Ongoing call") + ". Tap to return" }
                        .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
                    horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircasoIconImage(CircasoIcon.Phone, c.onMoney, Modifier.size(16.dp))
                    BasicText("${ongoingCall ?: "Ongoing call"} · Tap to return", style = t.footnote.copy(color = c.onMoney, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                }
            }
            // Sharing live location here: always visible, one tap to stop (WhatsApp shows the same bar).
            val stillBanner = CircasoTheme.reduceMotion
            AnimatedVisibility(
                myLiveShare != null,
                enter = if (stillBanner) fadeIn(tween(CircasoMotion.BaseMs)) else fadeIn(tween(CircasoMotion.BaseMs)) + androidx.compose.animation.expandVertically(tween(CircasoMotion.BaseMs)),
                exit = if (stillBanner) fadeOut(tween(CircasoMotion.QuickMs)) else fadeOut(tween(CircasoMotion.QuickMs)) + androidx.compose.animation.shrinkVertically(tween(CircasoMotion.QuickMs)),
            ) {
                val share = myLiveShare
                Row(
                    Modifier.fillMaxWidth().background(c.fill).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2)
                        .semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircasoIconImage(CircasoIcon.Pin, c.ink, Modifier.size(16.dp))
                    // Only "sharing" once the share has reached the server; before that, say what will happen.
                    val failedShare = share?.state == DeliveryState.Failed
                    val pendingShare = share?.state == DeliveryState.Waiting || share?.state == DeliveryState.Sending
                    BasicText(
                        when {
                            failedShare -> "Live location not sent"
                            pendingShare -> "Live location starts when you're back online"
                            else -> "Sharing live location" + (share?.details?.liveUntil?.let { " until ${formatLocalTime(it, LocalTimeStyle.Clock)}" } ?: "")
                        },
                        style = t.footnote.copy(color = if (failedShare) c.critical else c.ink), modifier = Modifier.weight(1f),
                    )
                    BasicText(
                        if (failedShare) "Options" else "Stop",
                        style = t.footnote.copy(color = if (failedShare) c.ink else c.critical, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                        modifier = Modifier.pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = {
                            share?.let { if (failedShare) selectedMedia = it else media?.onStopLive?.invoke(it) }
                        }).semantics { contentDescription = if (failedShare) "Live location not sent. Options" else "Stop sharing live location" }
                            .padding(vertical = CircasoSpacing.s1),
                    )
                }
            }
            // Design D2: the goal floats under the header; Add is the contribution (D3), the strip opens the wallet (D4).
            val still = CircasoTheme.reduceMotion
            AnimatedVisibility(
                chama != null,
                enter = if (still) fadeIn(tween(CircasoMotion.QuickMs)) else fadeIn(tween(CircasoMotion.BaseMs)) + expandVertically(tween(CircasoMotion.BaseMs)),
                exit = if (still) fadeOut(tween(CircasoMotion.QuickMs)) else fadeOut(tween(CircasoMotion.QuickMs)) + shrinkVertically(tween(CircasoMotion.QuickMs)),
            ) {
                if (chama != null) GoalStrip(chama, state.fiat, showAdd = state.canContribute && (payable || onContribute != null), canAdd = state.online, onAdd = { if (payable) startMoney() else onContribute?.invoke(null) }, onOpen = { onOpenGoal?.invoke() })
            }
        },
        topBar = {
            ThreadHeader(
                title = state.chat?.title ?: "Chat",
                subtitle = state.subtitle,
                onBack = onBack,
                avatar = {
                    Avatar(state.chat?.title ?: "", size = CircasoSizes.avatarMd, imageUrl = state.chat?.avatarUrl)
                },
                // WhatsApp's header: back, who it is (tap for their profile, with video call and Send money), and Call.
                onTitle = onInfo,
                trailingCount = if (onCall != null && state.isGroup) 2 else 1,
                trailing = {
                    if (onCall != null) {
                        if (state.isGroup) CircasoIconButton(CircasoIcon.Video, "Video call the group", { onCall(true) }, enabled = state.online)
                        CircasoIconButton(CircasoIcon.Phone, if (state.isSupport) "Call Circaso support" else if (state.isGroup) "Call the group" else "Voice call", { onCall(false) }, enabled = state.online)
                    } else {
                        HeaderSpacer()
                    }
                },
            )
        },
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
        overlay = {
            val m = state.selected
            CircasoSheet(visible = m != null, onDismiss = { onSelect(null) }, label = "Message") {
                if (m != null) {
                    BasicText(m.text, style = t.body.copy(color = c.ink))
                    if (m.state == DeliveryState.Failed) {
                        BasicText(failureText(m.errorCode), style = t.footnote.copy(color = c.inkSecondary))
                        CircasoButton("Send Again", { onRetry(m) }, size = ButtonSize.Large, block = true, enabled = state.online)
                        CircasoButton("Discard", { onDiscard(m) }, style = ButtonStyle.Destructive, size = ButtonSize.Large, block = true)
                    } else if (state.canDeleteForEveryone(m, now)) {
                        CircasoButton("Delete for Everyone", { onDeleteForEveryone(m) }, style = ButtonStyle.Destructive, size = ButtonSize.Large, block = true, enabled = state.online)
                    }
                    CircasoButton("Cancel", { onSelect(null) }, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
                }
            }
            // Attach (WhatsApp-style): a photo or the current location.
            LaunchedEffect(attaching) { if (attaching) media?.onGalleryOpen?.invoke() }
            CircasoSheet(visible = attaching, onDismiss = { attaching = false }, label = "Share") {
              Column(Modifier.fillMaxWidth().animateContentSize(tween(CircasoMotion.BaseMs)), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                com.circaso.designsystem.chrome.SheetHead("Share", { attaching = false })
                // WhatsApp's recent grid: tap to tick (numbered in order), send several at once.
                if (state.gallery.isNotEmpty() && media?.galleryThumb != null) {
                    GalleryGrid(state.gallery, state.gallerySelected, media.galleryThumb, media.onGalleryToggle)
                } else if (state.galleryLoading) {
                    // The grid's own shape while it loads, so the sheet doesn't jump when the photos arrive.
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                        modifier = Modifier.fillMaxWidth().height(360.dp).clip(com.circaso.designsystem.theme.CircasoShapes.md),
                        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp), userScrollEnabled = false,
                    ) { items(9) { Box(Modifier.aspectRatio(1f).background(c.fill)) } }
                }
                var caption by androidx.compose.runtime.remember(attaching) { androidx.compose.runtime.mutableStateOf("") }
                if (state.gallerySelected.isNotEmpty()) {
                    com.circaso.designsystem.components.CircasoTextField(caption, { caption = it.take(1000) }, label = "Caption", placeholder = "Add a caption")
                    val n = state.gallerySelected.size
                    val videos = state.gallerySelected.count { id -> state.gallery.any { it.id == id && it.video } }
                    val what = when { videos == 0 -> if (n == 1) "Photo" else "$n Photos"; videos == n -> if (n == 1) "Video" else "$n Videos"; else -> "$n Items" }
                    CircasoButton("Send $what", { attaching = false; media?.onGallerySend?.invoke(caption.trim().takeIf { it.isNotEmpty() }) }, size = ButtonSize.Large, block = true)
                } else {
                    AttachOption(CircasoIcon.Photo, "All Photos", "Choose from your library") { attaching = false; media?.onPhoto?.invoke() }
                    if (media?.canShareLocation == true) AttachOption(CircasoIcon.Pin, "Location", "Your current location") { attaching = false; media.onLocation() }
                }
              }
            }
            // A location is only shared after the person sees where it is and confirms.
            CircasoSheet(visible = state.locating, onDismiss = { media?.onCancelLocation?.invoke() }, label = "Send your location") {
                val here = state.pendingLocation
                // WhatsApp's two choices from one sheet: this place once, or live for a while.
                var liveChoosing by androidx.compose.runtime.remember(state.locating) { androidx.compose.runtime.mutableStateOf(false) }
                var liveFor by androidx.compose.runtime.remember(state.locating) { androidx.compose.runtime.mutableStateOf(1) }
                Column(Modifier.fillMaxWidth().animateContentSize(tween(CircasoMotion.BaseMs)), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                    BasicText(if (liveChoosing) "Share live location" else "Send your location", style = t.title3.copy(color = c.ink))
                    if (here == null) {
                        Row(Modifier.fillMaxWidth().height(MapTileHeight).clip(com.circaso.designsystem.theme.CircasoShapes.lg).background(c.fill).semantics { progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            com.circaso.designsystem.components.Spinner(c.inkSecondary, Modifier.size(20.dp))
                            Spacer(Modifier.width(CircasoSpacing.s2))
                            BasicText("Getting your location…", style = t.footnote.copy(color = c.inkSecondary))
                        }
                    } else {
                        MapTile(
                            here.latitude, here.longitude,
                            Modifier.fillMaxWidth().clip(com.circaso.designsystem.theme.CircasoShapes.lg)
                                .border(1.dp, c.separator, com.circaso.designsystem.theme.CircasoShapes.lg),
                        )
                        BasicText(
                            coordinates(here.latitude, here.longitude) + (here.accuracyMeters?.let { " · Accurate to about ${kotlin.math.round(it).toLong()} m" } ?: ""),
                            style = t.footnote.copy(color = c.inkSecondary),
                        )
                        val who = state.chat?.title?.let { if (state.isGroup) "Everyone in $it" else it } ?: "They"
                        BasicText(
                            if (liveChoosing) "$who will see where you are as you move, until the time is up or you stop. It keeps updating with Circaso closed."
                            else "$who will see this exact place.",
                            style = t.footnote.copy(color = c.inkSecondary),
                        )
                    }
                    if (liveChoosing) {
                        com.circaso.designsystem.components.SegmentedControl(listOf("15 minutes", "1 hour", "8 hours"), liveFor, { liveFor = it }, Modifier.fillMaxWidth())
                        CircasoButton(
                            "Share Live Location",
                            { media?.onShareLive?.invoke(com.circaso.feature.messaging.data.repository.LiveLocations.Durations[liveFor]) },
                            size = ButtonSize.Large, block = true, enabled = here != null,
                        )
                        CircasoButton("Back", { liveChoosing = false }, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
                    } else {
                        CircasoButton("Send Your Location", { media?.onConfirmLocation?.invoke() }, size = ButtonSize.Large, block = true, enabled = here != null)
                        CircasoButton("Share Live Location…", { liveChoosing = true }, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = here != null)
                        CircasoButton("Cancel", { media?.onCancelLocation?.invoke() }, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
                    }
                }
            }
            // The picked photo, with an optional caption, before it goes.
            CircasoSheet(visible = state.pendingPhoto != null, onDismiss = { media?.onCancelPhoto?.invoke() }, label = "Send photo") {
                val photo = state.pendingPhoto
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                    BasicText("Send photo", style = t.title3.copy(color = c.ink))
                    if (photo != null) {
                        val ratio = photo.width?.let { w -> photo.height?.takeIf { it > 0 }?.let { h -> (w.toFloat() / h).coerceIn(0.6f, 1.6f) } } ?: 1f
                        coil3.compose.AsyncImage(
                            model = photo.bytes, contentDescription = "The photo you picked", contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).aspectRatio(ratio, matchHeightConstraintsFirst = true).align(Alignment.CenterHorizontally).clip(com.circaso.designsystem.theme.CircasoShapes.lg).background(c.fill),
                        )
                    }
                    com.circaso.designsystem.components.CircasoTextField(state.photoCaption, { media?.onPhotoCaption?.invoke(it) }, "Caption", placeholder = "Add a caption", singleLine = false)
                    CircasoButton("Send", { media?.onConfirmPhoto?.invoke() }, size = ButtonSize.Large, block = true, enabled = photo != null)
                    CircasoButton("Cancel", { media?.onCancelPhoto?.invoke() }, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
                }
            }
            // A photo, voice note or location: send again or discard when it failed, delete for everyone when sent.
            CircasoSheet(visible = selectedMedia != null, onDismiss = { selectedMedia = null }, label = "Message") {
                val sm = selectedMedia
                if (sm != null) {
                    BasicText(mediaLabel(sm), style = t.body.copy(color = c.ink))
                    if (sm.state == DeliveryState.Failed) {
                        BasicText(failureText(sm.errorCode), style = t.footnote.copy(color = c.inkSecondary))
                        // A file the server refuses for what it is (too big) fails the same way every time: no false promise.
                        if (sm.errorCode !in TerminalMediaErrors) {
                            CircasoButton("Send Again", { selectedMedia = null; onRetryMedia(sm) }, size = ButtonSize.Large, block = true, enabled = state.online)
                        }
                        CircasoButton("Discard", { selectedMedia = null; onDiscard(sm) }, style = ButtonStyle.Destructive, size = ButtonSize.Large, block = true)
                    } else if (state.canDeleteForEveryone(sm, now)) {
                        CircasoButton("Delete for Everyone", { selectedMedia = null; onDeleteForEveryone(sm) }, style = ButtonStyle.Destructive, size = ButtonSize.Large, block = true, enabled = state.online)
                    }
                    CircasoButton("Cancel", { selectedMedia = null }, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
                }
            }
            ImageViewer(viewing) { viewing = null }
            // Fades in and out like the photo viewer; the last path is kept through the fade out.
            var lastVideo by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
            state.videoPath?.let { lastVideo = it }
            AnimatedVisibility(
                state.videoPath != null, Modifier.matchParentSize(),
                enter = if (CircasoTheme.reduceMotion) fadeIn(tween(CircasoMotion.BaseMs)) else fadeIn(tween(CircasoMotion.BaseMs)) + androidx.compose.animation.scaleIn(tween(CircasoMotion.BaseMs), initialScale = 0.94f),
                exit = fadeOut(tween(CircasoMotion.QuickMs)),
            ) { lastVideo?.let { path -> Box(Modifier.fillMaxSize()) { FullScreenVideo(path) { media?.onCloseVideo?.invoke() } } } }
            // GW10: Paying from, opened from the chat's money card; picking a group wallet turns it into Request Payout.
            quickPay?.let { q ->
                CircasoSheet(visible = q.state.choosing, onDismiss = { q.onChoosing(false) }, label = "Pay from") {
                    PayFromSheetContent(q.state.choices, q.state.payingFrom.walletId, q.state.balances, q.state.online, q.state.asOf, onPick = q.onPayFrom, onClose = { q.onChoosing(false) })
                }
            }
            // Design SP05: the person is done; was it sorted?
            CircasoSheet(visible = state.supportEnding, onDismiss = { onSupportVerdict(null) }, label = "Did we sort it out?") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                    Avatar(com.circaso.feature.support.Support.Name, size = CircasoSizes.avatarLg, imageUrl = state.chat?.avatarUrl)
                    BasicText("Did we sort it out?", style = t.title2.copy(color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                    BasicText("Your answer goes to the team in this chat. The conversation stays here in Help.", style = t.body.copy(color = c.inkSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                    // Ordinary messages: they queue offline like any other (offline-first.md).
                    CircasoButton("Yes, Thanks", { onSupportVerdict(true) }, size = ButtonSize.Large, block = true)
                    CircasoButton("Not Really", { onSupportVerdict(false) }, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
                    CircasoButton("Back to Chat", { onSupportVerdict(null) }, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
                }
            }
            // Design D6: the first time this phone opens a group with a goal.
            CircasoSheet(visible = state.showIntro && chama != null && state.chat != null, onDismiss = onIntroSeen, label = "About this group") {
                if (chama != null && state.chat != null) GroupIntroContent(state.chat, chama, state.fiat, state.myUserId, onDone = onIntroSeen, inGroupWallet = state.groupMoney != null)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { threadHeightPx.intValue = it.height }) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2, bottom = CircasoSpacing.s3),
                verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2, Alignment.Bottom),
            ) {
                // Bottom up: what sits under the conversation first, then the messages newest to oldest.
                threadTail(state, supportReplies, chama, onDecide, onPayRequest, onQuickReply, onCallSupport, onSupportDone)
                itemsIndexed(state.messages.asReversed(), key = { _, m -> m.localId }) { reversedIndex, m ->
                    val index = state.messages.lastIndex - reversedIndex
                    val lit by androidx.compose.animation.animateColorAsState(if (highlight == m.localId) c.fill else androidx.compose.ui.graphics.Color.Transparent, tween(CircasoMotion.BaseMs), label = "found")
                    Box(Modifier.fillMaxWidth().background(lit)) {
                    val previous = state.messages.getOrNull(index - 1)
                    if (previous == null || localDayIndex(previous.orderAt) != localDayIndex(m.orderAt)) DayLabel(threadDayLabel(m.orderAt, now))
                    when (m) {
                        is Message.Text -> {
                            val firstOfRun = previous !is Message.Text || previous.senderId != m.senderId || previous.outgoing != m.outgoing
                            TextMessage(m, state.isGroup, firstOfRun, peerName = state.chat?.title, react = reactUi(m.messageId), onOpenMoment = { q, outgoing ->
                                // My reply quotes their moment; their reply quotes mine.
                                if (q.expiresAt?.let { it <= com.circaso.core.sync.nowMillis() } == true) quoteScope.launch { toast.show("This moment is no longer available") }
                                else onOpenMoment(if (outgoing) state.chat?.peerId else null, q.momentId)
                            }) { onSelect(m) }
                        }
                        is Message.Payment -> PaymentMessage(m, onOpen = { onOpenTransfer(m.transferId) }, moment = !m.outgoing && index == state.messages.lastIndex && now - m.orderAt < MoneyMomentMillis, onThanks = { onQuickReply("Asante!") }, chama = chama, fiat = state.fiat, groupName = state.chat?.title?.takeIf { state.groupMoney?.hasWallet == true },
                            react = reactUi(m.messageId), transfer = state.paymentStates[m.transferId], pendingPayment = state.paymentPending(m, now),
                            // Only a payment made just now is read out (not old cards a new phone's sync brings back).
                            announce = m.outgoing && m.localId == lastOutgoingPayment && now - m.orderAt < AnnounceWithinMillis,
                            // Only my own payment from here comes back to the card; a payout is paid again from its request.
                            onRetry = if (m.outgoing && payable && m.note?.startsWith("Paid from") != true) ({ retryCard(m) }) else null)
                        is Message.Deleted -> MessageColumn(m.outgoing) { ChatBubble("This message was deleted", m.outgoing, quiet = true) }
                        is Message.Unreadable -> MessageColumn(false) { ChatBubble("A message from ${m.senderName} this phone can't show", false, quiet = true) }
                        is Message.System -> SystemLine(m.text)
                        is Message.Call -> CallRow(m, onCallBack = onCall?.let { call -> { call(m.video) } })
                        is Message.Media -> {
                            val firstOfRun = !(previous is Message.Media && previous.senderId == m.senderId && previous.outgoing == m.outgoing) &&
                                !(previous is Message.Text && previous.senderId == m.senderId && previous.outgoing == m.outgoing)
                            MediaRow(
                                m, state.isGroup, firstOfRun, online = state.online, playing = state.playing == m.localId,
                                playingSince = state.playingSince.takeIf { state.playing == m.localId }, load = loadMedia,
                                onPlay = { onPlay(m) }, onOptions = { selectedMedia = m }, onOpenImage = { bytes -> viewing = bytes },
                                avatarUrl = state.chat?.members?.firstOrNull { it.userId == m.senderId }?.avatarUrl,
                                onOpenLocation = { onOpenLocation(m) }, onStopLive = { media?.onStopLive?.invoke(m) }, now = liveNow,
                                onPlayVideo = { media?.onPlayVideo?.invoke(m) }, videoLoading = state.videoLoading == m.localId,
                                react = reactUi(m.messageId, options = if (m.state == DeliveryState.Failed || m.outgoing) ({ selectedMedia = m }) else null),
                            )
                        }
                    }
                    }
                }
            }
            // Back to the latest, once the person has scrolled up through older messages.
            androidx.compose.animation.AnimatedVisibility(
                awayFromLatest,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = CircasoSpacing.s4, bottom = CircasoSpacing.s3),
                enter = fadeIn(tween(CircasoMotion.QuickMs)) + androidx.compose.animation.scaleIn(tween(CircasoMotion.QuickMs)),
                exit = fadeOut(tween(CircasoMotion.QuickMs)) + androidx.compose.animation.scaleOut(tween(CircasoMotion.QuickMs)),
            ) {
                Box(Modifier.shadow(8.dp, com.circaso.designsystem.theme.CircasoShapes.circle).clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(c.surface)) {
                    CircasoIconButton(CircasoIcon.ArrowDown, "Jump to the latest message", { listScope.launch { listState.animateScrollToItem(0) } })
                }
            }
            }
            ComposerBar(
                value = state.draft,
                onValueChange = { text ->
                    val stripped = textWithoutAshaCommand(text)
                    val amount = if (payChip == null) amountShortcut(text) else null
                    when {
                        payChip != null -> {
                            onDraft(text)
                            val (tag, note) = splitAssetTag(text)
                            quickPay?.onAssetTag?.invoke(tag)
                            quickPay?.onNote?.invoke(note)
                        }
                        stripped != null && onAsha != null -> { onDraft(stripped); onAsha() }
                        amount != null && payable && quickPay != null -> {
                            chipHaptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            payChip = "#$amount"; onDraft(""); quickPay.onAmount(amount); quickPay.onNote("")
                        }
                        amount != null && onPay != null && !state.isGroup && !state.isSupport -> { onDraft(""); onPay(amount) }
                        amount != null && onContribute != null && state.canContribute -> { onDraft(""); onContribute(amount) }
                        else -> onDraft(text)
                    }
                },
                placeholder = if ((chama != null || state.groupMoney != null) && state.canContribute) "Message, or #amount to add" else if (!state.isGroup && !state.isSupport && onPay != null) "Message, or #amount to send" else "Message",
                onAttach = if (media != null) ({ attaching = true }) else null,
                focusRequester = composerFocus,
                sendLabel = if (paying) quickPay?.state?.let { q -> payLabel(q) + (q.payee?.displayName?.takeIf { !q.toGroup && q.failure == null && q.step != com.circaso.feature.wallet.presentation.PayStep.Signing }?.let { " to $it" } ?: "") } ?: "Send" else "Send",
                showSend = paying || media == null || state.draft.isNotBlank(),
                trailing = if (payChip == null && media != null && state.draft.isBlank()) ({ VoiceButton(state.recording, media.onVoiceStart, media.onVoiceEnd) }) else null,
                canSend = if (paying) quickPay?.state?.canPay == true else state.canSend,
                // The composer's arrow is the card's Pay: straight to the PIN, nothing in between.
                onSend = if (paying) ({ quickPay?.onPay?.invoke(); Unit }) else onSend,
                chip = payChip,
                onChipEdit = {
                    // A retried card's chip reads its coin amount ("#3.875968 USDC"); edit it in the person's currency.
                    val back = payChip.orEmpty().let { chip -> if (' ' in chip) quickPay?.state?.fiatText?.substringAfter(' ')?.replace(",", "")?.removeSuffix(".00")?.let { "#$it" } ?: chip.substringBefore(' ') else chip }
                    payChip = null; quickPay?.onReset?.invoke(); onDraft(back + if (state.draft.isNotEmpty()) " " + state.draft else "")
                },
                floating = {
                    AnimatedVisibility(
                        coinChoices.isNotEmpty(),
                        enter = if (CircasoTheme.reduceMotion) fadeIn(tween(CircasoMotion.QuickMs)) else fadeIn(tween(CircasoMotion.QuickMs)) + androidx.compose.animation.expandVertically(tween(CircasoMotion.QuickMs)),
                        exit = if (CircasoTheme.reduceMotion) fadeOut(tween(CircasoMotion.QuickMs)) else fadeOut(tween(CircasoMotion.QuickMs)) + androidx.compose.animation.shrinkVertically(tween(CircasoMotion.QuickMs)),
                    ) {
                        CoinSuggestions(coinChoices) { coin ->
                            chipHaptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            val text = state.draft.replace(Regex("""@[A-Za-z]*$"""), "@${coin.symbol.lowercase()} ")
                            onDraft(text)
                            val (tag, note) = splitAssetTag(text)
                            quickPay?.onAssetTag?.invoke(tag)
                            quickPay?.onNote?.invoke(note)
                        }
                    }
                    // The chat's money card (C3 grown into the whole review): floating over the thread, Pay opens the PIN.
                    AnimatedVisibility(
                        paying && quickPay != null,
                        enter = if (CircasoTheme.reduceMotion) fadeIn(tween(CircasoMotion.QuickMs)) else fadeIn(tween(CircasoMotion.QuickMs)) + androidx.compose.animation.slideInVertically(tween(CircasoMotion.QuickMs)) { it / 2 },
                        exit = fadeOut(tween(CircasoMotion.QuickMs / 2)),
                    ) { quickPay?.let { q -> ChatMoneyCard(q.state, q.onPay, { q.onChoosing(true) }, cardMotion, maxHeight = cardMax(), onRetryQuote = q.onRetryQuote, onUseMax = q.onUseMax) } }
                    // An approved request's Pay (D5/GW9): the same card, the amount and payee locked by the approval.
                    AnimatedVisibility(
                        payout != null,
                        enter = if (CircasoTheme.reduceMotion) fadeIn(tween(CircasoMotion.QuickMs)) else fadeIn(tween(CircasoMotion.QuickMs)) + androidx.compose.animation.slideInVertically(tween(CircasoMotion.QuickMs)) { it / 2 },
                        exit = fadeOut(tween(CircasoMotion.QuickMs / 2)),
                    ) { payout?.let { q -> ChatMoneyCard(q.state, q.onPay, { q.onChoosing(true) }, cardMotion, onClose = onPayoutClose, maxHeight = cardMax(), onRetryQuote = q.onRetryQuote, onUseMax = q.onUseMax) } }
                },
                above = {
                    // Support never takes money (so nobody pretending to be Circaso can ask for it): say so the moment
                    // someone types an amount here, instead of quietly sending it as text.
                    AnimatedVisibility(
                        state.isSupport && Regex("""^#\d""").containsMatchIn(state.draft),
                        enter = fadeIn(tween(CircasoMotion.QuickMs)), exit = fadeOut(tween(CircasoMotion.QuickMs)),
                    ) {
                        BasicText(
                            "Circaso support never asks for money, so payments are off in this chat.",
                            style = t.footnote.copy(color = c.inkSecondary),
                            modifier = Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2),
                        )
                    }
                    // Grows in and out rather than jumping the thread; the last start time stays through the exit.
                    var stripSince by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0L) }
                    state.recordingSince?.let { stripSince = it }
                    val still = CircasoTheme.reduceMotion
                    AnimatedVisibility(
                        state.recording,
                        enter = if (still) fadeIn(tween(CircasoMotion.BaseMs)) else fadeIn(tween(CircasoMotion.BaseMs)) + androidx.compose.animation.expandVertically(tween(CircasoMotion.BaseMs)),
                        exit = if (still) fadeOut(tween(CircasoMotion.QuickMs)) else fadeOut(tween(CircasoMotion.QuickMs)) + androidx.compose.animation.shrinkVertically(tween(CircasoMotion.QuickMs)),
                    ) { RecordingStrip(stripSince) }
                    if (!state.online) {
                        BasicText(
                            "You're offline. Messages send when you reconnect.",
                            style = t.footnote.copy(color = c.inkSecondary),
                            modifier = Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2),
                        )
                    }
                },
            )
        }
    }
    }
}

/** A text message as the design draws it: the bubble, then its time (or state) under it on the sender's side. */
@Composable
private fun TextMessage(m: Message.Text, group: Boolean, firstOfRun: Boolean, peerName: String? = null, onOpenMoment: (com.circaso.core.crypto.MomentQuote, Boolean) -> Unit = { _, _ -> }, react: ReactUi? = null, onTap: () -> Unit) {
    val c = CircasoTheme.colors
    val waiting = m.state == DeliveryState.Waiting || m.state == DeliveryState.Sending
    val failed = m.state == DeliveryState.Failed
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        // In a group, who said it: their avatar beside the first bubble of a run (design `.cx-msg`), an indent after.
        if (group && !m.outgoing) {
            if (firstOfRun) Avatar(m.senderName, Modifier.padding(end = CircasoSpacing.s2, bottom = 18.dp), size = CircasoSizes.avatarSm)
            else Spacer(Modifier.width(CircasoSizes.avatarSm + CircasoSpacing.s2))
        }
        MessageColumn(m.outgoing, Modifier.weight(1f, fill = false)) {
            val bubble: @Composable () -> Unit = {
                ChatBubble(m.text, m.outgoing, faded = waiting, onClick = if (failed || m.outgoing) onTap else null, onLongClick = react?.onOpen, above = if ((group && !m.outgoing && firstOfRun) || m.momentQuote != null) ({
                    if (group && !m.outgoing && firstOfRun) BasicText(m.senderName, style = CircasoTheme.typography.caption1.copy(color = c.inkSecondary), modifier = Modifier.padding(bottom = 2.dp))
                    m.momentQuote?.let { q -> MomentQuoteCard(q, m.outgoing, if (m.outgoing) peerName else m.senderName) { onOpenMoment(q, m.outgoing) } }
                }) else null)
            }
            if (react != null) Reacted(react, m.outgoing, bubble) else bubble()
            when {
                failed -> BubbleMeta("Not sent · tap for options", icon = CircasoIcon.Close, color = c.critical)
                waiting -> BubbleMeta("Waiting to send", icon = CircasoIcon.Clock)
                else -> {
                    val receipt = if (m.outgoing) when (m.state) { DeliveryState.Read -> " · Read"; DeliveryState.Delivered -> " · Delivered"; else -> "" } else ""
                    BubbleMeta(formatLocalTime(m.orderAt, LocalTimeStyle.Clock) + receipt)
                }
            }
        }
    }
}

private fun failureText(code: String?): String = when (code) {
    "outbox.stale" -> "This waited too long to send. Send it again?"
    null -> ErrorMessages.SomethingWrong
    else -> ErrorMessages.forError(AppError.Api(code, 400, null, null, emptyMap()))
}

/** Design C3: `#500` followed by a space in the composer opens the Send sheet with 500 filled in. */
internal fun amountShortcut(text: String): String? {
    val m = Regex("^#(\\d+(?:\\.\\d{1,2})?)\\s$").find(text) ?: return null
    return m.groupValues[1]
}

/** A payment card in the thread (designs C3–C7): the money colour for money received, `fill` for money sent. */
@Composable
private fun PaymentMessage(
    m: Message.Payment, onOpen: () -> Unit, moment: Boolean = false, onThanks: () -> Unit = {}, chama: Chama? = null, fiat: FiatValue? = null, react: ReactUi? = null, groupName: String? = null,
    /** The payment itself as this phone knows it (null: not read yet, as before). */
    transfer: com.circaso.feature.wallet.domain.TransferState? = null,
    /** Not sent: put the same payment back on the chat's money card. */
    onRetry: (() -> Unit)? = null,
    /** On its way, or too new for this phone to have read it: never shown as arrived yet. */
    pendingPayment: Boolean = transfer?.inFlight == true,
    /** My latest card: its head is read out as it changes (Sending → Sent). */
    announce: Boolean = false,
) {
    val waiting = m.state == DeliveryState.Waiting || m.state == DeliveryState.Sending
    val failed = m.state == DeliveryState.Failed
    // The card says Sent (or Paid, or Received) only once the chain has it; until then Sending (Pasaka, 2026-11-04).
    val inFlight = pendingPayment
    val notSent = transfer?.notSent == true
    val confirmed = transfer == com.circaso.feature.wallet.domain.TransferState.Confirmed
    val usdc = MinorUnits.coinText(m.amountMinor, m.asset)
    // Design D2/D3: in a group with a goal, money to the treasurer is a contribution and money from them is a payout;
    // the card reads it from the group's own copy, so the encrypted payload needs no goal field.
    val treasurer = chama?.goal?.treasurerUserId
    val kind = when {
        // Group wallets (GW18): the card's own note says which way the money went; a group without a target names the group.
        groupName != null -> if (m.note?.startsWith("Paid from") == true) "Paid from $groupName" else "Contribution"
        chama == null -> null
        m.senderId == treasurer -> "Paid from the group"
        else -> "Contribution"
    }
    val fiatAmount = fiat?.let { com.circaso.feature.chama.presentation.amountText(m.amountMinor, it) }
    // Only dollar coins have a rate; anything else shows as itself.
    val shownFiat = fiatAmount?.takeIf { MinorUnits.isDollar(m.asset) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start) {
        MessageColumn(m.outgoing, Modifier.weight(1f, fill = false)) {
            val card: @Composable () -> Unit = { MoneyCard(
                head = when {
                    notSent -> if (m.outgoing) "Not sent" else "Didn’t go through"
                    inFlight -> if (m.outgoing) "Sending" else "On its way"
                    else -> kind ?: if (m.outgoing) "Sent" else "Received"
                },
                // The person's currency large (You → Currency), the coin amount in the foot (design C4: "KES 500", "3.88 USDC").
                // No + or −: "Sent" and "Received" already say which way it went (the user, 2026-10-31).
                amount = shownFiat ?: usdc,
                note = if (kind == "Contribution" && (chama != null || groupName != null)) "${if (m.outgoing) "You" else m.senderName.substringBefore(' ')} added to ${chama?.goal?.name ?: groupName}" else m.note,
                foot = (if (shownFiat != null) "$usdc · " else "") + formatLocalTime(m.orderAt, LocalTimeStyle.Clock) + when { failed -> " · card not sent"; waiting -> " · sending card"; else -> "" },
                // Money on chain is never dimmed or greyed because its encrypted card is still going out or didn't
                // (a card the wallet added on a new phone, then sent): the foot says that; the look follows the money.
                outgoing = m.outgoing, pending = if (confirmed) inFlight else waiting || inFlight, failed = if (confirmed) notSent else failed || notSent,
                // A card that can't take a reaction yet (no message id: the wallet added it) opens its details on a long press.
                onClick = onOpen, onLongClick = react?.onOpen ?: onOpen, symbol = m.asset, announce = announce,
            ) }
            if (react != null) Reacted(react, m.outgoing, card) else card()
            if (notSent) NotSentLine(m.outgoing, onRetry)
            // Design C6: the money moment offers a thank-you and the details while the card is the latest thing said, once it has arrived.
            if (moment && !inFlight && !notSent) Row(Modifier.padding(top = CircasoSpacing.s2), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                com.circaso.designsystem.components.SuggestionChip("Say Thanks", onThanks)
                com.circaso.designsystem.components.SuggestionChip("View Details", onOpen)
            }
        }
    }
}

/** Say Thanks / View Details show under money received in the last day only (a new phone restores older cards). */
private const val MoneyMomentMillis = 24L * 60 * 60 * 1000

/** A Sending → Sent card is read out only for a payment made in the last few minutes. */
private const val AnnounceWithinMillis = 5L * 60 * 1000

/** "Missed voice call", "Video call", "No answer", "Declined": how a call reads in the chat and in the list. */
internal fun callLabel(m: Message.Call): String {
    val kind = if (m.video) "video call" else "voice call"
    return when (m.outcome) {
        com.circaso.feature.messaging.domain.CallOutcome.Completed -> kind.replaceFirstChar { it.uppercase() }
        com.circaso.feature.messaging.domain.CallOutcome.Missed -> "Missed $kind"
        com.circaso.feature.messaging.domain.CallOutcome.Declined -> if (m.outgoing) "${kind.replaceFirstChar { it.uppercase() }} · declined" else "Declined $kind"
        com.circaso.feature.messaging.domain.CallOutcome.Cancelled -> "Cancelled $kind"
        com.circaso.feature.messaging.domain.CallOutcome.NoAnswer -> "${kind.replaceFirstChar { it.uppercase() }} · no answer"
        com.circaso.feature.messaging.domain.CallOutcome.Failed -> "${kind.replaceFirstChar { it.uppercase() }} · couldn't connect"
    }
}

/** "4 min", "45 sec", "1 hr 2 min". */
private fun talkTime(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600} hr ${(seconds % 3600) / 60} min"
    seconds >= 60 -> "${seconds / 60} min"
    else -> "$seconds sec"
}

/**
 * A call in the chat, WhatsApp-style: on the caller's side, the kind of call with its talking time or how it ended,
 * and the time it happened; missed in the critical colour. Tapping it calls back.
 */
@Composable
private fun CallRow(m: Message.Call, onCallBack: (() -> Unit)?) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val missed = m.outcome == com.circaso.feature.messaging.domain.CallOutcome.Missed
    val detail = buildString {
        if (m.outcome == com.circaso.feature.messaging.domain.CallOutcome.Completed && m.durationSeconds > 0) append(talkTime(m.durationSeconds)).append(" · ")
        append(formatLocalTime(m.orderAt, LocalTimeStyle.Clock))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start) {
        Row(
            Modifier
                .clip(com.circaso.designsystem.theme.CircasoShapes.lg)
                .background(if (m.outgoing) c.fillStrong else c.fill)
                .then(if (onCallBack != null) Modifier.pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = onCallBack) else Modifier)
                .semantics(mergeDescendants = true) { contentDescription = "${callLabel(m)}, $detail" + if (onCallBack != null) ". Call back" else "" }
                .padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2),
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (missed) c.critical.copy(alpha = 0.12f) else c.surface), contentAlignment = Alignment.Center) {
                CircasoIconImage(if (m.video) CircasoIcon.Video else CircasoIcon.Phone, if (missed) c.critical else c.ink, Modifier.size(18.dp))
            }
            Column {
                BasicText(callLabel(m), style = t.callout.copy(color = if (missed) c.critical else c.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                BasicText(detail, style = t.footnote.copy(color = c.inkSecondary))
            }
        }
    }
}

/** What the thread can do with media; the screen's own slice of ThreadMedia. */
class ThreadMediaActions(
    val canShareLocation: Boolean,
    val onPhoto: () -> Unit,
    val onLocation: () -> Unit,
    val onVoiceStart: () -> Unit,
    val onVoiceEnd: (send: Boolean) -> Unit,
    val load: suspend (com.circaso.feature.messaging.data.repository.MediaDetails) -> com.circaso.feature.messaging.data.repository.MediaLoad,
    val mapPicture: MapPictureLoader? = null,
    val onShareLive: (durationMs: Long) -> Unit = {},
    val onGalleryOpen: () -> Unit = {},
    val onPlayVideo: (Message.Media) -> Unit = {},
    val onCloseVideo: () -> Unit = {},
    val onGalleryToggle: (com.circaso.core.platform.GalleryItem) -> Unit = {},
    val onGallerySend: (caption: String?) -> Unit = {},
    val galleryThumb: (suspend (com.circaso.core.platform.GalleryItem, Int) -> ByteArray?)? = null,
    val onStopLive: (Message.Media) -> Unit = {},
    val onPlay: (Message.Media) -> Unit,
    val onRetry: (Message.Media) -> Unit,
    val onConfirmLocation: () -> Unit,
    val onCancelLocation: () -> Unit,
    val onPhotoCaption: (String) -> Unit,
    val onConfirmPhoto: () -> Unit,
    val onCancelPhoto: () -> Unit,
)

/** "Photo", "Voice message (0:12)", "Location": how media reads in the chat list. */
internal fun mediaLabel(m: Message.Media): String = when {
    m.isImage -> m.details.caption?.let { "Photo · $it" } ?: "Photo"
    m.isVoice -> "Voice message" + (m.details.durationMs?.let { " (${clock(it / 1000)})" } ?: "")
    m.isVideo -> (m.details.caption?.let { "Video · $it" } ?: "Video") + (m.details.durationMs?.let { " (${clock(it / 1000)})" } ?: "")
    else -> m.details.label?.let { "Location · $it" } ?: "Location"
}

private fun clock(seconds: Long) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

@Composable
private fun AttachOption(icon: CircasoIcon, title: String, sub: String, onClick: () -> Unit) {
    com.circaso.designsystem.components.ListRow(title, sub = sub, leading = {
        Box(Modifier.size(MediaIconSize).clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(CircasoTheme.colors.fill), contentAlignment = Alignment.Center) {
            CircasoIconImage(icon, CircasoTheme.colors.ink, Modifier.size(20.dp))
        }
    }, onClick = onClick)
}

/**
 * The attach sheet's grid of recent photos and videos (WhatsApp-style): three across, a hollow ring that becomes a
 * numbered tick when chosen, and a video's length on its tile.
 */
@Composable
private fun GalleryGrid(
    items: List<com.circaso.core.platform.GalleryItem>,
    selected: List<String>,
    thumb: suspend (com.circaso.core.platform.GalleryItem, Int) -> ByteArray?,
    onToggle: (com.circaso.core.platform.GalleryItem) -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { 128.dp.roundToPx() }
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
        modifier = Modifier.fillMaxWidth().height(360.dp).clip(com.circaso.designsystem.theme.CircasoShapes.md),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items.size, key = { items[it].id }) { i ->
            val item = items[i]
            val order = selected.indexOf(item.id)
            val bytes by androidx.compose.runtime.produceState<ByteArray?>(null, item.id) { value = thumb(item, px) }
            Box(
                Modifier.aspectRatio(1f).background(c.fill)
                    .pressable(role = androidx.compose.ui.semantics.Role.Checkbox, pressedScale = 0.96f, onClick = { onToggle(item) })
                    .semantics {
                        contentDescription = if (item.video) "Video" else "Photo"
                        toggleableState = if (order >= 0) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
                        if (order >= 0) stateDescription = "Selected ${order + 1}"
                    },
            ) {
                bytes?.let { coil3.compose.AsyncImage(model = it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (item.video) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, c.scrim))))
                    Row(Modifier.align(Alignment.BottomStart).padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        CircasoIconImage(CircasoIcon.Play, androidx.compose.ui.graphics.Color.White, Modifier.size(12.dp))
                        item.durationMs?.let { BasicText(clock(it / 1000), style = t.caption2.copy(color = androidx.compose.ui.graphics.Color.White, fontFeatureSettings = "tnum")) }
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(com.circaso.designsystem.theme.CircasoShapes.circle)
                        .background(if (order >= 0) c.action else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.2f))
                        .border(1.5.dp, androidx.compose.ui.graphics.Color.White, com.circaso.designsystem.theme.CircasoShapes.circle),
                    contentAlignment = Alignment.Center,
                ) { if (order >= 0) BasicText("${order + 1}", style = t.caption1.copy(color = c.onAction, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) }
            }
        }
    }
}

/** A voice note's drawn shape, repeated to fill the bubble's width. */
private val VoiceShape = listOf(6, 12, 18, 10, 22, 14, 8, 16, 20, 12, 6, 14, 18, 10, 8, 12)

/** The chat's in-line payment (design C3): the pay screen's view model, driven from the composer. */
class QuickPay(
    val state: com.circaso.feature.wallet.presentation.PayUiState,
    val onAmount: (String) -> Unit,
    val onNote: (String) -> Unit,
    val onPay: () -> Unit,
    val onReset: () -> Unit,
    val onErrorShown: () -> Unit,
    val onAssetTag: (String?) -> Unit = {},
    /** GW10: open or close Paying from, and pick a wallet. */
    val onChoosing: (Boolean) -> Unit = {},
    val onPayFrom: (com.circaso.feature.wallet.presentation.NamedWallet) -> Unit = {},
    /** A Not sent card's Try Again: exactly its coin amount back on the money card. */
    val onRetryExact: (amountMinor: String, asset: String, note: String?) -> Unit = { _, _, _ -> },
    /** GW13d's Try Again on the fee. */
    val onRetryQuote: () -> Unit = {},
    /** GW14 / GW20's "… Instead". */
    val onUseMax: () -> Unit = {},
)

/** The amount while it is still being typed: "#5", "#12.", "#12.5" (no space yet). */
internal fun typingAmount(draft: String): String? =
    Regex("""^#(\d+(?:\.\d{0,2})?)(?:\s.*)?$""").find(draft)?.groupValues?.get(1)?.trimEnd('.')?.takeIf { it.isNotEmpty() && it.any { c -> c != '0' && c != '.' } }

/** What follows the amount in "#500 @usdc for lunch" (the coin and the note), while it is still plain text. */
internal fun afterTypedAmount(draft: String): String = draft.substringAfter(' ', "").trim()

/** The coin being typed after "@" at the end of the note ("for lunch @us" → "us", "@" → ""); null when none is. */
internal fun coinQuery(draft: String): String? = Regex("""(?:^|\s)@([A-Za-z]*)$""").find(draft)?.groupValues?.get(1)

/** One coin the wallet holds, over all its networks. */
internal data class HeldCoin(val symbol: String, val amount: String, val worth: Double)

/** Every coin with a balance, the most valuable first (by its local value, else by its amount). */
internal fun heldCoins(wallet: com.circaso.feature.wallet.domain.Wallet?): List<HeldCoin> {
    val assets = wallet?.chains.orEmpty().flatMap { it.assets }.filter { it.amountMinor.trimStart('0').isNotEmpty() }
    return assets.groupBy { it.symbol.uppercase() }.map { (symbol, list) ->
        // Summed in minor units (never floating point), shown with the coin's decimals.
        val decimals = list.first().decimals
        val minor = list.fold("0") { sum, it -> MinorUnits.add(sum, it.amountMinor.ifEmpty { "0" }) }
        val worth = list.sumOf { it.fiatAmount?.toDoubleOrNull() ?: 0.0 }
        val total = list.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
        HeldCoin(symbol, MinorUnits.toDisplay(minor, decimals), if (worth > 0) worth else total)
    }.sortedByDescending { it.worth }
}

/** The "@" picker over the composer: up to four held coins as chips; they settle in and out as the list narrows. */
@Composable
private fun CoinSuggestions(coins: List<HeldCoin>, onPick: (HeldCoin) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().padding(bottom = CircasoSpacing.s2),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = CircasoSpacing.s3),
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        items(coins, key = { it.symbol }) { coin ->
            Row(
                Modifier.animateItem(fadeInSpec = tween(CircasoMotion.QuickMs), fadeOutSpec = tween(CircasoMotion.QuickMs), placementSpec = if (CircasoTheme.reduceMotion) null else tween(CircasoMotion.BaseMs))
                    .then(Modifier.shadowPill())
                    .clip(com.circaso.designsystem.theme.CircasoShapes.pill).background(c.surface)
                    .pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = { onPick(coin) })
                    .semantics(mergeDescendants = true) { contentDescription = "Pay in ${coin.symbol}, you hold ${coin.amount}" }
                    .padding(start = 6.dp, end = CircasoSpacing.s3, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
            ) {
                com.circaso.designsystem.components.CoinDisc(coin.symbol, Modifier.size(28.dp))
                Column {
                    BasicText(coin.symbol, style = t.subhead.copy(color = c.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                    BasicText(coin.amount, style = t.caption2.copy(color = c.inkSecondary))
                }
            }
        }
    }
}

/** "#0.01 @eth for gas": the asset tag and the note without it. */
internal fun splitAssetTag(text: String): Pair<String?, String> {
    val m = Regex("""(?:^|\s)@([A-Za-z]{2,6})(?=\s|$)""").find(text) ?: return null to text
    return m.groupValues[1] to (text.removeRange(m.range)).trim()
}

/** Media failures that sending again can't fix. */
private val TerminalMediaErrors = setOf("messaging.attachment_too_large")

private val MediaBubbleWidth = 240.dp
private val MapTileHeight = 120.dp
private val MediaIconSize = 40.dp

/** While a voice note records: a pulsing dot, the running time, and how to send or drop it. */
@Composable
private fun RecordingStrip(since: Long) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    var seconds by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0L) }
    LaunchedEffect(since) { while (true) { seconds = (com.circaso.core.sync.nowMillis() - since) / 1000; kotlinx.coroutines.delay(250) } }
    val reduce = CircasoTheme.reduceMotion
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "rec")
    val alpha by pulse.animateFloat(1f, if (reduce) 1f else 0.4f, androidx.compose.animation.core.infiniteRepeatable(tween(CircasoMotion.MomentMs), androidx.compose.animation.core.RepeatMode.Reverse), label = "dot")
    Row(
        // Announced once as it appears; the ticking timer is left out so a screen reader doesn't repeat it every second.
        Modifier.fillMaxWidth().padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2)
            .clearAndSetSemantics {
                contentDescription = "Recording a voice message. Release to send, slide off to cancel."
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        Box(Modifier.size(10.dp).graphicsLayer { this.alpha = alpha }.clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(c.critical))
        BasicText(clock(seconds), style = t.callout.copy(color = c.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontFeatureSettings = "tnum"))
        BasicText("Release to send · Slide off to cancel", style = t.footnote.copy(color = c.inkSecondary))
    }
}

/**
 * Hold to record, release to send, slide off the button to drop it: WhatsApp's voice note button. A screen reader
 * double-tap toggles recording instead (holding isn't available there).
 */
@Composable
private fun VoiceButton(recording: Boolean, onStart: () -> Unit, onEnd: (send: Boolean) -> Unit) {
    val c = CircasoTheme.colors
    val start by androidx.compose.runtime.rememberUpdatedState(onStart)
    val end by androidx.compose.runtime.rememberUpdatedState(onEnd)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scale by androidx.compose.animation.core.animateFloatAsState(if (recording && !CircasoTheme.reduceMotion) 1.15f else 1f, tween(CircasoMotion.QuickMs), label = "mic")
    // Sized like Attach and Send so the row lines up; Compose still widens the touch area to the 48 dp minimum.
    Box(
        Modifier.size(com.circaso.designsystem.theme.CircasoSizes.iconButton).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(if (recording) c.critical else c.fill)
            .semantics {
                role = androidx.compose.ui.semantics.Role.Button
                contentDescription = if (recording) "Recording. Double-tap to send" else "Record a voice message"
                onClick(label = if (recording) "Send voice message" else "Start recording") { if (recording) end(true) else start(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    start()
                    // False when the finger slides off the button before lifting: the note is dropped.
                    val released = tryAwaitRelease()
                    // Sliding off drops the note: a tick says so, since the finger is already elsewhere.
                    if (!released) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    end(released)
                })
            },
        contentAlignment = Alignment.Center,
    ) { CircasoIconImage(CircasoIcon.Mic, if (recording) c.onAction else c.ink, Modifier.size(18.dp)) }
}

private sealed interface PhotoLoad {
    data object Loading : PhotoLoad
    class Loaded(val bytes: ByteArray) : PhotoLoad
    /** Why it isn't showing; only [retry] failures take a tap. */
    class Problem(val text: String, val retry: Boolean) : PhotoLoad
}

private fun photoProblem(result: com.circaso.feature.messaging.data.repository.MediaLoad): PhotoLoad = when (result) {
    is com.circaso.feature.messaging.data.repository.MediaLoad.Loaded -> PhotoLoad.Loaded(result.bytes)
    com.circaso.feature.messaging.data.repository.MediaLoad.Offline -> PhotoLoad.Problem("Photo loads when you're back online", retry = false)
    com.circaso.feature.messaging.data.repository.MediaLoad.Gone -> PhotoLoad.Problem("No longer available", retry = false)
    com.circaso.feature.messaging.data.repository.MediaLoad.Damaged -> PhotoLoad.Problem("This photo couldn't be opened safely", retry = false)
    com.circaso.feature.messaging.data.repository.MediaLoad.Failed -> PhotoLoad.Problem("Couldn't load photo · Tap to try again", retry = true)
}

/**
 * A photo, voice note or location in the chat (WhatsApp-style), shaped and coloured like a text bubble, with its time
 * and state under it. Photos load their decrypted bytes on demand; a failed load says so and retries on tap or when
 * the phone comes back online. A failed send, or a long press on a sent one, opens the message sheet ([onOptions]).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MediaRow(
    m: Message.Media, group: Boolean, firstOfRun: Boolean, online: Boolean, playing: Boolean, playingSince: Long?,
    load: suspend (com.circaso.feature.messaging.data.repository.MediaDetails) -> com.circaso.feature.messaging.data.repository.MediaLoad,
    onPlay: () -> Unit, onOptions: () -> Unit, onOpenImage: (ByteArray) -> Unit,
    avatarUrl: String? = null, onOpenLocation: () -> Unit = {}, onStopLive: () -> Unit = {}, now: Long = 0L,
    onPlayVideo: () -> Unit = {}, videoLoading: Boolean = false,
    react: ReactUi? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val failed = m.state == DeliveryState.Failed
    val waiting = m.state == DeliveryState.Waiting
    val sending = m.state == DeliveryState.Sending
    val ink = if (m.outgoing) c.onAction else c.ink
    val inkSoft = if (m.outgoing) c.onAction.copy(alpha = 0.75f) else c.inkSecondary
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (group && !m.outgoing) {
            if (firstOfRun) Avatar(m.senderName, Modifier.padding(end = CircasoSpacing.s2, bottom = 18.dp), size = CircasoSizes.avatarSm)
            else Spacer(Modifier.width(CircasoSizes.avatarSm + CircasoSpacing.s2))
        }
        MessageColumn(m.outgoing, Modifier.weight(1f, fill = false)) {
            if (group && !m.outgoing && firstOfRun) BasicText(m.senderName, style = t.caption1.copy(color = c.inkSecondary), modifier = Modifier.padding(bottom = 2.dp))
            val bubble = Modifier.width(MediaBubbleWidth).alpha(if (waiting || sending) 0.6f else 1f).clip(com.circaso.designsystem.components.bubbleShape(m.outgoing))
                .background(if (m.outgoing) c.action else c.fill)
            // A long press reacts (the bubble's ⋯ has the options); before it is sent it opens the options as before.
            val options = react?.onOpen ?: if (failed || m.outgoing) onOptions else null
            Reacted(react ?: ReactUi(emptyList(), null, false, null, {}, {}), m.outgoing, content = {
            when {
                m.isImage -> {
                    var photo by androidx.compose.runtime.remember(m.localId) { androidx.compose.runtime.mutableStateOf<PhotoLoad>(PhotoLoad.Loading) }
                    var attempt by androidx.compose.runtime.remember(m.localId) { androidx.compose.runtime.mutableStateOf(0) }
                    // Keyed on the row, not the attachment id: the id arriving after upload must not blank a shown photo.
                    LaunchedEffect(m.localId, attempt, online, m.details.attachmentId) {
                        if (photo is PhotoLoad.Loaded) return@LaunchedEffect
                        photo = PhotoLoad.Loading
                        photo = photoProblem(load(m.details))
                    }
                    val ratio = m.details.width?.let { w -> m.details.height?.takeIf { it > 0 }?.let { h -> (w.toFloat() / h).coerceIn(0.6f, 1.6f) } } ?: 1f
                    Column(
                        bubble.padding(3.dp).combinedClickable(
                            onClickLabel = "Open photo",
                            onLongClick = options,
                            onClick = {
                                when (val p = photo) {
                                    is PhotoLoad.Loaded -> if (failed) onOptions() else onOpenImage(p.bytes)
                                    is PhotoLoad.Problem -> if (failed) onOptions() else if (p.retry) attempt++
                                    PhotoLoad.Loading -> {}
                                }
                            },
                        ),
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(com.circaso.designsystem.components.bubbleShape(m.outgoing)).background(c.fillStrong), contentAlignment = Alignment.Center) {
                            when (val p = photo) {
                                is PhotoLoad.Loaded -> coil3.compose.AsyncImage(model = photoRequest(p.bytes, m), contentDescription = m.details.caption ?: "Photo from ${if (m.outgoing) "you" else m.senderName}", contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                PhotoLoad.Loading -> com.circaso.designsystem.components.Spinner(c.inkSecondary, Modifier.size(24.dp))
                                is PhotoLoad.Problem -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), modifier = Modifier.padding(CircasoSpacing.s3)) {
                                    CircasoIconImage(CircasoIcon.Photo, c.inkSecondary, Modifier.size(28.dp))
                                    BasicText(
                                        p.text,
                                        style = t.footnote.copy(color = c.inkSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                                    )
                                }
                            }
                            if (sending && photo is PhotoLoad.Loaded) Box(Modifier.matchParentSize().background(c.scrim), contentAlignment = Alignment.Center) { com.circaso.designsystem.components.Spinner(c.onAction, Modifier.size(24.dp)) }
                        }
                        m.details.caption?.let { BasicText(it, style = t.body.copy(color = ink), modifier = Modifier.padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2)) }
                    }
                }
                m.isVideo -> {
                    val ratio = m.details.width?.let { w -> m.details.height?.takeIf { it > 0 }?.let { h -> (w.toFloat() / h).coerceIn(0.6f, 1.6f) } } ?: (16f / 9f)
                    val thumb = androidx.compose.runtime.remember(m.details.thumb) {
                        m.details.thumb?.let { runCatching { @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class) kotlin.io.encoding.Base64.decode(it) }.getOrNull() }
                    }
                    Column(
                        bubble.padding(3.dp).combinedClickable(
                            onClickLabel = "Play video", onLongClick = options,
                            onClick = { if (failed) onOptions() else onPlayVideo() },
                        ).semantics(mergeDescendants = true) { contentDescription = "Video" + (m.details.durationMs?.let { ", ${clock(it / 1000)}" } ?: "") + (m.details.caption?.let { ". $it" } ?: "") },
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(com.circaso.designsystem.components.bubbleShape(m.outgoing)).background(c.fillStrong), contentAlignment = Alignment.Center) {
                            thumb?.let { coil3.compose.AsyncImage(model = it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            Box(Modifier.size(52.dp).clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                                if (videoLoading) com.circaso.designsystem.components.Spinner(androidx.compose.ui.graphics.Color.White, Modifier.size(22.dp))
                                else CircasoIconImage(CircasoIcon.Play, androidx.compose.ui.graphics.Color.White, Modifier.size(22.dp))
                            }
                            m.details.durationMs?.let {
                                // A scrim under the length so it reads on a bright frame.
                                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(32.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, c.scrim))))
                                BasicText(clock(it / 1000), style = t.caption1.copy(color = androidx.compose.ui.graphics.Color.White, fontFeatureSettings = "tnum"), modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
                            }
                            if (sending) Box(Modifier.matchParentSize().background(c.scrim), contentAlignment = Alignment.Center) { com.circaso.designsystem.components.Spinner(c.onAction, Modifier.size(24.dp)) }
                        }
                        m.details.caption?.let { BasicText(it, style = t.body.copy(color = ink), modifier = Modifier.padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2)) }
                    }
                }
                m.isVoice -> Row(
                    bubble.then(if (options != null) Modifier.combinedClickable(onLongClick = options, onClick = { if (failed) onOptions() }) else Modifier)
                        .padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
                ) {
                    val duration = m.details.durationMs?.let { clock(it / 1000) }
                    val loading = playing && playingSince == null
                    // Sized like the other round buttons (36 dp); Compose widens the touch area to the 48 dp minimum.
                    Box(
                        Modifier.size(com.circaso.designsystem.theme.CircasoSizes.iconButton).clip(com.circaso.designsystem.theme.CircasoShapes.circle)
                            .background(if (m.outgoing) c.onAction else c.action)
                            .pressable(role = androidx.compose.ui.semantics.Role.Button, enabled = !failed, onClick = onPlay)
                            .semantics { contentDescription = if (loading) "Loading voice message" else if (playing) "Stop voice message" else "Play voice message" + (duration?.let { ", $it" } ?: "") },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Fetching the note first: a spinner, not a Pause that does nothing yet.
                        if (loading) com.circaso.designsystem.components.Spinner(if (m.outgoing) c.action else c.onAction, Modifier.size(16.dp))
                        else CircasoIconImage(if (playing) CircasoIcon.Stop else CircasoIcon.Play, if (m.outgoing) c.action else c.onAction, Modifier.size(16.dp))
                    }
                    // Progress from the play clock against the note's length (the bars are a drawn shape, not the
                    // recording's levels). It is read only while drawing, so it moves every frame without recomposing.
                    val total = m.details.durationMs?.takeIf { it > 0 }
                    val progress = androidx.compose.runtime.remember(m.localId) { androidx.compose.runtime.mutableFloatStateOf(0f) }
                    var seconds by androidx.compose.runtime.remember(m.localId) { androidx.compose.runtime.mutableStateOf(0L) }
                    LaunchedEffect(playingSince) {
                        progress.floatValue = 0f
                        seconds = 0
                        if (playingSince != null) while (true) {
                            androidx.compose.runtime.withFrameMillis { }
                            val elapsed = com.circaso.core.sync.nowMillis() - playingSince
                            if (total != null) progress.floatValue = (elapsed.toFloat() / total).coerceIn(0f, 1f)
                            seconds = elapsed / 1000
                        }
                    }
                    androidx.compose.foundation.Canvas(Modifier.weight(1f).height(24.dp)) {
                        val bar = 3.dp.toPx()
                        val gap = 2.dp.toPx()
                        val count = ((size.width + gap) / (bar + gap)).toInt().coerceAtLeast(1)
                        val filledTo = size.width * progress.floatValue
                        for (i in 0 until count) {
                            val h = VoiceShape[i % VoiceShape.size].dp.toPx().coerceAtMost(size.height)
                            val x = i * (bar + gap)
                            val top = (size.height - h) / 2
                            drawRoundRect(ink.copy(alpha = 0.4f), androidx.compose.ui.geometry.Offset(x, top), androidx.compose.ui.geometry.Size(bar, h), androidx.compose.ui.geometry.CornerRadius(bar / 2))
                            if (x < filledTo) clipRect(right = filledTo) {
                                drawRoundRect(ink, androidx.compose.ui.geometry.Offset(x, top), androidx.compose.ui.geometry.Size(bar, h), androidx.compose.ui.geometry.CornerRadius(bar / 2))
                            }
                        }
                    }
                    BasicText(if (playingSince != null) clock(seconds) else duration ?: "Voice", style = t.footnote.copy(color = inkSoft, fontFeatureSettings = "tnum"))
                }
                else -> {
                    val live = m.details.isLive
                    // "Live until" and Stop only once the share has gone; waiting or failed shows the usual status line.
                    val moving = m.details.liveNow(now) && m.state != DeliveryState.Waiting && m.state != DeliveryState.Sending && !failed
                    val who = if (m.outgoing) "you" else m.senderName
                    Column(
                        bubble.combinedClickable(
                            onClickLabel = if (live) "View live location" else "View on map",
                            onLongClick = options,
                            onClick = { if (failed) onOptions() else onOpenLocation() },
                        ).semantics(mergeDescendants = true) {
                            contentDescription = when {
                                moving -> "Live location from $who, until ${formatLocalTime(m.details.liveUntil ?: 0, LocalTimeStyle.Clock)}"
                                live -> "Live location from $who, ended"
                                else -> "Location from $who" + (m.details.label?.let { ", $it" } ?: "")
                            }
                        },
                    ) {
                        MapTile(
                            m.details.latitude, m.details.longitude,
                            Modifier.fillMaxWidth().padding(3.dp).clip(com.circaso.designsystem.components.bubbleShape(m.outgoing)),
                            person = if (live) m.senderName to avatarUrl else null,
                            moving = moving,
                        )
                        Row(Modifier.padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                BasicText(if (live) "Live location" else m.details.label ?: "Location", style = t.callout.copy(color = ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                                val lat = m.details.latitude; val lng = m.details.longitude
                                val sub = when {
                                    moving -> "Live until ${formatLocalTime(m.details.liveUntil ?: 0, LocalTimeStyle.Clock)}"
                                    live && m.outgoing && m.details.liveStopSent == false -> "Stopped · they'll see it when you're back online"
                                    live -> "Live location ended"
                                    lat != null && lng != null -> "${coordinates(lat, lng)} · View on map"
                                    else -> null
                                }
                                sub?.let { BasicText(it, style = t.footnote.copy(color = inkSoft)) }
                            }
                            if (m.outgoing && moving) {
                                Box(
                                    Modifier.clip(com.circaso.designsystem.theme.CircasoShapes.pill).background(c.onAction)
                                        .pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = onStopLive)
                                        .semantics { contentDescription = "Stop sharing live location" }
                                        .padding(horizontal = CircasoSpacing.s3, vertical = 6.dp),
                                ) { BasicText("Stop", style = t.footnote.copy(color = c.critical, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) }
                            }
                        }
                    }
                }
            }
            })
            when {
                // Hugs the bubble like the other statuses; Compose widens the touch area to the 48 dp minimum.
                failed -> Box(Modifier.pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = onOptions)) {
                    BubbleMeta("Not sent · tap for options", icon = CircasoIcon.Close, color = c.critical)
                }
                waiting -> BubbleMeta("Waiting to send", icon = CircasoIcon.Clock)
                sending -> BubbleMeta("Sending…", icon = CircasoIcon.Clock)
                else -> BubbleMeta(formatLocalTime(m.orderAt, LocalTimeStyle.Clock) + if (m.outgoing) when (m.state) { DeliveryState.Read -> " · Read"; DeliveryState.Delivered -> " · Delivered"; else -> "" } else "")
            }
        }
    }
}

/** The decoded photo is cached in memory by its message, so scrolling back doesn't decode it again. */
@Composable
private fun photoRequest(bytes: ByteArray, m: Message.Media): coil3.request.ImageRequest {
    val context = coil3.compose.LocalPlatformContext.current
    val key = "chat-photo-" + (m.details.attachmentId ?: "local-${m.localId}")
    return androidx.compose.runtime.remember(bytes, key) {
        coil3.request.ImageRequest.Builder(context).data(bytes).memoryCacheKey(key).placeholderMemoryCacheKey(key).build()
    }
}

/** A map-like tile: the pin on a soft grid, so a shared place reads as a place before anyone opens Maps. */
@Composable
private fun MapTile(lat: Double?, lng: Double?, modifier: Modifier, person: Pair<String, String?>? = null, moving: Boolean = false) {
    val c = CircasoTheme.colors
    val load = LocalMapPicture.current
    val palette = androidx.compose.runtime.remember(c) { c.mapPalette() }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.height(MapTileHeight).background(c.surface), contentAlignment = Alignment.Center) {
        // The drawn grid is the placeholder: offline, while loading, and wherever the server has no map.
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val step = 24.dp.toPx()
            var x = 0f
            while (x < size.width) { drawLine(c.separator, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height)); x += step }
            var y = 0f
            while (y < size.height) { drawLine(c.separator, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y)); y += step }
        }
        val width = maxWidth.value.toInt()
        val picture by androidx.compose.runtime.produceState<ByteArray?>(null, lat, lng, width, palette, load) {
            if (load != null && lat != null && lng != null && width > 0) value = load(lat, lng, width, MapTileHeight.value.toInt(), TileZoom, palette)
        }
        val shown by androidx.compose.animation.core.animateFloatAsState(if (picture != null) 1f else 0f, tween(CircasoMotion.BaseMs), label = "map")
        picture?.let { bytes ->
            coil3.compose.AsyncImage(
                model = bytes, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shown },
            )
        }
        if (person != null) {
            // A live share shows who it is: their photo in a ring, dark while it moves, grey once it ended.
            Box(
                Modifier.size(MediaIconSize + 4.dp).clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(c.surface)
                    .border(3.dp, if (moving) c.action else c.inkTertiary, com.circaso.designsystem.theme.CircasoShapes.circle).padding(3.dp),
                contentAlignment = Alignment.Center,
            ) { com.circaso.designsystem.components.Avatar(person.first, size = MediaIconSize - 6.dp, imageUrl = person.second) }
        } else {
            Box(
                Modifier.size(MediaIconSize).clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(c.surface).padding(2.dp)
                    .clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(c.action),
                contentAlignment = Alignment.Center,
            ) {
                CircasoIconImage(CircasoIcon.Pin, c.onAction, Modifier.size(20.dp))
            }
        }
    }
}

/** "-1.29210, 36.82190": five decimals (about a metre), never scientific notation. */
internal fun coordinates(lat: Double, lng: Double): String = "${fixed5(lat)}, ${fixed5(lng)}"

private fun fixed5(v: Double): String {
    val scaled = kotlin.math.round(kotlin.math.abs(v) * 100000).toLong()
    val sign = if (v < 0 && scaled != 0L) "-" else ""
    return "$sign${scaled / 100000}.${(scaled % 100000).toString().padStart(5, '0')}"
}

/** A video full screen, on black, with the player's own controls; back or Close ends it. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun androidx.compose.foundation.layout.BoxScope.FullScreenVideo(path: String, onClose: () -> Unit) {
    androidx.compose.ui.backhandler.BackHandler(onBack = onClose)
    Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.Black)) {
        com.circaso.core.platform.VideoPlayer(path, Modifier.fillMaxSize())
        Box(Modifier.statusBarsPadding().padding(CircasoSpacing.s3).align(Alignment.TopStart)) {
            CircasoIconButton(CircasoIcon.Close, "Close video", onClose)
        }
    }
}

/**
 * A photo full screen, on black, with a close button. Pinch to zoom (up to 4×) and pan while zoomed; double-tap
 * zooms to 2× at the tap or back out; drag down at normal size to dismiss, the backdrop fading as it goes. A tap,
 * back or the close button also close it.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.ImageViewer(bytes: ByteArray?, onClose: () -> Unit) {
    androidx.compose.ui.backhandler.BackHandler(enabled = bytes != null, onBack = onClose)
    // Keep the last photo through the fade out (bytes is already null by then).
    var shown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ByteArray?>(null) }
    if (bytes != null) shown = bytes
    var zoom by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var pan by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var drag by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(bytes) { if (bytes != null) { zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero; drag = 0f } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val dismissAt = with(androidx.compose.ui.platform.LocalDensity.current) { 140.dp.toPx() }
    fun settle(toZoom: Float, toPan: androidx.compose.ui.geometry.Offset) {
        val fromZoom = zoom
        val fromPan = pan
        scope.launch {
            androidx.compose.animation.core.animate(0f, 1f, animationSpec = tween(CircasoMotion.BaseMs)) { f, _ ->
                zoom = fromZoom + (toZoom - fromZoom) * f
                pan = fromPan + (toPan - fromPan) * f
            }
        }
    }
    AnimatedVisibility(bytes != null, Modifier.matchParentSize(), enter = fadeIn(tween(CircasoMotion.BaseMs)), exit = fadeOut(tween(CircasoMotion.QuickMs))) {
        val fade = (1f - kotlin.math.abs(drag) / (dismissAt * 3)).coerceIn(0.2f, 1f)
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = fade))) {
            shown?.let {
                coil3.compose.AsyncImage(
                    model = it, contentDescription = "Photo", contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { onClose() },
                                onDoubleTap = { at ->
                                    if (zoom > 1.01f) settle(1f, androidx.compose.ui.geometry.Offset.Zero)
                                    // Zoom towards the tapped spot: it stays under the finger.
                                    else settle(2f, (androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f) - at))
                                },
                            )
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val slop = viewConfiguration.touchSlop
                                var travelled = 0f
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.calculateZoom()
                                    val move = event.calculatePan()
                                    if (change != 1f || zoom > 1.01f) {
                                        // Zoom about the fingers, and never let the photo leave the screen.
                                        val centroid = event.calculateCentroid() - androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                                        val next = (zoom * change).coerceIn(1f, 4f)
                                        val grown = if (zoom > 0f) next / zoom else 1f
                                        val limitX = (next - 1f) * size.width / 2f
                                        val limitY = (next - 1f) * size.height / 2f
                                        val raw = (pan - centroid) * grown + centroid + move
                                        pan = if (next > 1.01f) androidx.compose.ui.geometry.Offset(raw.x.coerceIn(-limitX, limitX), raw.y.coerceIn(-limitY, limitY)) else androidx.compose.ui.geometry.Offset.Zero
                                        zoom = next
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } else if (event.changes.size == 1) {
                                        // A drag to dismiss starts only past the touch slop, so a shaky tap still closes.
                                        travelled += kotlin.math.abs(move.y)
                                        if (travelled > slop) {
                                            drag += move.y
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                                if (kotlin.math.abs(drag) > dismissAt) onClose()
                                else if (drag != 0f) {
                                    val from = drag
                                    scope.launch { androidx.compose.animation.core.animate(from, 0f, animationSpec = tween(CircasoMotion.QuickMs)) { v, _ -> drag = v } }
                                }
                            }
                        }
                        .graphicsLayer {
                            scaleX = zoom; scaleY = zoom
                            translationX = pan.x
                            translationY = pan.y + drag
                        },
                )
            }
            Box(Modifier.statusBarsPadding().padding(CircasoSpacing.s3).align(Alignment.TopStart)) {
                CircasoIconButton(CircasoIcon.Close, "Close photo", onClose)
            }
        }
    }
}

/** The coin chips float over the thread, lifted like the payment preview. */
private fun Modifier.shadowPill(): Modifier = shadow(6.dp, com.circaso.designsystem.theme.CircasoShapes.pill)

/** What sits under the conversation (bottom up): open requests, then support's quick replies. */
private fun androidx.compose.foundation.lazy.LazyListScope.threadTail(
    state: ThreadUiState,
    supportReplies: Boolean,
    chama: com.circaso.feature.chama.domain.Chama?,
    onDecide: (com.circaso.feature.chama.domain.ChamaRequest, Boolean) -> Unit,
    onPayRequest: ((com.circaso.feature.chama.domain.ChamaRequest) -> Unit)?,
    onQuickReply: (String) -> Unit,
    onCallSupport: (() -> Unit)?,
    onSupportDone: () -> Unit,
) {
    // Design D5: the requests still open sit under the conversation, live, until they are paid or closed.
    items(state.openRequests.asReversed(), key = { "request-" + it.requestId }) { request ->
        RequestCard(
            request, state.fiat, nameOf = state::memberName,
            isAdmin = state.requestsAdmin, isTreasurer = state.requestsPayer, myUserId = state.myUserId, busy = state.chamaBusy, online = state.online,
            inGroupWallet = state.groupMoney != null,
            waitsUntil = state.payoutWaits[request.requestId],
            signerName = state.groupMoney?.wallet?.signerUserId?.let { state.memberName(it).substringBefore(' ') },
            onApprove = { onDecide(request, true) }, onDecline = { onDecide(request, false) }, onPay = { onPayRequest?.invoke(request) },
            modifier = Modifier.animateItem(),
        )
    }
    // Design SP02: replies one tap away while support has the last word.
    if (supportReplies) item(key = "support-replies") {
        // Wraps at large text instead of clipping; a 1:1 chat has no avatar column to indent for.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            com.circaso.designsystem.components.SuggestionChip("Yes, please", { onQuickReply("Yes, please") })
            if (onCallSupport != null) com.circaso.designsystem.components.SuggestionChip("Call me instead", onCallSupport)
            com.circaso.designsystem.components.SuggestionChip("That's all", onSupportDone)
        }
    }
}


/** Design V8: a reply to a moment shows the moment quoted (its picture and words); it stays after the moment expires. */
@Composable
private fun MomentQuoteCard(quote: com.circaso.core.crypto.MomentQuote, outgoing: Boolean, posterName: String?, onOpen: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val ink = if (outgoing) c.onAction else c.ink
    val gone = quote.expiresAt?.let { it <= com.circaso.core.sync.nowMillis() } == true
    val thumb = androidx.compose.runtime.remember(quote.thumb, gone) { if (gone) null else quote.thumb?.let { runCatching { kotlin.io.encoding.Base64.decode(it) }.getOrNull() } }
    Row(
        Modifier.padding(bottom = 6.dp).clip(com.circaso.designsystem.theme.CircasoShapes.md).background(ink.copy(alpha = 0.12f))
            // Tap to open that moment in the viewer (Pasaka, 2026-11-03); an ended one says so where it is.
            .pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = onOpen).padding(6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Reply to ${if (outgoing) "a" else "your"} moment" + (quote.text?.let { ": $it" } ?: "") + if (gone) ", no longer available" else ", open it" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (thumb != null) Box(Modifier.size(44.dp, 60.dp).clip(com.circaso.designsystem.theme.CircasoShapes.xs), contentAlignment = Alignment.Center) {
            coil3.compose.AsyncImage(thumb, null, Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            if (quote.kind == "VIDEO") Box(Modifier.size(22.dp).clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(c.scrim), contentAlignment = Alignment.Center) {
                CircasoIconImage(CircasoIcon.Play, c.onMoney, Modifier.size(12.dp))
            }
        } else if (quote.kind == "VOICE") CircasoIconImage(CircasoIcon.Waveform, ink, Modifier.size(22.dp))
        Column(Modifier.padding(end = 4.dp)) {
            BasicText(if (outgoing) "${posterName?.substringBefore(' ') ?: "Their"} · Moment" else "Your moment", style = t.footnote.copy(color = ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            BasicText(if (gone) "No longer available" else quote.text ?: when (quote.kind) { "IMAGE" -> "Photo"; "VIDEO" -> "Video"; "VOICE" -> "Voice"; else -> "Moment" }, style = t.footnote.copy(color = ink), maxLines = 2)
        }
    }
}


/** What one message needs to show and take reactions: who chose what, and whether its reaction bubble is open. */
private class ReactUi(
    val reactions: List<Pair<String, String>>,
    val myId: String?,
    val picking: Boolean,
    /** Opens the reaction bubble; null when the message can't take one yet (not sent). */
    val onOpen: (() -> Unit)?,
    val onPick: (String?) -> Unit,
    val onDismiss: () -> Unit,
    /** The message's other options (send again, delete), for messages whose tap does something else. */
    val onOptions: (() -> Unit)? = null,
) {
    val mine: String? get() = reactions.firstOrNull { it.first == myId }?.second
}

/**
 * A message with its reaction (WhatsApp-style, Pasaka 2026-11-03): no chip, no border, just the emoji on the bubble's
 * bottom right corner, half on the bubble and half off it like the + on the Moments ring. A long press opens the
 * reaction bubble just under the message, in place: no sheet.
 */
@Composable
private fun Reacted(ui: ReactUi, outgoing: Boolean, content: @Composable () -> Unit) {
    Box(
        // Long press is the gesture; screen readers get it as a named action.
        if (ui.onOpen != null) Modifier.semantics {
            customActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction("React") { ui.onOpen.invoke(); true })
        } else Modifier,
    ) {
        val inset = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
        // The bubble sets the width; the pill sits on its bottom edge at the right, half in and half out, and its lower
        // half is part of the height, so it never covers the time and takes taps at any text size. Under a bubble
        // narrower than the pill it starts at the left and runs past the bubble's end.
        androidx.compose.ui.layout.Layout(content = {
            content()
            if (ui.reactions.isNotEmpty()) ReactionBadge(ui)
        }) { measurables, constraints ->
            val bubble = measurables[0].measure(constraints)
            val badge = measurables.getOrNull(1)?.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(bubble.width, bubble.height + (badge?.height ?: 0) / 2) {
                bubble.place(0, 0)
                if (badge != null) {
                    val x = (bubble.width - badge.width - inset * 2).coerceAtLeast(inset * 2)
                    badge.place(x, bubble.height - badge.height / 2)
                }
            }
        }
        if (ui.picking) com.circaso.designsystem.components.ReactionPicker(
            current = ui.mine, onPick = ui.onPick, onDismiss = ui.onDismiss, onOptions = ui.onOptions, alignEnd = outgoing, gap = 6.dp,
        )
    }
}

/**
 * The reaction as WhatsApp draws it: a small pill holding the emoji (up to three kinds) and how many reacted once
 * there's more than one, filled with the chat's own background (the theme's, light or dark), so it looks punched out
 * of the bubble's bottom edge, half in and half out. Text-sized, not big. Tap to change mine.
 */
@Composable
private fun ReactionBadge(ui: ReactUi) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val counts = ui.reactions.groupingBy { it.second }.eachCount().entries.sortedByDescending { it.value }
    val shape = com.circaso.designsystem.theme.CircasoShapes.pill
    Row(
        Modifier
            // The cut-out: the thread's own background, so it follows the theme.
            .clip(shape).background(c.bg)
            .then(if (ui.onOpen != null) Modifier.pressable(role = androidx.compose.ui.semantics.Role.Button, pressedScale = 0.92f, onClick = ui.onOpen) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clearAndSetSemantics {
                contentDescription = "Reactions: " + counts.joinToString { "${it.key} ${it.value}" } + (ui.mine?.let { ", yours $it" } ?: "")
                ui.onOpen?.let { open -> onClick("Change reaction") { open(); true } }
            },
        horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        counts.take(3).forEach { BasicText(it.key, style = t.footnote.copy(lineHeight = t.footnote.fontSize * 1.3f)) }
        if (ui.reactions.size > 1) {
            BasicText(
                ui.reactions.size.toString(),
                style = t.caption2.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"),
                modifier = Modifier.padding(start = 2.dp, end = 1.dp),
            )
        }
    }
}
