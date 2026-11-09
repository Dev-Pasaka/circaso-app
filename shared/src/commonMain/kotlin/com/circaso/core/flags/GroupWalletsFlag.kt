package com.circaso.core.flags

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Group wallets (Spaces phase 2, backend PR #28) sit behind this flag, because a server without them is dangerous,
 * not just missing: it **ignores** `walletId` and would pay from the person's personal wallet. Two halves decide it:
 *
 * - **Wanted**: on by default in every build since the first Play release (2026-11-09: the server has had group wallets
 *   since PR #28, and release builds hid them), unless overridden on the phone
 *   (`AppPreferences.groupWalletsOverride`; in a debug build the `-group-wallets=on|off` launch argument sets it).
 * - **Server**: the first answer from a group-wallet route settles it. `404 request.not_found` from W15 or W16 means
 *   the server predates PR #28: the flag turns itself off for this run and nothing group-shaped is shown or sent.
 *
 * [enabled] decides what the screens show (before the server has answered they show it, so a server that has group
 * wallets doesn't make them flicker in). [canSendWalletId] guards the wire: `walletId`, `payoutRequestId` and
 * `to.groupId` leave the phone only once the server has proved it understands them (`KtorWalletRemote` refuses
 * otherwise), so not even a race at launch can pay from the wrong wallet.
 */
class GroupWalletsFlag(private val defaultOn: Boolean, override: Flow<Boolean?>? = null, scope: CoroutineScope? = null) {
    enum class Server { Unknown, Supported, Missing }

    private val server = MutableStateFlow(Server.Unknown)
    private val wanted = MutableStateFlow(defaultOn)

    init {
        if (override != null && scope != null) scope.launch { override.collect { wanted.value = it ?: defaultOn } }
    }

    private val _enabled = MutableStateFlow(defaultOn)

    /** On for the screens. */
    val enabled: StateFlow<Boolean> = if (scope == null) _enabled else
        combine(wanted, server) { w, s -> w && s != Server.Missing }.stateIn(scope, SharingStarted.Eagerly, defaultOn)

    val isOn: Boolean get() = wanted.value && server.value != Server.Missing

    /** Group-wallet fields may go on the wire: wanted, and the server has answered a group-wallet route. */
    val canSendWalletId: Boolean get() = wanted.value && server.value == Server.Supported

    /** A group-wallet route answered (a 2xx, or an error from its own catalogue). */
    fun onServerAnswered() {
        if (server.value == Server.Unknown) server.value = Server.Supported
        _enabled.value = isOn
    }

    /** A group-wallet route answered `404 request.not_found`: this server predates group wallets. */
    fun onServerMissing() {
        server.value = Server.Missing
        _enabled.value = false
    }

    companion object {
        /** Previews and tests: on, as if the server had answered. */
        fun on() = GroupWalletsFlag(true).also { it.onServerAnswered() }

        /** Previews and tests: off (the treasurer-model server). */
        fun off() = GroupWalletsFlag(false).also { it.onServerMissing() }
    }
}
