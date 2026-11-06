package com.circaso.feature.wallet.domain

/** W1: the person's wallet as the server sees it, read from the phone's copy. */
data class Wallet(
    val status: WalletStatus,
    val chains: List<WalletChain>,
    val totals: List<AssetTotal>,
    val fiat: FiatValue?,
    /** Outgoing payments are held until this time (after a support restore); null otherwise. */
    val holdUntil: String?,
    /** When this copy was read from the server (epoch ms); shown as "as of" when offline. */
    val fetchedAt: Long,
    /** Which wallet (W1 `walletId`): the person's user id for their own, a group wallet's id otherwise; null from an older server. */
    val walletId: String? = null,
    /** Whose it is; null from an older server (then it is the person's own). */
    val owner: WalletOwner? = null,
    /** Whether this person signs for it (their own wallet, or a group wallet whose PIN they set). */
    val canSign: Boolean = true,
) {
    val isGroup get() = owner?.type == WalletOwnerType.Group

    /** The stablecoin total everything is priced in. */
    val usdc: AssetTotal? get() = totals.firstOrNull { it.symbol == Usdc }
    val chainsReceiving get() = chains.filter { it.receiveEnabled && it.state == "ACTIVE" }

    companion object { const val Usdc = "USDC" }
}

enum class WalletStatus { None, SettingUp, Active }

data class WalletChain(val blockchain: String, val address: String, val state: String, val assets: List<Asset>, val receiveEnabled: Boolean) {
    val usdc: Asset? get() = assets.firstOrNull { it.symbol == Wallet.Usdc }
}

/**
 * One coin on one chain. [usdPrice] is one whole unit in US dollars from the server's price feed (CoinGecko, kept about a
 * minute): for showing it and for turning an amount typed in the person's money into it, never for a payment or a fee.
 */
data class Asset(val symbol: String, val tokenAddress: String, val decimals: Int, val amountMinor: String, val amount: String, val fiatAmount: String?, val usdPrice: String? = null) {
    /** One whole unit in US dollars: 1 for a dollar coin even when the server sent none, else the feed's; null when unpriced. */
    val dollarPrice: String? get() = if (MinorUnits.isDollar(symbol)) "1" else usdPrice?.takeIf { p -> decimalParts(p)?.let { !MinorUnits.isZero(it.first) } == true }
}
data class AssetTotal(val symbol: String, val decimals: Int, val amountMinor: String, val amount: String, val usdPrice: String? = null)

/** The fiat side of the balance: [rate] is USD → [currency], each coin at its `usdPrice`; `partial` when something held has no price. */
data class FiatValue(val currency: String, val amount: String, val rate: String, val asOf: String, val partial: Boolean)

/** W12: a chain the wallet is on, is joining, or could join. */
data class ChainOption(val blockchain: String, val assets: List<String>, val status: ChainStatus, val receiveEnabled: Boolean)
enum class ChainStatus { Enabled, Adding, Available }
data class Chains(val defaultChain: String, val chains: List<ChainOption>)

data class Amount(val asset: String, val amountMinor: String, val amount: String)

enum class TransferState { AwaitingSignature, Submitted, Confirmed, Failed, Expired }
enum class TransferDirection { Out, In }

/**
 * The server's own record says a payment made from a chat is on chain (W5, W6, `payment.received`): the chat must
 * show its card whatever happened to the end-to-end one (it may be unreadable on this phone, or this phone may be
 * new). [counterpartUserId] is the other person when known; [at] is when it happened (epoch ms). No note: the note
 * only ever travels end to end.
 */
data class ChatPaymentSeen(
    val chatId: String,
    val transferId: String,
    val outgoing: Boolean,
    val counterpartUserId: String?,
    val amountMinor: String,
    val asset: String,
    val blockchain: String,
    val at: Long,
)

