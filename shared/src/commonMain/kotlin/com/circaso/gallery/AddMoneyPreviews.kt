package com.circaso.gallery

import androidx.compose.runtime.Composable
import com.circaso.feature.ramp.domain.RampAmount
import com.circaso.feature.ramp.domain.RampKyc
import com.circaso.feature.ramp.domain.RampLimits
import com.circaso.feature.ramp.domain.RampOrder
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.ramp.domain.RampQuote
import com.circaso.feature.ramp.domain.RampStatus
import com.circaso.feature.ramp.presentation.AddMoneyContent
import com.circaso.feature.ramp.presentation.AddMoneyFailure
import com.circaso.feature.ramp.presentation.AddMoneyStep
import com.circaso.feature.ramp.presentation.AddMoneyUiState
import com.circaso.feature.ramp.presentation.RampBlock
import com.circaso.feature.wallet.domain.WalletNetwork

private fun kes(whole: String) = RampAmount("KES", whole + "00", whole)
private val usdc = RampAmount("USDC", "15310000", "15.31")
private const val Masked = "+254 7•• ••• 678"
private val status = RampStatus(
    canTrade = true, network = WalletNetwork.TESTNET, kyc = RampKyc.NotRequired, accountActive = true, blockchains = listOf("BASE-SEPOLIA"),
    merchantOnline = true, openOrderId = null, limits = RampLimits("KES", kes("100"), kes("150000"), kes("300000")), feePercent = "1.5",
)
private val quote = RampQuote("q1", "BASE-SEPOLIA", kes("2000"), usdc, "128.7", kes("30"), null)
private fun order(state: RampOrderState, reason: String? = null) =
    RampOrder("0c9f6a52-7a1e-4d1b-9d55-1f3a6f0f2b11", state, WalletNetwork.TESTNET, "BASE-SEPOLIA", kes("2000"), usdc, "128.7", kes("30"), Masked, null, reason, null, null)

/**
 * Add money (design E2/E3, the ramp), `-preview=`: every step and every state the backend has, from sample data.
 * `add-money` (amount), `-loading`, `-offline`, `-error`, `-low`, `-quote-failed`, `-review`, `-review-phone`,
 * `-review-expired`, `-review-sending`, `-review-failed`, `-unavailable`, `-verify`, `-restricted`, `-busy`, and the
 * order: `-prompt`, `-prompt-offline`, `-paid`, `-sending`, `-done`, `-failed`, `-expired`, `-cancelled`, `-review-order`;
 * the full list is the `when` below.
 */
