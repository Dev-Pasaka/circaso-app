package com.circaso.core.session

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.AccessTokenProvider
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.SessionRefresher
import com.circaso.core.network.call
import com.circaso.core.network.withoutAuth
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.circaso.core.common.Diagnostics
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Owns the session. Rules, in the order they matter for trust:
 * 1. Launch reads the secure store only. A stored session means [AuthState.Active] at once, offline or not.
 * 2. A network failure is never a sign-out. Only the server saying the session is over ends it.
 * 3. Refresh is single-flight (the refresh token rotates, so two at once would revoke each other) and the new
 *    tokens are written to the secure store before anything uses them.
 */
class AuthManager(
    private val store: SessionStore,
    private val device: DeviceIdentity,
    private val refreshCall: suspend (RefreshRequest) -> AppResult<RefreshResponse>,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val cleaner: SessionDataCleaner = SessionDataCleaner { },
) : AccessTokenProvider, SessionRefresher {
    private val _state = MutableStateFlow<AuthState>(AuthState.Restoring)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** True when the server (not the person) ended the session; the welcome screen says so. Cleared on the next sign-in. */
    private val _endedByServer = MutableStateFlow(false)
    val endedByServer: StateFlow<Boolean> = _endedByServer.asStateFlow()

    /** Onboarding progress and the masked number, for the sign-in screens. */
    val stored: StateFlow<StoredSession> get() = store.session

    private val _issue = MutableStateFlow<AuthIssue?>(null)
    val issue: StateFlow<AuthIssue?> = _issue.asStateFlow()

    private val refreshLock = Mutex()

    /** Reads the secure store. Called once at start; never touches the network. */
    suspend fun restore() {
        val s = store.load()
        _state.value = when {
            s.app != null -> AuthState.Active
            s.onboarding != null && s.onboarding.expiresAtMillis > nowMillis() -> AuthState.Onboarding
            else -> AuthState.SignedOut
        }
    }

    /** The token for the next call: the app session, or the short onboarding token while signing up. Refreshes early, never blocks on failure. */
    override suspend fun accessToken(): String? {
        val s = store.session.value
        val app = s.app
        if (app != null) {
            if (app.expiresAtMillis - nowMillis() < RefreshMarginMillis) refresh(rejectedAccessToken = null)
            return store.session.value.app?.accessToken ?: app.accessToken
        }
        return s.onboarding?.takeIf { it.expiresAtMillis > nowMillis() }?.accessToken
    }

    /** The read-only `agent` token Asha's calls carry in `X-Agent-Token`. Refreshed together with the app session. */
    suspend fun agentToken(): String? {
        accessToken() // refreshes early when needed, which also renews the agent token
        return store.session.value.agent?.accessToken
    }

    override suspend fun refreshAfterRejection(rejectedAccessToken: String?): Boolean = refresh(rejectedAccessToken)

    /**
     * True when the session now holds a token worth trying. [rejectedAccessToken] is the token the server just
     * refused; null means a routine early refresh.
     */
    private suspend fun refresh(rejectedAccessToken: String?): Boolean = refreshLock.withLock {
        val current = store.session.value.app ?: return false
        // Someone else refreshed while we waited for the lock: use their result.
        if (rejectedAccessToken != null && current.accessToken != rejectedAccessToken) return true
        if (rejectedAccessToken == null && current.expiresAtMillis - nowMillis() >= RefreshMarginMillis) return true
        val refreshToken = current.refreshToken ?: return false

        val proof = try {
            device.refreshProof(refreshToken)
        } catch (e: Exception) {
            // The device key is gone: this session can never be refreshed, the person must verify the phone again.
            signOutLocally(endedByServer = true)
            return false
        }
        val deviceId = device.deviceId()
        val result = refreshCall(RefreshRequest(refreshToken, deviceId, proof.timestamp, proof.signature))
        Diagnostics.note(when (result) {
            is AppResult.Success -> "refresh with device proof: ok"
            is AppResult.Failure -> "refresh with device proof: failed (${(result.error as? AppError.Api)?.code ?: result.error::class.simpleName})"
        })
        when (result) {
            is AppResult.Success -> {
                val received = nowMillis()
                val s = store.session.value
                store.save(
                    s.copy(
                        app = result.value.session.toStored(received),
                        agent = result.value.agentSession.toStored(received),
                    ),
                )
                _issue.value = null
                true
            }
            is AppResult.Failure -> when (val error = result.error) {
                AppError.Network -> false // offline or timed out: keep the session, try again later
                is AppError.Api -> when (error.code) {
                    in SessionEndedCodes -> { signOutLocally(endedByServer = true); false }
                    DeviceProofInvalid -> { _issue.value = AuthIssue.CheckDateAndTime; false }
                    else -> false // rate limits, 5xx and the like: not a reason to end anything
                }
                is AppError.Unexpected -> false
            }
        }
    }

    /** The signed-in person's user id (from the access token), or null. Tells whose data and queued writes these are. */
    val userId: String? get() = Jwt.subject(store.session.value.app?.accessToken)

    /** The sign-in flows call these once the server has answered. */
    suspend fun startSession(app: SessionDto, agent: SessionDto?) {
        // A different person on this phone never inherits the previous person's cache or unsent writes.
        val newUser = Jwt.subject(app.accessToken)
        val previous = store.dataOwner()
        if (newUser != null && previous != null && previous != newUser) cleaner.clearUserData()
        if (newUser != null) store.setDataOwner(newUser)
        val received = nowMillis()
        store.save(StoredSession(app = app.toStored(received), agent = agent?.toStored(received)))
        _issue.value = null
        _endedByServer.value = false
        _state.value = AuthState.Active
    }

    suspend fun startOnboarding(onboarding: SessionDto, step: String, maskedPhone: String?) {
        store.save(StoredSession(onboarding = onboarding.toStored(nowMillis()), onboardingStep = step, maskedPhone = maskedPhone))
        _state.value = AuthState.Onboarding
    }

    /** Moves onboarding to its next step (for example `REGISTER_DEVICE` once the phone is verified). */
    suspend fun advanceOnboarding(step: String) {
        val s = store.session.value
        if (s.onboarding == null) return
        store.save(s.copy(onboardingStep = step))
    }

    /** Ends the session on this phone. The phone stays registered: the device id and key are kept. */
    suspend fun signOutLocally(endedByServer: Boolean = false) {
        // The person chose to leave: their data goes with them. When the server ended the session the data stays, so the
        // same person signing back in keeps their unsent writes (a different person is handled in startSession).
        if (!endedByServer) {
            cleaner.clearUserData()
            store.setDataOwner(null)
        }
        store.clear()
        _endedByServer.value = endedByServer
        _issue.value = null
        _state.value = AuthState.SignedOut
    }

    private fun SessionDto.toStored(receivedAtMillis: Long) = StoredToken(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtMillis = receivedAtMillis + expiresInSeconds * 1000L,
        scope = scope,
    )

    companion object {
        /** Tokens last 15 minutes; refresh in the last 3 (guide: at 12 to 13 minutes). */
        const val RefreshMarginMillis = 3 * 60 * 1000L
        private val SessionEndedCodes = setOf("identity.session_revoked", "auth.token_invalid", "auth.token_missing")
        private const val DeviceProofInvalid = "identity.device_proof_invalid"
    }
}

/** The real refresh call: `POST /v1/auth/refresh`, no bearer token (the device proof is the credential). */
fun refreshOverHttp(api: Lazy<CircasoApi>): suspend (RefreshRequest) -> AppResult<RefreshResponse> = { request ->
    api.value.call<RefreshResponse> {
        method = HttpMethod.Post
        url("/v1/auth/refresh")
        withoutAuth()
        contentType(ContentType.Application.Json)
        setBody(request)
    }
}
