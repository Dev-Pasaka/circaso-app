package com.circaso.feature.mediacompose

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.animateContentSize
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
import com.circaso.designsystem.chrome.glass
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
            // The stage is dark in every theme: the status bar's icons go light while it is up.
            com.circaso.core.platform.StatusBarIcons(light = true)
            LaunchedEffect(state.error) { state.error?.let { toast.show(it, com.circaso.designsystem.chrome.ToastKind.Problem); composer.errorShown() } }
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

                if (state.recordingVideo) RecordingPill(state, composer)

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (reviewing) {
                        // Just above the strip, beside the marked tile (clear of the toasts at the top).
                        val over = state.item?.overLimit?.takeIf { capture != null && state.trim == null }
                        var lastOver by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0L) }
                        if (over != null) lastOver = over
                        androidx.compose.animation.AnimatedVisibility(
                            over != null,
                            enter = if (CircasoTheme.reduceMotion) androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(CircasoMotion.BaseMs))
                                else androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(CircasoMotion.BaseMs)) + androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(CircasoMotion.BaseMs)),
                            exit = if (CircasoTheme.reduceMotion) androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(CircasoMotion.QuickMs))
                                else androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(CircasoMotion.QuickMs)) + androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(CircasoMotion.QuickMs)),
                        ) { OverLimitNote(lastOver, state.item?.video != null, alone = state.items.size == 1, composer) }
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

/** The critical badge on an item over the size limit: on its strip tile (the reason shows on the stage). */
@Composable
private fun OverLimitBadge(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = OverLimitBadgeSize) {
    val c = CircasoTheme.colors
    Box(modifier.size(size).clip(CircasoShapes.circle).background(c.critical), contentAlignment = Alignment.Center) {
        CircasoIconImage(CircasoIcon.Warning, c.onAction, Modifier.size(size * 0.65f))
    }
}

private val OverLimitBadgeSize = 16.dp
private val OverLimitBadgeInset = 2.dp
private val OverLimitNoteMaxWidth = 480.dp

/**
 * Why the item on the stage won't go: over the size limit, with its size and what to do. Stays while it is on the
 * stage (a toast would fade before it is read), so tapping a marked tile on the strip shows the reason.
 */
