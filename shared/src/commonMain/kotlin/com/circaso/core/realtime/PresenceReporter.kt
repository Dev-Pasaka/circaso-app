package com.circaso.core.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tells the server whether this person is in the app, for the "Online" / "last seen" others see. Active while the app
 * is in front (renewed every [HeartbeatMillis], and again on every reconnect); away [AwayAfterMillis] after it leaves
 * the screen (the user, 2026-10-31: 15 seconds), so a quick trip to another app and back doesn't flicker. If the app is
 * killed or suspended before it can say so, the server lets "active" lapse on its own.
 *
 * Separately, and at once, it tells the gateway whether Circaso is in front (`system/app.state`, 2026-11-13), and again
 * on every connect: while it says `background` the server pushes messages as well as sending them on the socket, which
 * stays open in the background. Without it a message was "delivered" at once but its notification waited for the
 * socket's ping to time out. The 15-second wait is only for "last seen", never for this.
 */
class PresenceReporter(private val realtime: RealtimeClient, private val scope: CoroutineScope) {
    private var foreground = false

    /** Null until the lifecycle has said either way, so a connect before that tells the server nothing. */
    private var appState: String? = null
    private var heartbeat: Job? = null
    private var leaving: Job? = null

    init {
        // A fresh connection while in front: say so at once rather than waiting for the next beat.
        scope.launch {
            realtime.connected.filter { it }.collect {
                appState?.let { sendAppState(it) }
                if (foreground) send("active")
            }
        }
    }

    fun onForeground() {
        foreground = true
        appState = AppForeground
        scope.launch { sendAppState(AppForeground) }
        leaving?.cancel()
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (true) {
                send("active")
                delay(HeartbeatMillis)
            }
        }
    }

    fun onBackground() {
        foreground = false
        appState = AppBackground
        scope.launch { sendAppState(AppBackground) }
        heartbeat?.cancel()
        leaving?.cancel()
        leaving = scope.launch {
            delay(AwayAfterMillis)
            send("away")
        }
    }

    private suspend fun sendAppState(state: String) {
        realtime.send("system", "app.state", buildJsonObject { put("state", state) })
    }

    private suspend fun send(state: String) {
        realtime.send("messaging", "presence", buildJsonObject { put("state", state) })
    }

    companion object {
        const val AwayAfterMillis = 15_000L
        const val HeartbeatMillis = 30_000L
        const val AppForeground = "foreground"
        const val AppBackground = "background"
    }
}
