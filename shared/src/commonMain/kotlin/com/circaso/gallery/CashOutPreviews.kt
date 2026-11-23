package com.circaso.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.circaso.designsystem.theme.CircasoTheme
import kotlinx.coroutines.delay
import com.circaso.core.sync.nowMillis
import com.circaso.feature.ramp.domain.KycReasons
import com.circaso.feature.ramp.domain.KycStatus
import com.circaso.feature.ramp.domain.OffRampStatus
import com.circaso.feature.ramp.domain.RampAmount
import com.circaso.feature.ramp.domain.RampDirection
import com.circaso.feature.ramp.domain.RampKyc
import com.circaso.feature.ramp.domain.RampLimits
import com.circaso.feature.ramp.domain.RampOrder
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.ramp.domain.RampQuote
import com.circaso.feature.ramp.domain.RampStatus
import com.circaso.feature.ramp.domain.RampVerification
import com.circaso.feature.ramp.domain.SaleDeposit
import com.circaso.feature.ramp.presentation.AddMoneyFailure
import com.circaso.feature.ramp.presentation.CashOutContent
import com.circaso.feature.ramp.presentation.CashOutStep
import com.circaso.feature.ramp.presentation.CashOutUiState
import com.circaso.feature.ramp.presentation.RampBlock
import com.circaso.feature.ramp.presentation.SaleUnit
import com.circaso.feature.ramp.presentation.SendFee
import com.circaso.feature.ramp.presentation.SendHold
import com.circaso.feature.ramp.presentation.VerifyUi
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletStatus

private fun shillings(whole: String) = RampAmount("KES", whole + "00", whole)
private fun coins(amount: String, minor: String) = RampAmount("USDC", minor, amount)
private const val SaleMasked = "+254 7•• ••• 678"
private val saleLimits = RampLimits("KES", shillings("10"), shillings("5000"), shillings("20000"))
private val saleStatus = RampStatus(
    canTrade = true, network = WalletNetwork.TESTNET, kyc = RampKyc.NotRequired, accountActive = true, blockchains = listOf("BASE-SEPOLIA"),
    merchantOnline = true, openOrderId = null, limits = RampLimits("KES", shillings("100"), shillings("150000"), shillings("300000")), feePercent = "2",
    offRamp = OffRampStatus(open = true, merchantOnline = true, canSell = true, limits = saleLimits),
)
private fun walletWith(minor: String, amount: String) = Wallet(
    WalletStatus.Active,
    listOf(WalletChain("BASE-SEPOLIA", "0x5a3c0000000000000000000000000000000c19e", "ACTIVE", listOf(Asset("USDC", "0x036c", 6, minor, amount, null)), true)),
    emptyList(), null, null, 0L, network = WalletNetwork.TESTNET,
)
private val saleQuote = RampQuote(
    "q1", "BASE-SEPOLIA", coins("10", "10000000"), shillings("1269"), "130", shillings("26"), null,
    direction = RampDirection.OffRamp, providerFee = shillings("5"), worth = shillings("1300"),
)

/**
 * Cash out (E6's place, the ramp's selling side), `-preview=`: every step and state from sample data. The amount
 * (`cash-out`, `-empty`, `-kes`, `-estimating`, `-max`, `-over`, `-low`, `-small`, `-chains`, `-offline`, `-quote-failed`,
 * `-loading`, `-loading-order`, `-error`, `-error-offline`, `-order-unread`), the review (`-review`, `-review-phone`,
 * `-review-phone-error`, `-review-expired`, `-review-low`, `-review-repriced`, `-review-selling`, `-review-failed`), the
 * gates (`-closed`, `-closed-testnet`, `-barred`, `-restricted`, `-busy`, `-no-wallet`, `-verify`, `-verify-done`,
 * `-verify-checking`), and the sale (`-send`, `-send-fee`, `-send-pricing`, `-approving`, `-approval-cancelled`,
 * `-short`, `-other-network`, `-send-failed`, `-send-unknown`, `-send-offline`, `-time-up`, `-cancel-note`, `-sent`,
 * `-funded`, `-paying-out`, `-paying-out-stale`, `-paying-out-offline`, `-done`, `-done-no-receipt`, `-refunding`,
 * `-refunding-late`, `-refunded`, `-refunded-mismatch`, `-expired`, `-expired-sent`, `-cancelled`, `-review-sale`,
 * `-unknown`). Also `-verify-declined`; `-sell-less` (cancel and sell what fits); `-maybe-sent` and `-maybe-sent-expired`
 * (W4 got no answer). The conversion (2026-11-23): `-live` (a sale on a loop: the silver USDC card, then M-Pesa's green
 * card forming as the payout is sent, landing with the haptic and the real chime), `-live-fast` (the same with PAYING_OUT missed), `-converting-early`, `-converting`
 * and `-converting-late` (one frame held), `-landing` (opened on a payout not yet heard: it settles and chimes). Any of
 * them takes `-dark`, `-hc`, `-dark-hc` and `-still` (Reduce Motion), in that order: `cash-out-live-dark-still`. In the wallet: `activity-cash-out`, `activity-cash-out-states`, `transfer-cash-out`,
 * `transfer-cash-out-refunded` (PreviewScreens.kt).
 */
