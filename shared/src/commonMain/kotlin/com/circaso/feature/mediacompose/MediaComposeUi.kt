package com.circaso.feature.mediacompose

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.CameraStart
import com.circaso.core.platform.CameraViewfinder
import com.circaso.core.platform.LibraryAccess
import com.circaso.core.platform.LibraryItem
import com.circaso.core.platform.LibraryKind
import com.circaso.core.platform.MomentCamera
import com.circaso.core.platform.openAppSettings
import com.circaso.core.platform.excludeSystemGestures
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/**
 * The words that differ between a moment and a chat; the screens, controls and motion are the same.
 * [itemNoun]: one item on the review strip ("Moment 2 of 3"); [sendVerb]: "post" or "send".
 */
class MediaComposeCopy(
    val itemNoun: String,
    val sendVerb: String,
    val trimHint: String,
    /** Show [trimHint] whenever the trimmer is open (a chat, where cutting is optional), not only when a trim is required. */
    val trimHintAlways: Boolean = false,
)

/** The Send button's state: what it says to a screen reader, whether it can be tapped, and whether it is working. */
class MediaSendButton(val description: String, val enabled: Boolean, val busy: Boolean, val onClick: () -> Unit)

/** Whether the person's own theme is dark, for the list screens over the dark stage (the gallery, M3). */
private val LocalPersonalDark = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * The composer's whole screen, the same for a moment and a chat (Pasaka, 2026-11-07): the dark stage in every theme
 * (design M1), the top bar, the camera with its recents strip, shutter and modes, the review (M4) with its strip,
 * caption, the line saying what happens to it and the round Send, and the gallery (M3) and trimmer (M3b) rising over
 * it. What differs comes in through the slots: [leading] beside Send (a moment's audience, a chat's recipient),
 * [customStage] for a moment's text and voice, [topActions] (a moment's font and Forward) and [reviewExtras]
 * (a moment's backgrounds).
 */
@Composable
fun MediaComposeStage(
    state: MediaComposeState,
    composer: MediaComposer,
    camera: MomentCamera?,
    modes: List<ComposerMode>,
    copy: MediaComposeCopy,
    send: MediaSendButton,
    footnote: String?,
    onClose: () -> Unit,
    leading: @Composable RowScope.() -> Unit,
    /** True while the review shows (a capture, or a moment's text editor). */
    reviewing: Boolean = state.capture != null,
    /** A moment's own stages (text, voice); null shows the camera or the captured shot. */
    customStage: (@Composable BoxScope.() -> Unit)? = null,
    /** Recording something the shared stage doesn't know about (a moment's voice): the strip and modes step aside. */
    recordingOther: Boolean = false,
    topActions: @Composable RowScope.() -> Unit = {},
    reviewExtras: @Composable ColumnScope.() -> Unit = {},
    belowSend: @Composable ColumnScope.() -> Unit = {},
    /** Something covers the stage (a moment's Forward): the video on the review holds still and silent. */
    covered: Boolean = false,
    /** Close on the review (everything picked is let go); a chat opened on Files closes instead. */
    onDiscard: () -> Unit = composer::discard,
    /** Close on the gallery (M3). */
    onCloseGallery: () -> Unit = composer::closeGallery,
    /** False where there is no camera to come back to (a chat opened on Files): no strip, shutter or modes. */
    showCamera: Boolean = true,
) {
    // While it is being made ready and queued nothing on the review can change: what is on screen is what goes.
    val locked = send.busy
    androidx.compose.runtime.CompositionLocalProvider(LocalPersonalDark provides CircasoTheme.colors.isDark) {
        CircasoTheme(darkTheme = true, highContrast = CircasoTheme.colors.isHighContrast) {
            val c = CircasoTheme.colors
            val toast = rememberToastState()
            LaunchedEffect(state.error) { state.error?.let { toast.show(it); composer.errorShown() } }
            Box(Modifier.fillMaxSize().background(c.bg)) {
                val capture = state.capture
                when {
                    capture is ComposerCapture.Shot -> ShotReview(capture, state.item?.unplayable == true, playing = state.trim == null && !covered, copy, composer)
                    capture is ComposerCapture.Document -> DocumentReview(capture)
                    customStage != null -> customStage()
                    state.camera == CameraStart.Started && camera != null -> CameraViewfinder(camera, Modifier.fillMaxSize())
                    state.camera != null -> CameraBlocked(state.camera)
                }

                // Top bar: close, and per mode the camera's flash or the review's tools.
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StageButton(CircasoIcon.Close, if (locked) "Sending, please wait" else if (capture != null) "Discard" else "Close", enabled = !locked) {
                        if (capture != null) onDiscard() else onClose()
                    }
                    Box(Modifier.weight(1f))
                    topActions()
                    if (capture == null && (state.mode == ComposerMode.Photo || state.mode == ComposerMode.Video) && state.camera == CameraStart.Started) {
                        StageButton(CircasoIcon.Flash, if (state.flash) "Flash on" else "Flash off", filled = state.flash, onClick = composer::toggleFlash)
                    }
                    if (state.item?.video?.path != null && !locked) StageButton(CircasoIcon.Scissors, "Trim video") { composer.openTrim() }
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
                    if (reviewing) {
                        if (state.items.size > 1) PickedStrip(state, copy, composer, locked)
                        if (capture != null) CaptionField(state.caption, composer::onCaption, enabled = !locked)
                        // Under the caption, once (design M4): what happens to it.
                        if (capture != null && footnote != null) LockLine(footnote)
                        reviewExtras()
                        Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
                            leading()
                            Box(Modifier.weight(1f))
                            SendButton(send)
                        }
                        belowSend()
                    } else if (showCamera) {
                        if (!state.recordingVideo && !recordingOther) RecentsStrip(state, composer)
                        if (state.selecting && state.selection.isNotEmpty()) SelectionBar(state, composer)
                        else if (state.mode != ComposerMode.Voice) CameraControls(state, composer)
                    }
                    if (showCamera && capture == null && !state.recordingVideo && !recordingOther && !state.selecting && modes.size > 1) ModeRow(modes, state.mode, composer::setMode)
                }
                // The gallery and the trimmer rise over the stage and fade away (still under reduced motion).
                Layer(state.gallery) { GalleryScreen(it, state, composer, onCloseGallery) }
                Layer(state.trim) { TrimScreen(it, state, copy, composer) }
                ToastHost(toast, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp))
            }
        }
    }
}

