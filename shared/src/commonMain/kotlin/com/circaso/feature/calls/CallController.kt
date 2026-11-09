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
    /** Clears the platform's ringing notification once the call is answered, declined or over. */
    private val stopRinging: (callId: String) -> Unit = {},
    /** Whether the phone is online; a call rejoins when it comes back. */
    private val online: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    private val reconnectGiveUpMillis: Long = 40_000,
    /** How long a joined call may wait for media before it counts as connected anyway (stats can be missing). */
    private val mediaGraceMillis: Long = 15_000,
    /** Writes a finished call into its chat's history (WhatsApp-style); see [logEnded]. */
    private val log: suspend (CallLogEntry) -> Unit = {},
    /** The call's photos from this phone's store (never the network, so a ring is never held up). */
    private val photos: suspend (chatId: String) -> CallPhotos = { CallPhotos() },
    /**
     * How long to wait for this phone's own connection, and for someone's `connected` (CL3b), before pulling them
     * anyway: the SFU holds a pull of a publisher that isn't connected yet for ~9 s. 0 pulls at once (older servers).
     */
    private val pullGraceMillis: Long = 0,
    /** How often a live or ringing call is read again (CL2), so a lost `call.ended` can't strand it. 0: never. */
    private val pollMillis: Long = 0,
    /** True while the realtime socket is up; each time it comes back the call is read again. */
    private val socketUp: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
    /** No packet from anyone for this long in an active call: it is dead, hang up. 0: never. */
    private val stallMillis: Long = 0,
    /** The time for [pullGraceMillis] and [stallMillis]; tests pass their virtual clock. */
    private val clock: () -> Long = { nowMillis() },
) {
    private val _state = MutableStateFlow<CallUi?>(null)
    val state: StateFlow<CallUi?> = _state

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var call: CallDto? = null
    /** From CL1/CL2/CL3 only: frames carry no ICE servers, so TURN credentials never sit in the frame log. */
    private var ice: List<IceServerSpec> = emptyList()
    private var sessionId: String? = null
    /** Whose tracks are pulled, by their connection tag: a new tag (they rejoined) means pull them again. */
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

    val available get() = engine.available

    /** Design C2/D2's call buttons: ring the chat (CL1), then join at once so the caller is ready when they answer. */
    fun start(chatId: String, video: Boolean) {
        if (_state.value?.phase.let { it != null && it != CallPhase.Ended }) return
        takeOver()
        _state.value = CallUi(null, chatId, "", video, false, CallPhase.Ringing, camera = video, speaker = video)
        scope.launch {
            names(chatId)?.let { (t, g) -> _state.update { it?.copy(title = t, group = g) } }
            labelOf = labels(chatId)
            // Beside the call, never before it: the photo fills in when this phone's store answers.
            scope.launch { loadPhotos(chatId) }
            when (val r = remote.start(chatId, video, newIdempotencyKey())) {
                is AppResult.Success -> { call = r.value; publish(); join(r.value) }
                is AppResult.Failure -> {
                    // A call already running in this chat (a group call someone started): join that one instead.
                    if ((r.error as? AppError.Api)?.code == "calls.already_in_call") {
                        val existing = (remote.forChat(chatId) as? AppResult.Success)?.value
                        if (existing != null) { call = existing; publish(); join(existing); return@launch }
                    }
                    fail(r.error)
                }
            }
        }
    }

    /**
     * The chat's call bar (2026-11-09): straight into the call going on in [chatId], no second confirmation. Back to it
     * when this phone is already on it (minimised). With no call on this phone the call screen opens at once,
     * "Connecting…", from what the bar knows ([video], [title], [group]); the call's own data replaces it when CL2
     * answers. A call on another chat is hung up only once CL2 says there is a call here to switch to (one call at a
     * time), never for nothing. [onGone] runs when there's no call to join any more, so the bar can drop at once.
     */
    fun joinExisting(
        chatId: String, video: Boolean = false, title: String = "", group: Boolean = true,
        onGone: () -> Unit = {},
        /** A switch from my other call that couldn't read this one: why, for the chat to say (my call goes on). */
        onFailed: (String) -> Unit = {},
    ) {
        val now = _state.value
        val busy = now != null && now.phase != CallPhase.Ended
        if (busy && now.chatId == chatId) { minimise(false); return }
        if (joiningBar) return // a second tap while the first is on its way
        joiningBar = true
        if (!busy) {
            takeOver()
            call = null // an End before CL2 answers hangs up nothing old
            _state.value = CallUi(null, chatId, title, video, group, CallPhase.Connecting, camera = video, speaker = true)
        }
        val mine = epoch
        scope.launch {
            try {
                val found = remote.forChat(chatId)
                val existing = (found as? AppResult.Success)?.value?.takeIf { it.state != "ENDED" }
                if (existing == null) {
                    val gone = found is AppResult.Success || (found as AppResult.Failure).error.let { it is AppError.Api && it.code == "calls.not_found" }
                    if (gone) onGone()
                    if (busy || epoch != mine || _state.value?.phase != CallPhase.Connecting) {
                        // my other call goes on; say why nothing changed
                        if (!gone && busy) onFailed(ErrorMessages.forError((found as AppResult.Failure).error))
                        return@launch
                    }
                    if (gone) { _state.update { it?.copy(phase = CallPhase.Ended, endReason = "COMPLETED") }; finish(mine) }
                    else fail((found as AppResult.Failure).error)
                    return@launch
                }
                // a ring took over meanwhile, or End was pressed on the "Connecting…" screen
                if (busy) { leave(); takeOver() } else if (epoch != mine || _state.value?.phase != CallPhase.Connecting) return@launch
                labelOf = labels(chatId)
                // Never a call without its screen: the call's own data stands in when the chat isn't on this phone yet.
                val (t, g) = names(chatId) ?: (title.ifBlank { "Group call" } to (group || existing.participants.size > 2))
                _state.value = CallUi(existing.callId, chatId, t, existing.kind == "VIDEO", g, CallPhase.Connecting, camera = existing.kind == "VIDEO", speaker = true)
                call = existing
                scope.launch { loadPhotos(chatId) }
                join(existing)
            } finally {
                joiningBar = false
            }
        }
    }

    private var joiningBar = false

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

    /** H2–H4: End (CL7). */
    fun leave() {
        val c = call
        _state.update { it?.copy(phase = CallPhase.Ended, endReason = it.endReason ?: "COMPLETED") }
        closeEngine()
        val mine = epoch
        scope.launch {
            if (c != null) (remote.leave(c.callId) as? AppResult.Success)?.value?.takeIf { it.state == "ENDED" }?.let { logEnded(it) }
            finish(mine)
        }
    }

    fun toggleMic() = media { it.copy(mic = !it.mic) }

    /** The camera only counts as on once it really is: no black "video" when permission was refused. */
    fun toggleCamera() {
        val want = !(_state.value?.camera ?: return)
        scope.launch {
            if (engine.setCamera(want)) media { it.copy(camera = want) }
            else _state.update { it?.copy(error = "Circaso needs the camera for video. Turn it on in Settings.") }
        }
    }
    fun toggleSpeaker() { _state.update { it?.copy(speaker = !it.speaker) }; _state.value?.let { engine.setSpeaker(it.speaker) } }
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
        call?.let { c -> scope.launch { remote.media(c.callId, next.mic, next.camera) } }
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
                labelOf = labels(c.chatId)
                val (title, group) = names(c.chatId) ?: (c.participants.firstOrNull { it.userId == c.startedBy }?.displayName.orEmpty() to false)
                val caller = c.participants.firstOrNull { it.userId == c.startedBy }?.displayName?.takeIf { it.isNotBlank() }
                // The photo gets a moment to be in the first frame (a store read is a few ms); the ring never waits longer.
                val token = photoToken
                val lookup = scope.async { photosFor(c.chatId) }
                val quick = kotlinx.coroutines.withTimeoutOrNull(FirstFramePhotoMillis) { lookup.await() }
                _state.value = CallUi(c.callId, c.chatId, title, c.kind == "VIDEO", group, CallPhase.Incoming, caller = caller)
                if (quick != null) applyPhotos(token, c.chatId, quick)
                publish()
                if (quick == null) scope.launch { applyPhotos(token, c.chatId, lookup.await()) }
                startReconcile()
            }
            "call.updated" -> data?.let { decode(it) }?.let { c -> if (c.callId == call?.callId) { call = c; publish(); pullNew() } }
            "call.ended" -> data?.let { decode(it) }?.let { c -> endedByServer(c) }
            // Our ring stopped without us answering (the caller hung up, our 45 s ran out in a group call going on).
            "call.ring_stopped" -> {
                val callId = data?.get("callId")?.jsonPrimitive?.contentOrNull ?: return
                stopRinging(callId)
                if (callId != call?.callId || _state.value?.phase != CallPhase.Incoming) return
                _state.update { it?.copy(phase = CallPhase.Ended, endReason = data["reason"]?.jsonPrimitive?.contentOrNull ?: "MISSED") }
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
        _state.update { it?.copy(phase = CallPhase.Ended, endReason = c.endReason) }
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
            finish(epoch)
            return
        }
        if (!s.group && sessionId != null) {
            // a direct call: the other person hung up (the server ends it then; this covers an older server)
            val other = fresh.participants.firstOrNull { it.userId != me }
            if (other != null && other.joinedAt != null && other.state != "JOINED") return leave()
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
            while (true) {
                delay(pollMillis)
                reconcile()
                if (stallMillis <= 0 || _state.value?.phase != CallPhase.Active) { lastPackets = null; continue }
                val packets = engine.receivedPackets() ?: continue
                val now = clock()
                if (packets != lastPackets) { lastPackets = packets; quietSince = now; continue }
                if (now - quietSince >= stallMillis) {
                    com.circaso.core.common.Diagnostics.note("calls: no packets for ${now - quietSince}ms, hanging up")
                    _state.update { it?.copy(error = "The call dropped.") }
                    leave()
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
            "COMPLETED" -> if (joined && c.startedAt != null) CallLogOutcome.Completed else if (outgoing) CallLogOutcome.NoAnswer else CallLogOutcome.Missed
            "MISSED" -> if (outgoing) CallLogOutcome.NoAnswer else CallLogOutcome.Missed
            "DECLINED" -> CallLogOutcome.Declined
            "CANCELLED" -> if (outgoing) CallLogOutcome.Cancelled else CallLogOutcome.Missed
            else -> CallLogOutcome.Failed
        }
        val start = c.startedAt?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
        val end = c.endedAt?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
        val created = runCatching { kotlin.time.Instant.parse(c.createdAt).toEpochMilliseconds() }.getOrNull() ?: nowMillis()
        val seconds = if (outcome == CallLogOutcome.Completed && start != null && end != null) ((end - start) / 1000).coerceAtLeast(0) else 0
        log(CallLogEntry(c.chatId, c.callId, outgoing, c.kind == "VIDEO", outcome, seconds, c.startedBy, created))
    }

    private fun decode(o: JsonObject): CallDto? = runCatching { json.decodeFromJsonElement(CallDto.serializer(), o) }.getOrNull()

    private suspend fun join(c: CallDto) = lock.withLock {
        val me = myUserId() ?: return@withLock
        try {
            val servers = c.iceServers.map { IceServerSpec(it.urls, it.username, it.credential) }.ifEmpty { ice }.ifEmpty { listOf(IceServerSpec(listOf("stun:stun.cloudflare.com:3478"))) }
            val offer = engine.start(servers, c.kind == "VIDEO", me)
            when (val r = remote.join(c.callId, JoinCallRequest(offer.sdp, offer.tracks.map { LocalTrackDto(it.mid, it.trackName, it.kind) }))) {
                is AppResult.Success -> {
                    engine.acceptAnswer(r.value.answer)
                    sessionId = r.value.sessionId
                    joinedAtMillis = clock()
                    call = r.value.call
                    val s = _state.value
                    engine.setMicrophone(s?.mic ?: true)
                    engine.setSpeaker(s?.speaker ?: false)
                    watch()
                    // Tell everyone the real state: a pushed video track isn't a camera that's on (CL8).
                    remote.media(c.callId, s?.mic ?: true, s?.camera ?: false)
                    publish()
                }
                is AppResult.Failure -> { fail(r.error); return@withLock }
            }
        } catch (e: CallEngineException) {
            fail(null, if (e.reason == "microphone") "Circaso needs the microphone for calls. Turn it on in Settings." else "The call couldn't start on this phone.")
            remote.leave(c.callId)
            return@withLock
        }
        pullNewLocked()
    }

    private fun pullNew() { scope.launch { lock.withLock { pullNewLocked() } } }

    /**
     * Pull [p] now? Cloudflare's order is publish, wait for `connected`, then subscribe: a pull before both
     * connections are up is held ~9 s by the SFU (CL4 took 8.8 s every time, 2026-11-09). So wait for this phone's
     * connection and their `connected` (CL3b), each at most [pullGraceMillis] (an older app never says it).
     */
    private fun ready(p: com.circaso.feature.calls.data.ParticipantDto): Boolean {
        if (pullGraceMillis <= 0) return true
        val now = clock()
        val mineUp = engine.connected.value == true || now - joinedAtMillis >= pullGraceMillis
        val seen = firstSeen.getOrPut("${p.userId}/${p.connection}") { now }
        val theirsUp = p.connected || now - seen >= pullGraceMillis
        return mineUp && theirsUp
    }

    /** CL4 for everyone who has joined and isn't pulled yet, then the SFU's offer answered (CL5). */
    private suspend fun pullNewLocked() {
        val c = call ?: return
        val session = sessionId ?: return
        val me = myUserId()
        val waiting = c.participants.filter { p ->
            p.userId != me && p.state == "JOINED" && p.tracks.isNotEmpty() && (p.userId !in pulled || pulled[p.userId] != p.connection)
        }
        val fresh = waiting.filter { ready(it) }
        if (fresh.size < waiting.size && deferredPull?.isActive != true) {
            deferredPull = scope.launch { delay(pullGraceMillis); pullNew() }
        }
        if (fresh.isEmpty()) return
        val r = remote.pull(c.callId, PullRequest(session, fresh.flatMap { p -> p.tracks.map { PullTrackRequest(p.userId, it.trackName) } }))
        val pull = (r as? AppResult.Success)?.value ?: return
        pull.tracks.forEach { t ->
            if (t.trackName.startsWith("video") && t.mid != null) midOf[t.userId] = t.mid
            t.mid?.let { mediaMids[t.userId] = mediaMids[t.userId].orEmpty() + it }
        }
        com.circaso.core.common.Diagnostics.note("calls: pulled ${pull.tracks.joinToString { it.trackName.substringBefore('-') + "@" + (it.mid ?: "none") }} renegotiate=${pull.requiresRenegotiation}")
        val offer = pull.offer
        if (offer != null) {
            val answer = runCatching { engine.answerOffer(offer) }.getOrNull() ?: return
            remote.renegotiate(c.callId, RenegotiateRequest(session, answer))
        }
        fresh.forEach { pulled[it.userId] = it.connection }
        publish()
    }

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
        if (c.iceServers.isNotEmpty()) ice = c.iceServers.map { IceServerSpec(it.urls, it.username, it.credential) }
        val me = myUserId()
        val receiving = engine.receivingMids?.value
        val people = c.participants.filter { it.userId != me }.map { p ->
            val joined = p.state == "JOINED"
            val heard = receiving == null || graceOver || mediaMids[p.userId].orEmpty().any { it in receiving }
            CallPerson(
                p.userId, p.displayName.ifBlank { labelOf[p.userId] ?: "Someone" }, joined, p.audio, p.video, p.state == "INVITED", midOf[p.userId],
                handle = labelOf[p.userId], live = joined && heard, photoUrl = photosOf.people[p.userId],
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

    private fun fail(error: AppError?, message: String? = null) {
        closeEngine()
        reconcileJob?.cancel()
        _state.update { it?.copy(phase = CallPhase.Ended, error = message ?: error?.let { e -> ErrorMessages.forError(e) }, endReason = it.endReason ?: "FAILED") }
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
            kotlinx.coroutines.flow.combine(engine.connected, online) { media, net -> net && media != false }
                .collect { healthy ->
                    val s = _state.value ?: return@collect
                    if (s.phase == CallPhase.Ended || sessionId == null) return@collect
                    if (!healthy) {
                        if (s.phase != CallPhase.Reconnecting) {
                            _state.update { it?.copy(phase = CallPhase.Reconnecting) }
                            reconnectJob?.cancel()
                            reconnectJob = scope.launch { reconnect() }
                        }
                    }
                }
        }
    }

    private var reconnectJob: Job? = null

    private suspend fun reconnect() {
        val started = nowMillis()
        while (nowMillis() - started < reconnectGiveUpMillis) {
            // Wait for the network, then a moment for the media connection to come back on its own (ICE often does).
            val back = kotlinx.coroutines.withTimeoutOrNull(reconnectGiveUpMillis - (nowMillis() - started)) { online.first { it } } ?: break
            if (back) delay(2_500)
            if (engine.connected.value == true) { _state.update { it?.copy(phase = CallPhase.Active) }; return }
            if (rejoin()) { _state.update { it?.copy(phase = CallPhase.Active) }; return }
            delay(3_000)
        }
        _state.update { it?.copy(error = "The call dropped and couldn't reconnect.") }
        leave()
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
                    remote.media(c.callId, s?.mic ?: true, s?.camera ?: false)
                    pulled.clear()
                    midOf.clear()
                    mediaMids.clear()
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
        reconcileJob?.cancel()
        connectedJob?.cancel()
        deferredPull?.cancel()
        firstSeen.clear()
        reportedConnected = null
        joinedAtMillis = 0L
        watchJob?.cancel()
        reconnectJob?.cancel()
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
        midOf.clear()
        _state.value = null
    }
}

/** How long the call waits on this phone's store for photos before it goes on without them. */
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
