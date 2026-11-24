package com.circaso.feature.wallet.domain

import com.circaso.core.qr.QrPayload
import com.circaso.core.qr.QrPayload.Family
import com.circaso.core.qr.QrPayload.Scanned

/**
 * USDC's contract on each chain the backend offers (Circle's list, the same table as the backend's
 * `CustodyChainsConfig`). The wallet's own W1 asset list is checked first; this is for chains the wallet isn't on yet
 * and for a copy of W1 from before `tokenAddress` was sent.
 */
object UsdcContracts {
    private val byChain = linkedMapOf(
        "BASE" to "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
        "MATIC" to "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359",
        "ETH" to "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
        "SOL" to "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        "BASE-SEPOLIA" to "0x036CbD53842c5426634e7929541eC2318f3dCF7e",
        "MATIC-AMOY" to "0x41E94Eb019C0762f9Bfcf9Fb1E58725BfB0e7582",
        "ETH-SEPOLIA" to "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238",
        "SOL-DEVNET" to "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU",
    )

    fun of(blockchain: String): String? = byChain[blockchain.trim().uppercase()]

    /** The chain whose USDC [token] is (EVM contracts compared without case; Solana mints exactly). */
    fun chainOf(token: String): String? = byChain.entries.firstOrNull { same(it.value, token) }?.key

    internal fun same(a: String, b: String) = if (a.startsWith("0x") || b.startsWith("0x")) a.equals(b, ignoreCase = true) else a == b
}

/** USDC's contract on [chain]: the wallet's own (W1) when it is a real address, else Circle's published one. */
fun usdcTokenOf(chain: WalletChain): String? = chain.usdc?.tokenAddress?.takeIf { QrPayload.familyOf(it) != null } ?: UsdcContracts.of(chain.blockchain)

/** [token] is USDC on [chain]: the wallet's own contract (W1) or Circle's published one. */
private fun isUsdcOn(chain: WalletChain, token: String): Boolean =
    listOfNotNull(chain.usdc?.tokenAddress?.takeIf { it.isNotBlank() }, UsdcContracts.of(chain.blockchain)).any { UsdcContracts.same(it, token) }

/** What a scanned or pasted code fills in on Send to a Wallet, or why it fills in nothing. */
sealed interface ScanFill {
    /**
     * [blockchain] is the chain to select (null: the code didn't say; keep a compatible one). [amountText] is the
     * amount as the person would type it ("12.5"). [notice] is said calmly beside what was filled in: something the
     * code asked for that wasn't used.
     */
    data class Fill(val address: String, val blockchain: String?, val amountText: String? = null, val notice: String? = null) : ScanFill

    /** Nothing is filled in; [message] says why and what to do. */
    data class Refuse(val message: String) : ScanFill
}

/**
 * What a scanned code means for a USDC payment from this wallet ([chains], on [network]). Never guesses: a code for a
 * chain the wallet isn't on, a chain of the other network, a coin other than USDC or an amount it can't read exactly
 * is said plainly. [whose] names the wallet in the words ("your wallet", "Kilimani Chama’s wallet").
 */
fun resolveScan(scanned: Scanned, chains: List<WalletChain>, network: WalletNetwork, whose: String = "your wallet"): ScanFill {
    val active = chains.filter { it.state == "ACTIVE" }
    return when (scanned) {
        is Scanned.Handle -> ScanFill.Refuse("That's a Circaso code for @${scanned.handle}. Pay them from Send instead, no address needed.")
        is Scanned.Unsupported -> ScanFill.Refuse(
            when (scanned.why) {
                QrPayload.Why.NotATransfer -> "That code asks your wallet to do something other than pay. Circaso only sends payments, so nothing was filled in."
                QrPayload.Why.Name -> "That code has a wallet name, not an address. Ask for the 0x… address instead."
                QrPayload.Why.TransactionRequest -> "That's a Solana Pay checkout link. Circaso can't pay those yet: ask for the wallet address instead."
                QrPayload.Why.NoRecipient -> "That code doesn't say which wallet to pay."
            },
        )
        Scanned.Unknown -> ScanFill.Refuse("That code isn't a wallet address.")
        is Scanned.Address -> resolveAddress(scanned, active, network, whose)
    }
}