/** The round Send (design M4): ink when it can go, a spinner while it is being made ready. */
@Composable
fun SendButton(send: MediaSendButton) {
    val c = CircasoTheme.colors
    Box(
        Modifier.size(52.dp).clip(CircasoShapes.circle).background(if (send.enabled) c.ink else c.fill)
            .pressable(enabled = send.enabled, onClick = send.onClick)
            .semantics { contentDescription = send.description; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        if (send.busy) com.circaso.designsystem.components.Spinner(c.bg, Modifier.size(22.dp))
        else CircasoIconImage(CircasoIcon.ArrowUp, if (send.enabled) c.bg else c.inkTertiary, Modifier.size(22.dp))
    }
}

/** The line under the caption: a lock, and what happens to what is sent. */
@Composable
fun LockLine(text: String) {
    val c = CircasoTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        CircasoIconImage(CircasoIcon.Lock, c.inkSecondary, Modifier.size(13.dp))
        BasicText(text, style = CircasoTheme.typography.footnote.copy(color = c.inkSecondary))
    }
}

/**
 * Who it goes to, beside Send (a chat): the chat's picture and name in a glass pill, the same height as a moment's
 * audience chip. Read, not tapped: the chat is already chosen.
 */
@Composable
fun RecipientChip(name: String, avatarUrl: String?, group: Boolean) {
    val c = CircasoTheme.colors
    Row(
        Modifier.heightIn(min = 48.dp).widthIn(max = 240.dp).clip(CircasoShapes.pill).background(c.glass).padding(start = 6.dp, end = 14.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Sending to $name" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        com.circaso.designsystem.components.Avatar(name.ifBlank { if (group) "Group" else "Chat" }, size = CircasoSizes.avatarSm, imageUrl = avatarUrl)
        BasicText(name, style = CircasoTheme.typography.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** While several are ticked on the strip: how many, Cancel and Next (WhatsApp-style), in place of the shutter. */
@Composable
private fun SelectionBar(state: MediaComposeState, composer: MediaComposer) {
    val c = CircasoTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4).heightIn(min = 78.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        CircasoButton("Cancel", composer::clearSelection, style = ButtonStyle.Secondary, size = ButtonSize.Small)
        BasicText(
            "${state.selection.size} selected", Modifier.weight(1f).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
            style = CircasoTheme.typography.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
        )
        CircasoButton("Next", composer::useSelection, size = ButtonSize.Small, busy = state.loadingPick)
    }
}

/** M4: several picked; tap one to put it on the stage (and edit its caption), the bin takes it out. */
@Composable
private fun PickedStrip(state: MediaComposeState, copy: MediaComposeCopy, composer: MediaComposer, locked: Boolean) {
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
                        .pressable(enabled = !locked, onClick = { composer.select(i) })
                        .semantics { contentDescription = "${copy.itemNoun} ${i + 1} of ${state.items.size}" + if (state.needsTrim(item)) ", needs trimming" else ""; selected = on; role = Role.Tab },
                    contentAlignment = Alignment.Center,
                ) {
                    val picture = media?.thumb ?: media?.bytes?.takeIf { media.mime.startsWith("image") }
                    if (picture != null) coil3.compose.AsyncImage(picture, null, Modifier.fillMaxSize().graphicsLayer { alpha = if (on) 1f else 0.7f }, contentScale = ContentScale.Crop)
                    if (item.capture is ComposerCapture.Document) CircasoIconImage(CircasoIcon.Document, c.ink, Modifier.size(20.dp))
                    if (state.needsTrim(item)) CircasoIconImage(CircasoIcon.Scissors, c.ink, Modifier.size(16.dp))
                }
            }
        }
        if (!locked) StageButton(CircasoIcon.Trash, "Remove this one", onClick = composer::removeCurrent)
    }
}

