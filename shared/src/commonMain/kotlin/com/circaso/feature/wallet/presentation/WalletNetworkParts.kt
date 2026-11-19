package com.circaso.feature.wallet.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.runtime.remember
import com.circaso.designsystem.motion.pressIndication
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.circaso.designsystem.chrome.SheetHead
import com.circaso.designsystem.chrome.sheetGroupColor
import com.circaso.designsystem.chrome.sheetGroupEdge
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.feature.wallet.domain.WalletStatus
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.domain.WalletNetwork

/**
 * Test money is on screen: the selected wallet network is Testnet (and the server has said so). Provided once at the
 * root from `WalletNetworks`, for screens that have no payment or wallet of their own to read it from; false for
 * someone who only has Mainnet, who therefore never sees the word. A screen that holds its own network (a payment
 * being made, the Wallet tab's data) uses that instead, so the mark always belongs to the money beside it.
 */
val LocalTestnet = compositionLocalOf { false }

/** The dot that stands for a network: the same size in the chip, the Network sheet and the Testnet tag. */
internal val TestnetDot: Dp = CircasoSpacing.s2 - CircasoSpacing.s1 / 2

/** What a screen reader says for the mark, wherever it is. */
internal const val TestnetSpoken = "Network: Testnet, test money with no real value"

/** The dot that stands for a network wherever one is named: amber for Testnet's test money, a quiet ink dot for Mainnet. */
@Composable
internal fun NetworkDot(network: WalletNetwork, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    // The theme has no "positive" role (money is ink here), so Mainnet's dot is the quiet ink role, which holds 3:1
    // on its capsule in every theme.
    val color = if (network == WalletNetwork.TESTNET) c.warning else c.inkTertiary
    Box(modifier.size(TestnetDot).clip(CircasoShapes.circle).background(color))
}

/** The chip's capsule: 32 dp to look at, as the Personal | Groups track is; its touch target is the row's 44 dp. */
private val ChipHeight = 32.dp

/**
 * The Wallet tab's network chip (Pasaka, 2026-11-19; it replaced a second Mainnet | Testnet pill that sat beside
 * Personal | Groups as its equal). One quiet status capsule at the end of the tab's top row: the network's dot, its
 * name and a small chevron; a tap opens the Network sheet ([NetworkSheetContent]). Only for someone with both
 * networks. It is the same family as [TestnetTag] (the dot and the word), outlined with a hairline
 * instead of filled so it never reads as a second switch beside the filled track. When the network changes the word crossfades and nothing else
 * moves. Read out as "Network: Mainnet" (or "Network: Testnet, test money"), a button whose action is "change".
 */
@Composable
fun NetworkChip(network: WalletNetwork, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val still = CircasoTheme.reduceMotion
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    val spoken = if (network == WalletNetwork.TESTNET) TestnetSpoken else "Network: ${network.label}"
    // The 44 dp box is the control (its touch target, its focus and its spoken bounds); the capsule inside is what
    // is seen, and what Android's ripple is drawn on.
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier.heightIn(min = CircasoSizes.controlMd * scale)
            .pressable(role = Role.Button, onClickLabel = "change", interactionSource = source, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            // Outlined, never filled: beside the filled Personal | Groups track a second filled capsule read as a
            // second switch. A hairline keeps it a quiet status that can be tapped.
            Modifier.heightIn(min = ChipHeight * scale).clip(CircasoShapes.circle)
                .border(1.dp, if (c.isHighContrast) c.ink else c.separator, CircasoShapes.circle)
                .pressIndication(source)
                .padding(start = CircasoSpacing.s3, end = CircasoSpacing.s2 + CircasoSpacing.s1 / 2),
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1 + CircasoSpacing.s1 / 2), verticalAlignment = Alignment.CenterVertically,
        ) {
            // The dot changes with the word, in the same fade.
            AnimatedContent(
                network,
                transitionSpec = {
                    (fadeIn(tween(CircasoMotion.QuickMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs)))
                        .using(SizeTransform(clip = false) { _, _ -> if (still) snap() else tween(CircasoMotion.QuickMs) })
                },
                contentAlignment = Alignment.Center, label = "network",
            ) { on ->
                Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1 + CircasoSpacing.s1 / 2), verticalAlignment = Alignment.CenterVertically) {
                    NetworkDot(on)
                    BasicText(on.label, style = t.footnote.copy(color = c.ink, fontWeight = FontWeight.Medium), maxLines = 1)
                }
            }
            CircasoIconImage(CircasoIcon.ChevronDown, c.inkSecondary, Modifier.size(CircasoSpacing.s3 + CircasoSpacing.s1 / 2))
        }
    }
}

/** What the Network sheet says under a network's name: what its money is, and whether its wallet exists yet. */
internal fun networkLine(network: WalletNetwork, status: WalletStatus?): String =
    (if (network == WalletNetwork.MAINNET) "Real money" else "Test money, no real value") +
        // Only when the server's list says so: a status it didn't give is never guessed as "not set up".
        if (status == WalletStatus.None || status == WalletStatus.SettingUp) " · Not set up yet" else ""

