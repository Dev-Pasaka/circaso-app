package com.circaso.feature.status.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.status.domain.MomentViewer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Reactions on my moment that arrived since I last looked (Pasaka, 2026-11-13: "have the icon they reacted splash
 * into the screen"), played as one splash. Several that land while one is playing merge into the next ([merge]), so
 * a burst of reactions never queues for half a minute.
 */
data class ReactionBurst(val id: Long, val emojis: List<String>, val names: List<String>) {
    fun merge(other: ReactionBurst): ReactionBurst = copy(emojis = emojis + other.emojis, names = (names + other.names).distinct())

    /** At most three different emoji fly in, side by side; the rest are said in the line. */
    val shown: List<String> get() = emojis.distinct().take(3)

    /** "Amina reacted", "Amina and Juma reacted", "Amina and 3 others reacted". */
    val line: String get() = when (names.size) {
        0 -> "New reaction"
        1 -> "${names[0]} reacted"
        2 -> "${names[0]} and ${names[1]} reacted"
        else -> "${names[0]} and ${names.size - 1} others reacted"
    }

    val spoken: String get() = "$line ${emojis.distinct().joinToString(" ")}"
}

/** One reaction as this phone remembers having shown it: who and which emoji, so a changed emoji splashes again. */
fun reactionKey(v: MomentViewer): String? = v.reaction?.let { "${v.userId}|$it" }

/** The reactions in [viewers] this phone hasn't shown yet. */
fun unseenReactions(viewers: List<MomentViewer>, seen: Set<String>): List<MomentViewer> =
    viewers.filter { v -> reactionKey(v)?.let { it !in seen } == true }

/** A quick settle with a little life at the end (the call celebrations' pop), and their strong ease-out. */
private val PopEasing = CubicBezierEasing(0.34f, 1.4f, 0.64f, 1f)
private val OutEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** About 1.6 s, as long as a contribution's flight on a call: `duration-moment` plus two `duration-base` and a `duration-quick`. */
private const val SplashMillis = CircasoMotion.MomentMs + 2 * CircasoMotion.BaseMs + CircasoMotion.QuickMs
/** Reduce Motion: it fades in where it lands, stays long enough to read, and fades out. */
private const val StillMillis = 2 * CircasoMotion.MomentMs + 2 * CircasoMotion.QuickMs
/** A row's emoji popping in the viewers list. */
private const val RowPopMillis = 2 * CircasoMotion.BaseMs

private object SplashBeat {
    /** Each emoji after the first pops in this much later. */
    const val Stagger = 0.06f
    const val PopLength = 0.24f
    const val RingStart = 0.04f
    const val RingEnd = 0.46f
    const val LineIn = 0.14f
    const val LineFull = 0.26f
    const val ExitStart = 0.78f
}

/** One of the small copies thrown out of the splash: its direction, how far, its size, when it starts and its spin. */
private class Shard(val angle: Float, val reach: Float, val size: Float, val delay: Float, val spin: Float, val emoji: Int)

private const val Shards = 12

/**
 * The splash over my moment: the reaction's emoji springs up in the middle of the stage, a ring of light opens behind
 * it and a dozen small copies are thrown out and drift away; then it floats up and fades, with "Amina reacted" under
 * it. Plays [burst]; [onDone] says it has finished so the next (merged) one can play.
 *
 * Nothing here takes a touch, so the moment can still be tapped through mid-splash. If [burst] goes (the moment moved
 * on, Seen by opened) it fades out at once rather than finishing. One progress value drives every frame, read only in
 * `graphicsLayer` and draw blocks, so nothing recomposes while it plays. Reduce Motion: the emoji and its line fade in
 * and out where they are, with no spring, ring or copies.
 */
@Composable
internal fun BoxScope.ReactionSplash(burst: ReactionBurst?, onDone: (Long) -> Unit) {
    var playing by remember { mutableStateOf<ReactionBurst?>(null) }
    val presence = remember { Animatable(0f) }
    LaunchedEffect(burst?.id) {
        if (burst != null) {
            playing = burst
            presence.snapTo(1f)
        } else if (playing != null) {
            // Interrupted: gone quickly, from wherever it was.
            presence.animateTo(0f, tween(CircasoMotion.QuickMs, easing = OutEasing))
            playing = null
        }
    }
    val b = playing ?: return
    Box(Modifier.matchParentSize().graphicsLayer { alpha = presence.value }, contentAlignment = Alignment.Center) {
        key(b.id) { Splash(b, onDone) }
    }
}

@Composable
private fun Splash(b: ReactionBurst, onDone: (Long) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val still = CircasoTheme.reduceMotion
    val done by rememberUpdatedState(onDone)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(b.id) {
        // One light tap as it lands, as a coin settling on a call does.
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        progress.animateTo(1f, tween(if (still) StillMillis else SplashMillis, easing = LinearEasing))
        done(b.id)
    }
    val density = LocalDensity.current
    val emojis = b.shown
    // Fixed sizes, not the text size: this is a picture of the reaction, not words to read.
    val hero = when (emojis.size) { 1 -> 96.dp; 2 -> 76.dp; else -> 64.dp }
    val heroStyle = remember(hero, density) { TextStyle(fontSize = with(density) { (hero * 0.8f).toSp() }) }
    val shards = remember(b.id) {
        val r = Random(b.id.hashCode())
        List(Shards) { i ->
            // Mostly up and out, a few to the sides and down, evenly spread so none clump.
            val base = -PI.toFloat() / 2f + (i - (Shards - 1) / 2f) / Shards * PI.toFloat() * 1.7f
            Shard(base + (r.nextFloat() - 0.5f) * 0.35f, 0.75f + r.nextFloat() * 0.5f, 18f + r.nextFloat() * 12f, 0.05f + r.nextFloat() * 0.1f, (r.nextFloat() - 0.5f) * 80f, i % emojis.size)
        }
    }
    val reach = with(density) { 132.dp.toPx() }
    val fall = with(density) { 36.dp.toPx() }
    val lift = with(density) { 28.dp.toPx() }
    val ringPx = with(density) { hero.toPx() }
    val ink = c.ink

    Column(
        // A little above the middle, clear of the caption and Seen by.
        Modifier.padding(bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (!still) {
                // The ring of light, on its own layer so a frame redraws only this.
                Spacer(
                    Modifier.size(hero).graphicsLayer().drawWithCache {
                        val glow = Brush.radialGradient(listOf(ink.copy(alpha = 0.32f), Color.Transparent), center = center, radius = ringPx)
                        onDrawBehind {
                            val p = progress.value
                            val e = OutEasing.transform(segment(p, SplashBeat.RingStart, SplashBeat.RingEnd))
                            if (e > 0f && e < 1f) {
                                drawCircle(ink, radius = ringPx * (0.45f + 0.75f * e), alpha = 0.5f * (1f - e), style = Stroke(width = 3.dp.toPx() * (1f - e)))
                            }
                            val g = segment(p, 0f, 0.7f)
                            if (g > 0f && g < 1f) drawCircle(glow, radius = ringPx, alpha = sin(g * PI.toFloat()))
                        }
                    },
                )
                // The small copies, thrown out and drifting down as they fade.
                shards.forEach { s ->
                    val style = remember(s, density) { TextStyle(fontSize = with(density) { s.size.dp.toSp() }) }
                    BasicText(
                        emojis[s.emoji], style = style,
                        modifier = Modifier.clearAndSetSemantics {}.graphicsLayer {
                            val l = segment(progress.value, s.delay, s.delay + 0.62f)
                            if (l <= 0f || l >= 1f) { alpha = 0f; return@graphicsLayer }
                            val out = OutEasing.transform(l) * reach * s.reach
                            translationX = cos(s.angle) * out
                            translationY = sin(s.angle) * out + fall * l * l
                            val grow = PopEasing.transform(segment(l, 0f, 0.3f))
                            val sc = (0.4f + 0.6f * grow) * (1f - 0.35f * l)
                            scaleX = sc; scaleY = sc
                            rotationZ = s.spin * l
                            alpha = segment(l, 0f, 0.08f) * (1f - segment(l, 0.5f, 1f))
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1), verticalAlignment = Alignment.CenterVertically) {
                emojis.forEachIndexed { i, emoji ->
                    BasicText(
                        emoji, style = heroStyle,
                        modifier = Modifier.clearAndSetSemantics {}.graphicsLayer {
                            val p = progress.value
                            if (still) {
                                val fade = CircasoMotion.QuickMs.toFloat() / StillMillis
                                alpha = minOf(segment(p, 0f, fade), 1f - segment(p, 1f - fade, 1f))
                                return@graphicsLayer
                            }
                            val start = i * SplashBeat.Stagger
                            val pop = PopEasing.transform(segment(p, start, start + SplashBeat.PopLength))
                            val exit = OutEasing.transform(segment(p, SplashBeat.ExitStart, 1f))
                            val sc = (0.5f + 0.5f * pop) * (1f - 0.12f * exit)
                            scaleX = sc; scaleY = sc
                            rotationZ = (if (i % 2 == 0) -14f else 14f) * (1f - pop)
                            translationY = -lift * exit
                            alpha = segment(p, start, start + SplashBeat.PopLength * 0.4f) * (1f - exit)
                        },
                    )
                }
            }
        }
        // "Amina reacted", in the stage's glass, said once to screen readers.
        BasicText(
            b.line,
            Modifier
                .graphicsLayer {
                    val p = progress.value
                    if (still) {
                        val fade = CircasoMotion.QuickMs.toFloat() / StillMillis
                        alpha = minOf(segment(p, 0f, fade), 1f - segment(p, 1f - fade, 1f))
                        return@graphicsLayer
                    }
                    val inn = OutEasing.transform(segment(p, SplashBeat.LineIn, SplashBeat.LineFull))
                    val exit = segment(p, SplashBeat.ExitStart, 1f)
                    alpha = inn * (1f - exit)
                    translationY = (1f - inn) * 8.dp.toPx() - lift * OutEasing.transform(exit)
                }
                .clip(CircasoShapes.pill).background(c.glass)
                .clearAndSetSemantics { contentDescription = b.spoken; liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2),
            style = t.footnote.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A reaction's emoji in Seen by, popping in as its row first appears: it springs up from small with a little turn
 * while six sparks burst out of it. [play] false shows it still (it already popped this time the list was open).
 * [delayMillis] staggers the rows on screen when the list first shows. Reduce Motion: a short fade.
 */
@Composable
internal fun ReactionPop(emoji: String, play: Boolean, delayMillis: Int, style: TextStyle, size: Dp = 32.dp) {
    val still = CircasoTheme.reduceMotion
    val ink = CircasoTheme.colors.ink
    val p = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (p.value >= 1f) return@LaunchedEffect
        kotlinx.coroutines.delay(delayMillis.toLong())
        p.animateTo(1f, tween(if (still) CircasoMotion.QuickMs else RowPopMillis, easing = LinearEasing))
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (!still) {
            Spacer(
                Modifier.size(size).graphicsLayer().drawBehind {
                    val l = segment(p.value, 0.12f, 1f)
                    if (l <= 0f || l >= 1f) return@drawBehind
                    val out = OutEasing.transform(l)
                    val r = this.size.minDimension / 2f
                    for (i in 0 until 6) {
                        val a = (i * 60f + 30f) * PI.toFloat() / 180f
                        val d = r * (0.6f + 0.75f * out)
                        drawCircle(ink, radius = 1.6.dp.toPx() * (1f - l), center = center + Offset(cos(a) * d, sin(a) * d), alpha = 0.7f * (1f - l))
                    }
                },
            )
        }
        BasicText(
            emoji, style = style,
            modifier = Modifier.graphicsLayer {
                val v = p.value
                if (still) { alpha = v; return@graphicsLayer }
                val pop = PopEasing.transform(segment(v, 0f, 0.55f))
                val sc = 0.4f + 0.6f * pop
                scaleX = sc; scaleY = sc
                rotationZ = -18f * (1f - pop)
                alpha = segment(v, 0f, 0.18f)
            },
        )
    }
}

/** [p]'s place between [from] and [to], 0 before and 1 after. */
private fun segment(p: Float, from: Float, to: Float): Float = if (to <= from) (if (p >= to) 1f else 0f) else ((p - from) / (to - from)).coerceIn(0f, 1f)
