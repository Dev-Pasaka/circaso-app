package com.circaso.feature.messaging

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.platform.CameraStart
import com.circaso.core.platform.CapturedMedia
import com.circaso.core.platform.LibraryAccess
import com.circaso.core.platform.LibraryItem
import com.circaso.core.platform.LibraryKind
import com.circaso.core.platform.LibraryRecents
import com.circaso.core.platform.MediaLibrary
import com.circaso.core.platform.MomentCamera
import com.circaso.core.platform.MomentMediaMaxBytes
import com.circaso.feature.contacts.domain.Connection
import com.circaso.feature.contacts.domain.ConnectVia
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.Invite
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.contacts.domain.SyncSummary
import com.circaso.feature.mediacompose.ComposerCapture
import com.circaso.feature.mediacompose.IncomingShares
import com.circaso.feature.mediacompose.PreparedItem
import com.circaso.feature.mediacompose.ShareDestination
import com.circaso.feature.mediacompose.SharedFile
import com.circaso.feature.mediacompose.SharedKind
import com.circaso.feature.mediacompose.checkShare
import com.circaso.feature.mediacompose.shareFileName
import com.circaso.feature.mediacompose.sharedKind
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
import com.circaso.feature.messaging.presentation.ShareTargetViewModel
import com.circaso.feature.messaging.presentation.shareSummary
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val photo = SharedFile("/in/a.jpg", "a.jpg", "image/jpeg", 2_000)
private val clip = SharedFile("/in/b.mp4", "b.mp4", "video/mp4", 9_000)
private val pdf = SharedFile("/in/Lease.pdf", "Lease.pdf", "application/pdf", 12_000)
private val huge = SharedFile("/in/film.mov", "film.mov", "video/quicktime", MomentMediaMaxBytes + 1)

class SharedItemsTest {
    @Test fun whatEachSharedItemBecomes() {
        assertEquals(SharedKind.Photo, sharedKind("image/jpeg", "a.jpg"))
        assertEquals(SharedKind.Photo, sharedKind("", "IMG_1.HEIC"))
        assertEquals(SharedKind.Video, sharedKind("video/quicktime", "a.mov"))
        // A GIF or an SVG isn't a photo the review can show: it goes as a document.
        assertEquals(SharedKind.Document, sharedKind("image/gif", "a.gif"))
        assertEquals(SharedKind.Document, sharedKind("application/pdf", "Lease.pdf"))
        assertEquals(SharedKind.Document, sharedKind("", "notes"))
    }

    @Test fun tooLargeAndTooManyAreSaidBeforeSending() {
        val check = checkShare(listOf(photo, huge) + List(11) { photo.copy(path = "/in/$it.jpg") })
        assertEquals(10, check.usable.size)
        assertEquals(listOf(huge), check.tooLarge)
        assertEquals(2, check.overCount)
        assertEquals("film.mov is over 32 MB, so it can’t be sent. You can send up to 10 at a time; the first 10 are here.", check.notice)
        assertNull(checkShare(listOf(photo)).notice)
    }

    @Test fun onlyPhotosAndVideosCanBeAMoment() {
        assertTrue(checkShare(listOf(photo, clip)).canBeMoment)
        assertFalse(checkShare(listOf(photo, pdf)).canBeMoment)
        assertFalse(checkShare(listOf(huge)).canBeMoment)
    }

    @Test fun theSummaryNamesWhatIsSent() {
        assertEquals("1 photo", shareSummary(listOf(photo)))
        assertEquals("3 videos", shareSummary(List(3) { clip }))
        assertEquals("2 photos, 1 video and 1 document", shareSummary(listOf(photo, photo, clip, pdf)))
    }

    @Test fun sharedFileNamesAreSafeAndNeverEmpty() {
        assertEquals("Lease.pdf", shareFileName("Lease.pdf", "application/pdf", 0))
        assertEquals("passwd", shareFileName("../../etc/passwd", "", 0))
        assertEquals("env", shareFileName(".env", "", 0))
        assertEquals("a_b.txt", shareFileName("a:b.txt", "", 0))
        assertEquals("Shared 2.jpg", shareFileName(null, "image/jpeg", 1))
        assertEquals("Shared 1.pdf", shareFileName("  ", "application/pdf", 0))
    }