/**
 * The Network sheet, from the chip: the person's networks as one choice of one, built like the Group Wallets sheet's
 * rows (one rounded group on [sheetGroupColor], the 44 dp picture column, a hairline from where the text starts).
 * Each row: the network's dot in a small disc, its name, what its money is (and "Not set up yet" for a wallet that
 * doesn't exist); a tick on the selected one, its space kept on the other so the text lines up. A tap picks and
 * closes. It works offline: every screen reads the picked network's copy from the phone.
 */
@Composable
fun ColumnScope.NetworkSheetContent(
    selected: WalletNetwork,
    options: List<WalletNetwork>,
    statusOf: (WalletNetwork) -> WalletStatus?,
    onPick: (WalletNetwork) -> Unit,
    onClose: () -> Unit,
    /** A wallet is being made or put on its networks right now: the choice waits for it, and the sheet says so. */
    busy: Boolean = false,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    SheetHead("Network", onClose)
    // Mainnet first, whatever order the server listed them in.
    val shown = WalletNetwork.entries.filter { it in options }
    Column(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(sheetGroupColor()).sheetGroupEdge(CircasoShapes.md).semantics { selectableGroup() }) {
        shown.forEachIndexed { i, network ->
            if (i > 0) InsetDivider()
            val on = network == selected
            val sub = networkLine(network, statusOf(network))
            Row(
                Modifier.fillMaxWidth().heightIn(min = CircasoSizes.avatarMd + CircasoSpacing.s3 * 2)
                    .pressable(enabled = !busy || on, role = Role.RadioButton, onClick = { onPick(network) })
                    .clearAndSetSemantics { this.selected = on; role = Role.RadioButton; contentDescription = "${network.label}, $sub"; if (busy && !on) disabled() }
                    .padding(CircasoSpacing.s3),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
            ) {
                Box(Modifier.width(PictureColumn), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(CircasoSizes.avatarSm).clip(CircasoShapes.circle).background(c.fillStrong), contentAlignment = Alignment.Center) { NetworkDot(network) }
                }
                Column(Modifier.weight(1f)) {
                    BasicText(network.label, style = t.body.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1)
                    BasicText(sub, style = t.footnote.copy(color = c.inkSecondary))
                }
                if (on) CircasoIconImage(CircasoIcon.Check, c.action, Modifier.size(SheetGlyphSize), strokeWidth = 2.4f) else Spacer(Modifier.width(SheetGlyphSize))
            }
        }
    }
    BasicText(
        if (busy) "Your wallet is being set up. You can switch once that’s done."
        else "Each network is a separate wallet with its own PIN. Nothing moves between them.",
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s1), style = t.footnote.copy(color = c.inkSecondary),
    )
}

/**
 * The mark for test money: a small capsule with the caution dot and the word. It appears wherever an amount is shown
 * while Testnet is the network of that money, and on a payment's own page when the payment moved on a test chain,
 * whatever is selected now. [onGrouped] is for a grouped (tinted) background, where the plain fill would vanish: a
 * stronger fill there. In high contrast it gains an outline. One description for screen readers.
 */
@Composable
fun TestnetTag(modifier: Modifier = Modifier, onGrouped: Boolean = false) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        modifier.clip(CircasoShapes.circle).background(if (onGrouped) c.fillStrong else c.fill)
            .then(if (c.isHighContrast) Modifier.border(1.dp, c.ink, CircasoShapes.circle) else Modifier)
            .padding(horizontal = CircasoSpacing.s2, vertical = CircasoSpacing.s1 / 2)
            .clearAndSetSemantics { contentDescription = TestnetSpoken },
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1), verticalAlignment = Alignment.CenterVertically,
    ) {
        NetworkDot(WalletNetwork.TESTNET)
        BasicText("Testnet", style = t.caption1.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

/**
 * The same mark with its reason, as a line of its own: "Testnet · Test money, no real value". It keeps the page's
 * side inset itself and wraps (the reason goes under the tag) at large text or on a narrow screen rather than
 * clipping. Draws nothing unless [show], so screens place it unconditionally.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TestnetNote(modifier: Modifier = Modifier, show: Boolean = LocalTestnet.current, onGrouped: Boolean = false) {
    if (!show) return
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4).clearAndSetSemantics { contentDescription = TestnetSpoken },
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1 / 2),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        TestnetTag(onGrouped = onGrouped)
        BasicText("Test money, no real value", style = t.caption1.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
}

/**
 * [TestnetNote] for a screen's `strip` (under its bar) or the top of a page: the one place that owns its spacing and
 * its motion. It eases open and closed with the row's tokens, so a screen never jumps by its height when the network
 * becomes known or changes; with reduced motion it only fades.
 */
@Composable
fun TestnetStrip(show: Boolean = LocalTestnet.current, modifier: Modifier = Modifier, onGrouped: Boolean = false) {
    val still = CircasoTheme.reduceMotion
    AnimatedVisibility(
        show, modifier,
        enter = if (still) fadeIn(tween(CircasoMotion.QuickMs)) else expandVertically(tween(CircasoMotion.BaseMs)) + fadeIn(tween(CircasoMotion.BaseMs)),
        exit = if (still) fadeOut(tween(CircasoMotion.QuickMs)) else shrinkVertically(tween(CircasoMotion.QuickMs)) + fadeOut(tween(CircasoMotion.QuickMs)),
    ) {
        TestnetNote(Modifier.padding(vertical = CircasoSpacing.s1), show = true, onGrouped = onGrouped)
    }
}
