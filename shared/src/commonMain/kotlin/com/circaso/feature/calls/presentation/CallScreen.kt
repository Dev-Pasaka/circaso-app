package com.circaso.feature.calls.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.composed
import kotlinx.coroutines.launch
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.circaso.designsystem.adaptive.readableWidth
import androidx.compose.ui.draw.blur
import coil3.request.crossfade
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.core.calls.CallVideo
import com.circaso.core.calls.VideoSource
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.GoalProgress
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.calls.CallPerson
import com.circaso.feature.calls.CallPhase
import com.circaso.feature.calls.CallUi
import com.circaso.feature.chama.domain.Chama
import com.circaso.feature.chama.presentation.amountNumber
import com.circaso.feature.chama.presentation.amountText
import com.circaso.feature.wallet.domain.FiatValue
import kotlinx.coroutines.delay

/**
 * Calls are drawn on black in every theme (designs H1–H5), like the phone's own call screen: a video picture and white
 * controls read the same in light and dark. These are the call surface's own colours, not theme tokens.
 */
internal object CallColors {
    val Bg = Color(0xFF000000)
    val Ink = Color.White
    val InkSoft = Color.White.copy(alpha = 0.72f)
    val Control = Color.White.copy(alpha = 0.14f)
    val ControlOn = Color.White
    val Panel = Color(0xE61C1C1E)
    val Scrim = Color.Black.copy(alpha = 0.5f)
    /** Over the blurred photo behind H1 and H2. */
    val Backdrop = Color.Black.copy(alpha = 0.6f)
    /** H4's name chip on a tile (`.ti-name`). */
    val NameChip = Color.Black.copy(alpha = 0.45f)
    /** H1's Accept is a white disc with an ink handset; End and Decline the system call red, readable on black. */
    val Accept = Color.White
    val AcceptInk = Color(0xFF0B0B0C)
    val End = Color(0xFFFF3B30)
}

/** What the call screen can do beyond the call itself. */
class CallActions(
    val onAccept: () -> Unit,
    val onDecline: () -> Unit,
    val onEnd: () -> Unit,
    val onMute: () -> Unit,
    val onSpeaker: () -> Unit,
    val onCamera: () -> Unit,
    val onFlip: () -> Unit,
    val onMinimise: (Boolean) -> Unit,
    /** H1's "Message": decline and open the chat. */
    val onMessage: () -> Unit,
    /** H1's "Send money instead": decline, and start the payment in the chat (`#` in its composer). */
    val onSendMoneyInstead: (() -> Unit)?,
    /**
     * H2/H3's "Send money" and H4's contribute (Pasaka, 2026-11-09): the call's chat composer on a sheet over the call,
     * where `#500 for lunch` pays exactly as in the chat while the call keeps going behind it. Draws that sheet, shown
     * while `open`; `onPaid` gets the payment's last state once it is signed (the sheet has closed itself by then).
     * Null where no money can go from this call.
     */
    val moneySheet: (@Composable BoxScope.(open: Boolean, onClose: () -> Unit, onPaid: (com.circaso.feature.wallet.presentation.PayUiState) -> Unit) -> Unit)?,
    /** H4's "Add people": ring the group's members who aren't on the call (CL9). */
    val onAddPeople: () -> Unit = {},
    /** Share screen, and Stop (2026-11-09); null where this phone can't share (iOS until its broadcast extension ships). */
    val onShareScreen: (() -> Unit)? = null,
    /** The More sheet's "Ask Asha" (2026-11-10): her bubble over the call. Null where she can't be summoned. */
    val onAskAsha: (() -> Unit)? = null,
)

/**
 * The call, above every screen. Full screen unless minimised, then a pill at the top that goes back to it. [money] is
 * the newest payment said in this chat ("You sent KES 500 · for lunch", design H2); [chama] the group's goal (H4).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun BoxScope.CallOverlay(
    call: CallUi?,
    actions: CallActions,
    money: String?,
    chama: Chama?,
    fiat: FiatValue?,
    /** The contributions landing on a group call, held for the whole call by the caller (it outlives minimising). */
    celebrations: CallCelebrationsModel? = null,
    myUserId: String? = null,
    /** The gallery's capture of the More sheet. */
    moreAtStart: Boolean = false,
) {
    val still = CircasoTheme.reduceMotion
    // Back on a full-screen call minimises it (never declines or hangs up); the screens under it stay where they were.
    androidx.compose.ui.backhandler.BackHandler(enabled = call != null && !call.minimised && call.phase != CallPhase.Incoming) { actions.onMinimise(true) }
    androidx.compose.ui.backhandler.BackHandler(enabled = call != null && !call.minimised && call.phase == CallPhase.Incoming) {}
    // Who is big, held for the call above the screen so minimising and coming back keeps it.
    val stage = remember(call?.chatId) { CallStageState() }
    AnimatedVisibility(
        call != null && !call.minimised,
        Modifier.matchParentSize(),
        enter = if (still) fadeIn(tween(CircasoMotion.QuickMs)) else fadeIn(tween(CircasoMotion.BaseMs)) + slideInVertically(tween(CircasoMotion.BaseMs)) { it / 6 },
        exit = if (still) fadeOut(tween(CircasoMotion.QuickMs)) else fadeOut(tween(CircasoMotion.QuickMs)) + slideOutVertically(tween(CircasoMotion.QuickMs)) { it / 6 },
    ) {
        if (call != null) CallSurface(call, actions, money, chama, fiat, celebrations, myUserId, stage, moreAtStart)
    }
    AnimatedVisibility(call != null && call.minimised, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(CircasoMotion.BaseMs)), exit = fadeOut(tween(CircasoMotion.QuickMs))) {
        if (call != null) CallPill(call) { actions.onMinimise(false) }
    }
}

