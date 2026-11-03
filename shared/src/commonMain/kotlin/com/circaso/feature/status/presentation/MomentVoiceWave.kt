package com.circaso.feature.status.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.status.domain.VoiceLevelCount
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A voice moment's waveform (M5, M5b, V3), as the design's `.wave`: 3 dp bars 3 dp apart, 48 dp tall, centred. The
 * person's own levels as recorded, filled in [ink] up to [progress] and faint after it. While it plays the bars at the
 * playhead lift with their own level; held, everything stays where it is. Older moments and picked audio have no
 * levels: the bars then breathe gently, only while playing. With reduced motion nothing moves but the fill.
 *
 * [levels] is read in the draw phase, so a live wave (recording) redraws the canvas without recomposing anything.
 * [spoken] is what screen readers hear ("0:12 of 0:23"); null keeps the wave silent (a timer beside it speaks).
 */
@Composable
internal fun VoiceWave(
    levels: () -> List<Float>?,
    progress: Float,
    playing: Boolean,
    ink: Color,
    modifier: Modifier = Modifier,
    bars: Int = VoiceLevelCount,
    spoken: String? = null,
) {
    val reduce = CircasoTheme.reduceMotion
    val procedural = levels() == null
    // The breathing phase runs only for a wave without levels, only while playing, and stops where it is when held.
    val phase = remember { Animatable(0f) }
    LaunchedEffect(playing, reduce, procedural) {
        while (procedural && playing && !reduce) phase.animateTo(phase.value + 1f, tween(BreathMs, easing = LinearEasing))
    }
    val fill by animateFloatAsState(progress.coerceIn(0f, 1f), if (reduce) snap() else tween(PollMs, easing = LinearEasing))
    val semantics = if (spoken == null) Modifier.clearAndSetSemantics { } else Modifier.semantics {
        contentDescription = "Voice moment"
        stateDescription = spoken
        progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
    }
    Canvas(modifier.width(BarWidth * bars + BarGap * (bars - 1)).height(48.dp).then(semantics)) {
        val values = levels()?.takeIf { it.isNotEmpty() } ?: FallbackBars
        val n = bars
        val w = BarWidth.toPx()
        val gap = BarGap.toPx()
        val f = fill
        for (i in 0 until n) {
            val at = (i + 0.5f) / n
            val base = values.getOrElse(i * values.size / n) { 0f }.coerceIn(0f, 1f)
            val lift = when {
                reduce -> 0f
                // Follows the playhead, so held it stays where it is; nothing lifts before it starts.
                !procedural -> if (f <= 0f) 0f else 0.35f * exp(-((at - f) * n / 2.5f).let { it * it })
                else -> 0.3f * ((sin(((phase.value + i / n.toFloat()) * 2 * PI).toFloat()) + 1f) / 2f)
            }
            val h = (MinBar + (1f - MinBar) * (base * (1f + lift)).coerceAtMost(1f)) * size.height
            drawRoundRect(
                if (at <= f) ink else ink.copy(alpha = UnplayedAlpha),
                topLeft = Offset(i * (w + gap), (size.height - h) / 2f), size = Size(w, h), cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
        }
    }
}

/**
 * How far a clip has got, read from the player every [PollMs] while [playing]; it starts at 0 for each [key] and
 * holds the last value when paused.
 */
@Composable
internal fun playbackProgress(playing: Boolean, key: Any?, read: () -> Float?): Float {
    val value = remember(key) { mutableFloatStateOf(0f) }
    LaunchedEffect(key, playing) {
        while (playing) {
            read()?.let { value.floatValue = it }
            kotlinx.coroutines.delay(PollMs.toLong())
        }
    }
    return value.floatValue
}

/** The shortest bar, as a share of the wave's 48 dp: about 8 dp, as the frame's quietest bar. */
private const val MinBar = 0.17f
/** The part not yet played. */
private const val UnplayedAlpha = 0.35f
private const val PollMs = 80
private const val BreathMs = CircasoMotion.MomentMs + 300
private val BarWidth = 3.dp
private val BarGap = 3.dp
/** A wave for voice moments without levels (older phones, picked audio): fixed, so it never jumps. */
private val FallbackBars = List(VoiceLevelCount) { i -> 0.25f + 0.55f * (((i * 37 + 11) % 97) / 97f) }
