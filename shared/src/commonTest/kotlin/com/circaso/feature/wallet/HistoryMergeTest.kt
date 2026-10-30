package com.circaso.feature.wallet

import com.circaso.feature.wallet.data.local.TransferEntity
import com.circaso.feature.wallet.data.remote.AmountDto
import com.circaso.feature.wallet.data.remote.CounterpartyDto
import com.circaso.feature.wallet.data.remote.TransactionDto
import com.circaso.feature.wallet.data.repository.toEntity
import com.circaso.feature.wallet.domain.TransferDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/** W6 rows on a new phone, and on top of what a frame already stored. */
class HistoryMergeTest {
    private val usdc = AmountDto("USDC", "5000000", "5")

    @Test fun moneyReceivedOnANewPhoneShowsWhoSentIt() {
        val row = TransactionDto(
            id = "tx-1", direction = "IN", state = "CONFIRMED", blockchain = "BASE-SEPOLIA", amount = usdc,
            counterparty = CounterpartyDto(userId = "u-2", displayName = "Bob", address = "0xbob"), occurredAt = "2026-10-30T20:23:48Z",
        )
        val e = row.toEntity("tx-1", null)
        assertEquals(TransferDirection.In.name, e.direction)
        assertEquals("Bob", e.counterpartName)
        assertEquals("0xbob", e.fromAddress)
        assertEquals("5", e.amount)
    }

    @Test fun aSentPaymentKeepsWhatThePhoneKnewAndTakesTheServersState() {
        val stored = TransferEntity(
            transferId = "t-1", direction = TransferDirection.Out.name, state = "SUBMITTED", blockchain = "BASE-SEPOLIA", fromAddress = "0xme",
            toUserId = "u-2", toAddress = "0xbob", asset = "USDC", amountMinor = "5000000", amount = "5", feeMinor = "10", chatId = "c-1",
            txHash = null, failureReason = null, counterpartName = "Bob", note = "lunch", createdAt = 1_000, updatedAt = 2_000,
        )
        val row = TransactionDto(
            id = "tx-9", transferId = "t-1", direction = "OUT", state = "CONFIRMED", blockchain = "BASE-SEPOLIA", amount = usdc,
            counterparty = CounterpartyDto(userId = "u-2", handle = "bob"), txHash = "0xhash", occurredAt = "2026-10-30T20:23:40Z",
        )
        val e = row.toEntity("t-1", stored)
        assertEquals("CONFIRMED", e.state)
        assertEquals("0xhash", e.txHash)
        assertEquals("lunch", e.note)
        assertEquals("c-1", e.chatId)
        assertEquals("0xme", e.fromAddress)
        assertEquals(1_000, e.createdAt)
        assertEquals("Bob", e.counterpartName, "the name the phone already had beats a bare handle")
    }
}
