package com.circaso.feature.wallet.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.isTestnetChain

/**
 * Test money is on screen: the selected wallet network is Testnet (and the server has said so). Provided once at the
 * root from `WalletNetworks`, so every money screen marks itself without each one being told; false for someone who
 * only has Mainnet, who therefore never sees the word.
 */
val LocalTestnet = compositionLocalOf { false }

/**
 * Mainnet | Testnet at the top of the Wallet tab, shown only to someone who has both. No design frame exists for it:
 * it is the Personal | Groups pill ([TwoSegmentControl]: the same track, lift, spring and 44 dp target), so the two
 * switches read as a pair, with a small caution dot on Testnet. Each side reads out as "Network: Mainnet" / "Network:
 * Testnet", a tab, selected or not. Switching is instant: the screens read the other network's copy from the phone.
 */
@Composable
fun WalletNetworkControl(network: WalletNetwork, onNetwork: (WalletNetwork) -> Unit, modifier: Modifier = Modifier) {
    TwoSegmentControl(
        first = WalletNetwork.MAINNET.label, second = WalletNetwork.TESTNET.label, secondSelected = network == WalletNetwork.TESTNET,
        onSelect = { second -> onNetwork(if (second) WalletNetwork.TESTNET else WalletNetwork.MAINNET) },
        describe = { "Network: $it" }, modifier = modifier,
        secondDot = true, dotColor = CircasoTheme.colors.warning,
    )
}

/**
 * The mark for test money: a small capsule with the caution dot and the word. Used wherever an amount is shown while
 * Testnet is selected, and on any payment or card that moved on a test chain, whatever is selected. Quiet enough to
 * sit beside a title; in high contrast it gains an outline. One description for screen readers.
 */
@Composable
fun TestnetTag(modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        modifier.clip(CircasoShapes.circle).background(c.fill)
            .then(if (c.isHighContrast) Modifier.border(1.dp, c.ink, CircasoShapes.circle) else Modifier)
            .padding(horizontal = CircasoSpacing.s2, vertical = TagPadding)
            .clearAndSetSemantics { contentDescription = "Testnet. Test money with no real value." },
        horizontalArrangement = Arrangement.spacedBy(TagGap), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(TagDot).clip(CircasoShapes.circle).background(c.warning))
        BasicText("Testnet", style = t.caption1.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

/**
 * The same mark with its reason, as a line of its own under a screen's bar or above a form: "Testnet · Test money,
 * no real value". Draws nothing unless [show], so screens place it unconditionally.
 */
@Composable
fun TestnetNote(modifier: Modifier = Modifier, show: Boolean = LocalTestnet.current) {
    if (!show) return
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Testnet. Test money with no real value." },
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        TestnetTag()
        BasicText("Test money, no real value", style = t.caption1.copy(color = c.inkSecondary, textAlign = TextAlign.Center), maxLines = 1)
    }
}

/** A chain's own mark: [TestnetTag] for a test chain, nothing for a Mainnet one (which never shows the word). */
@Composable
fun ChainTestnetTag(blockchain: String?, modifier: Modifier = Modifier) {
    if (blockchain != null && isTestnetChain(blockchain)) TestnetTag(modifier)
}

private val TagDot = 6.dp
private val TagGap = 5.dp
private val TagPadding = 3.dp
