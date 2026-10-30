package com.circaso.feature.auth

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.NoBody
import com.circaso.core.network.circasoJson
import com.circaso.core.session.AuthManager
import com.circaso.core.session.AuthState
import com.circaso.core.session.DeviceIdentity
import com.circaso.core.session.SessionDto
import com.circaso.core.session.SessionStore
import com.circaso.feature.auth.data.remote.AuthRemote
import com.circaso.feature.auth.data.remote.CarrierRequest
import com.circaso.feature.auth.data.remote.NonceResponse
import com.circaso.feature.auth.data.remote.PhoneVerifiedResponse
import com.circaso.feature.auth.data.remote.RegisterDeviceRequest
import com.circaso.feature.auth.data.remote.RegisterDeviceResponse
import com.circaso.feature.auth.data.remote.SignInResponse
import com.circaso.feature.auth.data.remote.SocialSignInRequest
import com.circaso.feature.auth.data.remote.StartPhoneRequest
import com.circaso.feature.auth.data.remote.StartPhoneResponse
import com.circaso.feature.auth.data.remote.UserStateDto
import com.circaso.feature.auth.data.remote.VerifyCodeRequest
import com.circaso.feature.auth.data.repository.AuthRepositoryImpl
import com.circaso.feature.auth.domain.AppleName
import com.circaso.feature.auth.domain.SignInStep
import com.circaso.feature.auth.domain.SignInWithSocialUseCase
import com.circaso.feature.auth.domain.SocialCredential
import com.circaso.feature.auth.domain.SocialProvider
import com.circaso.feature.auth.domain.SocialSignIn
import com.circaso.feature.auth.domain.SocialSignInResult
import com.circaso.feature.auth.domain.SocialSignInState
import com.circaso.testing.FakeClientInfo
import com.circaso.testing.FakeKeys
import com.circaso.testing.MemoryStorage
import com.circaso.testing.runSuspending
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeRemote : AuthRemote {
    var nonce: AppResult<NonceResponse> = AppResult.Success(NonceResponse("n-1", 300))
    var social: AppResult<SignInResponse> = AppResult.Success(signInResponse("VERIFY_PHONE"))
    var verify: AppResult<PhoneVerifiedResponse> = AppResult.Success(PhoneVerifiedResponse(true, "+254 7•• ••• 678", "REGISTER_DEVICE"))
    var register: AppResult<RegisterDeviceResponse> = AppResult.Success(
        RegisterDeviceResponse(UserStateDto("u1", "ACTIVE"), SessionDto("app-access", "app-refresh", 900, "app"), SessionDto("agent", null, 900, "agent")),
    )
    var signOut: AppResult<NoBody> = AppResult.Success(NoBody())
    var forget: AppResult<NoBody> = AppResult.Success(NoBody())

    val socialRequests = mutableListOf<SocialSignInRequest>()
    val registerRequests = mutableListOf<RegisterDeviceRequest>()
    var signOutCalls = 0

    override suspend fun nonce() = nonce
    override suspend fun social(request: SocialSignInRequest): AppResult<SignInResponse> { socialRequests += request; return social }
    override suspend fun startPhone(request: StartPhoneRequest): AppResult<StartPhoneResponse> = AppResult.Success(StartPhoneResponse("v-1", 6, 300, 30))
    override suspend fun verifyCode(request: VerifyCodeRequest) = verify
    override suspend fun carrier(request: CarrierRequest) = verify
    override suspend fun registerDevice(request: RegisterDeviceRequest): AppResult<RegisterDeviceResponse> { registerRequests += request; return register }
    override suspend fun signOut(): AppResult<NoBody> { signOutCalls++; return signOut }
    override suspend fun forgetCurrentDevice() = forget
}

private fun signInResponse(next: String) = SignInResponse(
    next = next,
    maskedPhone = "+254 7•• ••• 678",
    session = SessionDto("session-access", "session-refresh", 900, if (next == "DONE") "app" else "onboarding"),
    agentSession = if (next == "DONE") SessionDto("agent", null, 900, "agent") else null,
)

private class FakeSocial(var result: SocialSignInResult) : SocialSignIn {
    var lastNonce: String? = null
    override suspend fun signIn(provider: SocialProvider, nonce: String): SocialSignInResult { lastNonce = nonce; return result }
}

