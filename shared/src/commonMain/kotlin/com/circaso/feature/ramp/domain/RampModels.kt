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

/** R1's short form of the identity check, as older servers send it; [RampVerification] is the check in full. */
enum class RampKyc { Verified, None, NotRequired;
    companion object { fun of(wire: String): RampKyc = when (wire.uppercase()) { "VERIFIED" -> Verified; "NOT_REQUIRED" -> NotRequired; else -> None } }
}

/** Where an identity check stands (guide "Identity verification (KYC)": `verification.status`). */
enum class KycStatus {
    /** Never started. */
    None,
    /** A check is open and unfinished (or a reviewer asked for a step again). */
    Pending,
    /** Didit's reviewers are looking: nothing to do but wait for the frame or the push. */
    InReview,
    Verified,
    Declined,
    /** The check wasn't finished in time, or the ID has since expired. */
    Expired,
    /** A status this version doesn't know (a newer server's): shown from `canStart` alone, never as an ending. */
    Unknown;

    companion object {
        fun of(wire: String): KycStatus = when (wire.uppercase()) {
            "NONE" -> None
            "PENDING" -> Pending
            "IN_REVIEW" -> InReview
            "VERIFIED" -> Verified
            "DECLINED" -> Declined
            "EXPIRED" -> Expired
            else -> Unknown
        }

        /** As the server writes it; an unknown status is kept as `UNKNOWN`, which reads back as [Unknown]. */
        fun wire(status: KycStatus): String = when (status) {
            None -> "NONE"
            Pending -> "PENDING"
            InReview -> "IN_REVIEW"
            Verified -> "VERIFIED"
            Declined -> "DECLINED"
            Expired -> "EXPIRED"
            Unknown -> "UNKNOWN"
        }
    }
}

/**
 * The identity check in full, as R1's `verification`, `GET /v1/ramp/kyc` and the `kyc.updated` frame give it. Every
 * KYC screen is built from this and nothing else: the app never guesses. Nothing personal is in it, only the state
 * and a [reason] code the app words itself ([KycReasons]).
 */
data class RampVerification(
    val status: KycStatus,
    /** This network asks for verification before a purchase (always on Mainnet; on Testnet when staff switch it on). */
    val required: Boolean,
    /** R0 may be called now: start, continue or try again. */
    val canStart: Boolean,
    val reason: String? = null,
    /** When today's tries are used up: the moment R0 may be called again (epoch ms). */
    val retryAt: Long? = null,
    /**
     * The person has done their part and the decision is awaited (2026-11-23): a `PENDING` check they completed, and
     * `IN_REVIEW`. The server's word, read from Didit; false from a server that doesn't send it. **Read before
     * [canStart]**: while it is true nothing is offered, R0 is never called and the SDK is never reopened.
     */
    val submitted: Boolean = false,
) {
    /** Nothing stands between the person and buying, as far as identity goes. */
    val cleared: Boolean get() = !required || status == KycStatus.Verified

    /** Finished by the person and still undecided: "We're checking your details", with nothing to tap. */
    val awaitingDecision: Boolean get() = submitted && (status == KycStatus.Pending || status == KycStatus.InReview)

    /** R0 may be called now. [submitted] wins over [canStart], whatever a server sends beside it. */
    val startable: Boolean get() = canStart && !awaitingDecision
}

/**
 * The identity check as this phone last heard it for one wallet network, kept so the profile can say where it stands
 * without a connection (offline-first: a **Read**). [verification] is null when the server has never answered with
 * one; [unavailable] when its last answer was that verification isn't open here (`ramp.unavailable`, a server from
 * before the routes were opened to everyone). [checkedAt] is when the server last said so (epoch ms).
 */
data class IdentitySnapshot(val verification: RampVerification?, val unavailable: Boolean, val checkedAt: Long)

/** The reason codes the screens word (guide: "Reason codes"); any other is a newer server's. */
object KycReasons {
    const val Document = "document"
    const val DocumentExpired = "document_expired"
    const val DocumentUnreadable = "document_unreadable"
    const val Liveness = "liveness"
    const val FaceMatch = "face_match"
    const val Declined = "declined"
    const val NotApproved = "not_approved"
    const val IdentityInUse = "identity_in_use"
    const val Blocked = "blocked"
    const val SessionExpired = "session_expired"
    const val ResubmissionRequested = "resubmission_requested"
}

/**
 * An open check (R0's `session`): [sessionToken] starts Didit's SDK, [url] is Didit's hosted page for a phone the SDK
 * can't run on. Both are the keys to someone's identity check: never logged, never kept beyond the launch.
 */
class KycSession(val sessionToken: String, val url: String, val expiresAt: Long?) {
    override fun toString(): String = "KycSession(***)"
}

