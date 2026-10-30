package com.circaso.core.session

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.circasoJson
import com.circaso.testing.FakeKeys
import com.circaso.testing.MemoryStorage
import com.circaso.testing.runSuspending
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthManagerTest {
    private var now = 1_000_000L
    private val storage = MemoryStorage()
    private val keys = FakeKeys()
    private val store = SessionStore(storage, circasoJson())
    private val device = DeviceIdentity(storage, keys)
    private val requests = mutableListOf<RefreshRequest>()
    private var answer: suspend (RefreshRequest) -> AppResult<RefreshResponse> = { ok("new-access", "new-refresh") }

    private fun ok(access: String, refresh: String) = AppResult.Success(
        RefreshResponse(
            session = SessionDto(access, refresh, 900, "app"),
            agentSession = SessionDto("new-agent", null, 900, "agent"),
        ),
    )

    private fun api(code: String) = AppResult.Failure(AppError.Api(code, 401, "req-1", null, emptyMap()))

    private fun manager() = AuthManager(store, device, { request -> requests += request; answer(request) }, { now })

    private fun token(access: String = "old-access", refresh: String? = "old-refresh", expiresInMillis: Long) =
        StoredToken(access, refresh, now + expiresInMillis, "app")

    private suspend fun signedIn(expiresInMillis: Long = 10 * 60_000L): AuthManager {
        store.save(StoredSession(app = token(expiresInMillis = expiresInMillis)))
        return manager().also { it.restore() }
    }

    // ---- launch

    @Test fun launchWithNothingStoredIsSignedOut() = runSuspending {
        val m = manager(); m.restore()
        assertEquals(AuthState.SignedOut, m.state.value)
    }

    @Test fun launchWithAStoredSessionIsActiveWithoutAnyRequest() = runSuspending {
        val m = signedIn()
        assertEquals(AuthState.Active, m.state.value)
        assertTrue(requests.isEmpty())
    }

    @Test fun launchWithAFreshOnboardingTokenIsOnboardingAndAnExpiredOneIsSignedOut() = runSuspending {
        store.save(StoredSession(onboarding = StoredToken("o", null, now + 60_000, "onboarding")))
        val fresh = manager(); fresh.restore()
        assertEquals(AuthState.Onboarding, fresh.state.value)
        assertEquals("o", fresh.accessToken())

        store.save(StoredSession(onboarding = StoredToken("o", null, now - 1, "onboarding")))
        val expired = manager(); expired.restore()
        assertEquals(AuthState.SignedOut, expired.state.value)
        assertNull(expired.accessToken())
    }

    // ---- routine refresh

    @Test fun farFromExpiryNoRefreshHappens() = runSuspending {
        val m = signedIn(expiresInMillis = 10 * 60_000L)
        assertEquals("old-access", m.accessToken())
        assertTrue(requests.isEmpty())
    }

    @Test fun nearExpiryRefreshesAndStoresTheRotatedTokensFirst() = runSuspending {
        val m = signedIn(expiresInMillis = 60_000L)
        assertEquals("new-access", m.accessToken())
        val saved = store.session.value
        assertEquals("new-refresh", saved.app?.refreshToken)
        assertEquals("new-agent", saved.agent?.accessToken)
        assertEquals(now + 900_000L, saved.app?.expiresAtMillis)
        // and the stored copy on disk is the new one too
        assertEquals("new-refresh", SessionStore(storage, circasoJson()).load().app?.refreshToken)
    }

    @Test fun theRefreshRequestCarriesTheDeviceProofInTheGuidesFormat() = runSuspending {
        val m = signedIn(expiresInMillis = 1_000L)
        m.accessToken()
        val request = requests.single()
        assertEquals("old-refresh", request.refreshToken)
        assertEquals(device.deviceId(), request.deviceId)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(request.timestamp), request.timestamp)
        assertEquals("sig<old-refresh|${request.timestamp}>", request.signature)
    }

    // ---- failures never end a session unless the server says so

    @Test fun offlineKeepsTheSessionAndTheOldToken() = runSuspending {
        answer = { AppResult.Failure(AppError.Network) }
        val m = signedIn(expiresInMillis = 1_000L)
        assertEquals("old-access", m.accessToken())
        assertEquals(AuthState.Active, m.state.value)
        assertEquals("old-refresh", store.session.value.app?.refreshToken)
    }

    @Test fun serverErrorsAndRateLimitsKeepTheSession() = runSuspending {
        val m = signedIn(expiresInMillis = 1_000L)
        for (failure in listOf(AppResult.Failure(AppError.Unexpected(503)), api("request.rate_limited"))) {
            answer = { failure }
            m.accessToken()
            assertEquals(AuthState.Active, m.state.value)
        }
    }

    @Test fun aRevokedSessionSignsOutAndClearsTheTokens() = runSuspending {
        answer = { api("identity.session_revoked") }
        val m = signedIn(expiresInMillis = 1_000L)
        m.accessToken()
        assertEquals(AuthState.SignedOut, m.state.value)
        assertNull(store.session.value.app)
        assertNull(storage.values["session.v1"])
    }

    @Test fun aRejectedDeviceProofAsksForDateAndTimeAndKeepsTheSession() = runSuspending {
        answer = { api("identity.device_proof_invalid") }
        val m = signedIn(expiresInMillis = 1_000L)
        m.accessToken()
        assertEquals(AuthState.Active, m.state.value)
        assertEquals(AuthIssue.CheckDateAndTime, m.issue.value)
        answer = { ok("a2", "r2") }
        m.accessToken()
        assertNull(m.issue.value) // fixed clock, issue gone
    }

    @Test fun aMissingDeviceKeySignsOutBecauseTheSessionCanNeverRefresh() = runSuspending {
        val m = signedIn(expiresInMillis = 1_000L)
        keys.present = false
        m.accessToken()
        assertEquals(AuthState.SignedOut, m.state.value)
        assertTrue(requests.isEmpty())
    }

    // ---- single flight and rejection handling

    @Test fun parallelCallersCauseExactlyOneRefresh() = runSuspending {
        answer = { delay(50); ok("new-access", "new-refresh") }
        val m = signedIn(expiresInMillis = 1_000L)
        val tokens = coroutineScope { (1..5).map { async { m.accessToken() } }.awaitAll() }
        assertEquals(1, requests.size)
        assertTrue(tokens.all { it == "new-access" })
    }

    @Test fun aTokenAlreadyReplacedNeedsNoSecondRefresh() = runSuspending {
        val m = signedIn()
        assertTrue(m.refreshAfterRejection("some-older-token"))
        assertTrue(requests.isEmpty())
    }

    @Test fun theCurrentTokenBeingRejectedTriggersARefresh() = runSuspending {
        val m = signedIn()
        assertTrue(m.refreshAfterRejection("old-access"))
        assertEquals(1, requests.size)
        assertEquals("new-access", store.session.value.app?.accessToken)
    }

    @Test fun withoutASessionThereIsNothingToRefresh() = runSuspending {
        val m = manager(); m.restore()
        assertEquals(false, m.refreshAfterRejection("x"))
    }

    // ---- start and end

    @Test fun startSessionStoresItAndSignOutKeepsThePhoneIdentity() = runSuspending {
        val m = manager(); m.restore()
        val deviceId = device.deviceId()
        m.startSession(SessionDto("a", "r", 900, "app"), SessionDto("g", null, 900, "agent"))
        assertEquals(AuthState.Active, m.state.value)
        assertEquals("a", m.accessToken())
        m.signOutLocally()
        assertEquals(AuthState.SignedOut, m.state.value)
        assertNull(m.accessToken())
        assertEquals(deviceId, device.deviceId()) // same phone, same id
        assertTrue(keys.present) // key kept: the next sign-in is one tap
        assertNotEquals("", deviceId)
    }
}
