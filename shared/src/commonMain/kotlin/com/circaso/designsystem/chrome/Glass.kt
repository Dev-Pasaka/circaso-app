package com.circaso.designsystem.chrome

import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.theme.CircasoTheme

/**
 * The design's "glass" surface: translucent fill with a hairline edge. [floating] adds the soft
 * float shadow (tab bar, toast). [edge] false drops the hairline (the chat composer, decision 2026-10-30: no borders),
 * except in the high-contrast themes, where the edge is what makes the surface visible. Compose has no cross-platform backdrop blur, so content behind
 * shows through the translucent fill without blurring; the high-contrast themes use a near-opaque fill.
 */
@Composable
fun Modifier.glass(shape: Shape, floating: Boolean = false, edge: Boolean = true): Modifier {
    val c = CircasoTheme.colors
    val base = if (floating) {
        this.shadow(
            elevation = 14.dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = if (c.isDark) 0.55f else 0.10f),
            spotColor = Color.Black.copy(alpha = if (c.isDark) 0.55f else 0.10f),
        )
    } else this
    // Without backdrop blur, a floating surface over scrolling content must be nearly opaque to stay legible.
    val fill = if (floating) c.glass.copy(alpha = maxOf(c.glass.alpha, 0.94f)) else c.glass
    val withFill = base.background(fill, shape)
    return if (edge || c.isHighContrast) withFill.border(1.dp, c.glassEdge, shape) else withFill
}
