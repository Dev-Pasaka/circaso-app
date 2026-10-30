package com.circaso.core.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class DeviceKeyFormatsTest {
    private val point = byteArrayOf(0x04) + ByteArray(64) { (it + 1).toByte() }

    @Test
    fun headerIsTheTwentySixBytesFromTheGuide() {
        assertEquals(26, DeviceKeyFormats.SpkiHeader.size)
        assertEquals("3059301306072A8648CE3D020106082A8648CE3D030107034200", DeviceKeyFormats.SpkiHeader.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.uppercase())
    }

    @Test
    fun rawPointBecomesA91ByteSpkiAndBack() {
        val spki = DeviceKeyFormats.spkiFromRawPoint(point)
        assertEquals(91, spki.size)
        assertContentEquals(DeviceKeyFormats.SpkiHeader, spki.copyOfRange(0, 26))
        assertContentEquals(point, DeviceKeyFormats.rawPointFromSpki(spki))
    }

    @Test
    fun spkiBase64Is124CharactersWithPadding() {
        val text = DeviceKeyFormats.base64(DeviceKeyFormats.spkiFromRawPoint(point))
        assertEquals(124, text.length)
        assertTrue(text.endsWith("="))
    }

    @Test
    fun rejectsAPointThatIsNotUncompressedP256() {
        assertFailsWith<IllegalArgumentException> { DeviceKeyFormats.spkiFromRawPoint(ByteArray(64)) }
        assertFailsWith<IllegalArgumentException> { DeviceKeyFormats.spkiFromRawPoint(byteArrayOf(0x02) + ByteArray(64)) }
    }

    @Test
    fun rawSignatureConvertsToDerWithPositiveIntegers() {
        val raw = ByteArray(64) { 0x01 } // r and s both small and positive
        val der = DeviceKeyFormats.derFromRawSignature(raw)
        assertEquals(0x30, der[0].toInt())
        assertEquals(der.size - 2, der[1].toInt() and 0xFF)
        assertEquals(0x02, der[2].toInt())

        val high = ByteArray(64) { 0xFF.toByte() } // top bit set: DER needs a leading zero
        val derHigh = DeviceKeyFormats.derFromRawSignature(high)
        assertEquals(0x02, derHigh[2].toInt())
        assertEquals(33, derHigh[3].toInt())
        assertEquals(0, derHigh[4].toInt())
    }

    @Test
    fun derDropsLeadingZerosOfShortIntegers() {
        val raw = ByteArray(64).also { it[31] = 0x05; it[63] = 0x07 } // r = 5, s = 7
        val der = DeviceKeyFormats.derFromRawSignature(raw)
        assertContentEquals(byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x05, 0x02, 0x01, 0x07), der)
    }

    @Test
    fun payloadsAreConcatenatedExactly() {
        assertEquals("abc2026-10-24T18:58:25Z", DeviceKeyFormats.signInPayload("abc", "2026-10-24T18:58:25Z"))
        assertEquals("tok|2026-10-24T18:58:25Z", DeviceKeyFormats.refreshPayload("tok", "2026-10-24T18:58:25Z"))
    }

    @Test
    fun timestampIsIsoUtcToTheSecond() {
        val now = Instant.fromEpochMilliseconds(1_761_332_305_789) // has milliseconds on purpose
        val text = DeviceKeyFormats.timestamp(now)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(text), text)
    }
}
