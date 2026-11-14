package com.circaso.feature.status.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.circaso.designsystem.components.ReactionAnchor
import com.circaso.designsystem.components.ReactionPicker
import kotlinx.coroutines.Job
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circaso.core.platform.AudioPlayer
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.MomentVideo
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.designsystem.chrome.SheetHead
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.designsystem.theme.LightColors
import com.circaso.feature.status.domain.Moment
import com.circaso.feature.wallet.presentation.PayFromSheetContent
import com.circaso.feature.status.domain.MomentKind
import com.circaso.feature.status.domain.MomentTextStyle
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import org.koin.compose.koinInject

/** How long a photo or text moment stays before moving on (WhatsApp uses about 5 s). */
private const val StillMs = 5_000

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MomentViewerScreen(
    viewModel: MomentViewerViewModel,
    onClose: () -> Unit,
    onMessage: (posterId: String) -> Unit = {},
    /** Closed by dragging it down: the viewer leaves downward, so the navigation shouldn't slide it sideways. */
    onDragClose: () -> Unit = onClose,
) {
    val state by viewModel.state.collectAsState()
    // Money typed in the reply field (`#500`, 2026-11-14) is the chat's own in-line payment, made for the chat with
    // whoever posted this moment and carrying the moment for its card. One per moment, made once a `#` has been typed.
    val made = state.moneyTarget?.let { target ->
        org.koin.compose.viewmodel.koinViewModel<com.circaso.feature.wallet.presentation.PayViewModel>(
            key = "moment-pay-${target.chatId}-${target.momentId}",
            parameters = { org.koin.core.parameter.parametersOf(com.circaso.feature.wallet.presentation.PayIntent(userId = target.userId, chatId = target.chatId, displayName = target.displayName, inChat = true, moment = target.quote)) },
        )
    }
    // One at the PIN or being sent is kept until it is through, whatever happens to the moment meanwhile.
    val holding = remember { arrayOfNulls<com.circaso.feature.wallet.presentation.PayViewModel>(1) }
    val pay = holding[0]?.takeIf { it.state.value.step.let { s -> s == com.circaso.feature.wallet.presentation.PayStep.Signing || s == com.circaso.feature.wallet.presentation.PayStep.Sending } } ?: made
    holding[0] = pay
    val quickPay = com.circaso.feature.messaging.presentation.rememberQuickPay(pay)
    // While the PIN is up or the payment is being sent, the viewer stays and the moment waits: leaving now would let
    // go of the payment before its card (with the moment) is in the chat.
    val payBusy = quickPay?.state?.step.let { it == com.circaso.feature.wallet.presentation.PayStep.Signing || it == com.circaso.feature.wallet.presentation.PayStep.Sending }
    LaunchedEffect(payBusy) { viewModel.onPayBusy(payBusy) }
    val busyNow = androidx.compose.runtime.rememberUpdatedState(payBusy)
    val close = { if (!busyNow.value && !viewModel.state.value.payBusy) onClose() }
    val dragClose = { if (!busyNow.value && !viewModel.state.value.payBusy) onDragClose() }
    LaunchedEffect(state.closed, state.payBusy) { if (state.closed && !state.payBusy) onClose() }
    var forward by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ForwardMedia?>(null) }
    androidx.compose.ui.backhandler.BackHandler(onBack = close)
    val actions = remember(viewModel) { viewModel.actions() }
    Box(Modifier.fillMaxSize()) {
        MomentViewerContent(state, actions, close, onMessage, dragClose,
            onForward = { m -> viewModel.openViewers(false); viewModel.setPaused(true); forward = m }, quickPay = quickPay)
        // GW10: Paying from, opened from the money card, as in a chat; on the dark stage like the viewer's other sheets.
        quickPay?.let { q ->
            CircasoTheme(darkTheme = true, highContrast = CircasoTheme.colors.isHighContrast) {
                CircasoSheet(visible = q.state.choosing, onDismiss = { q.onChoosing(false) }, label = "Pay from") {
                    PayFromSheetContent(q.state.choices, q.state.payingFrom.walletId, q.state.balances, q.state.online, q.state.asOf, onPick = q.onPayFrom, onClose = { q.onChoosing(false) })
                }
            }
        }
        // V4 Forward: a list screen in the person's own theme, over the stage; the moment waits paused behind it.
        forward?.let { m ->
            val done = { forward = null; viewModel.setPaused(false) }
            MomentForwardScreen(org.koin.compose.viewmodel.koinViewModel(key = "forward-viewer"), m, onClose = done, onSent = { names -> done(); viewModel.notice(forwardedNotice(names)) })
        }
    }
}

