package com.circaso.feature.wallet.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CoinIcon
import com.circaso.designsystem.components.brandEdge
import com.circaso.designsystem.motion.CircasoSpring
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.ramp.presentation.RampBrand
import com.circaso.feature.ramp.presentation.SilverBrush
import com.circaso.feature.ramp.presentation.CardStack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Where a payment to an address stands, as its card shows it. */
internal enum class SendPhase { Ready, Approving, Sending, Delivered, Failed }

/**
 * The payment as an object (2026-11-24): the silver USDC card Add Money lands and Cash Out sells, here carrying what
 * leaves the wallet. It appears on Review and stays put through the PIN and the sent page, so the payment reads as one
 * thing moving through its states rather than a new screen each time. It rises in on a spring; breathes while the PIN
 * is up; lifts and catches the light once when the payment goes (with the `Confirm` haptic), and again when the chain
 * confirms it. Reduce Motion: none of that, only the words changing. Brand colours as Add Money's ([RampBrand]).
 */
@Composable
internal fun SendCard(amount: String, chain: String, address: String, phase: SendPhase, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val still = CircasoTheme.reduceMotion
    val haptics = LocalHapticFeedback.current
    val (edge, edgeInk) = brandEdge()
    // Arrival: from a touch smaller and lower, settling without a visible bounce.
    val rise = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { if (!still) rise.animateTo(1f, CircasoSpring.settle()) }
    // One pass of light and a small lift that settles back, each time the payment moves on (handed to the network,
    // then delivered). Only delivery is celebrated with the `Confirm` haptic: the chain can still refuse a payment that
    // was only handed over, so that gets a light tick.
    val sheen = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }
    var felt by remember { mutableStateOf(phase) }
    LaunchedEffect(phase) {
        val moved = (phase == SendPhase.Sending || phase == SendPhase.Delivered) && felt != phase
        felt = phase
        if (!moved) return@LaunchedEffect
        haptics.performHapticFeedback(if (phase == SendPhase.Delivered) HapticFeedbackType.Confirm else HapticFeedbackType.TextHandleMove)
        if (still) return@LaunchedEffect
        sheen.snapTo(0f)
        coroutineScope {
            launch {
                lift.animateTo(1f, spring(stiffness = Spring.StiffnessMedium))
                lift.animateTo(0f, CircasoSpring.settle())
            }
            launch { sheen.animateTo(1f, tween(SheenMs, easing = FastOutSlowInEasing)) }
        }
    }
    // Waiting on the PIN: the sibling cards' breath (a 2 dp rise on a cosine), composed only while it runs.
    val breath = if (phase == SendPhase.Approving && !still) {
        rememberInfiniteTransition(label = "send-card").animateFloat(0f, 1f, infiniteRepeatable(tween(BreathMs, easing = LinearEasing), RepeatMode.Restart), label = "breath")
    } else null
    val failed = phase == SendPhase.Failed
    val dark = c.isDark
    val ground = c.bgGrouped
    val label = when (phase) {
        SendPhase.Ready -> "You send"
        SendPhase.Approving -> "Waiting for your PIN"
        SendPhase.Sending -> "Sending"
        SendPhase.Delivered -> "Delivered"
        SendPhase.Failed -> "Didn't go through"
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CardStack(
            Modifier.widthIn(max = SendCardMaxWidth).fillMaxWidth()
                .graphicsLayer {
                    val r = rise.value
                    val scale = (0.94f + 0.06f * r) * (1f + 0.015f * lift.value)
                    scaleX = scale; scaleY = scale
                    val b = breath?.value ?: 0f
                    translationY = (1f - r) * 24.dp.toPx() - lift.value * 6.dp.toPx() - 2.dp.toPx() * (0.5f - 0.5f * cos(b * 2f * PI.toFloat()))
                    alpha = r
                    // As Add Money's card: a shadow on light ground only.
                    shadowElevation = if (dark) 0f else SendCardShadow.toPx()
                    shape = CircasoShapes.lg
                    ambientShadowColor = CardShadowInk
                    spotShadowColor = CardShadowInk
                    clip = true
                }
                .background(SilverBrush).border(edge, edgeInk, CircasoShapes.lg)
                .drawWithCache {
                    // USDC's blue glowing from behind its mark, and the light on the far corner, as on Add Money's card.
                    val glow = Brush.radialGradient(listOf(RampBrand.UsdcBlue.copy(alpha = 0.20f), RampBrand.UsdcBlue.copy(alpha = 0f)), center = Offset.Zero, radius = size.width * 0.55f)
                    val glint = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f)), center = Offset(size.width, size.height), radius = size.width * 0.5f)
                    // The pass of light, made once per size and moved across by translation in the draw phase.
                    val bandWidth = size.width * 0.45f
                    val band = Brush.linearGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f)), start = Offset.Zero, end = Offset(bandWidth, size.height))
                    val veil = ground.copy(alpha = if (dark) DimVeilDark else DimVeil)
                    onDrawWithContent {
                        drawRect(glow); drawRect(glint)
                        drawContent()
                        val at = sheen.value
                        if (at > 0f && at < 1f) {
                            val x = -bandWidth + at * (size.width + bandWidth)
                            translate(left = x) { drawRect(band, size = Size(bandWidth, size.height)) }
                        }
                        // A payment that didn't go: the colour drains to grey under a light veil, as the ramp's cards dim.
                        if (failed) {
                            drawRect(DimGrey, blendMode = BlendMode.Saturation)
                            drawRect(veil)
                        }
                    }
                }
                .clearAndSetSemantics { contentDescription = "USDC on $chain. $label $amount, to ${spokenAddress(address)}" },
        ) {
            Column(Modifier.fillMaxSize().padding(CircasoSpacing.s5), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                    CoinIcon("USDC", size = 34.dp, tint = Color.White, disc = RampBrand.UsdcBlue)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        BasicText("USDC", style = t.headline.copy(color = RampBrand.OnSilver, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp), maxLines = 1)
                        BasicText(chain, style = t.footnote.copy(color = RampBrand.OnSilverSoft), maxLines = 1)
                    }
                    PhaseMark(phase)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AnimatedContent(label, transitionSpec = { fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs)) }, label = "send-card-label") { l ->
                        BasicText(l.uppercase(), style = t.caption1.copy(color = RampBrand.OnSilverSoft, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp), maxLines = 1)
                    }
                    BasicText(amount, style = t.amountMedium.copy(color = RampBrand.OnSilver, fontFeatureSettings = "tnum"), maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = t.amountMedium.fontSize))
                    BasicText("To ${shortGrouped(address)}", style = t.footnote.copy(color = RampBrand.OnSilverSoft, fontFamily = FontFamily.Monospace), maxLines = 1)
                }
            }
        }
    }
}

