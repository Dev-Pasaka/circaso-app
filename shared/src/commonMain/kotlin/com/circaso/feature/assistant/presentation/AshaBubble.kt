package com.circaso.feature.assistant.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.components.AshaMark
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.ComposerBar
import com.circaso.designsystem.components.LocalSecretsOnScreen
import com.circaso.designsystem.components.SuggestionChip
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Asha's floating bubble (2026-11-09): summoned with `/asha` from any text field that isn't a secret one, over
 * whatever screen the person is on. Expanded, it is a floating panel with her conversation, a field, voice and close;
 * minimised, a round Asha mark that can be dragged and rests on the nearest side of the screen. Tapping it opens the
 * panel again with the conversation as it was. Closing ends it until the next `/asha`.
 */
@Stable
class AshaBubbleState {
    /** Summoned and not closed. */
    var open by mutableStateOf(false)
        private set
    /** The panel is out; otherwise the round bubble. */
    var expanded by mutableStateOf(false)
        private set
    /** Bumped on every summon, so the panel's field takes the keyboard. */
    var summons by mutableIntStateOf(0)
        private set
    /** An answer arrived while minimised: a dot on the bubble until it is opened. */
    var unseen by mutableStateOf(false)
        internal set
    /** The thumbnail of the picture waiting to be sent (the picture itself waits in the view model). */
    var preview by mutableStateOf<ImageBitmap?>(null)
        internal set
    /** A calm line from the bubble itself (a screen it won't picture); cleared by typing or closing. */
    var notice by mutableStateOf<String?>(null)
        internal set
    /** Taking a picture of the screen right now. */
    var capturing by mutableStateOf(false)
        internal set
    /** The summon whose keyboard request was handled, so tapping the bubble open doesn't raise the keyboard again. */
    internal var focusedSummon = 0
    /** Where the minimised bubble rests (px, its top-left in the layer); null until it first shows. */
    internal var rest: Offset? = null

    fun summon() { open = true; expanded = true; unseen = false; summons++ }
    fun minimise() { expanded = false }
    fun expand() { expanded = true; unseen = false }
    fun close() { open = false; expanded = false; unseen = false; preview = null; notice = null }
}

/** What the bubble can do beyond its own conversation. */
class AshaBubbleActions(
    /** The live voice conversation (the existing voice mode); null where this phone records clips instead. */
    val onVoice: (() -> Unit)? = null,
    /** Opens Asha's own screen (her full chat, menu and recents); the bubble closes. */
    val onOpenFull: () -> Unit = {},
    /** Takes a picture of the screen under the bubble; null where that can't be done. */
    val capture: (suspend () -> ScreenShot?)? = null,
)

/** A call, as Asha's bubble sees it. */
enum class AshaCallState {
    None,
    /** Live, minimised to its pill: the app's screens are on show again. */
    Minimised,
    /** Ringing in, full screen (H1): Accept and Decline must show. */
    Ringing,
    /** Live on the full call screen (H2–H4). */
    OnScreen,
}

/**
 * How the bubble behaves with a call about (Pasaka, 2026-11-10: Asha works over calls too). [hidden] keeps her out of
 * sight without ending her: one of her own screens is on top, unless a call covers it. [voice]: the call owns the
 * microphone and the speaker, so no live voice, no spoken questions and no answers read aloud while one is live, even
 * minimised. [showScreen]: no "Show Asha my screen" over a call screen, since it would be other people's faces and
 * shared screens, and the picture would be of the app under the call, not what the person sees. [clearTop] and
 * [clearBottom]: room the resting bubble keeps clear of, so it never sits on the call's top row or its controls.
 * A ring coming in (H1) hides her until it is answered or declined: nothing of hers may sit on Accept or Decline,
 * however large the text. Over the live call the open panel also stops above the call's controls, so mute and End are
 * always one tap away.
 */
data class AshaBubbleMode(
    val hidden: Boolean,
    val voice: Boolean,
    val showScreen: Boolean,
    val clearTop: Dp = 0.dp,
    val clearBottom: Dp = 0.dp,
)

