package com.circaso.feature.messaging.presentation

import com.circaso.feature.messaging.domain.Presence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LastSeenTest {
    private val now = 1_761_900_000_000L // a fixed moment
    private val hour = 3_600_000L

    @Test
    fun `last seen reads today yesterday a weekday then a date`() {
        assertTrue(lastSeenText(now - 10 * 60_000, now).startsWith("last seen today at") || lastSeenText(now - 10 * 60_000, now).startsWith("last seen yesterday at"))
        assertTrue(lastSeenText(now - 30 * hour, now).let { it.startsWith("last seen yesterday at") || it.startsWith("last seen ") })
        assertTrue(lastSeenText(now - 30 * 24 * hour, now).startsWith("last seen ") && !lastSeenText(now - 30 * 24 * hour, now).contains(" at "))
    }

    @Test
    fun `the header never claims online without the server saying so`() {
        assertEquals("", ThreadUiState().subtitle)
        assertEquals("Online", ThreadUiState(presence = Presence(online = true, lastSeenAt = null)).subtitle)
        assertTrue(ThreadUiState(presence = Presence(online = false, lastSeenAt = now - hour)).subtitle.startsWith("last seen"))
    }
}
