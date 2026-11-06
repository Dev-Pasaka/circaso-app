package com.circaso.feature.wallet.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.theme.CircasoMotion
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.platform.localDayIndex
import com.circaso.core.platform.shareText
import com.circaso.core.platform.threadDayLabel
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.AmountDisplay
import com.circaso.designsystem.components.AmountSize
import com.circaso.designsystem.components.AmountTone
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CoinIcon
import com.circaso.designsystem.components.NetworkIcon
import com.circaso.designsystem.components.DetailRow
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SectionHeader
import com.circaso.designsystem.components.SuggestionChip
import com.circaso.designsystem.components.SearchPill
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.domain.ChainStatus
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.chainName
import com.circaso.feature.wallet.domain.formatFiat
import com.circaso.feature.wallet.domain.shortAddress
import com.circaso.feature.wallet.domain.usdcToFiat

/** Design C7: one payment. */
@Composable
fun TransferScreen(viewModel: TransferViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    TransferContent(state, onBack)
}

@Composable
fun TransferContent(state: TransferUiState, onBack: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val transfer = state.transfer
    val toast = rememberToastState()
    CircasoScaffold(
        topBar = { CircasoNavBar(title = "Payment", onBack = onBack, trailing = { if (transfer != null) CircasoIconButton(CircasoIcon.Share, "Share receipt", { shareText(receiptText(transfer, state.fiat), "Circaso payment") }) }) },
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
    ) { padding ->
        if (transfer == null) {
            if (state.error != null) {
                Column(Modifier.fillMaxSize().padding(CircasoSpacing.s6), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(state.error, style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                }
            } else {
                // The receipt's own shape while it loads (who, the amount, the rows), so nothing jumps when it arrives.
                Column(
                    Modifier.fillMaxSize().padding(CircasoSpacing.s4).semantics(mergeDescendants = true) { contentDescription = "Loading the payment" },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
                ) {
                    Spacer(Modifier.height(CircasoSpacing.s2))
                    androidx.compose.foundation.layout.Box(Modifier.size(CircasoSizes.avatarLg).clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(c.fill))
                    androidx.compose.foundation.layout.Box(Modifier.size(160.dp, 34.dp).clip(com.circaso.designsystem.theme.CircasoShapes.md).background(c.fill))
                    repeat(5) { androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(44.dp).clip(com.circaso.designsystem.theme.CircasoShapes.md).background(c.fill)) }
                }
            }
            return@CircasoScaffold
        }
        val incoming = transfer.direction == TransferDirection.In
        // A person by name; anyone else by their address, never "Someone".
        val name = transfer.counterpartName
            ?: (if (incoming) transfer.from else transfer.toAddress).takeIf { it.isNotEmpty() }?.let(::shortAddress)
            ?: if (incoming) "An outside wallet" else "An outside wallet"
        val fiat = state.fiat?.takeIf { MinorUnits.isDollar(transfer.amount.asset) }?.let { f -> usdcToFiat(transfer.amount.amountMinor, f.rate)?.let { formatFiat(f.currency, it) } }
        // The server's own decimal for the coin: 6 for USDC, 8 for cirBTC, 18 for ETH.
        // Always from minor units with the coin's decimals, so dollar coins read as money ("15.50", not the server's "15.5").
        val coinNumber = MinorUnits.toDisplay(transfer.amount.amountMinor, MinorUnits.decimalsOf(transfer.amount.asset))
        val usdc = coinNumber + " " + transfer.amount.asset
        val dollar = MinorUnits.isDollar(transfer.amount.asset)
        val when_ = formatLocalTime(transfer.createdAt, LocalTimeStyle.ShortDate) + ", " + formatLocalTime(transfer.createdAt, LocalTimeStyle.Clock)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4).padding(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
            Spacer(Modifier.height(CircasoSpacing.s2))
            if (transfer.counterpartName != null) Avatar(transfer.counterpartName, size = CircasoSizes.avatarLg, imageUrl = transfer.counterpartAvatar) else CoinIcon(transfer.amount.asset, size = CircasoSizes.avatarLg)
            // "KES 2,000" when it has a rate, else the coin itself ("ETH 0.01"): never split the coin text.
            val cur = if (fiat != null) fiat.substringBefore(' ') else transfer.amount.asset
            val num = if (fiat != null) fiat.substringAfter(' ') else coinNumber
            AmountDisplay(cur, num, sub = "${if (incoming) "Received from" else "Sent to"} $name · $when_", size = AmountSize.Medium, tone = if (incoming && transfer.settled) AmountTone.Money else AmountTone.Ink)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DetailRow(if (incoming) "From" else "To", name)
                handleOf(transfer)?.let { DetailRow("Username", it) }
                transfer.note?.let { DetailRow("Note", it) }
                DetailRow("Date", when_)
                DetailRow("Amount in coin", usdc)
                state.fiat?.takeIf { dollar }?.let { DetailRow("Rate", "1 ${transfer.amount.asset} = ${it.currency} ${it.rate.substringBefore('.')}.${it.rate.substringAfter('.', "00").take(2).padEnd(2, '0')}") }
                // Circaso pays the network fee on what its people send; money from outside paid its own.
                // From the fee's minor units (the phone's copy keeps no display string): "KES 12.99 · 0.10 USDC". It
                // said "No fee" for a charged fee before (2026-11-05).
                if (!incoming) DetailRow("Fee", feeText(transfer.fee, state.fiat))
                DetailRow("Status", statusText(transfer))
            }
            transfer.failureReason?.takeIf { transfer.state == TransferState.Failed }?.let { BasicText(it, style = t.footnote.copy(color = c.critical, textAlign = TextAlign.Center)) }
            // On-chain details for anyone who wants to check: both addresses, the hash (tap to copy) and the explorer.
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val chainRows = listOfNotNull(
                transfer.from.takeIf { it.isNotEmpty() }?.let { "From address" to it },
                transfer.toAddress.takeIf { it.isNotEmpty() }?.let { "To address" to it },
                transfer.txHash?.let { "Transaction hash" to it },
            )
            // Design C7: the network and the on-chain details sit behind one Advanced row, closed until asked for.
            var advanced by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clip(com.circaso.designsystem.theme.CircasoShapes.md).background(c.surface)) {
                com.circaso.designsystem.components.ListRow(
                    "Advanced", sub = chainName(transfer.blockchain),
                    trailing = {
                        val turn by androidx.compose.animation.core.animateFloatAsState(if (advanced) 90f else 0f, androidx.compose.animation.core.tween(if (CircasoTheme.reduceMotion) 0 else CircasoMotion.QuickMs), label = "chevron")
                        CircasoIconImage(CircasoIcon.ChevronRight, c.inkTertiary, Modifier.size(16.dp).graphicsLayer { rotationZ = turn })
                    },
                    onClick = { advanced = !advanced },
                )
                androidx.compose.animation.AnimatedVisibility(
                    advanced,
                    enter = if (CircasoTheme.reduceMotion) androidx.compose.animation.fadeIn() else androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                    exit = if (CircasoTheme.reduceMotion) androidx.compose.animation.fadeOut() else androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
                ) {
                    Column {
                        RowDivider()
                        com.circaso.designsystem.components.ListRow("Network", sub = chainName(transfer.blockchain))
                        chainRows.forEach { (label, value) ->
                            RowDivider()
                            com.circaso.designsystem.components.ListRow(
                                label, sub = shortAddress(value),
                                modifier = Modifier.semantics { contentDescription = "Copy ${label.lowercase()}" },
                                trailing = { CircasoIconImage(CircasoIcon.Copy, c.inkSecondary, Modifier.size(18.dp)) },
                                onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(value)); scope.launch { toast.show("Copied") } },
                            )
                        }
                        explorerUrl(transfer.blockchain, transfer.txHash)?.let { url ->
                            RowDivider()
                            com.circaso.designsystem.components.ListRow("View on explorer", trailing = { RowChevron() }, onClick = { com.circaso.core.platform.openUrl(url) })
                        }
                    }
                }
            }
            CircasoButton("Share Receipt", { shareText(receiptText(transfer, state.fiat), "Circaso payment") }, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
        }
    }
}

