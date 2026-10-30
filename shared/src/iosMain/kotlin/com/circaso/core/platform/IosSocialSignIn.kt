@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.circaso.core.platform

import com.circaso.feature.auth.domain.AppleName
import com.circaso.feature.auth.domain.SocialCredential
import com.circaso.feature.auth.domain.SocialProvider
import com.circaso.feature.auth.domain.SocialSignIn
import com.circaso.feature.auth.domain.SocialSignInResult
import com.circaso.core.common.Diagnostics
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AuthenticationServices.ASAuthorization
import platform.AuthenticationServices.ASAuthorizationAppleIDCredential
import platform.AuthenticationServices.ASAuthorizationAppleIDProvider
import platform.AuthenticationServices.ASAuthorizationController
import platform.AuthenticationServices.ASAuthorizationControllerDelegateProtocol
import platform.AuthenticationServices.ASAuthorizationControllerPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASAuthorizationErrorCanceled
import platform.AuthenticationServices.ASAuthorizationScopeEmail
import platform.AuthenticationServices.ASAuthorizationScopeFullName
import platform.AuthenticationServices.ASPresentationAnchor
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * Sign in with Apple through the system framework (no library). Apple is handed the SHA-256 of the server's nonce
 * (the usual native flow; the server accepts it). Google on iOS goes through [GoogleSignInBridge] (the SDK is on the Swift side). The app needs the "Sign in with Apple" capability enabled in Xcode.
 */
class IosSocialSignIn : SocialSignIn {
    // The controller keeps its delegate weakly, so hold it here until the sheet is done.
    private var active: Flow? = null

    override suspend fun signIn(provider: SocialProvider, nonce: String): SocialSignInResult = when (provider) {
        SocialProvider.Apple -> signInWithApple(nonce)
        SocialProvider.Google -> signInWithGoogle(nonce)
    }

    /** Hands over to the Swift side (Google's SDK). Without it installed, Google is simply not offered. */
    private suspend fun signInWithGoogle(nonce: String): SocialSignInResult {
        val provider = GoogleSignInBridge.provider ?: return SocialSignInResult.Unavailable
        return suspendCancellableCoroutine { continuation ->
            provider.signIn(nonce) { token, failure ->
                val result = when {
                    token != null -> SocialSignInResult.Success(SocialCredential(token))
                    failure == "cancelled" -> SocialSignInResult.Cancelled
                    else -> { Diagnostics.note("google sign-in: failed"); SocialSignInResult.Failed }
                }
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }

    private suspend fun signInWithApple(nonce: String): SocialSignInResult = suspendCancellableCoroutine { continuation ->
        val request = ASAuthorizationAppleIDProvider().createRequest().apply {
            requestedScopes = listOf(ASAuthorizationScopeFullName, ASAuthorizationScopeEmail)
            this.nonce = sha256Hex(nonce)
        }
        val flow = Flow { result ->
            active = null
            if (continuation.isActive) continuation.resume(result)
        }
        active = flow
        val controller = ASAuthorizationController(authorizationRequests = listOf(request))
        controller.delegate = flow
        controller.presentationContextProvider = flow
        continuation.invokeOnCancellation { active = null }
        controller.performRequests()
    }

    private class Flow(private val done: (SocialSignInResult) -> Unit) :
        NSObject(), ASAuthorizationControllerDelegateProtocol, ASAuthorizationControllerPresentationContextProvidingProtocol {

        override fun authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization: ASAuthorization) {
            val credential = didCompleteWithAuthorization.credential as? ASAuthorizationAppleIDCredential
            val token = credential?.identityToken?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }
            if (credential == null || token == null) return done(SocialSignInResult.Failed)
            val name = credential.fullName?.let { AppleName(it.givenName, it.familyName) }?.takeIf { it.given != null || it.family != null }
            done(SocialSignInResult.Success(SocialCredential(token, name)))
        }

        override fun authorizationController(controller: ASAuthorizationController, didCompleteWithError: NSError) {
            // The code says why (1000 unknown, 1001 cancelled, 1002 invalid response, 1003 not handled, 1004 failed); never the account.
            Diagnostics.note("apple sign-in error: ${didCompleteWithError.domain} ${didCompleteWithError.code}")
            done(if (didCompleteWithError.code == ASAuthorizationErrorCanceled) SocialSignInResult.Cancelled else SocialSignInResult.Failed)
        }

        override fun presentationAnchorForAuthorizationController(controller: ASAuthorizationController): ASPresentationAnchor =
            UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>().firstOrNull { it.isKeyWindow() }
                ?: UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>().first()
    }
}

private fun sha256Hex(text: String): String {
    val bytes = text.encodeToByteArray()
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { input ->
        digest.usePinned { output ->
            CC_SHA256(input.addressOf(0), bytes.size.convert(), output.addressOf(0).reinterpret())
        }
    }
    return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
