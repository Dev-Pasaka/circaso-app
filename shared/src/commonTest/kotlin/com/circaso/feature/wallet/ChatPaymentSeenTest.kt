package com.circaso.feature.wallet

import com.circaso.feature.wallet.data.remote.AmountDto
import com.circaso.feature.wallet.data.remote.CounterpartyDto
import com.circaso.feature.wallet.data.remote.TransactionDto
import com.circaso.feature.wallet.data.repository.WalletRepositoryImpl
import com.circaso.feature.wallet.domain.ChatPaymentSeen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The wallet tells the chats about confirmed chat payments from the server's own record (incident 2026-11-05). */
class ChatPaymentSeenTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val dao = FakeWalletDao()
    private val remote = FakeWalletRemote()
    private val seen = mutableListOf<ChatPaymentSeen>()
    private var sounds = 0
    private val repo = WalletRepositoryImpl(remote, dao, { FakeSigner() }, { "KES" }, json, onMoneyReceived = { sounds++ }, onChatPayment = { seen += it })

    private fun received(transferId: String?, chatId: String?) = buildJsonObject {
        put("transactionId", "tx-1"); put("blockchain", "BASE-SEPOLIA"); put("fromUserId", "pasaka"); put("fromName", "Pasaka Mutuku")
        transferId?.let { put("transferId", it) }; chatId?.let { put("chatId", it) }
        putJsonObject("amount") { put("asset", "USDC"); put("amountMinor", "7699911"); put("amount", "7.699911") }
    }

    @Test fun moneyReceivedInAChatPutsItsCardThereKeptUnderItsTransfer() = runTest {
        repo.onFrame("payment.received", received("t-in", "chat-1"))
        val card = seen.single()
        assertEquals("chat-1", card.chatId)
        assertEquals("t-in", card.transferId)
        assertFalse(card.outgoing)
        assertEquals("pasaka", card.counterpartUserId)
        assertEquals("7699911", card.amountMinor)
        val rows = repo.transfers.first()
        assertTrue(rows.any { it.transferId == "t-in" }, "kept under the transfer, so the chat's card reads it")
        assertTrue(rows.none { it.transferId == "tx-1" }, "not a second row under the history id")
        assertEquals(1, sounds)
    }

    @Test fun aReplayedFrameSoundsOnce() = runTest {
        repo.onFrame("payment.received", received("t-in", "chat-1"))
        repo.onFrame("payment.received", received("t-in", "chat-1"))
        assertEquals(1, sounds)
    }

    @Test fun moneyFromOutsideAChatAddsNoCard() = runTest {
        repo.onFrame("payment.received", received(null, null))
        assertTrue(seen.isEmpty())
        assertTrue(repo.transfers.first().any { it.transferId == "tx-1" })
    }

    @Test fun historyGivesANewPhoneItsSentAndReceivedCardsButOnlyConfirmedOnes() = runTest {
        val now = kotlin.time.Clock.System.now()
        remote.status = "ACTIVE"
        remote.history[null] = listOf(
            TransactionDto("row-out", "t-out", "OUT", "CONFIRMED", "BASE-SEPOLIA", AmountDto("USDC", "1000000", "1"), CounterpartyDto(userId = "amina"), "chat-1", occurredAt = now.toString()),
            TransactionDto("row-in", "t-in", "IN", "CONFIRMED", "BASE-SEPOLIA", AmountDto("USDC", "2000000", "2"), CounterpartyDto(userId = "amina"), "chat-1", occurredAt = now.toString()),
            TransactionDto("row-pending", "t-pending", "OUT", "SUBMITTED", "BASE-SEPOLIA", AmountDto("USDC", "3000000", "3"), CounterpartyDto(userId = "amina"), "chat-1", occurredAt = now.toString()),
            TransactionDto("row-old", "t-old", "IN", "CONFIRMED", "BASE-SEPOLIA", AmountDto("USDC", "4000000", "4"), CounterpartyDto(userId = "amina"), "chat-1", occurredAt = "2026-01-01T10:00:00Z"),
        )
        repo.refresh()
        assertEquals(listOf("t-out" to true, "t-in" to false), seen.map { it.transferId to it.outgoing }, "never a card for money not yet on chain, nor months-old money")
    }
}
