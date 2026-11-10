package com.circaso.feature.assistant.presentation

import androidx.compose.ui.unit.dp
import com.circaso.feature.calls.CallPerson
import com.circaso.feature.calls.CallPhase
import com.circaso.feature.calls.CallUi
import com.circaso.feature.calls.presentation.ashaCallState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Asha's bubble with a call about (2026-11-10): over the call, no voice, no screen pictures of other people. */
class AshaBubbleCallTest {
    private val mum = CallPerson("2", "Mum", true, true, false, false, null)
    private val call = CallUi("c1", "2", "Mum", false, false, CallPhase.Active, people = listOf(mum))

    @Test fun withoutACallSheIsAsBefore() {
        val m = ashaBubbleMode(ashaOnTop = false, call = AshaCallState.None)
        assertFalse(m.hidden); assertTrue(m.voice); assertTrue(m.showScreen)
        assertEquals(0.dp, m.clearBottom)
        assertTrue(ashaBubbleMode(ashaOnTop = true, call = AshaCallState.None).hidden, "her own screen is her already")
    }

    @Test fun overTheFullCallScreenSheShowsWithoutVoiceOrScreenPictures() {
        val m = ashaBubbleMode(ashaOnTop = false, call = AshaCallState.OnScreen)
        assertFalse(m.hidden)
        assertFalse(m.voice, "the call has the microphone")
        assertFalse(m.showScreen, "never a picture of the other people on the call")
        assertTrue(m.clearBottom > 0.dp && m.clearTop > 0.dp, "the resting bubble keeps off the call's controls")
        // A call over one of her own screens: she shows over the call.
        assertFalse(ashaBubbleMode(ashaOnTop = true, call = AshaCallState.OnScreen).hidden)
    }

    @Test fun aRingHidesHerUntilItIsAnswered() {
        val m = ashaBubbleMode(ashaOnTop = false, call = AshaCallState.Ringing)
        assertTrue(m.hidden, "nothing of hers on Accept or Decline")
        assertFalse(m.voice)
    }

    @Test fun minimisedTheScreenIsTheAppsButTheMicrophoneIsStillTheCalls() {
        val m = ashaBubbleMode(ashaOnTop = false, call = AshaCallState.Minimised)
        assertFalse(m.hidden); assertFalse(m.voice); assertTrue(m.showScreen)
        assertEquals(0.dp, m.clearBottom)
        assertTrue(ashaBubbleMode(ashaOnTop = true, call = AshaCallState.Minimised).hidden)
    }

    @Test fun theCallAsTheBubbleSeesIt() {
        assertEquals(AshaCallState.None, ashaCallState(null))
        assertEquals(AshaCallState.None, ashaCallState(call.copy(phase = CallPhase.Ended)))
        assertEquals(AshaCallState.OnScreen, ashaCallState(call))
        assertEquals(AshaCallState.OnScreen, ashaCallState(call.copy(phase = CallPhase.Ringing)), "my own call ringing out")
        assertEquals(AshaCallState.OnScreen, ashaCallState(call.copy(group = true, video = true)))
        assertEquals(AshaCallState.Ringing, ashaCallState(call.copy(phase = CallPhase.Incoming)))
        assertEquals(AshaCallState.Minimised, ashaCallState(call.copy(minimised = true)))
    }
}
