package com.circaso.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.circaso.core.platform.openInSystemBrowserAndWait
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.ramp.domain.IdentityVerifierBridge
import com.circaso.feature.ramp.domain.NoIdentityVerifier
import kotlinx.coroutines.delay
import com.circaso.core.sync.nowMillis
import com.circaso.feature.ramp.domain.KycReasons
import com.circaso.feature.ramp.domain.KycStatus
import com.circaso.feature.ramp.domain.RampAmount
import com.circaso.feature.ramp.domain.RampKyc
import com.circaso.feature.ramp.domain.RampLimits
import com.circaso.feature.ramp.domain.RampOrder
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.ramp.domain.RampQuote
import com.circaso.feature.ramp.domain.RampStatus
import com.circaso.feature.ramp.domain.RampVerification
import com.circaso.feature.ramp.presentation.AddMoneyContent
import com.circaso.feature.ramp.presentation.AddMoneyFailure
import com.circaso.feature.ramp.presentation.AddMoneyStep
import com.circaso.feature.ramp.presentation.AddMoneyUiState
import com.circaso.feature.ramp.presentation.RampBlock
import com.circaso.feature.ramp.presentation.VerifyTrouble
import com.circaso.feature.ramp.presentation.VerifyUi
import com.circaso.feature.wallet.domain.WalletNetwork

private fun kes(whole: String) = RampAmount("KES", whole + "00", whole)
// KES 2,000 prompted: PayHero's KES 20 for that band, the KES 1,980 left with Circaso's KES 30 inside it.
private val usdc = RampAmount("USDC", "15150000", "15.15")
private const val Masked = "+254 7•• ••• 678"
private val status = RampStatus(
    canTrade = true, network = WalletNetwork.TESTNET, kyc = RampKyc.NotRequired, accountActive = true, blockchains = listOf("BASE-SEPOLIA"),
    merchantOnline = true, openOrderId = null, limits = RampLimits("KES", kes("100"), kes("150000"), kes("300000")), feePercent = "1.5",
)
private val quote = RampQuote("q1", "BASE-SEPOLIA", kes("2000"), usdc, "128.7", kes("30"), null, providerFee = kes("20"))
private fun order(state: RampOrderState, reason: String? = null) =
    RampOrder(OrderId, state, WalletNetwork.TESTNET, "BASE-SEPOLIA", kes("2000"), usdc, "128.7", kes("30"), Masked, null, reason, null, null, providerFee = kes("20"))

/**
 * Add money (design E2/E3, the ramp), `-preview=`: every step and every state the backend has, from sample data.
 * `add-money` (amount), `-loading`, `-offline`, `-error`, `-low`, `-quote-failed`, `-review`, `-review-phone`,
 * `-review-expired`, `-review-sending`, `-review-failed`, `-unavailable`, `-verify`, `-restricted`, `-busy`, and the
 * order: `-prompt`, `-prompt-offline`, `-paid`, `-sending`, `-done`, `-failed`, `-expired`, `-cancelled`, `-review-order`;
 * the full list is the `when` below.
 *
 * Identity verification (E3 and every state after it), all under `add-money-verify`: `-starting`, `-offline`,
 * `-failed`, `-continue`, `-continue-offline`, `-sdk-stopped`, `-more`, `-checking`, `-checking-offline`,
 * `-checking-stale`, `-checking-slow`, `-returned`, `-review`, `-done`, `-declined` (and `-document`, `-unreadable`, `-liveness`, `-face`,
 * `-unknown`), `-declined-wait`, `-final`, `-in-use`, `-blocked`, `-expired`, `-id-expired`, `-expired-wait`, `-tries`,
 * `-tries-open`, `-down`, `-setup`, `-camera`, `-mainnet`; and `add-money-barred` (R1's `blocked`).
 */
/**
 * Debug only, `-preview=add-money-verify-probe` (and `-probe-page`): checks the wiring to Didit that sample data can't,
 * without a server. It starts the installed identity verifier with a token no server made, so the SDK's own screens
 * open over the app, say they couldn't start, and hand back an outcome when left; `-probe-page` opens Didit's site in
 * the system's in-app browser, the fallback's way in. No real check is started and nothing personal is involved.
 */
@Composable
private fun KycProbe(page: Boolean) {
    var said by remember { mutableStateOf(if (page) "Opening the system's in-app browser…" else "Opening Didit's screens with a token no server made…") }
    LaunchedEffect(Unit) {
        delay(1_500)
        said = if (page) "Back from the page: opened = " + openInSystemBrowserAndWait("https://didit.me/")
        else (IdentityVerifierBridge.verifier ?: NoIdentityVerifier).let { "SDK in this build: ${it.available}\nOutcome: " + it.verify("not-a-session") }
    }
    Box(Modifier.fillMaxSize().padding(CircasoSpacing.s6), contentAlignment = Alignment.Center) {
        BasicText(said, style = CircasoTheme.typography.body.copy(color = CircasoTheme.colors.ink, textAlign = TextAlign.Center))
    }
}

