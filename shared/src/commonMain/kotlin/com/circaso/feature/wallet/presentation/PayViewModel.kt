package com.circaso.feature.wallet.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.messaging.domain.OpenDirectChatUseCase
import com.circaso.feature.messaging.domain.PaymentCard
import com.circaso.feature.messaging.domain.SendPaymentCardUseCase
import com.circaso.feature.wallet.domain.CheckRecipientUseCase
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.PayUseCase
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.RecipientStatus
import com.circaso.feature.wallet.domain.RemindOutcome
import com.circaso.feature.wallet.domain.RemindRecipientUseCase
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.coinToFiat
import com.circaso.feature.wallet.domain.fiatToCoinMinor
import com.circaso.feature.wallet.domain.shortAddress
import com.circaso.feature.wallet.domain.unitFiatRate
import com.circaso.feature.chama.domain.AlreadyLinkedCodes
import com.circaso.feature.chama.domain.AttachChamaPaymentUseCase
import com.circaso.feature.chama.domain.Chama
import com.circaso.feature.chama.domain.ObserveChamaUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Where the payment is (designs C4 → C5 → C6). */
enum class PayStep { Pick, Amount, Signing, Sending, Done }

/**
 * Who is being paid and why: a person (C4), a person or an address from a chat, a contribution to a group's goal
 * (D3: `goalName`, the treasurer as `userId`), or a treasurer paying an approved request (D5: `requestId`).
 */
data class PayIntent(
    val userId: String? = null,
    val chatId: String? = null,
    val displayName: String? = null,
    val amount: String? = null,
    val address: String? = null,
    val goalName: String? = null,
    val requestId: String? = null,
    /** Group wallets: pay into this group's wallet (a contribution from its chat, GW9a2/GW9d). */
    val groupId: String? = null,
    /** Group wallets: pay from this group wallet; with [requestId] it is an approved payout (GW9), else a request (GW9c). */
    val fromWalletId: String? = null,
    /** The note typed after the amount in the chat ("#1000 November"). */
    val note: String? = null,
    /**
     * Driven from a chat (Pasaka, 2026-11-04): the chat's money card is the review, so once the PIN is done the card in
     * the thread carries Sending → Sent and this resets for the next payment, rather than waiting here (GW17).
     */
    val inChat: Boolean = false,
) {
    val contribution get() = groupId != null || (goalName != null && requestId == null)
    val payout get() = requestId != null
}

/** GW19 (and G2): the payment didn't go. Nothing was taken from [wallet]; [reference] is the request id for support. */
data class PayFailure(val wallet: String, val message: String, val reference: String?)

/**
 * The one Circaso fee (spaces-plan §7b: network cost included, the sender pays it on top, quoted by W17 for this exact
 * payment and held 15 minutes, then charged by W4 with [Quoted.quoteId]). Phase 2F, 2026-11-04.
 */
sealed interface PayFee {
    /** No fee line yet (no amount), or a server that takes no fees. */
    data object None : PayFee
    /** GW13: being worked out; Pay waits. */
    data object Quoting : PayFee
    /**
     * GW9b/GW9d/GW13b: [amountMinor] in the payment's coin at [rateBps] (0..200); [minimum] when the floor that covers
     * the network cost set it. [quoteId] holds it for W4 until [expiresAt] (only for the wallet's signer).
     */
    data class Quoted(
        val amountMinor: String,
        val rateBps: Int?,
        val minimum: Boolean = false,
        val quoteId: String? = null,
        val expiresAt: String? = null,
    ) : PayFee
    /** GW13d: couldn't be worked out (5xx after retries, or the network). Nothing can be signed; Try Again. */
    data class Failed(val reference: String?, val message: String? = null, val retryable: Boolean = true) : PayFee
    /** Offline: a fee can't be worked out, and money doesn't move offline anyway (G3/GW15). */
    data object Offline : PayFee
}

/** GW13c: W4 found a higher fee than the one shown (the quote ran out); the person sees it before signing. */
data class FeeChange(val fromMinor: String, val toMinor: String)

/**
 * Why W17 (or W4's refusal) says this payment can't go as it is (spaces-plan §7a, GW14/GW14b/GW20). Amounts in the
 * payment's coin minor units; [fitsAt] (ISO) when this payment fits the rolling 24 hours, null when it never can.
 */
sealed interface PayBlock {
    /** GW20: the amount fits but not with its fee; [maxAmountMinor] is the most that does. */
    data class Funds(val maxAmountMinor: String?) : PayBlock
    /** One payment's cap. */
    data class PerPayment(val maxAmountMinor: String?) : PayBlock
    /** GW14/GW14b: the rolling 24-hour cap, fees included. */
    data class Daily(val leftMinor: String?, val dailyMaxMinor: String?, val fitsAt: String?, val moreAt: String?, val maxAmountMinor: String?) : PayBlock
}

/** Why the chosen person can't be paid right now, and what the screen offers. */
sealed interface PayeeProblem {
    data object NoWallet : PayeeProblem
    data class NotOnChain(val theirChains: List<String>) : PayeeProblem
    data class Other(val message: String) : PayeeProblem
}

/** "@btc" means Bitcoin, which on these networks is a wrapped token; likewise ETH. */
private val AssetAliases = mapOf(
    "btc" to setOf("btc", "wbtc", "cbbtc"),
    "eth" to setOf("eth", "weth"),
)

