package com.circaso.feature.assistant

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.circasoJson
import com.circaso.core.platform.AudioPlayer
import com.circaso.core.platform.AudioRecorder
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.platform.Recording
import com.circaso.core.platform.RecordingStart
import com.circaso.core.sync.ConnectionState
import com.circaso.core.network.NetworkHealth
import com.circaso.feature.assistant.data.remote.UsageResponse
import com.circaso.feature.assistant.data.repository.toDomain
import com.circaso.feature.assistant.domain.AshaConversation
import com.circaso.feature.assistant.domain.AshaEvent
import com.circaso.feature.assistant.domain.AshaMessage
import com.circaso.feature.assistant.domain.AshaRepository
import com.circaso.feature.assistant.domain.AshaUsage
import com.circaso.feature.assistant.domain.AskAshaUseCase
import com.circaso.feature.assistant.domain.AskedFrom
import com.circaso.feature.assistant.domain.DeleteAshaHistoryUseCase
import com.circaso.feature.assistant.domain.GetAshaUsageUseCase
import com.circaso.feature.assistant.domain.NoChatsToolRunner
import com.circaso.feature.assistant.domain.ObserveAshaHistoryUseCase
import com.circaso.feature.assistant.domain.RefreshAshaHistoryUseCase
import com.circaso.feature.assistant.domain.Snippet
import com.circaso.feature.assistant.domain.SpeakUseCase
import com.circaso.feature.assistant.domain.TranscribeVoiceUseCase
import com.circaso.feature.assistant.domain.UsageMeter
import com.circaso.feature.assistant.presentation.AshaSheet
import com.circaso.feature.assistant.presentation.AshaViewModel
import com.circaso.feature.assistant.presentation.UsageUi
import com.circaso.feature.assistant.presentation.VoiceEnding
import com.circaso.feature.assistant.presentation.VoicePhase
import com.circaso.feature.assistant.presentation.VoiceTranscript
import com.circaso.feature.assistant.presentation.VoiceTurn
import com.circaso.feature.assistant.presentation.VoiceUiState
import com.circaso.feature.assistant.presentation.VoiceVisual
import com.circaso.feature.assistant.presentation.messagesLine
import com.circaso.feature.assistant.presentation.planName
import com.circaso.feature.assistant.presentation.relativeTime
import com.circaso.feature.assistant.presentation.resetsLabel
import com.circaso.feature.assistant.presentation.voiceLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MenuAsha : AshaRepository {
    override val history: Flow<List<AshaMessage>> = flowOf(emptyList())
    override suspend fun refreshHistory(): AppResult<Unit> = AppResult.Success(Unit)
    override fun ask(text: String, askedFrom: AskedFrom?, chatReading: Boolean): Flow<AshaEvent> = flowOf()
    override suspend fun submitToolResults(turnId: String, callId: String, snippets: List<Snippet>): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun deleteMessage(messageId: String) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun transcribe(audio: ByteArray, contentType: String, durationSeconds: Int): AppResult<String> = AppResult.Success("")
    override suspend fun speak(text: String, language: String?): AppResult<ByteArray> = AppResult.Success(ByteArray(0))

    var usageAnswer: AppResult<AshaUsage> = AppResult.Success(AshaUsage("free", UsageMeter(3, 50), UsageMeter(120, 600), "2026-12-01T00:00:00Z"))
    var usageCalls = 0
    override suspend fun usage(): AppResult<AshaUsage> { usageCalls++; return usageAnswer }

    override val conversations = MutableStateFlow(listOf(AshaConversation("c1", "Lunch", null), AshaConversation("c2", "Trip", null)))
    override val current = MutableStateFlow<String?>(null)
    val deleted = mutableListOf<String>()
    var created = 0
    override fun open(conversationId: String?) { current.value = conversationId }
    override suspend fun newConversation(): AppResult<Unit> { created++; return AppResult.Success(Unit) }
    override suspend fun deleteConversation(conversationId: String): AppResult<Unit> {
        deleted += conversationId
        conversations.value = conversations.value.filter { it.id != conversationId }
        return AppResult.Success(Unit)
    }
}

private class QuietRecorder : AudioRecorder {
    override suspend fun start() = RecordingStart.Started
    override suspend fun stop(): Recording? = null
    override suspend fun cancel() = Unit
}

private class QuietPlayer : AudioPlayer {
    override suspend fun play(bytes: ByteArray, contentType: String) = Unit
    override fun stop() = Unit
}

private class MenuMonitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

