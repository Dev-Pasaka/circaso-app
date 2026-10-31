package com.circaso.feature.assistant

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.NoBody
import com.circaso.core.network.circasoJson
import com.circaso.core.sync.Outbox
import com.circaso.feature.assistant.data.local.AshaDao
import com.circaso.feature.assistant.data.local.AshaMessageEntity
import com.circaso.feature.assistant.data.remote.AshaRemote
import com.circaso.feature.assistant.data.remote.ChatMessageDto
import com.circaso.feature.assistant.data.remote.ChatResponse
import com.circaso.feature.assistant.data.remote.ReadNoteDto
import com.circaso.feature.assistant.data.remote.SendMessageRequest
import com.circaso.feature.assistant.data.remote.SpeechRequest
import com.circaso.feature.assistant.data.remote.SseEvent
import com.circaso.feature.assistant.data.remote.SseParser
import com.circaso.feature.assistant.data.remote.ToolResultsRequest
import com.circaso.feature.assistant.data.remote.TranscriptResponse
import com.circaso.feature.assistant.data.remote.UsageResponse
import com.circaso.feature.assistant.data.repository.AshaDeleteAllHandler
import com.circaso.feature.assistant.data.repository.AshaDeleteMessageHandler
import com.circaso.feature.assistant.data.repository.AshaRepositoryImpl
import com.circaso.feature.assistant.domain.AshaEvent
import com.circaso.feature.assistant.domain.AshaMessage
import com.circaso.feature.assistant.domain.AshaRepository
import com.circaso.feature.assistant.domain.AskAshaUseCase
import com.circaso.feature.assistant.domain.AskedFrom
import com.circaso.feature.assistant.domain.DeviceToolRunner
import com.circaso.feature.assistant.domain.Snippet
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.runSuspending
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SseParserTest {
    private fun parse(vararg lines: String): List<SseEvent> {
        val p = SseParser(); val out = mutableListOf<SseEvent>()
        lines.forEach { p.feed(it)?.let(out::add) }
        p.finish()?.let(out::add)
        return out
    }

    @Test fun anEventEndsAtTheBlankLine() =
        assertEquals(listOf(SseEvent("message", """{"delta":"Hi"}""")), parse("event: message", """data: {"delta":"Hi"}""", ""))

    @Test fun severalEventsInARow() =
        assertEquals(listOf("message", "done"), parse("event: message", "data: 1", "", "event: done", "data: 2", "").map { it.name })

    @Test fun multipleDataLinesAreJoinedWithANewline() = assertEquals("a\nb", parse("data: a", "data: b", "").single().data)

    @Test fun noEventNameMeansMessage() = assertEquals("message", parse("data: x", "").single().name)

    @Test fun commentsAndUnknownFieldsAreIgnored() =
        assertEquals(listOf(SseEvent("done", "ok")), parse(": keep-alive", "id: 7", "retry: 100", "event: done", "data: ok", ""))

    @Test fun carriageReturnsAreStripped() = assertEquals(SseEvent("done", "ok"), parse("event: done\r", "data: ok\r", "\r").single())

    @Test fun aBlankLineWithNoDataMakesNoEvent() = assertTrue(parse("event: message", "").isEmpty())

    @Test fun aLastEventWithoutTheClosingBlankLineStillCounts() = assertEquals("end", parse("event: done", "data: end").single().data)

    @Test fun theValueKeepsColonsAndOnlyDropsOneLeadingSpace() = assertEquals("""{"a":"b: c"}""", parse("""data: {"a":"b: c"}""", "").single().data)
}

private class FakeAshaDao : AshaDao {
    val rows = MutableStateFlow<List<AshaMessageEntity>>(emptyList())
    override fun observe(): Flow<List<AshaMessageEntity>> = rows
    override suspend fun upsert(rows: List<AshaMessageEntity>) { this.rows.value = (this.rows.value.filter { r -> rows.none { it.messageId == r.messageId } } + rows).sortedBy { it.at } }
    override suspend fun delete(id: String) { rows.value = rows.value.filter { it.messageId != id } }
    override suspend fun clear() { rows.value = emptyList() }
}

private fun dto(id: String, role: String = "assistant", text: String = "t", at: String = "2026-10-25T10:00:00Z", note: ReadNoteDto? = null) =
    ChatMessageDto(id, role, text, null, null, note, at)

