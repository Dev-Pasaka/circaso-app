package com.circaso.feature.messaging.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.messaging.domain.SecurityCode
import com.circaso.feature.vault.presentation.metal
import com.circaso.feature.vault.presentation.softOnMetal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * A direct chat's security code (2026-11-13, Pasaka: "platinum ... polished, kind of premium, the way we did with
 * Vault"). The sixty digits sit on the PIN Vault's brushed metal, set in twelve engraved groups of five with tabular
 * figures, so two people reading them aloud keep their place. The card carries who the code is between and whether
 * this phone has checked it; the steps under it say how, in plain words.
 *
 * Verified is this phone's own note (Signal's model): it is kept with the code it was given for, so a new code (they
 * signed in again) is unverified until it's checked again. Nothing about it goes to the server.
 */

/** The widest a card gets on a tablet or a phone on its side: a bank card held in the hand, not a banner. */
private val CardMaxWidth = 480.dp

/**
 * Contact Info's Encryption section: the card, how to check it, and Mark as Verified. [code] null: the session isn't
 * there yet (no message both ways), so the card shows where the digits will be. [onVerified] null hides the button
 * (previews of the bare card).
 */
@Composable
internal fun SecurityCodeSection(
    code: SecurityCode?,
    verified: Boolean,
    /** The code has been worked out; until then the card shows its dots without saying why. */
    loaded: Boolean = true,
    /** It was checked once and has changed since: say so first. */
    changedSinceVerified: Boolean = false,
    myName: String,
    myPhoto: String?,
    peerName: String,
    peerPhoto: String?,
    onVerified: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val first = peerName.substringBefore(' ').ifBlank { peerName }
    Column(
        modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = CardMaxWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
            SecurityCodeCard(code, verified = verified && code != null, myName, myPhoto, peerName, peerPhoto)
            if (code == null) {
                if (loaded) BasicText(
                    "The code appears after your first message to each other. Then you can check it with $first.",
                    style = t.subhead.copy(color = c.inkSecondary),
                )
            } else {
                // The steps give way to "You've checked this code" (and back) by a cross-fade that resizes, instead of
                // everything under the card jumping at once.
                val still = CircasoTheme.reduceMotion
                AnimatedContent(
                    verified,
                    transitionSpec = {
                        if (still) EnterTransition.None togetherWith ExitTransition.None using SizeTransform(clip = false) { _, _ -> snap() }
                        else fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.ExitMs)) using SizeTransform(clip = false) { _, _ -> tween(CircasoMotion.BaseMs) }
                    },
                    label = "how-to-check",
                ) { ok -> HowToCheck(first, ok, changedSinceVerified && !ok) }
                if (onVerified != null) {
                    val haptics = LocalHapticFeedback.current
                    // One button whose words change, so a screen reader keeps its place on it.
                    CircasoButton(
                        if (verified) "Clear Verification" else "Mark as Verified",
                        { if (!verified) haptics.performHapticFeedback(HapticFeedbackType.LongPress); onVerified(!verified) },
                        style = if (verified) ButtonStyle.Plain else ButtonStyle.Secondary, size = ButtonSize.Large, block = true,
                    )
                }
            }
        }
    }
}

/**
 * The card itself: the vault's metal in a bank card's width (it grows with large text instead of clipping). Top, what
 * it is and its state; middle, the digits; bottom, the two people it's between.
 */
