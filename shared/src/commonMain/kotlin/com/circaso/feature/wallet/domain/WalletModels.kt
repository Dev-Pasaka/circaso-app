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
) {
    /** The stablecoin total everything is priced in. */
    val usdc: AssetTotal? get() = totals.firstOrNull { it.symbol == Usdc }
    val chainsReceiving get() = chains.filter { it.receiveEnabled && it.state == "ACTIVE" }

    companion object { const val Usdc = "USDC" }
}

enum class WalletStatus { None, SettingUp, Active }

data class WalletChain(val blockchain: String, val address: String, val state: String, val assets: List<Asset>, val receiveEnabled: Boolean) {
    val usdc: Asset? get() = assets.firstOrNull { it.symbol == Wallet.Usdc }
}

data class Asset(val symbol: String, val tokenAddress: String, val decimals: Int, val amountMinor: String, val amount: String, val fiatAmount: String?)
data class AssetTotal(val symbol: String, val decimals: Int, val amountMinor: String, val amount: String)

/** The fiat side of the balance: USDC = 1 USD at [rate] (USD → [currency]); `partial` when something held has no price. */
data class FiatValue(val currency: String, val amount: String, val rate: String, val asOf: String, val partial: Boolean)

/** W12: a chain the wallet is on, is joining, or could join. */
data class ChainOption(val blockchain: String, val assets: List<String>, val status: ChainStatus, val receiveEnabled: Boolean)
enum class ChainStatus { Enabled, Adding, Available }
data class Chains(val defaultChain: String, val chains: List<ChainOption>)

data class Amount(val asset: String, val amountMinor: String, val amount: String)

enum class TransferState { AwaitingSignature, Submitted, Confirmed, Failed, Expired }
enum class TransferDirection { Out, In }

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
) {
    val outgoing get() = direction == TransferDirection.Out
    val settled get() = state == TransferState.Confirmed
}

/** Money that arrived from on chain (`payment.received`), kept beside transfers so Recent shows it. */
enum class RecipientStatus { CanReceive, NoWallet, NoWalletOnChain }
data class Recipient(val userId: String, val status: RecipientStatus, val blockchains: List<String>)
enum class RemindOutcome { Sent, AlreadySent, HasWallet }

/** What the Circle SDK needs to run a challenge; kept in memory only, never logged. */
data class SdkSession(val appId: String, val userToken: String, val encryptionKey: String, val expiresAt: String)

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

    /** Only US dollar coins have a rate to the local currency (USDC = USDT = 1 USD). */
    fun isDollar(symbol: String) = symbol.uppercase() in setOf("USDC", "USDT")

    /** a < b on minor-unit strings. */
    fun lessThan(a: String, b: String): Boolean {
        val x = a.trimStart('0').ifEmpty { "0" }; val y = b.trimStart('0').ifEmpty { "0" }
        return if (x.length != y.length) x.length < y.length else x < y
    }

    /** Thousands grouping with a thin space-free comma, as the design writes amounts. */
    fun group(whole: String): String = whole.reversed().chunked(3).joinToString(",").reversed()
}

/** Fiat the way the design writes it: "KES 32,180", "KES 15.50"; decimals shown only when they carry something. */
fun formatFiat(currency: String, amount: String): String {
    val clean = amount.trim()
    val whole = clean.substringBefore('.')
    val frac = clean.substringAfter('.', "").take(2).trimEnd('0')
    return "$currency " + MinorUnits.group(whole) + if (frac.isEmpty()) "" else "." + frac.padEnd(2, '0')
}

/** Multiplies a USDC amount (minor units, 6 decimals) by a decimal rate string → fiat amount string with 2 decimals. */
fun usdcToFiat(amountMinor: String, rate: String): String? {
    val r = MinorUnits.fromDisplay(rate, 6) ?: return null
    val product = bigMultiply(amountMinor.trimStart('0').ifEmpty { "0" }, r) // 12 decimals
    return MinorUnits.toDisplay(product.dropLast(10).ifEmpty { "0" }, 2, minFraction = 2).replace(",", "")
}

/** Divides a fiat amount (2 decimals) by a decimal rate → USDC minor units (6 decimals), rounded down. */
fun fiatToUsdcMinor(fiatText: String, rate: String): String? {
    val fiatCents = MinorUnits.fromDisplay(fiatText, 2) ?: return null
    val r = MinorUnits.fromDisplay(rate, 6) ?: return null
    if (MinorUnits.isZero(r)) return null
    // cents * 10^10 / rateMicro → minor units: (cents/100) / (r/10^6) * 10^6 = cents * 10^10 / r
    return bigDivide(fiatCents + "0000000000", r)
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