@Composable
private fun OverLimitNote(size: Long, video: Boolean, alone: Boolean, composer: MediaComposer) {
    val c = CircasoTheme.colors
    Row(
        Modifier.padding(horizontal = CircasoSpacing.s4).widthIn(max = OverLimitNoteMaxWidth).fillMaxWidth().clip(CircasoShapes.md).background(c.glass)
            .padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s3)
            .semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        OverLimitBadge(size = CircasoSpacing.s6)
        BasicText(
            "${overLimitLabel(size, composer.maxBytes).replaceFirstChar { it.uppercase() }}, over the ${composer.limitLabel} limit. It won’t be sent: " +
                when { video -> "trim it shorter or remove it."; alone -> "remove it."; else -> "remove it to send the rest." },
            style = CircasoTheme.typography.subhead.copy(color = c.ink),
        )
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
                        .semantics {
                            contentDescription = "${copy.itemNoun} ${i + 1} of ${state.items.size}" + when {
                                item.overLimit != null -> ", over ${composer.limitLabel}, left out"
                                state.needsTrim(item) -> ", needs trimming"
                                else -> ""
                            }
                            selected = on; role = Role.Tab
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val picture = media?.thumb ?: media?.bytes?.takeIf { media.mime.startsWith("image") }
                    // Over the limit: dimmed, so the marked ones read as out of the send at a glance.
                    val dim = when { item.overLimit != null -> 0.4f; on -> 1f; else -> 0.7f }
                    if (picture != null) coil3.compose.AsyncImage(picture, null, Modifier.fillMaxSize().graphicsLayer { alpha = dim }, contentScale = ContentScale.Crop)
                    if (item.capture is ComposerCapture.Document) CircasoIconImage(CircasoIcon.Document, c.ink, Modifier.size(20.dp))
                    if (state.needsTrim(item)) CircasoIconImage(CircasoIcon.Scissors, c.ink, Modifier.size(16.dp))
                    if (item.overLimit != null) OverLimitBadge(Modifier.align(Alignment.TopEnd).padding(OverLimitBadgeInset))
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
        val progress = recordingProgress(state)
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
        BasicTextField(value, com.circaso.designsystem.components.ashaAware(value, onChange), enabled = enabled, textStyle = t.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Caption" })
    }
}

/**
 * The recents strip (WhatsApp-style): the newest photos, videos or audio on the phone. Tap one to use it; hold one to
 * pick several (numbered in the order they'll go). Without access to this kind it says so and offers to allow it;
 * with only some access an Allow all (Android) or Choose more (iPhone) tile leads.
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
                if (audio) "Allow access to your audio" else "Allow access to your photos and videos",
                wide = true, onClick = composer::manageAccess,
            )
        }
        if (access == LibraryAccess.Limited) item(key = "manage") {
            // The strip misses some of the phone: say what, and lead to the system's way to allow it (2026-11-14).
            // Android 14's prompt offers Allow all; iPhone's limited-library picker only chooses more.
            // One kind switched off is allowed; only chosen items is "all" where the phone's prompt offers it
            // (Android 14+), else choosing more (iPhone's limited library).
            val action = when {
                state.recentsGap == RecentsGap.NoVideos || state.recentsGap == RecentsGap.NoPhotos -> "Allow"
                state.limitedOffersAllowAll -> "Allow all"
                else -> "Choose more"
            }
            val why = when (state.recentsGap) {
                RecentsGap.NoVideos -> "Circaso can't see your videos."
                RecentsGap.NoPhotos -> "Circaso can't see your photos."
                else -> "Circaso can see only the photos and videos you chose."
            }
            StripTile(action, CircasoIcon.Lock, "$why $action", wide = false, onClick = composer::manageAccess)
        }
        items(state.recents.size, key = { state.recents[it].id }) { i ->
            val item = state.recents[i]
            if (audio) {
                Column(
                    // At least the strip's height, growing with large text rather than clipping it.
                    Modifier.width(StripWideTile).heightIn(min = StripTileSize).clip(CircasoShapes.md).background(c.glass).pressable(onClick = { composer.useRecent(item) })
                        .padding(horizontal = CircasoSpacing.s3, vertical = 6.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "Audio ${item.title.orEmpty()}, ${clock(item.durationMs ?: 0)}"; role = Role.Button },
                    verticalArrangement = Arrangement.Center,
                ) {
                    BasicText(item.title ?: "Audio", style = t.footnote.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(clock(item.durationMs ?: 0), style = t.caption2.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"))
                }
            } else {
                val order = state.selection.indexOfFirst { it.id == item.id }
                LibraryTile(
                    item, Modifier.size(StripTileSize), number = if (order >= 0) order + 1 else null, showTick = state.selecting,
                    onClick = { composer.useRecent(item) }, onLongClick = { composer.startSelecting(item) },
                )
            }
        }
    }
}

/** A glass tile at the start of the strip: Files, Allow access, Allow all / Choose more. */
@Composable
private fun StripTile(label: String, icon: CircasoIcon, description: String, wide: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    // At least the strip's size; with large text it grows taller (and a narrow one wider) instead of clipping its label.
    Column(
        Modifier.widthIn(min = if (wide) StripWideTile else StripTileSize).heightIn(min = StripTileSize).clip(CircasoShapes.md).background(c.glass).pressable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button }
            .padding(horizontal = CircasoSpacing.s2, vertical = CircasoSpacing.s1),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        if (busy) com.circaso.designsystem.components.Spinner(c.ink)
        else {
            CircasoIconImage(icon, c.ink, Modifier.size(18.dp))
            BasicText(label, style = CircasoTheme.typography.caption2.copy(color = c.ink, textAlign = TextAlign.Center), maxLines = 2)
        }
    }
}

/** The recents strip's tiles: a photo's square, and the wider glass tiles (Files, Allow access, an audio file). */
private val StripTileSize = 56.dp
private val StripWideTile = 120.dp

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

/**
 * The pill over the camera while a video records. A moment: "0:14 / 1:00". A chat (any length, up to its size limit):
 * the running time and size, "3:12 · 41 MB"; from [MediaComposer.RecordingWarnShare] of the limit it turns to the
 * warning colour and says it is nearly full, once, politely, so the stop at the limit is never a surprise.
 */
