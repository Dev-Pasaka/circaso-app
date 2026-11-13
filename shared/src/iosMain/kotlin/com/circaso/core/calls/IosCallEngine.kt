package com.circaso.core.calls

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.viewinterop.UIKitView
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoTheme
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import platform.CoreGraphics.CGAffineTransformIdentity
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGRectZero
import platform.QuartzCore.CATransaction
import platform.darwin.dispatch_time
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_after
import platform.darwin.NSEC_PER_SEC
import platform.darwin.DISPATCH_TIME_NOW
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIBlurEffect
import platform.UIKit.UIBlurEffectStyle
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAnimationOptionAllowUserInteraction
import platform.UIKit.UIViewAnimationOptionCurveEaseIn
import platform.UIKit.UIViewAnimationOptionCurveEaseOut
import platform.UIKit.UIViewAnimationOptionTransitionCrossDissolve
import platform.UIKit.UIVisualEffectView
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
    /** Kotlin sets this to hear which pulled mids have had packets (see [CallEngine.receivingMids]). */
    fun setReceivingListener(listener: (List<String>) -> Unit)
    /** [completion]: audio packets received over every pulled track so far, or -1 when there is no connection. */
    fun receivedPackets(completion: (Long) -> Unit)
    /**
     * [completion]: one stats reading for the call report as JSON (`rttMs`, `jitterMs`, `packetsReceived`,
     * `packetsLost`, `bytesReceived`, each optional), or null without a connection.
     */
    fun stats(completion: (String?) -> Unit)
    /** Kotlin sets this to hear every ICE and peer connection state change, as `ice:<state>` / `pc:<state>`. */
    fun setLinkListener(listener: (String) -> Unit)
    /**
     * Builds the connection and its offer ahead of the call (see [CallEngine.prepare]): no audio session, camera or
     * permission prompt. [completion] once done (null), or why not.
     */
    fun prepare(iceServersJson: String, video: Boolean, userId: String, completion: (String?) -> Unit)
    /** [completion]: a liveness reading as JSON (`{"mids":{"2":123},"transport":456}`), or null without a connection. */
    fun link(completion: (String?) -> Unit)
}

object CallEngineBridge {
    var provider: CallEngineProvider? = null
}

/**
 * Screen sharing on iOS (2026-11-09): a ReplayKit Broadcast Upload Extension captures the whole screen, also after the
 * person leaves Circaso, and hands its frames to the app, which feeds them into the call's video track
 * (`ScreenShareImpl.swift`, `iosApp/CircasoBroadcast`). Kept apart from [CallEngineProvider] so the call engine's
 * Swift side needs nothing new. Until the extension is a target in the Xcode project [available] is false and the
 * Share screen button isn't shown.
 */
interface ScreenShareProvider {
    /** The broadcast extension is in the app and the App Group is there: sharing can work. */
    val available: Boolean
    /** Shows the system's broadcast picker; [completion] says whether the broadcast started (false: dismissed, failed). */
    fun start(completion: (Boolean) -> Unit)
    /** Ends the broadcast (the extension is asked to finish) and gives the track back to the camera or keep-alive. */
    fun stop()
    /** Kotlin sets this to hear the broadcast end on its own (the red status bar's Stop, Control Center). */
    fun setStoppedListener(listener: () -> Unit)
}

object ScreenShareBridge {
    var provider: ScreenShareProvider? = null
}

@Serializable private data class WireIce(val urls: List<String>, val username: String? = null, val credential: String? = null)
@Serializable private data class WireTrack(val mid: String, val trackName: String, val kind: String)
@Serializable private data class WireOffer(val sdp: String, val tracks: List<WireTrack>)
@Serializable private data class WireLink(val mids: Map<String, Long> = emptyMap(), val transport: Long? = null)
@Serializable private data class WireStats(val rttMs: Double? = null, val jitterMs: Double? = null, val packetsReceived: Long? = null, val packetsLost: Long? = null, val bytesReceived: Long? = null)

