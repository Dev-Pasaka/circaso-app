package com.circaso.feature.status

import com.circaso.feature.mediacompose.TrimWindow
import kotlin.test.Test
import kotlin.test.assertEquals

/** M3b: a minute is the most a moment holds, not a fixed window (Pasaka, 2026-11-07). */
class TrimWindowTest {
    @Test
    fun aTwelveSecondClipCanBeCutFromEitherEnd() {
        assertEquals(0L to 12_000L, TrimWindow.initial(12_000))
        // The start handle in to 3 s, then the end handle back to 8 s.
        assertEquals(3_000L to 12_000L, TrimWindow.clamp(12_000, 0, 3_000, 12_000))
        assertEquals(3_000L to 8_000L, TrimWindow.clamp(12_000, 3_000, 3_000, 8_000))
        // The 5 s window slides, and stops at the end of the clip.
        assertEquals(6_000L to 11_000L, TrimWindow.slide(12_000, 3_000, 8_000, 3_000))
        assertEquals(7_000L to 12_000L, TrimWindow.slide(12_000, 3_000, 8_000, 9_000))
        assertEquals(0L to 5_000L, TrimWindow.slide(12_000, 3_000, 8_000, -9_000))
    }

    @Test
    fun aFiftyNineSecondClipCanBeShortenedToAnyLength() {
        assertEquals(0L to 59_000L, TrimWindow.initial(59_000))
        assertEquals(0L to 7_500L, TrimWindow.clamp(59_000, 0, 0, 7_500))
        assertEquals(51_000L to 59_000L, TrimWindow.clamp(59_000, 0, 51_000, 59_000))
        // Past either end: held inside the clip.
        assertEquals(0L to 59_000L, TrimWindow.clamp(59_000, 0, -4_000, 70_000))
    }

    @Test
    fun aThreeMinuteClipOpensOnAMovableMinuteThatCanShrink() {
        assertEquals(0L to 60_000L, TrimWindow.initial(180_000))
        assertEquals(100_000L to 160_000L, TrimWindow.slide(180_000, 0, 60_000, 100_000))
        assertEquals(120_000L to 180_000L, TrimWindow.slide(180_000, 0, 60_000, 500_000))
        // Shrunk to 20 s, then the end dragged far out: never more than a minute, the start follows it.
        assertEquals(100_000L to 120_000L, TrimWindow.clamp(180_000, 100_000, 100_000, 120_000))
        assertEquals(115_000L to 175_000L, TrimWindow.clamp(180_000, 100_000, 100_000, 175_000))
        // The start dragged back past a minute: the end follows it.
        assertEquals(40_000L to 100_000L, TrimWindow.clamp(180_000, 100_000, 40_000, 120_000))
    }

    @Test
    fun theWindowNeverGoesUnderASecond() {
        assertEquals(5_000L to 6_000L, TrimWindow.clamp(12_000, 5_000, 5_000, 5_200)) // the end moved: it gives way
        assertEquals(4_000L to 5_000L, TrimWindow.clamp(12_000, 3_000, 4_500, 5_000)) // the start moved: it gives way
        assertEquals(11_000L to 12_000L, TrimWindow.clamp(12_000, 11_000, 11_000, 11_300)) // at the very end
        assertEquals(0L to 600L, TrimWindow.clamp(600, 0, 0, 100)) // a clip under a second is kept whole
    }
}
