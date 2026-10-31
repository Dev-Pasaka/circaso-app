package com.circaso.feature.wallet.presentation

import com.circaso.designsystem.motion.pressable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import circaso.shared.generated.resources.circaso_mark_white
import circaso.shared.generated.resources.Res
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.CoinDisc
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowAmount
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.chainName
import com.circaso.feature.wallet.domain.formatFiat
import com.circaso.feature.wallet.domain.shortAddress
import kotlin.math.abs

/**
 * One bank-style card per blockchain (Base, Polygon, Ethereum, Solana), swiped left and right: the chain, what it
 * holds in the person's currency, its assets and its address. The card in focus is full size; the ones beside it
 * peek in smaller and dimmer, and follow the finger. Below, the focused chain's assets slide in from the side the
 * person swiped towards.
 */
@Composable
internal fun ChainCards(
    chains: List<WalletChain>,
    fiat: FiatValue?,
    hidden: Boolean,
    onToggleHidden: () -> Unit = {},
    /** The network of the card in front, as the person swipes (the list under the cards follows it). */
    onFocus: (String) -> Unit = {},
    /** An asset row was tapped: its statement on that network. */
    onAsset: (blockchain: String, symbol: String) -> Unit = { _, _ -> },
    /** The front card's colour as the person swipes, blended between neighbours: the page glows in it. */
    onGlow: (androidx.compose.ui.graphics.Color) -> Unit = {},
    between: @Composable () -> Unit = {},
) {
    if (chains.isEmpty()) return
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val still = CircasoTheme.reduceMotion
    val pager = rememberPagerState { chains.size }
    // Keep the card in focus when a refresh reorders them (the richest first): follow it by its network.
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(pager.currentPage, chains.size) {
        focused = chains.getOrNull(pager.currentPage)?.blockchain
        focused?.let(onFocus)
    }
    androidx.compose.runtime.LaunchedEffect(chains.map { it.blockchain }) {
        val want = chains.indexOfFirst { it.blockchain == focused }
        if (want >= 0 && want != pager.currentPage) pager.scrollToPage(want)
    }
    // The glow follows the finger: the front card's colour blending into the next one's mid-swipe.
    val glow = run {
        val here = cardArt(chainName(chains[pager.currentPage.coerceIn(chains.indices)].blockchain).substringBefore(" ("))[1]
        val next = (pager.currentPage + if (pager.currentPageOffsetFraction >= 0f) 1 else -1).coerceIn(chains.indices)
        val there = cardArt(chainName(chains[next].blockchain).substringBefore(" ("))[1]
        androidx.compose.ui.graphics.lerp(here, there, abs(pager.currentPageOffsetFraction))
    }
    androidx.compose.runtime.LaunchedEffect(glow) { onGlow(glow) }
    // Tapping the front card fans the cards out clockwise behind it, like a hand of cards (the user, 2026-10-31).
    // Tapping any card pulls it to the front and the fan springs closed into the row again.
    var deckShown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var spread by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var order by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(emptyList<String>()) }
    androidx.compose.runtime.LaunchedEffect(deckShown) { if (deckShown) spread = true }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    androidx.compose.ui.backhandler.BackHandler(enabled = spread) { spread = false }
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        if (deckShown) {
            CardDeck(
                chains = order.mapNotNull { b -> chains.firstOrNull { it.blockchain == b } },
                fiat = fiat, hidden = hidden, onToggleHidden = onToggleHidden, spread = spread, still = still,
                onPick = { picked ->
                    // The picked card goes to the front at once (it is pulled out), then the stack closes behind it.
                    order = order.filter { it != picked } + picked
                    spread = false
                    chains.indexOfFirst { it.blockchain == picked }.takeIf { it >= 0 }?.let { pager.requestScrollToPage(it) }
                },
                onClosed = { deckShown = false },
            )
        } else {
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = CircasoSpacing.s6),
                pageSpacing = CircasoSpacing.s3,
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                val distance = abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                ChainCard(
                    chains[page], fiat, hidden, onToggleHidden,
                    Modifier.graphicsLayer {
                        if (!still) {
                            val scale = 1f - 0.08f * distance
                            scaleX = scale; scaleY = scale
                        }
                        alpha = 1f - 0.35f * distance
                    }.let { m ->
                        if (chains.size > 1 && page == pager.currentPage) {
                            m.pressable(role = androidx.compose.ui.semantics.Role.Button) {
                                order = chains.map { it.blockchain }.filter { it != chains[page].blockchain } + chains[page].blockchain
                                deckShown = true
                            }
                        } else m
                    },
                )
            }
        }
        if (chains.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                chains.indices.forEach { i ->
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(if (i == pager.currentPage) 18.dp else 6.dp, 6.dp)
                            .clip(CircasoShapes.pill).background(if (i == pager.currentPage) c.ink else c.fillStrong),
                    )
                }
            }
        }
        // The actions sit between the cards and the focused card's assets (Pasaka, 2026-10-30).
        Box(Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2)) { between() }
        AnimatedContent(
            targetState = pager.currentPage,
            transitionSpec = {
                val forward = targetState > initialState
                if (still) fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs))
                else (slideInHorizontally(tween(CircasoMotion.BaseMs)) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(tween(CircasoMotion.BaseMs))) togetherWith
                    (slideOutHorizontally(tween(CircasoMotion.QuickMs)) { w -> if (forward) -w / 4 else w / 4 } + fadeOut(tween(CircasoMotion.QuickMs)))
            },
            label = "assets",
            // Only the cards reach the screen edges; the list keeps the page margins.
            modifier = Modifier.padding(horizontal = CircasoSpacing.s4),
        ) { page ->
            val chain = chains.getOrNull(page) ?: return@AnimatedContent
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                BasicText("Assets on ${chainName(chain.blockchain).substringBefore(" (")}", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(horizontal = 4.dp))
                Column(Modifier.clip(CircasoShapes.md).background(c.surface)) {
                val assets = chain.assets.ifEmpty { listOf(Asset("USDC", "", 6, "0", "0", null)) }
                assets.forEachIndexed { i, asset ->
                    ListRow(
                        title = asset.symbol,
                        sub = assetName(asset.symbol) + if (!chain.receiveEnabled) " · retiring" else "",
                        leading = { CoinDisc(asset.symbol) },
                        onClick = { onAsset(chain.blockchain, asset.symbol) },
                        trailing = {
                            RowAmount(
                                amount = if (hidden) "••••" else MinorUnits.toDisplay(asset.amountMinor, asset.decimals),
                                meta = if (hidden) null else asset.fiatAmount?.let { f -> fiat?.currency?.let { cur -> formatFiat(cur, f) } },
                                incoming = false,
                            )
                        },
                    )
                    if (i < assets.lastIndex) RowDivider(Modifier.padding(start = 72.dp))
                }
                }
            }
        }
    }
}

