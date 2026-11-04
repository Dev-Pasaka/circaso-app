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
 * Spaces core (phase 3, backend `spaces` module, references/spaces-api.md) sits behind this flag, so the app works
 * before the backend deploys. Same two halves as [GroupWalletsFlag]:
 *
 * - **Wanted**: on in debug builds, off in release builds, unless overridden on the phone (`AppPreferences.spacesOverride`;
 *   in a debug build the `-spaces=on|off|default` launch argument, Android `--es spaces off`).
 * - **Server**: `404 request.not_found` from any SP route means this server has no `spaces` module: the flag turns
 *   itself off for the run and the Spaces tab goes back to phase 1 (Moments and the honest "Spaces are coming" card).
 *   Any other answer (a 2xx, or a `spaces.*` error) proves the module is there.
 *
 * Unlike group wallets, nothing dangerous happens on a server without spaces (every route is new, so an older server
 * answers 404 rather than acting on the wrong thing), so [enabled] alone decides both the screens and the wire.
 */
class SpacesFlag(private val defaultOn: Boolean, override: Flow<Boolean?>? = null, scope: CoroutineScope? = null) {
    enum class Server { Unknown, Supported, Missing }

    private val server = MutableStateFlow(Server.Unknown)
    private val wanted = MutableStateFlow(defaultOn)
    private val _enabled = MutableStateFlow(defaultOn)

    init {
        if (override != null && scope != null) scope.launch { override.collect { wanted.value = it ?: defaultOn; _enabled.value = isOn } }
    }

    /** On for the screens (before the server has answered they show, so a server that has spaces doesn't flicker). */
    val enabled: StateFlow<Boolean> = if (scope == null) _enabled else
        combine(wanted, server) { w, s -> w && s != Server.Missing }.stateIn(scope, SharingStarted.Eagerly, defaultOn)

    val isOn: Boolean get() = wanted.value && server.value != Server.Missing

    val serverState: Server get() = server.value

    /** An SP route answered (2xx, or an error from its own `spaces.*` catalogue). */
    fun onServerAnswered() {
        if (server.value == Server.Unknown) server.value = Server.Supported
        _enabled.value = isOn
    }

    /** An SP route answered `404 request.not_found`: this server has no spaces module. */
    fun onServerMissing() {
        server.value = Server.Missing
        _enabled.value = false
    }

    companion object {
        /** Previews and tests: on, as if the server had answered. */
        fun on() = SpacesFlag(true).also { it.onServerAnswered() }

        /** Previews and tests: off (a server without spaces). */
        fun off() = SpacesFlag(false).also { it.onServerMissing() }
    }
}
