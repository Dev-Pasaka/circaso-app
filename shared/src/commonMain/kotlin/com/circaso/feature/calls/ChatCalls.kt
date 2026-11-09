package com.circaso.feature.calls

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.calls.data.CallDto
import com.circaso.feature.calls.data.CallsRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Someone on a call going on in a chat. */
data class LiveCallPerson(val userId: String, val name: String)

/**
 * A call going on in a chat, as the chats list and the thread's call bar show it (2026-11-09): [joined] is who is on
 * it now (never empty: a call nobody is on isn't shown), [sinceMillis] when it began (the server's `startedAt`, or the
 * first join while one person waits in a group call), [meOn] whether I'm on it, from this phone or another of mine.
 */
data class LiveChatCall(
    val callId: String,
    val chatId: String,
    val video: Boolean,
    val joined: List<LiveCallPerson>,
    val sinceMillis: Long?,
    val meOn: Boolean,
)

/**
 * Every call going on in my chats, so a chat shows "Call in progress" whether or not I'm on it (Pasaka, 2026-11-09).
 *
 * The server sends `calls/call.updated` and `call.ended` to everyone a call rang, on it or not, and lists the calls
 * going on with CL2c (`GET /v1/calls/live`). Frames keep this current within a second; the list is read again each
 * time the socket comes back (it dropped every ~2 minutes on 2026-11-09, and a `call.ended` lost with it must not
 * leave a "call in progress" bar up) and, while any call is shown and the socket is up, every [pollMillis]. A chat
 * that opens reads its own call (CL2 by chat), so the bar there is never older than the chat.
 *
 * A server without CL2c (before backend PR #81) still self-heals: each call shown is read again with CL2 and dropped
 * when it says `ENDED`.
 */
class ChatCalls(
    private val remote: CallsRemote,
    private val myUserId: () -> String?,
    private val scope: CoroutineScope,
    /** True while the realtime socket is up. */
    private val socketUp: Flow<Boolean>,
    /** This phone's own call, so leaving it here drops me from its bar at once rather than at the frame. */
    private val myCall: Flow<CallUi?> = kotlinx.coroutines.flow.flowOf(null),
    private val pollMillis: Long = 45_000,
) {
    private val _calls = MutableStateFlow<Map<String, LiveChatCall>>(emptyMap())
    /** By chat id. */
    val calls: StateFlow<Map<String, LiveChatCall>> = _calls.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    /** Bumped by every frame; [changedAt] keeps, per chat, the bump of its last frame, so a slower read never undoes a newer frame. */
    private var generation = 0L
    private val changedAt = mutableMapOf<String, Long>()
    /** Calls the server said are over: a read that started before it can't bring them back. */
    private val ended = ArrayDeque<String>()
    private var started = false
    private var pollJob: Job? = null

    /** Starts listening to the socket and this phone's call; idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            socketUp.distinctUntilChanged().collect { up -> if (up) refresh() }
        }
        scope.launch {
            var last: CallUi? = null
            myCall.collect { now ->
                val was = last
                last = now
                if (was?.callId != null && was.phase != CallPhase.Ended && (now == null || now.callId != was.callId || now.phase == CallPhase.Ended)) {
                    leftHere(was.callId, was.chatId, was.group)
                }
            }
        }
        scope.launch {
            combine(socketUp.distinctUntilChanged(), _calls) { up, calls -> up && calls.isNotEmpty() }.distinctUntilChanged().collect { poll ->
                pollJob?.cancel()
                if (poll && pollMillis > 0) pollJob = scope.launch { while (true) { delay(pollMillis); refresh() } }
            }
        }
    }

    /** A `calls` realtime frame (the same ones [CallController] gets). */
    suspend fun onFrame(type: String, data: JsonObject?) {
        when (type) {
            "call.updated", "call.ended" -> {
                val c = data?.let { runCatching { json.decodeFromJsonElement(CallDto.serializer(), it) }.getOrNull() } ?: return
                lock.withLock {
                    generation++
                    changedAt[c.chatId] = generation
                    if (type == "call.ended" || c.state == "ENDED") remember(c.callId)
                    put(c.chatId, c)
                }
            }
            // A ring: the caller joins at once and `call.updated` follows; read it now in case that frame is lost.
            "call.ringing" -> {
                val callId = data?.get("callId")?.jsonPrimitive?.contentOrNull ?: return
                val since = lock.withLock { generation }
                // beside the frames, never holding them up
                scope.launch {
                    val c = (remote.get(callId) as? AppResult.Success)?.value ?: return@launch
                    lock.withLock { if ((changedAt[c.chatId] ?: 0) <= since && c.callId !in ended && toLive(c) != null) put(c.chatId, c) }
                }
            }
        }
    }

    /** Reads every call going on in my chats (CL2c) and replaces what's shown, keeping anything a frame changed meanwhile. */
    suspend fun refresh() {
        val since = lock.withLock { generation }
        when (val r = remote.live()) {
            is AppResult.Success -> lock.withLock {
                val fresh = r.value.filter { it.callId !in ended }.mapNotNull { c -> toLive(c)?.let { c.chatId to it } }.toMap()
                _calls.update { old ->
                    val keep = old.filterKeys { (changedAt[it] ?: 0) > since }
                    fresh.filterKeys { (changedAt[it] ?: 0) <= since } + keep
                }
            }
            // An older server, or no answer: check each call shown on its own (CL2) and drop what's over.
            is AppResult.Failure -> _calls.value.values.toList().forEach { shown -> recheck(shown.chatId, shown.callId) }
        }
    }

    /** A chat opened: read its call (CL2 by chat), so its bar is right whatever frames were missed. */
    suspend fun refreshChat(chatId: String) {
        val since = lock.withLock { generation }
        val r = remote.forChat(chatId)
        lock.withLock {
            if ((changedAt[chatId] ?: 0) > since) return
            when {
                r is AppResult.Success -> if (r.value.callId in ended) drop(chatId) else put(chatId, r.value)
                (r as AppResult.Failure).error.let { it is AppError.Api && it.code == "calls.not_found" } -> drop(chatId)
                else -> Unit // offline: keep what's shown until the socket says otherwise
            }
        }
    }

    /** [refreshChat] from a callback: the call to join was gone, so the bar goes now. */
    fun recheck(chatId: String) { scope.launch { refreshChat(chatId) } }

    private suspend fun recheck(chatId: String, callId: String) {
        val since = lock.withLock { generation }
        val r = remote.get(callId)
        lock.withLock {
            if ((changedAt[chatId] ?: 0) > since) return
            when {
                r is AppResult.Success -> { if (r.value.state == "ENDED") remember(callId); put(chatId, r.value) }
                (r as AppResult.Failure).error.let { it is AppError.Api && it.code == "calls.not_found" } -> drop(chatId)
                else -> Unit
            }
        }
    }

    /**
     * I left [callId] on this phone (hung up, or it ended here). A direct call is over for both at once; in a group the
     * call goes on, so only I come off it. The frame that follows confirms either.
     */
    private suspend fun leftHere(callId: String, chatId: String, group: Boolean) = lock.withLock {
        val shown = _calls.value[chatId]?.takeIf { it.callId == callId } ?: return@withLock
        val me = myUserId()
        if (!group) { remember(callId); drop(chatId); return@withLock }
        val rest = shown.joined.filter { it.userId != me }
        if (rest.isEmpty()) drop(chatId) else _calls.update { it + (chatId to shown.copy(joined = rest, meOn = false)) }
    }

    private fun put(chatId: String, c: CallDto) {
        val live = toLive(c)
        if (live == null) drop(chatId) else _calls.update { it + (chatId to live) }
    }

    private fun drop(chatId: String) = _calls.update { if (chatId in it) it - chatId else it }

    private fun remember(callId: String) {
        if (callId in ended) return
        ended.addLast(callId)
        while (ended.size > 64) ended.removeFirst()
    }

    private fun toLive(c: CallDto): LiveChatCall? = liveCallOf(c, myUserId())
}

