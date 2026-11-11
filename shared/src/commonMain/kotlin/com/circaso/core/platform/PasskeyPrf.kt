package com.circaso.core.platform

/**
 * A passkey for circaso.com that gives back a secret through WebAuthn's PRF extension (iOS 18+ AuthenticationServices,
 * Android Credential Manager with Google Password Manager). The PIN Vault (2026-11-11) turns that secret into the key
 * its PIN is sealed with, so only the person's own passkey (Face ID, fingerprint or the phone's screen lock) opens it.
 * The passkey syncs with their Apple or Google account, so the vault survives a lost phone or a sign-out.
 *
 * The same [prfSalt] always gives the same 32-byte [PrfResult.Success.secret] for the same passkey.
 */
interface PasskeyPrf {
    /** Whether this phone can make and use a PRF passkey at all; the vault is offered only where it can. */
    suspend fun isSupported(): Boolean

    /** Makes the passkey (one system sheet), asking for its PRF secret for [prfSalt] in the same step where possible. */
    suspend fun create(userId: String, userName: String, displayName: String, prfSalt: ByteArray): PrfResult

    /** Uses the passkey [credentialId] (one system sheet) and returns its PRF secret for [prfSalt]. */
    suspend fun secret(credentialId: ByteArray, prfSalt: ByteArray): PrfResult

    companion object {
        /** The passkey domain (relying party). Fixed forever: a passkey is tied to it. */
        const val RelyingParty = "circaso.com"
        const val RelyingPartyName = "Circaso"
    }
}

sealed interface PrfResult {
    /** [secret] is null when the passkey was made but its maker gave no PRF secret at creation: ask with [PasskeyPrf.secret]. */
    class Success(val credentialId: ByteArray, val secret: ByteArray?) : PrfResult
    data object Cancelled : PrfResult
    /** The passkey provider has no PRF (an older iPhone, or a password manager without it). */
    data object Unsupported : PrfResult
    data class Failed(val reason: String) : PrfResult
}

/** No passkeys here (previews, tests, desktop): the vault isn't offered. */
object NoPasskeyPrf : PasskeyPrf {
    override suspend fun isSupported() = false
    override suspend fun create(userId: String, userName: String, displayName: String, prfSalt: ByteArray) = PrfResult.Unsupported
    override suspend fun secret(credentialId: ByteArray, prfSalt: ByteArray) = PrfResult.Unsupported
}
