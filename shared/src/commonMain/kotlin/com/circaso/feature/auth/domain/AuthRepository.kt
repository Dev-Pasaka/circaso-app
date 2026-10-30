package com.circaso.feature.auth.domain

import com.circaso.core.common.AppResult

/**
 * Everything the sign-in screens need from the server and the session. Results are domain types;
 * the repository starts or ends the session itself, so screens never see a token.
 */
interface AuthRepository {
    /** A one-time nonce, valid 5 minutes. */
    suspend fun requestNonce(): AppResult<String>

    /** Verifies the provider's ID token. On [SignInStep.Done] the person is already signed in. */
    suspend fun signInWithSocial(provider: SocialProvider, credential: SocialCredential, nonce: String): AppResult<SignInOutcome>

    suspend fun startPhoneVerification(phone: String): AppResult<PhoneVerification>

    suspend fun verifyPhoneCode(verificationId: String, code: String): AppResult<SignInStep>

    /** Android one-tap: verifies the number from a carrier token instead of an SMS. */
    suspend fun verifyPhoneWithCarrier(carrierToken: String): AppResult<SignInStep>

    /** Registers this phone's key. This activates the account and signs the person in. */
    suspend fun registerDevice(pushToken: String?): AppResult<Unit>

    /** Ends the session on this phone. The phone stays registered. Always signs out locally, even offline. */
    suspend fun signOut()

    /** Forgets this phone entirely: the next sign-in verifies the number again. Needs the server. */
    suspend fun forgetThisPhone(): AppResult<Unit>
}
