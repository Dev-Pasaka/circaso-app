package com.circaso.core.session

import kotlinx.serialization.Serializable

/** Token pair as the API sends it (`SessionDto` in the guide). */
@Serializable
data class SessionDto(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresInSeconds: Int,
    val scope: String,
)

@Serializable
data class RefreshRequest(val refreshToken: String, val deviceId: String, val timestamp: String, val signature: String)

@Serializable
data class RefreshResponse(val session: SessionDto, val agentSession: SessionDto)

/** A token as kept on the phone, with the moment it stops working (by this phone's clock). */
@Serializable
data class StoredToken(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMillis: Long,
    val scope: String,
)

/** What is on the phone: the `app` session, the read-only `agent` session for Asha, or a short `onboarding` token. */
@Serializable
data class StoredSession(
    val app: StoredToken? = null,
    val agent: StoredToken? = null,
    val onboarding: StoredToken? = null,
    /** Where onboarding stands, as the API names it (`VERIFY_PHONE`, `REVERIFY_PHONE`, `REGISTER_DEVICE`), so a restart resumes in the right place. */
    val onboardingStep: String? = null,
    /** The account's masked number (`+254 7•• ••• 678`), shown while verifying. */
    val maskedPhone: String? = null,
)

sealed interface AuthState {
    /** Still reading the secure store at launch. Takes milliseconds and never touches the network. */
    data object Restoring : AuthState
    data object SignedOut : AuthState

    /** Signed in with Google/Apple, phone or device not verified yet. */
    data object Onboarding : AuthState

    /** A full session is on the phone. Works offline. */
    data object Active : AuthState
}

/** A problem with the session that is not a sign-out and that the person can fix. */
enum class AuthIssue {
    /** The server rejected the device proof: almost always a wrong date or time on the phone. */
    CheckDateAndTime,
}
