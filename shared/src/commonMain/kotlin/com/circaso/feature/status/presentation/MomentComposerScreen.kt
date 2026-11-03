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
import com.circaso.feature.status.domain.MomentTextStyle

/** Whether the person's own theme is dark, for the list screens over the dark stage (the gallery, M3). */
private val LocalPersonalDark = androidx.compose.runtime.staticCompositionLocalOf { false }

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
        // The dark stage in every theme (design M1), from the dark tokens. The person's own theme is kept for the list
        // screens drawn over it (the gallery).
        androidx.compose.runtime.CompositionLocalProvider(LocalPersonalDark provides CircasoTheme.colors.isDark) {
            CircasoTheme(darkTheme = true, highContrast = CircasoTheme.colors.isHighContrast) {
                ComposerContent(state, viewModel, onClose, onAudience, onFindPeople)
            }
        }
        // Forward (V4) is a list screen in the person's own theme, over the stage.
        if (forward != null) {
            MomentForwardScreen(
                org.koin.compose.viewmodel.koinViewModel(key = "forward-composer"), forward, onClose = { viewModel.openForward(false) },
                onSent = { names -> viewModel.openForward(false); viewModel.notice(forwardedNotice(names)) },
            )
        }
    }
}

@Composable
private fun ComposerContent(state: MomentComposerUiState, vm: MomentComposerViewModel, onClose: () -> Unit, onAudience: () -> Unit, onFindPeople: () -> Unit) {
    val c = CircasoTheme.colors
    val toast = rememberToastState()
    LaunchedEffect(state.error) { state.error?.let { toast.show(it); vm.errorShown() } }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        val capture = state.capture
        when {
            capture is ComposerCapture.Shot -> ShotReview(capture, state.item?.unplayable == true, playing = state.trim == null && !state.forwarding, vm = vm)
            capture is ComposerCapture.Voice -> VoiceStageReview(capture, state.playingBack, vm)
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
                StageButton(null, "Font: ${state.style.label}. Change", label = "Aa", bold = true, onClick = vm::toggleStyle)
            }
            if (state.capture == null && (state.mode == ComposerMode.Photo || state.mode == ComposerMode.Video) && state.camera == CameraStart.Started) {
                StageButton(CircasoIcon.Flash, if (state.flash) "Flash on" else "Flash off", filled = state.flash, onClick = vm::toggleFlash)
            }
            if (state.capture != null) {
                if (state.item?.video?.path != null) StageButton(CircasoIcon.Scissors, "Trim video") { vm.openTrim() }
                StageButton(CircasoIcon.Forward, "Forward to a chat") { vm.openForward(true) }
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
                if (state.items.size > 1) PickedStrip(state, vm)
                if (state.capture != null) CaptionField(state.caption, vm::onCaption)
                // Under the caption, once (design M4): what happens to it.
                if (state.capture != null && !state.nobody) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircasoIconImage(CircasoIcon.Lock, c.inkSecondary, Modifier.size(13.dp))
                        BasicText(
                            when {
                                state.preparing -> "Making the video smaller to share…"
                                state.items.size > 1 -> "${state.items.size} moments, in this order. End-to-end encrypted."
                                else -> "End-to-end encrypted. Gone after 24 hours."
                            },
                            style = CircasoTheme.typography.footnote.copy(color = c.inkSecondary),
                        )
                    }
                }
                if (state.capture == null) Swatches(state.background, vm::onBackground)
                Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
                    // Compact (Pasaka, 2026-11-04): the people icon and the chevron only, to leave room for the moment. The
                    // audience is still read out in full, and the target stays 48 dp.
                    Row(
                        Modifier.heightIn(min = 48.dp).clip(CircasoShapes.pill).background(c.glass).pressable(onClick = onAudience).padding(horizontal = 14.dp)
                            .semantics(mergeDescendants = true) { contentDescription = "Who sees this: ${state.audienceLabel.replace(" · ", ", ")}. Change"; role = Role.Button },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircasoIconImage(CircasoIcon.People, if (state.nobody) c.critical else c.ink, Modifier.size(18.dp))
                        CircasoIconImage(CircasoIcon.ChevronDown, c.ink, Modifier.size(14.dp))
                    }
                    Box(Modifier.weight(1f))
                    Box(
                        Modifier.size(52.dp).clip(CircasoShapes.circle).background(if (state.canShare) c.ink else c.fill)
                            .pressable(enabled = state.canShare, onClick = vm::share)
                            .semantics { contentDescription = if (state.items.size > 1) "Share ${state.items.size} moments" else "Share moment"; role = Role.Button },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.sharing || state.preparing) com.circaso.designsystem.components.Spinner(c.bg, Modifier.size(22.dp))
                        else CircasoIconImage(CircasoIcon.ArrowUp, if (state.canShare) c.bg else c.inkTertiary, Modifier.size(22.dp))
                    }
                }
                if (state.nobody) {
                    // Caught before Share, not after: a moment with nobody to see it is never posted.
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4).clip(CircasoShapes.md).background(c.glass).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BasicText("Moments go to your contacts on Circaso. None are here yet.", Modifier.weight(1f), style = CircasoTheme.typography.footnote.copy(color = c.ink))
                        CircasoButton("Find People", onFindPeople, style = ButtonStyle.Secondary, size = ButtonSize.Small)
                    }
                }
            } else {
                if (!state.recordingVideo && !state.recordingVoice) RecentsStrip(state, vm)
                if (state.selecting && state.selection.isNotEmpty()) SelectionBar(state, vm)
                else if (state.mode != ComposerMode.Voice) CameraControls(state, vm)
            }
            if (state.capture == null && !state.recordingVideo && !state.recordingVoice && !state.selecting) ModeRow(state.mode, vm::setMode)
        }
        // The gallery and the trimmer rise over the stage and fade away (still under reduced motion).
        Layer(state.gallery) { GalleryScreen(it, state, vm) }
        Layer(state.trim) { TrimScreen(it, state, vm) }
        ToastHost(toast, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp))
    }
}