/** Everything the viewer can do, so the screen draws from state alone (previews pass [None]). */
class MomentViewerActions(
    val next: () -> Unit,
    val previous: () -> Unit,
    val nextRing: () -> Unit,
    val setPaused: (Boolean) -> Unit,
    val toggleMute: () -> Unit,
    val retryMedia: () -> Unit,
    val openPicker: () -> Unit,
    val closePicker: () -> Unit,
    /** Any emoji; null takes mine back. */
    val react: (String?) -> Unit,
    val onReply: (String) -> Unit,
    val onReplyFocus: (Boolean) -> Unit,
    val sendReply: () -> Unit,
    val openMore: (Boolean) -> Unit,
    val mute: () -> Unit,
    val openViewers: (Boolean) -> Unit,
    val askDelete: (Boolean) -> Unit,
    val delete: () -> Unit,
    val noticeShown: () -> Unit,
    /** A reaction splash over my moment finished (or was cut short). */
    val splashDone: (Long) -> Unit = {},
    /** A line for the toast (a payment from the reply field that failed). */
    val notice: (String) -> Unit = {},
    /** A payment from the reply field was signed. */
    val onPaid: () -> Unit = {},
    /** The line about a chat that didn't open was tapped. */
    val retryMoney: () -> Unit = {},
) {
    companion object {
        val None = MomentViewerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

private fun MomentViewerViewModel.actions() = MomentViewerActions(
    next = ::next, previous = ::previous, nextRing = ::nextRing, setPaused = ::setPaused, toggleMute = ::toggleMute,
    retryMedia = ::retryMedia, openPicker = ::openPicker, closePicker = ::closePicker, react = ::react, onReply = ::onReply,
    onReplyFocus = ::onReplyFocus, sendReply = ::sendReply, openMore = ::openMore, mute = ::mute, openViewers = ::openViewers,
    askDelete = ::askDelete, delete = ::delete, noticeShown = ::noticeShown, splashDone = ::splashDone, notice = ::notice, onPaid = ::onPaid, retryMoney = ::retryMoney,
)

/**
 * What sits under the money card on the stage at the default text size: the reply field at its three lines, the
 * line above it and the gaps. It grows with the text size.
 */
private val StageUnderCard = 172.dp
/** The money card's two fixed rows (how much, the coin and fee); everything else in it scrolls. */
private val StageCardMin = 72.dp

/** Past this share of the screen's height, letting go closes the viewer (a fast fling closes it from anywhere). */
private const val DragCloseFraction = 0.22f
/** Longer than this is a hold (pause), not a tap (skip). */
private const val HoldMillis = 250L
/** A downward fling this fast (dp per second) closes the viewer however far it moved. */
private val FlingClose = 1_000.dp

@Composable
fun MomentViewerContent(
    state: MomentViewerUiState,
    vm: MomentViewerActions,
    onClose: () -> Unit,
    onMessage: (String) -> Unit = {},
    onDragClose: () -> Unit = onClose,
    /** My moment to a chat (V4 Forward); null hides Forward (previews). */
    onForward: ((ForwardMedia) -> Unit)? = null,
    /** The chat's in-line payment for whoever posted the moment on screen; null until a `#` has got their chat ready. */
    quickPay: com.circaso.feature.messaging.presentation.QuickPay? = null,
) {
    // Always the dark stage, in every theme (design V1): the dark tokens, with the person's high-contrast choice kept.
    CircasoTheme(darkTheme = true, highContrast = CircasoTheme.colors.isHighContrast) {
        // A sheet or the picker on top owns the gestures; while a reply is being written, a drag down puts the
        // keyboard away instead of closing (and throwing the draft away).
        val enabled = !state.moreOpen && !state.viewersOpen && !state.confirmDelete && !state.picking && !state.payBusy
        val typing = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0 || state.reply.isNotEmpty()
        DraggableStage(enabled, typing, onDragClose) { dragging -> ViewerBody(state, vm, onClose, onMessage, dragging, onForward, quickPay) }
    }
}

/**
 * Drag down to close (V1): the moment follows the finger, shrinking as it goes; under the threshold it springs back,
 * past it (or on a fast fling down) it carries on down at the finger's speed while the viewer closes and fades over the
 * screen underneath. Reduced motion: it still follows the finger, but without the shrink, and lets go without the
 * spring or the slide.
 */
@Composable
private fun DraggableStage(enabled: Boolean, typing: Boolean, onClose: () -> Unit, content: @Composable (dragging: Boolean) -> Unit) {
    val c = CircasoTheme.colors
    val still = CircasoTheme.reduceMotion
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val close by rememberUpdatedState(onClose)
    val canDrag by rememberUpdatedState(enabled)
    val isTyping by rememberUpdatedState(typing)
    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var settle by remember { mutableStateOf<Job?>(null) }
    val back = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    Box(
        // The gesture sits on the box that doesn't move, so the finger's travel is measured on the screen, not the stage.
        Modifier.fillMaxSize().background(c.bg).pointerInput(still) {
            val tracker = VelocityTracker()
            val fling = FlingClose.toPx()
            var active = false
            detectVerticalDragGestures(
                onDragStart = {
                    active = canDrag && !isTyping
                    if (canDrag && isTyping) focus.clearFocus()
                    if (active) { settle?.cancel(); tracker.resetTracking(); dragging = true }
                },
                onDragEnd = {
                    if (active) {
                        active = false
                        val velocity = tracker.calculateVelocity().y
                        val height = size.height.toFloat()
                        // Past the line and not flicked back up, or flung down from anywhere.
                        val closing = (offset > height * DragCloseFraction && velocity > -fling) || (velocity > fling && offset > 0f)
                        if (closing) close()
                        settle = scope.launch {
                            when {
                                closing && still -> Unit
                                // Carries the finger's speed on, critically damped: no stop-and-go after a fling.
                                closing -> animate(offset, height, velocity, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> offset = v }
                                still -> { offset = 0f; dragging = false }
                                else -> { animate(offset, 0f, velocity, back) { v, _ -> offset = v }; dragging = false }
                            }
                        }
                    }
                },
                onDragCancel = {
                    if (active) {
                        active = false
                        settle = scope.launch {
                            if (!still) animate(offset, 0f, 0f, back) { v, _ -> offset = v }
                            offset = 0f; dragging = false
                        }
                    }
                },
            ) { change, dy ->
                if (active) {
                    tracker.addPosition(change.uptimeMillis, change.position)
                    // Down only: dragging up past the top does nothing.
                    offset = (offset + dy).coerceAtLeast(0f)
                    change.consume()
                }
            }
        },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val progress = (offset / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                translationY = offset
                if (!still) {
                    val scale = 1f - 0.25f * progress
                    scaleX = scale; scaleY = scale
                }
                if (offset > 0f) { clip = true; shape = CircasoShapes.lg }
            },
        ) { content(dragging) }
    }
}

/** My moment as something to send to a chat; null while its media isn't on the phone yet. */
private fun forwardOf(moment: Moment, media: ViewerMedia): ForwardMedia? {
    if (moment.kind == MomentKind.Text) return ForwardMedia(MomentKind.Text, null, null, "text/plain", null, null, null, null, null, text = moment.text)
    val ready = media as? ViewerMedia.Ready ?: return null
    val m = moment.media ?: return null
    return ForwardMedia(moment.kind, ready.bytes.takeIf { it.isNotEmpty() }, ready.path, m.mime, m.width, m.height, m.durationMs, moment.thumb?.let { runCatching { Base64.decode(it) }.getOrNull() }, moment.caption)
}

@Composable
private fun ViewerBody(state: MomentViewerUiState, vm: MomentViewerActions, onClose: () -> Unit, onMessage: (String) -> Unit, dragging: Boolean, onForward: ((ForwardMedia) -> Unit)? = null, quickPay: com.circaso.feature.messaging.presentation.QuickPay? = null) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val moment = state.moment
    val ring = state.current
    val toast = rememberToastState()
    LaunchedEffect(state.notice) { state.notice?.let { toast.show(it); vm.noticeShown() } }
    // The moment waits while it is held, dragged, or the picker or a sheet is open.
    val paused = state.waiting || dragging
    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (moment == null || ring == null) {
            Spinner(c.inkSecondary, Modifier.align(Alignment.Center))
            return@Box
        }
        Stage(moment, state.media, vm.retryMedia, paused = paused, muted = state.muted, posterName = ring.name, posterAvatar = ring.avatarUrl)
        // Shade behind the header and the caption/reply, so white text reads on any photo (design shade-top/bot).
        Box(Modifier.fillMaxWidth().height(200.dp).background(Brush.verticalGradient(listOf(c.scrim, Color.Transparent))))
        com.circaso.feature.mediacompose.StageShade(260.dp, Modifier.align(Alignment.BottomCenter))
        // V7: gone while open; say so, then move on.
        LaunchedEffect(moment.momentId, state.media) { if (state.media == ViewerMedia.Gone) { kotlinx.coroutines.delay(2_000); vm.next() } }

        // Tap the left third for back, anywhere else for next; hold to pause; swipe left for the next person (down is above).
        // While a reply or money is being written, a tap on the moment only puts the keyboard away: it never moves on
        // (that would throw away what was typed, perhaps a payment being set up) and never starts the moment again.
        val writing = androidx.compose.runtime.rememberUpdatedState(state.reply.isNotEmpty() || state.replyFocused || state.payBusy)
        val stageFocus = androidx.compose.ui.platform.LocalFocusManager.current
        Box(
            Modifier.fillMaxSize()
                .pointerInput(moment.momentId) {
                    // A hold pauses and lifting resumes, never skips (Pasaka, 2026-11-04): only a quick tap moves on.
                    var pressedAt = 0L
                    detectTapGestures(
                        onPress = {
                            pressedAt = com.circaso.core.sync.nowMillis()
                            if (!writing.value) { vm.setPaused(true); tryAwaitRelease(); vm.setPaused(false) }
                        },
                        onTap = { pos ->
                            if (writing.value) stageFocus.clearFocus()
                            else if (com.circaso.core.sync.nowMillis() - pressedAt < HoldMillis) {
                                if (pos.x < size.width / 3f) vm.previous() else vm.next()
                            }
                        },
                    )
                }
                .pointerInput(Unit) { var dx = 0f; detectHorizontalDragGestures(onDragEnd = { if (dx < -160 && !writing.value) vm.nextRing(); dx = 0f }) { _, d -> dx += d } }
                // The same moves for screen readers, where taps on thirds of the screen and holds can't be performed.
                .semantics {
                    contentDescription = "Moment ${state.index + 1} of ${ring.moments.size} from ${ring.name}"
                    customActions = listOf(
                        androidx.compose.ui.semantics.CustomAccessibilityAction("Next moment") { vm.next(); true },
                        androidx.compose.ui.semantics.CustomAccessibilityAction("Previous moment") { vm.previous(); true },
                        androidx.compose.ui.semantics.CustomAccessibilityAction(if (state.paused) "Resume" else "Pause") { vm.setPaused(!state.paused); true },
                        androidx.compose.ui.semantics.CustomAccessibilityAction("Next person") { vm.nextRing(); true },
                    )
                },
        )

        // Top: one segment per moment, the person, the time, More and Close.
        // Its bottom edge is measured: the money card over the reply field never grows up into it.
        var headerBottom by remember { mutableFloatStateOf(0f) }
        Column(Modifier.fillMaxWidth().onSizeChanged { headerBottom = it.height.toFloat() }.statusBarsPadding().padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Segments(ring.moments, state.index, moment, paused = paused || state.media !is ViewerMedia.Ready && moment.kind != MomentKind.Text, onDone = vm.next)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(ring.name, size = 36.dp, imageUrl = ring.avatarUrl)
                Column(Modifier.weight(1f)) {
                    BasicText(if (moment.mine) "My moment" else ring.name, style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1)
                    BasicText(ago(moment.postedAt), style = t.footnote.copy(color = c.inkSecondary))
                }
                if (moment.kind == MomentKind.Video) GlassButton(if (state.muted) CircasoIcon.VolumeOff else CircasoIcon.Speaker, if (state.muted) "Unmute" else "Mute", onClick = vm.toggleMute)
                GlassButton(CircasoIcon.More, "More") { if (moment.mine) vm.askDelete(true) else vm.openMore(true) }
                GlassButton(CircasoIcon.Close, "Close", onClick = onClose)
            }
        }

        // Bottom: caption, then the reply field and heart (someone else's) or Seen by (mine).
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            // While a reply or money is being written the caption steps aside: the room is the field's and the money card's.
            moment.caption?.takeIf { moment.mine || state.reply.isEmpty() }?.let {
                BasicText(it, Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s5), style = t.body.copy(color = c.ink, textAlign = TextAlign.Center))
            }
            if (moment.mine) {
                Row(
                    Modifier.align(Alignment.CenterHorizontally).clip(CircasoShapes.pill).background(c.glass).pressable(onClick = { vm.openViewers(true) })
                        .padding(horizontal = CircasoSpacing.s4, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                ) {
                    CircasoIconImage(CircasoIcon.Eye, c.ink, Modifier.size(18.dp))
                    BasicText("Seen by ${moment.viewCount}" + if (moment.reactionCount > 0) " · ${moment.reactionCount} reaction${if (moment.reactionCount == 1) "" else "s"}" else "", style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
                }
            } else {
                // The reply field is the chat's own composer on the stage (Pasaka, 2026-11-14: money in Moments works
                // as it does in chats, no button of its own): plain words are a reply quoting this moment; `#500` is
                // drawn as money, the chat's money card comes up over the shade, and the arrow pays straight to the
                // PIN. The payment's card in the chat carries this moment. The heart sits where a chat has its mic.
                val first = ring.name.substringBefore(' ')
                val thread = remember(state.reply, state.online) { com.circaso.feature.messaging.presentation.ThreadUiState(draft = state.reply, online = state.online) }
                val composerMoney = remember(moment.momentId) { com.circaso.feature.messaging.presentation.ChatComposerMoney() }
                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                val density = androidx.compose.ui.platform.LocalDensity.current
                val stageHeight = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height
                val ime = androidx.compose.foundation.layout.WindowInsets.ime
                val nav = androidx.compose.foundation.layout.WindowInsets.navigationBars
                val still = CircasoTheme.reduceMotion
                // Taps on the money card, the line and the gaps between stay here: they never reach the moment behind.
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { } }) {
                    // "Reply to Wanjiru, or #amount to send" where the field has room for it at this text size; else
                    // shorter, so "#amount" is never the part that is cut off.
                    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                    val room = with(density) { (maxWidth - com.circaso.designsystem.components.StageFieldMin - CircasoSpacing.s2 - CircasoSpacing.s4 * 2).toPx() }
                    val placeholder = remember(first, state.canPay, room, t.body) {
                        if (!state.canPay) "Reply to $first"
                        else listOf("Reply to $first, or #amount to send", "Reply, or #amount to send").firstOrNull { measurer.measure(it, t.body, maxLines = 1).size.width <= room } ?: "Reply or #amount"
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                        // Why `#` isn't money yet (the chat is opening or couldn't be, there is no connection): said here
                        // on glass, readable over any photo, never silently nothing. A chat that didn't open: tap to retry.
                        androidx.compose.animation.AnimatedVisibility(
                            state.moneyLine != null,
                            enter = androidx.compose.animation.fadeIn(tween(com.circaso.designsystem.theme.CircasoMotion.BaseMs)).let { if (still) it else it + androidx.compose.animation.expandVertically(tween(com.circaso.designsystem.theme.CircasoMotion.BaseMs)) },
                            exit = androidx.compose.animation.fadeOut(tween(com.circaso.designsystem.theme.CircasoMotion.ExitMs)).let { if (still) it else it + androidx.compose.animation.shrinkVertically(tween(com.circaso.designsystem.theme.CircasoMotion.ExitMs)) },
                        ) {
                            val last = remember { arrayOf("") }
                            state.moneyLine?.let { last[0] = it }
                            BasicText(
                                last[0],
                                Modifier.clip(CircasoShapes.md).glass(CircasoShapes.md, edge = false)
                                    .then(if (state.moneyRetry) Modifier.pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = vm.retryMoney) else Modifier)
                                    .padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                style = t.footnote.copy(color = c.ink),
                            )
                        }
                        com.circaso.feature.messaging.presentation.ChatComposer(
                            state = thread, onDraft = vm.onReply, onSend = vm.sendReply,
                            quickPay = quickPay?.takeIf { state.canPay }, money = composerMoney, focusRequester = focus,
                            onToast = { text, _ -> vm.notice(text) },
                            // The room the money card may take: from under the header down to the field, above the
                            // keyboard. Its rows scroll before it would cover Close and More.
                            cardMaxHeight = {
                                with(density) {
                                    val under = StageUnderCard * fontScale.coerceAtLeast(1f)
                                    ((stageHeight - headerBottom - maxOf(ime.getBottom(density), nav.getBottom(density))).toDp() - under).coerceAtLeast(StageCardMin)
                                }
                            },
                            onPaid = { keyboard?.hide(); focusManager.clearFocus(); vm.onPaid() },
                            stage = com.circaso.feature.messaging.presentation.StageComposer(
                                placeholder = placeholder, label = "Reply to ${ring.name}", sendLabel = "Send reply to $first",
                                canSendText = state.canReply, onFocus = vm.onReplyFocus,
                                trailing = {
                                    Box {
                                        ReactButton(moment.reaction, state.online, com.circaso.designsystem.components.StageFieldMin, vm.openPicker)
                                        // The chats' reaction bubble (any emoji), floating just above the button at the screen's end.
                                        if (state.picking) ReactionPicker(
                                            current = moment.reaction, onPick = vm.react, onDismiss = vm.closePicker,
                                            anchor = ReactionAnchor.Above, alignEnd = true, gap = CircasoSpacing.s2,
                                        )
                                    }
                                },
                            ),
                        )
                    }
                }
            }
        }

        // New reactions on my moment splash over it; it takes no touches, so the moment can be tapped through. Always
        // composed, so moving on fades it out; held while a sheet or confirm covers the moment, then played.
        ReactionSplash(state.burst?.takeIf { moment.mine && !state.moreOpen && !state.confirmDelete }, vm.splashDone)

        ToastHost(toast, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 96.dp))
        MoreSheet(state.moreOpen, ring.name, onMute = vm.mute, onMessage = { vm.openMore(false); onMessage(ring.posterId) }, onDismiss = { vm.openMore(false) })
        val forwardable = remember(moment.momentId, state.media) { forwardOf(moment, state.media) }
        ViewersSheet(
            state, onDismiss = { vm.openViewers(false) }, onDelete = { vm.openViewers(false); vm.askDelete(true) },
            onForward = if (onForward != null) forwardable?.let { f -> { onForward(f) } } else null,
        )
        ConfirmSheet(
            visible = state.confirmDelete, title = "Delete this moment?",
            message = "People who haven’t seen it won’t be able to. This can’t be undone.",
            confirmLabel = "Delete Moment", onConfirm = vm.delete, onDismiss = { vm.askDelete(false) }, cancelLabel = "Cancel",
            destructive = true,
        )
    }
}