/** R0's answer: where the check stands, and the open [session] when there is something to do now. */
data class KycStart(val verification: RampVerification, val session: KycSession?)

/** `ramp` / `kyc.updated`: the check moved on [network] (the frame says which; the screen takes only its own). */
data class KycUpdate(val network: WalletNetwork?, val verification: RampVerification)

data class RampLimits(val currency: String, val minOrder: RampAmount, val maxOrder: RampAmount, val daily: RampAmount)

/** R1: whether the person can buy now, and which gate is in the way when they can't. */
data class RampStatus(
    val canTrade: Boolean,
    val network: WalletNetwork?,
    val kyc: RampKyc,
    val accountActive: Boolean,
    /** The identity check in full. A server from before verification sends none, and it is then read off [kyc]. */
    val verification: RampVerification = RampVerification(KycStatus.None, required = false, canStart = false),
    /** A bar on the account or its identity: it wins over everything else, and verifying is never offered. */
    val blocked: Boolean = false,
    val blockchains: List<String>,
    val merchantOnline: Boolean,
    /** An order of theirs still under way: the flow resumes on it instead of starting another. */
    val openOrderId: String?,
    val limits: RampLimits,
    /** The fee as a percentage ("1.5"), inside what is paid. */
    val feePercent: String,
    /** Selling USDC for M-Pesa (2026-11-23), on its own switch; null from a server before selling. */
    val offRamp: OffRampStatus? = null,
)

/**
 * R1's `offRamp`: [open] says whether "Cash Out" is offered at all; [merchantOnline] whether someone can buy USDC now;
 * [canSell] is every gate at once (open, verified or not required, not barred, active, someone online, no open order).
 * [limits] are on what the person receives, in shillings.
 */
data class OffRampStatus(val open: Boolean, val merchantOnline: Boolean, val canSell: Boolean, val limits: RampLimits?)

/** Which way an order goes: buying USDC with M-Pesa, or selling it for M-Pesa. */
enum class RampDirection { OnRamp, OffRamp;
    companion object {
        fun of(wire: String?): RampDirection = if (wire.equals("OFF_RAMP", ignoreCase = true)) OffRamp else OnRamp
    }
}

/**
 * Where a sale's USDC goes (R7's `deposit`, only while `AWAITING_TRANSFER`): the app sends exactly [amountMinor] of
 * [asset] to [address] on [blockchain] with W4, naming the sale, before [expiresAt] (epoch ms).
 */
data class SaleDeposit(val address: String, val blockchain: String, val asset: String, val amountMinor: String, val amount: String, val expiresAt: Long?) {
    // An address is not secret, but it has no place in a log line beside an amount.
    override fun toString(): String = "SaleDeposit($blockchain, ***)"
}

/**
 * R2: a price that holds until [expiresAt] and reserves nothing. Buying: the [fee] is inside [pay]. Selling
 * ([direction] [RampDirection.OffRamp]): [pay] is the USDC sent, [worth] is that at the [rate], and [receive] is
 * `worth - fee - providerFee`, rounded down to the shilling.
 */
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
    val direction: RampDirection = RampDirection.OnRamp,
    /** Selling: the payout provider's charge for sending the shillings to M-Pesa, paid by the person. */
    val providerFee: RampAmount? = null,
    /** Selling: what the USDC is worth at the rate, before any fee. */
    val worth: RampAmount? = null,
) {
    companion object { const val QuoteSeconds = 60 }
}

/**
 * An order's state as the server names it. Buying: [PaymentRequested] is "check your phone"; [Paid] M-Pesa confirmed;
 * [Releasing] the USDC is being sent. Selling: [AwaitingTransfer] waits for the person's USDC; [Funded] it reached
 * the seller and is held there; [PayingOut] the shillings were sent to M-Pesa; [Refunding] / [Refunded] the USDC is
 * coming back / is back. [Review] a person at Circaso is looking.
 */
enum class RampOrderState {
    Reserved, PaymentRequested, Paid, Releasing, Completed, Expired, Cancelled, Failed, Review,
    AwaitingTransfer, Funded, PayingOut, Refunding, Refunded,
    /** A state this version doesn't know (a newer server's): shown as still under way, never as an ending. */
    Unknown;

    /** Nothing more happens by itself: stop following it. [Review] ends the following too, a person takes over. */
    val settled: Boolean get() = this == Completed || this == Expired || this == Cancelled || this == Failed || this == Review || this == Refunded

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
            "AWAITING_TRANSFER" -> AwaitingTransfer
            "FUNDED" -> Funded
            "PAYING_OUT" -> PayingOut
            "REFUNDING" -> Refunding
            "REFUNDED" -> Refunded
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
    /** When the unpaid prompt runs out (epoch ms); a sale's deadline for its USDC to arrive. */
    val expiresAt: Long?,
    val createdAt: Long?,
    val direction: RampDirection = RampDirection.OnRamp,
    /** A sale: the payout provider's charge, paid by the person. */
    val providerFee: RampAmount? = null,
    /** A sale waiting for its USDC: where and what to send (only while [RampOrderState.AwaitingTransfer]). */
    val deposit: SaleDeposit? = null,
    /** A sale paid out: the M-Pesa confirmation code. */
    val receipt: String? = null,
) {
    val sale: Boolean get() = direction == RampDirection.OffRamp
}

