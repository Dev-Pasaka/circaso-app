package com.circaso.feature.messaging

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.platform.GroupPhotoEncoder
import com.circaso.core.platform.GroupPhotoImage
import com.circaso.core.platform.PickedImage
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.Connection
import com.circaso.feature.contacts.domain.ConnectUseCase
import com.circaso.feature.contacts.domain.ConnectVia
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.CreateInviteUseCase
import com.circaso.feature.contacts.domain.Invite
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.contacts.domain.SyncSummary
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.CreateGroupUseCase
import com.circaso.feature.messaging.domain.GroupCreated
import com.circaso.feature.messaging.domain.GroupDraft
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.OpenDirectChatUseCase
import com.circaso.feature.messaging.domain.SecurityCode
import com.circaso.feature.messaging.domain.SetGroupPhotoUseCase
import com.circaso.feature.messaging.presentation.CreateStage
import com.circaso.feature.messaging.presentation.NewChatUiState
import com.circaso.feature.messaging.presentation.NewChatViewModel
import com.circaso.feature.messaging.presentation.NewGroupStep
import com.circaso.feature.messaging.presentation.NewGroupUiState
import com.circaso.feature.messaging.presentation.NewGroupViewModel
import kotlinx.coroutines.CoroutineScope
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NewGroupViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val monitor = object : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }
    private lateinit var connection: ConnectionState
    private val contacts = GroupContacts()
    private val chats = GroupChats()
    private val encoder = object : GroupPhotoEncoder {
        var fail = false
        override suspend fun encode(source: ByteArray) = if (fail) null else GroupPhotoImage(ByteArray(10), 1024, 1024, ByteArray(3))
    }

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm() = NewGroupViewModel(
        ObserveContactsUseCase(contacts), CreateGroupUseCase(chats), connection,
        setGroupPhoto = SetGroupPhotoUseCase(chats), encoder = encoder,
    )

    @Test fun membersArePickedInOrderAndCanBeTakenOut() = runTest(dispatcher) {
        val vm = vm()
        assertFalse(vm.state.value.canNext)
        vm.onToggle("3"); vm.onToggle("1"); vm.onToggle("2")
        assertEquals(listOf("3", "1", "2"), vm.state.value.selectedContacts.map { it.person.userId }, "the strip keeps the order they were picked")
        vm.onToggle("1")
        assertEquals(listOf("3", "2"), vm.state.value.selectedContacts.map { it.person.userId })
        assertTrue(vm.state.value.canNext)
    }

    @Test fun searchMatchesNamesAndHandlesAToZ() = runTest(dispatcher) {
        val vm = vm()
        assertEquals(listOf("Achieng", "Baraka", "Juma"), vm.state.value.shownContacts.map { it.person.displayName })
        vm.onQuery("@jum")
        assertEquals(listOf("Juma"), vm.state.value.shownContacts.map { it.person.displayName })
    }

    @Test fun nextGoesToTheDetailsAndBackKeepsTheChoice() = runTest(dispatcher) {
        val vm = vm()
        vm.onNext()
        assertEquals(NewGroupStep.Members, vm.state.value.step, "nobody chosen yet: no next")
        vm.onToggle("1"); vm.onNext()
        assertEquals(NewGroupStep.Details, vm.state.value.step)
        assertTrue(vm.onBack())
        assertEquals(NewGroupStep.Members, vm.state.value.step)
        assertEquals(setOf("1"), vm.state.value.selected)
        assertFalse(vm.onBack(), "from the people, back leaves the screen")
    }

    @Test fun theNameStopsAtItsLimit() = runTest(dispatcher) {
        val vm = vm()
        vm.onName("x".repeat(NewGroupUiState.MaxGroupName + 10))
        assertEquals(NewGroupUiState.MaxGroupName, vm.state.value.name.length)
    }

    @Test fun createWithoutAPhotoMakesTheGroupOnly() = runTest(dispatcher) {
        val vm = vm()
        vm.onToggle("1"); vm.onNext(); vm.onName("  Holiday Fund ")
        var opened: String? = null
        vm.onCreate { opened = it }
        assertEquals("g1", opened)
        assertEquals("Holiday Fund", chats.drafts.single().name)
        assertTrue(chats.photos.isEmpty())
    }

    @Test fun createWithAPhotoSealsItIntoTheNewGroup() = runTest(dispatcher) {
        val vm = vm()
        vm.onToggle("1"); vm.onNext(); vm.onName("Holiday Fund")
        vm.onPhotoPicked(PickedImage(ByteArray(5), "image/jpeg"), null)
        assertTrue(vm.state.value.photo != null)
        var opened: String? = null
        vm.onCreate { opened = it }
        assertEquals("g1", opened)
        assertEquals(listOf("g1"), chats.photos.map { it.first })
        assertTrue(chats.photos.single().second != null)
        assertNull(vm.state.value.stage)
    }

    @Test fun aPhotoThatCantBeReadSaysSoAndKeepsTheOldOne() = runTest(dispatcher) {
        val vm = vm()
        encoder.fail = true
        vm.onPhotoPicked(PickedImage(ByteArray(5), "image/jpeg"), null)
        assertNull(vm.state.value.photo)
        assertEquals("Couldn't use that photo. Try another one.", vm.state.value.error)
        vm.onPhotoPicked(null, "Couldn't open that photo. Try another one.")
        assertEquals("Couldn't open that photo. Try another one.", vm.state.value.error)
    }

    @Test fun aFailedPhotoUploadKeepsTheGroupAndRetriesOnlyThePhoto() = runTest(dispatcher) {
        val vm = vm()
        vm.onToggle("1"); vm.onNext(); vm.onName("Holiday Fund")
        vm.onPhotoPicked(PickedImage(ByteArray(5), "image/jpeg"), null)
        chats.photoResult = AppResult.Failure(AppError.Network)
        var opened: String? = null
        vm.onCreate { opened = it }
        assertNull(opened, "the person stays to choose: try again or go on")
        assertEquals("g1", vm.state.value.createdChatId)
        assertTrue(vm.state.value.photoFailed)
        assertTrue(vm.state.value.error!!.startsWith("The group is made, but its photo didn't upload."))
        assertFalse(vm.onBack(), "the group exists: back doesn't pretend it doesn't")

        chats.photoResult = null
        vm.onCreate { opened = it }
        assertEquals("g1", opened)
        assertEquals(1, chats.drafts.size, "no second group")
        assertEquals(2, chats.photos.size)
    }

    @Test fun goingOnWithoutThePhotoOpensTheGroup() = runTest(dispatcher) {
        val vm = vm()
        vm.onToggle("1"); vm.onNext(); vm.onName("Holiday Fund")
        vm.onPhotoPicked(PickedImage(ByteArray(5), "image/jpeg"), null)
        chats.photoResult = AppResult.Failure(AppError.Api("messaging.attachment_incomplete", 409, "req_1", null, emptyMap()))
        vm.onCreate { }
        assertTrue(vm.state.value.reference?.contains("req_1") == true)
        var opened: String? = null
        vm.onSkipPhoto { opened = it }
        assertEquals("g1", opened)
        assertEquals(1, chats.drafts.size)
    }

    @Test fun aFailedCreateKeepsTheFormAndItsKey() = runTest(dispatcher) {
        val vm = vm()
        vm.onToggle("1"); vm.onNext(); vm.onName("Holiday Fund")
        chats.createResult = AppResult.Failure(AppError.Api("messaging.group_invalid", 400, "req_9", null, emptyMap()))
        vm.onCreate { }
        assertTrue(vm.state.value.failed)
        chats.createResult = null
        vm.onCreate { }
        assertEquals(chats.keys[0], chats.keys[1], "Try Again sends the same Idempotency-Key")
    }

    @Test fun creatingNeedsAConnection() = runTest(dispatcher) {
        monitor.osStatus.value = ConnectionStatus.Offline
        val vm = vm()
        vm.onToggle("1"); vm.onNext(); vm.onName("Holiday Fund")
        assertFalse(vm.state.value.canCreate)
        vm.onCreate { }
        assertTrue(chats.drafts.isEmpty())
    }

    // ---- New Chat

    private fun newChat() = NewChatViewModel(
        ObserveContactsUseCase(contacts), OpenDirectChatUseCase(chats), connection,
        connect = ConnectUseCase(contacts), createInvite = CreateInviteUseCase(contacts),
    )

    @Test fun peopleAreInLetterSections() = runTest(dispatcher) {
        val vm = newChat()
        assertEquals(listOf("A", "B", "J"), vm.state.value.sections.map { it.letter })
        assertEquals("@juma", vm.state.value.sections.last().people.single().sub)
    }

    @Test fun anUnknownHandleOffersAFindRowThatConnectsThenOpens() = runTest(dispatcher) {
        val vm = newChat()
        vm.onQuery("@juma")
        assertNull(vm.state.value.handleToFind, "a contact already has it")
        vm.onQuery("@kevo")
        assertEquals("kevo", vm.state.value.handleToFind)
        var opened: String? = null
        vm.onFindHandle { opened = it }
        assertEquals(ConnectVia.Handle to "kevo", contacts.connected.single())
        assertEquals("d-9", opened)
    }

    @Test fun inviteOpensTheShareSheetWithTheLink() = runTest(dispatcher) {
        val vm = newChat()
        vm.onInvite()
        assertTrue(vm.state.value.share!!.endsWith("https://circaso.com/i/t"))
        vm.onShared()
        assertNull(vm.state.value.share)
    }

    @Test fun recentChatsComeFirstWithoutDuplicates() {
        val c = { id: String -> Contact(Person(id, "P$id", null, null, null), "sync", "t") }
        val chat = { id: String -> ChatSummary(Chat("d$id", ChatKind.Direct, "P$id", null, emptyList(), false, 1, 0, MemberRole.Member, id), null, 0) }
        val s = NewChatUiState(contacts = listOf(c("1"), c("2")), recentChats = listOf(chat("2"), chat("2"), chat("1")))
        assertEquals(listOf("2", "1"), s.recent.map { it.userId })
        assertTrue(s.copy(query = "p").recent.isEmpty(), "not while searching")
    }
}

