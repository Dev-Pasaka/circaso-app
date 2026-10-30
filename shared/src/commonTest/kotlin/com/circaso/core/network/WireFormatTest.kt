package com.circaso.core.network

import com.circaso.feature.auth.data.remote.DeviceDto
import com.circaso.feature.auth.data.remote.RegisterDeviceRequest
import com.circaso.feature.auth.data.remote.SocialSignInRequest
import com.circaso.feature.contacts.data.remote.SyncRequest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bytes that go to the server. A field the server requires must never be dropped just because it equals a default. */
class WireFormatTest {
    private val json = circasoJson()

    @Test fun theKeyAlgorithmIsSentEvenThoughItIsTheDefault() {
        val body = json.encodeToString(RegisterDeviceRequest.serializer(), RegisterDeviceRequest("d-1", "PUBLIC", model = "Pixel 8"))
        val obj = json.parseToJsonElement(body).jsonObject
        assertEquals("ES256", obj["keyAlgorithm"]?.jsonPrimitive?.content)
        assertEquals("d-1", obj["deviceId"]?.jsonPrimitive?.content)
        assertEquals("PUBLIC", obj["publicKey"]?.jsonPrimitive?.content)
    }

    @Test fun absentOptionalFieldsAreLeftOutNotSentAsNull() {
        val body = json.encodeToString(RegisterDeviceRequest.serializer(), RegisterDeviceRequest("d-1", "PUBLIC"))
        val obj = json.parseToJsonElement(body).jsonObject
        assertFalse("pushToken" in obj)
        assertFalse("attestation" in obj)
        assertFalse(body.contains("null"))
    }

    @Test fun socialSignInOmitsTheOptionalProofAndName() {
        val request = SocialSignInRequest("google", "tok", "n", device = DeviceDto("d", "android", "Pixel 8", "15", "1.0.0"))
        val obj = json.parseToJsonElement(json.encodeToString(SocialSignInRequest.serializer(), request)).jsonObject
        assertFalse("deviceProof" in obj)
        assertFalse("appleName" in obj)
        assertTrue("device" in obj)
    }

    @Test fun contactSyncKeepsItsBatchFlags() {
        val obj = json.parseToJsonElement(json.encodeToString(SyncRequest.serializer(), SyncRequest("s", batch = 1, final = false, phones = listOf("+254712345678")))).jsonObject
        assertEquals("1", obj["batch"]?.jsonPrimitive?.content)
        assertEquals("false", obj["final"]?.jsonPrimitive?.content) // a `false` that equals no default must still be there
    }
}