/**
 * The card itself, Apple Wallet-style: deep card art per network with a soft diagonal sheen and a real shadow, the
 * Circaso mark in white metal, the network top right, the balance large at the bottom and the address the way a card
 * shows its last digits. The same art in light and dark: it is the card's face, not the page's.
 */
@Composable
private fun ChainCard(chain: WalletChain, fiat: FiatValue?, hidden: Boolean, onToggleHidden: () -> Unit, modifier: Modifier) {
    val t = CircasoTheme.typography
    val full = chainName(chain.blockchain)
    val network = full.substringBefore(" (")
    val testnet = full.endsWith("(testnet)")
    val value = chainFiat(chain, fiat)
    val holdings = chain.assets.filter { !MinorUnits.isZero(it.amountMinor) }
    val summary = holdings.joinToString("  ·  ") { "${MinorUnits.toDisplay(it.amountMinor, it.decimals)} ${it.symbol}" }.ifEmpty { "Nothing here yet" }
    val art = cardArt(network)
    val white = androidx.compose.ui.graphics.Color.White
    Box(
        modifier.fillMaxWidth().aspectRatio(1.586f) // a bank card's own proportions
            .shadow(18.dp, CardShape, ambientColor = art.first(), spotColor = art.first())
            .clip(CardShape)
            .background(Brush.linearGradient(art))
            // The sheen: light falling across the top left, as on a real card.
            .background(Brush.radialGradient(listOf(white.copy(alpha = 0.22f), white.copy(alpha = 0f)), center = androidx.compose.ui.geometry.Offset(0f, 0f), radius = 900f))
            .border(0.5.dp, white.copy(alpha = 0.18f), CardShape)
            .padding(CircasoSpacing.s5)
            .semantics(mergeDescendants = true) { contentDescription = "$full card. " + if (hidden) "Balance hidden" else (value ?: summary) },
    ) {
        androidx.compose.foundation.Image(
            org.jetbrains.compose.resources.painterResource(Res.drawable.circaso_mark_white),
            contentDescription = null, modifier = Modifier.size(com.circaso.designsystem.theme.CircasoSizes.brandMarkSm).align(Alignment.TopStart),
        )
        Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
            BasicText(network, style = t.headline.copy(color = white))
            if (testnet) BasicText("TESTNET", style = t.caption2.copy(color = white.copy(alpha = 0.65f), letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp)))
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            // Show or hide balances right where they are (it used to be in a title bar).
            Row(
                Modifier.pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = onToggleHidden)
                    .semantics(mergeDescendants = true) { contentDescription = if (hidden) "Show balances" else "Hide balances" }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText("BALANCE", style = t.caption2.copy(color = white.copy(alpha = 0.6f), letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp)))
                com.circaso.designsystem.components.CircasoIconImage(
                    if (hidden) com.circaso.designsystem.components.CircasoIcon.EyeOff else com.circaso.designsystem.components.CircasoIcon.Eye,
                    white.copy(alpha = 0.75f), Modifier.size(14.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText(if (hidden) "••••••" else value ?: summary, style = (if (value != null) t.amountMedium else t.title3).copy(color = white), modifier = Modifier.weight(1f), maxLines = 1)
                BasicText("•••• " + chain.address.takeLast(4), style = t.footnote.copy(color = white.copy(alpha = 0.7f), fontFeatureSettings = "tnum"))
            }
            if (value != null && !hidden) BasicText(summary, style = t.footnote.copy(color = white.copy(alpha = 0.7f), fontFeatureSettings = "tnum"), maxLines = 1)
        }
    }
}