@Composable
internal fun CashOutPreview(name: String): Boolean {
    if (!name.startsWith("cash-out")) return false
    var base = name
    val still = base.endsWith("-still").also { if (it) base = base.removeSuffix("-still") }
    val hc = base.endsWith("-hc").also { if (it) base = base.removeSuffix("-hc") }
    val dark = base.endsWith("-dark").also { if (it) base = base.removeSuffix("-dark") }
    if (!still && !hc && !dark) return CashOutPreviewScreen(name)
    var shown = true
    CircasoTheme(darkTheme = dark || (!hc && CircasoTheme.colors.isDark), highContrast = hc) {
        CompositionLocalProvider(com.circaso.designsystem.platform.LocalReduceMotion provides (still || CircasoTheme.reduceMotion)) {
            shown = CashOutPreviewScreen(base)
        }
    }
    return shown
}

private const val SaleId = "0193a7c2-5d1e-4b8f-9e2a-6c4f1d8b2e11"

/** A sale from the USDC sent to the shillings landing on M-Pesa, on a loop, so the conversion can be watched and recorded. */
@Composable
private fun LiveSale(skipPayingOut: Boolean = false, onSale: (RampOrderState, String?) -> CashOutUiState) {
    var state by remember { mutableStateOf(onSale(RampOrderState.AwaitingTransfer, null).copy(sent = true)) }
    LaunchedEffect(Unit) {
        while (true) {
            state = onSale(RampOrderState.AwaitingTransfer, null).copy(sent = true)
            delay(2_400)
            state = onSale(RampOrderState.Funded, null)
            delay(2_400)
            // A poll can miss PAYING_OUT altogether: the card then turns on COMPLETED, at once.
            if (!skipPayingOut) { state = onSale(RampOrderState.PayingOut, null); delay(2_600) }
            state = onSale(RampOrderState.Completed, "TKN3H2X1QP").copy(celebrate = SaleId)
            delay(6_000)
        }
    }
    CashOutContent(state, onBack = {}, onLanded = {
        state = state.copy(celebrate = null)
        // The real chime, as the app plays it (silent switch and all).
        org.koin.mp.KoinPlatform.getKoinOrNull()?.getOrNull<com.circaso.core.platform.PaymentChime>()?.play()
    })
}

