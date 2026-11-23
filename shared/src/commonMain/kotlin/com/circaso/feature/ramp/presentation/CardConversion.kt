package com.circaso.feature.ramp.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circaso.designsystem.components.CoinIcon
import com.circaso.designsystem.components.MpesaLogo
import com.circaso.designsystem.motion.CircasoSpring
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

// ---- the conversion between M-Pesa's card and the USDC card, either way ------------------------------------------------
//
// One engine for both ramps (Pasaka, 2026-11-23: "the same animation from card to M-Pesa in reverse when cashing out"):
// Add Money turns the green M-Pesa card into the silver USDC card ([ConversionCard]); Cash Out turns the silver card
// into the green one ([SaleCard]). [CardConversion] only knows a card it starts from, a card it ends on and a stage;
// which face is which comes from [ConversionDirection].

/** Which way the money turns: the card the order starts on, and the card it lands on. */
internal enum class ConversionDirection {
    /** Add Money: shillings paid on M-Pesa become USDC. */
    MpesaToUsdc,
    /** Cash Out: USDC sold becomes shillings on M-Pesa. */
    UsdcToMpesa,
}

/** Where the order's card is: which card shows, and how it behaves. */
internal enum class CardStage {
    /** The card the order starts on, waiting (on the M-Pesa PIN, or on the USDC reaching Circaso's partner): it breathes. */
    Waiting,
    /** The money has moved on: the first card has turned into the second, which is on its way. */
    Converting,
    /** The money is there: the second card settles. */
    Landed,
    /** It didn't go through and nothing moved: the first card dims, nothing turns. */
    Ended,
    /**
     * The money may have moved and nothing is settled (in review, an ending after the payment, a state this version
     * doesn't know once paid): a conversion under way finishes, then whichever card shows holds; nothing celebrates,
     * and nothing turns back as if the money had gone home.
     */
    Held,
    /**
     * The money is coming back where it started (a sale's USDC refunded): the first card, whole and undimmed; a
     * conversion caught mid-way gently goes back to it. Never a celebration.
     */
    Returned,
}

/** What the two cards say: M-Pesa's side and the USDC side, whichever way the money goes. */
internal data class CardWords(
    val mpesaLabel: String, val mpesa: String, val phone: String?,
    val usdcLabel: String, val usdc: String, val chain: String,
    /** What a screen reader hears for the card on screen (the state itself is announced by the title). */
    val mpesaDescription: String, val usdcDescription: String,
)

/** The whole conversion, from the first piece leaving the first card to the last one landing on the second. */
internal const val ConvertMs = CircasoMotion.MomentMs + CircasoMotion.BaseMs + CircasoMotion.QuickMs
/** One pass of light across the card as it settles. */
internal const val SheenMs = CircasoMotion.MomentMs
/** The second card waits for the money a touch smaller, and settles to its size when it lands. */
private const val SettleFrom = 0.965f
/** A dimmed card: its colour gives way to grey, under a light veil of the ground (white text stays above 4.5:1). */
private const val DimVeil = 0.10f
private const val DimVeilDark = 0.24f
/** The waiting card's slow breath and the sending card's slow sheen, as periods. */
private const val BreathMs = CircasoMotion.MomentMs * 3
private const val SendingSheenMs = CircasoMotion.MomentMs * 4
/** How long a face's changed label takes to finish cross-fading ([FaceLabel]), with a frame to spare. */
private const val LabelSettleMs = CircasoMotion.BaseMs + 34L
/** The card's shadow on the light themes (none on dark ground, where it can't be seen). */
private val CardShadow = 10.dp
private val CardShadowInk = Color.Black.copy(alpha = 0.22f)
/** A card is a bank card's shape, never wider than this. */
private val CardMaxWidth = 340.dp
private const val CardAspect = 1.586f

/**
 * How light plays on a face: [band] is the sheen's strength in a pass as the card settles; the slow loops (the
 * breath, the sending sheen) show it at [loop] of that. A hint on the green, a polish on the silver.
 */
private class FaceLight(val band: Float, val loop: Float)
private val GreenLight = FaceLight(band = 0.22f, loop = 0.45f)
private val SilverLight = FaceLight(band = 0.42f, loop = 0.55f)

