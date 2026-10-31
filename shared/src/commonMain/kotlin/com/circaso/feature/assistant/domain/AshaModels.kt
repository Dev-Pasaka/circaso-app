package com.circaso.feature.assistant.domain

import com.circaso.core.common.AppError

data class AskedFrom(val chatId: String, val chatName: String?)

/** What Asha read to answer (shown as a note under the reply). */
data class ReadNote(val chatIds: List<String>, val chatNames: List<String>, val messageCount: Int)

/** One of Asha's separate conversations (C1): its own context, titled from its first question. */
data class AshaConversation(val id: String, val title: String, val lastMessageAt: String?)

data class AshaMessage(
    val messageId: String,
    /** What the server sends: `USER` or `ASHA` (seen live; the guide only says "string"), so compare with [isFromUser]. */
    val role: String,
    val text: String,
    val replyTo: String?,
    val askedFrom: String?,
    val readNote: ReadNote?,
    val at: String,
    /** The conversation it belongs to; null before conversations existed. */
    val conversationId: String? = null,
) {
    val isFromUser get() = role.equals("user", ignoreCase = true)
}

/** A short piece of a chat that the phone hands Asha for one turn. Used for that turn only and never stored by the server. */
data class Snippet(val chatId: String, val chatName: String?, val sender: String, val at: String, val text: String)

data class AshaUsage(
    val plan: String,
    val textTurnsUsed: Int,
    val textTurnsLeft: Int,
    val voiceSecondsUsed: Int,
    val voiceSecondsLeft: Int,
    val resetsAt: String,
)

/** What happens while Asha answers. */
sealed interface AshaEvent {
    /** A piece of the reply to append on screen. */
    data class Delta(val text: String) : AshaEvent

    /** Asha asked the phone to do something only it can (search or read chats). */
    data class ToolRequest(val turnId: String, val callId: String, val name: String, val argumentsJson: String) : AshaEvent

    /** The phone is running a tool for Asha (the screen can say "Reading your chats…"). */
    data class UsingTool(val name: String) : AshaEvent

    /** What Asha is doing right now ("Checking your transactions…"), written by the server per tool; null when done. */
    data class Status(val label: String?) : AshaEvent

    /** The reply is complete and saved. */
    data class Done(val message: AshaMessage) : AshaEvent

    data class Failed(val error: AppError) : AshaEvent
}
