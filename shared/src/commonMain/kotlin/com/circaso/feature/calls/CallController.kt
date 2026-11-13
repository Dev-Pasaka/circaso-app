package com.circaso.feature.calls

import com.circaso.core.calls.CallEngine
import com.circaso.core.calls.CallEngineException
import com.circaso.core.calls.IceServerSpec
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.sync.nowMillis
import com.circaso.feature.calls.data.CallDto
import com.circaso.feature.calls.data.CallsRemote
import com.circaso.feature.calls.data.JoinCallRequest
import com.circaso.feature.calls.data.LocalTrackDto
import com.circaso.feature.calls.data.PullRequest
import com.circaso.feature.calls.data.PullTrackRequest
import com.circaso.feature.calls.data.RenegotiateRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Where this phone is in a call (designs H1–H4). */
/** [Reconnecting]: the network or the media connection dropped mid-call; the phone is rejoining (as WhatsApp does). */
enum class CallPhase { Incoming, Ringing, Connecting, Active, Reconnecting, Ended }

/** [name] is the full name; [handle] the "@username" shown under it, as X does (Pasaka, 2026-10-30). */
data class CallPerson(
    val userId: String, val name: String, val joined: Boolean, val audio: Boolean, val video: Boolean, val ringing: Boolean, val videoMid: String?,
    val handle: String? = null,
    /** Joined and their audio or video is really arriving; until then their tile says "connecting". */
    val live: Boolean = joined,
    /** Their profile photo from this phone's chats or contacts; null shows their initials. */
    val photoUrl: String? = null,
    /** Their video is their screen (CL8): shown whole, never cropped, and said ("Amina is sharing their screen"). */
    val screen: Boolean = false,
    /**
     * They were heard, then their media stopped while this phone's own connection kept working (2026-11-13): their
     * phone dropped off the network. Shown at once ("Rhoda's connection dropped · Reconnecting…"); clears when their
     * media is back.
     */
    val dropped: Boolean = false,
)

/**
 * The photos a call shows, from what this phone already has (no network): [chat] is the chat's own photo (the other
 * person in a direct chat, the group's photo in a group), [people] each person's by user id, [me] mine.
 */
data class CallPhotos(val chat: String? = null, val people: Map<String, String> = emptyMap(), val me: String? = null)

/** One call as the screens see it. */
data class CallUi(
    val callId: String?,
    val chatId: String,
    val title: String,
    val video: Boolean,
    val group: Boolean,
    val phase: CallPhase,
    val people: List<CallPerson> = emptyList(),
    val mic: Boolean = true,
    val camera: Boolean = false,
    val speaker: Boolean = false,
    val frontCamera: Boolean = true,
    /** When someone's audio or video first really arrived (H2's timer); never while the call is only connecting. */
    val activeSince: Long? = null,
    val endReason: String? = null,
    val minimised: Boolean = false,
    val error: String? = null,
    /** Who started it, for a group's ring ("Otieno is calling · Holiday Fund"). */
    val caller: String? = null,
    /** The other person's "@username" under their name in a direct call. */
    val handle: String? = null,
    /** Who the call is with: the other person's photo in a direct call, the group's photo in a group (null: initials). */
    val photoUrl: String? = null,
    /** My own photo, for my tile in a group call. */
    val myPhotoUrl: String? = null,
    /** This phone is sharing its screen on the call's video track ("You're sharing your screen · Stop"). */
    val screen: Boolean = false,
    /** The phone has no network at all, as the system says ("You're offline · Reconnecting…"). */
    val offline: Boolean = false,
) {
    val joined get() = people.filter { it.joined }
    /** In the group but not on the call (missed, declined or left): who "Add people" rings. */
    val notOnCall get() = people.filter { !it.joined && !it.ringing }
}

/**
 * The app's one call at a time, alive above every screen (H1 can arrive anywhere; a call keeps going while the person
 * sends money from it). It talks to the backend (guide CL1–CL8) and drives the platform's WebRTC [engine]:
 * join = start the engine → CL3 with its offer → the SFU's answer; then every other person's tracks are pulled (CL4)
 * and the SFU's offer answered (CL5). Frames from the `calls` module keep everyone's state in step.
 */