/**
 * An order's hero, either way: the card the money starts on waits, breathing; once the money moves it comes apart
 * into small pieces that drift off and fade while the other card forms behind them, piece by piece, left to right;
 * when the money lands that card settles with one pass of light, the `Confirm` haptic and [onLanded] (the chime) on
 * the same frame. An ending where nothing moved calmly dims the first card; one in review holds; money coming back
 * ([CardStage.Returned]) shows the first card again, whole.
 *
 * **How it is drawn:** both faces are ordinary composables recorded into [GraphicsLayer]s. While the first card waits
 * each is copied once to a bitmap held as an image shader, the pieces are worked out once ([CardParticles]), and every
 * frame only fills small rects from those shaders under a transform: no allocation, no layout, no recomposition (the
 * progress is read in the draw phase only). (Drawing a bitmap square by square instead made iOS turn the whole bitmap
 * into a new Skia image on every call: a two-second stall.)
 * It can be interrupted: an order first seen already converted shows the second card at once, and a state that moves
 * on mid-way lets the conversion finish before the card settles. Under **Reduce Motion** the first card cross-fades to
 * the second, with no pieces, no breath and no sheen. Screen readers hear the card in words.
 *
 * [heldOnSecond]: a [CardStage.Held] card opened as such shows the second card (the money had already moved).
 * [celebrate] is the view model's word that this order's landing hasn't been heard on this phone yet; [quiet] that it
 * has. [frozenAt] draws one frame of the conversion (0..1) and holds it, for the gallery.
 */
