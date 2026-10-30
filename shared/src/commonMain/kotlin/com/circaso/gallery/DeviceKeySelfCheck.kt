package com.circaso.gallery

import com.circaso.core.platform.DeviceKeyFormats
import com.circaso.core.platform.DeviceKeyStore
import com.circaso.core.platform.verifyEs256
import com.circaso.core.session.DeviceIdentity

/**
 * Debug check that the key on this phone produces exactly what the backend expects (guide section 5):
 * a 91-byte SPKI, DER signatures, and signatures that verify. Run from the gallery on each platform.
 */
suspend fun deviceKeySelfCheck(identity: DeviceIdentity, keys: DeviceKeyStore): List<String> {
    val lines = mutableListOf<String>()
    fun check(name: String, ok: Boolean) { lines += (if (ok) "OK   " else "FAIL ") + name }
    try {
        val existed = keys.hasKey()
        val publicKey = identity.publicKey()
        val spki = DeviceKeyFormats.fromBase64(publicKey)
        check("key ${if (existed) "found" else "created"}", keys.hasKey())
        check("public key is 91 bytes (SPKI)", spki.size == DeviceKeyFormats.SpkiSize)
        check("starts with the P-256 SPKI header", spki.size >= 26 && spki.copyOfRange(0, 26).contentEquals(DeviceKeyFormats.SpkiHeader))
        check("Base64 is 124 characters with padding", publicKey.length == 124 && publicKey.endsWith("="))
        check("same key on a second read", identity.publicKey() == publicKey)

        val refreshProof = identity.refreshProof("sample-refresh-token")
        val payload = DeviceKeyFormats.refreshPayload("sample-refresh-token", refreshProof.timestamp)
        val der = DeviceKeyFormats.fromBase64(refreshProof.signature)
        check("signature is DER (starts 30, length matches)", der.size > 8 && der[0] == 0x30.toByte() && (der[1].toInt() and 0xFF) == der.size - 2)
        check("refresh signature verifies", verifyEs256(publicKey, payload, refreshProof.signature))
        check("a changed payload does not verify", !verifyEs256(publicKey, payload + "x", refreshProof.signature))

        val signIn = identity.signInProof("sample-nonce")
        check("sign-in signature verifies", verifyEs256(publicKey, DeviceKeyFormats.signInPayload("sample-nonce", signIn.timestamp), signIn.signature))
        check("timestamp is ISO-8601 UTC to the second", Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(signIn.timestamp))
        check("device id is stable", identity.deviceId() == identity.deviceId())
        lines += "key: ${publicKey.take(18)}…   device: ${identity.deviceId().take(8)}…"
    } catch (e: Exception) {
        lines += "FAIL ${e::class.simpleName}: ${e.message}"
    }
    return lines
}
