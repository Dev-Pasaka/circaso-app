package com.circaso.core.notifications

import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.formatFiat
import com.circaso.feature.wallet.domain.usdcToFiat

/**
 * What a notification is about, worked out on the phone (pushes carry ids, never chat text or amounts; the phone
 * decrypts and looks things up first). [NotificationText.compose] turns it into the words, with or without
 * Notification privacy (You → Notification privacy, Pasaka 2026-11-06).
 */
sealed interface NotificationEvent {
    /** A chat message: [group] chats are titled by the group and the text names [sender]. */
    data class ChatMessage(val chatName: String?, val group: Boolean, val sender: String?, val text: String?, val momentReply: Boolean = false) : NotificationEvent

    /** A reaction to one of my messages; [target] is the message's text when it has some. */
    data class Reaction(val chatName: String?, val group: Boolean, val who: String?, val emoji: String, val target: String?) : NotificationEvent

    data class AddedToGroup(val groupName: String?) : NotificationEvent

    /** An invite to a group (2026-11-09): "Amina invited you to Book club". Either name may be unknown. */
    data class GroupInvite(val inviterName: String?, val groupName: String?) : NotificationEvent

    /**
     * Money to me: a payment card in a chat or the wallet's own `payment.received`. [groupName] is set when the card
     * sits in a group chat (a payout from the group wallet), so the title names the group and the text the sender.
     */
    data class MoneyReceived(val fromName: String?, val amount: Money?, val note: String? = null, val groupName: String? = null) : NotificationEvent

    /** Someone put money into a group I'm in (design F4: "Holiday Fund · Wanjiru added KES 1,000"). */
    data class GroupContribution(val groupName: String?, val memberName: String?, val amount: Money?) : NotificationEvent

    /** A payout request in a group moved; [state] says which way and whether it's mine to pay. */
    data class GroupPayout(val groupName: String?, val requesterName: String?, val state: PayoutState, val amount: Money?) : NotificationEvent

    data class IncomingCall(val callerName: String?, val video: Boolean) : NotificationEvent

    data class MissedCall(val callerName: String?, val video: Boolean = false) : NotificationEvent
}

/** The pushes the group wallet's payout requests send (`chama.request.*`), from the reader's side. */
enum class PayoutState {
    /** An admin's approval is needed. */
    NeedsApproval,
    /** My request was approved. */
    Approved,
    /** Approved, and I sign for the group wallet: it's mine to pay. */
    ReadyToPay,
    Declined,
    /** My request was paid. */
    Paid,
}

/** An amount in its coin, in minor units. */
data class Money(val amountMinor: String, val asset: String)

data class NotificationContent(val title: String, val text: String)

object NotificationText {
    const val AppName = "Circaso"
    const val PrivateMoney = "You received money"
    const val PrivateGroupMoney = "Money moved in a group"
    const val PrivateMessage = "New message"
    const val PrivateIncomingCall = "Incoming call"
    const val PrivateMissedCall = "Missed call"
    const val PrivateGroupInvite = "You have a new group invite"
    const val GroupInviteTitle = "Group invite"

    /**
     * What a chat message says, without who said it (a conversation notification names the sender beside it): its
     * preview, or "New message" when there's none.
     */
    fun chatLine(event: NotificationEvent.ChatMessage): String = said(event) ?: PrivateMessage

    private fun said(event: NotificationEvent.ChatMessage): String? =
        event.text?.takeIf { it.isNotBlank() }?.let { if (event.momentReply) "Replied to your moment: $it" else it }