@Composable
private fun CallSurface(
    call: CallUi,
    actions: CallActions,
    money: String?,
    chama: Chama?,
    fiat: FiatValue?,
    celebrations: CallCelebrationsModel?,
    myUserId: String?,
    stage: CallStageState,
    moreAtStart: Boolean = false,
) {
    // The money sheet: the call chat's composer over the call (2026-11-09; it replaced H5's form and leaving for Pay).
    var moneyOpen by remember { mutableStateOf(false) }
    val openMoney: (() -> Unit)? = actions.moneySheet?.let { { moneyOpen = true } }
    // The More sheet: everything that isn't money, camera, mic or End (Pasaka, 2026-11-10).
    var moreOpen by remember { mutableStateOf(moreAtStart) }
    val more = MoreState(moreOpen) { moreOpen = !moreOpen }
    // After the PIN: "Your KES 1,000 is on its way to Holiday Fund" on the call for a few seconds, then the goal moves.
    var landed by remember { mutableStateOf<String?>(null) }
    // Keyed on each landing, so a second contribution of the same amount keeps it up its full time.
    var landings by remember { mutableIntStateOf(0) }
    LaunchedEffect(landings) { if (landed != null) { delay(LandedMillis); landed = null } }
    val onPaid: (com.circaso.feature.wallet.presentation.PayUiState) -> Unit = { s ->
        val r = s.result
        if (r != null) {
            // A group's money bubble is its contribution (the chat's `#` pays into the group wallet, or the treasurer):
            // mine shows as on its way at once on my phone; it lands, here as on every phone, when it confirms.
            if (call.group && chama != null) {
                val dollar = com.circaso.feature.wallet.domain.MinorUnits.isDollar(r.amount.asset)
                val mine = com.circaso.feature.chama.domain.GroupContribution(call.chatId, r.transferId, myUserId, null, r.amount.asset, r.amount.amount, if (dollar) r.amount.amountMinor else null, confirmed = false)
                celebrations?.receive(celebrationOf(mine, call.people, myUserId, s.wallet?.fiat ?: fiat, call.myPhotoUrl), confirmed = false, visible = true)
            }
            val to = if (call.group) chama?.goal?.name ?: call.title else s.payee?.displayName?.substringBefore(' ') ?: call.title
            // 1:1 (Pasaka, 2026-11-10): the sender sees the same splash the group call gives, "You sent Mum KES 500",
            // with the coin, the moment the PIN is done; the line under it still says it's on its way until it lands.
            if (!call.group) sentCelebration(r.transferId, r.amount.asset, r.amount.amountMinor, s.fiatText, to, myUserId, call.myPhotoUrl)?.let {
                celebrations?.receive(it, confirmed = true, visible = true)
            }
            landed =(s.fiatText ?: s.usdcText)?.let { "Your $it is on its way to $to" } ?: "On its way to $to"
        } else if (s.requested != null) {
            landed = "Asked the admins of ${s.walletName}. The request is in the group chat."
        }
        landings++
    }
    // Money to me on a 1:1 call (2026-11-10): "Juma sent you KES 500" splashes over the call, rising from low on the
    // screen, and its coin settles into the money line under their name.
    val directAnchors = remember { CelebrationAnchors() }
    Box(
        Modifier.fillMaxSize().background(CallColors.Bg)
            // Nothing under the call surface takes a tap; no ripple, nothing for a screen reader to announce.
            .pointerInput(Unit) { detectTapGestures { } }
            .onPlaced { directAnchors.root = it; directAnchors.tiles = it },
    ) {
        val layout = when {
            call.phase == CallPhase.Incoming -> 0
            call.group -> 1
            call.camera || call.people.any { it.video && it.joined } -> 2
            else -> 3
        }
        // H1 and H2 (and H3 while their camera is off): their photo, blurred and dimmed, behind everything. Once, under
        // the pages, so Accept (H1 to H2) keeps it steady, and with its own fade so a photo already in memory never pops.
        val theirVideo = call.people.any { it.joined && it.video && it.videoMid != null }
        val highContrastBackdrop = com.circaso.designsystem.platform.rememberIsHighContrast()
        val backdropUrl = call.photoUrl
        AnimatedVisibility(
            backdropUrl != null && !highContrastBackdrop && (layout == 0 || layout == 3 || (layout == 2 && !theirVideo)),
            Modifier.matchParentSize(), enter = fadeIn(tween(CircasoMotion.BaseMs)), exit = fadeOut(tween(CircasoMotion.QuickMs)),
        ) {
            // The last photo stays while it fades out.
            val last = remember { arrayOf<String?>(null) }
            CallBackdrop(backdropUrl?.also { last[0] = it } ?: last[0])
        }
        // Voice turning into video (or back) crossfades instead of jumping.
        androidx.compose.animation.AnimatedContent(layout, transitionSpec = { fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs)) }, label = "call") { l ->
            Box(Modifier.fillMaxSize()) {
                when (l) {
                    0 -> Incoming(call, actions)
                    1 -> GroupCall(
                        call, actions, stage, chama, fiat, landed, more,
                        onContribute = if (chama != null) openMoney else null,
                        celebrations = celebrations?.state ?: CelebrationState(),
                        onLanded = { id -> celebrations?.landed(id) },
                        onFinished = { id -> celebrations?.finished(id) },
                    )
                    // "On its way" first, then the payment's own line once its card is in the chat (H2/H3).
                    2 -> VideoCall(call, actions, stage, landed ?: money, openMoney, more, onNotePlaced = { directAnchors.goal = it })
                    else -> VoiceCall(call, actions, landed ?: money, openMoney, more, onNotePlaced = { directAnchors.goal = it })
                }
            }
        }
        if (!call.group && call.phase != CallPhase.Incoming && celebrations != null) {
            val highContrast = com.circaso.designsystem.platform.rememberIsHighContrast()
            androidx.compose.runtime.CompositionLocalProvider(com.circaso.designsystem.theme.LocalCircasoColors provides com.circaso.designsystem.theme.circasoColors(dark = true, highContrast = highContrast)) {
                CelebrationLayer(celebrations.state, directAnchors, onLanded = celebrations::landed, onFinished = celebrations::finished)
            }
            // The flying pill is silent to screen readers: the money line it settles into says it (a polite live region).
        }
        actions.moneySheet?.let { sheet ->
            // Dark over the black call in every theme, as H5's frame (data-theme="dark"); high contrast is kept. The call
            // keeps playing under the sheet's scrim.
            val highContrast = com.circaso.designsystem.platform.rememberIsHighContrast()
            androidx.compose.runtime.CompositionLocalProvider(com.circaso.designsystem.theme.LocalCircasoColors provides com.circaso.designsystem.theme.circasoColors(dark = true, highContrast = highContrast)) {
                sheet(moneyOpen && call.phase != CallPhase.Incoming && call.phase != CallPhase.Ended, { moneyOpen = false }, onPaid)
            }
        }
        CallMoreSheet(moreOpen && call.phase != CallPhase.Incoming && call.phase != CallPhase.Ended, call, actions) { moreOpen = false }
    }
}

/** Whether the More sheet is open, and its button. */
internal class MoreState(val open: Boolean, val onToggle: () -> Unit)

/** How long "on its way" stays on the call. */
private const val LandedMillis = 4_000L

/** Design H1. */
@Composable
private fun BoxScope.Incoming(call: CallUi, actions: CallActions) {
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = CircasoSpacing.s6, vertical = CircasoSpacing.s6), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(CircasoSpacing.s10))
        BasicText(if (call.video) "Circaso video call" else "Circaso voice call", style = t.subhead.copy(color = CallColors.InkSoft))
        Spacer(Modifier.height(CircasoSpacing.s5))
        Pulse { Avatar(call.title, size = 120.dp, imageUrl = call.photoUrl) }
        Spacer(Modifier.height(CircasoSpacing.s5))
        BasicText(call.title, style = t.largeTitle.copy(color = CallColors.Ink, textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Handle(call.handle)
        BasicText(if (call.group) (call.caller?.substringBefore(' ')?.let { "$it is calling the group" } ?: "Group call") else "is calling you…", style = t.body.copy(color = CallColors.InkSoft))
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlButton(CircasoIcon.Chat, "Message", "Reply with a message", actions.onMessage, toggle = false)
            if (actions.onSendMoneyInstead != null) ControlButton(CircasoIcon.Money, "Send money", "Send money instead", actions.onSendMoneyInstead, toggle = false)
        }
        Spacer(Modifier.height(CircasoSpacing.s6))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlButton(CircasoIcon.Phone, "Decline", "Decline", actions.onDecline, tint = Tint.End, big = true)
            ControlButton(if (call.video) CircasoIcon.Video else CircasoIcon.Phone, "Accept", "Accept", actions.onAccept, tint = Tint.Accept, big = true)
        }
    }
}

