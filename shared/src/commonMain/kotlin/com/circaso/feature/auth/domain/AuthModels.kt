package com.circaso.feature.auth.domain

/** Where sign-in goes next (`next` in the API). */
enum class SignInStep {
    /** New account or unknown phone: verify the phone number. */
    VerifyPhone,

    /** Known account on a different phone: verify the account's own number again. The old phone is signed out. */
    ReverifyPhone,

    /** Number verified on this phone but it has no key yet: register the device. */
    RegisterDevice,

    /** This phone is registered and proved it: the person is signed in. */
    Done,
}

enum class SocialProvider(val apiName: String) { Google("google"), Apple("apple") }

/** Apple gives the person's name only the first time, so it is passed on straight away. */
data class AppleName(val given: String?, val family: String?)

/** What the platform's Google/Apple sign-in hands back. */
data class SocialCredential(val idToken: String, val appleName: AppleName? = null)

data class SignInOutcome(val step: SignInStep, val maskedPhone: String?)

data class PhoneVerification(
    val verificationId: String,
    val codeLength: Int,
    val expiresInSeconds: Int,
    val resendAfterSeconds: Int,
)
