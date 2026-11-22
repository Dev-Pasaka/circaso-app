package com.circaso.feature.ramp.domain

import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.WalletNetwork

/**
 * Buying USDC with M-Pesa (guide "Ramp", R1–R6; design E2). A partner merchant sells the USDC and Circaso's engine
 * picks one, so the app never lists merchants. Money here is always a decimal string: no float touches it.
 */

/** An amount as the ramp hands it out: [unit] is `KES` or `USDC`, [amountMinor] exact (cents, or millionths of a USDC). */
data class RampAmount(val unit: String, val amountMinor: String, val amount: String) {
    /** "KES 2,020" (cents shown only when there are any) or "15.31 USDC". */
    val text: String
        get() = if (unit.equals(RampMoney.Coin, ignoreCase = true)) MinorUnits.toDisplay(amountMinor, RampMoney.CoinDecimals) + " " + unit
        else unit + " " + RampMoney.shillings(amountMinor)

    /** The number alone, as [text] writes it. */
    val number: String
        get() = if (unit.equals(RampMoney.Coin, ignoreCase = true)) MinorUnits.toDisplay(amountMinor, RampMoney.CoinDecimals) else RampMoney.shillings(amountMinor)

    // Amounts are money: never in a log line.
    override fun toString(): String = "RampAmount($unit, ***)"
}

object RampMoney {
    const val Fiat = "KES"
    const val Coin = "USDC"
    const val CoinDecimals = 6
    const val FiatDecimals = 2

    /** Cents as shillings with thousands grouped: "202000" → "2,020"; "12950" → "129.50". */
    fun shillings(minor: String): String = MinorUnits.toDisplay(minor, FiatDecimals, minFraction = 0).let { if (it.contains('.') && it.substringAfter('.').length == 1) it + "0" else it }

    /** Whole shillings as typed ("2000", "2,000") in cents; null when it isn't a whole number above zero. */
    fun wholeToMinor(text: String): String? {
        val digits = text.trim().replace(",", "").replace(" ", "")
        if (digits.isEmpty() || digits.any { !it.isDigit() } || digits.length > 9) return null
        val whole = digits.trimStart('0')
        return if (whole.isEmpty()) null else whole + "00"
    }
}

/** Where the person stands on identity checks: [NotRequired] on the testnet until verification is built. */
enum class RampKyc { Verified, None, NotRequired;
    companion object { fun of(wire: String): RampKyc = when (wire.uppercase()) { "VERIFIED" -> Verified; "NOT_REQUIRED" -> NotRequired; else -> None } }
}

data class RampLimits(val currency: String, val minOrder: RampAmount, val maxOrder: RampAmount, val daily: RampAmount)

/** R1: whether the person can buy now, and which gate is in the way when they can't. */
data class RampStatus(
    val canTrade: Boolean,
    val network: WalletNetwork?,
    val kyc: RampKyc,
    val accountActive: Boolean,
    val blockchains: List<String>,
    val merchantOnline: Boolean,
    /** An order of theirs still under way: the flow resumes on it instead of starting another. */
    val openOrderId: String?,
    val limits: RampLimits,
    /** The fee as a percentage ("1.5"), inside what is paid. */
    val feePercent: String,
)

/** R2: a price that holds until [expiresAt] and reserves nothing. The [fee] is inside [pay]. */
data class RampQuote(
    val quoteId: String,
    val blockchain: String,
    val pay: RampAmount,
    val receive: RampAmount,
    /** Shillings per USDC, a decimal string. */
    val rate: String,
    val fee: RampAmount,
    /** Server time (epoch ms); null when it couldn't be read, and the quote is then held for [QuoteSeconds]. */
    val expiresAt: Long?,
) {
    companion object { const val QuoteSeconds = 60 }
}

/**
 * An order's state as the server names it. [PaymentRequested] is "check your phone"; [Paid] M-Pesa confirmed;
 * [Releasing] the USDC is being sent; [Review] a person at Circaso is looking.
 */
enum class RampOrderState {
    Reserved, PaymentRequested, Paid, Releasing, Completed, Expired, Cancelled, Failed, Review,
    /** A state this version doesn't know (a newer server's): shown as still under way, never as an ending. */
    Unknown;

    /** Nothing more happens by itself: stop following it. [Review] ends the following too, a person takes over. */
    val settled: Boolean get() = this == Completed || this == Expired || this == Cancelled || this == Failed || this == Review

    /** The shillings have left the person's M-Pesa. */
    val paid: Boolean get() = this == Paid || this == Releasing || this == Completed

    companion object {
        fun of(wire: String): RampOrderState = when (wire.uppercase()) {
            "RESERVED" -> Reserved
            "PAYMENT_REQUESTED" -> PaymentRequested
            "PAID" -> Paid
            "RELEASING" -> Releasing
            "COMPLETED" -> Completed
            "EXPIRED" -> Expired
            "CANCELLED" -> Cancelled
            "FAILED" -> Failed
            "REVIEW" -> Review
            else -> Unknown
        }
    }
}

data class RampOrder(
    val orderId: String,
    val state: RampOrderState,
    val network: WalletNetwork?,
    val blockchain: String,
    val pay: RampAmount,
    val receive: RampAmount,
    val rate: String,
    val fee: RampAmount,
    /** The M-Pesa number the prompt went to, masked by the server. */
    val maskedPhone: String,
    val txHash: String?,
    /** The server's short reason (`payment_failed`, `cancelled`, …); for support, never shown as copy. */
    val failureReason: String?,
    /** When the unpaid prompt runs out (epoch ms). */
    val expiresAt: Long?,
    val createdAt: Long?,
)

/** `ramp` / `order.updated`: the order moved. It carries no fee or hash, so the order is read again (R4) after it. */
data class RampOrderUpdate(val orderId: String, val state: RampOrderState, val failureReason: String?) {
    companion object {
        /** [orderId] of a `resync` (the socket's gap is past its replay window): whichever order is on screen reads itself again. */
        const val ReloadAll = "*"
    }
}

/** The codes the screens act on (every other one only has its words). */
object RampCodes {
    const val Unavailable = "ramp.unavailable"
    const val KycRequired = "ramp.kyc_required"
    const val AccountRestricted = "ramp.account_restricted"
    const val WalletRequired = "ramp.wallet_required"
    const val AmountInvalid = "ramp.amount_invalid"
    const val LimitExceeded = "ramp.limit_exceeded"
    const val QuoteNotFound = "ramp.quote_not_found"
    const val QuoteExpired = "ramp.quote_expired"
    const val PhoneNotYours = "ramp.phone_not_yours"
    const val OrderInProgress = "ramp.order_in_progress"
    const val NoMerchant = "ramp.no_merchant_available"
    const val TooManyPrompts = "ramp.too_many_prompts"
    const val OrderNotFound = "ramp.order_not_found"
    const val NotCancellable = "ramp.order_not_cancellable"
}