/** [c] as a chat shows it, or null when it isn't going on (ended, or nobody on it yet). */
internal fun liveCallOf(c: CallDto, me: String?): LiveChatCall? {
    if (c.state == "ENDED") return null
    val on = c.participants.filter { it.state == "JOINED" }
    if (on.isEmpty()) return null
    fun millis(iso: String?) = iso?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
    val since = millis(c.startedAt) ?: on.mapNotNull { millis(it.joinedAt) }.minOrNull()
    return LiveChatCall(
        callId = c.callId,
        chatId = c.chatId,
        video = c.kind == "VIDEO",
        joined = on.map { LiveCallPerson(it.userId, it.displayName) },
        sinceMillis = since,
        meOn = on.any { it.userId == me },
    )
}

/**
 * Who is on a call, for its bar: "Amina", "Amina and Juma", "Amina and 2 others". Me last and only when nobody else
 * is ("You"). [nameOf] prefers the name this phone knows them by.
 */
fun whoIsOn(people: List<LiveCallPerson>, me: String?, nameOf: (LiveCallPerson) -> String = { it.name }): String {
    val others = people.filter { it.userId != me }
    if (others.isEmpty()) return if (people.isNotEmpty()) "You" else ""
    val first = nameOf(others.first()).trim().substringBefore(' ').ifEmpty { "Someone" }
    return when (others.size) {
        1 -> first
        2 -> "$first and ${nameOf(others[1]).trim().substringBefore(' ').ifEmpty { "someone" }}"
        else -> "$first and ${others.size - 1} others"
    }
}

/** A call's running time, as the call screen shows it: "00:42", "12:05", "1:02:09". */
fun callClock(elapsedMillis: Long): String {
    val s = (elapsedMillis / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(sec)}" else "${two(m)}:${two(sec)}"
}
