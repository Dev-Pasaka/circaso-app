package com.circaso.feature.calls.presentation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.CoinIcon
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The call's layout as the flights see it: the layer itself, the tiles a contribution rises over, and the goal's raised
 * amount its coin lands in. Coordinates are read only while drawing and placing, so the layout can change (rotation,
 * split view, the goal beside the tiles) mid-flight.
 */
internal class CelebrationAnchors {
    var root: LayoutCoordinates? = null
    var tiles: LayoutCoordinates? = null
    var goal: LayoutCoordinates? = null

    /** The tiles' box in the layer. */
    fun area(): Rect? {
        val r = root; val t = tiles
        if (r == null || t == null || !r.isAttached || !t.isAttached) return null
        return r.localBoundingBoxOf(t, clipBounds = false)
    }

    /** The centre of the goal's raised amount ("KES 36,900"), where the coin settles; null without a goal on screen. */
    fun target(): Offset? {
        val r = root; val g = goal
        if (r == null || g == null || !r.isAttached || !g.isAttached) return null
        return r.localPositionOf(g, Offset(g.size.width / 2f, g.size.height / 2f))
    }
}

/**
 * Contributions landing on a group call (H4, decided 2026-11-09; the design shows the goal panel's row of
 * "Juma +KES 2,500" pills but not how one arrives). Only a confirmed contribution flies (the money really moved): the
 * money card's pill "Amina +KES 1,000" pops up low over the tiles, rises, breaks into sparks that gather into the coin
 * that was paid, and the coin arcs into the goal's raised amount, which then counts up as the panel pulses and the
 * pill joins its row. About 1.6 s (`duration-moment` plus two `duration-base` and a `duration-quick`); while others
 * wait, every flight in the air speeds up to about 1.2 s. Several at once stack one above the other inside the tiles,
 * the same on a phone, a phone on its side and a tablet ([CelebrationState] gives each a lane).
 *
 * Cheap to draw: each frame only moves one progress value, read in `graphicsLayer`, `offset {}` and a spark canvas on
 * its own layer (so the video tiles under it aren't redrawn); the glow brushes are built once. Nothing here takes a
 * touch, and the moving pill is hidden from screen readers (the goal panel's row announces it, once). Leaving the call
 * screen part-way lands it at once. Reduce Motion: the pill fades in where it starts, stays, and fades out; no rise,
 * sparks or coin.
 */
@Composable
internal fun BoxScope.CelebrationLayer(
    state: CelebrationState,
    anchors: CelebrationAnchors,
    onLanded: (String) -> Unit,
    onFinished: (String) -> Unit,
) {
    Box(Modifier.matchParentSize()) {
        state.shown.forEach { shown ->
            key(shown.celebration.id) { Flight(shown, state.hurry, anchors, onLanded, onFinished) }
        }
    }
}

/** The timeline, as fractions of one flight. */
private object Beat {
    const val PopEnd = 0.16f
    const val RiseEnd = 0.50f
    const val DissolveStart = 0.40f
    const val DissolveEnd = 0.62f
    const val CoinIn = 0.52f
    const val CoinFull = 0.66f
    const val FlyStart = 0.66f
    const val Land = 0.92f
}

/** A quick settle with a little life at the end (the pop), and the standard ease for travel. */
private val PopEasing = CubicBezierEasing(0.34f, 1.4f, 0.64f, 1f)
private val TravelEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private val RiseEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

private const val FlightMillis = CircasoMotion.MomentMs + 2 * CircasoMotion.BaseMs + CircasoMotion.QuickMs
private const val HurryMillis = CircasoMotion.MomentMs + CircasoMotion.BaseMs
private const val StillHoldMillis = 2 * CircasoMotion.MomentMs

private val RiseBy = CircasoSpacing.s14
private val CoinSize = CircasoSizes.avatarSm
private const val Sparks = 22

/** H4's `.ti-pill` avatar (24 px in the frame; between the system's sizes, so the frame's own value). */
internal val PillAvatar = 24.dp

/** H4's `.ti-pill`: the money card's sheen at its corners, 140°, stops 0 / 40 / 62 / 100 %. */
internal fun moneyPillBrush(sheen: Color, card: Color): Brush =
    Brush.linearGradient(0f to sheen, 0.4f to card, 0.62f to card, 1f to sheen, start = Offset.Zero, end = Offset.Infinite)

/** One spark: where in the pill it starts (fractions of its size), how it swirls in, its size and its delay. */
private class Spark(val x: Float, val y: Float, val swirl: Float, val radius: Float, val delay: Float, val bright: Boolean)

