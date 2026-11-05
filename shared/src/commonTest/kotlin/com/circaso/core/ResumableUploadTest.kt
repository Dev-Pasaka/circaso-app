package com.circaso.core

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.platform.BackgroundUploads
import com.circaso.core.platform.MediaFiles
import com.circaso.core.platform.ResumableUpload
import com.circaso.core.platform.UploadKind
import com.circaso.core.platform.UploadSlot
import com.circaso.core.platform.UploadState
import com.circaso.core.sync.DrainResult
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxEntity
import com.circaso.core.sync.OutboxHandler
import com.circaso.core.sync.OutboxProcessor
import com.circaso.core.sync.OutboxState
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.FakeSignalCrypto
import com.circaso.testing.runSuspending
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryFiles : MediaFiles {
    val stored = mutableMapOf<String, ByteArray>()
    override suspend fun read(name: String) = stored[name]
    override suspend fun write(name: String, bytes: ByteArray) { stored[name] = bytes }
    override suspend fun clear() = stored.clear()
    override suspend fun delete(name: String) { stored.remove(name) }
}

/** The platform uploader: each PUT answers from [answers] (true = 2xx); [finishedUrls] were finished while "closed". */
private class ScriptedUploads(val answers: MutableList<Boolean> = mutableListOf()) : BackgroundUploads {
    val puts = mutableListOf<Pair<String, ByteArray>>()
    val finishedUrls = mutableSetOf<String>()
    override suspend fun <T> keep(kind: UploadKind, label: String?, work: suspend () -> T): T = work()
    override suspend fun put(url: String, headers: Map<String, String>, bytes: ByteArray, onProgress: (Long, Long) -> Unit): Boolean? {
        puts += url to bytes
        return answers.removeFirstOrNull() ?: true
    }
    override suspend fun finished(url: String) = url in finishedUrls
}

/**
 * The upload job's state machine (sealed → slot → put → completed), including a run cut off by the process dying:
 * the next run starts from the saved state, uploads the same ciphertext and completes the same slot.
 */
class ResumableUploadTest {
    private var now = 1_000_000L
    private val files = MemoryFiles()
    private val uploads = ScriptedUploads()
    private val upload = ResumableUpload(FakeSignalCrypto(), files, uploads, HttpClient(MockEngine { respondOk() })) { now }
    private val plain = ByteArray(1000) { (it % 251).toByte() }

    /** What the job's row holds between runs (a moment draft, a message's details). */
    private var saved: UploadState? = null
    private val savedSteps = mutableListOf<String>()
    private var slots = 0
    private val completed = mutableListOf<String>()
    private var completeAnswers = mutableListOf<AppResult<Unit>>()

    private suspend fun run(state: UploadState? = saved) = upload.run(
        plainFile = "plain", sealedFile = "sealed", state = state,
        save = { s -> saved = s; savedSteps += if (s.put) "put" else if (s.slotId != null) "slot" else "sealed" },
        create = { _ -> slots++; AppResult.Success(UploadSlot("m$slots", "https://r2/m$slots", mapOf("Content-Type" to "application/octet-stream"), now + 15 * 60_000)) },
        complete = { id -> completed += id; completeAnswers.removeFirstOrNull() ?: AppResult.Success(Unit) },
        startOver = { e -> (e as? AppError.Api)?.code == "status.media_invalid" },
        missing = AppError.Api("status.media_missing", 400, null, null, emptyMap()),
    )

    @Test fun aWholeRunSealsAsksForASlotPutsCompletesAndDropsTheCiphertext() = runSuspending {
        files.write("plain", plain)
        val done = assertIs<AppResult.Success<com.circaso.core.platform.UploadedFile>>(run()).value
        assertEquals("m1", done.id)
        assertEquals(listOf("sealed", "slot", "put"), savedSteps)
        assertEquals(listOf("m1"), completed)
        assertEquals(1, uploads.puts.size)
        assertContentEquals(plain.reversedArray(), uploads.puts.single().second) // the ciphertext, not the photo
        assertNull(files.read("sealed"), "the ciphertext is not kept once completed")
        assertNotNull(files.read("plain"), "the person's own copy stays")
    }

