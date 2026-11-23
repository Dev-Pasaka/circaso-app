package com.circaso.feature.ramp.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.core.common.maskedForDisplay
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.MpesaLogo
import com.circaso.designsystem.components.brandEdge
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.ramp.domain.RampOrder
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.wallet.domain.chainName

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
internal val MpesaBrush = Brush.linearGradient(listOf(RampBrand.MpesaLight, RampBrand.MpesaDeep), start = Offset.Zero, end = Offset.Infinite)

/** Brushed silver: light at the corners, deeper across the middle. */
internal val SilverBrush = Brush.linearGradient(
    0f to RampBrand.SilverLight, 0.42f to RampBrand.Silver, 0.7f to RampBrand.SilverDeep, 1f to RampBrand.Silver,
    start = Offset.Zero, end = Offset.Infinite,
)

/** The card's edge: a hairline on the light ground, a firm line in the high-contrast themes. */
@Composable
internal fun cardEdge(): Pair<Dp, Color> = brandEdge()

/**
 * Add Money's way to pay, on the amount page: M-Pesa's own green card in place of a plain row, with its logo, the
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
        MpesaLogo(22.dp, reversed = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(phone ?: "Your verified number", style = t.headline.copy(color = RampBrand.OnMpesa, fontFeatureSettings = "tnum"))
            BasicText("M-Pesa asks for your PIN on this number", style = t.footnote.copy(color = RampBrand.OnMpesa))
        }
        CircasoIconImage(CircasoIcon.Check, RampBrand.OnMpesa, Modifier.size(SelectedGlyph), strokeWidth = 2.4f)
    }
}

/**
 * M-Pesa's own green chip, its logo and the number: under what is paid on Add Money's review ("Pay with M-Pesa, from
 * …"), and where the shillings go on Cash Out's ([saying] "Paid to M-Pesa", [numberWord] "on").
 */
@Composable
internal fun MpesaChip(maskedPhone: String?, modifier: Modifier = Modifier, saying: String = "Pay with M-Pesa", numberWord: String = "from") {
    val t = CircasoTheme.typography
    val (edge, edgeInk) = cardEdge()
    val phone = maskedPhone?.maskedForDisplay()?.replace(' ', ' ')
    Row(
        modifier.clip(CircasoShapes.pill).background(MpesaBrush).border(edge, edgeInk, CircasoShapes.pill)
            .padding(start = CircasoSpacing.s3, end = CircasoSpacing.s3, top = 7.dp, bottom = 7.dp)
            .clearAndSetSemantics { contentDescription = saying + (phone?.let { ", $numberWord $it" } ?: "") },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        MpesaLogo(15.dp, reversed = true)
        BasicText(phone ?: "Your verified number", style = t.footnote.copy(color = RampBrand.OnMpesa, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

// ---- Add Money's conversion: an M-Pesa card becoming USDC ([CardConversion] does the drawing) ----------------------

internal fun cardStageOf(state: RampOrderState, everPaid: Boolean = false): CardStage = when (state) {
    RampOrderState.Paid, RampOrderState.Releasing -> CardStage.Converting
    RampOrderState.Completed -> CardStage.Landed
    RampOrderState.Cancelled, RampOrderState.Expired, RampOrderState.Failed -> if (everPaid) CardStage.Held else CardStage.Ended
    RampOrderState.Review -> CardStage.Held
    // Before the payment; or anything this version can't place (a sale's states never reach this page), held once paid.
    else -> if (everPaid) CardStage.Held else CardStage.Waiting
}

/** What the two cards say for a top-up, from the order and the words the page uses for its figures. */
internal fun cardWords(order: RampOrder, look: OrderLook): CardWords {
    val (paying, getting) = figureLabels(order.state, look)
    val phone = order.maskedPhone.takeIf { it.isNotBlank() }?.maskedForDisplay()
    val landed = order.state == RampOrderState.Completed
    val chain = chainName(order.blockchain)
    val getLabel = if (landed) "Added from M-Pesa" else getting
    return CardWords(
        mpesaLabel = paying, mpesa = order.pay.text, phone = phone,
        usdcLabel = getLabel, usdc = order.receive.text, chain = chain,
        mpesaDescription = listOfNotNull("M-Pesa", "$paying ${order.pay.text}", phone?.let { "from $it" }).joinToString(", "),
        usdcDescription = listOf("USDC on $chain", "$getLabel ${order.receive.text}").joinToString(", "),
    )
}

/**
 * Add Money's hero (Pasaka, 2026-11-23: "a conversion from cash… the green card with the M-Pesa logo disintegrating,
 * changing into a silver USDC card"): the M-Pesa card waits for the PIN, breathing; once M-Pesa has the money it comes
 * apart into the silver USDC card; when the USDC lands the card settles with one pass of light, the haptic and the
 * chime. An ending before the payment dims the green card; one in review, or any ending after the payment, holds.
 */
@Composable
internal fun ConversionCard(
    order: RampOrder, look: OrderLook, everPaid: Boolean, celebrate: Boolean, quiet: Boolean, onLanded: () -> Unit,
    modifier: Modifier = Modifier, frozenAt: Float? = null,
) = CardConversion(
    ConversionDirection.MpesaToUsdc, cardStageOf(order.state, everPaid), cardWords(order, look), heldOnSecond = everPaid,
    celebrate = celebrate, quiet = quiet, onLanded = onLanded, landingKey = order.orderId, modifier = modifier, frozenAt = frozenAt,
)
