package com.circaso.feature.messaging

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.ChatPayload
import com.circaso.core.platform.CameraStart
import com.circaso.core.platform.CapturedMedia
import com.circaso.core.platform.LibraryAccess
import com.circaso.core.platform.LibraryItem
import com.circaso.core.platform.LibraryKind
import com.circaso.core.platform.LibraryRecents
import com.circaso.core.platform.MediaLibrary
import com.circaso.core.platform.MomentCamera
import com.circaso.core.platform.PickedFile
import com.circaso.feature.mediacompose.ComposerCapture
import com.circaso.feature.mediacompose.PreparedItem
import com.circaso.feature.mediacompose.fileKind
import com.circaso.feature.mediacompose.sizeLabel
import com.circaso.feature.messaging.data.repository.MediaDetails
import com.circaso.feature.messaging.data.repository.safeFileName
import com.circaso.feature.messaging.data.repository.toMediaDetails
import com.circaso.feature.messaging.data.repository.toPayload
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.GroupCreated
import com.circaso.feature.messaging.domain.GroupDraft
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.SecurityCode
import com.circaso.feature.messaging.presentation.ChatComposeStart
import com.circaso.feature.messaging.presentation.ChatMediaComposerViewModel
import com.circaso.feature.messaging.presentation.ChatMediaSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun photo(id: String) = LibraryItem(id, LibraryKind.Photo)
private fun video(id: String, ms: Long) = LibraryItem(id, LibraryKind.Video, durationMs = ms)
private fun jpeg(n: Int) = CapturedMedia(ByteArray(n), "image/jpeg", 10, 10, thumb = ByteArray(1))
private fun clip(path: String, ms: Long) = CapturedMedia(ByteArray(0), "video/mp4", 720, 1280, durationMs = ms, thumb = ByteArray(1), path = path)

private class ChatLibrary : MediaLibrary {
    val media = mutableMapOf<String, CapturedMedia>()
    val files = mutableMapOf<String, ByteArray>()
    var nextFile: PickedFile? = null
    val trims = mutableListOf<Triple<String, Long, Long>>()
    val trimLimits = mutableListOf<Long>()
    /** A file's size when it isn't in [files] (a big video stands in for its bytes). */
    val sizes = mutableMapOf<String, Long>()
    override suspend fun size(path: String): Long? = sizes[path] ?: files[path]?.size?.toLong()
    var opened = 0
    override suspend fun access() = LibraryAccess.Granted
    override suspend fun recents(kind: LibraryKind, limit: Int, ask: Boolean) = LibraryRecents(emptyList(), LibraryAccess.Granted)
    override suspend fun manage(kind: LibraryKind) = LibraryAccess.Granted
    override suspend fun load(item: LibraryItem) = media[item.id]
    override suspend fun pick(kind: LibraryKind): CapturedMedia? = null
    override suspend fun read(path: String) = files[path]
    override suspend fun filmstrip(path: String, count: Int) = List(count) { ByteArray(1) }
    override suspend fun trim(path: String, startMs: Long, endMs: Long, maxBytes: Long): CapturedMedia? {
        trims += Triple(path, startMs, endMs)
        trimLimits += maxBytes
        return clip("$path-cut", endMs - startMs).also { files[it.path!!] = ByteArray(7) }
    }
    override suspend fun pickFile(): PickedFile? { opened++; return nextFile.also { nextFile = null } }
}

private class Camera : MomentCamera {
    var opens = 0
    val limits = mutableListOf<com.circaso.core.platform.RecordingLimits>()
    var status: com.circaso.core.platform.RecordingStatus? = null
    var stops = 0
    override suspend fun open(): CameraStart { opens++; return CameraStart.Started }
    override suspend fun takePhoto(): CapturedMedia? = jpeg(4)
    override suspend fun startVideo() = true
    override suspend fun startVideo(limits: com.circaso.core.platform.RecordingLimits): Boolean { this.limits += limits; return true }
    override fun recordingStatus() = status
    override suspend fun stopVideo(): CapturedMedia? { stops++; return clip("/rec", 300_000) }
    override fun flip() = Unit
    override fun setFlash(on: Boolean) = Unit
    override fun close() = Unit
}

