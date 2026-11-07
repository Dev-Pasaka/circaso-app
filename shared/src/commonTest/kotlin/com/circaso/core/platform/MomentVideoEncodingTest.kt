package com.circaso.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MomentVideoEncodingTest {
    private val e = MomentVideoEncoding

    @Test
    fun aShortPortrait1080pClipKeepsItsSizeAtTheFullRate() {
        val plan = e.plan(1080, 1920, 30f, 16_000_000, durationMs = 20_000)
        assertEquals(VideoPlan(1080, 1920, 8_000_000), plan)
    }

    @Test
    fun aFullMinuteStays1080pAtWhatTheLimitAllows() {
        val plan = e.plan(1920, 1080, 30f, 12_000_000, durationMs = 60_000)
        assertEquals(1920 to 1080, plan.width to plan.height)
        // 32 MiB at 92 %, over 60 s, less 128 kbit/s of audio: just under 4 Mbit/s.
        assertTrue(plan.bitRate in 3_900_000..4_000_000, "${plan.bitRate}")
        val mp4Bytes = (plan.bitRate + e.AudioBitRate).toLong() * 60 / 8
        assertTrue(mp4Bytes < MomentMediaMaxBytes, "$mp4Bytes")
    }

    @Test
    fun fourKComesDownTo1080p() {
        assertEquals(VideoPlan(1920, 1080, 8_000_000), e.plan(3840, 2160, 30f, 45_000_000, durationMs = 10_000))
        assertEquals(VideoPlan(1080, 1920, 8_000_000), e.plan(2160, 3840, 30f, 45_000_000, durationMs = 10_000))
    }

    @Test
    fun neverUpscales() {
        val plan = e.plan(720, 1280, 30f, null, durationMs = 15_000)
        assertEquals(720 to 1280, plan.width to plan.height)
        assertEquals(e.targetBitRate(720, 1280, 30f), plan.bitRate)
        assertEquals(480 to 852, e.plan(480, 853, 30f, null, 5_000).let { it.width to it.height })
    }

    @Test
    fun neverSpendsMoreThanTheSourceHad() {
        val plan = e.plan(1080, 1920, 30f, 2_500_000, durationMs = 10_000)
        assertEquals(VideoPlan(1080, 1920, 2_500_000), plan)
    }

    @Test
    fun aLongSixtyFpsClipDropsASizeRatherThanSmearing() {
        val plan = e.plan(1920, 1080, 60f, 20_000_000, durationMs = 60_000)
        assertEquals(1280 to 720, plan.width to plan.height)
        assertTrue(plan.bitRate >= e.MinBitsPerPixel * 1280 * 720 * 60)
    }

    @Test
    fun frameRateRaisesTheTarget() {
        assertTrue(e.targetBitRate(1920, 1080, 60f) > e.targetBitRate(1920, 1080, 30f))
        assertEquals(8_000_000, e.targetBitRate(1920, 1080, 30f))
        assertEquals(8_000_000, e.cameraBitRate())
        assertEquals(e.BitRate1080p30, MomentVideoBitRate)
    }

    @Test
    fun portraitFillsAPhoneAndLandscapeFits() {
        assertTrue(e.fills(1080, 1920, 1080, 2400))
        assertTrue(e.fills(1080, 1440, 1080, 1920))
        assertFalse(e.fills(1920, 1080, 1080, 2400))
        assertTrue(e.fills(1920, 1080, 2400, 1080)) // the phone turned sideways
        assertTrue(e.fills(0, 0, 1080, 2400)) // size not known yet
    }
}