@Composable
private fun BoxScope.RecordingPill(state: MediaComposeState, composer: MediaComposer) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val limits = state.recordingLimits
    val maxBytes = limits.maxBytes
    val near = maxBytes != null && state.recordingBytes >= maxBytes * MediaComposer.RecordingWarnShare
    val time = if (limits.maxMs != null) elapsedLabel(state.recordingSince) else runningClock(state.recordingSince)
    val text = when {
        maxBytes == null -> time
        // One line, as the frame (M1b): the warning colour says "nearly"; the size says how near.
        near -> "$time · ${(state.recordingBytes + 512 * 1024) / (1024 * 1024)} of ${composer.limitLabel}"
        state.recordingBytes > 0 -> "$time · ${sizeLabel(state.recordingBytes)}"
        else -> time
    }
    val ink = if (near) c.onAction else c.bg
    Row(
        Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = CircasoSpacing.s2).padding(horizontal = CircasoSizes.controlMd + CircasoSpacing.s6)
            .then(if (CircasoTheme.reduceMotion) Modifier else Modifier.animateContentSize(androidx.compose.animation.core.tween(CircasoMotion.QuickMs)))
            .clip(CircasoShapes.pill).background(if (near) c.warning else c.ink).padding(horizontal = CircasoSpacing.s3, vertical = 6.dp)
            // A fixed description, so a screen reader hears "Recording" and then "nearly 100 MB" once, not every tick.
            .clearAndSetSemantics { contentDescription = if (near) "Recording, nearly ${composer.limitLabel}" else "Recording"; liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        Box(Modifier.size(CircasoSpacing.s2).clip(CircasoShapes.circle).background(if (near) ink else c.critical))
        BasicText(text, style = t.subhead.copy(color = ink, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
    }
}

/** "3:12", "1:02:05": a recording's running time with no end. */
@Composable
private fun runningClock(since: Long?): String {
    val now by androidx.compose.runtime.produceState(com.circaso.core.sync.nowMillis(), since) { while (since != null) { value = com.circaso.core.sync.nowMillis(); kotlinx.coroutines.delay(250) } }
    return longClock(if (since == null) 0 else now - since)
}

/** "0:14 / 1:00" for the recording in progress; it ticks every second. */
@Composable
internal fun elapsedLabel(since: Long?): String {
    val now by androidx.compose.runtime.produceState(com.circaso.core.sync.nowMillis(), since) { while (since != null) { value = com.circaso.core.sync.nowMillis(); kotlinx.coroutines.delay(250) } }
    return "${clock(if (since == null) 0 else now - since)} / 1:00"
}

/** How far a recording is towards its limit, for the shutter ring (M1b): a moment's 1:00, or a chat's size. */
@Composable
private fun recordingProgress(state: MediaComposeState): Float {
    val since = state.recordingSince
    val maxMs = state.recordingLimits.maxMs
    val maxBytes = state.recordingLimits.maxBytes
    if (maxMs == null) return if (since == null || maxBytes == null) 0f else (state.recordingBytes.toFloat() / maxBytes).coerceIn(0f, 1f)
    val now by androidx.compose.runtime.produceState(com.circaso.core.sync.nowMillis(), since) { while (since != null) { value = com.circaso.core.sync.nowMillis(); kotlinx.coroutines.delay(100) } }
    return if (since == null) 0f else ((now - since) / maxMs.toFloat()).coerceIn(0f, 1f)
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
 * M3: the gallery. Everything Circaso may see, newest first, in a three-column grid with All, Photos and Videos (more
 * is read as the grid nears its end); tap to tick (numbers show the order), Next puts them on the review. It is the
 * phone's own library, read where it lives (WhatsApp, 2026-11-14): nothing is imported and there is no other picker.
 * Without access the screen asks for it, once, plainly; with only some (chosen photos, or one kind) a line above the
 * grid says what's missing and leads to the system's way to allow the rest.
 */
@Composable
private fun GalleryScreen(gallery: GalleryState, state: MediaComposeState, composer: MediaComposer, onClose: () -> Unit) {
    val t = CircasoTheme.typography
    // Back from the phone's Settings with access allowed: the grid fills without another tap.
    androidx.lifecycle.compose.LifecycleResumeEffect(composer) { composer.refreshAccess(); onPauseOrDispose { } }
    // The gallery is a list screen: the person's own theme, not the dark stage.
    CircasoTheme(darkTheme = LocalPersonalDark.current, highContrast = CircasoTheme.colors.isHighContrast) {
        val cc = CircasoTheme.colors
        // Over the dark stage (which lights the icons) in the person's theme: icons to suit it.
        com.circaso.core.platform.StatusBarIcons(light = cc.isDark)
        Box(Modifier.fillMaxSize()) {
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
            val denied = gallery.access == LibraryAccess.Denied
            val limited = gallery.access == LibraryAccess.Limited
            // What fills the gap: one kind switched off is allowed; only chosen items is "all" where the phone's
            // prompt offers it (Android 14+), else choosing more (iPhone's limited library).
            val action = when {
                gallery.gap == RecentsGap.NoVideos || gallery.gap == RecentsGap.NoPhotos -> "Allow"
                state.limitedOffersAllowAll -> "Allow all"
                else -> "Choose more"
            }
            // This tab is empty because of the gap (not because the phone has none of that kind).
            val gapHere = limited && when (gallery.gap) {
                RecentsGap.NoVideos -> gallery.tab != GalleryTab.Photos
                RecentsGap.NoPhotos -> gallery.tab != GalleryTab.Videos
                else -> true
            }
            // Access off: the ask fills the screen, no tabs over nothing.
            if (!denied) {
                com.circaso.designsystem.components.SegmentedControl(
                    listOf("All", "Photos", "Videos"), GalleryTab.entries.indexOf(gallery.tab), { composer.galleryTab(GalleryTab.entries[it]) },
                    Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1),
                )
                if (limited && gallery.items.isNotEmpty()) GalleryGapRow(gallery.gap, action, composer::manageAccess)
            }
            Box(Modifier.height(CircasoSpacing.s1))
            val items = gallery.items
            when {
                // The grid's own shape while the library answers, so the photos land where the eye already is.
                gallery.loading -> GalleryPlaceholder(Modifier.fillMaxWidth().weight(1f))
                denied -> GalleryAsk(
                    Modifier.fillMaxWidth().weight(1f), "Your photos and videos show here",
                    if (state.deniedOpensSettings) "Turn on Photos for Circaso in Settings to post the photos and videos on this phone."
                    else "Allow Circaso to see the photos and videos on this phone to post them.",
                    if (state.deniedOpensSettings) "Open Settings" else "Allow access", composer::manageAccess,
                )
                // Only some access and nothing to show here: they are on the phone, Circaso just can't see them.
                items.isEmpty() && gapHere -> {
                    val what = when {
                        gallery.gap == RecentsGap.NoVideos || gallery.tab == GalleryTab.Videos -> "videos"
                        gallery.gap == RecentsGap.NoPhotos || gallery.tab == GalleryTab.Photos -> "photos"
                        else -> "photos and videos"
                    }
                    GalleryAsk(
                        Modifier.fillMaxWidth().weight(1f), "Circaso can't see your $what yet",
                        if (gallery.gap == RecentsGap.OnlyChosen) "None of your $what are chosen for Circaso." else "Circaso isn't allowed to see your $what.",
                        action, composer::manageAccess,
                    )
                }
                items.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) {
                    BasicText(when (gallery.tab) { GalleryTab.Videos -> "No videos here yet."; GalleryTab.Photos -> "No photos here yet."; else -> "Nothing here yet." },
                        style = t.body.copy(color = cc.inkSecondary, textAlign = TextAlign.Center))
                }
                else -> {
                    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                    // Near the end: the next page of the library, read in the background (the grid keeps its place).
                    // Keyed on the count too, so a page that lands without a scroll still asks for the next.
                    androidx.compose.runtime.LaunchedEffect(grid, items.size, gallery.complete) {
                        androidx.compose.runtime.snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                            .collect { last -> if (last >= items.size - GalleryPrefetch) composer.loadMoreGallery() }
                    }
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        galleryCells(), Modifier.fillMaxWidth().weight(1f), state = grid,
                        horizontalArrangement = Arrangement.spacedBy(GalleryGap), verticalArrangement = Arrangement.spacedBy(GalleryGap),
                    ) {
                        items(items.size, key = { items[it].id }) { i ->
                            val item = items[i]
                            val order = state.selection.indexOfFirst { it.id == item.id }
                            LibraryTile(item, Modifier.fillMaxWidth().aspectRatio(1f), number = if (order >= 0) order + 1 else null, showTick = true, onClick = { composer.toggle(item) }, shape = androidx.compose.ui.graphics.RectangleShape)
                        }
                        // More to come: a row of blank tiles, so the grid never looks as if the library ends here.
                        if (!gallery.complete) items(GalleryMoreTiles, key = { "more-$it" }) {
                            com.circaso.designsystem.components.SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(1f), androidx.compose.ui.graphics.RectangleShape)
                        }
                    }
                }
            }
        }
        }
    }
}

