package com.circaso.feature.assistant

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.AudioPlayer
import com.circaso.core.platform.AudioRecorder
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.platform.LiveMicStart
import com.circaso.core.platform.LiveMicrophone
import com.circaso.core.platform.LiveSpeaker
import com.circaso.core.platform.Recording
import com.circaso.core.platform.RecordingStart
import com.circaso.core.realtime.RealtimeDispatcher
import com.circaso.core.realtime.RealtimeFrame
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.assistant.data.remote.AssistantStatusResponse
import com.circaso.feature.assistant.data.repository.AshaAvailabilityImpl
import com.circaso.feature.assistant.domain.AshaAvailability
import com.circaso.feature.assistant.domain.AshaEvent
import com.circaso.feature.assistant.domain.AshaMessage
import com.circaso.feature.assistant.domain.AshaRepository
import com.circaso.feature.assistant.domain.AshaUsage
import com.circaso.feature.assistant.domain.AskAshaUseCase
import com.circaso.feature.assistant.domain.AskedFrom
import com.circaso.feature.assistant.domain.DeleteAshaHistoryUseCase
import com.circaso.feature.assistant.domain.DeviceToolRunner
import com.circaso.feature.assistant.domain.LiveVoiceEvent
import com.circaso.feature.assistant.domain.LiveVoiceRepository
import com.circaso.feature.assistant.domain.LiveVoiceSession
import com.circaso.feature.assistant.domain.LiveVoiceStart
import com.circaso.feature.assistant.domain.NoChatsToolRunner
import com.circaso.feature.assistant.domain.ObserveAshaHistoryUseCase
import com.circaso.feature.assistant.domain.RefreshAshaHistoryUseCase
import com.circaso.feature.assistant.domain.Snippet
import com.circaso.feature.assistant.domain.SpeakUseCase
import com.circaso.feature.assistant.domain.TalkToAshaUseCase
import com.circaso.feature.assistant.domain.TranscribeVoiceUseCase
import com.circaso.feature.assistant.domain.UsageMeter
import com.circaso.feature.assistant.presentation.AshaViewModel
import com.circaso.feature.assistant.presentation.VoiceEnding
import com.circaso.feature.assistant.presentation.VoiceViewModel
import com.circaso.feature.assistant.presentation.VoiceVisual
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val unavailable = AppError.Api(AshaAvailability.UnavailableCode, 502, "req-1", null, emptyMap())

/** Asha with a scripted answer per ask. */
private class MaintenanceAsha : AshaRepository {
    val scripts = ArrayDeque<List<AshaEvent>>()
    val asked = mutableListOf<String>()
    override val history: Flow<List<AshaMessage>> = MutableStateFlow(emptyList())
    override suspend fun refreshHistory(): AppResult<Unit> = AppResult.Success(Unit)
    override fun ask(text: String, askedFrom: AskedFrom?, chatReading: Boolean): Flow<AshaEvent> = flow {
        asked += text
        (scripts.removeFirstOrNull() ?: emptyList()).forEach { emit(it) }
    }
    override suspend fun submitToolResults(turnId: String, callId: String, snippets: List<Snippet>): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun deleteMessage(messageId: String) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun transcribe(audio: ByteArray, contentType: String, durationSeconds: Int): AppResult<String> = AppResult.Success("")
    override suspend fun speak(text: String, language: String?): AppResult<ByteArray> = AppResult.Success(ByteArray(0))
    override suspend fun usage(): AppResult<AshaUsage> = AppResult.Success(AshaUsage("free", UsageMeter(0, 50), UsageMeter(0, 600), null))
}

/** A14 as the test sets it, counting reads. */
private class StatusRemote : com.circaso.feature.assistant.data.remote.AshaRemote by NoRemote {
    var available = true
    var reads = 0
    override suspend fun status(): AppResult<AssistantStatusResponse> { reads++; return AppResult.Success(AssistantStatusResponse(available, null)) }
}

