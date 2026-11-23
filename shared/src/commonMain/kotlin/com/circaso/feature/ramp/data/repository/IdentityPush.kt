package com.circaso.feature.ramp.data.repository

import com.circaso.feature.ramp.domain.RampRepository
import com.circaso.feature.wallet.domain.PinnedNetwork
import com.circaso.feature.wallet.domain.WalletNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A `ramp.kyc` push reaching the app while it runs (guide "Identity verification (KYC)": verified, declined, a step
 * asked for again, the ID expired). The push carries no state, only the network, so the check is read
 * (`GET /v1/ramp/kyc`) for that network: the answer is filed in the phone's copy, and the profile's Verification row
 * and any open Verification screen follow it. The notification itself is shown as it always was; nothing here is
 * logged. Best effort: with no answer the copy stays, and the next opening of the profile asks again.
 */
object IdentityPush {
    const val Kind = "ramp.kyc"

    /** True when [data] was a `ramp.kyc` push (and the read was started). Never blocks the caller. */
    fun received(data: Map<String, String?>, scope: CoroutineScope, ramp: () -> RampRepository): Boolean {
        if (data["kind"] != Kind) return false
        // Each network has its own check; the push says which. Without one, the network in use is read.
        val network = WalletNetwork.of(data["network"])
        scope.launch(network?.let { PinnedNetwork(it) } ?: EmptyCoroutineContext) { runCatching { ramp().kyc() } }
        return true
    }
}
