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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.circaso.designsystem.chrome.CircasoSheet
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
private object CallColors {
    val Bg = Color(0xFF000000)
    val Ink = Color.White
    val InkSoft = Color.White.copy(alpha = 0.72f)
    val Control = Color.White.copy(alpha = 0.14f)
    val ControlOn = Color.White
    val Panel = Color(0xE61C1C1E)
    val Scrim = Color.Black.copy(alpha = 0.5f)
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
    /** H1/H2/H3's "Send money": the call stays on, minimised, while Pay is open. */
    val onSendMoney: (() -> Unit)?,
    /** H5: add to the group's goal during the call; the amount in the person's currency. */
    val onContribute: ((String?) -> Unit)?,
    /** H4's "Add people": ring the group's members who aren't on the call (CL9). */
    val onAddPeople: () -> Unit = {},
)

/**
 * The call, above every screen. Full screen unless minimised, then a pill at the top that goes back to it. [money] is
 * the newest payment said in this chat ("You sent KES 500 · for lunch", design H2); [chama] the group's goal (H4).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun BoxScope.CallOverlay(call: CallUi?, actions: CallActions, money: String?, chama: Chama?, fiat: FiatValue?) {
    val still = CircasoTheme.reduceMotion
    // Back on a full-screen call minimises it (never declines or hangs up); the screens under it stay where they were.
    androidx.compose.ui.backhandler.BackHandler(enabled = call != null && !call.minimised && call.phase != CallPhase.Incoming) { actions.onMinimise(true) }
    androidx.compose.ui.backhandler.BackHandler(enabled = call != null && !call.minimised && call.phase == CallPhase.Incoming) {}
    AnimatedVisibility(
        call != null && !call.minimised,
        Modifier.matchParentSize(),
        enter = if (still) fadeIn(tween(CircasoMotion.QuickMs)) else fadeIn(tween(CircasoMotion.BaseMs)) + slideInVertically(tween(CircasoMotion.BaseMs)) { it / 6 },
        exit = if (still) fadeOut(tween(CircasoMotion.QuickMs)) else fadeOut(tween(CircasoMotion.QuickMs)) + slideOutVertically(tween(CircasoMotion.QuickMs)) { it / 6 },
    ) {
        if (call != null) CallSurface(call, actions, money, chama, fiat)
    }
    AnimatedVisibility(call != null && call.minimised, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(CircasoMotion.BaseMs)), exit = fadeOut(tween(CircasoMotion.QuickMs))) {
        if (call != null) CallPill(call) { actions.onMinimise(false) }
    }
}

@Composable
private fun CallSurface(call: CallUi, actions: CallActions, money: String?, chama: Chama?, fiat: FiatValue?) {
    var contributing by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(CallColors.Bg)
            // Nothing under the call surface takes a tap; no ripple, nothing for a screen reader to announce.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        val layout = when {
            call.phase == CallPhase.Incoming -> 0
            call.group -> 1
            call.camera || call.people.any { it.video && it.joined } -> 2
            else -> 3
        }
        // Voice turning into video (or back) crossfades instead of jumping.
        androidx.compose.animation.AnimatedContent(layout, transitionSpec = { fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs)) }, label = "call") { l ->
            Box(Modifier.fillMaxSize()) {
                when (l) {
                    0 -> Incoming(call, actions)
                    1 -> GroupCall(call, actions, chama, fiat, onContribute = if (chama != null && actions.onContribute != null) ({ contributing = true }) else null)
                    2 -> VideoCall(call, actions, money)
                    else -> VoiceCall(call, actions, money)
                }
            }
        }
        if (chama != null) ContributeSheet(contributing, chama, fiat, onClose = { contributing = false }) { amount -> contributing = false; actions.onContribute?.invoke(amount) }
    }
}

/** Design H1. */
@Composable
private fun BoxScope.Incoming(call: CallUi, actions: CallActions) {
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = CircasoSpacing.s6, vertical = CircasoSpacing.s6), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(CircasoSpacing.s10))
        BasicText(if (call.video) "Circaso video call" else "Circaso voice call", style = t.subhead.copy(color = CallColors.InkSoft))
        Spacer(Modifier.height(CircasoSpacing.s5))
        Pulse { Avatar(call.title, size = 120.dp) }
        Spacer(Modifier.height(CircasoSpacing.s5))
        BasicText(call.title, style = t.largeTitle.copy(color = CallColors.Ink, textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Handle(call.handle)
        BasicText(if (call.group) (call.caller?.substringBefore(' ')?.let { "$it is calling the group" } ?: "Group call") else "is calling you…", style = t.body.copy(color = CallColors.InkSoft))
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlButton(CircasoIcon.Chat, "Message", "Reply with a message", actions.onMessage)
            if (actions.onSendMoney != null) ControlButton(CircasoIcon.Money, "Send money", "Send money instead", actions.onSendMoney)
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
private fun VoiceCall(call: CallUi, actions: CallActions, money: String?) {
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = CircasoSpacing.s6, vertical = CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally) {
        TopRow(call, actions)
        Spacer(Modifier.height(CircasoSpacing.s6))
        SecureLine()
        Spacer(Modifier.height(CircasoSpacing.s6))
        Avatar(call.title, size = 120.dp)
        Spacer(Modifier.height(CircasoSpacing.s4))
        BasicText(call.title, style = t.largeTitle.copy(color = CallColors.Ink, textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Handle(call.handle)
        Status(call)
        money?.let { Spacer(Modifier.height(CircasoSpacing.s5)); MoneyNote(it) }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlButton(if (call.mic) CircasoIcon.Mic else CircasoIcon.MicOff, "Mute", if (call.mic) "Mute" else "Unmute", actions.onMute, on = !call.mic)
            ControlButton(CircasoIcon.Speaker, "Speaker", "Speaker", actions.onSpeaker, on = call.speaker)
            ControlButton(CircasoIcon.Video, "Video", "Video", actions.onCamera, on = call.camera)
        }
        Spacer(Modifier.height(CircasoSpacing.s5))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (actions.onSendMoney != null) ControlButton(CircasoIcon.Money, "Send money", "Send money", actions.onSendMoney)
            ControlButton(CircasoIcon.Phone, "End", "End call", actions.onEnd, tint = Tint.End)
        }
    }
}

/** Design H3: them full screen, me in the corner, the controls on a glass bar. */
@Composable
private fun BoxScope.VideoCall(call: CallUi, actions: CallActions, money: String?) {
    val t = CircasoTheme.typography
    val them = call.people.firstOrNull { it.joined }
    val theirMid = them?.videoMid
    if (them != null && them.video && theirMid != null) CallVideo(VideoSource.Remote(theirMid), Modifier.fillMaxSize())
    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Avatar(call.title, size = 120.dp) }
    Box(Modifier.fillMaxWidth().height(180.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))))
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s4)) {
        TopRow(call, actions)
        Column(Modifier.padding(top = CircasoSpacing.s2)) {
            BasicText(call.title, style = t.title2.copy(color = CallColors.Ink))
            Handle(call.handle, center = false)
            Status(call)
        }
        Spacer(Modifier.weight(1f))
        money?.let { MoneyNote(it, Modifier.align(Alignment.CenterHorizontally)); Spacer(Modifier.height(CircasoSpacing.s3)) }
        Row(
            Modifier.fillMaxWidth().clip(CircasoShapes.pill).background(CallColors.Panel).padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(CircasoIcon.FlipCamera, null, "Flip camera", actions.onFlip, enabled = call.camera)
            ControlButton(if (call.mic) CircasoIcon.Mic else CircasoIcon.MicOff, null, if (call.mic) "Mute" else "Unmute", actions.onMute, on = !call.mic)
            ControlButton(if (call.camera) CircasoIcon.Video else CircasoIcon.VideoOff, null, if (call.camera) "Turn camera off" else "Turn camera on", actions.onCamera, on = !call.camera)
            if (actions.onSendMoney != null) ControlButton(CircasoIcon.Money, null, "Send money", actions.onSendMoney)
            ControlButton(CircasoIcon.Phone, null, "End call", actions.onEnd, tint = Tint.End)
        }
    }
    // Me, in the corner above the controls, mirrored like a mirror when it's the front camera.
    if (call.camera) {
        Box(
            // Design H3: top right, under the top row, 104 x 150.
            Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = CircasoSpacing.s4, top = 64.dp)
                .width(104.dp).height(150.dp).clip(CircasoShapes.lg).border(1.dp, Color.White.copy(alpha = 0.2f), CircasoShapes.lg),
        ) { CallVideo(VideoSource.Local, Modifier.fillMaxSize(), mirror = call.frontCamera) }
    }
}