/** W5: one payment, from this phone's copy. */
data class Transfer(
    val transferId: String,
    val direction: TransferDirection,
    val state: TransferState,
    val blockchain: String,
    val from: String,
    val toUserId: String?,
    val toAddress: String,
    val amount: Amount,
    val fee: Amount?,
    val chatId: String?,
    val txHash: String?,
    val failureReason: String?,
    /** The other person's name when known (a contact or chat member), else null. */
    val counterpartName: String?,
    /** The note that travelled with the payment in the chat, if any (never sent to the wallet API). */
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** The other person's @handle (without the @) and photo when the payment was between two Circaso people. */
    val counterpartHandle: String? = null,
    val counterpartAvatar: String? = null,
    /** The group wallet this row belongs to; null for the person's own. */
    val walletId: String? = null,
    /** The group whose wallet it left (a payout) or paid into (a contribution), from W5. */
    val fromGroupId: String? = null,
    val toGroupId: String? = null,
    val payoutRequestId: String? = null,
    /** The other side is a group's wallet (W6 `counterparty.groupId`): name it from the group. */
    val counterpartGroupId: String? = null,
) {
    val outgoing get() = direction == TransferDirection.Out
    val settled get() = state == TransferState.Confirmed
    /** The other side is a space's wallet (W6 `toSpaceId`/`fromSpaceId`, phase 3): name it from the space on the phone. */
    val counterpartSpaceId: String? get() = counterpartGroupId?.takeIf { it.startsWith("space:") }?.removePrefix("space:")
    /** A group's wallet on the other side (not a space). */
    val counterpartGroupChat: String? get() = counterpartGroupId?.takeIf { !it.startsWith("space:") }
}

/** Money that arrived from on chain (`payment.received`), kept beside transfers so Recent shows it. */
enum class RecipientStatus { CanReceive, NoWallet, NoWalletOnChain }
data class Recipient(val userId: String, val status: RecipientStatus, val blockchains: List<String>)
enum class RemindOutcome { Sent, AlreadySent, HasWallet }

/**
 * What the Circle SDK needs to run a challenge; kept in memory only, never logged. Each wallet is its own Circle user,
 * so a session belongs to one wallet: [subject] says which, so the SDK module can name it on its screens and keep
 * biometrics apart per wallet.
 */
data class SdkSession(val appId: String, val userToken: String, val encryptionKey: String, val expiresAt: String, val subject: SignSubject = SignSubject.Personal)

/** Whose money a wallet holds (W1 / W16 `owner`). A group wallet's name is the group's sealed name: the phone supplies it. */
enum class WalletOwnerType { Person, Group, Space }

data class WalletOwner(val type: WalletOwnerType, val userId: String?, val chatId: String?, val signerUserId: String, val spaceId: String? = null)

/** One wallet the person can see (W15, W16). */
data class WalletListing(val walletId: String, val owner: WalletOwner, val status: WalletStatus, val canSign: Boolean, val blockchains: List<String>) {
    val personal get() = owner.type == WalletOwnerType.Person
}


/** W15: a group's wallet as the server's rows say (cheap to poll during set-up). */
data class GroupWallet(val chatId: String, val status: WalletStatus, val wallet: WalletListing?)

/**
 * Which wallet a challenge signs for, as the person reads it on every PIN, biometric and review screen ("Approve
 * from Kilimani Chama"). [walletId] null is the personal wallet. [name] comes from the phone (a group's name is sealed).
 */
data class SignSubject(val walletId: String?, val name: String?, val kind: WalletOwnerType = WalletOwnerType.Person, val purpose: SignPurpose = SignPurpose.Approve) {
    val personal get() = walletId == null
    companion object {
        val Personal = SignSubject(null, null)
        fun group(walletId: String, name: String, purpose: SignPurpose = SignPurpose.Approve) = SignSubject(walletId, name, WalletOwnerType.Group, purpose)
        /** A space's wallet (phase 3): its own Circle user and PIN; only the owner signs. */
        fun space(walletId: String, name: String, purpose: SignPurpose = SignPurpose.Approve) = SignSubject(walletId, name, WalletOwnerType.Space, purpose)
    }
}

/** What the challenge does, so the PIN screen says the right thing ("Set a PIN for Kilimani Chama" vs "Approve from"). */
enum class SignPurpose { SetPin, Approve, AddNetworks, EnableBiometrics }

