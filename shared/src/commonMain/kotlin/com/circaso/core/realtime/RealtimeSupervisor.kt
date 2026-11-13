package com.circaso.core.realtime

import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.session.AuthManager
import com.circaso.core.session.AuthState
import com.circaso.core.sync.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Wait before reconnect number [attempt] (0 based): 1 s, 2 s, 4 s ... capped at 60 s, plus up to 25% jitter. */
fun backoffMillis(attempt: Int, random: Random = Random.Default): Long {
    val base = (1_000L shl attempt.coerceIn(0, 6)).coerceAtMost(60_000L)
    return base + random.nextLong(base / 4 + 1)
}

/**
 * Keeps one connection open while a full session exists and the phone is online, and reconnects with backoff.
 * Offline or signed out means no connection and no retries. A good connection resets the backoff.
 */
class RealtimeSupervisor(
    private val client: RealtimeClient,
    private val auth: AuthManager,
    private val connection: ConnectionState,
    private val scope: CoroutineScope,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            combine(auth.state, connection.status) { a, c -> a == AuthState.Active && c == ConnectionStatus.Online }
                .distinctUntilChanged()
                .collect { shouldRun ->
                    stopLoop()
                    if (shouldRun) loop = scope.launch { keepConnected() }
                }
        }
    }

    private var loop: Job? = null
    private fun stopLoop() { loop?.cancel(); loop = null }

    private suspend fun keepConnected() {
        var attempt = 0
        while (true) {
            val startedAt = com.circaso.core.sync.nowMillis()
            val end = client.run()
            val lived = com.circaso.core.sync.nowMillis() - startedAt
            // A connection that lived a while was healthy: start the backoff over.
            attempt = if (lived > HealthyMillis) 0 else attempt + 1
            // No token right now (a refresh that failed offline): try again with backoff. Signing out cancels this loop,
            // so retrying never outlives the session; returning here left the socket closed for good.
            val pause = backoffMillis(attempt)
            com.circaso.core.common.Diagnostics.note("socket: ended $end after ${lived / 1000}s, retry ${attempt} in ${pause}ms")
            wait(pause)
        }
    }

    private companion object { const val HealthyMillis = 30_000L }
}