/** While several are ticked on the strip: how many, Cancel and Next (WhatsApp-style), in place of the shutter. */
@Composable
private fun SelectionBar(state: MomentComposerUiState, vm: MomentComposerViewModel) {
    val c = CircasoTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4).heightIn(min = 78.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        CircasoButton("Cancel", vm::clearSelection, style = ButtonStyle.Secondary, size = ButtonSize.Small)
        BasicText(
            "${state.selection.size} selected", Modifier.weight(1f).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
            style = CircasoTheme.typography.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
        )
        CircasoButton("Next", vm::useSelection, size = ButtonSize.Small, busy = state.loadingPick)
    }
}

/** M4: several picked; tap one to put it on the stage (and edit its caption), the bin takes it out. */
@Composable
private fun PickedStrip(state: MomentComposerUiState, vm: MomentComposerViewModel) {
    val c = CircasoTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.foundation.lazy.LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.items.size, key = { state.items[it].capture.hashCode() }) { i ->
                val item = state.items[i]
                val on = i == state.current
                val media = (item.capture as? ComposerCapture.Shot)?.media
                Box(
                    Modifier.size(CircasoSizes.controlMd).clip(CircasoShapes.sm).background(c.fill)
                        .then(if (on) Modifier.border(2.dp, c.ink, CircasoShapes.sm) else Modifier)
                        .pressable(onClick = { vm.select(i) })
                        .semantics { contentDescription = "Moment ${i + 1} of ${state.items.size}" + if (item.tooLong) ", needs trimming" else ""; selected = on; role = Role.Tab },
                ) {
                    val picture = media?.thumb ?: media?.bytes?.takeIf { media.mime.startsWith("image") }
                    if (picture != null) coil3.compose.AsyncImage(picture, null, Modifier.fillMaxSize().graphicsLayer { alpha = if (on) 1f else 0.7f }, contentScale = ContentScale.Crop)
                    if (item.tooLong) CircasoIconImage(CircasoIcon.Scissors, c.ink, Modifier.align(Alignment.Center).size(16.dp))
                }
            }
        }
        StageButton(CircasoIcon.Trash, "Remove this one", onClick = vm::removeCurrent)
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

/**
 * M4: the shot, before sharing. A video shows its thumbnail until the first frame is on screen, so the review is never
 * a black void; if this phone can't play it, the thumbnail stays and a line says it will still post.
 */
