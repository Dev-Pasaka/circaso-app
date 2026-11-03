package com.circaso.feature.status.presentation

import androidx.compose.foundation.background
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
import com.circaso.core.platform.CameraStart
import com.circaso.core.platform.CameraViewfinder
import com.circaso.core.platform.VideoPlayer
import com.circaso.core.platform.openAppSettings
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
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
import com.circaso.feature.status.domain.MomentBackground
import com.circaso.feature.status.domain.MomentTextStyle

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MomentComposerScreen(viewModel: MomentComposerViewModel, onClose: () -> Unit, onAudience: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.shared) { if (state.shared) onClose() }
    androidx.compose.ui.backhandler.BackHandler { if (state.capture != null) viewModel.discardCapture() else onClose() }
    // The dark stage in every theme (design M1), from the dark tokens.
    CircasoTheme(darkTheme = true, highContrast = CircasoTheme.colors.isHighContrast) {
        ComposerContent(state, viewModel, onClose, onAudience)
    }
}

@Composable
private fun ComposerContent(state: MomentComposerUiState, vm: MomentComposerViewModel, onClose: () -> Unit, onAudience: () -> Unit) {
    val c = CircasoTheme.colors
    val toast = rememberToastState()
    LaunchedEffect(state.error) { state.error?.let { toast.show(it); vm.errorShown() } }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        when {
            state.capture is ComposerCapture.Shot -> ShotReview(state.capture, state)
            state.capture is ComposerCapture.Voice -> VoiceStageReview(state.capture, state.playingBack, vm)
            state.mode == ComposerMode.Text -> TextEditor(state, vm)
            state.mode == ComposerMode.Voice -> VoiceRecorderStage(state, vm)
            state.camera == CameraStart.Started -> CameraViewfinder(vm.camera, Modifier.fillMaxSize())
            state.camera != null -> CameraBlocked(state.camera)
        }

        // Top bar: close, and per mode the camera's flash or the text style.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StageButton(CircasoIcon.Close, if (state.capture != null) "Discard" else "Close") { if (state.capture != null) vm.discardCapture() else onClose() }
            Box(Modifier.weight(1f))
            if (state.capture == null && state.mode == ComposerMode.Text) {
                StageButton(null, if (state.style == MomentTextStyle.Bold) "Text style: Bold" else "Text style: Regular", label = "Aa", bold = state.style == MomentTextStyle.Bold, onClick = vm::toggleStyle)
            }
            if (state.capture == null && (state.mode == ComposerMode.Photo || state.mode == ComposerMode.Video) && state.camera == CameraStart.Started) {
                StageButton(CircasoIcon.Flash, if (state.flash) "Flash on" else "Flash off", filled = state.flash, onClick = vm::toggleFlash)
            }
        }

        if (state.recordingVideo) {
            Row(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp).clip(CircasoShapes.pill).background(c.ink).padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics { contentDescription = "Recording"; liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircasoShapes.circle).background(c.critical))
                BasicText(elapsedLabel(state.recordingSince), style = CircasoTheme.typography.subhead.copy(color = c.bg, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.capture != null || state.mode == ComposerMode.Text) {
                if (state.capture != null) CaptionField(state.caption, vm::onCaption)
                if (state.capture == null) Swatches(state.background, vm::onBackground)
                Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(CircasoShapes.pill).background(c.glass).pressable(onClick = onAudience).padding(horizontal = 14.dp, vertical = 12.dp)
                            .semantics(mergeDescendants = true) { contentDescription = "Who can see this: ${state.audienceLabel}. Change" },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircasoIconImage(CircasoIcon.Person, c.ink, Modifier.size(16.dp))
                        BasicText(state.audienceLabel, style = CircasoTheme.typography.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1)
                    }
                    Box(Modifier.weight(1f))
                    Box(
                        Modifier.size(52.dp).clip(CircasoShapes.circle).background(if (state.canShare) c.ink else c.fill)
                            .pressable(enabled = state.canShare, onClick = vm::share).semantics { contentDescription = "Share moment"; role = Role.Button },
                        contentAlignment = Alignment.Center,
                    ) { CircasoIconImage(CircasoIcon.ArrowUp, if (state.canShare) c.bg else c.inkTertiary, Modifier.size(22.dp)) }
                }
                if (state.capture != null) {
                    Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircasoIconImage(CircasoIcon.Check, c.inkSecondary, Modifier.size(13.dp))
                        BasicText("End-to-end encrypted. Gone after 24 hours.", style = CircasoTheme.typography.footnote.copy(color = c.inkSecondary))
                    }
                }
            } else if (state.mode != ComposerMode.Voice) {
                CameraControls(state, vm)
            }
            if (state.capture == null && !state.recordingVideo && !state.recordingVoice) ModeRow(state.mode, vm::setMode)
        }
        ToastHost(toast, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp))
    }
}

