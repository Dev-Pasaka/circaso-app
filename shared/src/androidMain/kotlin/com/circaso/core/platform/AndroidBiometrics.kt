package com.circaso.core.platform

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.Build

class AndroidBiometrics(private val context: Context) : Biometrics {
    override val name = "Fingerprint"

    // Android 9 and older can't be asked without the retired fingerprint API: offer it and let Circle's own check decide.
    override fun available(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        val manager = context.getSystemService(BiometricManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 30) manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        else @Suppress("DEPRECATION") (manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
    }
}