private val NoRemote: com.circaso.feature.assistant.data.remote.AshaRemote = object : com.circaso.feature.assistant.data.remote.AshaRemote {
    override suspend fun history(cursor: String?, limit: Int) = error("not used")
    override suspend fun deleteAll(idempotencyKey: String?) = error("not used")
    override suspend fun deleteMessage(messageId: String, idempotencyKey: String?) = error("not used")
    override suspend fun toolResults(turnId: String, request: com.circaso.feature.assistant.data.remote.ToolResultsRequest) = error("not used")
    override suspend fun transcribe(audio: ByteArray, contentType: String, seconds: Int) = error("not used")
    override suspend fun speech(request: com.circaso.feature.assistant.data.remote.SpeechRequest) = error("not used")
    override suspend fun voices() = error("not used")
    override suspend fun voiceSample(voiceId: String) = error("not used")
    override suspend fun usage() = error("not used")
    override fun stream(request: com.circaso.feature.assistant.data.remote.SendMessageRequest) = error("not used")
}

private class OsMonitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

private class SilentRecorder : AudioRecorder {
    override suspend fun start() = RecordingStart.Failed
    override suspend fun stop(): Recording? = null
    override suspend fun cancel() = Unit
}

private class SilentPlayer : AudioPlayer {
    override suspend fun play(bytes: ByteArray, contentType: String) = Unit
    override fun stop() = Unit
}

