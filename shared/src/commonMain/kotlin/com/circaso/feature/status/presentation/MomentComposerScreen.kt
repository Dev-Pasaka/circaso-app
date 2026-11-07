package com.circaso.feature.status.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.designsystem.theme.LightColors
import com.circaso.feature.mediacompose.Blocked
import com.circaso.feature.mediacompose.ComposerCapture
import com.circaso.feature.mediacompose.ComposerMode
import com.circaso.feature.mediacompose.MediaComposeCopy
import com.circaso.feature.mediacompose.MediaComposeStage
import com.circaso.feature.mediacompose.MediaSendButton
import com.circaso.feature.mediacompose.StageButton
import com.circaso.feature.mediacompose.clock
import com.circaso.feature.mediacompose.elapsedLabel

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MomentComposerScreen(viewModel: MomentComposerViewModel, onClose: () -> Unit, onAudience: () -> Unit, onFindPeople: () -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.shared) { if (state.shared) onClose() }
    androidx.compose.ui.backhandler.BackHandler {
        when {
            state.trim != null -> viewModel.cancelTrim()
            state.gallery != null -> viewModel.closeGallery()
            state.selecting -> viewModel.clearSelection()
            state.capture != null -> viewModel.discardCapture()
            else -> onClose()
        }
    }
    val forward = if (state.forwarding) androidx.compose.runtime.remember(state.current, state.items) { viewModel.forwardItem() } else null
    Box(Modifier.fillMaxSize()) {
        ComposerContent(state, viewModel, onClose, onAudience, onFindPeople)
        // Forward (V4) is a list screen in the person's own theme, over the stage.
        if (forward != null) {
            MomentForwardScreen(
                org.koin.compose.viewmodel.koinViewModel(key = "forward-composer"), forward, onClose = { viewModel.openForward(false) },
                onSent = { names -> viewModel.openForward(false); viewModel.notice(forwardedNotice(names)) },
            )
        }
    }
}

/** The words a moment uses in the shared composer. */
private val MomentCopy = MediaComposeCopy(itemNoun = "Moment", sendVerb = "post", trimHint = "Moments are up to a minute. Drag the edges to choose which minute.")

/**
 * The shared composer (MediaComposeStage, the same screen a chat sends photos, videos and files from) with what only a
 * moment has: text and voice, the backgrounds, the audience and Forward.
 */
@Composable
private fun ComposerContent(state: MomentComposerUiState, vm: MomentComposerViewModel, onClose: () -> Unit, onAudience: () -> Unit, onFindPeople: () -> Unit) {
    val capture = state.capture
    MediaComposeStage(
        state = state.media,
        composer = vm.composer,
        camera = vm.camera,
        modes = ComposerMode.entries,
        copy = MomentCopy,
        send = MediaSendButton(
            description = if (state.items.size > 1) "Share ${state.items.size} moments" else "Share moment",
            enabled = state.canShare, busy = state.sharing || state.preparing, onClick = vm::share,
        ),
        footnote = when {
            state.nobody -> null
            state.preparing -> "Making the video smaller to share…"
            state.items.size > 1 -> "${state.items.size} moments, in this order. End-to-end encrypted."
            else -> "End-to-end encrypted. Gone after 24 hours."
        },
        onClose = onClose,
        reviewing = capture != null || state.mode == ComposerMode.Text,
        customStage = when {
            capture is ComposerCapture.Voice -> ({ VoiceStageReview(capture, state.playingBack, vm) })
            capture != null -> null
            state.mode == ComposerMode.Text -> ({ TextEditor(state, vm) })
            state.mode == ComposerMode.Voice -> ({ VoiceRecorderStage(state, vm) })
            else -> null
        },
        recordingOther = state.recordingVoice,
        topActions = {
            if (capture == null && state.mode == ComposerMode.Text) {
                StageButton(null, "Font: ${state.style.label}. Change", label = "Aa", bold = true, onClick = vm::toggleStyle)
            }
            if (capture != null) StageButton(CircasoIcon.Forward, "Forward to a chat") { vm.openForward(true) }
        },
        reviewExtras = {
            if (capture == null) Swatches(state.background, vm::onBackground)
            if (capture is ComposerCapture.Voice) Swatches(state.voiceBackground, vm::onVoiceBackground)
        },
        leading = { AudienceChip(state, onAudience) },
        belowSend = { if (state.nobody) NobodyBanner(onFindPeople) },
        covered = state.forwarding,
    )
}

