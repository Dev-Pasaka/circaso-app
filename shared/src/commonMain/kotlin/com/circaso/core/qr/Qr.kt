package com.circaso.core.qr

/**
 * What a Circaso QR code holds (design E4) and what a scanned one means. "On Circaso" codes are
 * `circaso://pay/@handle`, read by Circaso's own scanner (no system URL scheme is registered yet, so the phone's camera
 * app shows it as text). Wallet codes are payment URIs, the form wallets and exchanges read and write:
 *
 * - EIP-681 for EVM chains: `ethereum:<usdc>@<chainId>/transfer?address=<to>&uint256=<units>` (a token payment),
 *   `ethereum:<to>@<chainId>?value=<wei>` (the chain's own coin), and the bare `0x…` address.
 * - Solana Pay for Solana: `solana:<to>?amount=<decimal>&spl-token=<mint>`, and the bare base58 address.
 *
 * This object only reads and writes the text. Whether the person's wallet can pay what a code asks (the chain is
 * theirs, the token is USDC) is decided against their wallet by `resolveScan` in the wallet domain.
 */
object QrPayload {
    private const val PayPrefix = "circaso://pay/@"

    fun forHandle(handle: String) = PayPrefix + handle.trimStart('@')

    /** The bare address, for places that show or copy it as text. */
    fun forAddress(address: String) = address

    /**
     * The receive code for USDC at [address] on [blockchain] (2026-11-24): EIP-681's token transfer on an EVM chain
     * (`ethereum:<usdc>@<chainId>/transfer?address=<address>`), Solana Pay with the USDC mint on Solana
     * (`solana:<address>?spl-token=<mint>`), so a Circaso or any modern wallet scanning it lands on the right chain
     * and coin. Falls back to the bare address when the chain or its USDC contract isn't known.
     */
    fun forUsdcRequest(blockchain: String, address: String, usdcToken: String?): String {
        val token = usdcToken?.takeIf { it.isNotBlank() } ?: return address
        return when {
            blockchain.trim().uppercase().startsWith("SOL") ->
                if (SolanaRegex.matches(address) && SolanaRegex.matches(token)) "solana:$address?spl-token=$token" else address
            else -> {
                val id = ChainIds.idOf(blockchain) ?: return address
                if (!EvmRegex.matches(address) || !EvmRegex.matches(token)) address else "ethereum:$token@$id/transfer?address=$address"
            }
        }
    }

    /** Which kind of chain an address belongs to. */
    enum class Family { Evm, Solana }

    /** What a scanned code means for the screen that asked. */
    sealed interface Scanned {
        data class Handle(val handle: String) : Scanned

        /**
         * A wallet to pay. Everything but [address] is what the code asked for, when it asked: [blockchain] is the
         * Circle name of the EVM chain [chainId] names (null for a chain this app doesn't know, and for Solana, whose
         * codes don't name a cluster), [token] the ERC-20 contract or SPL mint, [amountUnits] an EIP-681 `uint256` in
         * the token's smallest units, [amount] a Solana Pay amount in whole units. [nativeCoin] is a code asking for
         * the chain's own coin (ETH, POL, SOL) with an amount; [amountUnreadable] an amount that isn't a number this
         * could read exactly.
         */
        data class Address(
            val address: String,
            val blockchain: String? = null,
            val family: Family = familyOf(address) ?: Family.Evm,
            val chainId: Long? = null,
            val token: String? = null,
            val amountUnits: String? = null,
            val amount: String? = null,
            val nativeCoin: Boolean = false,
            val amountUnreadable: Boolean = false,
        ) : Scanned

        /** A payment URI this app understands but can't act on ([why]); never guessed into an address. */
        data class Unsupported(val why: Why) : Scanned

        data object Unknown : Scanned
    }

    enum class Why {
        /** EIP-681 calling something other than `transfer` (an approval, a swap): not a payment. */
        NotATransfer,
        /** An ENS name (`ethereum:alice.eth`) in place of an address: there's no resolver on the phone. */
        Name,
        /** A Solana Pay transaction request (`solana:https://…`): the merchant's server builds the transaction. */
        TransactionRequest,
        /** A token transfer without the `address` it pays. */
        NoRecipient,
    }

