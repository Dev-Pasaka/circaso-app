package com.circaso.feature.wallet.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.AmountDisplay
import com.circaso.designsystem.components.AmountSize
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.DetailRow
import com.circaso.designsystem.components.SegmentedControl
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.chainName
import com.circaso.feature.wallet.domain.shortAddress

/** Design E5: an outside address, the network, the amount, the warning, then review and the SDK's PIN. */
@Composable
fun SendToWalletScreen(viewModel: SendToWalletViewModel, onBack: () -> Unit, onOpenTransfer: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    SendToWalletContent(state, onBack, viewModel::onAddress, viewModel::onChain, viewModel::onAmount, viewModel::onReview, viewModel::onBackToForm, viewModel::onConfirm, viewModel::onErrorShown, onOpenTransfer, onScan = viewModel::onScan)
}

@Composable
fun SendToWalletContent(
    state: SendToWalletUiState,
    onBack: () -> Unit,
    onAddress: (String) -> Unit,
    onChain: (String) -> Unit,
    onAmount: (String) -> Unit,
    onReview: () -> Unit,
    onBackToForm: () -> Unit,
    onConfirm: () -> Unit,
    onErrorShown: () -> Unit,
    onOpenTransfer: (String) -> Unit,
    onScan: () -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(state.error) { state.error?.let { toast.show(it); onErrorShown() } }
    CircasoScaffold(
        topBar = { CircasoNavBar(title = when (state.step) { ExternalStep.Review, ExternalStep.Signing -> "Review"; ExternalStep.Done -> "Sent"; else -> "Send to a Wallet" }, onBack = when (state.step) { ExternalStep.Signing -> null; ExternalStep.Review -> onBackToForm; else -> onBack }) },
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
    ) { padding ->
        val chains = state.wallet?.chains?.filter { it.state == "ACTIVE" }.orEmpty()
        val chainNames = chains.map { chainName(it.blockchain) }
        val chainIndex = chains.indexOfFirst { it.blockchain == state.blockchain }.coerceAtLeast(0)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = CircasoSpacing.s4).padding(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
            when (state.step) {
                ExternalStep.Form -> {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                        CircasoTextField(state.address, onAddress, label = "Wallet address", placeholder = "0x… or a Solana address", modifier = Modifier.weight(1f), singleLine = false)
                        CircasoIconButton(CircasoIcon.Clipboard, "Paste address", { clipboard.getText()?.text?.let(onAddress) }, modifier = Modifier.padding(bottom = 6.dp))
                        if (state.canScan) CircasoIconButton(CircasoIcon.Scan, "Scan a QR code", onScan, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    if (chains.size > 1) {
                        BasicText("Network", style = t.footnote.copy(color = c.inkSecondary))
                        SegmentedControl(chainNames.map { it.substringBefore(" (") }, chainIndex, { onChain(chains[it].blockchain) })
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DetailRow("Coin", "USDC")
                        DetailRow("Network", state.blockchain?.let { chainName(it) } ?: "—")
                    }
                    CircasoTextField(
                        state.amountText, onAmount, label = "Amount (USDC)", placeholder = "0.00",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        error = if (state.overBalance) "You have ${MinorUnits.toDisplay(state.balanceMinor, 6)} USDC on this network." else null,
                    )
                    BasicText(listOfNotNull(state.fiatHint, "You have ${MinorUnits.toDisplay(state.balanceMinor, 6)} USDC").joinToString(" · "), style = t.footnote.copy(color = c.inkSecondary))
                    Banner("Make sure the receiving wallet supports USDC on ${state.blockchain?.let { chainName(it) } ?: "this network"}. Crypto sent to the wrong network can't be recovered.", CircasoIcon.Warning, tone = BannerTone.Warning)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DetailRow("Network fee", "No fee")
                        DetailRow("They receive", state.amountMinor?.let { MinorUnits.toDisplay(it, 6) + " USDC" } ?: "—")
                    }
                    CircasoButton("Review", onReview, size = ButtonSize.Large, block = true, enabled = state.canReview, busy = state.checking)
                    if (!state.online) BasicText("You need a connection to send money.", style = t.footnote.copy(color = c.inkSecondary))
                }
                ExternalStep.Review, ExternalStep.Signing -> {
                    Spacer(Modifier.height(CircasoSpacing.s4))
                    AmountDisplay("USDC", state.amountMinor?.let { MinorUnits.toDisplay(it, 6) } ?: "0", sub = state.fiatHint, size = AmountSize.Medium)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DetailRow("To", state.checkedAddress?.let { shortAddress(it) } ?: "")
                        DetailRow("Network", state.blockchain?.let { chainName(it) } ?: "—")
                        DetailRow("Fee", "No fee")
                    }
                    BasicText(state.checkedAddress.orEmpty(), style = t.footnote.copy(color = c.inkSecondary))
                    Banner("Payments on chain can't be reversed. Check the address and network once more.", CircasoIcon.Warning, tone = BannerTone.Warning)
                    CircasoButton(if (state.step == ExternalStep.Signing) "Waiting for your PIN…" else "Confirm and Enter PIN", onConfirm, size = ButtonSize.Large, block = true, enabled = state.step == ExternalStep.Review && state.online, busy = state.step == ExternalStep.Signing)
                }
                ExternalStep.Done -> {
                    Spacer(Modifier.height(CircasoSpacing.s8))
                    BasicText("On its way", style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
                    BasicText("It settles on chain in a moment. You can follow it under Activity.", style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
                    state.result?.let { CircasoButton("View Payment", { onOpenTransfer(it.transferId) }, size = ButtonSize.Large, block = true) }
                    CircasoButton("Done", onBack, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
                }
            }
        }
    }
}