@Composable
internal fun SecurityCodeCard(
    code: SecurityCode?,
    verified: Boolean,
    myName: String,
    myPhoto: String?,
    peerName: String,
    peerPhoto: String?,
    modifier: Modifier = Modifier,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val ink = c.onMoney
    val soft = softOnMetal(c)
    val still = CircasoTheme.reduceMotion
    val tracked = TextUnit(1.2f, TextUnitType.Sp)
    // Marking it verified sends one band of light across the metal; never under reduced motion, never on first show.
    val sheen = remember { Animatable(1f) }
    var lastVerified by remember { mutableStateOf(verified) }
    LaunchedEffect(verified) {
        if (verified && !lastVerified && !still) { sheen.snapTo(0f); sheen.animateTo(1f, tween(CircasoMotion.MomentMs, easing = FastOutSlowInEasing)) }
        lastVerified = verified
    }
    val spoken = buildString {
        append("Security code with $peerName. ")
        append(if (code == null) "Not available yet." else if (verified) "Verified." else "Not verified.")
        // Read group by group, as people read it aloud to each other.
        code?.let { append(" "); append(it.groups.joinToString(", ") { g -> g.toList().joinToString(" ") }) }
    }
    Column(
        modifier.fillMaxWidth()
            .shadow(if (c.isDark) 0.dp else 10.dp, CircasoShapes.lg, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(CircasoShapes.lg)
            .background(metal(c))
            .drawBehind { drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = if (c.isDark) 0.3f else 0.14f), Color.Transparent), center = Offset.Zero, radius = size.maxDimension * 0.8f)) }
            .drawWithContent {
                drawContent()
                val p = sheen.value
                if (p < 1f) {
                    val band = size.width * 0.45f
                    val x = -band + (size.width + band * 2) * p
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = if (c.isDark) 0.28f else 0.2f), Color.Transparent),
                            start = Offset(x - band, 0f), end = Offset(x, size.height),
                        ),
                    )
                }
            }
            .border(if (c.isHighContrast) 1.dp else 0.5.dp, if (c.isHighContrast) c.separator else Color.White.copy(alpha = 0.18f), CircasoShapes.lg)
            .padding(CircasoSpacing.s5)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            // Stamped into the metal, as the vault's dial is on its card.
            Box(Modifier.size(40.dp).clip(CircasoShapes.circle).background(ink.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                CircasoIconImage(CircasoIcon.Shield, ink, Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                BasicText("Security Code", style = t.headline.copy(color = ink))
                BasicText("End-to-end encrypted", style = t.caption1.copy(color = soft))
            }
            if (code != null) {
                AnimatedContent(verified, transitionSpec = { fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.ExitMs)) }, label = "verified") { ok ->
                    StatusChip(ok)
                }
            }
        }
        DigitGrid(code, ink, soft)
        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
            BasicText("BETWEEN", style = t.caption2.copy(color = soft, letterSpacing = tracked))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                PairOfFaces(myName, myPhoto, peerName, peerPhoto, c.moneyCard)
                BasicText("You and $peerName", style = t.subhead.copy(color = ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}

/** Verified: the ink filled in, the strongest thing on the card. Not verified: a quiet engraved chip. */
@Composable
private fun StatusChip(verified: Boolean) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val ink = c.onMoney
    Row(
        Modifier.clip(CircasoShapes.pill)
            .background(if (verified) ink else ink.copy(alpha = 0.14f))
            .padding(horizontal = CircasoSpacing.s2, vertical = CircasoSpacing.s1),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1),
    ) {
        if (verified) CircasoIconImage(CircasoIcon.Check, c.moneyCard, Modifier.size(12.dp))
        BasicText(if (verified) "Verified" else "Not verified", style = t.caption1.copy(color = if (verified) c.moneyCard else ink, fontWeight = FontWeight.SemiBold))
    }
}

/**
 * The twelve groups in rows of three on a phone (four on a wide card), each engraved into the metal. With large text a
 * group can't fit three across, so it steps down to two, then one, never shrinking or clipping a digit. Before there
 * is a code, the cells hold dots where the digits will be.
 */
