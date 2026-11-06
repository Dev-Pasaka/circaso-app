package com.circaso.feature.calls.presentation

import com.circaso.core.common.ErrorMessages
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.fiatToUsdcMinor
import com.circaso.feature.wallet.presentation.PayFee
import com.circaso.feature.wallet.presentation.PayStep
import com.circaso.feature.wallet.presentation.PayUiState
import com.circaso.feature.wallet.presentation.PayViewModel
import com.circaso.feature.wallet.presentation.PayeeProblem
import com.circaso.feature.wallet.presentation.blockedLabel
import com.circaso.feature.wallet.presentation.fiatDisplay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Design H5 on the call (Pasaka, 2026-11-06): pick the coin, pick the amount in the person's currency, and
 * "Contribute KES 1,000" goes straight to the PIN. The group (its wallet, or the treasurer in the older chama model) is
 * the only destination. The money itself goes through the chat's PayViewModel, driven the way the chat's `#1000` card
 * drives it (ThreadScreen's quickPay): the same quote, limits, signing and money card into the group chat. There is no
 * second payment path here.
 */

/** One coin the sheet can contribute in. */
internal data class ContributeCoin(
    val symbol: String,
    val decimals: Int,
    /** The most of it on one network: one payment leaves one network. */
    val spendableMinor: String,
    /** One whole coin in the person's currency; null when the coin has no price yet (its amounts are then in the coin). */
    val fiatPerCoin: Double?,
    /** All of it in the person's currency, for the order; null without a price. */
    val worth: Double?,
)

/**
 * A coin's price in US dollars. Dollar stablecoins are 1. Other coins use the server's price.
 *
 * Seam (2026-11-06): another branch adds the server's per-coin `Asset.usdPrice`, used as `usdPrice × wallet.fiat.rate`.
 * When it lands, read it here first (`asset.usdPrice?.toDoubleOrNull()`). Until then the price comes from what the server
 * already says the holding is worth (`fiatAmount / amount / rate`). A coin with neither has no price, and the sheet
 * shows its amounts in the coin instead of blocking it.
 */
internal fun usdPriceOf(asset: Asset, fiat: FiatValue?): Double? {
    if (MinorUnits.isDollar(asset.symbol)) return 1.0
    val rate = fiat?.rate?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
    val worth = asset.fiatAmount?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
    val units = asset.amount.replace(",", "").toDoubleOrNull()?.takeIf { it > 0 } ?: return null
    return worth / units / rate
}

/**
 * Every coin the wallet holds a balance of, the most valuable first (coins without a price after those with one, by
 * amount). USDC is always offered: it is the default when nothing else is held.
 */
internal fun contributeCoins(wallet: Wallet?): List<ContributeCoin> {
    val fiat = wallet?.fiat
    val rate = fiat?.rate?.toDoubleOrNull()?.takeIf { it > 0 }
    val held = wallet?.chains.orEmpty().flatMap { it.assets }.filter { !MinorUnits.isZero(it.amountMinor.ifEmpty { "0" }) }
    val coins = held.groupBy { it.symbol.uppercase() }.map { (symbol, list) ->
        val most = list.maxWithOrNull { a, b -> if (MinorUnits.lessThan(a.amountMinor, b.amountMinor)) -1 else 1 }!!
        val usd = list.firstNotNullOfOrNull { usdPriceOf(it, fiat) }
        val perCoin = if (usd != null && rate != null) usd * rate else null
        val units = list.sumOf { it.amount.replace(",", "").toDoubleOrNull() ?: 0.0 }
        ContributeCoin(symbol, most.decimals, most.amountMinor, perCoin, perCoin?.let { it * units })
    }.toMutableList()
    if (coins.none { it.symbol == Wallet.Usdc }) coins += ContributeCoin(Wallet.Usdc, 6, "0", rate, 0.0)
    val units = held.groupBy { it.symbol.uppercase() }.mapValues { (_, l) -> l.sumOf { it.amount.replace(",", "").toDoubleOrNull() ?: 0.0 } }
    return coins.sortedWith(compareByDescending<ContributeCoin> { it.worth != null }.thenByDescending { it.worth ?: units[it.symbol] ?: 0.0 })
}

/** The coin they hold most of (by value), else USDC. */
internal fun defaultCoin(coins: List<ContributeCoin>): String =
    coins.firstOrNull { !MinorUnits.isZero(it.spendableMinor) }?.symbol ?: Wallet.Usdc

/** What the amount is written in: the person's currency, or the coin itself (no rate, or a coin with no price). */
internal enum class ContributeUnit { Fiat, Coin }

/** What the sheet has chosen: the coin, and the amount as written in [unit]. [custom] is "Other". */
internal data class ContributeChoice(
    val coin: String,
    val amount: String,
    val unit: ContributeUnit,
    val custom: Boolean = false,
    /** The person chose the coin; otherwise the default follows the wallet as it loads. */
    val picked: Boolean = false,
)

internal fun unitFor(coin: ContributeCoin?, fiat: FiatValue?): ContributeUnit =
    if (fiat != null && coin?.fiatPerCoin != null) ContributeUnit.Fiat else ContributeUnit.Coin

/** "645" → "500", "1290" → "1000", "3225" → "2500": down to 1, 2, 2.5 or 5 of its power of ten. */
internal fun niceAmount(x: Double): String? {
    if (x <= 0 || x.isNaN() || x.isInfinite()) return null
    var p = 1.0
    while (p * 10 <= x) p *= 10
    while (p > x) p /= 10
    val m = x / p
    val step = when { m >= 5 -> 5.0; m >= 2.5 -> 2.5; m >= 2 -> 2.0; else -> 1.0 }
    return plainNumber(step * p)
}

/** A double as plain digits ("2500", "0.025"), never in exponent form; at most 8 decimals. */
private fun plainNumber(v: Double): String {
    val scaled = kotlin.math.round(v * 1e8).toLong()
    val whole = scaled / 100_000_000
    val frac = (scaled % 100_000_000).toString().padStart(8, '0').trimEnd('0')
    return if (frac.isEmpty()) whole.toString() else "$whole.$frac"
}

/**
 * The three presets, as written in [unit]. In the person's currency: about 5, 10 and 25 dollars, rounded to amounts
 * people say (KES 500 / 1,000 / 2,500; USD 5 / 10 / 25). A dollar coin without a rate: 5 / 10 / 25 of it. A coin with
 * no price: a tenth, a quarter and half of what they hold, to two figures.
 */
internal fun presetsFor(coin: ContributeCoin?, unit: ContributeUnit, fiat: FiatValue?): List<String> {
    val rate = fiat?.rate?.toDoubleOrNull()?.takeIf { it > 0 }
    if (unit == ContributeUnit.Fiat && rate != null) return listOf(5.0, 10.0, 25.0).mapNotNull { niceAmount(it * rate) }.distinct()
    if (coin == null || MinorUnits.isDollar(coin.symbol)) return listOf("5", "10", "25")
    val held = MinorUnits.toDisplay(coin.spendableMinor, coin.decimals, minFraction = 0).replace(",", "").toDoubleOrNull() ?: 0.0
    return listOf(0.1, 0.25, 0.5).mapNotNull { f -> twoFigures(held * f) }.distinct()
}

/** Rounded down to two significant figures, as plain digits; null at zero. */
private fun twoFigures(x: Double): String? {
    if (x <= 0) return null
    var p = 1.0
    while (p * 10 <= x) p *= 10
    while (p > x) p /= 10
    val unit = p / 10
    return plainNumber(kotlin.math.floor(x / unit + 1e-9) * unit).takeIf { it != "0" }
}

/**
 * The coin's minor units for [choice]: typed in the coin, exactly; typed in the person's currency, that amount at the
 * coin's price, rounded down to five significant figures (so it never sends more than the amount it says).
 */
internal fun coinMinorFor(choice: ContributeChoice, coin: ContributeCoin?, fiat: FiatValue?): String? {
    val decimals = coin?.decimals ?: 6
    if (choice.unit == ContributeUnit.Coin) return MinorUnits.fromDisplay(choice.amount, decimals)?.takeIf { !MinorUnits.isZero(it) }
    val rate = fiat?.rate ?: return null
    // Dollar coins at the wallet's rate, exactly, without a float (USDC, USDT: 6 decimals).
    if (coin == null || MinorUnits.isDollar(coin.symbol)) return fiatToUsdcMinor(choice.amount, rate)?.takeIf { !MinorUnits.isZero(it) }
    val perCoin = coin.fiatPerCoin ?: return null
    val amount = choice.amount.replace(",", "").toDoubleOrNull()?.takeIf { it > 0 } ?: return null
    val units = amount / perCoin
    val shown = minOf(decimals, 8)
    var minor = kotlin.math.floor(units * pow10(shown)).toLong()
    // Five significant figures: "0.0023148 ETH", not "0.00231481".
    val digits = minor.toString().length
    if (digits > 5) { val cut = pow10(digits - 5).toLong(); minor = minor / cut * cut }
    if (minor <= 0) return null
    return minor.toString() + "0".repeat(decimals - shown)
}

private fun pow10(n: Int): Double { var v = 1.0; repeat(n) { v *= 10 }; return v }

/** How the choice is put on PayViewModel. */
internal sealed interface PayOrder {
    /** USDC typed in the person's currency: as the chat's `#1000` (KES 1,000 exactly, never 999.99). */
    data class TypedFiat(val text: String) : PayOrder
    /** Any other coin, or USDC in coin units: that exact coin amount. */
    data class Exact(val amountMinor: String, val asset: String) : PayOrder
    /** No amount yet (Other, before anything is typed). */
    data object Nothing : PayOrder
}

internal fun orderFor(choice: ContributeChoice, coin: ContributeCoin?, fiat: FiatValue?): PayOrder {
    if (choice.amount.isBlank()) return PayOrder.Nothing
    if (choice.coin == Wallet.Usdc && choice.unit == ContributeUnit.Fiat) return PayOrder.TypedFiat(choice.amount)
    return coinMinorFor(choice, coin, fiat)?.let { PayOrder.Exact(it, choice.coin) } ?: PayOrder.Nothing
}

/**
 * Contribute can be tapped: PayViewModel would sign it now (amount, payee, network, balance, limits) and the fee on the
 * sheet is this payment's, current and still held. A stale or run-out quote is never signed.
 */
internal fun contributeReady(s: PayUiState, now: Long): Boolean {
    if (!s.canPay || s.feeStale) return false
    val fee = s.fee
    if (fee is PayFee.Quoted) {
        val until = fee.expiresAt?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
        if (until != null && until <= now) return false
    }
    return true
}

/** Tone of the sheet's one line under the fee. */
internal enum class ContributeTone { Quiet, Warning, Critical }

/** What the sheet shows, worked out from the choice and PayViewModel's state. */
internal data class ContributeUi(
    val coins: List<ContributeCoin>,
    val coin: ContributeCoin?,
    val choice: ContributeChoice?,
    val presets: List<String>,
    /** The hero amount: "KES" and "1,000", or "ETH" and "0.025". */
    val heroCurrency: String,
    val heroNumber: String,
    /** Under the hero: the coin amount ("7.75 USDC"), or what they hold of an unpriced coin. */
    val sub: String?,
    val button: String,
    val enabled: Boolean,
    val signing: Boolean,
    val fee: ContributeFee,
    val status: Pair<String, ContributeTone>?,
)

/** The small line under the button: the one fee, gas included (spaces-plan §7b). */
internal sealed interface ContributeFee {
    data object Hidden : ContributeFee
    data object Working : ContributeFee
    data class Shown(val text: String) : ContributeFee
    /** GW13d: couldn't be worked out; [retry] when Try Again can fix it. */
    data class Failed(val text: String, val retry: Boolean) : ContributeFee
}

/** The amount as the person reads it ("KES 1,000", "0.025 ETH"), for the button and the confirmation. */
internal fun amountWords(choice: ContributeChoice?, fiat: FiatValue?): String? {
    if (choice == null || choice.amount.isBlank()) return null
    return when (choice.unit) {
        ContributeUnit.Fiat -> fiat?.currency?.let { cur -> MinorUnits.fromDisplay(choice.amount, 2)?.let { "$cur ${fiatDisplay(it)}" } }
        ContributeUnit.Coin -> "${groupedPlain(choice.amount)} ${choice.coin}"
    }
}

/** "2500" → "2,500"; "0.025" stays. */
private fun groupedPlain(text: String): String {
    val whole = text.substringBefore('.').ifEmpty { "0" }
    val frac = text.substringAfter('.', "")
    return MinorUnits.group(whole) + if (text.contains('.')) ".$frac" else ""
}

/** A coin amount in its own units, money-like for dollar coins ("7.75 USDC"), all its digits otherwise. */
internal fun coinWords(minor: String, coin: ContributeCoin): String {
    val text = MinorUnits.toDisplay(minor, coin.decimals, minFraction = if (MinorUnits.isDollar(coin.symbol)) 2 else 0)
    if (!MinorUnits.isDollar(coin.symbol)) return "$text ${coin.symbol}"
    // Dollar coins to the cent, rounded down: never shows more than leaves.
    val whole = text.substringBefore('.')
    val cents = text.substringAfter('.', "").padEnd(2, '0').take(2)
    return "$whole.$cents ${coin.symbol}"
}

/** A coin amount in the person's currency at the coin's price ("KES 5.17"); null without a price. */
internal fun fiatWords(minor: String, coin: ContributeCoin, fiat: FiatValue?): String? {
    val cur = fiat?.currency ?: return null
    val perCoin = coin.fiatPerCoin ?: return null
    val units = MinorUnits.toDisplay(minor, coin.decimals, minFraction = 0).replace(",", "").toDoubleOrNull() ?: return null
    val cents = kotlin.math.round(units * perCoin * 100).toLong()
    return "$cur ${fiatDisplay(cents.toString())}"
}

internal fun contributeUi(choice: ContributeChoice?, s: PayUiState, now: Long, treasurerName: String? = null): ContributeUi {
    val fiat = s.wallet?.fiat
    val coins = contributeCoins(s.wallet)
    val coin = coins.firstOrNull { it.symbol == choice?.coin } ?: coins.firstOrNull { it.symbol == Wallet.Usdc }
    val unit = choice?.unit ?: unitFor(coin, fiat)
    val presets = presetsFor(coin, unit, fiat)
    val words = amountWords(choice, fiat)
    val minor = choice?.let { coinMinorFor(it, coin, fiat) }
    val heroCurrency = if (unit == ContributeUnit.Fiat) fiat?.currency.orEmpty() else choice?.coin ?: Wallet.Usdc
    val heroNumber = when {
        choice == null || choice.amount.isBlank() -> "0"
        unit == ContributeUnit.Fiat -> MinorUnits.fromDisplay(choice.amount, 2)?.let { fiatDisplay(it) } ?: choice.amount
        else -> groupedPlain(choice.amount)
    }
    val sub = when {
        coin == null -> null
        unit == ContributeUnit.Fiat -> minor?.let { coinWords(it, coin) }
        // In the coin: what it is in their money when known, else what they hold of it.
        else -> minor?.let { fiatWords(it, coin, fiat) }?.let { "about $it" } ?: "You have ${coinWords(coin.spendableMinor, coin)}"
    }
    val signing = s.step == PayStep.Signing
    val button = when {
        signing -> "Waiting for your PIN…"
        s.failure != null -> "Try Again"
        else -> blockedLabel(s)?.replaceFirst("Pay ", "Contribute ") ?: (words?.let { "Contribute $it" } ?: "Contribute")
    }
    val f = s.fee
    val fee = when {
        minor == null || f is PayFee.Offline -> ContributeFee.Hidden
        f is PayFee.Quoting -> ContributeFee.Working
        f is PayFee.Failed -> ContributeFee.Failed(
            listOfNotNull(if (f.retryable) "We couldn’t work out the fee." else (f.message ?: "We couldn’t work out the fee."), f.reference?.let { "Ref $it" }).joinToString(" "),
            f.retryable,
        )
        s.feeStale -> ContributeFee.Working
        f is PayFee.Quoted && !MinorUnits.isZero(f.amountMinor) -> {
            val money = coin?.let { c -> if (c.symbol == Wallet.Usdc) s.moneyText(f.amountMinor) else fiatWords(f.amountMinor, c, fiat) ?: coinWords(f.amountMinor, c) }
                ?: s.moneyText(f.amountMinor)
            ContributeFee.Shown("Fee $money, network cost included")
        }
        else -> ContributeFee.Shown("No fee")
    }
    return ContributeUi(
        coins = coins, coin = coin, choice = choice, presets = presets, heroCurrency = heroCurrency, heroNumber = heroNumber, sub = sub,
        button = button, enabled = contributeReady(s, now) && minor != null,
        signing = signing, fee = fee, status = contributeStatus(s, coin, treasurerName),
    )
}

/** The one line under the fee, in the chat card's words: offline and the hold first, then what stops it. */
internal fun contributeStatus(s: PayUiState, coin: ContributeCoin?, treasurerName: String? = null): Pair<String, ContributeTone>? {
    val first = treasurerName?.substringBefore(' ') ?: "The treasurer"
    return when {
        s.step == PayStep.Signing -> null
        !s.online -> "You’re offline. Money sends only while you’re connected." to ContributeTone.Warning
        s.onHold -> "Sending is paused for a short while after your account was recovered. You can still receive money." to ContributeTone.Warning
        s.failure != null -> s.failure.let { f ->
            "Not sent. Nothing was taken from your wallet." + (f.message.takeIf { it != ErrorMessages.SomethingWrong && !it.contains("Nothing was") }?.let { " $it" } ?: "") +
                (f.reference?.let { " Ref $it" } ?: "")
        } to ContributeTone.Critical
        s.checking -> "Checking $first’s wallet…" to ContributeTone.Quiet
        s.problem is PayeeProblem.NoWallet -> "$first doesn’t have a Circaso wallet yet." to ContributeTone.Critical
        s.problem is PayeeProblem.NotOnChain -> "$first can’t receive on this network yet." to ContributeTone.Critical
        s.problem is PayeeProblem.Other -> s.problem.message to ContributeTone.Critical
        s.assetMissing -> "You don’t have ${s.assetTag?.uppercase()} in this wallet." to ContributeTone.Critical
        // The limit and balance-with-fee notes are drawn by FeeProblems.
        s.block != null -> null
        s.overBalance -> "You have ${coin?.let { coinWords(s.balanceMinor, it) } ?: "${s.balanceMinor} ${s.asset}"}. Choose a smaller amount or another coin." to ContributeTone.Critical
        else -> null
    }
}

/**
 * The sheet's state and what it asks of PayViewModel. Kept apart from the composable so the default coin, the
 * conversion, the quote gating and the straight-to-sign path are tested against a real PayViewModel.
 */
internal class CallContribution(private val pay: PayViewModel, private val now: () -> Long = { com.circaso.core.sync.nowMillis() }) {
    private val _choice = MutableStateFlow<ContributeChoice?>(null)
    val choice: StateFlow<ContributeChoice?> = _choice.asStateFlow()

    private val fiat get() = pay.state.value.wallet?.fiat
    private fun coins() = contributeCoins(pay.state.value.wallet)
    private fun coinOf(symbol: String) = coins().firstOrNull { it.symbol == symbol }

    /** The sheet opens: a clean payment, the default coin and the middle preset. */
    fun open() {
        pay.onQuickReset()
        applied = null
        _choice.value = null
        pickDefault()
    }

    /**
     * The wallet arrived or changed: until the person touches the coin or the amount, follow the default, including
     * when the rate arrives after the sheet opened ("USDC 10" becomes "KES 1,000"). Anything they chose stays.
     */
    fun onWallet() {
        val c = _choice.value ?: return pickDefault()
        if (c.picked || c.custom) return
        if (c.coin != defaultCoin(coins()) || c.unit != unitFor(coinOf(c.coin), fiat)) pickDefault()
    }

    private fun pickDefault() {
        val symbol = defaultCoin(coins())
        val coin = coinOf(symbol)
        val unit = unitFor(coin, fiat)
        val presets = presetsFor(coin, unit, fiat)
        set(ContributeChoice(symbol, presets.getOrElse(1) { presets.firstOrNull().orEmpty() }, unit))
    }

    /** Another coin: the same amount in their money when both are priced, else that coin's middle preset. */
    fun onCoin(symbol: String) {
        val now = _choice.value
        val coin = coinOf(symbol)
        val unit = unitFor(coin, fiat)
        val keep = now != null && now.unit == ContributeUnit.Fiat && unit == ContributeUnit.Fiat && now.amount.isNotBlank()
        val presets = presetsFor(coin, unit, fiat)
        set(
            if (keep) now!!.copy(coin = symbol, picked = true)
            else ContributeChoice(symbol, presets.getOrElse(1) { presets.firstOrNull().orEmpty() }, unit, picked = true),
        )
    }

    fun onPreset(amount: String) { _choice.value?.let { set(it.copy(amount = amount, custom = false, picked = true)) } }

    /** "Other": an amount of their own, typed in the sheet. */
    fun onOther() { _choice.value?.let { set(it.copy(amount = "", custom = true)) } }

    fun onCustom(text: String) {
        val c = _choice.value ?: return
        val clean = text.filter { it.isDigit() || it == '.' }
        if (clean.count { it == '.' } > 1) return
        val max = if (c.unit == ContributeUnit.Fiat) 2 else coinOf(c.coin)?.decimals ?: 6
        if (clean.substringAfter('.', "").length > max) return
        set(c.copy(amount = clean, custom = true))
    }

    /** GW14 / GW20's "… Instead": the most that fits, as PayViewModel offers it, shown in the unit it is written in. */
    fun onUseMax() {
        val c = _choice.value ?: return
        val s = pay.state.value
        val max = when (val b = s.block) {
            is com.circaso.feature.wallet.presentation.PayBlock.Funds -> b.maxAmountMinor
            is com.circaso.feature.wallet.presentation.PayBlock.PerPayment -> b.maxAmountMinor
            is com.circaso.feature.wallet.presentation.PayBlock.Daily -> b.maxAmountMinor
            null -> null
        }?.takeIf { !MinorUnits.isZero(it) } ?: return
        val text = com.circaso.feature.wallet.presentation.fittedAmount(s, max) ?: return
        val unit = if (s.inFiat && s.tagged == null && fiat != null) ContributeUnit.Fiat else ContributeUnit.Coin
        pay.onUseMax()
        val next = c.copy(amount = text, unit = unit, custom = true)
        _choice.value = next
        // Already on PayViewModel: only remember it.
        applied = orderFor(next, coinOf(next.coin), fiat)
    }

    /** Contribute: straight to the PIN (PayViewModel's onPay, the Circle SDK). False when it can't be signed as it is. */
    fun contribute(): Boolean {
        val s = pay.state.value
        // Try Again after a failure signs the same way: only with a current, unexpired fee.
        if (!contributeReady(s, now())) return false
        pay.onPay()
        return true
    }

    /** Closed without paying, or done: nothing left on PayViewModel to quote. */
    fun close() { pay.onQuickReset(); applied = null; _choice.value = null }

    /** What was last put on PayViewModel, so an unchanged choice doesn't start the payment (and its quote) again. */
    private var applied: PayOrder? = null

    private fun set(choice: ContributeChoice) {
        _choice.value = choice
        val coin = coinOf(choice.coin) ?: coins().firstOrNull { it.symbol == Wallet.Usdc }
        val order = orderFor(choice, coin, fiat)
        if (order == applied) return
        when (order) {
            is PayOrder.TypedFiat -> {
                // Back from a coin amount (or fresh): PayViewModel types in the person's money again, in USDC.
                if (applied !is PayOrder.TypedFiat) pay.onQuickReset()
                pay.onAmount(order.text)
            }
            // Exactly this coin amount, in the coin with its tag; the payee is checked again only when the coin changes.
            is PayOrder.Exact -> pay.onExactAmount(order.amountMinor, order.asset)
            PayOrder.Nothing -> pay.onAmount("")
        }
        applied = order
    }
}