@Composable
private fun CameraControls(state: MomentComposerUiState, vm: MomentComposerViewModel) {
    val c = CircasoTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 34.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        StageButton(CircasoIcon.Photo, "Gallery", onClick = vm::pickFromGallery)
        // Tap for a photo (Photo mode); hold for a video up to 60 s in either mode (M1b). Lifting stops it, even when
        // the recording was still starting. Screen readers get the same as plain actions: photo, start and stop video.
        val ready = state.camera == CameraStart.Started
        val progress = recordingProgress(state.recordingSince)
        Box(
            Modifier.size(78.dp)
                .drawBehind {
                    val w = 4.dp.toPx()
                    drawCircle(c.ink.copy(alpha = if (state.recordingVideo) 0.35f else 1f), radius = size.minDimension / 2 - w / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                    if (state.recordingVideo) drawArc(c.ink, -90f, 360f * progress, false, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                }
                .padding(if (state.recordingVideo) 24.dp else 8.dp).clip(if (state.recordingVideo) androidx.compose.foundation.shape.RoundedCornerShape(8.dp) else CircasoShapes.circle)
                .background(if (state.recordingVideo) c.critical else if (ready) c.ink else c.fill)
                .semantics {
                    role = Role.Button
                    contentDescription = when { !ready -> "Camera unavailable"; state.recordingVideo -> "Stop recording"; state.mode == ComposerMode.Video -> "Hold to record video"; else -> "Take photo, hold for video" }
                    if (ready) {
                        onClick(if (state.recordingVideo) "Stop recording" else if (state.mode == ComposerMode.Video) "Record video" else "Take photo") {
                            when { vm.state.value.recordingVideo -> vm.holdEnd(); vm.state.value.mode == ComposerMode.Video -> vm.holdStart(); else -> vm.takePhoto() }; true
                        }
                        onLongClick("Record video") { vm.holdStart(); true }
                    }
                }
                .pointerInput(ready) {
                    if (!ready) return@pointerInput
                    detectTapGestures(
                        onTap = { if (vm.state.value.mode == ComposerMode.Photo) vm.takePhoto() },
                        onLongPress = { vm.holdStart() },
                        onPress = { if (vm.state.value.mode == ComposerMode.Video) vm.holdStart(); tryAwaitRelease(); vm.holdEnd() },
                    )
                },
        )
        StageButton(CircasoIcon.FlipCamera, "Flip camera", onClick = vm::flip)
    }
}

@Composable
private fun ModeRow(mode: ComposerMode, onMode: (ComposerMode) -> Unit) {
    val c = CircasoTheme.colors
    Row(Modifier.fillMaxWidth().semantics { contentDescription = "Mode" }, horizontalArrangement = Arrangement.Center) {
        ComposerMode.entries.forEach { m ->
            BasicText(
                m.name,
                Modifier.pressable(onClick = { onMode(m) }).semantics { role = Role.Tab; selected = m == mode }.padding(horizontal = 12.dp, vertical = 13.dp),
                style = CircasoTheme.typography.subhead.copy(color = if (m == mode) c.ink else c.inkSecondary, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/** M4: the shot, before sharing. */
@Composable
private fun ShotReview(shot: ComposerCapture.Shot, state: MomentComposerUiState) {
    if (shot.media.mime.startsWith("video") && shot.media.path != null) VideoPlayer(shot.media.path, Modifier.fillMaxSize())
    else coil3.compose.AsyncImage(shot.media.bytes, "Your photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
}

@Composable
private fun CaptionField(value: String, onChange: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Box(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4).clip(CircasoShapes.lg).background(c.glass).padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (value.isEmpty()) BasicText("Add a caption", style = t.body.copy(color = c.inkSecondary))
        BasicTextField(value, onChange, textStyle = t.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Caption" })
    }
}

/** M2: big centred text on its background. */
@Composable
private fun TextEditor(state: MomentComposerUiState, vm: MomentComposerViewModel) {
    val (bg, ink) = composerColours(state.background)
    val t = CircasoTheme.typography
    Box(Modifier.fillMaxSize().background(bg).padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
        val style = t.title1.copy(color = ink, textAlign = TextAlign.Center, fontWeight = if (state.style == MomentTextStyle.Bold) FontWeight.Bold else FontWeight.Normal)
        if (state.text.isEmpty()) BasicText("Type a moment", style = style.copy(color = ink.copy(alpha = 0.55f)))
        BasicTextField(state.text, vm::onText, textStyle = style, cursorBrush = SolidColor(ink), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Moment text" })
    }
}

@Composable
private fun Swatches(selected: MomentBackground, onPick: (MomentBackground) -> Unit) {
    val c = CircasoTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
        MomentBackground.entries.forEach { bg ->
            val (fill, _) = composerColours(bg)
            Box(
                Modifier.size(CircasoSizes.controlMd).padding(5.dp).border(if (bg == selected) 2.5.dp else 1.5.dp, c.ink, CircasoShapes.circle).padding(3.dp)
                    .clip(CircasoShapes.circle).background(fill).pressable(onClick = { onPick(bg) })
                    .semantics { contentDescription = "Background ${bg.name}"; role = Role.RadioButton; this.selected = bg == selected },
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
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(LightColors.moneyCardSheen, LightColors.moneyCard, LightColors.moneyCard, LightColors.moneyCardSheen))))
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        com.circaso.designsystem.components.Avatar(state.myName.ifBlank { "Me" }, size = CircasoSizes.avatarXl, imageUrl = state.myAvatarUrl)
        LevelBars(active = state.recordingVoice)
        BasicText(
            if (state.recordingVoice) elapsedLabel(state.recordingSince) else "Hold to record",
            style = t.title3.copy(color = c.ink, fontFeatureSettings = "tnum"),
        )
    }
    var dragX by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(LightColors.moneyCardSheen, LightColors.moneyCard, LightColors.moneyCard, LightColors.moneyCardSheen))))
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        com.circaso.designsystem.components.Avatar(state.myName.ifBlank { "Me" }, size = CircasoSizes.avatarXl, imageUrl = state.myAvatarUrl)
        LevelBars(active = playing)
        BasicText("${clock(voice.durationMs)} / 1:00", style = t.title3.copy(color = c.ink, fontFeatureSettings = "tnum"))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            StageButton(CircasoIcon.Delete, "Delete recording", onClick = vm::discardCapture)
            StageButton(if (playing) CircasoIcon.Pause else CircasoIcon.Play, if (playing) "Pause" else "Play", filled = true, onClick = vm::togglePlayback)
        }
    }
}

/** A level meter while recording or playing. The recorder reports no level, so the bars breathe at a steady pace. */
@Composable
private fun LevelBars(active: Boolean) {
    val c = CircasoTheme.colors
    val reduce = CircasoTheme.reduceMotion
    val phase = if (active && !reduce) androidx.compose.animation.core.rememberInfiniteTransition().animateFloat(0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing))).value else 0f
    Row(Modifier.height(48.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(32) { i ->
            val base = ((i * 37 + 11) % 97) / 97f
            val wave = if (active) (kotlin.math.sin((phase + i / 32f) * 6.2832f) + 1f) / 2f else 0.3f
            Box(Modifier.size(3.dp, (8 + 40 * (0.35f * base + 0.65f * wave)).dp).clip(CircasoShapes.pill).background(c.ink))
        }
    }
}

