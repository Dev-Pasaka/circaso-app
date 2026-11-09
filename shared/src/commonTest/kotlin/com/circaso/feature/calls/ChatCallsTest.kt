package com.circaso.feature.calls

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.calls.data.CallDto
import com.circaso.feature.calls.data.CallsRemote
import com.circaso.feature.calls.data.JoinCallRequest
import com.circaso.feature.calls.data.JoinCallResponse
import com.circaso.feature.calls.data.ParticipantDto
import com.circaso.feature.calls.data.PullRequest
import com.circaso.feature.calls.data.PullResponse
import com.circaso.feature.calls.data.RenegotiateRequest
import com.circaso.feature.calls.presentation.CallBarAction
import com.circaso.feature.calls.presentation.CallBarFace
import com.circaso.feature.calls.presentation.chatCallBar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun p(id: String, state: String = "JOINED", joinedAt: String? = "2026-11-09T10:00:00Z") = ParticipantDto(id, id.replaceFirstChar { it.uppercase() }, state, joinedAt = joinedAt)

private fun dto(callId: String = "c1", chatId: String = "g1", state: String = "ACTIVE", startedAt: String? = "2026-11-09T10:00:05Z", vararg people: ParticipantDto) =
    CallDto(callId, chatId, "VOICE", state, "amina", "2026-11-09T10:00:00Z", startedAt = startedAt, participants = people.toList())

private fun frame(c: CallDto): JsonObject = Json.encodeToJsonElement(CallDto.serializer(), c).jsonObject

private class LiveRemote : CallsRemote {
    val log = mutableListOf<String>()
    var live: AppResult<List<CallDto>> = AppResult.Success(emptyList())
    var gate: CompletableDeferred<Unit>? = null
    val byChat = mutableMapOf<String, AppResult<CallDto>>()
    val byId = mutableMapOf<String, AppResult<CallDto>>()
    override suspend fun live(): AppResult<List<CallDto>> { log += "live"; gate?.await(); return live }
    override suspend fun forChat(chatId: String): AppResult<CallDto> { log += "forChat:$chatId"; gate?.await(); return byChat[chatId] ?: notFound }
    override suspend fun get(callId: String): AppResult<CallDto> { log += "get:$callId"; return byId[callId] ?: notFound }
    override suspend fun start(chatId: String, video: Boolean, key: String) = error("unused")
    override suspend fun join(callId: String, request: JoinCallRequest): AppResult<JoinCallResponse> = error("unused")
    override suspend fun connected(callId: String, sessionId: String): AppResult<JsonObject> = error("unused")
    override suspend fun pull(callId: String, request: PullRequest): AppResult<PullResponse> = error("unused")
    override suspend fun renegotiate(callId: String, request: RenegotiateRequest): AppResult<JsonObject> = error("unused")
    override suspend fun decline(callId: String): AppResult<CallDto> = error("unused")
    override suspend fun leave(callId: String): AppResult<CallDto> = error("unused")
    override suspend fun media(callId: String, audio: Boolean, video: Boolean): AppResult<JsonObject> = error("unused")
    override suspend fun ring(callId: String, userIds: List<String>): AppResult<CallDto> = error("unused")

