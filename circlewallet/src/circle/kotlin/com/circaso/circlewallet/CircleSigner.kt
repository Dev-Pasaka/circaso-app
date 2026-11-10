package com.circaso.circlewallet

import circle.programmablewallet.sdk.presentation.SettingsManagement
import android.app.Activity
import android.content.Context
import circle.programmablewallet.sdk.WalletSdk
import circle.programmablewallet.sdk.api.ApiError
import circle.programmablewallet.sdk.api.Callback
import circle.programmablewallet.sdk.api.ExecuteWarning
import circle.programmablewallet.sdk.presentation.SecurityQuestion
import circle.programmablewallet.sdk.result.ExecuteResult
import com.circaso.feature.wallet.domain.BiometricSignals
import com.circaso.feature.wallet.domain.SdkSession
import com.circaso.feature.wallet.domain.SignOutcome
import com.circaso.feature.wallet.domain.WalletSigner
import com.circaso.feature.wallet.domain.WalletSignerBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Runs Circle's challenges in the SDK's own screens (PIN, security questions, biometrics), coloured as Circaso through
 * the `circlepw_*` resources in this module. The SDK is configured once per app id (the server sends it with every
 * session); the user token and encryption key are used for one challenge and never kept.
 */
internal fun createSigner(context: Context, currentActivity: () -> Activity?): WalletSigner = CircleWalletSigner(context, currentActivity)

private class CircleWalletSigner(private val context: Context, private val currentActivity: () -> Activity?) : WalletSigner {
    override val available = true
    private var configuredAppId: String? = null

    /**
     * Circle's "biometrics instead of PIN" switch. The SDK keeps the very object passed to `init` and reads
     * `isEnableBiometricsPin` live at every PIN screen, so it is set per call (see [biometricsFor]):
     * - on: Circle's enter-PIN screen opens with the system fingerprint / face prompt over it when this wallet has a
     *   biometric key; cancel, failure or lockout leave its PIN pad underneath ("Use biometrics instead" retries). When
     *   new fingerprints invalidate the key, Circle drops it and, after the PIN, offers to set it up again.
     * - off: no prompt, and none of Circle's own "Allow biometrics" pages after a PIN; the wallet is PIN only.
     * Init starts with it off, so a phone with no biometrics enrolled (where Circle refuses it at init) still gets
     * its wallet.
     */
    private val settings = SettingsManagement(false)

    private fun configure(appId: String) {
        if (configuredAppId == appId) return
        WalletSdk.init(context, WalletSdk.Configuration(CircleWallet.Endpoint, appId, settings))
        WalletSdk.setSecurityQuestions(
            listOf(
                SecurityQuestion("What was the name of your first school?"),
                SecurityQuestion("What is the name of the street you grew up on?"),
                SecurityQuestion("What was your first pet called?"),
                SecurityQuestion("What is your mother's first name?"),
                SecurityQuestion("Which town were you born in?"),
                SecurityQuestion("What was your childhood nickname?"),
            ).toTypedArray(),
        )
        WalletSdk.setViewSetterProvider(CircasoPinImages(context))
        WalletSdk.setLayoutProvider(CircasoPinTexts(context))
        configuredAppId = appId
    }

    /** Names the wallet on Circle's screens for this call (see [CircasoPinTexts]). */
    private fun nameWallet(session: SdkSession) {
        CircasoPinTexts.subject = session.subject
        WalletSdk.setLayoutProvider(CircasoPinTexts(context))
    }