/** The bubble's rules for [call], with one of Asha's own screens on top or not ([ashaOnTop]). */
fun ashaBubbleMode(ashaOnTop: Boolean, call: AshaCallState): AshaBubbleMode = when (call) {
    AshaCallState.None -> AshaBubbleMode(hidden = ashaOnTop, voice = true, showScreen = true)
    AshaCallState.Minimised -> AshaBubbleMode(hidden = ashaOnTop, voice = false, showScreen = true)
    AshaCallState.Ringing -> AshaBubbleMode(hidden = true, voice = false, showScreen = false)
    AshaCallState.OnScreen -> AshaBubbleMode(hidden = false, voice = false, showScreen = false, clearTop = CallTopClear, clearBottom = CallBottomClear)
}

/** The call screen's top row (minimise, the name), and its control bar with the money line over it. */
internal val CallTopClear = 64.dp
internal val CallBottomClear = 112.dp

/**
 * The layer: over every screen and its sheets, and over calls (2026-11-10), under system dialogs and Circle's PIN.
 * [mode] says what she may do with a call about ([ashaBubbleMode]). [screen] is the screen's name for Asha
 * ("Wallet › Send").
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun BoxScope.AshaBubbleLayer(
    state: AshaBubbleState,
    viewModel: AshaViewModel,
    screen: String?,
    mode: AshaBubbleMode,
    actions: AshaBubbleActions,
) {
    val hidden = mode.hidden
    val ui by viewModel.state.collectAsState()
    val visible = state.open && !hidden
    // Asha's availability is watched only while the bubble is out (it stays composed, closed, for the session).
    androidx.lifecycle.compose.LifecycleResumeEffect(viewModel, visible) { if (visible) viewModel.onResume(); onPauseOrDispose { viewModel.onPause() } }
    // The screen is read when she is summoned and as the person moves on with her open, so each question says where.
    LaunchedEffect(screen) { viewModel.onScreen(screen) }
    // Closed or out of sight: no microphone left open.
    LaunchedEffect(state.open, hidden) { if (!state.open || hidden) viewModel.onCancelRecording() }
    // A call has the microphone and the speaker: a question being recorded is dropped and an answer being read stops.
    LaunchedEffect(mode.voice) { if (!mode.voice) viewModel.onVoiceOff() }
    // Closing drops the waiting picture: the next summon starts clean.
    LaunchedEffect(state.open) { if (!state.open) viewModel.onAttachment(null) }
    LaunchedEffect(ui.attachment) { if (ui.attachment == null) state.preview = null }
    // A new answer while minimised puts a dot on the bubble.
    val answers = ui.messages.count { !it.isFromUser }
    val seen = remember { intArrayOf(-1) }
    LaunchedEffect(answers) {
        if (seen[0] >= 0 && answers > seen[0] && !state.expanded) state.unseen = true
        seen[0] = answers
    }
    androidx.compose.ui.backhandler.BackHandler(enabled = visible && state.expanded) { state.minimise() }

    val still = CircasoTheme.reduceMotion
    BoxWithConstraints(Modifier.matchParentSize()) {
        val wide = maxWidth >= WidePanelAt
        AnimatedVisibility(
            visible && state.expanded,
            Modifier.fillMaxSize(),
            enter = fadeIn(tween(CircasoMotion.QuickMs)),
            exit = fadeOut(tween(CircasoMotion.QuickMs)),
        ) {
            // The panel grows from the bottom edge, where the field is; under Reduce Motion it only fades.
            val grow = if (still) Modifier else Modifier.animateEnterExit(
                enter = scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f)),
                exit = scaleOut(tween(CircasoMotion.QuickMs), targetScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 1f)),
            )
            AshaPanel(
                state, ui, viewModel, screen, actions, mode,
                alignment = if (wide) Alignment.BottomEnd else Alignment.BottomCenter,
                modifier = Modifier.width(min(maxWidth - CircasoSpacing.s3 * 2, PanelMaxWidth)).then(grow),
            )
        }
        AnimatedVisibility(
            visible && !state.expanded,
            Modifier.fillMaxSize(),
            enter = fadeIn(tween(CircasoMotion.QuickMs)),
            exit = fadeOut(tween(CircasoMotion.QuickMs)),
        ) {
            val pop = if (still) Modifier else Modifier.animateEnterExit(
                enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium), initialScale = 0.6f),
                exit = scaleOut(tween(CircasoMotion.QuickMs), targetScale = 0.6f),
            )
            DraggableBubble(state, busy = ui.sending, pop, mode.clearTop, mode.clearBottom)
        }
    }
}

/** The panel: Asha's header with her actions, the conversation, the picture waiting to go, and the field. */
@Composable
private fun AshaPanel(state: AshaBubbleState, ui: AshaUiState, viewModel: AshaViewModel, screen: String?, actions: AshaBubbleActions, mode: AshaBubbleMode, alignment: Alignment, modifier: Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val scope = rememberCoroutineScope()
    val secrets = LocalSecretsOnScreen.current
    val focus = remember { FocusRequester() }
    // The keyboard goes to Asha when she is summoned (it was up already for the field `/asha` was typed in).
    LaunchedEffect(state.summons) {
        if (state.expanded && state.summons != state.focusedSummon) { state.focusedSummon = state.summons; runCatching { focus.requestFocus() } }
    }
    val shape = CircasoShapes.lg
    Box(
        Modifier.fillMaxSize()
            // Above the keyboard and clear of the system bars and cut-outs.
            // Over a call it also stops above the call's controls (mute and End stay reachable), unless the keyboard is
            // higher still.
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                    .union(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).add(WindowInsets(bottom = mode.clearBottom)))
                    .union(WindowInsets.ime),
            )
            .padding(CircasoSpacing.s3),
        contentAlignment = alignment,
    ) {
        val window = com.circaso.designsystem.adaptive.LocalWindowLayout.current
        Column(
            // Sized from the whole window (not from what the keyboard leaves), then held to the room there is.
            modifier.heightIn(max = min(PanelMaxHeight, window.height * PanelHeightFraction)).fillMaxHeight()
                .glass(shape, floating = true).clip(shape)
                // Every touch on the panel is the panel's: a pointer-input node here ends hit-testing at the panel, so
                // nothing on the screen behind (a Pay button) gets it. It only listens. Consuming would cancel the
                // list's scroll and the buttons' taps, which check on the final pass whether something else took the
                // touch before their slop is reached: slow drags didn't scroll, only flicks did.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .semantics { paneTitle = "Asha"; isTraversalGroup = true },
        ) {
            PanelHeader(
                subtitle = screen?.let { "On $it" } ?: ui.conversationTitle ?: "Here to help",
                capturing = state.capturing,
                showScreen = actions.capture != null && mode.showScreen,
                canShowScreen = ui.canAsk && !ui.sending,
                onShowScreen = {
                    val capture = actions.capture
                    when {
                        capture == null || state.capturing || !mode.showScreen -> Unit
                        // Never a picture of a PIN, a code or a password, even half-typed.
                        secrets?.any == true -> state.notice = "Asha can't see a screen with a PIN, code or password on it."
                        else -> scope.launch {
                            state.capturing = true
                            val shot = try { capture() } finally { state.capturing = false }
                            if (shot == null) state.notice = "Couldn't take a picture of this screen. Try again."
                            else { state.preview = shot.preview; viewModel.onAttachment(shot.image) }
                        }
                    }
                },
                // While a question is in flight, her own screen would let a second one be asked over it.
                onOpenFull = { if (!ui.sending) actions.onOpenFull() },
                onMinimise = state::minimise,
                onClose = state::close,
            )
            val shown = bubblesOf(ui)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (shown.isEmpty()) {
                    PanelEmpty(screen, online = ui.canAsk) { text -> viewModel.onSuggestion(text) }
                } else {
                    val list = androidx.compose.runtime.key(ui.shownConversation) { androidx.compose.foundation.lazy.rememberLazyListState() }
                    val follow = rememberFollowLatest(list) { shown.size to shown.lastOrNull()?.key }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = list,
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s3),
                        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                    ) { items(shown.asReversed(), key = { it.key }) { BubbleView(it, speaking = it.speakable && it.text == ui.speakingText, canSpeak = ui.online && mode.voice, onSpeak = viewModel::onSpeak) } }
                    val still = CircasoTheme.reduceMotion
                    JumpToLatest(!follow.following, { scope.launch { follow.jump(!still) } }, Modifier.align(Alignment.BottomEnd).padding(end = CircasoSpacing.s3, bottom = CircasoSpacing.s2))
                }
            }
            // The app's own notice (a screen it won't picture): calm, in secondary ink, gone with the next keystroke.
            // On a call, where her voice button would be, she says why it isn't (once nothing else is being said).
            state.notice?.let {
                BasicText(
                    it, Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1).semantics { liveRegion = LiveRegionMode.Polite },
                    style = t.footnote.copy(color = c.inkSecondary),
                )
            }
            // A standing line, not an announcement: it would otherwise be spoken each time the panel opens.
            if (state.notice == null && !mode.voice && ui.error == null) {
                BasicText(VoiceOffOnCall, Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1), style = t.footnote.copy(color = c.inkSecondary))
            }
            ui.error?.let {
                BasicText(
                    it, Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1).semantics { liveRegion = LiveRegionMode.Polite },
                    style = t.footnote.copy(color = c.critical),
                )
            }
            state.preview?.takeIf { ui.attachment != null }?.let { preview -> AttachedScreen(preview) { viewModel.onAttachment(null) } }
            // Inside Asha, `/asha` is just words to her: the field doesn't summon her again.
            androidx.compose.runtime.CompositionLocalProvider(com.circaso.designsystem.components.LocalAshaLauncher provides null) {
            ComposerBar(
                value = ui.draft,
                onValueChange = { state.notice = null; viewModel.onDraft(it) },
                placeholder = when {
                    ui.voice == VoiceState.Recording -> "Listening…"
                    ui.voice == VoiceState.Transcribing -> "Turning your voice into words…"
                    !ui.online -> "Offline"
                    ui.maintenance -> "Asha's on a short break"
                    ui.attachment != null -> "Ask about your screen"
                    else -> "Ask Asha"
                },
                enabled = ui.canAsk && !ui.sending && ui.voice == VoiceState.Idle,
                canSend = ui.canSend,
                onSend = viewModel::onSend,
                focusRequester = focus,
                embedded = true,
                modifier = Modifier.padding(start = CircasoSpacing.s3, end = CircasoSpacing.s3, bottom = CircasoSpacing.s3),
                trailing = {
                    val recording = ui.voice == VoiceState.Recording
                    when {
                        !mode.voice -> Unit
                        actions.onVoice != null && !recording -> if (ui.canAsk && !ui.sending) CircasoIconButton(CircasoIcon.Waveform, "Talk to Asha", actions.onVoice)
                        ui.canUseVoice || recording ->
                            CircasoIconButton(if (recording) CircasoIcon.Stop else CircasoIcon.Mic, if (recording) "Stop recording" else "Ask by voice", viewModel::onMic, emphasised = recording)
                    }
                },
                above = {
                    ConnectionLine(
                        online = ui.online, backOnline = ui.backOnline, waiting = ui.waiting != null,
                        maintenance = if (ui.maintenance) maintenanceText(questionKept = ui.draft.isNotBlank(), toldWhenBack = ui.toldWhenBack) else null,
                        ashaBack = ui.ashaBack,
                    )
                },
            )
            }
        }
    }
}

