package com.circaso.feature.calls

import com.circaso.core.calls.CallEngine
import com.circaso.core.calls.IceServerSpec
import com.circaso.core.calls.LocalOffer
import com.circaso.core.calls.LocalTrackSpec
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.calls.data.CallDto
import com.circaso.feature.calls.data.CallTrackDto
import com.circaso.feature.calls.data.CallsRemote
import com.circaso.feature.calls.data.JoinCallRequest
import com.circaso.feature.calls.data.JoinCallResponse
import com.circaso.feature.calls.data.ParticipantDto
import com.circaso.feature.calls.data.PullRequest
import com.circaso.feature.calls.data.PullResponse
import com.circaso.feature.calls.data.PulledTrackDto
import com.circaso.feature.calls.data.RenegotiateRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun call(state: String = "RINGING", vararg people: ParticipantDto) =
    CallDto("c1", "chat1", "VOICE", state, "me", "2026-10-29T10:00:00Z", participants = people.toList())

private fun person(id: String, state: String, tracks: Boolean = state == "JOINED") =
    ParticipantDto(id, id.replaceFirstChar { it.uppercase() }, state, tracks = if (tracks) listOf(CallTrackDto("audio-$id", "audio"), CallTrackDto("video-$id", "video")) else emptyList())

private class FakeRemote : CallsRemote {
    val calls = mutableListOf<String>()
    var current = call(people = arrayOf(person("me", "INVITED"), person("mum", "INVITED")))
    var startResult: AppResult<CallDto>? = null
    var pullOffer: String? = "sfu-offer"
    /** What CL3 pulls with the join (2026-11-13), and whether each join asked for it. */
    var joinPull: PullResponse? = null
    val joinPulls = mutableListOf<Boolean>()
    override suspend fun start(chatId: String, video: Boolean, key: String): AppResult<CallDto> { calls += "start"; return startResult ?: AppResult.Success(current) }
    override suspend fun get(callId: String) = AppResult.Success(current).also { calls += "get" }
    override suspend fun forChat(chatId: String) = AppResult.Success(current).also { calls += "forChat" }
    override suspend fun join(callId: String, request: JoinCallRequest): AppResult<JoinCallResponse> {
        calls += "join:" + request.tracks.joinToString { it.trackName }
        current = current.copy(participants = current.participants.map { if (it.userId == "me") person("me", "JOINED") else it })
        if (current.participants.count { it.state == "JOINED" } >= 2) current = current.copy(state = "ACTIVE")
        joinPulls += request.pull
        return AppResult.Success(JoinCallResponse("s-me", "sfu-answer", current, joinPull))
    }
    /** Track names the SFU turns down once (no mid, as Cloudflare's `empty_track_error` for a video with no media). */
    val refuseOnce = mutableSetOf<String>()
    override suspend fun pull(callId: String, request: PullRequest): AppResult<PullResponse> {
        calls += "pull:" + request.tracks.joinToString { it.userId + "/" + it.trackName }
        return AppResult.Success(PullResponse(pullOffer, pullOffer != null, request.tracks.mapIndexed { i, t ->
            PulledTrackDto(t.userId, t.trackName, if (refuseOnce.remove(t.trackName)) null else "${i + 2}")
        }))
    }
    override suspend fun renegotiate(callId: String, request: RenegotiateRequest): AppResult<JsonObject> { calls += "renegotiate:${request.answer}"; return AppResult.Success(JsonObject(emptyMap())) }
    override suspend fun decline(callId: String) = AppResult.Success(current).also { calls += "decline" }
    /** How long CL7 takes to answer (Cloudflare made it 6-12 s on 2026-11-09). */
    var leaveMillis = 0L
    /** How many CL7s fail as if the network were down before one gets through. */
    var leaveFailures = 0
    /** Each CL7's cause and Idempotency-Key. */
    val leaves = mutableListOf<Pair<String?, String>>()
    override suspend fun leave(callId: String, cause: String?, key: String): AppResult<CallDto> {
        calls += "leave"
        leaves += cause to key
        if (leaveFailures > 0) { leaveFailures--; return AppResult.Failure(AppError.Network) }
        val answer = current
        if (leaveMillis > 0) kotlinx.coroutines.delay(leaveMillis)
        return AppResult.Success(answer)
    }
    override suspend fun connected(callId: String, sessionId: String): AppResult<JsonObject> { calls += "connected:$sessionId"; return AppResult.Success(JsonObject(emptyMap())) }
    override suspend fun ring(callId: String, userIds: List<String>): AppResult<CallDto> {
        calls += "ring"
        current = current.copy(participants = current.participants.map { if (it.state == "MISSED") it.copy(state = "INVITED") else it })
        return AppResult.Success(current)
    }
    override suspend fun media(callId: String, audio: Boolean, video: Boolean, screen: Boolean): AppResult<JsonObject> { calls += "media:$audio/$video" + if (screen) "/screen" else ""; return AppResult.Success(JsonObject(emptyMap())) }
}

