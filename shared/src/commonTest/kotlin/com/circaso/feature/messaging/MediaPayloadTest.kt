package com.circaso.feature.messaging

import com.circaso.feature.messaging.data.repository.MediaDetails
import com.circaso.feature.messaging.data.repository.toMediaDetails
import com.circaso.feature.messaging.data.repository.toPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaPayloadTest {
    @Test
    fun imageRoundTripsWithoutThePhoneOnlyFile() {
        val sent = MediaDetails(MediaDetails.Image, attachmentId = "a1", key = "k", digest = "d", size = 42, mime = "image/jpeg", width = 10, height = 20, caption = "hi", file = "local.bin")
        val got = sent.toPayload("2026-10-30T10:00:00Z").toMediaDetails()
        assertEquals(sent.copy(file = null), got)
    }

    @Test
    fun videoRoundTripsWithItsThumbnailAndLength() {
        val sent = MediaDetails(MediaDetails.Video, attachmentId = "a2", key = "k", digest = "d", size = 9_000_000, mime = "video/mp4", width = 720, height = 1280, durationMs = 42_000, caption = "goal!", thumb = "/9j/AAA=", file = "out-v")
        val got = sent.toPayload("2026-10-30T10:00:00Z").toMediaDetails()
        assertEquals(sent.copy(file = null), got)
    }

    @Test
    fun aThumbnailOnAPhotoIsIgnored() {
        val p = MediaDetails(MediaDetails.Image, attachmentId = "a1", key = "k", digest = "d", thumb = "x").toPayload("t")
        assertNull(p.toMediaDetails()?.thumb)
    }

    @Test
    fun locationRoundTrips() {
        val sent = MediaDetails(MediaDetails.Location, latitude = -1.29, longitude = 36.82, accuracy = 12.0, label = "Nairobi")
        assertEquals(sent, sent.toPayload("t").toMediaDetails())
    }

    @Test
    fun voiceWithoutKeyIsNotShown() {
        val sent = MediaDetails(MediaDetails.Voice, attachmentId = "a1", digest = "d", durationMs = 3000)
        assertNull(sent.toPayload("t").toMediaDetails())
    }

    @Test
    fun storedTextReadsBack() {
        val d = MediaDetails(MediaDetails.Voice, attachmentId = "a", key = "k", digest = "d", durationMs = 1200)
        assertEquals(d, MediaDetails.read(MediaDetails.write(d)))
    }
}
