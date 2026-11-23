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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circaso.core.common.maskedForDisplay
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CoinIcon
import com.circaso.designsystem.motion.CircasoSpring
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.ramp.domain.RampOrder
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.wallet.domain.chainName
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * The brands' own colours, the same in every theme: M-Pesa's green, USDC's blue and the silver of the card the USDC
 * arrives on. They are never the app's accent (Circaso is monochrome): green appears only as M-Pesa's own card and
 * mark, blue only as USDC's. White on the green is at least 4.5:1 at its lightest corner; the silver's ink is 10:1.
 */
internal object RampBrand {
    val MpesaLight = Color(0xFF0F8039)
    val MpesaDeep = Color(0xFF0A6A30)
    val OnMpesa = Color.White
    val UsdcBlue = Color(0xFF2775CA)
    val SilverLight = Color(0xFFF8F9FB)
    val Silver = Color(0xFFE3E6EA)
    val SilverDeep = Color(0xFFCBD0D6)
    val OnSilver = Color(0xFF1C1C1E)
    val OnSilverSoft = Color(0xFF48484D)
}

/** M-Pesa's green as a surface: lit from the top left, as the app's metal is. */
private val MpesaBrush = Brush.linearGradient(listOf(RampBrand.MpesaLight, RampBrand.MpesaDeep), start = Offset.Zero, end = Offset.Infinite)

/** Brushed silver: light at the corners, deeper across the middle. */
private val SilverBrush = Brush.linearGradient(
    0f to RampBrand.SilverLight, 0.42f to RampBrand.Silver, 0.7f to RampBrand.SilverDeep, 1f to RampBrand.Silver,
    start = Offset.Zero, end = Offset.Infinite,
)

/**
 * M-Pesa named in its own colours: "M-PESA" set in green on a white tag. Safaricom's logo isn't bundled (it needs their
 * artwork and permission), and an imitation of it would look counterfeit next to the real green, so the name is set
 * in type instead (decision 2026-11-23, after the design review). [height] sizes it; it doesn't follow the reader's
 * text size, because it is a brand's label, not words to read.
 */
@Composable
internal fun MpesaWordmark(height: Dp, modifier: Modifier = Modifier) {
    val glyph = with(LocalDensity.current) { (height * 0.5f).toSp() }
    Box(modifier.height(height).clip(CircasoShapes.xs).background(RampBrand.OnMpesa).padding(horizontal = height * 0.28f), contentAlignment = Alignment.Center) {
        BasicText("M-PESA", style = CircasoTheme.typography.headline.copy(color = RampBrand.MpesaDeep, fontWeight = FontWeight.Black, fontSize = glyph, lineHeight = glyph, letterSpacing = 0.6.sp), maxLines = 1)
    }
}

/** The card's edge: a hairline on the light ground, a firm line in the high-contrast themes. */
@Composable
private fun cardEdge(): Pair<Dp, Color> {
    val c = CircasoTheme.colors
    return when {
        c.isHighContrast -> 1.5.dp to c.ink
        c.isDark -> 0.5.dp to Color.White.copy(alpha = 0.14f)
        else -> 0.5.dp to Color.Black.copy(alpha = 0.10f)
    }
}

/**
 * Add Money's way to pay, on the amount page: M-Pesa's own green card in place of a plain row, with its mark, the
 * number the request goes to and the tick that says it is chosen (the only way to pay today).
 */
@Composable
internal fun MpesaMethodCard(maskedPhone: String?, modifier: Modifier = Modifier) {
    val t = CircasoTheme.typography
    val (edge, edgeInk) = cardEdge()
    val phone = maskedPhone?.maskedForDisplay()
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(CircasoShapes.md).background(MpesaBrush).border(edge, edgeInk, CircasoShapes.md)
            .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3)
            .clearAndSetSemantics { contentDescription = "Pay with M-Pesa, " + (phone ?: "your verified number"); selected = true },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        MpesaWordmark(26.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(phone ?: "Your verified number", style = t.headline.copy(color = RampBrand.OnMpesa, fontFeatureSettings = "tnum"))
            BasicText("M-Pesa asks for your PIN on this number", style = t.footnote.copy(color = RampBrand.OnMpesa))
        }
        CircasoIconImage(CircasoIcon.Check, RampBrand.OnMpesa, Modifier.size(SelectedGlyph), strokeWidth = 2.4f)
    }
}