/** Whole-unit and smallest-unit arithmetic on decimal strings, so no amount ever goes through a float. */
object MinorUnits {
    /** "20.5" with 6 decimals → "20500000"; null when the text is not an amount or has too many decimals. */
    fun fromDisplay(text: String, decimals: Int): String? {
        val t = text.trim().replace(",", "")
        if (t.isEmpty() || t.count { it == '.' } > 1 || t.any { !it.isDigit() && it != '.' }) return null
        val whole = t.substringBefore('.').ifEmpty { "0" }
        val frac = t.substringAfter('.', "")
        if (frac.length > decimals) return null
        val minor = (whole + frac.padEnd(decimals, '0')).trimStart('0')
        return minor.ifEmpty { "0" }
    }

    /** "20500000" with 6 decimals → "20.50" (at least [minFraction] decimals, trailing zeros beyond that dropped). */
    fun toDisplay(minor: String, decimals: Int, minFraction: Int = 2): String {
        val digits = minor.trim().trimStart('0').ifEmpty { "0" }
        val padded = digits.padStart(decimals + 1, '0')
        val whole = padded.dropLast(decimals)
        var frac = padded.takeLast(decimals)
        while (frac.length > minFraction && frac.endsWith('0')) frac = frac.dropLast(1)
        return if (frac.isEmpty()) group(whole) else group(whole) + "." + frac
    }

    fun isZero(minor: String) = minor.trim().all { it == '0' }

    /**
     * How many decimals a coin has, for amounts that arrive without them (a chat payment card carries only minor
     * units). Dollar and euro coins 6, bitcoins 8, Solana 9, ether and Polygon 18; an unknown coin is read as 6.
     */
    fun decimalsOf(symbol: String): Int = when (symbol.uppercase()) {
        "BTC", "WBTC", "CBBTC", "CIRBTC" -> 8
        "SOL" -> 9
        "ETH", "WETH", "POL", "MATIC", "BNB", "AVAX", "DAI", "LINK" -> 18
        else -> 6
    }

    /** "20.5 USDC", "0.01 ETH": an amount in its own coin, with the coin's real decimals. */
    fun coinText(minor: String, symbol: String): String = toDisplay(minor, decimalsOf(symbol)) + " " + symbol

    /** Two whole numbers in minor units added as digit strings, exactly (an 18-decimal balance passes a Long). */
    fun add(a: String, b: String): String {
        val x = a.trim().trimStart('0').reversed()
        val y = b.trim().trimStart('0').reversed()
        val out = StringBuilder()
        var carry = 0
        for (i in 0 until maxOf(x.length, y.length)) {
            val d = (x.getOrNull(i)?.digitToInt() ?: 0) + (y.getOrNull(i)?.digitToInt() ?: 0) + carry
            out.append(d % 10)
            carry = d / 10
        }
        if (carry > 0) out.append(carry)
        return out.reverse().toString().ifEmpty { "0" }
    }

    /** US dollar coins are one dollar each (USDC = USDT = 1 USD), whatever the price feed says. */
    fun isDollar(symbol: String) = symbol.uppercase() in setOf("USDC", "USDT")

    /** a < b on minor-unit strings. */
    fun lessThan(a: String, b: String): Boolean {
        val x = a.trimStart('0').ifEmpty { "0" }; val y = b.trimStart('0').ifEmpty { "0" }
        return if (x.length != y.length) x.length < y.length else x < y
    }

    /** Thousands grouping with a thin space-free comma, as the design writes amounts. */
    fun group(whole: String): String = whole.reversed().chunked(3).joinToString(",").reversed()
}

/**
 * A coin amount short enough for one line (what is signed is always the exact amount). Dollar and euro coins round
 * half up to the cent like money; other coins round down by default, so the line never shows more of the coin than is sent:
 * dollar and euro coins to the cent ("3.87 USDC"), other coins to 6 decimals from one whole coin up ("1.234568 SOL")
 * and to 4 significant digits below it ("0.001105 ETH").
 */
fun coinShort(minor: String, decimals: Int, symbol: String, roundDown: Boolean = !(MinorUnits.isDollar(symbol) || symbol.uppercase() == "EURC")): String {
    val digits = minor.trim().trimStart('0').ifEmpty { "0" }
    val cents = MinorUnits.isDollar(symbol) || symbol.uppercase() == "EURC"
    val keep = when {
        cents -> 2
        digits.length > decimals -> 6
        else -> (decimals - digits.length) + 4 // the zeros after the point, then 4 significant digits
    }.coerceAtMost(decimals)
    val drop = decimals - keep
    val rounded = if (drop == 0) digits else {
        val padded = digits.padStart(drop + 1, '0')
        val kept = padded.dropLast(drop).trimStart('0').ifEmpty { "0" }
        if (!roundDown && padded[padded.length - drop] >= '5') MinorUnits.add(kept, "1") else kept
    }
    return MinorUnits.toDisplay(rounded, keep, minFraction = if (cents) 2 else 0) + " " + symbol
}