private class FakeEngine : CallEngine {
    val events = mutableListOf<String>()
    override val available = true
    var ice: List<IceServerSpec> = emptyList()
    override suspend fun start(iceServers: List<IceServerSpec>, video: Boolean, userId: String): LocalOffer {
        events += "start:$video"
        ice = iceServers
        return LocalOffer("my-offer", listOf(LocalTrackSpec("0", "audio-$userId", "audio"), LocalTrackSpec("1", "video-$userId", "video")))
    }
    override suspend fun acceptAnswer(sdp: String) { events += "answer:$sdp" }
    override suspend fun answerOffer(sdp: String): String { events += "offer:$sdp"; return "my-answer" }
    override val remoteVideoMids: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    val link = MutableStateFlow<Boolean?>(null)
    override val connected: StateFlow<Boolean?> = link
    /** Set to report which pulled mids have had packets; null is an engine that can't tell. */
    var receiving: MutableStateFlow<Set<String>>? = null
    override val receivingMids: StateFlow<Set<String>>? get() = receiving
    override fun setMicrophone(on: Boolean) { events += "mic:$on" }
    var cameraAllowed = true
    override suspend fun setCamera(on: Boolean): Boolean { events += "camera:$on"; return !on || cameraAllowed }
    override fun flipCamera() { events += "flip" }
    override fun setSpeaker(on: Boolean) { events += "speaker:$on" }
    override fun close() { events += "close" }
    var packets: Long? = null
    override suspend fun receivedPackets(): Long? = packets
    /** The liveness reading the engine gives now; null: it can't tell. */
    var reading: (() -> com.circaso.core.calls.LinkReading?)? = null
    override suspend fun link(): com.circaso.core.calls.LinkReading? = reading?.invoke()
    override suspend fun prepare(iceServers: List<IceServerSpec>, video: Boolean, userId: String) { events += "prepare:$video" }
    var stats: com.circaso.core.calls.CallStats? = null
    override suspend fun stats(): com.circaso.core.calls.CallStats? = stats
    var screenSupported = true
    override val canShareScreen get() = screenSupported
    /** What the system's consent says; false is the person saying no. */
    var screenConsent = true
    val sharing = MutableStateFlow(false)
    override val screenSharing: StateFlow<Boolean> = sharing
    override suspend fun setScreenShare(on: Boolean): Boolean {
        events += "screen:$on"
        if (on && !screenConsent) return false
        sharing.value = on
        return true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CallControllerTest {
    private val remote = FakeRemote()
    private val engine = FakeEngine()
    private val rings = mutableListOf<String>()

    private fun TestScope.controller() = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, stopRinging = { rings += it })

    private fun frame(type: String, c: CallDto) = Json.encodeToJsonElement(CallDto.serializer(), c).jsonObject

    @Test fun callingRingsThenJoinsWithBothTracks() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        assertEquals(listOf("start", "join:audio-me, video-me", "media:true/false"), remote.calls)
        assertEquals(listOf("start:false", "answer:sfu-answer", "mic:true", "speaker:false"), engine.events)
        val s = cc.state.value!!
        assertEquals(CallPhase.Ringing, s.phase)
        assertEquals("Mum", s.title)
    }

