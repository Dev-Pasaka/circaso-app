package com.circaso.feature.calls

import com.circaso.feature.calls.presentation.Corner
import com.circaso.feature.calls.presentation.cornerFor
import com.circaso.feature.calls.presentation.spotlightAfterTap
import com.circaso.feature.calls.presentation.spotlightFor
import com.circaso.feature.calls.presentation.spotlightAfterShare
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallStageTest {
    @Test fun tappingATileSpotlightsItAndTappingItAgainGoesBackToTheGrid() {
        val big = spotlightAfterTap(null, "amina")
        assertEquals("amina", big)
        assertEquals("me", spotlightAfterTap(big, "me"), "a small tile takes the spotlight instead")
        assertNull(spotlightAfterTap("me", "me"))
    }

    @Test fun whenTheSpotlitPersonLeavesEveryoneIsBackInTheGrid() {
        assertEquals("amina", spotlightFor("amina", listOf("amina", "juma", "me")))
        assertNull(spotlightFor("amina", listOf("juma", "me")))
        assertNull(spotlightFor(null, listOf("juma", "me")))
    }

    @Test fun someoneSharingTheirScreenTakesTheSpotlightUnlessOneWasChosen() {
        assertEquals("juma" to "juma", spotlightAfterShare(null, null, "juma", null))
        assertEquals("me" to null, spotlightAfterShare("me", null, "juma", null), "this phone's own choice stands")
    }

    @Test fun theSharesSpotlightGoesWithTheShareButATappedOneStays() {
        // Juma's share took the spotlight; it ends: back to the grid.
        assertEquals(null to null, spotlightAfterShare("juma", "juma", null, "juma"))
        // The person tapped Amina meanwhile: the share's end leaves Amina big.
        assertEquals("amina" to null, spotlightAfterShare("amina", "juma", null, "juma"))
        // Someone else starts sharing while the first share still holds the spotlight: it moves to them.
        assertEquals("wanjiru" to "wanjiru", spotlightAfterShare("juma", "juma", "wanjiru", "juma"))
    }

    @Test fun aSelfViewSettlesInTheCornerItWasFlungToward() {
        val w = 400f
        val h = 800f
        assertEquals(Corner.TopEnd, cornerFor(330f, 120f, 0f, 0f, w, h))
        assertEquals(Corner.BottomStart, cornerFor(70f, 700f, 0f, 0f, w, h))
        // Let go just past the middle, but flung back up and to the right: it goes where it was heading.
        assertEquals(Corner.TopEnd, cornerFor(190f, 420f, 1500f, -1500f, w, h))
    }
}