@Composable
internal fun CardConversion(
    direction: ConversionDirection, stage: CardStage, live: CardWords, heldOnSecond: Boolean,
    celebrate: Boolean, quiet: Boolean, onLanded: () -> Unit, landingKey: String,
    modifier: Modifier = Modifier, frozenAt: Float? = null,
) {
    val c = CircasoTheme.colors
    val still = CircasoTheme.reduceMotion
    val toUsdc = direction == ConversionDirection.MpesaToUsdc
    val secondFirst = stage == CardStage.Converting || stage == CardStage.Landed || (stage == CardStage.Held && heldOnSecond)
    // 0 is the first card, 1 the second; read only while drawing (here only its target, which moves once per turn).
    val progress = remember { Animatable(if (secondFirst) 1f else 0f) }
    val particles = remember(direction) { CardParticles(if (toUsdc) GreenDust else SilverDust) }
    // Mid-conversion the faces keep the words they were copied with, and so does a card about to turn with its copies
    // ready (an order that skips straight from waiting to landed changes them, and a poll can easily miss PAYING_OUT):
    // the conversion starts on the next frame from the copies already made, never waits for new words to settle.
    // They catch up once it's whole.
    var midway by remember { mutableStateOf(false) }
    val aboutToTurn = (stage == CardStage.Converting || stage == CardStage.Landed) && particles.ready && progress.targetValue < 1f
    val shownWords = remember { arrayOf(live) }
    if (!midway && !aboutToTurn) shownWords[0] = live
    val words = shownWords[0]
    val dimTo = if (stage == CardStage.Ended) 1f else 0f
    val dim = remember { Animatable(dimTo) }
    // An order opened on a landing already heard is simply whole: no nudge each time it is opened from history.
    val settle = remember { Animatable(if (secondFirst && !still && !quiet) SettleFrom else 1f) }
    val sheen = remember { Animatable(0f) }
    // How the progress is drawn: in pieces, or as a cross-fade (Reduce Motion, going back, or no bitmaps).
    var crossfade by remember { mutableStateOf(still) }
    val firstLayer = rememberGraphicsLayer()
    val secondLayer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val landed by rememberUpdatedState(onLanded)
    val dust = with(LocalDensity.current) { 1.4.dp.toPx() }

    // Both faces are copied ahead, while the first card waits (and again if their words change), so the frame the
    // money moves on starts the conversion at once instead of reading back two bitmaps first.
    LaunchedEffect(words, stage == CardStage.Waiting) {
        if (stage != CardStage.Waiting || still || frozenAt != null) return@LaunchedEffect
        // A label that just changed cross-fades for Base: the copy waits for it, or the pieces would carry the old
        // words (seen on the simulator: "You sold" breaking up on a sale's next round).
        if (particles.capturedFor != null) delay(LabelSettleMs)
        withFrameNanos {}; withFrameNanos {} // both faces drawn, and so recorded
        particles.prepare(firstLayer, secondLayer, dust, words)
    }

    /** Runs (or finishes) the conversion from wherever it is. */
    suspend fun convert() {
        if (progress.value >= 1f) return
        if (!particles.ready || (progress.value <= 0f && particles.capturedFor != words)) {
            // Words that changed a moment ago are still cross-fading: copied once they have settled.
            if (particles.capturedFor != null && !still) delay(LabelSettleMs)
            withFrameNanos {}
            crossfade = still || !particles.prepare(firstLayer, secondLayer, dust, words)
        } else if (progress.value <= 0f) crossfade = still
        val left = 1f - progress.value
        midway = true
        try {
            coroutineScope {
                // Smaller while it waits for the money, in step with the pieces gathering.
                launch { if (!still) settle.animateTo(SettleFrom, tween((ConvertMs * left).toInt(), easing = FastOutSlowInEasing)) }
                progress.animateTo(1f, if (crossfade) tween((CircasoMotion.BaseMs * left).toInt()) else tween((ConvertMs * left).toInt(), easing = LinearEasing))
            }
        } finally {
            if (progress.value >= 1f) midway = false
        }
    }

    /** Back to the first card, gently (a cross-fade, never the pieces in reverse), and at its size. */
    suspend fun goBack() = coroutineScope {
        if (progress.value > 0f) {
            crossfade = true
            launch { progress.animateTo(0f, tween(CircasoMotion.BaseMs)); midway = false }
        }
        launch { settle.animateTo(1f, tween(CircasoMotion.BaseMs)) }
    }

    // The card follows the order. A conversion under way finishes unless the money is going back.
    LaunchedEffect(stage, frozenAt) {
        if (frozenAt != null) {
            withFrameNanos {} // both faces have been drawn (and so recorded) once
            crossfade = still || !particles.prepare(firstLayer, secondLayer, dust, words)
            progress.snapTo(frozenAt)
            return@LaunchedEffect
        }
        when (stage) {
            CardStage.Waiting -> {
                launch { dim.animateTo(0f, tween(CircasoMotion.BaseMs)) }
                if (progress.value > 0f) { crossfade = true; progress.animateTo(0f, tween(CircasoMotion.BaseMs)); midway = false }
            }
            CardStage.Converting, CardStage.Landed -> {
                launch { dim.animateTo(0f, tween(CircasoMotion.BaseMs)) }
                convert()
            }
            CardStage.Ended -> {
                // Nothing came apart for money that never moved: back to the first card, gently, and dimmed.
                launch { goBack() }
                dim.animateTo(1f, tween(if (still) CircasoMotion.BaseMs else CircasoMotion.MomentMs))
            }
            CardStage.Returned -> {
                launch { goBack() }
                dim.animateTo(0f, tween(CircasoMotion.BaseMs))
            }
            CardStage.Held -> {
                // A conversion caught mid-way finishes (never left in pieces), then the card holds at its size.
                if (progress.value > 0f) convert()
                launch { settle.animateTo(1f, tween(CircasoMotion.BaseMs)) }
                dim.animateTo(0f, tween(CircasoMotion.BaseMs))
            }
        }
    }
    // The landing: once the card is whole, it settles. Heard and felt only when the view model says so, and run in
    // its own scope so the view model clearing [celebrate] (once rung) never cuts the pass of light short.
    var celebrated by remember(landingKey) { mutableStateOf(false) }
    LaunchedEffect(stage == CardStage.Landed, celebrate, quiet, frozenAt) {
        if (stage != CardStage.Landed || frozenAt != null) return@LaunchedEffect
        snapshotFlow { progress.value >= 1f }.first { it }
        when {
            celebrate && !celebrated -> {
                celebrated = true
                scope.launch {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    landed()
                    if (still) { settle.snapTo(1f); return@launch }
                    launch { settle.animateTo(1f, CircasoSpring.settle()) }
                    sheen.snapTo(0f)
                    sheen.animateTo(1f, tween(SheenMs, easing = FastOutSlowInEasing))
                }
            }
            // Heard before: no settle, no chime. Undecided (the view model is a few ms from saying): the card waits.
            quiet && !celebrated -> if (still) settle.snapTo(1f) else settle.animateTo(1f, tween(CircasoMotion.QuickMs))
        }
    }

    // The slow motions of the two waits; none under Reduce Motion or in a frozen frame.
    val moving = !still && frozenAt == null
    val breathing = moving && stage == CardStage.Waiting
    val sending = moving && stage == CardStage.Converting
    // Only composed while they run, so a card at rest asks for no frames at all.
    val breath = if (breathing) rememberInfiniteTransition(label = "breath").animateFloat(0f, 1f, infiniteRepeatable(tween(BreathMs, easing = LinearEasing), RepeatMode.Restart), label = "breath") else AtRest
    val sendingSheen = if (sending) rememberInfiniteTransition(label = "sending").animateFloat(0f, 1f, infiniteRepeatable(tween(SendingSheenMs, easing = LinearEasing), RepeatMode.Restart), label = "sending sheen") else AtRest
    val ground = c.bgGrouped
    // A dark ground veils a light silver card only faintly: more of it there, so an ending reads as dimmed.
    val veil = if (c.isDark) DimVeilDark else DimVeil
    val corner = with(LocalDensity.current) { CircasoShapes.lgCorner.toPx() }
    val (edgeWidth, edgeInk) = cardEdge()
    val edgePx = with(LocalDensity.current) { edgeWidth.toPx() }
    val onSecond = secondFirst || (stage == CardStage.Held && progress.targetValue >= 1f)
    val description = if (onSecond == toUsdc) words.usdcDescription else words.mpesaDescription
    val firstLight = if (toUsdc) GreenLight else SilverLight
    val secondLight = if (toUsdc) SilverLight else GreenLight

    /**
     * Each face is recorded into its layer by a modifier of its own, behind a layer of its own, that reads no
     * animation: so the slow loops (the breath, the sending sheen) and the conversion only redraw the recording and
     * the light over it, never record the face again (a new display list a frame for as long as an order waits).
     */
    fun recorder(layer: GraphicsLayer) = Modifier.graphicsLayer().drawWithContent { layer.record { this@drawWithContent.drawContent() } }

    /** The first card, drawn whole while it is (before the pieces leave, or fading out in a cross-fade). */
    val firstDraw = Modifier.fillMaxSize().drawWithCache {
        val path = cardPath(size, corner)
        val band = sheenBand(size, firstLight.band)
        onDrawWithContent {
            drawContent() // the recorder below: draws nothing, re-records only when the face itself changes
            val p = frozenAt ?: progress.value
            val whole = p <= 0f || crossfade || !particles.ready
            if (!whole) return@onDrawWithContent
            val base = if (crossfade) 1f - p else 1f
            firstLayer.alpha = base
            drawLayer(firstLayer)
            if (breathing) drawSheen(path, band, breath.value, firstLight.loop * base)
            val d = dim.value
            if (d > 0f) drawDim(path, ground, d * base, veil)
        }
    }.then(recorder(firstLayer))
    /** The second card, drawn whole once it is (or fading in in a cross-fade), with its light as it lands. */
    val secondDraw = Modifier.fillMaxSize().drawWithCache {
        val path = cardPath(size, corner)
        val band = sheenBand(size, secondLight.band)
        onDrawWithContent {
            drawContent() // the recorder below: draws nothing, re-records only when the face itself changes
            val p = frozenAt ?: progress.value
            val whole = p >= 1f || (crossfade && p > 0f) || (!particles.ready && p > 0f)
            if (!whole) return@onDrawWithContent
            val base = if (p >= 1f) 1f else p
            secondLayer.alpha = base
            drawLayer(secondLayer)
            val d = dim.value
            if (d > 0f) drawDim(path, ground, d * base, veil)
            val pass = sheen.value
            when {
                pass > 0f && pass < 1f -> drawSheen(path, band, pass, 1f)
                sending && p >= 1f -> drawSheen(path, band, sendingSheen.value, secondLight.loop)
            }
        }
    }.then(recorder(secondLayer))

    CardStack(
        modifier.widthIn(max = CardMaxWidth).fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description }
            .graphicsLayer {
                val s = settle.value
                scaleX = s; scaleY = s
                // The waiting card rises and falls by a couple of points: alive, not busy.
                translationY = if (breathing) -2.dp.toPx() * (0.5f - 0.5f * kotlin.math.cos(breath.value * 2f * kotlin.math.PI.toFloat())) else 0f
            },
    ) {
        // The cards' shadow, on its own so it can fade while the card is in pieces.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val p = frozenAt ?: progress.value
                // Fades out as the first pieces leave and back in as the second card forms, never popping.
                val whole = if (crossfade) 1f else 1f - smooth(0f, 0.12f, p) * (1f - smooth(0.45f, 0.85f, p))
                alpha = whole * (1f - 0.6f * dim.value)
                shadowElevation = if (c.isDark) 0f else CardShadow.toPx()
                shape = CircasoShapes.lg
                ambientShadowColor = CardShadowInk
                spotShadowColor = CardShadowInk
            }.background(ground, CircasoShapes.lg),
        )
        if (toUsdc) { MpesaFace(words, firstDraw); UsdcFace(words, secondDraw) } else { UsdcFace(words, firstDraw); MpesaFace(words, secondDraw) }
        // The pieces, over both faces while the conversion runs; then the card's edge, which isn't part of either copy
        // (it would shatter into fragments) and fades with the shadow instead: the outline the second card forms inside.
        Box(Modifier.fillMaxSize().drawWithCache {
            val inset = edgePx / 2f
            val outline = Path().apply { addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(corner - inset))) }
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(edgePx)
            onDrawBehind {
                val p = frozenAt ?: progress.value
                if (!crossfade && particles.ready && p > 0f && p < 1f) particles.draw(this, p)
                // Ready and still waiting: each kind of draw is done once now, unseen, so the first frame of the real
                // thing doesn't pay for setting it up (on the iOS simulator that first frame was a second).
                else if (!crossfade && particles.ready && p <= 0f) particles.warm(this)
                val whole = if (crossfade) 1f else 1f - smooth(0f, 0.12f, p) * (1f - smooth(0.35f, 0.75f, p))
                if (whole > 0f) drawPath(outline, edgeInk, alpha = whole, style = stroke)
            }
        })
    }
}

