package com.circaso.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/**
 * `.cx-amount`: the currency small and secondary, the number big in the rounded face with tabular figures, a sub line
 * under it ("249.46 USDC"). Left-aligned on the wallet, centred in sheets.
 */
@Composable
fun AmountDisplay(currency: String, number: String, modifier: Modifier = Modifier, sub: String? = null, size: AmountSize = AmountSize.Large, centred: Boolean = true, tone: AmountTone = AmountTone.Ink) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val numStyle = when (size) {
        AmountSize.Hero -> t.amountHero
        AmountSize.Large -> t.amountLarge
        AmountSize.Medium -> t.amountMedium
        AmountSize.Small -> t.amountSmall
    }
    val curStyle = when (size) {
        AmountSize.Hero, AmountSize.Large -> t.title2
        AmountSize.Medium -> t.title3
        AmountSize.Small -> t.headline
    }
    val ink = when (tone) { AmountTone.Ink -> c.ink; AmountTone.Money -> c.money }
    Column(modifier, horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            BasicText(currency, style = curStyle.copy(fontWeight = FontWeight.SemiBold, color = if (tone == AmountTone.Money) c.money else c.inkSecondary), modifier = Modifier.padding(bottom = 6.dp))
            BasicText(number, style = numStyle.copy(color = ink))
        }
        if (sub != null) BasicText(sub, style = t.footnote.copy(color = c.inkSecondary, textAlign = if (centred) TextAlign.Center else TextAlign.Start))
    }
}

enum class AmountTone { Ink, Money }

/** The `.act` disc's size, shared with the wallet's loading skeleton. */
val ActionButtonSize = 58.dp

/** `.act`: the wallet's four round actions, a 58 dp surface disc with a 13 pt label under it. */
@Composable
fun ActionButton(icon: CircasoIcon, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        modifier.alpha(if (enabled) 1f else 0.45f).pressable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(ActionButtonSize).clip(CircasoShapes.circle).background(c.surface), contentAlignment = Alignment.Center) {
            CircasoIconImage(icon, c.ink, Modifier.size(22.dp))
        }
        BasicText(label, style = t.footnote.copy(color = c.ink, fontWeight = FontWeight.Medium))
    }
}

/** `.cx-seg`: a pill of equal buttons on `fill`, the chosen one lifted on `fillSelected`. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(modifier.fillMaxWidth().clip(CircasoShapes.pill).background(c.fill).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 32.dp).clip(CircasoShapes.pill).background(if (on) c.fillSelected else c.fill.copy(alpha = 0f))
                    .pressable(role = Role.Tab, onClick = { onSelect(i) }).semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { BasicText(label, style = t.callout.copy(color = c.ink, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center), modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) }
        }
    }
}

/**
 * `.cx-money`: a payment in a chat, drawn as metal (design: "money arriving is marked by material, not colour").
 *
 * - **Received** is titanium: `money-card` blended into `money-card-sheen` from the corners, with `on-money` ink.
 *   Graphite on a light phone, brushed silver on a dark one, always the opposite of the background.
 * - **Sent** is the same material in the background's own tone (decision 2026-10-30, the user: "shiny white for
 *   sent", the other way round on a dark phone): pearl white on light, polished dark grey on dark, with `ink` text and
 *   a soft lift instead of an outline.
 *
 * Head ("Received" / "Sent"), the amount in the rounded face, the note, then the coin amount and time in the foot.
 */
