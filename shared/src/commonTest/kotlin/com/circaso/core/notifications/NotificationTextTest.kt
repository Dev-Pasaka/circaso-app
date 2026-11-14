package com.circaso.core.notifications

import com.circaso.feature.wallet.domain.FiatValue
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationTextTest {
    private val kes = FiatValue(currency = "KES", amount = "0", rate = "100", asOf = "2026-11-06T00:00:00Z", partial = false)
    private val twentyDollars = Money("20000000", "USDC") // 20 USDC = KES 2,000 at 100

    private fun text(event: NotificationEvent, private: Boolean = false, fiat: FiatValue? = kes) = NotificationText.compose(event, private, fiat)

    @Test fun moneyForMyMomentSaysSo() {
        assertEquals(NotificationContent("Amina", "Sent you KES 2,000 for your moment"), text(NotificationEvent.MoneyReceived("Amina", twentyDollars, forMoment = true)))
        assertEquals("Sent you KES 2,000 for your moment · love this", text(NotificationEvent.MoneyReceived("Amina", twentyDollars, "love this", forMoment = true)).text)
        assertEquals("You received KES 2,000 for your moment", text(NotificationEvent.MoneyReceived(null, twentyDollars, forMoment = true)).text)
        // Privacy on: nothing about what it was for.
        assertEquals(NotificationContent(NotificationText.AppName, NotificationText.PrivateMoney), text(NotificationEvent.MoneyReceived("Amina", twentyDollars, forMoment = true), private = true))
        // A group's card never says it.
        assertEquals("Amina sent you KES 2,000", text(NotificationEvent.MoneyReceived("Amina", twentyDollars, groupName = "Diani", forMoment = true)).text)
    }

    @Test fun moneyIsInThePersonsCurrencyWithTheSenderAndNote() {
        assertEquals(NotificationContent("Amina Wanjiku", "Sent you KES 2,000 · for lunch"), text(NotificationEvent.MoneyReceived("Amina Wanjiku", twentyDollars, "for lunch")))
    }

    @Test fun moneyWithoutARateFallsBackToTheCoin() {
        assertEquals("Sent you 20.00 USDC", text(NotificationEvent.MoneyReceived("Amina", twentyDollars), fiat = null).text)
    }

    @Test fun aCoinWithNoPriceShowsAsItself() {
        assertEquals("Sent you 0.01 ETH", text(NotificationEvent.MoneyReceived("Amina", Money("10000000000000000", "ETH"))).text)
    }

    @Test fun aPerCoinPriceIsUsedWhenGiven() {
        // 0.01 ETH at 3,000 USD = 30 USD = KES 3,000 at 100.
        val content = NotificationText.compose(NotificationEvent.MoneyReceived("Amina", Money("10000000000000000", "ETH")), false, kes) { if (it == "ETH") "3000" else null }
        assertEquals("Sent you KES 3,000", content.text)
    }

    @Test fun smallAmountsKeepTheirCents() {
        assertEquals("Sent you KES 15.50", text(NotificationEvent.MoneyReceived("Amina", Money("155000", "USDC"))).text)
    }

    @Test fun moneyWithNoAmountOrSender() {
        assertEquals(NotificationContent("Amina", "Sent you money"), text(NotificationEvent.MoneyReceived("Amina", null)))
        assertEquals(NotificationContent("Circaso", "You received money"), text(NotificationEvent.MoneyReceived(null, null)))
        assertEquals(NotificationContent("Circaso", "You received KES 2,000"), text(NotificationEvent.MoneyReceived(null, twentyDollars)))
    }

    @Test fun aPayoutCardInAGroupNamesTheGroupAndTheSender() {
        assertEquals(NotificationContent("Holiday Fund", "Otieno sent you KES 2,000"), text(NotificationEvent.MoneyReceived("Otieno", twentyDollars, "Paid from Holiday Fund", groupName = "Holiday Fund")))
    }

    @Test fun groupContributionsAndRequestsNameTheGroup() {
        assertEquals(NotificationContent("Holiday Fund", "Wanjiru added KES 2,000"), text(NotificationEvent.GroupContribution("Holiday Fund", "Wanjiru", twentyDollars)))
        assertEquals(NotificationContent("Holiday Fund", "Otieno needs your approval to pay KES 2,000"), text(NotificationEvent.GroupPayout("Holiday Fund", "Otieno", PayoutState.NeedsApproval, twentyDollars)))
        assertEquals("Your request for KES 2,000 was paid", text(NotificationEvent.GroupPayout("Holiday Fund", null, PayoutState.Paid, twentyDollars)).text)
        assertEquals("A payout of KES 2,000 was approved. It's ready for you to pay", text(NotificationEvent.GroupPayout("Holiday Fund", null, PayoutState.ReadyToPay, twentyDollars)).text)
        assertEquals("Your request was declined", text(NotificationEvent.GroupPayout("Holiday Fund", null, PayoutState.Declined, null)).text)
    }

    @Test fun privacyHidesEveryMoneyDetail() {
        val expected = NotificationContent("Circaso", "You received money")
        assertEquals(expected, text(NotificationEvent.MoneyReceived("Amina Wanjiku", twentyDollars, "for lunch"), private = true))
        assertEquals(expected, text(NotificationEvent.MoneyReceived("Otieno", twentyDollars, groupName = "Holiday Fund"), private = true))
        assertEquals(NotificationContent("Circaso", "Money moved in a group"), text(NotificationEvent.GroupContribution("Holiday Fund", "Wanjiru", twentyDollars), private = true))
        assertEquals(NotificationContent("Circaso", "Money moved in a group"), text(NotificationEvent.GroupPayout("Holiday Fund", "Otieno", PayoutState.NeedsApproval, twentyDollars), private = true))
    }

    @Test fun chatMessages() {
        assertEquals(NotificationContent("Amina", "See you at 6"), text(NotificationEvent.ChatMessage("Amina", false, "Amina", "See you at 6")))
        assertEquals(NotificationContent("Family", "Amina: See you at 6"), text(NotificationEvent.ChatMessage("Family", true, "Amina", "See you at 6")))
        assertEquals("Replied to your moment: nice", text(NotificationEvent.ChatMessage("Amina", false, "Amina", "nice", momentReply = true)).text)
        assertEquals(NotificationContent("Amina", "Reacted ❤️ to \"lunch?\""), text(NotificationEvent.Reaction("Amina", false, "Amina", "❤️", "lunch?")))
    }

    @Test fun privacyHidesTheSenderAndThePreview() {
        val expected = NotificationContent("Circaso", "New message")
        assertEquals(expected, text(NotificationEvent.ChatMessage("Family", true, "Amina", "See you at 6"), private = true))
        assertEquals(expected, text(NotificationEvent.ChatMessage("Amina", false, "Amina", "See you at 6"), private = true))
        assertEquals(expected, text(NotificationEvent.Reaction("Amina", false, "Amina", "❤️", "lunch?"), private = true))
        assertEquals(NotificationContent("Circaso", "You were added to a group"), text(NotificationEvent.AddedToGroup("Family"), private = true))
    }

    @Test fun calls() {
        assertEquals(NotificationContent("Amina", "Circaso video call"), text(NotificationEvent.IncomingCall("Amina", true)))
        assertEquals(NotificationContent("Circaso", "Incoming call"), text(NotificationEvent.IncomingCall("Amina", true), private = true))
        assertEquals(NotificationContent("Missed call", "Missed call from Amina"), text(NotificationEvent.MissedCall("Amina")))
        assertEquals(NotificationContent("Circaso", "Missed call"), text(NotificationEvent.MissedCall("Amina"), private = true))
    }
}