private class FakeAshaRemote : AshaRemote {
    override suspend fun voices(): AppResult<com.circaso.feature.assistant.data.remote.VoicesResponse> = AppResult.Success(com.circaso.feature.assistant.data.remote.VoicesResponse())
    override suspend fun voiceSample(voiceId: String): AppResult<ByteArray> = AppResult.Success(ByteArray(0))
    var history: AppResult<ChatResponse> = AppResult.Success(ChatResponse(listOf(dto("m1", "user", at = "2026-10-25T09:00:00Z"), dto("m2", at = "2026-10-25T09:00:05Z")), null))
    var events: List<AppResult<SseEvent>> = emptyList()
    var toolResultsAnswer: AppResult<NoBody> = AppResult.Success(NoBody())
    val streamRequests = mutableListOf<SendMessageRequest>()
    val toolResults = mutableListOf<Pair<String, ToolResultsRequest>>()
    val deletedMessages = mutableListOf<Pair<String, String?>>()
    var deleteAllKeys = mutableListOf<String?>()
    var deleteMessageAnswer: AppResult<NoBody> = AppResult.Success(NoBody())

    override suspend fun history(cursor: String?, limit: Int) = history
    override suspend fun deleteAll(idempotencyKey: String?): AppResult<NoBody> { deleteAllKeys += idempotencyKey; return AppResult.Success(NoBody()) }
    override suspend fun deleteMessage(messageId: String, idempotencyKey: String?): AppResult<NoBody> { deletedMessages += messageId to idempotencyKey; return deleteMessageAnswer }
    override suspend fun toolResults(turnId: String, request: ToolResultsRequest): AppResult<NoBody> { toolResults += turnId to request; return toolResultsAnswer }
    override suspend fun transcribe(audio: ByteArray, contentType: String, seconds: Int) = AppResult.Success(TranscriptResponse("hello", seconds))
    override suspend fun speech(request: SpeechRequest) = AppResult.Success(byteArrayOf(1, 2, 3))
    override suspend fun usage() = AppResult.Success(UsageResponse("free", 3, 7, 10, 50, "2026-10-26T00:00:00Z"))
    override fun stream(request: SendMessageRequest): Flow<AppResult<SseEvent>> = flow { streamRequests += request; events.forEach { emit(it) } }
}

private fun ok(name: String, data: String) = AppResult.Success(SseEvent(name, data))
private const val DoneJson = """{"message":{"messageId":"m9","role":"assistant","text":"Done!","at":"2026-10-25T10:05:00Z"}}"""

class AshaRepositoryTest {
    private val json = circasoJson()
    private val dao = FakeAshaDao()
    private val outboxDao = FakeOutboxDao()
    private val remote = FakeAshaRemote()
    private var kicks = 0
    private val repo = AshaRepositoryImpl(remote, dao, Outbox(outboxDao) { 1_000L }, json, kickOutbox = { kicks++ })

    @Test fun historyIsSavedOldestFirstAndReadBack() = runSuspending {
        repo.refreshHistory()
        assertEquals(listOf("m1", "m2"), repo.history.first().map { it.messageId })
    }

    @Test fun aFailedRefreshKeepsTheSavedHistory() = runSuspending {
        repo.refreshHistory()
        remote.history = AppResult.Failure(AppError.Network)
        assertIs<AppResult.Failure>(repo.refreshHistory())
        assertEquals(2, repo.history.first().size)
    }

    @Test fun theReadNoteSurvivesTheRoundTrip() = runSuspending {
        remote.history = AppResult.Success(ChatResponse(listOf(dto("m3", note = ReadNoteDto(listOf("c1", "c2"), listOf("Mum", "Holiday Fund"), 12))), null))
        repo.refreshHistory()
        val note = repo.history.first().single().readNote!!
        assertEquals(listOf("c1", "c2"), note.chatIds)
        assertEquals(listOf("Mum", "Holiday Fund"), note.chatNames)
        assertEquals(12, note.messageCount)
    }

    @Test fun deletingAMessageHidesItAtOnceQueuesItAndItDoesNotComeBackOnRefresh() = runSuspending {
        repo.refreshHistory()
        repo.deleteMessage("m1")
        assertEquals(listOf("m2"), repo.history.first().map { it.messageId })
        assertEquals("m1", outboxDao.rows.value.single().payload)
        repo.refreshHistory() // the server still has it until the deletion is sent
        assertEquals(listOf("m2"), repo.history.first().map { it.messageId })
        assertEquals(1, kicks)
    }

    @Test fun deletingEverythingClearsTheHistoryAndPausesRefreshUntilSent() = runSuspending {
        repo.refreshHistory()
        repo.deleteAll()
        assertEquals(emptyList(), repo.history.first())
        repo.refreshHistory()
        assertEquals(emptyList(), repo.history.first())
    }