@Composable
fun MoneyCard(
    head: String,
    amount: String,
    foot: String,
    outgoing: Boolean,
    modifier: Modifier = Modifier,
    note: String? = null,
    pending: Boolean = false,
    failed: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** The coin it moved in: its mark sits at the card's top right, beside "Sent" / "Received". */
    symbol: String? = null,
    /** Read the head out when it changes (Sending → Sent), for the card the person just sent. */
    announce: Boolean = false,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val received = !outgoing && !failed
    val ink = if (received) c.onMoney else c.ink
    // The high-contrast themes keep head and foot at full strength (design `-hc`); a dark Sent card gets ink too,
    // because its lit corner is too bright for the secondary grey.
    val soft = when {
        c.isHighContrast -> ink
        received -> c.onMoney.copy(alpha = 0.82f)
        c.isDark -> c.ink.copy(alpha = 0.78f)
        else -> c.inkSecondary
    }
    val metal = when {
        failed -> Brush.linearGradient(listOf(c.fill, c.fill))
        received -> Brush.linearGradient(
            0f to c.moneyCardSheen, 0.38f to c.moneyCard, 0.72f to c.moneyCard, 1f to c.moneyCardSheen,
            start = Offset.Zero, end = Offset.Infinite,
        )
        c.isDark -> Brush.linearGradient(
            0f to Color(0xFF48484C), 0.4f to Color(0xFF2C2C2E), 0.75f to Color(0xFF252527), 1f to Color(0xFF3A3A3D),
            start = Offset.Zero, end = Offset.Infinite,
        )
        else -> Brush.linearGradient(
            0f to Color(0xFFFFFFFF), 0.45f to Color(0xFFF4F4F6), 0.8f to Color(0xFFE9E9EC), 1f to Color(0xFFFAFAFB),
            start = Offset.Zero, end = Offset.Infinite,
        )
    }
    // The light catching the top-left corner, as on the wallet cards.
    // The light catching the top-left corner, as on the wallet cards; not on a dark Sent card, where it would wash out the text.
    val glintAlpha = when { received && !c.isDark -> 0.16f; received -> 0.28f; c.isDark -> 0f; else -> 0.28f }
    val glint = Brush.radialGradient(listOf(Color.White.copy(alpha = glintAlpha), Color.White.copy(alpha = 0f)), center = Offset.Zero, radius = 520f)
    val lift = if (failed) 0.dp else if (c.isDark) 2.dp else 6.dp
    // Sending → Sent settles in rather than jumping; at once under reduced motion.
    val still = CircasoTheme.reduceMotion
    val shown by androidx.compose.animation.core.animateFloatAsState(if (pending) 0.7f else 1f, androidx.compose.animation.core.tween(if (still) 0 else CircasoMotion.BaseMs), label = "pending")
    Column(
        modifier.widthIn(max = 264.dp).fillMaxWidth(0.8f).alpha(shown)
            .shadow(lift, CircasoShapes.lg, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
            .clip(CircasoShapes.lg).background(metal).then(if (failed || glintAlpha == 0f) Modifier else Modifier.background(glint))
            .then(if (c.isHighContrast && !received) Modifier.border(1.dp, c.separator, CircasoShapes.lg) else Modifier)
            .then(if (onClick != null) Modifier.pressable(role = Role.Button, onLongClick = onLongClick, onClick = onClick) else Modifier)
            .padding(horizontal = CircasoSpacing.s5, vertical = CircasoSpacing.s4)
            .semantics {
                contentDescription = listOfNotNull(head, amount, note, foot).joinToString(", ")
                if (announce) liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.animation.Crossfade(head, Modifier.weight(1f), androidx.compose.animation.core.tween(if (still) 0 else CircasoMotion.BaseMs), label = "head") { h ->
                BasicText(h, style = t.footnote.copy(color = soft, fontWeight = FontWeight.SemiBold))
            }
            // On the metal the disc is the card's own ink, faint, so it reads on titanium, pearl and graphite alike.
            if (symbol != null) CoinIcon(symbol, size = CircasoSizes.avatarSm, tint = if (failed) c.inkSecondary else soft, disc = ink.copy(alpha = 0.12f))
        }
        BasicText(amount, style = t.amountMedium.copy(color = if (failed) c.inkSecondary else ink), maxLines = 1)
        if (!note.isNullOrBlank()) BasicText(note, style = t.callout.copy(color = ink))
        BasicText(foot, style = t.footnote.copy(color = soft))
    }
}

/** A `.cx-row-amount`: the end column of a wallet row, the amount in the rounded face and the time under it. */
@Composable
fun RowAmount(amount: String, meta: String?, incoming: Boolean, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(amount, style = t.headline.copy(color = if (incoming) c.money else c.ink, letterSpacing = (-0.2).sp))
        if (meta != null) BasicText(meta, style = t.caption1.copy(color = c.inkSecondary))
    }
}
