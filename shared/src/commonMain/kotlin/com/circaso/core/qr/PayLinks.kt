package com.circaso.core.qr

/**
 * Circaso pay links (2026-11-24), the one form Circaso's payment QR codes take:
 * `https://links.circaso.com/pay/<address>?chain=<BASE|BASE-SEPOLIA|MATIC|…>&asset=USDC[&amount=<decimal>]`.
 *
 * The recipient is the only address in it, so an exchange's scanner that pulls the first `0x…` out of a code gets the
 * right one (an EIP-681 code's first is the USDC contract, and money sent there is lost); a strict scanner refuses it
 * and the person copies the address instead. This app reads it and fills in the recipient, the network and the coin;
 * a phone's camera opens the link, which opens this app (App Links / Universal Links on the share host, `/pay/…`) or,
 * without it, the backend's page with the address to copy. Nothing but the address is in the path, and nothing about
 * the person is in the query.
 */
object PayLinks {
    private val hosts = mutableSetOf(DefaultHost, "sandbox.circaso.com")

    /** The host this phone makes codes on: the build's share host (Android manifest, iOS Info.plist). */
    var host: String = DefaultHost
        private set

    /** The build's share host: links on it are read, and codes are made on it. */
    fun useHost(value: String) {
        val h = value.trim().lowercase()
        if (h.isEmpty()) return
        hosts += h
        host = h
    }

    /**
     * The pay link for USDC to [address] on [blockchain] (Circle's name), with [amount] (a plain decimal) when asked;
     * null when the address isn't valid for that chain or the chain isn't one Circaso offers, so nothing invalid is
     * ever drawn as payable.
     */
    fun of(blockchain: String, address: String, amount: String? = null): String? {
        val chain = blockchain.trim().uppercase()
        if (chain !in Chains || !validFor(chain, address)) return null
        val a = amount?.let { QrPayload.decimalOf(it) }?.takeIf { it.any { c -> c in '1'..'9' } }
        return "https://$host/pay/$address?chain=$chain&asset=USDC" + (a?.let { "&amount=$it" } ?: "")
    }

    /** [text] is a pay link: `https://<share host>/pay/…` or the page's `circaso://pay/<address>…` (not a handle). */
    fun isPayLink(text: String): Boolean = pathOf(text.trim()) != null

    /** What a pay link asks for, read strictly; null when [text] isn't a pay link at all. */
    internal fun read(text: String): QrPayload.Scanned? {
        val rest = pathOf(text.trim()) ?: return null
        val address = rest.substringBefore('?').substringBefore('#').removeSuffix("/")
        val query = QrPayload.params(rest.substringAfter('?', "").substringBefore('#'))
        val family = QrPayload.familyOf(address) ?: return QrPayload.Scanned.Unknown
        val chain = query["chain"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (chain != null && !ChainText.matches(chain)) return QrPayload.Scanned.Unknown
        // The address must be whole, unmistyped and of the chain's kind: a 0x address on Solana is never payable.
        val valid = when {
            chain == null -> if (family == QrPayload.Family.Evm) Addresses.isValidEvm(address) else Addresses.isValidSolana(address)
            else -> validFor(chain, address)
        }
        if (!valid) return QrPayload.Scanned.Unsupported(QrPayload.Why.BadAddress)
        val raw = query["amount"]
        val amount = raw?.let(QrPayload::decimalOf)
        return QrPayload.Scanned.Address(
            address, family = family, chainName = chain, asset = query["asset"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            amount = amount?.takeIf { it.any { c -> c in '1'..'9' } }, amountUnreadable = raw != null && amount == null,
        )
    }

    private fun validFor(chain: String, address: String): Boolean =
        if (chain.startsWith("SOL")) Addresses.isValidSolana(address) else Addresses.isValidEvm(address)

    /** What follows `/pay/` in a pay link on a known host, or null. */
    private fun pathOf(text: String): String? {
        val rest = when {
            text.startsWith(SchemePrefix, ignoreCase = true) -> text.substring(SchemePrefix.length)
            text.startsWith("https://", ignoreCase = true) -> {
                val afterScheme = text.substring("https://".length)
                val host = afterScheme.substringBefore('/').substringBefore('?').lowercase()
                if (host !in hosts) return null
                val path = afterScheme.substring(host.length)
                if (!path.startsWith("/pay/")) return null
                path.substring("/pay/".length)
            }
            else -> return null
        }
        // `circaso://pay/@handle` is the "On Circaso" code, not a pay link.
        return rest.takeIf { it.isNotEmpty() && !it.startsWith("@") }
    }

    /** Circle's names for the chains Circaso offers USDC on (the backend's page accepts the same). */
    val Chains = setOf("BASE", "BASE-SEPOLIA", "MATIC", "MATIC-AMOY", "ETH", "ETH-SEPOLIA", "SOL", "SOL-DEVNET")

    const val DefaultHost = "links.circaso.com"
    private const val SchemePrefix = "circaso://pay/"
    private val ChainText = Regex("^[A-Z0-9-]{2,24}$")
}