    override suspend fun enableBiometrics(session: SdkSession): SignOutcome = withContext(Dispatchers.Main) {
        val activity = currentActivity() ?: return@withContext SignOutcome.Failed("no_activity", "Nothing to show the PIN screen on.")
        try {
            configure(session.appId)
            nameWallet(session)
        } catch (e: Throwable) {
            return@withContext SignOutcome.Failed("init_failed", e.message ?: "The PIN screen could not start.")
        }
        // Circle's set-up refuses to run with the switch off (biometricsSettingNotEnabled).
        settings.isEnableBiometricsPin = true
        suspendCancellableCoroutine<SignOutcome> { continuation ->
            WalletSdk.setBiometricsPin(
                activity, session.userToken, session.encryptionKey,
                object : Callback<ExecuteResult> {
                    override fun onResult(result: ExecuteResult) { if (continuation.isActive) continuation.resume(SignOutcome.Done) }
                    override fun onError(error: Throwable): Boolean {
                        val outcome = if (error is ApiError && error.code == ApiError.ErrorCode.userCanceled) SignOutcome.Cancelled
                        else SignOutcome.Failed((error as? ApiError)?.code?.name ?: "sdk", error.message ?: "Couldn't turn on biometrics.")
                        if (continuation.isActive) continuation.resume(outcome)
                        return false
                    }
                    // In the set-up every warning means it wasn't set up: Skip and "Don't ask me again" on Circle's
                    // page are the person's choice (no error shown); a lockout or sensor error is said in the app.
                    override fun onWarning(warning: ExecuteWarning, result: ExecuteResult?): Boolean {
                        val outcome = when (warning) {
                            ExecuteWarning.biometricsUserSkip, ExecuteWarning.biometricsUserDisableForPin -> SignOutcome.Cancelled
                            else -> SignOutcome.Failed(warning.name, warning.warningString ?: "Biometrics weren't turned on.")
                        }
                        if (continuation.isActive) continuation.resume(outcome)
                        return false
                    }
                },
            )
        }
    }

    override suspend fun execute(session: SdkSession, challengeId: String): SignOutcome = withContext(Dispatchers.Main) {
        val activity = currentActivity() ?: return@withContext SignOutcome.Failed("no_activity", "Nothing to show the PIN screen on.")
        try {
            configure(session.appId)
            nameWallet(session)
        } catch (e: Throwable) {
            return@withContext SignOutcome.Failed("init_failed", e.message ?: "The signing screen could not start.")
        }
        settings.isEnableBiometricsPin = session.biometrics
        suspendCancellableCoroutine<SignOutcome> { continuation ->
            WalletSdk.execute(
                activity, session.userToken, session.encryptionKey, arrayOf(challengeId),
                object : Callback<ExecuteResult> {
                    override fun onResult(result: ExecuteResult) { if (continuation.isActive) continuation.resume(SignOutcome.Done) }
                    override fun onError(error: Throwable): Boolean {
                        noteBiometrics((error as? ApiError)?.code?.name, session)
                        val outcome = if (error is ApiError && error.code == ApiError.ErrorCode.userCanceled) SignOutcome.Cancelled
                        else SignOutcome.Failed((error as? ApiError)?.code?.name ?: "sdk", error.message ?: "Signing failed.")
                        if (continuation.isActive) continuation.resume(outcome)
                        return false // the SDK closes its screen
                    }
                    // A biometrics warning comes from Circle's "allow / update biometrics" page after the PIN, with the
                    // signed result: only a result means the challenge was signed. Without one it is a failure the
                    // person can retry with the PIN.
                    override fun onWarning(warning: ExecuteWarning, result: ExecuteResult?): Boolean {
                        noteBiometrics(warning.name, session)
                        val outcome = if (result != null) SignOutcome.Done else SignOutcome.Failed(warning.name, warning.warningString ?: "Signing didn't finish.")
                        if (continuation.isActive) continuation.resume(outcome)
                        return false
                    }
                },
            )
        }
    }

    /** Biometric approval stopped working for the wallet (Circle user) this call was for only: the others keep theirs (GW8b). */
    private fun noteBiometrics(code: String?, session: SdkSession) {
        if (BiometricSignals.lost(code, usedBiometrics = session.biometrics)) WalletSignerBridge.reportBiometricsLost(session.subject.walletId)
    }
}