/** Design H4: everyone in a grid, the goal live under them. */
@Composable
private fun GroupCall(call: CallUi, actions: CallActions, chama: Chama?, fiat: FiatValue?, onContribute: (() -> Unit)?) {
    val t = CircasoTheme.typography
    val onCall = call.joined.size + 1
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(CircasoIcon.Minimise, "Minimise call") { actions.onMinimise(true) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(call.title, style = t.headline.copy(color = CallColors.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val time = elapsedText(call.activeSince)
                BasicText(if (call.phase == CallPhase.Active) "$onCall on the call · $time" else statusText(call), style = t.footnote.copy(color = CallColors.InkSoft))
            }
            if (call.notOnCall.isNotEmpty() && call.phase == CallPhase.Active) RoundIcon(CircasoIcon.Plus, "Add people: ring ${call.notOnCall.joinToString { it.name.substringBefore(' ') }}") { actions.onAddPeople() }
            else Spacer(Modifier.size(44.dp))
        }
        val tiles = call.people.filter { it.joined || it.ringing }
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            items(tiles, key = { it.userId }) { p -> Tile(p) }
            item(key = "me") { MeTile(call) }
        }
        if (chama != null) LiveGoal(chama, fiat)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlButton(if (call.mic) CircasoIcon.Mic else CircasoIcon.MicOff, null, if (call.mic) "Mute" else "Unmute", actions.onMute, on = !call.mic)
            ControlButton(if (call.camera) CircasoIcon.Video else CircasoIcon.VideoOff, null, if (call.camera) "Turn camera off" else "Turn camera on", actions.onCamera, on = !call.camera)
            // Design H4: the money moment is a button of its own, on the money card's colours.
            if (onContribute != null && chama != null) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(CircasoTheme.colors.moneyCardSheen, CircasoTheme.colors.moneyCard)))
                        .pressable(role = Role.Button, onClick = onContribute).semantics { contentDescription = "Contribute to ${chama.goal.name}" },
                    contentAlignment = Alignment.Center,
                ) { CircasoIconImage(CircasoIcon.Plus, CircasoTheme.colors.onMoney, Modifier.size(24.dp)) }
            } else {
                ControlButton(CircasoIcon.Speaker, null, "Speaker", actions.onSpeaker, on = call.speaker)
            }
            ControlButton(CircasoIcon.Phone, null, "Leave call", actions.onEnd, tint = Tint.End)
        }
    }
}

