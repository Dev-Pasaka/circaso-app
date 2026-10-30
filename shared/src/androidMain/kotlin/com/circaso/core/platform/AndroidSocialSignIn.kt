package com.circaso.core.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.circaso.feature.auth.domain.SocialCredential
import com.circaso.feature.auth.domain.SocialProvider
import com.circaso.feature.auth.domain.SocialSignIn
import com.circaso.feature.auth.domain.SocialSignInResult
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.lang.ref.WeakReference

/** Remembers the visible activity: Credential Manager needs one to show the account sheet. */
class CurrentActivity(application: Application) {
    private var ref = WeakReference<Activity>(null)

    init {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { ref = WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) { if (ref.get() === activity) ref = WeakReference(null) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun get(): Activity? = ref.get()
}

/** Google sign-in through Credential Manager. Apple is iOS-only, so it is not available here. The nonce goes in unchanged. */
class AndroidSocialSignIn(private val activities: CurrentActivity, private val webClientId: String) : SocialSignIn {
    private companion object { const val Tag = "CircasoSignIn" }

    override suspend fun signIn(provider: SocialProvider, nonce: String): SocialSignInResult {
        if (provider != SocialProvider.Google) return SocialSignInResult.Unavailable
        val activity = activities.get() ?: run {
            Log.w(Tag, "Google sign-in could not start: no visible activity")
            return SocialSignInResult.Failed
        }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).setNonce(nonce).build())
            .build()
        return try {
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                SocialSignInResult.Success(SocialCredential(GoogleIdTokenCredential.createFrom(credential.data).idToken))
            } else {
                SocialSignInResult.Failed
            }
        } catch (e: GetCredentialCancellationException) {
            SocialSignInResult.Cancelled
        } catch (e: GetCredentialException) {
            // Only the kind of failure is logged (for example "NoCredentialException" or a developer-console setup error); never tokens or accounts.
            Log.w(Tag, "Google sign-in failed: ${e::class.simpleName} type=${e.type} message=${e.message}")
            SocialSignInResult.Failed
        } catch (e: GoogleIdTokenParsingException) {
            Log.w(Tag, "Google sign-in returned an unreadable token")
            SocialSignInResult.Failed
        }
    }
}
