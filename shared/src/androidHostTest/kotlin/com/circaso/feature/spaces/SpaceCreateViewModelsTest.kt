package com.circaso.feature.spaces

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.flags.SpacesFlag
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.core.sync.Outbox
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.spaces.data.remote.HandleAvailabilityDto
import com.circaso.feature.spaces.data.remote.MeDto
import com.circaso.feature.spaces.data.remote.UpdatedSpaceDto
import com.circaso.feature.spaces.data.repository.SpacesRepositoryImpl
import com.circaso.feature.spaces.domain.PricePeriod
import com.circaso.feature.spaces.domain.PostingMode
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.domain.SpaceTopic
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.presentation.create.CreateProblem
import com.circaso.feature.spaces.presentation.create.HandleState
import com.circaso.feature.spaces.presentation.create.NewSpaceViewModel
import com.circaso.feature.spaces.presentation.create.SpaceEditMode
import com.circaso.feature.spaces.presentation.create.SpaceEditViewModel
import com.circaso.feature.spaces.presentation.create.SpaceSetupProblem
import com.circaso.feature.spaces.presentation.create.SpaceSetupStep
import com.circaso.feature.spaces.presentation.create.SpaceWalletSetupViewModel
import com.circaso.feature.spaces.presentation.create.handleProblem
import com.circaso.feature.wallet.data.repository.toActivity
import com.circaso.feature.wallet.data.repository.toDomain
import com.circaso.feature.wallet.domain.AddWalletNetworksUseCase
import com.circaso.feature.wallet.domain.AwaitSpaceWalletUseCase
import com.circaso.feature.wallet.domain.EnableWalletBiometricsUseCase
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.SignPurpose
import com.circaso.feature.wallet.domain.SignSubject
import com.circaso.feature.wallet.domain.SpaceWallet
import com.circaso.feature.wallet.domain.StartSpaceWalletUseCase
import com.circaso.feature.wallet.domain.WalletListing
import com.circaso.feature.wallet.domain.WalletOwner
import com.circaso.feature.wallet.domain.WalletOwnerType
import com.circaso.feature.wallet.domain.WalletRepository
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.presentation.NamedWallet
import com.circaso.feature.wallet.presentation.SpaceWalletName
import com.circaso.feature.wallet.presentation.nameWallets
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.FakeSpacesDao
import com.circaso.testing.FakeSpacesRemote
import com.circaso.testing.SpaceSamples
import com.circaso.testing.apiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private inline fun <reified T : Any> fake(noinline answer: (String, List<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        val list = args.orEmpty().toList().filterNot { it is kotlin.coroutines.Continuation<*> }
        when (method.name) {
            "toString" -> "fake ${T::class.simpleName}"
            "hashCode" -> 0
            "equals" -> false
            else -> answer(method.name, list)
        }
    } as T

private class CreateTestMonitor(online: Boolean = true) : ConnectivityMonitor { override val osStatus = MutableStateFlow(if (online) ConnectionStatus.Online else ConnectionStatus.Offline) }

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceCreateViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val dao = FakeSpacesDao()
    private val remote = FakeSpacesRemote()
    private lateinit var connection: ConnectionState
    private val repo by lazy {
        SpacesRepositoryImpl(dao, remote, SpacesFlag.on(), Outbox(FakeOutboxDao(), owner = { "me" }), {}, json, { "me" }, { null })
    }
    private val contacts = fake<ContactsRepository> { name, _ ->
        if (name == "getContacts") flowOf(listOf(Contact(Person("achieng", "Achieng Odhiambo", "achieng", null, null), "phone", ""), Contact(Person("faith", "Faith Chebet", "faithc", null, null), "phone", ""))) else null
    }
    private val walletCalls = mutableListOf<String>()
    private val subjects = mutableListOf<SignSubject>()
    private var startAnswer: AppResult<SpaceWallet> = AppResult.Success(SpaceWallet("s9", WalletStatus.SettingUp, listing(WalletStatus.SettingUp)))
    private val walletRepo = fake<WalletRepository> { name, args ->
        walletCalls += name
        when (name) {
            "getWallet" -> flowOf(null)
            "startSpaceWallet" -> { subjects += SignSubject.space("sw-9", args[1] as String, SignPurpose.SetPin); startAnswer }
            "awaitSpaceWallet" -> AppResult.Success(SpaceWallet("s9", WalletStatus.Active, listing(WalletStatus.Active)))
            "enableBiometricApproval", "addMissingChains" -> { subjects += args[0] as SignSubject; AppResult.Success(Unit) }
            else -> null
        }
    }

    private fun listing(status: WalletStatus) = WalletListing("sw-9", WalletOwner(WalletOwnerType.Space, null, null, "me", "s9"), status, true, emptyList())

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        connection = ConnectionState(CreateTestMonitor(), NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0)
    }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun newSpace(name: String? = null) = NewSpaceViewModel(name, repo, ObserveContactsUseCase(contacts), ObserveWalletUseCase(walletRepo), connection, picker = null, debounceMillis = 10)

    private fun NewSpaceViewModel.fillDetails() {
        onName("Kilimani Yoga Collective")
        onAbout("Sunrise yoga in Kilimani")
        onTopic(SpaceTopic.Health)
    }

    @Test fun theHandleFollowsTheNameUntilTypedAndIsCheckedLive() = runTest(dispatcher) {
        val vm = newSpace()
        vm.onName("Kilimani Yoga")
        assertEquals("kilimaniyoga", vm.state.value.handle)
        assertEquals(HandleState.Checking, vm.state.value.handleState)
        advanceUntilIdle()
        assertEquals(HandleState.Available, vm.state.value.handleState)
        remote.handle = { AppResult.Success(HandleAvailabilityDto(it, false, listOf("kilimaniyoga.ke", "yogakilimani", "kilimaniyogahub", "extra"))) }
        vm.onHandle("@KilimaniYoga")
        advanceUntilIdle()
        assertEquals(HandleState.Taken(listOf("kilimaniyoga.ke", "yogakilimani", "kilimaniyogahub")), vm.state.value.handleState)
        vm.onName("Something else")
        assertEquals("kilimaniyoga", vm.state.value.handle, "a typed handle stays")
        // A bad handle is said at once and never sent.
        val before = remote.calls.size
        vm.onHandle("9lives")
        assertTrue(vm.state.value.handleState is HandleState.Invalid)
        advanceUntilIdle()
        assertEquals(before, remote.calls.size)
        assertEquals("Start with a letter.", handleProblem("9lives"))
    }

    @Test fun nextWaitsForAFreeHandleAndTheTopicAndMembersOnlyNeedsAPrice() = runTest(dispatcher) {
        val vm = newSpace()
        vm.onName("Kilimani Yoga Collective")
        advanceUntilIdle()
        assertFalse(vm.state.value.canContinue, "no topic yet")
        vm.onTopic(SpaceTopic.Health)
        assertTrue(vm.state.value.canContinue)
        vm.onNext()
        assertEquals(2, vm.state.value.step)
        vm.onVisibility(SpaceVisibility.MembersOnly)
        assertFalse(vm.state.value.canContinue)
        vm.onPrice("0.05")
        assertEquals("At least 0.10 USDC.", vm.state.value.priceProblem)
        vm.onPrice("10000.01")
        assertEquals("At most 10,000 USDC.", vm.state.value.priceProblem)
        vm.onPrice("10.00")
        assertNull(vm.state.value.priceProblem)
        assertEquals("10000000", vm.state.value.priceMinor)
        vm.onNext()
        assertEquals(3, vm.state.value.step)
        assertTrue(vm.onBack())
        assertEquals(2, vm.state.value.step)
    }

    @Test fun createSendsTheDraftAndOpensTheWalletPin() = runTest(dispatcher) {
        remote.createAnswer = AppResult.Success(SpaceSamples.space("s9", "Kilimani Yoga Collective", "kilimaniyoga", "MEMBERS_ONLY", state = "SETTING_UP", me = MeDto("MEMBER", "OWNER")))
        val vm = newSpace()
        vm.fillDetails(); advanceUntilIdle()
        vm.onNext(); vm.onVisibility(SpaceVisibility.MembersOnly); vm.onPrice("10"); vm.onPeriod(PricePeriod.Monthly); vm.onNext()
        vm.onPostingMode(PostingMode.AdminsOnly)
        val achieng = vm.state.value.contacts.first()
        vm.onToggle(achieng)
        assertEquals(SpaceRole.Moderator, vm.state.value.team.single().role, "a pick starts with the least power")
        vm.onRole(achieng.userId, SpaceRole.Admin)
        vm.onNext()
        advanceUntilIdle()
        assertEquals("s9", vm.created.value)
        assertTrue("create:kilimaniyogacollective" in remote.calls, "the handle the name suggested")
    }

    @Test fun aLostAnswerKeepsTheKeyAndAFixedRefusalGetsANewOne() = runTest(dispatcher) {
        remote.createAnswer = AppResult.Failure(AppError.Network)
        val vm = newSpace()
        vm.fillDetails(); advanceUntilIdle(); vm.onNext(); vm.onNext()
        vm.onNext(); advanceUntilIdle()
        assertTrue(vm.state.value.problem is CreateProblem.Failed, "SN7b: nothing was made")
        vm.onTryAgain(); advanceUntilIdle()
        assertEquals(remote.createKeys[0], remote.createKeys[1], "Try Again can't make two spaces")
        remote.createAnswer = apiError("spaces.handle_taken", 409, mapOf("suggestions" to "kilimaniyoga.ke,yogakilimani"))
        vm.onTryAgain(); advanceUntilIdle()
        assertEquals(1, vm.state.value.step, "back to the handle")
        assertEquals(HandleState.Taken(listOf("kilimaniyoga.ke", "yogakilimani")), vm.state.value.handleState)
        vm.onSuggestion("yogakilimani"); advanceUntilIdle(); vm.onNext(); vm.onNext()
        remote.createAnswer = AppResult.Failure(AppError.Network)
        vm.onNext(); advanceUntilIdle()
        assertTrue(remote.createKeys.last() != remote.createKeys[2], "the refused request's key is never replayed")
    }

    @Test fun aTooNewAccountIsToldTheDate() = runTest(dispatcher) {
        remote.createAnswer = apiError("spaces.account_too_new", 403, mapOf("availableAt" to "2026-11-11T09:00:00Z"))
        val vm = newSpace()
        vm.fillDetails(); advanceUntilIdle(); vm.onNext(); vm.onNext(); vm.onNext(); advanceUntilIdle()
        val p = vm.state.value.problem as CreateProblem.NotYet
        assertEquals(kotlin.time.Instant.parse("2026-11-11T09:00:00Z").toEpochMilliseconds(), p.availableAt)
    }

    @Test fun offlineNextIsDisabledAndTheDraftStays() = runTest(dispatcher) {
        val monitor = CreateTestMonitor(online = false)
        connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0)
        val vm = newSpace()
        vm.fillDetails(); advanceUntilIdle()
        assertFalse(vm.state.value.online)
        assertFalse(vm.state.value.canContinue)
        vm.onNext()
        assertEquals(1, vm.state.value.step)
        assertEquals("Kilimani Yoga Collective", vm.state.value.name)
    }

    // ---- the space wallet (SN4–SN5b)

    private fun setup(bio: String? = "Fingerprint") = SpaceWalletSetupViewModel(
        "s9", repo, StartSpaceWalletUseCase(walletRepo), AwaitSpaceWalletUseCase(walletRepo), EnableWalletBiometricsUseCase(walletRepo),
        AddWalletNetworksUseCase(walletRepo), null, connection, bio,
    )

    @Test fun theSpaceWalletPinNamesTheSpaceThenBiometricsThenNetworksThenLive() = runTest(dispatcher) {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space("s9", "Kilimani Yoga Collective", "kilimaniyoga", state = "SETTING_UP", me = MeDto("MEMBER", "OWNER"))) }
        val vm = setup()
        vm.onStart(); advanceUntilIdle()
        assertEquals(SpaceSetupStep.Biometrics, vm.state.value.step)
        assertEquals("Kilimani Yoga Collective", subjects.first().name, "Circle's PIN names the space")
        vm.onTurnOnBiometrics(); advanceUntilIdle()
        assertEquals(SpaceSetupStep.Done, vm.state.value.step)
        assertTrue(subjects.drop(1).all { it.kind == WalletOwnerType.Space && it.walletId == "sw-9" }, "biometrics and networks for the space wallet only")
        assertEquals(listOf("startSpaceWallet", "awaitSpaceWallet", "enableBiometricApproval", "addMissingChains"), walletCalls.filter { it != "getWallet" })
    }

    @Test fun aCancelledPinKeepsTheSpaceAndTryAgainResumes() = runTest(dispatcher) {
        startAnswer = AppResult.Failure(AppError.Api("wallet.cancelled", 400, null, null, emptyMap()))
        val vm = setup(bio = null)
        vm.onStart(); advanceUntilIdle()
        assertEquals(SpaceSetupProblem.Cancelled, vm.state.value.problem)
        startAnswer = AppResult.Success(SpaceWallet("s9", WalletStatus.Active, listing(WalletStatus.Active)))
        vm.onTryAgain(); advanceUntilIdle()
        assertNull(vm.state.value.problem)
        assertEquals(SpaceSetupStep.Done, vm.state.value.step)
    }

    @Test fun aServerFailureSaysSoWithItsReference() = runTest(dispatcher) {
        startAnswer = AppResult.Failure(AppError.Api("wallet.custody_unavailable", 503, "req_W5sp3K", null, emptyMap()))
        val vm = setup()
        vm.onStart(); advanceUntilIdle()
        assertEquals(SpaceSetupProblem.Failed, vm.state.value.problem)
        assertEquals("req_W5sp3K", vm.state.value.reference)
    }

    // ---- Who can join (SN8b)

    @Test fun aPriceChangeSaysWhoWillBeToldAndSendsOnlyThePrice() = runTest(dispatcher) {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space("s9", visibility = "MEMBERS_ONLY", price = com.circaso.feature.spaces.data.remote.PriceDto(amountMinor = "10000000", period = "MONTHLY"), me = MeDto("MEMBER", "OWNER"))) }
        repo.refreshSpace("s9")
        remote.updateAnswer = AppResult.Success(UpdatedSpaceDto(SpaceSamples.space("s9", visibility = "MEMBERS_ONLY", me = MeDto("MEMBER", "OWNER")), notified = 38))
        val vm = SpaceEditViewModel("s9", SpaceEditMode.Join, repo, ObserveWalletUseCase(walletRepo), connection, null)
        advanceUntilIdle()
        assertEquals("10.00", vm.state.value.priceText)
        assertFalse(vm.state.value.priceChanged)
        assertFalse(vm.state.value.canSave)
        vm.onPrice("12.00")
        assertTrue(vm.state.value.priceChanged)
        assertEquals(1203, vm.state.value.membersToTell)
        assertNull(vm.state.value.change.visibility, "only what changed is sent")
        vm.onSave(); advanceUntilIdle()
        assertTrue(vm.state.value.saved)
        assertEquals(38, vm.state.value.notified)
    }

    @Test fun removePhotoTakesTheSpacesPhotoAwayOnSave() = runTest(dispatcher) {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space("s9", me = MeDto("MEMBER", "OWNER")).copy(photoUrl = "https://img/s9.jpg")) }
        repo.refreshSpace("s9")
        val vm = SpaceEditViewModel("s9", SpaceEditMode.Details, repo, ObserveWalletUseCase(walletRepo), connection, null)
        advanceUntilIdle()
        assertFalse(vm.state.value.change.removePhoto)
        vm.onRemovePhoto()
        assertTrue(vm.state.value.change.removePhoto)
        assertTrue(vm.state.value.canSave)
    }

    @Test fun aPaymentToASpaceIsNamedFromThePhonesSpaces() {
        val dto = com.circaso.feature.wallet.data.remote.TransactionDto(
            id = "r1", transferId = "t1", direction = "OUT", state = "CONFIRMED", blockchain = "BASE-SEPOLIA",
            amount = com.circaso.feature.wallet.data.remote.AmountDto("USDC", "5000000", "5"), occurredAt = "2026-11-04T10:00:00Z", toSpaceId = "s1",
        )
        val row = dto.toActivity("gw-1").toDomain()
        assertEquals("s1", row.counterpartSpaceId)
        assertNull(row.counterpartGroupChat)
        val ui = com.circaso.feature.chama.presentation.GroupWalletUiState(activity = listOf(row), spaceNames = mapOf("s1" to "Nairobi Sneaker Drop"))
        assertEquals("Nairobi Sneaker Drop", ui.rows.single().first.counterpartName)
        assertEquals("Space", ui.copy(spaceNames = emptyMap()).rows.single().first.counterpartName)
    }

    // ---- the wallet's names (SW1)

    @Test fun spaceWalletsAreNamedAfterTheirSpacesAndSignAsSpaces() {
        val listings = listOf(
            WalletListing("p", WalletOwner(WalletOwnerType.Person, "me", null, "me"), WalletStatus.Active, true, emptyList()),
            WalletListing("sw-1", WalletOwner(WalletOwnerType.Space, null, null, "me", "s1"), WalletStatus.Active, true, emptyList()),
            WalletListing("sw-2", WalletOwner(WalletOwnerType.Space, null, null, "baraka", "s2"), WalletStatus.SettingUp, false, emptyList()),
        )
        val named = nameWallets(listings, emptyList(), spaces = mapOf("s1" to SpaceWalletName("Nairobi Sneaker Drop", null, "Baraka Mwita")))
        assertEquals(listOf("Personal", "Nairobi Sneaker Drop", "Space wallet"), named.map { it.name })
        val space = named[1]
        assertTrue(space.space)
        assertEquals("s1", space.spaceId)
        assertNull(space.chatId)
        assertEquals(WalletOwnerType.Space, space.subject.kind)
        assertEquals(NamedWallet.Personal.subject.kind, named[0].subject.kind)
    }
}
