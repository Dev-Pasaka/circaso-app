package com.circaso.feature.wallet.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Payments approved with the PIN while the wallet's biometrics were off (Pasaka, 2026-11-10): right after one, the app
 * offers fingerprint / Face ID approval once, in a calm sheet over wherever the person is. The repository reports each
 * one here; the app-wide offer layer listens and applies [BiometricOfferRule].
 */
class PinApprovals {
    private val heard = MutableSharedFlow<SignSubject>(extraBufferCapacity = 4)
    val approved: SharedFlow<SignSubject> = heard

    fun report(subject: SignSubject) { heard.tryEmit(subject) }
}

/** When the one-time offer is made after a PIN approval. Pure, so it is tested on its own. */
object BiometricOfferRule {
    /**
     * Offer once per wallet per phone, only where it can work (the phone has biometrics enrolled), never while it is on,
     * and not when Circle just reported it gone for this wallet: then the person has just declined Circle's own "update
     * biometrics" page, and the Security row says how to turn it on again.
     */
    fun shouldOffer(supported: Boolean, enabled: Boolean, alreadyOffered: Boolean, lost: Boolean): Boolean =
        supported && !enabled && !alreadyOffered && !lost
}

/**
 * What a Circle warning or error code from a challenge means for a wallet's biometric approval on this phone.
 * Lockouts pass on their own (the PIN covers them meanwhile); these mean the key is gone or switched off until the
 * person sets it up again, for the wallet the call was for only (GW8b).
 */
object BiometricSignals {
    private val gone = setOf(
        "biometricsKeyPermanentlyInvalidated", "biometricsUserDisableForPin", "biometricsSettingNotEnabled",
        "deviceNotSupportBiometrics", "biometricsUserNotAllowPermission",
    )

    /**
     * [usedBiometrics]: the call ran with biometrics on. Then a skip means the person passed on Circle's "update
     * biometrics" page after their fingerprints or face changed (Circle had already dropped the old key), so it is gone too.
     */
    fun lost(code: String?, usedBiometrics: Boolean): Boolean =
        code in gone || (usedBiometrics && code == "biometricsUserSkip")
}
