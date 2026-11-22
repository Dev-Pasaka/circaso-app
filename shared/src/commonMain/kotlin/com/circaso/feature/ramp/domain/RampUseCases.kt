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
