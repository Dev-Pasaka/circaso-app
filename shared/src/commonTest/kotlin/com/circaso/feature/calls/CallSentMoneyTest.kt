package com.circaso.feature.calls

import com.circaso.feature.calls.presentation.CallCelebrationsModel
import com.circaso.feature.calls.presentation.sentCelebration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The sender's splash on a 1:1 call (Pasaka, 2026-11-10): "You sent Mum KES 500", once, the moment it is signed. */
class CallSentMoneyTest {
    @Test fun theSenderSeesWhatTheySentAndToWhom() {
        val c = sentCelebration("t1", "usdc", "3875968", "KES 500", "Mum Otieno", "me", null)!!
        assertEquals("You sent Mum KES 500", c.text)
        assertEquals("USDC", c.coin)
    }

    @Test fun withoutTheirMoneyTheCoinIsSaid() {
        assertEquals("You sent Juma 0.01 ETH", sentCelebration("t2", "ETH", "10000000000000000", null, "Juma", "me", null)!!.text)
    }

    @Test fun itFliesOnceAndNoTransferNoSplash() {
        val model = CallCelebrationsModel()
        val c = sentCelebration("t1", "USDC", "3875968", "KES 500", "Mum", "me", null)!!
        model.receive(c, confirmed = true, visible = true)
        model.receive(c, confirmed = true, visible = true)
        assertEquals(1, model.state.shown.size)
        assertNull(sentCelebration("", "USDC", "1", "KES 1", "Mum", "me", null))
    }
}