    @Test fun whenTheyJoinTheirTracksArePulledAndTheCallIsActive() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED"))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        advanceUntilIdle()
        assertTrue("pull:mum/audio-mum, mum/video-mum" in remote.calls)
        assertTrue("renegotiate:my-answer" in remote.calls)
        val s = cc.state.value!!
        assertEquals(CallPhase.Active, s.phase)
        assertEquals("3", s.people.single().videoMid) // the video track's mid from CL4 drives Mum's tile
        // Pulled once: another update with the same people doesn't pull again.
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        advanceUntilIdle()
        assertEquals(1, remote.calls.count { it.startsWith("pull") })
    }

    @Test fun aTrackTheSfuTurnsDownIsAskedForAgainAndTheirVideoShows() = runTest(UnconfinedTestDispatcher()) {
        // 2026-11-09: Mum's camera was off, so Cloudflare refused her video; turning it on later showed nothing.
        remote.refuseOnce += "video-mum"
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED").copy(video = true))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        advanceUntilIdle()
        // Only the video is asked for again, never the audio that already plays.
        assertEquals(listOf("pull:mum/audio-mum, mum/video-mum", "pull:mum/video-mum"), remote.calls.filter { it.startsWith("pull") })
        assertEquals("2", cc.state.value!!.people.single().videoMid)
        // Done: later updates don't pull again.
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        advanceUntilIdle()
        assertEquals(2, remote.calls.count { it.startsWith("pull") })
    }

    @Test fun joinedIsOnlyConnectingUntilTheirMediaArrivesThenTheTimerStarts() = runTest(UnconfinedTestDispatcher()) {
        engine.receiving = MutableStateFlow(emptySet())
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED"))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        runCurrent()
        cc.state.value!!.let { s ->
            assertEquals(CallPhase.Connecting, s.phase)
            assertNull(s.activeSince)
            assertEquals(false, s.people.single().live)
        }
        engine.receiving!!.value = setOf("2") // Mum's audio track (mid 2 from CL4) has had packets
        runCurrent()
        cc.state.value!!.let { s ->
            assertEquals(CallPhase.Active, s.phase)
            assertTrue(s.activeSince != null)
            assertEquals(true, s.people.single().live)
        }
    }

    @Test fun withNoMediaStatsTheCallStillConnectsAfterTheGrace() = runTest(UnconfinedTestDispatcher()) {
        engine.receiving = MutableStateFlow(emptySet())
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED"))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        runCurrent()
        assertEquals(CallPhase.Connecting, cc.state.value!!.phase)
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(CallPhase.Active, cc.state.value!!.phase)
    }

    @Test fun anIncomingCallRingsAndAcceptJoins() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        remote.current = call("RINGING", person("mum", "JOINED"), person("me", "INVITED")).copy(startedBy = "mum")
        cc.onFrame("call.ringing", buildJsonObject { put("callId", "c1") })
        advanceUntilIdle()
        assertEquals(CallPhase.Incoming, cc.state.value!!.phase)
        cc.accept()
        advanceUntilIdle()
        assertEquals(listOf("c1"), rings)
        assertTrue(remote.calls.any { it.startsWith("join") })
        assertTrue(remote.calls.any { it.startsWith("pull:mum") })
        assertEquals(CallPhase.Active, cc.state.value!!.phase)
    }

    @Test fun declineEndsAndClears() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.onFrame("call.ringing", buildJsonObject { put("callId", "c1") })
        advanceUntilIdle()
        cc.decline()
        assertEquals(CallPhase.Ended, cc.state.value?.phase)
        advanceTimeBy(5_000)
        assertTrue("decline" in remote.calls)
        assertNull(cc.state.value) // "Declined" shows a moment, then the call screen goes
    }

    @Test fun aSecondCallWhileBusyIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        val gets = remote.calls.count { it == "get" }
        cc.onFrame("call.ringing", buildJsonObject { put("callId", "c2") })
        assertEquals(gets, remote.calls.count { it == "get" })
        assertEquals(CallPhase.Ringing, cc.state.value!!.phase)
    }

    @Test fun aChatWithACallAlreadyGoingJoinsIt() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        remote.startResult = AppResult.Failure(AppError.Api("calls.already_in_call", 409, null, null, emptyMap()))
        cc.start("chat1", video = true)
        advanceUntilIdle()
        assertTrue("forChat" in remote.calls)
        assertTrue(remote.calls.any { it.startsWith("join") })
    }

    @Test fun muteAndCameraGoToTheEngineAndTheServer() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        cc.toggleMic()
        cc.toggleCamera()
        advanceUntilIdle()
        assertTrue("mic:false" in engine.events)
        assertTrue("camera:true" in engine.events)
        // The first is the real state sent on joining (camera off in a voice call), then mute, then camera on.
        assertEquals(listOf("media:true/false", "media:false/false", "media:false/true"), remote.calls.filter { it.startsWith("media") })
    }

    @Test fun sharingTheScreenTellsEveryoneItsAScreenAndStopGivesTheCameraBack() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        cc.toggleScreenShare()
        advanceUntilIdle()
        assertTrue(cc.state.value!!.screen)
        assertTrue("screen:true" in engine.events)
        cc.toggleScreenShare()
        advanceUntilIdle()
        assertEquals(false, cc.state.value!!.screen)
        // The video track counts as on while it carries the screen, and goes back to the camera's real state after.
        assertEquals(listOf("media:true/false", "media:true/true/screen", "media:true/false"), remote.calls.filter { it.startsWith("media") })
    }

    @Test fun sayingNoToTheSystemsPromptChangesNothing() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        engine.screenConsent = false
        cc.toggleScreenShare()
        advanceUntilIdle()
        assertEquals(false, cc.state.value!!.screen)
        assertNull(cc.state.value!!.error)
        assertEquals(listOf("media:true/false"), remote.calls.filter { it.startsWith("media") })
    }

    @Test fun theSystemStoppingTheShareStopsItOnTheCall() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        cc.toggleCamera()
        cc.toggleScreenShare()
        advanceUntilIdle()
        engine.sharing.value = false // the status bar's Stop, or the screen locked
        advanceUntilIdle()
        assertEquals(false, cc.state.value!!.screen)
        assertEquals("media:true/true", remote.calls.last { it.startsWith("media") }, "the camera that was on stays on")
    }

    @Test fun aPhoneThatCantShareItsScreenNeverTries() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        engine.screenSupported = false
        cc.start("chat1", video = false)
        advanceUntilIdle()
        assertEquals(false, cc.canShareScreen)
        cc.toggleScreenShare()
        advanceUntilIdle()
        assertTrue(engine.events.none { it.startsWith("screen") })
    }

    @Test fun someoneSharingTheirScreenIsMarkedOnTheirTile() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED").copy(video = true, screen = true))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        advanceUntilIdle()
        assertTrue(cc.state.value!!.people.first { it.userId == "mum" }.screen)
        // An older server never says it: a plain video.
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED").copy(video = true))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        assertEquals(false, cc.state.value!!.people.first { it.userId == "mum" }.screen)
    }

    @Test fun frameUpdatesDontLoseTheIceServers() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        remote.current = call("RINGING", person("mum", "JOINED"), person("me", "INVITED")).copy(startedBy = "mum", iceServers = listOf(com.circaso.feature.calls.data.IceServerDto(listOf("turn:turn.example:3478"), "u", "p")))
        cc.onFrame("call.ringing", buildJsonObject { put("callId", "c1") })
        // Frames carry no ICE servers (the backend keeps TURN credentials out of its frame log).
        cc.onFrame("call.updated", frame("call.updated", remote.current.copy(iceServers = emptyList())))
        cc.accept()
        advanceUntilIdle()
        assertEquals(listOf("turn:turn.example:3478"), engine.ice.single().urls)
    }

    @Test fun aRefusedCameraDoesntPretendToBeVideo() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        engine.cameraAllowed = false
        cc.toggleCamera()
        advanceUntilIdle()
        assertEquals(false, cc.state.value!!.camera)
        assertTrue(cc.state.value!!.error!!.contains("camera"))
        assertEquals(listOf("media:true/false"), remote.calls.filter { it.startsWith("media") }) // only the join's real state
    }

    @Test fun callingBackRightAfterAnEndIsntWipedByTheOldCleanUp() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        cc.onFrame("call.ended", frame("call.ended", remote.current.copy(state = "ENDED", endReason = "COMPLETED")))
        remote.current = call(people = arrayOf(person("me", "INVITED"), person("mum", "INVITED")))
        cc.start("chat1", video = false)
        advanceTimeBy(5_000)
        assertEquals(CallPhase.Ringing, cc.state.value?.phase)
    }

    @Test fun addPeopleRingsWhoeverIsntOnTheCall() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED"), person("dad", "MISSED"))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        advanceUntilIdle()
        assertEquals(listOf("dad"), cc.state.value!!.notOnCall.map { it.userId })
        cc.ringOthers()
        advanceUntilIdle()
        assertTrue("ring" in remote.calls)
        assertTrue(cc.state.value!!.notOnCall.isEmpty())
        assertTrue(cc.state.value!!.people.first { it.userId == "dad" }.ringing)
    }

    @Test fun aDroppedConnectionRejoinsAndPullsEveryoneAgain() = runTest(UnconfinedTestDispatcher()) {
        val net = MutableStateFlow(true)
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, online = net)
        cc.start("chat1", video = false)
        advanceUntilIdle()
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED"))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        advanceUntilIdle()
        engine.link.value = true
        val joins = remote.calls.count { it.startsWith("join") }
        val pulls = remote.calls.count { it.startsWith("pull") }

        net.value = false
        engine.link.value = false
        runCurrent()
        assertEquals(CallPhase.Reconnecting, cc.state.value!!.phase)
        net.value = true
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(joins + 1, remote.calls.count { it.startsWith("join") }, "rejoined with a fresh session")
        assertEquals(pulls + 1, remote.calls.count { it.startsWith("pull") }, "pulled everyone again")
        assertEquals(CallPhase.Reconnecting, cc.state.value!!.phase, "until the fresh connection is up")
        engine.link.value = true
        runCurrent()
        assertEquals(CallPhase.Active, cc.state.value!!.phase)
    }

    @Test fun someoneRejoiningIsPulledAgain() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED").copy(connection = "a1"))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        advanceUntilIdle()
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED").copy(connection = "b2"))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        advanceUntilIdle()
        assertEquals(2, remote.calls.count { it.startsWith("pull:mum") })
    }

    @Test fun aCallThatCantReconnectHangsUp() = runTest(UnconfinedTestDispatcher()) {
        val net = MutableStateFlow(true)
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, online = net, reconnectGiveUpMillis = 10_000)
        cc.start("chat1", video = false)
        advanceUntilIdle()
        net.value = false
        runCurrent()
        advanceTimeBy(11_000)
        runCurrent()
        assertTrue("leave" in remote.calls)
    }

    @Test fun aCallRungToMeThatINeverJoinedIsLoggedAsMissed() = runTest(UnconfinedTestDispatcher()) {
        val logged = mutableListOf<CallLogEntry>()
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, log = { logged += it })
        val ended = call("ENDED", person("mum", "LEFT"), person("me", "MISSED")).copy(startedBy = "mum", endReason = "MISSED", endedAt = "2026-10-29T10:00:45Z")
        cc.onFrame("call.ended", frame("call.ended", ended))
        assertEquals(CallLogOutcome.Missed, logged.single().outcome)
        assertEquals(false, logged.single().outgoing)
    }

    @Test fun aFinishedCallIsLoggedWithItsTalkingTime() = runTest(UnconfinedTestDispatcher()) {
        val logged = mutableListOf<CallLogEntry>()
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, log = { logged += it })
        val ended = call("ENDED", person("me", "LEFT").copy(joinedAt = "2026-10-29T10:00:05Z"), person("mum", "LEFT").copy(joinedAt = "2026-10-29T10:00:10Z"))
            .copy(endReason = "COMPLETED", startedAt = "2026-10-29T10:00:10Z", endedAt = "2026-10-29T10:04:10Z")
        cc.onFrame("call.ended", frame("call.ended", ended))
        assertEquals(CallLogOutcome.Completed, logged.single().outcome)
        assertEquals(240, logged.single().durationSeconds)
        assertTrue(logged.single().outgoing)
    }

    @Test fun aHistoryWriteThatFailsStillEndsTheCall() = runTest(UnconfinedTestDispatcher()) {
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, log = { throw IllegalStateException("UNIQUE constraint failed: message.clientMessageId") })
        cc.start("chat1", video = false)
        val ended = call("ENDED", person("me", "LEFT"), person("mum", "LEFT")).copy(endReason = "COMPLETED")
        cc.onFrame("call.ended", frame("call.ended", ended))
        assertEquals(CallPhase.Ended, cc.state.value?.phase)
    }

    @Test fun endedFrameClosesTheEngine() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        cc.onFrame("call.ended", frame("call.ended", remote.current.copy(state = "ENDED", endReason = "COMPLETED")))
        assertTrue("close" in engine.events)
        advanceTimeBy(5_000)
        assertNull(cc.state.value)
    }

    private val photos = CallPhotos(chat = "https://p/mum.jpg?sig=1", people = mapOf("mum" to "https://p/mum.jpg?sig=1", "otieno" to "https://p/otieno.jpg"), me = "https://p/me.jpg")

    @Test fun anIncomingCallShowsTheCallersPhoto() = runTest(UnconfinedTestDispatcher()) {
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, photos = { photos })
        remote.current = call("RINGING", person("mum", "JOINED"), person("me", "INVITED")).copy(startedBy = "mum")
        cc.onFrame("call.ringing", buildJsonObject { put("callId", "c1") })
        advanceUntilIdle()
        assertEquals("https://p/mum.jpg?sig=1", cc.state.value!!.photoUrl)
        cc.accept()
        advanceUntilIdle()
        val s = cc.state.value!!
        assertEquals(CallPhase.Active, s.phase)
        assertEquals("https://p/mum.jpg?sig=1", s.photoUrl)
        assertEquals("https://p/mum.jpg?sig=1", s.people.single().photoUrl)
    }

    @Test fun aCallerFromAChatNotOnThisPhoneStillShowsTheirPhotoFromContacts() = runTest(UnconfinedTestDispatcher()) {
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { null }, photos = { CallPhotos(people = mapOf("mum" to "https://p/mum.jpg")) })
        remote.current = call("RINGING", person("mum", "JOINED"), person("me", "INVITED")).copy(startedBy = "mum")
        cc.onFrame("call.ringing", buildJsonObject { put("callId", "c1") })
        advanceUntilIdle()
        assertEquals("https://p/mum.jpg", cc.state.value!!.photoUrl)
    }

    @Test fun aGroupRingsWithTheGroupsPhotoAndEachTileHasItsPersonsPhoto() = runTest(UnconfinedTestDispatcher()) {
        val group = photos.copy(chat = "/files/group.jpg")
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Holiday Fund" to true }, photos = { group })
        remote.current = call("RINGING", person("otieno", "JOINED"), person("mum", "INVITED"), person("me", "INVITED")).copy(startedBy = "otieno")
        cc.onFrame("call.ringing", buildJsonObject { put("callId", "c1") })
        advanceUntilIdle()
        val s = cc.state.value!!
        assertEquals(CallPhase.Incoming, s.phase)
        assertEquals("/files/group.jpg", s.photoUrl) // the group's photo, not the caller's
        assertEquals("https://p/me.jpg", s.myPhotoUrl)
        assertEquals(mapOf("otieno" to "https://p/otieno.jpg", "mum" to "https://p/mum.jpg?sig=1"), s.people.associate { it.userId to it.photoUrl })
    }

    @Test fun anOutgoingCallShowsWhoItIsCalling() = runTest(UnconfinedTestDispatcher()) {
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, photos = { photos })
        cc.start("chat1", video = false)
        advanceUntilIdle()
        assertEquals(CallPhase.Ringing, cc.state.value!!.phase)
        assertEquals("https://p/mum.jpg?sig=1", cc.state.value!!.photoUrl)
    }

    @Test fun aSlowOrBrokenPhotoLookupRingsAtOnceWithInitialsThenTheLatePhotoFillsIn() = runTest(UnconfinedTestDispatcher()) {
        val gate = kotlinx.coroutines.CompletableDeferred<CallPhotos>()
        val slow = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, photos = { gate.await() })
        remote.current = call("RINGING", person("mum", "JOINED"), person("me", "INVITED")).copy(startedBy = "mum")
        slow.onFrame("call.ringing", buildJsonObject { put("callId", "c1") })
        assertEquals(CallPhase.Incoming, slow.state.value!!.phase)
        assertTrue(currentTime <= 100, "rang after ${currentTime} ms") // waits at most 100 ms for the photo
        assertNull(slow.state.value!!.photoUrl)
        gate.complete(photos)
        kotlinx.coroutines.withTimeout(1_000) { slow.state.first { it?.photoUrl != null } }
        assertEquals("https://p/mum.jpg?sig=1", slow.state.value!!.photoUrl)
        assertEquals("https://p/mum.jpg?sig=1", slow.state.value!!.people.single().photoUrl)

        val broken = CallController(remote, FakeEngine(), myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, photos = { error("store closed") })
        broken.onFrame("call.ringing", buildJsonObject { put("callId", "c1") })
        advanceUntilIdle()
        assertEquals(CallPhase.Incoming, broken.state.value!!.phase)
        assertNull(broken.state.value!!.photoUrl)
    }

    @Test fun anOutgoingCallNeverWaitsForItsPhoto() = runTest(UnconfinedTestDispatcher()) {
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, photos = { kotlinx.coroutines.awaitCancellation() })
        cc.start("chat1", video = false)
        runCurrent()
        assertEquals("start", remote.calls.first()) // CL1 went out with no time passing
        assertEquals(0L, currentTime)
    }

    @Test fun aLatePhotoFromTheLastCallNeverLandsOnTheNextOne() = runTest(UnconfinedTestDispatcher()) {
        val gate = kotlinx.coroutines.CompletableDeferred<CallPhotos>()
        var first = true
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, photos = { if (first) { first = false; gate.await() } else CallPhotos() })
        remote.current = call("RINGING", person("mum", "JOINED"), person("me", "INVITED")).copy(startedBy = "mum")
        cc.onFrame("call.ringing", buildJsonObject { put("callId", "c1") })
        cc.decline()
        // Rung again while "Declined" is still up: the new call takes over before the first lookup answers.
        cc.onFrame("call.ringing", buildJsonObject { put("callId", "c1") })
        assertEquals(CallPhase.Incoming, cc.state.value!!.phase)
        gate.complete(photos)
        advanceUntilIdle()
        assertNull(cc.state.value!!.photoUrl)
    }

    // ---- 2026-11-09: one person could leave a 1:1 call; the other's call lived on ----

    private fun TestScope.liveController(socket: MutableStateFlow<Boolean> = MutableStateFlow(true)) =
        CallController(
            remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, stopRinging = { rings += it },
            pullGraceMillis = 3_000, pollMillis = 5_000, socketUp = socket, stallMillis = 20_000, clock = { currentTime },
        )

    @Test fun aSlowHangUpNoLongerWipesTheNextCall() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        remote.leaveMillis = 6_000
        cc.leave() // its CL7 answers in 6 s
        // Mum calls straight back while the hang-up is still on its way.
        remote.current = call("RINGING", person("me", "INVITED"), person("mum", "JOINED")).copy(callId = "c2", startedBy = "mum")
        cc.onFrame("call.ringing", buildJsonObject { put("callId", "c2") })
        assertEquals(CallPhase.Incoming, cc.state.value!!.phase)
        advanceTimeBy(6_000 + 1_500 + 100)
        runCurrent()
        assertEquals("c2", cc.state.value?.callId, "the last call's clean-up left the new one alone")
        assertEquals(CallPhase.Incoming, cc.state.value!!.phase)
    }

    @Test fun aCallEndedFrameLostWithTheSocketIsCaughtByReadingTheCall() = runTest(UnconfinedTestDispatcher()) {
        val cc = liveController()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED"))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        // Mum hangs up; the call.ended frame never arrives.
        remote.current = remote.current.copy(state = "ENDED", endReason = "COMPLETED", participants = listOf(person("me", "LEFT"), person("mum", "LEFT")))
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(CallPhase.Ended, cc.state.value!!.phase)
        assertTrue("close" in engine.events, "the peer connection is closed")
    }

    @Test fun theSocketComingBackReadsTheCallAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val socket = MutableStateFlow(true)
        val cc = liveController(socket)
        cc.start("chat1", video = false)
        advanceUntilIdle()
        socket.value = false
        remote.current = remote.current.copy(state = "ENDED", endReason = "CANCELLED")
        val before = remote.calls.count { it == "get" }
        socket.value = true
        runCurrent()
        assertEquals(before + 1, remote.calls.count { it == "get" })
        assertEquals(CallPhase.Ended, cc.state.value!!.phase)
    }

    @Test fun aRingThatStoppedEndsTheIncomingScreen() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        remote.current = call("RINGING", person("me", "INVITED"), person("mum", "JOINED")).copy(startedBy = "mum")
        cc.onFrame("call.ringing", buildJsonObject { put("callId", "c1") })
        assertEquals(CallPhase.Incoming, cc.state.value!!.phase)
        cc.onFrame("call.ring_stopped", buildJsonObject { put("callId", "c1"); put("reason", "CANCELLED") })
        assertEquals(CallPhase.Ended, cc.state.value!!.phase)
        assertEquals("CANCELLED", cc.state.value!!.endReason)
        assertTrue("c1" in rings)
    }

    @Test fun aRingingCallThatIsOverIsCaughtWithoutAFrame() = runTest(UnconfinedTestDispatcher()) {
        val cc = liveController()
        remote.current = call("RINGING", person("me", "INVITED"), person("mum", "JOINED")).copy(startedBy = "mum")
        cc.onFrame("call.ringing", buildJsonObject { put("callId", "c1") })
        remote.current = remote.current.copy(state = "ENDED", endReason = "CANCELLED")
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(CallPhase.Ended, cc.state.value!!.phase)
    }

    @Test fun pullsWaitForBothConnectionsAndTheConnectionIsReportedOnce() = runTest(UnconfinedTestDispatcher()) {
        val cc = liveController()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        // Mum joined but her connection isn't up yet, nor ours: no pull (the SFU would hold it ~9 s).
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED"))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        runCurrent()
        assertTrue(remote.calls.none { it.startsWith("pull") })
        // Ours comes up: reported once (CL3b).
        engine.link.value = true
        runCurrent()
        assertEquals(listOf("connected:s-me"), remote.calls.filter { it.startsWith("connected") })
        assertTrue(remote.calls.none { it.startsWith("pull") }, "Mum's still connecting")
        // Hers comes up: pulled at once.
        remote.current = remote.current.copy(participants = remote.current.participants.map { if (it.userId == "mum") it.copy(connected = true) else it })
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        runCurrent()
        assertEquals(1, remote.calls.count { it.startsWith("pull") })
        engine.link.value = false
        engine.link.value = true
        runCurrent()
        assertEquals(1, remote.calls.count { it.startsWith("connected") }, "same session: said once")
    }

    @Test fun someoneWhoseAppNeverSaysConnectedIsPulledAfterTheGrace() = runTest(UnconfinedTestDispatcher()) {
        val cc = liveController()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        engine.link.value = true
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED"))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        runCurrent()
        assertTrue(remote.calls.none { it.startsWith("pull") })
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(1, remote.calls.count { it.startsWith("pull") })
    }

    @Test fun aCallThatHearsNothingFor20SecondsReconnectsAndEndsOnlyIfNothingComesBack() = runTest(UnconfinedTestDispatcher()) {
        engine.receiving = MutableStateFlow(emptySet())
        val cc = liveController()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        engine.link.value = true
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED").copy(connected = true))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        runCurrent()
        engine.receiving!!.value = setOf("2", "3")
        runCurrent()
        assertEquals(CallPhase.Active, cc.state.value!!.phase)
        engine.packets = 500
        advanceTimeBy(5_001); runCurrent()
        engine.packets = 900
        advanceTimeBy(5_000); runCurrent()
        assertTrue("leave" !in remote.calls, "packets still arriving")
        // Nothing more arrives, and the server still says ACTIVE: the media path is rebuilt, not hung up (2026-11-13).
        val joins = remote.calls.count { it.startsWith("join") }
        advanceTimeBy(20_500); runCurrent()
        assertTrue("leave" !in remote.calls, "a stall alone no longer hangs up")
        assertEquals(CallPhase.Reconnecting, cc.state.value!!.phase)
        advanceTimeBy(2_600); runCurrent()
        assertEquals(joins + 1, remote.calls.count { it.startsWith("join") }, "a fresh session, even though ICE says it's up")
        assertEquals(CallPhase.Active, cc.state.value!!.phase)
        // Mum's phone is really gone: still nothing after the rejoin, so the call ends, once.
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, remote.calls.count { it == "leave" })
        assertEquals("STALL", remote.leaves.single().first)
        assertTrue(cc.state.value?.phase.let { it == null || it == CallPhase.Ended })
        assertTrue("close" in engine.events)
    }

    @Test fun packetsAfterAStallReconnectMeanTheNextStallReconnectsAgain() = runTest(UnconfinedTestDispatcher()) {
        engine.receiving = MutableStateFlow(emptySet())
        val cc = liveController()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        engine.link.value = true
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED").copy(connected = true))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        engine.receiving!!.value = setOf("2", "3")
        runCurrent()
        engine.packets = 100
        advanceTimeBy(5_001); runCurrent()
        advanceTimeBy(25_000); runCurrent() // stall → reconnect → rejoined
        assertEquals(CallPhase.Active, cc.state.value!!.phase)
        engine.packets = 50 // the new connection's counter
        advanceTimeBy(5_000); runCurrent()
        engine.packets = 400 // audio is back
        advanceTimeBy(5_000); runCurrent()
        advanceTimeBy(25_000); runCurrent() // a later stall: reconnect again, not the end
        assertTrue("leave" !in remote.calls)
    }

    @Test fun aNetworkBlipWhileTheMediaIsUpNeverEndsTheCall() = runTest(UnconfinedTestDispatcher()) {
        val net = MutableStateFlow(true)
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, online = net, reconnectGiveUpMillis = 40_000)
        cc.start("chat1", video = false)
        advanceUntilIdle()
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED"))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        engine.link.value = true
        runCurrent()
        // The OS flaps (the 2026-11-13 Redmi call), but ICE keeps the audio going the whole time.
        net.value = false
        advanceTimeBy(60_000); runCurrent()
        assertTrue("leave" !in remote.calls)
        assertEquals(CallPhase.Active, cc.state.value!!.phase)
    }

    @Test fun aDropThatIcesOwnRecoveryFixesGoesOnWithoutARejoin() = runTest(UnconfinedTestDispatcher()) {
        val net = MutableStateFlow(true)
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, online = net)
        cc.start("chat1", video = false)
        advanceUntilIdle()
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED"))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        engine.link.value = true
        val joins = remote.calls.count { it.startsWith("join") }
        engine.link.value = false
        runCurrent()
        assertEquals(CallPhase.Reconnecting, cc.state.value!!.phase)
        engine.link.value = true
        advanceTimeBy(2_600); runCurrent()
        assertEquals(CallPhase.Active, cc.state.value!!.phase)
        assertEquals(joins, remote.calls.count { it.startsWith("join") })
    }

    @Test fun oneLeavePerCallHoweverManyWaysItEnds() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        cc.leave()
        cc.leave()
        cc.leave(CallEndCause.ReconnectGaveUp)
        advanceUntilIdle()
        assertEquals(1, remote.calls.count { it == "leave" })
        assertEquals("USER_HANGUP", remote.leaves.single().first)
    }

    @Test fun aLeaveTheNetworkLostIsSentAgainWithTheSameKey() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        remote.leaveFailures = 2
        cc.leave()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(3, remote.leaves.size)
        assertEquals(1, remote.leaves.map { it.second }.toSet().size, "one Idempotency-Key for every try")
    }

    @Test fun aCallThatCantReconnectEndsAsDropped() = runTest(UnconfinedTestDispatcher()) {
        val net = MutableStateFlow(true)
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, online = net, reconnectGiveUpMillis = 10_000)
        cc.start("chat1", video = false)
        advanceUntilIdle()
        engine.link.value = false
        runCurrent()
        advanceTimeBy(11_000)
        runCurrent()
        assertTrue(remote.calls.count { it.startsWith("join") } >= 2, "it tried a fresh session")
        assertEquals("RECONNECT_GAVE_UP", remote.leaves.single().first)
        assertEquals("DROPPED", cc.state.value!!.endReason)
    }

    // ---- 2026-11-13: why did this call end ----

    private class MemoryTimeline : CallTimelineStore {
        var saved: String? = null
        override suspend fun save(snapshot: String) { saved = snapshot }
        override suspend fun load() = saved
        override suspend fun clear() { saved = null }
    }

    private fun TestScope.recorder(reports: MutableList<QueuedCallReport>, store: CallTimelineStore? = null, socket: MutableStateFlow<String>? = null) = CallRecorder(
        backgroundScope, engine, device = { CallReportDevice("android", "1.0 (1)", "14", "Xiaomi Redmi") },
        submit = { reports += it }, store = store, socket = socket, clock = { currentTime },
    )

    @Test fun anEndedCallSendsItsTimelineAndCause() = runTest(UnconfinedTestDispatcher()) {
        val reports = mutableListOf<QueuedCallReport>()
        val socket = MutableStateFlow("up")
        engine.stats = com.circaso.core.calls.CallStats(rttMs = 80.0, jitterMs = 4.0, packetsReceived = 100, packetsLost = 0, bytesReceived = 9_000)
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, recorder = recorder(reports, socket = socket))
        cc.start("chat1", video = false)
        advanceUntilIdle()
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED"))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        advanceTimeBy(5_001); runCurrent()
        engine.stats = engine.stats!!.copy(rttMs = 120.0, packetsReceived = 290, packetsLost = 10, bytesReceived = 25_000)
        socket.value = "down"
        cc.toggleMic()
        advanceTimeBy(5_000); runCurrent()
        cc.leave()
        advanceUntilIdle()
        val r = reports.single()
        assertEquals("c1", r.callId)
        assertEquals("USER_HANGUP", r.report.endCause)
        assertEquals(true, r.report.outgoing)
        assertEquals("Xiaomi Redmi", r.report.deviceModel)
        val types = r.report.events.map { it.type + ":" + it.detail }
        assertTrue("socket:down" in types, "$types")
        assertTrue("mic:off" in types, "$types")
        assertTrue("phase:Active" in types || "phase:Connecting" in types, "$types")
        assertEquals("end:USER_HANGUP", types.last())
        assertEquals(2, r.report.samples.size)
        assertEquals(5.0, r.report.samples.last().lossPct) // 10 lost of 190 + 10
        assertEquals(100.0, r.report.summary.avgRttMs)
        assertEquals(120.0, r.report.summary.maxRttMs)
        assertEquals(190L, r.report.summary.packetsReceived)
    }

    @Test fun aRingIDeclinedIsNotReported() = runTest(UnconfinedTestDispatcher()) {
        val reports = mutableListOf<QueuedCallReport>()
        val cc = CallController(remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, recorder = recorder(reports))
        remote.current = call("RINGING", person("me", "INVITED"), person("mum", "JOINED")).copy(startedBy = "mum")
        cc.onFrame("call.ringing", buildJsonObject { put("callId", "c1") })
        cc.decline()
        advanceUntilIdle()
        assertTrue(reports.isEmpty())
    }

    @Test fun aCallTheAppDidntLiveToEndIsReportedAtTheNextLaunch() = runTest(UnconfinedTestDispatcher()) {
        val reports = mutableListOf<QueuedCallReport>()
        val store = MemoryTimeline()
        val first = recorder(reports, store)
        first.begin("c9", video = true, outgoing = false)
        first.joined()
        advanceTimeBy(6_000); runCurrent()
        first.event("net", "offline")
        runCurrent()
        assertTrue(store.saved != null, "the running call is kept on the phone")
        // The process dies here; at the next launch a new recorder finds it.
        val next = recorder(reports, store)
        next.recoverInterrupted()
        advanceUntilIdle()
        assertEquals("APP_KILLED", reports.single().report.endCause)
        assertEquals("c9", reports.single().callId)
        assertNull(store.saved)
    }

    // ---- 2026-11-13: a dropped phone is noticed in seconds; connecting does less after the answer ----

    private val beats = mutableListOf<String>()

    private fun TestScope.livenessController(giveUp: Long = 45_000) =
        CallController(
            remote, engine, myUserId = { "me" }, scope = backgroundScope, names = { "Mum" to false }, stopRinging = { rings += it },
            pullGraceMillis = 3_000, pollMillis = 5_000, stallMillis = 20_000, clock = { currentTime },
            heartbeatMillis = 2_000, sendFrame = { type, data -> beats += type + ":" + data["callId"].toString(); true },
            livenessMillis = 1_000, peerGiveUpMillis = giveUp, prewarm = true,
        )

    /** A call with Mum heard on mids 2 and 3; [packets] and [transport] are what the engine reads from then on. */
    private suspend fun TestScope.liveCall(cc: CallController, packets: () -> Long, transport: () -> Long) {
        engine.receiving = MutableStateFlow(emptySet())
        cc.start("chat1", video = false)
        advanceUntilIdle()
        engine.link.value = true
        remote.current = call("ACTIVE", person("me", "JOINED"), person("mum", "JOINED").copy(connected = true))
        cc.onFrame("call.updated", frame("call.updated", remote.current))
        runCurrent()
        engine.receiving!!.value = setOf("2", "3")
        runCurrent()
        engine.reading = { com.circaso.core.calls.LinkReading(mapOf("2" to packets(), "3" to 0L), transport()) }
    }

    @Test fun theirPhoneDroppingShowsWithinSecondsAndTheCallWaitsForThemWithoutRejoining() = runTest(UnconfinedTestDispatcher()) {
        var packets = 0L
        var transport = 0L
        val cc = livenessController()
        liveCall(cc, { packets }, { transport })
        assertEquals(CallPhase.Active, cc.state.value!!.phase)
        repeat(3) { packets += 50; transport += 1_000; advanceTimeBy(1_000); runCurrent() }
        assertEquals(false, cc.state.value!!.people.single().dropped)
        val joins = remote.calls.count { it.startsWith("join") }
        // Mum's phone goes off the network: her packets stop, while this phone still hears the SFU.
        repeat(4) { transport += 100; advanceTimeBy(1_000); runCurrent() }
        val mum = cc.state.value!!.people.single()
        assertTrue(mum.dropped, "said within ~3 s, not 20")
        assertTrue(!mum.live)
        assertEquals(CallPhase.Active, cc.state.value!!.phase, "this phone's own call is fine: it doesn't say Reconnecting about itself")
        assertEquals(joins, remote.calls.count { it.startsWith("join") }, "the healthy side keeps its connection")
        // She's back.
        packets += 50; transport += 1_000; advanceTimeBy(1_000); runCurrent()
        assertEquals(false, cc.state.value!!.people.single().dropped)
        assertTrue("leave" !in remote.calls)
    }

    @Test fun whenThisPhonesOwnConnectionHearsNothingItReconnectsAtOnce() = runTest(UnconfinedTestDispatcher()) {
        var packets = 0L
        var transport = 0L
        val cc = livenessController()
        liveCall(cc, { packets }, { transport })
        repeat(5) { packets += 50; transport += 1_000; advanceTimeBy(1_000); runCurrent() }
        val joins = remote.calls.count { it.startsWith("join") }
        // Nothing at all arrives any more: not Mum's media, not the SFU's answers. It's this phone.
        repeat(3) { advanceTimeBy(1_000); runCurrent() }
        assertEquals(false, cc.state.value!!.people.single().dropped, "never blamed on Mum")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(joins + 1, remote.calls.count { it.startsWith("join") }, "rejoined at once, no 2.5 s wait and no 20 s stall")
        assertTrue(remote.joinPulls.last(), "the rejoin pulls everyone connected with it")
    }

    @Test fun aDirectCallWhoseOtherSideNeverComesBackEndsAsPeerLost() = runTest(UnconfinedTestDispatcher()) {
        var packets = 0L
        var transport = 0L
        val cc = livenessController(giveUp = 45_000)
        liveCall(cc, { packets }, { transport })
        repeat(3) { packets += 50; transport += 1_000; advanceTimeBy(1_000); runCurrent() }
        repeat(30) { transport += 100; advanceTimeBy(1_000); runCurrent() }
        assertTrue("leave" !in remote.calls, "still waiting for her")
        repeat(20) { transport += 100; advanceTimeBy(1_000); runCurrent() }
        assertEquals("PEER_LOST", remote.leaves.single().first)
    }

    @Test fun herHeartbeatSayingSheIsStillOnlineKeepsTheCallWaitingLonger() = runTest(UnconfinedTestDispatcher()) {
        var packets = 0L
        var transport = 0L
        val cc = livenessController(giveUp = 45_000)
        liveCall(cc, { packets }, { transport })
        repeat(3) { packets += 50; transport += 1_000; advanceTimeBy(1_000); runCurrent() }
        repeat(60) {
            transport += 100
            cc.onFrame("call.heartbeat", buildJsonObject { put("callId", "c1"); put("userId", "mum") })
            advanceTimeBy(1_000); runCurrent()
        }
        assertTrue("leave" !in remote.calls, "her phone is online and rebuilding its side")
        assertTrue(cc.state.value!!.people.single().dropped)
    }

    @Test fun aPhoneInACallSaysItIsStillThereEveryTwoSeconds() = runTest(UnconfinedTestDispatcher()) {
        val cc = livenessController()
        liveCall(cc, { 0L }, { 0L })
        beats.clear()
        advanceTimeBy(4_100); runCurrent()
        assertEquals(2, beats.size)
        assertEquals("heartbeat:\"c1\"", beats.first())
        cc.leave()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, beats.size, "no heartbeat after the call")
    }

    @Test fun anIncomingCallIsPreparedWhileItRingsAndDecliningDropsIt() = runTest(UnconfinedTestDispatcher()) {
        remote.current = call("RINGING", person("me", "INVITED"), person("mum", "JOINED")).copy(startedBy = "mum")
        val cc = livenessController()
        cc.onFrame("call.ringing", buildJsonObject { put("callId", "c1") })
        runCurrent()
        assertTrue("prepare:false" in engine.events, "built while it rings")
        assertTrue(engine.events.none { it.startsWith("mic") || it.startsWith("speaker") }, "no microphone or audio route while ringing")
        cc.decline()
        runCurrent()
        assertTrue("close" in engine.events)
    }

    @Test fun joiningAnswersThePullThatCameWithItWithoutAskingAgain() = runTest(UnconfinedTestDispatcher()) {
        remote.current = call("RINGING", person("me", "INVITED"), person("mum", "JOINED").copy(connected = true)).copy(startedBy = "mum")
        remote.joinPull = PullResponse("sfu-offer", true, listOf(PulledTrackDto("mum", "audio-mum", "2"), PulledTrackDto("mum", "video-mum", "3")))
        val cc = controller()
        cc.onFrame("call.ringing", buildJsonObject { put("callId", "c1") })
        cc.accept()
        advanceUntilIdle()
        assertTrue(remote.joinPulls.single())
        assertTrue("renegotiate:my-answer" in remote.calls)
        assertTrue(remote.calls.none { it.startsWith("pull") }, "Mum came with the join")
        assertEquals("3", cc.state.value!!.people.single().videoMid)
    }
}
