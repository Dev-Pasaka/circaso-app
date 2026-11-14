package com.circaso.feature.messaging.presentation.map

import com.circaso.core.platform.GeoPoint
import com.circaso.core.platform.LocationAccess
import com.circaso.core.platform.LocationProvider
import com.circaso.core.platform.appleDirectionsUrl
import com.circaso.core.platform.googleDirectionsUrl
import com.circaso.feature.messaging.data.repository.MediaDetails
import com.circaso.feature.messaging.data.repository.metersBetween
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val Now = 1_800_000_000_000L
private val Nairobi = GeoPoint(-1.29207, 36.82195, 8.0)

private fun member(id: String, name: String) = ChatMember(id, name, null, "https://photos/$id", MemberRole.Member, "2026-10-20T00:00:00Z")
private val group = Chat("g", ChatKind.Group, "Family", null, listOf(member("me", "Amina"), member("mum", "Mum Wanjiku"), member("kev", "Kevin")), false, 1, 0, MemberRole.Member, null)

private fun located(localId: Long, sender: String, lat: Double, lng: Double, live: Boolean = true, ended: Boolean = false, until: Long = Now + 600_000) = Message.Media(
    localId, "g", Now - 60_000, null, "m$localId", sender, sender, outgoing = sender == "me",
    details = if (live) MediaDetails(MediaDetails.Location, latitude = lat, longitude = lng, accuracy = 10.0, liveId = "l$localId", liveUntil = until, liveAt = Now - 30_000, liveEnded = ended.takeIf { it })
    else MediaDetails(MediaDetails.Location, latitude = lat, longitude = lng, label = "Java House"),
    state = DeliveryState.Read, errorCode = null,
)

private fun state(messages: List<Message>, opened: Long, focus: Long = opened, fix: GeoPoint? = Nairobi, access: LocationAccess = LocationAccess.Granted) =
    locationMapState(group, messages, opened, focus, fix, access, "me", Now)

/** The distances, the line and the notes on the location map. */
class LocationMapDistanceTest {
    // ---- the distance itself

    @Test fun aDegreeOfLatitudeIsAbout111Kilometres() {
        val d = metersBetween(GeoPoint(0.0, 36.0), GeoPoint(1.0, 36.0))
        assertTrue(abs(d - 111_195) < 50, "$d")
    }

    @Test fun nairobiToMombasaIsAbout440KilometresInAStraightLine() {
        val d = metersBetween(GeoPoint(-1.2921, 36.8219), GeoPoint(-4.0435, 39.6682))
        assertTrue(d in 436_000.0..446_000.0, "$d")
    }

    @Test fun theSamePlaceIsNoDistanceAndTheOrderOfTheEndsDoesNotMatter() {
        assertEquals(0.0, metersBetween(Nairobi, Nairobi))
        val a = GeoPoint(-1.30, 36.80)
        assertEquals(metersBetween(Nairobi, a), metersBetween(a, Nairobi), 1e-6)
    }

    @Test fun acrossTheDateLineIsTheShortWayRound() {
        val d = metersBetween(GeoPoint(0.0, 179.9), GeoPoint(0.0, -179.9))
        assertTrue(d < 23_000, "$d")
    }

    // ---- how it is written

    @Test fun metresAreRoundedToTensAndKilometresGetOneDecimalUntilTen() {
        assertEquals("under 10 m", formatDistance(4.0))
        assertEquals("10 m", formatDistance(12.0))
        assertEquals("350 m", formatDistance(349.6))
        assertEquals("990 m", formatDistance(994.0))
        assertEquals("1.0 km", formatDistance(996.0))
        assertEquals("2.4 km", formatDistance(2_449.0))
        assertEquals("9.9 km", formatDistance(9_949.0))
        assertEquals("10 km", formatDistance(9_960.0))
        assertEquals("24 km", formatDistance(24_400.0))
        assertEquals("1,240 km", formatDistance(1_240_300.0))
        assertEquals("12,345 km", formatDistance(12_345_000.0))
        assertEquals("under 10 m", formatDistance(-3.0))
    }

