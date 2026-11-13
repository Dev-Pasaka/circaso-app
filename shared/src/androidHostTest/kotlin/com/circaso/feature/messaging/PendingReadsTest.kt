package com.circaso.feature.messaging

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.circaso.core.datastore.AppPreferences
import com.circaso.feature.messaging.data.repository.PendingReads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Read receipts that waited for the socket (a chat marked read from a notification with Circaso closed). */
class PendingReadsTest {
    private val reads = PendingReads(AppPreferences(MemoryPreferences()))

    private fun sentBy(reads: PendingReads, user: String, accept: (String) -> Boolean = { true }): Map<String, String> = runBlocking {
        val sent = mutableMapOf<String, String>()
        reads.flush(user) { chatId, upTo -> accept(chatId).also { if (it) sent[chatId] = upTo } }
        sent
    }

    @Test fun theNewestReceiptPerChatWaitsAndGoesOnce() = runBlocking {
        reads.put("me", "c1", "m1")
        reads.put("me", "c1", "m2")
        reads.put("me", "c2", "m9")
        assertEquals(mapOf("c1" to "m2", "c2" to "m9"), sentBy(reads, "me"))
        assertEquals(emptyMap(), sentBy(reads, "me"))
    }

    @Test fun aReceiptTheSocketDidNotTakeWaitsForTheNextConnection() = runBlocking {
        reads.put("me", "c1", "m1")
        reads.put("me", "c2", "m2")
        assertEquals(mapOf("c2" to "m2"), sentBy(reads, "me") { it == "c2" })
        assertEquals(mapOf("c1" to "m1"), sentBy(reads, "me"))
    }

    @Test fun aReceiptSentLiveReplacesTheWaitingOne() = runBlocking {
        reads.put("me", "c1", "m1")
        reads.remove("me", "c1")
        assertEquals(emptyMap(), sentBy(reads, "me"))
    }

    @Test fun eachPersonOnlySendsTheirOwn() = runBlocking {
        reads.put("me", "c1", "m1")
        assertEquals(emptyMap(), sentBy(reads, "someone-else"))
        assertEquals(mapOf("c1" to "m1"), sentBy(reads, "me"))
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }
}