@Composable
private fun CameraControls(state: MediaComposeState, composer: MediaComposer) {
    val c = CircasoTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 34.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        StageButton(CircasoIcon.Photo, "Gallery", onClick = composer::pickFromLibrary)
        // Tap for a photo (Photo mode); hold for a video up to 60 s in either mode (M1b). Lifting stops it, even when
        // the recording was still starting. Screen readers get the same as plain actions: photo, start and stop video.
        val ready = state.camera == CameraStart.Started
        val progress = recordingProgress(state.recordingSince)
        val latest by androidx.compose.runtime.rememberUpdatedState(state)
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
                            when { latest.recordingVideo -> composer.holdEnd(); latest.mode == ComposerMode.Video -> composer.holdStart(); else -> composer.takePhoto() }; true
                        }
                        onLongClick("Record video") { composer.holdStart(); true }
                    }
                }
                .pointerInput(ready) {
                    if (!ready) return@pointerInput
                    detectTapGestures(
                        onTap = { if (latest.mode == ComposerMode.Photo) composer.takePhoto() },
                        onLongPress = { composer.holdStart() },
                        onPress = { if (latest.mode == ComposerMode.Video) composer.holdStart(); tryAwaitRelease(); composer.holdEnd() },
                    )
                },
        )
        StageButton(CircasoIcon.FlipCamera, "Flip camera", onClick = composer::flip)
    }
}