    @Test fun itSaysAwayNeverRouteAndAboutWhenTheFixIsRough() {
        assertEquals("2.4 km away", distanceAway(2_400.0))
        assertEquals("Less than 10 m away", distanceAway(3.0))
        // A fix good to 300 m: hundreds of metres are all it can honestly give.
        assertEquals("About 2.4 km away", distanceAway(2_449.0, roughness = 300.0))
        assertEquals("About 500 m away", distanceAway(460.0, roughness = 300.0))
    }

    @Test fun aRoughFixNeverClaimsMoreThanItKnows() {
        // Android's "Approximate" location is good to a couple of kilometres.
        assertEquals("Within about 1.8 km", distanceAway(350.0, roughness = 1_800.0))
        assertEquals("Within about 1.8 km", distanceAway(1_800.0, roughness = 1_800.0))
        assertEquals("About 5.0 km away", distanceAway(5_300.0, roughness = 1_800.0))
        assertEquals("About 24 km away", distanceAway(24_400.0, roughness = 1_800.0))
        assertEquals("About 440 km away", distanceAway(441_000.0, roughness = 12_000.0))
    }

    // ---- the state

    @Test fun eachPersonSharingHasTheirOwnDistanceFromMe() {
        val s = state(listOf(located(1, "mum", -1.30, 36.80), located(2, "kev", -1.20, 36.90)), opened = 1)
        assertEquals(listOf("Mum Wanjiku", "Kevin"), s.entries.map { it.name })
        assertEquals(metersBetween(Nairobi, GeoPoint(-1.30, 36.80)), s.entries[0].distanceMeters)
        assertEquals(metersBetween(Nairobi, GeoPoint(-1.20, 36.90)), s.entries[1].distanceMeters)
        assertEquals("https://photos/me", s.myAvatarUrl)
        assertEquals("Amina", s.myName)
    }

    @Test fun theOrderIsTheTappedOneFirstAndDoesNotChangeWithDistance() {
        // Kevin is far nearer than Mum, but Mum's share was the one tapped.
        val messages = listOf(located(1, "mum", -4.04, 39.66), located(2, "kev", -1.2921, 36.8220))
        assertEquals(listOf(1L, 2L), state(messages, opened = 1).entries.map { it.id })
        assertEquals(listOf(2L, 1L), state(messages, opened = 2).entries.map { it.id })
        // Moving doesn't shuffle the rows.
        assertEquals(listOf(1L, 2L), state(messages, opened = 1, fix = GeoPoint(-4.04, 39.66)).entries.map { it.id })
    }

    @Test fun aTappedPlaceIsMeasuredToAndComesFirst() {
        val s = state(listOf(located(1, "mum", -1.30, 36.80), located(5, "kev", -1.28, 36.81, live = false)), opened = 5)
        assertEquals(listOf(5L, 1L), s.entries.map { it.id })
        assertTrue(s.entries[0].place)
        assertNotNull(s.entries[0].distanceMeters)
        assertEquals(5L, s.focus)
    }

    @Test fun withoutMyLocationThereAreNoDistancesAndNoLineAndTheMapIsAsBefore() {
        for (access in listOf(LocationAccess.NotAsked, LocationAccess.Denied, LocationAccess.Off, LocationAccess.Granted)) {
            val s = state(listOf(located(1, "mum", -1.30, 36.80)), opened = 1, fix = null, access = access)
            assertEquals(1, s.entries.size)
            assertNull(s.entries[0].distanceMeters)
            assertNull(s.me)
            assertNull(s.line)
            assertEquals(1L, s.focus)
        }
    }

    @Test fun theNoteSaysWhyMyLocationIsMissingAndAsksOnlyWhenNeverAsked() {
        val mum = listOf(located(1, "mum", -1.30, 36.80))
        assertEquals(LocationNote.Ask, locationNote(state(mum, 1, fix = null, access = LocationAccess.NotAsked)))
        assertEquals(LocationNote.Refused, locationNote(state(mum, 1, fix = null, access = LocationAccess.Denied)))
        assertEquals(LocationNote.PhoneOff, locationNote(state(mum, 1, fix = null, access = LocationAccess.Off)))
        assertEquals(LocationNote.Finding, locationNote(state(mum, 1, fix = null, access = LocationAccess.Granted)))
        assertEquals(LocationNote.Known(private = true), locationNote(state(mum, 1)))
    }