/**
 * The conversion on the order page (2026-11-23), `-preview=`: `add-money-live` plays a whole top-up on a loop (the
 * M-Pesa card waiting, coming apart into the USDC card, landing with the haptic and the chime); `-converting-early`,
 * `-converting` (mid-disintegration) and `-converting-late` hold one frame of it; `-landing` opens on a completed
 * order whose landing hasn't been heard (it settles, chimes once). Any Add Money preview takes the endings `-dark`,
 * `-hc`, `-dark-hc` (the theme) and `-still` (Reduce Motion), in that order: `add-money-live-dark-still`.
 */
@Composable
internal fun AddMoneyPreview(name: String): Boolean {
    if (!name.startsWith("add-money")) return false
    var base = name
    val still = base.endsWith("-still").also { if (it) base = base.removeSuffix("-still") }
    val hc = base.endsWith("-hc").also { if (it) base = base.removeSuffix("-hc") }
    val dark = base.endsWith("-dark").also { if (it) base = base.removeSuffix("-dark") }
    if (!still && !hc && !dark) return AddMoneyPreviewScreen(name)
    var shown = true
    CircasoTheme(darkTheme = dark || (!hc && CircasoTheme.colors.isDark), highContrast = hc) {
        androidx.compose.runtime.CompositionLocalProvider(com.circaso.designsystem.platform.LocalReduceMotion provides (still || CircasoTheme.reduceMotion)) {
            shown = AddMoneyPreviewScreen(base)
        }
    }
    return shown
}

private const val OrderId = "0c9f6a52-7a1e-4d1b-9d55-1f3a6f0f2b11"

/** A top-up from the M-Pesa prompt to the USDC landing, on a loop, so the conversion can be watched and recorded. */
@Composable
private fun LiveTopUp(amount: AddMoneyUiState) {
    val waiting = amount.copy(step = AddMoneyStep.Order, order = order(RampOrderState.PaymentRequested))
    var state by remember { mutableStateOf(waiting) }
    LaunchedEffect(Unit) {
        while (true) {
            state = waiting
            delay(3_000)
            state = state.copy(order = order(RampOrderState.Paid), everPaid = true)
            delay(2_400)
            state = state.copy(order = order(RampOrderState.Releasing))
            delay(1_200)
            state = state.copy(order = order(RampOrderState.Completed), celebrate = OrderId)
            delay(6_000)
        }
    }
    AddMoneyContent(state, onBack = {}, onLanded = {
        state = state.copy(celebrate = null)
        // The real chime, as the app plays it (silent switch and all).
        org.koin.mp.KoinPlatform.getKoinOrNull()?.getOrNull<com.circaso.core.platform.PaymentChime>()?.play()
    })
}

