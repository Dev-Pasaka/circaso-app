package com.circaso.core.navigation

import androidx.navigation3.runtime.NavEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PaneRoutingTest {
    private fun entry(key: String, metadata: Map<String, Any> = emptyMap()) = NavEntry(key, metadata = metadata) {}

    private val home = entry("home", Panes.List)

    @Test
    fun aScreenOpenedFromTheListGoesToTheDetailSideNeverTheListPane() {
        val chat = entry("chat")
        val split = splitPanes(listOf(home, chat), twoPane = true)!!
        assertEquals(0, split.listIndex)
        assertSame(chat, split.detail)
        assertEquals(1, split.depth)
    }

    @Test
    fun deeperScreensStackInTheDetailPaneWithTheOneUnderneathKnown() {
        val chat = entry("chat")
        val info = entry("info")
        val split = splitPanes(listOf(home, chat, info), twoPane = true)!!
        assertSame(info, split.detail)
        assertSame(chat, split.underneath)
        assertEquals(2, split.depth)
    }

    @Test
    fun aFullScreenFlowFromTheListTakesTheWholeWindow() {
        val moment = entry("moment", Panes.FullScreen)
        assertNull(splitPanes(listOf(home, moment), twoPane = true))
        // ...and anything opened from it stays full screen too.
        assertNull(splitPanes(listOf(home, moment, entry("privacy")), twoPane = true))
    }

    @Test
    fun theListAloneShowsTheEmptyDetail() {
        val split = splitPanes(listOf(home), twoPane = true)!!
        assertNull(split.detail)
        assertEquals(0, split.depth)
    }

    @Test
    fun aNarrowWindowOrAStackWithoutTheListIsOneScreenAtATime() {
        assertNull(splitPanes(listOf(home, entry("chat")), twoPane = false))
        assertNull(splitPanes(listOf(entry("welcome"), entry("phone")), twoPane = true))
    }
}