    @Test fun deleteHandlersUseTheItemsKeyAndAnAlreadyGoneMessageIsFine() = runSuspending {
        repo.deleteMessage("m1"); repo.deleteAll()
        val (one, all) = outboxDao.rows.value
        assertIs<AppResult.Success<Unit>>(AshaDeleteMessageHandler(remote).send(one))
        assertEquals("m1" to one.idempotencyKey, remote.deletedMessages.single())
        remote.deleteMessageAnswer = AppResult.Failure(AppError.Api("assistant.turn_not_found", 404, null, null, emptyMap()))
        assertIs<AppResult.Success<Unit>>(AshaDeleteMessageHandler(remote).send(one))
        assertIs<AppResult.Success<Unit>>(AshaDeleteAllHandler(remote).send(all))
        assertEquals(listOf<String?>(all.idempotencyKey), remote.deleteAllKeys)
    }

    @Test fun theReplyStreamsAsDeltasThenDoneAndIsSaved() = runSuspending {
        remote.events = listOf(ok("message", """{"delta":"Hel"}"""), ok("message", """{"delta":"lo"}"""), ok("done", DoneJson))
        val events = repo.ask("hi", AskedFrom("c1", "Mum"), chatReading = true).toList()
        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<AshaEvent.Delta>().map { it.text })
        assertEquals("Done!", events.filterIsInstance<AshaEvent.Done>().single().message.text)
        assertTrue(repo.history.first().any { it.messageId == "m9" })
        val request = remote.streamRequests.single()
        assertEquals("hi", request.text)
        assertEquals("c1", request.askedFrom?.chatId)
        assertEquals(true, request.chatReading)
    }

    @Test fun unknownAndMalformedEventsAreSkipped() = runSuspending {
        remote.events = listOf(ok("future_thing", "{}"), ok("message", "not json"), ok("message", """{"delta":"ok"}"""))
        assertEquals(listOf("ok"), repo.ask("x", null, false).toList().filterIsInstance<AshaEvent.Delta>().map { it.text })
    }

    @Test fun aFailureBeforeAnyTextIsReported() = runSuspending {
        remote.events = listOf(AppResult.Failure(AppError.Api("assistant.quota_exceeded", 429, "r", 3600, emptyMap())))
        val failed = repo.ask("x", null, false).toList().single() as AshaEvent.Failed
        assertEquals("assistant.quota_exceeded", (failed.error as AppError.Api).code)
    }

    @Test fun voiceLongerThanASixtySecondsOrThreeMegabytesIsRefusedBeforeUploading() = runSuspending {
        assertEquals("assistant.voice_too_long", ((repo.transcribe(ByteArray(10), "audio/m4a", 61) as AppResult.Failure).error as AppError.Api).code)
        assertIs<AppResult.Failure>(repo.transcribe(ByteArray(3 * 1024 * 1024 + 1), "audio/m4a", 10))
        assertEquals("hello", (repo.transcribe(ByteArray(10), "audio/m4a", 5) as AppResult.Success).value)
    }

    @Test fun usageAndSpeechPassThrough() = runSuspending {
        assertEquals(7, (repo.usage() as AppResult.Success).value.textTurnsLeft)
        assertEquals(3, (repo.speak("hi", null) as AppResult.Success).value.size)
    }
}

class AskAshaUseCaseTest {
    private class ScriptedRepo(val script: List<AshaEvent>) : AshaRepository {
        val submitted = mutableListOf<Triple<String, String, Int>>()
        var submitAnswer: AppResult<Unit> = AppResult.Success(Unit)
        override val history: Flow<List<AshaMessage>> = MutableStateFlow(emptyList())
        override suspend fun refreshHistory() = AppResult.Success(Unit)
        override fun ask(text: String, askedFrom: AskedFrom?, chatReading: Boolean): Flow<AshaEvent> = flow { script.forEach { emit(it) } }
        override suspend fun submitToolResults(turnId: String, callId: String, snippets: List<Snippet>): AppResult<Unit> { submitted += Triple(turnId, callId, snippets.size); return submitAnswer }
        override suspend fun deleteMessage(messageId: String) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun transcribe(audio: ByteArray, contentType: String, durationSeconds: Int) = AppResult.Success("")
        override suspend fun speak(text: String, language: String?) = AppResult.Success(ByteArray(0))
        override suspend fun usage() = AppResult.Failure(AppError.Network)
    }

    private val reply = AshaMessage("m", "assistant", "ok", null, null, null, "2026-10-25T10:00:00Z")
    private fun snippets(n: Int) = List(n) { Snippet("c", null, "s", "t", "text $it") }