@Composable
private fun DigitGrid(code: SecurityCode?, ink: Color, soft: Color) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val style = t.title3.copy(color = ink, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum", letterSpacing = TextUnit(1.5f, TextUnitType.Sp), textAlign = TextAlign.Center)
    val groups = code?.groups ?: List(12) { "·····" }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val groupWidth: Dp = remember(style, density) { with(density) { measurer.measure("00000", style).size.width.toDp() } }
    val gap = CircasoSpacing.s2
    val cellPad = CircasoSpacing.s2
    val still = CircasoTheme.reduceMotion
    // The digits settle in once, group by group, the first time the card is shown (not on every scroll back).
    var shown by rememberSaveable(code?.digits) { mutableStateOf(still || code == null) }
    val rise = remember(code?.digits) { List(groups.size) { Animatable(if (shown) 1f else 0f) } }
    if (!shown) LaunchedEffect(code?.digits) {
        rise.forEachIndexed { i, a -> launch { delay(i * StaggerMs); a.animateTo(1f, tween(CircasoMotion.BaseMs, easing = FastOutSlowInEasing)) } }
        delay(groups.size * StaggerMs + CircasoMotion.BaseMs)
        shown = true
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        fun fits(n: Int) = (groupWidth + cellPad * 2) * n + gap * (n - 1) <= maxWidth
        val columns = when {
            maxWidth >= 420.dp && fits(4) -> 4
            fits(3) -> 3
            fits(2) -> 2
            else -> 1
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            groups.withIndex().chunked(columns).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    line.forEach { (i, g) ->
                        val a = rise[i]
                        Box(
                            Modifier.weight(1f)
                                .graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 6.dp.toPx() }
                                .clip(CircasoShapes.xs)
                                .background(ink.copy(alpha = if (c.isDark) 0.1f else 0.08f))
                                .then(if (c.isHighContrast) Modifier.border(1.dp, ink.copy(alpha = 0.6f), CircasoShapes.xs) else Modifier)
                                .padding(horizontal = cellPad, vertical = CircasoSpacing.s2),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(g, style = if (code == null) style.copy(color = soft) else style, maxLines = 1, softWrap = false)
                        }
                    }
                    // A short last row keeps its cells the same width as the rows above.
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** My face and theirs, overlapped, each ringed in the card's metal so they read as two. */
@Composable
private fun PairOfFaces(myName: String, myPhoto: String?, peerName: String, peerPhoto: String?, ring: Color) {
    val size = CircasoSizes.avatarSm
    Box(Modifier.size(width = size * 2 - 8.dp, height = size)) {
        Avatar(myName, Modifier.border(2.dp, ring, CircasoShapes.circle), size = size, imageUrl = myPhoto)
        Avatar(peerName, Modifier.offset(x = size - 8.dp).border(2.dp, ring, CircasoShapes.circle), size = size, imageUrl = peerPhoto)
    }
}

/** How to check, in three short steps, then what a match (or a change) means. */
@Composable
private fun HowToCheck(first: String, verified: Boolean, changed: Boolean) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        // Read out when it changes, since marking it verified moves nothing else a screen reader would notice.
        BasicText(
            if (verified) "You've checked this code" else if (changed) "Check it again" else "How to check it",
            style = t.headline.copy(color = c.ink), modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
        )
        if (changed) Row(
            Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.fill).padding(CircasoSpacing.s3),
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.Top,
        ) {
            CircasoIconImage(CircasoIcon.Shield, c.warning, Modifier.padding(top = 2.dp).size(16.dp))
            BasicText(
                "This code changed since you checked it with $first, usually because they signed in again. Check it again before you send money or anything private.",
                style = t.subhead.copy(color = c.ink), modifier = Modifier.weight(1f),
            )
        }
        if (verified) {
            BasicText(
                "You and $first compared these numbers and they matched. If the code changes, for example when $first signs in on a new phone, the chat tells you and this goes back to not verified.",
                style = t.subhead.copy(color = c.inkSecondary),
            )
        } else {
            Step(1, "Meet $first, or call them.")
            Step(2, "Read your numbers to each other, or hold your phones side by side.")
            Step(3, "If every number matches, mark it as verified.")
            BasicText(
                "When they match, nobody is in between: only you and $first can read this chat. If they don't, check with $first before you send money or anything private.",
                style = t.footnote.copy(color = c.inkSecondary),
            )
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.Top) {
        // Grows with the person's text size into a pill rather than clipping the number.
        Box(Modifier.sizeIn(minWidth = CircasoSpacing.s6, minHeight = CircasoSpacing.s6).clip(CircasoShapes.pill).background(c.fill).padding(horizontal = CircasoSpacing.s1), contentAlignment = Alignment.Center) {
            BasicText("$n", style = t.caption1.copy(color = c.ink, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), maxLines = 1, softWrap = false)
        }
        BasicText(text, style = t.subhead.copy(color = c.ink), modifier = Modifier.weight(1f).padding(top = 2.dp))
    }
}

private const val StaggerMs = 30L
