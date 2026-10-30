package com.circaso.feature.messaging

import com.circaso.feature.messaging.presentation.ThreadMedia
import com.circaso.feature.messaging.presentation.VoiceHold
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceHoldTest {
    private class Mic(scope: TestScope, var startError: String? = null, var finishError: String? = null) {
        val calls = mutableListOf<String>()
        val shown = mutableListOf<Pair<Boolean, String?>>()
        var gate = CompletableDeferred(Unit)
        val hold = VoiceHold(
            scope,
            start = { calls += "start"; gate.await(); startError },
            finish = { calls += "finish"; finishError },
            cancel = { calls += "cancel" },
            show = { recording, message -> shown += recording to message },
        )
    }

    @Test
    fun holdThenReleaseSends() = runTest {
        val mic = Mic(this)
        mic.hold.press(); runCurrent()
        mic.hold.release(send = true); runCurrent()
        assertEquals(listOf("start", "finish"), mic.calls)
        assertEquals(listOf<Pair<Boolean, String?>>(true to null, false to null), mic.shown)
    }

    @Test
    fun slidingOffCancels() = runTest {
        val mic = Mic(this)
        mic.hold.press(); runCurrent()
        mic.hold.release(send = false); runCurrent()
        assertEquals(listOf("start", "cancel"), mic.calls)
        assertEquals(listOf<Pair<Boolean, String?>>(true to null, false to null), mic.shown)
    }

    @Test
    fun releaseBeforeTheMicStartsDropsTheClipAndExplains() = runTest {
        val mic = Mic(this).apply { gate = CompletableDeferred() }
        mic.hold.press(); runCurrent()
        mic.hold.release(send = true); runCurrent()
        mic.gate.complete(Unit); runCurrent()
        assertEquals(listOf("start", "cancel"), mic.calls)
        assertEquals(listOf<Pair<Boolean, String?>>(false to ThreadMedia.HoldToRecord), mic.shown)
    }

    @Test
    fun deniedMicrophoneShowsWhyAndNeverRecords() = runTest {
        val mic = Mic(this, startError = "no mic")
        mic.hold.press(); runCurrent()
        mic.hold.release(send = true); runCurrent()
        assertEquals(listOf("start"), mic.calls)
        assertEquals(listOf<Pair<Boolean, String?>>(false to "no mic"), mic.shown)
    }

    @Test
    fun aFailedSaveIsShownAfterRecordingStops() = runTest {
        val mic = Mic(this, finishError = "couldn't save")
        mic.hold.press(); runCurrent()
        mic.hold.release(send = true); runCurrent()
        assertEquals(listOf<Pair<Boolean, String?>>(true to null, false to null, false to "couldn't save"), mic.shown)
    }

    @Test
    fun aSecondPressWhileHeldIsIgnored() = runTest {
        val mic = Mic(this)
        mic.hold.press(); mic.hold.press(); runCurrent()
        mic.hold.release(send = true); mic.hold.release(send = true); runCurrent()
        assertEquals(listOf("start", "finish"), mic.calls)
    }
}
