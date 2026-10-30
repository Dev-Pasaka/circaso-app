package com.circaso.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WireFormatTest {
    @Test fun framesRoundTripWithVersionAndType() {
        val body = byteArrayOf(9, 8, 7)
        val bytes = WireFormat.encode(WireFormat.Type.SenderKeyMessage, body)
        assertEquals(1, bytes[0])
        assertEquals(3, bytes[1])
        val frame = WireFormat.decode(bytes)!!
        assertEquals(WireFormat.Type.SenderKeyMessage, frame.type)
        assertContentEquals(body, frame.body)
    }

    @Test fun unknownVersionOrTypeIsNotOurs() {
        assertNull(WireFormat.decode(byteArrayOf(2, 1, 0)))
        assertNull(WireFormat.decode(byteArrayOf(1, 99, 0)))
        assertNull(WireFormat.decode(byteArrayOf(1)))
    }

    @Test fun paddingFillsToTheNextBucketAndComesOff() {
        for (size in listOf(0, 1, 159, 160, 161, 1000)) {
            val plain = ByteArray(size) { it.toByte() }
            val padded = Padding.pad(plain)
            assertEquals(0, padded.size % Padding.Bucket, "size $size")
            assertEquals(((size / Padding.Bucket) + 1) * Padding.Bucket, padded.size, "size $size")
            assertContentEquals(plain, Padding.unpad(padded), "size $size")
        }
    }

    @Test fun shortMessagesOfDifferentLengthsLookTheSameSize() {
        assertEquals(Padding.pad("hi".encodeToByteArray()).size, Padding.pad("see you at six, bring the tickets".encodeToByteArray()).size)
    }

    @Test fun badPaddingIsRefusedNotGuessed() {
        assertFailsWith<IllegalArgumentException> { Padding.unpad(ByteArray(10)) }
        assertFailsWith<IllegalArgumentException> { Padding.unpad(ByteArray(160)) } // count byte 0
    }

    @Test fun payloadRoundTripsAndSkipsUnknownFields() {
        val payload = ChatPayload(type = ChatPayload.Text, text = "Habari 👋", replyTo = "m1", clientSentAt = "2026-10-28T10:00:00Z")
        val bytes = ChatPayload.encode(payload)
        assertEquals(0, bytes.size % Padding.Bucket)
        assertEquals(payload, ChatPayload.decode(bytes))
        val newer = Padding.pad("""{"v":2,"type":"sticker","stickerId":"x","text":null}""".encodeToByteArray())
        assertEquals(ChatPayload(v = 2, type = "sticker"), ChatPayload.decode(newer))
        assertNull(ChatPayload.decode(Padding.pad(byteArrayOf(1, 2, 3))))
    }
}
