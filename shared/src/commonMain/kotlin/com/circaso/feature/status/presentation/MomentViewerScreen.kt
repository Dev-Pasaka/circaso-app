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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circaso.core.platform.AudioPlayer
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.VideoPlayer
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.designsystem.chrome.SheetHead
import com.circaso.designsystem.chrome.ToastHost
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
import com.circaso.feature.status.domain.MomentBackground
import com.circaso.feature.status.domain.MomentKind
import com.circaso.feature.status.domain.MomentTextStyle
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import org.koin.compose.koinInject

/** How long a photo or text moment stays before moving on (WhatsApp uses about 5 s). */
private const val StillMs = 5_000

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MomentViewerScreen(viewModel: MomentViewerViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.closed) { if (state.closed) onClose() }
    androidx.compose.ui.backhandler.BackHandler(onBack = onClose)
    // Always the dark stage, in every theme (design V1): the dark tokens, with the person's high-contrast choice kept.
    CircasoTheme(darkTheme = true, highContrast = CircasoTheme.colors.isHighContrast) {
        MomentViewerContent(state, viewModel, onClose)
    }
}

@Composable
private fun MomentViewerContent(state: MomentViewerUiState, vm: MomentViewerViewModel, onClose: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val moment = state.moment
    val ring = state.current
    val toast = rememberToastState()
    LaunchedEffect(state.notice) { state.notice?.let { toast.show(it); vm.noticeShown() } }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (moment == null || ring == null) {
            Spinner(c.inkSecondary, Modifier.align(Alignment.Center))
            return@Box
        }
        Stage(moment, state.media, vm::retryMedia)

        // Tap the left third for back, anywhere else for next; hold to pause; swipe down to close, left for the next person.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(moment.momentId) {
                    detectTapGestures(
                        onPress = { vm.setPaused(true); tryAwaitRelease(); vm.setPaused(false) },
                        onTap = { pos -> if (pos.x < size.width / 3f) vm.previous() else vm.next() },
                    )
                }
                .pointerInput(Unit) { var dy = 0f; detectVerticalDragGestures(onDragEnd = { if (dy > 160) onClose(); dy = 0f }) { _, d -> dy += d } }
                .pointerInput(Unit) { var dx = 0f; detectHorizontalDragGestures(onDragEnd = { if (dx < -160) vm.nextRing(); dx = 0f }) { _, d -> dx += d } },
        )

        // Top: one segment per moment, the person, the time, More and Close.
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Segments(ring.moments, state.index, moment, paused = state.paused || state.media !is ViewerMedia.Ready && moment.kind != MomentKind.Text, onDone = vm::next)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(ring.name, size = 36.dp, imageUrl = ring.avatarUrl)
                Column(Modifier.weight(1f)) {
                    BasicText(if (moment.mine) "My moment" else ring.name, style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1)
                    BasicText(ago(moment.postedAt), style = t.footnote.copy(color = c.inkSecondary))
                }
                GlassButton(CircasoIcon.ChevronRight, "More", rotate = 90f) { if (moment.mine) vm.askDelete(true) else vm.openMore(true) }
                GlassButton(CircasoIcon.Close, "Close", onClick = onClose)
            }
        }

        // Bottom: caption, then the reply field and heart (someone else's) or Seen by (mine).
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            moment.caption?.let {
                BasicText(it, Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s5), style = t.body.copy(color = c.ink, textAlign = TextAlign.Center))
            }
            if (moment.mine) {
                Row(
                    Modifier.align(Alignment.CenterHorizontally).clip(CircasoShapes.pill).background(c.glass).pressable(onClick = { vm.openViewers(true) })
                        .padding(horizontal = CircasoSpacing.s4, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                ) {
                    CircasoIconImage(CircasoIcon.Eye, c.ink, Modifier.size(18.dp))
                    BasicText("Seen by ${moment.viewCount}", style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).height(46.dp).clip(CircasoShapes.pill).border(1.dp, c.inkSecondary, CircasoShapes.pill).padding(horizontal = 18.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (state.reply.isEmpty()) BasicText("Reply to ${ring.name.substringBefore(' ')}", style = t.body.copy(color = c.inkSecondary))
                        BasicTextField(
                            state.reply, vm::onReply, singleLine = true, textStyle = t.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Reply to ${ring.name}" },
                        )
                    }
                    if (state.reply.isNotBlank()) GlassButton(CircasoIcon.ArrowUp, "Send reply", onClick = vm::sendReply)
                    else GlassButton(if (moment.reacted) CircasoIcon.Check else CircasoIcon.Plus, if (moment.reacted) "Loved. Tap to undo" else "Love this moment", heart = true, filled = moment.reacted, onClick = vm::react)
                }
            }
        }

        ToastHost(toast, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 96.dp))
        MoreSheet(state.moreOpen, ring.name, onMute = vm::mute, onDismiss = { vm.openMore(false) })
        ViewersSheet(state, onDismiss = { vm.openViewers(false) }, onDelete = { vm.openViewers(false); vm.askDelete(true) })
        ConfirmSheet(
            visible = state.confirmDelete, title = "Delete this moment?",
            message = "People who haven’t seen it won’t be able to. This can’t be undone.",
            confirmLabel = "Delete Moment", onConfirm = vm::delete, onDismiss = { vm.askDelete(false) }, cancelLabel = "Cancel",
        )
    }
}

