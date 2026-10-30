package com.circaso.feature.auth.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.map
import com.circaso.core.platform.ClientInfo
import com.circaso.core.session.AuthManager
import com.circaso.core.session.DeviceIdentity
import com.circaso.feature.auth.data.remote.AppleNameDto
import com.circaso.feature.auth.data.remote.AuthRemote
import com.circaso.feature.auth.data.remote.CarrierRequest
import com.circaso.feature.auth.data.remote.DeviceDto
import com.circaso.feature.auth.data.remote.DeviceProofDto
import com.circaso.feature.auth.data.remote.RegisterDeviceRequest
import com.circaso.feature.auth.data.remote.SocialSignInRequest
import com.circaso.feature.auth.data.remote.StartPhoneRequest
import com.circaso.feature.auth.data.remote.VerifyCodeRequest
import com.circaso.feature.auth.domain.AuthRepository
import com.circaso.feature.auth.domain.PhoneVerification
import com.circaso.feature.auth.domain.SignInOutcome
import com.circaso.feature.auth.domain.SignInStep
import com.circaso.feature.auth.domain.SocialCredential
import com.circaso.feature.auth.domain.SocialProvider

/** Talks to the auth routes and keeps the session in step: tokens go to [AuthManager], never to a screen. */
class AuthRepositoryImpl(
    private val remote: AuthRemote,
    private val session: AuthManager,
    private val identity: DeviceIdentity,
    private val client: ClientInfo,
) : AuthRepository {

    override suspend fun requestNonce(): AppResult<String> = remote.nonce().map { it.nonce }

    override suspend fun signInWithSocial(
        provider: SocialProvider,
        credential: SocialCredential,
        nonce: String,
    ): AppResult<SignInOutcome> {
        val device = DeviceDto(
            deviceId = identity.deviceId(),
            platform = client.platform,
            model = client.model,
            osVersion = client.osVersion,
            appVersion = client.appVersion,
        )
        // A phone that already holds its key proves it is the same phone (flow D). No key yet means first sign-in on this install.
        val proof = if (identity.hasKey()) {
            runCatching { identity.signInProof(nonce) }.getOrNull()?.let { DeviceProofDto(it.timestamp, it.signature) }
        } else null
        val request = SocialSignInRequest(
            provider = provider.apiName,
            idToken = credential.idToken,
            nonce = nonce,
            appleName = credential.appleName?.let { AppleNameDto(it.given, it.family) },
            device = device,
            deviceProof = proof,
        )
        return when (val result = remote.social(request)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val body = result.value
                val step = body.next.toStep() ?: return AppResult.Failure(AppError.Unexpected(null))
                if (step == SignInStep.Done) session.startSession(body.session, body.agentSession) else session.startOnboarding(body.session, body.next, body.maskedPhone)
                AppResult.Success(SignInOutcome(step, body.maskedPhone))
            }
        }
    }

    override suspend fun startPhoneVerification(phone: String): AppResult<PhoneVerification> =
        remote.startPhone(StartPhoneRequest(phone)).map {
            PhoneVerification(it.verificationId, it.codeLength, it.expiresInSeconds, it.resendAfterSeconds)
        }

    override suspend fun verifyPhoneCode(verificationId: String, code: String): AppResult<SignInStep> =
        remote.verifyCode(VerifyCodeRequest(verificationId, code)).toStepAndAdvance()

    override suspend fun verifyPhoneWithCarrier(carrierToken: String): AppResult<SignInStep> =
        remote.carrier(CarrierRequest(carrierToken)).toStepAndAdvance()

    override suspend fun registerDevice(pushToken: String?): AppResult<Unit> {
        val request = RegisterDeviceRequest(
            deviceId = identity.deviceId(),
            publicKey = identity.publicKey(), // creates the key on first use; the same key on every retry
            keyAlgorithm = "ES256",
            pushToken = pushToken,
            model = client.model,
        )
        return when (val result = remote.registerDevice(request)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                session.startSession(result.value.session, result.value.agentSession)
                AppResult.Success(Unit)
            }
        }
    }

    override suspend fun signOut() {
        // Tell the server if we can; the phone is signed out either way.
        remote.signOut()
        session.signOutLocally()
    }

    override suspend fun forgetThisPhone(): AppResult<Unit> =
        when (val result = remote.forgetCurrentDevice()) {
            is AppResult.Failure -> result // needs the server: nothing changes locally if it did not go through
            is AppResult.Success -> {
                identity.forgetKey()
                session.signOutLocally()
                AppResult.Success(Unit)
            }
        }

    private fun String.toStep(): SignInStep? = when (this) {
        "VERIFY_PHONE" -> SignInStep.VerifyPhone
        "REVERIFY_PHONE" -> SignInStep.ReverifyPhone
        "REGISTER_DEVICE" -> SignInStep.RegisterDevice
        "DONE" -> SignInStep.Done
        else -> null
    }

    /** The phone is verified: remember the next step so a restart resumes there. */
    private suspend fun AppResult<com.circaso.feature.auth.data.remote.PhoneVerifiedResponse>.toStepAndAdvance(): AppResult<SignInStep> = when (this) {
        is AppResult.Failure -> this
        is AppResult.Success -> {
            val step = value.next.toStep()
            if (step == null) AppResult.Failure(AppError.Unexpected(null)) else {
                session.advanceOnboarding(value.next)
                AppResult.Success(step)
            }
        }
    }
}
