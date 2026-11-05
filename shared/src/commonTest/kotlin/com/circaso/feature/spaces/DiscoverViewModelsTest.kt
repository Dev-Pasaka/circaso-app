package com.circaso.feature.spaces

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.flags.SpacesFlag
import com.circaso.core.sync.Outbox
import com.circaso.feature.spaces.data.remote.KnownDto
import com.circaso.feature.spaces.data.remote.MeDto
import com.circaso.feature.spaces.data.remote.MySpaceDto
import com.circaso.feature.spaces.data.remote.MySpacesPage
import com.circaso.feature.spaces.data.remote.PersonDto
import com.circaso.feature.spaces.data.remote.ReceivedInviteDto
import com.circaso.feature.spaces.data.remote.ReceivedInvitesDto
import com.circaso.feature.spaces.data.remote.SearchPage
import com.circaso.feature.spaces.data.remote.SuggestionDto
import com.circaso.feature.spaces.data.remote.SuggestionsPage
import com.circaso.feature.spaces.data.repository.SpacesRepositoryImpl
import com.circaso.feature.spaces.presentation.discover.ChatsSpacesViewModel
import com.circaso.feature.spaces.presentation.discover.FollowState
import com.circaso.feature.spaces.presentation.discover.SearchPerson
import com.circaso.feature.spaces.presentation.discover.SpaceSearchViewModel
import com.circaso.feature.spaces.presentation.discover.SpacesHomeViewModel
import com.circaso.feature.spaces.presentation.discover.highlight
import com.circaso.feature.spaces.presentation.discover.shortSpaceName
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.FakeSpacesDao
import com.circaso.testing.FakeSpacesRemote
import com.circaso.testing.SpaceSamples
import com.circaso.testing.apiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val remote = FakeSpacesRemote()
    private val online = MutableStateFlow(true)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun repo() = SpacesRepositoryImpl(FakeSpacesDao(), remote, SpacesFlag.on(), Outbox(FakeOutboxDao(), owner = { "me" }), {}, json, { "me" }, { null })

    private fun suggestion(id: String, vis: String, name: String = "Space $id") =
        SuggestionDto(id, "h$id", name, visibility = vis, memberCount = 10, reason = "CONTACTS", known = KnownDto(listOf("Juma"), 1))

    @Test fun suggestionsLeaveOutInviteOnlyAndFollowWaitsForTheServer() = runTest(dispatcher) {
        remote.suggestedAnswer = AppResult.Success(SuggestionsPage(listOf(suggestion("a", "PUBLIC"), suggestion("b", "INVITE_ONLY"), suggestion("c", "MEMBERS_ONLY"))))
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space(id = it, me = MeDto("MEMBER", "MEMBER"))) }
        val vm = SpacesHomeViewModel(repo(), online, now = { 1_000_000 })
        advanceUntilIdle()
        assertEquals(listOf("a", "c"), vm.state.value.shownSuggestions.map { it.space.spaceId })
        vm.onFollow("a")
        assertEquals(FollowState.Busy, vm.state.value.follow["a"]) // progress, not an optimistic Following
        advanceUntilIdle()
        assertEquals(FollowState.Following, vm.state.value.follow["a"])
        assertEquals("You follow Space a", vm.state.value.toast)
        assertEquals(listOf("a", "c"), vm.state.value.shownSuggestions.map { it.space.spaceId }) // flips in place
    }

    @Test fun aRefusedFollowSaysWhyAndGoesBack() = runTest(dispatcher) {
        remote.suggestedAnswer = AppResult.Success(SuggestionsPage(listOf(suggestion("a", "PUBLIC"))))
        remote.meAnswer = apiError("spaces.banned", 403)
        val vm = SpacesHomeViewModel(repo(), online)
        advanceUntilIdle()
        vm.onFollow("a")
        advanceUntilIdle()
        assertEquals(null, vm.state.value.follow["a"])
        assertEquals("You can’t follow this space.", vm.state.value.toast)
    }

    @Test fun offlineSuggestionsAreNotAnErrorAndAServerFailureShowsTheReference() = runTest(dispatcher) {
        remote.suggestedAnswer = AppResult.Failure(AppError.Network)
        val vm = SpacesHomeViewModel(repo(), online)
        advanceUntilIdle()
        assertFalse(vm.state.value.suggestionsFailed)
        remote.suggestedAnswer = apiError("internal.error", 500, requestId = "req_D2kq7Z")
        vm.onRetrySuggestions()
        advanceUntilIdle()
        assertTrue(vm.state.value.suggestionsFailed)
        assertEquals("req_D2kq7Z", vm.state.value.requestId)
    }

    @Test fun noSpacesYetIsTheEmptyStateNotLoading() = runTest(dispatcher) {
        remote.mineAnswer = AppResult.Success(MySpacesPage())
        val vm = SpacesHomeViewModel(repo(), online)
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.noSpaces)
    }

    @Test fun searchWaitsForAPauseAndAnAtSearchesHandles() = runTest(dispatcher) {
        remote.searchAnswer = { q -> AppResult.Success(SearchPage(exact = if (q.startsWith("@")) SpaceSamples.summary() else null, spaces = listOf(SpaceSamples.summary("s2", "Sneaker Drop Mombasa", "sneakerdropmsa")))) }
        val vm = SpaceSearchViewModel(repo(), flowOf(listOf(SearchPerson("f", "Faith Chebet", null, "faithc", "Book haul from Prestige"))), online, debounceMs = 300)
        runCurrent()
        vm.onQuery("b"); vm.onQuery("bo"); vm.onQuery("book")
        advanceTimeBy(100)
        assertTrue(remote.calls.none { it.startsWith("search:") })
        advanceUntilIdle()
        assertEquals(listOf("search:book"), remote.calls.filter { it.startsWith("search:") })
        assertEquals(listOf("f"), vm.state.value.people.map { it.userId }) // her moment's words matched, on the phone
        vm.onQuery("@sneakerdrop")
        advanceUntilIdle()
        assertTrue(vm.state.value.byHandle)
        assertEquals("s1", vm.state.value.exact?.spaceId)
        assertTrue(vm.state.value.people.isEmpty())
    }

    @Test fun offlineSearchLooksOnlyAtMySpaces() = runTest(dispatcher) {
        remote.mineAnswer = AppResult.Success(MySpacesPage(listOf(MySpaceDto(SpaceSamples.summary("s3", "Kilimani Chama Hub", "chamahub"), MeDto("MEMBER", "MEMBER")))))
        val r = repo()
        r.refreshMine()
        online.value = false
        val vm = SpaceSearchViewModel(r, flowOf(emptyList()), online)
        runCurrent()
        vm.onQuery("chama")
        advanceUntilIdle()
        assertEquals(listOf("s3"), vm.state.value.mine.map { it.space.spaceId })
        assertTrue(remote.calls.none { it.startsWith("search:") })
    }

    private fun invites() = AppResult.Success(ReceivedInvitesDto(listOf(
        ReceivedInviteDto("i1", SpaceSamples.summary("s8", "Nairobi Founders Circle", "founderscircle", "INVITE_ONLY", members = 48), PersonDto("w", "Wanjiru Kamau"), "2026-11-02T09:00:00Z", "2099-11-16T09:00:00Z"),
    )))

    @Test fun declineWaitsForUndoBeforeTellingTheServer() = runTest(dispatcher) {
        remote.myInvitesAnswer = invites()
        val vm = ChatsSpacesViewModel(repo(), flowOf(emptySet()), online, undoMs = 5_000)
        advanceUntilIdle()
        val invite = vm.state.value.shownInvites.single()
        vm.onDecline(invite)
        advanceTimeBy(1_000)
        assertTrue("i1" in vm.state.value.declining)
        vm.onUndo("i1")
        advanceUntilIdle()
        assertTrue(remote.calls.none { it.startsWith("decline:") })
        assertEquals(1, vm.state.value.shownInvites.size)
        vm.onDecline(invite)
        advanceUntilIdle()
        assertEquals(listOf("decline:i1"), remote.calls.filter { it.startsWith("decline:") })
        assertTrue(vm.state.value.shownInvites.isEmpty())
    }

    @Test fun acceptOpensTheSpaceOnlyAfterTheServerSaysYes() = runTest(dispatcher) {
        remote.myInvitesAnswer = invites()
        remote.acceptAnswer = AppResult.Success(SpaceSamples.space("s8", "Nairobi Founders Circle", "founderscircle", "INVITE_ONLY", me = MeDto("MEMBER", "MEMBER")))
        val vm = ChatsSpacesViewModel(repo(), flowOf(emptySet()), online)
        advanceUntilIdle()
        vm.onAccept(vm.state.value.shownInvites.single())
        assertTrue("i1" in vm.state.value.accepting)
        advanceUntilIdle()
        assertEquals("s8", vm.accepted.first().spaceId)
        assertEquals("You joined Nairobi Founders Circle", vm.state.value.toast)
    }

    @Test fun privateChatsAreNamedAndTheSpacesChipShowsForTheTeam() = runTest(dispatcher) {
        remote.mineAnswer = AppResult.Success(MySpacesPage(listOf(MySpaceDto(SpaceSamples.summary("s1"), MeDto("MEMBER", "OWNER")))))
        val r = repo()
        r.refreshMine()
        val vm = ChatsSpacesViewModel(r, flowOf(setOf("s1")), online)
        advanceUntilIdle()
        assertEquals("Nairobi Sneaker Drop", vm.state.value.spaceNames["s1"])
        assertTrue(vm.state.value.showFilter)
    }

    @Test fun matchingWordsAreBoldAndLabelsAreShort() {
        val h = highlight("Books · Members only", "book")
        assertEquals(1, h.spanStyles.size)
        assertEquals(0, h.spanStyles.single().start)
        assertEquals(4, h.spanStyles.single().end)
        assertEquals("Sneaker Drop", shortSpaceName("Nairobi Sneaker Drop"))
        assertEquals("Karura Runners", shortSpaceName("Karura Runners"))
    }
}