@Composable
private fun Tile(p: CallPerson) {
    val t = CircasoTheme.typography
    val mid = p.videoMid
    // Wider than tall, so six people fit on a phone without scrolling (design H4's 2 x 3 grid).
    Box(Modifier.fillMaxWidth().aspectRatio(1.15f).clip(CircasoShapes.lg).background(CallColors.Control)) {
        if (p.video && mid != null) CallVideo(VideoSource.Remote(mid), Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Avatar(p.name, size = CircasoSizes.avatarLg) }
        Row(Modifier.align(Alignment.BottomStart).padding(CircasoSpacing.s2), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1), verticalAlignment = Alignment.CenterVertically) {
            BasicText(p.name.substringBefore(' '), style = t.footnote.copy(color = CallColors.Ink, fontWeight = FontWeight.SemiBold))
            if (!p.audio && p.joined) CircasoIconImage(CircasoIcon.MicOff, CallColors.Ink, Modifier.size(14.dp))
            if (p.ringing) BasicText("· ringing", style = t.footnote.copy(color = CallColors.InkSoft))
        }
    }
}

@Composable
private fun MeTile(call: CallUi) {
    val t = CircasoTheme.typography
    Box(Modifier.fillMaxWidth().aspectRatio(1.15f).clip(CircasoShapes.lg).background(CallColors.Control)) {
        if (call.camera) CallVideo(VideoSource.Local, Modifier.fillMaxSize(), mirror = call.frontCamera)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Avatar("You", size = CircasoSizes.avatarLg) }
        BasicText("You", style = t.footnote.copy(color = CallColors.Ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.align(Alignment.BottomStart).padding(CircasoSpacing.s2))
    }
}

/** H4's goal card: "LIVE · contributions land as they happen", moving as the frames arrive. */
@Composable
private fun LiveGoal(chama: Chama, fiat: FiatValue?) {
    val t = CircasoTheme.typography
    val c = CircasoTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(CallColors.Panel).padding(CircasoSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
            BasicText("LIVE", style = t.caption1.copy(color = Color.White, fontWeight = FontWeight.Bold), modifier = Modifier.clip(CircasoShapes.pill).background(c.critical).padding(horizontal = 6.dp, vertical = 2.dp))
            BasicText("Contributions land as they happen", style = t.footnote.copy(color = CallColors.InkSoft))
        }
        // The goal component, on the call's dark panel.
        CompositionWhite { GoalProgress(amountText(chama.contributedMinor, fiat), amountNumber(chama.goal.targetMinor, fiat), chama.progress, meta = com.circaso.feature.chama.presentation.goalMeta(chama), label = "${chama.goal.name} progress") }
    }
}

/**
 * Design H5: the goal, preset amounts with the chosen one shown large, and "Contribute KES 1,000". The payment itself
 * is the usual Pay screen (PIN in the Circle SDK), with the call minimised to its pill meanwhile.
 */
