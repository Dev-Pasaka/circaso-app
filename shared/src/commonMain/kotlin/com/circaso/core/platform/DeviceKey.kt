package com.circaso.core.platform

import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant

/** The phone's hardware-backed ECDSA P-256 key. The private half never leaves the secure hardware. */
interface DeviceKeyStore {
    suspend fun hasKey(): Boolean

    /** Creates the key if there is none and returns the public key as standard Base64 of the DER SubjectPublicKeyInfo. */
    suspend fun getOrCreatePublicKey(): String

    /** Signs the UTF-8 bytes of [payload]; returns standard Base64 of the DER-encoded ECDSA signature. */
    suspend fun sign(payload: String): String

    suspend fun delete()
}

class DeviceKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Checks a signature against a public key. Used by the self-check and tests, never by the sign-in flow. */
expect fun verifyEs256(publicKeySpkiBase64: String, payload: String, signatureBase64: String): Boolean

/**
 * The exact byte formats the backend demands (integration guide, section 5). A wrong format fails with
 * `identity.device_proof_invalid` and no hint, so every rule here has a test.
 */
object DeviceKeyFormats {
    /** Fixed 26-byte DER prefix that turns a raw P-256 point (`04 || X || Y`) into a SubjectPublicKeyInfo. */
    val SpkiHeaderHex = "3059301306072A8648CE3D020106082A8648CE3D030107034200"
    val SpkiHeader: ByteArray = SpkiHeaderHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    const val RawPointSize = 65
    const val SpkiSize = 91

    /** iOS gives the bare 65-byte point; the server wants the 91-byte SPKI. */
    fun spkiFromRawPoint(point: ByteArray): ByteArray {
        require(point.size == RawPointSize && point[0] == 0x04.toByte()) { "expected an uncompressed P-256 point (65 bytes starting 04)" }
        return SpkiHeader + point
    }

    fun rawPointFromSpki(spki: ByteArray): ByteArray {
        require(spki.size == SpkiSize && spki.copyOfRange(0, SpkiHeader.size).contentEquals(SpkiHeader)) { "not a P-256 SubjectPublicKeyInfo" }
        return spki.copyOfRange(SpkiHeader.size, spki.size)
    }

    /** Some libraries return a raw 64-byte `r || s` signature; the server wants ASN.1 DER. */
    fun derFromRawSignature(raw: ByteArray): ByteArray {
        require(raw.size == 64) { "expected 64 bytes (r || s)" }
        fun integer(bytes: ByteArray): ByteArray {
            var start = 0
            while (start < bytes.size - 1 && bytes[start] == 0.toByte()) start++
            var v = bytes.copyOfRange(start, bytes.size)
            if (v[0] < 0) v = byteArrayOf(0) + v // keep the integer positive
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    /** Same-phone sign-in proof (`deviceProof` on `POST /v1/auth/social`): `nonce + timestamp`, nothing between. */
    fun signInPayload(nonce: String, timestamp: String) = nonce + timestamp

    /** Refresh proof (`POST /v1/auth/refresh`): `refreshToken + "|" + timestamp`. */
    fun refreshPayload(refreshToken: String, timestamp: String) = "$refreshToken|$timestamp"

    /** ISO-8601 UTC to the second (`2026-10-24T18:58:25Z`). Send exactly the string that was signed. */
    fun timestamp(now: Instant = Clock.System.now()): String = Instant.fromEpochSeconds(now.epochSeconds).toString()

    fun base64(bytes: ByteArray): String = Base64.encode(bytes)
    fun fromBase64(text: String): ByteArray = Base64.decode(text)
}
