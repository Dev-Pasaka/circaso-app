package com.circaso.feature.vault.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.theme.CircasoColors
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.presentation.CardRatio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The PIN Vault's look (2026-11-11, Pasaka: "more professional, more premium"). One object carries it: the vault, in
 * the same brushed metal as the money cards (`moneyCard` / `moneyCardSheen`, graphite in light, silver in dark), with a
 * dial of twelve marks round a lock. The intro shows the dial on its own, the saved vault is a card in the wallet's
 * proportions, and the shown PIN keeps to plain surfaces so nothing competes with the digits.
 */

/** The metal: the money cards' gradient, lit at the top-left and bottom-right corners. */
private fun metal(c: CircasoColors) = Brush.linearGradient(
    0f to c.moneyCardSheen, 0.38f to c.moneyCard, 0.72f to c.moneyCard, 1f to c.moneyCardSheen,
    start = Offset.Zero, end = Offset.Infinite,
)

/** Secondary ink on the metal: full strength in the high-contrast themes, as the money cards do. */
private fun softOnMetal(c: CircasoColors) = if (c.isHighContrast) c.onMoney else c.onMoney.copy(alpha = 0.72f)

/** The icon for how the vault opens. */
internal fun unlockIcon(unlock: String) = when (unlock) {
    "Face ID" -> CircasoIcon.FaceId
    "Screen Lock" -> CircasoIcon.Lock
    else -> CircasoIcon.Fingerprint
}

/**
 * The vault's mark: a metal dial, twelve marks round a lock. Decorative; the text beside it says what it is.
 * [onMetal] draws it stamped into the card it sits on (a faint disc of the card's ink) rather than as its own metal.
 */
@Composable
internal fun VaultEmblem(size: Dp, modifier: Modifier = Modifier, onMetal: Boolean = false) {
    val c = CircasoTheme.colors
    val mark = c.onMoney
    Box(
        modifier.size(size)
            .shadow(if (onMetal || c.isDark) 0.dp else size / 12, CircasoShapes.circle, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(CircasoShapes.circle)
            .then(if (onMetal) Modifier.background(mark.copy(alpha = 0.12f)) else Modifier.background(metal(c)))
            .then(if (c.isHighContrast && !onMetal) Modifier.border(1.dp, c.separator, CircasoShapes.circle) else Modifier)
            .drawBehind {
                val r = this.size.minDimension / 2f
                val centre = Offset(r, r)
                // The light catching the top-left edge, as on the cards.
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), center = Offset.Zero, radius = r * 1.6f))
                drawCircle(mark.copy(alpha = 0.28f), radius = r * 0.64f, center = centre, style = Stroke(width = r * 0.035f))
                repeat(12) { i ->
                    val a = (i * 30.0 - 90.0) * PI / 180.0
                    val inner = r * (if (i % 3 == 0) 0.74f else 0.78f)
                    val outer = r * 0.86f
                    drawLine(
                        mark.copy(alpha = if (i % 3 == 0) 0.55f else 0.3f),
                        Offset(centre.x + inner * cos(a).toFloat(), centre.y + inner * sin(a).toFloat()),
                        Offset(centre.x + outer * cos(a).toFloat(), centre.y + outer * sin(a).toFloat()),
                        strokeWidth = r * 0.04f, cap = StrokeCap.Round,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        CircasoIconImage(CircasoIcon.Lock, mark, Modifier.size(size * 0.34f))
    }
}

/**
 * The saved vault: a metal card in a bank card's proportions (it grows with large text rather than clipping), with
 * its status, what opens it and when it was last saved. Six sealed dots stand for the PIN; right after a save they
 * press in one by one ([sealing]), which is the moment the person sees the vault close.
 */
@Composable
internal fun VaultCard(unlock: String, savedOn: String?, sealing: Boolean, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val ink = c.onMoney
    val soft = softOnMetal(c)
    val tracked = TextUnit(1.2f, TextUnitType.Sp)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = maxWidth / CardRatio)
                .shadow(if (c.isDark) 0.dp else 10.dp, CircasoShapes.lg, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                .clip(CircasoShapes.lg)
                .background(metal(c))
                .drawBehind { drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = if (c.isDark) 0.3f else 0.14f), Color.Transparent), center = Offset.Zero, radius = size.maxDimension * 0.8f)) }
                .border(if (c.isHighContrast) 1.dp else 0.5.dp, if (c.isHighContrast) c.separator else Color.White.copy(alpha = 0.18f), CircasoShapes.lg)
                .padding(CircasoSpacing.s5)
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull("PIN Vault", "Protected", "Opens with $unlock", savedOn?.let { "Last saved $it" }).joinToString(". ")
                },
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                VaultEmblem(40.dp, onMetal = true)
                Column(Modifier.weight(1f)) {
                    BasicText("PIN Vault", style = t.headline.copy(color = ink))
                    BasicText("Circaso", style = t.caption1.copy(color = soft))
                }
                Row(
                    Modifier.clip(CircasoShapes.pill).background(ink.copy(alpha = 0.14f)).padding(horizontal = CircasoSpacing.s2, vertical = CircasoSpacing.s1),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1),
                ) {
                    CircasoIconImage(CircasoIcon.Check, ink, Modifier.size(12.dp))
                    BasicText("Protected", style = t.caption1.copy(color = ink, fontWeight = FontWeight.SemiBold))
                }
            }
            SealedDots(sealing, ink, Modifier.padding(vertical = CircasoSpacing.s5))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText("OPENS WITH", style = t.caption2.copy(color = soft, letterSpacing = tracked))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                        CircasoIconImage(unlockIcon(unlock), ink, Modifier.size(16.dp))
                        BasicText(unlock, style = t.subhead.copy(color = ink, fontWeight = FontWeight.SemiBold))
                    }
                }
                if (savedOn != null) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText("LAST SAVED", style = t.caption2.copy(color = soft, letterSpacing = tracked))
                    BasicText(savedOn, style = t.subhead.copy(color = ink, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
                }
            }
        }
    }
}