/** The moment itself: a text card, the photo, the video, or the voice with its waveform (V1–V3, V5–V6). */
@Composable
private fun BoxScope.Stage(moment: Moment, media: ViewerMedia, onRetry: () -> Unit, paused: Boolean, muted: Boolean, posterName: String = "", posterAvatar: String? = null) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    when (moment.kind) {
        MomentKind.Text -> {
            val swatch = MomentPalette.of(moment.background)
            val (family, weight) = momentFont(moment.style)
            Box(Modifier.fillMaxSize().background(brush = swatch.brush).padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                BasicText(
                    moment.text.orEmpty(), Modifier.semantics { contentDescription = moment.text.orEmpty() },
                    style = t.title1.copy(color = swatch.ink, textAlign = TextAlign.Center, fontWeight = weight, fontFamily = family ?: t.title1.fontFamily,
                        fontSize = if ((moment.text?.length ?: 0) > 120) 22.sp else 32.sp, lineHeight = if ((moment.text?.length ?: 0) > 120) 28.sp else 39.sp),
                )
            }
        }
        else -> {
            // The blurred placeholder that travelled in the encrypted body holds the place (V5).
            val thumb = remember(moment.thumb) { moment.thumb?.let { runCatching { Base64.decode(it) }.getOrNull() } }
            // A voice moment is its colour and wave from the first frame (V3); loading just says so on the clock line.
            if (moment.kind == MomentKind.Voice && (media is ViewerMedia.Loading || media is ViewerMedia.None)) {
                VoiceStage(moment, null, paused, posterName, posterAvatar)
                return
            }
            if (media !is ViewerMedia.Ready && thumb != null) {
                coil3.compose.AsyncImage(thumb, null, Modifier.fillMaxSize().blur(24.dp).graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }, contentScale = ContentScale.Crop)
            }
            when (media) {
                is ViewerMedia.Ready -> when (moment.kind) {
                    MomentKind.Image -> MomentPhoto(media.bytes, moment.caption ?: "Photo", thumb)
                    // No player controls (Pasaka, 2026-11-03): hold anywhere to pause, the speaker at the top mutes.
                    MomentKind.Video -> media.path?.let { MomentVideo(it, playing = !paused, muted = muted, modifier = Modifier.fillMaxSize(), poster = thumb) }
                    else -> VoiceStage(moment, media.bytes, paused, posterName, posterAvatar)
                }
                ViewerMedia.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spinner(c.ink, Modifier.size(28.dp))
                    BasicText("Loading", style = t.subhead.copy(color = c.ink))
                }
                ViewerMedia.Gone -> StageMessage(CircasoIcon.Clock, "This moment is no longer available", null)
                ViewerMedia.NeedsConnection -> StageMessage(CircasoIcon.Offline, "This moment needs a connection", "It opens as soon as you’re back online.")
                is ViewerMedia.Failed -> StageMessage(CircasoIcon.Warning, "This moment didn’t load", media.requestId?.let { "Ref $it" }, "Try Again" to onRetry)
                ViewerMedia.None -> Unit
            }
        }
    }
}