    @Test fun aShareWaitsUntilTakenOnceAndIsKeptInStep() {
        val seen = mutableListOf<List<SharedFile>?>()
        IncomingShares.onChange = { seen += it }
        IncomingShares.offer(listOf(photo))
        IncomingShares.offer(listOf(clip)) // a newer share replaces the one waiting
        assertEquals(listOf(clip), IncomingShares.pending.value)
        assertEquals(listOf(clip), IncomingShares.take())
        assertNull(IncomingShares.take())
        IncomingShares.offer(emptyList())
        assertNull(IncomingShares.pending.value)
        assertEquals(listOf(listOf(photo), listOf(clip), null), seen)
        IncomingShares.onChange = {}
    }
}

private class ShareChats : ChatsRepository {
    private fun chat(id: String, title: String, kind: ChatKind, peer: String?) = Chat(id, kind, title, null, emptyList(), false, 1, 1, MemberRole.Member, peer)
    override val chats: Flow<List<ChatSummary>> = MutableStateFlow(listOf(
        ChatSummary(chat("c1", "Wanjiru", ChatKind.Direct, "u1"), null, 0),
        ChatSummary(chat("g1", "Holiday Fund", ChatKind.Group, null), null, 0),
    ))
    override fun chat(chatId: String): Flow<Chat?> = flowOf(null)
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

private class People : ContactsRepository {
    override val contacts: Flow<List<Contact>> = MutableStateFlow(listOf(
        Contact(Person("u1", "Wanjiru", null, null, null), "sync", "2026-11-01"), // has a chat already
        Contact(Person("u2", "Otieno", null, null, null), "sync", "2026-11-01"),
    ))
    override val blocks: Flow<List<Person>> = MutableStateFlow(emptyList())
    override suspend fun refreshContacts() = AppResult.Success(Unit)
    override suspend fun refreshBlocks() = AppResult.Success(Unit)
    override suspend fun syncPhoneBook(phones: List<String>): AppResult<SyncSummary> = AppResult.Success(SyncSummary(0, emptyList()))
    override suspend fun connect(via: ConnectVia, value: String): AppResult<Connection> = AppResult.Failure(AppError.Network)
    override suspend fun createInvite(): AppResult<Invite> = AppResult.Failure(AppError.Network)
    override suspend fun revokeInvite(token: String) = AppResult.Success(Unit)
    override suspend fun removeContact(userId: String) = Unit
    override suspend fun block(person: Person) = Unit
    override suspend fun unblock(userId: String) = Unit
    override suspend fun canReach(userId: String) = AppResult.Success(true)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShareTargetViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val opened = mutableListOf<String>()
    private fun picker(files: List<SharedFile>, online: Boolean = true) = ShareTargetViewModel(ShareChats(), ObserveContactsUseCase(People()), openDirect = { uid ->
        opened += uid
        if (online) AppResult.Success("chat-$uid") else AppResult.Failure(AppError.Network)
    }, files = { files })

    @Test fun chatsThenContactsWithoutAChat() = runTest(dispatcher) {
        val vm = picker(listOf(photo))
        assertEquals(listOf("Wanjiru", "Holiday Fund"), vm.state.value.chats.map { it.title })
        // Wanjiru already has a chat, so only Otieno is offered as a contact.
        assertEquals(listOf("Otieno"), vm.state.value.people.map { it.title })
        vm.onQuery("hol")
        assertEquals(listOf("Holiday Fund"), vm.state.value.shownChats.map { it.title })
        assertTrue(vm.state.value.shownPeople.isEmpty())
    }

    @Test fun myMomentGoesAloneAndOnlyForPhotosAndVideos() = runTest(dispatcher) {
        val vm = picker(listOf(photo, clip))
        vm.toggle("c1")
        vm.toggleMoment()
        assertTrue(vm.state.value.moment)
        assertTrue(vm.state.value.selected.isEmpty())
        vm.toggle("g1")
        assertFalse(vm.state.value.moment)
        val withDoc = picker(listOf(photo, pdf))
        withDoc.toggleMoment()
        assertFalse(withDoc.state.value.moment)
        assertEquals("Moments are photos and videos. Send documents to a chat.", withDoc.state.value.error)
    }

    @Test fun nextRoutesToTheMoment() = runTest(dispatcher) {
        val vm = picker(listOf(photo))
        vm.toggleMoment()
        vm.next()
        assertEquals(ShareDestination.Moment, vm.state.value.go)
    }

