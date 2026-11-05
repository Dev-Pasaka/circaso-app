package com.circaso.feature.assistant.presentation

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.AshaMark
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.platform.PlatformStyle
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What a screen reader hears for each state of the logo (the header's status line says it too). */
fun VoiceVisual.label(): String = when (this) {
    VoiceVisual.Connecting -> "Connecting"
    VoiceVisual.Listening -> "Listening"
    VoiceVisual.Thinking -> "Thinking"
    VoiceVisual.Speaking -> "Speaking"
    VoiceVisual.Muted -> "Muted"
    VoiceVisual.Error -> "Not connected"
    VoiceVisual.Ended -> "Ended"
}

/**
 * Voice mode's Asha: her silver mark, breathing gently, with voice waves around it. The waves follow [level] (0..1),
 * which is the person's microphone while she listens and her own playback while she speaks, on a soft spring so
 * they swell and settle instead of flickering. Between words her waves never go flat: while she speaks a slow
 * synthetic swell keeps them moving, which also covers a platform that reports no level at all.
 *
 * - Thinking: a single arc of light travels round the mark.
 * - Connecting: one faint ring pulses.
 * - Muted, not connected, ended: still, with a small badge for muted and not connected.
 * - Reduced motion: no waves and no breathing; one still ring and the badge carry the state.
 *
 * Animated values are read only while drawing, so a frame redraws the canvas without recomposing anything.
 */
@Composable
fun AshaVoiceLogo(visual: VoiceVisual, level: Float, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val c = CircasoTheme.colors
    val still = CircasoTheme.reduceMotion
    val smooth = animateFloatAsState(
        level.coerceIn(0f, 1f),
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "level",
    )
    val tint by animateColorAsState(
        when (visual) {
            VoiceVisual.Speaking -> c.action
            VoiceVisual.Listening, VoiceVisual.Thinking -> c.ink
            VoiceVisual.Error -> c.critical
            else -> c.inkTertiary
        },
        tween(CircasoMotion.BaseMs),
        label = "tint",
    )
    val moving = !still && visual in setOf(VoiceVisual.Connecting, VoiceVisual.Listening, VoiceVisual.Thinking, VoiceVisual.Speaking)
    val clock = if (moving) rememberInfiniteTransition(label = "logo") else null
    val breath: State<Float> = clock?.animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (visual == VoiceVisual.Connecting) 1_600 else 3_200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath",
    ) ?: remember { mutableFloatStateOf(0.5f) }
    val phase: State<Float> = clock?.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(if (visual == VoiceVisual.Speaking) 2_200 else 3_600, easing = LinearEasing)), label = "phase")
        ?: remember { mutableFloatStateOf(0f) }
    val markSize = size * 0.42f

    Box(
        modifier.size(size).semantics { contentDescription = "Asha, ${visual.label().lowercase()}"; this.role = Role.Image },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val centre = Offset(this.size.width / 2, this.size.height / 2)
            val base = markSize.toPx() / 2
            val room = this.size.minDimension / 2 - base
            if (!moving) {
                stillRing(visual, tint, centre, base, room)
                return@Canvas
            }
            val b = breath.value
            val p = phase.value
            // Her voice never reads as flat between words; the person's silence does (that is what listening is).
            val heard = when (visual) {
                VoiceVisual.Speaking -> maxOf(smooth.value, 0.14f + 0.1f * sin(p * 2))
                VoiceVisual.Listening -> maxOf(smooth.value, 0.03f + 0.03f * b)
                else -> 0f
            }
            when (visual) {
                VoiceVisual.Connecting -> drawCircle(tint.copy(alpha = 0.15f + 0.25f * b), base + room * (0.3f + 0.1f * b), centre, style = Stroke(1.5.dp.toPx()))
                VoiceVisual.Thinking -> {
                    drawCircle(tint.copy(alpha = 0.12f), base + room * 0.34f, centre, style = Stroke(1.5.dp.toPx()))
                    rotate(p * (180f / PI.toFloat()) * 2f, centre) {
                        val r = base + room * 0.34f
                        drawArc(
                            Brush.sweepGradient(listOf(Color.Transparent, tint.copy(alpha = 0.85f)), centre),
                            startAngle = 0f, sweepAngle = 280f, useCenter = false,
                            topLeft = Offset(centre.x - r, centre.y - r), size = Size(r * 2, r * 2),
                            style = Stroke(width = 2.5.dp.toPx()),
                        )
                    }
                }
                else -> {
                    // A soft glow that grows with the voice, then three waves, each a little further out and fainter.
                    drawCircle(
                        Brush.radialGradient(listOf(tint.copy(alpha = 0.10f + 0.22f * heard), Color.Transparent), centre, base + room * (0.5f + 0.5f * heard)),
                        base + room, centre,
                    )
                    val speaking = visual == VoiceVisual.Speaking
                    for (i in 0 until 3) {
                        val spread = 0.22f + 0.26f * i
                        val radius = base + room * (spread + (0.18f + 0.1f * i) * heard)
                        val amplitude = room * (if (speaking) 0.11f else 0.07f) * heard * (1f - 0.2f * i)
                        val alpha = (if (speaking) 0.55f else 0.42f) - 0.14f * i
                        drawWave(centre, radius, amplitude, p, i + 1, i * 1.7f, tint.copy(alpha = alpha.coerceAtLeast(0.08f)), (2.2f - 0.5f * i).dp.toPx())
                    }
                }
            }
        }
        val breathing = if (moving) breath else null
        AshaMark(
            Modifier.graphicsLayer {
                val b = breathing?.value ?: 0f
                val swell = if (visual == VoiceVisual.Speaking || visual == VoiceVisual.Listening) 0.05f * smooth.value else 0f
                val s = 1f + 0.03f * b + swell
                scaleX = s; scaleY = s
                alpha = if (visual == VoiceVisual.Ended || visual == VoiceVisual.Connecting) 0.75f else 1f
            }.clearAndSetSemantics {},
            size = markSize,
        )
        stateBadge(visual, still)?.let { icon ->
            Box(
                Modifier.align(Alignment.Center).offset(markSize * 0.38f, markSize * 0.38f)
                    .size(26.dp).clip(CircasoShapes.circle).background(if (visual == VoiceVisual.Error) c.critical else c.fillStrong)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) { CircasoIconImage(icon, if (visual == VoiceVisual.Error) c.onAction else c.ink, Modifier.size(14.dp)) }
        }
    }
}