    @Test fun nothingIsAskedWhenTheOnlyShareIsMyOwn() {
        val s = state(listOf(located(3, "me", -1.30, 36.80)), opened = 3, fix = null, access = LocationAccess.NotAsked)
        assertFalse(s.canMeasure)
        assertNull(locationNote(s))
        assertNull(s.focus)
        assertNull(s.line)
    }

    @Test fun whileIShareLiveMyShareIsWhereIAmAndItIsNotPrivate() {
        val s = state(listOf(located(1, "mum", -1.30, 36.80), located(3, "me", -1.25, 36.85)), opened = 1, fix = null, access = LocationAccess.Granted)
        assertTrue(s.sharingLive)
        assertEquals(GeoPoint(-1.25, 36.85, 10.0), s.me)
        assertEquals(metersBetween(GeoPoint(-1.25, 36.85), GeoPoint(-1.30, 36.80)), s.entries[0].distanceMeters)
        assertNull(s.entries[1].distanceMeters, "no distance to myself")
        assertEquals(LocationNote.Known(private = false), locationNote(s))
    }

    @Test fun aShareOfMineThatEndedIsNotWhereIAm() {
        val s = state(listOf(located(1, "mum", -1.30, 36.80), located(3, "me", -1.25, 36.85, ended = true)), opened = 1, fix = null, access = LocationAccess.NotAsked)
        assertFalse(s.sharingLive)
        assertNull(s.me)
        assertEquals(LocationNote.Ask, locationNote(s))
    }

    @Test fun aRoughFixMakesDistancesApproximate() {
        val mum = listOf(located(1, "mum", -1.30, 36.80))
        assertNull(state(mum, 1).roughness)
        assertEquals(1_800.0, state(mum, 1, fix = Nairobi.copy(accuracyMeters = 1_800.0)).roughness)
    }

    // ---- focus and the line

    @Test fun theLineRunsFromMeToTheFocusAndFollowsIt() {
        val messages = listOf(located(1, "mum", -1.30, 36.80), located(2, "kev", -1.20, 36.90))
        val first = state(messages, opened = 1)
        assertEquals(MapLine(Nairobi.latitude, Nairobi.longitude, -1.30, 36.80, to = "1"), first.line)
        val second = state(messages, opened = 1, focus = 2)
        assertEquals(2L, second.focus)
        assertEquals(MapLine(Nairobi.latitude, Nairobi.longitude, -1.20, 36.90, to = "2"), second.line)
    }

    @Test fun theLineMovesWhenEitherOfUsMoves() {
        val moved = state(listOf(located(1, "mum", -1.31, 36.79)), opened = 1, fix = GeoPoint(-1.28, 36.83))
        assertEquals(MapLine(-1.28, 36.83, -1.31, 36.79, to = "1"), moved.line)
    }

    @Test fun myOwnShareIsNeverTheFocusAndAFocusThatLeftFallsBack() {
        val messages = listOf(located(3, "me", -1.25, 36.85), located(1, "mum", -1.30, 36.80))
        // Opened from my own share: the focus is the other person.
        assertEquals(1L, state(messages, opened = 3).focus)
        // A focus that isn't on the map any more falls back to the one that was opened.
        assertEquals(1L, state(messages, opened = 1, focus = 99).focus)
    }

    @Test fun onlyTheNewestLiveShareOfAPersonIsOnTheMap() {
        val s = state(listOf(located(1, "mum", -1.30, 36.80, ended = true), located(4, "mum", -1.31, 36.81)), opened = 1)
        assertEquals(listOf(4L), s.entries.map { it.id })
        assertEquals(4L, s.focus)
    }

    // ---- framing

    @Test fun framingTwoPointsFarApartUsesTheirBounds() {
        val plan = fitPlan(listOf(-1.29 to 36.82, -1.30 to 36.80))
        assertIs<FitPlan.Bounds>(plan)
        assertEquals(2, plan.points.size)
    }

