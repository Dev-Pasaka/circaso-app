package com.circaso.feature.messaging

import com.circaso.core.common.AppResult
import com.circaso.core.crypto.AndroidSignalCrypto
import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.ChunkedFileCipher
import com.circaso.core.platform.ChatAttachmentMaxBytes
import com.circaso.core.platform.ChatFileMaxBytes
import com.circaso.core.platform.MediaFiles
import com.circaso.core.sync.Outbox
import com.circaso.feature.messaging.data.repository.AttachmentDownloadResponse
import com.circaso.feature.messaging.data.repository.AttachmentReadyResponse
import com.circaso.feature.messaging.data.repository.AttachmentsRemote
import com.circaso.feature.messaging.data.repository.MediaDetails
import com.circaso.feature.messaging.data.repository.MediaMessages
import com.circaso.feature.messaging.data.repository.NewAttachmentResponse
import com.circaso.feature.messaging.data.repository.toMediaDetails
import com.circaso.feature.messaging.data.repository.toPayload
import com.circaso.testing.FakeChatsDao
import com.circaso.testing.FakeMessagesDao
import com.circaso.testing.FakeOutboxDao
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Files by name in memory, plus "the phone's" own files to copy in from (a picked video's path). */
private class LargeFiles : MediaFiles {
    val stored = mutableMapOf<String, ByteArray>()
    val phone = mutableMapOf<String, ByteArray>()
    override suspend fun read(name: String) = stored[name]
    override suspend fun write(name: String, bytes: ByteArray) { stored[name] = bytes }
    override suspend fun clear() = stored.clear()
    override suspend fun delete(name: String) { stored.remove(name) }
    override suspend fun path(name: String) = if (name in stored) "mem:$name" else null
    override suspend fun copyIn(path: String, name: String): Boolean = phone[path]?.let { stored[name] = it.copyOf(); true } ?: false
}

/**
 * Chat files up to 100 MiB (Pasaka, 2026-11-07): sealed in 1 MiB chunks with real AES-GCM, streamed up, and fetched
 * back through a dropped download that resumes with `Range`, all without a format older apps would try to load.
 */
class LargeAttachmentTest {
    private val crypto = AndroidSignalCrypto()

    private suspend fun roundTrip(size: Int) {
        val files = LargeFiles()
        val plain = Random(size).nextBytes(size)
        files.write("plain", plain)
        val key = crypto.randomBytes(32)
        val sealed = assertNotNull(ChunkedFileCipher.seal(crypto, files, key, "plain", "sealed"))
        assertEquals(ChunkedFileCipher.sealedSize(size.toLong()), sealed, "size $size")
        assertEquals(sealed, files.read("sealed")!!.size.toLong())
        assertEquals(ChunkedFileCipher.OpenResult.Opened, ChunkedFileCipher.open(crypto, files, key, "sealed", "back"))
        assertContentEquals(plain, files.read("back"), "size $size")
    }

    @Test fun chunkedFilesOpenAtEverySizeEdge() = runBlocking {
        val c = ChunkedFileCipher.ChunkBytes
        for (size in listOf(0, 1, c - 1, c, c + 1, 3 * c + 5)) roundTrip(size)
    }

    @Test fun chunksSwappedDroppedOrChangedDontOpen() = runBlocking {
        val files = LargeFiles()
        val c = ChunkedFileCipher.ChunkBytes
        val box = c + ChunkedFileCipher.ChunkOverhead
        files.write("plain", Random(1).nextBytes(2 * c + 10))
        val key = crypto.randomBytes(32)
        ChunkedFileCipher.seal(crypto, files, key, "plain", "sealed")
        val good = files.read("sealed")!!
        fun damaged(bytes: ByteArray, k: ByteArray = key) = runBlocking {
            files.write("bad", bytes)
            val r = ChunkedFileCipher.open(crypto, files, k, "bad", "out")
            assertEquals(ChunkedFileCipher.OpenResult.Damaged, r)
            assertNull(files.read("out"), "nothing half-opened is kept")
        }
        // Swapped: the second chunk first.
        damaged(good.copyOfRange(box, 2 * box) + good.copyOfRange(0, box) + good.copyOfRange(2 * box, good.size))
        // Cut at a chunk's edge: the last chunk dropped (the one before isn't marked last).
        damaged(good.copyOfRange(0, 2 * box))
        // One byte changed.
        damaged(good.copyOf().also { it[box + 40] = (it[box + 40].toInt() xor 1).toByte() })
        // Another key.
        damaged(good, crypto.randomBytes(32))
    }

    @Test fun theLimitsAreOneNamedConstantAndAFileAtTheLimitSealsToIt() {
        assertEquals(100L * 1024 * 1024, ChatAttachmentMaxBytes)
        assertEquals(ChatAttachmentMaxBytes, ChunkedFileCipher.sealedSizeFor(ChatFileMaxBytes))
        assertTrue(ChunkedFileCipher.sealedSizeFor(ChatFileMaxBytes + 1) > ChatAttachmentMaxBytes)
        // Up to the old 32 MiB the one box every app opens; over it, chunks.
        assertTrue(ChunkedFileCipher.fitsLegacy(32L * 1024 * 1024 - 28))
        assertTrue(!ChunkedFileCipher.fitsLegacy(32L * 1024 * 1024 - 27))
    }

    @Test fun aChunkedFileTravelsWithoutTheKeysAnOlderAppWouldUse() {
        val d = MediaDetails(MediaDetails.Video, attachmentId = "a", key = "k", size = 40, mime = "video/mp4", chunked = true)
        val payload = d.toPayload("2026-11-07T10:00:00Z")
        assertEquals("k", payload.chunkedKey)
        // An older app needs mediaKey and digest to show a file; without them it shows "a message this phone can't
        // show" instead of downloading 100 MB into memory.
        assertNull(payload.mediaKey)
        assertNull(payload.digest)
        val back = assertNotNull(payload.toMediaDetails())
        assertEquals(true, back.chunked)
        assertEquals("k", back.key)
        // A small file keeps the format every app opens.
        val small = MediaDetails(MediaDetails.Video, attachmentId = "a", key = "k", digest = "d", size = 40).toPayload("t")
        assertEquals("k", small.mediaKey)
        assertEquals("d", small.digest)
        assertNull(small.chunkedKey)
        assertNull(small.toMediaDetails()!!.chunked)
    }

    @Test fun aLargeVideoGoesUpChunkedAndComesBackThroughADroppedDownload() = runBlocking {
        val size = 33 * 1024 * 1024 + 123 // just over the old limit: chunked
        val video = Random(7).nextBytes(size)
        val files = LargeFiles().apply { phone["/picked/v.mp4"] = video }
        var uploaded: ByteArray? = null
        val ranges = mutableListOf<String?>()
        val storage = HttpClient(MockEngine { request ->
            when (request.method) {
                HttpMethod.Put -> {
                    uploaded = request.body.toByteArray()
                    assertEquals(uploaded!!.size.toLong(), request.body.contentLength)
                    respond("", HttpStatusCode.OK)
                }
                else -> {
                    val range = request.headers[HttpHeaders.Range]
                    ranges += range
                    val blob = uploaded!!
                    if (range == null) {
                        // The connection drops part-way: only the first 5 MiB arrive.
                        respond(blob.copyOfRange(0, 5 * 1024 * 1024), HttpStatusCode.OK)
                    } else {
                        val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                        respond(blob.copyOfRange(from, blob.size), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes $from-${blob.size - 1}/${blob.size}"))
                    }
                }
            }
        })
        val attachments = object : AttachmentsRemote {
            var promised = 0L
            override suspend fun create(chatId: String, sizeBytes: Long, kind: String): AppResult<NewAttachmentResponse> {
                promised = sizeBytes
                return AppResult.Success(NewAttachmentResponse("att1", "https://r2/put", mapOf("Content-Type" to "application/octet-stream")))
            }
            override suspend fun complete(attachmentId: String) = AppResult.Success(AttachmentReadyResponse(attachmentId, promised))
            override suspend fun download(attachmentId: String) = AppResult.Success(AttachmentDownloadResponse("https://r2/get", sizeBytes = promised))
        }
        val chats = FakeChatsDao()
        val dao = FakeMessagesDao(chats)
        val media = MediaMessages(
            dao, chats, Outbox(FakeOutboxDao()) { 1L }, {}, crypto, files, attachments, storage, Json, { "me" }, { 1L },
        )
        assertIs<AppResult.Success<Unit>>(media.sendVideoFile("c1", "/picked/v.mp4", "video/mp4", 1920, 1080, 600_000, null, "Long one"))
        val row = dao.rows.value.single()
        assertIs<AppResult.Success<Unit>>(media.uploadPending(row.localId))
        val sent = assertNotNull(MediaDetails.read(dao.byLocalId(row.localId)!!.text))
        assertEquals(true, sent.chunked)
        assertNull(sent.digest)
        assertEquals(ChunkedFileCipher.sealedSize(size.toLong()), uploaded!!.size.toLong())
        assertEquals(attachments.promised, uploaded!!.size.toLong())
        // The sender's own copy was renamed under the attachment id, not copied, and the ciphertext is gone.
        assertContentEquals(video, files.read("att1"))
        assertNull(files.stored.keys.firstOrNull { it.startsWith("sealed-") || it.startsWith("out-") })

        // The other phone: what the message carries, and nothing on disk yet.
        val received = assertNotNull(sent.toPayload("2026-11-07T10:00:00Z").toMediaDetails())
        val other = LargeFiles()
        val theirs = MediaMessages(
            FakeMessagesDao(), FakeChatsDao(), Outbox(FakeOutboxDao()) { 1L }, {}, crypto, other, attachments, storage, Json, { "them" }, { 1L },
        )
        val (path, why) = theirs.file(received)
        assertNull(why)
        assertEquals("mem:att1", path)
        assertContentEquals(video, other.read("att1"))
        // The first try was cut off; the second asked only for the rest.
        assertEquals(listOf(null, "bytes=${5 * 1024 * 1024}-"), ranges)
        assertNull(other.stored.keys.firstOrNull { it.startsWith("dl-") || it.endsWith(".part") }, "nothing left behind")
    }

    @Test fun aDownloadOutlivesTheBubbleThatAskedForItAndFillsItsRing() = runBlocking {
        val size = 3 * 1024 * 1024 + 7
        val doc = Random(11).nextBytes(size)
        var uploaded: ByteArray? = null
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var gets = 0
        val storage = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Put) { uploaded = request.body.toByteArray(); respond("", HttpStatusCode.OK) }
            else { gets++; gate.await(); respond(uploaded!!, HttpStatusCode.OK) }
        })
        val attachments = object : AttachmentsRemote {
            var promised = 0L
            override suspend fun create(chatId: String, sizeBytes: Long, kind: String): AppResult<NewAttachmentResponse> {
                promised = sizeBytes
                return AppResult.Success(NewAttachmentResponse("att2", "https://r2/put"))
            }
            override suspend fun complete(attachmentId: String) = AppResult.Success(AttachmentReadyResponse(attachmentId, promised))
            override suspend fun download(attachmentId: String) = AppResult.Success(AttachmentDownloadResponse("https://r2/get", sizeBytes = promised))
        }
        val chats = FakeChatsDao()
        val dao = FakeMessagesDao(chats)
        val mine = MediaMessages(dao, chats, Outbox(FakeOutboxDao()) { 1L }, {}, crypto, LargeFiles(), attachments, storage, Json, { "me" }, { 1L })
        assertIs<AppResult.Success<Unit>>(mine.sendDocument("c1", doc, "application/pdf", "Report.pdf", null))
        val row = dao.rows.value.single()
        assertIs<AppResult.Success<Unit>>(mine.uploadPending(row.localId))
        val received = assertNotNull(MediaDetails.read(dao.byLocalId(row.localId)!!.text)!!.toPayload("t").toMediaDetails())

        // The other phone, its downloads in the app's scope.
        val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val other = LargeFiles()
        val theirs = MediaMessages(
            FakeMessagesDao(), FakeChatsDao(), Outbox(FakeOutboxDao()) { 1L }, {}, crypto, other, attachments, storage, Json, { "them" }, { 1L },
            downloadScope = appScope,
        )
        val seen = java.util.Collections.synchronizedList(mutableListOf<Float?>())
        val watch = launch(kotlinx.coroutines.Dispatchers.Unconfined) { theirs.downloading.collect { seen += it["att2"] } }
        // The bubble asks, then scrolls away before the bytes come.
        val bubble = launch(kotlinx.coroutines.Dispatchers.Default) { theirs.file(received) }
        kotlinx.coroutines.withTimeout(5_000) { while (theirs.downloading.value["att2"] == null) kotlinx.coroutines.delay(10) }
        bubble.cancel()
        bubble.join()
        gate.complete(Unit)
        // It carries on anyway, and the ring fills on the way.
        kotlinx.coroutines.withTimeout(10_000) { while (other.read("att2") == null || theirs.downloading.value.isNotEmpty()) kotlinx.coroutines.delay(10) }
        assertContentEquals(doc, other.read("att2"))
        assertEquals(0f, seen.filterNotNull().first())
        assertTrue(seen.filterNotNull().any { it > 0f && it < 1f }, "progress between: $seen")
        assertTrue(theirs.downloading.value.isEmpty(), "the ring goes once it's done")
        // Back on screen: the file is there, nothing is fetched again.
        val (path, why) = theirs.file(received)
        assertNull(why)
        assertEquals("mem:att2", path)
        assertEquals(1, gets)
        watch.cancel()
        appScope.cancel()
    }

    @Test fun twoAskingForOneFileShareOneDownload() = runBlocking {
        val size = 1024 * 1024
        val doc = Random(12).nextBytes(size)
        var uploaded: ByteArray? = null
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var links = 0
        val storage = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Put) { uploaded = request.body.toByteArray(); respond("", HttpStatusCode.OK) }
            else { gate.await(); respond(uploaded!!, HttpStatusCode.OK) }
        })
        val attachments = object : AttachmentsRemote {
            var promised = 0L
            override suspend fun create(chatId: String, sizeBytes: Long, kind: String): AppResult<NewAttachmentResponse> { promised = sizeBytes; return AppResult.Success(NewAttachmentResponse("att3", "https://r2/put")) }
            override suspend fun complete(attachmentId: String) = AppResult.Success(AttachmentReadyResponse(attachmentId, promised))
            override suspend fun download(attachmentId: String): AppResult<AttachmentDownloadResponse> { links++; return AppResult.Success(AttachmentDownloadResponse("https://r2/get", sizeBytes = promised)) }
        }
        val chats = FakeChatsDao()
        val dao = FakeMessagesDao(chats)
        val mine = MediaMessages(dao, chats, Outbox(FakeOutboxDao()) { 1L }, {}, crypto, LargeFiles(), attachments, storage, Json, { "me" }, { 1L })
        mine.sendDocument("c1", doc, "application/pdf", "A.pdf", null)
        mine.uploadPending(dao.rows.value.single().localId)
        val received = assertNotNull(MediaDetails.read(dao.rows.value.single().text)!!.toPayload("t").toMediaDetails())
        val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val other = LargeFiles()
        val theirs = MediaMessages(FakeMessagesDao(), FakeChatsDao(), Outbox(FakeOutboxDao()) { 1L }, {}, crypto, other, attachments, storage, Json, { "them" }, { 1L }, downloadScope = appScope)
        val a = async(kotlinx.coroutines.Dispatchers.Default) { theirs.file(received) }
        val b = async(kotlinx.coroutines.Dispatchers.Default) { theirs.file(received) }
        kotlinx.coroutines.delay(200)
        gate.complete(Unit)
        assertEquals("mem:att3", a.await().first)
        assertEquals("mem:att3", b.await().first)
        assertEquals(1, links, "one download, joined by the second")
        appScope.cancel()
    }

    @Test fun aFileOverTheLimitIsNotQueued() = runBlocking {
        val files = LargeFiles().apply { phone["/big"] = ByteArray((ChatFileMaxBytes + 1).toInt()) }
        val chats = FakeChatsDao()
        val dao = FakeMessagesDao(chats)
        val media = MediaMessages(
            dao, chats, Outbox(FakeOutboxDao()) { 1L }, {}, crypto, files, object : AttachmentsRemote {
                override suspend fun create(chatId: String, sizeBytes: Long, kind: String): AppResult<NewAttachmentResponse> = error("not reached")
                override suspend fun complete(attachmentId: String): AppResult<AttachmentReadyResponse> = error("not reached")
                override suspend fun download(attachmentId: String): AppResult<AttachmentDownloadResponse> = error("not reached")
            }, HttpClient(MockEngine { error("not reached") }), Json, { "me" }, { 1L },
        )
        val r = media.sendDocumentFile("c1", "/big", "application/zip", "Archive.zip", null)
        assertIs<AppResult.Failure>(r)
        assertTrue(dao.rows.value.isEmpty())
        assertTrue(files.stored.isEmpty(), "the copy is let go")
        // Payload for the chunk format keeps ChatPayload's other fields untouched.
        assertNull(ChatPayload(type = ChatPayload.Video).chunkedKey)
    }
}
