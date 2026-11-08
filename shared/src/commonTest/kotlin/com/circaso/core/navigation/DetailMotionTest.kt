package com.circaso.core.navigation

import com.circaso.designsystem.adaptive.WindowLayout
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailMotionTest {
    @Test
    fun `opening going deeper swapping and backing out read as push swap and pop`() {
        val motion = DetailMotion()
        assertEquals(DetailChange.Swap, motion.next(0).kind) // first frame: nothing to animate from
        assertEquals(DetailChange.Push, motion.next(1).kind) // a chat opens beside the list
        assertEquals(DetailChange.Swap, motion.next(1).kind) // another chat picked from the list
        assertEquals(DetailChange.Push, motion.next(2).kind) // chat info on top
        assertEquals(DetailChange.Pop, motion.next(1).kind)
        assertEquals(DetailChange.Pop, motion.next(0).kind)
    }

    @Test
    fun `each pop draws below the screen it uncovers however many in a row`() {
        val motion = DetailMotion()
        motion.next(0)
        val a = motion.next(1)
        val b = motion.next(2)
        val c = motion.next(3)
        val back1 = motion.next(2)
        val back2 = motion.next(1)
        assertTrue(a.z < b.z && b.z < c.z)
        assertTrue(back1.z < c.z, "the screen uncovered sits under the one leaving")
        assertTrue(back2.z < back1.z, "and again on the second pop in a row")
    }

    @Test
    fun `split view needs an expanded not short window`() {
        assertTrue(WindowLayout(1180.dp, 820.dp).twoPane) // 11" iPad landscape
        assertFalse(WindowLayout(820.dp, 1180.dp).twoPane) // 11" iPad portrait: detail would be phone-narrow
        assertFalse(WindowLayout(915.dp, 412.dp).twoPane) // phone on its side
        assertTrue(WindowLayout(915.dp, 412.dp).short)
    }

    @Test
    fun `changing tab swaps the detail in place whatever the depth`() {
        val motion = DetailMotion()
        motion.next(0, tabEpoch = 0)
        assertEquals(DetailChange.Push, motion.next(2, tabEpoch = 0).kind) // You: Security, then its log
        assertEquals(DetailChange.Cut, motion.next(0, tabEpoch = 1).kind) // to Chats, nothing open: no slide back
        assertEquals(DetailChange.Cut, motion.next(1, tabEpoch = 2).kind) // back to a tab with a chat: no push
        assertEquals(DetailChange.Push, motion.next(2, tabEpoch = 2).kind) // then ordinary navigation again
    }
}
