package com.circaso.feature.wallet.presentation

import androidx.compose.animation.AnimatedVisibility
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

/** The caution dot that stands for Testnet: the same size in the switch and in the tag. */
internal val TestnetDot: Dp = CircasoSpacing.s2 - CircasoSpacing.s1 / 2

/** What a screen reader says for the mark, wherever it is. */
internal const val TestnetSpoken = "Network: Testnet, test money with no real value"

/**
 * Mainnet | Testnet at the top of the Wallet tab, shown only to someone who has both. No design frame exists for it:
 * it is the Personal | Groups pill ([TwoSegmentControl]: the same track, lift, spring and 44 dp target), so the two
 * switches read as a pair, with a small caution dot on Testnet. Each side reads out as "Network: Mainnet" / "Network:
 * Testnet, test money", a tab, selected or not. Switching is instant: the screens read the other network's copy from
 * the phone.
 */
@Composable
fun WalletNetworkControl(network: WalletNetwork, onNetwork: (WalletNetwork) -> Unit, modifier: Modifier = Modifier, segmentMinWidth: Dp? = null) {
    TwoSegmentControl(
        first = WalletNetwork.MAINNET.label, second = WalletNetwork.TESTNET.label, secondSelected = network == WalletNetwork.TESTNET,
        onSelect = { second -> onNetwork(if (second) WalletNetwork.TESTNET else WalletNetwork.MAINNET) },
        describe = { if (it == WalletNetwork.TESTNET.label) "Network: Testnet, test money" else "Network: $it" }, modifier = modifier,
        secondDot = true, dotColor = CircasoTheme.colors.warning, segmentMinWidth = segmentMinWidth,
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
        Box(Modifier.size(TestnetDot).clip(CircasoShapes.circle).background(c.warning))
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