private class OneChat(title: String, kind: ChatKind) : ChatsRepository {
    private val chat = Chat("c1", kind, title, "https://x/a.jpg", emptyList(), false, 1, 1, MemberRole.Member, if (kind == ChatKind.Direct) "u2" else null)
    override val chats: Flow<List<ChatSummary>> = MutableStateFlow(listOf(ChatSummary(chat, null, 0)))
    override fun chat(chatId: String): Flow<Chat?> = flowOf(chat.takeIf { it.chatId == chatId })
    override suspend fun refresh() = AppResult.Success(Unit)
    override suspend fun refreshChat(chatId: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun openDirect(userId: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun createGroup(draft: GroupDraft, idempotencyKey: String): AppResult<GroupCreated> = AppResult.Failure(AppError.Network)
    override suspend fun rename(chatId: String, name: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun setMembersCanAdd(chatId: String, allowed: Boolean): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun addMembers(chatId: String, userIds: List<String>): AppResult<GroupCreated> = AppResult.Failure(AppError.Network)
    override suspend fun removeMember(chatId: String, userId: String) = AppResult.Success(Unit)
    override suspend fun leave(chatId: String) = AppResult.Success(Unit)
    override suspend fun setRole(chatId: String, userId: String, role: MemberRole): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun securityCode(userId: String): SecurityCode? = null
}

/** Records what reaches the chat's queue; [failFrom] makes the nth send (0-based) and later fail. */
private class Queue(var failFrom: Int = Int.MAX_VALUE) : ChatMediaSender {
    val sent = mutableListOf<Pair<String, PreparedItem>>()
    val albums = mutableListOf<String?>()
    override suspend fun send(chatId: String, item: PreparedItem, album: String?): Boolean {
        if (sent.size >= failFrom) return false
        sent += chatId to item
        albums += album
        return true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMediaComposerViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val library = ChatLibrary()
    private val camera = Camera()
    private val queue = Queue()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun composer(start: ChatComposeStart, kind: ChatKind = ChatKind.Direct) =
        ChatMediaComposerViewModel("c1", start, library, camera, OneChat("Wanjiru", kind), queue, now = { 0 })

    @Test fun theCameraOpensWithTheChatNamedBesideSend() = runTest(dispatcher) {
        val vm = composer(ChatComposeStart.Camera, ChatKind.Group)
        assertEquals(CameraStart.Started, vm.state.value.media.camera)
        assertEquals("Wanjiru", vm.state.value.title)
        assertTrue(vm.state.value.group)
        vm.composer.takePhoto()
        assertIs<ComposerCapture.Shot>(vm.state.value.media.capture)
        assertTrue(vm.state.value.canSend)
    }

    @Test fun severalFromTheGalleryGoInOrderEachWithItsOwnCaption() = runTest(dispatcher) {
        library.media += mapOf("a" to jpeg(3), "b" to clip("/v", 20_000), "c" to jpeg(5))
        library.files["/v"] = ByteArray(9)
        val vm = composer(ChatComposeStart.Gallery)
        // The gallery is the way in; the camera waits behind it.
        assertNotNull(vm.state.value.media.gallery)
        assertEquals(0, camera.opens)
        listOf("a", "b", "c").forEach { vm.composer.toggle(if (it == "b") video(it, 20_000) else photo(it)) }
        vm.composer.useSelection()
        assertEquals(3, vm.state.value.media.items.size)
        vm.composer.onCaption("first")
        vm.composer.select(2)
        vm.composer.onCaption("third")
        vm.send()
        assertTrue(vm.state.value.sent)
        assertEquals(listOf("c1", "c1", "c1"), queue.sent.map { it.first })
        val items = queue.sent.map { it.second as PreparedItem.Shot }
        assertEquals(listOf("image/jpeg", "video/mp4", "image/jpeg"), items.map { it.media.mime })
        assertEquals(listOf("first", "", "third"), items.map { it.caption })
        // The video goes from its file as it is: never read into memory here.
        assertEquals(0, items[1].bytes.size)
        assertEquals("/v", items[1].path)
        // Sent together: one album id, shared by all three, so the chat draws one album.
        assertNotNull(queue.albums.first())
        assertEquals(1, queue.albums.distinct().size)
    }

    @Test fun closingTheGalleryWithoutAPickStartsTheCamera() = runTest(dispatcher) {
        val vm = composer(ChatComposeStart.Gallery)
        vm.closeGallery()
        assertNull(vm.state.value.media.gallery)
        assertEquals(1, camera.opens)
    }

    @Test fun aLongVideoGoesWholeAndTheTrimmerIsOnlyAnOption() = runTest(dispatcher) {
        // Pasaka, 2026-11-07: trimming is for moments; a chat sends people's videos as they are.
        library.media["long"] = clip("/long", 600_000)
        library.sizes["/long"] = 90L * 1024 * 1024
        val vm = composer(ChatComposeStart.Camera)
        vm.composer.useRecent(video("long", 600_000))
        assertNull(vm.state.value.media.trim, "no trimmer forced open")
        assertTrue(vm.state.value.canSend)
        // The trimmer is still there if the person wants to cut: any length, never a minute's window.
        vm.composer.openTrim()
        val trim = assertNotNull(vm.state.value.media.trim)
        assertFalse(trim.required)
        assertEquals(0L to 600_000L, trim.startMs to trim.endMs)
        vm.composer.setTrim(30_000, 300_000)
        assertEquals(30_000L to 300_000L, vm.state.value.media.trim!!.let { it.startMs to it.endMs })
        vm.composer.cancelTrim()
        assertEquals(1, vm.state.value.media.items.size, "cancel keeps the whole video")
        vm.send()
        val sent = assertIs<PreparedItem.Shot>(queue.sent.single().second)
        assertEquals("/long", sent.path)
        assertTrue(library.trims.isEmpty(), "never re-encoded")
    }

    @Test fun aCutFromALongVideoIsMadeToTheChatsLimitNotAMoments() = runTest(dispatcher) {
        library.media["long"] = clip("/long", 600_000)
        library.sizes["/long"] = 90L * 1024 * 1024
        val vm = composer(ChatComposeStart.Camera)
        vm.composer.useRecent(video("long", 600_000))
        vm.composer.openTrim()
        vm.composer.setTrim(0, 240_000)
        vm.composer.applyTrim()
        assertEquals(listOf(Triple("/long", 0L, 240_000L)), library.trims)
        assertEquals(listOf(com.circaso.core.platform.ChatFileMaxBytes), library.trimLimits)
    }

    @Test fun aVideoOverTheLimitIsMarkedAndSendAsksBeforeLeavingItOut() = runTest(dispatcher) {
        library.media += mapOf("a" to jpeg(3), "big" to clip("/big", 900_000))
        library.sizes["/big"] = com.circaso.core.platform.ChatFileMaxBytes + 1
        val vm = composer(ChatComposeStart.Camera)
        vm.composer.toggle(photo("a")); vm.composer.toggle(video("big", 900_000))
        vm.composer.useSelection()
        // Put on the stage and marked on its item for as long as it is there (the note says why; no toast as well).
        assertNull(vm.state.value.media.error)
        assertEquals(1, vm.state.value.media.current)
        assertEquals(listOf(null, com.circaso.core.platform.ChatFileMaxBytes + 1), vm.state.value.media.items.map { it.overLimit })
        assertTrue(vm.state.value.canSend, "the photo can still go")
        vm.composer.errorShown()
        vm.send()
        // Asked first: nothing goes yet, nothing is re-encoded.
        assertEquals(1, vm.state.value.askLeaveOut)
        assertTrue(queue.sent.isEmpty())
        assertTrue(library.trims.isEmpty())
        // Go Back: still nothing sent, the marked one on the stage.
        vm.leaveOutCancelled()
        assertNull(vm.state.value.askLeaveOut)
        assertEquals(1, vm.state.value.media.current)
        assertTrue(queue.sent.isEmpty())
        // Send the other one: only the photo goes.
        vm.send()
        vm.leaveOutConfirmed()
        assertEquals(1, queue.sent.size)
        assertTrue(queue.sent.single().second is PreparedItem.Shot)
        assertTrue(vm.state.value.sent)
    }

    @Test fun severalOverTheLimitAreEachMarkedAndAllOverMeansNoSend() = runTest(dispatcher) {
        library.media += mapOf("b1" to clip("/b1", 900_000), "b2" to clip("/b2", 900_000))
        library.sizes["/b1"] = 150L * 1024 * 1024
        library.sizes["/b2"] = 120L * 1024 * 1024
        val vm = composer(ChatComposeStart.Camera)
        vm.composer.toggle(video("b1", 900_000)); vm.composer.toggle(video("b2", 900_000))
        vm.composer.useSelection()
        assertEquals(2, vm.state.value.media.overLimitCount)
        assertFalse(vm.state.value.canSend, "nothing left to send")
        vm.send()
        assertNull(vm.state.value.askLeaveOut)
        assertTrue(queue.sent.isEmpty())
    }

    @Test fun oneFoundOverTheLimitOnlyAtSendIsMarkedAndAskedAbout() = runTest(dispatcher) {
        library.media += mapOf("a" to jpeg(3), "v" to clip("/v", 60_000))
        library.sizes["/v"] = 10L * 1024 * 1024
        val vm = composer(ChatComposeStart.Camera)
        vm.composer.toggle(photo("a")); vm.composer.toggle(video("v", 60_000))
        vm.composer.useSelection()
        assertEquals(0, vm.state.value.media.overLimitCount)
        // The file grew past the limit after it was picked (or its size wasn't known then).
        library.sizes["/v"] = com.circaso.core.platform.ChatFileMaxBytes + 5
        vm.send()
        assertTrue(queue.sent.isEmpty())
        assertEquals(1, vm.state.value.askLeaveOut)
        assertEquals(1, vm.state.value.media.current)
        assertEquals(com.circaso.core.platform.ChatFileMaxBytes + 5, vm.state.value.media.items[1].overLimit)
        vm.leaveOutConfirmed()
        assertEquals(1, queue.sent.size)
    }

    @Test fun aChatsCameraRecordsAnyLengthUpToTheSizeLimit() = runTest(dispatcher) {
        val vm = composer(ChatComposeStart.Camera)
        vm.composer.holdStart()
        val limits = camera.limits.single()
        assertNull(limits.maxMs, "no length limit in a chat")
        val max = limits.maxBytes!!
        assertTrue(max < com.circaso.core.platform.ChatFileMaxBytes && max > com.circaso.core.platform.ChatFileMaxBytes * 9 / 10)
        // Its size shows as it records; the camera stopping at the limit puts the clip on the review and says why.
        camera.status = com.circaso.core.platform.RecordingStatus(max - 10, stopped = false)
        dispatcher.scheduler.advanceTimeBy(300)
        assertEquals(max - 10, vm.state.value.media.recordingBytes)
        camera.status = com.circaso.core.platform.RecordingStatus(max, stopped = true)
        dispatcher.scheduler.advanceTimeBy(300)
        assertFalse(vm.state.value.media.recordingVideo)
        assertEquals(1, camera.stops)
        assertEquals("The video stopped at 100 MB, the most a chat can send.", vm.state.value.media.error)
        vm.composer.holdEnd() // the finger lifting after it stopped changes nothing
        assertEquals(1, camera.stops)
    }

    @Test fun aVideoJustUnderTheLimitGoesAsItIs() = runTest(dispatcher) {
        library.media["v"] = clip("/v", 120_000)
        library.sizes["/v"] = com.circaso.core.platform.ChatFileMaxBytes
        val vm = composer(ChatComposeStart.Camera)
        vm.composer.useRecent(video("v", 120_000))
        assertNull(vm.state.value.media.error)
        vm.send()
        assertEquals("/v", (queue.sent.single().second as PreparedItem.Shot).path)
    }

    @Test fun aDocumentGoesUnderItsOwnNameWithItsCaption() = runTest(dispatcher) {
        library.files["/doc"] = ByteArray(12)
        library.nextFile = PickedFile("Lease.pdf", "application/pdf", 12, path = "/doc")
        val vm = composer(ChatComposeStart.Document)
        val doc = assertIs<ComposerCapture.Document>(vm.state.value.media.capture)
        assertEquals("Lease.pdf", doc.file.name)
        // No camera behind a document.
        assertEquals(0, camera.opens)
        vm.composer.onCaption("Signed")
        vm.send()
        val sent = assertIs<PreparedItem.Document>(queue.sent.single().second)
        assertEquals("Lease.pdf", sent.file.name)
        assertEquals("Signed", sent.caption)
        // From its file, as it is.
        assertEquals("/doc", sent.path)
        assertEquals(0, sent.bytes.size)
        assertNull(queue.albums.single())
    }

    @Test fun closingFilesWithNothingChosenClosesTheComposer() = runTest(dispatcher) {
        val vm = composer(ChatComposeStart.Document)
        assertTrue(vm.state.value.closed)
    }

    @Test fun aFileOverTheLimitIsRefusedBeforeTheReview() = runTest(dispatcher) {
        library.nextFile = PickedFile("Film.mov", "video/quicktime", 150L * 1024 * 1024, path = "/film")
        val vm = composer(ChatComposeStart.Document)
        assertTrue(vm.state.value.media.items.isEmpty())
        assertEquals("Documents up to 100 MB can be sent. This one is 150.0 MB.", vm.state.value.media.error)
    }

    @Test fun aDocumentOverTheOldLimitIsFine() = runTest(dispatcher) {
        library.sizes["/report"] = 60L * 1024 * 1024
        library.nextFile = PickedFile("Report.pdf", "application/pdf", 60L * 1024 * 1024, path = "/report")
        val vm = composer(ChatComposeStart.Document)
        assertNull(vm.state.value.media.error)
        vm.send()
        assertEquals("/report", (queue.sent.single().second as PreparedItem.Document).path)
    }

    @Test fun discardingADocumentGoesBackToTheChat() = runTest(dispatcher) {
        library.nextFile = PickedFile("a.txt", "text/plain", 1, bytes = ByteArray(1))
        val vm = composer(ChatComposeStart.Document)
        assertFalse(vm.state.value.closed)
        vm.discard()
        assertTrue(vm.state.value.closed)
    }

    @Test fun anotherDocumentJoinsTheReview() = runTest(dispatcher) {
        library.nextFile = PickedFile("a.txt", "text/plain", 1, bytes = ByteArray(1))
        val vm = composer(ChatComposeStart.Document)
        library.nextFile = PickedFile("b.txt", "text/plain", 2, bytes = ByteArray(2))
        vm.addDocument()
        assertEquals(listOf("a.txt", "b.txt"), vm.state.value.media.items.map { (it.capture as ComposerCapture.Document).file.name })
        assertEquals(1, vm.state.value.media.current)
    }

    @Test fun aFileGoneFromThePhoneStopsBeforeAnythingIsQueued() = runTest(dispatcher) {
        library.media["a"] = jpeg(3)
        library.nextFile = PickedFile("gone.pdf", "application/pdf", 5, path = "/missing")
        val vm = composer(ChatComposeStart.Document)
        vm.composer.useRecent(photo("a"))
        vm.send()
        assertTrue(queue.sent.isEmpty())
        assertFalse(vm.state.value.sending)
        assertEquals(0, vm.state.value.media.current)
        assertEquals("This document isn’t on the phone any more. Pick it again.", vm.state.value.media.error)
    }

    @Test fun nothingCanBeLetGoWhileItIsBeingSent() = runTest(dispatcher) {
        library.media["a"] = jpeg(3)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val held = ChatMediaSender { _, _, _ -> gate.await(); true }
        val vm = ChatMediaComposerViewModel("c1", ChatComposeStart.Camera, library, camera, OneChat("Wanjiru", ChatKind.Direct), held, now = { 0 })
        vm.composer.useRecent(photo("a"))
        vm.send()
        assertTrue(vm.state.value.sending)
        vm.discard()
        // Still on the review, still going: what was on screen when Send was tapped is what goes.
        assertEquals(1, vm.state.value.media.items.size)
        assertFalse(vm.state.value.closed)
        gate.complete(Unit)
        assertTrue(vm.state.value.sent)
    }

    @Test fun whatWasQueuedLeavesTheReviewWhenQueueingStopsPartWay() = runTest(dispatcher) {
        library.media += mapOf("a" to jpeg(3), "b" to jpeg(4))
        queue.failFrom = 1
        val vm = composer(ChatComposeStart.Camera)
        vm.composer.toggle(photo("a")); vm.composer.toggle(photo("b"))
        vm.composer.useSelection()
        vm.send()
        assertEquals(1, queue.sent.size)
        assertFalse(vm.state.value.sent)
        // Only the one that didn't go is left, so Send again never sends the first twice.
        assertEquals(1, vm.state.value.media.items.size)
        assertEquals(4, ((vm.state.value.media.items.single().capture as ComposerCapture.Shot).media.bytes.size))
        queue.failFrom = Int.MAX_VALUE
        vm.send()
        assertEquals(2, queue.sent.size)
        assertTrue(vm.state.value.sent)
    }
}

class ChatDocumentWireTest {
    @Test fun aDocumentTravelsAsAFileWithItsName() {
        val d = MediaDetails(MediaDetails.File, attachmentId = "a", key = "k", digest = "d", size = 10, mime = "application/pdf", label = "Lease.pdf", caption = "Signed", file = "local")
        val payload = d.toPayload("2026-11-07T10:00:00Z")
        assertEquals(ChatPayload.File, payload.type)
        assertEquals("Lease.pdf", payload.label)
        val back = assertNotNull(payload.toMediaDetails())
        assertEquals(MediaDetails.File, back.kind)
        assertEquals("Lease.pdf", back.label)
        assertEquals("Signed", back.caption)
        assertNull(back.file)
    }

    @Test fun aSendersFileNameIsMadeSafe() {
        assertEquals("passwd", safeFileName("../../etc/passwd"))
        assertEquals("evil.txt", safeFileName("C:\\Users\\evil.txt"))
        assertEquals("ab.pdf", safeFileName("a\u0000b.pdf"))
        assertEquals("File", safeFileName("   "))
        assertEquals("x".repeat(116) + ".pdf", safeFileName("y".repeat(10) + "x".repeat(116) + ".pdf"))
    }

    @Test fun sizesAndKindsReadAsPeopleSayThem() {
        assertEquals("2.4 MB", sizeLabel(2_480_000))
        assertEquals("47 KB", sizeLabel(48_200))
        assertEquals("900 bytes", sizeLabel(900))
        // Refused sizes never read as if they fit, and say the chat's own limit.
        val chat = com.circaso.core.platform.ChatFileMaxBytes
        assertEquals("just over 100 MB", com.circaso.feature.mediacompose.overLimitLabel(chat + 1, chat))
        assertEquals("150.0 MB", com.circaso.feature.mediacompose.overLimitLabel(150L * 1024 * 1024, chat))
        val big = com.circaso.feature.mediacompose.SharedFile("/in/f.mov", "f.mov", "video/quicktime", 150L * 1024 * 1024)
        assertEquals("f.mov is 150.0 MB. Up to 100 MB can be sent.", com.circaso.feature.mediacompose.checkShare(listOf(big), chat).notice)
        assertEquals("PDF", fileKind("Lease.pdf", "application/pdf"))
        assertEquals("XLSX", fileKind("Budget.xlsx", ""))
        assertEquals("PDF", fileKind("README", "application/pdf"))
        assertEquals("Document", fileKind("README", "application/octet-stream"))
    }
}