class AuthRepositoryTest {
    private val storage = MemoryStorage()
    private val keys = FakeKeys(present = false) // a fresh install has no key yet
    private val remote = FakeRemote()
    private val store = SessionStore(storage, circasoJson())
    private val identity = DeviceIdentity(storage, keys)
    private val session = AuthManager(store, identity, { error("refresh not expected") }, { 1_000L })
    private val repo = AuthRepositoryImpl(remote, session, identity, FakeClientInfo())
    private val credential = SocialCredential("id-token")

    @Test fun firstSignInSendsTheDeviceButNoProofBecauseThereIsNoKeyYet() = runSuspending {
        repo.signInWithSocial(SocialProvider.Google, credential, "n-1")
        val request = remote.socialRequests.single()
        assertEquals("google", request.provider)
        assertEquals("n-1", request.nonce)
        assertEquals("android", request.device.platform)
        assertEquals("Pixel 8", request.device.model)
        assertEquals(identity.deviceId(), request.device.deviceId)
        assertNull(request.deviceProof)
    }

    @Test fun aPhoneThatHoldsItsKeySendsTheProofOverNoncePlusTimestamp() = runSuspending {
        keys.present = true
        repo.signInWithSocial(SocialProvider.Google, credential, "nonce-xyz")
        val proof = assertNotNull(remote.socialRequests.single().deviceProof)
        assertEquals("sig<nonce-xyz${proof.timestamp}>", proof.signature)
    }

    @Test fun appleNameIsPassedOnTheFirstSignIn() = runSuspending {
        repo.signInWithSocial(SocialProvider.Apple, SocialCredential("t", AppleName("Amina", "Wanjiru")), "n")
        val name = assertNotNull(remote.socialRequests.single().appleName)
        assertEquals("Amina", name.given)
        assertEquals("Wanjiru", name.family)
    }

    @Test fun anOnboardingStepStartsAnOnboardingSessionNotASignIn() = runSuspending {
        val result = repo.signInWithSocial(SocialProvider.Google, credential, "n")
        assertEquals(SignInStep.VerifyPhone, assertIs<AppResult.Success<*>>(result).let { (it.value as com.circaso.feature.auth.domain.SignInOutcome).step })
        assertEquals(AuthState.Onboarding, session.state.value)
        assertEquals("session-access", session.accessToken())
    }

    @Test fun theOnboardingStepAndMaskedPhoneAreRememberedForARestart() = runSuspending {
        repo.signInWithSocial(SocialProvider.Google, credential, "n")
        assertEquals("VERIFY_PHONE", store.session.value.onboardingStep)
        assertEquals("+254 7•• ••• 678", store.session.value.maskedPhone)
        repo.verifyPhoneCode("v-1", "123456")
        assertEquals("REGISTER_DEVICE", SessionStore(storage, circasoJson()).load().onboardingStep)
    }

    @Test fun doneMeansSignedInAtOnceWithTheAppAndAgentSessions() = runSuspending {
        remote.social = AppResult.Success(signInResponse("DONE"))
        repo.signInWithSocial(SocialProvider.Google, credential, "n")
        assertEquals(AuthState.Active, session.state.value)
        assertEquals("agent", store.session.value.agent?.accessToken)
    }

    @Test fun anUnknownNextValueIsNotTrusted() = runSuspending {
        remote.social = AppResult.Success(signInResponse("SOMETHING_NEW"))
        val result = repo.signInWithSocial(SocialProvider.Google, credential, "n")
        assertIs<AppError.Unexpected>(assertIs<AppResult.Failure>(result).error)
        assertEquals(AuthState.Restoring, session.state.value) // nothing was started
    }

    @Test fun aRejectedTokenLeavesTheSessionUntouched() = runSuspending {
        remote.social = AppResult.Failure(AppError.Api("identity.provider_token_invalid", 401, "r", null, emptyMap()))
        val result = repo.signInWithSocial(SocialProvider.Google, credential, "n")
        assertEquals("identity.provider_token_invalid", (assertIs<AppResult.Failure>(result).error as AppError.Api).code)
        assertNull(store.session.value.onboarding)
    }