/** The card's corner mark: nothing while it's only being reviewed; a pulse while it goes; a tick once it's there. */
@Composable
private fun PhaseMark(phase: SendPhase) {
    val t = CircasoTheme.typography
    AnimatedContent(phase, transitionSpec = { fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs)) }, label = "send-card-mark") { p ->
        when (p) {
            SendPhase.Ready -> Box(Modifier.size(1.dp))
            SendPhase.Approving, SendPhase.Sending -> Row(
                Modifier.clip(CircasoShapes.pill).background(Color.White.copy(alpha = 0.6f)).padding(horizontal = CircasoSpacing.s2, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PulseDot()
                BasicText(if (p == SendPhase.Approving) "Approving" else "On its way", style = t.caption1.copy(color = RampBrand.OnSilver, fontWeight = FontWeight.SemiBold), maxLines = 1)
            }
            SendPhase.Delivered -> Box(Modifier.size(26.dp).clip(CircasoShapes.circle).background(RampBrand.OnSilver), contentAlignment = Alignment.Center) {
                CircasoIconImage(CircasoIcon.Check, Color.White, Modifier.size(15.dp), strokeWidth = 2.6f)
            }
            SendPhase.Failed -> Box(Modifier.size(26.dp).clip(CircasoShapes.circle).background(RampBrand.OnSilverSoft), contentAlignment = Alignment.Center) {
                CircasoIconImage(CircasoIcon.Close, Color.White, Modifier.size(13.dp), strokeWidth = 2.6f)
            }
        }
    }
}

@Composable
private fun PulseDot() {
    val still = CircasoTheme.reduceMotion
    val pulse = if (still) null else rememberInfiniteTransition(label = "pulse").animateFloat(0.35f, 1f, infiniteRepeatable(tween(CircasoMotion.MomentMs), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.size(7.dp).graphicsLayer { alpha = pulse?.value ?: 1f }.clip(CircasoShapes.circle).background(RampBrand.UsdcBlue))
}

/**
 * An address in groups of four, for reading it against the other wallet: an EVM address's `0x` stays on its first
 * group (`0x7a3F 0000 … 4b00`, eleven groups in all); a Solana address is grouped from its start.
 */
internal fun addressGroups(address: String): List<String> {
    val a = address.trim()
    if (a.isEmpty()) return emptyList()
    val prefix = if (a.startsWith("0x") || a.startsWith("0X")) 2 else 0
    val rest = a.drop(prefix)
    val groups = rest.chunked(4).toMutableList()
    if (prefix > 0) { if (groups.isEmpty()) groups.add(a.take(prefix)) else groups[0] = a.take(prefix) + groups[0] }
    return groups
}

/** The first and last groups with the middle left out: `0x7a3F … 4b00`. */
internal fun shortGrouped(address: String): String {
    val g = addressGroups(address)
    return if (g.size <= 3) g.joinToString(" ") else "${g.first()} … ${g.last()}"
}

/** What a screen reader says for an address: its kind and its first and last groups, never forty characters. */
internal fun spokenAddress(address: String): String {
    val g = addressGroups(address)
    if (g.size <= 2) return address
    return "the address starting ${g.first().spaced()} and ending ${g.last().spaced()}"
}

private fun String.spaced() = toCharArray().joinToString(" ")

/**
 * The whole address, grouped in fours, the first and last groups in ink and the rest quieter (design: the parts people
 * check against the other wallet). Wraps by group at any text size; fixed-width so the columns line up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroupedAddress(address: String, modifier: Modifier = Modifier, style: TextStyle = CircasoTheme.typography.callout) {
    val c = CircasoTheme.colors
    val groups = addressGroups(address)
    FlowRow(
        modifier.clearAndSetSemantics { contentDescription = spokenAddress(address) },
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        groups.forEachIndexed { i, g ->
            val ends = i == 0 || i == groups.lastIndex
            BasicText(g, style = style.copy(fontFamily = FontFamily.Monospace, color = if (ends) c.ink else c.inkSecondary, fontWeight = if (ends) FontWeight.SemiBold else FontWeight.Normal))
        }
    }
}

/** About a bank card held up to the screen: the same size as Add Money's. */
private val SendCardMaxWidth = 340.dp
private val SendCardShadow = 10.dp
private val CardShadowInk = Color.Black.copy(alpha = 0.22f)
private const val BreathMs = CircasoMotion.MomentMs * 3
private const val SheenMs = CircasoMotion.MomentMs
private const val DimVeil = 0.10f
private const val DimVeilDark = 0.24f
private val DimGrey = Color(0xFF808080)
