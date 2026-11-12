package com.circaso.core.notifications

import com.circaso.core.datastore.AppPreferences
import com.circaso.feature.chama.domain.ChamaRepository
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.local.MessageEntity
import com.circaso.feature.messaging.data.local.MessageKind
import com.circaso.feature.messaging.data.local.MessagesDao
import com.circaso.feature.messaging.data.repository.InboxProcessor
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.MessagesRepository
import com.circaso.feature.wallet.domain.WalletRepository
import com.circaso.core.common.AppResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The notification channel a notice goes on: money has its own chime, calls ring. */
enum class NoticeChannel { Message, Money, Call }

/**
 * One notification ready to show. [key] decides which notification it replaces: every notice about one payment shares
 * `money:<transferId>`, so the chat's card and the wallet's `payment.received` for the same money show once.
 */
data class Notice(
    val content: NotificationContent,
    val channel: NoticeChannel,
    val key: String,
    val destination: NotificationDestination?,
    /** The chat it belongs to, for "no notification for the chat on screen" and clearing it when the chat opens. */
    val chatId: String? = null,
)

/**
 * Turns a push (guide section 8; ids and a `kind`, never chat text or an amount) into what the phone shows: it
 * decrypts and looks things up first, then words it with [NotificationText], honouring Notification privacy.
 * Shared so every platform words a push the same way; Android's messaging service calls it inside FCM's window.
 */
