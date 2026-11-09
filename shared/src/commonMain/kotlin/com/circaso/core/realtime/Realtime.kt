package com.circaso.core.realtime

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.cancel
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One message from the server: `{ module, type, id, seq, ts, data }`. `seq` rises per module; a gap means a missed frame. */
@Serializable
data class RealtimeFrame(
    val module: String? = null,
    val type: String,
    val id: String? = null,
    val seq: Long? = null,
    val ts: String? = null,
    val data: JsonObject? = null,
)

/** The text connection the client talks over. A tiny seam so reconnect and replay can be tested without a network. */
interface Socket {
    suspend fun send(text: String)
    val incoming: Flow<String>
    suspend fun close()
}

fun interface SocketFactory {
    suspend fun open(url: String): Socket
}

class KtorSocketFactory(private val client: HttpClient) : SocketFactory {
    override suspend fun open(url: String): Socket {
        val session = client.webSocketSession(urlString = url)
        return object : Socket {
            override suspend fun send(text: String) = session.send(Frame.Text(text))
            override val incoming: Flow<String> = session.incoming.receiveAsFlow().filterIsInstance<Frame.Text>().map { it.readText() }
            // The close frame, then the connection itself, so nothing is left open behind a socket given up.
            override suspend fun close() {
                try { session.close() } finally { session.cancel() }
            }
        }
    }
}

/** `https://host` becomes `wss://host/v1/ws`. */
fun realtimeUrl(baseUrl: String): String = baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/v1/ws"
