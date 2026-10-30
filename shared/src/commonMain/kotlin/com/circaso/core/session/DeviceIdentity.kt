package com.circaso.core.session

import com.circaso.core.platform.DeviceKeyFormats
import com.circaso.core.platform.DeviceKeyStore
import com.circaso.core.platform.SecureStorage
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Who this phone is to the server: an install id and the hardware key that signs proofs. */
class DeviceIdentity(private val storage: SecureStorage, private val keys: DeviceKeyStore) {
    /** A UUID made once per install and reused in every request. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun deviceId(): String =
        storage.get(DeviceIdKey) ?: Uuid.random().toString().also { storage.put(DeviceIdKey, it) }

    suspend fun publicKey(): String = keys.getOrCreatePublicKey()

    suspend fun hasKey() = keys.hasKey()

    /** Forgets the phone's key. The next sign-in has to verify the phone number again. */
    suspend fun forgetKey() = keys.delete()

    /** Signs the refresh proof. Returns the timestamp and signature that must be sent together. */
    suspend fun refreshProof(refreshToken: String): Proof {
        val timestamp = DeviceKeyFormats.timestamp()
        return Proof(timestamp, keys.sign(DeviceKeyFormats.refreshPayload(refreshToken, timestamp)))
    }

    /** Signs the same-phone sign-in proof for a nonce. */
    suspend fun signInProof(nonce: String): Proof {
        val timestamp = DeviceKeyFormats.timestamp()
        return Proof(timestamp, keys.sign(DeviceKeyFormats.signInPayload(nonce, timestamp)))
    }

    data class Proof(val timestamp: String, val signature: String)

    private companion object {
        const val DeviceIdKey = "device.id"
    }
}
