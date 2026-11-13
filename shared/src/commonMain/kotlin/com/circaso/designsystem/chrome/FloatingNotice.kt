package com.circaso.designsystem.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoTheme

/**
 * A notice that floats over the screen under the top bar (2026-11-13, Pasaka: pop-ups float, never push the screen
 * down, and look as considered as iOS's own). A glass capsule sized to its words, a tinted icon badge, a bold title and
 * a quieter detail line. Put it in the scaffold's `banner` slot (or the `toast` slot through [ToastHost]) and show it
 * with [NoticeVisibility].
 */
@Composable
fun FloatingNotice(
    title: String,
    icon: CircasoIcon,
    modifier: Modifier = Modifier,
    detail: String? = null,
    tone: BannerTone = BannerTone.Neutral,
    /** One line, no detail: the toast's pill. Two lines get the rounder card. */
    compact: Boolean = false,
    maxWidth: Dp = 420.dp,
    /** What a screen reader says; defaults to the title and the detail. */
    spoken: String? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val tint = when (tone) {
        BannerTone.Neutral -> c.inkSecondary
        BannerTone.Warning -> c.warning
        BannerTone.Critical -> c.critical
        BannerTone.Success -> c.money
    }
    // The badge reads as the notice's colour without shouting: the tone at low strength, the neutral one on the fill.
    val badge = if (tone == BannerTone.Neutral) c.fill else tint.copy(alpha = if (c.isDark) 0.22f else 0.14f)
    val shape = if (compact || detail == null) CircasoShapes.pill else CircasoShapes.lg
    Row(
        modifier
            .widthIn(max = maxWidth)
            .heightIn(min = 44.dp)
            .glass(shape, floating = true)
            .padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = spoken ?: listOfNotNull(title, detail).joinToString(". ")
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).background(badge, CircasoShapes.circle), contentAlignment = Alignment.Center) {
            CircasoIconImage(icon, if (tone == BannerTone.Neutral) c.ink else tint, Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold),
            )
            if (detail != null) {
                BasicText(detail, maxLines = 2, overflow = TextOverflow.Ellipsis, style = t.footnote.copy(color = c.inkSecondary))
            }
        }
    }
}

/**
 * How a floating notice comes and goes: it drops in from above on a soft spring, settling from slightly smaller, and
 * leaves quicker than it came. With reduced motion it only fades.
 */
object NoticeVisibility {
    @Composable
    fun enter(): EnterTransition =
        if (CircasoTheme.reduceMotion) fadeIn(tween(CircasoMotion.BaseMs))
        else slideInVertically(spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)) { -it - 24 } +
            scaleIn(spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 0f)) +
            fadeIn(tween(CircasoMotion.QuickMs))

    @Composable
    fun exit(): ExitTransition =
        if (CircasoTheme.reduceMotion) fadeOut(tween(CircasoMotion.QuickMs))
        else slideOutVertically(tween(CircasoMotion.BaseMs - 60)) { -it - 24 } +
            scaleOut(tween(CircasoMotion.BaseMs - 60), targetScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 0f)) +
            fadeOut(tween(CircasoMotion.QuickMs))
}

/** [content] floating in with [NoticeVisibility] while [visible]. */
@Composable
fun FloatingNoticeHost(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier, enter = NoticeVisibility.enter(), exit = NoticeVisibility.exit()) { content() }
}
