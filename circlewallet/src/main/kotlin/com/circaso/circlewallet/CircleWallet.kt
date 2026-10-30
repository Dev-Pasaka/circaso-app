package com.circaso.circlewallet

import android.app.Activity
import android.content.Context
import com.circaso.feature.wallet.domain.WalletSigner
import com.circaso.feature.wallet.domain.WalletSignerBridge

/**
 * The app's one call into this module: installs the Circle signer behind the shared seam. [currentActivity] gives
 * the Activity the SDK presents its PIN screens over (the one in front when a challenge starts).
 */
object CircleWallet {
    /** Circle's end-user SDK endpoint; the app id arrives with each session from the server (W2/W3). */
    // The Android SDK (Retrofit) requires the trailing slash; without it init throws and every signing fails.
    const val Endpoint = "https://enduser-sdk.circle.com/v1/w3s/"

    fun install(context: Context, currentActivity: () -> Activity?) {
        WalletSignerBridge.signer = createSigner(context.applicationContext, currentActivity)
    }
}

// `createSigner` is defined once, in `src/circle` (with the SDK) or `src/stub` (without), whichever the build chose.
