package com.circaso.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionBannerTextTest {
    @Test fun offlineWithNothingWaitingUsesTheShowingLine() =
        assertEquals("You’re offline · Showing chats as of 21:05", connectionBannerText("Showing chats as of 21:05", 0))

    @Test fun oneWaitingIsSingular() =
        assertEquals("You’re offline. 1 message waiting to send.", connectionBannerText("x", 1))

    @Test fun manyWaitingAreCounted() =
        assertEquals("You’re offline. 2 messages waiting to send.", connectionBannerText("x", 2))
}
