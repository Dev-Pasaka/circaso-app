package com.circaso.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** Where a people row's hairline starts: past the avatar, so the faces line up and the lines don't cut through them. */
val PeopleDividerInset: Dp = CircasoSpacing.s4 + CircasoSizes.avatarMd + CircasoSpacing.s3

/** A quick action's round glyph, the size of an avatar so it lines up with the people below (design C8, `cx-avatar-md`). */
@Composable
fun ActionDisc(icon: CircasoIcon, enabled: Boolean = true) {
    val c = CircasoTheme.colors
    Box(Modifier.size(CircasoSizes.avatarMd).clip(CircasoShapes.circle).background(c.fillStrong), contentAlignment = Alignment.Center) {
        CircasoIconImage(icon, if (enabled) c.ink else c.inkTertiary, Modifier.size(20.dp), strokeWidth = 1.8f)
    }
}

/** A letter over a run of names (A, B, …), as the phone's contacts app; also a screen-reader heading. */
@Composable
fun LetterHeader(letter: String, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    BasicText(
        letter,
        modifier.fillMaxWidth().background(c.bg).padding(start = CircasoSpacing.s4, top = CircasoSpacing.s3, bottom = CircasoSpacing.s1)
            .semantics { heading() },
        style = CircasoTheme.typography.footnote.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold),
    )
}

/** The design's uppercase list caption ("ON CIRCASO", "INVITE TO CIRCASO"): footnote, ink-secondary, a heading. */
@Composable
fun ListCaption(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text.uppercase(),
        modifier.fillMaxWidth().padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s5, bottom = 6.dp).semantics { heading() },
        style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary, letterSpacing = CircasoTheme.typography.footnote.letterSpacing),
    )
}

/**
 * A round checkbox at a people row's end: an empty ring, filling with the action colour and its tick on a quick spring
 * when chosen (no spring under reduced motion). The row carries the meaning for screen readers.
 */
@Composable
fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = CircasoTheme.colors
    val reduce = CircasoTheme.reduceMotion
    val ring by animateColorAsState(if (selected) c.action else if (enabled) c.inkTertiary else c.fillStrong, if (reduce) snap() else tween(CircasoMotion.QuickMs), label = "check-ring")
    Box(modifier.size(24.dp).clip(CircasoShapes.circle).border(1.5.dp, ring, CircasoShapes.circle).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            selected,
            enter = if (reduce) fadeIn(snap()) else scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), initialScale = 0.4f) + fadeIn(tween(CircasoMotion.QuickMs)),
            exit = if (reduce) fadeOut(snap()) else scaleOut(tween(CircasoMotion.QuickMs), targetScale = 0.6f) + fadeOut(tween(CircasoMotion.QuickMs)),
        ) {
            Box(Modifier.size(24.dp).background(c.action, CircasoShapes.circle), contentAlignment = Alignment.Center) {
                CircasoIconImage(CircasoIcon.Check, c.onAction, Modifier.size(14.dp), strokeWidth = 2.6f)
            }
        }
    }
}

/**
 * The invite card: the Circaso mark, a line that says what an invite does, and one clear button. On glass so it sits
 * above the list on both plain and grouped backgrounds without becoming another grey box.
 */
@Composable
fun InviteCard(
    title: String,
    sub: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        modifier.fillMaxWidth().glass(CircasoShapes.lg).padding(start = CircasoSpacing.s4, end = CircasoSpacing.s3, top = CircasoSpacing.s3, bottom = CircasoSpacing.s3),
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(Modifier.clearAndSetSemantics {}, size = CircasoSizes.avatarMd)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = t.headline.copy(color = c.ink))
            BasicText(sub, style = t.footnote.copy(color = c.inkSecondary))
        }
        CircasoButton(action, onClick, size = ButtonSize.Small, enabled = enabled, busy = busy)
    }
}

/**
 * Overlapping avatars, each in a ring of the background colour (design A9's hero), for "your people" moments. With
 * no faces to show, soft initials discs keep the shape.
 */
@Composable
fun AvatarStack(people: List<Pair<String, String?>>, modifier: Modifier = Modifier, size: Dp = CircasoSizes.avatarLg) {
    val c = CircasoTheme.colors
    val ring = 3.dp
    val overlap = size * 0.22f
    Row(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(-overlap)) {
        people.take(4).forEach { (name, url) ->
            Box(Modifier.size(size + ring * 2).clip(CircasoShapes.circle).background(c.bg), contentAlignment = Alignment.Center) {
                Avatar(name, size = size, imageUrl = url)
            }
        }
    }
}

/** A small badge on an avatar's corner (the camera on a photo you can change). */
@Composable
fun AvatarBadge(icon: CircasoIcon, modifier: Modifier = Modifier, size: Dp = 32.dp, enabled: Boolean = true) {
    val c = CircasoTheme.colors
    Box(
        modifier.size(size).clip(CircasoShapes.circle).background(c.bgGrouped).padding(3.dp)
            .clip(CircasoShapes.circle).background(if (enabled) c.action else c.fillStrong),
        contentAlignment = Alignment.Center,
    ) { CircasoIconImage(icon, c.onAction, Modifier.size(size * 0.45f)) }
}