@Composable
private fun PanelHeader(
    subtitle: String,
    capturing: Boolean,
    showScreen: Boolean,
    canShowScreen: Boolean,
    onShowScreen: () -> Unit,
    onOpenFull: () -> Unit,
    onMinimise: () -> Unit,
    onClose: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().padding(start = CircasoSpacing.s3, end = CircasoSpacing.s2, top = CircasoSpacing.s2, bottom = CircasoSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1),
    ) {
        // Her mark and name open her own screen (recents, usage, settings), as the header of a chat opens its info.
        Row(
            Modifier.weight(1f).clip(CircasoShapes.md).pressable(onClick = onOpenFull)
                .semantics(mergeDescendants = true) { contentDescription = "Asha, $subtitle. Opens Asha's full screen" }
                .padding(vertical = CircasoSpacing.s1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
        ) {
            AshaMark(size = CircasoSizes.avatarSm)
            Column(Modifier.weight(1f)) {
                BasicText("Asha", style = t.headline.copy(color = c.ink), maxLines = 1)
                BasicText(subtitle, style = t.caption1.copy(color = c.inkSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showScreen) CircasoIconButton(CircasoIcon.Screen, "Show Asha my screen", onShowScreen, enabled = canShowScreen || capturing, busy = capturing)
        CircasoIconButton(CircasoIcon.Minimise, "Minimise Asha", onMinimise)
        CircasoIconButton(CircasoIcon.Close, "Close Asha", onClose)
    }
}

/** Nothing asked yet in this conversation: a short hello that names the screen, and a question to start with. */
@Composable
private fun PanelEmpty(screen: String?, online: Boolean, onSuggestion: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.fillMaxSize().padding(horizontal = CircasoSpacing.s5),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            if (screen != null) "Hi! Ask me anything about $screen, or how something works in Circaso." else "Hi! Ask me anything about Circaso or your money.",
            style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
        )
        if (online) SuggestionChip(PanelSuggestion, { onSuggestion(PanelSuggestion) })
    }
}

/** The picture waiting to go with the next question: a thumbnail the person checks first, and an X to take it off. */
@Composable
private fun AttachedScreen(preview: ImageBitmap, onRemove: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Image(
            preview, contentDescription = "Picture of your screen",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 44.dp, height = 64.dp).clip(CircasoShapes.sm).border(1.dp, c.separator, CircasoShapes.sm),
        )
        Column(Modifier.weight(1f)) {
            BasicText("Your screen goes with your next question", style = t.footnote.copy(color = c.ink))
            BasicText("Only Asha sees it, for this answer", style = t.caption1.copy(color = c.inkSecondary))
        }
        CircasoIconButton(CircasoIcon.Close, "Don't send the picture", onRemove)
    }
}

