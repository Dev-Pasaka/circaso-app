package com.circaso.feature.spaces

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.flags.SpacesFlag
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.core.sync.Outbox
import com.circaso.feature.contacts.domain.BlockUseCase
import com.circaso.feature.contacts.domain.Connection
import com.circaso.feature.contacts.domain.ConnectVia
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.Invite
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.contacts.domain.SyncSummary
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.GroupCreated
import com.circaso.feature.messaging.domain.GroupDraft
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.SecurityCode
import com.circaso.feature.profile.domain.FindByHandleUseCase
import com.circaso.feature.profile.domain.ProfileRepository
import com.circaso.feature.profile.domain.PublicProfile
import com.circaso.feature.spaces.data.remote.InviteResultDto
import com.circaso.feature.spaces.data.remote.InviteResultsDto
import com.circaso.feature.spaces.data.remote.InvitesDto
import com.circaso.feature.spaces.data.remote.JoinRequestDto
import com.circaso.feature.spaces.data.remote.MeDto
import com.circaso.feature.spaces.data.remote.MemberDto
import com.circaso.feature.spaces.data.remote.MembersPage
import com.circaso.feature.spaces.data.remote.PersonDto
import com.circaso.feature.spaces.data.remote.PostsPage
import com.circaso.feature.spaces.data.remote.ReportGroupDto
import com.circaso.feature.spaces.data.remote.ReportsPage
import com.circaso.feature.spaces.data.repository.SpacesRepositoryImpl
import com.circaso.feature.spaces.domain.MemberFilter
import com.circaso.feature.spaces.domain.MuteDuration
import com.circaso.feature.spaces.domain.PostSendState
import com.circaso.feature.spaces.domain.ReportAction
import com.circaso.feature.spaces.domain.ReportReason
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.presentation.chat.ChatFoot
import com.circaso.feature.spaces.presentation.chat.InviteRow
import com.circaso.feature.spaces.presentation.chat.MemberMenuMode
import com.circaso.feature.spaces.presentation.chat.SpaceChatEvent
import com.circaso.feature.spaces.presentation.chat.SpaceChatViewModel
import com.circaso.feature.spaces.presentation.chat.SpaceInviteViewModel
import com.circaso.feature.spaces.presentation.chat.SpaceInvitesViewModel
import com.circaso.feature.spaces.presentation.chat.SpaceMembersViewModel
import com.circaso.feature.spaces.presentation.chat.SpaceReportViewModel
import com.circaso.feature.spaces.presentation.chat.SpaceReportsViewModel
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.FakeSpacesDao
import com.circaso.testing.FakeSpacesRemote
import com.circaso.testing.SpaceSamples
import com.circaso.testing.apiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ChatTestMonitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

