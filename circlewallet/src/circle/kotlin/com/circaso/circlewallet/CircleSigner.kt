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

    private fun configure(appId: String) {
        if (configuredAppId == appId) return
        // Biometric PIN on: once the person enables it, Circle's screens offer fingerprint / Face ID instead of the PIN.
        // A phone Circle won't do that on must still get its wallet, so it then starts with the PIN only.
        try {
            WalletSdk.init(context, WalletSdk.Configuration(CircleWallet.Endpoint, appId, SettingsManagement(true)))
        } catch (e: Throwable) {
            WalletSdk.init(context, WalletSdk.Configuration(CircleWallet.Endpoint, appId))
        }
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
                    override fun onWarning(warning: ExecuteWarning, result: ExecuteResult?): Boolean {
                        val outcome = if (result != null) SignOutcome.Done else SignOutcome.Failed(warning.name, warning.warningString ?: "Biometrics weren't turned on.")
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
        suspendCancellableCoroutine<SignOutcome> { continuation ->
            WalletSdk.execute(
                activity, session.userToken, session.encryptionKey, arrayOf(challengeId),
                object : Callback<ExecuteResult> {
                    override fun onResult(result: ExecuteResult) { if (continuation.isActive) continuation.resume(SignOutcome.Done) }
                    override fun onError(error: Throwable): Boolean {
                        noteBiometrics((error as? ApiError)?.code?.name, session.subject.walletId)
                        val outcome = if (error is ApiError && error.code == ApiError.ErrorCode.userCanceled) SignOutcome.Cancelled
                        else SignOutcome.Failed((error as? ApiError)?.code?.name ?: "sdk", error.message ?: "Signing failed.")
                        if (continuation.isActive) continuation.resume(outcome)
                        return false // the SDK closes its screen
                    }
                    // A biometrics warning (skipped, locked out) can come with the signed result or without one: only a
                    // result means the challenge was signed. Without one it is a failure the person can retry with the PIN.
                    override fun onWarning(warning: ExecuteWarning, result: ExecuteResult?): Boolean {
                        noteBiometrics(warning.name, session.subject.walletId)
                        val outcome = if (result != null) SignOutcome.Done else SignOutcome.Failed(warning.name, warning.warningString ?: "Signing didn't finish.")
                        if (continuation.isActive) continuation.resume(outcome)
                        return false
                    }
                },
            )
        }
    }

    /**
     * Lockouts pass on their own; these mean the biometric key is gone or switched off until it's set up again, for
     * the wallet (Circle user) this call was for only: the others keep theirs (GW8b).
     */
    private fun noteBiometrics(code: String?, walletId: String?) {
        if (code in BiometricsGone) WalletSignerBridge.reportBiometricsLost(walletId)
    }

    private companion object {
        val BiometricsGone = setOf(
            "biometricsKeyPermanentlyInvalidated", "biometricsUserDisableForPin", "biometricsSettingNotEnabled",
            "deviceNotSupportBiometrics", "biometricsUserNotAllowPermission",
        )
    }
}