/** On the review, under what is paid: who it is paid with, as M-Pesa's own green chip. */
@Composable
internal fun MpesaChip(maskedPhone: String?, modifier: Modifier = Modifier) {
    val t = CircasoTheme.typography
    val (edge, edgeInk) = cardEdge()
    val phone = maskedPhone?.maskedForDisplay()?.replace(' ', ' ')
    Row(
        modifier.clip(CircasoShapes.pill).background(MpesaBrush).border(edge, edgeInk, CircasoShapes.pill)
            .padding(start = 6.dp, end = CircasoSpacing.s3, top = 6.dp, bottom = 6.dp)
            .clearAndSetSemantics { contentDescription = "Pay with M-Pesa" + (phone?.let { ", from $it" } ?: "") },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        MpesaWordmark(18.dp)
        BasicText(phone ?: "Your verified number", style = t.footnote.copy(color = RampBrand.OnMpesa, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

// ---- the conversion: an M-Pesa card becoming USDC ------------------------------------------------------------------

/** Where the order's card is: which card shows, and how it behaves. */
internal enum class CardStage {
    /** The M-Pesa card, waiting on the person's PIN (it breathes). */
    Waiting,
    /** M-Pesa has the money: the green card has turned into the silver USDC card, which is on its way. */
    Converting,
    /** The USDC is in the wallet: the silver card settles. */
    Landed,
    /** It didn't go through: the M-Pesa card dims, nothing turns. */
    Ended,
    /**
     * The money may have moved and nothing is settled (in review, an ending after the payment, a state this version
     * doesn't know once paid): a conversion under way finishes, then whichever card shows holds; nothing celebrates,
     * and nothing turns back to green as if the shillings had gone home.
     */
    Held,
}

internal fun cardStageOf(state: RampOrderState, everPaid: Boolean = false): CardStage = when (state) {
    RampOrderState.Paid, RampOrderState.Releasing -> CardStage.Converting
    RampOrderState.Completed -> CardStage.Landed
    RampOrderState.Cancelled, RampOrderState.Expired, RampOrderState.Failed -> if (everPaid) CardStage.Held else CardStage.Ended
    RampOrderState.Review -> CardStage.Held
    // Before the payment; or anything this version can't place (a sale's states never reach this page), held once paid.
    else -> if (everPaid) CardStage.Held else CardStage.Waiting
}

/**
 * A held card doesn't dim: the money may have moved and isn't lost, so the card stays as it is and the badge and
 * words under it say what happened.
 */
@Suppress("UNUSED_PARAMETER")
internal fun heldDim(state: RampOrderState): Float = 0f

/** What the two cards say, from the order and the words the page uses for its figures. */
internal data class CardWords(
    val payLabel: String, val pay: String, val phone: String?,
    val getLabel: String, val get: String, val chain: String,
    /** What a screen reader hears for the card on screen (the state itself is announced by the title). */
    val mpesaDescription: String, val usdcDescription: String,
)

internal fun cardWords(order: RampOrder, look: OrderLook): CardWords {
    val (paying, getting) = figureLabels(order.state, look)
    val phone = order.maskedPhone.takeIf { it.isNotBlank() }?.maskedForDisplay()
    val landed = order.state == RampOrderState.Completed
    val chain = chainName(order.blockchain)
    val getLabel = if (landed) "Added from M-Pesa" else getting
    return CardWords(
        payLabel = paying, pay = order.pay.text, phone = phone,
        getLabel = getLabel, get = order.receive.text, chain = chain,
        mpesaDescription = listOfNotNull("M-Pesa", "$paying ${order.pay.text}", phone?.let { "from $it" }).joinToString(", "),
        usdcDescription = listOf("USDC on $chain", "$getLabel ${order.receive.text}").joinToString(", "),
    )
}

/** The whole conversion, from the first piece leaving the green card to the last one landing on the silver. */
internal const val ConvertMs = CircasoMotion.MomentMs + CircasoMotion.BaseMs + CircasoMotion.QuickMs
/** One pass of light across the silver card as it settles. */
internal const val SheenMs = CircasoMotion.MomentMs
/** The silver card waits for the USDC a touch smaller, and settles to its size when it lands. */
private const val SettleFrom = 0.965f
/** A dimmed card: its colour gives way to grey, under a light veil of the ground (white text stays above 4.5:1). */
private const val DimVeil = 0.10f
/** How far the card's light shows on each: a hint on the green, a polish on the silver. */
private const val GreenSheen = 0.10f
private const val SilverSheen = 0.42f
/** The waiting card's slow breath and the sending card's slow sheen, as periods. */
private const val BreathMs = CircasoMotion.MomentMs * 3
private const val SendingSheenMs = CircasoMotion.MomentMs * 4
/** A card is a bank card's shape, never wider than this. */
private val CardMaxWidth = 340.dp
private const val CardAspect = 1.586f

/**
 * The order page's hero (Pasaka, 2026-11-23: "a conversion from cash… the green card with the M-Pesa logo
 * disintegrating, changing into a silver USDC card"). The M-Pesa card waits for the PIN, breathing; once M-Pesa has the
 * money it comes apart into small pieces that drift off and fade while the silver USDC card forms behind them, piece by
 * piece, left to right; when the USDC lands the card settles with one pass of light, the `Confirm` haptic and
 * [onLanded] (the chime) on the same frame. An ending that didn't go through calmly dims the green card; one in review
 * holds.
 *
 * **How it is drawn:** both faces are ordinary composables recorded into [GraphicsLayer]s. While the M-Pesa card waits
 * each is copied once to a bitmap held as an image shader, the pieces are worked out once ([CardParticles]), and every
 * frame only fills small rects from those shaders under a transform: no allocation, no layout, no recomposition (the
 * progress is read in the draw phase only).
 * It can be interrupted: an order first seen already paid or completed shows the silver card at once, and a state
 * that moves on mid-way lets the conversion finish before the card settles. Under **Reduce Motion** the green card
 * cross-fades to the silver, with no pieces, no breath and no sheen. Screen readers hear the card in words.
 *
 * [celebrate] is the view model's word that this order's landing hasn't been heard on this phone yet. [frozenAt]
 * draws one frame of the conversion (0..1) and holds it, for the gallery.
 */
@Composable
internal fun ConversionCard(
    order: RampOrder, look: OrderLook, everPaid: Boolean, celebrate: Boolean, quiet: Boolean, onLanded: () -> Unit,
    modifier: Modifier = Modifier, frozenAt: Float? = null,
) {
    val c = CircasoTheme.colors
    val still = CircasoTheme.reduceMotion
    val stage = cardStageOf(order.state, everPaid)
    val live = cardWords(order, look)
    // Mid-conversion the faces keep the words they were copied with (an order that skips straight from waiting to
    // completed changes them), so no copy is taken again on the frame it starts; they catch up once it's whole.
    var midway by remember { mutableStateOf(false) }
    val shownWords = remember { arrayOf(live) }
    if (!midway) shownWords[0] = live
    val words = shownWords[0]
    val silverFirst = stage == CardStage.Converting || stage == CardStage.Landed || (stage == CardStage.Held && everPaid)
    // 0 is the M-Pesa card, 1 the USDC card; read only while drawing.
    val progress = remember { Animatable(if (silverFirst) 1f else 0f) }
    val dimTo = when (stage) { CardStage.Ended -> 1f; CardStage.Held -> heldDim(order.state); else -> 0f }
    val dim = remember { Animatable(dimTo) }
    // Opened on a completed top-up, the card is already whole: it only settles (from a touch smaller) when it is new.
    val openedLanded = remember { stage == CardStage.Landed }
    val settle = remember { Animatable(if (silverFirst && !still) SettleFrom else 1f) }
    val sheen = remember { Animatable(0f) }
    // How the progress is drawn: in pieces, or as a cross-fade (Reduce Motion, going back, or no bitmaps).
    var crossfade by remember { mutableStateOf(still) }
    val greenLayer = rememberGraphicsLayer()
    val silverLayer = rememberGraphicsLayer()
    val particles = remember { CardParticles() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val landed by rememberUpdatedState(onLanded)
    val dust = with(LocalDensity.current) { 1.4.dp.toPx() }

    // Both faces are copied ahead, while the M-Pesa card waits (and again if their words change), so the frame the
    // payment is confirmed on starts the conversion at once instead of reading back two bitmaps first.
    LaunchedEffect(words, stage == CardStage.Waiting) {
        if (stage != CardStage.Waiting || still || frozenAt != null) return@LaunchedEffect
        withFrameNanos {}; withFrameNanos {} // both faces drawn, and so recorded
        particles.prepare(greenLayer, silverLayer, dust, words)
    }

    /** Runs (or finishes) the conversion from wherever it is. */
    suspend fun convert() {
        if (progress.value >= 1f) return
        if (!particles.ready || (progress.value <= 0f && particles.capturedFor != words)) {
            withFrameNanos {}
            crossfade = still || !particles.prepare(greenLayer, silverLayer, dust, words)
        } else if (progress.value <= 0f) crossfade = still
        val left = 1f - progress.value
        midway = true
        try {
            coroutineScope {
                // Smaller while it waits for the USDC, in step with the pieces gathering.
                launch { if (!still) settle.animateTo(SettleFrom, tween((ConvertMs * left).toInt(), easing = FastOutSlowInEasing)) }
                progress.animateTo(1f, if (crossfade) tween((CircasoMotion.BaseMs * left).toInt()) else tween((ConvertMs * left).toInt(), easing = LinearEasing))
            }
        } finally {
            if (progress.value >= 1f) midway = false
        }
    }
    // The card follows the order. A conversion under way always finishes, whatever comes next.
    LaunchedEffect(stage, dimTo, frozenAt) {
        if (frozenAt != null) {
            withFrameNanos {} // both faces have been drawn (and so recorded) once
            crossfade = still || !particles.prepare(greenLayer, silverLayer, dust, words)
            progress.snapTo(frozenAt)
            return@LaunchedEffect
        }
        when (stage) {
            CardStage.Waiting -> {
                launch { dim.animateTo(0f, tween(CircasoMotion.BaseMs)) }
                if (progress.value > 0f) { crossfade = true; progress.animateTo(0f, tween(CircasoMotion.BaseMs)) }
            }
            CardStage.Converting, CardStage.Landed -> {
                launch { dim.animateTo(0f, tween(CircasoMotion.BaseMs)) }
                convert()
            }
            CardStage.Ended -> {
                // Nothing came apart for money that never moved: back to the green card, gently, and dimmed.
                if (progress.value > 0f) { crossfade = true; launch { progress.animateTo(0f, tween(CircasoMotion.BaseMs)) } }
                launch { settle.animateTo(1f, tween(CircasoMotion.BaseMs)) }
                dim.animateTo(dimTo, tween(if (still) CircasoMotion.BaseMs else CircasoMotion.MomentMs))
            }
            CardStage.Held -> {
                // A conversion caught mid-way finishes (never left in pieces), then the card holds at its size.
                if (progress.value > 0f) convert()
                launch { settle.animateTo(1f, tween(CircasoMotion.BaseMs)) }
                dim.animateTo(dimTo, tween(if (still) CircasoMotion.BaseMs else CircasoMotion.MomentMs))
            }
        }
    }
    // The landing: once the card is whole, it settles. Heard and felt only when the view model says so, and run in
    // its own scope so the view model clearing [celebrate] (once rung) never cuts the pass of light short.
    var celebrated by remember(order.orderId) { mutableStateOf(false) }
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
    val corner = with(LocalDensity.current) { CircasoShapes.lgCorner.toPx() }
    val (edgeWidth, edgeInk) = cardEdge()
    val edgePx = with(LocalDensity.current) { edgeWidth.toPx() }
    val description = if (stage == CardStage.Converting || stage == CardStage.Landed || (stage == CardStage.Held && everPaid)) words.usdcDescription else words.mpesaDescription

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
                // Fades out as the first pieces leave and back in as the silver forms, never popping.
                val whole = if (crossfade) 1f else 1f - smooth(0f, 0.12f, p) * (1f - smooth(0.45f, 0.85f, p))
                alpha = whole * (1f - 0.6f * dim.value)
                shadowElevation = if (c.isDark) 0f else 10.dp.toPx()
                shape = CircasoShapes.lg
                ambientShadowColor = Color.Black.copy(alpha = 0.22f)
                spotShadowColor = Color.Black.copy(alpha = 0.22f)
            }.background(ground, CircasoShapes.lg),
        )
        MpesaFace(words, Modifier.fillMaxSize().drawWithCache {
            val path = cardPath(size, corner)
            val band = sheenBand(size, GreenSheen)
            onDrawWithContent {
                greenLayer.record { this@onDrawWithContent.drawContent() }
                val p = frozenAt ?: progress.value
                val whole = p <= 0f || crossfade || !particles.ready
                if (!whole) return@onDrawWithContent
                val base = if (crossfade) 1f - p else 1f
                greenLayer.alpha = base
                drawLayer(greenLayer)
                if (breathing) drawSheen(path, band, breath.value, base)
                val d = dim.value
                if (d > 0f) drawDim(path, ground, d * base)
            }
        })
        UsdcFace(words, Modifier.fillMaxSize().drawWithCache {
            val path = cardPath(size, corner)
            val band = sheenBand(size, SilverSheen)
            onDrawWithContent {
                silverLayer.record { this@onDrawWithContent.drawContent() }
                val p = frozenAt ?: progress.value
                val whole = p >= 1f || (crossfade && p > 0f) || (!particles.ready && p > 0f)
                if (!whole) return@onDrawWithContent
                val base = if (p >= 1f) 1f else p
                silverLayer.alpha = base
                drawLayer(silverLayer)
                val d = dim.value
                if (d > 0f) drawDim(path, ground, d * base)
                val pass = sheen.value
                when {
                    pass > 0f && pass < 1f -> drawSheen(path, band, pass, 1f)
                    sending && p >= 1f -> drawSheen(path, band, sendingSheen.value, 0.55f)
                }
            }
        })
        // The pieces, over both faces while the conversion runs; then the card's edge, which isn't part of either copy
        // (it would shatter into fragments) and fades with the shadow instead: the outline the silver forms inside.
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
 * dark stay and white words keep their contrast), under a light veil of the ground.
 */
private fun DrawScope.drawDim(path: Path, ground: Color, amount: Float) {
    clipPath(path) {
        drawRect(DimGrey, alpha = amount, blendMode = androidx.compose.ui.graphics.BlendMode.Saturation)
        drawRect(ground, alpha = DimVeil * amount)
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

/** The M-Pesa card: its mark and name, the number the request went to, and what is paid in shillings. */
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
                    MpesaWordmark(28.dp)
                    words.phone?.let { BasicText(it, Modifier.weight(1f), style = t.subhead.copy(color = RampBrand.OnMpesa, fontFeatureSettings = "tnum", textAlign = androidx.compose.ui.text.style.TextAlign.End), maxLines = 1) }
                }
                Column(Modifier.padding(top = CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(words.payLabel, style = t.footnote.copy(color = RampBrand.OnMpesa, fontWeight = FontWeight.Medium))
                    BasicText(words.pay, style = t.amountMedium.copy(color = RampBrand.OnMpesa), maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = t.amountMedium.fontSize))
                }
            }
        }
    }
}

