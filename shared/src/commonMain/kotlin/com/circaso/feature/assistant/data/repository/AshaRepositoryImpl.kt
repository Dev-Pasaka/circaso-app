package com.circaso.feature.assistant.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.map
import com.circaso.core.sync.Outbox
import com.circaso.feature.assistant.data.local.AshaDao
import com.circaso.feature.assistant.data.local.AshaMessageEntity
import com.circaso.feature.assistant.data.remote.AshaRemote
import com.circaso.feature.assistant.data.remote.AskedFromDto
import com.circaso.feature.assistant.data.remote.ChatMessageDto
import com.circaso.feature.assistant.data.remote.DeltaData
import com.circaso.feature.assistant.data.remote.DoneData
import com.circaso.feature.assistant.data.remote.ReadNoteDto
import com.circaso.feature.assistant.data.remote.SendMessageRequest
import com.circaso.feature.assistant.data.remote.SnippetDto
import com.circaso.feature.assistant.data.remote.SpeechRequest
import com.circaso.feature.assistant.data.remote.ToolRequestData
import com.circaso.feature.assistant.data.remote.ToolResultsRequest
import com.circaso.feature.assistant.domain.AshaEvent
import com.circaso.feature.assistant.domain.AshaMessage
import com.circaso.feature.assistant.domain.AshaRepository
import com.circaso.feature.assistant.domain.AshaUsage
import com.circaso.feature.assistant.domain.AskedFrom
import com.circaso.feature.assistant.domain.ReadNote
import com.circaso.feature.assistant.domain.Snippet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

