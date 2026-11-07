package com.circaso.feature.assistant.domain

import kotlinx.coroutines.flow.Flow

/**
 * A live conversation with Asha (guide A11): one socket open for as long as the person is on the voice screen. The
 * phone streams Opus; the server sends captions, her voice and the saved messages. Nothing said is kept on the phone
 * beyond the messages the server saves into the Asha chat.
 */
sealed interface LiveVoiceEvent {
    /** The server accepted the conversation. [sampleRate] is that of the voice it will send ([encoding] `mulaw` or `linear16`). */
    data class Ready(val voiceSecondsLeft: Long, val encoding: String, val sampleRate: Int) : LiveVoiceEvent

    /** The live caption of what the person is saying; `final` false may still change. */
    data class Transcript(val text: String, val final: Boolean) : LiveVoiceEvent

    /** The question Asha is answering (saved in the chat). */
    data class Turn(val text: String) : LiveVoiceEvent

    /** One sentence of the answer, about when its audio starts. */
    data class Reply(val text: String) : LiveVoiceEvent

    /** A piece of Asha's voice, in the format [Ready] announced. */
    class Audio(val bytes: ByteArray) : LiveVoiceEvent

    /** The answer as saved; `spoken` false means the person talked over it. */
    data class ReplyDone(val message: AshaMessage, val spoken: Boolean) : LiveVoiceEvent

    /**
     * How long the server took on one answered turn, from the moment it took the question: Asha's first word from the
     * model, her first sentence sent to the voice, her first audio sent to the phone. Null for a step that didn't
     * happen (the person talked over her first).
     */
    data class Timing(val turn: Int, val modelFirstTextMs: Long?, val firstSentenceMs: Long?, val firstAudioMs: Long?) : LiveVoiceEvent

    /** Drop queued audio and stop the sound at once. */
    data object Interrupted : LiveVoiceEvent

    /** Asha asked the phone to search or read chats; answered on the same socket. */
    data class ToolRequest(val turnId: String, val callId: String, val name: String, val argumentsJson: String) : LiveVoiceEvent

    /** What Asha is doing ("Checking your transactions…"); null once that tool is done. */
    data class Status(val label: String?) : LiveVoiceEvent

    /** The phone is running a tool for Asha. */
    data class UsingTool(val name: String) : LiveVoiceEvent

    /** One answer failed; the conversation goes on. */
    data class Error(val code: String, val message: String) : LiveVoiceEvent

    /** The server is closing: `ended`, `silence`, or an error code (`assistant.quota_exceeded` with [retryAfterSeconds], …). */
    data class Closing(val reason: String, val message: String?, val retryAfterSeconds: Long?) : LiveVoiceEvent

    /** The socket is gone. [reason] is the close reason when the server gave one (`unauthorized`, `auth required`, `Frame is too big: N`). */
    data class Closed(val code: Int?, val reason: String?) : LiveVoiceEvent
}

/** The open socket. Collecting [events] is what reads it; cancelling the collector closes it. */
interface LiveVoiceSession {
    val events: Flow<LiveVoiceEvent>

    /** One Opus packet. Dropped quietly once the socket is closed. */
    suspend fun sendAudio(packet: ByteArray)

    suspend fun sendToolResults(turnId: String, callId: String, snippets: List<Snippet>)

    /** Fresh tokens before the access token's 15 minutes are up. */
    suspend fun sendAuth(accessToken: String, agentToken: String?)

    /** Hangs up politely: the server answers `closing` `ended`. */
    suspend fun end()

    /**
     * The phone's own barge-in: it heard the person talk over Asha and has stopped her already. The server cancels her
     * answer as for one it heard, and answers `interrupted`. [at] is the onset, ISO-8601, for its latency log.
     */
    suspend fun sendInterrupt(at: String) {}

    suspend fun close()
}

/** Why a live conversation could not even start. */
sealed interface LiveVoiceStart {
    data class Opened(val session: LiveVoiceSession) : LiveVoiceStart
    data object Offline : LiveVoiceStart
    data object SignedOut : LiveVoiceStart
}
