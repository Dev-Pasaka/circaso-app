package com.circaso.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TabStacksTest {
    private val chats = 0
    private val you = 2

    @Test
    fun movingToAnotherTabTakesTheOldTabsScreensOffSoBackNeverShowsThem() {
        val stack = mutableListOf("home", "security", "security-log")
        val tabs = TabStacks<String>()
        tabs.switch(stack, "home", from = you, to = chats, keep = true)
        assertEquals(listOf("home"), stack)
        // Back from Chats now has nothing of You's to go to.
        stack.removeLastOrNull().let { assertEquals("home", it) }
    }

    @Test
    fun eachTabGetsBackTheScreensItHadOpenOnTwoPanes() {
        val stack = mutableListOf("home", "security", "security-log")
        val tabs = TabStacks<String>()
        tabs.switch(stack, "home", from = you, to = chats, keep = true)
        stack.add("chat-mum")
        tabs.switch(stack, "home", from = chats, to = you, keep = true)
        assertEquals(listOf("home", "security", "security-log"), stack)
        tabs.switch(stack, "home", from = you, to = chats, keep = true)
        assertEquals(listOf("home", "chat-mum"), stack)
    }

    @Test
    fun onOnePaneATabTapNeverPushesScreensAndNothingIsKept() {
        val stack = mutableListOf("home", "security")
        val tabs = TabStacks<String>()
        tabs.switch(stack, "home", from = you, to = chats, keep = false)
        assertEquals(listOf("home"), stack)
        tabs.switch(stack, "home", from = chats, to = you, keep = false)
        assertEquals(listOf("home"), stack)
        assertTrue(tabs.keptFor(you).isEmpty())
    }

    @Test
    fun whenHomeLeavesTheStackNoTabsScreensComeBack() {
        val stack = mutableListOf("home", "security")
        val tabs = TabStacks<String>()
        tabs.switch(stack, "home", from = you, to = chats, keep = true)
        // Signed out: the stack is replaced and Home is gone.
        stack.clear(); stack.add("welcome")
        tabs.clear()
        stack.clear(); stack.add("home")
        tabs.switch(stack, "home", from = chats, to = you, keep = true)
        assertEquals(listOf("home"), stack)
    }

    @Test
    fun aParentPoppedTakesItsChildrenWithIt() {
        // Security opened Security log; popping back past Security leaves nothing of either to restore later.
        val stack = mutableListOf("home", "security", "security-log")
        stack.removeAt(stack.lastIndex); stack.removeAt(stack.lastIndex)
        val tabs = TabStacks<String>()
        tabs.switch(stack, "home", from = you, to = chats, keep = true)
        tabs.switch(stack, "home", from = chats, to = you, keep = true)
        assertEquals(listOf("home"), stack)
    }

    @Test
    fun aNotificationOpeningATabFreshDropsWhatThatTabHadOpen() {
        val stack = mutableListOf("home", "security")
        val tabs = TabStacks<String>()
        tabs.switch(stack, "home", from = you, to = chats, keep = true)
        tabs.forget(you)
        tabs.switch(stack, "home", from = chats, to = you, keep = true)
        assertEquals(listOf("home"), stack)
    }
}