/** The public block explorer page for a transaction on [blockchain]; null when there is no hash yet. */
internal fun explorerUrl(blockchain: String, txHash: String?): String? {
    val hash = txHash?.takeIf { it.isNotBlank() } ?: return null
    return when (blockchain.uppercase()) {
        "BASE" -> "https://basescan.org/tx/$hash"
        "BASE-SEPOLIA" -> "https://sepolia.basescan.org/tx/$hash"
        "ETH" -> "https://etherscan.io/tx/$hash"
        "ETH-SEPOLIA" -> "https://sepolia.etherscan.io/tx/$hash"
        "MATIC" -> "https://polygonscan.com/tx/$hash"
        "MATIC-AMOY" -> "https://amoy.polygonscan.com/tx/$hash"
        "SOL" -> "https://explorer.solana.com/tx/$hash"
        "SOL-DEVNET" -> "https://explorer.solana.com/tx/$hash?cluster=devnet"
        else -> null
    }
}

internal fun statusText(t: Transfer) = when (t.state) {
    TransferState.AwaitingSignature -> "Not signed"
    TransferState.Submitted -> "On its way"
    TransferState.Confirmed -> "Completed"
    TransferState.Failed -> "Failed"
    TransferState.Expired -> if (t.failureReason == "cancelled") "Cancelled" else "Expired"
}

