package com.circaso.feature.auth.domain

/** How a platform sign-in attempt ended. */
sealed interface SocialSignInResult {
    data class Success(val credential: SocialCredential) : SocialSignInResult

    /** The person closed the sheet. Not an error: stay on the screen, say nothing. */
    data object Cancelled : SocialSignInResult

    /** This build has no implementation for the provider (not set up yet). The screen says so plainly. */
    data object Unavailable : SocialSignInResult

    /** The provider or the phone could not do it (no Google account, no network, no Play Services). */
    data object Failed : SocialSignInResult
}

/**
 * Google and Apple sign-in, implemented per platform. The [nonce] comes from the server and must go into the
 * request unchanged (Google), or as its SHA-256 hex (Apple), so the server can tie the token to this attempt.
 */
interface SocialSignIn {
    suspend fun signIn(provider: SocialProvider, nonce: String): SocialSignInResult
}

/** Stands in until the platform's Google/Apple sign-in exists, so nothing pretends to work. */
object UnavailableSocialSignIn : SocialSignIn {
    override suspend fun signIn(provider: SocialProvider, nonce: String): SocialSignInResult = SocialSignInResult.Unavailable
}
