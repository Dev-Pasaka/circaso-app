package com.circaso.feature.assistant

import com.circaso.core.common.AppResult
import com.circaso.core.platform.AudioLevel
import com.circaso.core.platform.LiveMicStart
import com.circaso.core.platform.LiveMicrophone
import com.circaso.core.platform.LiveSpeaker
import com.circaso.core.platform.Mulaw
import com.circaso.feature.assistant.data.remote.LiveFrame
import com.circaso.feature.assistant.data.remote.LiveSocket
import com.circaso.feature.assistant.data.remote.LiveVoiceSocketSession
import com.circaso.feature.assistant.data.remote.authFrame
import com.circaso.feature.assistant.data.remote.liveVoiceUrl
import com.circaso.feature.assistant.domain.AshaRepository
import com.circaso.feature.assistant.domain.DeviceToolRunner
import com.circaso.feature.assistant.domain.LiveVoiceEvent
import com.circaso.feature.assistant.domain.LiveVoiceRepository
import com.circaso.feature.assistant.domain.LiveVoiceSession
import com.circaso.feature.assistant.domain.LiveVoiceStart
import com.circaso.feature.assistant.domain.RefreshAshaHistoryUseCase
import com.circaso.feature.assistant.domain.Snippet
import com.circaso.feature.assistant.domain.TalkToAshaUseCase
import com.circaso.feature.assistant.presentation.VoiceEnding
import com.circaso.feature.assistant.presentation.VoicePhase
import com.circaso.feature.assistant.presentation.VoiceViewModel
import com.circaso.feature.assistant.presentation.VoiceVisual
import com.circaso.feature.assistant.presentation.VoiceActivity
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

/** A socket the test feeds; everything the session sends is kept. */
private class FakeLiveSocket : LiveSocket {
    val fromServer = Channel<LiveFrame>(Channel.UNLIMITED)
    val sentText = mutableListOf<String>()
    val sentBinary = mutableListOf<ByteArray>()
    var closed = false
    override val incoming: Flow<LiveFrame> = fromServer.consumeAsFlow()
    override suspend fun sendText(text: String) { sentText += text }
    override suspend fun sendBinary(bytes: ByteArray) { sentBinary += bytes }
    override suspend fun close() { closed = true; fromServer.close() }
    fun server(text: String) { fromServer.trySend(LiveFrame.Text(text)) }
    fun serverAudio(bytes: ByteArray) { fromServer.trySend(LiveFrame.Binary(bytes)) }
    fun serverClose(code: Int = 1000, reason: String? = null) { fromServer.trySend(LiveFrame.Closed(code, reason)); fromServer.close() }
}

private fun typesOf(texts: List<String>) = texts.map { json.parseToJsonElement(it).jsonObject["type"]!!.jsonPrimitive.content }

class LiveVoiceSessionTest {
    private val socket = FakeLiveSocket()
    private val session = LiveVoiceSocketSession(socket, json, authFrame("app-token", "agent-token", "en", true))

    @Test fun urlIsTheLiveVoiceSocket() {
        assertEquals("wss://sandbox.circaso.com/v1/assistant/voice/live", liveVoiceUrl("https://sandbox.circaso.com/"))
    }

    @Test fun authGoesFirstThenFramesBecomeEvents() = runTest {
        socket.server("""{"type":"ready","voiceSecondsLeft":118,"audio":{"encoding":"mulaw","sampleRate":8000}}""")
        socket.server("""{"type":"transcript","text":"what did","final":false}""")
        socket.server("""{"type":"turn","text":"What did I spend?"}""")
        socket.server("""{"type":"reply","text":"About 500 shillings."}""")
        socket.serverAudio(byteArrayOf(1, 2, 3))
        socket.server("""{"type":"reply_done","message":{"messageId":"m2","role":"ASHA","text":"About 500 shillings.","replyTo":"m1","at":"2026-10-29T10:00:00Z"},"spoken":true}""")
        socket.server("""{"type":"interrupted"}""")
        socket.server("""{"type":"error","code":"assistant.model_unavailable","message":"busy"}""")
        socket.server("""{"type":"something_new","x":1}""")
        socket.server("not json")
        socket.server("""{"type":"closing","reason":"silence"}""")
        socket.serverClose(1000, "silence")

        val events = session.events.toList()

        val first = json.parseToJsonElement(socket.sentText.first()).jsonObject
        assertEquals("auth", first["type"]!!.jsonPrimitive.content)
        assertEquals("app-token", first["accessToken"]!!.jsonPrimitive.content)
        assertEquals("agent-token", first["agentToken"]!!.jsonPrimitive.content)
        assertEquals("en", first["language"]!!.jsonPrimitive.content)
        assertEquals("true", first["chatReading"]!!.jsonPrimitive.content)

        assertEquals(LiveVoiceEvent.Ready(118, "mulaw", 8000), events[0])
        assertEquals(LiveVoiceEvent.Transcript("what did", false), events[1])
        assertEquals(LiveVoiceEvent.Turn("What did I spend?"), events[2])
        assertEquals(LiveVoiceEvent.Reply("About 500 shillings."), events[3])
        assertIs<LiveVoiceEvent.Audio>(events[4]).also { assertEquals(listOf<Byte>(1, 2, 3), it.bytes.toList()) }
        assertIs<LiveVoiceEvent.ReplyDone>(events[5]).also { assertEquals("m2", it.message.messageId); assertTrue(it.spoken) }
        assertEquals(LiveVoiceEvent.Interrupted, events[6])
        assertEquals(LiveVoiceEvent.Error("assistant.model_unavailable", "busy"), events[7])
        // the unknown frame and the non-JSON one are skipped
        assertEquals(LiveVoiceEvent.Closing("silence", null, null), events[8])
        assertEquals(LiveVoiceEvent.Closed(1000, "silence"), events[9])
        assertEquals(10, events.size)
    }

