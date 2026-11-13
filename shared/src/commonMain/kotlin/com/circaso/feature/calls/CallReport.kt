package com.circaso.feature.calls

import com.circaso.core.calls.CallEngine
import com.circaso.core.calls.CallStats
import com.circaso.core.common.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.roundToLong

/**
 * Why this phone's side of a call ended (2026-11-13). Three calls dropped that day and nothing on the phone said why;
 * every local end now names its cause, sent with CL7 and in the call report. [wire] is the name the server stores.
 */
enum class CallEndCause(val wire: String) {
    /** The person pressed End (or the system's call screen's End). */
    UserHangup("USER_HANGUP"),
    /** The person declined a ring. */
    Declined("DECLINED"),
    /** The server said the call is over (`call.ended`, or a read of it). */
    RemoteEnded("REMOTE_ENDED"),
    /** A re-read showed the other person in a direct call had left. */
    RemoteLeft("REMOTE_LEFT"),
    /** Our ring stopped without an answer. */
    RingStopped("RING_STOPPED"),
    /** Answered on another of the person's phones. */
    AnsweredElsewhere("ANSWERED_ELSEWHERE"),
    /** No media packets arrived, and reconnecting didn't bring them back. */
    Stall("STALL"),
    /** The network or the media connection dropped and 40 s of reconnecting failed. */
    ReconnectGaveUp("RECONNECT_GAVE_UP"),
    /** The server refused or failed the join. */
    JoinFailed("JOIN_FAILED"),
    /** No microphone permission. */
    PermissionDenied("PERMISSION_DENIED"),
    /** WebRTC couldn't start on this phone. */
    EngineFailed("ENGINE_FAILED"),
    /** The app was closed or killed mid-call; found at the next launch. */
    AppKilled("APP_KILLED"),
    Other("OTHER"),
    ;

    /** Ends the server should record as dropped rather than completed. */
    val dropped: Boolean get() = this == Stall || this == ReconnectGaveUp
}

@Serializable data class CallReportEvent(val t: Long, val type: String, val detail: String? = null)

@Serializable data class CallReportSample(
    val t: Long,
    val rttMs: Double? = null,
    val jitterMs: Double? = null,
    val lossPct: Double? = null,
    val packetsReceived: Long? = null,
    val bytesReceived: Long? = null,
)

@Serializable data class CallReportSummary(
    val durationMs: Long? = null,
    val samples: Int = 0,
    val avgRttMs: Double? = null,
    val maxRttMs: Double? = null,
    val avgJitterMs: Double? = null,
    val maxJitterMs: Double? = null,
    val lossPct: Double? = null,
    val packetsReceived: Long? = null,
    val bytesReceived: Long? = null,
    val reconnects: Int = 0,
)

/** CL10 `POST /v1/calls/{callId}/report`: no message content, phone numbers or names, only how the call went. */
@Serializable data class CallReportRequest(
    val platform: String,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val video: Boolean,
    val outgoing: Boolean,
    val startedAt: String,
    val endedAt: String,
    val endCause: String,
    val events: List<CallReportEvent>,
    val samples: List<CallReportSample>,
    val summary: CallReportSummary,
)

/** A report waiting in the outbox: which call it's for, and the body. */
@Serializable data class QueuedCallReport(val callId: String, val report: CallReportRequest)

/** The device line of a report; never anything about the person. */
data class CallReportDevice(val platform: String, val appVersion: String, val osVersion: String, val deviceModel: String)

/** Where a call's timeline is kept while it runs, so a call cut off by the app being killed is still reported. */
interface CallTimelineStore {
    suspend fun save(snapshot: String)
    suspend fun load(): String?
    suspend fun clear()
}

/** The in-progress state written to [CallTimelineStore]. */
@Serializable internal data class TimelineSnapshot(
    val callId: String? = null,
    val video: Boolean = false,
    val outgoing: Boolean = false,
    val joined: Boolean = false,
    val startedAt: Long = 0,
    val lastSeenAt: Long = 0,
    val events: List<CallReportEvent> = emptyList(),
    val samples: List<CallReportSample> = emptyList(),
    val totals: Totals = Totals(),
)

