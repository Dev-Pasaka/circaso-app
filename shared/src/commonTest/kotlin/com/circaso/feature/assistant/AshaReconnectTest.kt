package com.circaso.feature.assistant

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.AudioPlayer
import com.circaso.core.platform.AudioRecorder
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.platform.Recording
import com.circaso.core.platform.RecordingStart
import com.circaso.core.sync.ConnectionState
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
import com.circaso.feature.assistant.presentation.AshaViewModel
import com.circaso.feature.assistant.presentation.FollowRule
import com.circaso.feature.assistant.presentation.UsageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Asha with a scripted answer per ask, and a history the test can change (as the server would). */
private class ScriptedAsha : AshaRepository {
    val scripts = ArrayDeque<List<AshaEvent>>()
    val asked = mutableListOf<String>()
    val rows = MutableStateFlow<List<AshaMessage>>(emptyList())
    var refreshes = 0
    /** What the server holds, copied into [rows] on the next refresh. */
    var server: List<AshaMessage>? = null
    var usageAnswer: AppResult<AshaUsage> = AppResult.Success(AshaUsage("free", UsageMeter(3, 50), UsageMeter(120, 600), null))
    var usageCalls = 0

    override val history: Flow<List<AshaMessage>> = rows
    override suspend fun refreshHistory(): AppResult<Unit> { refreshes++; server?.let { rows.value = it }; return AppResult.Success(Unit) }
    override fun ask(text: String, askedFrom: AskedFrom?, chatReading: Boolean): Flow<AshaEvent> = flow {
        asked += text
        (scripts.removeFirstOrNull() ?: emptyList()).forEach { emit(it) }
    }
    override suspend fun submitToolResults(turnId: String, callId: String, snippets: List<Snippet>): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun deleteMessage(messageId: String) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun transcribe(audio: ByteArray, contentType: String, durationSeconds: Int): AppResult<String> = AppResult.Success("")
    override suspend fun speak(text: String, language: String?): AppResult<ByteArray> = AppResult.Success(ByteArray(0))
    override suspend fun usage(): AppResult<AshaUsage> { usageCalls++; return usageAnswer }
}

private class Monitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

private class NoRecorder : AudioRecorder {
    override suspend fun start() = RecordingStart.Failed
    override suspend fun stop(): Recording? = null
    override suspend fun cancel() = Unit
}

private class NoPlayer : AudioPlayer {
    override suspend fun play(bytes: ByteArray, contentType: String) = Unit
    override fun stop() = Unit
}

private fun message(id: String, role: String, text: String) = AshaMessage(id, role, text, null, null, null, "2026-11-07T10:00:00Z")

