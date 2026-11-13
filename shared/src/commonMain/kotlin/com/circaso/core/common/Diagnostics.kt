package com.circaso.core.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * The app's technical notes (2026-11-13). Release builds used to keep nothing, so when a call dropped nothing on the
 * phone said why. Every [note] is now scrubbed of secrets ([LogScrubber]), stamped with the time and kept in a rolling
 * file on the phone ([log], about 2 MB and 48 hours) that the person can send from You → Help → "Send diagnostic log".
 * Debug builds still print each note to the console ([sink]); the crash reporter gets them as breadcrumbs.
 *
 * Never note message text, names, phone numbers, emails, tokens, keys, PINs or amounts: the scrubber is a net, not a
 * licence.
 */
object Diagnostics {
    /** The debug console (logcat, Xcode); null in release builds. */
    var sink: ((String) -> Unit)? = null

    /** The crash reporter's breadcrumbs (Crashlytics `log`), when it is in the build. */
    var breadcrumbs: ((String) -> Unit)? = null

    /** The rolling file, set at launch on both platforms; null in tests. */
    var log: DiagnosticsLog? = null

    fun note(message: String) {
        val clean = LogScrubber.scrub(message)
        sink?.invoke(clean)
        breadcrumbs?.let { runCatching { it(clean) } }
        log?.append(clean)
    }
}

/**
 * The rolling diagnostic log: `diagnostics-0.log` takes new lines; once it passes [maxFileBytes] or is a day old it
 * becomes `diagnostics-1.log` (replacing the last one), so at most about twice [maxFileBytes] and two days are kept.
 * [read] gives everything from the last [maxAgeMillis], oldest first. Lines are written in order on one background
 * writer, a few at a time; a write that fails is dropped (a log must never take the app down).
 */
class DiagnosticsLog(
    private val fs: FileSystem,
    private val dir: Path,
    private val clock: () -> Long = { com.circaso.core.sync.nowMillis() },
    private val maxFileBytes: Long = 1_000_000,
    private val maxAgeMillis: Long = 48 * 60 * 60 * 1000L,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val current = dir / "diagnostics-0.log"
    private val previous = dir / "diagnostics-1.log"
    private val lines = Channel<String>(capacity = 2_048, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val io = Mutex()
    /** When [current] got its first line; it rolls over a day later. */
    private var startedAt: Long? = null

    init {
        scope.launch {
            runCatching { fs.createDirectories(dir) }
            for (first in lines) {
                val batch = buildString {
                    append(first).append('\n')
                    while (true) { append(lines.tryReceive().getOrNull() ?: break).append('\n') }
                }
                io.withLock { runCatching { write(batch) } }
            }
        }
    }

    fun append(message: String) {
        lines.trySend(stamp(clock()) + " " + message.replace('\n', ' '))
    }

    private fun write(batch: String) {
        val now = clock()
        val size = fs.metadataOrNull(current)?.size ?: 0L
        val started = startedAt ?: firstStamp(current)?.also { startedAt = it }
        if (size > 0 && (size + batch.length > maxFileBytes || (started != null && now - started > maxAgeMillis / 2))) {
            fs.delete(previous, mustExist = false)
            fs.atomicMove(current, previous)
            startedAt = null
        }
        if (startedAt == null) startedAt = now
        fs.appendingSink(current, mustExist = false).buffer().use { it.writeUtf8(batch) }
    }

    /** Everything from the last 48 hours, oldest first; waits for lines still being written. */
    suspend fun read(): String = io.withLock {
        val cutoff = clock() - maxAgeMillis
        buildString {
            for (path in listOf(previous, current)) {
                val text = runCatching { fs.read(path) { readUtf8() } }.getOrNull() ?: continue
                text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val at = parseStamp(line)
                    if (at == null || at >= cutoff) append(line).append('\n')
                }
            }
        }
    }

    private fun firstStamp(path: Path): Long? = runCatching {
        fs.read(path) { readUtf8Line() }?.let(::parseStamp)
    }.getOrNull()

    companion object {
        internal fun stamp(millis: Long): String = kotlin.time.Instant.fromEpochMilliseconds(millis).toString()
        internal fun parseStamp(line: String): Long? =
            runCatching { kotlin.time.Instant.parse(line.substringBefore(' ')).toEpochMilliseconds() }.getOrNull()
    }
}

/**
 * Takes secrets out of a note before it's kept anywhere: tokens (JWTs, `Bearer`), `key=value` pairs whose key names a
 * secret, long hex or base64 runs (keys, signatures), emails and phone numbers. Over-scrubbing beats a leak.
 */
object LogScrubber {
    private val jwt = Regex("""eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*""")
    private val bearer = Regex("""(?i)\bbearer\s+\S+""")
    private val secretPair = Regex("""(?i)\b(access_?token|refresh_?token|token|secret|password|passcode|pin|otp|credential|signature|authorization|api_?key|private_?key|seed|mnemonic|cookie)(\s*["']?\s*[:=]\s*["']?)([^\s,"'&}]+)""")
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val phone = Regex("""\+\d[\d\s-]{6,16}\d""")
    private val longDigits = Regex("""(?<![\w.:-])\d{9,}(?![\w.:-])""")
    private val blob = Regex("""[A-Za-z0-9+/_-]{40,}={0,2}""")

    fun scrub(text: String): String {
        var s = text
        s = jwt.replace(s, "[token]")
        s = bearer.replace(s, "Bearer [token]")
        s = secretPair.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + "[redacted]" }
        s = email.replace(s, "[email]")
        s = phone.replace(s, "[phone]")
        s = longDigits.replace(s, "[number]")
        s = blob.replace(s, "[blob]")
        return s
    }
}