/**
 * The minimised bubble: Asha's mark in a round, raised disc. Drag it anywhere; let go and it springs to the nearest
 * side (a flick carries it to the side it was thrown at), clear of the system bars. Tap opens the panel.
 */
@Composable
private fun DraggableBubble(state: AshaBubbleState, busy: Boolean, appear: Modifier, clearTop: Dp, clearBottom: Dp) {
    val c = CircasoTheme.colors
    val density = LocalDensity.current
    val still = CircasoTheme.reduceMotion
    val scope = rememberCoroutineScope()
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = with(density) { BubbleSize.toPx() }
        val margin = with(density) { CircasoSpacing.s3.toPx() }
        val left = with(density) { safe.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).toPx() } + margin
        val right = with(density) { maxWidth.toPx() - safe.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).toPx() } - margin - size
        // Clear of the system bars, and of a call's top row and controls while one is on screen.
        val top = with(density) { (safe.calculateTopPadding() + clearTop).toPx() } + margin
        val bottom = with(density) { maxHeight.toPx() - (safe.calculateBottomPadding() + clearBottom).toPx() } - margin - size
        fun settle(at: Offset, velocityX: Float = 0f): Offset {
            // Where a flick would carry it, then the nearer side.
            val aimX = at.x + velocityX * FlickProjection
            val x = if (aimX + size / 2 < (left + right + size) / 2) left else right
            return Offset(x, at.y.coerceIn(top, maxOf(top, bottom)))
        }
        val start = state.rest ?: Offset(right, top + (bottom - top) * 0.62f)
        val position = remember { Animatable(settle(start), Offset.VectorConverter) }
        val latestSettle by rememberUpdatedState(::settle)
        val latestStill by rememberUpdatedState(still)
        // Where the finger has it while dragging (null when resting or springing): read in the offset, no coroutine per move.
        var dragged by remember { mutableStateOf<Offset?>(null) }
        fun shown() = dragged ?: position.value
        fun moveToOtherSide() {
            val target = settle(Offset(if (position.value.x <= left) right else left, position.value.y))
            state.rest = target
            scope.launch { if (still) position.snapTo(target) else position.animateTo(target, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) }
        }
        // A turned or resized screen: back to its side, in the new bounds. A call coming on screen (or going) moves it
        // clear of the call's controls with a spring; the first placing, a drag under way and Reduce Motion jump there.
        val placed = remember { booleanArrayOf(false) }
        LaunchedEffect(left, right, top, bottom) {
            val target = settle(position.value)
            state.rest = target
            if (!placed[0] || latestStill || dragged != null) { placed[0] = true; position.snapTo(target) }
            else position.animateTo(target, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        }
        fun release(at: Offset, velocity: Offset) {
            val target = latestSettle(at, velocity.x)
            state.rest = target
            scope.launch {
                position.snapTo(at)
                dragged = null
                if (latestStill) position.snapTo(target)
                else position.animateTo(target, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), initialVelocity = velocity)
            }
        }
        Box(
            Modifier
                .offset { shown().let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
                .size(BubbleSize)
                .then(appear)
                .glass(CircasoShapes.circle, floating = true)
                .clip(CircasoShapes.circle)
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = { tracker.resetTracking(); dragged = position.value; scope.launch { position.stop() } },
                        onDrag = { change, amount ->
                            change.consume()
                            val now = (dragged ?: position.value) + amount
                            dragged = now
                            // In the layer's coordinates: the bubble moves with the finger, so its own would read still.
                            tracker.addPosition(change.uptimeMillis, now + change.position)
                        },
                        onDragEnd = { val v = tracker.calculateVelocity(); release(dragged ?: position.value, Offset(v.x, v.y)) },
                        onDragCancel = { release(dragged ?: position.value, Offset.Zero) },
                    )
                }
                .pressable(pressedScale = 0.92f, onClick = state::expand)
                .semantics {
                    contentDescription = if (state.unseen) "Asha, new answer. Opens Asha" else if (busy) "Asha is answering. Opens Asha" else "Asha. Opens Asha"
                    customActions = listOf(
                        CustomAccessibilityAction("Move to the other side") { moveToOtherSide(); true },
                        CustomAccessibilityAction("Close Asha") { state.close(); true },
                    )
                    if (busy) progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate
                },
            contentAlignment = Alignment.Center,
        ) {
            AshaMark(size = BubbleMarkSize)
            // While she answers: a thin arc turning round the disc (a still ring under Reduce Motion).
            if (busy) AnsweringRing(still)
        }
        // The new-answer dot, on the bubble's top end; it pops in and out.
        AnimatedVisibility(
            state.unseen,
            Modifier.offset { shown().let { IntOffset((it.x + size - with(density) { UnseenDot.toPx() }).roundToInt(), it.y.roundToInt()) } },
            enter = if (still) fadeIn(tween(CircasoMotion.QuickMs)) else fadeIn(tween(CircasoMotion.QuickMs)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)),
            exit = fadeOut(tween(CircasoMotion.QuickMs)),
        ) {
            Box(Modifier.size(UnseenDot).clip(CircasoShapes.circle).background(c.action).border(2.dp, c.surfaceRaised, CircasoShapes.circle))
        }
    }
}