/** The USDC card: silver, USDC's blue mark and glow, the network, and the USDC that arrives. */
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
                    androidx.compose.animation.Crossfade(words.getLabel, animationSpec = tween(if (CircasoTheme.reduceMotion) 0 else CircasoMotion.BaseMs), label = "usdc label") { label ->
                        BasicText(label, style = t.footnote.copy(color = RampBrand.OnSilverSoft, fontWeight = FontWeight.Medium))
                    }
                    BasicText(words.get, style = t.amountMedium.copy(color = RampBrand.OnSilver), maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = t.amountMedium.fontSize))
                }
            }
        }
    }
}

/**
 * The pieces of the conversion, worked out once per conversion: the card is cut into a grid of small squares. Each
 * green square leaves in a sweep from left to right, shrinking to a speck as it drifts up and to the right and fades,
 * with a fleck of dust beside it; each silver square gathers in from a little way off, from a speck to its full
 * size, in a sweep that follows a beat behind. Squares in place tile the card exactly (whole pixels, the same grid),
 * so the hand-over to the real card at either end is seamless. Drawing a frame only reads these arrays.
 */
private class CardParticles {
    // Each face's copy as a shader, made once per copy: every square is a rect filled from it. (Drawing the bitmap
    // itself square by square made iOS turn the whole bitmap into a new Skia image on every call, ~600 a frame.)
    // Snapshot state: a face drawn before the copies were ready (a frozen frame, the first frame) is drawn again once they are.
    private var green by mutableStateOf<Paint?>(null)
    private var silver by mutableStateOf<Paint?>(null)
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

