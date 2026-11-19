package com.circaso.feature.wallet.domain

import com.circaso.core.common.AppResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Group wallets and networks (Pasaka, 2026-11-19). A group's wallet lives on one network, and a few old groups have
 * one on each: a group that had a Testnet wallet can also get a Mainnet one; a new group has just one, on Mainnet.
 * So a group's money is not on "the selected network": it is on the network its wallet is on, and every screen that
 * shows or moves a group's money works for that network ([PinnedNetwork]) whatever the Wallet tab shows.
 */

/** The network a new group wallet is made on: Mainnet whenever the person may use it, else Testnet. Never asked. */
fun newGroupWalletNetwork(usable: List<WalletNetwork>, selected: WalletNetwork): WalletNetwork = when {
    WalletNetwork.MAINNET in usable -> WalletNetwork.MAINNET
    WalletNetwork.TESTNET in usable -> WalletNetwork.TESTNET
    // The server hasn't said which networks this account has (or has none of them): as before networks.
    else -> selected
}

/**
 * **The one rule for which network a group's money is on.**
 *
 * - [usable]: the networks the person may use (empty before the server has said, on which nothing is decided here);
 * - [selected]: the network the Wallet tab shows;
 * - [wallets]: the networks the group has a wallet on, with where each stands (from the phone's W16 copies).
 *
 * The selected network when the group has a wallet there; otherwise its only wallet's network, when the person may
 * use it; otherwise (no wallet anywhere) the network a new wallet is made on ([newGroupWalletNetwork]). A wallet
 * that works comes before one still being set up, so an old group whose Mainnet wallet was started and not finished
 * keeps showing its Testnet money until the Mainnet one is ready.
 */
fun groupNetwork(usable: List<WalletNetwork>, selected: WalletNetwork, wallets: Map<WalletNetwork, WalletStatus>): WalletNetwork {
    if (usable.isEmpty()) return selected
    fun withStatus(status: WalletStatus): WalletNetwork? {
        // Mainnet first, for the case no rule above settles (two wallets and a selection that is neither).
        val on = WalletNetwork.entries.filter { it in usable && wallets[it] == status }
        return selected.takeIf { it in on } ?: on.firstOrNull()
    }
    return withStatus(WalletStatus.Active) ?: withStatus(WalletStatus.SettingUp) ?: newGroupWalletNetwork(usable, selected)
}

/** What the phone knows of the group wallets on every network the person may use. */
data class GroupNetworksState(
    val usable: List<WalletNetwork> = emptyList(),
    val selected: WalletNetwork = WalletNetwork.TESTNET,
    /** The server has said which networks there are: only then is test money ever called that. */
    val known: Boolean = false,
    /** W16 as this phone last read it, per network; a network never read here is absent. */
    val listings: Map<WalletNetwork, List<WalletListing>> = emptyMap(),
) {
    /** The networks [chatId]'s group has a wallet on, with the wallet. */
    fun walletsOf(chatId: String): Map<WalletNetwork, WalletListing> = buildMap {
        listings.forEach { (network, list) ->
            list.firstOrNull { it.owner.type == WalletOwnerType.Group && it.owner.chatId == chatId && it.status != WalletStatus.None }?.let { put(network, it) }
        }
    }

    /** The rule ([groupNetwork]) for one group. */
    fun networkOf(chatId: String): WalletNetwork = groupNetwork(usable, selected, walletsOf(chatId).mapValues { it.value.status })

    /** A wallet is on one network: the one whose list holds it. Null when no list on this phone does. */
    fun networkOfWallet(walletId: String): WalletNetwork? =
        WalletNetwork.entries.firstOrNull { n -> listings[n]?.any { it.walletId == walletId } == true }

    /** Test money: [network] is Testnet and the server has said so (someone with only Mainnet never reads the word). */
    fun testnet(network: WalletNetwork): Boolean = known && network == WalletNetwork.TESTNET

    /** Both networks are this person's: only then does a wallet need its network's name beside it. */
    val both: Boolean get() = usable.size > 1

    /**
     * An old group that can also get a Mainnet wallet: it has a working Testnet wallet this person signs for (its
     * owner), no Mainnet one that works, and the person may use Mainnet. [GroupMainnet.SettingUp] when the Mainnet
     * one was started and not finished.
     */
    fun mainnetFor(chatId: String): GroupMainnet? {
        if (WalletNetwork.MAINNET !in usable || WalletNetwork.TESTNET !in usable) return null
        val wallets = walletsOf(chatId)
        if (wallets[WalletNetwork.TESTNET]?.takeIf { it.status == WalletStatus.Active && it.canSign } == null) return null
        return when (wallets[WalletNetwork.MAINNET]?.status) {
            null, WalletStatus.None -> GroupMainnet.Add
            WalletStatus.SettingUp -> GroupMainnet.SettingUp
            WalletStatus.Active -> null
        }
    }

    /** The groups this person owns that have a Testnet wallet and no working Mainnet one, in the Testnet list's order. */
    val withoutMainnet: List<WalletListing> get() =
        listings[WalletNetwork.TESTNET].orEmpty().filter { w -> w.owner.type == WalletOwnerType.Group && w.owner.chatId?.let { mainnetFor(it) } != null }
}