@OptIn(ExperimentalCoroutinesApi::class)
class AshaMenuViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val monitor = MenuMonitor()
    private val asha = MenuAsha()
    private lateinit var connection: ConnectionState

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.keep(flow: StateFlow<*>) { backgroundScope.launch(dispatcher) { flow.collect { } } }

    private fun vm() = AshaViewModel(
        ObserveAshaHistoryUseCase(asha), RefreshAshaHistoryUseCase(asha), AskAshaUseCase(asha, NoChatsToolRunner), DeleteAshaHistoryUseCase(asha), connection,
        QuietRecorder(), QuietPlayer(), TranscribeVoiceUseCase(asha), SpeakUseCase(asha), chats = asha, usage = GetAshaUsageUseCase(asha),
    )

    @Test fun openingTheMenuReadsTheUsageAndClosingItShutsTheSheet() = runTest(dispatcher) {
        val vm = vm(); keep(vm.state)
        assertNull(vm.state.value.sheet)
        vm.onMenu(true)
        assertEquals(AshaSheet.Menu, vm.state.value.sheet)
        assertEquals(1, asha.usageCalls)
        assertEquals(3, (vm.state.value.usage as UsageUi.Loaded).usage.messages.used)
        vm.onMenu(false)
        assertNull(vm.state.value.sheet)
    }

    @Test fun backInsideTheSheetReturnsToTheMenuThenCloses() = runTest(dispatcher) {
        val vm = vm(); keep(vm.state)
        vm.onMenu(true); vm.onSheet(AshaSheet.Recents)
        assertEquals(AshaSheet.Recents, vm.state.value.sheet)
        vm.onSheetBack()
        assertEquals(AshaSheet.Menu, vm.state.value.sheet)
        vm.onSheetBack()
        assertNull(vm.state.value.sheet)
    }

    @Test fun aMissingUsageRouteIsACalmNoteNotAnError() = runTest(dispatcher) {
        asha.usageAnswer = AppResult.Failure(AppError.Api("request.not_found", 404, "r", null, emptyMap()))
        val vm = vm(); keep(vm.state)
        vm.onMenu(true)
        assertEquals(UsageUi.Unavailable, vm.state.value.usage)
        asha.usageAnswer = AppResult.Failure(AppError.Unexpected(404))
        vm.onRetryUsage()
        assertEquals(UsageUi.Unavailable, vm.state.value.usage, "a 404 without an envelope too")
    }

    @Test fun usageFailuresAndOfflineAreToldApart() = runTest(dispatcher) {
        asha.usageAnswer = AppResult.Failure(AppError.Api("service.unavailable", 503, "r", null, emptyMap()))
        val vm = vm(); keep(vm.state)
        vm.onMenu(true)
        assertIs<UsageUi.Failed>(vm.state.value.usage)
        monitor.osStatus.value = ConnectionStatus.Offline
        vm.onMenu(false); vm.onMenu(true)
        assertEquals(UsageUi.Offline, vm.state.value.usage)
        assertEquals(1, asha.usageCalls, "offline, the server is not asked")
    }

    @Test fun aUsageFigureStaysOnScreenWhenTheConnectionGoes() = runTest(dispatcher) {
        val vm = vm(); keep(vm.state)
        vm.onMenu(true)
        monitor.osStatus.value = ConnectionStatus.Offline
        vm.onMenu(false); vm.onMenu(true)
        assertIs<UsageUi.Loaded>(vm.state.value.usage)
    }

    @Test fun newChatStartsAConversationAndClosesTheSheet() = runTest(dispatcher) {
        val vm = vm(); keep(vm.state)
        vm.onMenu(true); vm.onNewChat()
        assertEquals(1, asha.created)
        assertNull(vm.state.value.sheet)
    }

    @Test fun newChatNeedsAConnection() = runTest(dispatcher) {
        monitor.osStatus.value = ConnectionStatus.Offline
        val vm = vm(); keep(vm.state)
        vm.onMenu(true); vm.onNewChat()
        assertEquals(0, asha.created)
        assertEquals(AshaSheet.Menu, vm.state.value.sheet)
    }

    @Test fun aRecentChatOpensAndTheSheetCloses() = runTest(dispatcher) {
        val vm = vm(); keep(vm.state)
        assertEquals("c1", vm.state.value.shownConversation, "the latest shows when none was chosen")
        vm.onMenu(true); vm.onSheet(AshaSheet.Recents); vm.onOpenConversation("c2")
        assertEquals("c2", vm.state.value.shownConversation)
        assertNull(vm.state.value.sheet)
    }

    @Test fun deletingARecentChatAsksFirst() = runTest(dispatcher) {
        val vm = vm(); keep(vm.state)
        vm.onMenu(true); vm.onSheet(AshaSheet.Recents)
        vm.onRequestDelete("c2")
        assertEquals("c2", vm.state.value.confirmDelete)
        assertEquals(emptyList(), asha.deleted)
        vm.onCancelDelete()
        assertNull(vm.state.value.confirmDelete)
        vm.onConfirmDelete()
        assertEquals(emptyList(), asha.deleted, "nothing is deleted without the confirmation")
        vm.onRequestDelete("c2"); vm.onConfirmDelete()
        assertEquals(listOf("c2"), asha.deleted)
        assertEquals(listOf("c1"), vm.state.value.conversations.map { it.id })
        assertEquals(AshaSheet.Recents, vm.state.value.sheet, "the list stays open after a delete")
    }

    @Test fun aTapWhileConfirmingOnlyCancelsAndOfflineNothingCanBeDeleted() = runTest(dispatcher) {
        val vm = vm(); keep(vm.state)
        vm.onMenu(true); vm.onSheet(AshaSheet.Recents)
        vm.onRequestDelete("c2"); vm.onOpenConversation("c1")
        assertNull(vm.state.value.confirmDelete)
        assertEquals(AshaSheet.Recents, vm.state.value.sheet, "the tap cancelled the delete, it didn't open the chat")
        monitor.osStatus.value = ConnectionStatus.Offline
        vm.onRequestDelete("c2")
        assertNull(vm.state.value.confirmDelete)
    }

    @Test fun deleteAllFromTheMenuStillNeedsItsConfirmation() = runTest(dispatcher) {
        val vm = vm(); keep(vm.state)
        vm.onMenu(true); vm.onMenu(false); vm.onClearRequested()
        assertTrue(vm.state.value.confirmClear)
    }
}

