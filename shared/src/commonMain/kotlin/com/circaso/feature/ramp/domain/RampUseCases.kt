package com.circaso.feature.ramp.domain

// Every action here is class **Money** (offline-first.md): online-only, never queued, never optimistic.

class GetRampStatusUseCase(private val ramp: RampRepository) { suspend operator fun invoke() = ramp.status() }
class QuoteTopUpUseCase(private val ramp: RampRepository) { suspend operator fun invoke(blockchain: String, shillingsMinor: String, idempotencyKey: String) = ramp.quote(blockchain, shillingsMinor, idempotencyKey) }
class BuyWithMpesaUseCase(private val ramp: RampRepository) { suspend operator fun invoke(quoteId: String, phone: String, idempotencyKey: String) = ramp.buy(quoteId, phone, idempotencyKey) }
class GetRampOrderUseCase(private val ramp: RampRepository) { suspend operator fun invoke(orderId: String) = ramp.order(orderId) }
class CancelRampOrderUseCase(private val ramp: RampRepository) { suspend operator fun invoke(orderId: String, idempotencyKey: String) = ramp.cancel(orderId, idempotencyKey) }
class ObserveRampUpdatesUseCase(private val ramp: RampRepository) { operator fun invoke() = ramp.updates }
class ObserveTopUpsUseCase(private val ramp: RampRepository) { operator fun invoke() = ramp.topUps }
class GetOwnMpesaNumberUseCase(private val ramp: RampRepository) { suspend operator fun invoke() = ramp.ownPhone() }

// Selling USDC for M-Pesa (R2 `OFF_RAMP`, R7, then W4 naming the sale). **Money**: online-only, never queued, never optimistic.
class QuoteSaleUseCase(private val ramp: RampRepository) { suspend operator fun invoke(blockchain: String, usdcMinor: String?, shillingsMinor: String?, idempotencyKey: String) = ramp.sellQuote(blockchain, usdcMinor, shillingsMinor, idempotencyKey) }
class SellForMpesaUseCase(private val ramp: RampRepository) { suspend operator fun invoke(quoteId: String, phone: String, idempotencyKey: String) = ramp.sell(quoteId, phone, idempotencyKey) }
class ObserveSalesUseCase(private val ramp: RampRepository) { operator fun invoke() = ramp.sales }

/**
 * The wallet as a sale needs it: the balance on the network the sale is pinned to, W17's fee for sending the USDC,
 * and W4 with the PIN or biometrics (the app's usual approval). Kept apart from the wallet's own repository so the
 * sale's screen can be tested with a scripted wallet.
 */
interface SaleWallet {
    fun wallet(network: com.circaso.feature.wallet.domain.WalletNetwork): kotlinx.coroutines.flow.Flow<com.circaso.feature.wallet.domain.Wallet?>
    /** W17 for the transfer that pays the sale; null when the server takes no fee (an older server). */
    suspend fun fee(request: com.circaso.feature.wallet.domain.PaymentRequest): com.circaso.core.common.AppResult<com.circaso.feature.wallet.domain.PaymentQuote?>
    /** W4 then the Circle SDK. The sale's order id is the key, always: a retry returns the same transfer, never a second. */
    suspend fun send(request: com.circaso.feature.wallet.domain.PaymentRequest, idempotencyKey: String): com.circaso.core.common.AppResult<com.circaso.feature.wallet.domain.Transfer>
}

// Identity verification (R0). **Online-only**: a check can only be started, continued or read with a live answer.
class StartKycUseCase(private val ramp: RampRepository) { suspend operator fun invoke(idempotencyKey: String) = ramp.startKyc(idempotencyKey) }
class GetKycUseCase(private val ramp: RampRepository) { suspend operator fun invoke() = ramp.kyc() }
class ObserveKycUpdatesUseCase(private val ramp: RampRepository) { operator fun invoke() = ramp.kycUpdates }
/** The last known state of the check on the network being read, from the phone. **Read**: works offline. */
class ObserveIdentityUseCase(private val ramp: RampRepository) { operator fun invoke() = ramp.identity }

/**
 * Runs the open check where the person does it: Didit's own screens ([IdentityVerifier.verify]), or, with [hosted],
 * Didit's hosted page in the system's in-app browser ([openPage]: a Custom Tab or `SFSafariViewController`, the two
 * that may use the camera on a page) on a build or phone the SDK can't run in. Back from the page it isn't known how
 * far they got ([VerifierOutcome.Left]). Whatever comes back is a hint; the server has the truth.
 */
class RunIdentityCheckUseCase(private val verifier: () -> IdentityVerifier, private val openPage: suspend (String) -> Boolean) {
    suspend operator fun invoke(session: KycSession, hosted: Boolean = false): VerifierOutcome = when {
        !hosted -> verifier().verify(session.sessionToken)
        session.url.isNotBlank() && openPage(session.url) -> VerifierOutcome.Left
        else -> VerifierOutcome.Unavailable
    }
}