@Composable
private fun BoxScope.ShotReview(shot: ComposerCapture.Shot, unplayable: Boolean, playing: Boolean, vm: MomentComposerViewModel) {
    val c = CircasoTheme.colors
    val path = shot.media.path
    if (shot.media.mime.startsWith("video") && path != null) {
        com.circaso.core.platform.MomentVideo(
            path, playing = playing, muted = false, modifier = Modifier.fillMaxSize(), loop = true,
            poster = shot.media.thumb, onUnplayable = vm::markUnplayable,
        )
        if (unplayable) {
            Row(
                Modifier.align(Alignment.Center).padding(horizontal = 32.dp).clip(CircasoShapes.md).background(c.glass).padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircasoIconImage(CircasoIcon.Video, c.ink, Modifier.size(18.dp))
                BasicText("This phone can’t preview this video. It will still post.", style = CircasoTheme.typography.subhead.copy(color = c.ink))
            }
        }
    } else {
        coil3.compose.AsyncImage(shot.media.bytes, "Your photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
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

/**
 * The recents strip (WhatsApp-style): the newest photos, videos or audio on the phone. Tap one to use it; hold one to
 * pick several (numbered in the order they'll post). Without access to this kind it says so and offers to allow it;
 * with limited access (only chosen items) a Manage tile leads.
 */
@Composable
private fun RecentsStrip(state: MomentComposerUiState, vm: MomentComposerViewModel) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val audio = state.mode == ComposerMode.Voice
    val access = state.recentsAccess
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = CircasoSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Photo and video have Gallery beside the shutter (as WhatsApp); audio has no shutter, so Files leads its strip.
        if (audio) item(key = "pick") {
            StripTile("Files", CircasoIcon.Waveform, "Choose an audio file", wide = true, busy = state.loadingPick, onClick = vm::pickFromLibrary)
        }
        if (access == com.circaso.core.platform.LibraryAccess.Denied) item(key = "allow") {
            StripTile(
                "Allow access", CircasoIcon.Lock,
                when (state.mode) { ComposerMode.Video -> "Allow access to your videos"; ComposerMode.Voice -> "Allow access to your audio"; else -> "Allow access to your photos" },
                wide = true, onClick = vm::manageAccess,
            )
        }
        if (access == com.circaso.core.platform.LibraryAccess.Limited) item(key = "manage") {
            StripTile("Manage", CircasoIcon.Plus, "Circaso can see only what you chose. Choose more", wide = false, onClick = vm::manageAccess)
        }
        items(state.recents.size, key = { state.recents[it].id }) { i ->
            val item = state.recents[i]
            if (audio) {
                Column(
                    Modifier.size(120.dp, 56.dp).clip(CircasoShapes.md).background(c.glass).pressable(onClick = { vm.useRecent(item) }).padding(horizontal = 10.dp, vertical = 6.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "Audio ${item.title.orEmpty()}, ${clock(item.durationMs ?: 0)}"; role = Role.Button },
                    verticalArrangement = Arrangement.Center,
                ) {
                    BasicText(item.title ?: "Audio", style = t.footnote.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    BasicText(clock(item.durationMs ?: 0), style = t.caption2.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"))
                }
            } else {
                val order = state.selection.indexOfFirst { it.id == item.id }
                LibraryTile(
                    item, Modifier.size(56.dp), number = if (order >= 0) order + 1 else null, showTick = state.selecting,
                    onClick = { vm.useRecent(item) }, onLongClick = { vm.startSelecting(item) },
                )
            }
        }
    }
}

/** A glass tile at the start of the strip: Files, Allow access, Manage. */
@Composable
private fun StripTile(label: String, icon: CircasoIcon, description: String, wide: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    Column(
        Modifier.size(if (wide) 120.dp else 56.dp, 56.dp).clip(CircasoShapes.md).background(c.glass).pressable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (busy) com.circaso.designsystem.components.Spinner(c.ink)
        else {
            CircasoIconImage(icon, c.ink, Modifier.size(18.dp))
            BasicText(label, style = CircasoTheme.typography.caption2.copy(color = c.ink), maxLines = 1)
        }
    }
}

/**
 * One photo or video in the strip or the gallery: its thumbnail, a video's length, and when picking several the
 * round tick with its number (design M3).
 */
@Composable
private fun LibraryTile(
    item: com.circaso.core.platform.LibraryItem, modifier: Modifier, number: Int?, showTick: Boolean, onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null, shape: androidx.compose.ui.graphics.Shape = CircasoShapes.sm,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val video = item.kind == com.circaso.core.platform.LibraryKind.Video
    Box(
        modifier.clip(shape).background(c.fill).pressable(onLongClick = onLongClick, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = (if (video) "Video, ${clock(item.durationMs ?: 0)}" else "Photo") + if (number != null) ", selected, $number" else ""
                role = if (showTick) Role.Checkbox else Role.Button
                if (showTick) selected = number != null
            },
    ) {
        com.circaso.core.platform.LibraryThumbnail(item, Modifier.fillMaxSize())
        // On the picture in every theme: the light stage ink with a soft shadow (design M3), never the theme's ink.
        item.durationMs?.let {
            BasicText(longClock(it), Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 5.dp),
                style = t.caption1.copy(
                    color = com.circaso.designsystem.theme.DarkColors.ink, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum",
                    shadow = androidx.compose.ui.graphics.Shadow(com.circaso.designsystem.theme.DarkColors.scrim, androidx.compose.ui.geometry.Offset(0f, 1f), 4f),
                ))
        }
        if (showTick) Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { Tick(number != null, number, onMedia = true) }
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
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(LightColors.moneyCardSheen, LightColors.moneyCard, LightColors.moneyCard, LightColors.moneyCardSheen))))
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        com.circaso.designsystem.components.Avatar(state.myName.ifBlank { "Me" }, size = CircasoSizes.avatarXl, imageUrl = state.myAvatarUrl)
        LevelBars(active = playing)
        BasicText("${clock(voice.durationMs)} / 1:00", style = t.title3.copy(color = c.ink, fontFeatureSettings = "tnum"))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            StageButton(CircasoIcon.Trash, "Delete recording", onClick = vm::discardCapture)
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

/** A library video's length, past a minute too ("2:14", "1:02:05"). */
private fun longClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

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

/**
 * M3: the gallery. Recents in a three-column grid with All, Photos and Videos; tap to tick (numbers show the order),
 * Next puts them on the review, each its own moment. Limited access (M3c) says so with Manage; with access off, one
 * line, Open Settings, and the phone's own picker (which needs no permission).
 */
@Composable
private fun GalleryScreen(gallery: GalleryState, state: MomentComposerUiState, vm: MomentComposerViewModel) {
    val t = CircasoTheme.typography
    // The gallery is a list screen: the person's own theme, not the dark stage.
    CircasoTheme(darkTheme = LocalPersonalDark.current, highContrast = CircasoTheme.colors.isHighContrast) {
        val cc = CircasoTheme.colors
        Column(Modifier.fillMaxSize().background(cc.bg).statusBarsPadding().navigationBarsPadding()) {
            // Design M3's bar: Close, "Recents", Next.
            Row(
                Modifier.fillMaxWidth().heightIn(min = CircasoSizes.controlMd).padding(horizontal = CircasoSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(CircasoSizes.controlMd).clip(CircasoShapes.circle).pressable(onClick = vm::closeGallery).semantics { contentDescription = "Close"; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) { CircasoIconImage(CircasoIcon.Close, cc.ink, Modifier.size(20.dp)) }
                BasicText("Recents", Modifier.weight(1f).semantics { heading() }, style = t.headline.copy(color = cc.ink, textAlign = TextAlign.Center))
                CircasoButton(if (state.selection.size > 1) "Next (${state.selection.size})" else "Next", vm::useSelection, size = ButtonSize.Small, enabled = state.selection.isNotEmpty(), busy = state.loadingPick)
            }
            com.circaso.designsystem.components.SegmentedControl(
                listOf("All", "Photos", "Videos"), GalleryTab.entries.indexOf(gallery.tab), { vm.galleryTab(GalleryTab.entries[it]) },
                Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
            )
            if (gallery.access == com.circaso.core.platform.LibraryAccess.Limited) {
                Row(
                    Modifier.fillMaxWidth().padding(CircasoSpacing.s4).clip(CircasoShapes.md).background(cc.fill).padding(horizontal = 14.dp, vertical = 10.dp)
                        .semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircasoIconImage(CircasoIcon.Photo, cc.inkSecondary, Modifier.size(18.dp))
                    val n = gallery.photos.size + gallery.videos.size
                    BasicText("Circaso can see $n ${if (n == 1) "item" else "items"} you chose.", Modifier.weight(1f), style = t.subhead.copy(color = cc.ink))
                    BasicText("Manage", Modifier.heightIn(min = CircasoSizes.controlMd).pressable(onClick = vm::manageAccess).padding(horizontal = 4.dp, vertical = 12.dp), style = t.subhead.copy(color = cc.action, fontWeight = FontWeight.SemiBold))
                }
            } else {
                Box(Modifier.height(CircasoSpacing.s3))
            }
            val items = androidx.compose.runtime.remember(gallery.photos, gallery.videos, gallery.tab) { gallery.items }
            when {
                gallery.loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { com.circaso.designsystem.components.Spinner(cc.inkSecondary) }
                gallery.access == com.circaso.core.platform.LibraryAccess.Denied -> Column(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    CircasoIconImage(CircasoIcon.Photo, cc.inkTertiary, Modifier.size(40.dp))
                    BasicText("Allow photo access to share from your gallery.", style = t.body.copy(color = cc.ink, textAlign = TextAlign.Center))
                    CircasoButton("Open Settings", vm::manageAccess, style = ButtonStyle.Secondary, size = ButtonSize.Small)
                    BasicText("Choose from your phone instead", Modifier.heightIn(min = CircasoSizes.controlMd).pressable(onClick = { vm.closeGallery(); vm.pickWithSystem() }).padding(horizontal = 8.dp, vertical = 12.dp),
                        style = t.subhead.copy(color = cc.action, fontWeight = FontWeight.SemiBold))
                }
                items.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) {
                    BasicText(when (gallery.tab) { GalleryTab.Videos -> "No videos here yet."; GalleryTab.Photos -> "No photos here yet."; else -> "Nothing here yet." },
                        style = t.body.copy(color = cc.inkSecondary, textAlign = TextAlign.Center))
                }
                else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    androidx.compose.foundation.lazy.grid.GridCells.Fixed(3), Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(items.size, key = { items[it].id }) { i ->
                        val item = items[i]
                        val order = state.selection.indexOfFirst { it.id == item.id }
                        LibraryTile(item, Modifier.fillMaxWidth().aspectRatio(1f), number = if (order >= 0) order + 1 else null, showTick = true, onClick = { vm.toggle(item) }, shape = androidx.compose.ui.graphics.RectangleShape)
                    }
                }
            }
        }
    }
}

