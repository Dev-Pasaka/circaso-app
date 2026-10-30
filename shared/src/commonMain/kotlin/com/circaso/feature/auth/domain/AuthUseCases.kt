package com.circaso.feature.auth.domain

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult

/** What a sign-in attempt came to, for the screen to act on. */
sealed interface SocialSignInState {
    data class Next(val outcome: SignInOutcome) : SocialSignInState
    data object Cancelled : SocialSignInState
    data object Unavailable : SocialSignInState
    data class Failed(val error: AppError?) : SocialSignInState
}

/** Nonce, platform sign-in, server check: the three steps of flows A and D as one action. */
class SignInWithSocialUseCase(private val repository: AuthRepository, private val social: SocialSignIn) {
    suspend operator fun invoke(provider: SocialProvider): SocialSignInState {
        val nonce = when (val n = repository.requestNonce()) {
            is AppResult.Success -> n.value
            is AppResult.Failure -> return SocialSignInState.Failed(n.error)
        }
        val credential = when (val r = social.signIn(provider, nonce)) {
            is SocialSignInResult.Success -> r.credential
            SocialSignInResult.Cancelled -> return SocialSignInState.Cancelled
            SocialSignInResult.Unavailable -> return SocialSignInState.Unavailable
            SocialSignInResult.Failed -> return SocialSignInState.Failed(null)
        }
        return when (val result = repository.signInWithSocial(provider, credential, nonce)) {
            is AppResult.Success -> SocialSignInState.Next(result.value)
            is AppResult.Failure -> SocialSignInState.Failed(result.error)
        }
    }
}

class StartPhoneVerificationUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(phone: String) = repository.startPhoneVerification(phone)
}

class VerifyPhoneCodeUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(verificationId: String, code: String) = repository.verifyPhoneCode(verificationId, code)
}

class VerifyPhoneWithCarrierUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(carrierToken: String) = repository.verifyPhoneWithCarrier(carrierToken)
}

class RegisterDeviceUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(pushToken: String? = null) = repository.registerDevice(pushToken)
}

class SignOutUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke() = repository.signOut()
}

class ForgetThisPhoneUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke() = repository.forgetThisPhone()
}