class AshaRepositoryImpl(
    private val remote: AshaRemote,
    private val dao: AshaDao,
    private val outbox: Outbox,
    private val json: Json,
    private val kickOutbox: () -> Unit,
    /** The display currency to answer in: the person's choice, else their phone's region's. */
    private val currency: suspend () -> String? = { null },
) : AshaRepository {

    override val history: Flow<List<AshaMessage>> = dao.observe().map { rows -> inConversationOrder(rows.map { it.toDomain() }) }

    override suspend fun refreshHistory(): AppResult<Unit> {
        // Messages queued for deletion must not come back while the deletion waits to be sent.
        val deleting = outbox.waitingPayloads(AshaDeleteMessageHandler.Type).toSet()
        if (outbox.waiting(AshaDeleteAllHandler.Type) > 0) return AppResult.Success(Unit)
        return when (val result = remote.history(null, PageSize)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                dao.upsert(result.value.messages.filter { it.messageId !in deleting }.map { it.toEntity() })
                AppResult.Success(Unit)
            }
        }
    }

    override fun ask(text: String, askedFrom: AskedFrom?, chatReading: Boolean): Flow<AshaEvent> = flow {
        val request = SendMessageRequest(text, askedFrom?.let { AskedFromDto(it.chatId, it.chatName) }, chatReading, currency())
        remote.stream(request).collect { item ->
            when (item) {
                is AppResult.Failure -> emit(AshaEvent.Failed(item.error))
                is AppResult.Success -> translate(item.value.name, item.value.data)?.let { event ->
                    if (event is AshaEvent.Done) { dao.upsert(listOf(event.message.toEntity())); emit(event); refreshHistory() } else emit(event)
                }
            }
        }
    }

    /** Turns one stream event into a domain event. Unknown or malformed events are skipped, never fatal. */
    private fun translate(name: String, data: String): AshaEvent? = try {
        when (name) {
            "message" -> AshaEvent.Delta(json.decodeFromString(DeltaData.serializer(), data).delta)
            "tool_request" -> json.decodeFromString(ToolRequestData.serializer(), data).let {
                AshaEvent.ToolRequest(it.turnId, it.callId, it.name, (it.arguments ?: JsonNull).toString())
            }
            "tool_status" -> AshaEvent.Status(json.parseToJsonElement(data).jsonObject["label"]?.jsonPrimitive?.contentOrNull)
            "tool_done" -> AshaEvent.Status(null)
            "done" -> AshaEvent.Done(json.decodeFromString(DoneData.serializer(), data).message.toDomain())
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    override suspend fun submitToolResults(turnId: String, callId: String, snippets: List<Snippet>): AppResult<Unit> =
        remote.toolResults(turnId, ToolResultsRequest(callId, snippets.map { SnippetDto(it.chatId, it.chatName, it.sender, it.at, it.text) })).map { }

    override suspend fun deleteMessage(messageId: String) {
        dao.delete(messageId)
        outbox.enqueue(AshaDeleteMessageHandler.Type, messageId)
        kickOutbox()
    }

    override suspend fun deleteAll() {
        dao.clear()
        outbox.enqueue(AshaDeleteAllHandler.Type, "")
        kickOutbox()
    }

    override suspend fun transcribe(audio: ByteArray, contentType: String, durationSeconds: Int): AppResult<String> {
        if (durationSeconds > MaxVoiceSeconds || audio.size > MaxVoiceBytes) {
            return AppResult.Failure(AppError.Api("assistant.voice_too_long", 400, null, null, emptyMap()))
        }
        return remote.transcribe(audio, contentType, durationSeconds).map { it.text }
    }

    override suspend fun speak(text: String, language: String?): AppResult<ByteArray> = remote.speech(SpeechRequest(text, language))

    override suspend fun usage(): AppResult<AshaUsage> =
        remote.usage().map { AshaUsage(it.plan, it.textTurnsUsed, it.textTurnsLeft, it.voiceSecondsUsed, it.voiceSecondsLeft, it.resetsAt) }

    private companion object {
        const val PageSize = 50
        const val MaxVoiceSeconds = 60
        const val MaxVoiceBytes = 3 * 1024 * 1024
    }
}

private const val Sep = "\u001F"

private fun ChatMessageDto.toEntity() = AshaMessageEntity(
    messageId, role, text, replyTo, askedFrom,
    readChatIds = readNote?.chatIds?.joinToString(Sep),
    readChatNames = readNote?.chatNames?.joinToString(Sep),
    readMessageCount = readNote?.messageCount,
    at = at,
)

internal fun ChatMessageDto.toDomain() = AshaMessage(
    messageId, role, text, replyTo, askedFrom,
    readNote = readNote?.let { ReadNote(it.chatIds, it.chatNames.orEmpty(), it.messageCount) },
    at = at,
)

private fun AshaMessage.toEntity() = AshaMessageEntity(
    messageId, role, text, replyTo, askedFrom,
    readChatIds = readNote?.chatIds?.joinToString(Sep),
    readChatNames = readNote?.chatNames?.joinToString(Sep),
    readMessageCount = readNote?.messageCount,
    at = at,
)

private fun AshaMessageEntity.toDomain() = AshaMessage(
    messageId, role, text, replyTo, askedFrom,
    readNote = readMessageCount?.let { ReadNote(readChatIds?.split(Sep)?.filter { s -> s.isNotEmpty() }.orEmpty(), readChatNames?.split(Sep)?.filter { s -> s.isNotEmpty() }.orEmpty(), it) },
    at = at,
)

/**
 * Puts a conversation in reading order. Time alone is not enough: the server can stamp a reply a moment before the
 * question it answers (seen live), and a reply under the wrong question is confusing. So every reply goes directly
 * under the message it answers (`replyTo`); everything else is by time, and on a tie the person's message comes first.
 */
internal fun inConversationOrder(messages: List<AshaMessage>): List<AshaMessage> {
    val byTime = messages.sortedWith(compareBy<AshaMessage> { it.at }.thenBy { if (it.isFromUser) 0 else 1 })
    val ids = byTime.map { it.messageId }.toSet()
    val repliesTo = byTime.filter { it.replyTo != null && it.replyTo in ids }.groupBy { it.replyTo }
    val placed = mutableSetOf<String>()
    val out = mutableListOf<AshaMessage>()
    fun add(m: AshaMessage) {
        if (!placed.add(m.messageId)) return
        out += m
        repliesTo[m.messageId]?.forEach { add(it) }
    }
    byTime.forEach { m -> if (m.replyTo == null || m.replyTo !in ids) add(m) }
    byTime.forEach { add(it) } // links that go round in a circle: nothing is ever dropped
    return out
}
