package com.circaso.feature.status

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.ConnectVia
import com.circaso.feature.contacts.domain.Connection
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.Invite
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.contacts.domain.SyncSummary
import com.circaso.feature.profile.domain.AvatarImage
import com.circaso.feature.profile.domain.HandleAvailability
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.profile.domain.Profile
import com.circaso.feature.profile.domain.ProfileChange
import com.circaso.feature.profile.domain.ProfileRepository
import com.circaso.feature.profile.domain.PublicProfile
import com.circaso.feature.status.domain.Moment
import com.circaso.feature.status.domain.MomentDraft
import com.circaso.feature.status.domain.MomentKind
import com.circaso.feature.status.domain.MomentMediaLoad
import com.circaso.feature.status.domain.MomentPrivacy
import com.circaso.feature.status.domain.MomentViewer
import com.circaso.feature.status.domain.MomentsRepository
import com.circaso.feature.status.domain.PendingMoment
import com.circaso.feature.status.presentation.MomentViewerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
class MomentViewerViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val monitor = object : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }
    private lateinit var connection: ConnectionState
    private val repo = ViewerFakeMoments()

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewer() = MomentViewerViewModel(
        posterId = "w", repository = repo, observeContacts = ObserveContactsUseCase(FakeContacts()), observeProfile = ObserveProfileUseCase(FakeProfile()),
        connection = connection, replyInChat = { _, _, _ -> AppResult.Success("c") },
    )

    @Test fun openingThePickerPausesTheMomentAndClosingItCarriesOn() = runTest(dispatcher) {
        val vm = viewer()
        vm.openPicker()
        assertTrue(vm.state.value.picking)
        assertTrue(vm.state.value.paused)
        vm.closePicker()
        assertFalse(vm.state.value.picking)
        assertFalse(vm.state.value.paused)
    }

    @Test fun anyEmojiReactsAndTheMomentCarriesOn() = runTest(dispatcher) {
        val vm = viewer()
        vm.openPicker()
        vm.react("🦒")
        assertEquals(listOf<Pair<String, String?>>("m1" to "🦒"), repo.reactions)
        assertEquals("🦒", vm.state.value.moment?.reaction)
        assertFalse(vm.state.value.picking)
        assertFalse(vm.state.value.paused)
    }

    @Test fun pickingMyEmojiAgainOrNullTakesItBack() = runTest(dispatcher) {
        repo.setReaction("m1", "🔥")
        val vm = viewer()
        vm.react("🔥")
        assertNull(vm.state.value.moment?.reaction)
        // Nothing left to take back: the server isn't asked again.
        vm.react(null)
        assertEquals(listOf<Pair<String, String?>>("m1" to null), repo.reactions)
        repo.setReaction("m1", "👍")
        vm.react(null)
        assertEquals(listOf<Pair<String, String?>>("m1" to null, "m1" to null), repo.reactions)
    }

    @Test fun thePickerClosesWhenTheMomentMovesOn() = runTest(dispatcher) {
        repo.list.value = listOf(moment("m1"), moment("m2").copy(postedAt = 2))
        val vm = viewer()
        vm.openPicker()
        vm.next()
        assertEquals("m2", vm.state.value.moment?.momentId)
        assertFalse(vm.state.value.picking)
        assertFalse(vm.state.value.paused)
    }

    @Test fun aPickAfterTheConnectionDroppedIsNotSent() = runTest(dispatcher) {
        val vm = viewer()
        vm.openPicker()
        monitor.osStatus.value = ConnectionStatus.Offline
        vm.react("👍")
        assertFalse(vm.state.value.picking)
        assertFalse(vm.state.value.paused)
        assertEquals("Reactions need a connection.", vm.state.value.notice)
        assertTrue(repo.reactions.isEmpty())
    }

    @Test fun aReactionTheServerDidNotTakeIsUndoneAndSaid() = runTest(dispatcher) {
        repo.result = AppResult.Failure(AppError.Network)
        val vm = viewer()
        vm.react("😂")
        assertNull(vm.state.value.moment?.reaction)
        assertEquals("You’re offline. Try again when you’re connected.", vm.state.value.notice)
    }

    @Test fun offlineThePickerDoesNotOpenAndNothingIsSent() = runTest(dispatcher) {
        val vm = viewer()
        monitor.osStatus.value = ConnectionStatus.Offline
        vm.openPicker()
        assertFalse(vm.state.value.picking)
        assertEquals("Reactions need a connection.", vm.state.value.notice)
        vm.react("👍")
        assertTrue(repo.reactions.isEmpty())
    }

    @Test fun myOwnMomentTakesNoReaction() = runTest(dispatcher) {
        repo.list.value = listOf(moment("mine").copy(mine = true, posterId = "me"))
        val vm = MomentViewerViewModel(
            posterId = null, repository = repo, observeContacts = ObserveContactsUseCase(FakeContacts()), observeProfile = ObserveProfileUseCase(FakeProfile()),
            connection = connection, replyInChat = { _, _, _ -> AppResult.Success("c") },
        )
        vm.openPicker()
        vm.react("👍")
        assertFalse(vm.state.value.picking)
        assertTrue(repo.reactions.isEmpty())
    }
}