    /**
     * The words for [event]. With [private] on, nothing that names a person, a group, an amount or what was said:
     * money is "You received money", chats "New message", calls "Incoming call". [fiat] is the person's currency and
     * its rate (the money card's), [usdPrice] the seam for a per-coin price.
     */
    fun compose(event: NotificationEvent, private: Boolean, fiat: FiatValue?, usdPrice: (String) -> String? = { null }): NotificationContent {
        fun amount(m: Money?) = m?.let { MoneyWording.amount(it, fiat, usdPrice(it.asset)) }
        return when (event) {
            is NotificationEvent.ChatMessage -> if (private) NotificationContent(AppName, PrivateMessage) else {
                val said = said(event)
                if (event.group) {
                    NotificationContent(
                        event.chatName ?: "Group",
                        when {
                            said != null && event.sender != null -> "${event.sender}: $said"
                            said != null -> said
                            event.sender != null -> "${event.sender} sent a message"
                            else -> PrivateMessage
                        },
                    )
                } else {
                    NotificationContent(event.sender ?: event.chatName ?: AppName, said ?: PrivateMessage)
                }
            }

            is NotificationEvent.Reaction -> if (private) NotificationContent(AppName, PrivateMessage) else {
                val target = event.target?.takeIf { it.isNotBlank() && !it.startsWith("{") }?.let { " to \"${it.take(60)}\"" } ?: " to your message"
                val who = event.who ?: "Someone"
                if (event.group) NotificationContent(event.chatName ?: "Group", "$who reacted ${event.emoji}$target")
                else NotificationContent(who, "Reacted ${event.emoji}$target")
            }

            is NotificationEvent.AddedToGroup -> if (private) NotificationContent(AppName, "You were added to a group")
            else NotificationContent(event.groupName ?: "New group", "You were added to a group")

            is NotificationEvent.GroupInvite -> if (private) NotificationContent(AppName, PrivateGroupInvite)
            else NotificationContent(
                GroupInviteTitle,
                "${event.inviterName ?: "Someone"} invited you to ${event.groupName ?: "a group"}",
            )

            is NotificationEvent.MoneyReceived -> if (private) NotificationContent(AppName, PrivateMoney) else {
                val sum = amount(event.amount)
                val note = event.note?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("Paid from") }?.let { " · $it" }.orEmpty()
                when {
                    event.groupName != null -> NotificationContent(
                        event.groupName,
                        (event.fromName?.let { "$it sent you " } ?: "You received ") + (sum ?: "money") + note,
                    )
                    event.fromName != null -> NotificationContent(event.fromName, "Sent you ${sum ?: "money"}$note")
                    sum != null -> NotificationContent(AppName, "You received $sum$note")
                    else -> NotificationContent(AppName, PrivateMoney)
                }
            }

            is NotificationEvent.GroupContribution -> if (private) NotificationContent(AppName, PrivateGroupMoney) else {
                val who = event.memberName ?: "Someone"
                NotificationContent(event.groupName ?: "Your group", "$who added ${amount(event.amount) ?: "money"}")
            }

            is NotificationEvent.GroupPayout -> if (private) NotificationContent(AppName, PrivateGroupMoney) else {
                val sum = amount(event.amount)
                val title = event.groupName ?: "Your group"
                val text = when (event.state) {
                    PayoutState.NeedsApproval -> "${event.requesterName ?: "Someone"} needs your approval to pay ${sum ?: "from the group wallet"}"
                    PayoutState.Approved -> if (sum != null) "Your request for $sum was approved" else "Your request was approved"
                    PayoutState.ReadyToPay -> if (sum != null) "A payout of $sum was approved. It's ready for you to pay" else "A payout was approved. It's ready for you to pay"
                    PayoutState.Declined -> if (sum != null) "Your request for $sum was declined" else "Your request was declined"
                    PayoutState.Paid -> if (sum != null) "Your request for $sum was paid" else "Your request was paid"
                }
                NotificationContent(title, text)
            }

            is NotificationEvent.IncomingCall -> if (private) NotificationContent(AppName, PrivateIncomingCall)
            else NotificationContent(event.callerName ?: PrivateIncomingCall, if (event.video) "Circaso video call" else "Circaso voice call")

            is NotificationEvent.MissedCall -> if (private) NotificationContent(AppName, PrivateMissedCall)
            else NotificationContent(PrivateMissedCall, event.callerName?.let { "Missed ${if (event.video) "video call" else "call"} from $it" } ?: "You missed a call")
        }
    }
}

/**
 * Amounts the way the money card writes them: in the person's own currency ("KES 2,000") when there's a price,
 * else in the coin ("3.88 USDC"). Today only dollar coins have one (USD → the person's currency, from the wallet's
 * `fiat`); [usdPrice] is the seam for a per-coin USD price (another change adds `usdPrice` per asset), used first
 * when given.
 */
object MoneyWording {
    fun amount(money: Money, fiat: FiatValue?, usdPrice: String? = null): String {
        val coin = MinorUnits.coinText(money.amountMinor, money.asset)
        if (fiat == null) return coin
        val usdMinor = when {
            // A per-coin price: the coin's amount in dollars, to 6 decimals, then to the person's currency.
            usdPrice != null -> usdMicros(money, usdPrice)
            MinorUnits.isDollar(money.asset) -> rescale(money.amountMinor, MinorUnits.decimalsOf(money.asset), 6)
            else -> null
        } ?: return coin
        val local = usdcToFiat(usdMinor, fiat.rate) ?: return coin
        return formatFiat(fiat.currency, roundLarge(local))
    }

    /** Fiat of 100 or more reads in whole units, as the money card and the design do ("KES 2,000", "KES 15.50"). */
    private fun roundLarge(fiat: String): String {
        val units = fiat.substringBefore('.').toLongOrNull() ?: return fiat
        if (units < 100) return fiat
        val cents = fiat.substringAfter('.', "0").padEnd(2, '0').take(2).toIntOrNull() ?: 0
        return (if (cents >= 50) units + 1 else units).toString()
    }

    private fun rescale(minor: String, from: Int, to: Int): String = when {
        from == to -> minor
        from > to -> minor.dropLast(from - to).ifEmpty { "0" }
        else -> minor + "0".repeat(to - from)
    }

    /** amount (minor, its decimals) × price (decimal string) → micro-dollars, rounded down. */
    private fun usdMicros(money: Money, usdPrice: String): String? {
        val price = MinorUnits.fromDisplay(usdPrice, 6) ?: return null
        val decimals = MinorUnits.decimalsOf(money.asset)
        val product = com.circaso.feature.wallet.domain.bigMultiply(money.amountMinor.trimStart('0').ifEmpty { "0" }, price)
        // product has decimals + 6 decimals; keep 6.
        return product.dropLast(decimals).ifEmpty { "0" }
    }
}