private class GroupContacts : ContactsRepository {
    override val contacts: Flow<List<Contact>> = MutableStateFlow(
        listOf(
            Contact(Person("2", "Juma", "juma", null, null), "sync", "t"),
            Contact(Person("1", "Achieng", "achieng", null, null), "sync", "t"),
            Contact(Person("3", "Baraka", null, null, "Mombasa"), "sync", "t"),
        ),
    )
    override val blocks: Flow<List<Person>> = MutableStateFlow(emptyList())
    val connected = mutableListOf<Pair<ConnectVia, String>>()
    override suspend fun refreshContacts() = AppResult.Success(Unit)
    override suspend fun refreshBlocks() = AppResult.Success(Unit)
    override suspend fun syncPhoneBook(phones: List<String>): AppResult<SyncSummary> = AppResult.Success(SyncSummary(0, emptyList()))
    override suspend fun connect(via: ConnectVia, value: String): AppResult<Connection> { connected += via to value; return AppResult.Success(Connection(Person("9", "Kevin", value, null, null), true)) }
    override suspend fun createInvite(): AppResult<Invite> = AppResult.Success(Invite("t", "https://circaso.com/i/t", "qr", "2026-11-12"))
    override suspend fun revokeInvite(token: String) = AppResult.Success(Unit)
    override suspend fun removeContact(userId: String) {}
    override suspend fun block(person: Person) {}
    override suspend fun unblock(userId: String) {}
    override suspend fun canReach(userId: String) = AppResult.Success(true)
}