    @Test fun phoneStepsMapTheNextValue() = runSuspending {
        assertEquals(AppResult.Success(SignInStep.RegisterDevice), repo.verifyPhoneCode("v-1", "123456"))
        remote.verify = AppResult.Success(PhoneVerifiedResponse(true, "x", "DONE"))
        assertEquals(AppResult.Success(SignInStep.Done), repo.verifyPhoneWithCarrier("tok"))
    }

    @Test fun registeringTheDeviceCreatesTheKeyAndSignsThePersonIn() = runSuspending {
        assertTrue(!keys.present)
        val result = repo.registerDevice(pushToken = "fcm-1")
        assertEquals(AppResult.Success(Unit), result)
        val request = remote.registerRequests.single()
        assertEquals("PUBLIC", request.publicKey)
        assertEquals("ES256", request.keyAlgorithm)
        assertEquals("fcm-1", request.pushToken)
        assertEquals(identity.deviceId(), request.deviceId)
        assertTrue(keys.present)
        assertEquals(AuthState.Active, session.state.value)
        assertEquals("app-access", session.accessToken())
    }

    @Test fun aFailedRegistrationKeepsTheSameKeyForTheRetry() = runSuspending {
        remote.register = AppResult.Failure(AppError.Network)
        repo.registerDevice(null)
        val first = remote.registerRequests.single().publicKey
        remote.register = RegisterDeviceResponse(UserStateDto("u", "ACTIVE"), SessionDto("a", "r", 900, "app"), SessionDto("g", null, 900, "agent")).let { AppResult.Success(it) }
        repo.registerDevice(null)
        assertEquals(first, remote.registerRequests.last().publicKey)
    }

    @Test fun signOutAlwaysSignsOutHereEvenWhenOffline() = runSuspending {
        session.startSession(SessionDto("a", "r", 900, "app"), null)
        remote.signOut = AppResult.Failure(AppError.Network)
        repo.signOut()
        assertEquals(1, remote.signOutCalls)
        assertEquals(AuthState.SignedOut, session.state.value)
    }

    @Test fun signOutKeepsThePhoneRegistered() = runSuspending {
        keys.present = true
        session.startSession(SessionDto("a", "r", 900, "app"), null)
        repo.signOut()
        assertTrue(keys.present)
    }

    @Test fun forgettingThePhoneNeedsTheServerAndChangesNothingIfItFails() = runSuspending {
        keys.present = true
        session.startSession(SessionDto("a", "r", 900, "app"), null)
        remote.forget = AppResult.Failure(AppError.Network)
        assertIs<AppResult.Failure>(repo.forgetThisPhone())
        assertEquals(AuthState.Active, session.state.value)
        assertTrue(keys.present)

        remote.forget = AppResult.Success(NoBody())
        assertEquals(AppResult.Success(Unit), repo.forgetThisPhone())
        assertEquals(AuthState.SignedOut, session.state.value)
        assertTrue(!keys.present)
    }

    // ---- the use case that strings the steps together

    @Test fun theUseCaseRunsNonceThenPlatformThenServerWithTheSameNonce() = runSuspending {
        val social = FakeSocial(SocialSignInResult.Success(credential))
        val state = SignInWithSocialUseCase(repo, social)(SocialProvider.Google)
        assertEquals("n-1", social.lastNonce)
        assertEquals("n-1", remote.socialRequests.single().nonce)
        assertIs<SocialSignInState.Next>(state)
    }

    @Test fun cancellingTheSheetIsNotAnErrorAndNeverCallsTheServer() = runSuspending {
        val state = SignInWithSocialUseCase(repo, FakeSocial(SocialSignInResult.Cancelled))(SocialProvider.Google)
        assertEquals(SocialSignInState.Cancelled, state)
        assertTrue(remote.socialRequests.isEmpty())
    }

    @Test fun noNonceMeansNoPlatformSignIn() = runSuspending {
        remote.nonce = AppResult.Failure(AppError.Network)
        val social = FakeSocial(SocialSignInResult.Success(credential))
        val state = SignInWithSocialUseCase(repo, social)(SocialProvider.Google)
        assertEquals(SocialSignInState.Failed(AppError.Network), state)
        assertNull(social.lastNonce)
    }
}
