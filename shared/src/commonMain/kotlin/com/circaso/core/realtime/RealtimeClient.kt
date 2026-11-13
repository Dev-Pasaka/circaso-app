package com.circaso.core.realtime

import kotlinx.coroutines.launch
import com.circaso.core.common.Diagnostics
import com.circaso.core.network.AccessTokenProvider
import kotlinx.coroutines.CancellationException
import com.circaso.core.sync.nowMillis
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where the last sequence number per module is kept (DataStore in the app). */
interface RealtimeCursors {
    suspend fun lastSeq(module: String): Long
    suspend fun setLastSeq(module: String, seq: Long)
    suspend fun clear()
}

/** Modules the server sequences today. */
private val KnownModules = listOf("users", "messaging", "wallet", "chama", "calls", "status")

/** What a finished connection tells the supervisor. */
enum class ConnectionEnd { Closed, Unauthorized, Failed }

/**
 * One WebSocket connection. The first frame authenticates with the app token and the last sequence number per
 * module, so the server replays what was missed. Frames are published to [frames] after their sequence number is
 * saved; a number that does not rise (a duplicate from replay) is dropped. A `resync` frame means the gap is older
 * than seven days: the saved number for that module is reset and the frame is published so the module reloads over HTTP.
 * The socket is an optimisation: the app works without it.
 */
class RealtimeClient(
    private val sockets: SocketFactory,
    private val url: String,
    private val tokens: AccessTokenProvider,
    private val cursors: RealtimeCursors,
    private val json: Json,
) {
    private val _frames = MutableSharedFlow<RealtimeFrame>(extraBufferCapacity = 64)
    val frames: SharedFlow<RealtimeFrame> = _frames.asSharedFlow()

    /** True while a socket is open and authenticated as far as we know; features sync when it turns true. */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var current: Socket? = null
    private val sendLock = Mutex()
    private var windowStart = 0L
    private var sentInWindow = 0

    suspend fun run(): ConnectionEnd {
        val token = tokens.accessToken() ?: return ConnectionEnd.Unauthorized
        val socket = try { sockets.open(url) } catch (e: CancellationException) { throw e } catch (e: Throwable) { Diagnostics.note("socket: could not open"); return ConnectionEnd.Failed }
        Diagnostics.note("socket: open")
        return try {
            socket.send(json.encodeToString(JsonObject.serializer(), authFrame(token)))
            current = socket
            _connected.value = true
            // The server sends {"type":"ping"} every 25 s. Silence for longer than two of those means the network went
            // away under a socket that never noticed (a half-open connection): give it up and reconnect.
            var lastFrame = com.circaso.core.sync.nowMillis()
            kotlinx.coroutines.coroutineScope {
                val watchdog = launch {
                    while (true) {
                        kotlinx.coroutines.delay(IdleCheckMillis)
                        if (com.circaso.core.sync.nowMillis() - lastFrame > IdleLimitMillis) {
                            Diagnostics.note("socket: no frame for ${IdleLimitMillis}ms, giving it up")
                            throw StaleSocket()
                        }
                    }
                }
                socket.incoming.collect { text -> lastFrame = com.circaso.core.sync.nowMillis(); handle(text) }
                watchdog.cancel()
            }
            Diagnostics.note("socket: closed by server")
            ConnectionEnd.Closed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Diagnostics.note("socket: failed (${e::class.simpleName})")
            ConnectionEnd.Failed
        } finally {
            current = null
            _connected.value = false
            // NonCancellable: this runs when the supervisor cancels the loop (the network flapped, sign-out), and a
            // suspending close in a cancelled coroutine returns at once without sending anything. The socket then
            // stayed open with nobody reading it, so its pings went unanswered: the server saw a second socket for
            // the phone and timed the first out ~15-40 s later ("Ping timeout", 1011), and any frame sent to it in
            // between (a call.ended) was lost (2026-11-09).
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.withTimeoutOrNull(CloseMillis) { runCatching { socket.close() } }
            }
        }
    }

    /**
     * Sends a frame the phone may send (`ack`, `read`, `typing`; guide section 8). False when there is no connection
     * or the frame would break a limit: the caller then uses HTTP (acks) or drops it (typing). At most 100 frames a
     * second and 64 KB a frame; over the rate the server drops frames silently, so they are not sent at all.
     */
    suspend fun send(module: String, type: String, data: JsonObject): Boolean = sendLock.withLock {
        val socket = current ?: return false
        val text = json.encodeToString(JsonObject.serializer(), buildJsonObject { put("module", module); put("type", type); put("data", data) })
        if (text.length > MaxFrameChars) return false
        val now = nowMillis()
        if (now - windowStart >= 1_000) { windowStart = now; sentInWindow = 0 }
        if (sentInWindow >= MaxFramesPerSecond) return false
        sentInWindow++
        try { socket.send(text); true } catch (e: CancellationException) { throw e } catch (e: Throwable) { false }
    }

    private suspend fun authFrame(token: String): JsonObject {
        val last = buildJsonArray {
            KnownModules.forEach { m -> cursors.lastSeq(m).takeIf { it > 0 }?.let { seq -> add(buildJsonObject { put("module", m); put("seq", seq) }) } }
        }
        return buildJsonObject {
            put("type", "auth")
            put("accessToken", token)
            put("last", last)
        }
    }

    private suspend fun handle(text: String) {
        val frame = try { json.decodeFromString(RealtimeFrame.serializer(), text) } catch (e: Exception) { return } // not ours to guess at
        Diagnostics.note("socket: frame ${frame.type}")
        val module = frame.module
        if (frame.type == "resync" && module != null) {
            cursors.setLastSeq(module, 0)
            _frames.emit(frame)
            return
        }
        val seq = frame.seq
        // `message.new` and `typing` carry seq 0 and are never replayed: pass them on without touching the saved number.
        if (module != null && seq != null && seq > 0) {
            if (seq <= cursors.lastSeq(module)) return // already seen
            cursors.setLastSeq(module, seq)
        }
        _frames.emit(frame)
    }

    private companion object {
        const val MaxFramesPerSecond = 100
        const val MaxFrameChars = 60_000 // under the server's 64 KB with room for multi-byte characters
    }
}

/** The socket went quiet past [IdleLimitMillis]: treated as dropped. */
private class StaleSocket : Exception("no frame from the server")

private const val IdleCheckMillis = 10_000L

/** How long closing a socket may take before it is abandoned (a dead network never answers the close). */
private const val CloseMillis = 2_000L
private const val IdleLimitMillis = 70_000L
