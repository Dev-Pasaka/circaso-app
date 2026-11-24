package com.circaso.feature.wallet

import com.circaso.feature.wallet.data.local.TransferEntity
import com.circaso.feature.wallet.data.remote.AmountDto
import com.circaso.feature.wallet.data.remote.CounterpartyDto
import com.circaso.feature.wallet.data.remote.TransactionDto
import com.circaso.feature.wallet.data.remote.TransactionsResponse
import com.circaso.feature.wallet.data.repository.WalletRepositoryImpl
import com.circaso.feature.wallet.data.repository.toActivity
import com.circaso.feature.wallet.data.repository.toEntity
import com.circaso.feature.wallet.domain.TransferDirection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The verified badge on payment rows (2026-11-24): W6 `counterparty.verified` is kept on the stored payment, so a payee
 * in no contact or chat shows the seal offline, and every row of one person follows the newest answer.
 */
class VerifiedPayeeTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val dao = FakeWalletDao()
    private val remote = FakeWalletRemote()
    private val repo = WalletRepositoryImpl(remote, dao, { FakeSigner() }, { "KES" }, json)
    private val usdc = AmountDto("USDC", "1000000", "1")

    private fun row(id: String, userId: String?, verified: Boolean, at: String, direction: String = "OUT", address: String? = null) = TransactionDto(
        id = "row-$id", transferId = id, direction = direction, state = "CONFIRMED", blockchain = "BASE-SEPOLIA", amount = usdc,
        counterparty = CounterpartyDto(userId = userId, displayName = userId?.let { "Name $it" }, address = address, verified = verified), occurredAt = at,
    )

    @Test fun anOlderServerWithoutTheFieldReadsAsNotVerified() {
        val page = json.decodeFromString<TransactionsResponse>(
            """{"transactions":[{"id":"r1","direction":"IN","state":"CONFIRMED","blockchain":"BASE","amount":{"asset":"USDC","amountMinor":"1","amount":"0.000001"},"counterparty":{"userId":"u-1"},"occurredAt":"2026-11-24T10:00:00Z"},""" +
                """{"id":"r2","direction":"IN","state":"CONFIRMED","blockchain":"BASE","amount":{"asset":"USDC","amountMinor":"1","amount":"0.000001"},"counterparty":{"userId":"u-2","verified":true},"occurredAt":"2026-11-24T10:00:00Z"}]}""",
        )
        assertEquals(listOf(false, true), page.transactions.map { it.counterparty?.verified })
    }

    @Test fun aVerifiedPayeeIsKeptOnTheirPayment() = runTest {
        remote.status = "ACTIVE"
        remote.history[null] = listOf(row("t-1", "kamau", verified = true, at = "2026-11-24T10:00:00Z"))
        repo.refresh()
        assertTrue(dao.transfers.value.getValue("t-1").counterpartVerified)
    }

    @Test fun aSealTakenBackLeavesTheirOlderPaymentsToo() = runTest {
        remote.status = "ACTIVE"
        // An older payment, stored when they were verified and no longer on the first page.
        dao.upsert(row("t-old", "kamau", verified = true, at = "2026-10-01T10:00:00Z").toEntity("t-old", null))
        dao.upsert(row("t-other", "wanjiru", verified = true, at = "2026-10-01T10:00:00Z").toEntity("t-other", null))
        remote.history[null] = listOf(row("t-new", "kamau", verified = false, at = "2026-11-24T10:00:00Z"))
        repo.refresh()
        assertFalse(dao.transfers.value.getValue("t-new").counterpartVerified)
        assertFalse(dao.transfers.value.getValue("t-old").counterpartVerified, "the newest answer reaches every row of theirs")
        assertTrue(dao.transfers.value.getValue("t-other").counterpartVerified, "someone else's rows are left alone")
    }

    @Test fun aGroupWalletsHistoryCarriesTheBadgeAndFollowsTheNewestAnswer() = runTest {
        remote.history["gw-1"] = listOf(row("t-9", "wanjiru", verified = true, at = "2026-11-03T10:00:00Z", direction = "IN"))
        repo.refreshActivity("gw-1")
        assertTrue(dao.activity.value.single().counterpartVerified)
        // Her badge is taken back; the personal history says so first.
        remote.status = "ACTIVE"
        remote.history[null] = listOf(row("t-10", "wanjiru", verified = false, at = "2026-11-24T10:00:00Z"))
        repo.refresh()
        assertFalse(dao.activity.value.single().counterpartVerified)
    }

    @Test fun aRowNamingNoPersonKeepsWhatWasStored() {
        val stored = row("t-1", "kamau", verified = true, at = "2026-11-24T10:00:00Z").toEntity("t-1", null)
        val bare = row("t-1", null, verified = false, at = "2026-11-24T10:00:00Z", address = "0xabc").copy(counterparty = null)
        assertTrue(bare.toEntity("t-1", stored).counterpartVerified)
        assertFalse(row("t-2", null, verified = true, at = "2026-11-24T10:00:00Z", address = "0xabc").toActivity("gw-1").counterpartVerified, "an address alone is no person")
    }

    @Test fun aFrameKeepsTheBadgeTheHistoryGave() {
        val stored: TransferEntity = row("t-1", "kamau", verified = true, at = "2026-11-24T10:00:00Z").toEntity("t-1", null)
        assertEquals(TransferDirection.Out.name, stored.direction)
        val updated = com.circaso.feature.wallet.data.remote.TransferResponse(
            transferId = "t-1", state = "CONFIRMED", direction = "OUT", blockchain = "BASE-SEPOLIA", from = "0xme",
            to = com.circaso.feature.wallet.data.remote.PayeeDto(userId = "kamau", address = "0xk"), amount = usdc,
        ).toEntity(stored)
        assertTrue(updated.counterpartVerified)
    }
}