    val ready get() = green != null && silver != null

    /** What the faces said when they were copied: copied again when that changes. */
    var capturedFor: Any? = null
        private set
    private var cutWidth = 0
    private var cutHeight = 0

    /** Copies both faces and cuts them up (once per size); false if a copy can't be had (the card then cross-fades). */
    suspend fun prepare(greenLayer: GraphicsLayer, silverLayer: GraphicsLayer, dust: Float, words: Any?): Boolean = runCatching {
        // A copy is of the face itself, never of how it was last drawn (faded, greyed).
        greenLayer.alpha = 1f; greenLayer.colorFilter = null
        silverLayer.alpha = 1f; silverLayer.colorFilter = null
        val g = greenLayer.toImageBitmap()
        val s = silverLayer.toImageBitmap()
        if (g.width <= 1 || g.height <= 1 || s.width != g.width || s.height != g.height) return false
        if (g.width != cutWidth || g.height != cutHeight) cut(g.width, g.height)
        dustRadius = dust
        green = facePaint(g); silver = facePaint(s)
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
        val g = green ?: return
        val s = silver ?: return
        with(scope) {
            for (i in 0 until count) {
                // The silver square, gathering into place.
                val a = ((t - arrive[i]) / ArriveLife).coerceIn(0f, 1f)
                if (a >= 1f) {
                    inPlace(s, i)
                } else if (a > 0f) {
                    // Home, exactly, a little before the square is done fading in: nothing settles by a fraction of a pixel.
                    val e = min(1f, (1f - (1f - a).pow(3)) / 0.85f)
                    piece(s, i, x[i] + w[i] / 2f + fromX[i] * (1f - e), y[i] + h[i] / 2f + fromY[i] * (1f - e), 0.25f + 0.75f * e, min(1f, a * 1.6f))
                }
                // The green square, leaving (drawn over the silver, which forms behind it).
                val u = ((t - leave[i]) / LeaveLife).coerceIn(0f, 1f)
                if (u >= 1f) continue
                if (u <= 0f) {
                    inPlace(g, i)
                    continue
                }
                val go = u.pow(1.6f)
                val cx = x[i] + w[i] / 2f + driftX[i] * go
                val cy = y[i] + h[i] / 2f + driftY[i] * go
                val fade = 1f - u.pow(1.3f)
                piece(g, i, cx, cy, 1f - 0.78f * u, fade)
                // A fleck of dust trailing it: round, lighter, gone a little sooner.
                if (u > 0.12f) drawCircle(DustInk, radius = dustRadius * (1f - 0.5f * u), center = Offset(cx - driftX[i] * go * dustX[i] * 0.4f, cy - driftY[i] * go * dustY[i] * 0.5f), alpha = 0.7f * fade * (1f - u))
            }
        }
    }