@Composable
private fun AddMoneyPreviewScreen(name: String): Boolean {
    if (name == "add-money-verify-probe" || name == "add-money-verify-probe-page") { KycProbe(page = name.endsWith("-page")); return true }
    val amount = AddMoneyUiState(step = AddMoneyStep.Amount, status = status, blockchain = "BASE-SEPOLIA", amountText = "2000", maskedPhone = Masked, testnet = true)
    val review = amount.copy(step = AddMoneyStep.Review, quote = quote, secondsLeft = 42)
    fun onOrder(state: RampOrderState, reason: String? = null, paid: Boolean = false) = amount.copy(step = AddMoneyStep.Order, order = order(state, reason), everPaid = paid)
    // A check as the server describes it, on the verify pages.
    fun verify(status: KycStatus, canStart: Boolean = true, reason: String? = null, retryAt: Long? = null, ui: VerifyUi.() -> VerifyUi = { this }) =
        AddMoneyUiState(step = AddMoneyStep.Blocked, block = RampBlock.Verify, testnet = true, verify = VerifyUi(RampVerification(status, required = true, canStart = canStart, reason = reason, retryAt = retryAt)).ui())
    // Tomorrow morning, whenever the preview is opened.
    val tomorrow = nowMillis() + 14 * 60 * 60 * 1000L
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
        // E3 itself, and the moments around its one button.
        "add-money-verify" -> verify(KycStatus.None)
        "add-money-verify-mainnet" -> verify(KycStatus.None).copy(testnet = false)
        "add-money-verify-starting" -> verify(KycStatus.None) { copy(starting = true) }
        "add-money-verify-offline" -> verify(KycStatus.None).copy(online = false)
        "add-money-verify-failed" -> verify(KycStatus.None) { copy(failure = AddMoneyFailure("We couldn't start your verification right now. Nothing is lost. Please try again in a moment.", "req_8f2a1c · ramp.kyc_failed")) }
        // A check already open: one tap carries on with the same session.
        "add-money-verify-continue" -> verify(KycStatus.Pending)
        "add-money-verify-continue-starting" -> verify(KycStatus.Pending) { copy(starting = true) }
        "add-money-verify-continue-offline" -> verify(KycStatus.Pending).copy(online = false)
        "add-money-verify-sdk-stopped" -> verify(KycStatus.Pending) { copy(failure = AddMoneyFailure("The verification closed before it could finish. Continue, and it opens in your browser instead.", null, calm = true)) }
        "add-money-verify-more" -> verify(KycStatus.Pending, reason = KycReasons.ResubmissionRequested)
        // Everything sent: waiting for the result, which arrives by itself.
        "add-money-verify-checking" -> verify(KycStatus.Pending) { copy(checking = true) }
        // The server's own word that it was submitted (2026-11-23): the same wait, with nothing to start.
        "add-money-verify-submitted" -> AddMoneyUiState(step = AddMoneyStep.Blocked, block = RampBlock.Verify, testnet = true, verify = VerifyUi(RampVerification(KycStatus.Pending, required = true, canStart = false, submitted = true), checking = true))
        "add-money-verify-checking-offline" -> verify(KycStatus.Pending) { copy(checking = true) }.copy(online = false)
        "add-money-verify-checking-stale" -> verify(KycStatus.Pending) { copy(checking = true, stale = true) }
        "add-money-verify-checking-slow" -> verify(KycStatus.Pending) { copy(checking = true, slow = true) }
        // Back from Didit's hosted page: nothing is said to be sent, because it isn't known.
        "add-money-verify-returned" -> verify(KycStatus.Pending) { copy(returned = true) }
        "add-money-verify-review" -> verify(KycStatus.InReview, canStart = false)
        "add-money-verify-done" -> verify(KycStatus.Verified, canStart = false) { copy(verified = true) }
        // Declined for something another try can fix, by reason.
        "add-money-verify-declined", "add-money-verify-declined-face" -> verify(KycStatus.Declined, reason = KycReasons.FaceMatch)
        "add-money-verify-declined-document" -> verify(KycStatus.Declined, reason = KycReasons.Document)
        "add-money-verify-declined-unreadable" -> verify(KycStatus.Declined, reason = KycReasons.DocumentUnreadable)
        "add-money-verify-declined-liveness" -> verify(KycStatus.Declined, reason = KycReasons.Liveness)
        "add-money-verify-declined-unknown" -> verify(KycStatus.Declined, reason = "something_new")
        "add-money-verify-declined-wait" -> verify(KycStatus.Declined, canStart = false, reason = KycReasons.Liveness, retryAt = tomorrow)
        // Declined for good: honest words, Support, no retry.
        "add-money-verify-final" -> verify(KycStatus.Declined, canStart = false, reason = KycReasons.NotApproved)
        "add-money-verify-in-use" -> verify(KycStatus.Declined, canStart = false, reason = KycReasons.IdentityInUse)
        "add-money-verify-blocked" -> verify(KycStatus.Declined, canStart = false, reason = KycReasons.Blocked)
        "add-money-verify-expired" -> verify(KycStatus.Expired, reason = KycReasons.SessionExpired)
        "add-money-verify-id-expired" -> verify(KycStatus.Expired, reason = KycReasons.DocumentExpired)
        "add-money-verify-expired-wait" -> verify(KycStatus.Expired, canStart = false, reason = KycReasons.SessionExpired, retryAt = tomorrow)
        // Today's tries are used up: `ramp.kyc_attempts_exceeded`, and an open check that can't be continued today.
        "add-money-verify-tries" -> verify(KycStatus.Declined, reason = KycReasons.Liveness) { copy(trouble = VerifyTrouble.TriesUsedUp, retryAt = tomorrow) }
        "add-money-verify-tries-open" -> verify(KycStatus.Pending, canStart = false, retryAt = tomorrow)
        // On our side: Didit out of reach (try again), and verification not set up yet (nothing to try).
        "add-money-verify-down" -> verify(KycStatus.None) { copy(trouble = VerifyTrouble.ProviderDown, reference = "req_8f2a1c") }
        "add-money-verify-down-starting" -> verify(KycStatus.None) { copy(trouble = VerifyTrouble.ProviderDown, reference = "req_8f2a1c", starting = true) }
        "add-money-verify-setup" -> verify(KycStatus.None) { copy(trouble = VerifyTrouble.NotConfigured, reference = "req_8f2a1c") }
        "add-money-verify-camera" -> verify(KycStatus.Pending) { copy(trouble = VerifyTrouble.CameraDenied) }
        // R1's `blocked`, or `ramp.blocked` from any route.
        "add-money-barred" -> AddMoneyUiState(step = AddMoneyStep.Blocked, block = RampBlock.Barred)
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
        // The conversion (2026-11-23): played whole, held at one frame, and landing.
        "add-money-live" -> { LiveTopUp(amount); return true }
        "add-money-converting-early" -> { AddMoneyContent(onOrder(RampOrderState.Paid, paid = true), onBack = {}, frozenAt = 0.22f); return true }
        "add-money-converting" -> { AddMoneyContent(onOrder(RampOrderState.Paid, paid = true), onBack = {}, frozenAt = 0.5f); return true }
        "add-money-converting-late" -> { AddMoneyContent(onOrder(RampOrderState.Releasing, paid = true), onBack = {}, frozenAt = 0.8f); return true }
        "add-money-landing" -> onOrder(RampOrderState.Completed, paid = true).copy(celebrate = OrderId)
        else -> return false
    }
    AddMoneyContent(state, onBack = {})
    return true
}