/**
 * Who sees it, beside Share. Compact (Pasaka, 2026-11-04): the people icon and the chevron only, to leave room for the
 * moment. The audience is still read out in full, and the target stays 48 dp.
 */
@Composable
private fun AudienceChip(state: MomentComposerUiState, onAudience: () -> Unit) {
    val c = CircasoTheme.colors
    Row(
        Modifier.heightIn(min = 48.dp).clip(CircasoShapes.pill).background(c.glass).pressable(onClick = onAudience).padding(horizontal = 14.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Who sees this: ${state.audienceLabel.replace(" · ", ", ")}. Change"; role = Role.Button },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CircasoIconImage(CircasoIcon.People, if (state.nobody) c.critical else c.ink, Modifier.size(18.dp))
        CircasoIconImage(CircasoIcon.ChevronDown, c.ink, Modifier.size(14.dp))
    }
}

/** Caught before Share, not after: a moment with nobody to see it is never posted. */
@Composable
private fun NobodyBanner(onFindPeople: () -> Unit) {
    val c = CircasoTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4).clip(CircasoShapes.md).background(c.glass).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText("Moments go to your contacts on Circaso. None are here yet.", Modifier.weight(1f), style = CircasoTheme.typography.footnote.copy(color = c.ink))
        CircasoButton("Find People", onFindPeople, style = ButtonStyle.Secondary, size = ButtonSize.Small)
    }
}

/** M2: big centred text on its background, in the chosen font. */
@Composable
private fun TextEditor(state: MomentComposerUiState, vm: MomentComposerViewModel) {
    val swatch = MomentPalette.of(state.background)
    val (family, weight) = momentFont(state.style)
    val t = CircasoTheme.typography
    Box(Modifier.fillMaxSize().background(brush = swatch.brush).padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
        val style = t.title1.copy(color = swatch.ink, textAlign = TextAlign.Center, fontWeight = weight, fontFamily = family ?: t.title1.fontFamily, fontSize = 32.sp, lineHeight = 39.sp)
        if (state.text.isEmpty()) BasicText("Type a moment", style = style.copy(color = swatch.ink.copy(alpha = 0.55f)))
        BasicTextField(state.text, vm::onText, textStyle = style, cursorBrush = SolidColor(swatch.ink), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Moment text" })
    }
}

/** Every background in the palette, scrolling (Pasaka, 2026-11-03: many colours). */
@Composable
private fun Swatches(selected: String, onPick: (String) -> Unit) {
    val c = CircasoTheme.colors
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = CircasoSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(MomentPalette.entries.size) { i ->
            val swatch = MomentPalette.entries[i]
            val on = swatch.id == selected || (i == 0 && MomentPalette.of(selected) == swatch)
            Box(
                Modifier.size(CircasoSizes.controlMd).padding(5.dp).border(if (on) 2.5.dp else 1.dp, if (on) c.ink else c.inkSecondary, CircasoShapes.circle).padding(3.dp)
                    .clip(CircasoShapes.circle).background(brush = swatch.brush).pressable(onClick = { onPick(swatch.id) })
                    .semantics { contentDescription = "Background ${swatch.name}"; role = Role.RadioButton; this.selected = on },
            )
        }
    }
}