class PushNotices(
    private val chats: ChatsDao,
    private val messagesDao: MessagesDao,
    private val chatsRepo: ChatsRepository,
    private val messages: MessagesRepository,
    private val inbox: InboxProcessor,
    private val wallet: WalletRepository,
    private val chama: ChamaRepository,
    private val prefs: AppPreferences,
    /** Group invites (2026-11-09): an invite's push is worded from the invite itself. */
    private val invites: com.circaso.feature.messaging.domain.GroupInvitesRepository? = null,
    /** Names I saved for people: a notice the phone words itself uses them (the server's own pushes can't). */
    private val names: com.circaso.feature.names.domain.PeopleNames = com.circaso.feature.names.domain.PeopleNames.None,
) {
    suspend fun privacyOn(): Boolean = prefs.flag(AppPreferences.NotificationPrivacy).first()

    private suspend fun fiat() = withTimeoutOrNull(1_000) { wallet.wallet.first()?.fiat }

    private suspend fun compose(event: NotificationEvent) = NotificationText.compose(event, privacyOn(), fiat())

    /** Null when there is nothing to show (a control message, a taken-back reaction, a ringing call). */
    suspend fun forPush(data: Map<String, String>): Notice? {
        val kind = data["kind"]
        val chatId = data["chatId"]?.takeIf { it.isNotBlank() }
        return when {
            kind == InviteKind && chatId != null -> groupInvite(chatId, data["userId"])
            kind == "payment.received" -> moneyReceived(data)
            kind == "chama.contribution" && chatId != null -> contribution(chatId, data["userId"])
            kind?.startsWith("chama.request") == true && chatId != null -> payoutRequest(chatId, kind, data["requestId"])
            kind == "call.missed" || kind == "call.ended" -> missedCall(chatId, data["callId"], data["callerName"], data["callKind"] == "VIDEO")
            kind != null && kind.startsWith("space.") -> null // Spaces is archived (2026-11-05)
            chatId != null && data["messageId"] == null && kind == null -> addedToGroup(chatId)
            chatId != null && data["messageId"] != null -> message(chatId, data["messageId"]!!)
            else -> null
        }
    }

    private suspend fun addedToGroup(chatId: String): Notice {
        chatsRepo.refreshChat(chatId)
        val content = compose(NotificationEvent.AddedToGroup(chats.chat(chatId)?.name))
        return Notice(content, NoticeChannel.Message, "chat:$chatId", NotificationDestination.Chat(chatId), chatId)
    }

    /**
     * `chat.invite` (group invites, 2026-11-09): the invites are loaded first, which opens the key the inviter sealed for
     * me, so it reads "Amina invited you to Book club". The tap opens Chats, where the invite waits at the top. One
     * notice per group, so a cancelled-then-repeated invite never stacks.
     */
    private suspend fun groupInvite(chatId: String, inviterId: String?): Notice {
        withTimeoutOrNull(InviteLoadMillis) { invites?.refresh() }
        val invite = invites?.let { repo -> withTimeoutOrNull(1_000) { repo.invites.first() } }?.firstOrNull { it.chatId == chatId }
        val inviter = invite?.invitedBy?.displayName
        val content = compose(NotificationEvent.GroupInvite(inviter, invite?.groupName))
        return Notice(content, NoticeChannel.Message, "invite:$chatId", NotificationDestination.Invites, null)
    }

    private suspend fun senderName(chatId: String, userId: String?) =
        userId?.let { id -> names.orNull(id, chats.members(chatId).firstOrNull { it.userId == id }?.displayName) }

    private suspend fun message(chatId: String, messageId: String): Notice? {
        messages.syncInbox()
        val chat = chats.chat(chatId)
        val group = chat?.kind == "GROUP"
        // A reaction isn't a message: name it as WhatsApp does, or say nothing when one was taken back.
        inbox.reactionCarriedBy(messageId)?.let { r ->
            val target = messagesDao.byMessageId(r.targetMessageId)?.text
            val content = compose(NotificationEvent.Reaction(chat?.name, group, senderName(chatId, r.senderId), r.emoji, target))
            return Notice(content, NoticeChannel.Message, "chat:$chatId", NotificationDestination.Chat(chatId, r.targetMessageId), chatId)
        }
        val row = messagesDao.byMessageId(messageId) ?: return null // a control message: nothing to show
        val sender = senderName(chatId, row.senderId)
        if (row.kind == MessageKind.Payment && row.transferId != null) return paymentCard(chatId, chat?.name, group, sender, row)
        val content = compose(NotificationEvent.ChatMessage(chat?.name, group, sender, row.text, momentReply = row.momentQuote != null))
        return Notice(content, NoticeChannel.Message, "chat:$chatId", NotificationDestination.Chat(chatId, messageId, row.localId), chatId)
    }

    /** A money card in a chat: money, on the money channel, worded in the person's currency. */
    private suspend fun paymentCard(chatId: String, chatName: String?, group: Boolean, sender: String?, row: MessageEntity): Notice {
        val money = Money(row.amountMinor ?: "0", row.asset ?: "USDC")
        val note = row.text
        val event = when {
            !group -> NotificationEvent.MoneyReceived(sender, money, note)
            // Group wallets (GW18): the card's note says the money left the group's wallet; otherwise it went in.
            note?.startsWith("Paid from") == true -> NotificationEvent.MoneyReceived(sender, money, null, groupName = chatName ?: "Your group")
            else -> NotificationEvent.GroupContribution(chatName, sender, money)
        }
        val destination = NotificationDestination.Payment(chatId, row.transferId)
        return Notice(compose(event), NoticeChannel.Money, "money:${row.transferId}", destination, chatId)
    }

    /** `payment.received` (Wallet → Realtime frames): the amount from the chat's card, else the wallet's record. */
    private suspend fun moneyReceived(data: Map<String, String>): Notice {
        val transferId = data["transferId"]?.takeIf { it.isNotBlank() }
        val transactionId = data["transactionId"]?.takeIf { it.isNotBlank() }
        val chatId = data["chatId"]?.takeIf { it.isNotBlank() }
        val key = transferId ?: transactionId
        if (chatId != null) messages.syncInbox()
        val card = if (chatId != null && transferId != null) messagesDao.paymentCardLocalId(chatId, transferId)?.let { messagesDao.byLocalId(it) } else null
        val destination = NotificationDestination.Payment(chatId, transferId, transactionId)
        if (card != null && chatId != null) {
            val chat = chats.chat(chatId)
            return paymentCard(chatId, chat?.name, chat?.kind == "GROUP", senderName(chatId, card.senderId), card)
        }
        // No card (money from outside Circaso, or the card is still on its way): the wallet's own record of it.
        if (key != null) withTimeoutOrNull(3_000) { wallet.refresh() }
        val transfer = key?.let { withTimeoutOrNull(1_000) { wallet.transfer(it).first() } }
        val from = transfer?.counterpartName ?: data["fromName"]?.takeIf { it.isNotBlank() } ?: nameFromBody(data["body"])
        val money = transfer?.amount?.let { Money(it.amountMinor, it.asset) }
        val content = compose(NotificationEvent.MoneyReceived(from, money, transfer?.note))
        return Notice(content, NoticeChannel.Money, "money:${key ?: data["body"].hashCode()}", destination, chatId)
    }

    /** "Amina sent you money" (an older server's words): the name, if that's what it says. */
    private fun nameFromBody(body: String?): String? = body?.takeIf { it.endsWith(" sent you money") }?.removeSuffix(" sent you money")

    /** `chama.contribution`: the member's card in the group chat carries the amount; it shows as the same notice. */
    private suspend fun contribution(chatId: String, userId: String?): Notice {
        messages.syncInbox()
        val chatName = chats.chat(chatId)?.name
        val sender = senderName(chatId, userId)
        val card = userId?.let { latestCardFrom(chatId, it) }
        val event = NotificationEvent.GroupContribution(chatName, sender, card?.let { Money(it.amountMinor ?: "0", it.asset ?: "USDC") })
        val key = card?.transferId?.let { "money:$it" } ?: "group:$chatId:${userId.orEmpty()}"
        return Notice(compose(event), NoticeChannel.Money, key, NotificationDestination.Payment(chatId, card?.transferId), chatId)
    }

    private suspend fun latestCardFrom(chatId: String, userId: String): MessageEntity? =
        messagesDao.observeRecent(chatId, RecentCards).first()
            .firstOrNull { it.kind == MessageKind.Payment && it.senderId == userId && it.note()?.startsWith("Paid from") != true }

    private fun MessageEntity.note() = text

    /** `chama.request.<state>`: a payout request moved; the amount from the group's copy when it is still open. */
    private suspend fun payoutRequest(chatId: String, kind: String, requestId: String?): Notice? {
        val state = when (kind.removePrefix("chama.request").removePrefix(".")) {
            "pending" -> PayoutState.NeedsApproval
            "approved" -> PayoutState.Approved
            "payable" -> PayoutState.ReadyToPay
            "declined" -> PayoutState.Declined
            "paid" -> PayoutState.Paid
            else -> return null
        }
        val money = withTimeoutOrNull(3_000) { (chama.refreshMoney(chatId) as? AppResult.Success)?.value } ?: chama.money(chatId).first()
        val request = money?.openRequests?.firstOrNull { it.requestId == requestId }
        val event = NotificationEvent.GroupPayout(
            chats.chat(chatId)?.name, senderName(chatId, request?.requestedBy), state,
            request?.let { Money(it.amountMinor, "USDC") },
        )
        val destination = NotificationDestination.Chat(chatId)
        return Notice(compose(event), NoticeChannel.Money, "request:${requestId ?: chatId}", destination, chatId)
    }

    private suspend fun missedCall(chatId: String?, callId: String?, callerName: String?, video: Boolean): Notice {
        val chat = chatId?.let { chats.chat(it) }
        // A direct call: the name I saved for them first, over the server's.
        val saved = chat?.takeIf { it.kind != "GROUP" }?.let { c -> chats.members(c.chatId).firstNotNullOfOrNull { names.saved.value[it.userId] } }
        val caller = saved ?: callerName ?: chat?.takeIf { it.kind != "GROUP" }?.name ?: chat?.name
        val content = compose(NotificationEvent.MissedCall(caller, video))
        return Notice(content, NoticeChannel.Message, "call:${callId ?: chatId}", NotificationDestination.MissedCall(chatId, callId), chatId)
    }

    /** A ringing call's words, with privacy: "Incoming call" and nothing more. */
    suspend fun incomingCall(callerName: String?, video: Boolean): NotificationContent =
        NotificationText.compose(NotificationEvent.IncomingCall(callerName, video), privacyOn(), null)

    private companion object {
        /** How far back a contribution's card is looked for in its group chat. */
        const val RecentCards = 30
        /** The invite push's `kind` (backend PR #83). */
        const val InviteKind = "chat.invite"
        /** How long an invite push waits for the invites to load before it words itself without the group's name. */
        const val InviteLoadMillis = 4_000L
    }
}

/** [NotificationLookups] from the phone's own copy, pulling the inbox once when a tapped message isn't here yet. */
class AppNotificationLookups(
    private val messagesDao: MessagesDao,
    private val messages: MessagesRepository,
    private val wallet: WalletRepository,
) : NotificationLookups {
    private var synced = false

    private suspend fun <T> orAfterSync(find: suspend () -> T?): T? = find() ?: run {
        if (synced) return null
        synced = true
        withTimeoutOrNull(SyncMillis) { messages.syncInbox() }
        find()
    }

    override suspend fun messageLocalId(messageId: String) = orAfterSync { messagesDao.byMessageId(messageId)?.localId }
    override suspend fun paymentCardLocalId(chatId: String, transferId: String) = orAfterSync { messagesDao.paymentCardLocalId(chatId, transferId) }
    override suspend fun lastCallLocalId(chatId: String) = messagesDao.lastCallLocalId(chatId)
    override suspend fun chatOfPayment(key: String) = withTimeoutOrNull(1_000) { wallet.transfer(key).first()?.chatId }

    private companion object { const val SyncMillis = 4_000L }
}
