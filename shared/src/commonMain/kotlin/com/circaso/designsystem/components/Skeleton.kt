package com.circaso.designsystem.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/**
 * Placeholder shapes for a list's first paint, in the `fill` colour, breathing slowly (alpha only, on the GPU layer, so
 * nothing re-lays out per frame). Still under reduced motion.
 */
@Composable
fun SkeletonBlock(modifier: Modifier, shape: Shape = CircasoShapes.xs) {
    val c = CircasoTheme.colors
    val alpha = if (CircasoTheme.reduceMotion) 1f else {
        val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 1f, targetValue = 0.55f,
            animationSpec = infiniteRepeatable(tween(CircasoMotion.MomentMs), RepeatMode.Reverse), label = "skeleton-alpha",
        )
        pulse
    }
    Box(modifier.graphicsLayer { this.alpha = alpha }.background(c.fill, shape))
}

/** [count] placeholder people rows (avatar, name, sub line), the shape of the list that is about to arrive. */
@Composable
fun SkeletonPeopleRows(count: Int = 6, modifier: Modifier = Modifier) {
    Column(modifier.semantics { contentDescription = "Loading" }) {
        repeat(count) { i ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
                horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(Modifier.size(CircasoSizes.avatarMd), CircasoShapes.circle)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Different widths so it reads as names, not a grid of bars.
                    SkeletonBlock(Modifier.fillMaxWidth(listOf(0.46f, 0.6f, 0.38f, 0.52f)[i % 4]).height(14.dp))
                    SkeletonBlock(Modifier.fillMaxWidth(listOf(0.28f, 0.34f, 0.24f, 0.3f)[i % 4]).height(11.dp))
                }
            }
        }
    }
}