    @Test fun nextRoutesToEveryChatOpeningAContactsChatFirst() = runTest(dispatcher) {
        val vm = picker(listOf(pdf))
        vm.toggle("g1"); vm.toggle("person:u2"); vm.toggle("c1")
        vm.next()
        assertEquals(ShareDestination.Chats(listOf("g1", "chat-u2", "c1")), vm.state.value.go)
        assertEquals(listOf("u2"), opened)
    }

    @Test fun offlineAContactsChatCantBeOpened() = runTest(dispatcher) {
        val vm = picker(listOf(pdf), online = false)
        vm.toggle("person:u2")
        vm.next()
        assertNull(vm.state.value.go)
        assertFalse(vm.state.value.opening)
        assertEquals("Couldn’t open a chat with Otieno. Check your connection and try again.", vm.state.value.error)
    }

    @Test fun nothingUsableCantGoAnywhere() = runTest(dispatcher) {
        val vm = picker(listOf(huge))
        vm.toggle("c1")
        assertFalse(vm.state.value.canGo)
    }

    @Test fun upToFiveChats() = runTest(dispatcher) {
        val vm = picker(listOf(photo))
        repeat(6) { vm.toggle("x$it") }
        assertEquals(5, vm.state.value.selected.size)
        assertEquals("You can send to up to 5 chats at a time.", vm.state.value.error)
    }
}

private class ShareLibrary : MediaLibrary {
    override suspend fun access() = LibraryAccess.Granted
    override suspend fun recents(kind: LibraryKind, limit: Int, ask: Boolean) = LibraryRecents(emptyList(), LibraryAccess.Granted)
    override suspend fun manage(kind: LibraryKind) = LibraryAccess.Granted
    override suspend fun load(item: LibraryItem): CapturedMedia? = null
    override suspend fun pick(kind: LibraryKind): CapturedMedia? = null
    override suspend fun read(path: String) = ByteArray(5)
    override suspend fun loadFile(path: String, mime: String): CapturedMedia? =
        if (mime.startsWith("video")) CapturedMedia(ByteArray(0), mime, 1, 1, durationMs = 5_000, path = path) else CapturedMedia(ByteArray(3), "image/jpeg", 1, 1)
}

private object NoCamera : MomentCamera {
    override suspend fun open() = CameraStart.Started
    override suspend fun takePhoto(): CapturedMedia? = null
    override suspend fun startVideo() = false
    override suspend fun stopVideo(): CapturedMedia? = null
    override fun flip() = Unit
    override fun setFlash(on: Boolean) = Unit
    override fun close() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class SharedIntoChatsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun theSameItemsGoToEachChosenChatEachItsOwnAlbum() = runTest(dispatcher) {
        val sent = mutableListOf<Triple<String, PreparedItem, String?>>()
        val vm = ChatMediaComposerViewModel(
            "c1", ChatComposeStart.Shared, ShareLibrary(), NoCamera, ShareChats(), ChatMediaSender { c, i, a -> sent += Triple(c, i, a); true },
            now = { 0 }, moreChats = listOf("g1"), shared = { listOf(photo, clip, pdf) },
        )
        // On the review at once, documents too, and no camera behind it.
        assertEquals(3, vm.state.value.media.items.size)
        assertIs<ComposerCapture.Document>(vm.state.value.media.items[2].capture)
        assertNull(vm.state.value.media.camera)
        assertEquals("Wanjiru and Holiday Fund", vm.state.value.title)
        vm.send()
        assertTrue(vm.state.value.sent)
        assertEquals(listOf("c1", "c1", "c1", "g1", "g1", "g1"), sent.map { it.first })
        val albums = sent.filter { it.second is PreparedItem.Shot }.map { it.third }
        assertEquals(albums[0], albums[1])
        assertNotEquals(albums[1], albums[2])
        assertNull(sent.first { it.second is PreparedItem.Document }.third)
    }

    @Test fun aShareWithNothingUsableClosesAndSaysWhy() = runTest(dispatcher) {
        val vm = ChatMediaComposerViewModel("c1", ChatComposeStart.Shared, ShareLibrary(), NoCamera, ShareChats(), ChatMediaSender { _, _, _ -> true }, now = { 0 }, shared = { listOf(huge) })
        assertTrue(vm.state.value.closed)
    }
}
