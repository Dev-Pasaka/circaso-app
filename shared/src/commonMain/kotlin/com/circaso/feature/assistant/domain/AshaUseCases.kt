package com.circaso.feature.assistant.domain

import com.circaso.core.common.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ObserveAshaHistoryUseCase(private val repository: AshaRepository) { operator fun invoke() = repository.history }
class RefreshAshaHistoryUseCase(private val repository: AshaRepository) { suspend operator fun invoke() = repository.refreshHistory() }
class DeleteAshaMessageUseCase(private val repository: AshaRepository) { suspend operator fun invoke(messageId: String) = repository.deleteMessage(messageId) }
class DeleteAshaHistoryUseCase(private val repository: AshaRepository) { suspend operator fun invoke() = repository.deleteAll() }
class TranscribeVoiceUseCase(private val repository: AshaRepository) { suspend operator fun invoke(audio: ByteArray, contentType: String, seconds: Int) = repository.transcribe(audio, contentType, seconds) }
class SpeakUseCase(private val repository: AshaRepository) { suspend operator fun invoke(text: String, language: String? = null) = repository.speak(text, language) }
class GetAshaUsageUseCase(private val repository: AshaRepository) { suspend operator fun invoke() = repository.usage() }

/**
 * Asks Asha and runs her tool requests on the phone. The screen only sees [AshaEvent.Delta], [AshaEvent.UsingTool],
 * [AshaEvent.Done] and [AshaEvent.Failed]: a tool request is answered here (at most 30 snippets, as the server allows)
 * and the reply carries on in the same stream.
 */
class AskAshaUseCase(private val repository: AshaRepository, private val tools: DeviceToolRunner) {
    operator fun invoke(text: String, askedFrom: AskedFrom? = null, chatReading: Boolean = false): Flow<AshaEvent> = flow {
        repository.ask(text, askedFrom, chatReading).collect { event ->
            when (event) {
                is AshaEvent.ToolRequest -> {
                    emit(AshaEvent.UsingTool(event.name))
                    val snippets = tools.run(event.name, event.argumentsJson).take(MaxSnippets)
                    val sent = repository.submitToolResults(event.turnId, event.callId, snippets)
                    if (sent is AppResult.Failure) emit(AshaEvent.Failed(sent.error))
                }
                else -> emit(event)
            }
        }
    }

    private companion object { const val MaxSnippets = 30 }
}