/**
 * A photo moment. Shaped like the stage (a portrait photo on an upright phone) it fills it, cropped a little, as the
 * design shows; shaped very differently (portrait on a phone on its side or an iPad in landscape, landscape on an
 * upright phone) it shows whole, by the same rule as moment videos ([com.circaso.core.platform.MomentVideoEncoding.fills]),
 * over its own small placeholder (the 96 px thumbnail from the encrypted body) blurred, exactly as the loading state
 * draws it, so nothing changes around the photo when it arrives. Before Android 12 the blur is a no-op
 * and the tiny thumbnail stretched soft stands in for it.
 */
@Composable
private fun MomentPhoto(bytes: ByteArray, description: String, thumb: ByteArray?) {
    var photo by remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Size.Unspecified) }
    var stage by remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val fills = photo.isUnspecified || stage == androidx.compose.ui.unit.IntSize.Zero ||
        com.circaso.core.platform.MomentVideoEncoding.fills(photo.width.toInt(), photo.height.toInt(), stage.width, stage.height)
    Box(Modifier.fillMaxSize().onSizeChanged { stage = it }) {
        if (!fills && thumb != null) {
            coil3.compose.AsyncImage(thumb, null, Modifier.fillMaxSize().blur(24.dp).graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }, contentScale = ContentScale.Crop)
        }
        coil3.compose.AsyncImage(
            bytes, description, Modifier.fillMaxSize(),
            contentScale = if (fills) ContentScale.Crop else ContentScale.Fit,
            onSuccess = { photo = it.painter.intrinsicSize },
        )
    }
}

