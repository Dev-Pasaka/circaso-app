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
import com.circaso.feature.status.domain.toQuote
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

    // ---- money for a moment, chat native (2026-11-14): `#500` in the reply field

    private val opened = mutableListOf<String>()
    private var localChat: String? = "chat-w"
    private var serverChat: String? = "chat-new"
    private val replies = mutableListOf<Pair<String, String>>()

    private fun moneyViewer(posterId: String? = "w") = MomentViewerViewModel(
        posterId = posterId, repository = repo, observeContacts = ObserveContactsUseCase(FakeContacts()), observeProfile = ObserveProfileUseCase(FakeProfile()),
        connection = connection, replyInChat = { to, text, _ -> replies += to to text; AppResult.Success("c") },
        directChatId = { localChat }, openChat = { opened += it; serverChat },
    )

    @Test fun anAmountInTheReplyFieldPaysWhoeverPostedItWithTheMomentQuoted() = runTest(dispatcher) {
        repo.list.value = listOf(moment("m1").copy(caption = "Sunset at Diani", thumb = "dGh1bWI="))
        val vm = moneyViewer()
        assertNull(vm.state.value.moneyTarget, "nothing is made until a # is typed")
        vm.onReply("#500 lunch")
        val s = vm.state.value
        val target = s.moneyTarget!!
        assertEquals("w", target.userId)
        assertEquals("Wanjiru Kamau", target.displayName)
        assertEquals("chat-w", target.chatId)
        assertEquals("m1", target.momentId)
        // The same quote a text reply carries.
        assertEquals(s.moment!!.toQuote(), target.quote)
        assertEquals(com.circaso.core.crypto.MomentQuote("m1", "TEXT", "Sunday market", "dGh1bWI=", Long.MAX_VALUE), target.quote)
        assertTrue(opened.isEmpty(), "the chat on this phone is used as it is")
        assertNull(s.moneyLine)
        // The arrow pays (the composer's money card); it never sends "#500 lunch" as words.
        assertFalse(s.canReply)
        vm.sendReply()
        assertTrue(replies.isEmpty())
        assertEquals("#500 lunch", vm.state.value.reply)
    }

    @Test fun plainWordsAreStillAReplyAndGetNoChatReady() = runTest(dispatcher) {
        val vm = moneyViewer()
        vm.onReply("So beautiful")
        assertTrue(vm.state.value.canReply)
        assertNull(vm.state.value.moneyTarget)
        assertNull(vm.state.value.moneyLine)
        vm.sendReply()
        assertEquals(listOf("w" to "So beautiful"), replies)
        assertEquals("", vm.state.value.reply)
        assertTrue(opened.isEmpty())
    }

    @Test fun withNoChatOnThisPhoneAHashOpensItAndTheFieldSaysSoMeanwhile() = runTest(dispatcher) {
        localChat = null
        val gate = kotlinx.coroutines.CompletableDeferred<String?>()
        val vm = MomentViewerViewModel(
            posterId = "w", repository = repo, observeContacts = ObserveContactsUseCase(FakeContacts()), observeProfile = ObserveProfileUseCase(FakeProfile()),
            connection = connection, replyInChat = { to, text, _ -> replies += to to text; AppResult.Success("c") },
            directChatId = { null }, openChat = { opened += it; gate.await() },
        )
        vm.onReply("#")
        assertEquals(listOf("w"), opened)
        vm.onReply("#500")
        assertEquals(listOf("w"), opened, "asked once while it opens")
        assertEquals("Opening the chat with Wanjiru…", vm.state.value.moneyLine)
        assertNull(vm.state.value.moneyTarget)
        assertFalse(vm.state.value.canReply, "the amount is not sent as words while the chat opens")
        gate.complete("chat-new")
        assertEquals("chat-new", vm.state.value.moneyTarget?.chatId)
        assertNull(vm.state.value.moneyLine)
    }

    @Test fun aChatThatWontOpenIsSaidAndATapOnTheLineTriesAgain() = runTest(dispatcher) {
        localChat = null; serverChat = null
        val vm = moneyViewer()
        vm.onReply("#500")
        assertNull(vm.state.value.moneyTarget)
        assertEquals("The chat with Wanjiru didn’t open, so money can’t go yet. Tap to try again.", vm.state.value.moneyLine)
        assertTrue(vm.state.value.moneyRetry)
        assertFalse(vm.state.value.canReply)
        // Typing on doesn't ask the server again at every key.
        vm.onReply("#5000")
        assertEquals(listOf("w"), opened)
        serverChat = "chat-new"
        vm.retryMoney()
        assertEquals(listOf("w", "w"), opened)
        assertEquals("chat-new", vm.state.value.moneyTarget?.chatId)
        assertNull(vm.state.value.moneyLine)
        // Without the amount it is a reply again.
        vm.onReply("lovely")
        assertTrue(vm.state.value.canReply)
    }

    @Test fun backOnlineTheChatIsOpenedForTheAmountStillInTheField() = runTest(dispatcher) {
        localChat = null
        monitor.osStatus.value = ConnectionStatus.Offline
        val vm = moneyViewer()
        vm.onReply("#500")
        assertEquals("You’re offline. Money needs a connection.", vm.state.value.moneyLine)
        assertFalse(vm.state.value.moneyRetry, "nothing to tap offline")
        monitor.osStatus.value = ConnectionStatus.Online
        assertEquals(listOf("w"), opened)
        assertEquals("chat-new", vm.state.value.moneyTarget?.chatId)
        assertNull(vm.state.value.moneyLine)
    }

    @Test fun aTagIsNotMoneyAndZeroIsOnlyWords() = runTest(dispatcher) {
        localChat = null
        val vm = moneyViewer()
        vm.onReply("#blessed")
        assertTrue(opened.isEmpty(), "no chat is opened for a tag")
        assertNull(vm.state.value.moneyLine)
        assertTrue(vm.state.value.canReply)
        localChat = "chat-w"
        vm.onReply("#0")
        assertTrue(vm.state.value.canReply, "as in a chat, #0 is not an amount: the arrow sends it as words")
    }

    @Test fun aTapOrAHoldOnTheMomentNeverRestartsItUnderWhatIsBeingWritten() = runTest(dispatcher) {
        val vm = moneyViewer()
        vm.onReplyFocus(true)
        vm.setPaused(true); vm.setPaused(false)
        assertTrue(vm.state.value.paused, "the field has the keyboard")
        vm.onReply("#500 lunch")
        vm.onReplyFocus(false)
        vm.setPaused(true); vm.setPaused(false)
        assertTrue(vm.state.value.paused, "money is in the field")
        assertEquals("#500 lunch", vm.state.value.reply)
        vm.onReply("")
        assertFalse(vm.state.value.paused)
    }

    @Test fun aMomentThatEndsWhileThePinIsUpStaysUntilThePaymentIsThrough() = runTest(dispatcher) {
        val vm = moneyViewer()
        vm.onReply("#500")
        vm.onPayBusy(true)
        repo.list.value = emptyList()
        assertFalse(vm.state.value.closed)
        assertEquals("m1", vm.state.value.moneyTarget?.momentId, "the payment keeps the moment it was made for")
        vm.onPaid()
        assertTrue(vm.state.value.closed)
    }

    @Test fun offlineWithNoChatTheFieldSaysMoneyNeedsAConnection() = runTest(dispatcher) {
        localChat = null
        monitor.osStatus.value = ConnectionStatus.Offline
        val vm = moneyViewer()
        assertFalse(vm.state.value.online)
        vm.onReply("#500")
        assertTrue(opened.isEmpty(), "nothing is asked of the server offline")
        assertEquals("You’re offline. Money needs a connection.", vm.state.value.moneyLine)
    }

    @Test fun theMomentWaitsWhileTypingAndThroughThePinThenCarriesOn() = runTest(dispatcher) {
        val vm = moneyViewer()
        vm.onReply("#500")
        assertTrue(vm.state.value.waiting, "typing, with the money card up")
        vm.onPayBusy(true)
        // The keyboard going away for the PIN, or a finger lifting, doesn't start it again; nor can it move on.
        vm.onReplyFocus(false)
        vm.setPaused(false)
        vm.onReply("")
        assertTrue(vm.state.value.waiting)
        vm.next()
        assertFalse(vm.state.value.closed)
        vm.onPaid()
        assertFalse(vm.state.value.waiting)
        assertEquals("", vm.state.value.reply)
        assertEquals("Money for Wanjiru is on its way. It’s in your chat.", vm.state.value.notice)
    }

    @Test fun onMyOwnMomentAHashIsNeverMoney() = runTest(dispatcher) {
        repo.list.value = listOf(moment("mine").copy(mine = true, posterId = "me"))
        val vm = moneyViewer(posterId = null)
        assertFalse(vm.state.value.canPay)
        vm.onReply("#500")
        assertNull(vm.state.value.moneyTarget)
        assertNull(vm.state.value.moneyLine)
        assertTrue(opened.isEmpty())
    }

    @Test fun onCircasoSupportsMomentAnAmountIsWordsAndTheFieldSaysPaymentsAreOff() = runTest(dispatcher) {
        val before = com.circaso.feature.support.Support.UserId
        com.circaso.feature.support.Support.UserId = "w"
        try {
            val vm = moneyViewer()
            assertFalse(vm.state.value.canPay)
            vm.onReply("#500")
            assertNull(vm.state.value.moneyTarget)
            assertTrue(opened.isEmpty())
            assertEquals("Circaso support never asks for money, so payments are off here.", vm.state.value.moneyLine)
            assertTrue(vm.state.value.canReply)
        } finally { com.circaso.feature.support.Support.UserId = before }
    }

    @Test fun eachMomentGetsItsOwnPaymentSoEachCardCarriesItsOwnMoment() = runTest(dispatcher) {
        repo.list.value = listOf(moment("m1"), moment("m2").copy(postedAt = 2))
        val vm = moneyViewer()
        vm.onReply("#500")
        assertEquals("m1", vm.state.value.moneyTarget?.momentId)
        vm.onReply("")
        vm.next()
        assertEquals("", vm.state.value.reply)
        assertEquals("m2", vm.state.value.moneyTarget?.momentId, "the same person's chat is still known")
    }

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