data class PayUiState(
    val step: PayStep = PayStep.Amount,
    val payee: Person? = null,
    val chatId: String? = null,
    val contacts: List<Person> = emptyList(),
    val query: String = "",
    val wallet: Wallet? = null,
    val online: Boolean = true,
    /** The chain the payment leaves from: the wallet's default, or one the payee can receive on. */
    val blockchain: String? = null,
    /** True when the person types in fiat; the coin amount is derived from the coin's price and the wallet's rate. */
    val inFiat: Boolean = true,
    val amountText: String = "",
    val note: String = "",
    val checking: Boolean = false,
    val problem: PayeeProblem? = null,
    val reminded: RemindOutcome? = null,
    val error: String? = null,
    val result: Transfer? = null,
    val goalName: String? = null,
    val requestId: String? = null,
    /** The group's goal, for "after your contribution" (design D3). */
    val chama: Chama? = null,
    /** The payment went but linking it to the request (CH6) hasn't yet: the treasurer must not pay twice. */
    val attachPending: Boolean = false,
    val attaching: Boolean = false,
    /** The chat's "@eth" / "@btc" (design C3 extension): which asset to pay in; null is USDC. */
    val assetTag: String? = null,
    // ---- Group wallets (phase 2)
    /** Group wallets are on (the flag and a server that has them). */
    val groupWallets: Boolean = false,
    /** Paying into this group's wallet: a contribution (GW9d). */
    val groupId: String? = null,
    /** The wallet the money leaves (GW9b's "Paying from"); Personal unless switched or paying a payout. */
    val payingFrom: NamedWallet = NamedWallet.Personal,
    /** Every wallet the person can send from (W16 `canSign`, active), personal first. One means no chip. */
    val choices: List<NamedWallet> = emptyList(),
    /** Each choice's last balance, for GW10's lines. */
    val balances: Map<String?, Wallet?> = emptyMap(),
    /** GW10 is open. */
    val choosing: Boolean = false,
    /** The group's money: the one paid into (target after this) or the one paid from (approvals). */
    val groupMoney: com.circaso.feature.chama.domain.GroupMoney? = null,
    /** The group's members' names, for "Approved by Otieno and Wanjiru". */
    val memberNames: Map<String, String> = emptyMap(),
    /** GW19: didn't go; nothing was taken. */
    val failure: PayFailure? = null,
    /** GW9c done: the request the admins now decide on. */
    val requested: com.circaso.feature.chama.domain.ChamaRequest? = null,
    /** When the wallets' balances were read, for "as of" offline. */
    val asOf: String? = null,
    /** Payout requests and approvals (parked 2026-11-06): off, the group wallet's owner pays directly. */
    val approvals: Boolean = com.circaso.core.flags.GroupPayoutApprovals.Enabled,
    /** The person themselves, so a group wallet's owner can pay its money to their own wallet (2026-11-06). */
    val me: Person? = null,
    /** §7b's fee line, from W17 (phase 2F). */
    val fee: PayFee = PayFee.None,
    /**
     * What the typed number is in, fixed when it is typed, a coin is tagged or the unit is switched: true is the
     * person's currency, false the coin; null until then (follow [inFiat]). A later wallet refresh never changes it, so
     * a price that goes away can't turn "KES 500" into 500 ETH (design review, 2026-11-06).
     */
    val typedInFiat: Boolean? = null,
    /** The wallet copy's prices are recent enough to turn money into a coin other than a dollar coin. */
    val pricesFresh: Boolean = true,
    /** Asking for a fresh coin price (the wallet the money leaves from): what the card says while the price waits. */
    val priceRefresh: PriceRefresh = PriceRefresh.Idle,
    /** The fee shown was asked for an earlier version of this payment; the new answer is on its way and Pay waits. */
    val feeStale: Boolean = false,
    /** GW13c: the fee rose since it was shown; said once, until the next quote. */
    val feeChange: FeeChange? = null,
    /** GW14/GW14b/GW20: what stops this payment as it is, from W17 or W4's refusal. */
    val block: PayBlock? = null,
) {
    /** Paying from a group wallet (only ever its signer). */
    val fromGroupWallet get() = !payingFrom.personal
    /** GW9: an approved payout, paid from the group wallet with its PIN. */
    val groupPayout get() = groupWallets && requestId != null && fromGroupWallet
    /** GW9c: from a group wallet without an approved request, while approvals are on: Request Payout (CH4). */
    val requestPayout get() = groupWallets && approvals && requestId == null && fromGroupWallet
    /** 2026-11-06: the group wallet's owner (its only signer) pays from it directly, no request, no approvals. */
    val directPayout get() = groupWallets && !approvals && requestId == null && fromGroupWallet
    /** The owner paying the group's money to their own wallet. */
    val toSelf get() = me != null && payee?.userId == me.userId
    /** Pay into a group wallet. */
    val toGroup get() = groupId != null
    /** Show GW9b's chip: the person has other wallets, or this one is a group's. */
    val showPayingFrom get() = groupWallets && (choices.size > 1 || fromGroupWallet)
    /** The chip opens GW10: there is something to switch to, and nothing fixes the wallet (a payout, a contribution). */
    val canSwitch get() = showPayingFrom && choices.size > 1 && requestId == null && !toGroup && !toSelf && step == PayStep.Amount
    /** The approved request being paid. */
    val payoutRequest get() = requestId?.let { id -> groupMoney?.openRequests?.firstOrNull { it.requestId == id } }
    /** The wallet's name as the approval says it ("Approve from Kilimani Chama"). */
    val walletName get() = payingFrom.name
    /** Who sends from the group wallet, for a member who asks (GW9c). */
    val signerName get() = memberNames[payingFrom.signerUserId ?: groupMoney?.wallet?.signerUserId]?.substringBefore(' ') ?: "its signer"
    /** The held asset the tag names ("@btc" also matches wrapped BTC), on the chain that holds the most of it; null for USDC. */
    val tagged: Pair<String, com.circaso.feature.wallet.domain.Asset>?
        get() {
            val tag = assetTag?.lowercase() ?: return null
            if (tag == "usdc") return null
            val names = AssetAliases[tag] ?: setOf(tag)
            return wallet?.chains.orEmpty()
                .flatMap { chain -> chain.assets.map { chain.blockchain to it } }
                .filter { (_, a) -> a.symbol.lowercase() in names && !MinorUnits.isZero(a.amountMinor) }
                .maxWithOrNull { x, y -> if (MinorUnits.lessThan(x.second.amountMinor, y.second.amountMinor)) -1 else 1 }
        }

    /** "@eth" was typed but this wallet holds none. */
    val assetMissing get() = assetTag != null && assetTag.lowercase() != "usdc" && tagged == null

    /** What the payment is in: USDC unless a held asset was tagged. */
    val asset: String get() = tagged?.second?.symbol ?: Wallet.Usdc
    /** The payment coin's decimals (6 for USDC). */
    val decimals: Int get() = tagged?.second?.decimals ?: 6
    val contribution get() = toGroup || (goalName != null && requestId == null)
    val payout get() = requestId != null
    /** The request is marked paid only once the link has landed (treasurer model; W4 links it with group wallets). */
    val linked get() = payout && result != null && !attaching && !attachPending
    val currency get() = wallet?.fiat?.currency
    /** One US dollar in the person's currency (W1 `fiat.rate`). */
    val rate get() = wallet?.fiat?.rate

    /**
     * One whole unit of the payment's coin in the person's currency: its US dollar price (W1 `usdPrice`; dollar coins
     * are 1 even without one) × [rate]. Null when the coin has no price: then it is typed and shown in its own units.
     */
    val unitRate: String?
        get() {
            if (currency == null) return null
            val coin = tagged?.second
            // An old copy's price isn't used to work out how much of a coin to send; a fresh one is on its way.
            if (coin != null && !MinorUnits.isDollar(coin.symbol) && !pricesFresh) return null
            return unitFiatRate(coin, rate)
        }

    /** The payment's coin can be shown and typed in the person's currency. */
    val canUseFiat get() = unitRate != null

    /** The coin has a price at all (perhaps not a fresh one): what decides the unit a number is typed in. */
    val coinPriced get() = currency != null && rate != null && (tagged?.second?.dollarPrice ?: if (tagged == null) "1" else null) != null

    /** The unit a number typed now would be in: the person's currency when the coin has a price. */
    val fiatUnitNow get() = inFiat && coinPriced

    /** Typing in the person's currency: "#500 @eth" is KES 500 worth of ETH. */
    val typingFiat get() = typedInFiat ?: fiatUnitNow

    /** Typed in the person's currency, but there's no usable price to turn it into the coin: Pay waits, nothing is guessed. */
    val priceWaiting get() = typingFiat && unitRate == null && amountText.any { it in '1'..'9' }

    /** The coin amount in minor units for what was typed (rounded down, never through a float), or null when it isn't an amount yet. */
    val amountMinor: String?
        get() = if (typingFiat) unitRate?.let { fiatToCoinMinor(amountText, it, decimals) } else MinorUnits.fromDisplay(amountText, decimals)

    /** USDC on the chosen chain. */
    val balanceMinor: String get() = tagged?.second?.amountMinor ?: wallet?.chains?.firstOrNull { it.blockchain == blockchain }?.usdc?.amountMinor ?: "0"

    /** The chain the money leaves from: the tagged asset's, else the chosen one. */
    val payChain: String? get() = tagged?.first ?: blockchain

    val usdcText: String? get() = amountMinor?.let { MinorUnits.toDisplay(it, decimals) + " " + asset }

    /**
     * The fee row's words (GW9b, GW13, GW13b, GW13d): "No fee", "Working it out", "KES 2.50 · 0.5%", "KES 5 · minimum",
     * and for a payout request, whose fee is set when it is paid, "about KES 50 · 1%, set when paid" (GW9c).
     */
    /** The fee alone, in the person's currency ("KES 5.17"), for the chat's slim card; "No fee" when none is taken. */
    val feeShort: String
        get() = (fee as? PayFee.Quoted)?.takeIf { !MinorUnits.isZero(it.amountMinor) }?.let { moneyText(it.amountMinor) } ?: feeText

    val feeText: String
        get() = when (val f = fee) {
            PayFee.None -> "No fee"
            PayFee.Quoting -> "Working it out"
            PayFee.Offline -> "Needs a connection"
            is PayFee.Failed -> "Couldn’t work it out"
            is PayFee.Quoted -> if (MinorUnits.isZero(f.amountMinor)) "No fee" else {
                val money = moneyText(f.amountMinor)
                val why = if (f.minimum) "minimum" else f.rateBps?.let(::rateText)
                val line = listOfNotNull(money, why).joinToString(" · ")
                if (requestPayout) "about $line, set when paid" else line
            }
        }

    /** A coin amount in the person's currency when the coin is priced ("KES 2.50", "KES 5"), else in the coin. */
    fun moneyText(minor: String): String =
        localCents(minor)?.let { "$currency ${fiatDisplay(it)}" } ?: (MinorUnits.toDisplay(minor, decimals) + " " + asset)

    /** [minor] of the payment's coin in the person's currency, in cents; null when the coin has no price. */
    private fun localCents(minor: String): String? =
        unitRate?.let { coinToFiat(minor, decimals, it) }?.let { MinorUnits.fromDisplay(it, 2) }

    /** The fee's minor units, when one is quoted. */
    val feeMinor: String? get() = (fee as? PayFee.Quoted)?.amountMinor

    /** The fee is known (or there is none), so the total can be shown and signed. */
    val feeSettled: Boolean get() = (fee is PayFee.None || fee is PayFee.Quoted) && !feeStale

    /** A payout request may be over today's cap (it waits once approved, GW14); everything else waits on [block]. */
    val blocked: Boolean get() = block != null && !requestPayout

    /** What leaves the wallet: the amount plus the fee (the receiver gets exactly the amount, §7b). */
    val totalMinor: String? get() = amountMinor?.let { a -> (fee as? PayFee.Quoted)?.let { MinorUnits.add(a, it.amountMinor) } ?: a }

    /** The button's amount: the total, in the person's currency when typed in it. */
    val totalText: String?
        get() {
            val feeMinor = (fee as? PayFee.Quoted)?.amountMinor
            typedFiatCents?.let { cents ->
                val feeCents = feeMinor?.let(::localCents) ?: "0"
                return "$currency ${fiatDisplay(MinorUnits.add(cents, feeCents))}"
            }
            return totalMinor?.let { t -> localCents(t)?.takeIf { inFiat }?.let { "$currency ${fiatDisplay(it)}" } ?: (MinorUnits.toDisplay(t, decimals) + " " + asset) }
        }
    /** Typed in the person's currency: what they typed ("#500" is KES 500, never the 499.99 a round trip through the coin gives). */
    private val typedFiatCents: String? get() = if (typingFiat && currency != null) MinorUnits.fromDisplay(amountText, 2) else null
    val fiatText: String?
        get() = typedFiatCents?.let { "$currency ${fiatDisplay(it)}" }
            ?: amountMinor?.let(::localCents)?.let { "$currency ${fiatDisplay(it)}" }
    /**
     * What the wallet holds of the payment's coin, as the over-balance line says it: in the person's money with the coin
     * short ("KES 5,584 (0.01234 ETH)"), both rounded down so a balance never reads higher than it is; else the coin.
     */
    val balanceText: String
        get() {
            val coin = com.circaso.feature.wallet.domain.coinShort(balanceMinor, decimals, asset, roundDown = true)
            val money = unitRate?.let { coinToFiat(balanceMinor, decimals, it, roundDown = true) }?.let { MinorUnits.fromDisplay(it, 2) }
            return if (money != null) "$currency ${fiatDisplay(money)} ($coin)" else coin
        }
    val overBalance: Boolean get() = totalMinor?.let { MinorUnits.lessThan(balanceMinor, it) } ?: false
    val onHold: Boolean get() = wallet?.holdUntil != null
    val canPay: Boolean get() = online && !onHold && payee != null && payChain != null && problem == null && !checking && !assetMissing && amountMinor?.let { !MinorUnits.isZero(it) } == true && !overBalance && step == PayStep.Amount && (feeSettled || requestPayout) && !blocked &&
        // A request needs the group's own chat to post in; a payout needs the request it pays.
        (!requestPayout || payingFrom.chatId != null) && !(toSelf && !fromGroupWallet)
    val filteredContacts get() = contacts.filter { query.isBlank() || it.displayName.contains(query, true) || it.handle?.contains(query.trimStart('@'), true) == true }

    /** 0..1 of the goal once this contribution lands. */
    val progressAfter: Float?
        get() {
            groupMoney?.takeIf { toGroup }?.let { m ->
                val target = m.goal?.targetMinor?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
                val got = (m.contributedMinor.toDoubleOrNull() ?: 0.0) + (amountMinor?.toDoubleOrNull() ?: 0.0)
                return (got / target).coerceIn(0.0, 1.0).toFloat()
            }
            val c = chama ?: return null
            val target = c.goal.targetMinor.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            val got = (c.contributedMinor.toDoubleOrNull() ?: 0.0) + (amountMinor?.toDoubleOrNull() ?: 0.0)
            return (got / target).coerceIn(0.0, 1.0).toFloat()
        }
}

