package com.circaso.core

import androidx.room.Room
import com.circaso.core.database.CircasoDatabase
import com.circaso.core.database.buildDatabase
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.datastore.createPreferencesDataStore
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxState
import com.circaso.core.sync.OutboxType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DataLayerTest {
    private fun outbox(): Outbox {
        val db = buildDatabase(Room.inMemoryDatabaseBuilder<CircasoDatabase>())
        return Outbox(db.outboxDao())
    }

    @Test
    fun queuedMessagesAreCountedAndKeepOrder() = runBlocking {
        val outbox = outbox()
        assertEquals(0, outbox.waitingMessages.first())
        val a = outbox.enqueue(OutboxType.ChatMessage, "first")
        val b = outbox.enqueue(OutboxType.ChatMessage, "second")
        assertEquals(2, outbox.waitingMessages.first())
        assertEquals(a.id, outbox.next()?.id)
        outbox.markDone(a.id)
        assertEquals(b.id, outbox.next()?.id)
        assertEquals(1, outbox.waitingMessages.first())
    }

    @Test
    fun everyQueuedActionGetsItsOwnKeyAndKeepsItOnRetry() = runBlocking {
        val outbox = outbox()
        val a = outbox.enqueue(OutboxType.ChatMessage, "a")
        val b = outbox.enqueue(OutboxType.ChatMessage, "b")
        assertNotEquals(a.idempotencyKey, b.idempotencyKey)
        outbox.markSending(a.id)
        outbox.requeueInterrupted()
        val again = outbox.next()
        assertEquals(a.id, again?.id)
        assertEquals(a.idempotencyKey, again?.idempotencyKey)
        assertEquals(1, again?.attempts)
    }

    @Test
    fun failedItemsStopCountingAsWaitingAndKeepTheirError() = runBlocking {
        val outbox = outbox()
        val a = outbox.enqueue(OutboxType.ChatMessage, "a")
        outbox.markFailed(a.id, "users.version_conflict")
        assertEquals(0, outbox.waitingMessages.first())
        assertNull(outbox.next())
    }

    @Test
    fun otherTypesDoNotCountAsMessages() = runBlocking {
        val outbox = outbox()
        outbox.enqueue("profile.update", "{}")
        assertEquals(0, outbox.waitingMessages.first())
    }

    @Test
    fun discardAllEmptiesTheQueue() = runBlocking {
        val outbox = outbox()
        outbox.enqueue(OutboxType.ChatMessage, "a")
        outbox.discardAll()
        assertEquals(0, outbox.waitingMessages.first())
    }

    @Test
    fun themeOverridesPersistAndClear() = runBlocking {
        val path = NSTemporaryDirectory() + "prefs-" + NSUUID().UUIDString + ".preferences_pb"
        val prefs = AppPreferences(createPreferencesDataStore(path))
        assertNull(prefs.themeOverrides.first().dark)
        prefs.setDark(true)
        prefs.setHighContrast(false)
        val saved = prefs.themeOverrides.first()
        assertEquals(true, saved.dark)
        assertEquals(false, saved.highContrast)
        prefs.setDark(null)
        assertNull(prefs.themeOverrides.first().dark)
    }
}
