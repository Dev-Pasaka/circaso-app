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
import com.circaso.core.platform.MomentMediaMaxBytes
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
    var opened = 0
    override suspend fun access() = LibraryAccess.Granted
    override suspend fun recents(kind: LibraryKind, limit: Int, ask: Boolean) = LibraryRecents(emptyList(), LibraryAccess.Granted)
    override suspend fun manage(kind: LibraryKind) = LibraryAccess.Granted
    override suspend fun load(item: LibraryItem) = media[item.id]
    override suspend fun pick(kind: LibraryKind): CapturedMedia? = null
    override suspend fun read(path: String) = files[path]
    override suspend fun filmstrip(path: String, count: Int) = List(count) { ByteArray(1) }
    override suspend fun trim(path: String, startMs: Long, endMs: Long): CapturedMedia? {
        trims += Triple(path, startMs, endMs)
        return clip("$path-cut", endMs - startMs).also { files[it.path!!] = ByteArray(7) }
    }
    override suspend fun pickFile(): PickedFile? { opened++; return nextFile.also { nextFile = null } }
}

private class Camera : MomentCamera {
    var opens = 0
    override suspend fun open(): CameraStart { opens++; return CameraStart.Started }
    override suspend fun takePhoto(): CapturedMedia? = jpeg(4)
    override suspend fun startVideo() = true
    override suspend fun stopVideo(): CapturedMedia? = null
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
    override suspend fun send(chatId: String, item: PreparedItem): Boolean {
        if (sent.size >= failFrom) return false
        sent += chatId to item
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
        // The video's bytes are read from its file only when it is sent.
        assertEquals(9, items[1].bytes.size)
    }

    @Test fun closingTheGalleryWithoutAPickStartsTheCamera() = runTest(dispatcher) {
        val vm = composer(ChatComposeStart.Gallery)
        vm.closeGallery()
        assertNull(vm.state.value.media.gallery)
        assertEquals(1, camera.opens)
    }

    @Test fun aVideoOverAMinuteMustBeTrimmedBeforeItCanGo() = runTest(dispatcher) {
        library.media["long"] = clip("/long", 134_000)
        val vm = composer(ChatComposeStart.Camera)
        vm.composer.useRecent(video("long", 134_000))
        val trim = assertNotNull(vm.state.value.media.trim)
        assertTrue(trim.required)
        assertFalse(vm.state.value.canSend)
        vm.composer.applyTrim()
        assertNull(vm.state.value.media.trim)
        assertEquals(listOf(Triple("/long", 0L, 60_000L)), library.trims)
        assertTrue(vm.state.value.canSend)
    }

    @Test fun aVideoTooLargeToSendIsMadeSmallerWithTheMomentsOwnStep() = runTest(dispatcher) {
        library.media["big"] = clip("/big", 50_000)
        library.files["/big"] = ByteArray((MomentMediaMaxBytes + 1).toInt())
        val vm = composer(ChatComposeStart.Camera)
        vm.composer.useRecent(video("big", 50_000))
        vm.send()
        // The same re-encode a moment uses: the whole clip through the library's trim.
        assertEquals(listOf(Triple("/big", 0L, 50_000L)), library.trims)
        assertEquals(7, (queue.sent.single().second as PreparedItem.Shot).bytes.size)
        assertTrue(vm.state.value.sent)
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
        assertEquals(12, sent.bytes.size)
    }

    @Test fun closingFilesWithNothingChosenClosesTheComposer() = runTest(dispatcher) {
        val vm = composer(ChatComposeStart.Document)
        assertTrue(vm.state.value.closed)
    }

    @Test fun aFileOverTheLimitIsRefusedBeforeTheReview() = runTest(dispatcher) {
        library.nextFile = PickedFile("Film.mov", "video/quicktime", MomentMediaMaxBytes + 10, path = "/film")
        val vm = composer(ChatComposeStart.Document)
        assertTrue(vm.state.value.media.items.isEmpty())
        assertEquals("Documents up to 32 MB can be sent. This one is 32.0 MB.", vm.state.value.media.error)
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
        val held = ChatMediaSender { _, _ -> gate.await(); true }
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
        assertEquals("PDF", fileKind("Lease.pdf", "application/pdf"))
        assertEquals("XLSX", fileKind("Budget.xlsx", ""))
        assertEquals("PDF", fileKind("README", "application/pdf"))
        assertEquals("Document", fileKind("README", "application/octet-stream"))
    }
}