/**
 * Dims the card drawn under it by [amount] (0..1): its colour drains to grey (a saturation blend, so its light and
 * dark stay and the words keep their contrast), under a light veil of the ground.
 */
private fun DrawScope.drawDim(path: Path, ground: Color, amount: Float, veil: Float) {
    clipPath(path) {
        drawRect(DimGrey, alpha = amount, blendMode = androidx.compose.ui.graphics.BlendMode.Saturation)
        drawRect(ground, alpha = veil * amount)
    }
}

private val DimGrey = Color(0xFF808080)

/** 0 before [from], 1 after [to], eased between. */
private fun smooth(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** A motion that isn't running: still at its start. */
private object AtRest : State<Float> { override val value = 0f }

/** The card's rounded outline at this size, made once per size. */
private fun cardPath(size: Size, corner: Float) = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner))) }

/** A diagonal band of light as wide as half the card, made once per size; [drawSheen] slides it across. */
private fun sheenBand(size: Size, strength: Float): Brush {
    val w = size.width * 0.5f
    return Brush.linearGradient(
        0f to Color.White.copy(alpha = 0f), 0.5f to Color.White.copy(alpha = strength), 1f to Color.White.copy(alpha = 0f),
        start = Offset.Zero, end = Offset(w, w * 0.45f),
    )
}