@Composable
internal fun AddMoneyPreview(name: String): Boolean {
    val amount = AddMoneyUiState(step = AddMoneyStep.Amount, status = status, blockchain = "BASE-SEPOLIA", amountText = "2000", maskedPhone = Masked, testnet = true)
    val review = amount.copy(step = AddMoneyStep.Review, quote = quote, secondsLeft = 42)
    fun onOrder(state: RampOrderState, reason: String? = null, paid: Boolean = false) = amount.copy(step = AddMoneyStep.Order, order = order(state, reason), everPaid = paid)
    val state = when (name) {
        "add-money" -> amount
        "add-money-empty" -> amount.copy(amountText = "")
        "add-money-loading" -> AddMoneyUiState(testnet = true)
        "add-money-loading-order" -> AddMoneyUiState(testnet = true, loadingOrder = true)
        // Back from a review: the last price says roughly what another amount buys.
        "add-money-estimate" -> amount.copy(amountText = "5000", lastQuote = quote)
        "add-money-max" -> amount.copy(amountText = "150000")
        "add-money-offline" -> amount.copy(online = false)
        "add-money-error" -> AddMoneyUiState(step = AddMoneyStep.LoadFailed, failure = AddMoneyFailure("We couldn't open Add Money right now. No M-Pesa request was sent. Please try again in a moment.", "req_8f2a1c · ramp.settings_missing"))
        "add-money-error-offline" -> AddMoneyUiState(step = AddMoneyStep.LoadFailed, online = false, failure = AddMoneyFailure("Adding money needs a connection. Nothing was sent.", null))
        "add-money-low" -> amount.copy(amountText = "50")
        "add-money-quote-failed" -> amount.copy(failure = AddMoneyFailure("Nobody can fill that amount right now. Try a smaller amount, or try again soon. Nothing was taken.", "req_8f2a1c"))
        "add-money-review" -> review
        "add-money-review-phone" -> review.copy(needsPhone = true)
        "add-money-review-phone-error" -> review.copy(needsPhone = true, phoneText = "0712 000 111", phoneError = "That isn't the number your account is verified with. It ends in 678.")
        "add-money-review-expired" -> review.copy(secondsLeft = 0)
        "add-money-review-repriced" -> review.copy(repriced = true, secondsLeft = 58)
        "add-money-review-sending" -> review.copy(placing = true)
        "add-money-review-failed" -> review.copy(failure = AddMoneyFailure("We couldn't reach M-Pesa. Nothing was taken. Try again in a moment.", "req_8f2a1c"))
        // `ramp.merchant_unavailable` (409): the seller can't take the payment right now.
        "add-money-review-merchant" -> review.copy(failure = AddMoneyFailure("We couldn't start this payment right now. Nothing was taken. Please try again in a moment.", "req_8f2a1c"))
        "add-money-review-low" -> review.copy(secondsLeft = 8)
        "add-money-unavailable" -> AddMoneyUiState(step = AddMoneyStep.Blocked, block = RampBlock.Unavailable)
        "add-money-verify" -> AddMoneyUiState(step = AddMoneyStep.Blocked, block = RampBlock.Verify)
        "add-money-restricted" -> AddMoneyUiState(step = AddMoneyStep.Blocked, block = RampBlock.Restricted)
        "add-money-busy" -> AddMoneyUiState(step = AddMoneyStep.Blocked, block = RampBlock.NoMerchant)
        "add-money-prompt" -> onOrder(RampOrderState.PaymentRequested)
        "add-money-prompt-offline" -> onOrder(RampOrderState.PaymentRequested).copy(online = false)
        "add-money-prompt-cancel" -> onOrder(RampOrderState.PaymentRequested).copy(cancelNote = "The M-Pesa request is still open on your phone. Dismiss it there and this ends by itself.")
        "add-money-paid" -> onOrder(RampOrderState.Paid, paid = true)
        "add-money-sending" -> onOrder(RampOrderState.Releasing, paid = true)
        "add-money-done" -> onOrder(RampOrderState.Completed, paid = true)
        "add-money-failed" -> onOrder(RampOrderState.Failed, "payment_failed")
        "add-money-expired" -> onOrder(RampOrderState.Expired, "expired")
        "add-money-cancelled" -> onOrder(RampOrderState.Cancelled, "cancelled")
        // Money may have gone: the careful words, the reference, Contact Support (never Try Again).
        "add-money-failed-paid" -> onOrder(RampOrderState.Failed, "release_failed", paid = true)
        "add-money-expired-paid" -> onOrder(RampOrderState.Expired, "paid_after_expiry", paid = true)
        "add-money-requesting" -> onOrder(RampOrderState.Reserved)
        "add-money-unknown" -> onOrder(RampOrderState.Unknown)
        "add-money-stale" -> onOrder(RampOrderState.Releasing, paid = true).copy(stale = true)
        "add-money-high" -> amount.copy(amountText = "200000")
        "add-money-whole" -> amount.copy(wholeOnlyHint = true)
        "add-money-chains" -> amount.copy(status = status.copy(blockchains = listOf("BASE-SEPOLIA", "SOL-DEVNET")))
        "add-money-order-unread" -> AddMoneyUiState(step = AddMoneyStep.LoadFailed, orderUnread = true, failure = AddMoneyFailure("We couldn't load this top-up just now. It isn't affected: if you approved the M-Pesa request, your USDC still arrives.", "req_8f2a1c"))
        "add-money-review-order" -> onOrder(RampOrderState.Review, "payment_mismatch", paid = true)
        else -> return false
    }
    AddMoneyContent(state, onBack = {})
    return true
}