private val CardShape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)

/**
 * The cards as a fan, like slices of a pie: [chains] back to front, the front one upright and the ones behind
 * turned clockwise about the shared bottom centre, the rearmost by [FanAngle] and the rest evenly between. They start
 * piled exactly where the front card sat in the row, so opening reads as fanning them out and closing as gathering
 * them back; [onClosed] runs once they have settled together.
 */
@Composable
private fun CardDeck(
    chains: List<WalletChain>,
    fiat: FiatValue?,
    hidden: Boolean,
    onToggleHidden: () -> Unit,
    spread: Boolean,
    still: Boolean,
    onPick: (String) -> Unit,
    onClosed: () -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s6)) {
        val cardHeight = maxWidth / 1.586f
        // Turned about its bottom centre, the rearmost card's bottom-right corner hangs this far below the front card.
        val turn = FanAngle * kotlin.math.PI.toFloat() / 180f
        val drop = maxWidth * FanBehindScale * 0.5f * kotlin.math.sin(turn)
        // ...and its top-left corner rises this far above the front card's top: room is made above the fan for it,
        // so nothing reaches past the top of the list (where it would be cut off under the status bar).
        val rise = ((maxWidth * 0.5f * kotlin.math.sin(turn) + cardHeight * kotlin.math.cos(turn)) * FanBehindScale - cardHeight).coerceAtLeast(0.dp)
        // The open fan leans right of its pivot by this much; it slides left as it opens so the slices sit centred.
        val lean = with(androidx.compose.ui.platform.LocalDensity.current) {
            ((cardHeight * kotlin.math.sin(turn) + maxWidth * 0.5f * kotlin.math.cos(turn) - maxWidth * 0.5f) * FanBehindScale / 2f).toPx()
        }
        fun <T> motion() = if (still) tween<T>(CircasoMotion.BaseMs) else androidx.compose.animation.core.spring<T>(dampingRatio = DeckDamping, stiffness = DeckStiffness)
        // Fanned, the whole hand shrinks so it barely needs more room than the card did, and springs back to full
        // size as it closes: the actions below move a little, not a card's height (the user, 2026-10-31).
        val shrink by androidx.compose.animation.core.animateFloatAsState(if (spread) FanShrink else 1f, motion(), label = "shrink")
        val lift by androidx.compose.animation.core.animateDpAsState(if (spread) rise else 0.dp, motion(), label = "lift")
        val height by androidx.compose.animation.core.animateDpAsState(
            if (spread) (rise + cardHeight + drop) * FanShrink else cardHeight, motion(), label = "fan",
            finishedListener = { if (!spread) onClosed() },
        )
        Box(
            Modifier.fillMaxWidth().height(height).graphicsLayer {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                scaleX = shrink; scaleY = shrink
                translationX = -lean * ((1f - shrink) / (1f - FanShrink)).coerceIn(0f, 1.2f)
            },
        ) {
            val last = chains.lastIndex.coerceAtLeast(1)
            chains.forEachIndexed { slot, chain ->
                androidx.compose.runtime.key(chain.blockchain) {
                    val front = slot == chains.lastIndex
                    val angle by androidx.compose.animation.core.animateFloatAsState(if (spread) FanAngle * (chains.lastIndex - slot) / last else 0f, motion(), label = "angle")
                    // Behind cards sit a touch smaller, so piled up their edges never show around the front card.
                    val scale by androidx.compose.animation.core.animateFloatAsState(if (front) 1f else FanBehindScale, motion(), label = "scale")
                    ChainCard(
                        chain, fiat, hidden, onToggleHidden,
                        Modifier.zIndex(slot.toFloat())
                            .graphicsLayer {
                                translationY = lift.toPx()
                                // One point for every card, its bottom centre: the fan opens like slices of a pie.
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                                rotationZ = angle
                                scaleX = scale; scaleY = scale
                            }
                            .pressable(role = androidx.compose.ui.semantics.Role.Button) { onPick(chain.blockchain) },
                    )
                }
            }
        }
    }
}

