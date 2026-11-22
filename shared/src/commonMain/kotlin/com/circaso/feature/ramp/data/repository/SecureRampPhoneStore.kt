package com.circaso.feature.ramp.data.repository

import com.circaso.core.platform.SecureStorage
import com.circaso.feature.ramp.domain.RampPhoneStore

/**
 * The person's own M-Pesa number in secure storage (Keystore / Keychain), under their own user id so another account
 * on this phone never reads it. The server only ever returns the number masked, and R3 needs it in full. Never logged.
 */
class SecureRampPhoneStore(private val storage: SecureStorage, private val userId: () -> String?) : RampPhoneStore {
    private fun key(): String? = userId()?.let { "ramp.phone.$it" }
    override suspend fun get(): String? = key()?.let { storage.get(it) }?.takeIf { it.isNotBlank() }
    override suspend fun put(phone: String) { key()?.let { storage.put(it, phone) } }
    override suspend fun clear() { key()?.let { storage.remove(it) } }
}
