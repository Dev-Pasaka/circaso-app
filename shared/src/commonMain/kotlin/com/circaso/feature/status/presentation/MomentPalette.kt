package com.circaso.feature.status.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.designsystem.theme.LightColors
import com.circaso.feature.status.domain.MomentTextStyle

/** One text-moment background: its id (what travels in the encrypted body), how it paints, and the text on it. */
class MomentSwatch(val id: String, val brush: Brush, val ink: Color, val name: String)

/**
 * Text moment backgrounds (Pasaka, 2026-11-03: "many colours", replacing the monochrome four). These are the person's
 * own content, like a photo, not app chrome, so they are a fixed palette rather than theme tokens: the same moment
 * looks the same to every viewer in every theme. The first four keep the original ids so older moments still read.
 */
object MomentPalette {
    private fun solid(id: String, hex: Long, name: String, dark: Boolean = true) = MomentSwatch(id, SolidColor(Color(hex)), if (dark) Color.White else Color(0xFF0B0B0C), name)
    private fun grad(id: String, a: Long, b: Long, name: String, dark: Boolean = true) =
        MomentSwatch(id, Brush.linearGradient(listOf(Color(a), Color(b))), if (dark) Color.White else Color(0xFF0B0B0C), name)

    val entries: List<MomentSwatch> = listOf(
        MomentSwatch("Ink", SolidColor(LightColors.ink), LightColors.bg, "Ink"),
        MomentSwatch("Metal", Brush.linearGradient(listOf(LightColors.moneyCardSheen, LightColors.moneyCard, LightColors.moneyCard, LightColors.moneyCardSheen)), LightColors.onMoney, "Metal"),
        MomentSwatch("Paper", SolidColor(LightColors.fill), LightColors.ink, "Paper"),
        MomentSwatch("Graphite", SolidColor(LightColors.inkSecondary), LightColors.bg, "Graphite"),
        // Solids
        solid("c-red", 0xFFC8102E, "Red"), solid("c-tomato", 0xFFE5533D, "Tomato"), solid("c-coral", 0xFFFF7A59, "Coral"),
        solid("c-orange", 0xFFF2994A, "Orange"), solid("c-amber", 0xFFF5B700, "Amber", dark = false), solid("c-lemon", 0xFFF7E26B, "Lemon", dark = false),
        solid("c-lime", 0xFF9BCB3C, "Lime", dark = false), solid("c-green", 0xFF2E9E5B, "Green"), solid("c-forest", 0xFF1F5E3B, "Forest"),
        solid("c-mint", 0xFF7DD3B0, "Mint", dark = false), solid("c-teal", 0xFF0F8B8D, "Teal"), solid("c-sky", 0xFF56B4E9, "Sky"),
        solid("c-blue", 0xFF1F6FEB, "Blue"), solid("c-navy", 0xFF16325C, "Navy"), solid("c-indigo", 0xFF4B3FCF, "Indigo"),
        solid("c-violet", 0xFF7B4FD6, "Violet"), solid("c-purple", 0xFF8E2C9E, "Purple"), solid("c-plum", 0xFF5B1F4A, "Plum"),
        solid("c-magenta", 0xFFD6247F, "Magenta"), solid("c-pink", 0xFFF48FB1, "Pink", dark = false), solid("c-rose", 0xFFB5475B, "Rose"),
        solid("c-brown", 0xFF7A4B2A, "Brown"), solid("c-sand", 0xFFE8D5B5, "Sand", dark = false), solid("c-olive", 0xFF6B6B2E, "Olive"),
        // Gradients
        grad("g-sunset", 0xFFFF7E5F, 0xFFFEB47B, "Sunset"), grad("g-dusk", 0xFF2B5876, 0xFF4E4376, "Dusk"),
        grad("g-ocean", 0xFF2193B0, 0xFF6DD5ED, "Ocean"), grad("g-forest", 0xFF134E5E, 0xFF71B280, "Rainforest"),
        grad("g-berry", 0xFF8E2DE2, 0xFF4A00E0, "Berry"), grad("g-flame", 0xFFF12711, 0xFFF5AF19, "Flame"),
        grad("g-peach", 0xFFFFDDE1, 0xFFEE9CA7, "Peach", dark = false), grad("g-savanna", 0xFFDE6262, 0xFFFFB88C, "Savanna"),
        grad("g-night", 0xFF0F2027, 0xFF2C5364, "Night"), grad("g-lagoon", 0xFF43C6AC, 0xFF191654, "Lagoon"),
        grad("g-candy", 0xFFFC5C7D, 0xFF6A82FB, "Candy"), grad("g-gold", 0xFFF7971E, 0xFFFFD200, "Gold", dark = false),
    )

    private val byId = entries.associateBy { it.id }
    fun of(id: String?): MomentSwatch = id?.let { byId[it] ?: byId[it.replaceFirstChar { c -> c.uppercase() }] } ?: entries.first()
}

/** The fonts a text moment can use (Pasaka, 2026-11-03), cycled by the Aa button. */
@Composable
fun momentFont(style: MomentTextStyle): Pair<FontFamily?, FontWeight> = when (style) {
    MomentTextStyle.Bold -> null to FontWeight.Bold
    MomentTextStyle.Regular -> null to FontWeight.Normal
    MomentTextStyle.Serif -> FontFamily.Serif to FontWeight.Bold
    MomentTextStyle.Rounded -> CircasoTheme.typography.amountHero.fontFamily to FontWeight.SemiBold
    MomentTextStyle.Mono -> FontFamily.Monospace to FontWeight.Medium
    MomentTextStyle.Script -> FontFamily.Cursive to FontWeight.Normal
}

val MomentTextStyle.label: String
    get() = when (this) {
        MomentTextStyle.Bold -> "Bold"; MomentTextStyle.Regular -> "Light"; MomentTextStyle.Serif -> "Serif"
        MomentTextStyle.Rounded -> "Rounded"; MomentTextStyle.Mono -> "Typewriter"; MomentTextStyle.Script -> "Script"
    }
