package com.circaso.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.domain.Amount
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.presentation.ExternalStep
import com.circaso.feature.wallet.presentation.PayFailure
import com.circaso.feature.wallet.presentation.PayFee
import com.circaso.feature.wallet.presentation.RecentAddress
import com.circaso.feature.wallet.presentation.SendPage
import com.circaso.feature.wallet.presentation.SendToWalletContent
import com.circaso.feature.wallet.presentation.SendToWalletUiState
import kotlinx.coroutines.delay

/**
 * Send to a Wallet, redesigned (2026-11-24): `send-wallet` (the recipient page with recent addresses),
 * `-address` (an address typed, the networks that can pay it), `-scanned` (filled from a code that asked for ETH),
 * `-scan-refused` (a code for the other network), `-amount`, `-amount-over`, `-review`, `-signing`, `-failed`, `-sent`,
 * `-delivered`, `-sent-hash`, `-sent-failed`, `-checking`, `-problem`, `-max`, `-no-network`, `-gas`, `-review-offline`, `-group`, `-group-sent`, `-not-owner`, `-live` (review → PIN → sent → delivered on a loop), `-offline`, `-loading`, `-solana`. Any of them
 * takes `-dark`, `-hc`, `-dark-hc` and `-still` (Reduce Motion), in that order: `send-wallet-review-dark-still`.
 */
@Composable
internal fun SendWalletPreview(name: String): Boolean {
    if (!name.startsWith("send-wallet")) return false
    var base = name
    val still = base.endsWith("-still").also { if (it) base = base.removeSuffix("-still") }
    val hc = base.endsWith("-hc").also { if (it) base = base.removeSuffix("-hc") }
    val dark = base.endsWith("-dark").also { if (it) base = base.removeSuffix("-dark") }
    if (!still && !hc && !dark) return SendWalletScreen(name)
    var shown = true
    CircasoTheme(darkTheme = dark || (!hc && CircasoTheme.colors.isDark), highContrast = hc) {
        CompositionLocalProvider(com.circaso.designsystem.platform.LocalReduceMotion provides (still || CircasoTheme.reduceMotion)) {
            shown = SendWalletScreen(base)
        }
    }
    return shown
}

private const val To = "0x7a3F09c2E4b0d81aF52c3e9B6d0A71c4F21e4b00"
private const val SolTo = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"

private fun twoChains() = sampleWallet().let { w ->
    w.copy(
        chains = w.chains + listOf(
            WalletChain("MATIC-AMOY", "0x7a3f0000000000000000000000000000001e4b00", "ACTIVE", listOf(Asset("USDC", "0x41E94Eb019C0762f9Bfcf9Fb1E58725BfB0e7582", 6, "18000000", "18", null)), true),
            WalletChain("SOL-DEVNET", "So1AbCdEf0000000000000000000000000000002f9", "ACTIVE", listOf(Asset("USDC", "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU", 6, "5000000", "5", null)), true),
        ),
    )
}

private val recents = listOf(
    RecentAddress("0x51c0aa71b2c94e0f8d3a6b5c7e9f1a2b3c4d5e6f", "BASE-SEPOLIA", nowMillis() - 3_600_000),
    RecentAddress(To, "MATIC-AMOY", nowMillis() - 86_400_000 * 2),
    RecentAddress(SolTo, "SOL-DEVNET", nowMillis() - 86_400_000 * 9),
)

private val groupFrom = com.circaso.feature.wallet.presentation.NamedWallet("gw-1", "g1", "Kilimani Chama", null, com.circaso.feature.wallet.domain.WalletOwnerType.Group, true, com.circaso.feature.wallet.domain.WalletStatus.Active, "me")

private fun base() = SendToWalletUiState(canScan = true, wallet = twoChains(), blockchain = "BASE-SEPOLIA", testnet = true, recent = recents)

private fun reviewed() = base().copy(
    step = ExternalStep.Review, page = SendPage.Amount, address = To, checkedAddress = To.lowercase(), amountText = "50", note = "To Binance",
    fee = PayFee.Quoted("250000", 50),
)