/** Design H2. */
@Composable
private fun VoiceCall(call: CallUi, actions: CallActions, money: String?, onSendMoney: (() -> Unit)?, more: MoreState, onNotePlaced: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = {}) {
    val t = CircasoTheme.typography
    val short = com.circaso.designsystem.adaptive.LocalWindowLayout.current.short
    val controls: @Composable () -> Unit = {
        CallControlBar(call, actions, onSendMoney, "Send money", voice = true, moreOpen = more.open, onMore = more.onToggle, modifier = Modifier.fillMaxWidth())
    }
    // Framed like every screen: the controls never spread across a tablet or a phone on its side.
    Column(Modifier.fillMaxSize().readableWidth(if (short) com.circaso.designsystem.adaptive.WindowLayout.WideContentWidth else com.circaso.designsystem.adaptive.WindowLayout.ReadableWidth).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = CircasoSpacing.s6, vertical = CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally) {
        TopRow(call, actions)
        if (short) {
            // On its side, as FaceTime audio: who you're talking to on the left, the controls on the right.
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s6)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    SecureLine()
                    Spacer(Modifier.height(CircasoSpacing.s3))
                    Avatar(call.title, size = 88.dp, imageUrl = call.photoUrl)
                    Spacer(Modifier.height(CircasoSpacing.s3))
                    BasicText(call.title, style = t.title1.copy(color = CallColors.Ink, textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Handle(call.handle)
                    Status(call)
                    SharingLine(call, actions.onShareScreen, Modifier.padding(top = CircasoSpacing.s3))
                    money?.let { Spacer(Modifier.height(CircasoSpacing.s3)); MoneyNote(it, Modifier.onPlaced(onNotePlaced)) }
                }
                Column(Modifier.weight(1f)) { controls() }
            }
        } else {
            Spacer(Modifier.height(CircasoSpacing.s6))
            SecureLine()
            Spacer(Modifier.height(CircasoSpacing.s6))
            Avatar(call.title, size = 120.dp, imageUrl = call.photoUrl)
            Spacer(Modifier.height(CircasoSpacing.s4))
            BasicText(call.title, style = t.largeTitle.copy(color = CallColors.Ink, textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Handle(call.handle)
            Status(call)
            SharingLine(call, actions.onShareScreen, Modifier.padding(top = CircasoSpacing.s4))
            money?.let { Spacer(Modifier.height(CircasoSpacing.s5)); MoneyNote(it, Modifier.onPlaced(onNotePlaced)) }
            Spacer(Modifier.weight(1f))
            controls()
        }
    }
}

/**
 * Design H3: them full screen, me in the corner, the controls on a glass bar. Tapping the small picture swaps the two
 * (me big, them small, and back), and the small one can be dragged to any corner (FaceTime; Pasaka, 2026-11-09).
 * Only this phone's view changes.
 */
@Composable
private fun BoxScope.VideoCall(call: CallUi, actions: CallActions, stage: CallStageState, money: String?, onSendMoney: (() -> Unit)?, more: MoreState, onNotePlaced: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = {}) {
    val t = CircasoTheme.typography
    val them = call.people.firstOrNull { it.joined }
    // My picture: the camera, or the screen I'm sharing (drawn as a placeholder, not a mirror of itself).
    val mine = call.camera || call.screen
    // With my picture gone (camera off), it comes back small next time.
    LaunchedEffect(mine) { if (!mine) stage.selfBig = false }
    val meBig = stage.selfBig && mine
    DuoStage(call, them, mine, meBig = meBig, stage.corner, onSwap = { stage.selfBig = !meBig }, onCorner = { stage.corner = it })
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s4)) {
        TopRow(call, actions)
        Column(Modifier.padding(top = CircasoSpacing.s2)) {
            BasicText(call.title, style = t.title2.copy(color = CallColors.Ink))
            Handle(call.handle, center = false)
            Status(call)
            SharingLine(call, actions.onShareScreen, Modifier.padding(top = CircasoSpacing.s2))
        }
        Spacer(Modifier.weight(1f))
        money?.let { MoneyNote(it, Modifier.align(Alignment.CenterHorizontally).onPlaced(onNotePlaced)); Spacer(Modifier.height(CircasoSpacing.s3)) }
        // The glass bar, centred: money, camera, mic, More, End; the rest is on the More sheet.
        CallControlBar(call, actions, onSendMoney, "Send money", voice = false, moreOpen = more.open, onMore = more.onToggle, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

/**
 * H3's two pictures in one layout, so a swap only moves them: each keeps its video surface and is drawn from its old
 * place and size to its new one ([TileMotion]); the one becoming small is drawn on top. The small one sits in [corner]
 * (design H3: top right, under the top row, 104 x 150 on a phone, landscape when the phone is on its side, larger on a
 * tablet), clear of the controls at the bottom. The screen's dimming gradients sit between the two.
 */
@Composable
private fun DuoStage(call: CallUi, them: CallPerson?, mine: Boolean, meBig: Boolean, corner: Corner, onSwap: () -> Unit, onCorner: (Corner) -> Unit) {
    val still = CircasoTheme.reduceMotion
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val themMotion = remember { TileMotion(scope) }
    val meMotion = remember { TileMotion(scope) }
    val spec = remember { com.circaso.designsystem.motion.CircasoSpring.progress() }
    val radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { SelfViewRadius.toPx() }
    val hairlinePx = with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }
    val window = com.circaso.designsystem.adaptive.LocalWindowLayout.current
    val tablet = minOf(window.width, window.height) >= 600.dp
    val (long, narrow) = if (tablet) SelfViewTabletLong to SelfViewTabletShort else SelfViewLong to SelfViewShort
    val landscape = window.width > window.height
    val smallW = if (landscape) long else narrow
    val smallH = if (landscape) narrow else long
    val density = androidx.compose.ui.platform.LocalDensity.current
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    // The stage's size, for where a flung picture settles.
    val stage = remember { floatArrayOf(0f, 0f) }
    val themSmall = mine && meBig
    val meSmall = mine && !meBig
    val them1 = them?.name?.substringBefore(' ')?.ifBlank { null } ?: call.title.substringBefore(' ')
    val release: (TileMotion, Offset) -> Unit = { motion, velocity ->
        val v = motion.visual()
        val next = if (v == null) corner else cornerFor(v.center.x, v.center.y, velocity.x, velocity.y, stage[0], stage[1])
        if (next == corner) motion.settleDrag(still, com.circaso.designsystem.motion.CircasoSpring.resize())
        else onCorner(next)
    }
    androidx.compose.ui.layout.Layout(
        content = {
            Box(
                Modifier.tileMotion(themMotion, still, spec, if (themSmall) radiusPx else 0f, SelfViewHairline, hairlinePx).smallPicture(themSmall, themMotion, "Show yourself big", onSwap, release)
                    .semantics { if (themSmall) { contentDescription = "$them1's video" } },
            ) {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val theirMid = them?.videoMid
                    if (them != null && them.video && theirMid != null) {
                        // A shared screen is shown whole; a face fills the picture.
                        CallVideo(VideoSource.Remote(theirMid), Modifier.fillMaxSize(), fill = !them.screen)
                    } else {
                        Avatar(call.title, size = if (maxWidth < 200.dp) CircasoSizes.avatarLg else 120.dp, imageUrl = them?.photoUrl ?: call.photoUrl)
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(180.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
            }
            if (mine) {
                Box(
                    Modifier.tileMotion(meMotion, still, spec, if (meSmall) radiusPx else 0f, SelfViewHairline, hairlinePx).smallPicture(meSmall, meMotion, "Show $them1 big", onSwap, release)
                        .semantics { if (meSmall) { contentDescription = if (call.screen) "Your shared screen" else "Your camera" } },
                ) {
                    // Mirrored like a mirror when it's the front camera.
                    if (call.screen) ScreenPlaceholder()
                    else CallVideo(VideoSource.Local, Modifier.fillMaxSize(), mirror = call.frontCamera)
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        stage[0] = w.toFloat(); stage[1] = h.toFloat()
        val full = androidx.compose.ui.unit.Constraints.fixed(w, h)
        val sw = smallW.roundToPx()
        val sh = smallH.roundToPx()
        val small = androidx.compose.ui.unit.Constraints.fixed(sw, sh)
        val gutter = CircasoSpacing.s4.roundToPx()
        val left = insets.getLeft(density, direction) + gutter
        val right = w - insets.getRight(density, direction) - gutter - sw
        val top = insets.getTop(density) + SelfViewTop.roundToPx()
        // On the left it sits under H3's name, timer and sharing line rather than over them.
        val topStart = insets.getTop(density) + SelfViewTopStart.roundToPx()
        val bottom = h - insets.getBottom(density) - SelfViewBottom.roundToPx() - sh
        val rtl = direction == androidx.compose.ui.unit.LayoutDirection.Rtl
        val (x, y) = when (corner) {
            Corner.TopStart -> (if (rtl) right else left) to topStart
            Corner.TopEnd -> (if (rtl) left else right) to top
            Corner.BottomStart -> (if (rtl) right else left) to bottom
            Corner.BottomEnd -> (if (rtl) left else right) to bottom
        }
        val themPlaceable = measurables[0].measure(if (themSmall) small else full)
        val shade = measurables[1].measure(full)
        val me = measurables.getOrNull(2)?.measure(if (meSmall) small else full)
        layout(w, h) {
            if (themSmall) themPlaceable.place(x, y, zIndex = 2f) else themPlaceable.place(0, 0)
            shade.place(0, 0, zIndex = 1f)
            if (me != null) { if (meSmall) me.place(x, y, zIndex = 2f) else me.place(0, 0) }
        }
    }
}

/**
 * The small picture's handling: tap to swap, drag to a corner, a light press. Its rounded corners and hairline (design
 * H3) are drawn by its [TileMotion], at screen size through the whole move. The big one has none of it.
 */
private fun Modifier.smallPicture(small: Boolean, motion: TileMotion, swapLabel: String, onSwap: () -> Unit, onRelease: (TileMotion, Offset) -> Unit): Modifier = composed {
    // The gesture below lives as long as the picture; it must always settle into the corner as it is now.
    val release by androidx.compose.runtime.rememberUpdatedState(onRelease)
    if (!small) return@composed this
    this
        .background(CallColors.Panel)
        .pressable(role = Role.Button, onClickLabel = swapLabel, onClick = onSwap)
        .pointerInput(Unit) {
            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
            detectDragGestures(
                onDragStart = { motion.grab(); tracker.resetTracking() },
                onDragEnd = { val v = tracker.calculateVelocity(); release(motion, Offset(v.x, v.y)) },
                onDragCancel = { release(motion, Offset.Zero) },
            ) { change, amount ->
                change.consume()
                motion.drag += amount
                // Tracked by how far the picture has moved: the finger's own position moves with the picture.
                tracker.addPosition(change.uptimeMillis, motion.drag)
            }
        }
}

/** My tile while I share my screen: not the screen itself (a picture of the picture), but what it is. */
@Composable
private fun ScreenPlaceholder() {
    val t = CircasoTheme.typography
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(CallColors.Control), contentAlignment = Alignment.Center) {
        val narrow = maxWidth < 160.dp
        val tiny = maxWidth < 96.dp
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            CircasoIconImage(CircasoIcon.ScreenShare, CallColors.Ink, Modifier.size(if (narrow) 24.dp else 32.dp))
            if (!tiny) BasicText("Your screen", style = t.footnote.copy(color = CallColors.InkSoft, fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

/**
 * Who is sharing a screen: "You're sharing your screen · Stop" on the sharer's phone, "Amina is sharing their screen"
 * on everyone else's. Nothing when nobody is.
 */
@Composable
private fun SharingLine(call: CallUi, onStop: (() -> Unit)?, modifier: Modifier = Modifier) {
    val t = CircasoTheme.typography
    val sharer = call.people.firstOrNull { it.screen && it.joined }
    val text = when {
        call.screen -> "You're sharing your screen"
        sharer != null -> "${sharer.name.substringBefore(' ')} is sharing their screen"
        else -> null
    }
    androidx.compose.animation.AnimatedVisibility(text != null, modifier, enter = fadeIn(tween(CircasoMotion.BaseMs)), exit = fadeOut(tween(CircasoMotion.QuickMs))) {
        val last = remember { arrayOf("") }
        val shown = text?.also { last[0] = it } ?: last[0]
        Row(
            Modifier.clip(CircasoShapes.pill).background(CallColors.Panel)
                .semantics(mergeDescendants = false) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
                .padding(start = CircasoSpacing.s3, end = if (call.screen && onStop != null) CircasoSpacing.s1 else CircasoSpacing.s3, top = CircasoSpacing.s1, bottom = CircasoSpacing.s1),
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically,
        ) {
            CircasoIconImage(CircasoIcon.ScreenShare, CallColors.Ink, Modifier.size(16.dp))
            BasicText(shown, Modifier.weight(1f, fill = false).padding(vertical = CircasoSpacing.s1), style = t.footnote.copy(color = CallColors.Ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (call.screen && onStop != null) {
                // A full touch target, ink on white (the controls' "on" style) so it reads at any size.
                Box(
                    Modifier.sizeIn(minHeight = CircasoSizes.controlMd, minWidth = CircasoSizes.controlMd).clip(CircasoShapes.pill).background(CallColors.ControlOn)
                        .pressable(role = Role.Button, onClick = onStop).semantics { contentDescription = "Stop sharing your screen" }
                        .padding(horizontal = CircasoSpacing.s4),
                    contentAlignment = Alignment.Center,
                ) { BasicText("Stop", style = t.subhead.copy(color = CallColors.AcceptInk, fontWeight = FontWeight.SemiBold), modifier = Modifier.clearAndSetSemantics {}) }
            }
        }
    }
}

/** Design H4: everyone in a grid, the goal live under them. */
@Composable
private fun GroupCall(
    call: CallUi,
    actions: CallActions,
    stage: CallStageState,
    chama: Chama?,
    fiat: FiatValue?,
    landed: String?,
    more: MoreState,
    onContribute: (() -> Unit)?,
    celebrations: CelebrationState = CelebrationState(),
    onLanded: (String) -> Unit = {},
    onFinished: (String) -> Unit = {},
) {
    val t = CircasoTheme.typography
    // Only people who are really heard count as on the call.
    val onCall = call.people.count { it.live } + 1
    // Where a contribution rises from (low in the tiles, clear of "on its way") and where its coin settles (the goal's
    // raised amount), measured from the live layout so a phone on its side or a tablet's split view aims true.
    val anchors = remember { CelebrationAnchors() }
    Box(Modifier.fillMaxSize().onPlaced { anchors.root = it }) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(CircasoIcon.Minimise, "Minimise call") { actions.onMinimise(true) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                // The group's photo beside its name, so ringing and connecting say which group at a glance.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    Avatar(call.title, size = CircasoSizes.avatarSm, imageUrl = call.photoUrl)
                    BasicText(call.title, style = t.headline.copy(color = CallColors.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val time = elapsedText(call.activeSince)
                BasicText(if (call.phase == CallPhase.Active) "$onCall on the call · $time" else statusText(call), style = t.footnote.copy(color = CallColors.InkSoft))
            }
            if (call.notOnCall.isNotEmpty() && call.phase == CallPhase.Active) RoundIcon(CircasoIcon.Plus, "Add people: ring ${call.notOnCall.joinToString { it.name.substringBefore(' ') }}") { actions.onAddPeople() }
            else Spacer(Modifier.size(44.dp))
        }
        val tiles = call.people.filter { it.joined || it.ringing }
        // Tap a tile to see it big, the others in a strip; tap it again for the grid (Pasaka, 2026-11-09). A screen
        // someone starts sharing takes the spotlight by itself, unless this phone already chose one.
        // Someone else's screen (mine on my own phone is only a placeholder, never worth the spotlight). The share's
        // spotlight goes when the share does; one the person tapped stays.
        val sharer = tiles.firstOrNull { it.screen && it.joined }?.userId
        LaunchedEffect(sharer) {
            if (sharer == stage.lastSharer) return@LaunchedEffect
            val (next, auto) = spotlightAfterShare(stage.chosen, stage.auto, sharer, stage.lastSharer)
            stage.chosen = next
            stage.auto = auto
            stage.lastSharer = sharer
        }
        val ids = tiles.map { it.userId } + MeTileId
        val spotlight = spotlightFor(stage.chosen, ids)
        // The spotlit person left: back to the grid, and not back in the spotlight if they rejoin.
        LaunchedEffect(spotlight) { if (spotlight == null) { stage.chosen = null; stage.auto = null } }
        val tapLabel: (String) -> String = { id ->
            val name = if (id == MeTileId) "yourself" else tiles.firstOrNull { it.userId == id }?.name?.substringBefore(' ') ?: "them"
            if (id == spotlight) "Show everyone" else "Show $name big"
        }
        // Everyone fits on screen at the largest size the space allows, as FaceTime lays out a group: more columns as
        // the screen widens, never a tile stretched or cut off. On a phone on its side the live goal moves beside the
        // tiles so they keep their height.
        val beside = com.circaso.designsystem.adaptive.LocalWindowLayout.current.short && chama != null
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            Box(Modifier.weight(1f).fillMaxHeight().onPlaced { anchors.tiles = it }, contentAlignment = Alignment.BottomCenter) {
                // Someone who just left is still drawn while their tile fades out.
            val lastSeen = remember { mutableMapOf<String, CallPerson>() }
            androidx.compose.runtime.SideEffect { tiles.forEach { lastSeen[it.userId] = it } }
            FittedTiles(ids, Modifier.fillMaxSize(), spotlight = spotlight, onTap = { id -> stage.chosen = spotlightAfterTap(spotlight, id); stage.auto = null }, tapLabel = tapLabel) { id ->
                if (id == MeTileId) MeTile(call) else (tiles.firstOrNull { it.userId == id } ?: lastSeen[id])?.let { Tile(it) }
            }
                // Who is sharing a screen, over the tiles' top edge (overlaid, so the tiles never re-layout when it comes and goes).
                SharingLine(call, actions.onShareScreen, Modifier.align(Alignment.TopCenter).padding(top = CircasoSpacing.s2))
                // H5 after the PIN: a short line that the contribution is on its way, over the grid's lower edge just above
                // the live goal (a transform only, so the video tiles aren't measured again each frame).
                val reduce = CircasoTheme.reduceMotion
                androidx.compose.animation.AnimatedVisibility(
                    landed != null, Modifier.padding(bottom = CircasoSpacing.s2),
                    enter = fadeIn(tween(CircasoMotion.BaseMs)) + if (reduce) androidx.compose.animation.EnterTransition.None else androidx.compose.animation.slideInVertically(tween(CircasoMotion.BaseMs)) { it / 2 },
                    exit = fadeOut(tween(CircasoMotion.QuickMs)),
                ) {
                    // The text stays while it fades out, after [landed] has gone.
                    val last = remember { arrayOf("") }
                    Landed(landed?.also { last[0] = it } ?: last[0])
                }
            }
            // `beside` already means a goal; the check again lets the compiler know it isn't null.
            if (beside && chama != null) Box(Modifier.width(GoalBesideWidth).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) { LiveGoal(chama, fiat, celebrations, onRaisedPlaced = { anchors.goal = it }) }
        }
        if (!beside && chama != null) Box(Modifier.readableWidth()) { LiveGoal(chama, fiat, celebrations, onRaisedPlaced = { anchors.goal = it }) }
        // The same bar as every call; the contribute button is H4's money moment.
        CallControlBar(
            call, actions, if (chama != null) onContribute else null, chama?.let { "Contribute to ${it.goal.name}" } ?: "Contribute",
            // A group voice call (nobody asked for video, my camera off) offers the camera as H2 does, not as "off".
            voice = !call.video && !call.camera, moreOpen = more.open, onMore = more.onToggle, modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
        // Above the tiles, the goal and the controls; it takes no touches. On the H4 panel's dark money colours.
        val highContrast = com.circaso.designsystem.platform.rememberIsHighContrast()
        androidx.compose.runtime.CompositionLocalProvider(com.circaso.designsystem.theme.LocalCircasoColors provides com.circaso.designsystem.theme.circasoColors(dark = true, highContrast = highContrast)) {
            CelebrationLayer(celebrations, anchors, onLanded, onFinished)
        }
    }
}

@Composable
private fun Tile(p: CallPerson) {
    val t = CircasoTheme.typography
    val mid = p.videoMid
    // Wider than tall, so six people fit on a phone without scrolling (design H4's 2 x 3 grid).
    // The tile reads as one: who, and whether they are ringing or still connecting (on a narrow tile only the name's
    // softer colour shows it, so screen readers get it in words).
    val state = when { p.ringing -> ", ringing"; p.joined && !p.live -> ", connecting"; !p.audio && p.joined -> ", muted"; else -> "" } +
        if (p.screen) ", sharing their screen" else ""
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().clip(CircasoShapes.lg).background(CallColors.Control)
            .semantics(mergeDescendants = true) { contentDescription = p.name + state },
    ) {
        // A small tile (a phone on its side, a big call) keeps its face clear of the name under it.
        val small = maxHeight < SmallTileHeight
        val wide = maxWidth >= WideTileWidth
        // A shared screen is shown whole, never cropped.
        if (p.video && mid != null) CallVideo(VideoSource.Remote(mid), Modifier.fillMaxSize(), fill = !p.screen)
        else PersonFill(p.name, p.photoUrl, present = p.live, avatarSize = if (small) CircasoSizes.avatarMd else CircasoSizes.avatarLg)
        // H4's name chip: readable on any photo or video.
        Row(
            Modifier.align(Alignment.BottomStart).padding(CircasoSpacing.s2).clip(CircasoShapes.pill).background(CallColors.NameChip)
                .padding(horizontal = CircasoSpacing.s2, vertical = CircasoSpacing.s1),
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1), verticalAlignment = Alignment.CenterVertically,
        ) {
            // Ringing or still connecting: the name in the soft colour says so on a narrow tile, in words on a wide one.
            val waiting = p.ringing || (p.joined && !p.live)
            BasicText(
                p.name.substringBefore(' '), Modifier.weight(1f, fill = false),
                style = t.footnote.copy(color = if (waiting && !wide) CallColors.InkSoft else CallColors.Ink, fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!p.audio && p.joined) CircasoIconImage(CircasoIcon.MicOff, CallColors.Ink, Modifier.size(14.dp))
            if (p.screen) CircasoIconImage(CircasoIcon.ScreenShare, CallColors.Ink, Modifier.size(14.dp))
            if (wide) {
                if (p.ringing) BasicText("· ringing", style = t.footnote.copy(color = CallColors.InkSoft), maxLines = 1)
                else if (p.joined && !p.live) BasicText("· connecting", style = t.footnote.copy(color = CallColors.InkSoft), maxLines = 1)
            }
        }
    }
}

@Composable
private fun MeTile(call: CallUi) {
    val t = CircasoTheme.typography
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().clip(CircasoShapes.lg).background(CallColors.Control)) {
        val small = maxHeight < SmallTileHeight
        if (call.screen) ScreenPlaceholder()
        else if (call.camera) CallVideo(VideoSource.Local, Modifier.fillMaxSize(), mirror = call.frontCamera)
        else PersonFill("You", call.myPhotoUrl, avatarSize = if (small) CircasoSizes.avatarMd else CircasoSizes.avatarLg)
        BasicText("You", style = t.footnote.copy(color = CallColors.Ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.align(Alignment.BottomStart).padding(CircasoSpacing.s2).clip(CircasoShapes.pill).background(CallColors.NameChip).padding(horizontal = CircasoSpacing.s2, vertical = CircasoSpacing.s1))
    }
}

/** Below this height a tile's avatar steps down a size so the name under it never runs across the face. */
private val SmallTileHeight = 120.dp

/** From this width a tile says "ringing" or "connecting" in words beside the name. */
private val WideTileWidth = 140.dp

/**
 * The tiles laid out at the largest size that fits the space without scrolling (FaceTime's group grid): every column
 * count is tried and the biggest tile kept, each tile wider than tall (design H4's 1.15). Rows are centred, the last
 * one too. With so many people that a tile would be smaller than [MinTile], it keeps that size and scrolls.
 *
 * One layout, each tile keyed by [ids]: when someone joins or leaves, or the columns change (rotation, split view),
 * a tile only moves, on a spring, to its new place; its video surface is never torn down and built again.
 *
 * With a [spotlight] (2026-11-09) the same tiles are laid out again: that one big, everyone else in a strip under it
 * (a phone upright) or beside it (a wider space: on its side, a tablet), each strip tile as large as the strip allows.
 * Tiles grow and shrink into place from where they were ([TileMotion]), so going in and out of the spotlight is one
 * continuous move, not a new screen.
 */
@Composable
private fun FittedTiles(
    ids: List<String>,
    modifier: Modifier = Modifier,
    spotlight: String? = null,
    onTap: ((String) -> Unit)? = null,
    tapLabel: (String) -> String = { "" },
    tile: @Composable (String) -> Unit,
) {
    val still = CircasoTheme.reduceMotion
    val motionScope = androidx.compose.runtime.rememberCoroutineScope()
    val motionSpec = remember { com.circaso.designsystem.motion.CircasoSpring.progress() }
    val tileRadius = with(androidx.compose.ui.platform.LocalDensity.current) { SelfViewRadius.toPx() }
    // Someone leaving fades out in place for a moment before the others close the gap; someone joining fades and
    // grows in while the others move aside. The tiles there when the call opened are simply there.
    val first = remember { ids.toSet() }
    var shown by remember { androidx.compose.runtime.mutableStateOf(ids) }
    val leaving = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    androidx.compose.runtime.LaunchedEffect(ids) {
        val gone = shown.filter { it !in ids }
        if (gone.isEmpty() || still) { leaving.clear(); shown = ids; return@LaunchedEffect }
        leaving.addAll(gone.filter { it !in leaving })
        shown = shown + ids.filter { it !in shown }
        kotlinx.coroutines.delay(CircasoMotion.QuickMs.toLong())
        leaving.clear()
        shown = ids
    }
    val laidOut = if (leaving.isEmpty()) ids else shown
    val spot = spotlight?.takeIf { it in laidOut }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val gap = CircasoSpacing.s2
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        var (cols, width) = fitTiles(laidOut.size, viewportWidth, viewportHeight, gap, TileAspect)
        if (width < MinTile) {
            cols = ((viewportWidth + gap) / (MinTile + gap)).toInt().coerceAtLeast(1)
            width = (viewportWidth - gap * (cols - 1)) / cols
        }
        val scroll = androidx.compose.foundation.rememberScrollState()
        androidx.compose.ui.layout.Layout(
            content = {
                laidOut.forEach { id ->
                    androidx.compose.runtime.key(id) {
                        val appear = remember { androidx.compose.animation.core.Animatable(if (still || id in first) 1f else 0f) }
                        androidx.compose.runtime.LaunchedEffect(Unit) { appear.animateTo(1f, tween(CircasoMotion.BaseMs)) }
                        val fade by androidx.compose.animation.core.animateFloatAsState(if (id in leaving) 0f else 1f, tween(CircasoMotion.QuickMs), label = "leave")
                        val motion = remember { TileMotion(motionScope) }
                        Box(
                            Modifier.tileMotion(motion, still, motionSpec, tileRadius).graphicsLayer {
                                alpha = appear.value * fade
                                val scale = 0.92f + 0.08f * appear.value * fade
                                scaleX = scale; scaleY = scale
                            }.then(
                                if (onTap == null) Modifier
                                else Modifier.pressable(role = Role.Button, onClickLabel = tapLabel(id)) { onTap(id) },
                            ),
                        ) { tile(id) }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().verticalScroll(scroll, enabled = spot == null && scroll.maxValue > 0),
        ) { measurables, _ ->
            val gapPx = gap.roundToPx()
            val w = viewportWidth.roundToPx()
            val viewH = viewportHeight.roundToPx()
            val spotAt = spot?.let { laidOut.indexOf(it) }?.takeIf { it >= 0 }
            if (spotAt != null) {
                // The spotlight and the strip of everyone else, all inside the viewport (no scrolling).
                val others = measurables.size - 1
                val side = viewportWidth > viewportHeight
                val strip = when {
                    others == 0 -> 0
                    side -> minOf(StripMaxWidth.roundToPx(), (w * 0.3f).toInt())
                    else -> minOf(StripMaxHeight.roundToPx(), (viewH * 0.28f).toInt())
                }
                val bigW = if (side && others > 0) w - strip - gapPx else w
                val bigH = if (!side && others > 0) viewH - strip - gapPx else viewH
                val (stripCols, stripWidthDp) = if (others == 0) 1 to 0.dp else if (side) fitTiles(others, strip.toDp(), viewportHeight, gap, TileAspect) else fitTiles(others, viewportWidth, strip.toDp(), gap, TileAspect)
                val sw = stripWidthDp.roundToPx().coerceAtLeast(1)
                val sh = (stripWidthDp / TileAspect).roundToPx().coerceAtLeast(1)
                val stripRows = if (others == 0) 0 else (others + stripCols - 1) / stripCols
                val placeables = measurables.mapIndexed { i, m ->
                    m.measure(if (i == spotAt) androidx.compose.ui.unit.Constraints.fixed(bigW, bigH) else androidx.compose.ui.unit.Constraints.fixed(sw, sh))
                }
                layout(w, viewH) {
                    var k = 0
                    // The strip's own grid, centred in its band.
                    val stripLeft = if (side) bigW + gapPx else 0
                    val stripTop = if (side) 0 else bigH + gapPx
                    val bandW = if (side) strip else w
                    val bandH = if (side) viewH else strip
                    val gridH = stripRows * sh + (stripRows - 1).coerceAtLeast(0) * gapPx
                    placeables.forEachIndexed { i, p ->
                        if (i == spotAt) { p.place(0, 0); return@forEachIndexed }
                        val row = k / stripCols
                        val inRow = minOf(stripCols, others - row * stripCols)
                        val left = stripLeft + (bandW - (inRow * sw + (inRow - 1) * gapPx)) / 2
                        val top = stripTop + (bandH - gridH) / 2
                        p.place(left + (k % stripCols) * (sw + gapPx), top + row * (sh + gapPx))
                        k++
                    }
                }
            } else {
                val tileW = width.roundToPx()
                val tileH = (width / TileAspect).roundToPx()
                val rows = (measurables.size + cols - 1) / cols
                val contentH = rows * tileH + (rows - 1).coerceAtLeast(0) * gapPx
                val h = maxOf(contentH, viewH)
                val placeables = measurables.map { it.measure(androidx.compose.ui.unit.Constraints.fixed(tileW, tileH)) }
                layout(w, h) {
                    val top = (h - contentH) / 2
                    placeables.forEachIndexed { i, p ->
                        val row = i / cols
                        val inRow = minOf(cols, placeables.size - row * cols)
                        val left = (w - (inRow * tileW + (inRow - 1) * gapPx)) / 2
                        p.place(left + (i % cols) * (tileW + gapPx), top + row * (tileH + gapPx))
                    }
                }
            }
        }
    }
}


/** The key of the person's own tile, after everyone else's. */
private const val MeTileId = "me"

/** A call tile is a little wider than tall (design H4: six people fit a phone in a 2 x 3 grid). */
private const val TileAspect = 1.15f

/**
 * The column count that gives [count] tiles of [aspect] (width / height) the largest width inside [width] x [height]
 * with [gap] between them, and that width.
 */
internal fun fitTiles(count: Int, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, gap: androidx.compose.ui.unit.Dp, aspect: Float): Pair<Int, androidx.compose.ui.unit.Dp> {
    var bestCols = 1
    var bestWidth = 0.dp
    for (cols in 1..maxOf(count, 1)) {
        val rows = (count + cols - 1) / cols
        val byWidth = (width - gap * (cols - 1)) / cols
        val byHeight = ((height - gap * (rows - 1)) / rows) * aspect
        val w = minOf(byWidth, byHeight)
        if (w > bestWidth) { bestWidth = w; bestCols = cols }
    }
    return bestCols to bestWidth
}

/** Below this a tile can't show a face: the grid scrolls instead of shrinking further. */
private val MinTile = 96.dp

/** Design H3's self-view, 104 x 150 on a phone, and a tablet's in proportion to its screen; [SelfViewTop] clears the top row. */
private val SelfViewLong = 150.dp
private val SelfViewShort = 104.dp
private val SelfViewTabletLong = 220.dp
private val SelfViewTabletShort = 152.dp
private val SelfViewTop = 64.dp
/** A self-view moved to a bottom corner stays this far above the screen's bottom edge, clear of the control bar. */
private val SelfViewBottom = 132.dp
/** The small picture's corners, as CircasoShapes.lg (animated in as it shrinks, so the value is needed on its own). */
private val SelfViewRadius = 22.dp
/** H3's hairline round the self-view (`0 0 0 1px rgba(255,255,255,.25)`). */
private val SelfViewHairline = Color.White.copy(alpha = 0.25f)
/** The self-view in the top-left corner sits under H3's name, timer and sharing line. */
private val SelfViewTopStart = 188.dp

/** In a group's spotlight, the strip of everyone else is at most this tall on a phone, or this wide beside the spotlight. */
private val StripMaxHeight = 120.dp
private val StripMaxWidth = 220.dp

/** The live goal's column beside the tiles on a phone on its side. */
private val GoalBesideWidth = 280.dp

/** H4's goal card: "LIVE · contributions land as they happen", moving as the frames arrive. */
@Composable
private fun LiveGoal(chama: Chama, fiat: FiatValue?, celebrations: CelebrationState = CelebrationState(), onRaisedPlaced: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = {}) {
    val t = CircasoTheme.typography
    val c = CircasoTheme.colors
    val still = CircasoTheme.reduceMotion
    // Each coin that settles in gives the panel one soft pulse and a light tap (2026-11-09); no pulse under Reduce Motion.
    val pulse = remember { androidx.compose.animation.core.Animatable(0f) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val seenLandings = remember { intArrayOf(celebrations.landings) }
    LaunchedEffect(celebrations.landings) {
        if (celebrations.landings <= seenLandings[0]) return@LaunchedEffect
        seenLandings[0] = celebrations.landings
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        if (still) return@LaunchedEffect
        pulse.animateTo(1f, tween(CircasoMotion.QuickMs))
        pulse.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))
    }
    // The raised amount counts up to the new total once the coin is in (held while one is in the air, so number, coin
    // and pulse arrive together); a reload with nothing flying counts up at once. Reduce Motion: it just changes.
    val target = chama.contributedMinor.toLongOrNull() ?: 0L
    val raised = remember { androidx.compose.animation.core.Animatable(target.toFloat()) }
    val flying = celebrations.shown.isNotEmpty()
    LaunchedEffect(target, flying, still) {
        if (flying) return@LaunchedEffect
        if (still || raised.value > target) raised.snapTo(target.toFloat()) else raised.animateTo(target.toFloat(), tween(CircasoMotion.BaseMs))
    }
    val raisedMinor = if (raised.isRunning || flying) raised.value.toLong() else target
    Column(
        Modifier.fillMaxWidth()
            .graphicsLayer { val s = 1f + PulseScale * pulse.value; scaleX = s; scaleY = s }
            .clip(CircasoShapes.lg).background(CallColors.Panel).padding(CircasoSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
            BasicText("LIVE", style = t.caption1.copy(color = Color.White, fontWeight = FontWeight.Bold), modifier = Modifier.clip(CircasoShapes.pill).background(c.critical).padding(horizontal = 6.dp, vertical = 2.dp))
            BasicText("Contributions land as they happen", style = t.footnote.copy(color = CallColors.InkSoft))
        }
        // The goal component, on the call's dark panel.
        CompositionWhite {
            GoalProgress(
                amountText(raisedMinor.toString(), fiat), amountNumber(chama.goal.targetMinor, fiat), chama.progress,
                meta = com.circaso.feature.chama.presentation.goalMeta(chama), label = "${chama.goal.name} progress",
                raisedModifier = Modifier.onPlaced(onRaisedPlaced),
            )
        }
        // H4: the latest contributions under the goal ("Juma +KES 2,500 · Wanjiru +1,000"), newest first, after any still
        // on their way. It opens the first time one arrives rather than pushing the tiles up in one frame.
        val pills = celebrations.pending.map { it to true } + celebrations.recent.map { it to false }
        androidx.compose.animation.AnimatedVisibility(
            pills.isNotEmpty(),
            enter = if (still) fadeIn(tween(CircasoMotion.QuickMs)) else fadeIn(tween(CircasoMotion.BaseMs)) + androidx.compose.animation.expandVertically(tween(CircasoMotion.BaseMs)),
            exit = fadeOut(tween(CircasoMotion.QuickMs)) + if (still) androidx.compose.animation.ExitTransition.None else androidx.compose.animation.shrinkVertically(tween(CircasoMotion.QuickMs)),
        ) {
            val last = remember { arrayOf<List<Pair<Celebration, Boolean>>>(emptyList()) }
            RecentContributions(pills.ifEmpty { last[0] }.also { last[0] = it }.take(CelebrationState.MaxRecent))
        }
    }
}

/** The goal panel's pulse at its peak; a panel as wide as a tablet's reading width needs no more. */
private const val PulseScale = 0.02f

/**
 * H4's row of `.ti-pill`s: the newest at full strength, the one before it softer; each slides in as it arrives. One
 * still on its way says so and stays dim until the chain confirms it.
 */
@Composable
private fun RecentContributions(pills: List<Pair<Celebration, Boolean>>) {
    val t = CircasoTheme.typography
    val still = CircasoTheme.reduceMotion
    val highContrast = com.circaso.designsystem.platform.rememberIsHighContrast()
    androidx.compose.runtime.CompositionLocalProvider(com.circaso.designsystem.theme.LocalCircasoColors provides com.circaso.designsystem.theme.circasoColors(dark = true, highContrast = highContrast)) {
        val c = CircasoTheme.colors
        Row(
            Modifier.fillMaxWidth().clip(CircasoShapes.pill).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
        ) {
            pills.forEachIndexed { i, (r, pending) ->
                androidx.compose.runtime.key(r.id) {
                    val appear = remember { androidx.compose.animation.core.Animatable(if (still) 1f else 0f) }
                    LaunchedEffect(Unit) { appear.animateTo(1f, tween(CircasoMotion.BaseMs)) }
                    val strength by androidx.compose.animation.core.animateFloatAsState(
                        when { pending -> 0.6f; i == 0 -> 1f; else -> 0.75f }, tween(CircasoMotion.BaseMs), label = "recent",
                    )
                    Row(
                        Modifier
                            .graphicsLayer { alpha = appear.value * strength; translationX = (1f - appear.value) * -CircasoSpacing.s4.toPx() }
                            .clip(CircasoShapes.pill)
                            .background(moneyPillBrush(c.moneyCardSheen, c.moneyCard))
                            .semantics(mergeDescendants = true) { contentDescription = if (pending) "${r.spoken}, on its way" else r.spoken }
                            .padding(start = CircasoSpacing.s2, end = CircasoSpacing.s3, top = CircasoSpacing.s2, bottom = CircasoSpacing.s2),
                        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(r.who, size = PillAvatar, imageUrl = r.photoUrl)
                        BasicText(r.text, Modifier.weight(1f, fill = false), style = t.footnote.copy(color = c.onMoney, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (pending) CircasoIconImage(CircasoIcon.Clock, c.onMoney, Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TopRow(call: CallUi, actions: CallActions) {
    Row(Modifier.fillMaxWidth()) { RoundIcon(CircasoIcon.Minimise, "Minimise call") { actions.onMinimise(true) } }
}

/** Media goes through Cloudflare's SFU encrypted in transit, not end to end, so the line says "Secure", not more. */
@Composable
private fun SecureLine() {
    val t = CircasoTheme.typography
    BasicText("Secure call · Free over the internet", style = t.footnote.copy(color = CallColors.InkSoft))
}

/** The "@username" under a full name, as X shows people. */
@Composable
private fun Handle(handle: String?, center: Boolean = true) {
    if (handle == null) return
    val t = CircasoTheme.typography
    BasicText(handle, style = t.subhead.copy(color = CallColors.InkSoft, textAlign = if (center) TextAlign.Center else TextAlign.Start), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Status(call: CallUi) {
    val t = CircasoTheme.typography
    BasicText(statusText(call), style = t.body.copy(color = CallColors.InkSoft))
    call.error?.let { BasicText(it, style = t.footnote.copy(color = CallColors.Ink, textAlign = TextAlign.Center), modifier = Modifier.padding(top = CircasoSpacing.s2)) }
}

@Composable
private fun statusText(call: CallUi, short: Boolean = false): String = when (call.phase) {
    CallPhase.Incoming -> "Incoming…"
    CallPhase.Ringing -> "Ringing…"
    // Joined isn't heard: until their audio or video arrives it says who it's reaching, and no timer runs.
    CallPhase.Connecting -> connectingTo(call).takeUnless { short }?.let { "Connecting to $it…" } ?: "Connecting…"
    CallPhase.Reconnecting -> "Reconnecting…"
    CallPhase.Active -> elapsedText(call.activeSince)
    CallPhase.Ended -> when (call.endReason) { "DECLINED" -> "Declined"; "MISSED" -> "No answer"; "CANCELLED" -> "Cancelled"; "FAILED" -> "Call failed"; else -> "Call ended" }
}

/** Who a call is still reaching: the other person, or in a group the first who joined but isn't heard yet. */
private fun connectingTo(call: CallUi): String? {
    val name = if (call.group) call.people.firstOrNull { it.joined && !it.live }?.name else call.people.firstOrNull()?.name ?: call.title
    return name?.substringBefore(' ')?.takeIf { it.isNotBlank() }
}

/** "04:12", ticking once a second. */
@Composable
private fun elapsedText(since: Long?): String {
    var now by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(since) { while (since != null) { now = nowMillis(); delay(1_000) } }
    val s = ((now - (since ?: now)) / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}" else "${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}"
}

/** "Your KES 1,000 is on its way to Holiday Fund": the money's colours, announced once. */
@Composable
private fun Landed(text: String) {
    val t = CircasoTheme.typography
    val c = CircasoTheme.colors
    Row(
        Modifier.clip(CircasoShapes.pill).background(c.money)
            .semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
            .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically,
    ) {
        CircasoIconImage(CircasoIcon.Check, c.onMoney, Modifier.size(16.dp))
        BasicText(text, style = t.footnote.copy(color = c.onMoney, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MoneyNote(text: String, modifier: Modifier = Modifier) {
    val t = CircasoTheme.typography
    Row(
        // Said as it changes ("Juma sent you KES 500" as their money lands on a 1:1 call), once, politely.
        modifier.clip(CircasoShapes.pill).background(CallColors.Control)
            .semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
            .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically,
    ) {
        CircasoIconImage(CircasoIcon.Money, CallColors.Ink, Modifier.size(16.dp))
        BasicText(text, style = t.footnote.copy(color = CallColors.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Minimised: a pill at the top of the app, tap to go back (design H4's minimise). */
@Composable
private fun CallPill(call: CallUi, onOpen: () -> Unit) {
    val t = CircasoTheme.typography
    val c = CircasoTheme.colors
    Row(
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(top = CircasoSpacing.s2)
            .clip(CircasoShapes.pill).background(c.money).pressable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "Back to the call with ${call.title}" }
            .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically,
    ) {
        // Their photo when there is one, else the call's handset (initials would be too small to read here).
        val photo = call.photoUrl
        if (photo != null) Avatar(call.title, size = PillPhoto, imageUrl = photo)
        else CircasoIconImage(CircasoIcon.Phone, c.onMoney, Modifier.size(16.dp))
        BasicText("${call.title.substringBefore(' ')} · ${statusText(call, short = true)}", style = t.footnote.copy(color = c.onMoney, fontWeight = FontWeight.SemiBold))
    }
}

internal enum class Tint { Default, End, Accept }

/**
 * The call's round buttons: 64 dp glass discs with a label under, white when on; red to end, green to accept.
 * [toggle]: it switches something on and off, so a screen reader hears its state (not Send money, More or Message). */
@Composable
internal fun ControlButton(icon: CircasoIcon, label: String?, description: String, onClick: () -> Unit, on: Boolean = false, tint: Tint = Tint.Default, big: Boolean = false, enabled: Boolean = true, size: androidx.compose.ui.unit.Dp? = null, toggle: Boolean = true) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val bg = when (tint) { Tint.End -> CallColors.End; Tint.Accept -> CallColors.Accept; Tint.Default -> if (on) CallColors.ControlOn else CallColors.Control }
    val fg = if (tint == Tint.Accept || (tint == Tint.Default && on)) CallColors.AcceptInk else Color.White
    val size = size ?: if (big) 72.dp else if (label == null) CallBarButton else 64.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(if (enabled) bg else bg.copy(alpha = 0.06f))
                .pressable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description; if (tint == Tint.Default && toggle) stateDescription = if (on) "On" else "Off" },
            contentAlignment = Alignment.Center,
        ) {
            // End and Decline draw the handset turned down, as the frames do.
            CircasoIconImage(icon, if (enabled) fg else fg.copy(alpha = 0.4f), Modifier.size(if (big) 30.dp else 24.dp).then(if (tint == Tint.End) Modifier.rotate(135f) else Modifier))
        }
        if (label != null) BasicText(label, style = t.footnote.copy(color = CallColors.Ink), modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun RoundIcon(icon: CircasoIcon, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(CallColors.Control).pressable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        CircasoIconImage(icon, CallColors.Ink, Modifier.size(20.dp))
    }
}

/**
 * H1 and H2's backdrop: the photo decoded tiny (so it is cheap and already soft where blur isn't available), scaled up,
 * blurred and darkened so the white text on it stays readable. Nothing when there's no photo: the call stays black.
 * Drawn once; the ring's pulse animates only the avatar's layer, so this never redraws per frame.
 */
@Composable
private fun CallBackdrop(url: String?) {
    if (url == null) return
    val context = coil3.compose.LocalPlatformContext.current
    val request = remember(url) {
        val key = url.substringBefore('?')
        coil3.request.ImageRequest.Builder(context).data(url).size(BackdropPx).memoryCacheKey("$key@backdrop").diskCacheKey(key).crossfade(CircasoMotion.BaseMs).build()
    }
    coil3.compose.AsyncImage(
        request, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }.blur(42.dp),
    )
    // H2's brightness(0.42): dark enough that the 72% white lines on it stay readable on a bright photo. High contrast
    // has no photo behind the text at all (as You does).
    Box(Modifier.fillMaxSize().background(CallColors.Backdrop))
}

/** The pill's photo, a little larger than the handset it replaces so a face reads at a glance. */
private val PillPhoto = 20.dp

/** The backdrop's decode size: tiny on purpose, it is blurred over the whole screen. */
private const val BackdropPx = 64

/**
 * H4's tile without video: the person's photo fills the tile (their initials, centred, until it loads or when they have
 * none, so the tile never changes size). Someone still ringing or connecting is shown faded until they're really on.
 */
@Composable
private fun PersonFill(name: String, url: String?, present: Boolean = true, avatarSize: androidx.compose.ui.unit.Dp = CircasoSizes.avatarLg) {
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (present) 1f else 0.45f, tween(CircasoMotion.BaseMs), label = "present")
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }, contentAlignment = Alignment.Center) {
        Avatar(name, size = avatarSize)
        if (url != null) {
            val context = coil3.compose.LocalPlatformContext.current
            val request = remember(url) {
                val key = url.substringBefore('?')
                coil3.request.ImageRequest.Builder(context).data(url).memoryCacheKey("$key@tile").diskCacheKey(key)
                    // The chat list's copy stands in while the tile's decodes, as Avatar does.
                    .placeholderMemoryCacheKey("$key@${CircasoSizes.avatarMd.value.toInt()}").crossfade(CircasoMotion.BaseMs).build()
            }
            coil3.compose.AsyncImage(request, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** H1's ring: the avatar breathes while it rings (still when the person asked for less motion). */
@Composable
private fun Pulse(content: @Composable () -> Unit) {
    if (CircasoTheme.reduceMotion) { content(); return }
    val scale by androidx.compose.animation.core.rememberInfiniteTransition(label = "ring").animateFloat(
        1f, 1.06f, androidx.compose.animation.core.infiniteRepeatable(tween(CircasoMotion.MomentMs), androidx.compose.animation.core.RepeatMode.Reverse), label = "scale",
    )
    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) { content() }
}

/** The goal component reads theme ink; on the call's black panel its text must be white. */
@Composable
private fun CompositionWhite(content: @Composable () -> Unit) {
    val base = CircasoTheme.colors
    androidx.compose.runtime.CompositionLocalProvider(
        com.circaso.designsystem.theme.LocalCircasoColors provides base.copy(ink = Color.White, inkSecondary = Color.White.copy(alpha = 0.72f), fillStrong = Color.White.copy(alpha = 0.2f)),
        content = content,
    )
}
