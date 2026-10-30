package com.circaso.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoTheme
import kotlin.math.PI
import kotlin.math.sin

/** What the orb is doing, which sets its colour and rhythm. */
enum class OrbMood {
    /** Waiting to connect: a faint, slow breath. */
    Idle,
    /** The person may speak: silver rings that swell with their voice. */
    Listening,
    /** Asha is working on the answer: a slow sweep of light around her mark. */
    Thinking,
    /** Asha is speaking: rings in the action colour that move with her voice. */
    Speaking,
}

/**
 * Asha's mark with a living halo, the centre of the voice screen. [level] (0..1) is how loud the person or Asha is
 * right now; the halo follows it on a soft spring so it swells and settles like breath rather than flickering. With
 * nobody speaking it still breathes slowly, so the screen never looks frozen. Everything is drawn with theme roles,
 * so it reads in all four themes.
 */
@Composable
fun AshaOrb(mood: OrbMood, level: Float, modifier: Modifier = Modifier, size: Dp = 176.dp) {
    val c = CircasoTheme.colors
    val smooth by animateFloatAsState(level.coerceIn(0f, 1f), spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow), label = "level")
    val tint by animateColorAsState(
        when (mood) {
            OrbMood.Speaking -> c.action
            OrbMood.Thinking -> c.inkSecondary
            OrbMood.Listening -> c.ink
            OrbMood.Idle -> c.inkTertiary
        },
        tween(CircasoMotion.BaseMs),
        label = "tint",
    )
    val breathing = rememberInfiniteTransition(label = "breath")
    val breath by breathing.animateFloat(0f, 1f, infiniteRepeatable(tween(if (mood == OrbMood.Idle) 3_200 else 2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val sweep by breathing.animateFloat(0f, 360f, infiniteRepeatable(tween(1_800, easing = LinearEasing)), label = "sweep")
    val ripple by breathing.animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "ripple")

    // The halo's swell: a quiet breath plus the voice. Speaking moves more than listening, as a voice heard does.
    val gain = if (mood == OrbMood.Speaking) 0.55f else 0.4f
    val swell = 0.06f * breath + gain * smooth
    val markSize = size * 0.5f

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val centre = Offset(this.size.width / 2, this.size.height / 2)
            val base = markSize.toPx() / 2
            val room = this.size.minDimension / 2 - base
            // The glow behind everything, stronger the louder it gets.
            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.22f + 0.3f * smooth), Color.Transparent), centre, base + room * (0.55f + 0.45f * swell)), base + room, centre)
            // Three rings, each a little further out and fainter; the voice pushes them outwards.
            for (i in 0 until 3) {
                val spread = (i + 1) / 3f
                val r = base + room * spread * (0.45f + 0.55f * swell.coerceAtMost(1f))
                val alpha = (0.34f - 0.1f * i) * (0.5f + 0.5f * smooth.coerceAtLeast(breath * 0.6f))
                drawCircle(tint.copy(alpha = alpha), r, centre, style = Stroke(width = (2.5f - 0.5f * i).dp.toPx()))
            }
            when (mood) {
                // A single arc of light travelling round the mark: something is happening, nothing to hear yet.
                OrbMood.Thinking -> rotate(sweep, centre) {
                    val r = base + room * 0.35f
                    drawArc(
                        Brush.sweepGradient(listOf(Color.Transparent, tint.copy(alpha = 0.85f)), centre),
                        startAngle = 0f, sweepAngle = 300f, useCenter = false,
                        topLeft = Offset(centre.x - r, centre.y - r), size = Size(r * 2, r * 2),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
                // Ripples leaving the mark while Asha talks, their pace set by her loudness.
                OrbMood.Speaking -> for (k in 0 until 2) {
                    val t = (ripple + k * 0.5f) % 1f
                    val r = base + room * t
                    drawCircle(tint.copy(alpha = (1f - t) * (0.25f + 0.45f * smooth)), r, centre, style = Stroke(width = 1.5.dp.toPx()))
                }
                else -> Unit
            }
        }
        AshaMark(Modifier.scale(1f + 0.08f * swell), size = markSize)
    }
}

/** A tiny caption-side version for rows and headers: the mark with one ring that pulses while speaking. */
@Composable
fun AshaPulse(active: Boolean, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val c = CircasoTheme.colors
    val breathing = rememberInfiniteTransition(label = "pulse")
    val phase by breathing.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "phase")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (active) Canvas(Modifier.size(size)) {
            val t = (sin(phase * 2 * PI).toFloat() + 1f) / 2f
            drawCircle(c.action.copy(alpha = 0.35f * (1f - t)), this.size.minDimension / 2 * (0.7f + 0.3f * t), style = Stroke(width = 1.5.dp.toPx()))
        }
        AshaMark(size = size * 0.72f)
    }
}
