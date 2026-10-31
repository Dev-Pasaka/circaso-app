package com.circaso.feature.assistant.data.remote

import kotlinx.serialization.Serializable

@Serializable data class AskedFromDto(val chatId: String, val chatName: String? = null)
@Serializable data class ReadNoteDto(val chatIds: List<String>, val chatNames: List<String>? = null, val messageCount: Int)

@Serializable
data class ChatMessageDto(
    val messageId: String,
    val role: String,
    val text: String,
    val replyTo: String? = null,
    val askedFrom: String? = null,
    val readNote: ReadNoteDto? = null,
    val at: String,
)

@Serializable data class ChatResponse(val messages: List<ChatMessageDto>, val nextCursor: String? = null)
/** [currency]: the person's display currency (You → Currency), so Asha answers in it. */
@Serializable data class SendMessageRequest(val text: String, val askedFrom: AskedFromDto? = null, val chatReading: Boolean? = null, val currency: String? = null)
@Serializable data class SnippetDto(val chatId: String, val chatName: String? = null, val sender: String, val at: String, val text: String)
@Serializable data class ToolResultsRequest(val callId: String, val snippets: List<SnippetDto>? = null)
@Serializable data class TranscriptResponse(val text: String, val seconds: Int)
@Serializable data class SpeechRequest(val text: String, val language: String? = null, val voice: String? = null)

/** A12: the voices Asha can speak in; one is the default. */
@Serializable data class VoiceDto(val id: String, val name: String, val character: String? = null, val gender: String? = null, val default: Boolean = false)
@Serializable data class VoicesResponse(val voices: List<VoiceDto> = emptyList())

@Serializable
data class UsageResponse(
    val plan: String,
    val textTurnsUsed: Int,
    val textTurnsLeft: Int,
    val voiceSecondsUsed: Int,
    val voiceSecondsLeft: Int,
    val resetsAt: String,
)

/** The `data` of the stream's events. */
@Serializable data class DeltaData(val delta: String)
@Serializable data class ToolRequestData(val turnId: String, val callId: String, val name: String, val arguments: kotlinx.serialization.json.JsonElement? = null)
@Serializable data class DoneData(val message: ChatMessageDto)