private class GroupChats : ChatsRepository {
    val drafts = mutableListOf<GroupDraft>()
    val keys = mutableListOf<String>()
    val photos = mutableListOf<Pair<String, GroupPhotoImage?>>()
    var createResult: AppResult<GroupCreated>? = null
    var photoResult: AppResult<Chat>? = null
    private fun made(id: String, kind: ChatKind = ChatKind.Group) = Chat(id, kind, "Holiday Fund", null, emptyList(), false, 1, 1, MemberRole.Owner, null)
    override val chats: Flow<List<ChatSummary>> = flowOf(emptyList())
    override fun chat(chatId: String): Flow<Chat?> = flowOf(null)
    override suspend fun refresh() = AppResult.Success(Unit)
    override suspend fun refreshChat(chatId: String): AppResult<Chat> = AppResult.Success(made(chatId))
    override suspend fun openDirect(userId: String): AppResult<Chat> = AppResult.Success(made("d-$userId", ChatKind.Direct))
    override suspend fun createGroup(draft: GroupDraft, idempotencyKey: String): AppResult<GroupCreated> {
        keys += idempotencyKey
        createResult?.let { return it }
        drafts += draft
        return AppResult.Success(GroupCreated(made("g1"), emptyList()))
    }
    override suspend fun setGroupPhoto(chatId: String, image: GroupPhotoImage?): AppResult<Chat> { photos += chatId to image; return photoResult ?: AppResult.Success(made(chatId)) }
    override suspend fun rename(chatId: String, name: String): AppResult<Chat> = AppResult.Success(made(chatId))
    override suspend fun setMembersCanAdd(chatId: String, allowed: Boolean): AppResult<Chat> = AppResult.Success(made(chatId))
    override suspend fun addMembers(chatId: String, userIds: List<String>): AppResult<GroupCreated> = AppResult.Success(GroupCreated(made(chatId), emptyList()))
    override suspend fun removeMember(chatId: String, userId: String) = AppResult.Success(Unit)
    override suspend fun leave(chatId: String) = AppResult.Success(Unit)
    override suspend fun setRole(chatId: String, userId: String, role: MemberRole): AppResult<Chat> = AppResult.Success(made(chatId))
    override suspend fun securityCode(userId: String): SecurityCode? = null
}