/** Running aggregates over every sample, also the ones thinned out of the kept list. */
@Serializable internal data class Totals(
    val samples: Int = 0,
    val rttSum: Double = 0.0,
    val rttCount: Int = 0,
    val rttMax: Double? = null,
    val jitterSum: Double = 0.0,
    val jitterCount: Int = 0,
    val jitterMax: Double? = null,
    val firstReceived: Long? = null,
    val firstLost: Long? = null,
    val lastReceived: Long? = null,
    val lastLost: Long? = null,
    val lastBytes: Long? = null,
    val reconnects: Int = 0,
    /** Counters drop back to 0 when a rejoin opens a new connection: what earlier connections received and lost. */
    val carriedReceived: Long = 0,
    val carriedLost: Long = 0,
    val carriedBytes: Long = 0,
)

/**
 * The per-call timeline behind "why did this call end" (2026-11-13): network and transport changes, the realtime
 * socket, the media connection's states, reconnects, mic/camera/speaker, a WebRTC stats sample every
 * [sampleMillis], and the end cause. When the call ends a compact report is handed to [submit] (the outbox, so it
 * waits out a dead network). A call this phone never joined (missed, declined while ringing) sends nothing.
 */
@OptIn(ExperimentalAtomicApi::class)
class CallRecorder(
    private val scope: CoroutineScope,
    private val engine: CallEngine,
    private val device: () -> CallReportDevice,
    private val submit: suspend (QueuedCallReport) -> Unit,
    private val store: CallTimelineStore? = null,
    /** "online"/"offline" from the operating system. */
    private val network: Flow<String>? = null,
    /** "wifi", "cellular", "ethernet", "other", "none". */
    private val transport: Flow<String>? = null,
    /** Whether our server answers ("online"/"offline"). */
    private val reach: Flow<String>? = null,
    /** The realtime socket ("up"/"down"). */
    private val socket: Flow<String>? = null,
    private val clock: () -> Long = { com.circaso.core.sync.nowMillis() },
    private val sampleMillis: Long = 5_000,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val lock = Mutex()
    /** Changed from the call's coroutines on any thread: every change is a compare-and-set, so none is lost. */
    private val live = AtomicReference<TimelineSnapshot?>(null)
    private val current: TimelineSnapshot? get() = live.load()
    private var jobs: List<Job> = emptyList()
    private var lastSaved = 0L

    val recording: Boolean get() = current != null

    /** A new call's timeline; any earlier one still open is closed as [CallEndCause.Other]. */
    fun begin(callId: String?, video: Boolean, outgoing: Boolean) {
        val now = clock()
        val earlier = live.exchange(TimelineSnapshot(callId, video, outgoing, startedAt = now, lastSeenAt = now))
        jobs.forEach { it.cancel() }
        if (earlier != null) scope.launch { close(earlier, CallEndCause.Other, now) }
        event("start", if (outgoing) "outgoing" else "incoming")
        jobs = listOfNotNull(
            network?.let { watch(it, "net") },
            transport?.let { watch(it, "transport") },
            reach?.let { watch(it, "reach") },
            socket?.let { watch(it, "socket") },
            engine.linkStates?.let { states ->
                scope.launch { states.collect { s -> s.split(':', limit = 2).let { event(it[0], it.getOrNull(1)) } } }
            },
            scope.launch { sampleLoop() },
        )
    }

    fun callId(id: String) = change { it.copy(callId = id) }

    /** This phone joined (CL3 answered): only joined calls are reported. */
    fun joined() = change { it.copy(joined = true) }

    /** Applies [f] to the open timeline, if any, without losing a change made at the same moment elsewhere. */
    private fun change(f: (TimelineSnapshot) -> TimelineSnapshot) {
        while (true) {
            val c = live.load() ?: return
            if (live.compareAndSet(c, f(c))) return
        }
    }

    fun event(type: String, detail: String? = null) {
        if (current == null) return
        val now = clock()
        change { c ->
            val e = CallReportEvent((now - c.startedAt).coerceAtLeast(0), type.take(32), detail?.take(160))
            var events = c.events + e
            // The first moments and the last ones matter most: keep the first [KeepFirst], then the most recent.
            if (events.size > MaxEvents) events = events.take(KeepFirst) + events.takeLast(MaxEvents - KeepFirst)
            val reconnect = type == "reconnect" && detail?.startsWith("start") == true
            c.copy(events = events, lastSeenAt = now, totals = if (reconnect) c.totals.copy(reconnects = c.totals.reconnects + 1) else c.totals)
        }
        persistSoon()
    }

    /** Closes the timeline with [cause] and queues its report; once per call. */
    fun finish(cause: CallEndCause) {
        val c = live.exchange(null) ?: return
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        val now = clock()
        scope.launch { close(c.withEvent(now, "end", cause.wire), cause, now) }
    }

    /** At launch: a timeline left by a call the app didn't live to end is reported as [CallEndCause.AppKilled]. */
    suspend fun recoverInterrupted() {
        val raw = runCatching { store?.load() }.getOrNull() ?: return
        val snapshot = runCatching { json.decodeFromString(TimelineSnapshot.serializer(), raw) }.getOrNull()
        if (snapshot == null) { store?.clear(); return }
        if (current != null) return
        Diagnostics.note("calls: a call cut off by the app closing was found, reporting it")
        close(snapshot.withEvent(snapshot.lastSeenAt, "end", CallEndCause.AppKilled.wire), CallEndCause.AppKilled, snapshot.lastSeenAt)
    }

    private fun TimelineSnapshot.withEvent(at: Long, type: String, detail: String) =
        copy(events = (events + CallReportEvent((at - startedAt).coerceAtLeast(0), type, detail)).takeLast(MaxEvents))

    private suspend fun close(c: TimelineSnapshot, cause: CallEndCause, endedAt: Long) {
        lock.withLock { runCatching { store?.clear() } }
        val callId = c.callId ?: return
        if (!c.joined && cause != CallEndCause.JoinFailed && cause != CallEndCause.PermissionDenied && cause != CallEndCause.EngineFailed) return
        val report = build(c, cause, endedAt)
        try {
            submit(QueuedCallReport(callId, report))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Diagnostics.note("calls: couldn't queue the call report (${e::class.simpleName})")
        }
    }

    internal fun build(c: TimelineSnapshot, cause: CallEndCause, endedAt: Long): CallReportRequest {
        val d = device()
        val t = c.totals
        val received = t.lastReceived?.let { it - (t.firstReceived ?: 0) + t.carriedReceived }
        val lost = t.lastLost?.let { it - (t.firstLost ?: 0) + t.carriedLost }
        val lossPct = if (received != null && lost != null && received + lost > 0) round2(lost * 100.0 / (received + lost)) else null
        val summary = CallReportSummary(
            durationMs = (endedAt - c.startedAt).coerceAtLeast(0),
            samples = t.samples,
            avgRttMs = if (t.rttCount > 0) round2(t.rttSum / t.rttCount) else null,
            maxRttMs = t.rttMax,
            avgJitterMs = if (t.jitterCount > 0) round2(t.jitterSum / t.jitterCount) else null,
            maxJitterMs = t.jitterMax,
            lossPct = lossPct,
            packetsReceived = received,
            bytesReceived = t.lastBytes?.let { it + t.carriedBytes },
            reconnects = t.reconnects,
        )
        var report = CallReportRequest(
            platform = d.platform.take(16), appVersion = d.appVersion.take(40), osVersion = d.osVersion.take(40), deviceModel = d.deviceModel.take(80),
            video = c.video, outgoing = c.outgoing,
            startedAt = iso(c.startedAt), endedAt = iso(endedAt), endCause = cause.wire,
            events = c.events, samples = c.samples, summary = summary,
        )
        // Well under the server's 64 KiB: thin the samples, then the events, until it fits.
        while (json.encodeToString(CallReportRequest.serializer(), report).length > MaxReportChars && (report.samples.size > 1 || report.events.size > 20)) {
            report = if (report.samples.size > 1) report.copy(samples = report.samples.filterIndexed { i, _ -> i % 2 == 0 })
            else report.copy(events = report.events.take(10) + report.events.takeLast(report.events.size / 2))
        }
        return report
    }

    private fun watch(flow: Flow<String>, type: String): Job = scope.launch {
        // The state at the start of the call, then every change.
        var first = true
        flow.distinctUntilChanged().collect { v -> event(type, if (first) "$v (at start)" else v); first = false }
    }

    private suspend fun sampleLoop() {
        while (scope.isActive) {
            delay(sampleMillis)
            val s = runCatching { engine.stats() }.getOrNull() ?: continue
            sample(s)
        }
    }

    internal fun sample(s: CallStats) {
        if (current == null) return
        val now = clock()
        change { c -> withSample(c, s, now) }
        persistSoon()
    }

    private fun withSample(c: TimelineSnapshot, s: CallStats, now: Long): TimelineSnapshot {
        val t0 = c.totals
        // A rejoin opens a fresh connection whose counters start again from 0: carry the old one's totals.
        val reset = (s.packetsReceived != null && t0.lastReceived != null && s.packetsReceived < t0.lastReceived)
        val t = if (reset) t0.copy(
            carriedReceived = t0.carriedReceived + (t0.lastReceived ?: 0) - (t0.firstReceived ?: 0),
            carriedLost = t0.carriedLost + (t0.lastLost ?: 0) - (t0.firstLost ?: 0),
            carriedBytes = t0.carriedBytes + (t0.lastBytes ?: 0),
            firstReceived = 0, firstLost = 0, lastReceived = null, lastLost = null, lastBytes = null,
        ) else t0
        val dReceived = if (s.packetsReceived != null && t.lastReceived != null) s.packetsReceived - t.lastReceived else null
        val dLost = if (s.packetsLost != null && t.lastLost != null) (s.packetsLost - t.lastLost).coerceAtLeast(0) else null
        val lossPct = if (dReceived != null && dLost != null && dReceived + dLost > 0) round2(dLost * 100.0 / (dReceived + dLost)) else null
        val point = CallReportSample(
            t = (now - c.startedAt).coerceAtLeast(0),
            rttMs = s.rttMs?.let(::round2), jitterMs = s.jitterMs?.let(::round2), lossPct = lossPct,
            packetsReceived = s.packetsReceived, bytesReceived = s.bytesReceived,
        )
        var samples = c.samples + point
        // A long call keeps its whole length at a coarser grain rather than only its last minutes.
        if (samples.size > MaxSamples) samples = samples.filterIndexed { i, _ -> i % 2 == 0 }
        val totals = t.copy(
            samples = t.samples + 1,
            rttSum = t.rttSum + (s.rttMs ?: 0.0), rttCount = t.rttCount + if (s.rttMs != null) 1 else 0,
            rttMax = maxOfNullable(t.rttMax, s.rttMs?.let(::round2)),
            jitterSum = t.jitterSum + (s.jitterMs ?: 0.0), jitterCount = t.jitterCount + if (s.jitterMs != null) 1 else 0,
            jitterMax = maxOfNullable(t.jitterMax, s.jitterMs?.let(::round2)),
            firstReceived = t.firstReceived ?: s.packetsReceived, firstLost = t.firstLost ?: s.packetsLost,
            lastReceived = s.packetsReceived ?: t.lastReceived, lastLost = s.packetsLost ?: t.lastLost,
            lastBytes = s.bytesReceived ?: t.lastBytes,
        )
        return c.copy(samples = samples, totals = totals, lastSeenAt = now)
    }

    /** Written at most every few seconds: enough to report a killed call, cheap enough to run all call long. */
    private fun persistSoon() {
        val s = store ?: return
        val now = clock()
        if (now - lastSaved < PersistEveryMillis) return
        lastSaved = now
        val snapshot = current ?: return
        scope.launch {
            lock.withLock {
                if (current == null) return@withLock // ended meanwhile; never resurrect a finished call
                runCatching { s.save(json.encodeToString(TimelineSnapshot.serializer(), snapshot)) }
            }
        }
    }

    private fun maxOfNullable(a: Double?, b: Double?) = when { a == null -> b; b == null -> a; else -> maxOf(a, b) }
    private fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0
    private fun iso(millis: Long) = kotlin.time.Instant.fromEpochMilliseconds(millis).toString()

    companion object {
        const val MaxEvents = 300
        const val KeepFirst = 60
        const val MaxSamples = 240
        const val MaxReportChars = 56_000
        const val PersistEveryMillis = 5_000L
    }
}
