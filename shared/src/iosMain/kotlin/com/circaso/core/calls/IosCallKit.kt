package com.circaso.core.calls

import com.circaso.core.platform.PushTokenProvider
import com.circaso.feature.calls.CallController
import com.circaso.feature.calls.CallPhase
import com.circaso.feature.calls.data.CallsRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.koin.mp.KoinPlatform

/**
 * The Swift side of incoming calls on iOS (`CallKitVoip.swift`): PushKit wakes Circaso for a ring even when it's
 * closed, and CallKit shows the system's full-screen incoming call. Swift reports what CallKit decides; Kotlin tells
 * Swift when a call ended or was answered in the app, so the system's call follows.
 */
interface CallKitProvider {
    /** The call ended (hung up, declined in the app, cancelled by the caller, missed). */
    fun reportEnded(callId: String)
    /** The call was answered from Circaso's own screen: CallKit marks it answered and hands over the audio. */
    fun reportAnswered(callId: String)
}

object IosCallKitBridge {
    var provider: CallKitProvider? = null

    /** This phone's PushKit (VoIP) token, hex; registered with the server by [voipRegistrar]. */
    internal val voipToken = MutableStateFlow<String?>(null)
    private val koin get() = KoinPlatform.getKoin()
    private val scope get() = koin.get<CoroutineScope>()
    private val controller get() = koin.get<CallController>()
    private var watching = false

    /** Set by the voip registrar (IosKoin): a token PushKit hands over later is registered at once. */
    internal var onToken: ((String) -> Unit)? = null

    fun tokenUpdated(hex: String) {
        voipToken.value = hex
        onToken?.invoke(hex)
    }

    /** A VoIP push rang this phone and CallKit is showing it: load the call so answering is instant. */
    fun incoming(callId: String) {
        watch()
        scope.launch { load(callId) }
    }

    /** Answer on the system's call screen. */
    fun answer(callId: String) {
        watch()
        scope.launch {
            load(callId)
            val s = controller.state.value
            if (s?.callId == callId && s.phase == CallPhase.Incoming) controller.accept()
        }
    }

    /**
     * The server's "ended" VoIP push stopped CallKit's ring for [callId] (Swift already ended the system's call): stop
     * the app's ringing state too, without declining (there is nothing left to decline).
     */
    fun ringStopped(callId: String) {
        scope.launch {
            controller.onFrame(
                "call.ring_stopped",
                buildJsonObject {
                    put("callId", JsonPrimitive(callId))
                    put("reason", JsonPrimitive("CANCELLED"))
                },
            )
        }
    }

    /** Decline or hang up on the system's call screen. */
    fun end(callId: String) {
        scope.launch {
            val s = controller.state.value
            when {
                s?.callId != callId || s.phase == CallPhase.Ended -> koin.get<CallsRemote>().decline(callId)
                s.phase == CallPhase.Incoming -> controller.decline()
                else -> controller.leave()
            }
        }
    }

    private suspend fun load(callId: String) {
        if (controller.state.value?.callId == callId) return
        controller.onFrame("call.ringing", buildJsonObject { put("callId", JsonPrimitive(callId)) })
    }

    /** Keeps CallKit in step with the app's call: answered in the app, or over. */
    private fun watch() {
        if (watching) return
        watching = true
        scope.launch {
            var current: String? = null
            var answered = false
            controller.state.collect { s ->
                val id = s?.callId
                if (current != null && (id != current || s?.phase == CallPhase.Ended)) {
                    provider?.reportEnded(current!!)
                    current = null
                }
                if (id != null && s.phase != CallPhase.Ended && current == null) { current = id; answered = false }
                if (id != null && id == current && !answered && s.phase != CallPhase.Incoming && s.phase != CallPhase.Ended) {
                    answered = true
                    provider?.reportAnswered(id)
                }
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class VoipTokenRequest(val voipToken: String)

/** The PushKit token for the voip registrar; waits a little for PushKit to hand it over at launch. */
internal object IosVoipTokenProvider : PushTokenProvider {
    override suspend fun token(): String? = withTimeoutOrNull(15_000) { IosCallKitBridge.voipToken.filterNotNull().first() }
}
