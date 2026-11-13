package com.circaso.core.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsLogTest {
    private val dir = kotlin.io.path.createTempDirectory("diag").toFile().toOkioPath()
    private var now = 1_763_000_000_000L

    @Test fun linesAreStampedKeptInOrderAndRead() = runTest(UnconfinedTestDispatcher()) {
        val log = DiagnosticsLog(FileSystem.SYSTEM, dir, clock = { now }, scope = backgroundScope)
        log.append("calls: one")
        now += 1_000
        log.append("calls: two")
        val text = log.read()
        val lines = text.lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size, text)
        assertTrue(lines[0].startsWith(kotlin.time.Instant.fromEpochMilliseconds(1_763_000_000_000L).toString()))
        assertTrue(lines[1].endsWith("calls: two"))
    }

    @Test fun aFullFileRollsOverAndOnlyTwoAreKept() = runTest(UnconfinedTestDispatcher()) {
        val log = DiagnosticsLog(FileSystem.SYSTEM, dir, clock = { now }, maxFileBytes = 2_000, scope = backgroundScope)
        repeat(200) { log.append("socket: frame typing $it") }
        log.read()
        val files = FileSystem.SYSTEM.list(dir).map { it.name }.sorted()
        assertEquals(listOf("diagnostics-0.log", "diagnostics-1.log"), files)
        val total = files.sumOf { FileSystem.SYSTEM.metadata(dir / it).size ?: 0 }
        assertTrue(total <= 4_200, "about two files' worth: $total")
        assertTrue(log.read().trimEnd().endsWith("typing 199"), "the newest line survives")
    }

    @Test fun linesOlderThanTwoDaysAreLeftOut() = runTest(UnconfinedTestDispatcher()) {
        val log = DiagnosticsLog(FileSystem.SYSTEM, dir, clock = { now }, scope = backgroundScope)
        log.append("old")
        log.read()
        now += 49 * 60 * 60 * 1000L
        log.append("new")
        val text = log.read()
        assertFalse("old" in text, text)
        assertTrue("new" in text)
    }

    @Test fun secretsNeverReachTheLog() {
        val scrubbed = LogScrubber.scrub(
            "auth: Bearer abc.def.ghi token=s3cr3t pin: 1234 eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2ln " +
                "mail amina@example.com phone +254712345678 key AAAAB3NzaC1yc2EAAAADAQABAAABAQC7vQ9xYz0123456789abcdef",
        )
        listOf("abc.def.ghi", "s3cr3t", "1234", "eyJhbGci", "amina@example.com", "+254712345678", "AAAAB3NzaC1yc2E").forEach {
            assertFalse(it in scrubbed, "$it leaked: $scrubbed")
        }
    }

    @Test fun ordinaryNotesAreLeftAlone() {
        val note = "calls: state=ACTIVE people=JOINED/v=false/mid=2 renegotiate=true after 12s, retry 3 in 4000ms"
        assertEquals(note, LogScrubber.scrub(note))
        assertEquals("calls: leave failed (calls.not_found), giving up", LogScrubber.scrub("calls: leave failed (calls.not_found), giving up"))
    }
}
