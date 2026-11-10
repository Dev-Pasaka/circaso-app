package com.circaso.feature.calls

import com.circaso.feature.calls.presentation.CallCelebrationsModel
import com.circaso.feature.calls.presentation.ClockSlackMillis
import com.circaso.feature.calls.presentation.receivedCelebration
import com.circaso.feature.calls.presentation.receivedOnCall
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.TransferState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Money to me on a 1:1 call: which payments splash on the receiver's call screen (2026-11-10). */
class CallReceivedMoneyTest {
    private val kes = FiatValue("KES", "0", "129", "2026-11-09T10:00:00Z", partial = false)
    private val juma = CallPerson("juma", "Juma Kariuki", joined = true, audio = true, video = false, ringing = false, videoMid = null, photoUrl = "https://p/juma")
    private val since = 1_000_000L

    private fun payment(
        id: String,
        at: Long = since + 5_000,
        outgoing: Boolean = false,
        from: String = "juma",
        minor: String = "7751938",
        asset: String = "USDC",
    ) = Message.Payment(
        localId = id.hashCode().toLong(), chatId = "chat", orderAt = at, sentAt = null, messageId = "m-$id", senderId = from, senderName = "Juma K.",
        outgoing = outgoing, transferId = id, amountMinor = minor, asset = asset, blockchain = "BASE", note = null, state = DeliveryState.Delivered, errorCode = null,
    )

    private fun confirmed(vararg ids: String) = ids.associateWith { TransferState.Confirmed }

    @Test fun aConfirmedPaymentFromThePeerDuringTheCallSplashes() {
        val got = receivedOnCall(listOf(payment("t1")), confirmed("t1"), "juma", since, baseline = emptySet(), seen = emptySet())
        assertEquals(listOf("t1"), got.map { it.transferId })
    }

    @Test fun onlyOnceItHasLanded() {
        listOf(TransferState.AwaitingSignature, TransferState.Submitted, TransferState.Failed, TransferState.Expired).forEach { s ->
            assertTrue(receivedOnCall(listOf(payment("t1")), mapOf("t1" to s), "juma", since, emptySet(), emptySet()).isEmpty(), "$s")
        }
        assertTrue(receivedOnCall(listOf(payment("t1")), emptyMap(), "juma", since, emptySet(), emptySet()).isEmpty(), "not heard of yet")
    }

    @Test fun neverMyOwnPaymentNorSomeoneElses() {
        assertTrue(receivedOnCall(listOf(payment("t1", outgoing = true, from = "me")), confirmed("t1"), "juma", since, emptySet(), emptySet()).isEmpty())
        assertTrue(receivedOnCall(listOf(payment("t1", from = "amina")), confirmed("t1"), "juma", since, emptySet(), emptySet()).isEmpty())
    }

    @Test fun neverHistory() {
        // Already in the chat when the call went live.
        assertTrue(receivedOnCall(listOf(payment("old")), confirmed("old"), "juma", since, baseline = setOf("old"), seen = emptySet()).isEmpty())
        // Dated well before the call (an older card synced in during it); a little clock skew is forgiven.
        assertTrue(receivedOnCall(listOf(payment("old", at = since - ClockSlackMillis - 1)), confirmed("old"), "juma", since, emptySet(), emptySet()).isEmpty())
        assertEquals(1, receivedOnCall(listOf(payment("skew", at = since - 2_000)), confirmed("skew"), "juma", since, emptySet(), emptySet()).size)
    }

    @Test fun notBeforeTheCallIsLive() {
        assertTrue(receivedOnCall(listOf(payment("t1")), confirmed("t1"), "juma", since = null, baseline = emptySet(), seen = emptySet()).isEmpty())
        assertTrue(receivedOnCall(listOf(payment("t1")), confirmed("t1"), peerUserId = null, since = since, baseline = emptySet(), seen = emptySet()).isEmpty())
    }

    @Test fun oncePerPaymentEvenWithTwoCardsForIt() {
        // The end-to-end card and the server's own card for the same transfer are one payment.
        val twice = listOf(payment("t1"), payment("t1", at = since + 6_000))
        assertEquals(1, receivedOnCall(twice, confirmed("t1"), "juma", since, emptySet(), emptySet()).size)
        assertTrue(receivedOnCall(twice, confirmed("t1"), "juma", since, emptySet(), seen = setOf("t1")).isEmpty())
        // And the call's model flies it once, however often the thread emits.
        val model = CallCelebrationsModel()
        val c = receivedCelebration(payment("t1"), listOf(juma), kes)
        model.receive(c, confirmed = true, visible = true)
        model.receive(c, confirmed = true, visible = true)
        assertEquals(1, model.state.shown.size)
    }

    @Test fun oldestFirst() {
        val got = receivedOnCall(listOf(payment("b", at = since + 9_000), payment("a", at = since + 3_000)), confirmed("a", "b"), "juma", since, emptySet(), emptySet())
        assertEquals(listOf("a", "b"), got.map { it.transferId })
    }

    @Test fun itSaysWhoSentWhatInMyMoney() {
        val c = receivedCelebration(payment("t1"), listOf(juma), kes)
        assertEquals("Juma sent you KES 1,000", c.text)
        assertEquals("Juma sent you KES 1,000", c.spoken)
        assertEquals("https://p/juma", c.photoUrl)
        assertEquals("USDC", c.coin)
    }

    @Test fun withoutARateOrForAnotherCoinItSaysTheCoin() {
        assertEquals("Juma sent you 7.751938 USDC", receivedCelebration(payment("t1"), listOf(juma), fiat = null).text)
        // Not on the call's people list: the card's own name, first name only.
        assertTrue(receivedCelebration(payment("t2", asset = "ETH", minor = "10000000000000000"), emptyList(), kes).text.startsWith("Juma sent you 0.01"))
    }
}