/** One pass of [band] across the card at [at] (0..1: from just off its left edge to just off its right). */
private fun DrawScope.drawSheen(path: Path, band: Brush, at: Float, alpha: Float) {
    val w = size.width * 0.5f
    val x = -w * 1.2f + (size.width + w * 1.4f) * at
    clipPath(path) { translate(left = x) { drawRect(band, topLeft = Offset(-x, 0f), size = size, alpha = alpha) } }
}

/** Lays its children over each other at one size: the card's shape, or taller when its words need it (large text). */
@Composable
private fun CardStack(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val shape = (width / CardAspect).toInt()
        val loose = Constraints(minWidth = width, maxWidth = width)
        val height = max(shape, measurables.maxOf { it.minIntrinsicHeight(width) }).coerceIn(constraints.minHeight, constraints.maxHeight)
        val exact = Constraints.fixed(width, height)
        val placeables = measurables.map { it.measure(if (height > 0) exact else loose) }
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}

/** A label that changes (from what is to come to what happened) cross-fades; the amount under it stays. */
@Composable
private fun FaceLabel(label: String, color: Color) {
    val t = CircasoTheme.typography
    androidx.compose.animation.Crossfade(label, animationSpec = tween(if (CircasoTheme.reduceMotion) 0 else CircasoMotion.BaseMs), label = "card label") { now ->
        BasicText(now, style = t.footnote.copy(color = color, fontWeight = FontWeight.Medium))
    }
}