/**
 * M3b: trim a video. The frame on the stage, Cancel and Done at the top, "0:58 selected of 2:14" and the filmstrip with
 * two handles below. The window is at most a minute and at least a second; the trimmed copy stays on this phone until
 * it is shared.
 */
@Composable
private fun TrimScreen(trim: TrimState, state: MomentComposerUiState, vm: MomentComposerViewModel) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Box(Modifier.fillMaxSize().background(c.bg).pointerInput(Unit) { detectTapGestures { } }) {
        val thumb = (state.items.getOrNull(trim.index)?.capture as? ComposerCapture.Shot)?.media?.thumb
        val still = trim.frames.getOrNull(trim.frames.size / 2) ?: thumb
        if (still != null) coil3.compose.AsyncImage(still, null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.9f }, contentScale = ContentScale.Crop)
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StageButton(CircasoIcon.Close, if (trim.required) "Cancel, and leave this video out" else "Cancel trimming", onClick = vm::cancelTrim)
            Box(Modifier.weight(1f))
            CircasoButton("Done", vm::applyTrim, size = ButtonSize.Small, busy = trim.working)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = CircasoSpacing.s4, vertical = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                if (trim.working) "Trimming…" else "${longClock(trim.selectedMs)} selected of ${longClock(trim.durationMs)}",
                Modifier.clip(CircasoShapes.pill).background(c.scrim).padding(horizontal = 12.dp, vertical = 4.dp)
                    .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            )
            TrimBar(trim, vm)
            if (trim.required) BasicText("Moments are up to a minute. Drag the edges to choose which minute.", style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        }
    }
}

