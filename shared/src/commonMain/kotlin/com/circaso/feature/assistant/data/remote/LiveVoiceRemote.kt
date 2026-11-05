package com.circaso.feature.assistant.data.remote

import com.circaso.core.common.Diagnostics
import com.circaso.feature.assistant.data.repository.toDomain
import com.circaso.feature.assistant.domain.LiveVoiceEvent
import com.circaso.feature.assistant.domain.LiveVoiceSession
import com.circaso.feature.assistant.domain.Snippet
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A frame on the live voice socket. The realtime [com.circaso.core.realtime.Socket] carries text only; this one carries audio too. */
sealed interface LiveFrame {
    data class Text(val text: String) : LiveFrame
    class Binary(val bytes: ByteArray) : LiveFrame
    data class Closed(val code: Int?, val reason: String?) : LiveFrame
}

interface LiveSocket {
    /** Frames in order, ending with one [LiveFrame.Closed]. */
    val incoming: Flow<LiveFrame>
    suspend fun sendText(text: String)
    suspend fun sendBinary(bytes: ByteArray)
    suspend fun close()
}

fun interface LiveSocketFactory {
    suspend fun open(url: String): LiveSocket
}

class KtorLiveSocketFactory(private val client: HttpClient) : LiveSocketFactory {
    override suspend fun open(url: String): LiveSocket {
        val session = client.webSocketSession(urlString = url)
        return object : LiveSocket {
            override val incoming: Flow<LiveFrame> = flow {
                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Text -> emit(LiveFrame.Text(frame.readText()))
                        is Frame.Binary -> emit(LiveFrame.Binary(frame.readBytes()))
                        else -> Unit
                    }
                }
                val reason = session.closeReason.await()
                emit(LiveFrame.Closed(reason?.code?.toInt(), reason?.message))
            }
            override suspend fun sendText(text: String) = session.send(Frame.Text(text))
            override suspend fun sendBinary(bytes: ByteArray) = session.send(Frame.Binary(true, bytes))
            override suspend fun close() = session.close()
        }
    }
}

/** `https://host` becomes `wss://host/v1/assistant/voice/live`. */
fun liveVoiceUrl(baseUrl: String): String = baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/v1/assistant/voice/live"

interface LiveVoiceRemote {
    /** Opens the socket. The auth frame goes out when [LiveVoiceSession.events] is first collected. */
    suspend fun open(accessToken: String, agentToken: String?, language: String, chatReading: Boolean): LiveVoiceSession
}

class KtorLiveVoiceRemote(
    private val sockets: LiveSocketFactory,
    private val url: String,
    private val json: Json,
    private val currency: suspend () -> String? = { null },
    private val voice: suspend () -> String? = { null },
    /** The Asha conversation the voice chat belongs to (the one open), so its turns land there. */
    private val conversation: () -> String? = { null },
) : LiveVoiceRemote {
    override suspend fun open(accessToken: String, agentToken: String?, language: String, chatReading: Boolean): LiveVoiceSession =
        LiveVoiceSocketSession(sockets.open(url), json, authFrame(accessToken, agentToken, language, chatReading, currency(), voice(), conversation()))
}

internal fun authFrame(accessToken: String, agentToken: String?, language: String? = null, chatReading: Boolean? = null, currency: String? = null, voice: String? = null, conversationId: String? = null): JsonObject = buildJsonObject {
    put("type", "auth")
    put("accessToken", accessToken)
    agentToken?.let { put("agentToken", it) }
    language?.let { put("language", it) }
    chatReading?.let { put("chatReading", it) }
    currency?.let { put("currency", it) }
    voice?.let { put("voice", it) }
    conversationId?.let { put("conversationId", it) }
}

/**
 * The protocol of guide section 9 over one [LiveSocket]: the first frame authenticates, binary frames are audio both
 * ways, text frames are JSON routed on `type`. Frames that do not decode are skipped, never fatal. Collect [events] once.
 */
class LiveVoiceSocketSession(private val socket: LiveSocket, private val json: Json, private val auth: JsonObject) : LiveVoiceSession {
    private val sendLock = Mutex()
    private var open = true