/** The M-Pesa card: M-Pesa's own logo, the number the money goes to or comes from, and the shillings. */
@Composable
private fun MpesaFace(words: CardWords, modifier: Modifier) {
    val t = CircasoTheme.typography
    Box(modifier) {
        // The face's own layer: recorded once, so each frame of the animation only redraws it, never its words.
        Box(
            Modifier.fillMaxSize().graphicsLayer().clip(CircasoShapes.lg).background(MpesaBrush).drawBehind {
                // Depth: two faint rings off the right edge, as a bank card has.
                drawCircle(Color.White.copy(alpha = 0.06f), radius = size.height * 0.95f, center = Offset(size.width * 1.05f, size.height * 0.1f))
                drawCircle(Color.White.copy(alpha = 0.05f), radius = size.height * 0.62f, center = Offset(size.width * 1.02f, size.height * 0.95f))
            },
        ) {
            Column(Modifier.fillMaxSize().padding(CircasoSpacing.s5), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                    MpesaLogo(26.dp, reversed = true)
                    words.phone?.let { BasicText(it, Modifier.weight(1f), style = t.subhead.copy(color = RampBrand.OnMpesa, fontFeatureSettings = "tnum", textAlign = TextAlign.End), maxLines = 1) }
                }
                Column(Modifier.padding(top = CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FaceLabel(words.mpesaLabel, RampBrand.OnMpesa)
                    BasicText(words.mpesa, style = t.amountMedium.copy(color = RampBrand.OnMpesa), maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = t.amountMedium.fontSize))
                }
            }
        }
    }
}

/** The USDC card: silver, USDC's blue mark and glow, the network, and the USDC. */
@Composable
private fun UsdcFace(words: CardWords, modifier: Modifier) {
    val t = CircasoTheme.typography
    Box(modifier) {
        Box(
            Modifier.fillMaxSize().graphicsLayer().clip(CircasoShapes.lg).background(SilverBrush).drawWithCache {
                // USDC's blue, glowing from behind its mark's corner; and the light catching the top-left edge.
                val glow = Brush.radialGradient(listOf(RampBrand.UsdcBlue.copy(alpha = 0.20f), RampBrand.UsdcBlue.copy(alpha = 0f)), center = Offset(0f, 0f), radius = size.width * 0.55f)
                val glint = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f)), center = Offset(size.width, size.height), radius = size.width * 0.5f)
                onDrawBehind { drawRect(glow); drawRect(glint) }
            },
        ) {
            Column(Modifier.fillMaxSize().padding(CircasoSpacing.s5), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                    CoinIcon("USDC", size = 34.dp, tint = Color.White, disc = RampBrand.UsdcBlue)
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        BasicText("USDC", style = t.headline.copy(color = RampBrand.OnSilver, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp), maxLines = 1)
                        BasicText(words.chain, style = t.footnote.copy(color = RampBrand.OnSilverSoft), maxLines = 1)
                    }
                }
                Column(Modifier.padding(top = CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FaceLabel(words.usdcLabel, RampBrand.OnSilverSoft)
                    BasicText(words.usdc, style = t.amountMedium.copy(color = RampBrand.OnSilver), maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = t.amountMedium.fontSize))
                }
            }
        }
    }
}

/** The fleck trailing a green piece, and the one trailing a silver piece (a shade darker, so it shows on light ground). */
private val GreenDust = Color(0xFF7FD69B)
private val SilverDust = Color(0xFFAEB4BC)

/**
 * The pieces of the conversion, worked out once per conversion: the card is cut into a grid of small squares. Each
 * square of the first card leaves in a sweep from left to right, shrinking to a speck as it drifts up and to the
 * right and fades, with a fleck of [dustInk] beside it; each square of the second card gathers in from a little way
 * off, from a speck to its full size, in a sweep that follows a beat behind. Squares in place tile the card exactly
 * (whole pixels, the same grid), so the hand-over to the real card at either end is seamless. Drawing a frame only
 * reads these arrays.
 */
