package com.circaso.feature.ramp.domain

/**
 * Didit's identity verification on the phone (guide "Identity verification (KYC)"): the ID photo, the selfie and the
 * face match run in Didit's own screens, started with the session token the backend made (R0). Shared code never sees
 * the SDK: each platform installs an implementation at launch ([IdentityVerifierBridge], the Android module
 * `:diditkyc`, `DiditKycImpl.swift`), and a build without it answers [VerifierOutcome.Unavailable], which sends the
 * person to Didit's hosted page instead (`RunIdentityCheckUseCase`, in the system's browser).
 *
 * **Whatever comes back is a hint, never the truth.** The backend reads the decision from Didit, so the caller reads
 * `GET /v1/ramp/kyc` after every outcome and shows that. Nothing here is logged: not the token, not the link.
 */
interface IdentityVerifier {
    /** The SDK is in this build. */
    val available: Boolean

    /** Runs the open check in the SDK's screens; returns when they have closed, however they closed. */
    suspend fun verify(sessionToken: String): VerifierOutcome
}

sealed interface VerifierOutcome {
    /** The SDK's last screen was reached: everything was sent, and the result is on its way. */
    data object Finished : VerifierOutcome

    /** The person closed it before the end. The check stays open at the server and can be continued. */
    data object Cancelled : VerifierOutcome

    /** Back from the hosted page: it isn't known whether they finished. */
    data object Left : VerifierOutcome

    /** Camera access is refused for Circaso, so the SDK had nothing to work with: Settings is the way on. */
    data object CameraDenied : VerifierOutcome

    /** The SDK ended the check itself (the session ran out, or its tries are used up): the server says what is next. */
    data object Ended : VerifierOutcome

    /** The connection dropped and the person left the SDK's own "no connection" screen. */
    data object Offline : VerifierOutcome

    /** The SDK isn't in this build, or couldn't be shown (nothing to present it over). */
    data object Unavailable : VerifierOutcome

    /** The SDK stopped for a reason of its own. [code] is a short word for the logs, never shown and never personal. */
    data class Failed(val code: String) : VerifierOutcome
}

/** Where a platform puts its [IdentityVerifier] at launch, like the wallet signer's bridge. */
object IdentityVerifierBridge {
    var verifier: IdentityVerifier? = null
}

/** Stands in until a platform installs the real one: no SDK in this build. */
object NoIdentityVerifier : IdentityVerifier {
    override val available = false
    override suspend fun verify(sessionToken: String): VerifierOutcome = VerifierOutcome.Unavailable
}