    override val events: Flow<LiveVoiceEvent> = flow {
        send(json.encodeToString(JsonObject.serializer(), auth))
        var closed = false
        try {
            socket.incoming.collect { frame ->
                when (frame) {
                    is LiveFrame.Binary -> emit(LiveVoiceEvent.Audio(frame.bytes))
                    is LiveFrame.Text -> translate(frame.text)?.let { emit(it) }
                    is LiveFrame.Closed -> { open = false; closed = true; emit(LiveVoiceEvent.Closed(frame.code, frame.reason)) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The connection dropped mid-read (a socket abort on a network change): the conversation ends, the app does not.
            Diagnostics.note("voice: socket failed (${e::class.simpleName})")
        }
        open = false
        if (!closed) emit(LiveVoiceEvent.Closed(null, "failed"))
    }

    override suspend fun sendAudio(packet: ByteArray) {
        if (!open) return
        try { sendLock.withLock { socket.sendBinary(packet) } } catch (e: CancellationException) { throw e } catch (e: Throwable) { open = false }
    }

    override suspend fun sendToolResults(turnId: String, callId: String, snippets: List<Snippet>) = send(
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("type", "tool_results")
                put("turnId", turnId)
                put("callId", callId)
                put("snippets", json.encodeToJsonElement(ListSerializer(SnippetDto.serializer()), snippets.map { SnippetDto(it.chatId, it.chatName, it.sender, it.at, it.text) }))
            },
        ),
    )

    override suspend fun sendAuth(accessToken: String, agentToken: String?) = send(json.encodeToString(JsonObject.serializer(), authFrame(accessToken, agentToken)))

    override suspend fun end() = send("""{"type":"end"}""")

    override suspend fun close() {
        open = false
        runCatching { socket.close() }
    }

    private suspend fun send(text: String) {
        if (!open) return
        try { sendLock.withLock { socket.sendText(text) } } catch (e: CancellationException) { throw e } catch (e: Throwable) { open = false }
    }

    private fun translate(text: String): LiveVoiceEvent? {
        val o = try { json.parseToJsonElement(text).jsonObject } catch (e: Exception) { return null }
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        return try {
            when (str("type")) {
                "ready" -> {
                    val audio = o["audio"]?.jsonObject
                    LiveVoiceEvent.Ready(
                        o["voiceSecondsLeft"]?.jsonPrimitive?.longOrNull ?: 0,
                        audio?.get("encoding")?.jsonPrimitive?.contentOrNull ?: "mulaw",
                        audio?.get("sampleRate")?.jsonPrimitive?.intOrNull ?: 8_000,
                    )
                }
                "transcript" -> LiveVoiceEvent.Transcript(str("text").orEmpty(), o["final"]?.jsonPrimitive?.booleanOrNull ?: false)
                "turn" -> LiveVoiceEvent.Turn(str("text").orEmpty())
                "reply" -> LiveVoiceEvent.Reply(str("text").orEmpty())
                "reply_done" -> LiveVoiceEvent.ReplyDone(
                    json.decodeFromJsonElement(ChatMessageDto.serializer(), o.getValue("message")).toDomain(),
                    o["spoken"]?.jsonPrimitive?.booleanOrNull ?: true,
                )
                "tool_status" -> LiveVoiceEvent.Status(str("label"))
                "tool_done" -> LiveVoiceEvent.Status(null)
                // `mascot` frames (backend PR #32) may still arrive; the mascot is gone, so they are ignored like any unknown frame.
                "tool_request" -> LiveVoiceEvent.ToolRequest(str("turnId").orEmpty(), str("callId").orEmpty(), str("name").orEmpty(), argumentsOf(o["arguments"]))
                "timing" -> LiveVoiceEvent.Timing(
                    o["turn"]?.jsonPrimitive?.intOrNull ?: 0,
                    o["modelFirstTextMs"]?.jsonPrimitive?.longOrNull,
                    o["firstSentenceMs"]?.jsonPrimitive?.longOrNull,
                    o["firstAudioMs"]?.jsonPrimitive?.longOrNull,
                )
                "interrupted" -> LiveVoiceEvent.Interrupted
                "error" -> LiveVoiceEvent.Error(str("code") ?: "request.unknown", str("message").orEmpty())
                "closing" -> LiveVoiceEvent.Closing(str("reason") ?: "ended", str("message"), o["retryAfterSeconds"]?.jsonPrimitive?.longOrNull)
                else -> null
            }
        } catch (e: Exception) {
            Diagnostics.note("voice: frame skipped")
            null
        }
    }

    /** The guide says `arguments` is a JSON string; an object is taken as is, in case that changes. */
    private fun argumentsOf(e: JsonElement?): String = when (e) {
        null -> "{}"
        is JsonPrimitive -> e.contentOrNull ?: "{}"
        else -> e.toString()
    }
}
