package com.circaso.feature.vault.data

import com.circaso.core.crypto.SignalCrypto
import com.circaso.feature.vault.domain.VaultContents
import kotlinx.serialization.json.Json

/**
 * Seals what the PIN Vault keeps (2026-11-11): key = HKDF-SHA256(passkey PRF secret, salt "circaso-vault", info
 * "pin/v1|" + userId), then AES-256-GCM with the same text as associated data, so a vault can't be opened as another
 * person's. The server only ever holds the sealed bytes.
 */
class VaultCrypto(private val crypto: SignalCrypto) {
    fun seal(prfSecret: ByteArray, userId: String, contents: VaultContents): ByteArray {
        val key = key(prfSecret, userId)
        return try { crypto.aesGcmSeal(key, json.encodeToString(VaultContents.serializer(), contents).encodeToByteArray(), info(userId)) } finally { key.fill(0) }
    }

    /** Null when the secret, person or bytes don't match (a wrong passkey, or a damaged vault). */
    fun open(prfSecret: ByteArray, userId: String, sealed: ByteArray): VaultContents? {
        val key = key(prfSecret, userId)
        return try {
            runCatching { json.decodeFromString(VaultContents.serializer(), crypto.aesGcmOpen(key, sealed, info(userId)).decodeToString()) }.getOrNull()
        } finally { key.fill(0) }
    }

    private fun key(secret: ByteArray, userId: String) = Hkdf.sha256(secret, Salt, info(userId), 32, crypto::sha256)

    private fun info(userId: String) = "$Info|$userId".encodeToByteArray()

    companion object {
        const val Algorithm = "prf-hkdf-sha256+aes-256-gcm/v1"
        private val Salt = "circaso-vault".encodeToByteArray()
        private const val Info = "pin/v1"
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

/** RFC 5869 HKDF over HMAC-SHA256, built on the app's one SHA-256 so no second crypto library is needed. */
internal object Hkdf {
    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int, sha256: (ByteArray) -> ByteArray): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmac(salt, ikm, sha256)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()), sha256)
            val n = minOf(previous.size, length - filled)
            previous.copyInto(out, filled, 0, n)
            filled += n
            counter++
        }
        prk.fill(0)
        return out
    }

    fun hmac(key: ByteArray, message: ByteArray, sha256: (ByteArray) -> ByteArray): ByteArray {
        val block = 64
        val k = (if (key.size > block) sha256(key) else key).copyOf(block)
        val inner = ByteArray(block) { (k[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(block) { (k[it].toInt() xor 0x5c).toByte() }
        return sha256(outer + sha256(inner + message))
    }
}