    @Test fun aToolRequestIsAnsweredOnThePhoneAndTheScreenSeesOnlyStatusAndText() = runSuspending {
        val repo = ScriptedRepo(listOf(AshaEvent.Delta("a"), AshaEvent.ToolRequest("turn-1", "call-1", "search_chats", "{}"), AshaEvent.Delta("b"), AshaEvent.Done(reply)))
        val events = AskAshaUseCase(repo, DeviceToolRunner { _, _ -> snippets(3) })("hi").toList()
        assertEquals(listOf("Delta", "UsingTool", "Delta", "Done"), events.map { it::class.simpleName })
        assertEquals(listOf(Triple("turn-1", "call-1", 3)), repo.submitted)
    }

    @Test fun atMostThirtySnippetsAreSent() = runSuspending {
        val repo = ScriptedRepo(listOf(AshaEvent.ToolRequest("t", "c", "read_chat", "{}")))
        AskAshaUseCase(repo, DeviceToolRunner { _, _ -> snippets(45) })("hi").toList()
        assertEquals(30, repo.submitted.single().third)
    }

    @Test fun aFailureToSendToolResultsIsReported() = runSuspending {
        val repo = ScriptedRepo(listOf(AshaEvent.ToolRequest("t", "c", "x", "{}"))).also { it.submitAnswer = AppResult.Failure(AppError.Api("assistant.turn_not_found", 404, null, null, emptyMap())) }
        val events = AskAshaUseCase(repo, DeviceToolRunner { _, _ -> emptyList() })("hi").toList()
        assertIs<AshaEvent.Failed>(events.last())
    }

    @Test fun withNoToolRequestsEventsPassStraightThrough() = runSuspending {
        val repo = ScriptedRepo(listOf(AshaEvent.Delta("x"), AshaEvent.Done(reply)))
        assertEquals(2, AskAshaUseCase(repo, DeviceToolRunner { _, _ -> emptyList() })("hi").toList().size)
        assertNull(repo.submitted.firstOrNull())
    }
}

class ConversationOrderTest {
    private fun m(id: String, role: String, at: String, replyTo: String? = null) = com.circaso.feature.assistant.domain.AshaMessage(id, role, id, replyTo, null, null, at)
    private fun order(vararg msgs: com.circaso.feature.assistant.domain.AshaMessage) =
        com.circaso.feature.assistant.data.repository.inConversationOrder(msgs.toList()).map { it.messageId }

    @Test fun aReplyStampedBeforeItsQuestionStillSitsUnderIt() =
        assertEquals(listOf("q", "a"), order(m("a", "assistant", "2026-10-25T11:09:00.100Z", replyTo = "q"), m("q", "user", "2026-10-25T11:09:00.200Z")))

    @Test fun aWholeConversationReadsInOrder() = assertEquals(
        listOf("q1", "a1", "q2", "a2"),
        order(m("a2", "assistant", "2026-10-25T11:11:00Z", "q2"), m("q2", "user", "2026-10-25T11:11:01Z"), m("a1", "assistant", "2026-10-25T11:09:00Z", "q1"), m("q1", "user", "2026-10-25T11:09:01Z")),
    )

    @Test fun withoutAReplyLinkItIsByTimeAndTheQuestionWinsATie() =
        assertEquals(listOf("u", "a"), order(m("a", "assistant", "2026-10-25T11:09:00Z"), m("u", "user", "2026-10-25T11:09:00Z")))

    @Test fun aReplyToAMessageThatIsGoneIsNotLost() =
        assertEquals(listOf("a"), order(m("a", "assistant", "2026-10-25T11:09:00Z", replyTo = "deleted")))

    @Test fun aLoopInTheLinksCannotHangOrDuplicate() =
        assertEquals(2, order(m("x", "assistant", "2026-10-25T11:09:00Z", "y"), m("y", "assistant", "2026-10-25T11:09:01Z", "x")).size)

    @Test fun theServersCapitalisedRolesAreRecognised() {
        assertEquals(true, m("q", "USER", "2026-10-25T11:09:00Z").isFromUser)
        assertEquals(false, m("a", "ASHA", "2026-10-25T11:09:00Z").isFromUser)
    }

    @Test fun onATieThePersonsMessageComesFirstWithTheServersRoleNames() =
        assertEquals(listOf("q", "a"), order(m("a", "ASHA", "2026-10-25T11:09:00Z"), m("q", "USER", "2026-10-25T11:09:00Z")))
}
