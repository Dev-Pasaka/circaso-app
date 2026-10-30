package com.circaso.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.LocalAuthentication.LABiometryTypeTouchID
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics

@OptIn(ExperimentalForeignApi::class)
class IosBiometrics : Biometrics {
    // biometryType is only filled in after canEvaluatePolicy; it reports the hardware even when nothing is enrolled.
    override val name: String
        get() {
            val context = LAContext()
            context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, null)
            return if (context.biometryType == LABiometryTypeTouchID) "Touch ID" else "Face ID"
        }

    override fun available(): Boolean = LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, null)
}