class CallController(
    private val remote: CallsRemote,
    private val engine: CallEngine,
    /** The signed-in person. */
    private val myUserId: () -> String?,
    private val scope: CoroutineScope,
    private val names: suspend (chatId: String) -> Pair<String, Boolean>?,
    /** Each person's "@username" in the call's chat, shown under their full name (Pasaka, 2026-10-30). */
    private val labels: suspend (chatId: String) -> Map<String, String> = { emptyMap() },
    /** The name shown for someone on the call: the one I saved for them, else [profileName] (theirs). */
    private val personName: (userId: String, profileName: String) -> String = { _, profileName -> profileName },
    /** Clears the platform's ringing notification once the call is answered, declined or over. */
    private val stopRinging: (callId: String) -> Unit = {},
    /**
     * Whether the phone has a network, as the operating system says. Never "can our server be reached": a dead realtime
     * socket or slow API says nothing about the media path, and tying calls to it hung up a call whose audio was fine
     * (2026-11-13, see [reconnect]). A call rejoins when it comes back.
     */
    private val online: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    private val reconnectGiveUpMillis: Long = 40_000,
    /** How long a joined call may wait for media before it counts as connected anyway (stats can be missing). */
    private val mediaGraceMillis: Long = 15_000,
    /** Writes a finished call into its chat's history (WhatsApp-style); see [logEnded]. */
    private val log: suspend (CallLogEntry) -> Unit = {},
    /** The call's photos from this phone's store (never the network, so a ring is never held up). */
    private val photos: suspend (chatId: String) -> CallPhotos = { CallPhotos() },
    /**
     * How long to wait for someone's `connected` (CL3b) before pulling them anyway, for an app that never says it (or a
     * frame and the reads after it all lost). Someone not connected is never pulled before: the SFU answers that with
     * `not_found_track_error`, which made the iPhone poll three times while the Redmi connected (2026-11-13). This
     * phone's own connection is never waited for (2026-11-14): once CL3's answer is set, the SFU holds a pull until it
     * is up (at most 5 s) and answers at once then. 0 pulls everyone joined at once (older servers).
     */
    private val pullGraceMillis: Long = 0,
    /** How often a live or ringing call is read again (CL2), so a lost `call.ended` can't strand it. 0: never. */
    private val pollMillis: Long = 0,
    /** True while the realtime socket is up; each time it comes back the call is read again. */
    private val socketUp: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
    /**
     * No packet from anyone for this long in an active call: the media path is dead, so the call reconnects (a fresh
     * session). If a second stall follows without a packet in between, the other side is gone and it ends. 0: never.
     */
    private val stallMillis: Long = 0,
    /** The time for [pullGraceMillis] and [stallMillis]; tests pass their virtual clock. */
    private val clock: () -> Long = { nowMillis() },
    /** The per-call timeline and end cause behind the call report (2026-11-13). */
    private val recorder: CallRecorder? = null,
    /** How long apart the attempts to send CL7 are when the network fails them; the first is at once. */
    private val leaveRetryMillis: List<Long> = listOf(2_000, 4_000, 8_000, 15_000, 30_000),
    /**
     * Build the call's connection before it is answered (2026-11-13): while an incoming call rings, and beside CL1 for
     * one this phone makes, so answering doesn't wait ~1 s for WebRTC (see [CallEngine.prepare]).
     */
    private val prewarm: Boolean = false,
    /**
     * How often this phone says it is still on the call (`calls/heartbeat` over the socket, relayed to the others as
     * `call.heartbeat`). 0: never. See [livenessMillis] for how heartbeats and media are judged.
     */
    private val heartbeatMillis: Long = 0,
    /** Sends a `calls` frame over the realtime socket; false when there is no socket. */
    private val sendFrame: suspend (type: String, data: JsonObject) -> Boolean = { _, _ -> false },
    /**
     * How often the media's liveness is read (2026-11-13; 0: never, and the older [stallMillis] check runs). Each
     * person's audio arrives 50 times a second, even muted, so their packet count standing still for [peerDropMillis]
     * while this phone's own connection still receives (the SFU's ICE answers and RTCP) means their phone dropped: the
     * screen says so at once and this phone keeps its own connection, waiting for them. This phone's own connection
     * receiving nothing for [selfDropMillis] means it is this phone: it reconnects at once.
     */
    private val livenessMillis: Long = 0,
    private val peerDropMillis: Long = 2_500,
    private val selfDropMillis: Long = 4_000,
    /** A direct call whose other side stays dropped this long ends; longer while their heartbeat says their phone is still online. */
    private val peerGiveUpMillis: Long = 45_000,
    private val peerGiveUpOnlineMillis: Long = 90_000,
) {
    private val _state = MutableStateFlow<CallUi?>(null)
    val state: StateFlow<CallUi?> = _state

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var call: CallDto? = null
    /** From CL1/CL2/CL3 only: frames carry no ICE servers, so TURN credentials never sit in the frame log. */
    private var ice: List<IceServerSpec> = emptyList()
    private var sessionId: String? = null
    /** Each pulled track ("userId/trackName") by its owner's connection tag: a new tag (they rejoined) means pull it again. */
    private val pulled = mutableMapOf<String, String?>()
    private var watchJob: Job? = null
    private val midOf = mutableMapOf<String, String>()
    /** Every pulled mid (audio and video) by its owner, to tell from [CallEngine.receivingMids] who is really heard. */
    private val mediaMids = mutableMapOf<String, Set<String>>()
    private var receivingJob: Job? = null
    private var graceJob: Job? = null
    /** Set when [mediaGraceMillis] ran out while connecting: the engine's stats can't be trusted for this call. */
    private var graceOver = false
    private var clearJob: Job? = null

    /**
     * Bumped for each new call on this phone. A hang-up's clean-up runs after its CL7 answers, and a slow answer
     * (6-12 s on 2026-11-09) used to land after the next call had begun, wiping it: the screen went while its
     * peer connection played on, and that call's `call.ended` no longer matched anything. Clean-up now only resets the
     * call it was for.
     */
    private var epoch = 0
    /** When each person's current connection was first seen ("userId/connection"), for [pullGraceMillis]. */
    private val firstSeen = mutableMapOf<String, Long>()
    private var joinedAtMillis = 0L
    private var reportedConnected: String? = null
    private var connectedJob: Job? = null
    private var deferredPull: Job? = null
    private var reconcileJob: Job? = null
    /** The calls this phone has sent CL7 for: one hang-up per call, however many paths end it (2026-11-13). */
    private val leaving = mutableSetOf<String>()
    private var phaseJob: Job? = null
    /** The latest of [online], as [watch] last saw it. */
    private var netUp = true

    /** When each person's `call.heartbeat` last came, by user id (this call). */
    private val heartbeats = mutableMapOf<String, Long>()
    /** Who is dropped now (their media stopped while this phone's connection kept working), and since when. */
    private var dropped: Map<String, Long> = emptyMap()
    /** Liveness bookkeeping: each person's packet total and when it last rose; the connection's total and when it last rose. */
    private val peerPackets = mutableMapOf<String, Long>()
    private val peerRose = mutableMapOf<String, Long>()
    private var linkTotal: Long? = null
    private var linkRose = 0L
    /** True once the engine gave a liveness reading this call: the older stall check stands down. */
    private var livenessActive = false
    private var heartbeatJob: Job? = null
    private var livenessJob: Job? = null
    /** The last ICE servers the server handed out (TURN credentials last a day), so a call can be built beside CL1. */
    private var lastIce: List<IceServerSpec> = emptyList()

    val available get() = engine.available

    /** Opens a new call's timeline and notes each phase it passes through. */
    private fun record(callId: String?, video: Boolean, outgoing: Boolean) {
        val r = recorder ?: return
        r.begin(callId, video, outgoing)
        phaseJob?.cancel()
        phaseJob = scope.launch {
            _state.map { it?.phase }.distinctUntilChanged().collect { p -> if (p != null) r.event("phase", p.name) }
        }
    }

    private fun note(type: String, detail: String? = null) { recorder?.event(type, detail) }

    /** Closes this call's timeline with [cause]; only the first cause of a call counts. */
    private fun ended(cause: CallEndCause) {
        if (recorder?.recording == true) com.circaso.core.common.Diagnostics.note("calls: ended, cause=${cause.wire}")
        phaseJob?.cancel()
        recorder?.finish(cause)
    }

    /** Design C2/D2's call buttons: ring the chat (CL1), then join at once so the caller is ready when they answer. */
    fun start(chatId: String, video: Boolean) {
        if (_state.value?.phase.let { it != null && it != CallPhase.Ended }) return
        takeOver()
        _state.value = CallUi(null, chatId, "", video, false, CallPhase.Ringing, camera = video, speaker = video)
        record(null, video, outgoing = true)
        scope.launch {
            names(chatId)?.let { (t, g) -> _state.update { it?.copy(title = t, group = g) } }
            labelOf = labels(chatId)
            // Beside the call, never before it: the photo fills in when this phone's store answers.
            scope.launch { loadPhotos(chatId) }
            // The connection is built while CL1 is on its way, with the ICE servers of the last call (the same TURN
            // credentials, cached by the server for a day); join takes it over when they match (2026-11-13).
            val me = myUserId()
            if (prewarm && me != null && lastIce.isNotEmpty()) { val warm = lastIce; scope.launch { lock.withLock { runCatching { engine.prepare(warm, video, me) } } } }
            val asked = clock()
            when (val r = remote.start(chatId, video, newIdempotencyKey())) {
                is AppResult.Success -> { note("cl1", "${clock() - asked}ms"); call = r.value; recorder?.callId(r.value.callId); publish(); join(r.value) }
                is AppResult.Failure -> {
                    // A call already running in this chat (a group call someone started): join that one instead.
                    if ((r.error as? AppError.Api)?.code == "calls.already_in_call") {
                        val existing = (remote.forChat(chatId) as? AppResult.Success)?.value
                        if (existing != null) { call = existing; recorder?.callId(existing.callId); publish(); join(existing); return@launch }
                    }
                    fail(r.error, cause = CallEndCause.JoinFailed)
                }
            }
        }
    }

    /** A group's "Join call" banner. */
    fun joinExisting(chatId: String) {
        scope.launch {
            val existing = (remote.forChat(chatId) as? AppResult.Success)?.value ?: return@launch
            takeOver()
            labelOf = labels(chatId)
            // Never a call without its screen: the call's own data stands in when the chat isn't on this phone yet.
            val (t, g) = names(chatId) ?: ("Group call" to (existing.participants.size > 2))
            _state.value = CallUi(existing.callId, chatId, t, existing.kind == "VIDEO", g, CallPhase.Connecting, camera = existing.kind == "VIDEO", speaker = true)
            call = existing
            record(existing.callId, existing.kind == "VIDEO", outgoing = false)
            scope.launch { loadPhotos(chatId) }
            join(existing)
        }
    }

    /** H1: Accept. */
    fun accept() {
        val c = call ?: return
        stopRinging(c.callId)
        _state.update { it?.copy(phase = CallPhase.Connecting, camera = c.kind == "VIDEO", speaker = c.kind == "VIDEO") }
        scope.launch { join(c) }
    }

    /** H1: Decline (CL6). */
    fun decline() {
        val c = call ?: return
        stopRinging(c.callId)
        _state.update { it?.copy(phase = CallPhase.Ended, endReason = "DECLINED") }
        ended(CallEndCause.Declined)
        closeEngine() // a connection prepared while it rang
        val mine = epoch
        scope.launch {
            (remote.decline(c.callId) as? AppResult.Success)?.value?.takeIf { it.state == "ENDED" }?.let { logEnded(it) }
            finish(mine)
        }
    }

    /**
     * Tears the peer connection down after whatever the lock is running (a join, rejoin or renegotiation) is done.
     * Closing it while WebRTC was still applying a description freed it under WebRTC's signaling thread and crashed
     * the app (2026-10-30, SIGSEGV in libjingle_peerconnection).
     */
    private fun closeEngine() {
        scope.launch { lock.withLock { engine.close() } }
    }

    /**
     * H2–H4: End (CL7), for [cause]. Idempotent (2026-11-13): the End button, the system's call screen, a give-up and a
     * re-read could each end the same call, and the Redmi sent CL7 four to six times per call. Now one CL7 per call,
     * repeated only when the request itself fails (no network, a 5xx), with the same Idempotency-Key.
     */
    fun leave(cause: CallEndCause = CallEndCause.UserHangup) {
        val c = call
        val already = c != null && c.callId in leaving
        if (already && _state.value?.phase == CallPhase.Ended) return
        // The server already ended it (its frame, or a read): there's nothing left to leave.
        if (c?.state == "ENDED" && _state.value?.phase == CallPhase.Ended) return
        _state.update { it?.copy(phase = CallPhase.Ended, endReason = it.endReason ?: if (cause.dropped) "DROPPED" else "COMPLETED") }
        ended(cause)
        closeEngine()
        reconnectJob?.cancel()
        val mine = epoch
        if (c == null || already) { finish(mine); return }
        leaving += c.callId
        scope.launch {
            sendLeave(c.callId, cause)?.takeIf { it.state == "ENDED" }?.let { logEnded(it) }
            finish(mine)
        }
    }

    /** One CL7, retried while the request itself fails; the server's answer, or null if it never got through. */
    private suspend fun sendLeave(callId: String, cause: CallEndCause): CallDto? {
        val key = newIdempotencyKey()
        val waits = leaveRetryMillis.iterator()
        while (true) {
            when (val r = remote.leave(callId, cause.wire, key)) {
                is AppResult.Success -> {
                    com.circaso.core.common.Diagnostics.note("calls: leave sent, cause=${cause.wire}")
                    return r.value
                }
                is AppResult.Failure -> {
                    val e = r.error
                    val retry = e == AppError.Network || e is AppError.Unexpected || (e is AppError.Api && (e.httpStatus >= 500 || e.httpStatus == 429 || e.code == "request.in_progress"))
                    com.circaso.core.common.Diagnostics.note("calls: leave failed (${describe(e)}), ${if (retry && waits.hasNext()) "retrying" else "giving up"}")
                    if (!retry || !waits.hasNext()) return null
                    delay(waits.next())
                }
            }
        }
    }

    private fun describe(e: AppError) = when (e) {
        AppError.Network -> "network"
        is AppError.Unexpected -> "http ${e.httpStatus}"
        is AppError.Api -> e.code
    }

    fun toggleMic() { media { it.copy(mic = !it.mic) }; _state.value?.let { note("mic", if (it.mic) "on" else "off") } }

    /** The camera only counts as on once it really is: no black "video" when permission was refused. */
    fun toggleCamera() {
        val want = !(_state.value?.camera ?: return)
        scope.launch {
            if (engine.setCamera(want)) { media { it.copy(camera = want) }; note("camera", if (want) "on" else "off") }
            else _state.update { it?.copy(error = "Circaso needs the camera for video. Turn it on in Settings.") }
        }
    }
    /** Whether this phone can share its screen at all (see [CallEngine.canShareScreen]); the button is hidden when not. */
    val canShareScreen get() = engine.canShareScreen

    /**
     * Share screen, and Stop (Pasaka, 2026-11-09). The system asks first; saying no there leaves the call as it was, with
     * nothing to apologise for. Everyone is told it's a screen (CL8 `screen`) so they fit it instead of cropping it.
     */
    fun toggleScreenShare() {
        val s = _state.value ?: return
        if (s.phase == CallPhase.Ended || s.phase == CallPhase.Incoming || !engine.canShareScreen) return
        val want = !s.screen
        scope.launch {
            val ok = engine.setScreenShare(want)
            if (ok && _state.value?.phase.let { it != null && it != CallPhase.Ended }) { media { it.copy(screen = want) }; note("screen", if (want) "on" else "off") }
        }
    }

    fun toggleSpeaker() { _state.update { it?.copy(speaker = !it.speaker) }; _state.value?.let { engine.setSpeaker(it.speaker); note("speaker", if (it.speaker) "on" else "off") } }
    fun flipCamera() { engine.flipCamera(); _state.update { it?.copy(frontCamera = !it.frontCamera) } }
    fun minimise(on: Boolean) = _state.update { it?.copy(minimised = on) }

    /** H4's "Add people" (CL9): ring everyone in the group who isn't on the call. */
    fun ringOthers() {
        val c = call ?: return
        scope.launch {
            when (val r = remote.ring(c.callId, emptyList())) {
                is AppResult.Success -> { call = r.value; publish() }
                is AppResult.Failure -> _state.update { it?.copy(error = ErrorMessages.forError(r.error)) }
            }
        }
    }

    private fun media(change: (CallUi) -> CallUi) {
        val next = _state.value?.let(change) ?: return
        _state.value = next
        engine.setMicrophone(next.mic)
        // A shared screen goes out on the video track, so the track counts as on while it does.
        call?.let { c -> scope.launch { remote.media(c.callId, next.mic, next.camera || next.screen, next.screen) } }
    }

    /** A `calls` realtime frame. */
    suspend fun onFrame(type: String, data: JsonObject?) {
        when (type) {
            "call.ringing" -> {
                val callId = data?.get("callId")?.jsonPrimitive?.contentOrNull ?: return
                if (_state.value?.phase.let { it != null && it != CallPhase.Ended }) return // busy: one call at a time
                val c = (remote.get(callId) as? AppResult.Success)?.value ?: return
                if (c.state == "ENDED") return
                takeOver()
                call = c
                record(c.callId, c.kind == "VIDEO", outgoing = false)
                labelOf = labels(c.chatId)
                val starter = c.participants.firstOrNull { it.userId == c.startedBy }
                val caller = starter?.let { personName(it.userId, it.displayName) }?.takeIf { it.isNotBlank() }
                val (title, group) = names(c.chatId) ?: (caller.orEmpty() to false)
                // The photo gets a moment to be in the first frame (a store read is a few ms); the ring never waits longer.
                val token = photoToken
                val lookup = scope.async { photosFor(c.chatId) }
                val quick = kotlinx.coroutines.withTimeoutOrNull(FirstFramePhotoMillis) { lookup.await() }
                _state.value = CallUi(c.callId, c.chatId, title, c.kind == "VIDEO", group, CallPhase.Incoming, caller = caller)
                if (quick != null) applyPhotos(token, c.chatId, quick)
                publish()
                if (quick == null) scope.launch { applyPhotos(token, c.chatId, lookup.await()) }
                startReconcile()
                // Answering shouldn't wait for WebRTC: the connection and its offer are made while it rings, with no
                // microphone, camera or audio route touched (2026-11-13).
                val me = myUserId()
                if (prewarm && me != null) {
                    val servers = serversOf(c)
                    scope.launch { lock.withLock { if (call?.callId == c.callId && _state.value?.phase == CallPhase.Incoming) runCatching { engine.prepare(servers, c.kind == "VIDEO", me) } } }
                }
            }
            "call.heartbeat" -> {
                val callId = data?.get("callId")?.jsonPrimitive?.contentOrNull ?: return
                val userId = data["userId"]?.jsonPrimitive?.contentOrNull ?: return
                if (callId == call?.callId) heartbeats[userId] = clock()
            }
            "call.updated" -> data?.let { decode(it) }?.let { c -> if (c.callId == call?.callId) { call = c; publish(); pullNew() } }
            "call.ended" -> data?.let { decode(it) }?.let { c -> endedByServer(c) }
            // Our ring stopped without us answering (the caller hung up, our 45 s ran out in a group call going on).
            "call.ring_stopped" -> {
                val callId = data?.get("callId")?.jsonPrimitive?.contentOrNull ?: return
                stopRinging(callId)
                if (callId != call?.callId || _state.value?.phase != CallPhase.Incoming) return
                _state.update { it?.copy(phase = CallPhase.Ended, endReason = data["reason"]?.jsonPrimitive?.contentOrNull ?: "MISSED") }
                ended(CallEndCause.RingStopped)
                closeEngine()
                finish(epoch)
            }
        }
    }

    /** The server says [c] is over (its frame, or a read of it): close everything for it, once. */
    private suspend fun endedByServer(c: CallDto) {
        stopRinging(c.callId)
        if (c.callId != call?.callId) {
            logEnded(c)
            return
        }
        if (_state.value?.phase == CallPhase.Ended && call?.state == "ENDED") return
        logEnded(c)
        call = c
        closeEngine()
        reconnectJob?.cancel()
        _state.update { it?.copy(phase = CallPhase.Ended, endReason = c.endReason) }
        ended(CallEndCause.RemoteEnded)
        finish(epoch)
    }

    /**
     * Reads the call again (CL2) and acts on what the server says, so a `call.ended` lost with a dead socket can't
     * leave this phone in a call nobody else is in (2026-11-09: the Redmi played a silent call for minutes). Runs
     * every [pollMillis], whenever the socket comes back, and when media stops.
     */
    private suspend fun reconcile() {
        val c = call ?: return
        val s = _state.value ?: return
        if (s.phase == CallPhase.Ended || s.callId != c.callId) return
        val fresh = (remote.get(c.callId) as? AppResult.Success)?.value ?: return
        if (fresh.callId != call?.callId || _state.value?.phase == CallPhase.Ended) return
        if (fresh.state == "ENDED") return endedByServer(fresh)
        val me = myUserId()
        val mine = fresh.participants.firstOrNull { it.userId == me }
        if (s.phase == CallPhase.Incoming && mine != null && mine.state != "INVITED") {
            // our ring stopped (missed in a group call going on) or we answered elsewhere
            stopRinging(fresh.callId)
            _state.update { it?.copy(phase = CallPhase.Ended, endReason = if (mine.state == "JOINED") "COMPLETED" else mine.state) }
            ended(if (mine.state == "JOINED") CallEndCause.AnsweredElsewhere else CallEndCause.RingStopped)
            closeEngine()
            finish(epoch)
            return
        }
        if (!s.group && sessionId != null) {
            // a direct call: the other person hung up (the server ends it then; this covers an older server)
            val other = fresh.participants.firstOrNull { it.userId != me }
            if (other != null && other.joinedAt != null && other.state != "JOINED") return leave(CallEndCause.RemoteLeft)
        }
        if (fresh != call) {
            call = fresh
            publish()
            pullNew()
        }
    }

    private fun startReconcile() {
        if (pollMillis <= 0 || reconcileJob?.isActive == true) return
        reconcileJob = scope.launch {
            launch {
                socketUp.distinctUntilChanged().drop(1).collect { up -> if (up) reconcile() }
            }
            var lastPackets: Long? = null
            var quietSince = 0L
            // Set when a stall sent the call reconnecting, cleared by the first packet after it: a second stall with
            // nothing in between means the other side is gone, not the path, and the call ends.
            var stallUnresolved = false
            while (true) {
                delay(pollMillis)
                reconcile()
                if (stallMillis <= 0 || livenessActive || _state.value?.phase != CallPhase.Active) { lastPackets = null; continue }
                val packets = engine.receivedPackets() ?: continue
                val now = clock()
                if (packets != lastPackets) {
                    if (lastPackets != null && packets > lastPackets) stallUnresolved = false
                    lastPackets = packets; quietSince = now; continue
                }
                if (now - quietSince >= stallMillis) {
                    val quiet = now - quietSince
                    note("stall", "${quiet}ms")
                    lastPackets = null
                    if (stallUnresolved) {
                        com.circaso.core.common.Diagnostics.note("calls: no packets for ${quiet}ms again after reconnecting, ending")
                        _state.update { it?.copy(error = "The call dropped.") }
                        leave(CallEndCause.Stall)
                        continue
                    }
                    // 2026-11-13: this used to hang up at once, and again on every later poll. Now the media path is
                    // rebuilt as for any other drop; only the reconnect's give-up ends the call.
                    com.circaso.core.common.Diagnostics.note("calls: no packets for ${quiet}ms, reconnecting")
                    stallUnresolved = true
                    startReconnect(stall = true)
                }
            }
        }
    }

    /**
     * One history entry per ended call, from this phone's side: a call I started that nobody answered is "No answer",
     * one rung to me that I never joined is "Missed", a call someone joined has its talking time.
     */
    private suspend fun logEnded(c: CallDto) {
        val me = myUserId() ?: return
        val mine = c.participants.firstOrNull { it.userId == me }
        val outgoing = c.startedBy == me
        val joined = mine?.joinedAt != null
        val outcome = when (c.endReason) {
            // A dropped call (2026-11-13) reads like a finished one, with the time it lasted, as WhatsApp's do.
            "COMPLETED", "DROPPED" -> if (joined && c.startedAt != null) CallLogOutcome.Completed else if (outgoing) CallLogOutcome.NoAnswer else CallLogOutcome.Missed
            "MISSED" -> if (outgoing) CallLogOutcome.NoAnswer else CallLogOutcome.Missed
            "DECLINED" -> CallLogOutcome.Declined
            "CANCELLED" -> if (outgoing) CallLogOutcome.Cancelled else CallLogOutcome.Missed
            else -> CallLogOutcome.Failed
        }
        val start = c.startedAt?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
        val end = c.endedAt?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
        val created = runCatching { kotlin.time.Instant.parse(c.createdAt).toEpochMilliseconds() }.getOrNull() ?: nowMillis()
        val seconds = if (outcome == CallLogOutcome.Completed && start != null && end != null) ((end - start) / 1000).coerceAtLeast(0) else 0
        // The history entry is a nicety: failing to write it must never take the call (or the app) down with it.
        runCatching { log(CallLogEntry(c.chatId, c.callId, outgoing, c.kind == "VIDEO", outcome, seconds, c.startedBy, created)) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    private fun decode(o: JsonObject): CallDto? = runCatching { json.decodeFromJsonElement(CallDto.serializer(), o) }.getOrNull()

    /** The call's ICE servers: CL1/CL2's, else the last ones handed out, else Cloudflare's STUN. */
    private fun serversOf(c: CallDto): List<IceServerSpec> =
        c.iceServers.map { IceServerSpec(it.urls, it.username, it.credential) }.ifEmpty { ice }.ifEmpty { lastIce }
            .ifEmpty { listOf(IceServerSpec(listOf("stun:stun.cloudflare.com:3478"))) }

    private suspend fun join(c: CallDto) = lock.withLock {
        val me = myUserId() ?: return@withLock
        try {
            val servers = serversOf(c)
            val t0 = clock()
            val offer = engine.start(servers, c.kind == "VIDEO", me)
            val t1 = clock()
            note("offer", "${t1 - t0}ms")
            when (val r = remote.join(c.callId, JoinCallRequest(offer.sdp, offer.tracks.map { LocalTrackDto(it.mid, it.trackName, it.kind) }))) {
                is AppResult.Success -> {
                    engine.acceptAnswer(r.value.answer)
                    sessionId = r.value.sessionId
                    joinedAtMillis = clock()
                    call = r.value.call
                    recorder?.joined()
                    note("join", "ok ${joinedAtMillis - t1}ms")
                    val s = _state.value
                    engine.setMicrophone(s?.mic ?: true)
                    engine.setSpeaker(s?.speaker ?: false)
                    watch()
                    publish()
                    watchScreen()
                    // Tell everyone the real state (a pushed video track isn't a camera that's on, CL8), beside the
                    // pull below rather than before it: the person answering hears the caller ~0.1 s sooner.
                    scope.launch { remote.media(c.callId, s?.mic ?: true, s?.camera ?: false) }
                }
                is AppResult.Failure -> { fail(r.error, cause = CallEndCause.JoinFailed); return@withLock }
            }
        } catch (e: CallEngineException) {
            val noMic = e.reason == "microphone"
            note("engine", "failed:" + e.reason.take(60))
            fail(null, if (noMic) "Circaso needs the microphone for calls. Turn it on in Settings." else "The call couldn't start on this phone.", if (noMic) CallEndCause.PermissionDenied else CallEndCause.EngineFailed)
            if (leaving.add(c.callId)) scope.launch { sendLeave(c.callId, if (noMic) CallEndCause.PermissionDenied else CallEndCause.EngineFailed) }
            return@withLock
        }
        pullNewLocked()
    }

    private fun pullNew() { scope.launch { lock.withLock { pullNewLocked() } } }

    /**
     * Pull [p] now? Cloudflare's order is publish, wait for `connected`, then subscribe. Their side: only once they
     * say `connected` (CL3b, in `call.updated`), or after [pullGraceMillis] for an app that never does; a pull before
     * that is answered `not_found_track_error` (2026-11-13). Our side: never waited for (2026-11-14); this runs only
     * once CL3's answer is set, and the SFU holds the pull until our connection is up, answering the moment it is.
     */
    private fun ready(p: com.circaso.feature.calls.data.ParticipantDto): Boolean {
        if (pullGraceMillis <= 0 || p.connected) return true
        val seen = firstSeen.getOrPut("${p.userId}/${p.connection}") { clock() }
        return clock() - seen >= pullGraceMillis
    }

    /** CL4 for everyone who has joined and isn't pulled yet, then the SFU's offer answered (CL5). */
    private suspend fun pullNewLocked() {
        val c = call ?: return
        val session = sessionId ?: return
        val me = myUserId()
        // Each track counts on its own: the SFU can turn down one (a video with no media yet, `empty_track_error`)
        // while the audio beside it is fine. Only what came back with a mid is done; the rest is asked for again.
        fun missing(p: com.circaso.feature.calls.data.ParticipantDto) = p.tracks.filter { "${p.userId}/${it.trackName}".let { k -> k !in pulled || pulled[k] != p.connection } }
        val waiting = c.participants.filter { p ->
            p.userId != me && p.state == "JOINED" && p.tracks.isNotEmpty() && missing(p).isNotEmpty()
        }
        val fresh = waiting.filter { ready(it) }
        if (fresh.size < waiting.size && deferredPull?.isActive != true) {
            deferredPull = scope.launch { delay(pullGraceMillis); pullNew() }
        }
        if (fresh.isEmpty()) return
        val asked = fresh.flatMap { p -> missing(p).map { PullTrackRequest(p.userId, it.trackName) } }
        val t0 = clock()
        val mineUp = engine.connected.value == true
        val r = remote.pull(c.callId, PullRequest(session, asked))
        val pull = (r as? AppResult.Success)?.value
        if (pull == null) {
            // Our own connection not up within the SFU's 5 s (425), or the request failed: our `connected` pulls again
            // when it comes up; once it is up, a short backoff does.
            note("pull", "failed ${clock() - t0}ms" + if (mineUp) "" else " early")
            if (mineUp) retryPull(c)
            return
        }
        // "early": sent before this phone's own connection was up, so the time includes the SFU's wait for it.
        note("pull", "${clock() - t0}ms tracks=${pull.tracks.count { it.mid != null }}" + if (mineUp) "" else " early")
        applyPull(c, session, fresh, asked, pull)
    }

    /**
     * Asks again shortly (1, 2, 4, 8, 8 s; at most [MaxPullRetries] times a call) for what a pull didn't bring, but only
     * from people who say they are connected: anyone else is pulled when their `connected` arrives, never polled.
     */
    private fun retryPull(c: CallDto, from: Set<String>? = null) {
        if (pullRetries >= MaxPullRetries || retryJob?.isActive == true) return
        val me = myUserId()
        val anyoneReady = c.participants.any { it.userId != me && it.state == "JOINED" && (from == null || it.userId in from) && (it.connected || pullGraceMillis <= 0) }
        if (!anyoneReady) return
        val wait = PullRetryMillis.getOrElse(pullRetries) { PullRetryMillis.last() }
        pullRetries++
        retryJob = scope.launch { delay(wait); pullNew() }
    }

    private var retryJob: Job? = null

    /**
     * What a pull (CL4, or the one that came with the join) brought: each track's mid, the SFU's offer answered (CL5),
     * and what the SFU turned down asked for again shortly. [asked] empty: everything in [pull] was what was wanted.
     */
    private suspend fun applyPull(c: CallDto, session: String, fresh: List<com.circaso.feature.calls.data.ParticipantDto>, asked: List<PullTrackRequest>, pull: com.circaso.feature.calls.data.PullResponse) {
        pull.tracks.forEach { t ->
            if (t.trackName.startsWith("video") && t.mid != null) midOf[t.userId] = t.mid
            t.mid?.let { mediaMids[t.userId] = mediaMids[t.userId].orEmpty() + it }
        }
        com.circaso.core.common.Diagnostics.note("calls: pulled ${pull.tracks.joinToString { it.trackName.substringBefore('-') + "@" + (it.mid ?: "none") }} renegotiate=${pull.requiresRenegotiation}")
        val offer = pull.offer
        if (offer != null) {
            val answer = runCatching { engine.answerOffer(offer) }.getOrNull() ?: return
            val t0 = clock()
            remote.renegotiate(c.callId, RenegotiateRequest(session, answer))
            note("renegotiate", "${clock() - t0}ms")
        }
        val connectionOf = fresh.associate { it.userId to it.connection }
        pull.tracks.filter { it.mid != null }.forEach { t -> pulled["${t.userId}/${t.trackName}"] = connectionOf[t.userId] }
        // A track the SFU turned down is tried again shortly, not left out for the rest of the call.
        val wanted = asked.ifEmpty { pull.tracks.map { PullTrackRequest(it.userId, it.trackName) } }
        val refused = wanted.filter { a -> pull.tracks.none { it.userId == a.userId && it.trackName == a.trackName && it.mid != null } }
        if (refused.isNotEmpty()) retryPull(c, refused.map { it.userId }.toSet())
        publish()
    }

    /** Re-pulls of tracks the SFU turned down, this call; bounded so a track that never comes back isn't asked for forever. */
    private var pullRetries = 0

    /** userId → "@handle" for the call's chat, loaded once per call. */
    private var labelOf: Map<String, String> = emptyMap()

    /** The call's photos, loaded once per call from this phone's store. */
    private var photosOf = CallPhotos()

    /** A photo lookup that fails or is slow never stops a call: it rings with initials instead. */
    private suspend fun photosFor(chatId: String): CallPhotos =
        runCatching { kotlinx.coroutines.withTimeoutOrNull(PhotoLookupMillis) { photos(chatId) } }.getOrNull() ?: CallPhotos()

    /** Changes with every call, so a slow lookup from the last call never puts its photo on this one. */
    private var photoToken = Any()

    private suspend fun loadPhotos(chatId: String) {
        val token = photoToken
        applyPhotos(token, chatId, photosFor(chatId))
    }

    /**
     * A group shows the group's photo; a direct call the other person's (from contacts when the chat isn't on this
     * phone yet). Everyone's tile gets theirs on the next [publish].
     */
    private fun applyPhotos(token: Any, chatId: String, p: CallPhotos) {
        if (token !== photoToken || _state.value?.chatId != chatId) return
        photosOf = p
        val caller = call?.startedBy?.let { p.people[it] }
        _state.update { s ->
            if (s == null || s.chatId != chatId) s
            else s.copy(photoUrl = s.photoUrl ?: if (s.group) p.chat else p.chat ?: caller, myPhotoUrl = p.me)
        }
        publish()
    }

    private fun publish() {
        val c = call ?: return
        com.circaso.core.common.Diagnostics.note("calls: state=${c.state} people=${c.participants.joinToString { it.state + "/v=" + it.video + "/mid=" + (midOf[it.userId] ?: "-") }}")
        if (c.iceServers.isNotEmpty()) { ice = c.iceServers.map { IceServerSpec(it.urls, it.username, it.credential) }; lastIce = ice }
        val me = myUserId()
        val receiving = engine.receivingMids?.value
        val people = c.participants.filter { it.userId != me }.map { p ->
            val joined = p.state == "JOINED"
            val heard = receiving == null || graceOver || mediaMids[p.userId].orEmpty().any { it in receiving }
            val gone = joined && p.userId in dropped
            CallPerson(
                p.userId, personName(p.userId, p.displayName).ifBlank { labelOf[p.userId] ?: "Someone" }, joined, p.audio, p.video, p.state == "INVITED", midOf[p.userId],
                handle = labelOf[p.userId], live = joined && heard && !gone, photoUrl = photosOf.people[p.userId],
                screen = joined && p.video && p.screen, dropped = gone,
            )
        }
        val othersJoined = people.any { it.joined }
        val someoneLive = people.any { it.live }
        if (othersJoined && sessionId != null && !someoneLive && !graceOver && graceJob?.isActive != true && _state.value?.activeSince == null) {
            graceJob = scope.launch {
                delay(mediaGraceMillis)
                com.circaso.core.common.Diagnostics.note("calls: no media after ${mediaGraceMillis}ms, counting the call as connected")
                graceOver = true
                publish()
            }
        }
        if (someoneLive) graceJob?.cancel()
        _state.update { s ->
            val base = s ?: CallUi(c.callId, c.chatId, people.firstOrNull()?.name.orEmpty(), c.kind == "VIDEO", people.size > 1, CallPhase.Incoming)
            val phase = when {
                c.state == "ENDED" -> CallPhase.Ended
                base.phase == CallPhase.Incoming -> CallPhase.Incoming
                base.phase == CallPhase.Reconnecting -> CallPhase.Reconnecting
                // Joined isn't heard: the timer starts only once someone's audio or video really arrives.
                c.state == "ACTIVE" && othersJoined && sessionId != null ->
                    if (someoneLive || base.activeSince != null) CallPhase.Active else CallPhase.Connecting
                sessionId == null -> base.phase
                else -> CallPhase.Ringing
            }
            base.copy(
                callId = c.callId, people = people, phase = phase,
                // A direct call is titled by the other person's full name with their @username under it; a group keeps its name.
                title = if (!base.group) people.firstOrNull()?.name ?: base.title else base.title.ifBlank { people.firstOrNull()?.name.orEmpty() },
                handle = if (!base.group) people.firstOrNull()?.handle else null,
                photoUrl = if (!base.group) people.firstOrNull()?.photoUrl ?: base.photoUrl else base.photoUrl,
                activeSince = base.activeSince ?: if (phase == CallPhase.Active) nowMillis() else null,
            )
        }
    }

    private fun fail(error: AppError?, message: String? = null, cause: CallEndCause = CallEndCause.JoinFailed) {
        closeEngine()
        reconcileJob?.cancel()
        reconnectJob?.cancel()
        _state.update { it?.copy(phase = CallPhase.Ended, error = message ?: error?.let { e -> ErrorMessages.forError(e) }, endReason = it.endReason ?: "FAILED") }
        error?.let { note("join", "failed:" + describe(it)) }
        ended(cause)
        finish()
    }

    /** "Call ended" stays a moment, then the call screen goes, unless another call ([epoch]) has begun since. */
    private fun finish(forEpoch: Int = epoch) {
        if (forEpoch != epoch) return
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(if (_state.value?.error != null) 3_000 else 1_500)
            if (forEpoch == epoch) reset()
        }
    }

    /** A new call right after the last one ended: the last one's pending clean-up must not wipe this one. */
    private fun takeOver() {
        // A timeline still open is the last call's; the new call's own begins with it.
        if (recorder?.recording == true) ended(CallEndCause.Other)
        epoch++
        if (clearJob?.isActive == true) { clearJob?.cancel(); reset() }
        // the last call's engine, if a stale clean-up ever left it running, never plays under this one
        reconcileJob?.cancel()
    }

    /**
     * WhatsApp-style reconnecting: when the network or the media connection drops mid-call the screen says
     * "Reconnecting…"; once both are back the phone rejoins with a fresh session (CL3) and pulls everyone again.
     * If it can't within [reconnectGiveUpMillis] it hangs up, so nobody is left alone on a call.
     */
    private fun watch() {
        startReconcile()
        startHeartbeat()
        startLiveness()
        if (connectedJob?.isActive != true) {
            connectedJob = scope.launch {
                engine.connected.collect { up ->
                    val c = call ?: return@collect
                    val session = sessionId ?: return@collect
                    if (up != true || reportedConnected == session) return@collect
                    reportedConnected = session
                    // CL3b: everyone may pull us now; and our own pulls waiting for this connection can go.
                    remote.connected(c.callId, session)
                    pullNew()
                }
            }
        }
        val receivingFlow = engine.receivingMids
        if (receivingFlow != null && receivingJob?.isActive != true) {
            receivingJob = scope.launch { receivingFlow.collect { if (it.isNotEmpty()) publish() } }
        }
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            kotlinx.coroutines.flow.combine(engine.connected, online) { media, net -> net to media }
                .collect { (net, media) ->
                    netUp = net
                    if (_state.value?.offline != !net) _state.update { it?.copy(offline = !net) }
                    val s = _state.value ?: return@collect
                    if (s.phase == CallPhase.Ended || sessionId == null) return@collect
                    // The media connection is the judge: up means the call is fine whatever else says. With no
                    // network the phone says "Reconnecting…" at once rather than wait for ICE to notice.
                    if (media == false || (!net && media != true)) startReconnect(stall = false)
                }
        }
    }

    /**
     * Says "Reconnecting…" and starts [reconnect], unless it is already running. [now]: this phone's own connection
     * receives nothing at all, so there is nothing for ICE to recover: rejoin at once.
     */
    private fun startReconnect(stall: Boolean, now: Boolean = false) {
        if (_state.value?.phase.let { it == null || it == CallPhase.Ended }) return
        if (reconnectJob?.isActive == true) return
        _state.update { it?.copy(phase = CallPhase.Reconnecting) }
        reconnectJob = scope.launch { reconnect(stall, now) }
    }

    /** `calls/heartbeat` every [heartbeatMillis] while this phone has a session in the call (2026-11-13). */
    private fun startHeartbeat() {
        if (heartbeatMillis <= 0 || heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (true) {
                val c = call
                val phase = _state.value?.phase
                if (c != null && sessionId != null && phase != null && phase != CallPhase.Ended && phase != CallPhase.Incoming) {
                    runCatching { sendFrame("heartbeat", kotlinx.serialization.json.buildJsonObject { put("callId", kotlinx.serialization.json.JsonPrimitive(c.callId)) }) }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                }
                delay(heartbeatMillis)
            }
        }
    }

    private fun startLiveness() {
        if (livenessMillis <= 0 || livenessJob?.isActive == true) return
        livenessJob = scope.launch {
            while (true) {
                delay(livenessMillis)
                runCatching { liveness() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            }
        }
    }

    /** Forgets what liveness knew: a new connection's counters start from 0. */
    private fun resetLiveness() {
        peerPackets.clear()
        peerRose.clear()
        linkTotal = null
        linkRose = clock()
        if (dropped.isNotEmpty()) { dropped = emptyMap(); publish() }
    }

    /**
     * One liveness reading (2026-11-13). Their media is the heartbeat that matters: 50 audio packets a second, even
     * muted, and their video's keep-alive. Someone whose count stood still for [peerDropMillis] while this phone's own
     * connection received something after they went quiet (so it can't be this phone) has dropped: the screen says
     * so at once and the call waits for them, this phone keeping its connection. Their `call.heartbeat` only adds what
     * media can't say: whether their phone is still online (then the wait is longer), and, on an engine with no
     * readings, that they've gone quiet at all. This phone's connection receiving nothing for [selfDropMillis] means
     * it is this phone: it reconnects at once.
     */
    private suspend fun liveness() {
        val s = _state.value ?: return
        val c = call ?: return
        if (sessionId == null || (s.phase != CallPhase.Active && s.phase != CallPhase.Reconnecting)) return
        val reading = engine.link()
        val now = clock()
        val me = myUserId()
        val others = c.participants.filter { it.userId != me && it.state == "JOINED" }
        if (reading != null) {
            livenessActive = true
            val total = reading.transportReceived
            if (total != null && total != linkTotal) { linkTotal = total; linkRose = now }
            others.forEach { p ->
                val n = mediaMids[p.userId].orEmpty().sumOf { reading.packetsByMid[it] ?: 0L }
                val before = peerPackets[p.userId]
                if (before == null || n > before) {
                    // Only once they've really been heard does silence from them mean anything.
                    if (n > 0) peerRose[p.userId] = now
                    peerPackets[p.userId] = n
                }
            }
        }
        if (s.phase == CallPhase.Reconnecting) return // this phone is the one rebuilding; nobody else is judged meanwhile
        if (reading?.transportReceived != null && linkTotal != null && now - linkRose >= selfDropMillis && now - joinedAtMillis >= selfDropMillis) {
            note("link", "quiet ${now - linkRose}ms")
            com.circaso.core.common.Diagnostics.note("calls: this phone's connection received nothing for ${now - linkRose}ms, reconnecting")
            startReconnect(stall = true, now = true)
            return
        }
        val gone = others.filter { p ->
            if (reading != null) {
                val rose = peerRose[p.userId] ?: return@filter false
                now - rose >= peerDropMillis && linkRose > rose + 500 && engine.connected.value == true
            } else {
                // No readings: their heartbeat going quiet while ours is up is all there is.
                val beat = heartbeats[p.userId] ?: return@filter false
                heartbeatMillis > 0 && now - beat >= heartbeatMillis * 3
            }
        }.map { it.userId }.toSet()
        val next = gone.associateWith { dropped[it] ?: now }
        if (next.keys != dropped.keys) {
            (next.keys - dropped.keys).forEach { note("peer", "dropped"); com.circaso.core.common.Diagnostics.note("calls: someone's media stopped while ours kept going: they dropped") }
            (dropped.keys - next.keys).forEach { note("peer", "back") }
            dropped = next
            publish()
        }
        // A direct call whose other side never comes back ends, a little after their own phone would have given up.
        if (!s.group && others.isNotEmpty() && others.all { it.userId in dropped }) {
            val since = dropped.values.maxOrNull() ?: return
            val online = others.any { p -> heartbeats[p.userId]?.let { now - it < heartbeatMillis * 3 } == true }
            if (now - since >= if (online) peerGiveUpOnlineMillis else peerGiveUpMillis) {
                val name = s.people.firstOrNull { it.joined }?.name?.let { com.circaso.designsystem.components.firstNameWord(it) }
                _state.update { it?.copy(error = if (name.isNullOrBlank()) "The call dropped." else "$name's connection dropped.") }
                leave(CallEndCause.PeerLost)
            }
        }
    }

    /** How long a fresh session from a rejoin gets to connect before the next try. */
    private val RejoinMediaMillis = 10_000L
    private var reconnectJob: Job? = null
    private var screenJob: Job? = null

    /** The system can stop a share on its own (its Stop, the screen locked): the call and everyone on it follow. */
    private fun watchScreen() {
        if (screenJob?.isActive == true) return
        screenJob = scope.launch {
            engine.screenSharing.collect { sharing ->
                if (!sharing && _state.value?.screen == true) media { it.copy(screen = false) }
            }
        }
    }

    /**
     * Gets the media flowing again within [reconnectGiveUpMillis], or hangs up. Call 1 of 2026-11-13 dropped because
     * this waited for the app's "online" (can our server be reached), which a socket ping timeout had turned off; the
     * probe that turns it back on backs off 5, 10, 20 s, so 40 s ran out on a call whose audio was fine. Now the media
     * connection decides: once it is up again (ICE often recovers by itself) the call goes on; otherwise, whenever the
     * phone has a network, a fresh session is made (CL3). [stall]: the connection says it is up but nothing arrives, so
     * only a fresh session counts.
     */
    private suspend fun reconnect(stall: Boolean, now: Boolean = false) {
        note("reconnect", if (now) "start:link" else if (stall) "start:stall" else "start:" + if (!netUp) "network" else "media")
        com.circaso.core.common.Diagnostics.note("calls: reconnecting (${if (now) "link" else if (stall) "stall" else if (!netUp) "network" else "media"})")
        val back = kotlinx.coroutines.withTimeoutOrNull(reconnectGiveUpMillis) {
            var needRejoin = stall
            // ICE often recovers a blip by itself; a connection that receives nothing at all has nothing to recover.
            if (!now) delay(2_500)
            while (true) {
                if (!needRejoin && engine.connected.value == true) { note("reconnect", "ok:media"); return@withTimeoutOrNull true }
                if (netUp) {
                    note("reconnect", "rejoin")
                    needRejoin = false
                    if (rejoin()) {
                        // A session the server took is not a call that works: the fresh connection has to come up.
                        val up = kotlinx.coroutines.withTimeoutOrNull(RejoinMediaMillis) { engine.connected.first { it == true } }
                        if (up != null) { note("reconnect", "ok:rejoin"); return@withTimeoutOrNull true }
                        note("reconnect", "failed:no_media")
                    } else {
                        note("reconnect", "failed")
                        delay(3_000)
                    }
                } else {
                    delay(1_000)
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        if (back == true) {
            com.circaso.core.common.Diagnostics.note("calls: reconnected")
            _state.update { if (it?.phase == CallPhase.Reconnecting) it.copy(phase = CallPhase.Active) else it }
            return
        }
        note("reconnect", "gave_up")
        com.circaso.core.common.Diagnostics.note("calls: couldn't reconnect in ${reconnectGiveUpMillis}ms, ending")
        _state.update { it?.copy(error = "The call dropped and couldn't reconnect.") }
        leave(if (stall) CallEndCause.Stall else CallEndCause.ReconnectGaveUp)
    }

    /** CL3 again with a fresh peer connection; everyone is pulled again from scratch. */
    private suspend fun rejoin(): Boolean = lock.withLock {
        val c = call ?: return@withLock false
        val me = myUserId() ?: return@withLock false
        val s = _state.value
        try {
            val offer = engine.start(ice.ifEmpty { listOf(IceServerSpec(listOf("stun:stun.cloudflare.com:3478"))) }, s?.camera == true, me)
            when (val r = remote.join(c.callId, JoinCallRequest(offer.sdp, offer.tracks.map { LocalTrackDto(it.mid, it.trackName, it.kind) }))) {
                is AppResult.Success -> {
                    engine.acceptAnswer(r.value.answer)
                    sessionId = r.value.sessionId
                    joinedAtMillis = clock()
                    reportedConnected = null
                    call = r.value.call
                    engine.setMicrophone(s?.mic ?: true)
                    engine.setSpeaker(s?.speaker ?: false)
                    // The share went with the old connection (the server clears it on a join too); Share screen again.
                    _state.update { it?.copy(screen = false) }
                    remote.media(c.callId, s?.mic ?: true, s?.camera ?: false)
                    pulled.clear()
                    pullRetries = 0
                    midOf.clear()
                    mediaMids.clear()
                    resetLiveness()
                }
                is AppResult.Failure -> return@withLock false
            }
        } catch (e: CallEngineException) {
            return@withLock false
        }
        pullNewLocked()
        true
    }

    private fun reset() {
        heartbeatJob?.cancel()
        livenessJob?.cancel()
        heartbeats.clear()
        dropped = emptyMap()
        peerPackets.clear()
        peerRose.clear()
        linkTotal = null
        livenessActive = false
        phaseJob?.cancel()
        reconcileJob?.cancel()
        connectedJob?.cancel()
        deferredPull?.cancel()
        retryJob?.cancel()
        firstSeen.clear()
        reportedConnected = null
        joinedAtMillis = 0L
        watchJob?.cancel()
        reconnectJob?.cancel()
        screenJob?.cancel()
        receivingJob?.cancel()
        graceJob?.cancel()
        graceOver = false
        mediaMids.clear()
        call = null
        ice = emptyList()
        labelOf = emptyMap()
        photosOf = CallPhotos()
        photoToken = Any()
        sessionId = null
        pulled.clear()
        pullRetries = 0
        midOf.clear()
        _state.value = null
    }
}

/** How long the call waits on this phone's store for photos before it goes on without them. */
/** How many times a call asks the SFU again for tracks it turned down. */
private const val MaxPullRetries = 5

/** How long apart those asks are: a video with no frames yet (`empty_track_error`) usually has them within a second. */
private val PullRetryMillis = listOf(1_000L, 2_000L, 4_000L, 8_000L)

private const val PhotoLookupMillis = 500L

/** How long an incoming call waits for its photo so the first frame has it; after this it rings with initials. */
private const val FirstFramePhotoMillis = 100L

/** How a finished call reads in its chat, from this phone's side. */
enum class CallLogOutcome { Completed, Missed, Declined, Cancelled, NoAnswer, Failed }

/** A finished call, for its chat's history. */
data class CallLogEntry(
    val chatId: String,
    val callId: String,
    val outgoing: Boolean,
    val video: Boolean,
    val outcome: CallLogOutcome,
    val durationSeconds: Long,
    val callerId: String,
    val at: Long,
)