internal fun moment(id: String) = Moment(
    momentId = id, posterId = "w", kind = MomentKind.Text, text = "Sunday market", postedAt = 1, expiresAt = Long.MAX_VALUE,
    mine = false, viewed = true, reacted = false,
)

/** The phone's moments; a reaction shows at once and goes back if the server says no, like the real repository. */
internal class ViewerFakeMoments : MomentsRepository {
    val list = MutableStateFlow(listOf(moment("m1")))
    val reactions = mutableListOf<Pair<String, String?>>()
    var result: AppResult<Unit> = AppResult.Success(Unit)
    fun setReaction(id: String, emoji: String?) = list.update { all -> all.map { if (it.momentId == id) it.copy(reaction = emoji) else it } }

    override val moments: Flow<List<Moment>> = list
    val lockedList = MutableStateFlow<List<com.circaso.feature.status.domain.LockedMoment>>(emptyList())
    override val locked: Flow<List<com.circaso.feature.status.domain.LockedMoment>> = lockedList
    val pendingList = MutableStateFlow<List<PendingMoment>>(emptyList())
    override val pending: Flow<List<PendingMoment>> = pendingList
    var resumes = 0
    override suspend fun resume() { resumes++ }
    override val privacy: Flow<MomentPrivacy> = MutableStateFlow(MomentPrivacy())
    override val muted: Flow<Set<String>> = MutableStateFlow(emptySet())
    var refreshResult: AppResult<Unit> = AppResult.Success(Unit)
    var refreshes = 0
    override suspend fun refresh(): AppResult<Unit> { refreshes++; return refreshResult }
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

internal class FakeContacts : ContactsRepository {
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

internal class FakeProfile : ProfileRepository {
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
