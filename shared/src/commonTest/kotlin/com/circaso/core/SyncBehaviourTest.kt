package com.circaso.core

import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.DrainResult
import com.circaso.core.sync.FailedChangeText
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxProcessor
import com.circaso.core.sync.OutboxState
import com.circaso.core.sync.ReconnectCoordinator
import com.circaso.core.sync.SyncAnnouncements
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.runSuspending
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReconnectCoordinatorTest {
    private val announced = mutableListOf<Int>()
    private var next: DrainResult = DrainResult.Drained(0)
    private val announcements = SyncAnnouncements()
    private val coordinator = ReconnectCoordinator({ next }, announcements)

    // SyncAnnouncements is a flow; collect through a tiny listener by reading what the coordinator would have emitted.
    private suspend fun status(s: ConnectionStatus): List<Int> {
        val seen = mutableListOf<Int>()
        kotlinx.coroutines.coroutineScope {
            val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { announcements.sentAfterOffline.collect { seen += it } }
            coordinator.onStatus(s)
            job.cancel()
        }
        return seen
    }

    @Test fun startingOnlineWithNothingWaitingSaysNothing() = runSuspending {
        assertEquals(emptyList(), status(ConnectionStatus.Online))
    }

    @Test fun comingBackOnlineAfterBeingOfflineAnnouncesWhatWasSent() = runSuspending {
        status(ConnectionStatus.Offline)
        next = DrainResult.Drained(2)
        assertEquals(listOf(2), status(ConnectionStatus.Online))
    }

    @Test fun theAnnouncementHappensOnlyOnce() = runSuspending {
        status(ConnectionStatus.Offline)
        next = DrainResult.Drained(2)
        status(ConnectionStatus.Online)
        assertEquals(emptyList(), status(ConnectionStatus.Online)) // already online: nothing new to say
    }

    @Test fun nothingWaitingMeansNoToastEvenAfterBeingOffline() = runSuspending {
        status(ConnectionStatus.Offline)
        next = DrainResult.Drained(0)
        assertEquals(emptyList(), status(ConnectionStatus.Online))
    }

    @Test fun aPartialSendStillReportsWhatWentOut() = runSuspending {
        status(ConnectionStatus.Offline)
        next = DrainResult.Paused(1, "network")
        assertEquals(listOf(1), status(ConnectionStatus.Online))
    }

    @Test fun sendingWhileAlwaysOnlineIsQuiet() = runSuspending {
        next = DrainResult.Drained(3)
        assertEquals(emptyList(), status(ConnectionStatus.Online)) // the phone never lost its connection
    }
}

class FailedChangesTest {
    private val dao = FakeOutboxDao()
    private val outbox = Outbox(dao, owner = { "alice" }) { 1_000L }

    @Test fun onlyTheSignedInPersonsFailedChangesAreListed() = runSuspending {
        val mine = outbox.enqueue("profile.update", "{}")
        outbox.markFailed(mine.id, "users.version_conflict")
        val others = Outbox(dao, owner = { "bob" }) { 1_000L }.enqueue("contact.remove", "x")
        dao.update(others.id, OutboxState.Failed, 0, "x")
        assertEquals(listOf(mine.id), outbox.failed().first().map { it.id })
    }

    @Test fun retryPutsItBackInLineWithItsOriginalKeyAndNoError() = runSuspending {
        val item = outbox.enqueue("profile.update", "{}")
        outbox.markFailed(item.id, "users.version_conflict")
        outbox.retry(item.id)
        val again = outbox.next()!!
        assertEquals(item.idempotencyKey, again.idempotencyKey)
        assertNull(again.lastErrorCode)
        assertEquals(emptyList(), outbox.failed().first())
    }

    @Test fun discardRemovesItForGood() = runSuspending {
        val item = outbox.enqueue("block.set", "u1")
        outbox.markFailed(item.id, "users.cannot_block_self")
        outbox.discard(item.id)
        assertEquals(emptyList(), dao.rows.value)
    }

    @Test fun retryDoesNothingToAnItemThatIsNotFailed() = runSuspending {
        val item = outbox.enqueue("block.set", "u1")
        outbox.retry(item.id)
        assertEquals(OutboxState.Pending, dao.rows.value.single().state)
    }

    // ---- words

    @Test fun titlesAreHumanAndUnknownTypesStayPolite() {
        assertEquals("Profile change", FailedChangeText.title("profile.update"))
        assertEquals("Blocking someone", FailedChangeText.title("block.set"))
        assertEquals("A change", FailedChangeText.title("something.new"))
    }

    @Test fun reasonsComeFromTheErrorCodeNeverRaw() {
        assertEquals("It waited too long to send, so it needs your OK before it goes.", FailedChangeText.reason(OutboxProcessor.Stale))
        assertEquals("Your profile changed on another phone. We've loaded the latest, so check and save again.", FailedChangeText.reason("users.version_conflict"))
        assertEquals("It couldn't be sent.", FailedChangeText.reason(null))
        assertEquals("Something went wrong on our side. Please try again.", FailedChangeText.reason("brand.new.code"))
    }
}