    fun familyOf(address: String): Family? = when {
        EvmRegex.matches(address) -> Family.Evm
        SolanaRegex.matches(address) -> Family.Solana
        else -> null
    }

    /** A payment URI rather than an address or a handle: what a paste into the address field is read as. */
    fun isPaymentUri(text: String): Boolean = text.trim().substringBefore(':', "").lowercase().let { it == "ethereum" || it == "solana" }

    fun read(raw: String): Scanned {
        val text = raw.trim()
        if (text.startsWith(PayPrefix, ignoreCase = true)) return text.substring(PayPrefix.length).takeIf { HandleRegex.matches(it) }?.let { Scanned.Handle(it.lowercase()) } ?: Scanned.Unknown
        val scheme = text.substringBefore(':', "").lowercase()
        return when {
            scheme == "ethereum" -> readEthereum(text.substringAfter(':'))
            scheme == "solana" -> readSolana(text.substringAfter(':'))
            EvmRegex.matches(text) -> Scanned.Address(text, family = Family.Evm)
            SolanaRegex.matches(text) -> Scanned.Address(text, family = Family.Solana)
            text.startsWith("@") && HandleRegex.matches(text.drop(1)) -> Scanned.Handle(text.drop(1).lowercase())
            else -> Scanned.Unknown
        }
    }

    /** `[pay-]<target>[@<chainId>][/<function>][?<params>]` (EIP-681). */
    private fun readEthereum(rest: String): Scanned {
        val path = rest.substringBefore('?')
        val query = params(rest.substringAfter('?', ""))
        val head = path.substringBefore('/')
        val function = path.substringAfter('/', "").takeIf { it.isNotEmpty() }
        val target = head.substringBefore('@').removePrefix("pay-")
        val chainText = head.substringAfter('@', "")
        if (!EvmRegex.matches(target)) return if (isName(target)) Scanned.Unsupported(Why.Name) else Scanned.Unknown
        // A chain id that isn't a number is a malformed code: nothing is guessed from it.
        val chainId = if (chainText.isEmpty()) null else chainText.toLongOrNull()?.takeIf { it > 0 } ?: return Scanned.Unknown
        val blockchain = chainId?.let(ChainIds::blockchainOf)
        return when (function?.lowercase()) {
            null -> {
                // The chain's own coin to [target]: its amount is in wei, so it never becomes a USDC amount.
                val value = query["value"]
                val units = value?.let(::integerOf)
                Scanned.Address(
                    target, blockchain, Family.Evm, chainId,
                    nativeCoin = units != null && units != "0",
                    amountUnreadable = value != null && units == null,
                )
            }
            "transfer" -> {
                val to = query["address"] ?: return Scanned.Unsupported(Why.NoRecipient)
                if (!EvmRegex.matches(to)) return if (isName(to)) Scanned.Unsupported(Why.Name) else Scanned.Unknown
                val raw = query["uint256"]
                val units = raw?.let(::integerOf)
                Scanned.Address(to, blockchain, Family.Evm, chainId, token = target, amountUnits = units?.takeIf { it != "0" }, amountUnreadable = raw != null && units == null)
            }
            else -> Scanned.Unsupported(Why.NotATransfer)
        }
    }

    private fun isName(text: String) = NameRegex.matches(text)