class AshaUsageTest {
    @Test fun thePeriodShapeIsReadAsSent() {
        val body = """{"plan":"plus","periodStart":"2026-11-01T00:00:00Z","periodEnd":"2026-12-01T00:00:00Z","resetsAt":"2026-12-01T00:00:00Z","messages":{"used":12,"limit":50},"voiceSeconds":{"used":90,"limit":null}}"""
        val u = circasoJson().decodeFromString(UsageResponse.serializer(), body).toDomain()
        assertEquals("plus", u.plan)
        assertEquals(UsageMeter(12, 50), u.messages)
        assertEquals(38, u.messages.left)
        assertEquals(UsageMeter(90, null), u.voiceSeconds)
        assertNull(u.voiceSeconds.left, "no limit, nothing left to count")
        assertEquals("2026-11-01T00:00:00Z", u.periodStart)
        assertEquals("2026-12-01T00:00:00Z", u.resetsAt)
    }

    @Test fun theDayShapeA7ShippedWithStillReads() {
        val body = """{"plan":"free","textTurnsUsed":3,"textTurnsLeft":7,"voiceSecondsUsed":10,"voiceSecondsLeft":50,"resetsAt":"2026-10-26T00:00:00Z"}"""
        val u = circasoJson().decodeFromString(UsageResponse.serializer(), body).toDomain()
        assertEquals(UsageMeter(3, 10), u.messages)
        assertEquals(UsageMeter(10, 60), u.voiceSeconds)
        assertNull(u.periodStart)
    }

    @Test fun theMetersAreWrittenPlainly() {
        assertEquals("12 of 50 messages", messagesLine(UsageMeter(12, 50)))
        assertEquals("1 message", messagesLine(UsageMeter(1, null)))
        assertEquals("2 of 10 min", voiceLine(UsageMeter(61, 600)), "a started minute counts")
        assertEquals(1f, UsageMeter(80, 50).fraction)
        assertEquals(0, UsageMeter(80, 50).left)
        assertEquals("Free", planName("free"))
        assertEquals("Pay as you go", planName("pay_as_you_go"))
    }

    @Test fun theResetIsSaidInTheLargestUsefulUnit() {
        val now = kotlin.time.Instant.parse("2026-11-05T10:00:00Z").toEpochMilliseconds()
        assertEquals("Resets in 30 min", resetsLabel("2026-11-05T10:30:00Z", now))
        assertEquals("Resets in 6 h", resetsLabel("2026-11-05T15:30:00Z", now))
        assertEquals("Resets in 26 days", resetsLabel("2026-12-01T00:00:00Z", now))
        assertEquals("Resets now", resetsLabel("2026-11-05T09:00:00Z", now))
        assertNull(resetsLabel(null, now))
        assertNull(resetsLabel("not a time", now))
    }

