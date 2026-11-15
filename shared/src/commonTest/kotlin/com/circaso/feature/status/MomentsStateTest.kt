package com.circaso.feature.status

import com.circaso.feature.status.domain.Moment
import com.circaso.feature.status.domain.MomentKind
import com.circaso.feature.status.domain.MomentRing
import com.circaso.feature.status.domain.PendingMoment
import com.circaso.feature.status.domain.PendingState
import com.circaso.feature.status.presentation.MomentPalette
import com.circaso.feature.status.presentation.MyRing
import com.circaso.feature.status.presentation.MomentsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MomentsStateTest {
    private fun moment(id: String, viewed: Boolean, mine: Boolean = false) =
        Moment(momentId = id, posterId = "p", kind = MomentKind.Text, postedAt = 1, expiresAt = Long.MAX_VALUE, mine = mine, viewed = viewed, reacted = false)

    private fun pending(state: PendingState) = PendingMoment("l", MomentKind.Image, state, null, 0)

    @Test
    fun `my ring shows add seen posting waiting and failed`() {
        assertEquals(MyRing.Add, MomentsUiState().myRing)
        assertEquals(MyRing.Seen, MomentsUiState(myMoments = listOf(moment("m", true, mine = true))).myRing)
        assertEquals(MyRing.Posting, MomentsUiState(pending = listOf(pending(PendingState.Posting))).myRing)
        // Still being got ready after Post (a video made smaller): on its way, the same ring.
        assertEquals(MyRing.Posting, MomentsUiState(pending = listOf(pending(PendingState.Preparing))).myRing)
        assertEquals(MyRing.Waiting, MomentsUiState(pending = listOf(pending(PendingState.Preparing)), online = false).myRing)
        // One of a batch failed while others are still going: the arc stays (the failed one's card is under the row).
        assertEquals(MyRing.Posting, MomentsUiState(pending = listOf(pending(PendingState.Preparing), pending(PendingState.Failed))).myRing)
        assertEquals(MyRing.Failed, MomentsUiState(pending = listOf(pending(PendingState.Failed))).myRing)
        assertEquals(MyRing.Waiting, MomentsUiState(pending = listOf(pending(PendingState.Posting)), online = false).myRing)
        assertEquals(MyRing.Failed, MomentsUiState(pending = listOf(pending(PendingState.Waiting), pending(PendingState.Failed))).myRing)
    }

    @Test
    fun `skeletons only before the first answer and empty only after it`() {
        assertTrue(MomentsUiState().loading)
        assertFalse(MomentsUiState(refreshed = true).loading)
        assertFalse(MomentsUiState().empty)
        assertTrue(MomentsUiState(refreshed = true).empty)
        // Offline the first open is never a spinner forever.
        assertFalse(MomentsUiState(online = false).loading)
    }

    private fun ring(id: String, viewed: Boolean, at: Long, muted: Boolean = false) =
        MomentRing(id, "Person $id", null, listOf(moment("m-$id", viewed).copy(posterId = id, postedAt = at)), muted = muted)

    @Test
    fun `the Moments chip sorts people into Recent Viewed and Muted newest first`() {
        val s = MomentsUiState(
            rings = listOf(ring("old-new", false, 10), ring("seen", true, 30), ring("new", false, 50), ring("muted-new", false, 60, muted = true), ring("seen-old", true, 5)),
        )
        assertEquals(listOf("new", "old-new"), s.recent.map { it.posterId })
        assertEquals(listOf("seen", "seen-old"), s.viewed.map { it.posterId })
        // Muted people never count as recent, even with something new.
        assertEquals(listOf("muted-new"), s.mutedRings.map { it.posterId })
    }

    @Test
    fun `the chip's dot and the chat rings follow unseen moments of people not muted`() {
        assertFalse(MomentsUiState().hasUnseen)
        assertFalse(MomentsUiState(rings = listOf(ring("a", true, 1))).hasUnseen)
        assertFalse(MomentsUiState(rings = listOf(ring("m", false, 1, muted = true))).hasUnseen, "a muted person's moment is no dot")
        val s = MomentsUiState(rings = listOf(ring("a", false, 1), ring("b", true, 2), ring("m", false, 3, muted = true)))
        assertTrue(s.hasUnseen)
        assertEquals(setOf("a"), s.unseenPosters)
    }

    @Test
    fun `my moment row counts the people who saw it not every view`() {
        val mine = listOf(moment("1", true, mine = true).copy(viewCount = 4, postedAt = 100), moment("2", true, mine = true).copy(viewCount = 6, postedAt = 200))
        val s = MomentsUiState(myMoments = mine)
        assertEquals(6, s.myViews)
        assertEquals(200L, s.myLatestAt)
        assertEquals(0, MomentsUiState().myViews)
        assertEquals(null, MomentsUiState().myLatestAt)
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