/** A thin arc in the action colour turning round the bubble while Asha answers; a still ring under Reduce Motion. */
@Composable
private fun AnsweringRing(still: Boolean) {
    val c = CircasoTheme.colors
    val turn = if (still) 0f else {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "answering")
        t.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(tween(1_100, easing = androidx.compose.animation.core.LinearEasing)), label = "turn").value
    }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2
        drawArc(
            c.action, startAngle = turn - 90f, sweepAngle = if (still) 360f else 100f, useCenter = false,
            topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

/** The bubble's disc, and Asha's mark inside it. */
private val BubbleSize = 56.dp
private val BubbleMarkSize = 40.dp
private val UnseenDot = 14.dp

/** How far ahead a flick is projected (seconds of its speed) to choose the side it lands on. */
private const val FlickProjection = 0.12f

/** The panel's size: most of the screen's height under the keyboard, never wider than a phone on a tablet. */
private const val PanelHeightFraction = 0.62f
private val PanelMaxHeight = 560.dp
private val PanelMaxWidth = 420.dp
/** From this width the panel sits in the bottom-end corner (a tablet), not across the bottom. */
private val WidePanelAt = 600.dp

private const val PanelSuggestion = "How do I use this screen?"

/** Said where her voice button would be, while a call is live. */
internal const val VoiceOffOnCall = "Voice is off during a call. Type to Asha."
