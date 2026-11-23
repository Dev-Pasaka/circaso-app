package com.circaso.feature.ramp.data.remote

import com.circaso.feature.ramp.domain.OffRampStatus
import com.circaso.feature.ramp.domain.RampAmount
import com.circaso.feature.ramp.domain.RampDirection
import com.circaso.feature.ramp.domain.SaleDeposit
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

/**
 * R2 request: `fiat` or `crypto`, never both. Buying: `fiat` is what you pay (whole shillings), `crypto` what you want.
 * Selling (`OFF_RAMP`): `crypto` is the USDC you send, `fiat` the shillings you want on M-Pesa (whole shillings).
 */
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
    /** Selling: the payout provider's charge, paid by you. */
    val providerFee: RampAmountDto? = null,
    /** Selling: what the USDC is worth at the rate, before any fee. */
    val worth: RampAmountDto? = null,
)

/** R7 request. `phone` is the account's own verified number in E.164; it is never logged. */
@Serializable
data class StartOffRampRequest(val quoteId: String, val phone: String) {
    override fun toString(): String = "StartOffRampRequest(***)"
}

/** A sale's `deposit`: where and exactly what to send with W4 (only while `AWAITING_TRANSFER`). */
@Serializable
data class DepositDto(val address: String, val blockchain: String, val asset: String = "USDC", val amountMinor: String, val amount: String = "", val expiresAt: String? = null) {
    override fun toString(): String = "DepositDto($blockchain, ***)"
}

/** R1's `offRamp` (2026-11-23): selling, on its own switch. `limits` are on what the person receives. */
@Serializable
data class OffRampDto(val open: Boolean = false, val merchantOnline: Boolean = false, val canSell: Boolean = false, val limits: LimitsDto? = null)

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
    /** A sale: the payout provider's charge. */
    val providerFee: RampAmountDto? = null,
    /** A sale waiting for its USDC (`AWAITING_TRANSFER` only). */
    val deposit: DepositDto? = null,
    /** A sale paid out: the M-Pesa confirmation code. */
    val receipt: String? = null,
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
    /** Selling (2026-11-23); absent from a server before it. */
    val offRamp: OffRampDto? = null,
)

/** `GET /v1/ramp/kyc`, R1's `verification`, and (with `network`) the data of `ramp` / `kyc.updated`. */
@Serializable
data class VerificationDto(
    val status: String,
    val required: Boolean = true,
    val canStart: Boolean = false,
    val reason: String? = null,
    val retryAt: String? = null,
    /** Finished by the person, decision awaited (2026-11-23); absent from a server before it, and then false. */
    val submitted: Boolean = false,
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
    /** Finished by the person, decision awaited (2026-11-23): `session` is then null. */
    val submitted: Boolean = false,
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
    val network: String? = null,
)

internal fun isoMillis(iso: String?): Long? = iso?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

internal fun RampAmountDto.toDomain() = RampAmount(unit, amountMinor, amount)

internal fun VerificationDto.toDomain() = RampVerification(KycStatus.of(status), required, canStart, reason?.takeIf { it.isNotBlank() }, isoMillis(retryAt), submitted)

internal fun KycResponse.toDomain() = KycStart(
    RampVerification(KycStatus.of(status), required, canStart, reason?.takeIf { it.isNotBlank() }, isoMillis(retryAt), submitted),
    // A session with no token is nothing to start: treated as none. Neither is one for a check already submitted.
    session?.takeIf { it.sessionToken.isNotBlank() && !submitted }?.let { KycSession(it.sessionToken, it.url, isoMillis(it.expiresAt)) },
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
    limits = limits.toDomain(),
    feePercent = feePercent,
    // A server from before selling sends only `offRampAvailable`: nothing to build a sale on, so selling isn't offered.
    offRamp = offRamp?.let { OffRampStatus(it.open, it.merchantOnline, it.canSell, it.limits?.toDomain()) },
)

internal fun LimitsDto.toDomain() = RampLimits(currency, minOrder.toDomain(), maxOrder.toDomain(), daily.toDomain())

internal fun QuoteResponse.toDomain() = RampQuote(
    quoteId, blockchain, pay.toDomain(), receive.toDomain(), rate, fee.toDomain(), isoMillis(expiresAt),
    direction = RampDirection.of(direction), providerFee = providerFee?.toDomain(), worth = worth?.toDomain(),
)

internal fun DepositDto.toDomain() = SaleDeposit(address, blockchain, asset, amountMinor, amount, isoMillis(expiresAt))

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
    direction = RampDirection.of(direction),
    providerFee = providerFee?.toDomain(),
    // `deposit` is only meaningful while the sale waits for its USDC; an address and an amount are never sent to blindly.
    deposit = deposit?.takeIf { it.address.isNotBlank() && it.amountMinor.isNotBlank() }?.toDomain(),
    receipt = receipt?.takeIf { it.isNotBlank() },
)

/**
 * A sale this phone paid, as it is kept for the wallet's history (one network's list, under `text.wallet.`): the order,
 * the W4 transfer, the masked number, the shillings, the receipt and the refund's hash. Nothing else.
 */
@Serializable
data class SavedSale(
    val orderId: String,
    val transferId: String? = null,
    val maskedPhone: String = "",
    val receiveMinor: String,
    val receiveUnit: String = "KES",
    val receipt: String? = null,
    val state: String,
    val refundHash: String? = null,
    /** W4 was asked for this sale and no definite answer came (see [com.circaso.feature.ramp.domain.RampRepository.saleAttempt]). */
    val attempted: Boolean = false,
) {
    fun label() = com.circaso.feature.ramp.domain.SaleLabel(
        orderId, transferId, maskedPhone, RampAmount(receiveUnit, receiveMinor, ""), receipt, RampOrderState.of(state), refundHash,
    )

    companion object {
        fun wire(state: RampOrderState): String = when (state) {
            RampOrderState.AwaitingTransfer -> "AWAITING_TRANSFER"
            RampOrderState.PayingOut -> "PAYING_OUT"
            RampOrderState.PaymentRequested -> "PAYMENT_REQUESTED"
            else -> state.name.uppercase()
        }

        /** [order] as it now stands; what an older copy ([kept]) knew is kept where the order no longer says it. */
        fun of(order: RampOrder, transferId: String?, kept: SavedSale?) = SavedSale(
            orderId = order.orderId,
            transferId = transferId ?: kept?.transferId,
            maskedPhone = order.maskedPhone.ifBlank { kept?.maskedPhone.orEmpty() },
            receiveMinor = order.receive.amountMinor,
            receiveUnit = order.receive.unit,
            receipt = order.receipt ?: kept?.receipt,
            state = wire(order.state),
            // A refunded sale's `txHash` is the refund (guide: "REFUNDED ... txHash is the refund").
            refundHash = (if (order.state == RampOrderState.Refunded) order.txHash?.lowercase() else null) ?: kept?.refundHash,
            attempted = kept?.attempted == true,
        )
    }
}

/**
 * The check as it is kept on the phone for one network ([com.circaso.feature.ramp.domain.IdentitySnapshot]): only the
 * state and its reason code, never anything personal, never a session.
 */
@Serializable
data class SavedIdentity(
    val status: String? = null,
    val required: Boolean = true,
    val canStart: Boolean = false,
    val reason: String? = null,
    val retryAt: Long? = null,
    val submitted: Boolean = false,
    val unavailable: Boolean = false,
    val checkedAt: Long = 0,
)