class IosCallEngine : CallEngine {
    private val json = Json { ignoreUnknownKeys = true }
    private val _mids = MutableStateFlow<Set<String>>(emptySet())
    override val remoteVideoMids: StateFlow<Set<String>> = _mids
    private val _connected = MutableStateFlow<Boolean?>(null)
    override val connected: StateFlow<Boolean?> = _connected
    private val _receiving = MutableStateFlow<Set<String>>(emptySet())
    override val receivingMids: StateFlow<Set<String>> = _receiving
    private val _links = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val linkStates: kotlinx.coroutines.flow.Flow<String> = _links
    override val available: Boolean get() = CallEngineBridge.provider != null
    private val p get() = CallEngineBridge.provider ?: throw CallEngineException("unavailable")

    private fun listen(provider: CallEngineProvider) {
        provider.setRemoteVideoListener { _mids.value = it.toSet() }
        provider.setConnectionListener { _connected.value = it == 1 }
        provider.setReceivingListener { _receiving.value = it.toSet() }
        provider.setLinkListener { _links.tryEmit(it) }
    }

    private fun iceJson(iceServers: List<IceServerSpec>) =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(WireIce.serializer()), iceServers.map { WireIce(it.urls, it.username, it.credential) })

    override suspend fun prepare(iceServers: List<IceServerSpec>, video: Boolean, userId: String) {
        val provider = CallEngineBridge.provider ?: return
        listen(provider)
        suspendCancellableCoroutine<Unit> { cont -> provider.prepare(iceJson(iceServers), video, userId) { if (cont.isActive) cont.resume(Unit) } }
    }

    override suspend fun link(): LinkReading? {
        val p = CallEngineBridge.provider ?: return null
        val raw = suspendCancellableCoroutine<String?> { cont -> p.link { s -> if (cont.isActive) cont.resume(s) } } ?: return null
        val w = runCatching { json.decodeFromString(WireLink.serializer(), raw) }.getOrNull() ?: return null
        return LinkReading(w.mids, w.transport)
    }

    override suspend fun start(iceServers: List<IceServerSpec>, video: Boolean, userId: String): LocalOffer {
        val provider = p
        listen(provider)
        val ice = iceJson(iceServers)
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
    private val _sharing = MutableStateFlow(false)
    override val screenSharing: StateFlow<Boolean> = _sharing
    override val canShareScreen: Boolean get() = ScreenShareBridge.provider?.available == true

    override suspend fun setScreenShare(on: Boolean): Boolean {
        val share = ScreenShareBridge.provider ?: return !on
        if (!on) { share.stop(); _sharing.value = false; return true }
        if (!share.available) return false
        share.setStoppedListener { _sharing.value = false }
        val started = suspendCancellableCoroutine<Boolean> { cont -> share.start { ok -> if (cont.isActive) cont.resume(ok) } }
        _sharing.value = started
        return started
    }

    override suspend fun receivedPackets(): Long? {
        val p = CallEngineBridge.provider ?: return null
        return suspendCancellableCoroutine { cont -> p.receivedPackets { n -> if (cont.isActive) cont.resume(n.takeIf { it >= 0 }) } }
    }

    override suspend fun stats(): CallStats? {
        val p = CallEngineBridge.provider ?: return null
        val raw = suspendCancellableCoroutine<String?> { cont -> p.stats { s -> if (cont.isActive) cont.resume(s) } } ?: return null
        val w = runCatching { json.decodeFromString(WireStats.serializer(), raw) }.getOrNull() ?: return null
        return CallStats(w.rttMs, w.jitterMs, w.packetsReceived, w.packetsLost, w.bytesReceived)
    }

    override fun close() {
        if (_sharing.value) { ScreenShareBridge.provider?.stop(); _sharing.value = false }
        CallEngineBridge.provider?.close(); _mids.value = emptySet(); _connected.value = null; _receiving.value = emptySet()
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@Composable
actual fun CallVideo(source: VideoSource, modifier: Modifier, mirror: Boolean, fill: Boolean) {
    val engine = koinInject<CallEngine>()
    val provider = CallEngineBridge.provider ?: return
    val mids by engine.remoteVideoMids.collectAsState()
    val id = when (source) { VideoSource.Local -> "local"; is VideoSource.Remote -> source.mid }
    val ready = source is VideoSource.Local || id in mids
    val clip = LocalVideoClip.current
    val still = CircasoTheme.reduceMotion
    // A new view once the track exists, so the renderer attaches to it. Not on [mirror]: a flip turns the picture
    // over in place (VideoTileView.setMirror) instead of swapping in a new view.
    key(id, ready, fill) {
        val tile = remember { VideoTileView(provider.videoView(id, mirror, fill), mirror) }
        UIKitView(
            factory = { tile },
            // Read while drawing, so the native crop follows a tile through a swap frame by frame.
            modifier = modifier.drawBehind { tile.setClip(clip?.invoke(), size) },
            update = { it.setMirror(mirror, still) },
        )
    }
}

/**
 * A call tile on iOS: the renderer inside a view cut to the tile's rounded crop. Compose's clip never reaches a UIKit
 * view (it is only cut to its bounding rectangle), so on its own the self-view's square corners showed around the
 * rounded picture and its hairline (2026-11-13). The crop is kept as fractions of the bounds, so it stays right
 * whatever size and scale Compose gives the view, laid out with it in the same pass. Hidden until its first crop, so
 * it never shows square for a frame.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private class VideoTileView(private val video: UIView, private var mirrored: Boolean) : UIView(frame = CGRectZero.readValue()) {
    private val crop = UIView(frame = CGRectZero.readValue())
    /** The blur over the last frame while the other camera starts (see [setMirror]). */
    private var veil: UIVisualEffectView? = null
    private var left = 0.0
    private var top = 0.0
    private var width = 1.0
    private var height = 1.0
    /** Corner radius as a fraction of the tile's width (the tile is only ever scaled evenly). */
    private var radius = 0.0
    private var cropped = false
    /** Bumped on every flip: a later flip takes over and the earlier one's steps stand down. */
    private var flip = 0
    private var turned = false
    private var cameraReady = false
    private var cameraObserver: Any? = null

    init {
        backgroundColor = UIColor.clearColor
        setOpaque(false)
        crop.backgroundColor = UIColor.clearColor
        crop.setOpaque(false)
        crop.clipsToBounds = true
        crop.setHidden(true)
        video.backgroundColor = UIColor.clearColor
        video.transform = mirrorTransform(mirrored)
        crop.addSubview(video)
        addSubview(crop)
    }

    fun setClip(clip: VideoClip?, size: Size) {
        if (size.width <= 0f || size.height <= 0f) return
        val l = (clip?.rect?.left ?: 0f) / size.width.toDouble()
        val t = (clip?.rect?.top ?: 0f) / size.height.toDouble()
        val w = (clip?.rect?.width ?: size.width) / size.width.toDouble()
        val h = (clip?.rect?.height ?: size.height) / size.height.toDouble()
        val r = (clip?.radius ?: 0f) / size.width.toDouble()
        if (cropped && l == left && t == top && w == width && h == height && r == radius) return
        left = l; top = t; width = w; height = h; radius = r
        setNeedsLayout()
        layoutIfNeeded()
        if (!cropped) { cropped = true; crop.setHidden(false) }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val (bw, bh) = bounds.useContents { size.width to size.height }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        // Bounds and centre, not frame: the crop turns (a transform) on a flip, the picture mirrors.
        crop.setBounds(CGRectMake(0.0, 0.0, width * bw, height * bh))
        crop.setCenter(CGPointMake((left + width / 2) * bw, (top + height / 2) * bh))
        crop.layer.cornerRadius = radius * bw
        veil?.setFrame(crop.bounds)
        // The picture keeps the tile's full size and place; only the crop moves over it.
        video.setBounds(CGRectMake(0.0, 0.0, bw, bh))
        video.setCenter(CGPointMake(bw / 2 - left * bw, bh / 2 - top * bh))
        CATransaction.commit()
    }

    /**
     * The camera was flipped: the picture blurs and turns over (FaceTime's flip; the iOS camera's blur over the last
     * frame), comes round showing the other camera's mirroring, and clears once that camera has started (the engine
     * says so, [CameraStartedNotification]), or after [CameraWaitSeconds] at most. Mirrored for the front camera only.
     * The turn squeezes the picture within its own crop, so it never spills past the rounded tile. [still] (Reduce
     * Motion): a cross-fade instead of the turn.
     */
    fun setMirror(mirror: Boolean, still: Boolean) {
        if (mirror == mirrored) return
        mirrored = mirror
        val n = ++flip
        if (window == null) { video.transform = mirrorTransform(mirror); return }
        turned = false
        cameraReady = false
        listenForCamera(n)
        val blur = veil ?: UIVisualEffectView(effect = UIBlurEffect.effectWithStyle(UIBlurEffectStyle.UIBlurEffectStyleDark)).also {
            it.setFrame(crop.bounds)
            it.setUserInteractionEnabled(false)
            it.alpha = 0.0
            crop.addSubview(it)
            veil = it
        }
        blur.layer.removeAllAnimations()
        crop.layer.removeAllAnimations()
        val done: () -> Unit = { if (n == flip) { turned = true; clearIfReady(n) } }
        if (still) {
            crop.transform = CGAffineTransformIdentity.readValue()
            UIView.transitionWithView(
                crop, duration = QuickSeconds, options = UIViewAnimationOptionTransitionCrossDissolve,
                animations = { video.transform = mirrorTransform(mirror); blur.alpha = 1.0 },
                completion = { _ -> done() },
            )
        } else {
            // Half a turn edge-on, the mirroring swaps out of sight, then the other half.
            UIView.animateWithDuration(
                HalfTurnSeconds, delay = 0.0, options = UIViewAnimationOptionCurveEaseIn,
                animations = { crop.transform = CGAffineTransformMakeScale(EdgeOn, 1.0); blur.alpha = 1.0 },
                completion = { _ ->
                    if (n == flip) {
                        video.transform = mirrorTransform(mirror)
                        UIView.animateWithDuration(
                            HalfTurnSeconds, delay = 0.0, options = UIViewAnimationOptionCurveEaseOut,
                            animations = { crop.transform = CGAffineTransformIdentity.readValue() },
                            completion = { _ -> done() },
                        )
                    }
                },
            )
        }
        // Never left blurred: the camera may not say it started (no camera, refused).
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (CameraWaitSeconds * NSEC_PER_SEC.toDouble()).toLong()), dispatch_get_main_queue()) {
            if (n == flip) { cameraReady = true; clearIfReady(n) }
        }
    }

    private fun listenForCamera(n: Int) {
        cameraObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        cameraObserver = NSNotificationCenter.defaultCenter.addObserverForName(CameraStartedNotification, null, NSOperationQueue.mainQueue) { _ ->
            if (n == flip) { cameraReady = true; clearIfReady(n) }
        }
    }

    /** The turn is over and the other camera is running: the blur clears, quicker than it came. */
    private fun clearIfReady(n: Int) {
        if (n != flip || !turned || !cameraReady) return
        cameraObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        cameraObserver = null
        val blur = veil ?: return
        UIView.animateWithDuration(
            QuickSeconds, delay = 0.0, options = UIViewAnimationOptionCurveEaseOut or UIViewAnimationOptionAllowUserInteraction,
            animations = { blur.alpha = 0.0 },
            completion = { finished -> if (finished && n == flip && veil === blur) { blur.removeFromSuperview(); veil = null } },
        )
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        if (window == null) { cameraObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }; cameraObserver = null }
    }

    private fun mirrorTransform(mirror: Boolean) = if (mirror) CGAffineTransformMakeScale(-1.0, 1.0) else CGAffineTransformIdentity.readValue()
}

/** Posted by the Swift engine (CallEngineImpl.flipCamera) once the camera it switched to is capturing. */
private const val CameraStartedNotification = "CircasoCallCameraStarted"
/** Each half of the turn: together CircasoMotion.BaseMs. */
private const val HalfTurnSeconds = CircasoMotion.BaseMs / 2000.0
/** How thin the picture gets edge-on: a sliver, not zero, so the transform stays invertible. */
private const val EdgeOn = 0.01
/** The blur clearing, and Reduce Motion's cross-fade: CircasoMotion.QuickMs. */
private const val QuickSeconds = CircasoMotion.QuickMs / 1000.0
/** The longest the picture stays blurred waiting for the other camera. */
private const val CameraWaitSeconds = 1.5
