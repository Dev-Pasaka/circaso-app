package com.circaso.feature.wallet.domain

/**
 * The Circle SDK on the phone: the only place a wallet key share, a PIN or a signature ever exists. The server makes
 * challenges (create the wallet, add a chain, sign a payment); the person completes them here with the PIN or
 * biometrics in the SDK's own screens, themed as Circaso. Shared code never sees the SDK: each platform installs an
 * implementation at launch (`WalletSignerBridge`), and a phone without it answers [SignOutcome.Unavailable].
 *
 * **Several wallets, one SDK.** A person may sign for their own wallet and for group wallets; each is its own Circle
 * user with its own PIN and biometrics. The SDK stays configured once (one app id); every call carries the session of
 * the wallet it is for, and `session.subject` names that wallet so the PIN screens say whose money it is. Calls never
 * overlap: the repository runs one challenge at a time, so one wallet's PIN screen can't stand over another's.
 */
interface WalletSigner {
    val available: Boolean
    suspend fun execute(session: SdkSession, challengeId: String): SignOutcome

    /**
     * Lets payments be approved with the fingerprint or Face ID instead of the PIN (Circle's biometric PIN): the
     * person confirms with the PIN once, and Circle's SDK keeps the key behind the phone's secure hardware. Circaso
     * never sees or stores the PIN.
     */
    suspend fun enableBiometrics(session: SdkSession): SignOutcome
}

sealed interface SignOutcome {
    data object Done : SignOutcome
    data object Cancelled : SignOutcome
    /** The SDK is not in this build (no Circle credentials at build time, or the iOS pod is not installed). */
    data object Unavailable : SignOutcome
    data class Failed(val code: String, val message: String) : SignOutcome
}

object WalletSignerBridge {
    var signer: WalletSigner? = null

    private val lost = kotlinx.coroutines.flow.MutableSharedFlow<BiometricsLost>(extraBufferCapacity = 4)

    /**
     * Circle said biometric approval no longer works for one wallet on this phone (the enrolled fingerprints or Face ID
     * changed, it was turned off, or permission was taken away). That wallet falls back to its PIN; the app stops
     * showing it as on. Other wallets keep theirs: biometrics are per Circle user (GW8b).
     */
    val biometricsLost: kotlinx.coroutines.flow.SharedFlow<BiometricsLost> = lost

    /** The personal wallet's biometrics are gone. */
    fun reportBiometricsLost() { lost.tryEmit(BiometricsLost(null)) }

    /** The biometrics of the wallet [walletId] (null: the personal one) are gone. */
    fun reportBiometricsLost(walletId: String?) { lost.tryEmit(BiometricsLost(walletId)) }
}

/** Stands in until a platform installs the real one. */
object NoWalletSigner : WalletSigner {
    override val available = false
    override suspend fun execute(session: SdkSession, challengeId: String): SignOutcome = SignOutcome.Unavailable
    override suspend fun enableBiometrics(session: SdkSession): SignOutcome = SignOutcome.Unavailable
}

/** Which wallet lost its biometric approval; [walletId] null is the personal wallet. */
data class BiometricsLost(val walletId: String?)