/**
 * Paying a person (C4 Send sheet, C5 PIN in the Circle SDK, C6 the moment). The person is checked with W9 as soon as
 * they are chosen; the payment goes through W4, is signed in the SDK, and its card is sent into the chat with them,
 * encrypted, so both sides see it (the receiver loads W5 from the card).
 */
class PayViewModel(
    private val intent: PayIntent,
    observeWallet: ObserveWalletUseCase,
    private val checkRecipient: CheckRecipientUseCase,
    private val remind: RemindRecipientUseCase,
    private val pay: PayUseCase,
    observeContacts: ObserveContactsUseCase,
    connection: ConnectionState,
    private val sendCard: SendPaymentCardUseCase,
    private val openDirectChat: OpenDirectChatUseCase,
    private val attachPayment: AttachChamaPaymentUseCase,
    observeChama: ObserveChamaUseCase,
    private val scanner: com.circaso.core.qr.QrScanner = com.circaso.core.qr.NoQrScanner,
    private val findHandle: (suspend (String) -> AppResult<com.circaso.feature.profile.domain.PublicProfile>)? = null,
    // Group wallets (phase 2); off and absent in the treasurer model, tests and previews.
    private val groupWallets: com.circaso.core.flags.GroupWalletsFlag = com.circaso.core.flags.GroupWalletsFlag.off(),
    private val wallets: GroupWalletSources? = null,
    /** The phone's copy of one transfer: GW17 waits on it, so a group payment says Paid only once the chain has it. */
    private val observeTransfer: com.circaso.feature.wallet.domain.ObserveTransferUseCase? = null,
    /** W17, the fee and limits before signing (phase 2F); absent in older tests and previews, which show no fee. */
    private val quotePayment: com.circaso.feature.wallet.domain.QuotePaymentUseCase? = null,
    /** The pause after the last change before quoting again (GW13: "after a short pause"). */
    private val quoteDelayMillis: Long = QuoteDelayMillis,
    /** The clock the quote's hold and the limit's time are measured against (tests move it). */
    private val now: () -> Long = { com.circaso.core.sync.nowMillis() },
    /** The person themselves (their profile), for "Your wallet" when a group wallet's owner pays out (2026-11-06). */
    observeMe: kotlinx.coroutines.flow.Flow<Person?> = flowOf(null),
    /** Payout requests and approvals (parked 2026-11-06, [com.circaso.core.flags.GroupPayoutApprovals]). */
    approvals: Boolean = com.circaso.core.flags.GroupPayoutApprovals.Enabled,
    /**
     * A fresh coin price for the wallet the money leaves from (null: W1, the personal wallet; else W16 for that group
     * wallet), when a coin other than a dollar coin is tagged or its price grows old; absent in older tests and previews.
     */
    private val refreshPrices: (suspend (walletId: String?) -> AppResult<*>)? = null,
) : ViewModel() {
    private val userId = intent.userId
    private val chatId = intent.chatId
    private val address = intent.address
    private val local = MutableStateFlow(
        PayUiState(
            step = if (userId == null && address == null && intent.groupId == null) PayStep.Pick else PayStep.Amount, chatId = chatId,
            amountText = intent.amount?.filter { it.isDigit() || it == '.' }.orEmpty(), goalName = intent.goalName, requestId = intent.requestId,
            // A request's amount is exact USDC (CH3); a `#500` shortcut is in the person's currency.
            inFiat = !intent.payout,
            groupId = intent.groupId, note = intent.note.orEmpty(), approvals = approvals,
        ),
    )
    /** The wallet chosen to pay from: the intent's group wallet, or one picked in GW10; null is Personal. */
    private val fromId = MutableStateFlow(intent.fromWalletId)
    private var idempotencyKey = newIdempotencyKey()
    private var pendingAttach: Pair<String, String>? = null

    /** The group whose money matters here: the one paid into, or the one whose wallet pays. */
    private val groupChatId: kotlinx.coroutines.flow.Flow<String?> =
        if (intent.groupId != null) flowOf(intent.groupId)
        else combine(fromId, wallets?.named ?: flowOf(emptyList())) { id, list ->
            id?.let { w -> list.firstOrNull { it.walletId == w }?.chatId ?: chatId }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val groupSide: kotlinx.coroutines.flow.Flow<GroupSide> = if (wallets == null) flowOf(GroupSide()) else
        combine(groupWallets.enabled, fromId, wallets.named, groupChatId) { on, from, named, group -> Triple(on, from, named) to group }
            .flatMapLatest { (t, group) ->
                val (on, from, named) = t
                if (!on) return@flatMapLatest flowOf(GroupSide())
                val choices = named.filter { it.canSign && it.status == com.circaso.feature.wallet.domain.WalletStatus.Active }
                    .ifEmpty { listOf(NamedWallet.Personal) }
                    .let { list -> if (list.none { it.personal }) listOf(NamedWallet.Personal) + list else list }
                val paying = from?.let { id -> named.firstOrNull { it.walletId == id } ?: NamedWallet(id, chatId, "the group", null, com.circaso.feature.wallet.domain.WalletOwnerType.Group, true, com.circaso.feature.wallet.domain.WalletStatus.Active, null) } ?: NamedWallet.Personal
                combine(
                    wallets.walletOf(from), wallets.balances(choices), group?.let { wallets.money(it) } ?: flowOf(null), group?.let { wallets.chat(it) } ?: flowOf(null),
                ) { source, balances, money, chat ->
                    val chatName = chat?.title
                    GroupSide(
                        on = true, choices = choices, balances = balances, money = money,
                        payingFrom = if (!paying.personal && chatName != null && paying.name == "the group") paying.copy(name = chatName, avatarUrl = chat.avatarUrl) else paying,
                        source = source, sourceIsGroup = from != null,
                        names = chat?.members.orEmpty().associate { it.userId to it.displayName },
                        groupTitle = chatName, groupAvatar = chat?.avatarUrl,
                    )
                }
            }

    /** Bumped when the wallet copy's prices pass [PriceFreshMillis], so [PayUiState.pricesFresh] is worked out again. */
    private val priceTick = MutableStateFlow(0)

    val state: StateFlow<PayUiState> = combine(
        combine(local, priceTick) { ui, _ -> ui }, observeWallet(), observeContacts(), connection.status,
        combine(if (intent.goalName != null && chatId != null) observeChama(chatId) else flowOf(null), groupSide, observeMe) { ch, g, m -> Triple(ch, g, m) },
    ) { ui, personal, contacts, c, sides ->
        val (chama, group, me) = sides
        val people = contacts.map { it.person }
        // The money leaves the chosen wallet: its chains and balance, and its fiat rate (the server prices each one).
        val wallet = if (group.sourceIsGroup) group.source else personal
        ui.copy(
            wallet = wallet,
            contacts = people,
            online = c == ConnectionStatus.Online,
            chama = chama,
            payee = ui.payee
                ?: userId?.let { id -> people.firstOrNull { it.userId == id } ?: Person(id, intent.displayName ?: "Them", null, null, null) }
                ?: address?.let { Person("", intent.displayName ?: shortAddress(it), null, null, null) }
                // Paying into a group: the group itself is who receives (its name and photo come from the chat).
                ?: intent.groupId?.let { Person("", group.groupTitle ?: intent.displayName ?: "The group", null, group.groupAvatar, null) },
            blockchain = ui.blockchain?.takeIf { b -> wallet?.chains?.any { it.blockchain == b } != false } ?: wallet?.chainsReceiving?.firstOrNull()?.blockchain,
            inFiat = ui.inFiat && wallet?.fiat != null,
            pricesFresh = wallet == null || now() - wallet.fetchedAt < PriceFreshMillis,
            groupWallets = group.on,
            payingFrom = group.payingFrom,
            choices = group.choices,
            balances = group.balances,
            groupMoney = group.money,
            memberNames = group.names,
            me = me,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    // Declared before init: the quoting collector starts at once (Main.immediate) and would read them unset.
    /** Bumped by Try Again on GW13d, so the same payment is quoted once more. */
    private val quoteTry = MutableStateFlow(0)

    /** The server has no W17 (before phase 2F): no fee is charged there, so nothing more is asked. */
    private var quotesUnsupported = false

    /** What the quote on the card was asked for; a re-ask of the same payment compares against it (GW13c). */
    private var quotedFor: QuoteKey? = null

    /** Marks the fee on the card out of date at once, so a tap can't sign it for another amount (before the re-ask). */
    private fun staleFee() { if (quotePayment != null && !quotesUnsupported) local.update { it.copy(feeStale = true) } }

    /** GW10: open or close the switcher. */
    fun onChoosing(open: Boolean) { if (!open || state.value.canSwitch) local.update { it.copy(choosing = open) } }

    /** GW10: pay from another wallet. A group wallet turns the review into Request Payout (GW9c). */
    fun onPayFrom(wallet: NamedWallet) {
        fromId.value = wallet.walletId
        idempotencyKey = newIdempotencyKey()
        staleFee()
        local.update { it.copy(choosing = false, blockchain = null, failure = null) }
        state.value.payee?.userId?.takeIf { it.isNotEmpty() && it != state.value.me?.userId }?.let { check(it, null) }
    }

    init {
        // An address can't be checked (W9 is for people); the chain validates it when the payment goes. A group's
        // wallet is checked by W4 itself (members only).
        if (userId != null) check(userId, null)
        wallets?.let { w -> viewModelScope.launch { if (groupWallets.isOn) w.refresh(intent.fromWalletId, intent.groupId ?: intent.chatId) } }
        viewModelScope.launch { keepPricesFresh() }
        quotePayment?.let { quote ->
            viewModelScope.launch { quoteAsTyped(quote) }
            viewModelScope.launch { keepQuoteForTheSamePayment() }
        }
    }

    /**
     * While a coin other than a dollar coin is tagged, its price is kept fresh: when the wallet copy's prices pass
     * [PriceFreshMillis] (or the connection comes back with an old one) a fresh copy of the wallet the money leaves from
     * is asked for. A failure is said on the card with Try Again; nothing loops (2026-11-06 design review).
     */
    private suspend fun keepPricesFresh() {
        state.map { s ->
            val wants = s.currency != null && s.tagged?.second?.let { !MinorUnits.isDollar(it.symbol) } == true
            Triple(wants, s.wallet?.fetchedAt, s.online)
        }
            .distinctUntilChanged()
            .collectLatest { (wants, fetchedAt, online) ->
                if (!wants || fetchedAt == null || !online) return@collectLatest
                val left = fetchedAt + PriceFreshMillis - now()
                if (left > 0) {
                    delay(left)
                    priceTick.update { it + 1 }
                }
                if (fetchedAt + PriceFreshMillis - now() <= 0) refreshPrice()
            }
    }

    private var priceJob: kotlinx.coroutines.Job? = null

    /** Asks for a fresh coin price once (never while one is on its way, never offline: the offline line says why). */
    private fun refreshPrice() {
        val refresh = refreshPrices ?: return
        if (priceJob?.isActive == true || !state.value.online) return
        priceJob = viewModelScope.launch {
            local.update { it.copy(priceRefresh = PriceRefresh.Loading) }
            val r = refresh(fromId.value)
            local.update { it.copy(priceRefresh = if (r is AppResult.Failure) PriceRefresh.Failed(ErrorMessages.reference(r.error)) else PriceRefresh.Idle) }
        }
    }

    /** The card's Try Again when a fresh price couldn't be had. */
    fun onRetryPrice() = refreshPrice()

    /**
     * Marked stale but nothing that decides the fee changed (in the chat, typing the note after "#500" passes the same
     * amount again): the quote on the card is still this payment's, so it counts again at once. Never asks W17 itself,
     * so a failed quote can't turn into a loop. Without it Pay waited forever (2026-11-05).
     */
    private suspend fun keepQuoteForTheSamePayment() {
        state.map { s -> if (s.feeStale && s.fee is PayFee.Quoted) keyOf(s)?.request else null }
            .distinctUntilChanged()
            .collect { request -> if (request != null && quotedFor?.request == request) local.update { it.copy(feeStale = false) } }
    }

    /**
     * GW13: whenever what decides the fee changes (who, how much, which coin and network, Paying from), the fee row says
     * "Working it out" at once and Pay waits; after a short pause W17 is asked, and only the latest answer counts.
     * Offline there is no quote (money never moves offline, G3/GW15). Nothing is guessed: a failure is GW13d.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun quoteAsTyped(quote: com.circaso.feature.wallet.domain.QuotePaymentUseCase) {
        combine(state, quoteTry) { s, attempt -> keyOf(s)?.copy(attempt = attempt) to s.online }
            .distinctUntilChanged()
            .collectLatest { (key, online) ->
                if (quotesUnsupported) return@collectLatest
                if (key == null) { local.update { it.copy(fee = PayFee.None, feeStale = false, block = null, feeChange = null) }; return@collectLatest }
                if (!online) { local.update { it.copy(fee = PayFee.Offline, feeStale = false, block = null) }; return@collectLatest }
                // The last answer stays on the card, marked stale, until the next one: the card doesn't jump while typing.
                val before = local.value.fee as? PayFee.Quoted
                val sameAsked = quotedFor?.request == key.request
                local.update { it.copy(fee = if (it.fee is PayFee.Quoted || it.fee is PayFee.Failed) it.fee else PayFee.Quoting, feeStale = true) }
                // A re-ask of the same payment (Try Again, a hold run out, the limit freed) goes at once.
                if (!sameAsked) delay(quoteDelayMillis)
                when (val r = quote(key.request)) {
                    is AppResult.Success -> {
                        val q = r.value
                        if (q == null) { quotesUnsupported = true; local.update { it.copy(fee = PayFee.None, feeStale = false, block = null) }; return@collectLatest }
                        quotedFor = key
                        local.update {
                            it.copy(
                                fee = PayFee.Quoted(q.feeMinor, q.rateBps, q.minimum, q.quoteId, q.expiresAt),
                                feeStale = false,
                                block = q.blockOf(),
                                feeChange = when {
                                    // GW13c: the same payment quoted again after its hold ran out, at another fee.
                                    sameAsked && before != null && before.amountMinor != q.feeMinor -> FeeChange(before.amountMinor, q.feeMinor)
                                    // Anything else asked is a new payment: the notice goes with the old one.
                                    sameAsked -> it.feeChange
                                    else -> it.feeChange?.takeIf { c -> c.toMinor == q.feeMinor }
                                },
                            )
                        }
                        // Ask again when the hold runs out (GW13c) or the daily limit frees this payment (GW14b).
                        millisUntil(q.expiresAt, (q.blockOf() as? PayBlock.Daily)?.fitsAt)?.let { wait ->
                            delay(wait)
                            quoteTry.update { it + 1 }
                        }
                    }
                    is AppResult.Failure -> local.update {
                        val api = r.error as? AppError.Api
                        // GW13d only for what Try Again can fix (Circle or the network); a refusal says why, as W4 would.
                        val retryable = api == null || api.code == "wallet.custody_unavailable" || api.httpStatus >= 500
                        it.copy(fee = PayFee.Failed(ErrorMessages.reference(r.error), ErrorMessages.forError(r.error), retryable), feeStale = false, block = null)
                    }
                }
            }
    }

    /** Milliseconds (plus a second) until the earlier of these instants still ahead; null when none is. */
    private fun millisUntil(vararg isos: String?): Long? {
        val at = now()
        return isos.mapNotNull { iso -> iso?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() - at }.getOrNull() } }
            .filter { it > 0 }.minOrNull()?.let { it + 1_000 }
    }

    /**
     * What W17 is asked for this card as it stands, or null when there is nothing to quote yet: no amount, no payee or
     * network, a payee that can't be paid, or a coin the wallet doesn't hold.
     */
    private fun keyOf(s: PayUiState): QuoteKey? {
        val payee = s.payee ?: return null
        val chain = s.payChain ?: return null
        val minor = s.amountMinor?.takeIf { !MinorUnits.isZero(it) } ?: return null
        // Not the step: signing keeps the quote it signs, and coming back to the card mustn't ask again.
        if (s.step == PayStep.Pick || s.problem != null || s.assetMissing) return null
        val toAddress = address?.takeIf { payee.userId.isEmpty() && !s.toGroup }
        val toUser = payee.userId.takeIf { it.isNotEmpty() && toAddress == null && !s.toGroup }
        if (toAddress == null && toUser == null && s.groupId == null) return null
        return QuoteKey(
            PaymentRequest(
                blockchain = chain, toUserId = toUser, toAddress = toAddress, amountMinor = minor, asset = s.asset, chatId = chatId,
                toGroupId = s.groupId, fromWalletId = s.payingFrom.walletId,
            ),
        )
    }

    /** GW13d's Try Again: the same payment, quoted again. */
    fun onRetryQuote() { quoteTry.update { it + 1 } }

    /** GW14 / GW20's offer: the most that fits, in the unit the person is typing in, never a cent over. */
    fun onUseMax() {
        val s = state.value
        val max = when (val b = s.block) {
            is PayBlock.Funds -> b.maxAmountMinor
            is PayBlock.PerPayment -> b.maxAmountMinor
            is PayBlock.Daily -> b.maxAmountMinor
            null -> null
        } ?: return
        if (MinorUnits.isZero(max)) return
        // The same amount the offer names (FeeParts.fittedAmount): never a cent over what fits.
        val text = fittedAmount(s, max) ?: return
        staleFee()
        local.update { it.copy(amountText = text, error = null, failure = null) }
    }

    fun onQuery(q: String) = local.update { it.copy(query = q) }

    val canScan: Boolean get() = scanner.available && findHandle != null

    /** Design E4 the other way round: scan someone's Circaso code to pay them; a wallet code opens nothing here. */
    fun onScan() {
        val find = findHandle ?: return
        viewModelScope.launch {
            val raw = scanner.scan() ?: return@launch
            when (val code = com.circaso.core.qr.QrPayload.read(raw)) {
                is com.circaso.core.qr.QrPayload.Scanned.Handle -> when (val r = find(code.handle)) {
                    is AppResult.Success -> onPick(Person(r.value.userId, r.value.displayName, r.value.handle, r.value.avatarUrl, r.value.about))
                    is AppResult.Failure -> local.update { it.copy(error = ErrorMessages.forError(r.error)) }
                }
                is com.circaso.core.qr.QrPayload.Scanned.Address -> local.update { it.copy(error = "That's a wallet address. Use Send to a Wallet for it.") }
                com.circaso.core.qr.QrPayload.Scanned.Unknown -> local.update { it.copy(error = "That code isn't a Circaso code.") }
            }
        }
    }

    fun onPick(person: Person) {
        local.update { it.copy(payee = person, step = PayStep.Amount, problem = null, reminded = null, error = null) }
        // The owner's own wallet (the group's money to themselves) isn't W9's question; W4 answers for it.
        if (person.userId != state.value.me?.userId) check(person.userId, state.value.blockchain)
    }

    /** Back from the amount: to the person picker when that's where the payment started; false means leave Pay. */
    fun onBackToPick(): Boolean {
        if (userId != null || address != null || intent.groupId != null || state.value.step != PayStep.Amount) return false
        local.update { it.copy(step = PayStep.Pick) }
        return true
    }

    private fun check(userId: String, blockchain: String?) {
        local.update { it.copy(checking = true) }
        viewModelScope.launch {
            val chain = blockchain ?: state.value.blockchain
            when (val r = checkRecipient(userId, chain, chatId)) {
                is AppResult.Success -> local.update {
                    it.copy(
                        checking = false,
                        problem = when (r.value.status) {
                            RecipientStatus.CanReceive -> null
                            RecipientStatus.NoWallet -> PayeeProblem.NoWallet
                            RecipientStatus.NoWalletOnChain -> PayeeProblem.NotOnChain(r.value.blockchains)
                        },
                    )
                }
                is AppResult.Failure -> local.update { it.copy(checking = false, problem = PayeeProblem.Other(ErrorMessages.forError(r.error))) }
            }
        }
    }

    /** They can be paid on one of their chains: pay from there if this wallet is on it too. */
    fun onChain(blockchain: String) {
        staleFee()
        local.update { it.copy(blockchain = blockchain, problem = null) }
        state.value.payee?.let { check(it.userId, blockchain) }
    }

    fun onRemind() {
        val payee = state.value.payee ?: return
        viewModelScope.launch {
            when (val r = remind(payee.userId)) {
                is AppResult.Success -> local.update { it.copy(reminded = r.value, problem = if (r.value == RemindOutcome.HasWallet) null else it.problem) }
                is AppResult.Failure -> local.update { it.copy(error = ErrorMessages.forError(r.error)) }
            }
        }
    }

    fun onAmount(text: String) {
        // A request's amount is what the admins approved; changing it would fail the link after the money left.
        if (state.value.payout) return
        // Digits and one point; the decimals the unit allows (2 for fiat, the coin's own otherwise).
        val clean = text.filter { it.isDigit() || it == '.' }
        if (clean.count { it == '.' } > 1) return
        val s = state.value
        // The person's own choices from [local] (the derived state can lag a toggle made a moment ago).
        val inFiat = typingFiatNow(s)
        val max = if (inFiat) 2 else s.decimals
        if (clean.substringAfter('.', "").length > max) return
        staleFee()
        local.update { it.copy(amountText = clean, error = null, typedInFiat = if (clean.isEmpty()) null else inFiat) }
    }

    fun onToggleUnit() {
        val s = state.value
        if (!s.canUseFiat || s.payout) return
        // Carry the value across so the number doesn't jump.
        val minor = s.amountMinor
        // From what the number is in now, not the default: a coin typed in its units before it had a price switches to money.
        val next = !typingFiatNow(s)
        val text = when {
            minor == null || MinorUnits.isZero(minor) -> ""
            next -> s.unitRate?.let { coinToFiat(minor, s.decimals, it) } ?: ""
            else -> MinorUnits.toDisplay(minor, s.decimals).replace(",", "")
        }
        staleFee()
        local.update { it.copy(inFiat = next, amountText = text, typedInFiat = next) }
    }

    /** [PayUiState.typingFiat] from the person's latest choices in [local], the price from the derived state. */
    private fun typingFiatNow(s: PayUiState): Boolean = local.value.let { l ->
        // The coin from [local] too: a coin changed a moment ago (the call sheet's chooser) isn't in [s] yet.
        l.typedInFiat ?: (l.inFiat && s.copy(assetTag = l.assetTag).coinPriced)
    }

    fun onNote(text: String) = local.update { it.copy(note = text.take(140)) }

    /** The chat's "@eth": pay in that asset if the wallet holds it (the recipient is checked on its chain). */
    fun onAssetTag(tag: String?) {
        if (local.value.assetTag == tag) return
        staleFee()
        // The unit is decided again for the coin now named: its currency when it has a price, else the coin (as before
        // prices). The person sees the change on the card at once; nothing changes it later behind their back.
        val next = state.value.copy(assetTag = tag)
        local.update { it.copy(assetTag = tag, typedInFiat = if (it.amountText.isEmpty()) null else next.fiatUnitNow) }
        // A coin other than a dollar coin is worked out at its price: ask for a fresh one at the moment it is tagged
        // (keepPricesFresh asks again once that grows old).
        if (next.tagged?.second?.let { !MinorUnits.isDollar(it.symbol) } == true) refreshPrice()
        state.value.payee?.userId?.takeIf { it.isNotEmpty() }?.let { check(it, state.value.payChain) }
    }

    /**
     * W4, the SDK, then the card into the chat. The same key on a retry returns the same transfer and challenge.
     * From a group wallet without an approved request this is CH4 instead (Request Payout, GW9c): no money moves.
     */
    fun onPay() {
        val s = state.value
        // The fee may have gone stale this instant (the amount just changed): never sign the old quote for it.
        if (!s.canPay || (local.value.feeStale && !s.requestPayout)) return
        // "You" is only the group's money to the owner; never a fee-paying transfer from their own wallet to itself.
        if (s.toSelf && !s.fromGroupWallet) return
        val payee = s.payee ?: return
        val chain = s.payChain ?: return
        val minor = s.amountMinor ?: return
        local.update { it.copy(step = PayStep.Signing, error = null, failure = null) }
        if (s.requestPayout) { requestPayout(s, payee, minor); return }
        viewModelScope.launch {
            val typed = s.note.trim().takeIf { it.isNotEmpty() }
            val group = s.groupMoney?.goal?.name ?: s.payee?.displayName.takeIf { s.toGroup }
            // The card says what the money was for: "Added to Holiday Fund", "Paid from Holiday Fund", or the note alone.
            val note = when {
                s.toGroup -> listOfNotNull("Added to ${s.groupMoney?.goal?.name ?: payee.displayName}", typed).joinToString(" · ")
                // 2026-11-06: the group chat's card says where the group's money went ("Paid from Diani to Pasaka").
                s.directPayout -> payoutNote(s.walletName, payee.displayName, address?.takeIf { payee.userId.isEmpty() }, typed)
                s.groupPayout -> listOfNotNull("Paid from ${s.walletName}", typed).joinToString(" · ")
                s.contribution -> listOfNotNull("Added to ${s.goalName}", typed).joinToString(" · ")
                s.payout -> listOfNotNull("Paid from ${s.goalName ?: "the group"}", typed).joinToString(" · ")
                else -> typed
            }
            val toAddress = address?.takeIf { payee.userId.isEmpty() && !s.toGroup }
            // Every approval names its wallet; a person with only a personal wallet keeps today's wording (GW11b).
            val subject = if (s.fromGroupWallet) s.payingFrom.subject else if (s.choices.size > 1) NamedWallet.Personal.subject else com.circaso.feature.wallet.domain.SignSubject.Personal
            val request = PaymentRequest(
                blockchain = chain, toUserId = payee.userId.takeIf { it.isNotEmpty() && toAddress == null && !s.toGroup }, toAddress = toAddress, amountMinor = minor,
                // A direct payout belongs to the group's chat, wherever it was started (W4 reads reach and membership there).
                asset = s.asset, chatId = if (s.directPayout) s.payingFrom.chatId ?: chatId else chatId, note = note, counterpartName = payee.displayName ?: group,
                toGroupId = s.groupId, fromWalletId = s.payingFrom.walletId, payoutRequestId = s.requestId.takeIf { s.groupPayout }, subject = subject,
                quoteId = (s.fee as? PayFee.Quoted)?.quoteId,
            )
            when (val r = pay(request, idempotencyKey)) {
                is AppResult.Success -> {
                    idempotencyKey = newIdempotencyKey()
                    val transfer = r.value
                    val card = PaymentCard(transfer.transferId, transfer.amount.amountMinor, transfer.amount.asset, transfer.blockchain, note)
                    val target = if (s.directPayout) sendPayoutCards(s, payee, toAddress, card)
                    else (chatId ?: if (toAddress == null && !s.toGroup) (openDirectChat(payee.userId) as? AppResult.Success)?.value?.chatId else null)
                        ?.also { sendCard(it, card) }
                    val follow = observeTransfer
                    if ((s.groupPayout || s.directPayout || s.toGroup) && follow != null && !transfer.settled && !intent.inChat) {
                        // GW17: signed, not yet on chain. The review stays, named; nothing says paid until the server does.
                        local.update { it.copy(step = PayStep.Sending, result = transfer, chatId = it.chatId ?: target) }
                        followTransfer(follow, transfer.transferId, s)
                    } else local.update { it.copy(step = PayStep.Done, result = transfer, chatId = it.chatId ?: target) }
                    // Treasurer model only: with group wallets, W4's payoutRequestId ties the payment to the request.
                    if (!s.groupWallets) s.requestId?.let { attach(it, transfer.transferId) }
                }
                is AppResult.Failure -> {
                    val code = (r.error as? AppError.Api)?.code
                    if (code != "wallet.cancelled") idempotencyKey = newIdempotencyKey().takeIf { code == "wallet.sdk_failed" || code == "wallet.payout_in_progress" || code in Refusals } ?: idempotencyKey
                    // Phase 2F: refused before anything was prepared, with the numbers to say why (GW13c, GW14b, GW20).
                    if (refusedWithNumbers(s, r.error as? AppError.Api)) return@launch
                    // G2 / GW19: say Not sent, and that nothing was taken from the named wallet; the approval stands.
                    val failure = if (code == "wallet.cancelled") null else PayFailure(s.walletName.takeIf { s.fromGroupWallet } ?: "your wallet", ErrorMessages.forError(r.error), ErrorMessages.reference(r.error))
                    local.update { it.copy(step = PayStep.Amount, failure = failure) }
                }
            }
        }
    }

    /**
     * Every payment out of a group wallet shows in the group's chat (Pasaka, 2026-11-06), like contributions coming in.
     * Someone outside the group also gets it in the chat with them (the chat this started in, or a direct one); a
     * member sees it in the group, and the owner paying themselves needs no second card. Returns the group's chat.
     */
    private suspend fun sendPayoutCards(s: PayUiState, payee: Person, toAddress: String?, card: PaymentCard): String? {
        val group = s.payingFrom.chatId
        group?.let { sendCard(it, card) }
        val person = payee.userId.takeIf { it.isNotEmpty() && toAddress == null && !s.toSelf && it !in s.memberNames }
        val theirs = chatId?.takeIf { it != group } ?: person?.let { (openDirectChat(it) as? AppResult.Success)?.value?.chatId }
        // Their own chat says where it came from, not "to Mum" back to Mum: "From Diani vacation · Bus fare".
        theirs?.takeIf { it != group }?.let { sendCard(it, card.copy(note = listOfNotNull("From ${s.walletName}", cardNoteTyped(card.note).takeIf { n -> n.isNotEmpty() }).joinToString(" · "))) }
        return group ?: theirs
    }

    /** GW9c: from a group wallet, ask the admins (CH4) in that group's chat; the signer pays it once approved. */
    private fun requestPayout(s: PayUiState, payee: Person, minor: String) {
        val group = s.payingFrom.chatId ?: return
        val ask = wallets?.createRequest ?: return
        viewModelScope.launch {
            val r = ask(group, payee.userId.takeIf { it.isNotEmpty() }, address?.takeIf { payee.userId.isEmpty() }, payee.displayName, minor, s.note.trim().takeIf { it.isNotEmpty() }, idempotencyKey)
            when (r) {
                is AppResult.Success -> { idempotencyKey = newIdempotencyKey(); local.update { it.copy(step = PayStep.Done, requested = r.value) } }
                is AppResult.Failure -> local.update { it.copy(step = PayStep.Amount, failure = PayFailure(s.walletName, ErrorMessages.forError(r.error), ErrorMessages.reference(r.error))) }
            }
        }
    }

    fun onFailureShown() = local.update { it.copy(failure = null) }

    /** Confirmed: Done says it. Failed or expired: GW19 (nothing was taken). Leaving keeps it going; the chat card updates. */
    private fun followTransfer(follow: com.circaso.feature.wallet.domain.ObserveTransferUseCase, transferId: String, s: PayUiState) {
        viewModelScope.launch {
            follow(transferId).collect { t ->
                if (t == null) return@collect
                when (t.state) {
                    com.circaso.feature.wallet.domain.TransferState.Confirmed -> { local.update { it.copy(step = PayStep.Done, result = t) }; return@collect }
                    com.circaso.feature.wallet.domain.TransferState.Failed, com.circaso.feature.wallet.domain.TransferState.Expired -> {
                        idempotencyKey = newIdempotencyKey()
                        local.update { it.copy(step = PayStep.Amount, result = null, failure = PayFailure(s.walletName.takeIf { s.fromGroupWallet } ?: "your wallet", ErrorMessages.forError(AppError.Api("wallet.sdk_failed", 400, null, null, emptyMap())), null)) }
                    }
                    else -> local.update { it.copy(result = t) }
                }
            }
        }
    }

    /**
     * CH6: tell the chama which transfer paid the request. The repository writes the link down before sending it and
     * keeps trying after this screen is gone; here a few quick tries decide what the Done screen can honestly say.
     */
    private fun attach(requestId: String, transferId: String) {
        val chat = chatId ?: return
        pendingAttach = requestId to transferId
        local.update { it.copy(attaching = true, attachPending = false) }
        viewModelScope.launch {
            var failure: AppError? = null
            for (attempt in 0 until AttachAttempts) {
                val r = attachPayment(chat, requestId, transferId)
                val code = ((r as? AppResult.Failure)?.error as? AppError.Api)?.code
                when {
                    r is AppResult.Success || (attempt > 0 && code in AlreadyLinkedCodes) -> {
                        pendingAttach = null
                        local.update { it.copy(attaching = false, attachPending = false) }
                        return@launch
                    }
                    // The server says this transfer isn't the request's payment: retrying can't fix that, say so.
                    code == "chama.transfer_mismatch" -> { failure = r.let { (it as AppResult.Failure).error }; break }
                    else -> failure = (r as AppResult.Failure).error
                }
                if (attempt < AttachAttempts - 1) delay(AttachBackoffMillis * (attempt + 1))
            }
            local.update { it.copy(attaching = false, attachPending = true, error = failure?.let { f -> ErrorMessages.forError(f) }) }
        }
    }

    fun onAttachAgain() { pendingAttach?.let { (r, t) -> attach(r, t) } }

    fun onErrorShown() = local.update { it.copy(error = null) }

    /**
     * The chat's `#500` card (design C3) reuses this view model for each payment: after one is sent (or the chip is
     * cleared), it starts again empty, with a fresh idempotency key, and the payee check kept.
     */
    fun onQuickReset() {
        idempotencyKey = newIdempotencyKey()
        // Personal again (GW9b's default) unless the chat fixes the wallet (a payout from the group's).
        if (fromId.value != intent.fromWalletId) fromId.value = intent.fromWalletId
        local.update {
            it.copy(
                // A payout's amount is the approved one (CH3), so it comes back with it; a `#` card starts empty.
                step = PayStep.Amount, amountText = if (intent.payout) intent.amount?.filter { c -> c.isDigit() || c == '.' }.orEmpty() else "", note = "",
                result = null, error = null, assetTag = null, failure = null, choosing = false, requested = null,
                inFiat = !intent.payout, blockchain = null, typedInFiat = null, priceRefresh = PriceRefresh.Idle,
            )
        }
    }

    /**
     * The call's contribute sheet (H5): exactly this amount of [asset], as the person's money turned into the coin. Like
     * onAmount (the fee is asked again; the key stays), and the payee is checked again only when the coin changes.
     */
    fun onExactAmount(amountMinor: String, asset: String) {
        if (state.value.payout) return
        val tag = asset.lowercase().takeIf { it != "usdc" }
        val coinChanged = local.value.assetTag != tag
        staleFee()
        val decimals = state.value.wallet?.chains.orEmpty().flatMap { it.assets }.firstOrNull { it.symbol.equals(asset, true) }?.decimals ?: 6
        local.update {
            it.copy(step = PayStep.Amount, inFiat = false, typedInFiat = false, amountText = MinorUnits.toDisplay(amountMinor, decimals).replace(",", ""), assetTag = tag, failure = null, error = null)
        }
        if (coinChanged) state.value.payee?.userId?.takeIf { it.isNotEmpty() }?.let { check(it, state.value.payChain) }
    }

    /**
     * A card in the chat said Not sent (the chain refused it; nothing was taken): Try Again puts exactly that payment
     * back on the chat's money card, in its coin amount, so a rounding or a rate move can't change what is sent. It is
     * signed again with a fresh key; nothing repeats on its own.
     */
    fun onRetryExact(amountMinor: String, asset: String, note: String?) {
        idempotencyKey = newIdempotencyKey()
        // As onAmount: the fee on the card was for the last amount, so nothing can sign it until it is asked again.
        staleFee()
        val decimals = state.value.wallet?.chains.orEmpty().flatMap { it.assets }.firstOrNull { it.symbol.equals(asset, true) }?.decimals ?: 6
        local.update {
            it.copy(
                step = PayStep.Amount, inFiat = false, typedInFiat = false, amountText = MinorUnits.toDisplay(amountMinor, decimals).replace(",", ""),
                assetTag = asset.lowercase().takeIf { a -> a != "usdc" }, note = cardNoteTyped(note), failure = null, result = null, error = null,
            )
        }
        state.value.payee?.userId?.takeIf { it.isNotEmpty() }?.let { check(it, state.value.payChain) }
    }

    /**
     * W4 refused before preparing anything, with its numbers in `details.fields` (phase 2F): a higher fee than the one
     * shown (GW13c: the new fee and its quote are shown, and the next tap pays it), a limit (GW14b) or the balance with
     * the fee (GW20). The card stays, and says it; "Not sent" would be untrue, nothing was attempted. False when the
     * refusal isn't one of these, so the ordinary failure line shows.
     */
    private fun refusedWithNumbers(s: PayUiState, error: AppError.Api?): Boolean {
        val f = error?.fields ?: return false
        when (error.code) {
            "wallet.fee_changed" -> {
                val fee = f["feeMinor"] ?: return false
                local.update {
                    it.copy(
                        step = PayStep.Amount, failure = null, feeStale = false,
                        fee = PayFee.Quoted(fee, f["rateBps"]?.toIntOrNull(), f["minimum"] == "true", f["quoteId"], f["expiresAt"]),
                        feeChange = FeeChange(f["quotedFeeMinor"] ?: s.feeMinor ?: "0", fee),
                    )
                }
            }
            "wallet.limit_exceeded" -> {
                val block = when (f["limit"]) {
                    "DAILY" -> PayBlock.Daily(f["leftMinor"], f["limitMinor"], f["resetsAt"], f["moreAt"], f["maxAmountMinor"])
                    "PER_PAYMENT" -> PayBlock.PerPayment(f["maxAmountMinor"])
                    else -> return false
                }
                local.update { it.copy(step = PayStep.Amount, failure = null, block = block) }
            }
            "wallet.insufficient_funds" -> {
                val max = f["maxAmountMinor"] ?: return false
                local.update { it.copy(step = PayStep.Amount, failure = null, block = PayBlock.Funds(max)) }
            }
            else -> return false
        }
        return true
    }

    private companion object {
        const val AttachAttempts = 3
        const val AttachBackoffMillis = 1_000L

        /** W4 refusals that prepared nothing: the same key would be answered from the replay cache, so the next try is new. */
        val Refusals = setOf("wallet.fee_changed", "wallet.limit_exceeded", "wallet.insufficient_funds")
    }
}

/** GW13: the pause after the last keystroke before the fee is asked for. */
const val QuoteDelayMillis = 400L

/** Asking for a fresh coin price: [Failed] carries the request reference for support, when there is one. */
sealed interface PriceRefresh {
    data object Idle : PriceRefresh
    data object Loading : PriceRefresh
    data class Failed(val reference: String?) : PriceRefresh
}

/** How old the wallet's copy may be for its coin prices to turn money into ETH, SOL and the like (the server keeps them a minute). */
const val PriceFreshMillis = 5L * 60 * 1000

/** What the group-wallet side of the review reads, combined. */
/** "0.5%", "1%", "2%" from basis points. */
internal fun rateText(bps: Int): String = (bps / 100.0).toString().removeSuffix(".0") + "%"

/** "500", "1,000", "502.50": whole amounts as the design writes them, cents only when there are some. */
internal fun fiatDisplay(cents: String): String {
    val digits = cents.trimStart('0')
    val whole = digits.isEmpty() || digits.endsWith("00")
    return MinorUnits.toDisplay(cents, 2, minFraction = if (whole) 0 else 2)
}

/** What the person typed, from a card's note ("Added to Holiday Fund · November" → "November"). */
/** One quote's question: the payment, and which try (GW13d's Try Again asks the same again). */
internal data class QuoteKey(val request: PaymentRequest, val attempt: Int = 0)

/** What W17 says stops the payment as it is, in the card's words. */
internal fun com.circaso.feature.wallet.domain.PaymentQuote.blockOf(): PayBlock? = when (problem) {
    com.circaso.feature.wallet.domain.QuoteProblem.InsufficientFunds -> PayBlock.Funds(maxAmountMinor)
    com.circaso.feature.wallet.domain.QuoteProblem.PerPaymentLimit -> PayBlock.PerPayment(maxAmountMinor)
    com.circaso.feature.wallet.domain.QuoteProblem.DailyLimit -> PayBlock.Daily(leftMinor, dailyMaxMinor, fitsAt, moreAt, maxAmountMinor)
    null -> null
}

internal fun cardNoteTyped(note: String?): String {
    val n = note?.trim().orEmpty()
    if (!n.startsWith("Added to ") && !n.startsWith("Paid from ") && !n.startsWith("Sent from ")) return n
    return n.substringAfter(" · ", "")
}

/**
 * A direct payout's card note (2026-11-06): "Paid from Diani vacation to Pasaka", "Sent from Diani vacation to
 * 0x12…ab4", then what the owner typed. Written once, so it reads the same to every member and to the payee.
 */
internal fun payoutNote(wallet: String, payeeName: String?, address: String?, typed: String?): String {
    val head = if (address != null) "Sent from $wallet to ${shortAddress(address)}" else "Paid from $wallet to ${payeeName ?: "them"}"
    return listOfNotNull(head, typed).joinToString(" · ")
}

/**
 * GW18's card from a payout note: the head over the amount ("Paid from Diani vacation", "Sent from Diani vacation") and
 * the rest under it ("to Pasaka · Bus fare", "to 0x12…ab4"); null when the note isn't a payout. [wallet] is the group's
 * name, so a name with " to " in it still splits in the right place.
 */
internal fun payoutHeadAndRest(note: String?, wallet: String?): Pair<String, String?>? {
    if (!isGroupPayoutNote(note)) return null
    val n = note!!.trim()
    val first = n.substringBefore(" · ")
    val typed = n.substringAfter(" · ", "").takeIf { it.isNotBlank() }
    val verb = first.substringBefore(" from ") + " from "
    val head = wallet?.let { verb + it }?.takeIf { first.startsWith(it) } ?: first.substringBefore(" to ")
    val to = first.removePrefix(head).trim().takeIf { it.isNotEmpty() }
    return head to listOfNotNull(to, typed).joinToString(" · ").takeIf { it.isNotEmpty() }
}

/** A card the group wallet's owner sent out of the group's money: "Paid from …" (a payout) or "Sent from …" (an address). */
internal fun isGroupPayoutNote(note: String?): Boolean = note?.startsWith("Paid from ") == true || note?.startsWith("Sent from ") == true

private data class GroupSide(
    val on: Boolean = false,
    val choices: List<NamedWallet> = emptyList(),
    val balances: Map<String?, Wallet?> = emptyMap(),
    val money: com.circaso.feature.chama.domain.GroupMoney? = null,
    val payingFrom: NamedWallet = NamedWallet.Personal,
    val source: Wallet? = null,
    val sourceIsGroup: Boolean = false,
    val names: Map<String, String> = emptyMap(),
    val groupTitle: String? = null,
    val groupAvatar: String? = null,
)

/**
 * Where the review reads group wallets from (W16 named from the chats, each wallet's W1, the group's money, its chat)
 * and the one write it makes itself (CH4 for Request Payout). Reads come from Room; [refresh] fetches online.
 */
class GroupWalletSources(
    private val observeWallets: com.circaso.feature.wallet.domain.ObserveWalletsUseCase,
    private val refreshWallets: com.circaso.feature.wallet.domain.RefreshWalletsUseCase,
    private val observeWalletOf: com.circaso.feature.wallet.domain.ObserveWalletOfUseCase,
    private val refreshWalletOf: com.circaso.feature.wallet.domain.RefreshWalletOfUseCase,
    private val observeChats: com.circaso.feature.messaging.domain.ObserveChatsUseCase,
    private val observeChat: com.circaso.feature.messaging.domain.ObserveChatUseCase,
    private val observeMoney: com.circaso.feature.chama.domain.ObserveGroupMoneyUseCase,
    private val refreshMoney: com.circaso.feature.chama.domain.RefreshGroupMoneyUseCase,
    val createRequest: com.circaso.feature.chama.domain.CreateChamaRequestUseCase,
) {
    val named: kotlinx.coroutines.flow.Flow<List<NamedWallet>> =
        combine(observeWallets(), observeChats()) { list, chats -> nameWallets(list, chats) }
    fun walletOf(walletId: String?) = observeWalletOf(walletId)
    fun money(chatId: String) = observeMoney(chatId)
    fun chat(chatId: String) = observeChat(chatId)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun balances(choices: List<NamedWallet>): kotlinx.coroutines.flow.Flow<Map<String?, Wallet?>> =
        if (choices.isEmpty()) flowOf(emptyMap())
        else combine(choices.map { w -> observeWalletOf(w.walletId).map { w.walletId to it } }) { pairs -> pairs.toMap() }

    /** W16, the wallet paid from, and the group's money: once, when the review opens. True when W16 was read. */
    suspend fun refresh(fromWalletId: String?, groupChatId: String?): Boolean {
        val read = (refreshWallets() as? AppResult.Success)?.value
        read.orEmpty().filter { !it.personal && it.canSign }.forEach { refreshWalletOf(it.walletId) }
        fromWalletId?.let { refreshWalletOf(it) }
        groupChatId?.let { refreshMoney(it) }
        return read != null
    }
}
