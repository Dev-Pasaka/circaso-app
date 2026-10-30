package com.circaso.feature.messaging

import com.circaso.core.crypto.AndroidSignalCrypto
import com.circaso.core.platform.DeviceKeyStore
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** The worked example in integration guide section 5 (key uploads), checked byte for byte. */
class SignedKeyUploadVectorTest {
    private val body = """{"oneTimePreKeys":[{"keyId":101,"publicKey":"BQECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8g"}]}""".encodeToByteArray()
    private val timestamp = "2026-10-25T09:00:00Z"
    private val crypto = AndroidSignalCrypto()

    @Test fun theBodyIs93BytesAndHashesAsTheGuideSays() {
        assertEquals(93, body.size)
        assertEquals("94a80dfab795f31ad61df91575b9a16e6c60ba7e0b056b80b7ed87536b08660a", SignedKeyUpload.payloadToSign(body, timestamp, crypto::sha256).substringAfter('\n'))
    }

    @Test fun theSignedStringIs85BytesWithOneNewline() {
        val payload = SignedKeyUpload.payloadToSign(body, timestamp, crypto::sha256)
        assertEquals(85, payload.encodeToByteArray().size)
        assertEquals(1, payload.count { it == '\n' })
        assertEquals(timestamp, payload.substringBefore('\n'))
    }

    @Test fun theProofSignsExactlyThatStringAndSendsTheSameTimestamp() = runBlocking {
        var signed: String? = null
        val deviceKey = object : DeviceKeyStore {
            override suspend fun hasKey() = true
            override suspend fun getOrCreatePublicKey() = "pk"
            override suspend fun sign(payload: String): String { signed = payload; return "sig" }
            override suspend fun delete() {}
        }
        val proof = SignedKeyUpload(deviceKey, crypto).prove(body, timestamp)
        assertEquals(SignedKeyUpload.payloadToSign(body, timestamp, crypto::sha256), signed)
        assertEquals(mapOf("X-Device-Timestamp" to timestamp, "X-Device-Signature" to "sig"), proof.headers)
    }
}
