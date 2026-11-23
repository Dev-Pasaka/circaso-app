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