private class Chats : ChatsRepository {
    val opened = mutableListOf<String>()
    override val chats: Flow<List<ChatSummary>> = MutableStateFlow(emptyList())
    override fun chat(chatId: String): Flow<Chat?> = MutableStateFlow(null)
    override suspend fun refresh() = AppResult.Success(Unit)
    override suspend fun refreshChat(chatId: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    private fun chat(id: String, via: String?) = Chat(id, ChatKind.Direct, "Baraka", null, emptyList(), false, 1, 0, MemberRole.Member, "baraka", via)
    override suspend fun openDirect(userId: String): AppResult<Chat> { opened += "direct:$userId"; return AppResult.Success(chat("dm-$userId", null)) }
    override suspend fun openViaSpace(userId: String, spaceId: String): AppResult<Chat> { opened += "via:$userId:$spaceId"; return AppResult.Success(chat("pv-$userId", spaceId)) }
    override suspend fun createGroup(draft: GroupDraft, idempotencyKey: String): AppResult<GroupCreated> = AppResult.Failure(AppError.Network)
    override suspend fun rename(chatId: String, name: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun setMembersCanAdd(chatId: String, allowed: Boolean): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun addMembers(chatId: String, userIds: List<String>): AppResult<GroupCreated> = AppResult.Failure(AppError.Network)
    override suspend fun removeMember(chatId: String, userId: String) = AppResult.Success(Unit)
    override suspend fun leave(chatId: String) = AppResult.Success(Unit)
    override suspend fun setRole(chatId: String, userId: String, role: MemberRole): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun securityCode(userId: String): SecurityCode? = null
}

private class Contacts : ContactsRepository {
    val blocked = mutableListOf<String>()
    override val contacts: Flow<List<Contact>> = MutableStateFlow(listOf(
        Contact(Person("faith", "Faith Chebet", "faithc", null, null), "phone", "2026-10-01"),
        Contact(Person("kevin", "Kevin Ochieng", "kevo", null, null), "phone", "2026-10-01"),
        Contact(Person("wanjiru", "Wanjiru Kamau", "wanjiru", null, null), "phone", "2026-10-01"),
    ))
    override val blocks: Flow<List<Person>> = MutableStateFlow(emptyList())
    override suspend fun refreshContacts() = AppResult.Success(Unit)
    override suspend fun refreshBlocks() = AppResult.Success(Unit)
    override suspend fun syncPhoneBook(phones: List<String>): AppResult<SyncSummary> = AppResult.Success(SyncSummary(0, emptyList()))
    override suspend fun connect(via: ConnectVia, value: String): AppResult<Connection> = AppResult.Failure(AppError.Network)
    override suspend fun createInvite(): AppResult<Invite> = AppResult.Failure(AppError.Network)
    override suspend fun revokeInvite(token: String) = AppResult.Success(Unit)
    override suspend fun removeContact(userId: String) = Unit
    override suspend fun block(person: Person) { blocked += person.userId }
    override suspend fun unblock(userId: String) = Unit
    override suspend fun canReach(userId: String) = AppResult.Success(true)
}

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceChatViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private lateinit var monitor: ChatTestMonitor
    private lateinit var connection: ConnectionState
    private val dao = FakeSpacesDao()
    private val remote = FakeSpacesRemote()
    private val outbox = Outbox(FakeOutboxDao(), owner = { "me" })
    private val repo by lazy {
        SpacesRepositoryImpl(dao, remote, SpacesFlag.on(), outbox, kickOutbox = {}, json = json, ownUserId = { "me" },
            me = { com.circaso.feature.spaces.domain.PostAuthor("me", "Pasaka Mutuku", "pasaka", null, null) })
    }
    private val chats = Chats()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        monitor = ChatTestMonitor()
        connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0)
    }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun asMember(role: String = "MEMBER", postingMode: String = "EVERYONE", mutedUntil: String? = null) {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space(me = MeDto("MEMBER", role, mutedUntil = mutedUntil), postingMode = postingMode)) }
    }

    private fun chatVm() = SpaceChatViewModel("s1", repo, chats, connection, { "me" })

    @Test fun theChatLoadsPostsAndMovesTheReadMarker() = runTest(dispatcher) {
        asMember()
        remote.postsAnswer = { _, _ -> AppResult.Success(PostsPage(listOf(SpaceSamples.post("p1", "Jordans Friday"), SpaceSamples.post("p2", "Size 42 back", at = "2026-11-04T10:00:00Z")))) }
        val vm = chatVm()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf("Jordans Friday", "Size 42 back"), s.posts.map { it.text })
        assertFalse(s.loading)
        assertEquals(ChatFoot.Composer, s.foot)
        assertTrue("read:p2" in remote.calls)
        job.cancel()
    }

    @Test fun aPostIsQueuedAtOnceAndTheDraftClears() = runTest(dispatcher) {
        asMember()
        val vm = chatVm()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.onDraft("Is the CBD shop open Sunday?")
        vm.onSend()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("", s.draft)
        val mine = s.posts.single { it.mine }
        assertEquals("Is the CBD shop open Sunday?", mine.text)
        assertTrue(mine.sendState == PostSendState.Waiting || mine.sendState == PostSendState.Sent)
        job.cancel()
    }

    @Test fun adminsOnlyAndMutedHaveNoComposerButKeepMessagePrivately() = runTest(dispatcher) {
        asMember(postingMode = "ADMINS_ONLY")
        val vm = chatVm()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(ChatFoot.AdminsOnly, vm.state.value.foot)
        assertTrue(vm.state.value.canMessageOwner)
        val events = mutableListOf<SpaceChatEvent>()
        val ev = launch { vm.events.collect { events += it } }
        vm.onMessagePrivately()
        advanceUntilIdle()
        assertEquals(SpaceChatEvent.OpenChat("pv-baraka"), events.single())
        assertEquals("via:baraka:s1", chats.opened.single())
        asMember(mutedUntil = "2099-01-01T20:00:00Z")
        repo.refreshSpace("s1")
        advanceUntilIdle()
        assertTrue(vm.state.value.foot is ChatFoot.Muted)
        ev.cancel(); job.cancel()
    }

    @Test fun theMenuOffersOnlyWhatTheRoleAllows() = runTest(dispatcher) {
        asMember(role = "MODERATOR")
        remote.postsAnswer = { _, _ -> AppResult.Success(PostsPage(listOf(
            SpaceSamples.post("p1", "Owner post", author = "baraka", role = "OWNER"),
            SpaceSamples.post("p2", "Member post", author = "juma", name = "Juma", role = null),
            SpaceSamples.post("p3", "Admin post", author = "amina", name = "Amina", role = "ADMIN"),
        ))) }
        val vm = chatVm()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        val s = vm.state.value
        val (owner, member, admin) = s.posts
        assertFalse(s.canModerate(owner))
        assertTrue(s.canModerate(member))
        assertTrue(s.canRemovePost(member))
        assertFalse(s.canRemoveMember(member)) // moderators can't remove people
        assertFalse(s.canModerate(admin))
        job.cancel()
    }

    @Test fun removingAPostIsLiveAndOfflineItIsNotTried() = runTest(dispatcher) {
        asMember(role = "ADMIN")
        remote.postsAnswer = { _, _ -> AppResult.Success(PostsPage(listOf(SpaceSamples.post("p2", "DM me", author = "kevin", name = "Kevin", role = null)))) }
        remote.removePostAnswer = { id -> AppResult.Success(SpaceSamples.post(id, "").copy(text = null, removed = com.circaso.feature.spaces.data.remote.RemovedMarkDto("MODERATOR", "2026-11-04T10:05:00Z"))) }
        val vm = chatVm()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        val post = vm.state.value.posts.single()
        monitor.osStatus.value = ConnectionStatus.Offline
        advanceUntilIdle()
        vm.onRemovePost(post)
        advanceUntilIdle()
        assertFalse(remote.calls.any { it.startsWith("removePost") })
        monitor.osStatus.value = ConnectionStatus.Online
        advanceUntilIdle()
        vm.onRemovePost(post)
        advanceUntilIdle()
        assertTrue("removePost:p2" in remote.calls)
        assertEquals(com.circaso.feature.spaces.domain.RemovedBy.Moderator, vm.state.value.posts.single().removed)
        job.cancel()
    }

    @Test fun anOlderPageThatFailsSaysSoWithItsReference() = runTest(dispatcher) {
        asMember()
        remote.postsAnswer = { before, _ -> if (before == null) AppResult.Success(PostsPage(listOf(SpaceSamples.post("p5", "latest")))) else apiError("server.error", 500, requestId = "req_H5tq0B") }
        val vm = chatVm()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.onLoadOlder()
        advanceUntilIdle()
        assertTrue(vm.state.value.olderFailed)
        assertTrue(vm.state.value.olderRef!!.startsWith("req_H5tq0B"))
        job.cancel()
    }

    // ---- SR1

    private fun membersVm() = SpaceMembersViewModel("s1", repo, chats, connection, { "me" })

    @Test fun membersAdminCanRemoveAMemberButNotTheOwnerOrAnotherAdmin() = runTest(dispatcher) {
        asMember(role = "ADMIN")
        remote.membersAnswer = AppResult.Success(MembersPage(
            team = listOf(MemberDto("baraka", "Baraka", role = "OWNER"), MemberDto("amina", "Amina", role = "ADMIN"), MemberDto("me", "Pasaka", role = "ADMIN")),
            members = listOf(MemberDto("faith", "Faith Chebet", role = "MEMBER")), count = 1201,
        ))
        val vm = membersVm()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        val s = vm.state.value
        val faith = s.members.single()
        assertTrue(s.canRemove(faith))
        assertEquals(listOf(SpaceRole.Moderator), s.rolesFor(faith)) // an admin makes moderators, not admins
        assertFalse(s.canRemove(s.team[0]))
        assertFalse(s.canRemove(s.team[1]))
        vm.onOpenMenu(faith)
        vm.onMenuMode(MemberMenuMode.ConfirmRemove)
        vm.onRemove(faith)
        advanceUntilIdle()
        assertTrue("remove:faith" in remote.calls)
        assertNull(vm.state.value.menu)
        job.cancel()
    }

    @Test fun aModeratorOnlyMutesAndSeesTheNote() = runTest(dispatcher) {
        asMember(role = "MODERATOR")
        remote.membersAnswer = AppResult.Success(MembersPage(members = listOf(MemberDto("faith", "Faith Chebet", role = "MEMBER"))))
        val vm = membersVm()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        val s = vm.state.value
        val faith = s.members.single()
        assertTrue(s.canMute(faith))
        assertFalse(s.canRemove(faith))
        assertTrue(s.rolesFor(faith).isEmpty())
        assertEquals("Only admins can remove people or change roles.", s.limitedNote)
        vm.onMute(faith, MuteDuration.Hour)
        advanceUntilIdle()
        assertTrue("mute:faith:HOUR" in remote.calls)
        vm.onFilter(MemberFilter.Muted)
        advanceUntilIdle()
        assertTrue(remote.calls.any { it.startsWith("members:MUTED") })
        job.cancel()
    }

    @Test fun theOwnerMessagesAMemberInTheSpacesPrivateChat() = runTest(dispatcher) {
        asMember(role = "OWNER")
        remote.membersAnswer = AppResult.Success(MembersPage(members = listOf(MemberDto("faith", "Faith Chebet", role = "MEMBER"))))
        val vm = membersVm()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.onMessage(vm.state.value.members.single())
        advanceUntilIdle()
        assertEquals("via:faith:s1", chats.opened.single())
        job.cancel()
    }

    // ---- SR3, SR4

    @Test fun aReportIsSentThenBlocksWhenAsked() = runTest(dispatcher) {
        asMember()
        remote.postsAnswer = { _, _ -> AppResult.Success(PostsPage(listOf(SpaceSamples.post("k1", "Pay to my till first", author = "kevin", name = "Kevin Ochieng", role = null)))) }
        repo.refreshPosts("s1")
        val contacts = Contacts()
        val vm = SpaceReportViewModel("s1", "k1", repo, BlockUseCase(contacts), connection)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals("Kevin", vm.state.value.authorFirst)
        vm.onReason(ReportReason.SpamScam)
        vm.onAlsoBlock(true)
        vm.onSend()
        advanceUntilIdle()
        assertTrue(vm.state.value.sent)
        assertEquals("report:k1:SPAM_SCAM", remote.calls.last { it.startsWith("report:") })
        assertEquals(listOf("kevin"), contacts.blocked)
        job.cancel()
    }

    @Test fun reportsAreOnlineOnly() = runTest(dispatcher) {
        monitor.osStatus.value = ConnectionStatus.Offline
        val vm = SpaceReportViewModel("s1", null, repo, BlockUseCase(Contacts()), connection)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        assertFalse(vm.state.value.canSend)
        vm.onSend()
        advanceUntilIdle()
        assertFalse(remote.calls.any { it.startsWith("report:") })
        job.cancel()
    }

    @Test fun actingOnAReportClosesIt() = runTest(dispatcher) {
        asMember(role = "ADMIN")
        val post = SpaceSamples.post("k1", "Pay to my till first", author = "kevin", name = "Kevin", role = null)
        remote.reportsAnswer = AppResult.Success(ReportsPage(listOf(ReportGroupDto(post, "SPAM_SCAM", 4))))
        val vm = SpaceReportsViewModel("s1", repo, connection)
        val job = launch { vm.state.collect {} }
        val toasts = mutableListOf<String>()
        val t = launch { vm.toasts.collect { toasts += it } }
        advanceUntilIdle()
        val r = vm.state.value.reports.single()
        assertEquals(4, r.count)
        vm.onAct(r, ReportAction.Mute)
        advanceUntilIdle()
        assertEquals("resolve:k1:MUTE", remote.calls.last())
        assertTrue(vm.state.value.reports.isEmpty())
        assertEquals("Kevin is muted for 24 hours", toasts.single())
        t.cancel(); job.cancel()
    }

    // ---- SI1, SI4

    @Test fun invitingMarksTheRowAndContactsAlreadyInShowAsMember() = runTest(dispatcher) {
        asMember(role = "ADMIN")
        remote.membersAnswer = AppResult.Success(MembersPage(contacts = listOf(MemberDto("wanjiru", "Wanjiru Kamau", role = "MEMBER"))))
        remote.inviteAnswer = AppResult.Success(InviteResultsDto(listOf(InviteResultDto("kevin", "INVITED"))))
        val profiles = FindByHandleUseCase(object : ProfileRepository by NoProfile {})
        val vm = SpaceInviteViewModel("s1", repo, ObserveContactsUseCase(Contacts()), profiles, connection)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(InviteRow.Member, s.row("wanjiru"))
        vm.onInvite(s.contacts.first { it.userId == "kevin" })
        advanceUntilIdle()
        assertEquals(InviteRow.Invited, vm.state.value.row("kevin"))
        assertEquals("invite:kevin", remote.calls.last { it.startsWith("invite:") })
        vm.onQuery("@kevo254")
        advanceUntilIdle()
        assertTrue(vm.state.value.notFound)
        job.cancel()
    }

    @Test fun answeringARequestTakesItOffTheList() = runTest(dispatcher) {
        remote.invitesAnswer = AppResult.Success(InvitesDto(requests = listOf(JoinRequestDto(PersonDto("njeri", "Njeri Mwangi"), "2026-11-04T09:12:00Z"))))
        val vm = SpaceInvitesViewModel("s1", repo, connection)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(1, vm.state.value.requests.size)
        vm.onAnswer(vm.state.value.requests.single(), accept = true)
        advanceUntilIdle()
        assertTrue("answer:njeri:true" in remote.calls)
        assertTrue(vm.state.value.requests.isEmpty())
        assertTrue(vm.state.value.empty)
        job.cancel()
    }
}

/** A profile repository whose only use here is SI1b's lookup, which never finds anyone. */
private object NoProfile : ProfileRepository {
    override val profile: Flow<com.circaso.feature.profile.domain.Profile?> = MutableStateFlow(null)
    override suspend fun refresh() = AppResult.Success(Unit)
    override suspend fun update(change: com.circaso.feature.profile.domain.ProfileChange): Boolean = true
    override suspend fun checkHandle(handle: String): AppResult<com.circaso.feature.profile.domain.HandleAvailability> = AppResult.Failure(AppError.Network)
    override suspend fun claimHandle(handle: String): AppResult<Unit> = AppResult.Failure(AppError.Network)
    override suspend fun uploadAvatar(image: com.circaso.feature.profile.domain.AvatarImage): AppResult<Unit> = AppResult.Failure(AppError.Network)
    override suspend fun removeAvatar(): AppResult<Unit> = AppResult.Failure(AppError.Network)
    override suspend fun findByHandle(handle: String): AppResult<PublicProfile> = apiError("users.not_found", 404)
    override suspend fun publicProfile(userId: String): AppResult<PublicProfile> = AppResult.Failure(AppError.Network)
}
