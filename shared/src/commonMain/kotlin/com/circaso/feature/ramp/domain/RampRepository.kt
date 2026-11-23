package com.circaso.feature.ramp.domain

import com.circaso.core.common.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * The ramp (R1–R6). **Money: never queued, never optimistic.** Every call needs a live answer; nothing here is kept in
 * an outbox, and an order only ever shows the state the server last gave. Every route is wallet-scoped, so each call
 * carries `X-Wallet-Network` for the network the caller is pinned to (or the selected one).
 */
interface RampRepository {
    /** `ramp` / `order.updated` frames as they arrive (and as they are replayed after a reconnect). */
    val updates: Flow<RampOrderUpdate>

    /**
     * Completed top-ups this phone knows, on the network being read: the release's transaction hash (lower case) to
     * the masked M-Pesa number, so the wallet's row for that money reads "Added from M-Pesa" (design E1/E7).
     */
    val topUps: Flow<Map<String, String>>

    /** `ramp` / `kyc.updated` frames as they arrive (and as they are replayed after a reconnect), for any network. */
    val kycUpdates: Flow<KycUpdate>

    /**
     * Where the identity check stood when the server last said (R0, the read, R1 or a frame), for the network being
     * read (the pinned one, else the selected one); null until it has answered once. Kept on the phone per network and
     * wiped at sign-out, so the profile shows the last known status offline. **Read**: never a reason to wait.
     */
    val identity: Flow<IdentitySnapshot?>

    /**
     * R0 `POST /v1/ramp/kyc`: starts the identity check, or returns the one still open (never a second). **Online-only**:
     * never queued. One [idempotencyKey] per tap, the same on a retry that got no definite answer.
     */
    suspend fun startKyc(idempotencyKey: String): AppResult<KycStart>

    /**
     * `GET /v1/ramp/kyc`: where the check stands. While one is unfinished the server asks Didit itself, so this is what
     * to read the moment the SDK closes (its own result is a hint, never the truth).
     */
    suspend fun kyc(): AppResult<RampVerification>

    /** R1. `ramp.unavailable` means buying isn't open to this person on this network. */
    suspend fun status(): AppResult<RampStatus>

    /** R2: [shillingsMinor] is whole shillings in cents. The same [idempotencyKey] on a retry of the same ask. */
    suspend fun quote(blockchain: String, shillingsMinor: String, idempotencyKey: String): AppResult<RampQuote>

    /**
     * R3: sends the M-Pesa prompt to [phone] (E.164), which must be the account's own verified number. One
     * [idempotencyKey] per quote, the same on every retry: the same quote again returns the same order, no second prompt.
     */
    suspend fun buy(quoteId: String, phone: String, idempotencyKey: String): AppResult<RampOrder>

    /** R4. */
    suspend fun order(orderId: String): AppResult<RampOrder>

    /** R6: only before the payment; while the prompt is open on the phone the server answers `ramp.order_not_cancellable`. */
    suspend fun cancel(orderId: String, idempotencyKey: String): AppResult<RampOrder>

    /** R5, newest page: refreshes [topUps]. */
    suspend fun refreshOrders(): AppResult<Unit>

    /**
     * The person's own number in full, when this phone has it (the server only ever returns it masked): kept in secure
     * storage after the first purchase it was accepted for. Never logged.
     */
    suspend fun ownPhone(): String?

    suspend fun onFrame(type: String, data: JsonObject?)

    /** The socket's gap is older than its replay window (`resync`): what is on screen reads its order again. */
    suspend fun reload()
}

/** The person's own M-Pesa number on this phone (secure storage, per account). */
interface RampPhoneStore {
    suspend fun get(): String?
    suspend fun put(phone: String)
    suspend fun clear()

    companion object {
        val None: RampPhoneStore = object : RampPhoneStore {
            override suspend fun get(): String? = null
            override suspend fun put(phone: String) = Unit
            override suspend fun clear() = Unit
        }
    }
}