private fun sent(state: TransferState, hash: String? = null) = reviewed().copy(
    step = ExternalStep.Done,
    result = Transfer(
        "t-sent-1", TransferDirection.Out, state, "BASE-SEPOLIA", "0xme", null, To.lowercase(), Amount("USDC", "50000000", "50"), Amount("USDC", "250000", "0.25"),
        null, hash, null, null, "To Binance", nowMillis(), nowMillis(),
    ),
)

@Composable
private fun SendWalletScreen(name: String): Boolean {
    val state: SendToWalletUiState = when (name) {
        "send-wallet" -> base()
        "send-wallet-address" -> base().copy(address = To)
        "send-wallet-scanned" -> base().copy(address = To, blockchain = "MATIC-AMOY", notice = "That code asks for POL, not USDC. The address is filled in: choose how much USDC to send.")
        "send-wallet-scan-refused" -> base().copy(scanProblem = "That code is for USDC on Base Mainnet. This is your wallet on Testnet: switch to Mainnet on the Wallet tab to pay it.")
        "send-wallet-solana" -> base().copy(address = SolTo, blockchain = "SOL-DEVNET")
        "send-wallet-loading" -> SendToWalletUiState(canScan = true, testnet = true)
        "send-wallet-offline" -> base().copy(online = false, address = To, page = SendPage.Amount, amountText = "20")
        "send-wallet-amount" -> base().copy(address = To, page = SendPage.Amount, amountText = "50", note = "")
        "send-wallet-amount-over" -> base().copy(address = To, page = SendPage.Amount, amountText = "500")
        "send-wallet-review" -> reviewed()
        "send-wallet-signing" -> reviewed().copy(step = ExternalStep.Signing)
        "send-wallet-failed" -> reviewed().copy(failure = PayFailure("your wallet", "Something went wrong while signing. Nothing was sent.", "req_N2sX8c"))
        "send-wallet-sent" -> sent(TransferState.Submitted)
        "send-wallet-delivered" -> sent(TransferState.Confirmed, "0x9f2c4b7a1e0d3c5b8a6f4e2d1c0b9a8f7e6d5c4b3a291807f6e5d4c3b2a1908f")
        "send-wallet-live" -> { LiveSend(); return true }
        "send-wallet-checking" -> base().copy(address = To, page = SendPage.Amount, amountText = "50", checking = true)
        "send-wallet-problem" -> base().copy(address = To, page = SendPage.Amount, amountText = "50", problem = PayFailure("your wallet", "Wallets are busy right now. Please try again in a moment.", "req_7Hq2Lm"))
        "send-wallet-max" -> base().copy(address = To, page = SendPage.Amount, amountText = "249.21", notice = "Max leaves room for the fee: 249.21 USDC can go.")
        "send-wallet-no-network" -> base().copy(wallet = sampleWallet(), address = SolTo)
        "send-wallet-gas" -> base().copy(address = SolTo, blockchain = "SOL-DEVNET", page = SendPage.Amount, amountText = "4", gasShort = true)
        "send-wallet-review-offline" -> reviewed().copy(online = false)
        "send-wallet-sent-failed" -> sent(TransferState.Failed)
        "send-wallet-sent-hash" -> sent(TransferState.Submitted, "0x9f2c4b7a1e0d3c5b8a6f4e2d1c0b9a8f7e6d5c4b3a291807f6e5d4c3b2a1908f")
        "send-wallet-group" -> reviewed().copy(group = true, from = groupFrom)
        "send-wallet-group-sent" -> sent(TransferState.Submitted).copy(group = true, from = groupFrom)
        "send-wallet-not-owner" -> base().copy(group = true, from = groupFrom.copy(canSign = false))
        else -> return false
    }
    SendToWalletContent(state, {})
    return true
}

/** Review, the PIN, sent and delivered on a loop, so the card's motion can be watched. */
@Composable
private fun LiveSend() {
    var state by remember { mutableStateOf(reviewed()) }
    LaunchedEffect(Unit) {
        while (true) {
            state = reviewed(); delay(1800)
            state = reviewed().copy(step = ExternalStep.Signing); delay(2200)
            state = sent(TransferState.Submitted); delay(2600)
            state = sent(TransferState.Confirmed, "0x9f2c4b7a1e0d3c5b8a6f4e2d1c0b9a8f7e6d5c4b3a291807f6e5d4c3b2a1908f"); delay(3200)
        }
    }
    SendToWalletContent(state, {})
}
