package com.circaso.feature.chama

import com.circaso.feature.chama.data.repository.ContributionFeed
import com.circaso.feature.chama.data.repository.contributionOf
import com.circaso.feature.chama.domain.GroupContribution
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContributionFeedTest {
    private fun data(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun aSubmittedContributionCarriesTheCoinAndItsDollarValue() {
        val c = contributionOf(
            "contribution.submitted",
            data("""{"chatId":"c1","transferId":"t1","userId":"u1","displayName":"Amina","paid":{"asset":"ETH","amountMinor":"10000000000000000","amount":"0.01"},"amount":{"asset":"USDC","amountMinor":"30000000","amount":"30"}}"""),
        )
        assertEquals(GroupContribution("c1", "t1", "u1", "Amina", "ETH", "0.01", "30000000", confirmed = false), c)
    }

    @Test
    fun aConfirmedContributionIsMoneyUpdatedOfKindContribution() {
        val c = contributionOf(
            "money.updated",
            data("""{"chatId":"c1","transferId":"t1","kind":"CONTRIBUTION","amount":{"asset":"USDC","amountMinor":"7750000","amount":"7.75"},"userId":"u1"}"""),
        )
        // An older server says no `paid`: the dollar amount stands in for it.
        assertEquals(GroupContribution("c1", "t1", "u1", null, "USDC", "7.75", "7750000", confirmed = true), c)
    }

    @Test
    fun payoutsGoalsAndBrokenFramesAreNotContributions() {
        assertNull(contributionOf("money.updated", data("""{"chatId":"c1","transferId":"t1","kind":"PAYOUT","amount":{"asset":"USDC","amountMinor":"1","amount":"0.000001"}}""")))
        assertNull(contributionOf("goal.updated", data("""{"chatId":"c1"}""")))
        assertNull(contributionOf("contribution.submitted", data("""{"chatId":"c1"}""")))
        assertNull(contributionOf("contribution.submitted", null))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theFeedPassesContributionsOnAndAResyncAsksForAReload() = runTest(UnconfinedTestDispatcher()) {
        val feed = ContributionFeed()
        val seen = mutableListOf<String>()
        var resyncs = 0
        val a = launch { feed.contributions.collect { seen += it.transferId } }
        val b = launch { feed.resyncs.collect { resyncs++ } }
        feed.onFrame("contribution.submitted", data("""{"chatId":"c1","transferId":"t1","userId":"u1","displayName":"A","paid":{"asset":"USDC","amountMinor":"1","amount":"0.000001"}}"""))
        feed.onFrame("goal.updated", data("""{"chatId":"c1"}"""))
        feed.onFrame("resync", null)
        assertEquals(listOf("t1"), seen)
        assertEquals(1, resyncs)
        a.cancel(); b.cancel()
    }
}