/** The gallery without access (or with nothing it may show): what would be here, and the one way to allow it. */
@Composable
private fun GalleryAsk(modifier: Modifier, title: String, body: String, action: String, onAllow: () -> Unit) {
    val cc = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        // Scrolls rather than clips on a short screen with large text.
        modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = CircasoSpacing.s8, vertical = CircasoSpacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3, Alignment.CenterVertically),
    ) {
        CircasoIconImage(CircasoIcon.Photo, cc.inkTertiary, Modifier.size(40.dp))
        BasicText(title, Modifier.semantics { heading() }, style = t.headline.copy(color = cc.ink, textAlign = TextAlign.Center))
        BasicText(body, style = t.subhead.copy(color = cc.inkSecondary, textAlign = TextAlign.Center))
        Box(Modifier.height(CircasoSpacing.s1))
        // The screen's one action: primary and full height (M3c draws a small secondary beside a picker that is gone).
        CircasoButton(action, onAllow)
    }
}

/**
 * Above the grid while Circaso sees only some of the phone: what's missing and the way to allow the rest, in one
 * line (WhatsApp's own notice). The whole line is the button.
 */
@Composable
private fun GalleryGapRow(gap: RecentsGap?, action: String, onAllow: () -> Unit) {
    val cc = CircasoTheme.colors
    val t = CircasoTheme.typography
    val why = when (gap) {
        RecentsGap.NoVideos -> "Your videos aren't showing."
        RecentsGap.NoPhotos -> "Your photos aren't showing."
        else -> "Showing only what you chose."
    }
    Row(
        // The design's inline banner (cx-banner: glass, 16/10, 18 dp icon, subhead), with its action at the end.
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1).glass(CircasoShapes.md)
            .pressable(role = Role.Button, onClick = onAllow).heightIn(min = CircasoSizes.controlMd)
            .padding(horizontal = CircasoSpacing.s4, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$why $action" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircasoIconImage(CircasoIcon.Lock, cc.inkSecondary, Modifier.size(18.dp))
        BasicText(why, Modifier.weight(1f), style = t.subhead.copy(color = cc.ink))
        BasicText(action, style = t.subhead.copy(color = cc.action, fontWeight = FontWeight.SemiBold))
    }
}