/** The moment itself: a text card, the photo, the video, or the voice with its waveform (V1–V3, V5–V6). */
@Composable
private fun BoxScope.Stage(moment: Moment, media: ViewerMedia, onRetry: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    when (moment.kind) {
        MomentKind.Text -> {
            val (bg, ink) = textColours(moment.background)
            Box(Modifier.fillMaxSize().background(bg).padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                BasicText(
                    moment.text.orEmpty(), Modifier.semantics { contentDescription = moment.text.orEmpty() },
                    style = t.title1.copy(color = ink, textAlign = TextAlign.Center, fontWeight = if (moment.style == MomentTextStyle.Bold) FontWeight.Bold else FontWeight.Normal,
                        fontSize = if ((moment.text?.length ?: 0) > 120) 22.sp else 32.sp, lineHeight = if ((moment.text?.length ?: 0) > 120) 28.sp else 39.sp),
                )
            }
        }
        else -> {
            // The blurred placeholder that travelled in the encrypted body holds the place (V5).
            val thumb = remember(moment.thumb) { moment.thumb?.let { runCatching { Base64.decode(it) }.getOrNull() } }
            if (media !is ViewerMedia.Ready && thumb != null) {
                coil3.compose.AsyncImage(thumb, null, Modifier.fillMaxSize().blur(24.dp).graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }, contentScale = ContentScale.Crop)
            }
            when (media) {
                is ViewerMedia.Ready -> when (moment.kind) {
                    MomentKind.Image -> coil3.compose.AsyncImage(media.bytes, moment.caption ?: "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    MomentKind.Video -> media.path?.let { VideoPlayer(it, Modifier.fillMaxSize()) }
                    else -> VoiceStage(moment, media.bytes)
                }
                ViewerMedia.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spinner(c.ink, Modifier.size(28.dp))
                    BasicText("Loading", style = t.subhead.copy(color = c.ink))
                }
                ViewerMedia.NeedsConnection -> StageMessage(CircasoIcon.Offline, "This moment needs a connection", "It opens as soon as you’re back online.")
                is ViewerMedia.Failed -> StageMessage(CircasoIcon.Warning, "This moment didn’t load", media.requestId?.let { "Ref $it" }, "Try Again" to onRetry)
                ViewerMedia.None -> Unit
            }
        }
    }
}

