package com.circaso.core.qr

/**
 * What a Circaso QR code holds (design E4). "On Circaso" codes are `circaso://pay/@handle`, read by Circaso's own
 * scanner (no system URL scheme is registered yet, so the phone's camera app shows it as text); wallet codes are the
 * bare address, the form every wallet and exchange scanner reads.
 */
object QrPayload {
    private const val PayPrefix = "circaso://pay/@"

    fun forHandle(handle: String) = PayPrefix + handle.trimStart('@')

    fun forAddress(address: String) = address

    /** What a scanned code means for the screen that asked. */
    sealed interface Scanned {
        data class Handle(val handle: String) : Scanned
        /** [blockchain] is the network the code names (a Circle blockchain id such as BASE-SEPOLIA), when it names one. */
        data class Address(val address: String, val blockchain: String? = null) : Scanned
        data object Unknown : Scanned
    }

    fun read(raw: String): Scanned {
        val text = raw.trim()
        if (text.startsWith(PayPrefix, ignoreCase = true)) return text.substring(PayPrefix.length).takeIf { HandleRegex.matches(it) }?.let { Scanned.Handle(it.lowercase()) } ?: Scanned.Unknown
        // Wallet URIs: EIP-681 `ethereum:0xabc@84532?...`, `solana:Abc...?amount=`; the address and, when given, its network.
        val scheme = text.substringBefore(':', "").lowercase()
        val address = text.substringAfter(':', text).substringBefore('@').substringBefore('?').substringBefore('/')
        val chainId = text.substringAfter(':', text).substringAfter('@', "").substringBefore('?').substringBefore('/').toLongOrNull()
        return when {
            EvmRegex.matches(address) -> Scanned.Address(address, chainId?.let { EvmChains[it] })
            SolanaRegex.matches(address) -> Scanned.Address(address, if (scheme == "solana") "SOL" else null)
            HandleRegex.matches(text.trimStart('@')) && text.startsWith("@") -> Scanned.Handle(text.trimStart('@').lowercase())
            else -> Scanned.Unknown
        }
    }

    /** EIP-155 chain ids to Circle's blockchain ids, for the networks the wallet can be on. */
    private val EvmChains = mapOf(
        1L to "ETH", 11155111L to "ETH-SEPOLIA", 8453L to "BASE", 84532L to "BASE-SEPOLIA", 137L to "MATIC", 80002L to "MATIC-AMOY",
        42161L to "ARB", 421614L to "ARB-SEPOLIA", 43114L to "AVAX", 43113L to "AVAX-FUJI",
    )

    private val HandleRegex = Regex("^[A-Za-z0-9_]{3,30}$")
    private val EvmRegex = Regex("^0x[0-9a-fA-F]{40}$")
    private val SolanaRegex = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")
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