    companion object {
        val notFound = AppResult.Failure(AppError.Api("calls.not_found", 404, null, null, emptyMap()))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatCallsTest {
    private fun TestScope.store(remote: LiveRemote, socket: MutableStateFlow<Boolean> = MutableStateFlow(false), mine: MutableStateFlow<CallUi?> = MutableStateFlow(null), poll: Long = 0) =
        ChatCalls(remote, { "me" }, backgroundScope, socket, mine, poll)

    @Test
    fun `a call someone else is on shows in its chat from the frame, and goes at call ended`() = runTest {
        val s = store(LiveRemote())
        s.onFrame("call.updated", frame(dto(people = arrayOf(p("amina"), p("juma"), p("me", "DECLINED")))))
        val live = s.calls.value.getValue("g1")
        assertEquals(listOf("amina", "juma"), live.joined.map { it.userId })
        assertFalse(live.meOn)
        assertEquals(kotlin.time.Instant.parse("2026-11-09T10:00:05Z").toEpochMilliseconds(), live.sinceMillis)

        s.onFrame("call.ended", frame(dto(state = "ENDED", people = arrayOf(p("amina", "LEFT"), p("juma", "LEFT")))))
        assertTrue(s.calls.value.isEmpty())
    }

    @Test
    fun `a call nobody is on yet isn't shown, and one person waiting in a group counts from their join`() = runTest {
        val s = store(LiveRemote())
        s.onFrame("call.updated", frame(dto(state = "RINGING", startedAt = null, people = arrayOf(p("amina", "INVITED"), p("me", "INVITED")))))
        assertTrue(s.calls.value.isEmpty())
        s.onFrame("call.updated", frame(dto(state = "RINGING", startedAt = null, people = arrayOf(p("amina", joinedAt = "2026-11-09T10:00:02Z"), p("me", "INVITED")))))
        assertEquals(kotlin.time.Instant.parse("2026-11-09T10:00:02Z").toEpochMilliseconds(), s.calls.value.getValue("g1").sinceMillis)
    }

    @Test
    fun `each socket reconnect reads every live call again, so a lost call ended can't leave a stale bar`() = runTest {
        val remote = LiveRemote()
        val socket = MutableStateFlow(true)
        remote.live = AppResult.Success(listOf(dto(people = arrayOf(p("amina")))))
        val s = store(remote, socket)
        s.start()
        runCurrent()
        assertEquals(setOf("g1"), s.calls.value.keys)

        // the call ends while the socket is down; its call.ended never arrives
        socket.value = false
        runCurrent()
        remote.live = AppResult.Success(emptyList())
        socket.value = true
        runCurrent()
        assertTrue(s.calls.value.isEmpty())
        assertEquals(2, remote.log.count { it == "live" })
    }

    @Test
    fun `a read that started before a frame never undoes it`() = runTest {
        val remote = LiveRemote()
        remote.gate = CompletableDeferred()
        remote.live = AppResult.Success(listOf(dto(people = arrayOf(p("amina")))))
        val s = store(remote)
        val reading = launch { s.refresh() }
        runCurrent()
        s.onFrame("call.ended", frame(dto(state = "ENDED", people = arrayOf(p("amina", "LEFT")))))
        remote.gate!!.complete(Unit)
        reading.join()
        assertTrue(s.calls.value.isEmpty(), "the older read said it was on; the frame said it ended")
    }

    @Test
    fun `opening a chat reads its call, and a 404 drops a stale one`() = runTest {
        val remote = LiveRemote()
        val s = store(remote)
        s.onFrame("call.updated", frame(dto(people = arrayOf(p("amina")))))
        s.refreshChat("g1")
        assertTrue(s.calls.value.isEmpty())

        remote.byChat["g1"] = AppResult.Success(dto(callId = "c2", people = arrayOf(p("juma"))))
        s.refreshChat("g1")
        assertEquals("c2", s.calls.value.getValue("g1").callId)

        // offline: what's shown stays until the server says otherwise
        remote.byChat["g1"] = AppResult.Failure(AppError.Network)
        s.refreshChat("g1")
        assertEquals("c2", s.calls.value.getValue("g1").callId)
    }

    @Test
    fun `a server without the live list still drops what CL2 says is over`() = runTest {
        val remote = LiveRemote()
        remote.live = AppResult.Failure(AppError.Api("route.not_found", 404, null, null, emptyMap()))
        val s = store(remote)
        s.onFrame("call.updated", frame(dto(people = arrayOf(p("amina")))))
        s.onFrame("call.updated", frame(dto(callId = "c9", chatId = "d1", people = arrayOf(p("otieno"), p("me")))))
        remote.byId["c1"] = AppResult.Success(dto(state = "ENDED", people = arrayOf(p("amina", "LEFT"))))
        remote.byId["c9"] = AppResult.Success(dto(callId = "c9", chatId = "d1", people = arrayOf(p("otieno"), p("me"))))
        s.refresh()
        assertEquals(setOf("d1"), s.calls.value.keys)
        assertTrue(s.calls.value.getValue("d1").meOn)
    }

    @Test
    fun `hanging up here takes me off a group call at once and ends a direct one`() = runTest {
        val mine = MutableStateFlow<CallUi?>(null)
        val s = store(LiveRemote(), mine = mine)
        s.start()
        runCurrent()
        s.onFrame("call.updated", frame(dto(people = arrayOf(p("amina"), p("me")))))
        s.onFrame("call.updated", frame(dto(callId = "c9", chatId = "d1", people = arrayOf(p("otieno"), p("me")))))
        mine.value = CallUi("c1", "g1", "Fund", false, group = true, phase = CallPhase.Active)
        runCurrent()
        mine.value = mine.value!!.copy(phase = CallPhase.Ended)
        runCurrent()
        val g = s.calls.value.getValue("g1")
        assertFalse(g.meOn)
        assertEquals(listOf("amina"), g.joined.map { it.userId })

        mine.value = CallUi("c9", "d1", "Otieno", false, group = false, phase = CallPhase.Active)
        runCurrent()
        mine.value = null
        runCurrent()
        assertNull(s.calls.value["d1"])
    }

    @Test
    fun `while a call shows and the socket is up, the list is read again every so often`() = runTest {
        val remote = LiveRemote()
        val socket = MutableStateFlow(true)
        remote.live = AppResult.Success(listOf(dto(people = arrayOf(p("amina")))))
        val s = store(remote, socket, poll = 45_000)
        s.start()
        runCurrent()
        assertEquals(1, remote.log.count { it == "live" })
        advanceTimeBy(45_001)
        assertEquals(2, remote.log.count { it == "live" })
        socket.value = false
        advanceTimeBy(200_000)
        assertEquals(2, remote.log.count { it == "live" }, "no polling without a socket")
    }

    @Test
    fun `the bar joins a call I'm not on, returns to mine when minimised, and only shows one on my other phone`() {
        val face = { id: String, name: String -> CallBarFace(if (id == "amina") "Amina Odhiambo" else name, null) }
        val group = liveCallOf(dto(people = arrayOf(p("amina"), p("juma"), p("faith"), p("me", "LEFT"))), "me")!!
        val join = chatCallBar("g1", group, null, "me", face = face)!!
        assertEquals(CallBarAction.Join, join.action)
        assertEquals("Amina and 2 others", join.who)
        assertEquals(3, join.faces.size)

        val myCall = CallUi("c1", "g1", "Fund", false, true, CallPhase.Active, people = listOf(CallPerson("amina", "Amina", true, true, false, false, null)), activeSince = 5L)
        assertNull(chatCallBar("g1", group, myCall, "me", face = face), "the call screen is showing")
        val back = chatCallBar("g1", group, myCall.copy(minimised = true), "me", face = face)!!
        assertEquals(CallBarAction.Return, back.action)
        assertEquals(5L, back.sinceMillis)
        assertNull(chatCallBar("g1", group, myCall.copy(phase = CallPhase.Incoming, minimised = true), "me", face = face), "H1 is ringing")

        val elsewhere = liveCallOf(dto(chatId = "d1", people = arrayOf(p("otieno"), p("me"))), "me")!!
        val bar = chatCallBar("d1", elsewhere, null, "me", face = face)!!
        assertEquals(CallBarAction.None, bar.action)
        assertEquals("On your other phone", bar.note)
        assertEquals("Otieno", bar.who)

        assertEquals(CallBarAction.None, chatCallBar("g1", group, null, "me", online = false, face = face)!!.action, "offline: no tap that would fail")
        assertEquals("Needs a connection", chatCallBar("g1", group, null, "me", online = false, face = face)!!.note)
        val onAnother = myCall.copy(callId = "c7", chatId = "d9", minimised = true)
        assertEquals(CallBarAction.Switch, chatCallBar("g1", group, onAnother, "me", face = face)!!.action)
        val ringingOut = CallUi(null, "d1", "Otieno", false, false, CallPhase.Ringing, minimised = true)
        val calling = chatCallBar("d1", null, ringingOut, "me", face = face)!!
        assertEquals("Calling…", calling.note)
        assertNull(calling.sinceMillis)

        assertNull(chatCallBar("g1", null, null, "me", face = face))
    }

    @Test
    fun `who is on a call and for how long, in words`() {
        val people = listOf(LiveCallPerson("me", "Me"), LiveCallPerson("a", "Amina Odhiambo"), LiveCallPerson("j", "Juma"))
        assertEquals("Amina and Juma", whoIsOn(people, "me"))
        assertEquals("Amina", whoIsOn(people.take(2), "me"))
        assertEquals("You", whoIsOn(people.take(1), "me"))
        assertEquals("Amina and 2 others", whoIsOn(people + LiveCallPerson("f", "Faith"), "me"))
        assertEquals("00:42", callClock(42_000))
        assertEquals("12:05", callClock(725_000))
        assertEquals("1:02:09", callClock(3_729_000))
    }
}
