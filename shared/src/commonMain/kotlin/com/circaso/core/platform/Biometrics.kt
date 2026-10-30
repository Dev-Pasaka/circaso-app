package com.circaso.core.platform

/** The phone's biometric unlock, for the payment-approval offer and its copy. */
interface Biometrics {
    /** "Face ID", "Touch ID" or "Fingerprint". */
    val name: String

    /** The phone has the hardware and something enrolled, so Circle's biometric PIN can be set up. */
    fun available(): Boolean
}