    /** `<recipient>[?amount=<decimal>&spl-token=<mint>&…]` (Solana Pay transfer request). */
    private fun readSolana(rest: String): Scanned {
        val recipient = rest.substringBefore('?')
        if (recipient.startsWith("http", ignoreCase = true)) return Scanned.Unsupported(Why.TransactionRequest)
        if (!SolanaRegex.matches(recipient)) return Scanned.Unknown
        val query = params(rest.substringAfter('?', ""))
        val mintText = query["spl-token"]
        // A mint that isn't a Solana address is a malformed code, not "no token" (which would mean SOL).
        if (mintText != null && !SolanaRegex.matches(mintText)) return Scanned.Unknown
        val raw = query["amount"]
        val amount = raw?.let(::decimalOf)
        val positive = amount != null && amount.any { it in '1'..'9' }
        return Scanned.Address(
            recipient, family = Family.Solana, token = mintText,
            amount = amount?.takeIf { positive && mintText != null },
            nativeCoin = positive && mintText == null,
            amountUnreadable = raw != null && amount == null,
        )
    }

    /** Query parameters, percent-decoded; the first of a repeated key wins. */
    private fun params(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('=')).lowercase()
            if (key !in out) out[key] = decode(pair.substringAfter('=', ""))
        }
        return out
    }

    private fun decode(s: String): String {
        if ('%' !in s) return s
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%' && i + 2 <= s.lastIndex) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) { bytes.add(hex.toByte()); i += 3; continue }
            }
            ch.toString().encodeToByteArray().forEach { bytes.add(it) }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    /**
     * EIP-681's number (`2014000`, `2.014e6`, `2.014E6`) as an exact integer string, or null when it isn't one (a
     * fraction left after the exponent, a negative, hex, anything else). No float ever touches it.
     */
    internal fun integerOf(text: String): String? {
        val m = NumberRegex.matchEntire(text.trim()) ?: return null
        val whole = m.groupValues[1]
        val frac = m.groupValues[2]
        val expText = m.groupValues[3]
        if (expText.length > 3) return null
        val exp = expText.toIntOrNull() ?: 0
        // Only zeros may be left over after the point moves.
        if (frac.length > exp && frac.drop(exp).any { it != '0' }) return null
        val digits = whole + frac.take(exp).padEnd(exp, '0')
        return digits.trimStart('0').ifEmpty { "0" }
    }

    /** A Solana Pay amount (`1`, `0.01`, `.5`): a plain non-negative decimal, normalised; null otherwise. */
    internal fun decimalOf(text: String): String? {
        val t = text.trim()
        if (!DecimalRegex.matches(t)) return null
        val whole = t.substringBefore('.').trimStart('0').ifEmpty { "0" }
        val frac = t.substringAfter('.', "").trimEnd('0')
        return if (frac.isEmpty()) whole else "$whole.$frac"
    }

    private val HandleRegex = Regex("^[A-Za-z0-9_]{3,30}$")
    private val EvmRegex = Regex("^0x[0-9a-fA-F]{40}$")
    private val SolanaRegex = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")
    private val NameRegex = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$")
    private val NumberRegex = Regex("^([0-9]+)(?:\\.([0-9]+))?(?:[eE]\\+?([0-9]+))?$")
    private val DecimalRegex = Regex("^(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)$")
}

/**
 * EIP-155 chain ids and Circle's names for the EVM chains a Circaso wallet can be on (the backend offers Base,
 * Polygon and Ethereum, mainnet and testnet), plus Arbitrum and Avalanche so a code for them is named in words rather
 * than called unknown.
 */
object ChainIds {
    private val byId = linkedMapOf(
        8453L to "BASE", 84532L to "BASE-SEPOLIA",
        137L to "MATIC", 80002L to "MATIC-AMOY",
        1L to "ETH", 11155111L to "ETH-SEPOLIA",
        42161L to "ARB", 421614L to "ARB-SEPOLIA",
        43114L to "AVAX", 43113L to "AVAX-FUJI",
    )
    private val byName = byId.entries.associate { (id, name) -> name to id }

    fun blockchainOf(chainId: Long): String? = byId[chainId]
    fun idOf(blockchain: String): Long? = byName[blockchain.trim().uppercase()]
}

/** The platform's QR scanner: Google's code scanner on Android, VisionKit on iOS. Null when closed or unreadable. */
interface QrScanner {
    val available: Boolean
    suspend fun scan(): String?
}

object NoQrScanner : QrScanner {
    override val available = false
    override suspend fun scan(): String? = null
}