    @Test fun aChatActiveMomentsAgoIsJustNow() {
        val now = kotlin.time.Instant.parse("2026-11-05T10:00:00Z").toEpochMilliseconds()
        assertEquals("Just now", relativeTime(now - 20_000, now))
    }
}

class VoiceTranscriptTest {
    @Test fun aTurnCollectsItsSentencesAndClosesOnReplyDone() {
        var t = VoiceTranscript.question(emptyList(), " What did I spend? ")
        t = VoiceTranscript.sentence(t, "About 500 shillings.")
        t = VoiceTranscript.sentence(t, "Mostly lunch.")
        assertEquals(listOf(VoiceTurn("What did I spend?", listOf("About 500 shillings.", "Mostly lunch."))), t)
        t = VoiceTranscript.done(t, "About 500 shillings. Mostly lunch.", spoken = true)
        assertEquals(VoiceTurn("What did I spend?", listOf("About 500 shillings.", "Mostly lunch."), done = true), t.single())
        t = VoiceTranscript.question(t, "And yesterday?")
        assertEquals(2, t.size)
        assertEquals("And yesterday?", t.last().question)
    }

    @Test fun anAnswerWithNoSentencesTakesTheSavedText() {
        val t = VoiceTranscript.done(VoiceTranscript.question(emptyList(), "Hi"), "Hello!", spoken = true)
        assertEquals(listOf("Hello!"), t.single().reply)
    }

    @Test fun talkingOverHerIsMarked() {
        var t = VoiceTranscript.sentence(VoiceTranscript.question(emptyList(), "Tell me a story"), "Once upon a time")
        t = VoiceTranscript.interrupted(t)
        t = VoiceTranscript.done(t, "Once upon a time there was a chama.", spoken = false)
        assertTrue(t.single().interrupted)
        assertEquals(listOf("Once upon a time"), t.single().reply, "what she actually said, not the whole saved answer")
    }

    @Test fun interruptingBeforeSheSpokeIsNotMarked() {
        val t = VoiceTranscript.interrupted(VoiceTranscript.question(emptyList(), "Hi"))
        assertEquals(false, t.single().interrupted)
    }

    @Test fun aSentenceWithoutATurnStillShows() {
        val t = VoiceTranscript.sentence(emptyList(), "Welcome back.")
        assertEquals(VoiceTurn("", listOf("Welcome back.")), t.single())
    }

    @Test fun aFailedAnswerClosesItsTurn() {
        val t = VoiceTranscript.failed(VoiceTranscript.question(emptyList(), "Hi"))
        assertTrue(t.single().done)
        assertEquals(VoiceTurn("Again", listOf("Sure.")), VoiceTranscript.sentence(VoiceTranscript.question(t, "Again"), "Sure.").last())
    }

    @Test fun theTranscriptKeepsTheLatestTurns() {
        var t = emptyList<VoiceTurn>()
        repeat(VoiceTranscript.MaxTurns + 5) { t = VoiceTranscript.done(VoiceTranscript.question(t, "q$it"), "a$it", true) }
        assertEquals(VoiceTranscript.MaxTurns, t.size)
        assertEquals("q${VoiceTranscript.MaxTurns + 4}", t.last().question)
    }

    @Test fun theLogoStateFollowsThePhase() {
        assertEquals(VoiceVisual.Connecting, VoiceUiState().visual)
        assertEquals(VoiceVisual.Listening, VoiceUiState(phase = VoicePhase.Listening).visual)
        assertEquals(VoiceVisual.Muted, VoiceUiState(phase = VoicePhase.Listening, muted = true).visual)
        assertEquals(VoiceVisual.Speaking, VoiceUiState(phase = VoicePhase.Speaking).visual)
        assertEquals(VoiceVisual.Error, VoiceUiState(phase = VoicePhase.Ended, ending = VoiceEnding.Offline).visual)
        assertEquals(VoiceVisual.Ended, VoiceUiState(phase = VoicePhase.Ended, ending = VoiceEnding.Quiet).visual)
        assertEquals(0.6f, VoiceUiState(phase = VoicePhase.Listening, micLevel = 0.6f, voiceLevel = 0.9f).waveLevel, "listening follows the microphone")
        assertEquals(0.9f, VoiceUiState(phase = VoicePhase.Speaking, micLevel = 0.6f, voiceLevel = 0.9f).waveLevel, "speaking follows her voice")
        assertEquals(0f, VoiceUiState(phase = VoicePhase.Listening, muted = true, micLevel = 0.6f).waveLevel)
    }
}