/** How far the rearmost card turns (the user, 2026-10-31). */
private const val FanAngle = 45f
private const val FanBehindScale = 0.94f
/** The open fan is 30% smaller than the card (the user, 2026-10-31). */
private const val FanShrink = 0.7f
/** A little give as the cards land, like cards fanned in the hand, without wobbling. */
private const val DeckDamping = 0.78f
private const val DeckStiffness = 420f

/**
 * Card art per network: deep, quiet gradients, so the stack reads like a wallet of real cards and each network is
 * recognisable at a glance. Card faces, not interface colours, so they don't change with the theme.
 */
private fun cardArt(network: String): List<androidx.compose.ui.graphics.Color> {
    fun c(hex: Long) = androidx.compose.ui.graphics.Color(hex)
    return when (network) {
        "Base" -> listOf(c(0xFF0B1F4D), c(0xFF1D4ED8), c(0xFF0A1A3F))
        "Polygon" -> listOf(c(0xFF2A1250), c(0xFF6D28D9), c(0xFF1F0D3D))
        "Ethereum" -> listOf(c(0xFF1C1E2E), c(0xFF4B5079), c(0xFF15172A))
        "Solana" -> listOf(c(0xFF062A2A), c(0xFF0F766E), c(0xFF06201F))
        else -> listOf(c(0xFF1C1C1E), c(0xFF48484A), c(0xFF141416))
    }
}

/** The chain's worth in the person's currency, from each priced asset; null when nothing on it has a price. */
internal fun chainFiat(chain: WalletChain, fiat: FiatValue?): String? {
    val currency = fiat?.currency ?: return null
    val priced = chain.assets.mapNotNull { it.fiatAmount?.let(::centsOf) }
    if (priced.isEmpty()) return null
    val cents = priced.sum()
    return formatFiat(currency, "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}")
}

/** Networks in the order the cards show them: the one holding the most first (by value, then by raw amount). */
internal fun byValue(chains: List<WalletChain>, fiat: FiatValue?): List<WalletChain> =
    chains.sortedWith(
        compareByDescending<WalletChain> { chain -> chain.assets.mapNotNull { it.fiatAmount?.let(::centsOf) }.sum() }
            .thenByDescending { chain -> chain.assets.count { !MinorUnits.isZero(it.amountMinor) } },
    ).let { sorted ->
        // Without a rate, assets can't be compared (1 ETH isn't 1 USDC): order by USDC alone, then by what's held.
        if (fiat != null) sorted
        else chains.sortedWith(
            compareByDescending<WalletChain> { c -> c.usdc?.amountMinor?.toLongOrNull() ?: 0L }
                .thenByDescending { c -> c.assets.count { !MinorUnits.isZero(it.amountMinor) } },
        )
    }

/** "1234.5" → 123450; tolerant of thousands separators. */
internal fun centsOf(amount: String): Long? {
    val clean = amount.replace(",", "").trim()
    val whole = clean.substringBefore('.').toLongOrNull() ?: return null
    val frac = clean.substringAfter('.', "").padEnd(2, '0').take(2).toLongOrNull() ?: 0
    return whole * 100 + frac
}

private fun assetName(symbol: String) = when (symbol.uppercase()) {
    "USDC" -> "USD Coin"
    "EURC" -> "Euro Coin"
    "ETH" -> "Ether"
    "POL", "MATIC" -> "Polygon"
    "SOL" -> "Solana"
    "WBTC", "CBBTC", "BTC" -> "Bitcoin"
    "CIRBTC" -> "Circle Bitcoin"
    "USDT" -> "Tether"
    else -> symbol
}