@Composable
private fun BoxScope.VoiceStage(moment: Moment, bytes: ByteArray) {
    val c = CircasoTheme.colors
    val player: AudioPlayer = koinInject()
    LaunchedEffect(moment.momentId) { player.play(bytes, moment.media?.mime ?: "audio/mp4") }
    androidx.compose.runtime.DisposableEffect(moment.momentId) { onDispose { player.stop() } }
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(CircasoSizes.avatarXl).border(3.dp, c.ink, CircasoShapes.circle), contentAlignment = Alignment.Center) {
            CircasoIconImage(CircasoIcon.Waveform, c.ink, Modifier.size(40.dp))
        }
    }
}

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
private fun GlassButton(icon: CircasoIcon, label: String, rotate: Float = 0f, heart: Boolean = false, filled: Boolean = false, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    Box(
        Modifier.size(CircasoSizes.controlMd).clip(CircasoShapes.circle).background(if (filled) c.ink else c.glass)
            .pressable(onClick = onClick).semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (heart) HeartGlyph(if (filled) c.bg else c.ink) else CircasoIconImage(icon, c.ink, Modifier.size(18.dp).graphicsLayer { rotationZ = rotate })
    }
}

/** The quiet reaction (V1): a heart outline, filled once sent. */
@Composable
private fun HeartGlyph(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, w * 0.85f)
            cubicTo(w * 0.1f, w * 0.6f, w * 0.05f, w * 0.3f, w * 0.27f, w * 0.18f)
            cubicTo(w * 0.4f, w * 0.12f, w * 0.48f, w * 0.2f, w * 0.5f, w * 0.28f)
            cubicTo(w * 0.52f, w * 0.2f, w * 0.6f, w * 0.12f, w * 0.73f, w * 0.18f)
            cubicTo(w * 0.95f, w * 0.3f, w * 0.9f, w * 0.6f, w * 0.5f, w * 0.85f)
            close()
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}

/** Text backgrounds: the light theme's own token values, fixed, so a moment looks the same to every viewer. */
private fun textColours(bg: MomentBackground): Pair<Brush, Color> = when (bg) {
    MomentBackground.Ink -> SolidColor(LightColors.ink) to LightColors.bg
    MomentBackground.Metal -> Brush.linearGradient(listOf(LightColors.moneyCardSheen, LightColors.moneyCard, LightColors.moneyCard, LightColors.moneyCardSheen)) to LightColors.onMoney
    MomentBackground.Paper -> SolidColor(LightColors.fill) to LightColors.ink
    MomentBackground.Graphite -> SolidColor(LightColors.inkSecondary) to LightColors.bg
}

private fun Modifier.background(brush: Brush): Modifier = this.then(Modifier.background(brush = brush))

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
private fun BoxScope.MoreSheet(visible: Boolean, name: String, onMute: () -> Unit, onDismiss: () -> Unit) {
    CircasoSheet(visible, onDismiss, label = "Moment options") {
        SheetHead(name.substringBefore(' '), onDismiss)
        ListGroup {
            ListRow("Mute ${name.substringBefore(' ')}’s Moments", sub = "They move to the end of the row. They aren’t told.", onClick = onMute)
        }
    }
}

/** V4: who saw my moment, with their reaction; Delete from here too. */
@Composable
private fun BoxScope.ViewersSheet(state: MomentViewerUiState, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val count = state.viewers?.size ?: state.moment?.viewCount ?: 0
    CircasoSheet(state.viewersOpen, onDismiss, label = "Viewers") {
        SheetHead("Seen by $count", onDismiss)
        when {
            state.viewersError != null -> BasicText(state.viewersError, Modifier.padding(CircasoSpacing.s4), style = t.subhead.copy(color = c.inkSecondary))
            state.viewers == null -> Spinner(c.inkSecondary, Modifier.padding(CircasoSpacing.s4))
            state.viewers.isEmpty() -> BasicText("Nobody has seen it yet.", Modifier.padding(CircasoSpacing.s4), style = t.subhead.copy(color = c.inkSecondary))
            else -> ListGroup {
                state.viewers.take(50).forEach { (v, name) ->
                    ListRow(name, sub = formatLocalTime(v.viewedAt, LocalTimeStyle.Clock), leading = { Avatar(name, size = CircasoSizes.avatarMd) },
                        trailing = { if (v.reacted) HeartGlyph(c.ink) })
                }
            }
        }
        CircasoButton("Delete", onDelete, style = ButtonStyle.Destructive, icon = CircasoIcon.Delete)
    }
}