/** How close to the grid's end the next page is read. */
private const val GalleryPrefetch = 60

/** Blank tiles at the grid's end while more of the library is still to be read. */
private const val GalleryMoreTiles = 6

/** The gallery's grid as blank tiles (the same columns and gaps), shown while the library is read. */
@Composable
private fun GalleryPlaceholder(modifier: Modifier) {
    val cc = CircasoTheme.colors
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        galleryCells(), modifier.semantics { contentDescription = "Loading your gallery" },
        horizontalArrangement = Arrangement.spacedBy(GalleryGap), verticalArrangement = Arrangement.spacedBy(GalleryGap), userScrollEnabled = false,
    ) {
        items(GalleryPlaceholderTiles) { com.circaso.designsystem.components.SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(1f), androidx.compose.ui.graphics.RectangleShape) }
    }
}

/**
 * Three across on a phone (the design), more as the screen widens, as Photos does: a phone on its side or a small
 * tablet gets more columns of a phone's tile (112 dp keeps every phone from 360 to 450 dp wide at three), a large
 * tablet larger tiles (Photos shows about 7 across a 13" iPad), never three huge tiles nor a contact sheet.
 */
@Composable
private fun galleryCells() = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(
    minSize = if (com.circaso.designsystem.adaptive.LocalWindowLayout.current.widthClass == com.circaso.designsystem.adaptive.WidthClass.Expanded) GalleryTileLarge else GalleryTile,
)

/** A gallery tile's least width: a phone's (three across), and a large tablet's (about seven across a 13" iPad). */
private val GalleryTile = 112.dp
private val GalleryTileLarge = 160.dp

/** Enough blank tiles to cover the largest screen; the lazy grid only draws the ones on screen. */
private const val GalleryPlaceholderTiles = 72
private val GalleryGap = 2.dp

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