/** The filmstrip with its two handles (design M3b): drag an edge to move it, the middle to slide the whole window. */
@Composable
private fun TrimBar(trim: TrimState, vm: MomentComposerViewModel) {
    val c = CircasoTheme.colors
    val handle = 12.dp
    var widthPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(1) }
    val latest by androidx.compose.runtime.rememberUpdatedState(trim)
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(CircasoShapes.sm).background(c.fill)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .semantics {
                contentDescription = "Trim"
                stateDescription = "${longClock(trim.startMs)} to ${longClock(trim.endMs)}"
                // Screen readers: move the window a few seconds at a time.
                customActions = listOf(
                    androidx.compose.ui.semantics.CustomAccessibilityAction("Later by 5 seconds") { val x = latest; vm.setTrim(x.startMs + 5_000, x.endMs + 5_000); true },
                    androidx.compose.ui.semantics.CustomAccessibilityAction("Earlier by 5 seconds") { val x = latest; vm.setTrim(x.startMs - 5_000, x.endMs - 5_000); true },
                    androidx.compose.ui.semantics.CustomAccessibilityAction("Shorter by 5 seconds") { val x = latest; vm.setTrim(x.startMs, x.endMs - 5_000); true },
                    androidx.compose.ui.semantics.CustomAccessibilityAction("Longer by 5 seconds") { val x = latest; vm.setTrim(x.startMs, x.endMs + 5_000); true },
                )
            }
            .pointerInput(trim.durationMs) {
                val edge = 28.dp.toPx()
                var grab = 0 // -1 start, 1 end, 0 the window
                detectHorizontalDragGestures(
                    onDragStart = { at ->
                        val x = latest
                        val startX = x.startMs.toFloat() / x.durationMs * widthPx
                        val endX = x.endMs.toFloat() / x.durationMs * widthPx
                        grab = when { kotlin.math.abs(at.x - startX) < edge -> -1; kotlin.math.abs(at.x - endX) < edge -> 1; else -> 0 }
                    },
                ) { change, dx ->
                    change.consume()
                    val x = latest
                    val dMs = (dx / widthPx * x.durationMs).toLong()
                    when (grab) {
                        -1 -> vm.setTrim(x.startMs + dMs, x.endMs)
                        1 -> vm.setTrim(x.startMs, x.endMs + dMs)
                        else -> {
                            val shift = dMs.coerceIn(-x.startMs, x.durationMs - x.endMs)
                            vm.setTrim(x.startMs + shift, x.endMs + shift)
                        }
                    }
                }
            },
    ) {
        Row(Modifier.fillMaxSize()) {
            if (trim.frames.isEmpty()) Box(Modifier.fillMaxSize())
            trim.frames.forEach { f ->
                coil3.compose.AsyncImage(f, null, Modifier.weight(1f).fillMaxHeight().graphicsLayer { alpha = 0.45f }, contentScale = ContentScale.Crop)
            }
        }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val left = with(density) { (trim.startMs.toFloat() / trim.durationMs * widthPx).toDp() }
        val width = with(density) { ((trim.endMs - trim.startMs).toFloat() / trim.durationMs * widthPx).toDp() }
        Box(
            Modifier.offset(x = left).width(width).fillMaxHeight()
                .border(androidx.compose.foundation.BorderStroke(3.dp, c.ink), CircasoShapes.sm),
        ) {
            Box(Modifier.align(Alignment.CenterStart).width(handle).fillMaxHeight().background(c.ink))
            Box(Modifier.align(Alignment.CenterEnd).width(handle).fillMaxHeight().background(c.ink))
        }
    }
}

/** A full-screen layer over the stage: in with a short rise and fade, out with a fade; the last value draws the exit. */
@Composable
private fun <T : Any> Layer(value: T?, content: @Composable (T) -> Unit) {
    val reduce = CircasoTheme.reduceMotion
    var last by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(value) }
    if (value != null) last = value
    androidx.compose.animation.AnimatedVisibility(
        value != null, Modifier.fillMaxSize(),
        enter = if (reduce) androidx.compose.animation.fadeIn(androidx.compose.animation.core.snap())
        else androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.BaseMs)) { it / 12 } +
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.BaseMs)),
        exit = if (reduce) androidx.compose.animation.fadeOut(androidx.compose.animation.core.snap())
        else androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.QuickMs)),
    ) { last?.let { content(it) } }
}