@Composable
private fun BoxScope.VoiceStage(moment: Moment, bytes: ByteArray?, paused: Boolean, posterName: String, posterAvatar: String?) {
    val t = CircasoTheme.typography
    val player: AudioPlayer = koinInject()
    var playing by remember(moment.momentId) { mutableStateOf(false) }
    var finished by remember(moment.momentId) { mutableStateOf(false) }
    // Played once the bytes are here; until then the stage is already the poster's colour and wave (no spinner).
    LaunchedEffect(moment.momentId, bytes != null) {
        if (bytes == null) return@LaunchedEffect
        playing = true
        player.play(bytes, moment.media?.mime ?: "audio/mp4")
        playing = false
        finished = true
    }
    // Hold to pause holds the voice where it is, and the wave with it (the player keeps the hold if it hasn't started).
    LaunchedEffect(moment.momentId, paused) { if (paused) player.pause() else player.resume() }
    androidx.compose.runtime.DisposableEffect(moment.momentId) { onDispose { player.stop() } }
    // V3: the poster's background (older voice moments have none: Ink, the dark stage they always had).
    val swatch = MomentPalette.of(moment.background)
    Box(Modifier.fillMaxSize().background(brush = swatch.brush))
    val polled = playbackProgress(playing && !paused, moment.momentId) { player.progress() }
    val progress = if (finished) 1f else polled
    val total = moment.media?.durationMs ?: 0
    val clockLine = if (bytes == null) "Loading" else "${voiceClock((total * progress).toLong())} / ${voiceClock(total)}"
    Column(Modifier.align(Alignment.Center).padding(horizontal = CircasoSpacing.s6), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Avatar(posterName, size = CircasoSizes.avatarXl, imageUrl = posterAvatar, modifier = Modifier.border(3.dp, swatch.ink.copy(alpha = 0.9f), CircasoShapes.circle))
        VoiceWave(
            { moment.levels }, progress = progress, playing = playing && !paused, ink = swatch.ink,
            spoken = if (bytes == null) "Loading" else "${voiceClock((total * progress).toLong())} of ${voiceClock(total)}",
        )
        BasicText(clockLine, Modifier.clearAndSetSemantics { }, style = t.subhead.copy(color = swatch.ink.copy(alpha = 0.8f), fontFeatureSettings = "tnum"))
    }
}

