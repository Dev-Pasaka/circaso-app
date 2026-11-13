package com.circaso.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/**
 * The one answer to "are we online?" that the UI shows. Online only when the OS reports a
 * network AND recent requests have been reaching the server. Going offline waits [offlineDelayMs]
 * so a flapping network does not flicker the banner; coming back online is immediate.
 *
 * This only drives what the person sees. Requests are always attempted, which is also how an
 * "offline" state ends by itself.
 */
class ConnectionState(
    monitor: ConnectivityMonitor,
    private val health: NetworkHealth,
    scope: CoroutineScope,
    offlineDelayMs: Long = 1_500,
    /**
     * Asks the server whether it can be reached (any answer counts). While the OS has a network but recent requests
     * failed, nothing else would try again (the socket stops and the outbox waits while "offline"), so this probes
     * with backoff and ends the offline state the moment the server answers. Without it a blip that didn't change
     * the network left the app offline until it was restarted.
     */
    probe: (suspend () -> Boolean)? = null,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * The server answered a probe while the OS said there was no network: the OS is wrong (it can miss a switch
     * between Wi-Fi and mobile data), so believe the server until the OS reports a change.
     */
    private val reachedDespiteOs = MutableStateFlow(false)

    val status: StateFlow<ConnectionStatus> = combine(monitor.osStatus, health.reachable, reachedDespiteOs) { os, ok, override ->
        if ((os == ConnectionStatus.Online || override) && ok) ConnectionStatus.Online else ConnectionStatus.Offline
    }.transformLatest { next ->
        if (next == ConnectionStatus.Offline) delay(offlineDelayMs)
        emit(next)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, ConnectionStatus.Online)

    init {
        // Each change of what the app shows, and of what the OS says, in the diagnostic log.
        status.drop(1).onEach { com.circaso.core.common.Diagnostics.note("net: app ${it.name.lowercase()}") }.launchIn(scope)
        monitor.osStatus.drop(1).onEach { com.circaso.core.common.Diagnostics.note("net: os ${it.name.lowercase()}") }.launchIn(scope)
        // A network change is a fresh start: forget earlier failures so the next call decides.
        monitor.osStatus.drop(1).onEach { reachedDespiteOs.value = false; health.reset() }.launchIn(scope)
        // Whatever made the app think it is offline (failed requests, or an OS that missed a network switch), ask the
        // server now and then; its answer ends the offline state.
        if (probe != null) scope.launch {
            combine(monitor.osStatus, health.reachable, reachedDespiteOs) { os, ok, override -> !ok || (os != ConnectionStatus.Online && !override) }
                .distinctUntilChanged()
                .collectLatest { stuck ->
                    var attempt = 0
                    while (stuck) {
                        wait(probeDelayMillis(attempt++))
                        if (runCatching { probe() }.getOrDefault(false)) {
                            if (monitor.osStatus.value != ConnectionStatus.Online) reachedDespiteOs.value = true
                            health.onSuccess()
                            break
                        }
                    }
                }
        }
    }

    /** The person tapped "Try again": drop the failure count so the next request is tried for real. */
    fun recheck() = health.reset()
}

/** Probes while stuck offline: 5 s, 10 s, 20 s, 40 s, then every 60 s. */
internal fun probeDelayMillis(attempt: Int): Long = (5_000L shl attempt.coerceIn(0, 4)).coerceAtMost(60_000L)
