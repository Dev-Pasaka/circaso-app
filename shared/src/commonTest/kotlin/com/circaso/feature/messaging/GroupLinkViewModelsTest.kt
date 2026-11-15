package com.circaso.feature.messaging

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.GroupCreated
import com.circaso.feature.messaging.domain.GroupDraft
import com.circaso.feature.messaging.domain.GroupLink
import com.circaso.feature.messaging.domain.GroupLinkPreview
import com.circaso.feature.messaging.domain.GroupLinksRepository
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.ObserveChatUseCase
import com.circaso.feature.messaging.domain.SecurityCode
import com.circaso.feature.messaging.presentation.GroupJoinStage
import com.circaso.feature.messaging.presentation.GroupJoinViewModel
import com.circaso.feature.messaging.presentation.GroupLinkConfirm
import com.circaso.feature.messaging.presentation.GroupLinkViewModel
import com.circaso.feature.messaging.presentation.GroupLinkWork
import com.circaso.feature.messaging.presentation.JoinUnreachable
import com.circaso.feature.messaging.presentation.memberCountText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val Token = "h3Xq9LmT2vKc8RzYw4Nd6B"
private const val Url = "https://links.circaso.com/s/$Token"

private fun api(code: String, status: Int = 400) = AppError.Api(code, status, "req_1", null, emptyMap())

/** Join links as the screens see them: a link per group, and scripted answers for each call. */
private class FakeLinks : GroupLinksRepository {
    val stored = MutableStateFlow<GroupLink?>(null)
    var refreshResult: () -> AppResult<GroupLink?> = { AppResult.Success(stored.value) }
    var refreshes = 0
    var createResult: () -> AppResult<GroupLink> = { AppResult.Success(GroupLink("g1", Token, Url, "Book club", null, false)) }
    var resetResult: () -> AppResult<GroupLink> = { AppResult.Success(GroupLink("g1", "N3wT0kenN3wT0kenN3wT0k", "https://links.circaso.com/s/N3wT0kenN3wT0kenN3wT0k", "Book club", null, false)) }
    var turnOffResult: () -> AppResult<Unit> = { AppResult.Success(Unit) }
    var created = 0
    var resets = 0
    var turnedOff = 0
    var republished = 0
    var previewResult: suspend () -> AppResult<GroupLinkPreview> = { AppResult.Failure(AppError.Network) }
    var previews = 0
    var joinResult: suspend () -> AppResult<Chat> = { AppResult.Failure(AppError.Network) }
    var joins = 0
    var joinedWith: GroupLinkPreview? = null

    override fun link(chatId: String): Flow<GroupLink?> = stored
    override suspend fun refresh(chatId: String): AppResult<GroupLink?> { refreshes++; return refreshResult().also { if (it is AppResult.Success) stored.value = it.value } }
    override suspend fun create(chatId: String): AppResult<GroupLink> { created++; return createResult().also { if (it is AppResult.Success) stored.value = it.value } }
    override suspend fun reset(chatId: String): AppResult<GroupLink> { resets++; return resetResult().also { if (it is AppResult.Success) stored.value = it.value } }
    override suspend fun turnOff(chatId: String): AppResult<Unit> { turnedOff++; return turnOffResult().also { if (it is AppResult.Success) stored.value = null } }
    override suspend fun republishIfStale(chatId: String) { republished++ }
    override suspend fun preview(token: String): AppResult<GroupLinkPreview> { previews++; return previewResult() }
    override suspend fun join(token: String, preview: GroupLinkPreview?): AppResult<Chat> { joins++; joinedWith = preview; return joinResult() }
}

private fun group(role: MemberRole = MemberRole.Owner, nameKnown: Boolean = true) = Chat(
    "g1", ChatKind.Group, "Book club", null,
    listOf(ChatMember("me", "Amina", null, null, role, ""), ChatMember("b", "Brian", null, null, MemberRole.Member, "")),
    false, 1, 1, role, null, nameKnown = nameKnown,
)

