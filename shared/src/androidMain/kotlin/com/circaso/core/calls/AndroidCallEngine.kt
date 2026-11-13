package com.circaso.core.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.circaso.core.platform.PermissionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
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
    /** Feeds the video track while the camera is off; see [keepVideoAlive]. */
    private var keepAlive: Job? = null
    private val remote = mutableMapOf<String, VideoTrack>()
    private val _remoteMids = MutableStateFlow<Set<String>>(emptySet())
    override val remoteVideoMids: StateFlow<Set<String>> = _remoteMids
    private val _connected = MutableStateFlow<Boolean?>(null)
    override val connected: StateFlow<Boolean?> = _connected
    private val _receiving = MutableStateFlow<Set<String>>(emptySet())
    override val receivingMids: StateFlow<Set<String>> = _receiving
    private val statsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _links = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val linkStates: kotlinx.coroutines.flow.Flow<String> = _links
    private var statsJob: Job? = null
    private var previousMode = AudioManager.MODE_NORMAL

    /** The screen going out on the video track instead of the camera; see [setScreenShare]. */
    private var screen: ScreenCapturerAndroid? = null
    private var screenHelper: SurfaceTextureHelper? = null
    /** Whether the camera should be on once the share stops (it was on before, or was turned on during it). */
    private var cameraAfterShare = false
    private val _sharing = MutableStateFlow(false)
    override val screenSharing: StateFlow<Boolean> = _sharing
    override val canShareScreen = true
    private val mainHandler = Handler(Looper.getMainLooper())

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
        if (!cameraOn) keepVideoAlive(true)

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
        return synchronized(guard) {
            if (pc !== connection) throw CallEngineException("closed")
            watchReceiving(connection)
            connection.localDescription.description
        }
    }

    /**
     * Polls the inbound RTP stats while a pulled track hasn't had a packet yet, so the call only counts as connected
     * once the other side is really heard or seen. Quiet once every pulled track is receiving.
     */
    private fun watchReceiving(connection: PeerConnection) {
        if (statsJob?.isActive == true) return
        statsJob = statsScope.launch {
            while (isActive) {
                val pending = synchronized(guard) {
                    if (pc !== connection) return@launch
                    connection.transceivers.filter { !it.isStopped && (it.currentDirection ?: it.direction) == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY }.mapNotNull { it.mid }
                        .filter { it !in _receiving.value }
                        .let { mids -> if (mids.isEmpty()) null else connection.transceivers.associate { (it.receiver.track()?.id() ?: "") to it.mid } }
                }
                if (pending != null) {
                    val heard = suspendCancellableCoroutine<Set<String>> { cont ->
                        val ok = runCatching {
                            synchronized(guard) {
                                if (pc !== connection) throw CallEngineException("closed")
                                connection.getStats { report ->
                                    val mids = report.statsMap.values.filter { it.type == "inbound-rtp" }.mapNotNull { s ->
                                        val packets = (s.members["packetsReceived"] as? Number)?.toLong() ?: 0
                                        if (packets <= 0) null
                                        else (s.members["mid"] as? String) ?: pending[s.members["trackIdentifier"] as? String]
                                    }.toSet()
                                    if (cont.isActive) cont.resume(mids)
                                }
                            }
                        }.isSuccess
                        if (!ok && cont.isActive) cont.resume(emptySet())
                    }
                    if (heard.any { it !in _receiving.value }) _receiving.value = _receiving.value + heard
                }
                delay(300)
            }
        }
    }

    override suspend fun receivedPackets(): Long? {
        val connection = synchronized(guard) { pc } ?: return null
        return suspendCancellableCoroutine { cont ->
            val ok = runCatching {
                synchronized(guard) {
                    if (pc !== connection) throw CallEngineException("closed")
                    connection.getStats { report ->
                        val packets = report.statsMap.values
                            .filter { it.type == "inbound-rtp" && it.members["kind"] == "audio" }
                            .sumOf { (it.members["packetsReceived"] as? Number)?.toLong() ?: 0L }
                        if (cont.isActive) cont.resume(packets)
                    }
                }
            }.isSuccess
            if (!ok && cont.isActive) cont.resume(null)
        }
    }

    /** One reading for the call report: RTT from the nominated candidate pair, jitter and totals from inbound RTP. */
    override suspend fun stats(): CallStats? {
        val connection = synchronized(guard) { pc } ?: return null
        return suspendCancellableCoroutine { cont ->
            val ok = runCatching {
                synchronized(guard) {
                    if (pc !== connection) throw CallEngineException("closed")
                    connection.getStats { report ->
                        val all = report.statsMap.values
                        fun num(s: org.webrtc.RTCStats, key: String) = (s.members[key] as? Number)
                        val pair = all.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
                        val rtt = pair?.let { num(it, "currentRoundTripTime")?.toDouble() }
                            ?: all.firstOrNull { it.type == "remote-inbound-rtp" }?.let { num(it, "roundTripTime")?.toDouble() }
                        val inbound = all.filter { it.type == "inbound-rtp" }
                        val audioIn = inbound.filter { it.members["kind"] == "audio" }
                        val stats = CallStats(
                            rttMs = rtt?.times(1000),
                            jitterMs = audioIn.mapNotNull { num(it, "jitter")?.toDouble() }.maxOrNull()?.times(1000),
                            packetsReceived = audioIn.sumOf { num(it, "packetsReceived")?.toLong() ?: 0L },
                            packetsLost = audioIn.sumOf { num(it, "packetsLost")?.toLong() ?: 0L },
                            bytesReceived = inbound.sumOf { num(it, "bytesReceived")?.toLong() ?: 0L },
                        )
                        if (cont.isActive) cont.resume(stats)
                    }
                }
            }.isSuccess
            if (!ok && cont.isActive) cont.resume(null)
        }
    }

    override fun setMicrophone(on: Boolean) { audio?.setEnabled(on) }

    override suspend fun setCamera(on: Boolean): Boolean {
        if (on && !granted(Manifest.permission.CAMERA) && !PermissionBridge.request(Manifest.permission.CAMERA)) return false
        // The screen has the track: the camera takes it back, in this state, when the share stops.
        if (synchronized(guard) { screen != null }) { cameraAfterShare = on; return true }
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
            keepVideoAlive(false) // the camera takes over the track from here
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
        if (!on) keepVideoAlive(true)
    }

    /**
     * While the camera is off, the video track gets a tiny black frame twice a second. Cloudflare's SFU needs media on
     * a track: a pull of one with none fails after about 8.5 s (`empty_track_error`), and a track with no packets for
     * 30 s is deleted. Without this, a camera turned on mid-call was never seen by the other side, and every pull
     * waited 8.5 s for the silent video (2026-11-09). Receivers show the photo while `video` is false, never these.
     */
    private fun keepVideoAlive(on: Boolean) {
        keepAlive?.cancel()
        keepAlive = null
        if (!on) return
        val source = videoSource ?: return
        keepAlive = statsScope.launch {
            var started = false
            while (isActive) {
                synchronized(guard) {
                    if (videoSource !== source || cameraOn || screen != null) return@launch
                    if (!started) { source.capturerObserver.onCapturerStarted(true); started = true }
                    val buffer = JavaI420Buffer.allocate(KeepAliveWidth, KeepAliveHeight)
                    fill(buffer.dataY, 16)
                    fill(buffer.dataU, 128)
                    fill(buffer.dataV, 128)
                    val frame = VideoFrame(buffer, 0, System.nanoTime())
                    source.capturerObserver.onFrameCaptured(frame)
                    frame.release()
                }
                delay(KeepAliveFrameMillis)
            }
        }
    }

    private fun fill(plane: java.nio.ByteBuffer, value: Int) {
        val v = value.toByte()
        for (i in 0 until plane.capacity()) plane.put(i, v)
    }

    override fun flipCamera() { capturer?.switchCamera(null) }

    /**
     * Screen sharing (Pasaka, 2026-11-09): WebRTC's own ScreenCapturerAndroid feeds the call's existing video track,
     * exactly as the camera does, so nothing is renegotiated and the others keep the same track. Order matters on
     * Android 14+: the person's consent first, then the call's foreground service gains the mediaProjection type, and
     * only then is the projection made (the capturer makes it when it starts). Stopping, by the button, the system's
     * own Stop or the call ending, gives the track back to the camera (if it was on) or the black keep-alive.
     */
    override suspend fun setScreenShare(on: Boolean): Boolean {
        if (!on) { onMain { stopScreen(restore = true) }; return true }
        if (synchronized(guard) { screen != null }) return true
        if (synchronized(guard) { videoSource == null }) return false
        val consent = ScreenCaptureBridge.request(context) ?: return false
        if (!CallService.startProjection()) {
            android.util.Log.w("CallEngine", "screen share: the call service couldn't take the mediaProjection type")
            return false
        }
        val started = onMain { startScreen(consent) }
        if (!started) CallService.stopProjection()
        return started
    }

    private suspend fun <T> onMain(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.Main) { block() }

    private fun startScreen(consent: android.content.Intent): Boolean = synchronized(guard) {
        val source = videoSource ?: return@synchronized false
        cameraAfterShare = cameraOn
        if (cameraOn) cameraSwitch(false)
        val cap = ScreenCapturerAndroid(consent, object : MediaProjection.Callback() {
            // The system's Stop (status bar chip, the screen locking on some phones): the call follows.
            override fun onStop() { mainHandler.post { stopScreen(restore = true) } }
        })
        screen = cap
        keepVideoAlive(false)
        val helper = SurfaceTextureHelper.create("screen", egl.eglBaseContext)
        val ok = runCatching {
            cap.initialize(helper, context, source.capturerObserver)
            val (w, h) = screenSize()
            cap.startCapture(w, h, ScreenFps)
        }.onFailure { android.util.Log.w("CallEngine", "screen share didn't start", it) }.isSuccess
        if (!ok) {
            screen = null
            runCatching { cap.dispose() }
            helper.dispose()
            if (cameraAfterShare) cameraSwitch(true) else keepVideoAlive(true)
            return@synchronized false
        }
        screenHelper = helper
        localVideo?.setEnabled(true)
        _sharing.value = true
        true
    }

    /** Ends the share; with [restore] the camera (or the keep-alive) takes the track back, without it the call is closing. */
    private fun stopScreen(restore: Boolean) = synchronized(guard) {
        val cap = screen ?: return@synchronized
        screen = null
        runCatching { cap.stopCapture() }
        runCatching { cap.dispose() }
        screenHelper?.dispose()
        screenHelper = null
        _sharing.value = false
        CallService.stopProjection()
        if (restore && videoSource != null) {
            if (cameraAfterShare && granted(Manifest.permission.CAMERA)) cameraSwitch(true)
            if (!cameraOn) { localVideo?.setEnabled(false); keepVideoAlive(true) }
        }
        cameraAfterShare = false
    }

    /** The screen at about 720p (its long side at most [ScreenLongSide]), in its own shape, even for the encoder. */
    private fun screenSize(): Pair<Int, Int> {
        val m = context.resources.displayMetrics
        val long = maxOf(m.widthPixels, m.heightPixels).coerceAtLeast(1)
        val scale = minOf(1f, ScreenLongSide.toFloat() / long)
        fun even(v: Float) = (v.toInt() / 2 * 2).coerceAtLeast(2)
        return even(m.widthPixels * scale) to even(m.heightPixels * scale)
    }

    override fun setSpeaker(on: Boolean) = routeAudio(on)

    /** No WebRTC call runs while the connection is being torn down, and none after (see [sdp] and [set]). */
    private val guard = Any()

    override fun close() = synchronized(guard) {
        stopScreen(restore = false)
        cameraSwitch(false)
        keepVideoAlive(false)
        remote.clear()
        _remoteMids.value = emptySet()
        _connected.value = null
        statsJob?.cancel()
        statsJob = null
        _receiving.value = emptySet()
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
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            newState?.let { _links.tryEmit("pc:" + it.name.lowercase()) }
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            state?.let { _links.tryEmit("ice:" + it.name.lowercase()) }
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
    // A texture, not a SurfaceView, so the tile can be clipped, swapped and grown on the call's motion (CallTextureView).
    val renderer = remember(source) { mutableListOf<CallTextureView>() }
    AndroidView(
        factory = { ctx ->
            CallTextureView(ctx).apply {
                init(engine.egl.eglBaseContext)
                setFill(fill)
                renderer += this
            }
        },
        update = { it.setMirror(mirror); it.setFill(fill) },
        modifier = modifier,
    )
    DisposableEffect(track) {
        val view = renderer.firstOrNull()
        if (view != null) track?.addSink(view)
        onDispose { if (view != null) track?.removeSink(view) }
    }
    DisposableEffect(Unit) { onDispose { renderer.forEach { it.release() }; renderer.clear() } }
}

/** The black keep-alive frame and how often it is sent while the camera is off (see AndroidCallEngine.keepVideoAlive). */
private const val KeepAliveWidth = 160
private const val KeepAliveHeight = 90
private const val KeepAliveFrameMillis = 500L

/** A shared screen goes at about 720p and 15 frames a second: text stays sharp and the upload stays modest. */
private const val ScreenLongSide = 1280
private const val ScreenFps = 15