private fun voiceClock(ms: Long): String { val s = (ms / 1000).coerceIn(0, 3600); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

@Composable
private fun BoxScope.StageMessage(icon: CircasoIcon, title: String, sub: String?, action: Pair<String, () -> Unit>? = null) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.align(Alignment.Center).padding(horizontal = 40.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircasoIconImage(icon, c.ink, Modifier.size(32.dp))
        BasicText(title, style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center))
        action?.let { (label, onClick) -> CircasoButton(label, onClick, style = ButtonStyle.Secondary, size = ButtonSize.Small) }
        sub?.let { BasicText(it, style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center)) }
    }
}

/** One segment per moment; the current one fills over the moment's time and moves on by itself. */
@Composable
private fun Segments(moments: List<Moment>, index: Int, current: Moment, paused: Boolean, onDone: () -> Unit) {
    val c = CircasoTheme.colors
    val progress = remember(current.momentId) { Animatable(0f) }
    val duration = when (current.kind) {
        MomentKind.Video, MomentKind.Voice -> (current.media?.durationMs ?: StillMs.toLong()).toInt().coerceIn(1_000, 60_000)
        else -> StillMs
    }
    LaunchedEffect(current.momentId, paused) {
        if (paused) { progress.stop(); return@LaunchedEffect }
        val left = ((1f - progress.value) * duration).toInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(left, easing = LinearEasing))
        onDone()
    }
    Row(
        Modifier.fillMaxWidth().height(2.5.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo((index + progress.value) / moments.size, 0f..1f); contentDescription = "Moment ${index + 1} of ${moments.size}" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        moments.forEachIndexed { i, _ ->
            val fill = when { i < index -> 1f; i == index -> progress.value; else -> 0f }
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.ink.copy(alpha = 0.38f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(c.ink))
            }
        }
    }
}