private class LinkScreenChats(val chat: MutableStateFlow<Chat?>) : ChatsRepository {
    override val chats: Flow<List<ChatSummary>> = flowOf(emptyList())
    override fun chat(chatId: String): Flow<Chat?> = chat.map { it }
    override suspend fun refresh(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun refreshChat(chatId: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun openDirect(userId: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun createGroup(draft: GroupDraft, idempotencyKey: String): AppResult<GroupCreated> = AppResult.Failure(AppError.Network)
    val edits = mutableListOf<Pair<String, String>>()
    val renames = mutableListOf<String>()
    override suspend fun rename(chatId: String, name: String): AppResult<Chat> { renames += name; return AppResult.Failure(AppError.Network) }
    var failEdits: AppError? = null
    var beforeEdit: suspend () -> Unit = {}
    override suspend fun editGroup(chatId: String, name: String, description: String): AppResult<Chat> {
        beforeEdit()
        failEdits?.let { return AppResult.Failure(it) }
        edits += name to description
        val next = chat.value!!.copy(title = name, description = description.trim().ifEmpty { null })
        chat.value = next
        return AppResult.Success(next)
    }
    override suspend fun setMembersCanAdd(chatId: String, allowed: Boolean): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun addMembers(chatId: String, userIds: List<String>): AppResult<GroupCreated> = AppResult.Failure(AppError.Network)
    override suspend fun removeMember(chatId: String, userId: String): AppResult<Unit> = AppResult.Failure(AppError.Network)
    override suspend fun leave(chatId: String): AppResult<Unit> = AppResult.Failure(AppError.Network)
    override suspend fun setRole(chatId: String, userId: String, role: MemberRole): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun securityCode(userId: String): SecurityCode? = null
}

/**
 * Invite via link, the admins' screen (2026-11-14): an existing link shows from the phone's copy with no connection;
 * making, resetting and turning it off need the server, never fire offline, and say what happened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupLinkViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val os = MutableStateFlow(ConnectionStatus.Online)
    private val monitor = object : ConnectivityMonitor { override val osStatus = os }
    private lateinit var connection: ConnectionState
    private val links = FakeLinks()
    private val chat = MutableStateFlow<Chat?>(group())

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm() = GroupLinkViewModel("g1", ObserveChatUseCase(LinkScreenChats(chat)), links, connection)
    private val existing = GroupLink("g1", Token, Url, "Book club", "Fridays at six.", false)

    @Test fun withNoLinkItAsksTheServerThenOffersToMakeOne() = runTest(dispatcher) {
        val vm = vm()
        val s = vm.state.value
        assertEquals(1, links.refreshes)
        assertTrue(s.checked)
        assertNull(s.link)
        assertFalse(s.loading)
        assertTrue(s.canChange)
        assertEquals(0, links.created, "nothing is made, so nothing is published, until Create Link")
    }

    @Test fun createLinkMakesItOnce() = runTest(dispatcher) {
        val vm = vm()
        vm.onCreate()
        assertEquals(1, links.created)
        assertEquals(Url, vm.state.value.link?.url)
        assertNull(vm.state.value.work)
        assertNull(vm.state.value.message, "the link appearing is the answer")
    }

    @Test fun anExistingLinkShowsOfflineAndItsChangesRest() = runTest(dispatcher) {
        links.stored.value = existing
        links.refreshResult = { AppResult.Failure(AppError.Network) }
        os.value = ConnectionStatus.Offline
        val vm = vm()
        val s = vm.state.value
        assertEquals(Url, s.link?.url, "from this phone's copy")
        assertFalse(s.online)
        assertFalse(s.canChange)
        assertFalse(s.unknownOffline)
        assertNull(s.loadFailed, "being offline is not an error")

        vm.onConfirm(GroupLinkConfirm.Reset); vm.onConfirmed()
        vm.onConfirm(GroupLinkConfirm.TurnOff); vm.onConfirmed()
        vm.onCreate()
        assertEquals(0, links.resets + links.turnedOff + links.created, "nothing fires blind offline")

        vm.onShare()
        assertEquals("Join *Book club* on Circaso: $Url", vm.state.value.share?.text, "sharing what the phone has needs no connection")
        vm.onShared()
        assertNull(vm.state.value.share)
        vm.onCopied()
        assertEquals("Link copied", vm.state.value.message)
    }

    @Test fun offlineWithNoCopySaysSoAndLoadsWhenBackOnline() = runTest(dispatcher) {
        links.refreshResult = { AppResult.Failure(AppError.Network) }
        os.value = ConnectionStatus.Offline
        val vm = vm()
        assertTrue(vm.state.value.unknownOffline)
        assertFalse(vm.state.value.checked)

        links.refreshResult = { AppResult.Success(existing) }
        os.value = ConnectionStatus.Online

        assertEquals(2, links.refreshes, "asked again without being asked")
        assertTrue(vm.state.value.checked)
        assertEquals(Url, vm.state.value.link?.url)
        assertEquals(1, links.republished, "and the published preview is checked against the group")
    }

    @Test fun aLoadThatFailsIsSaidWithItsReference() = runTest(dispatcher) {
        links.refreshResult = { AppResult.Failure(api("internal.error", 500)) }
        val vm = vm()
        assertTrue(vm.state.value.loadFailed!!.contains("Ref req_1"))
        assertFalse(vm.state.value.loading)

        links.refreshResult = { AppResult.Success(null) }
        vm.load()
        assertNull(vm.state.value.loadFailed)
        assertTrue(vm.state.value.checked)
    }

    @Test fun aServerFromBeforeGroupLinksSaysTheyAreNotAvailableYet() = runTest(dispatcher) {
        links.refreshResult = { AppResult.Failure(api("request.not_found", 404)) }
        val vm = vm()
        assertTrue(vm.state.value.loadFailed!!.startsWith("Group links aren't available yet."))
        assertNull(vm.state.value.link)
    }

    @Test fun aLoadWithNoAnswerWhileOnlineNeverSitsOnItsLoadingShapes() = runTest(dispatcher) {
        // One timeout: the phone still counts as online, so nothing would ever retry by itself.
        links.refreshResult = { AppResult.Failure(AppError.Network) }
        val vm = vm()
        val s = vm.state.value
        assertTrue(s.online)
        assertFalse(s.loading, "not an endless wait")
        assertTrue(s.unreachable, "said, with Try Again")
        assertFalse(s.unknownOffline)

        links.refreshResult = { AppResult.Success(null) }
        vm.load()
        assertFalse(vm.state.value.unreachable)
        assertTrue(vm.state.value.checked)
    }

    @Test fun aFailureIsShownAsAProblemAndASuccessAsDone() = runTest(dispatcher) {
        links.stored.value = existing
        val vm = vm()
        links.turnOffResult = { AppResult.Failure(api("internal.error", 500)) }
        vm.onConfirm(GroupLinkConfirm.TurnOff); vm.onConfirmed()
        assertTrue(vm.state.value.messageIsProblem)

        vm.onCopied()
        assertFalse(vm.state.value.messageIsProblem)

        links.turnOffResult = { AppResult.Success(Unit) }
        vm.onConfirm(GroupLinkConfirm.TurnOff); vm.onConfirmed()
        assertFalse(vm.state.value.messageIsProblem)
    }

    @Test fun aPhoneThatCannotReadTheNameCanTurnTheLinkOffButNotResetIt() = runTest(dispatcher) {
        links.stored.value = existing
        chat.value = group(nameKnown = false)
        val vm = vm()
        assertFalse(vm.state.value.canReset, "a reset publishes the name again, and this phone has none to publish")
        vm.onConfirm(GroupLinkConfirm.Reset); vm.onConfirmed()
        assertEquals(0, links.resets)

        vm.onConfirm(GroupLinkConfirm.TurnOff); vm.onConfirmed()
        assertEquals(1, links.turnedOff, "taking the public copy down never needs the name")
    }

    @Test fun resetAsksFirstThenReplacesTheLink() = runTest(dispatcher) {
        links.stored.value = existing
        val vm = vm()
        vm.onConfirm(GroupLinkConfirm.Reset)
        assertEquals(0, links.resets, "not before the confirm")
        vm.onConfirm(null)
        vm.onConfirmed()
        assertEquals(0, links.resets, "a dismissed confirm does nothing")

        vm.onConfirm(GroupLinkConfirm.Reset)
        vm.onConfirmed()

        assertEquals(1, links.resets)
        assertNull(vm.state.value.confirm)
        assertEquals("N3wT0kenN3wT0kenN3wT0k", vm.state.value.link?.token)
        assertEquals("New link made. The old one no longer works.", vm.state.value.message)
    }

    @Test fun turningOffRemovesTheLinkAndAFailureKeepsIt() = runTest(dispatcher) {
        links.stored.value = existing
        val vm = vm()
        links.turnOffResult = { AppResult.Failure(api("messaging.not_admin", 403)) }
        vm.onConfirm(GroupLinkConfirm.TurnOff); vm.onConfirmed()
        assertEquals(Url, vm.state.value.link?.url, "still on")
        assertTrue(vm.state.value.message!!.startsWith("Only the group's admins can manage its link."))

        links.turnOffResult = { AppResult.Success(Unit) }
        vm.onConfirm(GroupLinkConfirm.TurnOff); vm.onConfirmed()
        assertNull(vm.state.value.link)
        assertEquals("The link is off.", vm.state.value.message)
        vm.onMessageShown()
        assertNull(vm.state.value.message)
    }

    @Test fun oneChangeAtATime() = runTest(dispatcher) {
        links.stored.value = existing
        val gate = CompletableDeferred<Unit>()
        val vm = vm()
        // The reset is still on its way...
        val slow = FakeLinks().also { it.stored.value = existing }
        val waiting = GroupLinkViewModel("g1", ObserveChatUseCase(LinkScreenChats(chat)), object : GroupLinksRepository by slow {
            override suspend fun reset(chatId: String): AppResult<GroupLink> { gate.await(); return slow.reset(chatId) }
        }, connection)
        waiting.onConfirm(GroupLinkConfirm.Reset); waiting.onConfirmed()
        assertEquals(GroupLinkWork.Resetting, waiting.state.value.work)
        assertFalse(waiting.state.value.canChange)
        // ...so a second tap does nothing.
        waiting.onConfirm(GroupLinkConfirm.TurnOff); waiting.onConfirmed()
        assertEquals(0, slow.turnedOff)
        gate.complete(Unit)
        assertNull(waiting.state.value.work)
        assertEquals(1, slow.resets)
        assertTrue(vm.state.value.canChange)
    }

    @Test fun aMemberOrAPhoneWithoutTheNameCannotMakeALink() = runTest(dispatcher) {
        chat.value = group(MemberRole.Member)
        val vm = vm()
        assertFalse(vm.state.value.amAdmin)
        vm.onCreate()
        assertEquals(0, links.created)

        chat.value = group(nameKnown = false)
        assertTrue(vm.state.value.nameUnknown, "the screen says why and rests the button")
    }
}

/**
 * The join sheet (2026-11-14): a tapped link shows the group's published preview and Join Group; someone already in
 * goes straight to the chat; a link that no longer works says so; nothing is joined offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupJoinViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val os = MutableStateFlow(ConnectionStatus.Online)
    private val monitor = object : ConnectivityMonitor { override val osStatus = os }
    private lateinit var connection: ConnectionState
    private val links = FakeLinks()
    private val preview = GroupLinkPreview(Token, "Book club", "Fridays at six.", null, 24, member = false, chatId = null)

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm() = GroupJoinViewModel(links, connection)

    @Test fun aTappedLinkShowsTheGroupAndJoiningOpensItsChat() = runTest(dispatcher) {
        links.previewResult = { AppResult.Success(preview) }
        links.joinResult = { AppResult.Success(group(MemberRole.Member)) }
        val vm = vm()
        assertFalse(vm.state.value.open)

        vm.open(Token)
        assertTrue(vm.state.value.open)
        assertEquals(GroupJoinStage.Ready(preview), vm.state.value.stage)
        assertEquals(0, links.joins, "seeing the group is not joining it")

        vm.onJoin()
        assertEquals(1, links.joins)
        assertEquals(preview, links.joinedWith, "the chat is named from the preview until its key arrives")
        assertFalse(vm.state.value.open, "the sheet closes")
        assertEquals("g1", vm.state.value.openChatId)
        vm.onChatOpened()
        assertNull(vm.state.value.openChatId, "opened once")
    }

    @Test fun someoneAlreadyInTheGroupGoesStraightToTheChat() = runTest(dispatcher) {
        links.previewResult = { AppResult.Success(preview.copy(member = true, chatId = "g1")) }
        val vm = vm()
        vm.open(Token)
        assertFalse(vm.state.value.open, "no sheet: there is nothing to join")
        assertEquals("g1", vm.state.value.openChatId)
        assertEquals(0, links.joins)
    }

    @Test fun aLinkThatNoLongerWorksSaysSo() = runTest(dispatcher) {
        links.previewResult = { AppResult.Failure(api("messaging.link_not_found", 404)) }
        val vm = vm()
        vm.open(Token)
        assertEquals(GroupJoinStage.Gone, vm.state.value.stage)
        vm.onJoin()
        assertEquals(0, links.joins)
        vm.onDismiss()
        assertFalse(vm.state.value.open)
    }

    @Test fun aLinkResetBetweenLookingAndJoiningSaysSoToo() = runTest(dispatcher) {
        links.previewResult = { AppResult.Success(preview) }
        links.joinResult = { AppResult.Failure(api("messaging.link_not_found", 404)) }
        val vm = vm()
        vm.open(Token)
        vm.onJoin()
        assertEquals(GroupJoinStage.Gone, vm.state.value.stage)
        assertTrue(vm.state.value.open)
        assertNull(vm.state.value.openChatId)
    }

    @Test fun someoneWhoCannotComeInByLinkIsToldToAskAnAdmin() = runTest(dispatcher) {
        links.previewResult = { AppResult.Success(preview) }
        links.joinResult = { AppResult.Failure(api("messaging.link_not_allowed", 403)) }
        val vm = vm()
        vm.open(Token)
        vm.onJoin()
        assertEquals(GroupJoinStage.NotAllowed, vm.state.value.stage)
    }

    @Test fun aFullGroupKeepsThePreviewAndSaysWhy() = runTest(dispatcher) {
        links.previewResult = { AppResult.Success(preview) }
        links.joinResult = { AppResult.Failure(api("messaging.group_full", 409)) }
        val vm = vm()
        vm.open(Token)
        vm.onJoin()
        val stage = assertIs<GroupJoinStage.Ready>(vm.state.value.stage)
        assertEquals(preview, stage.preview)
        assertTrue(stage.problem!!.startsWith("This group is full."))
        assertFalse(vm.state.value.joining)

        // They can try again once someone leaves.
        links.joinResult = { AppResult.Success(group(MemberRole.Member)) }
        vm.onJoin()
        assertEquals("g1", vm.state.value.openChatId)
    }

    @Test fun tooManyTriesIsSaidInTheLinksOwnWords() = runTest(dispatcher) {
        links.previewResult = { AppResult.Failure(api("messaging.rate_limited", 429)) }
        val vm = vm()
        vm.open(Token)
        val stage = assertIs<GroupJoinStage.Failed>(vm.state.value.stage)
        assertTrue(stage.text.startsWith("Too many tries. Please wait a moment."), "not the new-chats wording")
    }

    @Test fun offlineItWaitsAndOpensByItselfWhenBackOnline() = runTest(dispatcher) {
        os.value = ConnectionStatus.Offline
        links.previewResult = { AppResult.Failure(AppError.Network) }
        val vm = vm()
        vm.open(Token)
        assertEquals(GroupJoinStage.Offline, vm.state.value.stage)
        assertFalse(vm.state.value.canJoin)

        links.previewResult = { AppResult.Success(preview) }
        os.value = ConnectionStatus.Online

        assertEquals(GroupJoinStage.Ready(preview), vm.state.value.stage)
        assertEquals(2, links.previews)
    }

    @Test fun noAnswerWhileOnlineOffersTryAgainRatherThanWaiting() = runTest(dispatcher) {
        links.previewResult = { AppResult.Failure(AppError.Network) }
        val vm = vm()
        vm.open(Token)
        assertEquals(GroupJoinStage.Offline, vm.state.value.stage)
        assertTrue(vm.state.value.online, "the sheet shows Try Again here: nothing would retry by itself")

        links.previewResult = { AppResult.Success(preview) }
        vm.load()
        assertEquals(GroupJoinStage.Ready(preview), vm.state.value.stage)
    }

    @Test fun joiningNeverFiresOfflineAndADroppedJoinIsNotFaked() = runTest(dispatcher) {
        links.previewResult = { AppResult.Success(preview) }
        val vm = vm()
        vm.open(Token)
        os.value = ConnectionStatus.Offline
        vm.onJoin()
        assertEquals(0, links.joins, "the button rests offline")

        os.value = ConnectionStatus.Online
        links.joinResult = { AppResult.Failure(AppError.Network) }
        vm.onJoin()
        val stage = assertIs<GroupJoinStage.Ready>(vm.state.value.stage)
        assertEquals(JoinUnreachable, stage.problem, "said as a failed try, with a way to try again")
        assertNull(vm.state.value.openChatId, "not in the group, and the sheet doesn't pretend")
    }

    @Test fun aSecondLinkReplacesTheFirstAndAnOldAnswerIsIgnored() = runTest(dispatcher) {
        val first = CompletableDeferred<AppResult<GroupLinkPreview>>()
        var call = 0
        links.previewResult = { if (call++ == 0) first.await() else AppResult.Success(preview.copy(token = "N3wT0kenN3wT0kenN3wT0k", name = "Runners")) }
        val vm = vm()
        vm.open(Token)
        assertEquals(GroupJoinStage.Loading, vm.state.value.stage)

        vm.open("N3wT0kenN3wT0kenN3wT0k")
        first.complete(AppResult.Success(preview))

        assertEquals("N3wT0kenN3wT0kenN3wT0k", vm.state.value.token)
        assertEquals("Runners", (vm.state.value.stage as GroupJoinStage.Ready).preview.name)
    }

    @Test fun membersAreCountedInWords() {
        assertEquals("1 member", memberCountText(1))
        assertEquals("24 members", memberCountText(24))
    }
}

/**
 * Group info's Edit group sheet (2026-11-14): the description is edited where the name is, saved with it in one
 * change, limited to 500 characters, and never sent offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditGroupDescriptionTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val os = MutableStateFlow(ConnectionStatus.Online)
    private val monitor = object : ConnectivityMonitor { override val osStatus = os }
    private lateinit var connection: ConnectionState
    private val chat = MutableStateFlow<Chat?>(group().copy(description = "Fridays at six."))
    private val repo = LinkScreenChats(chat)

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm(links: GroupLinksRepository? = null) = com.circaso.feature.messaging.presentation.ChatInfoViewModel(
        "g1", ObserveChatUseCase(repo), com.circaso.feature.contacts.domain.ObserveContactsUseCase(com.circaso.feature.status.FakeContacts()),
        com.circaso.feature.messaging.domain.SecurityCodeUseCase(repo), com.circaso.feature.messaging.domain.RenameGroupUseCase(repo),
        com.circaso.feature.messaging.domain.SetMembersCanAddUseCase(repo), com.circaso.feature.messaging.domain.AddMembersUseCase(repo),
        com.circaso.feature.messaging.domain.RemoveMemberUseCase(repo), com.circaso.feature.messaging.domain.LeaveGroupUseCase(repo),
        com.circaso.feature.messaging.domain.SetRoleUseCase(repo), connection,
        editGroup = com.circaso.feature.messaging.domain.EditGroupUseCase(repo), links = links,
    )

    @Test fun theSheetOpensOnTheGroupsNameAndDescription() = runTest(dispatcher) {
        val vm = vm()
        vm.onRename(true)
        val s = vm.state.value
        assertTrue(s.renaming)
        assertEquals("Book club", s.nameDraft)
        assertEquals("Fridays at six.", s.descriptionDraft)
        assertFalse(s.editChanged, "nothing to save yet")
    }

    @Test fun aNewDescriptionIsSavedWithTheNameInOneChange() = runTest(dispatcher) {
        val vm = vm()
        vm.onRename(true)
        vm.onDescriptionDraft("Fridays at six. Bring a book.")
        assertTrue(vm.state.value.editChanged)

        vm.onRenameConfirm()

        assertEquals(listOf("Book club" to "Fridays at six. Bring a book."), repo.edits)
        assertTrue(repo.renames.isEmpty(), "one change, not a rename and then another")
        assertFalse(vm.state.value.renaming)
        assertEquals("Fridays at six. Bring a book.", vm.state.value.chat?.description)
    }

    @Test fun clearingTheDescriptionRemovesIt() = runTest(dispatcher) {
        val vm = vm()
        vm.onRename(true)
        vm.onDescriptionDraft("  ")
        assertTrue(vm.state.value.editChanged)
        vm.onRenameConfirm()
        assertNull(vm.state.value.chat?.description)
    }

    @Test fun aDescriptionOverFiveHundredCharactersIsNotSent() = runTest(dispatcher) {
        val vm = vm()
        vm.onRename(true)
        vm.onDescriptionDraft("d".repeat(501))
        assertTrue(vm.state.value.descriptionTooLong)
        vm.onRenameConfirm()
        assertTrue(repo.edits.isEmpty())
        assertTrue(vm.state.value.renaming, "the sheet stays, saying why")

        vm.onDescriptionDraft("d".repeat(500))
        assertFalse(vm.state.value.descriptionTooLong)
        vm.onRenameConfirm()
        assertEquals(1, repo.edits.size)
    }

    @Test fun aSaveThatFailsKeepsTheSheetAndWhatWasTyped() = runTest(dispatcher) {
        repo.failEdits = AppError.Api("internal.error", 500, "req_9", null, emptyMap())
        val vm = vm()
        vm.onRename(true)
        vm.onDescriptionDraft("Saturdays now.")
        vm.onRenameConfirm()

        val s = vm.state.value
        assertTrue(s.renaming, "the sheet stays")
        assertEquals("Saturdays now.", s.descriptionDraft, "nothing typed is lost")
        assertTrue(s.editError!!.contains("req_9"), "said in the sheet, with its reference")
        assertFalse(s.saving)

        repo.failEdits = null
        vm.onRenameConfirm()
        assertFalse(vm.state.value.renaming)
        assertEquals("Saturdays now.", vm.state.value.chat?.description)
    }

    @Test fun aSaveOnItsWayHoldsTheSheetOpen() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        repo.beforeEdit = { gate.await() }
        val vm = vm()
        vm.onRename(true)
        vm.onDescriptionDraft("Saturdays now.")
        vm.onRenameConfirm()
        assertTrue(vm.state.value.saving)
        vm.onRename(false)
        assertTrue(vm.state.value.renaming, "it closes when the server answers, not before")
        gate.complete(Unit)
        assertFalse(vm.state.value.renaming)
        assertFalse(vm.state.value.saving)
    }

    @Test fun aPhoneThatCannotReadTheNameKeepsTheDescriptionItHas() = runTest(dispatcher) {
        chat.value = group(nameKnown = false).copy(description = "Fridays at six.")
        val vm = vm()
        vm.onRename(true)
        vm.onNameDraft("Book club")
        vm.onRenameConfirm()
        assertEquals(listOf("Book club" to "Fridays at six."), repo.edits, "never replaced by the field it couldn't fill")
    }

    @Test fun groupInfoKnowsWhenTheLinkIsOn() = runTest(dispatcher) {
        val links = FakeLinks()
        val vm = vm(links)
        assertFalse(vm.state.value.linkOn)
        links.stored.value = GroupLink("g1", Token, Url, "Book club", null, false)
        assertTrue(vm.state.value.linkOn, "so the Edit group sheet says the link's page shows the change too")
    }

    @Test fun nothingIsSentOffline() = runTest(dispatcher) {
        val vm = vm()
        vm.onRename(true)
        vm.onDescriptionDraft("Saturdays now.")
        os.value = ConnectionStatus.Offline
        vm.onRenameConfirm()
        assertTrue(repo.edits.isEmpty())
        assertFalse(vm.state.value.canChange)
    }
}