/** "@amina" when the other side is a Circaso person and the name isn't already that handle. */
private fun handleOf(t: Transfer): String? = t.counterpartHandle?.let { "@$it" }?.takeIf { it != t.counterpartName }

private fun receiptText(t: Transfer, fiat: FiatValue?): String {
    val usdc = t.amount.amount.takeIf { it.isNotEmpty() }?.let { "$it ${t.amount.asset}" } ?: MinorUnits.coinText(t.amount.amountMinor, t.amount.asset)
    val f = fiat?.takeIf { MinorUnits.isDollar(t.amount.asset) }?.let { usdcToFiat(t.amount.amountMinor, it.rate) }?.let { formatFiat(fiat.currency, it) }
    return listOfNotNull(
        "${if (t.direction == TransferDirection.In) "Received" else "Sent"} ${f ?: usdc}" + (if (f != null) " ($usdc)" else ""),
        t.counterpartName?.let { "${if (t.direction == TransferDirection.In) "From" else "To"}: $it" + (handleOf(t)?.let { h -> " ($h)" } ?: "") },
        t.note?.let { "Note: $it" },
        "Network: ${chainName(t.blockchain)}",
        "Status: ${statusText(t)}",
        t.txHash?.let { "Transaction: $it" },
    ).joinToString("\n")
}

/** Design E7. */
@Composable
fun ActivityScreen(viewModel: ActivityViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val now = nowMillis()
    val network = state.blockchain?.let { chainName(it).substringBefore(" (") }
    val title = when {
        state.symbol != null -> "${state.symbol} on $network"
        network != null -> "Activity on $network"
        else -> "Activity"
    }
    CircasoScaffold(topBar = { CircasoNavBar(title = title, onBack = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4)) {
            // An asset's statement opens on what is held, then every payment in it on that network.
            state.symbol?.let { symbol ->
                item(key = "asset-head") {
                    val held = state.held
                    val amount = held?.let { MinorUnits.toDisplay(it.amountMinor, it.decimals) } ?: "0"
                    val worth = held?.fiatAmount?.let { f -> state.fiat?.currency?.let { cur -> formatFiat(cur, f) } }
                    val sent = state.transfers.count { it.direction == TransferDirection.Out && it.settled }
                    val received = state.transfers.count { it.direction == TransferDirection.In && it.settled }
                    Column(
                        Modifier.fillMaxWidth().padding(CircasoSpacing.s4),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                    ) {
                        CoinIcon(symbol, size = CircasoSizes.avatarLg)
                        AmountDisplay(symbol, amount, sub = worth ?: chainName(state.blockchain.orEmpty()), size = AmountSize.Medium)
                        BasicText("$received received · $sent sent", style = t.footnote.copy(color = c.inkSecondary))
                    }
                }
            }
            item(key = "search") {
                SearchPill(state.query, viewModel::onQuery, "Search payments", Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2))
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    items(ActivityFilter.entries) { f -> SuggestionChip(if (f == state.filter) "● ${f.label}" else f.label, { viewModel.onFilter(f) }) }
                }
            }
            if (state.shown.isEmpty()) item { BasicText(if (state.query.isNotBlank()) "No payments match \"${state.query.trim()}\"." else if (state.symbol != null) "No ${state.symbol} payments on this network yet." else if (network != null) "No payments on $network yet." else "Nothing here yet. Payments you send and receive will show here.", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(CircasoSpacing.s4)) }
            val shown = state.shown
            shown.forEachIndexed { i, transfer ->
                val previous = shown.getOrNull(i - 1)
                if (previous == null || localDayIndex(previous.createdAt) != localDayIndex(transfer.createdAt)) item { SectionHeader(threadDayLabel(transfer.createdAt, now), Modifier.padding(top = CircasoSpacing.s2)) }
                item(key = transfer.transferId) {
                    TransferRow(transfer, state.fiat, hidden = false, meta = formatLocalTime(transfer.createdAt, LocalTimeStyle.Clock), onClick = { onOpen(transfer.transferId) })
                    RowDivider(Modifier.padding(start = 72.dp))
                }
            }
        }
    }
}

