package com.circaso.feature.calls

import com.circaso.feature.calls.presentation.CallMoreItem
import com.circaso.feature.calls.presentation.callMoreItems
import kotlin.test.Test
import kotlin.test.assertEquals

class CallMoreItemsTest {
    private val mum = CallPerson("2", "Mum", true, true, false, false, null)

    @Test fun aVoiceCallListsShareSpeakerAndMinimiseButNoFlip() {
        val call = CallUi("c1", "2", "Mum", false, false, CallPhase.Active, people = listOf(mum))
        assertEquals(listOf(CallMoreItem.ShareScreen, CallMoreItem.Speaker, CallMoreItem.Minimise), callMoreItems(call, canShareScreen = true))
    }

    @Test fun shareScreenIsHiddenWhereThePhoneCannotShare() {
        val call = CallUi("c1", "2", "Mum", true, false, CallPhase.Active, camera = true, people = listOf(mum))
        assertEquals(listOf(CallMoreItem.FlipCamera, CallMoreItem.Speaker, CallMoreItem.Minimise), callMoreItems(call, canShareScreen = false))
    }

    @Test fun flipIsHiddenWhileTheScreenHasTheVideoTrack() {
        val call = CallUi("c1", "2", "Mum", true, false, CallPhase.Active, camera = true, screen = true, people = listOf(mum))
        assertEquals(listOf(CallMoreItem.ShareScreen, CallMoreItem.Speaker, CallMoreItem.Minimise), callMoreItems(call, canShareScreen = true))
    }

    @Test fun askAshaIsLastWhereSheCanBeSummonedButNeverWhileItRingsIn() {
        val call = CallUi("c1", "2", "Mum", false, false, CallPhase.Active, people = listOf(mum))
        assertEquals(listOf(CallMoreItem.Speaker, CallMoreItem.Minimise, CallMoreItem.AskAsha), callMoreItems(call, canShareScreen = false, canAskAsha = true))
        assertEquals(false, CallMoreItem.AskAsha in callMoreItems(call, canShareScreen = false, canAskAsha = false), "no full session")
        assertEquals(false, CallMoreItem.AskAsha in callMoreItems(call.copy(phase = CallPhase.Incoming), canShareScreen = false, canAskAsha = true))
        assertEquals(true, CallMoreItem.AskAsha in callMoreItems(call.copy(group = true, video = true), canShareScreen = false, canAskAsha = true))
    }

    @Test fun addPeopleOnlyOnALiveGroupCallWithSomeoneToRing() {
        val juma = CallPerson("6", "Juma Kariuki", false, true, false, false, null)
        val live = CallUi("c2", "g1", "Holiday Fund", false, true, CallPhase.Active, people = listOf(mum, juma))
        assertEquals(listOf(CallMoreItem.Speaker, CallMoreItem.AddPeople, CallMoreItem.Minimise), callMoreItems(live, canShareScreen = false))
        assertEquals(false, CallMoreItem.AddPeople in callMoreItems(live.copy(phase = CallPhase.Ringing), canShareScreen = false))
        assertEquals(false, CallMoreItem.AddPeople in callMoreItems(live.copy(people = listOf(mum, juma.copy(ringing = true))), canShareScreen = false), "already ringing")
        assertEquals(false, CallMoreItem.AddPeople in callMoreItems(live.copy(group = false), canShareScreen = false))
    }
}