    @Test fun audioToolResultsAuthAndEndAreSentAsTheGuideSays() = runTest {
        session.sendAudio(byteArrayOf(9, 9))
        session.sendToolResults("t1", "c1", listOf(Snippet("chat1", "Mum", "Mum", "2026-10-29T09:00:00Z", "lunch?")))
        session.sendAuth("new-app", "new-agent")
        session.end()
        assertEquals(listOf<Byte>(9, 9), socket.sentBinary.single().toList())
        assertEquals(listOf("tool_results", "auth", "end"), typesOf(socket.sentText))
        val results = json.parseToJsonElement(socket.sentText[0]).jsonObject
        assertEquals("t1", results["turnId"]!!.jsonPrimitive.content)
        assertEquals("c1", results["callId"]!!.jsonPrimitive.content)
        assertEquals("lunch?", results["snippets"]!!.jsonObjectList().single()["text"]!!.jsonPrimitive.content)
        val auth = json.parseToJsonElement(socket.sentText[1]).jsonObject
        assertEquals("new-app", auth["accessToken"]!!.jsonPrimitive.content)
        assertTrue(auth["language"] == null, "a refresh carries tokens only")
    }

    @Test fun nothingIsSentOnceClosed() = runTest {
        socket.serverClose(1008, "unauthorized")
        assertEquals(LiveVoiceEvent.Closed(1008, "unauthorized"), session.events.toList().single())
        session.sendAudio(byteArrayOf(1))
        session.end()
        assertTrue(socket.sentBinary.isEmpty())
        assertEquals(listOf("auth"), typesOf(socket.sentText)) // only the opening frame
    }