/** Asha down for maintenance (backend 2026-11-07): the calm state, the question kept, and the state clearing by itself. */
@OptIn(ExperimentalCoroutinesApi::class)
class AshaMaintenanceTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val monitor = OsMonitor()
    private val asha = MaintenanceAsha()
    private val remote = StatusRemote()
    private val availability = AshaAvailabilityImpl(remote, CoroutineScope(dispatcher))
    private lateinit var connection: ConnectionState

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.keep(flow: StateFlow<*>) { backgroundScope.launch(dispatcher) { flow.collect { } } }

    private fun vm() = AshaViewModel(
        ObserveAshaHistoryUseCase(asha), RefreshAshaHistoryUseCase(asha), AskAshaUseCase(asha, NoChatsToolRunner), DeleteAshaHistoryUseCase(asha), connection,
        SilentRecorder(), SilentPlayer(), TranscribeVoiceUseCase(asha), SpeakUseCase(asha), availability = availability,
    )

    @Test fun anUnavailableAnswerIsTheMaintenanceStateNotAnErrorAndTheQuestionStays() = runTest(dispatcher) {
        asha.scripts += listOf(AshaEvent.Failed(unavailable))
        val vm = vm(); keep(vm.state)
        vm.onDraft("What's my balance?"); vm.onSend()

        val s = vm.state.value
        assertTrue(s.maintenance)
        assertNull(s.error, "no error line, no code")
        assertEquals("What's my balance?", s.draft, "the question is kept in the composer")
        assertNull(s.pendingQuestion)
        assertFalse(s.canSend, "the composer waits")
        assertFalse(s.canUseVoice, "and so does voice")
        assertFalse(s.canAsk)
        assertTrue(availability.isDown.value, "the whole app knows")

        vm.onSuggestion("What can you help me with?")
        assertEquals(1, asha.asked.size, "a suggestion chip doesn't ask either")
    }

    @Test fun anotherFailureIsStillAnErrorAsBefore() = runTest(dispatcher) {
        asha.scripts += listOf(AshaEvent.Failed(AppError.Api("assistant.model_unavailable", 502, null, null, emptyMap())))
        val vm = vm(); keep(vm.state)
        vm.onDraft("Hi"); vm.onSend()
        assertFalse(vm.state.value.maintenance)
        assertTrue(vm.state.value.error != null)
        assertFalse(availability.isDown.value)
    }

    @Test fun itClearsByItselfWhenA14SaysSheIsBackWithABriefAshaIsBack() = runTest(dispatcher) {
        asha.scripts += listOf(AshaEvent.Failed(unavailable))
        val vm = vm(); keep(vm.state)
        vm.onResume() // the screen is visible
        vm.onDraft("Hi"); vm.onSend()
        assertTrue(vm.state.value.toldWhenBack, "their turn was recorded: the promise may be made")
        val readsAtDown = remote.reads
        remote.available = false

        advanceTimeBy(AshaAvailability.PollMillis + 1)
        assertEquals(readsAtDown + 1, remote.reads, "A14 is read every 30 s while she is down")
        assertTrue(vm.state.value.maintenance)

        remote.available = true
        advanceTimeBy(AshaAvailability.PollMillis)
        assertFalse(vm.state.value.maintenance, "back by itself")
        assertTrue(vm.state.value.ashaBack, "with a short \"Asha is back\"")
        assertTrue(vm.state.value.canSend, "the kept question can go now")

        advanceTimeBy(AshaViewModel.AshaBackMillis + 1)
        assertFalse(vm.state.value.ashaBack)
        val reads = remote.reads
        advanceTimeBy(5 * AshaAvailability.PollMillis)
        assertEquals(reads, remote.reads, "no polling while she is up")
    }

    @Test fun noPollingWhileTheScreenIsNotVisibleAndOnlyOneLoopForTwoScreens() = runTest(dispatcher) {
        remote.available = false
        val vm = vm(); keep(vm.state)
        val reads = remote.reads
        advanceTimeBy(3 * AshaAvailability.PollMillis)
        assertEquals(reads, remote.reads, "not visible: no polling")

        vm.onResume()
        availability.watch() // the voice screen over it
        val visible = remote.reads
        advanceTimeBy(AshaAvailability.PollMillis + 1)
        assertEquals(visible + 1, remote.reads, "one read per 30 s, not one per screen")

        availability.unwatch()
        vm.onPause()
        advanceTimeBy(3 * AshaAvailability.PollMillis)
        assertEquals(visible + 1, remote.reads, "backgrounded: no polling")
    }

    @Test fun openingWhileDownPromisesNothingTheServerWontDo() = runTest(dispatcher) {
        remote.available = false
        val vm = vm(); keep(vm.state)
        assertTrue(vm.state.value.maintenance)
        assertFalse(vm.state.value.toldWhenBack, "they never asked, so the server won't push them")
    }

    @Test fun openingAshaWhileSheIsDownShowsItBeforeAnyoneTypes() = runTest(dispatcher) {
        remote.available = false
        val vm = vm(); keep(vm.state)
        assertTrue(vm.state.value.maintenance)

        availability.reportBack() // the socket's assistant/status frame
        assertFalse(vm.state.value.maintenance)
        assertTrue(vm.state.value.ashaBack)
    }

    @Test fun resumingReadsA14Again() = runTest(dispatcher) {
        val vm = vm(); keep(vm.state)
        val reads = remote.reads
        remote.available = false
        vm.onResume()
        assertEquals(reads + 1, remote.reads)
        assertTrue(vm.state.value.maintenance)
    }

    @Test fun offlineNeverReadsA14() = runTest(dispatcher) {
        monitor.osStatus.value = ConnectionStatus.Offline
        val vm = vm(); keep(vm.state)
        vm.onResume()
        assertEquals(0, remote.reads)
    }

    @Test fun theSocketsStatusFrameSaysSheIsBack() = runTest(dispatcher) {
        var back = 0
        val d = RealtimeDispatcher(
            frames = MutableSharedFlow(), scope = CoroutineScope(dispatcher),
            onSessionRevoked = {}, refreshContacts = {}, refreshBlocks = {}, refreshOwnProfile = {}, isOwnUser = { false },
            onAshaBack = { back++ },
        )
        fun f(data: String) = RealtimeFrame("assistant", "status", seq = 0, data = Json.parseToJsonElement(data).jsonObject)
        d.handle(f("""{"available":true,"since":"2026-11-07T12:00:00Z"}"""))
        d.handle(f("""{"available":false,"since":null}"""))
        assertEquals(1, back)
    }
}

// ---- voice ---------------------------------------------------------------------------------------------------

private class MaintenanceSession : LiveVoiceSession {
    val fromServer = MutableSharedFlow<LiveVoiceEvent>(extraBufferCapacity = 64)
    override val events: Flow<LiveVoiceEvent> = fromServer
    override suspend fun sendAudio(packet: ByteArray) = Unit
    override suspend fun sendToolResults(turnId: String, callId: String, snippets: List<Snippet>) = Unit
    override suspend fun sendAuth(accessToken: String, agentToken: String?) = Unit
    override suspend fun end() = Unit
    override suspend fun close() = Unit
}

