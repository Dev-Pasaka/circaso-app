package com.circaso.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * iOS-style type scale from the design tokens. The system font is used on both platforms
 * (SF on iOS, Roboto on Android) so text metrics match what each user expects.
 * Amount styles use tabular figures so digits do not jiggle while counting.
 */
@Immutable
data class CircasoTypography(
    val largeTitle: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val title3: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val callout: TextStyle,
    val subhead: TextStyle,
    val footnote: TextStyle,
    val caption1: TextStyle,
    val caption2: TextStyle,
    val amountHero: TextStyle,
    val amountLarge: TextStyle,
    val amountMedium: TextStyle,
    val amountSmall: TextStyle,
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Float, tabular: Boolean = false) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
    fontFeatureSettings = if (tabular) "tnum" else null,
)

val DefaultTypography = CircasoTypography(
    largeTitle = style(34, 41, FontWeight.Bold, 0.37f),
    title1 = style(28, 34, FontWeight.Bold, 0.36f),
    title2 = style(22, 28, FontWeight.Bold, -0.26f),
    title3 = style(20, 25, FontWeight.SemiBold, -0.45f),
    headline = style(17, 22, FontWeight.SemiBold, -0.43f),
    body = style(17, 22, FontWeight.Normal, -0.43f),
    callout = style(16, 21, FontWeight.Normal, -0.31f),
    subhead = style(15, 20, FontWeight.Normal, -0.23f),
    footnote = style(13, 18, FontWeight.Normal, -0.08f),
    caption1 = style(12, 16, FontWeight.Normal, 0f),
    caption2 = style(11, 13, FontWeight.Normal, 0.06f),
    amountHero = style(56, 60, FontWeight.SemiBold, -0.8f, tabular = true),
    amountLarge = style(40, 44, FontWeight.SemiBold, -0.5f, tabular = true),
    amountMedium = style(28, 32, FontWeight.SemiBold, -0.3f, tabular = true),
    amountSmall = style(17, 22, FontWeight.SemiBold, -0.2f, tabular = true),
)