@Composable
private fun BoxScope.ContributeSheet(visible: Boolean, chama: Chama, fiat: FiatValue?, onClose: () -> Unit, onPick: (String?) -> Unit) {
    val t = CircasoTheme.typography
    val c = CircasoTheme.colors
    // Presets in the person's money: about KES 500 / 1,000 / 2,500, or 5 / 10 / 25 USDC without a rate.
    val presets = if (fiat != null) listOf("500", "1000", "2500") else listOf("5", "10", "25")
    var chosen by remember(visible) { mutableStateOf(presets[1]) }
    CircasoSheet(visible, onClose, label = "Contribute to ${chama.goal.name}") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            Avatar(chama.goal.name, size = CircasoSizes.avatarMd)
            Column(Modifier.weight(1f)) {
                BasicText("Contribute to ${chama.goal.name}", style = t.headline.copy(color = c.ink))
                BasicText("Everyone on the call sees it land", style = t.footnote.copy(color = c.inkSecondary))
            }
            com.circaso.designsystem.components.CircasoIconButton(CircasoIcon.Close, "Close", onClose)
        }
        val left = ((chama.goal.targetMinor.toLongOrNull() ?: 0L) - (chama.contributedMinor.toLongOrNull() ?: 0L)).coerceAtLeast(0L)
        GoalProgress(amountText(chama.contributedMinor, fiat), amountNumber(chama.goal.targetMinor, fiat), chama.progress, meta = if (left > 0) "${amountText(left.toString(), fiat)} to go" else "Goal reached", label = "${chama.goal.name} progress")
        val number = com.circaso.feature.wallet.domain.MinorUnits.group(chosen)
        val usdc = fiat?.let { com.circaso.feature.wallet.domain.fiatToUsdcMinor(chosen, it.rate) }?.let { com.circaso.feature.wallet.domain.MinorUnits.toDisplay(it, 6) + " USDC" }
        com.circaso.designsystem.components.AmountDisplay(fiat?.currency ?: "USDC", number, sub = usdc, size = com.circaso.designsystem.components.AmountSize.Hero)
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            presets.forEach { a -> com.circaso.designsystem.components.SuggestionChip(com.circaso.feature.wallet.domain.MinorUnits.group(a), { chosen = a }, selected = chosen == a) }
            com.circaso.designsystem.components.SuggestionChip("Other", { onPick(null) }, selected = false)
        }
        com.circaso.designsystem.components.CircasoButton(
            "Contribute ${fiat?.currency?.let { "$it " } ?: ""}$number" + if (fiat == null) " USDC" else "",
            { onPick(chosen) }, size = com.circaso.designsystem.components.ButtonSize.Large, block = true,
        )
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
private fun statusText(call: CallUi): String = when (call.phase) {
    CallPhase.Incoming -> "Incoming…"
    CallPhase.Ringing -> "Ringing…"
    CallPhase.Connecting -> "Connecting…"
    CallPhase.Reconnecting -> "Reconnecting…"
    CallPhase.Active -> elapsedText(call.activeSince)
    CallPhase.Ended -> when (call.endReason) { "DECLINED" -> "Declined"; "MISSED" -> "No answer"; "CANCELLED" -> "Cancelled"; "FAILED" -> "Call failed"; else -> "Call ended" }
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

@Composable
private fun MoneyNote(text: String, modifier: Modifier = Modifier) {
    val t = CircasoTheme.typography
    Row(
        modifier.clip(CircasoShapes.pill).background(CallColors.Control).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
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
        CircasoIconImage(CircasoIcon.Phone, c.onMoney, Modifier.size(16.dp))
        BasicText("${call.title.substringBefore(' ')} · ${statusText(call)}", style = t.footnote.copy(color = c.onMoney, fontWeight = FontWeight.SemiBold))
    }
}

private enum class Tint { Default, End, Accept }

/** The call's round buttons: 64 dp glass discs with a label under, white when on; red to end, green to accept. */
@Composable
private fun ControlButton(icon: CircasoIcon, label: String?, description: String, onClick: () -> Unit, on: Boolean = false, tint: Tint = Tint.Default, big: Boolean = false, enabled: Boolean = true) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val bg = when (tint) { Tint.End -> CallColors.End; Tint.Accept -> CallColors.Accept; Tint.Default -> if (on) CallColors.ControlOn else CallColors.Control }
    val fg = if (tint == Tint.Accept || (tint == Tint.Default && on)) CallColors.AcceptInk else Color.White
    val size = if (big) 72.dp else if (label == null) 52.dp else 64.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(if (enabled) bg else bg.copy(alpha = 0.06f))
                .pressable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description; if (tint == Tint.Default && label != "Send money") stateDescription = if (on) "On" else "Off" },
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
