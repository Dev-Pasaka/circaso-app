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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
    override suspend fun start(chatId: String, video: Boolean, key: String): AppResult<CallDto> { calls += "start"; return startResult ?: AppResult.Success(current) }
    override suspend fun get(callId: String) = AppResult.Success(current).also { calls += "get" }
    override suspend fun forChat(chatId: String) = AppResult.Success(current).also { calls += "forChat" }
    override suspend fun join(callId: String, request: JoinCallRequest): AppResult<JoinCallResponse> {
        calls += "join:" + request.tracks.joinToString { it.trackName }
        current = current.copy(participants = current.participants.map { if (it.userId == "me") person("me", "JOINED") else it })
        if (current.participants.count { it.state == "JOINED" } >= 2) current = current.copy(state = "ACTIVE")
        return AppResult.Success(JoinCallResponse("s-me", "sfu-answer", current))
    }
    override suspend fun pull(callId: String, request: PullRequest): AppResult<PullResponse> {
        calls += "pull:" + request.tracks.joinToString { it.userId + "/" + it.trackName }
        return AppResult.Success(PullResponse(pullOffer, pullOffer != null, request.tracks.mapIndexed { i, t -> PulledTrackDto(t.userId, t.trackName, "${i + 2}") }))
    }
    override suspend fun renegotiate(callId: String, request: RenegotiateRequest): AppResult<JsonObject> { calls += "renegotiate:${request.answer}"; return AppResult.Success(JsonObject(emptyMap())) }
    override suspend fun decline(callId: String) = AppResult.Success(current).also { calls += "decline" }
    override suspend fun leave(callId: String) = AppResult.Success(current).also { calls += "leave" }
    override suspend fun ring(callId: String, userIds: List<String>): AppResult<CallDto> {
        calls += "ring"
        current = current.copy(participants = current.participants.map { if (it.state == "MISSED") it.copy(state = "INVITED") else it })
        return AppResult.Success(current)
    }
    override suspend fun media(callId: String, audio: Boolean, video: Boolean): AppResult<JsonObject> { calls += "media:$audio/$video"; return AppResult.Success(JsonObject(emptyMap())) }
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

    @Test fun endedFrameClosesTheEngine() = runTest(UnconfinedTestDispatcher()) {
        val cc = controller()
        cc.start("chat1", video = false)
        advanceUntilIdle()
        cc.onFrame("call.ended", frame("call.ended", remote.current.copy(state = "ENDED", endReason = "COMPLETED")))
        assertTrue("close" in engine.events)
        advanceTimeBy(5_000)
        assertNull(cc.state.value)
    }
}
