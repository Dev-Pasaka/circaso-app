package com.circaso.feature.status

import com.circaso.feature.status.domain.Moment
import com.circaso.feature.status.domain.MomentKind
import com.circaso.feature.status.domain.MomentRing
import com.circaso.feature.status.domain.PendingMoment
import com.circaso.feature.status.domain.PendingState
import com.circaso.feature.status.presentation.MomentPalette
import com.circaso.feature.status.presentation.MyRing
import com.circaso.feature.status.presentation.SpacesUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MomentsStateTest {
    private fun moment(id: String, viewed: Boolean, mine: Boolean = false) =
        Moment(momentId = id, posterId = "p", kind = MomentKind.Text, postedAt = 1, expiresAt = Long.MAX_VALUE, mine = mine, viewed = viewed, reacted = false)

    private fun pending(state: PendingState) = PendingMoment("l", MomentKind.Image, state, null, 0)

    @Test
    fun `my ring shows add, seen, posting, waiting and failed`() {
        assertEquals(MyRing.Add, SpacesUiState().myRing)
        assertEquals(MyRing.Seen, SpacesUiState(myMoments = listOf(moment("m", true, mine = true))).myRing)
        assertEquals(MyRing.Posting, SpacesUiState(pending = listOf(pending(PendingState.Posting))).myRing)
        assertEquals(MyRing.Waiting, SpacesUiState(pending = listOf(pending(PendingState.Posting)), online = false).myRing)
        assertEquals(MyRing.Failed, SpacesUiState(pending = listOf(pending(PendingState.Waiting), pending(PendingState.Failed))).myRing)
    }

    @Test
    fun `skeletons only before the first answer, and empty only after it`() {
        assertTrue(SpacesUiState().loading)
        assertFalse(SpacesUiState(refreshed = true).loading)
        assertFalse(SpacesUiState().empty)
        assertTrue(SpacesUiState(refreshed = true).empty)
        // Offline the first open is never a spinner forever.
        assertFalse(SpacesUiState(online = false).loading)
    }

    @Test
    fun `a ring is new until every moment in it is seen`() {
        val ring = MomentRing("p", "Wanjiru", null, listOf(moment("a", true), moment("b", false)), muted = false)
        assertTrue(ring.unseen)
        assertFalse(ring.copy(moments = listOf(moment("a", true))).unseen)
    }

    @Test
    fun `older moments keep their background and unknown ones fall back to Ink`() {
        assertEquals("Metal", MomentPalette.of("Metal").id)
        assertEquals("Ink", MomentPalette.of("INK").id)
        assertEquals("Ink", MomentPalette.of("from-a-newer-phone").id)
        assertEquals("Ink", MomentPalette.of(null).id)
        assertTrue(MomentPalette.entries.size >= 30)
        assertEquals(MomentPalette.entries.size, MomentPalette.entries.map { it.id }.toSet().size)
    }
}