    @Test fun toolRequestArgumentsComeAsTheJsonString() = runTest {
        socket.server("""{"type":"tool_request","turnId":"t1","callId":"c1","name":"search_chats","arguments":"{\"query\":\"lunch\"}"}""")
        socket.serverClose()
        val request = session.events.toList().first()
        assertEquals(LiveVoiceEvent.ToolRequest("t1", "c1", "search_chats", """{"query":"lunch"}"""), request)
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectList() = (this as kotlinx.serialization.json.JsonArray).map { it.jsonObject }

/** A session the test drives directly, standing in for the socket one. */
private class FakeSession : LiveVoiceSession {
    val fromServer = MutableSharedFlow<LiveVoiceEvent>(extraBufferCapacity = 64)
    val audio = mutableListOf<ByteArray>()
    val toolResults = mutableListOf<Triple<String, String, List<Snippet>>>()
    val auths = mutableListOf<Pair<String, String?>>()
    var ended = false
    var closed = false
    override val events: Flow<LiveVoiceEvent> = fromServer
    override suspend fun sendAudio(packet: ByteArray) { audio += packet }
    override suspend fun sendToolResults(turnId: String, callId: String, snippets: List<Snippet>) { toolResults += Triple(turnId, callId, snippets) }
    override suspend fun sendAuth(accessToken: String, agentToken: String?) { auths += accessToken to agentToken }
    override suspend fun end() { ended = true }
    override suspend fun close() { closed = true }
}

private class FakeLiveVoiceRepository(val session: FakeSession = FakeSession()) : LiveVoiceRepository {
    var start: LiveVoiceStart = LiveVoiceStart.Opened(session)
    var tokens: Pair<String, String?>? = "app-1" to "agent-1"
    /** The conversation each open asked for, in order. */
    val opened = mutableListOf<String?>()
    override suspend fun open(language: String, chatReading: Boolean, conversationId: String?): LiveVoiceStart { opened += conversationId; return start }
    override suspend fun currentTokens() = tokens
}

@OptIn(ExperimentalCoroutinesApi::class)
class TalkToAshaUseCaseTest {
    private val repo = FakeLiveVoiceRepository()
    private val tools = DeviceToolRunner { name, args -> listOf(Snippet("c", "Mum", "Mum", "2026-10-29T09:00:00Z", "$name:$args")) }
    private val talk = TalkToAshaUseCase(repo, tools, tokenCheckMillis = 1_000)

    @Test fun toolRequestsAreAnsweredOnTheSocketAndShownAsWork() = runTest {
        val session = (talk() as LiveVoiceStart.Opened).session
        val seen = mutableListOf<LiveVoiceEvent>()
        val job = session.events.onEach { seen += it }.launchIn(backgroundScope)
        testScheduler.runCurrent()
        repo.session.fromServer.emit(LiveVoiceEvent.ToolRequest("t1", "c1", "search_chats", """{"q":"lunch"}"""))
        advanceTimeBy(1)
        assertEquals(listOf<LiveVoiceEvent>(LiveVoiceEvent.UsingTool("search_chats")), seen)
        val (turn, call, snippets) = repo.session.toolResults.single()
        assertEquals("t1" to "c1", turn to call)
        assertEquals("""search_chats:{"q":"lunch"}""", snippets.single().text)
        job.cancel()
    }

    @Test fun freshTokensGoOutWhenTheSessionsTokenChanges() = runTest {
        val session = (talk() as LiveVoiceStart.Opened).session
        val job = session.events.launchIn(backgroundScope)
        advanceTimeBy(2_500)
        assertTrue(repo.session.auths.isEmpty(), "same token: no auth frame")
        repo.tokens = "app-2" to "agent-2"
        advanceTimeBy(1_000)
        assertEquals(listOf<Pair<String, String?>>("app-2" to "agent-2"), repo.session.auths)
        advanceTimeBy(3_000)
        assertEquals(1, repo.session.auths.size, "sent once per change")
        repo.session.fromServer.emit(LiveVoiceEvent.Closed(1000, "ended"))
        advanceTimeBy(1)
        assertTrue(job.isCompleted || !job.isActive)
    }

    @Test fun offlineIsPassedThrough() = runTest {
        repo.start = LiveVoiceStart.Offline
        assertEquals(LiveVoiceStart.Offline, talk())
    }
}

class AudioCodingTest {
    @Test fun mulawExpandsLikeG711() {
        assertEquals(0, Mulaw.decodeSample(0xFF).toInt())
        assertEquals(0, Mulaw.decodeSample(0x7F).toInt())
        assertEquals(-32_124, Mulaw.decodeSample(0x00).toInt())
        assertEquals(32_124, Mulaw.decodeSample(0x80).toInt())
        assertEquals(-372, Mulaw.decodeSample(0x60).toInt())
        assertEquals(listOf(0, 32_124), Mulaw.decode(byteArrayOf(0xFF.toByte(), 0x80.toByte())).map { it.toInt() })
    }

    @Test fun levelIsZeroForSilenceAndFullForALoudVoice() {
        assertEquals(0f, AudioLevel.of(ShortArray(320)))
        assertEquals(1f, AudioLevel.of(ShortArray(320) { if (it % 2 == 0) 20_000 else -20_000 }))
        val quiet = AudioLevel.of(ShortArray(320) { if (it % 2 == 0) 300 else -300 })
        assertTrue(quiet > 0f && quiet < 0.5f, "a quiet voice sits low: $quiet")
    }
}

private class FakeMicrophone : LiveMicrophone {
    override var supported = true
    val out = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    override val packets: Flow<ByteArray> = out
    override val level = MutableStateFlow(0f)
    override var muted = false
    var startResult = LiveMicStart.Started
    var started = 0
    var stopped = 0
    override suspend fun start(): LiveMicStart { started++; return startResult }
    override fun stop() { stopped++ }
}

private class FakeSpeaker : LiveSpeaker {
    val played = mutableListOf<ShortArray>()
    var flushed = 0
    var startedAt: Int? = null
    var stopped = 0
    override suspend fun start(sampleRate: Int) { startedAt = sampleRate }
    override fun play(pcm: ShortArray) { played += pcm }
    override fun flush() { flushed++ }
    /** Every volume set, in order; [volume] is the latest. */
    val volumes = mutableListOf<Float>()
    val volume get() = volumes.lastOrNull() ?: 1f
    override fun setVolume(volume: Float) { volumes += volume }
    override val playing = MutableStateFlow(false)
    override val level = MutableStateFlow(0f)
    override fun stop() { stopped++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeLiveVoiceRepository()
    private val mic = FakeMicrophone()
    private val speaker = FakeSpeaker()
    private var refreshes = 0
    private val history = RefreshAshaHistoryUseCase(object : AshaRepository by NoAsha {
        override suspend fun refreshHistory(): AppResult<Unit> { refreshes++; return AppResult.Success(Unit) }
    })

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val online = MutableStateFlow(com.circaso.core.platform.ConnectionStatus.Online)

    private fun viewModel(repository: FakeLiveVoiceRepository = repo, microphone: FakeMicrophone = mic, spk: FakeSpeaker = speaker) =
        VoiceViewModel(TalkToAshaUseCase(repository, DeviceToolRunner { _, _ -> emptyList() }, 60_000), history, microphone, spk, connection = online, clock = { now })

    /** The microphone gate's clock, moved by hand. */
    private var now = 1_000_000L

    /** A live call with one answered turn saved in conversation `c9`, then the line drops while offline. */
    private suspend fun TestScopeLike.liveCallThenDrop(vm: VoiceViewModel) {
        repo.session.fromServer.emit(LiveVoiceEvent.Ready(118, "mulaw", 8000))
        repo.session.fromServer.emit(LiveVoiceEvent.Turn("Who did I chat with?"))
        repo.session.fromServer.emit(LiveVoiceEvent.ReplyDone(msg("m2", "ASHA", "Mostly Amina.", conversation = "c9"), spoken = true))
        repo.session.fromServer.emit(LiveVoiceEvent.Turn("And Mum?"))
        repo.session.fromServer.emit(LiveVoiceEvent.Reply("You and Mum talked on"))
        online.value = com.circaso.core.platform.ConnectionStatus.Offline
        val micStops = mic.stopped
        repo.session.fromServer.emit(LiveVoiceEvent.Closed(1006, null))
        testScheduler.runCurrent()
        assertEquals(VoicePhase.Reconnecting, vm.state.value.phase, "a dropped call waits to reconnect instead of ending")
        assertTrue(mic.stopped > micStops, "the microphone stops while the line is down")
        assertTrue(speaker.stopped >= 1, "her voice stops too")
    }

    @Test fun aDroppedCallReconnectsInTheSameConversationWhenTheConnectionIsBack() = runTest(timeout = 15.seconds) {
        val vm = viewModel()
        with(vm) {
            liveCallThenDrop(vm)
            assertEquals(2, vm.state.value.turns.size, "the transcript stays")
            assertTrue(vm.state.value.turns.last().done, "the cut-off answer is closed as it stood")
            assertTrue(vm.state.value.turns.last().cutOff, "and marked cut off, never shown as complete")
            assertFalse(vm.state.value.turns.first().cutOff)
            val second = FakeSession()
            repo.start = LiveVoiceStart.Opened(second)
            advanceTimeBy(10_000)
            assertEquals(1, repo.opened.size, "nothing is tried while offline")
            online.value = com.circaso.core.platform.ConnectionStatus.Online
            advanceTimeBy(600)
            assertEquals(listOf(null, "c9"), repo.opened, "reopened once, in the same conversation")
            second.fromServer.emit(LiveVoiceEvent.Ready(100, "mulaw", 8000))
            assertEquals(VoicePhase.Listening, vm.state.value.phase)
            assertEquals(2, mic.started, "the microphone opens again")
            advanceTimeBy(40_000)
            assertEquals(VoicePhase.Listening, vm.state.value.phase, "a reopened call is not ended by the reconnect timer")
        }
    }

    @Test fun aCallStillOfflineAfterThirtySecondsEnds() = runTest(timeout = 15.seconds) {
        val vm = viewModel()
        with(vm) {
            liveCallThenDrop(vm)
            advanceTimeBy(29_000)
            assertEquals(VoicePhase.Reconnecting, vm.state.value.phase)
            advanceTimeBy(1_500)
            assertEquals(VoiceEnding.Offline, vm.state.value.ending, "ends as before, with Talk Again")
            assertEquals(1, repo.opened.size)
        }
    }

    @Test fun threeFailedReopensEndTheCall() = runTest(timeout = 15.seconds) {
        val vm = viewModel()
        with(vm) {
            liveCallThenDrop(vm)
            repo.start = LiveVoiceStart.Offline
            online.value = com.circaso.core.platform.ConnectionStatus.Online
            advanceTimeBy(600)
            assertEquals(2, repo.opened.size)
            assertEquals(VoicePhase.Reconnecting, vm.state.value.phase)
            advanceTimeBy(1_600)
            assertEquals(3, repo.opened.size)
            advanceTimeBy(3_100)
            assertEquals(4, repo.opened.size)
            assertEquals(VoiceEnding.Offline, vm.state.value.ending)
            advanceTimeBy(30_000)
            assertEquals(4, repo.opened.size, "no more tries")
        }
    }

    @Test fun hangingUpNeverReconnects() = runTest(timeout = 15.seconds) {
        val vm = viewModel()
        with(vm) {
            repo.session.fromServer.emit(LiveVoiceEvent.Ready(118, "mulaw", 8000))
            vm.onEnd()
            repo.session.fromServer.emit(LiveVoiceEvent.Closed(1000, "ended"))
            advanceTimeBy(10_000)
            assertEquals(VoiceEnding.HungUp, vm.state.value.ending)
            assertEquals(1, repo.opened.size)
        }
    }

    @Test fun quotaAndAuthEndingsNeverReconnect() = runTest(timeout = 15.seconds) {
        val vm = viewModel()
        with(vm) {
            repo.session.fromServer.emit(LiveVoiceEvent.Ready(118, "mulaw", 8000))
            repo.session.fromServer.emit(LiveVoiceEvent.Closing("assistant.quota_exceeded", null, 600))
            repo.session.fromServer.emit(LiveVoiceEvent.Closed(1000, null))
            advanceTimeBy(10_000)
            assertEquals(VoiceEnding.OutOfVoice(600), vm.state.value.ending)
            assertEquals(1, repo.opened.size)
        }
        val other = FakeLiveVoiceRepository()
        val vm2 = viewModel(repository = other)
        with(vm2) {
            other.session.fromServer.emit(LiveVoiceEvent.Ready(118, "mulaw", 8000))
            other.session.fromServer.emit(LiveVoiceEvent.Closed(1008, "unauthorized"))
            advanceTimeBy(10_000)
            assertIs<VoiceEnding.Failed>(vm2.state.value.ending)
            assertEquals(1, other.opened.size)
        }
    }

    /** Keeps the state collected while [block] runs, then clears the ViewModel as the navigation would, whatever happened. */
    private suspend fun TestScopeLike.with(vm: VoiceViewModel, block: suspend () -> Unit) {
        val collecting = vm.state.launchIn(backgroundScope)
        testScheduler.runCurrent() // the background scope dispatches lazily; subscribe before the first frame
        try { block() } finally { collecting.cancel(); vm.clear() }
    }

    @Test fun aConversationFromReadyToQuietHangUp() = runTest(timeout = 15.seconds) {
        val vm = viewModel()
        with(vm) {
            assertEquals(VoicePhase.Connecting, vm.state.value.phase)

            repo.session.fromServer.emit(LiveVoiceEvent.Ready(118, "mulaw", 8000))
            assertEquals(VoicePhase.Listening, vm.state.value.phase)
            assertEquals(8000, speaker.startedAt)
            assertEquals(1, mic.started)
            assertEquals(118L, vm.state.value.voiceSecondsLeft)

            mic.out.emit(byteArrayOf(1))
            assertEquals(1, repo.session.audio.size, "the microphone streams to the socket")

            repo.session.fromServer.emit(LiveVoiceEvent.Transcript("what did I", false))
            assertEquals("what did I", vm.state.value.heard)
            repo.session.fromServer.emit(LiveVoiceEvent.Turn("What did I spend?"))
            assertEquals(VoicePhase.Thinking, vm.state.value.phase)
            assertEquals("", vm.state.value.heard)

            repo.session.fromServer.emit(LiveVoiceEvent.Audio(byteArrayOf(0xFF.toByte(), 0x80.toByte())))
            assertEquals(VoicePhase.Speaking, vm.state.value.phase)
            assertEquals(listOf(0, 32_124), speaker.played.single().map { it.toInt() }, "mulaw is expanded before playing")
            repo.session.fromServer.emit(LiveVoiceEvent.Reply("About 500 shillings."))
            assertEquals(listOf("About 500 shillings."), vm.state.value.reply)

            repo.session.fromServer.emit(LiveVoiceEvent.Interrupted)
            assertEquals(1, speaker.flushed)
            assertEquals(VoicePhase.Listening, vm.state.value.phase)

            repo.session.fromServer.emit(LiveVoiceEvent.ReplyDone(msg("m2", "ASHA", "About 500 shillings."), spoken = false))
            assertEquals(1, refreshes, "the saved answer reaches the chat through the history")
            val turn = vm.state.value.turns.single()
            assertEquals("What did I spend?", turn.question, "the spoken question is the person's bubble")
            assertEquals(listOf("About 500 shillings."), turn.reply, "her answer is hers")
            assertTrue(turn.done); assertTrue(turn.interrupted)

            repo.session.fromServer.emit(LiveVoiceEvent.Transcript("and yester", false))
            assertEquals("and yester", vm.state.value.heard, "the next question shows live under the transcript")
            repo.session.fromServer.emit(LiveVoiceEvent.Turn("And yesterday?"))
            repo.session.fromServer.emit(LiveVoiceEvent.Reply("Nothing yesterday."))
            assertEquals(listOf("What did I spend?", "And yesterday?"), vm.state.value.turns.map { it.question })
            assertEquals(listOf("Nothing yesterday."), vm.state.value.reply)

            repo.session.fromServer.emit(LiveVoiceEvent.Closing("silence", null, null))
            assertEquals(VoicePhase.Ended, vm.state.value.phase)
            assertEquals(VoiceEnding.Quiet, vm.state.value.ending)
            assertTrue(mic.stopped >= 1); assertTrue(speaker.stopped >= 1); assertTrue(repo.session.closed)

            mic.out.emit(byteArrayOf(2))
            assertEquals(1, repo.session.audio.size, "nothing streams after the end")
        }
    }

    /** Microphone packets every 20 ms for [millis] at [level], on the test clock. */
    private suspend fun talk(level: Float, millis: Long) {
        mic.level.value = level
        repeat((millis / 20).toInt()) { now += 20; mic.out.emit(byteArrayOf(level.times(100).toInt().toByte())) }
    }

    /** Ready, a question, and her voice playing. */
    private suspend fun ashaSpeaking() {
        repo.session.fromServer.emit(LiveVoiceEvent.Ready(118, "mulaw", 8000))
        repo.session.fromServer.emit(LiveVoiceEvent.Turn("Tell me a story"))
        repo.session.fromServer.emit(LiveVoiceEvent.Audio(byteArrayOf(0xFF.toByte())))
        repo.session.fromServer.emit(LiveVoiceEvent.Reply("Once upon a time"))
        speaker.playing.value = true
    }

    @Test fun theMicrophoneStreamsThroughHerWholeTurn() = runTest(timeout = 15.seconds) {
        val vm = viewModel()
        with(vm) {
            repo.session.fromServer.emit(LiveVoiceEvent.Ready(118, "mulaw", 8000))
            talk(0.05f, 40)
            repo.session.fromServer.emit(LiveVoiceEvent.Turn("What did I spend?"))
            talk(0.05f, 40) // while she thinks
            repo.session.fromServer.emit(LiveVoiceEvent.Audio(byteArrayOf(0xFF.toByte())))
            speaker.playing.value = true
            talk(0.2f, 40) // a quiet voice under her, after the echo canceller
            speaker.playing.value = false
            repo.session.fromServer.emit(LiveVoiceEvent.ReplyDone(msg("m2", "ASHA", "About 500."), spoken = true))
            talk(0.05f, 40) // the echo tail
            assertEquals(listOf<Byte>(5, 5, 5, 5, 20, 20, 5, 5), repo.session.audio.map { it.single() }, "no loudness gate: the server decides what is the person")
        }
    }

    @Test fun theirVoiceOverHersDucksHerAndShowsListening() = runTest(timeout = 15.seconds) {
        val vm = viewModel()
        with(vm) {
            ashaSpeaking()
            talk(0.05f, 200) // a quiet room under her voice
            talk(0.6f, 100) // a click: too short
            assertFalse(vm.state.value.ducked)
            talk(0.05f, 100)
            assertEquals(1f, speaker.volume)
            assertEquals(VoiceVisual.Speaking, vm.state.value.visual)

            talk(0.6f, 180) // a voice, held past the attack
            assertTrue(vm.state.value.ducked, "ducked as soon as the person is heard")
            assertEquals(VoiceVisual.Listening, vm.state.value.visual, "the screen listens at once")
            assertEquals(VoicePhase.Speaking, vm.state.value.phase, "she has not been told to stop yet")
            advanceTimeBy(100)
            assertEquals(0.25f, speaker.volume, 0.001f, "her voice dips to a quarter in about 80 ms")
            assertTrue(speaker.volumes.size > 2, "as a ramp, not a jump")
            assertEquals(0, speaker.flushed, "ducked, not stopped")

            repo.session.fromServer.emit(LiveVoiceEvent.Interrupted)
            assertEquals(1, speaker.flushed, "the server's interrupted flushes her voice")
            assertEquals(1f, speaker.volume, "and the next reply plays at full volume")
            assertFalse(vm.state.value.ducked)
            assertEquals(VoicePhase.Listening, vm.state.value.phase)
            assertTrue(vm.state.value.turns.last().interrupted, "the cut-off reply is marked talked over")
        }
    }

    @Test fun withoutConfirmationSheComesBackUp() = runTest(timeout = 15.seconds) {
        val vm = viewModel()
        with(vm) {
            ashaSpeaking()
            talk(0.05f, 200)
            talk(0.6f, 300) // a cough, or a word to someone in the room
            assertTrue(vm.state.value.ducked)
            advanceTimeBy(100)
            talk(0.05f, 1_000) // quiet again: the voice ends after its release, then the wait for `interrupted`
            assertTrue(vm.state.value.ducked, "still waiting on the server")
            assertEquals(0.25f, speaker.volume, 0.001f)
            talk(0.05f, 200)
            assertFalse(vm.state.value.ducked, "no interrupted: she carries on")
            assertEquals(VoiceVisual.Speaking, vm.state.value.visual)
            advanceTimeBy(200)
            assertEquals(1f, speaker.volume, 0.001f, "back to full volume in about 150 ms")
            assertEquals(0, speaker.flushed)
        }
    }

    @Test fun herAnswerEndingWhileDuckedFinishesItsTailDucked() = runTest(timeout = 15.seconds) {
        val vm = viewModel()
        with(vm) {
            ashaSpeaking()
            talk(0.05f, 200)
            talk(0.6f, 200)
            advanceTimeBy(100)
            assertTrue(vm.state.value.ducked)
            assertEquals(VoiceVisual.Listening, vm.state.value.visual)
            assertEquals(VoiceVisual.Speaking, vm.state.value.announcedVisual, "a screen reader isn't told until the server confirms")
            repo.session.fromServer.emit(LiveVoiceEvent.ReplyDone(msg("m2", "ASHA", "Once upon a time."), spoken = true))
            assertFalse(vm.state.value.ducked)
            assertEquals(0.25f, speaker.volume, 0.001f, "what is still buffered plays out ducked, never jumping up over the person")
            speaker.playing.value = false
            assertEquals(1f, speaker.volume, "full volume for her next answer")
        }
    }

    @Test fun duckingIsOnlyForHerVoice() = runTest(timeout = 15.seconds) {
        val vm = viewModel()
        with(vm) {
            repo.session.fromServer.emit(LiveVoiceEvent.Ready(118, "mulaw", 8000))
            talk(0.6f, 400)
            assertFalse(vm.state.value.ducked, "nothing to duck while she is quiet")
            assertTrue(speaker.volumes.isEmpty())
        }
    }

    @Test fun muteStopsEverything() = runTest(timeout = 15.seconds) {
        val vm = viewModel()
        with(vm) {
            ashaSpeaking()
            talk(0.05f, 200)
            talk(0.6f, 200)
            assertTrue(vm.state.value.ducked)
            val sent = repo.session.audio.size
            vm.onMute()
            assertFalse(vm.state.value.ducked, "muting undoes the duck")
            advanceTimeBy(200)
            assertEquals(1f, speaker.volume, 0.001f, "she comes back up")
            assertEquals(VoiceVisual.Speaking, vm.state.value.visual)
            talk(0.6f, 400)
            assertEquals(sent, repo.session.audio.size, "muted, nothing is sent")
            assertFalse(vm.state.value.ducked, "and nothing ducks her")
            vm.onMute()
            talk(0.05f, 40)
            assertEquals(sent + 2, repo.session.audio.size, "unmuted, it streams again")
        }
    }

    @Test fun hangingUpSendsEnd() = runTest(timeout = 15.seconds) {
        val vm = viewModel()
        with(vm) {
            repo.session.fromServer.emit(LiveVoiceEvent.Ready(118, "mulaw", 8000))
            vm.onMute()
            assertTrue(mic.muted)
            vm.onEnd()
            assertEquals(VoiceEnding.HungUp, vm.state.value.ending)
            assertTrue(repo.session.ended)
        }
    }

    @Test fun quotaShowsTheOffer() = runTest(timeout = 15.seconds) {
        val vm = viewModel()
        with(vm) {
            repo.session.fromServer.emit(LiveVoiceEvent.Closing("assistant.quota_exceeded", "Voice time is used up", 3_600))
            assertEquals(VoiceEnding.OutOfVoice(3_600), vm.state.value.ending)
        }
    }

    @Test fun noMicrophonePermissionEndsPolitely() = runTest(timeout = 15.seconds) {
        mic.startResult = LiveMicStart.PermissionDenied
        val vm = viewModel()
        with(vm) {
            repo.session.fromServer.emit(LiveVoiceEvent.Ready(118, "mulaw", 8000))
            assertEquals(VoiceEnding.NoMicrophone, vm.state.value.ending)
            assertTrue(repo.session.ended)
        }
    }

    @Test fun offlineEndsBeforeOpening() = runTest(timeout = 15.seconds) {
        repo.start = LiveVoiceStart.Offline
        val vm = viewModel()
        with(vm) { assertEquals(VoiceEnding.Offline, vm.state.value.ending) }
    }

    @Test fun aPhoneWithoutOpusIsSentToClips() = runTest(timeout = 15.seconds) {
        mic.supported = false
        val vm = viewModel()
        with(vm) {
            assertEquals(VoiceEnding.UseClips, vm.state.value.ending)
            assertFalse(repo.session.closed, "never opened, nothing to close")
        }
    }

    private fun msg(id: String, role: String, text: String, conversation: String? = null) = com.circaso.feature.assistant.domain.AshaMessage(id, role, text, null, null, null, "2026-10-29T10:00:00Z", conversation)
}

/** Mirrors VoiceViewModel's echo tail (private there). */

private typealias TestScopeLike = kotlinx.coroutines.test.TestScope

/** What the navigation does when the screen goes: clears the ViewModel, which cancels everything it runs. */
private fun VoiceViewModel.clear() = ViewModelStore().apply { put("voice", this@clear) }.clear()

/** An Asha repository with nothing in it, for tests that only need one method. */
private object NoAsha : AshaRepository {
    override val history = kotlinx.coroutines.flow.flowOf(emptyList<com.circaso.feature.assistant.domain.AshaMessage>())
    override suspend fun refreshHistory(): AppResult<Unit> = AppResult.Success(Unit)
    override fun ask(text: String, askedFrom: com.circaso.feature.assistant.domain.AskedFrom?, chatReading: Boolean) = kotlinx.coroutines.flow.flowOf<com.circaso.feature.assistant.domain.AshaEvent>()
    override suspend fun submitToolResults(turnId: String, callId: String, snippets: List<Snippet>): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun deleteMessage(messageId: String) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun transcribe(audio: ByteArray, contentType: String, durationSeconds: Int): AppResult<String> = AppResult.Success("")
    override suspend fun speak(text: String, language: String?): AppResult<ByteArray> = AppResult.Success(ByteArray(0))
    override suspend fun usage(): AppResult<com.circaso.feature.assistant.domain.AshaUsage> = AppResult.Success(com.circaso.feature.assistant.domain.AshaUsage("free", com.circaso.feature.assistant.domain.UsageMeter(0, 0), com.circaso.feature.assistant.domain.UsageMeter(0, 0), null))
}

class VoiceActivityTest {
    @Test fun aSustainedVoiceIsActiveAfterTheAttackAndEndsAfterTheRelease() {
        val v = VoiceActivity()
        var t = 0L
        fun feed(level: Float, millis: Long): Boolean { var a = false; repeat((millis / 20).toInt()) { t += 20; a = v.update(level, t) }; return a }
        assertFalse(feed(0.05f, 500), "a quiet room")
        assertFalse(feed(0.6f, 100), "a click is too short")
        assertFalse(feed(0.05f, 100))
        assertTrue(feed(0.6f, 180), "a voice past ~150 ms")
        assertTrue(feed(0.05f, 200), "the gap between two words doesn't end it")
        assertFalse(feed(0.05f, 100), "it ends after the release")
    }

    @Test fun aSteadySoundRaisesTheFloorInsteadOfStayingAVoice() {
        val v = VoiceActivity()
        var t = 0L
        fun feed(level: Float, millis: Long): Boolean { var a = false; repeat((millis / 20).toInt()) { t += 20; a = v.update(level, t) }; return a }
        assertFalse(feed(0.25f, 200), "under the minimum level: never a voice")
        assertTrue(feed(0.38f, 400), "a steady hum at first reads as a voice")
        assertFalse(feed(0.38f, 10_000), "the floor learns it within seconds")
        assertTrue(v.floor <= VoiceActivity.MaxFloor)
        assertTrue(feed(0.7f, 200), "a real voice over it still counts")
    }
}