private class MaintenanceVoiceRepo(val session: MaintenanceSession = MaintenanceSession()) : LiveVoiceRepository {
    var opens = 0
    override suspend fun open(language: String, chatReading: Boolean, conversationId: String?): LiveVoiceStart { opens++; return LiveVoiceStart.Opened(session) }
    override suspend fun currentTokens() = "app" to "agent"
}

private class QuietMic : LiveMicrophone {
    override val supported = true
    override suspend fun start() = LiveMicStart.Started
    override val packets: Flow<ByteArray> = MutableSharedFlow()
    override val level = MutableStateFlow(0f)
    override var muted = false
    override fun stop() = Unit
}

private class DrainingSpeaker : LiveSpeaker {
    override suspend fun start(sampleRate: Int) = Unit
    override fun play(pcm: ShortArray) = Unit
    override fun flush() = Unit
    override fun setVolume(volume: Float) = Unit
    override val playing = MutableStateFlow(false)
    override val level = MutableStateFlow(0f)
    var stops = 0
    override fun stop() { stops++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceMaintenanceTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = MaintenanceVoiceRepo()
    private val speaker = DrainingSpeaker()
    private val remote = StatusRemote()
    private val availability = AshaAvailabilityImpl(remote, CoroutineScope(dispatcher))

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = VoiceViewModel(
        TalkToAshaUseCase(repo, DeviceToolRunner { _, _ -> emptyList() }, 60_000),
        RefreshAshaHistoryUseCase(MaintenanceAsha()), QuietMic(), speaker,
        connection = MutableStateFlow(ConnectionStatus.Online), availability = availability,
    )

    @Test fun herMaintenanceLinePlaysOutThenTheCallEndsCalmlyAndClearsWhenSheIsBack() = runTest(dispatcher, timeout = 15.seconds) {
        val vm = viewModel()
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        vm.onVisible(true)
        repo.session.fromServer.emit(LiveVoiceEvent.Ready(118, "mulaw", 8000))
        repo.session.fromServer.emit(LiveVoiceEvent.Turn("What's my balance?"))
        repo.session.fromServer.emit(LiveVoiceEvent.Reply("I'm down for maintenance at the moment. I'll let you know as soon as I'm back."))
        speaker.playing.value = true // her line is still playing when the closing arrives
        val stopsBefore = speaker.stops
        repo.session.fromServer.emit(LiveVoiceEvent.Closing(AshaAvailability.UnavailableCode, "Asha is down for maintenance.", null))
        advanceTimeBy(1_000)
        assertEquals(stopsBefore, speaker.stops, "her line isn't cut off")
        assertNull(vm.state.value.ending)

        speaker.playing.value = false
        advanceTimeBy(1)
        assertEquals(VoiceEnding.Maintenance(toldWhenBack = true), vm.state.value.ending)
        assertEquals(VoiceVisual.Ended, vm.state.value.visual, "calm, not the error look")
        assertTrue(availability.isDown.value, "the chat shows it too")
        advanceTimeBy(10_000)
        assertEquals(1, repo.opens, "a maintenance ending never reconnects")

        remote.available = true
        advanceTimeBy(AshaAvailability.PollMillis + 1)
        assertEquals(VoiceEnding.AshaBack, vm.state.value.ending, "she is back: one tap talks again")
    }

    @Test fun aLineThatNeverFinishesStillEndsTheCall() = runTest(dispatcher, timeout = 15.seconds) {
        val vm = viewModel()
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        repo.session.fromServer.emit(LiveVoiceEvent.Ready(118, "mulaw", 8000))
        speaker.playing.value = true
        repo.session.fromServer.emit(LiveVoiceEvent.Closing(AshaAvailability.UnavailableCode, null, null))
        advanceTimeBy(6_001)
        assertEquals(VoiceEnding.Maintenance(), vm.state.value.ending)
    }

    @Test fun askingForACallWhileSheIsKnownDownOpensNothing() = runTest(dispatcher, timeout = 15.seconds) {
        remote.available = false
        availability.refresh()
        val vm = viewModel()
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        assertEquals(0, repo.opens, "no socket, no microphone")
        assertEquals(VoiceEnding.Maintenance(toldWhenBack = false), vm.state.value.ending)

        availability.reportBack()
        assertEquals(VoiceEnding.AshaBack, vm.state.value.ending)
        vm.start() // Talk Again
        assertEquals(1, repo.opens)
    }
}