@Composable
private fun GlassButton(
    icon: CircasoIcon, label: String, filled: Boolean = false,
    onClick: () -> Unit,
) {
    val c = CircasoTheme.colors
    Box(
        Modifier.size(CircasoSizes.controlMd).clip(CircasoShapes.circle).then(if (filled) Modifier.background(c.ink) else Modifier.glass(CircasoShapes.circle, edge = false))
            .pressable(onClick = onClick).semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        CircasoIconImage(icon, if (filled) c.bg else c.ink, Modifier.size(18.dp))
    }
}



private fun ago(at: Long): String {
    val minutes = ((nowMillis() - at) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes minute${if (minutes == 1L) "" else "s"} ago"
        minutes < 24 * 60 -> "${minutes / 60} hour${if (minutes / 60 == 1L) "" else "s"} ago"
        else -> formatLocalTime(at, LocalTimeStyle.Clock)
    }
}

/** V1b: someone else's moment, More. Muting moves their ring to the end; they aren't told. */
@Composable
private fun BoxScope.MoreSheet(visible: Boolean, name: String, onMute: () -> Unit, onMessage: () -> Unit, onDismiss: () -> Unit) {
    CircasoSheet(visible, onDismiss, label = "Moment options") {
        SheetHead(name.substringBefore(' '), onDismiss)
        ListGroup {
            ListRow("Message ${name.substringBefore(' ')}", onClick = onMessage)
            ListRow("Mute ${name.substringBefore(' ')}’s Moments", sub = "They move to the end of the row. They aren’t told.", onClick = onMute)
        }
    }
}

