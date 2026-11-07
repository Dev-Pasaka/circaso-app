package com.circaso.core.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

/** What each frame the server sends today means for the app. Everything else is ignored (new frame types arrive over time). */
class RealtimeDispatcher(
    private val frames: SharedFlow<RealtimeFrame>,
    private val scope: CoroutineScope,
    private val onSessionRevoked: suspend () -> Unit,
    private val refreshContacts: suspend () -> Unit,
    private val refreshBlocks: suspend () -> Unit,
    private val refreshOwnProfile: suspend () -> Unit,
    private val isOwnUser: suspend (userId: String) -> Boolean,
    private val onMessagingFrame: suspend (type: String, data: kotlinx.serialization.json.JsonObject?) -> Unit = { _, _ -> },
    private val reloadMessaging: suspend () -> Unit = {},
    private val onWalletFrame: suspend (type: String, data: kotlinx.serialization.json.JsonObject?) -> Unit = { _, _ -> },
    private val reloadWallet: suspend () -> Unit = {},
    private val onChamaFrame: suspend (type: String, data: kotlinx.serialization.json.JsonObject?) -> Unit = { _, _ -> },
    private val onCallsFrame: suspend (type: String, data: kotlinx.serialization.json.JsonObject?) -> Unit = { _, _ -> },
    /** Moments (`status.posted`, `status.deleted`, `status.viewed`); also called on its `resync` with type `resync`. */
    private val onStatusFrame: suspend (type: String, data: kotlinx.serialization.json.JsonObject?) -> Unit = { _, _ -> },
    /** `assistant` / `status` (2026-11-07): Asha is back from maintenance when `available` is true. */
    private val onAshaBack: suspend () -> Unit = {},
) {
    fun start() {
        scope.launch { frames.collect { handle(it) } }
    }

    internal suspend fun handle(frame: RealtimeFrame) {
        when {
            frame.module == "system" && frame.type == "session.revoked" -> onSessionRevoked()
            frame.type == "resync" -> when (frame.module) { // the gap is older than 7 days: reload that module over HTTP
                "messaging" -> reloadMessaging()
                "wallet" -> reloadWallet()
                "status" -> onStatusFrame("resync", null)
                "spaces" -> Unit // Spaces is archived (2026-11-05): an old server's frames are ignored
                else -> { refreshContacts(); refreshBlocks(); refreshOwnProfile() }
            }
            frame.module == "messaging" -> onMessagingFrame(frame.type, frame.data)
            frame.module == "wallet" -> onWalletFrame(frame.type, frame.data)
            frame.module == "chama" -> onChamaFrame(frame.type, frame.data)
            frame.module == "calls" -> onCallsFrame(frame.type, frame.data)
            frame.module == "status" -> onStatusFrame(frame.type, frame.data)
            frame.module == "assistant" && frame.type == "status" ->
                if (frame.data?.get("available")?.jsonPrimitive?.content == "true") onAshaBack()
            frame.module == "users" && frame.type in setOf("contact.joined", "connection.added") -> refreshContacts()
            frame.module == "users" && frame.type == "profile.updated" -> {
                val userId = frame.data?.get("userId")?.jsonPrimitive?.content
                if (userId != null && isOwnUser(userId)) refreshOwnProfile() else refreshContacts()
            }
        }
    }
}
