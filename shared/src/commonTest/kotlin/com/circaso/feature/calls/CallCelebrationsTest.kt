package com.circaso.feature.calls

import com.circaso.feature.calls.presentation.Celebration
import com.circaso.feature.calls.presentation.CelebrationState
import com.circaso.feature.calls.presentation.celebrationOf
import com.circaso.feature.calls.presentation.coinNumber
import com.circaso.feature.chama.domain.GroupContribution
import com.circaso.feature.wallet.domain.FiatValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallCelebrationsTest {
    private val kes = FiatValue("KES", "0", "129", "2026-11-09T10:00:00Z", partial = false)
    private val people = listOf(
        CallPerson("amina", "Amina Wanjiru", joined = true, audio = true, video = false, ringing = false, videoMid = null, photoUrl = "https://p/amina"),
        CallPerson("juma", "Juma", joined = true, audio = true, video = false, ringing = false, videoMid = null),
    )

    private fun contribution(
        userId: String? = "amina",
        coin: String? = "USDC",
        coinAmount: String? = "7.751938",
        usdMinor: String? = "7751938",
        displayName: String? = "Amina W.",
        id: String = "t1",
    ) = GroupContribution("chat", id, userId, displayName, coin, coinAmount, usdMinor, confirmed = false)

    private fun c(id: String) = Celebration(id, "Amina", "KES 1,000", "USDC", "amina")


    // ---- the words

    @Test
    fun aDollarCoinIsSaidInThePersonsMoneyWithTheNameTheCallShows() {
        val c = celebrationOf(contribution(), people, myUserId = "me", fiat = kes)
        assertEquals("Amina +KES 1,000", c.text)
        assertEquals("USDC", c.coin)
        assertEquals("https://p/amina", c.photoUrl)
        assertEquals("Amina added KES 1,000", c.spoken)
    }

    @Test
    fun anyOtherCoinIsSaidInItsOwnUnits() {
        val c = celebrationOf(contribution(coin = "eth", coinAmount = "0.0100", usdMinor = "30000000"), people, "me", kes)
        assertEquals("Amina +0.01 ETH", c.text)
        assertEquals("ETH", c.coin)
    }

    @Test
    fun myOwnIsYouAndSomeoneOffTheCallKeepsTheServersName() {
        assertEquals("You +KES 1,000", celebrationOf(contribution(userId = "me"), people, "me", kes).text)
        val mine = celebrationOf(contribution(userId = "me"), people, "me", kes, myPhotoUrl = "https://p/me")
        assertEquals("You added KES 1,000", mine.spoken)
        assertEquals("https://p/me", mine.photoUrl, "my own photo, as on my tile")
        assertEquals("Faith +KES 1,000", celebrationOf(contribution(userId = "faith", displayName = "Faith Achieng"), people, "me", kes).text)
        assertEquals("Someone +KES 1,000", celebrationOf(contribution(userId = null, displayName = null), people, "me", kes).text)
    }

    @Test
    fun withoutAnExchangeRateADollarCoinIsSaidInItsOwnUnitsToTheCent() {
        assertEquals("Amina +7.75 USDC", celebrationOf(contribution(), people, "me", fiat = null).text)
        // An older server's money.updated: the dollar amount only.
        assertEquals("Juma +KES 1,290", celebrationOf(contribution(userId = "juma", coin = null, coinAmount = "10", usdMinor = null), people, "me", kes).text)
    }

    @Test
    fun coinNumbersAreGroupedAndTrimmed() {
        assertEquals("1,234.5", coinNumber("1234.5000"))
        assertEquals("0.12345678", coinNumber("0.123456789"))
        assertEquals("2", coinNumber("2.000"))
    }

    // ---- the queue

    @Test
    fun sentIsOnlyPendingNothingFliesOrLandsUntilItConfirms() {
        var s = CelebrationState().sent(c("t1")).sent(c("t1"))
        assertEquals(listOf("t1"), s.pending.map { it.id })
        assertTrue(s.shown.isEmpty())
        assertTrue(s.recent.isEmpty())
        assertEquals(0, s.landings)

        s = s.confirmed(c("t1"))
        assertTrue(s.pending.isEmpty(), "confirmed, it is no longer on its way")
        assertEquals(listOf("t1"), s.shown.map { it.celebration.id })
        s = s.landed("t1").finished("t1")
        assertEquals(listOf("t1"), s.recent.map { it.id })
        assertEquals(1, s.landings)
        assertEquals(s, s.sent(c("t1")).confirmed(c("t1")), "a late frame for it changes nothing")
    }

    @Test
    fun oneThatNeverConfirmsIsDroppedNotCelebrated() {
        val s = CelebrationState().sent(c("t1")).expire("t1")
        assertTrue(s.pending.isEmpty())
        assertTrue(s.recent.isEmpty())
        assertEquals(0, s.landings)
    }

    @Test
    fun aConfirmationWithoutASentFrameStillLands() {
        val s = CelebrationState().confirmed(c("t1"))
        assertEquals(listOf("t1"), s.shown.map { it.celebration.id })
    }

    @Test
    fun theCallsOwnWordsFromThePendingPillAreKept() {
        val mine = Celebration("t1", "You", "KES 1,000", "USDC", "me")
        val s = CelebrationState().sent(mine).confirmed(c("t1"))
        assertEquals("You", s.shown.single().celebration.who)
    }

    @Test
    fun upToThreeFlyAtOnceInTheirOwnLanesAndTheRestWait() {
        var s = CelebrationState()
        listOf("a", "b", "c", "d").forEach { s = s.confirmed(c(it)) }
        assertEquals(listOf(0, 1, 2), s.shown.map { it.lane })
        assertEquals(listOf("d"), s.waiting.map { it.id })
        assertFalse(s.hurry)

        s = s.finished("b")
        assertEquals(listOf("a", "c", "d"), s.shown.map { it.celebration.id })
        assertEquals(1, s.shown.single { it.celebration.id == "d" }.lane, "the next one takes the lane that freed")
        assertTrue(s.waiting.isEmpty())
    }

    @Test
    fun landingAddsToTheRecentRowNewestFirstAndPulsesOnce() {
        var s = CelebrationState().confirmed(c("a")).confirmed(c("b"))
        s = s.landed("a").landed("a")
        assertEquals(1, s.landings)
        s = s.landed("b")
        assertEquals(listOf("b", "a"), s.recent.map { it.id })
        assertEquals(2, s.landings)
        s = s.confirmed(c("x")).landed("x")
        assertEquals(listOf("x", "b"), s.recent.map { it.id }, "two at most, as H4 shows")
    }

    @Test
    fun aBurstHurriesAndNeverQueuesForever() {
        var s = CelebrationState()
        (1..12).forEach { s = s.confirmed(c("t$it")) }
        assertEquals(3, s.shown.size)
        assertEquals(CelebrationState.MaxWaiting, s.waiting.size)
        assertTrue(s.hurry)
        // The oldest waiting ones skipped the animation and went straight to the row.
        assertEquals(listOf("t6", "t5"), s.recent.map { it.id })
        assertEquals(listOf("t7", "t8", "t9", "t10", "t11", "t12"), s.waiting.map { it.id })
    }

    @Test
    fun aMinimisedCallOnlyAddsToTheRow() {
        val s = CelebrationState().sent(c("a")).quiet(c("a")).quiet(c("a"))
        assertTrue(s.shown.isEmpty())
        assertTrue(s.pending.isEmpty())
        assertEquals(listOf("a"), s.recent.map { it.id })
        assertEquals(0, s.landings, "no pulse for what nobody watched")
        assertEquals(s, s.confirmed(c("a")), "and it never flies later")
    }
}