/** Where a group's second, Mainnet wallet stands for its owner. */
enum class GroupMainnet { Add, SettingUp }

/**
 * The phone's view of group wallets across networks (a Koin single): the rule above over [WalletNetworks] and the
 * per-network W16 copies, kept in memory so a screen can ask at once. Reads only; [refresh] is the one call, W16 for
 * each network the person may use and never for one that isn't theirs.
 */
class GroupNetworks(
    private val networks: WalletNetworks,
    listingsOn: (WalletNetwork) -> Flow<List<WalletListing>?>,
    /** W16 for the network pinned on the caller. */
    private val refreshListings: suspend () -> AppResult<*>,
    scope: CoroutineScope,
) {
    val state: StateFlow<GroupNetworksState> = combine(
        networks.state,
        combine(WalletNetwork.entries.map { n -> listingsOn(n).map { n to it } }) { pairs -> pairs.mapNotNull { (n, list) -> list?.let { n to it } }.toMap() },
    ) { all, lists ->
        // A server from before networks has one stand-in entry and takes no header: nothing to choose between.
        val usable = if (!all.known || all.legacy) emptyList() else all.options.map { it.network }
        GroupNetworksState(usable, all.selected, all.known, if (usable.isEmpty()) lists.filterKeys { it == all.selected } else lists.filterKeys { it in usable })
    }.stateIn(scope, SharingStarted.Eagerly, GroupNetworksState(selected = networks.current()))

    /** The network [chatId]'s money is on, now and as it changes. */
    fun of(chatId: String): Flow<WalletNetwork> = state.map { it.networkOf(chatId) }.distinctUntilChanged()

    fun now(chatId: String): WalletNetwork = state.value.networkOf(chatId)

    /** The network a wallet is on (a group's by its list; the person's own and an unknown one follow the selection). */
    fun ofWallet(walletId: String?): Flow<WalletNetwork> =
        if (walletId == null) networks.selected else state.map { it.networkOfWallet(walletId) ?: it.selected }.distinctUntilChanged()

    fun ofWalletNow(walletId: String?): WalletNetwork = walletId?.let { state.value.networkOfWallet(it) } ?: networks.current()

    /** Where a new group wallet is made. */
    fun newWalletNetwork(): WalletNetwork = networks.state.value.let { s ->
        newGroupWalletNetwork(if (!s.known || s.legacy) emptyList() else s.options.map { it.network }, s.selected)
    }

    /** W16 on every network the person may use, so the rule knows where each group's wallets are. */
    suspend fun refresh() {
        networks.refresh()
        val s = networks.state.value
        if (!s.known || s.legacy) { refreshListings(); return }
        s.options.forEach { option -> withContext(PinnedNetwork(option.network)) { refreshListings() } }
    }

    companion object {
        /** No networks (tests, previews): every group's money is on the selected network, as before. */
        fun single(networks: WalletNetworks, scope: CoroutineScope): GroupNetworks = GroupNetworks(networks, { flowOf(null) }, { AppResult.Success(Unit) }, scope)
    }
}