@Composable
private fun ModeRow(modes: List<ComposerMode>, mode: ComposerMode, onMode: (ComposerMode) -> Unit) {
    val c = CircasoTheme.colors
    Row(Modifier.fillMaxWidth().semantics { contentDescription = "Mode" }, horizontalArrangement = Arrangement.Center) {
        modes.forEach { m ->
            BasicText(
                m.name,
                Modifier.pressable(onClick = { onMode(m) }).semantics { role = Role.Tab; selected = m == mode }.padding(horizontal = 12.dp, vertical = 13.dp),
                style = CircasoTheme.typography.subhead.copy(color = if (m == mode) c.ink else c.inkSecondary, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/**
 * M4: the shot, before sending. A video shows its thumbnail until the first frame is on screen, so the review is never
 * a black void; if this phone can't play it, the thumbnail stays and a line says it will still go.
 */
@Composable
private fun BoxScope.ShotReview(shot: ComposerCapture.Shot, unplayable: Boolean, playing: Boolean, copy: MediaComposeCopy, composer: MediaComposer) {
    val c = CircasoTheme.colors
    val path = shot.media.path
    if (shot.video && path != null) {
        com.circaso.core.platform.MomentVideo(
            path, playing = playing, muted = false, modifier = Modifier.fillMaxSize(), loop = true,
            poster = shot.media.thumb, onUnplayable = composer::markUnplayable,
        )
        if (unplayable) {
            Row(
                Modifier.align(Alignment.Center).padding(horizontal = 32.dp).clip(CircasoShapes.md).background(c.glass).padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircasoIconImage(CircasoIcon.Video, c.ink, Modifier.size(18.dp))
                BasicText("This phone can’t preview this video. It will still ${copy.sendVerb}.", style = CircasoTheme.typography.subhead.copy(color = c.ink))
            }
        }
    } else {
        coil3.compose.AsyncImage(shot.media.bytes, "Your photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/**
 * A document on the stage, in the review's own language: a glass card in the middle with the page, the file's name
 * and its type and size, so a file reads as one of the family before it goes.
 */
@Composable
private fun BoxScope.DocumentReview(doc: ComposerCapture.Document) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.align(Alignment.Center).padding(horizontal = CircasoSpacing.s6).widthIn(max = 360.dp).fillMaxWidth()
            .clip(CircasoShapes.lg).background(c.glass).padding(horizontal = CircasoSpacing.s5, vertical = CircasoSpacing.s6)
            .semantics(mergeDescendants = true) { contentDescription = "Document ${doc.file.name}, ${fileKind(doc.file.name, doc.file.mime)}, ${sizeLabel(doc.file.size)}" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Box(Modifier.size(72.dp).clip(CircasoShapes.md).background(c.fill), contentAlignment = Alignment.Center) {
            CircasoIconImage(CircasoIcon.Document, c.ink, Modifier.size(36.dp))
        }
        BasicText(doc.file.name, style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center), maxLines = 3, overflow = TextOverflow.Ellipsis)
        BasicText("${fileKind(doc.file.name, doc.file.mime)} · ${sizeLabel(doc.file.size)}", style = t.subhead.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"))
    }
}

/** "PDF", "DOCX", "Document": the extension people know, else the type's own name. */
fun fileKind(name: String, mime: String): String {
    val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 && it != name }
    return ext?.uppercase() ?: when {
        mime == "application/pdf" -> "PDF"
        mime.startsWith("image/") -> "Image"
        mime.startsWith("audio/") -> "Audio"
        mime.startsWith("video/") -> "Video"
        mime.startsWith("text/") -> "Text"
        else -> "Document"
    }
}

@Composable
private fun CaptionField(value: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Box(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4).clip(CircasoShapes.lg).background(c.glass).padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (value.isEmpty()) BasicText("Add a caption", style = t.body.copy(color = c.inkSecondary))
        BasicTextField(value, onChange, enabled = enabled, textStyle = t.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Caption" })
    }
}

/**
 * The recents strip (WhatsApp-style): the newest photos, videos or audio on the phone. Tap one to use it; hold one to
 * pick several (numbered in the order they'll go). Without access to this kind it says so and offers to allow it;
 * with limited access (only chosen items) a Manage tile leads.
 */
@Composable
private fun RecentsStrip(state: MediaComposeState, composer: MediaComposer) {
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
            StripTile("Files", CircasoIcon.Waveform, "Choose an audio file", wide = true, busy = state.loadingPick, onClick = composer::pickFromLibrary)
        }
        if (access == LibraryAccess.Denied) item(key = "allow") {
            StripTile(
                "Allow access", CircasoIcon.Lock,
                when (state.mode) { ComposerMode.Video -> "Allow access to your videos"; ComposerMode.Voice -> "Allow access to your audio"; else -> "Allow access to your photos" },
                wide = true, onClick = composer::manageAccess,
            )
        }
        if (access == LibraryAccess.Limited) item(key = "manage") {
            StripTile("Manage", CircasoIcon.Plus, "Circaso can see only what you chose. Choose more", wide = false, onClick = composer::manageAccess)
        }
        items(state.recents.size, key = { state.recents[it].id }) { i ->
            val item = state.recents[i]
            if (audio) {
                Column(
                    Modifier.size(120.dp, 56.dp).clip(CircasoShapes.md).background(c.glass).pressable(onClick = { composer.useRecent(item) }).padding(horizontal = 10.dp, vertical = 6.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "Audio ${item.title.orEmpty()}, ${clock(item.durationMs ?: 0)}"; role = Role.Button },
                    verticalArrangement = Arrangement.Center,
                ) {
                    BasicText(item.title ?: "Audio", style = t.footnote.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(clock(item.durationMs ?: 0), style = t.caption2.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"))
                }
            } else {
                val order = state.selection.indexOfFirst { it.id == item.id }
                LibraryTile(
                    item, Modifier.size(56.dp), number = if (order >= 0) order + 1 else null, showTick = state.selecting,
                    onClick = { composer.useRecent(item) }, onLongClick = { composer.startSelecting(item) },
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
    item: LibraryItem, modifier: Modifier, number: Int?, showTick: Boolean, onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null, shape: androidx.compose.ui.graphics.Shape = CircasoShapes.sm,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val video = item.kind == LibraryKind.Video
    Box(
        modifier.clip(shape).background(c.fill).pressable(onLongClick = onLongClick, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = (if (video) "Video, ${longClock(item.durationMs ?: 0)}" else "Photo") + if (number != null) ", selected, $number" else ""
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

/**
 * The round tick (as the design's selection circles): filled with a check, or the pick's [number], when chosen.
 * [onMedia]: drawn over a photo, so the ring is the light stage ink on a soft scrim in every theme (design M3).
 */
@Composable
internal fun Tick(on: Boolean, number: Int? = null, onMedia: Boolean = false) {
    val c = CircasoTheme.colors
    val ring = if (onMedia) com.circaso.designsystem.theme.DarkColors.ink else c.inkTertiary
    Box(
        Modifier.size(24.dp).clip(CircasoShapes.circle)
            .then(
                when {
                    on && onMedia -> Modifier.border(2.dp, ring, CircasoShapes.circle).padding(2.dp).clip(CircasoShapes.circle).background(c.action)
                    on -> Modifier.background(c.action)
                    onMedia -> Modifier.background(com.circaso.designsystem.theme.DarkColors.scrim).border(2.dp, ring, CircasoShapes.circle)
                    else -> Modifier.border(2.dp, ring, CircasoShapes.circle)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (on) {
            if (number != null) BasicText("$number", style = CircasoTheme.typography.footnote.copy(color = c.onAction, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
            else CircasoIconImage(CircasoIcon.Check, c.onAction, Modifier.size(16.dp))
        }
    }
}

internal fun clock(ms: Long): String { val s = (ms / 1000).coerceIn(0, 60); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

/** A library video's length, past a minute too ("2:14", "1:02:05"). */
internal fun longClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

/** "0:14 / 1:00" for the recording in progress; it ticks every second. */
@Composable
internal fun elapsedLabel(since: Long?): String {
    val now by androidx.compose.runtime.produceState(com.circaso.core.sync.nowMillis(), since) { while (since != null) { value = com.circaso.core.sync.nowMillis(); kotlinx.coroutines.delay(250) } }
    return "${clock(if (since == null) 0 else now - since)} / 1:00"
}

/** How far a recording is towards 1:00, for the shutter ring (M1b). */
@Composable
private fun recordingProgress(since: Long?): Float {
    val now by androidx.compose.runtime.produceState(com.circaso.core.sync.nowMillis(), since) { while (since != null) { value = com.circaso.core.sync.nowMillis(); kotlinx.coroutines.delay(100) } }
    return if (since == null) 0f else ((now - since) / com.circaso.core.platform.MomentVideoMaxMs.toFloat()).coerceIn(0f, 1f)
}

/** M1c: camera not allowed or not there; the gallery still works. */
@Composable
private fun BoxScope.CameraBlocked(start: CameraStart) {
    if (start == CameraStart.PermissionDenied) Blocked("Allow the camera to take photos and videos", "Circaso uses the camera only while this screen is open.")
    else Blocked("No camera here", "Pick a photo or video from your gallery.", settings = false)
}

@Composable
fun BoxScope.Blocked(title: String, sub: String, settings: Boolean = true, icon: CircasoIcon = CircasoIcon.Camera) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircasoIconImage(icon, c.inkSecondary, Modifier.size(40.dp))
        BasicText(title, style = t.title3.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(sub, style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        if (settings) CircasoButton("Open Settings", { openAppSettings() }, style = ButtonStyle.Secondary, size = ButtonSize.Small)
    }
}

/** A round glass button on the stage (close, flash, scissors, the bin). */
@Composable
fun StageButton(icon: CircasoIcon?, description: String, label: String? = null, bold: Boolean = false, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    Box(
        Modifier.size(CircasoSizes.controlMd).clip(CircasoShapes.circle).background(if (filled) c.ink else c.glass)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .pressable(enabled = enabled, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) BasicText(label, style = CircasoTheme.typography.headline.copy(color = c.ink, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
        else if (icon != null) CircasoIconImage(icon, if (filled) c.bg else c.ink, Modifier.size(20.dp))
    }
}

/**
 * M3: the gallery. Recents in a three-column grid with All, Photos and Videos; tap to tick (numbers show the order),
 * Next puts them on the review. Limited access (M3c) says so with Manage; with access off, one line, Open Settings,
 * and the phone's own picker (which needs no permission).
 */
@Composable
private fun GalleryScreen(gallery: GalleryState, state: MediaComposeState, composer: MediaComposer, onClose: () -> Unit) {
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
                    Modifier.size(CircasoSizes.controlMd).clip(CircasoShapes.circle).pressable(onClick = onClose).semantics { contentDescription = "Close"; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) { CircasoIconImage(CircasoIcon.Close, cc.ink, Modifier.size(20.dp)) }
                BasicText("Recents", Modifier.weight(1f).semantics { heading() }, style = t.headline.copy(color = cc.ink, textAlign = TextAlign.Center))
                CircasoButton(if (state.selection.size > 1) "Next (${state.selection.size})" else "Next", composer::useSelection, size = ButtonSize.Small, enabled = state.selection.isNotEmpty(), busy = state.loadingPick)
            }
            com.circaso.designsystem.components.SegmentedControl(
                listOf("All", "Photos", "Videos"), GalleryTab.entries.indexOf(gallery.tab), { composer.galleryTab(GalleryTab.entries[it]) },
                Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
            )
            if (gallery.access == LibraryAccess.Limited) {
                Row(
                    Modifier.fillMaxWidth().padding(CircasoSpacing.s4).clip(CircasoShapes.md).background(cc.fill).padding(horizontal = 14.dp, vertical = 10.dp)
                        .semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircasoIconImage(CircasoIcon.Photo, cc.inkSecondary, Modifier.size(18.dp))
                    val n = gallery.photos.size + gallery.videos.size
                    BasicText("Circaso can see $n ${if (n == 1) "item" else "items"} you chose.", Modifier.weight(1f), style = t.subhead.copy(color = cc.ink))
                    BasicText("Manage", Modifier.heightIn(min = CircasoSizes.controlMd).pressable(onClick = composer::manageAccess).padding(horizontal = 4.dp, vertical = 12.dp), style = t.subhead.copy(color = cc.action, fontWeight = FontWeight.SemiBold))
                }
            } else {
                Box(Modifier.height(CircasoSpacing.s3))
            }
            val items = androidx.compose.runtime.remember(gallery.photos, gallery.videos, gallery.tab) { gallery.items }
            when {
                gallery.loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { com.circaso.designsystem.components.Spinner(cc.inkSecondary) }
                gallery.access == LibraryAccess.Denied -> Column(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    CircasoIconImage(CircasoIcon.Photo, cc.inkTertiary, Modifier.size(40.dp))
                    BasicText("Allow photo access to share from your gallery.", style = t.body.copy(color = cc.ink, textAlign = TextAlign.Center))
                    CircasoButton("Open Settings", composer::manageAccess, style = ButtonStyle.Secondary, size = ButtonSize.Small)
                    BasicText("Choose from your phone instead", Modifier.heightIn(min = CircasoSizes.controlMd).pressable(onClick = { composer.closeGallery(); composer.pickWithSystem() }).padding(horizontal = 8.dp, vertical = 12.dp),
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
                        LibraryTile(item, Modifier.fillMaxWidth().aspectRatio(1f), number = if (order >= 0) order + 1 else null, showTick = true, onClick = { composer.toggle(item) }, shape = androidx.compose.ui.graphics.RectangleShape)
                    }
                }
            }
        }
    }
}

/**
 * M3b: trim a video. The video itself on the stage, held at the edge being dragged, Cancel and Done at the top,
 * "0:58 selected of 2:14" and the filmstrip with two handles below. The window is at most the limit and at least a
 * second; the trimmed copy stays on this phone until it is sent.
 */
@Composable
private fun TrimScreen(trim: TrimState, state: MediaComposeState, copy: MediaComposeCopy, composer: MediaComposer) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Box(Modifier.fillMaxSize().background(c.bg).pointerInput(Unit) { detectTapGestures { } }) {
        val media = (state.items.getOrNull(trim.index)?.capture as? ComposerCapture.Shot)?.media
        // The stage is the video itself, held at the edge being dragged, at full resolution: never a filmstrip frame
        // blown up (they are 160 px), so what the person sees is exactly the picture they will send.
        val path = media?.path
        if (path != null) {
            com.circaso.core.platform.MomentVideo(
                path, playing = false, muted = true, poster = media.thumb, positionMs = trim.previewMs,
                modifier = Modifier.fillMaxSize().semantics { contentDescription = "Video at ${longClock(trim.previewMs)}" },
            )
        } else {
            (trim.frames.getOrNull(trim.frames.size / 2) ?: media?.thumb)?.let { coil3.compose.AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        // Shades behind Cancel and Done, and under the clock, the strip and the hint, so they read on any picture
        // (design shade-top/bot, the viewer's heights).
        Box(Modifier.fillMaxWidth().height(200.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.scrim, androidx.compose.ui.graphics.Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, c.scrim))),
        )
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StageButton(CircasoIcon.Close, if (trim.required) "Cancel, and leave this video out" else "Cancel trimming", onClick = composer::cancelTrim)
            Box(Modifier.weight(1f))
            CircasoButton("Done", composer::applyTrim, size = ButtonSize.Small, busy = trim.working)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = CircasoSpacing.s4, vertical = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                if (trim.working) "Trimming…" else "${longClock(trim.selectedMs)} selected of ${longClock(trim.durationMs)}",
                Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            )
            TrimBar(trim, composer)
            if (trim.required || copy.trimHintAlways) BasicText(copy.trimHint, style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        }
    }
}

/**
 * The filmstrip with its two handles (design M3b): drag an edge to move it, the middle to slide the whole window. The
 * strip sits [HandleReach] in from its touch area on each side, so the handles are clear of the screen edges, where the
 * phone's back swipe takes the touch first (Pasaka, 2026-11-07: on a clip under a minute both handles sat on the edges
 * and wouldn't move); on Android the area is also excluded from that swipe. Each drag is measured from where it began,
 * so no movement is lost between frames.
 */
@Composable
private fun TrimBar(trim: TrimState, composer: MediaComposer) {
    val c = CircasoTheme.colors
    val handle = 12.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val reachPx = with(density) { HandleReach.toPx() }
    var widthPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(1) }
    val latest by androidx.compose.runtime.rememberUpdatedState(trim)
    Box(
        Modifier.fillMaxWidth().height(56.dp)
            .excludeSystemGestures()
            .onSizeChanged { widthPx = (it.width - 2 * reachPx).toInt().coerceAtLeast(1) }
            .semantics {
                contentDescription = "Trim"
                stateDescription = "${longClock(trim.startMs)} to ${longClock(trim.endMs)}"
                // Screen readers: move the window a few seconds at a time.
                customActions = listOf(
                    androidx.compose.ui.semantics.CustomAccessibilityAction("Later by 5 seconds") { val x = latest; composer.slideTrim(x.startMs, x.endMs, 5_000); true },
                    androidx.compose.ui.semantics.CustomAccessibilityAction("Earlier by 5 seconds") { val x = latest; composer.slideTrim(x.startMs, x.endMs, -5_000); true },
                    androidx.compose.ui.semantics.CustomAccessibilityAction("Shorter by 5 seconds") { val x = latest; composer.setTrim(x.startMs, x.endMs - 5_000); true },
                    androidx.compose.ui.semantics.CustomAccessibilityAction("Longer by 5 seconds") { val x = latest; composer.setTrim(x.startMs, x.endMs + 5_000); true },
                )
            }
            .pointerInput(trim.durationMs) {
                val edge = 28.dp.toPx()
                var grab = 0 // -1 start, 1 end, 0 the window
                var fromStart = 0L
                var fromEnd = 0L
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { at ->
                        val x = latest
                        fromStart = x.startMs; fromEnd = x.endMs; travelled = 0f
                        val startX = reachPx + x.startMs.toFloat() / x.durationMs * widthPx
                        val endX = reachPx + x.endMs.toFloat() / x.durationMs * widthPx
                        val toStart = kotlin.math.abs(at.x - startX)
                        val toEnd = kotlin.math.abs(at.x - endX)
                        // The nearer edge wins, so a short window's two handles can both be caught.
                        grab = when { toStart < edge && toStart <= toEnd -> -1; toEnd < edge -> 1; else -> 0 }
                    },
                ) { change, dx ->
                    change.consume()
                    travelled += dx
                    val dMs = (travelled / widthPx * latest.durationMs).toLong()
                    when (grab) {
                        -1 -> composer.setTrim(fromStart + dMs, fromEnd)
                        1 -> composer.setTrim(fromStart, fromEnd + dMs)
                        else -> composer.slideTrim(fromStart, fromEnd, dMs)
                    }
                }
            },
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = HandleReach).clip(CircasoShapes.sm).background(c.fill)) {
            Row(Modifier.fillMaxSize()) {
                if (trim.frames.isEmpty()) Box(Modifier.fillMaxSize())
                trim.frames.forEach { f ->
                    coil3.compose.AsyncImage(f, null, Modifier.weight(1f).fillMaxHeight().graphicsLayer { alpha = 0.45f }, contentScale = ContentScale.Crop)
                }
            }
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
}

/** How far the trim strip sits inside its touch area on each side (clear of the edge swipes, and easy to grab). */
private val HandleReach = CircasoSpacing.s5

/** A full-screen layer over the stage: in with a short rise and fade, out with a fade; the last value draws the exit. */
@Composable
private fun <T : Any> Layer(value: T?, content: @Composable (T) -> Unit) {
    val reduce = CircasoTheme.reduceMotion
    var last by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(value) }
    if (value != null) last = value
    androidx.compose.animation.AnimatedVisibility(
        value != null, Modifier.fillMaxSize(),
        enter = if (reduce) androidx.compose.animation.fadeIn(androidx.compose.animation.core.snap())
        else androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(CircasoMotion.BaseMs)) { it / 12 } +
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(CircasoMotion.BaseMs)),
        exit = if (reduce) androidx.compose.animation.fadeOut(androidx.compose.animation.core.snap())
        else androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(CircasoMotion.QuickMs)),
    ) { last?.let { content(it) } }
}

/**
 * Upload progress while something goes (M6): a track and the real share of bytes sent filling clockwise, eased between
 * steps and never running backwards; before the first byte, an arc turning. The same ring on my moment's avatar
 * (drawn [outset] outside it) and on a chat's photo, video or file. Both animations are read only while drawing.
 */
@Composable
fun UploadRing(
    progress: Float?,
    modifier: Modifier = Modifier,
    ink: androidx.compose.ui.graphics.Color = CircasoTheme.colors.ink,
    track: androidx.compose.ui.graphics.Color = ink.copy(alpha = 0.3f),
    stroke: Dp = 3.dp,
    outset: Dp = 0.dp,
    spinSweep: Float = 90f,
) {
    val reduce = CircasoTheme.reduceMotion
    val angle = if (reduce || progress != null) null else androidx.compose.animation.core.rememberInfiniteTransition().animateFloat(
        0f, 360f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(CircasoMotion.MomentMs * 2, easing = androidx.compose.animation.core.LinearEasing),
            androidx.compose.animation.core.RepeatMode.Restart,
        ),
    )
    val fill = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(progress ?: 0f) }
    LaunchedEffect(progress) {
        val target = progress ?: 0f
        // Forward it fills smoothly; back to the start (a send again) it snaps, never running backwards.
        if (reduce || target < fill.value) fill.snapTo(target) else fill.animateTo(target, androidx.compose.animation.core.tween(CircasoMotion.BaseMs))
    }
    androidx.compose.foundation.Canvas(
        modifier.semantics { if (progress != null) progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(progress, 0f..1f) },
    ) {
        val w = stroke.toPx()
        val out = outset.toPx() + (if (outset > 0.dp) w / 2 else -w / 2)
        val arc = androidx.compose.ui.geometry.Size(size.width + 2 * out, size.height + 2 * out)
        val topLeft = androidx.compose.ui.geometry.Offset(-out, -out)
        drawArc(track, 0f, 360f, false, topLeft, arc, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        if (progress != null) drawArc(ink, -90f, 360f * fill.value, false, topLeft, arc, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        else drawArc(ink, (angle?.value ?: 0f) - 90f, spinSweep, false, topLeft, arc, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}