@OptIn(ExperimentalCoroutinesApi::class)
class AshaReconnectTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val monitor = Monitor()
    private val asha = ScriptedAsha()
    private lateinit var connection: ConnectionState

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.keep(flow: StateFlow<*>) { backgroundScope.launch(dispatcher) { flow.collect { } } }

    private fun vm() = AshaViewModel(
        ObserveAshaHistoryUseCase(asha), RefreshAshaHistoryUseCase(asha), AskAshaUseCase(asha, NoChatsToolRunner), DeleteAshaHistoryUseCase(asha), connection,
        NoRecorder(), NoPlayer(), TranscribeVoiceUseCase(asha), SpeakUseCase(asha), usage = GetAshaUsageUseCase(asha),
    )

    private fun offline() { monitor.osStatus.value = ConnectionStatus.Offline }
    private fun online() { monitor.osStatus.value = ConnectionStatus.Online }
    private val done = AshaEvent.Done(message("a1", "ASHA", "About KES 900."))

    @Test fun aQuestionCutOffByTheConnectionWaitsAndIsAskedOnceWhenItIsBack() = runTest(dispatcher) {
        asha.scripts += listOf(AshaEvent.Delta("Let me "), AshaEvent.Failed(AppError.Network))
        asha.scripts += listOf(AshaEvent.Delta("About KES 900."), done)
        val vm = vm(); keep(vm.state)
        vm.onDraft("And transport?"); vm.onSend()
        offline()

        val waiting = assertNotNull(vm.state.value.waiting, "the question waits in its bubble")
        assertEquals("And transport?", waiting.question)
        assertEquals("Let me ", waiting.partial, "what had arrived stays")
        assertEquals("", vm.state.value.draft, "not handed back to the composer")
        assertNull(vm.state.value.error, "no error: it is waiting, not failed")
        assertNull(vm.state.value.pendingQuestion, "one bubble for the question, not two")

        advanceTimeBy(60_000)
        assertEquals(1, asha.asked.size, "nothing is asked while offline")

        online()
        advanceTimeBy(1)
        assertEquals(listOf("And transport?", "And transport?"), asha.asked, "asked again exactly once")
        assertNull(vm.state.value.waiting)
        assertNull(vm.state.value.pendingQuestion)
        advanceTimeBy(60_000)
        assertEquals(2, asha.asked.size, "and never again")
    }

    @Test fun whileAskingAgainTheCutOffAnswerStaysUntilTheNewOneArrives() = runTest(dispatcher) {
        asha.scripts += listOf(AshaEvent.Delta("Let me "), AshaEvent.Failed(AppError.Network))
        val vm = vm(); keep(vm.state)
        vm.onDraft("And transport?"); vm.onSend()
        offline()
        // The second ask gets stuck before any word arrives: a never-ending script.
        asha.scripts += listOf(AshaEvent.Status("Checking your transactions…"))
        online(); advanceTimeBy(3_001)
        assertEquals("Let me ", vm.state.value.waiting?.partial ?: vm.state.value.carried, "the partial answer is kept on screen while asking again")
    }

    @Test fun aStreamThatStopsWithoutAnAnswerCountsAsADroppedLine() = runTest(dispatcher) {
        asha.scripts += listOf(AshaEvent.Delta("Half an ans"))
        val vm = vm(); keep(vm.state)
        vm.onDraft("hi"); vm.onSend()
        assertEquals("hi", vm.state.value.waiting?.question)
    }

    @Test fun quotaAndAuthFailuresAreNotAskedAgain() = runTest(dispatcher) {
        asha.scripts += listOf(AshaEvent.Failed(AppError.Api("assistant.quota_exceeded", 429, "r", 3600, emptyMap())))
        asha.scripts += listOf(AshaEvent.Failed(AppError.Api("identity.unauthorized", 401, "r", null, emptyMap())))
        val vm = vm(); keep(vm.state)
        vm.onDraft("one"); vm.onSend()
        assertNull(vm.state.value.waiting)
        assertEquals("one", vm.state.value.draft, "handed back as before")
        assertNotNull(vm.state.value.error)
        vm.onDraft("two"); vm.onSend()
        assertNull(vm.state.value.waiting)
        offline(); online(); advanceTimeBy(10_000)
        assertEquals(listOf("one", "two"), asha.asked, "neither is asked again")
    }

    @Test fun aQuestionTheServerAnsweredBeforeTheLineDroppedIsNotAskedTwice() = runTest(dispatcher) {
        asha.scripts += listOf(AshaEvent.Failed(AppError.Network))
        val vm = vm(); keep(vm.state)
        vm.onDraft("And transport?"); vm.onSend()
        offline()
        asha.server = listOf(message("q1", "USER", "And transport?"), message("a1", "ASHA", "About KES 900."))
        online(); advanceTimeBy(3_001)
        assertEquals(1, asha.asked.size)
        assertNull(vm.state.value.waiting)
        assertEquals(listOf("q1", "a1"), vm.state.value.messages.map { it.messageId }, "the saved answer shows instead")
    }

    @Test fun aLineThatKeepsDroppingGivesTheQuestionBackAfterThreeAsks() = runTest(dispatcher) {
        repeat(3) { asha.scripts += listOf(AshaEvent.Failed(AppError.Network)) }
        val vm = vm(); keep(vm.state)
        vm.onDraft("hi"); vm.onSend()
        repeat(3) { offline(); online(); advanceTimeBy(3_001) }
        assertEquals(3, asha.asked.size)
        assertNull(vm.state.value.waiting)
        assertEquals("hi", vm.state.value.draft)
    }

    @Test fun backOnlineShowsBrieflyAndReloadsWhatWasOffline() = runTest(dispatcher) {
        val vm = vm(); keep(vm.state)
        offline()
        vm.onMenu(true)
        assertEquals(UsageUi.Offline, vm.state.value.usage)
        assertFalse(vm.state.value.backOnline)
        val refreshes = asha.refreshes
        online()
        assertTrue(vm.state.value.backOnline, "Back online appears")
        assertIs<UsageUi.Loaded>(vm.state.value.usage, "the usage reloads by itself")
        assertTrue(asha.refreshes > refreshes, "the history and recents reload quietly")
        advanceTimeBy(AshaViewModel.BackOnlineMillis + 1)
        assertFalse(vm.state.value.backOnline, "and goes away")
    }

    @Test fun backOnlineIsNotShownWhenTheConnectionNeverWent() = runTest(dispatcher) {
        val vm = vm(); keep(vm.state)
        advanceTimeBy(5_000)
        assertFalse(vm.state.value.backOnline)
    }
}

class FollowRuleTest {
    @Test fun nearTheBottomIsTheNewestItemWithinTheThreshold() {
        assertTrue(FollowRule.nearLatest(0, 0, 200))
        assertTrue(FollowRule.nearLatest(0, 200, 200))
        assertFalse(FollowRule.nearLatest(0, 201, 200))
        assertFalse(FollowRule.nearLatest(1, 0, 200))
    }

    @Test fun scrollingUpStopsFollowingAndComingBackOrJumpingResumes() {
        val rule = FollowRule()
        assertTrue(rule.following)
        rule.onUserScroll(moving = true, nearLatest = true)
        assertTrue(rule.following, "a nudge near the bottom keeps following")
        rule.onUserScroll(moving = true, nearLatest = false)
        assertFalse(rule.following, "stops as soon as they leave the bottom, mid-drag")
        rule.onUserScroll(moving = false, nearLatest = false)
        assertFalse(rule.following)
        rule.onUserScroll(moving = true, nearLatest = true)
        assertFalse(rule.following, "not until they settle there")
        rule.onUserScroll(moving = false, nearLatest = true)
        assertTrue(rule.following, "scrolling back down resumes")
        rule.onUserScroll(moving = false, nearLatest = false)
        rule.resume()
        assertTrue(rule.following, "the jump button or a new question resumes")
    }
}
