package com.circaso.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import coil3.Uri
import coil3.decode.DataSource
import coil3.map.Mapper
import coil3.request.Options
import coil3.toUri
import com.circaso.designsystem.coins.CoinSymbols
import com.circaso.designsystem.coins.bundledCoinVector
import com.circaso.designsystem.coins.bundledNetworkVector
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoTheme

/** How much of the disc the mark's 24-unit box takes: the set's marks fill 18 of 24 units, so ~half the disc. */
private const val GlyphRatio = 0.62f

/**
 * A coin's mark (decision 2026-11-06): the Web3 Icons mono set, painted in one theme colour on a theme-toned disc
 * (the design's coin badge, `cx-avatar-md`), never the coin's brand colours. Bundled coins draw at once and offline;
 * any other symbol is fetched as SVG from `GET /v1/assets/icons/{symbol}` (see [CoinIconMapper]) and kept in Coil's
 * disk cache. Until it arrives, offline on first sight, or for a symbol the set doesn't have, the coin's letters show.
 * Decorative: the row or card around it says the coin's name.
 */
@Composable
fun CoinIcon(
    symbol: String,
    modifier: Modifier = Modifier,
    size: Dp = CircasoSizes.avatarMd,
    tint: Color = CircasoTheme.colors.ink,
    disc: Color = CircasoTheme.colors.fillStrong,
) {
    val key = CoinSymbols.normalise(symbol)
    val bundled = key?.let(::bundledCoinVector)
    IconDisc(modifier, size, disc) {
        when {
            bundled != null -> Mark(bundled, size, tint)
            key != null && key !in MissingIcons -> RemoteMark(key, symbol, size, tint)
            else -> Letters(symbol, size, tint)
        }
    }
}

/** A network's mark (Base, Ethereum, Polygon, Solana, Avalanche, Arbitrum, Arc) on the same disc; letters for others. */
@Composable
fun NetworkIcon(
    blockchain: String,
    modifier: Modifier = Modifier,
    size: Dp = CircasoSizes.avatarMd,
    tint: Color = CircasoTheme.colors.ink,
    disc: Color = CircasoTheme.colors.fillStrong,
) {
    val vector = CoinSymbols.network(blockchain)?.let(::bundledNetworkVector)
    IconDisc(modifier, size, disc) {
        if (vector != null) Mark(vector, size, tint) else Letters(blockchain.take(3), size, tint)
    }
}

/** The lettered disc on its own ("IN", "OUT"), for a row that has no person or coin to show. */
@Composable
fun LetterDisc(text: String, modifier: Modifier = Modifier, size: Dp = CircasoSizes.avatarMd) {
    IconDisc(modifier, size, CircasoTheme.colors.fillStrong) { Letters(text, size, CircasoTheme.colors.ink) }
}

/** What Coil is asked for: an icon key, turned into the server's URL by [CoinIconMapper]. */
data class CoinIconData(val key: String)

/** Registered on the app's image loader with the API's base URL; previews without it fall back to letters. */
class CoinIconMapper(private val baseUrl: String) : Mapper<CoinIconData, Uri> {
    override fun map(data: CoinIconData, options: Options): Uri = coinIconUrl(baseUrl, data.key).toUri()
}

/** `https://…/v1/assets/icons/USDC`: the key is already upper case and allow-listed. */
fun coinIconUrl(baseUrl: String, key: String): String = baseUrl.trimEnd('/') + "/v1/assets/icons/" + key

/** Symbols the server answered 404 for, so this run of the app doesn't ask again. */
private val MissingIcons = mutableSetOf<String>()

@Composable
private fun IconDisc(modifier: Modifier, size: Dp, disc: Color, content: @Composable () -> Unit) {
    Box(
        modifier.size(size).clip(CircasoShapes.circle).background(disc).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Mark(vector: ImageVector, size: Dp, tint: Color) {
    Image(rememberVectorPainter(vector), contentDescription = null, modifier = Modifier.size(size * GlyphRatio), colorFilter = ColorFilter.tint(tint))
}

@Composable
private fun RemoteMark(key: String, symbol: String, size: Dp, tint: Color) {
    // A mark Coil already holds in memory (a row scrolling back, a tab coming back) shows at once; only a real
    // network or disk load fades the letters out.
    val shown = remember(key) { Animatable(0f) }
    var loadedFrom by remember(key) { mutableStateOf<DataSource?>(null) }
    val still = CircasoTheme.reduceMotion
    LaunchedEffect(loadedFrom) {
        when (loadedFrom) {
            null -> shown.snapTo(0f)
            DataSource.MEMORY_CACHE -> shown.snapTo(1f)
            else -> if (still) shown.snapTo(1f) else shown.animateTo(1f, tween(CircasoMotion.QuickMs))
        }
    }
    Letters(symbol, size, tint, Modifier.graphicsLayer { alpha = 1f - shown.value })
    coil3.compose.AsyncImage(
        model = CoinIconData(key),
        contentDescription = null,
        modifier = Modifier.size(size * GlyphRatio).graphicsLayer { alpha = shown.value },
        colorFilter = ColorFilter.tint(tint),
        onSuccess = { loadedFrom = it.result.dataSource },
        onError = { e ->
            loadedFrom = null
            if ((e.result.throwable as? coil3.network.HttpException)?.response?.code == 404) MissingIcons += key
        },
    )
}

@Composable
private fun Letters(text: String, size: Dp, ink: Color, modifier: Modifier = Modifier) {
    val t = CircasoTheme.typography
    val shown = text.take(4)
    // Sized to the disc like the design's `cx-avatar` (13 of 44 for "USDT"), and not grown by the font scale: the badge
    // is decorative and the row beside it already says the coin at the reader's text size.
    val density = LocalDensity.current
    val fontSize = with(density) { (size * if (shown.length <= 2) 0.36f else 0.30f).toSp() / density.fontScale }
    BasicText(shown, modifier, style = t.caption1.copy(color = ink, fontWeight = FontWeight.SemiBold, fontSize = fontSize), maxLines = 1)
}