    @Test fun framingTwoPeopleInTheSameRoomStaysAtStreetLevelAroundTheirMiddle() {
        val plan = fitPlan(listOf(-1.29200 to 36.82190, -1.29210 to 36.82200))
        assertIs<FitPlan.Centre>(plan)
        assertEquals(17f, plan.zoom)
        assertEquals(-1.29205, plan.latitude, 1e-9)
        assertEquals(36.82195, plan.longitude, 1e-9)
    }

    @Test fun framingOnePointCentresOnItAndNoPointsIsNothing() {
        assertEquals(FitPlan.Centre(-1.29, 36.82, 16f), fitPlan(listOf(-1.29 to 36.82, -1.29 to 36.82)))
        assertNull(fitPlan(emptyList()))
    }

    @Test fun aMovingLineGlidesBothEndsAndKeepsItsFocus() {
        val a = MapLine(0.0, 0.0, 10.0, 10.0, to = "1")
        val b = MapLine(2.0, 4.0, 20.0, 30.0, to = "1")
        assertEquals(MapLine(1.0, 2.0, 15.0, 20.0, to = "1"), a.towards(b, 0.5))
        assertEquals(b, a.towards(b, 1.0))
    }

    // ---- the fallback picture

    @Test fun theFallbackPictureKeepsThePinsBetweenTheBarAndThePanel() {
        val points = listOf(mercator(-1.29, 36.82), mercator(-1.30, 36.80))
        val plain = fallbackFrame(points, 360, 640)
        val inset = fallbackFrame(points, 360, 640, top = 80, bottom = 240)
        assertTrue(inset.zoom <= plain.zoom)
        // The picture's centre is lower than the pins' middle, so the pins sit above the panel.
        assertTrue(inset.y > (points[0].second + points[1].second) / 2)
        assertTrue(inset.holds(points, 360, 640, top = 80, bottom = 240))
    }

    @Test fun theSamePictureIsKeptWhileAPinOnlyMovesALittleAndReplacedWhenItLeaves() {
        val mum = mercator(-1.30, 36.80)
        val frame = fallbackFrame(listOf(mercator(-1.29, 36.82), mum), 360, 640)
        assertEquals(frame, fallbackFrame(listOf(mercator(-1.29, 36.82), mum), 360, 640), "the same pins ask for the same picture")
        assertTrue(frame.holds(listOf(mercator(-1.2901, 36.8201), mum), 360, 640), "a few metres' walk keeps the picture")
        assertFalse(frame.holds(listOf(mercator(-1.10, 37.20), mum), 360, 640), "across town needs a new one")
    }

    // ---- directions

    @Test fun directionsCarryOnlyTheDestination() {
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=-1.3,36.8", googleDirectionsUrl(-1.3, 36.8))
        assertEquals("https://maps.apple.com/?daddr=-1.3,36.8", appleDirectionsUrl(-1.3, 36.8))
    }
}

