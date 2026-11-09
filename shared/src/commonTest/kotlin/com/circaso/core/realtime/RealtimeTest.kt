package com.circaso.core.realtime

import com.circaso.core.network.AccessTokenProvider
import com.circaso.core.network.circasoJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class MemoryCursors : RealtimeCursors {
    val seqs = mutableMapOf<String, Long>()
    override suspend fun lastSeq(module: String) = seqs[module] ?: 0L
    override suspend fun setLastSeq(module: String, seq: Long) { seqs[module] = seq }
    override suspend fun clear() = seqs.clear()
}

private class ScriptedSocket(private val lines: List<String>, private val failAfter: Boolean = false) : Socket {
    val sent = mutableListOf<String>()
    var closed = false
    override suspend fun send(text: String) { sent += text }
    override val incoming: Flow<String> = flow { lines.forEach { emit(it) }; if (failAfter) error("connection reset") }
    override suspend fun close() { closed = true }
}

private fun frame(module: String, type: String, seq: Long, data: String = "{}") =
    """{"module":"$module","type":"$type","id":"i$seq","seq":$seq,"ts":"2026-10-25T10:00:00Z","data":$data}"""

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeTest {
    private val json: Json = circasoJson()
    private val cursors = MemoryCursors()
    private var token: String? = "app-token"
    private val tokens = AccessTokenProvider { token }

    private fun client(socket: ScriptedSocket) = RealtimeClient({ socket }, "wss://x/v1/ws", tokens, cursors, json)

    private suspend fun collectFrames(client: RealtimeClient, block: suspend () -> Unit): List<RealtimeFrame> {
        val seen = mutableListOf<RealtimeFrame>()
        kotlinx.coroutines.coroutineScope {
            val job = launch(UnconfinedTestDispatcher()) { client.frames.collect { seen += it } }
            block()
            job.cancel()
        }
        return seen
    }

    @Test fun theFirstFrameAuthenticatesWithTheTokenAndTheLastSeenNumbers() = runTest {
        cursors.seqs["users"] = 1042
        val socket = ScriptedSocket(emptyList())
        client(socket).run()
        val auth = json.parseToJsonElement(socket.sent.first()).jsonObject
        assertEquals("auth", auth["type"]!!.jsonPrimitive.content)
        assertEquals("app-token", auth["accessToken"]!!.jsonPrimitive.content)
        val last = auth["last"]!!.jsonArray.single().jsonObject
        assertEquals("users", last["module"]!!.jsonPrimitive.content)
        assertEquals(1042, last["seq"]!!.jsonPrimitive.int)
    }

    @Test fun firstConnectionSendsAnEmptyLastList() = runTest {
        val socket = ScriptedSocket(emptyList())
        client(socket).run()
        assertEquals(JsonArray(emptyList()), json.parseToJsonElement(socket.sent.first()).jsonObject["last"])
    }

    @Test fun noTokenMeansNoConnection() = runTest {
        token = null
        val socket = ScriptedSocket(emptyList())
        assertEquals(ConnectionEnd.Unauthorized, client(socket).run())
        assertTrue(socket.sent.isEmpty())
    }

    @Test fun framesArePublishedAndTheirNumbersSavedAndDuplicatesFromReplayAreDropped() = runTest {
        val socket = ScriptedSocket(listOf(frame("users", "contact.joined", 1043), frame("users", "contact.joined", 1043), frame("users", "connection.added", 1044)))
        val c = client(socket)
        val seen = collectFrames(c) { c.run() }
        assertEquals(listOf("contact.joined", "connection.added"), seen.map { it.type })
        assertEquals(1044, cursors.seqs["users"])
    }

    @Test fun anOldNumberNeverMovesTheSavedOneBack() = runTest {
        cursors.seqs["users"] = 2000
        val socket = ScriptedSocket(listOf(frame("users", "contact.joined", 1500)))
        val c = client(socket)
        val seen = collectFrames(c) { c.run() }
        assertTrue(seen.isEmpty())
        assertEquals(2000, cursors.seqs["users"])
    }

    @Test fun unsequencedFramesPassThroughAndNeverMoveTheSavedNumber() = runTest {
        cursors.seqs["messaging"] = 17
        val socket = ScriptedSocket(listOf(frame("messaging", "message.new", 0), frame("messaging", "typing", 0)))
        val c = client(socket)
        val seen = collectFrames(c) { c.run() }
        assertEquals(listOf("message.new", "typing"), seen.map { it.type })
        assertEquals(17, cursors.seqs["messaging"])
    }

    @Test fun resyncResetsTheModuleAndIsPublishedSoItReloads() = runTest {
        cursors.seqs["users"] = 99
        val socket = ScriptedSocket(listOf("""{"module":"users","type":"resync"}"""))
        val c = client(socket)
        val seen = collectFrames(c) { c.run() }
        assertEquals(listOf("resync"), seen.map { it.type })
        assertEquals(0, cursors.seqs["users"])
    }

    @Test fun garbageFramesAreIgnoredNotFatal() = runTest {
        val socket = ScriptedSocket(listOf("not json", """{"nothing":true}""", frame("users", "contact.joined", 5)))
        val c = client(socket)
        val seen = collectFrames(c) { c.run() }
        assertEquals(1, seen.size)
    }

    @Test fun aDroppedConnectionIsReportedAndTheSocketIsClosed() = runTest {
        val socket = ScriptedSocket(emptyList(), failAfter = true)
        assertEquals(ConnectionEnd.Failed, client(socket).run())
        assertTrue(socket.closed)
    }

    @Test fun aCleanCloseIsReported() = runTest {
        assertEquals(ConnectionEnd.Closed, client(ScriptedSocket(emptyList())).run())
    }

    // ---- backoff

    @Test fun backoffDoublesFromOneSecondAndStopsAtAMinute() {
        val noJitter = object : Random() { override fun nextBits(bitCount: Int) = 0 }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (0..7).map { backoffMillis(it, noJitter) })
    }

    @Test fun backoffJitterStaysWithinAQuarter() {
        repeat(200) { attempt ->
            val base = (1_000L shl attempt.coerceIn(0, 6)).coerceAtMost(60_000L)
            val v = backoffMillis(attempt)
            assertTrue(v >= base && v <= base + base / 4, "attempt $attempt gave $v")
        }
    }

    // ---- what frames do

    private class Calls {
        val log = mutableListOf<String>()
    }

    private fun dispatcher(calls: Calls, own: String = "me") = RealtimeDispatcher(
        frames = MutableSharedFlow(),
        scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
        onSessionRevoked = { calls.log += "signout" },
        refreshContacts = { calls.log += "contacts" },
        refreshBlocks = { calls.log += "blocks" },
        refreshOwnProfile = { calls.log += "profile" },
        isOwnUser = { it == own },
    )

    private fun f(module: String?, type: String, data: String = "{}") =
        RealtimeFrame(module, type, data = json.parseToJsonElement(data).jsonObject)

    @Test fun aRevokedSessionSignsOut() = runTest {
        val calls = Calls(); dispatcher(calls).handle(f("system", "session.revoked"))
        assertEquals(listOf("signout"), calls.log)
    }

    @Test fun aNewContactOrConnectionRefreshesContacts() = runTest {
        val calls = Calls(); val d = dispatcher(calls)
        d.handle(f("users", "contact.joined")); d.handle(f("users", "connection.added"))
        assertEquals(listOf("contacts", "contacts"), calls.log)
    }

    @Test fun aProfileChangeRefreshesTheRightThing() = runTest {
        val calls = Calls(); val d = dispatcher(calls)
        d.handle(f("users", "profile.updated", """{"userId":"me","version":3}"""))
        d.handle(f("users", "profile.updated", """{"userId":"someone","version":9}"""))
        assertEquals(listOf("profile", "contacts"), calls.log)
    }

    @Test fun someoneElsesProfileChangeAlsoRefreshesTheirChats() = runTest {
        val calls = Calls()
        val d = RealtimeDispatcher(
            frames = MutableSharedFlow(), scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            onSessionRevoked = {}, refreshContacts = { calls.log += "contacts" }, refreshBlocks = {}, refreshOwnProfile = { calls.log += "profile" },
            isOwnUser = { it == "me" }, onPeerProfile = { calls.log += "chats:$it" },
        )
        d.handle(f("users", "profile.updated", """{"userId":"amina","version":4}"""))
        d.handle(f("users", "profile.updated", """{"userId":"me","version":5}"""))
        assertEquals(listOf("contacts", "chats:amina", "profile"), calls.log)
    }

    @Test fun resyncReloadsEverythingOverHttp() = runTest {
        val calls = Calls(); dispatcher(calls).handle(f("users", "resync"))
        assertEquals(listOf("contacts", "blocks", "profile"), calls.log)
    }

    @Test fun aChamaResyncGoesToChamaNotTheContacts() = runTest {
        val seen = mutableListOf<String>()
        val calls = Calls()
        RealtimeDispatcher(
            frames = MutableSharedFlow(), scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            onSessionRevoked = {}, refreshContacts = { calls.log += "contacts" }, refreshBlocks = {}, refreshOwnProfile = {},
            isOwnUser = { false }, onChamaFrame = { type, _ -> seen += type },
        ).handle(f("chama", "resync"))
        assertEquals(listOf("resync"), seen)
        assertTrue(calls.log.isEmpty())
    }

    @Test fun unknownFramesDoNothing() = runTest {
        val calls = Calls(); dispatcher(calls).handle(f("chats", "message.created"))
        assertTrue(calls.log.isEmpty())
    }
}
