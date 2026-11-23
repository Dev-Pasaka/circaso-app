package com.circaso.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import circaso.shared.generated.resources.Res
import circaso.shared.generated.resources.mpesa_logo
import circaso.shared.generated.resources.mpesa_logo_reversed
import com.circaso.core.common.maskedForDisplay
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import org.jetbrains.compose.resources.painterResource

// M-Pesa's own logo wherever the app names M-Pesa: Add Money, Cash Out and the wallet's history all use these.

/**
 * M-Pesa's own logo, Safaricom's artwork as published in its Brand Toolkit (`assets/brand/mpesa/`, made into vectors by
 * `scripts/make_mpesa_logo.py` with every path unchanged; Pasaka, 2026-11-23: "use the real M-Pesa logo… the branding
 * is important"). [reversed] is Safaricom's version for green ground (white letters and phone), for M-Pesa's green
 * card and chip; otherwise the logo as published (green letters, grey phone), which only ever sits on white
 * ([MpesaTag], [MpesaDisc]). Never recoloured, stretched or cropped: [height] sizes it, its width follows. It doesn't
 * follow the reader's text size, because it is a brand's mark, not words to read; screen readers hear "M-Pesa" from
 * whatever holds it.
 */
@Composable
fun MpesaLogo(height: Dp, modifier: Modifier = Modifier, reversed: Boolean = false) {
    Image(
        painterResource(if (reversed) Res.drawable.mpesa_logo_reversed else Res.drawable.mpesa_logo), contentDescription = null,
        modifier = modifier.height(height).aspectRatio(MpesaLogoAspect), contentScale = ContentScale.Fit,
    )
}

/** The logo's own width to height (511.70 × 132.97 in Safaricom's file). */
const val MpesaLogoAspect = 3.848f

/** A brand surface's edge: a hairline on the light ground, a firm line in the high-contrast themes. */
@Composable
fun brandEdge(): Pair<Dp, Color> {
    val c = CircasoTheme.colors
    return when {
        c.isHighContrast -> 1.5.dp to c.ink
        c.isDark -> 0.5.dp to Color.White.copy(alpha = 0.14f)
        else -> 0.5.dp to Color.Black.copy(alpha = 0.10f)
    }
}

/**
 * The logo as published, on the white it is made for, with clear space all round (a third of its height across, a
 * quarter above and below): where M-Pesa is named on the app's own ground, in every theme.
 */
@Composable
fun MpesaTag(height: Dp, modifier: Modifier = Modifier) {
    val (edge, edgeInk) = brandEdge()
    Box(
        modifier.clip(CircasoShapes.xs).background(Color.White).border(edge, edgeInk, CircasoShapes.xs)
            .padding(horizontal = height * 0.34f, vertical = height * 0.24f),
        contentAlignment = Alignment.Center,
    ) { MpesaLogo(height) }
}

/**
 * M-Pesa in a row's round place (where a person's photo or a coin goes): the logo on a white disc, as wide as fits
 * with clear space inside the circle (the circle's own curve gives it room at the corners). In place of the old "M"
 * letter disc.
 */
@Composable
fun MpesaDisc(modifier: Modifier = Modifier, size: Dp = CircasoSizes.avatarMd) {
    val (edge, edgeInk) = brandEdge()
    Box(
        modifier.size(size).clip(CircasoShapes.circle).background(Color.White).border(edge, edgeInk, CircasoShapes.circle),
        contentAlignment = Alignment.Center,
    ) { MpesaLogo(size * DiscLogoWidth / MpesaLogoAspect) }
}

/** How much of the disc the logo spans. */
private const val DiscLogoWidth = 0.86f

/**
 * A detail row naming M-Pesa: [key] ("To", "From"), then the logo with the number. Read as one line ("To M-Pesa,
 * +254 7•• ••• 678").
 */
@Composable
fun MpesaDetailRow(key: String, maskedPhone: String?) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val number = maskedPhone?.takeIf { it.isNotBlank() }?.maskedForDisplay() ?: "your verified number"
    DetailRow(key, Modifier.clearAndSetSemantics { contentDescription = "$key M-Pesa, $number" }) {
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
            MpesaTag(13.dp)
            BasicText(number, style = t.subhead.copy(color = c.ink, fontFeatureSettings = "tnum"), maxLines = 1)
        }
    }
}