private fun clock(ms: Long): String { val s = (ms / 1000).coerceIn(0, 60); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

/** "0:14 / 1:00" for the recording in progress; it ticks every second. */
@Composable
private fun elapsedLabel(since: Long?): String {
    val now by androidx.compose.runtime.produceState(com.circaso.core.sync.nowMillis(), since) { while (since != null) { value = com.circaso.core.sync.nowMillis(); kotlinx.coroutines.delay(250) } }
    return "${clock(if (since == null) 0 else now - since)} / 1:00"
}

/** How far a recording is towards 1:00, for the shutter ring (M1b). */
@Composable
private fun recordingProgress(since: Long?): Float {
    val now by androidx.compose.runtime.produceState(com.circaso.core.sync.nowMillis(), since) { while (since != null) { value = com.circaso.core.sync.nowMillis(); kotlinx.coroutines.delay(100) } }
    return if (since == null) 0f else ((now - since) / com.circaso.core.platform.MomentVideoMaxMs.toFloat()).coerceIn(0f, 1f)
}

/** M1c: camera not allowed or not there; gallery and text still work. */
@Composable
private fun BoxScope.CameraBlocked(start: CameraStart) {
    if (start == CameraStart.PermissionDenied) Blocked("Allow camera to take moments", "Circaso uses the camera only while this screen is open.")
    else Blocked("No camera here", "Pick a photo from your gallery, or write a text moment.", settings = false)
}

@Composable
private fun BoxScope.Blocked(title: String, sub: String, settings: Boolean = true, icon: CircasoIcon = CircasoIcon.Camera) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircasoIconImage(icon, c.inkSecondary, Modifier.size(40.dp))
        BasicText(title, style = t.title3.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(sub, style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        if (settings) CircasoButton("Open Settings", { openAppSettings() }, style = ButtonStyle.Secondary, size = ButtonSize.Small)
    }
}

@Composable
private fun StageButton(icon: CircasoIcon?, description: String, label: String? = null, bold: Boolean = false, filled: Boolean = false, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    Box(
        Modifier.size(CircasoSizes.controlMd).clip(CircasoShapes.circle).background(if (filled) c.ink else c.glass)
            .pressable(onClick = onClick).semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) BasicText(label, style = CircasoTheme.typography.headline.copy(color = c.ink, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
        else if (icon != null) CircasoIconImage(icon, if (filled) c.bg else c.ink, Modifier.size(20.dp))
    }
}

/** The same fixed text backgrounds the viewer draws. */
private fun composerColours(bg: MomentBackground) = when (bg) {
    MomentBackground.Ink -> androidx.compose.ui.graphics.SolidColor(LightColors.ink) to LightColors.bg
    MomentBackground.Metal -> androidx.compose.ui.graphics.Brush.linearGradient(listOf(LightColors.moneyCardSheen, LightColors.moneyCard, LightColors.moneyCard, LightColors.moneyCardSheen)) to LightColors.onMoney
    MomentBackground.Paper -> androidx.compose.ui.graphics.SolidColor(LightColors.fill) to LightColors.ink
    MomentBackground.Graphite -> androidx.compose.ui.graphics.SolidColor(LightColors.inkSecondary) to LightColors.bg
}