private class CardParticles(private val dustInk: Color) {
    // Each face's copy as a shader, made once per copy: every square is a rect filled from it. (Drawing the bitmap
    // itself square by square made iOS turn the whole bitmap into a new Skia image on every call, ~600 a frame.)
    // Snapshot state: a face drawn before the copies were ready (a frozen frame, the first frame) is drawn again once they are.
    private var first by mutableStateOf<Paint?>(null)
    private var second by mutableStateOf<Paint?>(null)
    private var count = 0
    private var x = IntArray(0)
    private var y = IntArray(0)
    private var w = IntArray(0)
    private var h = IntArray(0)
    private var leave = FloatArray(0)
    private var driftX = FloatArray(0)
    private var driftY = FloatArray(0)
    private var arrive = FloatArray(0)
    private var fromX = FloatArray(0)
    private var fromY = FloatArray(0)
    private var dustX = FloatArray(0)
    private var dustY = FloatArray(0)
    private var dustRadius = 0f

    val ready get() = first != null && second != null

    /** What the faces said when they were copied: copied again when that changes. */
    var capturedFor: Any? = null
        private set
    private var cutWidth = 0
    private var cutHeight = 0

    /** Copies both faces and cuts them up (once per size); false if a copy can't be had (the card then cross-fades). */
    suspend fun prepare(firstLayer: GraphicsLayer, secondLayer: GraphicsLayer, dust: Float, words: Any?): Boolean = runCatching {
        // A copy is of the face itself, never of how it was last drawn (faded, greyed).
        firstLayer.alpha = 1f; firstLayer.colorFilter = null
        secondLayer.alpha = 1f; secondLayer.colorFilter = null
        val a = firstLayer.toImageBitmap()
        val b = secondLayer.toImageBitmap()
        if (a.width <= 1 || a.height <= 1 || b.width != a.width || b.height != a.height) return false
        if (a.width != cutWidth || a.height != cutHeight) cut(a.width, a.height)
        dustRadius = dust
        first = facePaint(a); second = facePaint(b)
        capturedFor = words
        true
    }.getOrDefault(false)

    private fun cut(width: Int, height: Int) {
        cutWidth = width; cutHeight = height
        val cols = Columns
        val rows = max(6, (cols * height / width.toFloat()).toInt())
        count = cols * rows
        x = IntArray(count); y = IntArray(count); w = IntArray(count); h = IntArray(count)
        leave = FloatArray(count); driftX = FloatArray(count); driftY = FloatArray(count)
        arrive = FloatArray(count); fromX = FloatArray(count); fromY = FloatArray(count)
        dustX = FloatArray(count); dustY = FloatArray(count)
        // The same scatter every time: a conversion looks like itself, never random noise.
        val random = Random(Seed)
        for (r in 0 until rows) for (col in 0 until cols) {
            val i = r * cols + col
            val left = col * width / cols
            val top = r * height / rows
            x[i] = left; y[i] = top
            w[i] = (col + 1) * width / cols - left
            h[i] = (r + 1) * height / rows - top
            val across = col / (cols - 1f)
            val down = r / (rows - 1f)
            leave[i] = (LeaveFrom + LeaveSweep * across + LeaveJitter * random.nextFloat() - 0.03f * down).coerceAtLeast(0f)
            driftX[i] = width * (0.16f + 0.40f * random.nextFloat())
            driftY[i] = -height * (0.10f + 0.50f * random.nextFloat()) + height * 0.12f * (down - 0.5f)
            arrive[i] = ArriveFrom + ArriveSweep * across + ArriveJitter * random.nextFloat()
            val angle = random.nextFloat() * 2f * kotlin.math.PI.toFloat()
            val reach = width * (0.04f + 0.10f * random.nextFloat())
            fromX[i] = kotlin.math.cos(angle) * reach
            fromY[i] = kotlin.math.sin(angle) * reach
            dustX[i] = 0.35f + 0.5f * random.nextFloat()
            dustY[i] = 0.2f + 0.6f * random.nextFloat()
        }
    }

