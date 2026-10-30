package com.circaso.core.platform

/**
 * Google sign-in on iOS needs Google's own SDK, which lives on the Swift side. The Swift app implements this and
 * installs it at launch; the shared code only ever sees an ID token (or why there is none).
 * [completion] gets the token, or null plus `"cancelled"` / `"failed"`.
 */
interface GoogleSignInProvider {
    fun signIn(nonce: String, completion: (String?, String?) -> Unit)
}

object GoogleSignInBridge {
    var provider: GoogleSignInProvider? = null
}
