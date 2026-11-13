package com.circaso.feature.messaging.data.local

import com.circaso.core.datastore.AppPreferences
import com.circaso.feature.messaging.domain.VerifiedCodes
import kotlinx.coroutines.flow.Flow

/**
 * Security codes this phone has checked, per person, in the app's preferences (`text.verified.<peerId>`). Not secret:
 * the code comes from public keys. [AppPreferences.clear] drops them on sign-out.
 */
class VerifiedCodesStore(private val prefs: AppPreferences) : VerifiedCodes {
    override fun verifiedCode(peerId: String): Flow<String?> = prefs.text("verified.$peerId")
    override suspend fun setVerifiedCode(peerId: String, digits: String?) = prefs.setText("verified.$peerId", digits)
}
