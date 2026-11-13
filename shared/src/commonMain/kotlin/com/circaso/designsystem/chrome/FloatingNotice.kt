package com.circaso.designsystem.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.adaptive.WindowLayout
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.CircasoSpring
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** The tinted badge's strength over the glass: enough to read as the notice's colour, never loud (4.5:1+ icon contrast). */
private const val BadgeAlphaLight = 0.14f
private const val BadgeAlphaDark = 0.22f

/**
 * A notice that floats over the screen (2026-11-13, Pasaka: pop-ups float, never push the screen down, and look as
 * considered as iOS's own). A glass capsule sized to its words, a tinted icon badge, a bold title and an optional
 * quieter detail line. It takes its own taps (nothing underneath opens by accident): [onTap] when given, and a swipe
 * up calls [onDismiss]. Put it in the scaffold's `banner` slot, or the `toast` slot through [ToastHost], inside a
 * [FloatingNoticeHost].
 *
 * Departure from `cx-banner` (recorded in the design-system reference): a badge instead of a bare icon, a title plus
 * detail instead of one sentence, and `lg` corners once it wraps (concentric with the 30 dp badge and 8 dp inset).
 */
@Composable
fun FloatingNotice(
    title: String,
    icon: CircasoIcon,
    modifier: Modifier = Modifier,
    detail: String? = null,
    tone: BannerTone = BannerTone.Neutral,
    maxWidth: Dp = WindowLayout.NoticeMaxWidth,
    /** What a screen reader says; defaults to the title and the detail. */
    spoken: String? = null,
    onTap: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val tint = when (tone) {
        BannerTone.Neutral -> c.inkSecondary
        BannerTone.Warning -> c.warning
        BannerTone.Critical -> c.critical
    }
    val badge = if (tone == BannerTone.Neutral) c.fill else tint.copy(alpha = if (c.isDark) BadgeAlphaDark else BadgeAlphaLight)
    // One line of words is a pill; once the words wrap (or there is a detail line) the rounder card.
    var titleLines by remember { mutableIntStateOf(1) }
    val shape = if (detail == null && titleLines <= 1) CircasoShapes.pill else CircasoShapes.lg
    val swipe = with(LocalDensity.current) { CircasoSpacing.s6.toPx() }
    Row(
        modifier
            .widthIn(max = maxWidth)
            .animateContentSize(CircasoSpring.resize())
            .heightIn(min = CircasoSizes.controlMd)
            .glass(shape, floating = true)
            // Its own taps and drags: the row or field under a floating notice never opens through it.
            .pointerInput(onTap) { detectTapGestures { onTap?.invoke() } }
            .pointerInput(onDismiss) {
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = { if (travelled < -swipe) onDismiss?.invoke() },
                ) { _, dy -> travelled += dy }
            }
            .padding(start = CircasoSpacing.s2, end = CircasoSpacing.s4, top = CircasoSpacing.s2, bottom = CircasoSpacing.s2)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                // Drawn last, read first: a screen reader meets the notice before the screen under it.
                traversalIndex = -1f
                contentDescription = spoken ?: listOfNotNull(title, detail).joinToString(". ")
            },
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3 - 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(CircasoSizes.noticeBadge).background(badge, CircasoShapes.circle), contentAlignment = Alignment.Center) {
            CircasoIconImage(icon, if (tone == BannerTone.Neutral) c.ink else tint, Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f, fill = false)) {
            BasicText(
                title,
                // Errors run long and end with a support reference: wrap rather than cut it off.
                maxLines = if (detail == null) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { titleLines = it.lineCount },
                style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold),
            )
            if (detail != null) {
                BasicText(detail, maxLines = 2, overflow = TextOverflow.Ellipsis, style = t.footnote.copy(color = c.inkSecondary))
            }
        }
    }
}

/**
 * How a floating notice comes and goes: it drops a short way in on the notice spring, settling from slightly smaller,
 * and leaves quicker than it came, accelerating away. With reduced motion it only fades. Interruptible: a notice that
 * comes back mid-exit reverses from where it is.
 */
object NoticeVisibility {
    @Composable
    fun enter(): EnterTransition {
        if (CircasoTheme.reduceMotion) return fadeIn(tween(CircasoMotion.BaseMs))
        val travel = with(LocalDensity.current) { CircasoSpacing.s3.roundToPx() }
        return slideInVertically(CircasoSpring.notice()) { -travel } +
            scaleIn(CircasoSpring.notice(), initialScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 0f)) +
            fadeIn(tween(CircasoMotion.QuickMs))
    }

    @Composable
    fun exit(): ExitTransition {
        if (CircasoTheme.reduceMotion) return fadeOut(tween(CircasoMotion.QuickMs))
        val travel = with(LocalDensity.current) { CircasoSpacing.s3.roundToPx() }
        return slideOutVertically(tween(CircasoMotion.QuickMs, easing = FastOutLinearInEasing)) { -travel } +
            scaleOut(tween(CircasoMotion.QuickMs, easing = FastOutLinearInEasing), targetScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 0f)) +
            fadeOut(tween(CircasoMotion.QuickMs))
    }
}

/** [content] floating in with [NoticeVisibility] while [visible]. */
@Composable
fun FloatingNoticeHost(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier, enter = NoticeVisibility.enter(), exit = NoticeVisibility.exit()) { content() }
}