/** V4: who saw my moment, with their reaction; Delete from here too. */
@Composable
private fun BoxScope.ViewersSheet(state: MomentViewerUiState, onDismiss: () -> Unit, onDelete: () -> Unit, onForward: (() -> Unit)?) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val count = state.viewers?.size ?: state.moment?.viewCount ?: 0
    CircasoSheet(state.viewersOpen, onDismiss, label = "Viewers") {
        SheetHead("Seen by $count", onDismiss)
        // Forward and Delete side by side (design V4). Forward waits until the media is on the phone.
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            CircasoButton("Forward", { onForward?.invoke() }, style = ButtonStyle.Secondary, size = com.circaso.designsystem.components.ButtonSize.Small, icon = CircasoIcon.Forward, enabled = onForward != null)
            CircasoButton("Delete", onDelete, style = ButtonStyle.Destructive, size = com.circaso.designsystem.components.ButtonSize.Small, icon = CircasoIcon.Trash)
        }
        when {
            state.viewersError != null -> BasicText(state.viewersError, Modifier.padding(CircasoSpacing.s4), style = t.subhead.copy(color = c.inkSecondary))
            state.viewers == null -> Spinner(c.inkSecondary, Modifier.padding(CircasoSpacing.s4))
            state.viewers.isEmpty() -> BasicText("Nobody has seen it yet.", Modifier.padding(CircasoSpacing.s4), style = t.subhead.copy(color = c.inkSecondary))
            // Scrolls within the sheet, every viewer, never cut at a number (V4).
            else -> {
                // Each reaction pops in as its row first shows, once per opening of the list: the rows there when it
                // loads one after another, a row scrolled to later as it arrives, never again on scrolling back.
                // Keyed on who and which emoji, so a reaction changed while the list is open pops again.
                val popped = remember { mutableSetOf<String>() }
                val shownAt = remember { nowMillis() }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(CircasoShapes.md).background(c.surfaceRaised)) {
                    items(state.viewers.size, key = { state.viewers[it].viewer.userId }) { i ->
                        val row = state.viewers[i]
                        ListRow(row.name, sub = formatLocalTime(row.viewer.viewedAt, LocalTimeStyle.Clock), leading = { Avatar(row.name, size = CircasoSizes.avatarMd, imageUrl = row.avatarUrl) },
                            trailing = row.viewer.reaction?.let { emoji ->
                                {
                                    val id = "${row.viewer.userId}|$emoji"
                                    androidx.compose.runtime.key(id) {
                                        val play = remember { popped.add(id) }
                                        val delay = remember { if (nowMillis() - shownAt < FirstRowsMillis) reactedBefore(state.viewers, i).coerceAtMost(MaxStaggered) * RowStaggerMillis + SheetSettleMillis else 0 }
                                        ReactionPop(emoji, play, delay, t.title3)
                                    }
                                }
                            })
                    }
                }
            }
        }
    }
}

/** How many rows above [i] have a reaction: only reactions take a place in the stagger. */
private fun reactedBefore(rows: List<ViewerRow>, i: Int): Int = (0 until i).count { rows[it].viewer.reaction != null }

/** Rows on screen when Seen by loads pop one after another, starting once the sheet has settled. */
private const val FirstRowsMillis = 400L
private const val RowStaggerMillis = 45
private const val MaxStaggered = 8
private const val SheetSettleMillis = 120

/** My reaction to this moment: the emoji I chose, or a heart outline that opens the reaction picker. */
@Composable
private fun ReactButton(reaction: String?, online: Boolean, size: androidx.compose.ui.unit.Dp = CircasoSizes.controlMd, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    // Reactions need the server: offline it looks off and says so before the tap (a tap still explains, in a toast).
    Box(
        Modifier.size(size).clip(CircasoShapes.circle).glass(CircasoShapes.circle, edge = false)
            .pressable(onClick = onClick).semantics(mergeDescendants = true) {
                contentDescription = reaction?.let { "Your reaction $it. Change, opens reactions" } ?: "React to this moment, opens reactions"
                if (!online) stateDescription = "Needs a connection"
            },
        contentAlignment = Alignment.Center,
    ) {
        if (reaction != null) BasicText(reaction, Modifier.graphicsLayer { alpha = if (online) 1f else 0.4f }, style = CircasoTheme.typography.title3)
        else CircasoIconImage(CircasoIcon.Heart, if (online) c.ink else c.inkTertiary, Modifier.size(18.dp))
    }
}
