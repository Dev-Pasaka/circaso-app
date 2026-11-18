package com.circaso.feature.wallet.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.Diagnostics
import com.circaso.core.sync.nowMillis
import com.circaso.feature.wallet.data.remote.NetworksResponse
import com.circaso.feature.wallet.domain.NetworkNotice
import com.circaso.feature.wallet.domain.NetworkOption
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetworks
import com.circaso.feature.wallet.domain.WalletNetworksState
import com.circaso.feature.wallet.domain.pinnedNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the phone keeps of the networks between launches: the choice, and the server's last list. Both small text settings. */
data class SavedNetworks(val selected: String? = null, val list: String? = null)

/**
 * [WalletNetworks] over the server's list and two small settings (`text.wallet.network`, `text.wallet.networks`, both
 * wiped at sign-out with the rest of `text.wallet.*`, which resets this too because it follows the settings).
 *
 * - Before the server has answered for this account the selection is a placeholder and no header is sent: the server
 *   then picks what it would for an older app.
 * - `404 request.not_found` on the list is a server from before networks: one network, no switch, no header, and the
 *   app works exactly as it did.
 * - A choice the server no longer lists (an admin took Testnet away) gives way to the default, and the Wallet tab says
 *   so once ([WalletNetworksState.notice]).
 */
class WalletNetworksStore(
    private val load: suspend () -> AppResult<NetworksResponse>,
    saved: Flow<SavedNetworks>,
    private val save: suspend (SavedNetworks) -> Unit,
    scope: CoroutineScope,
    private val now: () -> Long = ::nowMillis,
) : WalletNetworks {
    private val raw = MutableStateFlow<SavedNetworks?>(null)
    private val notice = MutableStateFlow<NetworkNotice?>(null)
    private val _state = MutableStateFlow(WalletNetworksState())
    override val state: StateFlow<WalletNetworksState> = _state.asStateFlow()

    private val refreshing = Mutex()
    private var refreshedAt = 0L

    init {
        // The settings are the truth: a sign-out that wipes them puts this back to "not asked yet" by itself.
        scope.launch { saved.collect { apply(it) } }
    }

    private fun apply(texts: SavedNetworks) {
        raw.value = texts
        if (texts.list == null) refreshedAt = 0L
        _state.value = derive(texts).copy(notice = notice.value)
    }

    private suspend fun write(texts: SavedNetworks) {
        apply(texts)
        try { save(texts) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { /* a lost write only forgets the choice */ }
    }

    private suspend fun loaded(): SavedNetworks = raw.value ?: raw.first { it != null }!!

    override suspend fun effective(): WalletNetwork {
        pinnedNetwork()?.let { return it }
        val texts = loaded()
        if (texts.list == null) refresh()
        return _state.value.selected
    }

    override suspend fun header(): String? {
        val network = effective()
        val s = _state.value
        return if (!s.known || s.legacy) null else network.wire
    }

    override suspend fun refresh(): AppResult<Unit> = refreshing.withLock {
        val before = loaded()
        // Asked again within a few seconds (the tab's refresh, a frame, a pull): the last answer stands.
        if (before.list != null && now() - refreshedAt < FreshMillis) return@withLock AppResult.Success(Unit)
        when (val r = load()) {
            is AppResult.Success -> {
                refreshedAt = now()
                val options = r.value.networks.mapNotNull { n -> WalletNetwork.of(n.network)?.let { it to n.walletStatus } }
                if (options.isEmpty()) {
                    // Nothing usable at all (every network off for this person): kept as it was rather than guessed.
                    Diagnostics.note("wallet: networks list is empty")
                    return@withLock AppResult.Success(Unit)
                }
                val default = WalletNetwork.of(r.value.default)?.takeIf { d -> options.any { it.first == d } } ?: options.first().first
                val list = options.joinToString(",") { "${it.first.wire}:${it.second}" } + "|" + default.wire
                val chosen = WalletNetwork.of(before.selected)
                val kept = chosen?.takeIf { c -> options.any { it.first == c } }
                // A choice that is no longer theirs: the default, said once. (No choice yet is just the default.)
                if (chosen != null && kept == null && before.list != null && before.list != Legacy) notice.value = NetworkNotice(chosen, default)
                // Only a choice the person made is kept: with none, the server's default leads (Mainnet once it opens).
                write(SavedNetworks(selected = kept?.wire, list = list))
                AppResult.Success(Unit)
            }
            is AppResult.Failure -> {
                val api = r.error as? AppError.Api
                if (api?.code == "request.not_found" && api.httpStatus == 404) {
                    refreshedAt = now()
                    if (before.list != Legacy) write(SavedNetworks(selected = null, list = Legacy))
                    AppResult.Success(Unit)
                } else r
            }
        }
    }

    override suspend fun select(network: WalletNetwork): Boolean {
        val texts = loaded()
        if (_state.value.options.none { it.network == network }) return false
        if (_state.value.selected != network) write(texts.copy(selected = network.wire))
        return true
    }

    override suspend fun onRefused(sent: String?) {
        val refused = WalletNetwork.of(sent) ?: return
        refreshedAt = 0L
        val listed = refresh() is AppResult.Success && _state.value.options.any { it.network == refused }
        val s = _state.value
        // Still listed yet refused (the list and the gate disagree for a moment): move off it all the same.
        if (listed && s.selected == refused) {
            val other = s.default?.takeIf { it != refused } ?: s.options.firstOrNull { it.network != refused }?.network ?: return
            notice.value = NetworkNotice(refused, other)
            write(loaded().copy(selected = other.wire))
        }
    }

    override fun noticeShown() {
        notice.value = null
        _state.update { it.copy(notice = null) }
    }

    internal companion object {
        /** The saved list of a server that has no networks. */
        const val Legacy = "LEGACY"
        const val FreshMillis = 5_000L

        /** The state the two saved texts stand for. Pure, so a write shows at once and a restart reads the same thing. */
        fun derive(texts: SavedNetworks): WalletNetworksState {
            val list = texts.list
            val chosen = WalletNetwork.of(texts.selected)
            if (list == null) return WalletNetworksState(selected = chosen ?: WalletNetwork.TESTNET, loaded = true)
            if (list == Legacy) {
                return WalletNetworksState(
                    options = listOf(NetworkOption(WalletNetwork.TESTNET, com.circaso.feature.wallet.domain.WalletStatus.None)),
                    selected = WalletNetwork.TESTNET, default = WalletNetwork.TESTNET, known = true, legacy = true, loaded = true,
                )
            }
            val options = list.substringBefore('|').split(',').mapNotNull { part ->
                WalletNetwork.of(part.substringBefore(':'))?.let { NetworkOption(it, walletStatus(part.substringAfter(':', ""))) }
            }
            if (options.isEmpty()) return WalletNetworksState(selected = chosen ?: WalletNetwork.TESTNET, loaded = true)
            val default = WalletNetwork.of(list.substringAfter('|', ""))?.takeIf { d -> options.any { it.network == d } } ?: options.first().network
            return WalletNetworksState(
                options = options, selected = chosen?.takeIf { c -> options.any { it.network == c } } ?: default,
                default = default, known = true, legacy = false, loaded = true,
            )
        }
    }
}
