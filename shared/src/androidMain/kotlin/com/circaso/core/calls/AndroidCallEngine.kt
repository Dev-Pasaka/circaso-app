package com.circaso.core.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.circaso.core.platform.PermissionBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.compose.koinInject
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource as RtcVideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Google WebRTC (Stream's maintained build): one peer connection to Cloudflare's SFU, hardware echo cancelling. */
class AndroidCallEngine(private val context: Context) : CallEngine {
    override val available = true

    val egl: EglBase by lazy { EglBase.create() }
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(context).setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true).createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private var pc: PeerConnection? = null
    private var audio: AudioTrack? = null
    private var videoSource: RtcVideoSource? = null
    var localVideo: VideoTrack? = null
        private set
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var cameraOn = false
    private val remote = mutableMapOf<String, VideoTrack>()
    private val _remoteMids = MutableStateFlow<Set<String>>(emptySet())
    override val remoteVideoMids: StateFlow<Set<String>> = _remoteMids
    private val _connected = MutableStateFlow<Boolean?>(null)
    override val connected: StateFlow<Boolean?> = _connected
    private var previousMode = AudioManager.MODE_NORMAL

    fun remoteTrack(mid: String): VideoTrack? = remote[mid]

    override suspend fun start(iceServers: List<IceServerSpec>, video: Boolean, userId: String): LocalOffer {
        if (!granted(Manifest.permission.RECORD_AUDIO) && !PermissionBridge.request(Manifest.permission.RECORD_AUDIO)) throw CallEngineException("microphone")
        if (video && !granted(Manifest.permission.CAMERA)) PermissionBridge.request(Manifest.permission.CAMERA)
        close()
        routeAudio(speaker = video)
        val config = PeerConnection.RTCConfiguration(
            iceServers.map { s -> PeerConnection.IceServer.builder(s.urls).apply { s.username?.let(::setUsername); s.credential?.let(::setPassword) }.createIceServer() },
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }
        val connection = factory.createPeerConnection(config, observer) ?: throw CallEngineException("connection")
        pc = connection
        val mic = factory.createAudioTrack("audio-$userId", factory.createAudioSource(MediaConstraints()))
        audio = mic
        val audioT = connection.addTransceiver(mic, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
        val source = factory.createVideoSource(false)
        videoSource = source
        val cam = factory.createVideoTrack("video-$userId", source)
        cam.setEnabled(false)
        localVideo = cam
        val videoT = connection.addTransceiver(cam, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
        if (video) cameraSwitch(granted(Manifest.permission.CAMERA))

        val offer = sdp { o -> live(connection) { connection.createOffer(o, MediaConstraints()) } }
        set { o -> live(connection) { connection.setLocalDescription(o, offer) } }
        return LocalOffer(
            connection.localDescription.description,
            listOf(LocalTrackSpec(audioT.mid, "audio-$userId", "audio"), LocalTrackSpec(videoT.mid, "video-$userId", "video")),
        )
    }

    override suspend fun acceptAnswer(sdp: String) {
        val connection = pc ?: return
        set { o -> live(connection) { connection.setRemoteDescription(o, SessionDescription(SessionDescription.Type.ANSWER, sdp)) } }
    }

    override suspend fun answerOffer(sdp: String): String {
        val connection = pc ?: throw CallEngineException("closed")
        set { o -> live(connection) { connection.setRemoteDescription(o, SessionDescription(SessionDescription.Type.OFFER, sdp)) } }
        val answer = sdp { o -> live(connection) { connection.createAnswer(o, MediaConstraints()) } }
        set { o -> live(connection) { connection.setLocalDescription(o, answer) } }
        return synchronized(guard) { if (pc !== connection) throw CallEngineException("closed"); connection.localDescription.description }
    }

    override fun setMicrophone(on: Boolean) { audio?.setEnabled(on) }

    override suspend fun setCamera(on: Boolean): Boolean {
        if (on && !granted(Manifest.permission.CAMERA) && !PermissionBridge.request(Manifest.permission.CAMERA)) return false
        cameraSwitch(on)
        return !on || cameraOn
    }

    private fun cameraSwitch(on: Boolean) {
        if (on == cameraOn) return
        if (on) {
            if (!granted(Manifest.permission.CAMERA)) return
            val source = videoSource ?: return
            val enumerator = Camera2Enumerator(context)
            val name = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing) ?: enumerator.deviceNames.firstOrNull() ?: return
            val cap = enumerator.createCapturer(name, null)
            val helper = SurfaceTextureHelper.create("camera", egl.eglBaseContext)
            cap.initialize(helper, context, source.capturerObserver)
            cap.startCapture(1280, 720, 30)
            capturer = cap
            surfaceHelper = helper
            localVideo?.setEnabled(true)
        } else {
            localVideo?.setEnabled(false)
            runCatching { capturer?.stopCapture() }
            capturer?.dispose()
            surfaceHelper?.dispose()
            capturer = null
            surfaceHelper = null
        }
        cameraOn = on
    }

    override fun flipCamera() { capturer?.switchCamera(null) }

    override fun setSpeaker(on: Boolean) = routeAudio(on)

    /** No WebRTC call runs while the connection is being torn down, and none after (see [sdp] and [set]). */
    private val guard = Any()

    override fun close() = synchronized(guard) {
        cameraSwitch(false)
        remote.clear()
        _remoteMids.value = emptySet()
        _connected.value = null
        runCatching { pc?.close() }
        pc?.dispose()
        pc = null
        audio = null
        localVideo = null
        videoSource?.dispose()
        videoSource = null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            am.mode = previousMode
        }
    }

    /** The call's audio mode (echo cancelling on), earpiece or loudspeaker; a headset wins when one is plugged in. */
    private fun routeAudio(speaker: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.mode != AudioManager.MODE_IN_COMMUNICATION) { previousMode = am.mode; am.mode = AudioManager.MODE_IN_COMMUNICATION }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = am.availableCommunicationDevices
            val headset = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            val target = headset ?: devices.firstOrNull { it.type == if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            target?.let(am::setCommunicationDevice)
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = speaker
        }
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private val observer = object : PeerConnection.Observer {
        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track() as? VideoTrack ?: return
            val mid = transceiver.mid ?: return
            remote[mid] = track
            _remoteMids.value = remote.keys.toSet()
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            _connected.value = when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> true
                PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.FAILED -> false
                else -> _connected.value
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    }

    /** Runs [block] against the connection only if it is still the current one; otherwise the call has ended. */
    private fun live(connection: PeerConnection?, block: () -> Unit) = synchronized(guard) {
        if (connection == null || pc !== connection) throw CallEngineException("closed")
        block()
    }

    private suspend fun sdp(block: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { cont ->
        block(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { if (cont.isActive) cont.resume(d) }
            override fun onCreateFailure(e: String?) { if (cont.isActive) cont.resumeWithException(CallEngineException(e ?: "sdp")) }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(e: String?) = Unit
        })
    }

    private suspend fun set(block: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
        block(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) = Unit
            override fun onCreateFailure(e: String?) = Unit
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(e: String?) { if (cont.isActive) cont.resumeWithException(CallEngineException(e ?: "sdp")) }
        })
    }
}

@Composable
actual fun CallVideo(source: VideoSource, modifier: Modifier, mirror: Boolean, fill: Boolean) {
    val engine = koinInject<CallEngine>() as? AndroidCallEngine ?: return
    // Recompose when a pulled track arrives, so a tile that was waiting for it picks it up.
    val mids by engine.remoteVideoMids.collectAsState()
    val track = when (source) { VideoSource.Local -> engine.localVideo; is VideoSource.Remote -> if (source.mid in mids) engine.remoteTrack(source.mid) else null }
    val renderer = remember(source) { mutableListOf<SurfaceViewRenderer>() }
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(engine.egl.eglBaseContext, null)
                setScalingType(if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                renderer += this
            }
        },
        update = { it.setMirror(mirror) },
        modifier = modifier,
    )
    DisposableEffect(track) {
        val view = renderer.firstOrNull()
        if (view != null) track?.addSink(view)
        onDispose { if (view != null) track?.removeSink(view) }
    }
    DisposableEffect(Unit) { onDispose { renderer.forEach { it.release() }; renderer.clear() } }
}
