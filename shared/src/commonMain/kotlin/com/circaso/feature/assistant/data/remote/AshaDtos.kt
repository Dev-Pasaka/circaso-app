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
    val conversationId: String? = null,
)

@Serializable data class ChatResponse(val messages: List<ChatMessageDto>, val nextCursor: String? = null)
/** [currency]: the person's display currency (You → Currency), so Asha answers in it. */
@Serializable
data class SendMessageRequest(
    val text: String,
    val askedFrom: AskedFromDto? = null,
    val chatReading: Boolean? = null,
    val currency: String? = null,
    val conversationId: String? = null,
    /** Where the person summoned Asha's bubble ("Wallet › Send"), as light context for this one question. */
    val screen: String? = null,
    /** A picture of the app's screen for this one question; the server reads it and does not keep it. */
    val image: ImageDto? = null,
)

/** [data] is the picture's bytes in base64; [mediaType] is `image/jpeg` or `image/png`. */
@Serializable data class ImageDto(val mediaType: String, val data: String)
@Serializable data class SnippetDto(val chatId: String, val chatName: String? = null, val sender: String, val at: String, val text: String)
@Serializable data class ToolResultsRequest(val callId: String, val snippets: List<SnippetDto>? = null)
@Serializable data class TranscriptResponse(val text: String, val seconds: Int)
@Serializable data class SpeechRequest(val text: String, val language: String? = null, val voice: String? = null)

/** A12: the voices Asha can speak in; one is the default. */
@Serializable data class VoiceDto(val id: String, val name: String, val character: String? = null, val gender: String? = null, val default: Boolean = false)
@Serializable data class VoicesResponse(val voices: List<VoiceDto> = emptyList())

@Serializable data class UsageMeterDto(val used: Int = 0, val limit: Int? = null)

/**
 * A7. Two shapes are read: the period one (`messages` and `voiceSeconds` as used/limit, `periodStart`, `periodEnd`,
 * `resetsAt`) and the day one A7 first shipped with (`textTurnsUsed`/`Left`, `voiceSecondsUsed`/`Left`). Every field
 * is optional so either decodes.
 */
/** A14 (2026-11-07): whether Asha can answer now; `available` false is "down for maintenance". */
@Serializable
data class AssistantStatusResponse(val available: Boolean = true, val since: String? = null)

@Serializable
data class UsageResponse(
    val plan: String = "free",
    val periodStart: String? = null,
    val periodEnd: String? = null,
    val resetsAt: String? = null,
    val messages: UsageMeterDto? = null,
    val voiceSeconds: UsageMeterDto? = null,
    val textTurnsUsed: Int? = null,
    val textTurnsLeft: Int? = null,
    val voiceSecondsUsed: Int? = null,
    val voiceSecondsLeft: Int? = null,
)

/** The `data` of the stream's events. */
@Serializable data class DeltaData(val delta: String)
@Serializable data class ToolRequestData(val turnId: String, val callId: String, val name: String, val arguments: kotlinx.serialization.json.JsonElement? = null)
@Serializable data class DoneData(val message: ChatMessageDto, val conversationId: String? = null, val conversation: ConversationDto? = null)

/** C1–C4: Asha's separate conversations, each with its own context. */
@Serializable data class ConversationDto(val conversationId: String, val title: String, val createdAt: String? = null, val updatedAt: String? = null, val lastMessageAt: String? = null)
@Serializable data class ConversationsResponse(val conversations: List<ConversationDto> = emptyList(), val nextCursor: String? = null)
@Serializable data class ConversationRequest(val title: String? = null)
