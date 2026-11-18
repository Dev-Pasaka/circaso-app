package com.circaso.feature.wallet.domain

import com.circaso.core.common.AppResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Which side of Circle a wallet lives on (wallet networks, 2026-11-18). One server talks to both; each is its own
 * Circle user, so a person's Mainnet and Testnet wallets are two wallets with two PINs. Everything about money
 * carries one: `X-Wallet-Network` on the way up, `network` on the way down, and the phone's copies are kept apart.
 */
enum class WalletNetwork(val label: String) {
    MAINNET("Mainnet"), TESTNET("Testnet");

    /** As the server writes it (the header's value and every `network` field). */
    val wire: String get() = name

    companion object {
        fun of(wire: String?): WalletNetwork? = wire?.trim()?.uppercase()?.let { w -> entries.firstOrNull { it.name == w } }

        /** Chain names never overlap between networks, so a chain says which one it is on (money cards carry only the chain). */
        fun ofChain(blockchain: String): WalletNetwork = if (isTestnetChain(blockchain)) TESTNET else MAINNET

        /** The chain's network when there is a chain to read; null for an empty name. */
        fun ofChainOrNull(blockchain: String?): WalletNetwork? = blockchain?.takeIf { it.isNotBlank() }?.let(::ofChain)
    }
}

/** `BASE-SEPOLIA`, `MATIC-AMOY`, `SOL-DEVNET`, `AVAX-FUJI`, `ARC-TESTNET`: a test chain, by the contract's suffix rule. */
fun isTestnetChain(blockchain: String): Boolean {
    val up = blockchain.trim().uppercase()
    return TestnetSuffixes.any { up.endsWith(it) }
}

private val TestnetSuffixes = listOf("SEPOLIA", "AMOY", "DEVNET", "FUJI", "TESTNET")

/** A Solana chain (`SOL`, `SOL-DEVNET`): no Circaso fee there, and the person pays the network fee in SOL themselves. */
fun isSolanaChain(blockchain: String?): Boolean = blockchain?.trim()?.uppercase()?.startsWith("SOL") == true

/** One network the person may use, and where their wallet on it stands. */
data class NetworkOption(val network: WalletNetwork, val walletStatus: WalletStatus)

/** The selected network stopped being usable (an admin took it away, or it was switched off): where the app went instead. */
data class NetworkNotice(val lost: WalletNetwork, val now: WalletNetwork)

data class WalletNetworksState(
    /** The networks the person may use, as the server last listed them; one entry on a server without networks. */
    val options: List<NetworkOption> = emptyList(),
    /** The network every wallet screen shows. Before the server has answered once it is a placeholder (see [known]). */
    val selected: WalletNetwork = WalletNetwork.TESTNET,
    val default: WalletNetwork? = null,
    /** The server has said which networks this account has (now or on an earlier run), or that it has no such thing. */
    val known: Boolean = false,
    /** A server from before wallet networks (`GET /v1/wallet/networks` is 404): one network, no switch, no header. */
    val legacy: Boolean = false,
    /** The saved choice has been read from the phone. */
    val loaded: Boolean = false,
    /** Said once on the Wallet tab, then cleared. */
    val notice: NetworkNotice? = null,
) {
    /** The Mainnet | Testnet switch shows only for someone with more than one network. */
    val switchable: Boolean get() = options.size > 1

    /**
     * Test money is on screen. Only once the server has answered: a new person's placeholder must never flash the
     * word at someone who will only ever have Mainnet.
     */
    val testnet: Boolean get() = known && selected == WalletNetwork.TESTNET

    fun statusOf(network: WalletNetwork): WalletStatus? = options.firstOrNull { it.network == network }?.walletStatus
}

/**
 * The one source of truth for the wallet network: which the person may use, which is selected (kept between
 * launches, reset at sign-out), and what goes in `X-Wallet-Network`. Every wallet repository keys its copies on it and
 * every wallet screen observes it.
 */
interface WalletNetworks {
    val state: StateFlow<WalletNetworksState>

    /** The selected network, each change once. */
    val selected: Flow<WalletNetwork> get() = state.map { it.selected }.distinctUntilChanged()

    fun current(): WalletNetwork = state.value.selected

    /**
     * The network a call is for: the one pinned on this coroutine ([PinnedNetwork], a payment under way or a frame
     * for the other network), else the selected one once the saved choice is read and the server has been asked.
     */
    suspend fun effective(): WalletNetwork = pinnedNetwork() ?: current()

    /** `GET /v1/wallet/networks`, saved. A server without the route becomes [WalletNetworksState.legacy]. */
    suspend fun refresh(): AppResult<Unit> = AppResult.Success(Unit)

    /** Picks a network the person may use; false when it isn't one of theirs. Instant: the screens read the phone's copy. */
    suspend fun select(network: WalletNetwork): Boolean = false

    /** The value for `X-Wallet-Network` on a call for [effective]; null when the server takes none (legacy, or not asked yet). */
    suspend fun header(): String? = null

    /** The server refused a call's network (`wallet.network_unavailable`): fall back to the default and say so once. */
    suspend fun onRefused(sent: String?) {}

    fun noticeShown() {}

    companion object {
        /** No networks at all: tests, previews and a build without the feature. Testnet copies, no header, no switch. */
        val Single: WalletNetworks = fixed(WalletNetwork.TESTNET)

        /** One fixed network with no server behind it. */
        fun fixed(network: WalletNetwork, switchable: Boolean = false, known: Boolean = false): WalletNetworks = object : WalletNetworks {
            override val state = MutableStateFlow(
                WalletNetworksState(
                    options = if (switchable) WalletNetwork.entries.map { NetworkOption(it, WalletStatus.Active) } else listOf(NetworkOption(network, WalletStatus.Active)),
                    selected = network, default = network, known = known, legacy = !known, loaded = true,
                ),
            )
        }
    }
}

/**
 * Marks a coroutine as working for one network whatever is selected on screen: a payment being prepared and signed,
 * or a realtime frame for the network that isn't visible. The remotes send it and the repositories key their copies
 * on it, so nothing started for one network can land on another.
 */
class PinnedNetwork(val network: WalletNetwork) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PinnedNetwork>
}

suspend fun pinnedNetwork(): WalletNetwork? = currentCoroutineContext()[PinnedNetwork]?.network

/** The selection moved while a payment was between its review and its PIN: nothing is signed (never sent to the server). */
const val NetworkChangedCode = "wallet.network_changed"

/** The server's refusal of a network the person may not use (403). */
const val NetworkUnavailableCode = "wallet.network_unavailable"

/** Not enough SOL to pay Solana's own network fee (the app's name for it; see `isGasProblem`). */
const val InsufficientGasCode = "wallet.insufficient_gas"

/**
 * The server says the wallet can't pay the chain's network fee. The backend had no code for it when this was written
 * (2026-11-18), so the likely names are all read as the same thing.
 */
fun isGasProblem(code: String?): Boolean = code != null && code in GasCodes

private val GasCodes = setOf(InsufficientGasCode, "wallet.insufficient_native_balance", "wallet.gas_insufficient", "wallet.insufficient_sol")
