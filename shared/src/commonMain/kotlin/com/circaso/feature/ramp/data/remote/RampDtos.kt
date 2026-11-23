package com.circaso.feature.ramp.data.remote

import com.circaso.feature.ramp.domain.RampAmount
import com.circaso.feature.ramp.domain.KycSession
import com.circaso.feature.ramp.domain.KycStart
import com.circaso.feature.ramp.domain.KycStatus
import com.circaso.feature.ramp.domain.RampKyc
import com.circaso.feature.ramp.domain.RampVerification
import com.circaso.feature.ramp.domain.RampLimits
import com.circaso.feature.ramp.domain.RampOrder
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.ramp.domain.RampQuote
import com.circaso.feature.ramp.domain.RampStatus
import com.circaso.feature.wallet.domain.WalletNetwork
import kotlinx.serialization.Serializable

// Named as the backend names them (modules/ramp, presentation/dto/Dtos.kt, 2026-11-22). Amounts are strings.

@Serializable
data class RampAmountDto(val unit: String, val amountMinor: String, val amount: String) {
    override fun toString(): String = "RampAmountDto($unit, ***)"
}

/** One side of a quote request: whole units in `amount`. */
@Serializable
data class AmountDto(val amountMinor: String? = null, val amount: String? = null)

/** R2 request: `fiat` (what you pay, whole shillings) or `crypto` (what you want), never both. */
@Serializable
data class QuoteRequest(val direction: String, val blockchain: String, val fiat: AmountDto? = null, val crypto: AmountDto? = null)

@Serializable
data class QuoteResponse(
    val quoteId: String,
    val direction: String = "ON_RAMP",
    val network: String? = null,
    val blockchain: String,
    val pay: RampAmountDto,
    val receive: RampAmountDto,
    val rate: String,
    val fee: RampAmountDto,
    val expiresAt: String,
)

/** R3 request. `phone` is the account's own verified number in E.164; it is never logged. */
@Serializable
data class StartOnRampRequest(val quoteId: String, val phone: String) {
    override fun toString(): String = "StartOnRampRequest(***)"
}

@Serializable
data class OrderResponse(
    val orderId: String,
    val direction: String = "ON_RAMP",
    val state: String,
    val network: String? = null,
    val blockchain: String,
    val pay: RampAmountDto,
    val receive: RampAmountDto,
    val rate: String,
    val fee: RampAmountDto,
    val maskedPhone: String = "",
    val merchantName: String = "",
    val txHash: String? = null,
    val failureReason: String? = null,
    val expiresAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class OrdersResponse(val orders: List<OrderResponse> = emptyList(), val next: String? = null)

@Serializable
data class LimitsDto(val currency: String, val minOrder: RampAmountDto, val maxOrder: RampAmountDto, val daily: RampAmountDto)

@Serializable
data class RampStatusResponse(
    val canTrade: Boolean,
    val network: String? = null,
    val kyc: String,
    /** A bar on the account or its identity (2026-11-22); absent from a server before verification. */
    val blocked: Boolean = false,
    /** The identity check in full (2026-11-22); absent from a server before verification. */
    val verification: VerificationDto? = null,
    val accountActive: Boolean = true,
    val blockchains: List<String> = emptyList(),
    val merchantOnline: Boolean = false,
    val offRampAvailable: Boolean = false,
    val openOrderId: String? = null,
    val limits: LimitsDto,
    val feePercent: String = "0",
)

/** `GET /v1/ramp/kyc`, R1's `verification`, and (with `network`) the data of `ramp` / `kyc.updated`. */
@Serializable
data class VerificationDto(
    val status: String,
    val required: Boolean = true,
    val canStart: Boolean = false,
    val reason: String? = null,
    val retryAt: String? = null,
    /** Only on the frame: the network the check moved on. */
    val network: String? = null,
)

/** R0's `session`. The token and the link open someone's identity check: never in a log line. */
@Serializable
data class KycSessionDto(val sessionToken: String, val url: String = "", val expiresAt: String? = null) {
    override fun toString(): String = "KycSessionDto(***)"
}

/** R0 `POST /v1/ramp/kyc`. */
@Serializable
data class KycResponse(
    val status: String,
    val required: Boolean = true,
    val canStart: Boolean = false,
    val reason: String? = null,
    val retryAt: String? = null,
    val session: KycSessionDto? = null,
)

/** `ramp` / `order.updated`. */
@Serializable
data class OrderUpdatedFrame(
    val orderId: String,
    val direction: String = "ON_RAMP",
    val state: String,
    val blockchain: String? = null,
    val pay: RampAmountDto? = null,
    val receive: RampAmountDto? = null,
    val failureReason: String? = null,
)

internal fun isoMillis(iso: String?): Long? = iso?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

internal fun RampAmountDto.toDomain() = RampAmount(unit, amountMinor, amount)

internal fun VerificationDto.toDomain() = RampVerification(KycStatus.of(status), required, canStart, reason?.takeIf { it.isNotBlank() }, isoMillis(retryAt))

internal fun KycResponse.toDomain() = KycStart(
    RampVerification(KycStatus.of(status), required, canStart, reason?.takeIf { it.isNotBlank() }, isoMillis(retryAt)),
    // A session with no token is nothing to start: treated as none.
    session?.takeIf { it.sessionToken.isNotBlank() }?.let { KycSession(it.sessionToken, it.url, isoMillis(it.expiresAt)) },
)

/** What a server from before verification means by its short `kyc`: `NONE` is "required, never started". */
internal fun RampKyc.asVerification() = when (this) {
    RampKyc.Verified -> RampVerification(KycStatus.Verified, required = true, canStart = false)
    RampKyc.NotRequired -> RampVerification(KycStatus.None, required = false, canStart = false)
    RampKyc.None -> RampVerification(KycStatus.None, required = true, canStart = true)
}

internal fun RampStatusResponse.toDomain() = RampStatus(
    canTrade = canTrade,
    network = WalletNetwork.of(network),
    kyc = RampKyc.of(kyc),
    verification = verification?.toDomain() ?: RampKyc.of(kyc).asVerification(),
    blocked = blocked,
    accountActive = accountActive,
    blockchains = blockchains,
    merchantOnline = merchantOnline,
    openOrderId = openOrderId?.takeIf { it.isNotBlank() },
    limits = RampLimits(limits.currency, limits.minOrder.toDomain(), limits.maxOrder.toDomain(), limits.daily.toDomain()),
    feePercent = feePercent,
)

internal fun QuoteResponse.toDomain() = RampQuote(quoteId, blockchain, pay.toDomain(), receive.toDomain(), rate, fee.toDomain(), isoMillis(expiresAt))

internal fun OrderResponse.toDomain() = RampOrder(
    orderId = orderId,
    state = RampOrderState.of(state),
    network = WalletNetwork.of(network),
    blockchain = blockchain,
    pay = pay.toDomain(),
    receive = receive.toDomain(),
    rate = rate,
    fee = fee.toDomain(),
    maskedPhone = maskedPhone,
    txHash = txHash?.takeIf { it.isNotBlank() },
    failureReason = failureReason,
    expiresAt = isoMillis(expiresAt),
    createdAt = isoMillis(createdAt),
)