/** Six dots in two threes, the PIN sealed away. [sealing] presses them in one after another, once. */
@Composable
private fun SealedDots(sealing: Boolean, ink: Color, modifier: Modifier = Modifier) {
    val still = CircasoTheme.reduceMotion
    val play = remember { sealing && !still }
    val dots = remember { List(6) { Animatable(if (play) 0f else 1f) } }
    if (play) LaunchedEffect(Unit) {
        dots.forEachIndexed { i, a ->
            launch { delay(CircasoMotion.BaseMs + i * SealStaggerMs); a.animateTo(1f, tween(CircasoMotion.BaseMs)) }
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s6), verticalAlignment = Alignment.CenterVertically) {
        (0..1).forEach { group ->
            Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                (0..2).forEach { j ->
                    val a = dots[group * 3 + j]
                    Box(
                        Modifier.size(12.dp)
                            .graphicsLayer {
                                val v = a.value
                                alpha = v
                                // From a little larger, as if pressed into the metal.
                                val s = 1f + 0.6f * (1f - v)
                                scaleX = s; scaleY = s
                            }
                            .clip(CircasoShapes.circle).background(ink.copy(alpha = 0.9f)),
                    )
                }
            }
        }
    }
}

private const val SealStaggerMs = 45L

/**
 * A ring that empties as the shown PIN's minute runs out. It sweeps smoothly; under reduced motion it steps once a
 * second with the text beside it. Decorative: the "Hides in" text carries the time for screen readers.
 */
@Composable
internal fun CountdownRing(hidesAt: Long?, totalMs: Long, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val c = CircasoTheme.colors
    val still = CircasoTheme.reduceMotion
    fun left() = hidesAt?.let { ((it - nowMillis()).coerceIn(0, totalMs)).toFloat() / totalMs } ?: 0f
    val progress = remember(hidesAt) { Animatable(left()) }
    LaunchedEffect(hidesAt, still) {
        if (hidesAt == null) return@LaunchedEffect
        if (still) {
            while (progress.value > 0f) { progress.snapTo(left()); delay(1_000) }
        } else {
            val remaining = (hidesAt - nowMillis()).coerceIn(0, totalMs).toInt()
            progress.animateTo(0f, tween(remaining, easing = LinearEasing))
        }
    }
    val track = c.fillStrong
    val arc = c.ink
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val w = this.size.minDimension * 0.14f
        val inset = w / 2f
        val box = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(w))
        drawArc(arc, -90f, 360f * progress.value, false, Offset(inset, inset), box, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** "2026-11-11T09:30:00Z" → "11 Nov 2026"; the raw date when it isn't in that shape. */
internal fun vaultDate(iso: String): String {
    val day = iso.substringBefore('T')
    val parts = day.split('-')
    val month = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 } ?: return day
    val d = parts.getOrNull(2)?.toIntOrNull() ?: return day
    return "$d ${Months[month - 1]} ${parts[0]}"
}

private val Months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
