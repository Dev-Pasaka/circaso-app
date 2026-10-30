package com.circaso.feature.wallet.domain

/**
 * The Circle SDK on the phone: the only place a wallet key share, a PIN or a signature ever exists. The server makes
 * challenges (create the wallet, add a chain, sign a payment); the person completes them here with the PIN or
 * biometrics in the SDK's own screens, themed as Circaso. Shared code never sees the SDK: each platform installs an
 * implementation at launch (`WalletSignerBridge`), and a phone without it answers [SignOutcome.Unavailable].
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

    private val lost = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Circle said biometric approval no longer works on this phone (the enrolled fingerprints or Face ID changed, it
     * was turned off, or permission was taken away). Payments fall back to the PIN; the app stops showing it as on.
     */
    val biometricsLost: kotlinx.coroutines.flow.SharedFlow<Unit> = lost

    fun reportBiometricsLost() { lost.tryEmit(Unit) }
}

/** Stands in until a platform installs the real one. */
object NoWalletSigner : WalletSigner {
    override val available = false
    override suspend fun execute(session: SdkSession, challengeId: String): SignOutcome = SignOutcome.Unavailable
    override suspend fun enableBiometrics(session: SdkSession): SignOutcome = SignOutcome.Unavailable
}
