package com.circaso.feature.wallet.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SearchPill
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.chainName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class NetworkAssetsUiState(
    val chain: WalletChain? = null,
    val fiat: FiatValue? = null,
    val query: String = "",
) {
    /** Matches the coin's symbol or its name ("usd" finds USDC and USDT, "ether" finds ETH). */
    val shown: List<Asset>
        get() = chain?.let(::assetsByValue).orEmpty().filter { a ->
            val q = query.trim()
            q.isEmpty() || a.symbol.contains(q, ignoreCase = true) || assetName(a.symbol).contains(q, ignoreCase = true)
        }
}

/** Every asset on one network, from the wallet's copy on the phone; See All under a card opens it. */
class NetworkAssetsViewModel(
    observeWallet: ObserveWalletUseCase,
    private val blockchain: String,
    /** A group wallet's assets instead of the person's own; null for the personal wallet. */
    walletId: String? = null,
    observeWalletOf: com.circaso.feature.wallet.domain.ObserveWalletOfUseCase? = null,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val held = if (walletId != null && observeWalletOf != null) observeWalletOf(walletId) else observeWallet()
    val state: StateFlow<NetworkAssetsUiState> = combine(held, query) { wallet, q ->
        NetworkAssetsUiState(wallet?.chains?.firstOrNull { it.blockchain == blockchain }, wallet?.fiat, q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkAssetsUiState())

    fun onQuery(text: String) { query.value = text }
}

@Composable
fun NetworkAssetsScreen(viewModel: NetworkAssetsViewModel, onBack: () -> Unit, onAsset: (blockchain: String, symbol: String) -> Unit) {
    val state by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val network = state.chain?.let { chainName(it.blockchain).substringBefore(" (") }
    CircasoScaffold(grouped = true, topBar = { CircasoNavBar(title = network?.let { "Assets on $it" } ?: "Assets", onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = CircasoSpacing.s4, end = CircasoSpacing.s4, bottom = padding.calculateBottomPadding() + CircasoSpacing.s4),
        ) {
            item(key = "search") { SearchPill(state.query, viewModel::onQuery, "Search assets", Modifier.padding(top = CircasoSpacing.s2, bottom = CircasoSpacing.s3)) }
            val chain = state.chain
            val shown = state.shown
            if (chain == null || shown.isEmpty()) {
                item(key = "empty") {
                    BasicText(
                        if (chain == null) "This network isn't on your wallet." else "No assets match \"${state.query.trim()}\".",
                        style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(horizontal = 4.dp, vertical = CircasoSpacing.s2),
                    )
                }
            } else {
                item(key = "list") {
                    Column(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.surface)) {
                        shown.forEachIndexed { i, asset ->
                            AssetRow(chain, asset, state.fiat, hidden = false) { onAsset(chain.blockchain, asset.symbol) }
                            if (i < shown.lastIndex) RowDivider(Modifier.padding(start = 72.dp))
                        }
                    }
                }
            }
        }
    }
}