/** Fiat the way the design writes it: "KES 32,180", "KES 15.50"; decimals shown only when they carry something. */
fun formatFiat(currency: String, amount: String): String {
    val clean = amount.trim()
    val whole = clean.substringBefore('.')
    val frac = clean.substringAfter('.', "").take(2).trimEnd('0')
    return "$currency " + MinorUnits.group(whole) + if (frac.isEmpty()) "" else "." + frac.padEnd(2, '0')
}

/**
 * Multiplies a USDC amount (minor units, 6 decimals) by a decimal rate string → fiat amount string with 2 decimals,
 * to the nearest cent: `#500` sent as 3.875968 USDC (rounded down to the micro-USDC) reads KES 500.00 again on both
 * phones, not 499.99. Display only; what moves is always the USDC amount.
 */
fun usdcToFiat(amountMinor: String, rate: String): String? {
    val r = MinorUnits.fromDisplay(rate, 6) ?: return null
    val product = bigMultiply(amountMinor.trimStart('0').ifEmpty { "0" }, r).padStart(11, '0') // 12 decimals
    val cents = product.dropLast(10).ifEmpty { "0" }
    val rounded = if (product[product.length - 10] >= '5') MinorUnits.add(cents, "1") else cents
    return MinorUnits.toDisplay(rounded, 2, minFraction = 2).replace(",", "")
}

/** Divides a fiat amount (2 decimals) by a decimal rate → USDC minor units (6 decimals), rounded down. */
fun fiatToUsdcMinor(fiatText: String, rate: String): String? {
    val fiatCents = MinorUnits.fromDisplay(fiatText, 2) ?: return null
    val r = MinorUnits.fromDisplay(rate, 6) ?: return null
    if (MinorUnits.isZero(r)) return null
    // cents * 10^10 / rateMicro → minor units: (cents/100) / (r/10^6) * 10^6 = cents * 10^10 / r
    return bigDivide(fiatCents + "0000000000", r)
}

/**
 * A plain non-negative decimal as its digits and scale: "3512.27" → ("351227", 2), "129" → ("129", 0); null for anything
 * else (a sign, an exponent, two points). The digits have no leading zeros ("0" for zero).
 */
internal fun decimalParts(text: String): Pair<String, Int>? {
    val t = text.trim()
    if (!Regex("""\d+(\.\d+)?""").matches(t) || t.length > 60) return null
    val frac = t.substringAfter('.', "")
    return (t.substringBefore('.') + frac).trimStart('0').ifEmpty { "0" } to frac.length
}

/** [a] × [b] exactly, as a plain decimal ("3500" × "129.25" → "452375"); null when either isn't a plain decimal. */
fun multiplyDecimal(a: String, b: String): String? {
    val (x, sx) = decimalParts(a) ?: return null
    val (y, sy) = decimalParts(b) ?: return null
    return MinorUnits.toDisplay(bigMultiply(x, y), sx + sy, minFraction = 0).replace(",", "")
}

/**
 * What one whole coin is in the person's money: its US dollar price × the wallet's rate (USD → local). Null when the
 * coin has no price. Display and typing only: what moves is always the coin amount.
 */
fun unitFiatRate(asset: Asset?, usdRate: String?): String? {
    val price = asset?.dollarPrice ?: if (asset == null) "1" else return null
    return usdRate?.let { multiplyDecimal(price, it) }?.takeIf { r -> decimalParts(r)?.let { !MinorUnits.isZero(it.first) } == true }
}

/**
 * A coin amount ([amountMinor] with [decimals]) in the person's money at [unitRate] (one whole coin's local value), to
 * the nearest cent, as "452.38" (no grouping). [usdcToFiat] is this with 6 decimals and the dollar rate.
 */