/** `ramp` / `order.updated`: the order moved. It carries no fee or hash, so the order is read again (R4) after it. */
data class RampOrderUpdate(val orderId: String, val state: RampOrderState, val failureReason: String?, val direction: RampDirection = RampDirection.OnRamp) {
    companion object {
        /** [orderId] of a `resync` (the socket's gap is past its replay window): whichever order is on screen reads itself again. */
        const val ReloadAll = "*"
    }
}

/** The codes the screens act on (every other one only has its words). */
object RampCodes {
    const val Unavailable = "ramp.unavailable"
    const val KycRequired = "ramp.kyc_required"
    /** The account or its identity is barred from the ramp (R0, R2, R3). */
    const val Blocked = "ramp.blocked"
    /** Today's tries at verifying are used up (R0, 429); `details.retryAfterSeconds` says when. */
    const val KycAttemptsExceeded = "ramp.kyc_attempts_exceeded"
    /** Didit is out of reach (R0, 502): nothing was counted. */
    const val KycUnavailable = "ramp.kyc_unavailable"
    /** Verification isn't set up on this network yet (R0, 503). */
    const val KycNotConfigured = "ramp.kyc_not_configured"
    const val AccountRestricted = "ramp.account_restricted"
    const val WalletRequired = "ramp.wallet_required"
    const val AmountInvalid = "ramp.amount_invalid"
    const val LimitExceeded = "ramp.limit_exceeded"
    const val QuoteNotFound = "ramp.quote_not_found"
    const val QuoteExpired = "ramp.quote_expired"
    const val PhoneNotYours = "ramp.phone_not_yours"
    const val OrderInProgress = "ramp.order_in_progress"
    const val NoMerchant = "ramp.no_merchant_available"
    /** The seller the server picked can't take this payment right now (409): nothing was sent to M-Pesa. */
    const val MerchantUnavailable = "ramp.merchant_unavailable"
    const val TooManyPrompts = "ramp.too_many_prompts"
    const val OrderNotFound = "ramp.order_not_found"
    const val NotCancellable = "ramp.order_not_cancellable"
    /** Selling isn't open to this person on this network (R2, R7, 409): "Cash Out" shows its closed page. */
    const val OffRampUnavailable = "ramp.offramp_unavailable"
    /** The payout provider's charge can't be read right now (R2 for a sale, 502). */
    const val PayoutFeeUnavailable = "ramp.payout_fee_unavailable"
    const val PricingUnavailable = "ramp.pricing_unavailable"
    const val ChainUnsupported = "ramp.chain_unsupported"
    /** W4: another transfer already pays this sale (409). Never send again: show the order. */
    const val SaleFunded = "wallet.ramp_order_funded"
}

/**
 * A sale as this phone remembers it for the wallet's history (designs E1/E7): the W4 transfer that paid it reads
 * "Cashed out to M-Pesa" with the shillings and the M-Pesa receipt; the refund's transaction reads as the USDC coming
 * back. Nothing personal beyond the masked number the server already shows.
 */
data class SaleLabel(
    val orderId: String,
    /** The W4 transfer that sent the USDC from this phone; null until it was sent. */
    val transferId: String?,
    val maskedPhone: String,
    /** What reaches M-Pesa, "KES 1,269". */
    val receive: RampAmount,
    val receipt: String?,
    val state: RampOrderState,
    /** The refund's transaction hash, once the USDC came back. */
    val refundHash: String?,
)

/** The sales this phone knows, looked up by the transfer that paid each one and by its refund's hash. */
data class MpesaSales(val byTransfer: Map<String, SaleLabel> = emptyMap(), val byRefund: Map<String, SaleLabel> = emptyMap()) {
    fun paidBy(transferId: String): SaleLabel? = byTransfer[transferId.lowercase()]
    fun refundedBy(txHash: String?): SaleLabel? = txHash?.lowercase()?.let { byRefund[it] }

    companion object {
        fun of(labels: List<SaleLabel>) = MpesaSales(
            labels.mapNotNull { l -> l.transferId?.let { it.lowercase() to l } }.toMap(),
            labels.mapNotNull { l -> l.refundHash?.let { it.lowercase() to l } }.toMap(),
        )
    }
}