    @Test fun aRunCutOffMidPutResumesWithTheSameCiphertextAndSlot() = runSuspending {
        files.write("plain", plain)
        uploads.answers += false // the connection drops, or the process dies, during the PUT
        assertEquals(AppError.Network, assertIs<AppResult.Failure>(run()).error)
        val key = saved!!.key
        val digest = saved!!.digest
        assertFalse(saved!!.put)

        // The next run (after a restart) starts from what was saved.
        val done = assertIs<AppResult.Success<com.circaso.core.platform.UploadedFile>>(run()).value
        assertEquals(1, slots, "the slot is reused while its URL lasts")
        assertEquals(listOf("https://r2/m1", "https://r2/m1"), uploads.puts.map { it.first })
        assertContentEquals(uploads.puts[0].second, uploads.puts[1].second, "the same bytes go up again")
        assertEquals(key, kotlin.io.encoding.Base64.encode(done.key))
        assertEquals(digest, kotlin.io.encoding.Base64.encode(done.digest))
    }

    @Test fun aRunCutOffAfterThePutOnlyCompletes() = runSuspending {
        files.write("plain", plain)
        completeAnswers += AppResult.Failure(AppError.Network) // killed (or offline) before ST2 answered
        assertIs<AppResult.Failure>(run())
        assertTrue(saved!!.put)

        assertIs<AppResult.Success<*>>(run())
        assertEquals(1, uploads.puts.size, "no second upload")
        assertEquals(listOf("m1", "m1"), completed, "completing twice is safe (AT2/ST2 answer the same)")
    }

    @Test fun aPutThePlatformFinishedWhileCircasoWasClosedIsNotSentAgain() = runSuspending {
        files.write("plain", plain)
        uploads.answers += false // this run never heard the end (iOS: the background session finished it later)
        assertIs<AppResult.Failure>(run())
        uploads.finishedUrls += "https://r2/m1"

        assertIs<AppResult.Success<*>>(run())
        assertEquals(1, uploads.puts.size)
        assertEquals(listOf("m1"), completed)
    }

    @Test fun anExpiredUrlGetsAFreshSlotBeforeThePut() = runSuspending {
        files.write("plain", plain)
        uploads.answers += false
        assertIs<AppResult.Failure>(run())
        now += 20 * 60_000 // the 15-minute URL is gone

        assertIs<AppResult.Success<*>>(run())
        assertEquals(2, slots)
        assertEquals("https://r2/m2", uploads.puts.last().first)
        assertEquals(listOf("m2"), completed)
    }

    @Test fun aSlotTheServerNoLongerTakesStartsOverOnceThenGivesUp() = runSuspending {
        files.write("plain", plain)
        val refused = AppResult.Failure(AppError.Api("status.media_invalid", 400, null, null, emptyMap()))
        completeAnswers += refused
        assertIs<AppResult.Success<*>>(run())
        assertEquals(listOf("m1", "m2"), completed)

        files.write("plain", plain); saved = null; completed.clear()
        completeAnswers += listOf(refused, refused)
        assertEquals("status.media_invalid", (assertIs<AppResult.Failure>(run()).error as AppError.Api).code)
        assertEquals(2, completed.size, "only one fresh start per run")
    }

    @Test fun aMissingFileIsARefusalNotARetry() = runSuspending {
        val r = assertIs<AppResult.Failure>(run())
        assertEquals("status.media_missing", (r.error as AppError.Api).code)
        assertEquals(0, slots)
    }

    @Test fun aSavedStateWhoseCiphertextIsGoneSealsAgain() = runSuspending {
        files.write("plain", plain)
        val stale = UploadState("sealed", "a2V5", "ZGlnZXN0", 5, slotId = "old", url = "https://r2/old", put = true)
        assertIs<AppResult.Success<*>>(run(stale))
        assertEquals(listOf("m1"), completed, "a new slot for the new ciphertext")
    }
}

/** The outbox side of a run cut off by the process dying. */
class OutboxResumeTest {
    private val dao = FakeOutboxDao()
    private val outbox = Outbox(dao) { 1_000L }

    @Test fun aRowLeftSendingByADeadProcessIsSentAgainWithItsOwnKey() = runSuspending {
        val item = outbox.enqueue("t", "a")
        outbox.markSending(item.id) // the process died here, mid-upload
        val keys = mutableListOf<String>()
        val handler = object : OutboxHandler {
            override val type = "t"
            override suspend fun send(item: OutboxEntity): AppResult<Unit> { keys += item.idempotencyKey; return AppResult.Success(Unit) }
        }
        val result = OutboxProcessor(outbox, listOf(handler)) { 1_000L }.drain()
        assertEquals(DrainResult.Drained(1), result)
        assertEquals(listOf(item.idempotencyKey), keys, "the commit is retried with the key minted when it was queued")
        assertEquals(listOf(OutboxState.Done), dao.rows.value.map { it.state })
    }
}
