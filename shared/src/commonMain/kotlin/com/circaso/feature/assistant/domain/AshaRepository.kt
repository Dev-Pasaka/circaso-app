package com.circaso.feature.assistant.domain

import com.circaso.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * History is read from the phone and refreshed in the background; deleting is a queued write. Asking, voice, speech
 * and the usage figures need the server (online-only), and the reply streams as events.
 */
interface AshaRepository {
    /** The chat, oldest first. */
    val history: Flow<List<AshaMessage>>

    suspend fun refreshHistory(): AppResult<Unit>

    /** Asks Asha. The reply arrives as events on the returned flow; cancelling the flow stops listening. */
    fun ask(text: String, askedFrom: AskedFrom?, chatReading: Boolean): Flow<AshaEvent>

    /** The phone's answer to a [AshaEvent.ToolRequest]; the reply then continues on the same event stream. */
    suspend fun submitToolResults(turnId: String, callId: String, snippets: List<Snippet>): AppResult<Unit>

    suspend fun deleteMessage(messageId: String)
    suspend fun deleteAll()

    suspend fun transcribe(audio: ByteArray, contentType: String, durationSeconds: Int): AppResult<String>
    suspend fun speak(text: String, language: String?): AppResult<ByteArray>
    suspend fun usage(): AppResult<AshaUsage>
}

/** Runs what Asha asks of the phone. Chats are end-to-end encrypted, so only the phone can search or read them. */
fun interface DeviceToolRunner {
    suspend fun run(name: String, argumentsJson: String): List<Snippet>
}

/** Used until messaging exists: there are no chats to read, so Asha gets nothing and says so. */
object NoChatsToolRunner : DeviceToolRunner {
    override suspend fun run(name: String, argumentsJson: String): List<Snippet> = emptyList()
}
