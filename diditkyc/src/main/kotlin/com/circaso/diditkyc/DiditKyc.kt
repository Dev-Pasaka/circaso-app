package com.circaso.diditkyc

import android.app.Activity
import android.app.Application
import com.circaso.feature.ramp.domain.IdentityVerifier
import com.circaso.feature.ramp.domain.IdentityVerifierBridge
import com.circaso.feature.ramp.domain.VerifierOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.didit.sdk.Configuration
import me.didit.sdk.DiditSdk
import me.didit.sdk.DiditSdkState
import me.didit.sdk.VerificationError
import me.didit.sdk.VerificationResult

/**
 * The app's one call into this module: installs Didit's SDK behind the shared seam. [currentActivity] gives the
 * Activity the SDK opens its screens over (the one in front when a check starts).
 */
object DiditKyc {
    fun install(application: Application, currentActivity: () -> Activity?) {
        IdentityVerifierBridge.verifier = DiditIdentityVerifier(application, currentActivity)
    }
}

/**
 * Runs one identity check in Didit's own screens (the ID, the selfie, the face match), started with the session token
 * Circaso's backend made. The SDK is set up the first time someone verifies, not at launch. What it reports back is
 * turned into a [VerifierOutcome] and nothing more: the decision itself is the backend's to read from Didit.
 *
 * Nothing is logged here, and the SDK's own logging stays off: a session token opens one person's identity check.
 */
private class DiditIdentityVerifier(private val application: Application, private val currentActivity: () -> Activity?) : IdentityVerifier {
    override val available = true

    /**
     * The close button and its "are you sure?" stay (leaving is always the person's choice, and the check can be
     * continued); the SDK closes itself when the last step is done, so Circaso's own "Checking your details" follows
     * at once instead of a second "done" screen to tap through. Language: the phone's, English where Didit has none.
     */
    private val configuration = Configuration(
        loggingEnabled = false,
        showCloseButton = true,
        showExitConfirmation = true,
        closeOnComplete = true,
    )

    override suspend fun verify(sessionToken: String): VerifierOutcome = withContext(Dispatchers.Main.immediate) {
        if (currentActivity() == null) return@withContext VerifierOutcome.Unavailable
        try {
            if (!DiditSdk.isInitialized()) DiditSdk.initialize(application)
        } catch (e: Throwable) {
            return@withContext VerifierOutcome.Failed("init")
        }
        val result = CompletableDeferred<VerifierOutcome>()
        try {
            DiditSdk.startVerification(token = sessionToken, configuration = configuration) { ended -> result.complete(ended.toOutcome()) }
        } catch (e: Throwable) {
            return@withContext VerifierOutcome.Failed("start")
        }
        coroutineScope {
            // The SDK has begun loading the session: its screens are opened over the Activity in front as soon as it
            // says so. It shows its own loading and "couldn't start" screens, and every way out of them ends in the
            // result. Should Circaso have gone to the background meanwhile, the check is let go (it stays open at
            // the server and can be continued).
            val opener = launch {
                DiditSdk.state.first { it !is DiditSdkState.Idle }
                if (!result.isCompleted) currentActivity()?.let { DiditSdk.launchVerificationUI(it) } ?: DiditSdk.cancelVerification()
            }
            result.await().also { opener.cancel() }
        }
    }
}

private fun VerificationResult.toOutcome(): VerifierOutcome = when (this) {
    is VerificationResult.Completed -> VerifierOutcome.Finished
    is VerificationResult.Cancelled -> VerifierOutcome.Cancelled
    is VerificationResult.Failed -> when (error) {
        is VerificationError.CameraAccessDenied -> VerifierOutcome.CameraDenied
        is VerificationError.SessionExpired, is VerificationError.RetryBlocked -> VerifierOutcome.Ended
        is VerificationError.NetworkError -> VerifierOutcome.Offline
        is VerificationError.NotInitialized -> VerifierOutcome.Failed("not_initialized")
        is VerificationError.ApiError -> VerifierOutcome.Failed("api")
        else -> VerifierOutcome.Failed("unknown")
    }
}
