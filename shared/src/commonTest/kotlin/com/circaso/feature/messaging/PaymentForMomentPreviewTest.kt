package com.circaso.feature.messaging

import com.circaso.core.crypto.MomentQuote
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.messaging.presentation.paymentForMoment
import kotlin.test.Test
import kotlin.test.assertEquals

/** The chat list's line for money sent for a moment (2026-11-14). */
class PaymentForMomentPreviewTest {
    private fun card(outgoing: Boolean, quote: MomentQuote?) = Message.Payment(
        localId = 1, chatId = "c1", orderAt = 1, sentAt = null, messageId = "m", senderId = if (outgoing) "me" else "amina", senderName = "Amina",
        outgoing = outgoing, transferId = "t", amountMinor = "5000000", asset = "USDC", blockchain = "BASE-SEPOLIA", note = null,
        state = DeliveryState.Sent, errorCode = null, momentQuote = quote,
    )
    private val sunset = MomentQuote("mo-1", "IMAGE", "Sunset at Diani")

    @Test fun moneyForAMomentSaysWhoseMoment() {
        assertEquals(" for their moment", paymentForMoment(card(outgoing = true, sunset), group = false))
        assertEquals(" for your moment", paymentForMoment(card(outgoing = false, sunset), group = false))
    }

    @Test fun aPlainCardAndAGroupsCardReadAsBefore() {
        assertEquals("", paymentForMoment(card(outgoing = false, null), group = false))
        assertEquals("", paymentForMoment(card(outgoing = false, sunset), group = true))
    }
}