    /**
     * Draws one square of each face the ways a frame will, where it can't be seen: the green square exactly over
     * itself, the silver one and a fleck at the faintest alpha.
     */
    fun warm(scope: DrawScope) {
        val g = green ?: return
        val s = silver ?: return
        if (count == 0) return
        with(scope) {
            inPlace(g, 0)
            piece(g, 0, x[0] + w[0] / 2f, y[0] + h[0] / 2f, 1f, 1f)
            piece(s, 0, x[0] + w[0] / 2f, y[0] + h[0] / 2f, 1f, Unseen)
            drawCircle(DustInk, radius = dustRadius, center = Offset(x[0] + w[0] / 2f, y[0] + h[0] / 2f), alpha = Unseen)
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
        // When the green squares leave (fractions of the conversion) and how long each takes to be gone.
        const val LeaveFrom = 0.02f
        const val LeaveSweep = 0.34f
        const val LeaveJitter = 0.10f
        const val LeaveLife = 0.40f
        // When the silver squares arrive, and how long each takes to settle: the last lands at exactly 1.
        const val ArriveFrom = 0.38f
        const val ArriveSweep = 0.26f
        const val ArriveJitter = 0.06f
        const val ArriveLife = 0.30f
        val DustInk = Color(0xFF7FD69B)
        /** Faint enough not to be seen, strong enough not to be skipped. */
        const val Unseen = 0.01f
    }
}