/** W12: the chains the wallet is on and can join. */
@Composable
fun NetworksScreen(viewModel: NetworksViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    LaunchedEffect(state.notice, state.error) { (state.notice ?: state.error)?.let { toast.show(it); viewModel.onNoticeShown() } }
    CircasoScaffold(topBar = { CircasoNavBar(title = "Networks", onBack = onBack) }, toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) }) { padding ->
        val chains = state.chains
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4)) {
            item { BasicText("Your wallet has its own address on each network. USDC sent on a network you're not on can't reach you.", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(CircasoSpacing.s4)) }
            if (chains == null) item { BasicText(if (state.loading) "Loading…" else state.error ?: "", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(horizontal = CircasoSpacing.s4)) }
            else items(chains.chains, key = { it.blockchain }) { option ->
                val status = when (option.status) { ChainStatus.Enabled -> "On this network"; ChainStatus.Adding -> "Being added"; ChainStatus.Available -> option.assets.joinToString() }
                ListRow(
                    title = chainName(option.blockchain) + if (option.blockchain == chains.defaultChain) " · default" else "",
                    sub = status + if (!option.receiveEnabled) " · retiring" else "",
                    leading = { NetworkIcon(option.blockchain) },
                    trailing = {
                        when (option.status) {
                            ChainStatus.Available -> CircasoButton(if (state.adding == option.blockchain) "Adding…" else "Add", { viewModel.onAdd(option.blockchain) }, style = ButtonStyle.Secondary, size = ButtonSize.Small, enabled = state.adding == null && state.online && option.receiveEnabled, busy = state.adding == option.blockchain)
                            ChainStatus.Adding -> CircasoButton("Finish", { viewModel.onAdd(option.blockchain) }, style = ButtonStyle.Secondary, size = ButtonSize.Small, enabled = state.adding == null && state.online)
                            ChainStatus.Enabled -> Row { BasicText("✓", style = t.headline.copy(color = c.money)) }
                        }
                    },
                )
                RowDivider(Modifier.padding(start = 72.dp))
            }
        }
    }
}

/** The fee a person paid, in their currency first for dollar coins, then the coin; "No fee" only when none was taken. */
private fun feeText(fee: com.circaso.feature.wallet.domain.Amount?, rate: com.circaso.feature.wallet.domain.FiatValue?): String {
    val minor = fee?.amountMinor?.takeIf { it.isNotBlank() && !MinorUnits.isZero(it) } ?: return "No fee"
    val coin = MinorUnits.toDisplay(minor, MinorUnits.decimalsOf(fee.asset)) + " " + fee.asset
    val fiat = rate?.takeIf { MinorUnits.isDollar(fee.asset) }?.let { r -> usdcToFiat(minor, r.rate)?.let { formatFiat(r.currency, it) } }
    return if (fiat != null) "$fiat · $coin" else coin
}
