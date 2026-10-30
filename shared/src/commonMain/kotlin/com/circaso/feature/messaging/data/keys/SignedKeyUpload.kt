package com.circaso.feature.messaging.data.keys

import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.platform.DeviceKeyStore

/**
 * The device-key proof on `PUT /v1/keys` and `POST /v1/keys/one-time` (integration guide, section 5, key uploads).
 * The signed string is `timestamp + "\n" + hex(SHA-256(body))` over the **exact bytes sent**, so the body is
 * serialised once, hashed, signed and then sent unchanged and uncompressed. The proof travels in two headers.
 */
class SignedKeyUpload(private val deviceKey: DeviceKeyStore, private val crypto: SignalCrypto) {
    class Proof(val timestamp: String, val signature: String) {
        val headers get() = mapOf(TimestampHeader to timestamp, SignatureHeader to signature)
    }

    suspend fun prove(body: ByteArray, timestamp: String): Proof =
        Proof(timestamp, deviceKey.sign(payloadToSign(body, timestamp, crypto::sha256)))

    companion object {
        const val TimestampHeader = "X-Device-Timestamp"
        const val SignatureHeader = "X-Device-Signature"

        /** Pure assembly of the signed string, kept apart so the guide's example can be checked byte for byte. */
        fun payloadToSign(body: ByteArray, timestamp: String, sha256: (ByteArray) -> ByteArray): String =
            timestamp + "\n" + sha256(body).toHex()

        fun ByteArray.toHex(): String = joinToString("") { b -> ((b.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    }
}
