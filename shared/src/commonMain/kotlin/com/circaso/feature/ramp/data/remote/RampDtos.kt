package com.circaso.feature.ramp.data.remote

import com.circaso.feature.ramp.domain.RampAmount
import com.circaso.feature.ramp.domain.RampKyc
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
    val accountActive: Boolean = true,
    val blockchains: List<String> = emptyList(),
    val merchantOnline: Boolean = false,
    val offRampAvailable: Boolean = false,
    val openOrderId: String? = null,
    val limits: LimitsDto,
    val feePercent: String = "0",
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

internal fun RampStatusResponse.toDomain() = RampStatus(
    canTrade = canTrade,
    network = WalletNetwork.of(network),
    kyc = RampKyc.of(kyc),
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