private fun resolveAddress(s: Scanned.Address, active: List<WalletChain>, network: WalletNetwork, whose: String): ScanFill {
    val token = s.token
    // Which chain the code is for: the one it names, else the one its USDC contract or mint belongs to.
    val named: String? = when {
        s.chainId != null -> s.blockchain ?: return ScanFill.Refuse("That code is for a network Circaso doesn't support (chain ${s.chainId}). Nothing was filled in.")
        token != null -> active.firstOrNull { c -> isUsdcOn(c, token) }?.blockchain
            ?: UsdcContracts.chainOf(token)
            ?: return notUsdc()
        else -> null
    }
    if (named != null) {
        if (UsdcContracts.of(named) == null && active.none { it.blockchain.equals(named, ignoreCase = true) }) return ScanFill.Refuse("That code is for ${chainBaseName(named)}, which Circaso doesn't support yet. Nothing was filled in.")
        // The other network's chain (a testnet code on Mainnet, or the reverse): never paid from this wallet.
        val its = WalletNetwork.ofChain(named)
        if (its != network) return ScanFill.Refuse("That code is for USDC on ${chainBaseName(named)} ${its.label}. This is $whose on ${network.label}: switch to ${its.label} on the Wallet tab to pay it.")
        val chain = active.firstOrNull { it.blockchain.equals(named, ignoreCase = true) }
            ?: return ScanFill.Refuse("That code is for USDC on ${chainName(named)}, which $whose isn't on. Add ${chainBaseName(named)} under Wallet → Networks first.")
        if (token != null && !isUsdcOn(chain, token)) return notUsdc()
        val decimals = chain.usdc?.decimals ?: UsdcDecimals
        return fill(s, chain.blockchain, decimals)
    }
    // No chain named: an address of one family. Solana's codes never name a cluster, but each network has one.
    if (s.family == Family.Solana) {
        val sol = active.firstOrNull { isSolanaChain(it.blockchain) }
            ?: return ScanFill.Refuse("That's a Solana address, and $whose isn't on Solana. Add Solana under Wallet → Networks first.")
        return fill(s, sol.blockchain, sol.usdc?.decimals ?: UsdcDecimals)
    }
    if (active.isNotEmpty() && active.none { !isSolanaChain(it.blockchain) }) {
        return ScanFill.Refuse("That's an address for Base, Polygon or Ethereum, and $whose is only on Solana. Add a network under Wallet → Networks first.")
    }
    return fill(s, null, UsdcDecimals)
}

private fun notUsdc() = ScanFill.Refuse("That code asks for a coin other than USDC. Circaso sends USDC only, so nothing was filled in.")

private fun fill(s: Scanned.Address, blockchain: String?, decimals: Int): ScanFill.Fill {
    val coin = when {
        isSolanaChain(blockchain) || s.family == Family.Solana -> "SOL"
        blockchain?.uppercase()?.startsWith("MATIC") == true -> "POL"
        blockchain?.uppercase()?.startsWith("AVAX") == true -> "AVAX"
        else -> "ETH"
    }
    val amount = s.amountUnits?.let { unitsToText(it, decimals) } ?: s.amount?.takeIf { (it.substringAfter('.', "").length) <= decimals }
    val tooPrecise = s.amount != null && amount == null
    val notice = when {
        s.nativeCoin -> "That code asks for $coin, not USDC. The address is filled in: choose how much USDC to send."
        s.amountUnreadable || tooPrecise -> "The amount in that code couldn't be read exactly, so it wasn't filled in."
        else -> null
    }
    return ScanFill.Fill(s.address, blockchain, amount, notice)
}

/** "12500000" with 6 decimals → "12.5": as the person would type it, no grouping, no trailing zeros. */
internal fun unitsToText(units: String, decimals: Int): String {
    val digits = units.trimStart('0').ifEmpty { "0" }
    if (decimals == 0) return digits
    val padded = digits.padStart(decimals + 1, '0')
    val whole = padded.dropLast(decimals)
    val frac = padded.takeLast(decimals).trimEnd('0')
    return if (frac.isEmpty()) whole else "$whole.$frac"
}

private const val UsdcDecimals = 6
