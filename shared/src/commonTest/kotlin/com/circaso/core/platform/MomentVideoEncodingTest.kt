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
    fun aFullMinuteStays1080pAtNearlyTheFullRate() {
        val plan = e.plan(1920, 1080, 30f, 12_000_000, durationMs = 60_000)
        assertEquals(1920 to 1080, plan.width to plan.height)
        // 64 MiB (2026-11-14, was 32) at 85 %, over 60 s, less 128 kbit/s of audio: about 7.5 of the 8 Mbit/s it deserves.
        assertTrue(plan.bitRate in 7_400_000..7_550_000, "${plan.bitRate}")
        val mp4Bytes = (plan.bitRate + e.AudioBitRate).toLong() * 60 / 8
        assertTrue(mp4Bytes < MomentMediaMaxBytes, "$mp4Bytes")
        // Twice what the same minute got under the old limit.
        val before = e.plan(1920, 1080, 30f, 12_000_000, durationMs = 60_000, maxBytes = MomentMediaLegacyMaxBytes)
        assertTrue(before.bitRate in 3_600_000..3_700_000, "${before.bitRate}")
    }

    @Test
    fun whatGoesUntouchedAndWhatAMinuteGetsAtTheNewLimit() {
        // Forty seconds or less gets the full 8 Mbit/s target (it was about 23 s).
        assertEquals(8_000_000, e.plan(1080, 1920, 30f, 20_000_000, durationMs = 40_000).bitRate)
        // A minute of 1080p at 60 frames keeps its size now; under the old limit it dropped to 720p.
        val sixty = e.plan(1920, 1080, 60f, 20_000_000, durationMs = 60_000)
        assertEquals(1920 to 1080, sixty.width to sixty.height)
        assertEquals(1280 to 720, e.plan(1920, 1080, 60f, 20_000_000, durationMs = 60_000, maxBytes = MomentMediaLegacyMaxBytes).let { it.width to it.height })
        // A minute recorded by the in-app camera (8 Mbit/s and its sound) is under the limit as it is: never re-encoded.
        assertTrue((MomentVideoBitRate + e.AudioBitRate).toLong() * 60 / 8 < MomentMediaMaxBytes)
        assertEquals(2, (MomentMediaMaxBytes + 64) / (MomentMediaLegacyMaxBytes + 64))
    }

    @Test
    fun aPassThatCameOutOverIsPlannedAgainSmallerByAsMuchAsItOvershot() {
        val max = MomentMediaMaxBytes
        // A fifth over the limit: the next pass aims at the limit over 1.2, less a tenth (three quarters of it).
        val second = e.smallerBudget(max, max * 12 / 10)
        assertTrue(second in max * 74 / 100..max * 76 / 100, "$second")
        // Over again from there: smaller still, never back up.
        val third = e.smallerBudget(second, max * 11 / 10)
        assertTrue(third < second && third in max * 60 / 100..max * 63 / 100, "$third")
        // An encoder that ignores what it is asked for: the budget keeps falling, down to the floor and no further.
        var budget = max
        repeat(12) { budget = e.smallerBudget(budget, max * 2) }
        assertEquals(e.MinBudgetBytes, budget)
        assertEquals(max, e.smallerBudget(max, 0), "nothing measured: nothing changed")
    }

    @Test
    fun aSmallerBudgetLowersTheBitRateAndThenTheSize() {
        // The Redmi's clip (2026-11-14): 57 s of 1080x1920 at 20 Mbit/s.
        // (against the 32 MiB limit of that day)
        val old = MomentMediaLegacyMaxBytes
        val first = e.plan(1080, 1920, 30f, 20_000_000, durationMs = 57_000, maxBytes = old)
        assertEquals(1080 to 1920, first.width to first.height)
        assertTrue(first.bitRate in 3_800_000..3_950_000, "${first.bitRate}")
        // Its encoder came out a quarter over: the second pass is planned at 720p, where a lower rate still looks right.
        val budget = e.smallerBudget(old, old * 125 / 100, old)
        val second = e.plan(1080, 1920, 30f, 20_000_000, durationMs = 57_000, maxBytes = budget)
        assertEquals(720 to 1280, second.width to second.height)
        assertTrue(second.bitRate < first.bitRate * 3 / 4, "${second.bitRate}")
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
        val plan = e.plan(1920, 1080, 60f, 20_000_000, durationMs = 60_000, maxBytes = MomentMediaLegacyMaxBytes)
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
