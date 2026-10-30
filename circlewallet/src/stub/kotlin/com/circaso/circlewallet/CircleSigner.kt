package com.circaso.circlewallet

import android.app.Activity
import android.content.Context
import com.circaso.feature.wallet.domain.NoWalletSigner
import com.circaso.feature.wallet.domain.WalletSigner

/** No Circle credentials at build time: every challenge answers "not available in this build". */
internal fun createSigner(context: Context, currentActivity: () -> Activity?): WalletSigner = NoWalletSigner
