package com.circaso.core.calls

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import platform.UIKit.UIView
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google WebRTC lives on the Swift side (`CallEngineImpl.swift`, the stasel/WebRTC package), installed at launch like
 * the other bridges. Offers and track lists cross as JSON so the bridge needs only strings.
 */
interface CallEngineProvider {
    /** [completion]: the offer as `{"sdp": "...", "tracks": [{"mid","trackName","kind"}]}`, or null and why. */
    fun start(iceServersJson: String, video: Boolean, userId: String, completion: (String?, String?) -> Unit)
    fun acceptAnswer(sdp: String, completion: (String?) -> Unit)
    fun answerOffer(sdp: String, completion: (String?, String?) -> Unit)
    fun setMicrophone(on: Boolean)
    /** [completion] says whether the camera is now in the state asked for (false: permission refused). */
    fun setCamera(on: Boolean, completion: (Boolean) -> Unit)
    fun flipCamera()
    fun setSpeaker(on: Boolean)
    fun close()
    /** A renderer for `"local"` or a pulled track's mid. */
    fun videoView(source: String, mirror: Boolean, fill: Boolean): UIView
    /** Kotlin sets this to hear which pulled video mids exist. */
    fun setRemoteVideoListener(listener: (List<String>) -> Unit)
    /** Kotlin sets this to hear the media connection: 1 up, 0 dropped or failed. */
    fun setConnectionListener(listener: (Int) -> Unit)
}

object CallEngineBridge {
    var provider: CallEngineProvider? = null
}

@Serializable private data class WireIce(val urls: List<String>, val username: String? = null, val credential: String? = null)
@Serializable private data class WireTrack(val mid: String, val trackName: String, val kind: String)
@Serializable private data class WireOffer(val sdp: String, val tracks: List<WireTrack>)

class IosCallEngine : CallEngine {
    private val json = Json { ignoreUnknownKeys = true }
    private val _mids = MutableStateFlow<Set<String>>(emptySet())
    override val remoteVideoMids: StateFlow<Set<String>> = _mids
    private val _connected = MutableStateFlow<Boolean?>(null)
    override val connected: StateFlow<Boolean?> = _connected
    override val available: Boolean get() = CallEngineBridge.provider != null
    private val p get() = CallEngineBridge.provider ?: throw CallEngineException("unavailable")

    override suspend fun start(iceServers: List<IceServerSpec>, video: Boolean, userId: String): LocalOffer {
        val provider = p
        provider.setRemoteVideoListener { _mids.value = it.toSet() }
        provider.setConnectionListener { _connected.value = it == 1 }
        val ice = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(WireIce.serializer()), iceServers.map { WireIce(it.urls, it.username, it.credential) })
        val raw = suspendCancellableCoroutine<String> { cont ->
            provider.start(ice, video, userId) { offer, error ->
                if (!cont.isActive) return@start
                if (offer != null) cont.resume(offer) else cont.resumeWithException(CallEngineException(error ?: "start"))
            }
        }
        val o = json.decodeFromString(WireOffer.serializer(), raw)
        return LocalOffer(o.sdp, o.tracks.map { LocalTrackSpec(it.mid, it.trackName, it.kind) })
    }

    override suspend fun acceptAnswer(sdp: String) = suspendCancellableCoroutine<Unit> { cont ->
        p.acceptAnswer(sdp) { error -> if (cont.isActive) { if (error == null) cont.resume(Unit) else cont.resumeWithException(CallEngineException(error)) } }
    }

    override suspend fun answerOffer(sdp: String): String = suspendCancellableCoroutine { cont ->
        p.answerOffer(sdp) { answer, error -> if (cont.isActive) { if (answer != null) cont.resume(answer) else cont.resumeWithException(CallEngineException(error ?: "answer")) } }
    }

    override fun setMicrophone(on: Boolean) { CallEngineBridge.provider?.setMicrophone(on) }
    override suspend fun setCamera(on: Boolean): Boolean {
        val p = CallEngineBridge.provider ?: return !on
        return suspendCancellableCoroutine { cont -> p.setCamera(on) { ok -> if (cont.isActive) cont.resume(ok) } }
    }
    override fun flipCamera() { CallEngineBridge.provider?.flipCamera() }
    override fun setSpeaker(on: Boolean) { CallEngineBridge.provider?.setSpeaker(on) }
    override fun close() { CallEngineBridge.provider?.close(); _mids.value = emptySet(); _connected.value = null }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@Composable
actual fun CallVideo(source: VideoSource, modifier: Modifier, mirror: Boolean, fill: Boolean) {
    val engine = koinInject<CallEngine>()
    val provider = CallEngineBridge.provider ?: return
    val mids by engine.remoteVideoMids.collectAsState()
    val id = when (source) { VideoSource.Local -> "local"; is VideoSource.Remote -> source.mid }
    val ready = source is VideoSource.Local || id in mids
    // A new view once the track exists, so the renderer attaches to it.
    key(id, ready, mirror, fill) {
        UIKitView(factory = { provider.videoView(id, mirror, fill) }, modifier = modifier)
    }
}