/** The view model: when the phone's location is read, and what happens to the answer to "Turn on". */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationMapViewModelTest {
    private class FakeLocation(var answer: LocationAccess, private val onRequest: LocationAccess = LocationAccess.Granted) : LocationProvider {
        val fixes = MutableSharedFlow<GeoPoint>(replay = 1)
        var listening = 0
        var asked = 0
        override val available = true
        override suspend fun current(): GeoPoint? = null
        override fun access() = answer
        override suspend fun requestAccess(): LocationAccess { asked++; if (onRequest == LocationAccess.Granted) answer = onRequest; return onRequest }
        override fun updates(): Flow<GeoPoint> = fixes.onStart { listening++ }.onCompletion { listening-- }
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val thread = MutableStateFlow<List<Message>>(listOf(located(1, "mum", -1.30, 36.80), located(2, "kev", -1.20, 36.90)))
    private fun viewModel(location: LocationProvider) = LocationMapViewModel(1, MutableStateFlow(group), thread, null, location, "me") { Now }

    @Test fun myLocationIsReadOnlyWhileTheScreenIsOpenAndGivesDistances() = runTest(UnconfinedTestDispatcher()) {
        val location = FakeLocation(LocationAccess.Granted)
        val vm = viewModel(location)
        assertEquals(0, location.listening, "nothing is read before the screen shows")
        val screen = launch { vm.state.collect { } }
        assertEquals(1, location.listening)
        assertNull(vm.state.value.me)
        assertEquals(LocationNote.Finding, locationNote(vm.state.value))
        location.fixes.emit(Nairobi)
        assertEquals(Nairobi, vm.state.value.me)
        assertNotNull(vm.state.value.entries[0].distanceMeters)
        assertEquals(0, location.asked, "already allowed: nothing is asked")
        screen.cancel()
    }

    @Test fun nothingIsAskedOrReadUntilThePersonTapsTurnOn() = runTest(UnconfinedTestDispatcher()) {
        val location = FakeLocation(LocationAccess.NotAsked)
        val vm = viewModel(location)
        val screen = launch { vm.state.collect { } }
        assertEquals(0, location.asked)
        assertEquals(0, location.listening)
        assertEquals(LocationNote.Ask, locationNote(vm.state.value))
        vm.onAllowLocation()
        assertEquals(1, location.asked)
        assertEquals(1, location.listening)
        location.fixes.emit(Nairobi)
        assertEquals(LocationNote.Known(private = true), locationNote(vm.state.value))
        screen.cancel()
    }

    @Test fun aRefusalIsRememberedAndNotAskedAgainOnComingBack() = runTest(UnconfinedTestDispatcher()) {
        // Android: after "Don't allow" the system still reports "not asked".
        val location = FakeLocation(LocationAccess.NotAsked, onRequest = LocationAccess.Denied)
        val vm = viewModel(location)
        val screen = launch { vm.state.collect { } }
        vm.onAllowLocation()
        assertEquals(LocationNote.Refused, locationNote(vm.state.value))
        vm.onResume()
        assertEquals(LocationNote.Refused, locationNote(vm.state.value))
        assertEquals(0, location.listening)
        assertNull(vm.state.value.entries[0].distanceMeters)
        // Turned on in Settings, then back.
        location.answer = LocationAccess.Granted
        vm.onResume()
        assertEquals(1, location.listening)
        assertEquals(1, location.asked)
        screen.cancel()
    }

    @Test fun thePhoneIsNotReadWhileCircasoIsInTheBackground() = runTest(UnconfinedTestDispatcher()) {
        val location = FakeLocation(LocationAccess.Granted)
        val vm = viewModel(location)
        val screen = launch { vm.state.collect { } }
        assertEquals(1, location.listening)
        vm.onPause()
        assertEquals(0, location.listening)
        vm.onResume()
        assertEquals(1, location.listening)
        screen.cancel()
    }

    @Test fun focusFollowsTheTapAndTheLineWithIt() = runTest(UnconfinedTestDispatcher()) {
        val location = FakeLocation(LocationAccess.Granted)
        val vm = viewModel(location)
        val screen = launch { vm.state.collect { } }
        location.fixes.emit(Nairobi)
        assertEquals(1L, vm.state.value.focus)
        vm.onFocus(vm.state.value.entries[1])
        assertEquals(2L, vm.state.value.focus)
        assertEquals(MapLine(Nairobi.latitude, Nairobi.longitude, -1.20, 36.90, to = "2"), vm.state.value.line)
        // Kevin moves: the line's far end moves with him.
        thread.value = listOf(located(1, "mum", -1.30, 36.80), located(2, "kev", -1.21, 36.91))
        assertEquals(MapLine(Nairobi.latitude, Nairobi.longitude, -1.21, 36.91, to = "2"), vm.state.value.line)
        screen.cancel()
    }

    @Test fun whileIShareLiveThePhoneIsNotReadASecondTime() = runTest(UnconfinedTestDispatcher()) {
        val location = FakeLocation(LocationAccess.Granted)
        val vm = viewModel(location)
        val screen = launch { vm.state.collect { } }
        assertEquals(1, location.listening)
        thread.value = thread.value + located(3, "me", -1.25, 36.85)
        assertEquals(0, location.listening)
        assertEquals(GeoPoint(-1.25, 36.85, 10.0), vm.state.value.me)
        assertTrue(vm.state.value.sharingLive)
        screen.cancel()
    }
}
