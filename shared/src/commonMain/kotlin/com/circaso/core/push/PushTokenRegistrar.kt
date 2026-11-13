package com.circaso.core.push

import com.circaso.core.common.AppResult
import com.circaso.core.common.Diagnostics
import com.circaso.core.platform.PushTokenProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class PushTokenRequest(val pushToken: String)

/**
 * Tells the server where to reach this phone (`PUT /v1/devices/current/push-token`). It runs when a person is signed
 * in and the phone is online, and again when the platform issues a new token. A failure is simply tried again at the
 * next trigger: the call is idempotent and nothing depends on it. The same token for the same person is sent once per
 * app start. The token itself is never logged.
 */
class PushTokenRegistrar(
    /** True while a full session exists and the phone is online. */
    private val ready: Flow<Boolean>,
    private val userId: () -> String?,
    private val provider: PushTokenProvider,
    private val send: suspend (String) -> AppResult<Unit>,
    private val scope: CoroutineScope,
) {
    private var lastSent: Pair<String, String>? = null // person to token, in memory only
    private val lock = Mutex() // the start-up sync and the platform's first token can arrive together

    fun start() {
        // Signing out forgets what was sent: the server clears a device's tokens on sign-out, so the next sign-in sends
        // them again even when the token itself hasn't changed. Only going offline keeps it (no resend per reconnect).
        scope.launch {
            ready.distinctUntilChanged().collect { if (it) sync() else if (userId() == null) lock.withLock { lastSent = null } }
        }
    }

    /** The platform issued a new token. */
    fun onNewToken(token: String) {
        scope.launch { if (userId() != null) register(token) }
    }

    suspend fun sync() {
        val token = try { provider.token() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (token != null) register(token)
    }

    private suspend fun register(token: String) = lock.withLock {
        val person = userId() ?: return@withLock
        if (lastSent == person to token) return@withLock
        when (send(token)) {
            is AppResult.Success -> { lastSent = person to token; Diagnostics.note("push: token registered") }
            is AppResult.Failure -> Diagnostics.note("push: token not registered yet")
        }
    }
}
