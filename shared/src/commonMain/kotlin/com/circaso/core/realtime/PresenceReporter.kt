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
 */
class PresenceReporter(private val realtime: RealtimeClient, private val scope: CoroutineScope) {
    private var foreground = false
    private var heartbeat: Job? = null
    private var leaving: Job? = null

    init {
        // A fresh connection while in front: say so at once rather than waiting for the next beat.
        scope.launch { realtime.connected.filter { it }.collect { if (foreground) send("active") } }
    }

    fun onForeground() {
        foreground = true
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
        heartbeat?.cancel()
        leaving?.cancel()
        leaving = scope.launch {
            delay(AwayAfterMillis)
            send("away")
        }
    }

    private suspend fun send(state: String) {
        realtime.send("messaging", "presence", buildJsonObject { put("state", state) })
    }

    companion object {
        const val AwayAfterMillis = 15_000L
        const val HeartbeatMillis = 30_000L
    }
}