private fun moment(id: String) = Moment(
    momentId = id, posterId = "w", kind = MomentKind.Text, text = "Sunday market", postedAt = 1, expiresAt = Long.MAX_VALUE,
    mine = false, viewed = true, reacted = false,
)

/** The phone's moments; a reaction shows at once and goes back if the server says no, like the real repository. */
private class ViewerFakeMoments : MomentsRepository {
    val list = MutableStateFlow(listOf(moment("m1")))
    val reactions = mutableListOf<Pair<String, String?>>()
    var result: AppResult<Unit> = AppResult.Success(Unit)
    fun setReaction(id: String, emoji: String?) = list.update { all -> all.map { if (it.momentId == id) it.copy(reaction = emoji) else it } }

    override val moments: Flow<List<Moment>> = list
    override val pending: Flow<List<PendingMoment>> = MutableStateFlow(emptyList())
    override val privacy: Flow<MomentPrivacy> = MutableStateFlow(MomentPrivacy())
    override val muted: Flow<Set<String>> = MutableStateFlow(emptySet())
    override suspend fun refresh(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun post(draft: MomentDraft): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun retry(localId: String) = Unit
    override suspend fun discard(localId: String) = Unit
    override suspend fun markViewed(momentId: String, reacted: Boolean?): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun react(momentId: String, emoji: String?): AppResult<Unit> {
        reactions += momentId to emoji
        val before = list.value.first { it.momentId == momentId }.reaction
        setReaction(momentId, emoji)
        return result.also { if (it is AppResult.Failure) setReaction(momentId, before) }
    }
    override suspend fun viewers(momentId: String): AppResult<List<MomentViewer>> = AppResult.Success(emptyList())
    override suspend fun delete(momentId: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun media(moment: Moment): MomentMediaLoad = MomentMediaLoad.NeedsConnection
    override suspend fun setPrivacy(privacy: MomentPrivacy) = Unit
    override suspend fun setMuted(userId: String, muted: Boolean) = Unit
    override suspend fun onFrame(type: String, momentId: String?, posterId: String?, requesterId: String?) = Unit
}

private class FakeContacts : ContactsRepository {
    override val contacts: Flow<List<Contact>> = MutableStateFlow(listOf(Contact(Person("w", "Wanjiru Kamau", "wanjiru", null, null), "phone", "2026-10-01")))
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

private class FakeProfile : ProfileRepository {
    override val profile: Flow<Profile?> = MutableStateFlow(null)
    override suspend fun refresh() = AppResult.Success(Unit)
    override suspend fun update(change: ProfileChange): Boolean = true
    override suspend fun checkHandle(handle: String): AppResult<HandleAvailability> = AppResult.Failure(AppError.Network)
    override suspend fun claimHandle(handle: String): AppResult<Unit> = AppResult.Failure(AppError.Network)
    override suspend fun uploadAvatar(image: AvatarImage): AppResult<Unit> = AppResult.Failure(AppError.Network)
    override suspend fun removeAvatar(): AppResult<Unit> = AppResult.Failure(AppError.Network)
    override suspend fun findByHandle(handle: String): AppResult<PublicProfile> = AppResult.Failure(AppError.Network)
    override suspend fun publicProfile(userId: String): AppResult<PublicProfile> = AppResult.Failure(AppError.Network)
}