/** M5b: hold the mic to record (1:00 at most), slide left to cancel, let go to review (M5). */
@Composable
private fun BoxScope.VoiceRecorderStage(state: MomentComposerUiState, vm: MomentComposerViewModel) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    if (state.micDenied) {
        Blocked("Allow the microphone for voice moments", "Circaso only listens while you hold the mic button.", icon = CircasoIcon.Mic)
        return
    }
    // The background the person chose (M5), the same palette as text moments; the wave and words take its ink.
    val swatch = MomentPalette.of(state.voiceBackground)
    Box(Modifier.fillMaxSize().background(brush = swatch.brush))
    // The viewer's shades (design shade-top/bot), so the controls and lines read on a light background too.
    VoiceShades()
    Column(Modifier.align(Alignment.Center).padding(horizontal = CircasoSpacing.s6), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        com.circaso.designsystem.components.Avatar(state.myName.ifBlank { "Me" }, size = CircasoSizes.avatarXl, imageUrl = state.myAvatarUrl,
            modifier = Modifier.border(3.dp, swatch.ink.copy(alpha = 0.9f), CircasoShapes.circle))
        // Live: the microphone's own level, newest on the right; read in the draw phase, so only the wave redraws.
        // Idle: a quiet line. The timer below speaks for it.
        val live = vm.liveLevels.collectAsState()
        val recording = state.recordingVoice
        VoiceWave(
            levels = { val l = live.value; if (recording && l.isNotEmpty()) List(MomentComposerViewModel.LiveBars - l.size) { 0f } + l else IdleWave },
            progress = 1f, playing = false, ink = swatch.ink, bars = MomentComposerViewModel.LiveBars,
        )
        BasicText(
            if (state.recordingVoice) elapsedLabel(state.recordingSince) else "Hold to record",
            style = t.title3.copy(color = swatch.ink, fontFeatureSettings = "tnum"),
        )
    }
    var dragX by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 180.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.graphicsLayer { translationX = dragX }.size(84.dp).clip(CircasoShapes.circle).background(if (state.recordingVoice) c.ink else c.glass)
                .semantics {
                    role = Role.Button
                    contentDescription = if (state.recordingVoice) "Recording, release to stop" else "Hold to record a voice moment"
                    // Screen readers: tap to start, tap again to stop (a hold can't be performed there).
                    onClick(if (state.recordingVoice) "Stop recording" else "Start recording") { if (vm.state.value.recordingVoice) vm.voiceUp() else vm.voiceDown(); true }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        dragX = 0f
                        vm.voiceDown()
                        var cancelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            dragX = (dragX + (change.position.x - change.previousPosition.x)).coerceAtMost(0f)
                            if (dragX < -120.dp.toPx()) { cancelled = true; break }
                        }
                        dragX = 0f
                        if (cancelled) vm.voiceCancel() else vm.voiceUp()
                    }
                },
            contentAlignment = Alignment.Center,
        ) { CircasoIconImage(CircasoIcon.Mic, if (state.recordingVoice) c.critical else c.ink, Modifier.size(30.dp)) }
        if (state.recordingVoice) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CircasoIconImage(CircasoIcon.Back, c.inkSecondary, Modifier.size(14.dp))
                BasicText("Slide to cancel", style = t.subhead.copy(color = c.inkSecondary))
            }
        }
    }
}

/** M5: the recorded voice, before sharing. Play to listen back; the bin starts again. */
@Composable
private fun BoxScope.VoiceStageReview(voice: ComposerCapture.Voice, playing: Boolean, vm: MomentComposerViewModel) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val state by vm.state.collectAsState()
    // The background the person chose (M5), the same palette as text moments; the wave and words take its ink.
    val swatch = MomentPalette.of(state.voiceBackground)
    Box(Modifier.fillMaxSize().background(brush = swatch.brush))
    // The viewer's shades (design shade-top/bot), so the controls and lines read on a light background too.
    VoiceShades()
    Column(Modifier.align(Alignment.Center).padding(horizontal = CircasoSpacing.s6), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        com.circaso.designsystem.components.Avatar(state.myName.ifBlank { "Me" }, size = CircasoSizes.avatarXl, imageUrl = state.myAvatarUrl,
            modifier = Modifier.border(3.dp, swatch.ink.copy(alpha = 0.9f), CircasoShapes.circle))
        // Filled as it plays, held where it is when paused, back to the start when it ends.
        val progress = playbackProgress(playing, voice) { vm.playbackProgress() }
        val shown = if (playing || state.playbackHeld) progress else 0f
        VoiceWave(
            { voice.levels }, progress = shown, playing = playing, ink = swatch.ink,
            spoken = "${clock((voice.durationMs * shown).toLong())} of ${clock(voice.durationMs)}",
        )
        BasicText("${clock(voice.durationMs)} / 1:00", style = t.title3.copy(color = swatch.ink, fontFeatureSettings = "tnum"))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            StageButton(CircasoIcon.Trash, "Delete recording", onClick = vm::discardCapture)
            StageButton(if (playing) CircasoIcon.Pause else CircasoIcon.Play, if (playing) "Pause" else "Play", filled = true, onClick = vm::togglePlayback)
        }
    }
}

/** A silent wave while the mic waits. */
private val IdleWave = List(MomentComposerViewModel.LiveBars) { 0f }

/** Top and bottom shades over a voice moment's background, as the viewer draws them. */
@Composable
private fun BoxScope.VoiceShades() {
    val c = CircasoTheme.colors
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(160.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.scrim, androidx.compose.ui.graphics.Color.Transparent))))
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(320.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, c.scrim))))
}