    /** One frame at [t] (0..1 of the conversion). */
    fun draw(scope: DrawScope, t: Float) {
        val a = first ?: return
        val b = second ?: return
        with(scope) {
            for (i in 0 until count) {
                // The second card's square, gathering into place.
                val arriving = ((t - arrive[i]) / ArriveLife).coerceIn(0f, 1f)
                if (arriving >= 1f) {
                    inPlace(b, i)
                } else if (arriving > 0f) {
                    // Home, exactly, a little before the square is done fading in: nothing settles by a fraction of a pixel.
                    val e = min(1f, (1f - (1f - arriving).pow(3)) / 0.85f)
                    piece(b, i, x[i] + w[i] / 2f + fromX[i] * (1f - e), y[i] + h[i] / 2f + fromY[i] * (1f - e), 0.25f + 0.75f * e, min(1f, arriving * 1.6f))
                }
                // The first card's square, leaving (drawn over the second, which forms behind it).
                val u = ((t - leave[i]) / LeaveLife).coerceIn(0f, 1f)
                if (u >= 1f) continue
                if (u <= 0f) {
                    inPlace(a, i)
                    continue
                }
                val go = u.pow(1.6f)
                val cx = x[i] + w[i] / 2f + driftX[i] * go
                val cy = y[i] + h[i] / 2f + driftY[i] * go
                val fade = 1f - u.pow(1.3f)
                piece(a, i, cx, cy, 1f - 0.78f * u, fade)
                // A fleck of dust trailing it: round, lighter, gone a little sooner.
                if (u > 0.12f) drawCircle(dustInk, radius = dustRadius * (1f - 0.5f * u), center = Offset(cx - driftX[i] * go * dustX[i] * 0.4f, cy - driftY[i] * go * dustY[i] * 0.5f), alpha = 0.7f * fade * (1f - u))
            }
        }
    }

    /**
     * Draws one square of each face the ways a frame will, where it can't be seen: the first card's square exactly
     * over itself, the second's and a fleck at the faintest alpha.
     */
    fun warm(scope: DrawScope) {
        val a = first ?: return
        val b = second ?: return
        if (count == 0) return
        with(scope) {
            inPlace(a, 0)
            piece(a, 0, x[0] + w[0] / 2f, y[0] + h[0] / 2f, 1f, 1f)
            piece(b, 0, x[0] + w[0] / 2f, y[0] + h[0] / 2f, 1f, Unseen)
            drawCircle(dustInk, radius = dustRadius, center = Offset(x[0] + w[0] / 2f, y[0] + h[0] / 2f), alpha = Unseen)
        }
    }

    /** Square [i] of [face] where it belongs on the card. */
    private fun DrawScope.inPlace(face: Paint, i: Int) {
        face.alpha = 1f
        drawIntoCanvas { it.drawRect(x[i].toFloat(), y[i].toFloat(), (x[i] + w[i]).toFloat(), (y[i] + h[i]).toFloat(), face) }
    }

    /**
     * Square [i] of [face], centred on ([cx], [cy]) at [scale]: placed to the sub-pixel, so slow pieces glide. The
     * canvas is moved so the square's own part of the shader travels with it.
     */
    private fun DrawScope.piece(face: Paint, i: Int, cx: Float, cy: Float, scale: Float, alpha: Float) {
        face.alpha = alpha
        translate(cx, cy) {
            scale(scale, scale, Offset.Zero) {
                translate(-(x[i] + w[i] / 2f), -(y[i] + h[i] / 2f)) {
                    drawIntoCanvas { it.drawRect(x[i].toFloat(), y[i].toFloat(), (x[i] + w[i]).toFloat(), (y[i] + h[i]).toFloat(), face) }
                }
            }
        }
    }

    /**
     * A face's copy as a fill: the image as a shader, **without anti-aliasing**, so squares sharing an edge meet
     * exactly at any scale (anti-aliased edges each blend half a pixel with the ground, a lattice of seams).
     */
    private fun facePaint(image: androidx.compose.ui.graphics.ImageBitmap) = Paint().apply {
        isAntiAlias = false
        shader = ImageShader(image)
    }

    companion object {
        /** The grid across the card: about 15 pt squares on a phone. */
        const val Columns = 22
        const val Seed = 2_026_11_23
        // When the first card's squares leave (fractions of the conversion) and how long each takes to be gone.
        const val LeaveFrom = 0.02f
        const val LeaveSweep = 0.34f
        const val LeaveJitter = 0.10f
        const val LeaveLife = 0.40f
        // When the second card's squares arrive, and how long each takes to settle: the last lands at exactly 1.
        const val ArriveFrom = 0.38f
        const val ArriveSweep = 0.26f
        const val ArriveJitter = 0.06f
        const val ArriveLife = 0.30f
        /** Faint enough not to be seen, strong enough not to be skipped. */
        const val Unseen = 0.01f
    }
}