/** The small badge on the mark: always for muted and not connected; with reduced motion, every state has one. */
private fun stateBadge(v: VoiceVisual, still: Boolean): CircasoIcon? = when (v) {
    VoiceVisual.Muted -> CircasoIcon.MicOff
    VoiceVisual.Error -> CircasoIcon.Offline
    VoiceVisual.Listening -> if (still) CircasoIcon.Mic else null
    VoiceVisual.Speaking -> if (still) CircasoIcon.Speaker else null
    VoiceVisual.Thinking -> if (still) CircasoIcon.More else null
    VoiceVisual.Connecting -> if (still) CircasoIcon.Clock else null
    VoiceVisual.Ended -> null
}

/** One still ring whose weight says the state: firm while live, faint once over. */
private fun DrawScope.stillRing(v: VoiceVisual, tint: Color, centre: Offset, base: Float, room: Float) {
    val (alpha, width) = when (v) {
        VoiceVisual.Speaking -> 0.6f to 3f
        VoiceVisual.Listening, VoiceVisual.Thinking -> 0.45f to 2f
        VoiceVisual.Error -> 0.5f to 1.5f
        else -> 0.25f to 1.5f
    }
    drawCircle(tint.copy(alpha = alpha), base + room * 0.32f, centre, style = Stroke(width.dp.toPx()))
}

/** A closed ring whose edge ripples: two sine waves of different counts round the circle, travelling with [phase] at [speed]. */
private fun DrawScope.drawWave(centre: Offset, radius: Float, amplitude: Float, phase: Float, speed: Int, offset: Float, color: Color, width: Float) {
    val path = Path()
    val steps = 96
    for (k in 0..steps) {
        val a = (k.toFloat() / steps) * 2f * PI.toFloat()
        // Whole-number speeds, so the loop of [phase] (0..2π) joins up without a jump.
        val r = radius + amplitude * (0.6f * sin(3 * a + phase * speed + offset) + 0.4f * sin(5 * a - phase * (speed + 1) + offset))
        val x = centre.x + r * cos(a)
        val y = centre.y + r * sin(a)
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color, style = Stroke(width))
}

/**
 * The menu button in Asha's header, in each platform's idiom: a vertical ⋮ on Android, an ellipsis in a circle on
 * iOS. Same size and fill as the header's Back, so the bar stays balanced.
 */
@Composable
fun AshaMenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val ios = CircasoTheme.platform == PlatformStyle.Ios
    Box(
        modifier.size(CircasoSizes.iconButton).clip(CircasoShapes.circle).background(c.fill)
            .pressable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Asha menu" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val u = this.size.width / 24f
            val r = (if (ios) 1.7f else 1.9f) * u
            val dots = if (ios) listOf(Offset(6f, 12f), Offset(12f, 12f), Offset(18f, 12f)) else listOf(Offset(12f, 5f), Offset(12f, 12f), Offset(12f, 19f))
            dots.forEach { d -> drawCircle(c.ink, r, Offset(d.x * u, d.y * u)) }
        }
    }
}
