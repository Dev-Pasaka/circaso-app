package com.circaso.core.calls

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/** A STUN or TURN server for the peer connection, as the backend hands them out (guide CL1). */
data class IceServerSpec(val urls: List<String>, val username: String? = null, val credential: String? = null)

/** One track this phone sends: its transceiver `mid` after the offer, and the name the SFU knows it by. */
data class LocalTrackSpec(val mid: String, val trackName: String, val kind: String)

data class LocalOffer(val sdp: String, val tracks: List<LocalTrackSpec>)

/**
 * One stats reading: [rttMs] from the active candidate pair, [jitterMs] from inbound audio, and running totals over
 * every inbound track since the connection opened. Any of them null when WebRTC doesn't say.
 */
data class CallStats(
    val rttMs: Double? = null,
    val jitterMs: Double? = null,
    val packetsReceived: Long? = null,
    val packetsLost: Long? = null,
    val bytesReceived: Long? = null,
)

/**
 * One liveness reading (2026-11-13): [packetsByMid] is the RTP packets received so far on each pulled transceiver (a
 * person's audio arrives 50 times a second, even muted, and their video's keep-alive twice a second, so a count that
 * stops means their media stopped); [transportReceived] is everything this phone's connection to the SFU has received
 * (media, RTCP and the SFU's answers to ICE's own checks), so a count that keeps rising while someone's stops means
 * they went quiet, not this phone. Null fields: the engine can't tell.
 */
data class LinkReading(val packetsByMid: Map<String, Long>, val transportReceived: Long?)

/** Why the engine couldn't start: no microphone permission, no camera, or the platform has no WebRTC. */
class CallEngineException(val reason: String) : Exception(reason)

/**
 * WebRTC on the phone (designs H1–H5): one peer connection to Cloudflare's SFU per call. The backend brokers every SDP
 * (guide CL3–CL5); this side only makes and answers offers, sends the microphone and camera, and plays what it pulls.
 *
 * Audio and video are both pushed from the start; the camera only runs while it is on, so a voice call can turn into
 * video (design H2's Video button) without renegotiating.
 */
interface CallEngine {
    val available: Boolean

    /** Opens the connection with the microphone (and the camera when [video]); the offer to push (CL3). */
    suspend fun start(iceServers: List<IceServerSpec>, video: Boolean, userId: String): LocalOffer

    /**
     * Gets a call ready before it is answered (2026-11-13): while a call rings, or while CL1 is on its way, the peer
     * connection is built, its offer made and its network candidates gathered, with no microphone, camera or audio
     * route touched and nothing sent. [start] with the same [iceServers] and [userId] then reuses it, so answering
     * doesn't wait ~1 s for WebRTC to start. Does nothing without the microphone permission (it never asks) or when
     * the platform can't. Anything prepared is dropped by [close].
     */
    suspend fun prepare(iceServers: List<IceServerSpec>, video: Boolean, userId: String) = Unit

    /** A liveness reading of the connection (see [LinkReading]); null without a connection or when it can't tell. */
    suspend fun link(): LinkReading? = null

    /** The SFU's answer to [start]'s offer. */
    suspend fun acceptAnswer(sdp: String)

    /** The SFU offers the tracks this phone pulled (CL4); the answer goes back with CL5. */
    suspend fun answerOffer(sdp: String): String

    /** Remote video tracks by transceiver mid, so a tile can show whoever owns that mid. */
    val remoteVideoMids: StateFlow<Set<String>>

    /** The media connection to the SFU: true while up, false when it dropped or failed, null before it starts. */
    val connected: StateFlow<Boolean?>

    /**
     * The pulled tracks (by transceiver mid) whose packets have really arrived: someone is heard or seen only once
     * their mid is in here, which can be seconds after the SFU says they joined. Null when the engine can't tell.
     */
    val receivingMids: StateFlow<Set<String>>? get() = null

    /** Audio packets received so far over every pulled track; a number that stops rising means nobody is sending. Null: can't tell. */
    suspend fun receivedPackets(): Long? = null

    /**
     * A reading of the connection's WebRTC stats for the call report (2026-11-13): round trip, jitter and the running
     * totals of what was received and lost. Null when there is no connection or the engine can't tell.
     */
    suspend fun stats(): CallStats? = null

    /**
     * Every change of the connection's states for the call report, as `ice:<state>` and `pc:<state>` in WebRTC's own
     * lowercase names (`ice:disconnected`, `pc:failed`). Null when the engine can't tell.
     */
    val linkStates: kotlinx.coroutines.flow.Flow<String>? get() = null

    fun setMicrophone(on: Boolean)
    /** Turns the camera on (asking for permission) or off; false when it couldn't be turned on. */
    suspend fun setCamera(on: Boolean): Boolean
    fun flipCamera()
    fun setSpeaker(on: Boolean)

    /**
     * Whether this phone can share its screen on a call (Pasaka, 2026-11-09): Android always; iOS only once the
     * ReplayKit broadcast extension is in the app, so the button never shows for something that can't work.
     */
    val canShareScreen: Boolean get() = false

    /**
     * Shares the screen on the call's one video track instead of the camera (asking the system first), or stops and
     * gives the track back to the camera or its black keep-alive. Nothing is renegotiated. False when it didn't start
     * (the person said no, or the system refused).
     */
    suspend fun setScreenShare(on: Boolean): Boolean = !on

    /** True while the screen is going out; turns false by itself when the system stops it (its own Stop, a lock). */
    val screenSharing: StateFlow<Boolean> get() = NotSharing

    /** Ends everything: tracks, camera, connection, audio route. Safe to call twice. */
    fun close()
}

private val NotSharing: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)

object NoCallEngine : CallEngine {
    override val available = false
    override suspend fun start(iceServers: List<IceServerSpec>, video: Boolean, userId: String): LocalOffer = throw CallEngineException("unavailable")
    override suspend fun acceptAnswer(sdp: String) = Unit
    override suspend fun answerOffer(sdp: String): String = throw CallEngineException("unavailable")
    override val remoteVideoMids: StateFlow<Set<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptySet())
    override val connected: StateFlow<Boolean?> = kotlinx.coroutines.flow.MutableStateFlow(null)
    override fun setMicrophone(on: Boolean) = Unit
    override suspend fun setCamera(on: Boolean) = !on
    override fun flipCamera() = Unit
    override fun setSpeaker(on: Boolean) = Unit
    override fun close() = Unit
}

/** Where a video tile gets its picture: this phone's camera, or a pulled track by its mid. */
sealed interface VideoSource {
    data object Local : VideoSource
    data class Remote(val mid: String) : VideoSource
}

/**
 * A live video tile (the platform's renderer). [mirror] for the front camera; [fill] crops instead of letterboxing (a
 * shared screen is never cropped). It composes like any other view (clipped, moved, scaled), so a tile can swap
 * places or grow on the call's own motion without its picture being torn down.
 */
@Composable
expect fun CallVideo(source: VideoSource, modifier: Modifier = Modifier, mirror: Boolean = false, fill: Boolean = true)
