package com.circaso.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Real request outcomes. The OS can say "connected" on a dead Wi-Fi or a captive portal, so after
 * [threshold] network failures in a row we treat the server as unreachable until a call succeeds
 * or [reset] is called.
 */
class NetworkHealth(private val threshold: Int = 3) {
    private val failures = MutableStateFlow(0)
    private val _reachable = MutableStateFlow(true)
    val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

    fun onSuccess() {
        failures.value = 0
        _reachable.value = true
    }

    fun onNetworkFailure() {
        failures.update { it + 1 }
        if (failures.value >= threshold) _reachable.value = false
    }

    /** Optimistic restart: used when the OS reports a network change or the person taps "Try again". */
    fun reset() = onSuccess()
}

/** Facts the network layer learns that the whole app must react to. */
class NetworkEvents {
    private val _clientOutdated = MutableStateFlow(false)

    /** True once the server answered `426 request.client_outdated`. Sticky: only an update clears it. */
    val clientOutdated: StateFlow<Boolean> = _clientOutdated.asStateFlow()

    fun onClientOutdated() {
        _clientOutdated.value = true
    }
}