@Composable
private fun CashOutPreviewScreen(name: String): Boolean {
    val wallet = walletWith("25500000", "25.5")
    val amount = CashOutUiState(
        step = CashOutStep.Amount, status = saleStatus, wallet = wallet, blockchain = "BASE-SEPOLIA", amountText = "10", maskedPhone = SaleMasked, testnet = true,
        estimate = saleQuote, estimateFor = "Usdc:10000000:BASE-SEPOLIA",
    )
    val review = amount.copy(step = CashOutStep.Review, quote = saleQuote, secondsLeft = 42)
    val deadline = nowMillis() + 14 * 60 * 1000L + 32_000L
    fun sale(state: RampOrderState, reason: String? = null, receipt: String? = null, txHash: String? = null) = RampOrder(
        SaleId, state, WalletNetwork.TESTNET, "BASE-SEPOLIA", saleQuote.pay, saleQuote.receive, "130", shillings("26"), SaleMasked, txHash, reason,
        deadline, null, direction = RampDirection.OffRamp, providerFee = shillings("5"),
        deposit = if (state == RampOrderState.AwaitingTransfer) SaleDeposit("0x5a3c0000000000000000000000000000000beef", "BASE-SEPOLIA", "USDC", "10000000", "10", deadline) else null,
        receipt = receipt,
    )
    fun onSale(state: RampOrderState, reason: String? = null, receipt: String? = null, sent: Boolean = state != RampOrderState.AwaitingTransfer) =
        amount.copy(step = CashOutStep.Order, order = sale(state, reason, receipt), sent = sent, sendFee = SendFee.Free, depositLeft = if (state == RampOrderState.AwaitingTransfer) 872 else null)
    val awaiting = onSale(RampOrderState.AwaitingTransfer)
    val state = when (name) {
        "cash-out" -> amount
        "cash-out-empty" -> amount.copy(amountText = "", estimate = null, estimateFor = null)
        "cash-out-kes" -> amount.copy(unit = SaleUnit.Kes, amountText = "1269", estimateFor = "Kes:126900:BASE-SEPOLIA")
        "cash-out-estimating" -> amount.copy(amountText = "12", estimate = null, estimateFor = null, estimating = true)
        "cash-out-max" -> amount.copy(amountText = "25.5", estimateFor = null, estimate = null, estimating = true)
        "cash-out-over" -> amount.copy(amountText = "40", estimate = null, estimateFor = null)
        "cash-out-low" -> amount.copy(wallet = walletWith("0", "0"), amountText = "", estimate = null, estimateFor = null)
        "cash-out-small" -> amount.copy(amountText = "0.01", estimate = null, estimateFor = null, estimateProblem = "Enter a larger amount: nothing would reach M-Pesa after the fees.")
        "cash-out-chains" -> amount.copy(status = saleStatus.copy(blockchains = listOf("BASE-SEPOLIA", "SOL-DEVNET")))
        "cash-out-offline" -> amount.copy(online = false)
        "cash-out-quote-failed" -> amount.copy(failure = AddMoneyFailure("We can't price a sale right now. Nothing left your wallet. Try again later.", "req_8f2a1c"))
        "cash-out-loading" -> CashOutUiState(testnet = true)
        "cash-out-loading-order" -> CashOutUiState(testnet = true, loadingOrder = true)
        "cash-out-error" -> CashOutUiState(step = CashOutStep.LoadFailed, failure = AddMoneyFailure("We couldn't open Cash Out right now. Nothing left your wallet. Please try again in a moment.", "req_8f2a1c"))
        "cash-out-error-offline" -> CashOutUiState(step = CashOutStep.LoadFailed, online = false)
        "cash-out-order-unread" -> CashOutUiState(step = CashOutStep.LoadFailed, orderUnread = true, failure = AddMoneyFailure("We couldn't load this sale just now. It isn't affected: if you sent the USDC, the sale carries on, and your money is either paid out or comes back.", "req_8f2a1c"))
        "cash-out-review" -> review
        "cash-out-review-phone" -> review.copy(needsPhone = true)
        "cash-out-review-phone-error" -> review.copy(needsPhone = true, phoneText = "0712 000 111", phoneError = "That isn't the number your account is verified with. It ends in 678.")
        "cash-out-review-expired" -> review.copy(secondsLeft = 0)
        "cash-out-review-low" -> review.copy(secondsLeft = 7)
        "cash-out-review-repriced" -> review.copy(repriced = true, secondsLeft = 58)
        "cash-out-review-selling" -> review.copy(placing = true)
        "cash-out-review-failed" -> review.copy(failure = AddMoneyFailure("No one can buy this amount right now. Try a smaller amount, or try again later. Nothing left your wallet.", "req_8f2a1c"))
        "cash-out-closed" -> CashOutUiState(step = CashOutStep.Blocked, block = RampBlock.Unavailable)
        "cash-out-closed-testnet" -> CashOutUiState(step = CashOutStep.Blocked, block = RampBlock.Unavailable, testnet = true)
        "cash-out-barred" -> CashOutUiState(step = CashOutStep.Blocked, block = RampBlock.Barred)
        "cash-out-restricted" -> CashOutUiState(step = CashOutStep.Blocked, block = RampBlock.Restricted)
        "cash-out-busy" -> CashOutUiState(step = CashOutStep.Blocked, block = RampBlock.NoMerchant)
        "cash-out-no-wallet" -> CashOutUiState(step = CashOutStep.Blocked, block = RampBlock.NoWallet)
        "cash-out-verify" -> CashOutUiState(step = CashOutStep.Blocked, block = RampBlock.Verify, testnet = true, verify = VerifyUi(RampVerification(KycStatus.None, required = true, canStart = true)))
        "cash-out-verify-done" -> CashOutUiState(step = CashOutStep.Blocked, block = RampBlock.Verify, testnet = true, verify = VerifyUi(RampVerification(KycStatus.Verified, required = true, canStart = false), verified = true))
        "cash-out-verify-checking" -> CashOutUiState(step = CashOutStep.Blocked, block = RampBlock.Verify, testnet = true, verify = VerifyUi(RampVerification(KycStatus.Pending, required = true, canStart = false, submitted = true), checking = true))
        "cash-out-verify-declined" -> CashOutUiState(step = CashOutStep.Blocked, block = RampBlock.Verify, testnet = true, verify = VerifyUi(RampVerification(KycStatus.Declined, required = true, canStart = false, reason = KycReasons.Blocked)))
        // The sale waits for this phone's USDC.
        "cash-out-send" -> awaiting.copy(sendHold = null)
        "cash-out-send-fee" -> awaiting.copy(sendFee = SendFee.Quoted("50000", "wq1"))
        "cash-out-send-pricing" -> awaiting.copy(sendFee = null, pricingSend = true)
        "cash-out-approving" -> awaiting.copy(sending = true)
        "cash-out-approval-cancelled" -> awaiting.copy(sendHold = SendHold.Cancelled)
        "cash-out-short", "cash-out-sell-less" -> awaiting.copy(sendFee = SendFee.Quoted("50000", "wq1"), sendHold = SendHold.Short, wallet = walletWith("10000000", "10"))
        "cash-out-other-network" -> awaiting.copy(sendHold = SendHold.OtherNetwork)
        "cash-out-maybe-sent" -> awaiting.copy(sendHold = SendHold.Unknown, maybeSent = true, depositLeft = 0, sendFailure = AddMoneyFailure("We couldn't confirm the transfer.", "req_8f2a1c"))
        "cash-out-maybe-sent-expired" -> onSale(RampOrderState.Expired, "never_funded", sent = false).copy(maybeSent = true)
        "cash-out-send-failed" -> awaiting.copy(sendHold = SendHold.Failed, sendFailure = AddMoneyFailure("Too many wrong PIN tries. Try again in 30 minutes.", "req_8f2a1c"))
        "cash-out-send-unknown" -> awaiting.copy(sendHold = SendHold.Unknown, sendFailure = AddMoneyFailure("We couldn't confirm the transfer.", "req_8f2a1c"))
        "cash-out-send-offline" -> awaiting.copy(online = false)
        "cash-out-time-up" -> awaiting.copy(depositLeft = 0)
        "cash-out-cancel-note" -> awaiting.copy(cancelNote = "Your USDC has already reached Circaso's partner, so this sale can't be cancelled. It finishes by itself, or your USDC comes back.")
        "cash-out-sent" -> onSale(RampOrderState.AwaitingTransfer, sent = true)
        "cash-out-funded" -> onSale(RampOrderState.Funded)
        "cash-out-paying-out" -> onSale(RampOrderState.PayingOut)
        "cash-out-paying-out-stale" -> onSale(RampOrderState.PayingOut).copy(stale = true)
        "cash-out-paying-out-offline" -> onSale(RampOrderState.PayingOut).copy(online = false)
        "cash-out-done" -> onSale(RampOrderState.Completed, receipt = "TKN3H2X1QP")
        "cash-out-done-no-receipt" -> onSale(RampOrderState.Completed)
        "cash-out-refunding" -> onSale(RampOrderState.Refunding, "payout_failed")
        "cash-out-refunding-late" -> onSale(RampOrderState.Refunding, "late_transfer")
        "cash-out-refunded" -> onSale(RampOrderState.Refunded, "payout_failed")
        "cash-out-refunded-mismatch" -> onSale(RampOrderState.Refunded, "amount_mismatch")
        "cash-out-expired" -> onSale(RampOrderState.Expired, "never_funded", sent = false)
        "cash-out-expired-sent" -> onSale(RampOrderState.Expired, "never_funded", sent = true)
        "cash-out-cancelled" -> onSale(RampOrderState.Cancelled, sent = false)
        "cash-out-review-sale" -> onSale(RampOrderState.Review, "payout_unconfirmed")
        "cash-out-unknown" -> onSale(RampOrderState.Unknown)
        // The conversion (2026-11-23): played whole, held at one frame, and landing.
        "cash-out-live" -> { LiveSale { st, receipt -> onSale(st, receipt = receipt) }; return true }
        "cash-out-live-fast" -> { LiveSale(skipPayingOut = true) { st, receipt -> onSale(st, receipt = receipt) }; return true }
        "cash-out-converting-early" -> { CashOutContent(onSale(RampOrderState.PayingOut), onBack = {}, frozenAt = 0.22f); return true }
        "cash-out-converting" -> { CashOutContent(onSale(RampOrderState.PayingOut), onBack = {}, frozenAt = 0.5f); return true }
        "cash-out-converting-late" -> { CashOutContent(onSale(RampOrderState.PayingOut), onBack = {}, frozenAt = 0.8f); return true }
        "cash-out-landing" -> onSale(RampOrderState.Completed, receipt = "TKN3H2X1QP").copy(celebrate = SaleId)
        else -> return false
    }
    CashOutContent(state, onBack = {})
    return true
}