@Composable
private fun Flight(
    shown: ShownCelebration,
    hurry: Boolean,
    anchors: CelebrationAnchors,
    onLanded: (String) -> Unit,
    onFinished: (String) -> Unit,
) {
    val c = shown.celebration
    val still = CircasoTheme.reduceMotion
    val landed by rememberUpdatedState(onLanded)
    val finished by rememberUpdatedState(onFinished)
    val hurrying by rememberUpdatedState(hurry)
    val progress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(c.id) {
        var told = false
        val land = { if (!told) { told = true; landed(c.id) } }
        try {
            if (still) land()
            // Frame by frame, so a burst arriving mid-flight speeds up the ones already in the air.
            var last = withFrameNanos { it }
            while (progress.floatValue < 1f) {
                val now = withFrameNanos { it }
                val length = if (still) StillHoldMillis + 2 * CircasoMotion.QuickMs else if (hurrying) HurryMillis else FlightMillis
                progress.floatValue = (progress.floatValue + (now - last) / 1_000_000f / length).coerceAtMost(1f)
                last = now
                if (progress.floatValue >= Beat.Land) land()
            }
        } finally {
            // Cut short (the call minimised or ended): it still counts as landed, and its lane frees.
            land()
            finished(c.id)
        }
    }

    val density = LocalDensity.current
    val rise = with(density) { RiseBy.toPx() }
    val coinPx = with(density) { CoinSize.toPx() }
    val gap = with(density) { CircasoSpacing.s2.toPx() }
    val pop = with(density) { CircasoSpacing.s2.toPx() }
    var pillSize by remember { mutableStateOf(IntSize.Zero) }
    val sparks = remember(c.id) {
        val r = Random(c.id.hashCode())
        List(Sparks) { Spark(r.nextFloat() - 0.5f, r.nextFloat() - 0.5f, (r.nextFloat() - 0.5f) * PI.toFloat(), 1.2f + r.nextFloat() * 1.8f, r.nextFloat() * 0.35f, r.nextFloat() < 0.4f) }
    }
    val colors = CircasoTheme.colors
    val sparkBright = colors.onMoney
    val sparkSoft = colors.moneyCardSheen

    /**
     * Where the pill's centre is at progress [p]: low in the tiles (clear of "on its way" under them), its lane
     * stacked above the one below, kept inside the tiles' box; then risen. Null until the pill has been measured.
     */
    fun pillCentre(p: Float): Offset? {
        if (pillSize == IntSize.Zero) return null
        val area = anchors.area() ?: return null
        val half = pillSize.width / 2f
        val x = area.center.x.coerceIn(area.left + half, (area.right - half).coerceAtLeast(area.left + half))
        val base = area.top + area.height * 0.62f - shown.lane * (pillSize.height + gap)
        val up = if (still) 0f else rise * RiseEasing.transform(segment(p, 0f, Beat.RiseEnd))
        // The stack as a whole stays inside the box: on a short tiles box (a phone on its side) the lanes keep their
        // spacing and the rise gives way, rather than the top pills running into each other.
        val floor = area.top + pillSize.height / 2f + (CelebrationState.MaxShown - 1 - shown.lane) * (pillSize.height + gap)
        return Offset(x, (base - up).coerceAtLeast(floor))
    }

    Box(Modifier.fillMaxSize()) {
        // The sparks, and the light where the coin forms and where it lands: on a layer of its own, so a frame redraws
        // only this canvas, never the video tiles under it.
        if (!still) {
            Spacer(
                Modifier.fillMaxSize().graphicsLayer().drawWithCache {
                    val glowRadius = coinPx * 1.6f
                    val glow = Brush.radialGradient(listOf(sparkBright, Color.Transparent), center = Offset.Zero, radius = glowRadius)
                    onDrawBehind {
                        val p = progress.floatValue
                        val from = pillCentre(minOf(p, Beat.FlyStart)) ?: return@onDrawBehind
                        val d = segment(p, Beat.DissolveStart, Beat.DissolveEnd + 0.08f)
                        if (d > 0f && d < 1f) {
                            sparks.forEach { s ->
                                val local = segment(d, s.delay, 1f)
                                if (local <= 0f || local >= 1f) return@forEach
                                // Out a little, then drawn in to the coin on a curl.
                                val pull = TravelEasing.transform(local)
                                val burst = (1f + 0.35f * sin(local * PI.toFloat())) * (1f - pull)
                                val dx = s.x * pillSize.width * burst
                                val dy = s.y * pillSize.height * burst
                                val angle = s.swirl * (1f - pull)
                                val rx = dx * cos(angle) - dy * sin(angle)
                                val ry = dx * sin(angle) + dy * cos(angle)
                                drawCircle(if (s.bright) sparkBright else sparkSoft, radius = s.radius * density.density, center = Offset(from.x + rx, from.y + ry), alpha = sin(local * PI.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                        val form = segment(p, Beat.CoinIn, Beat.FlyStart + 0.06f)
                        if (form > 0f && form < 1f) translate(from.x, from.y) { drawCircle(glow, radius = glowRadius, center = Offset.Zero, alpha = sin(form * PI.toFloat()) * 0.35f) }
                        val target = anchors.target()
                        val settle = segment(p, Beat.Land - 0.04f, 1f)
                        if (target != null && settle > 0f && settle < 1f) translate(target.x, target.y) { drawCircle(glow, radius = glowRadius, center = Offset.Zero, alpha = sin(settle * PI.toFloat()) * 0.4f) }
                    }
                },
            )
        }

        // "Amina +KES 1,000", in the money card's pill (H4's `.ti-pill`).
        Row(
            Modifier
                .offset {
                    val centre = pillCentre(progress.floatValue) ?: return@offset IntOffset(-10_000, -10_000)
                    IntOffset((centre.x - pillSize.width / 2f).roundToInt(), (centre.y - pillSize.height / 2f).roundToInt())
                }
                .onSizeChanged { pillSize = it }
                .graphicsLayer {
                    val p = progress.floatValue
                    if (still) {
                        val fade = CircasoMotion.QuickMs.toFloat() / (StillHoldMillis + 2 * CircasoMotion.QuickMs)
                        alpha = minOf(segment(p, 0f, fade), 1f - segment(p, 1f - fade, 1f))
                    } else {
                        val popped = PopEasing.transform(segment(p, 0f, Beat.PopEnd))
                        val gone = segment(p, Beat.DissolveStart, Beat.DissolveEnd)
                        alpha = segment(p, 0f, Beat.PopEnd * 0.6f) * (1f - gone)
                        val s = (0.86f + 0.14f * popped) * (1f + 0.06f * gone)
                        scaleX = s; scaleY = s
                        translationY = (1f - popped) * pop
                    }
                }
                .clip(CircasoShapes.pill)
                .background(moneyPillBrush(colors.moneyCardSheen, colors.moneyCard))
                .clearAndSetSemantics {}
                .padding(start = CircasoSpacing.s2, end = CircasoSpacing.s3, top = CircasoSpacing.s2, bottom = CircasoSpacing.s2),
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(c.who, size = PillAvatar, imageUrl = c.photoUrl)
            BasicText(c.text, style = CircasoTheme.typography.footnote.copy(color = colors.onMoney, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // The coin that was paid: forms where the pill broke up, then arcs into the goal.
        if (!still) {
            CoinIcon(
                c.coin,
                Modifier
                    .offset {
                        val p = progress.floatValue
                        val from = pillCentre(minOf(p, Beat.FlyStart)) ?: return@offset IntOffset(-10_000, -10_000)
                        val to = anchors.target() ?: from
                        val fly = TravelEasing.transform(segment(p, Beat.FlyStart, Beat.Land))
                        // A gentle arc, up and over, rather than a straight line.
                        val control = Offset((from.x + to.x) / 2f, minOf(from.y, to.y) - rise * 0.6f)
                        val q = 1f - fly
                        val x = q * q * from.x + 2 * q * fly * control.x + fly * fly * to.x
                        val y = q * q * from.y + 2 * q * fly * control.y + fly * fly * to.y
                        IntOffset((x - coinPx / 2f).roundToInt(), (y - coinPx / 2f).roundToInt())
                    }
                    .graphicsLayer {
                        val p = progress.floatValue
                        val appear = PopEasing.transform(segment(p, Beat.CoinIn, Beat.CoinFull))
                        val fly = segment(p, Beat.FlyStart, Beat.Land)
                        val settle = segment(p, Beat.Land, 1f)
                        alpha = segment(p, Beat.CoinIn, Beat.CoinFull) * (1f - settle)
                        val s = (0.4f + 0.6f * appear) * (1f - 0.4f * fly) * (1f - 0.5f * settle)
                        scaleX = s; scaleY = s
                    },
                size = CoinSize,
                tint = colors.onMoney,
                disc = colors.moneyCardSheen,
            )
        }
    }
}

/** [p]'s place between [from] and [to], 0 before and 1 after. */
private fun segment(p: Float, from: Float, to: Float): Float = if (to <= from) (if (p >= to) 1f else 0f) else ((p - from) / (to - from)).coerceIn(0f, 1f)