fun coinToFiat(amountMinor: String, decimals: Int, unitRate: String, roundDown: Boolean = false): String? {
    val (rate, scale) = decimalParts(unitRate) ?: return null
    val minor = amountMinor.trim().trimStart('0').ifEmpty { "0" }
    if (minor.any { !it.isDigit() }) return null
    val product = bigMultiply(minor, rate)
    // product has decimals + scale fraction digits; cents keep 2 of them.
    val drop = decimals + scale - 2
    val cents = if (drop <= 0) product + "0".repeat(-drop) else {
        val padded = product.padStart(drop + 1, '0')
        val kept = padded.dropLast(drop).trimStart('0').ifEmpty { "0" }
        if (!roundDown && padded[padded.length - drop] >= '5') MinorUnits.add(kept, "1") else kept
    }
    return MinorUnits.toDisplay(cents, 2, minFraction = 2).replace(",", "")
}

/**
 * An amount typed in the person's money ([fiatText], up to 2 decimals) as minor units of a coin with [decimals] at
 * [unitRate], rounded down so it never stands for more than was typed. Null when it isn't an amount or there's no rate.
 */
fun fiatToCoinMinor(fiatText: String, unitRate: String, decimals: Int): String? {
    val cents = MinorUnits.fromDisplay(fiatText, 2) ?: return null
    val (rate, scale) = decimalParts(unitRate) ?: return null
    if (MinorUnits.isZero(rate)) return null
    // (cents / 100) / (rate / 10^scale) × 10^decimals = cents × 10^(scale + decimals) / (rate × 100)
    return bigDivide(cents + "0".repeat(scale + decimals), rate + "00")
}

internal fun bigMultiply(a: String, b: String): String {
    val x = a.map { it - '0' }.reversed(); val y = b.map { it - '0' }.reversed()
    val out = IntArray(x.size + y.size)
    for (i in x.indices) for (j in y.indices) out[i + j] += x[i] * y[j]
    for (k in out.indices) { if (out[k] >= 10) { if (k + 1 < out.size) out[k + 1] += out[k] / 10; out[k] %= 10 } }
    return out.reversed().joinToString("").trimStart('0').ifEmpty { "0" }
}

internal fun bigDivide(a: String, b: String): String {
    val divisor = b.trimStart('0').ifEmpty { "0" }
    if (divisor == "0") return "0"
    val sb = StringBuilder(); var rem = "0"
    for (ch in a.trimStart('0').ifEmpty { "0" }) {
        rem = (rem + ch).trimStart('0').ifEmpty { "0" }
        var q = 0
        while (!MinorUnits.lessThan(rem, divisor)) { rem = bigSubtract(rem, divisor); q++ }
        sb.append(q)
    }
    return sb.toString().trimStart('0').ifEmpty { "0" }
}

internal fun bigSubtract(a: String, b: String): String {
    val x = a.reversed(); val y = b.reversed().padEnd(x.length, '0')
    val out = StringBuilder(); var borrow = 0
    for (i in x.indices) {
        var d = (x[i] - '0') - (y[i] - '0') - borrow
        if (d < 0) { d += 10; borrow = 1 } else borrow = 0
        out.append(d)
    }
    return out.reverse().toString().trimStart('0').ifEmpty { "0" }
}

/** Chain names as people read them (`BASE-SEPOLIA` → "Base", with the testnet named when it is one). */
fun chainName(blockchain: String): String {
    val up = blockchain.uppercase()
    val base = when {
        up.startsWith("BASE") -> "Base"
        up.startsWith("MATIC") || up.startsWith("POLYGON") -> "Polygon"
        up.startsWith("ETH") -> "Ethereum"
        up.startsWith("SOL") -> "Solana"
        up.startsWith("ARB") -> "Arbitrum"
        up.startsWith("AVAX") -> "Avalanche"
        up.startsWith("ARC") -> "Arc"
        else -> blockchain.lowercase().replaceFirstChar { it.uppercase() }
    }
    val testnet = up.endsWith("SEPOLIA") || up.endsWith("AMOY") || up.endsWith("DEVNET") || up.endsWith("TESTNET")
    return if (testnet) "$base (testnet)" else base
}

/** `0x7a3F…9c21E4b0`: enough of an address to recognise it, never the whole thing in a row. */
fun shortAddress(address: String): String = if (address.length <= 14) address else address.take(6) + "…" + address.takeLast(6)
