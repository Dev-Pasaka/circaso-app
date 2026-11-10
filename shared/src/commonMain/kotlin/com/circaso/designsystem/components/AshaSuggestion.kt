package com.circaso.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/**
 * The `/` suggestion (Pasaka 2026-11-10): while `/asha` is being typed as a word ([ashaFragment]), a small glass pill
 * with Asha's mark and her name floats just above the field; a tap summons her at once. It takes no room in the layout
 * (it hangs above where it is placed, over whatever is there), so the field and the screen never jump as it comes and
 * goes. Grows in from its bottom-start corner, the field's side, with the quick motion token; fades under reduce motion.
 */
@Composable
fun AshaSuggestion(visible: Boolean, onSummon: () -> Unit, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val reduce = CircasoTheme.reduceMotion
    val origin = TransformOrigin(0f, 1f)
    Box(
        modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0))
            layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
        },
    ) {
        AnimatedVisibility(
            visible,
            // In with ease-out at the quick token; out faster, with ease-in, so it never lingers once it no longer applies.
            enter = if (reduce) fadeIn(snap()) else fadeIn(tween(CircasoMotion.QuickMs, easing = LinearOutSlowInEasing)) +
                scaleIn(tween(CircasoMotion.QuickMs, easing = LinearOutSlowInEasing), initialScale = 0.9f, transformOrigin = origin),
            exit = if (reduce) fadeOut(snap()) else fadeOut(tween(CircasoMotion.ExitMs, easing = FastOutLinearInEasing)) +
                scaleOut(tween(CircasoMotion.ExitMs, easing = FastOutLinearInEasing), targetScale = 0.9f, transformOrigin = origin),
        ) {
            // The 44 dp touch target around a 34 dp pill: the extra room is below it, toward the field it belongs to.
            // One node for a screen reader; the pill itself takes the press (its ripple keeps the pill's shape), the
            // room around it still counts as a tap.
            Box(
                Modifier
                    .clearAndSetSemantics {
                        contentDescription = "Summon Asha"
                        role = Role.Button
                        // Focus stays in the field, so a screen reader is told the suggestion is there.
                        liveRegion = LiveRegionMode.Polite
                        onClick { onSummon(); true }
                    }
                    .heightIn(min = CircasoSizes.controlMd)
                    .clickable(interactionSource = null, indication = null, onClick = onSummon),
                contentAlignment = Alignment.TopStart,
            ) {
                Row(
                    Modifier.heightIn(min = CircasoSizes.controlSm).glass(CircasoShapes.pill, floating = true)
                        .clip(CircasoShapes.pill).pressable(role = Role.Button, onClick = onSummon)
                        .padding(start = CircasoSpacing.s1, end = CircasoSpacing.s3),
                    horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The mark fills the pill less its 4 dp inset on each side, round in round.
                    AshaMark(size = CircasoSizes.controlSm - CircasoSpacing.s2)
                    BasicText("Asha", style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}
