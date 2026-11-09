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

/**
 * Notifies a message that came over the open socket while Circaso isn't on screen (Android keeps its socket while the
 * process lives, and the server pushes only to phones without one). iOS has none: it is suspended off screen.
 */
interface LiveMessageNotifier {
    /** Returns at once; the wording and showing happen on the app's scope. */
    fun notify(chatId: String, messageId: String)
}

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
    /**
     * A chat message's parts, for a conversation notification (Android's MessagingStyle with the sender's photo, iOS's
     * communication notification): who said what, in which chat. Null with Notification privacy on, and for anything
     * that isn't a message; then [content] is shown as it is.
     */
    val conversation: NoticeConversation? = null,
)

/**
 * One message in a conversation notification. [title] is the group's name (null for a direct chat, which is titled
 * by the sender); [line] is what was said without the sender's name ("📷 Photo", "Reacted 👍 to …"). The photos are
 * what the app itself shows: [senderPhoto] the profile picture's link, [groupPhoto] the group's decrypted local copy;
 * either may be missing, and the platform then draws initials.
 */
data class NoticeConversation(
    val chatId: String,
    val group: Boolean,
    val title: String?,
    val senderId: String?,
    val senderName: String,
    val senderPhoto: String?,
    val groupPhoto: String?,
    val line: String,
    val at: Long,
    /** The message it is about, so a line already shown (a push and a live frame for one message) isn't added twice. */
    val messageId: String? = null,
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
) {
    suspend fun privacyOn(): Boolean = prefs.flag(AppPreferences.NotificationPrivacy).first()

    private suspend fun fiat() = withTimeoutOrNull(1_000) { wallet.wallet.first()?.fiat }

    private suspend fun compose(event: NotificationEvent) = NotificationText.compose(event, privacyOn(), fiat())

    /**
     * Null when there is nothing to show (a control message, a taken-back reaction, a ringing call). [sync] false: the
     * message is already on this phone (it came over the socket), so the inbox isn't pulled first.
     */
    suspend fun forPush(data: Map<String, String>, sync: Boolean = true): Notice? {
        val kind = data["kind"]
        val chatId = data["chatId"]?.takeIf { it.isNotBlank() }
        return when {
            kind == "payment.received" -> moneyReceived(data)
            kind == "chama.contribution" && chatId != null -> contribution(chatId, data["userId"])
            kind?.startsWith("chama.request") == true && chatId != null -> payoutRequest(chatId, kind, data["requestId"])
            kind == "call.missed" || kind == "call.ended" -> missedCall(chatId, data["callId"], data["callerName"], data["callKind"] == "VIDEO")
            kind != null && kind.startsWith("space.") -> null // Spaces is archived (2026-11-05)
            chatId != null && data["messageId"] == null && kind == null -> addedToGroup(chatId)
            chatId != null && data["messageId"] != null -> message(chatId, data["messageId"]!!, data["senderId"]?.takeIf { it.isNotBlank() }, sync)
            else -> null
        }
    }

    private suspend fun addedToGroup(chatId: String): Notice {
        withTimeoutOrNull(SyncMillis) { chatsRepo.refreshChat(chatId) }
        val content = compose(NotificationEvent.AddedToGroup(chats.chat(chatId)?.name))
        return Notice(content, NoticeChannel.Message, "chat:$chatId", NotificationDestination.Chat(chatId), chatId)
    }

    private suspend fun senderName(chatId: String, userId: String?) =
        userId?.let { id -> chats.members(chatId).firstOrNull { it.userId == id }?.displayName }

    /**
     * Who sent it and the photos, as the chat list shows them: the member's name, else (a direct chat) the chat's own
     * title, which is the other person. Null name means not known on this phone; the wording then falls back.
     */
    private suspend fun who(chatId: String, userId: String?, group: Boolean): Who {
        val member = userId?.let { id -> chats.members(chatId).firstOrNull { it.userId == id } }
        val chat = withTimeoutOrNull(LookupMillis) { chatsRepo.chat(chatId).first() }
        val name = member?.displayName?.takeIf { it.isNotBlank() } ?: chat?.title?.takeIf { !group && it.isNotBlank() && it != "Chat" }
        val photo = member?.avatarUrl ?: chat?.avatarUrl?.takeIf { !group }
        return Who(name, photo, chat?.avatarUrl?.takeIf { group }, chat?.title?.takeIf { group })
    }

    private class Who(val name: String?, val photo: String?, val groupPhoto: String?, val groupName: String?)

    /**
     * Null with privacy on, and when the sender isn't known on this phone: then the plain words show, never a person
     * made up (and never one named "Circaso", which a stranger's message must not look like).
     */
    private suspend fun conversation(chatId: String, group: Boolean, senderId: String?, who: Who, chatName: String?, line: String, at: Long?, messageId: String? = null): NoticeConversation? {
        if (privacyOn()) return null
        val sender = who.name ?: return null
        return NoticeConversation(
            chatId = chatId, group = group, title = if (group) chatName ?: who.groupName ?: "Group" else null,
            senderId = senderId, senderName = sender,
            senderPhoto = who.photo, groupPhoto = who.groupPhoto, line = line, at = at ?: com.circaso.core.sync.nowMillis(), messageId = messageId,
        )
    }

    /**
     * [senderId] comes with the push (ids only, 2026-11-09): when the inbox can't be pulled now (the connection
     * dropped after the push), the notification still names the sender, rather than nothing being shown at all.
     */
    private suspend fun message(chatId: String, messageId: String, senderId: String? = null, sync: Boolean = true): Notice? {
        // Bounded, so a slow network still leaves time to word and show it (FCM gives about ten seconds in all).
        val synced = !sync || withTimeoutOrNull(SyncMillis) { messages.syncInbox() } is AppResult.Success
        val chat = chats.chat(chatId)
        val group = chat?.kind == "GROUP"
        // A reaction isn't a message: name it as WhatsApp does, or say nothing when one was taken back.
        inbox.reactionCarriedBy(messageId)?.let { r ->
            val target = messagesDao.byMessageId(r.targetMessageId)?.let { MessagePreview.of(it.kind, it.text, it.status) }
            val who = who(chatId, r.senderId, group)
            val chatName = chat?.name ?: who.groupName
            val content = compose(NotificationEvent.Reaction(chatName, group, who.name, r.emoji, target))
            val line = NotificationText.compose(NotificationEvent.Reaction(null, false, who.name, r.emoji, target), false, null).text
            val conversation = conversation(chatId, group, r.senderId, who, chatName, line, null)
            return Notice(content, NoticeChannel.Message, "chat:$chatId", NotificationDestination.Chat(chatId, r.targetMessageId), chatId, conversation)
        }
        val row = messagesDao.byMessageId(messageId) ?: return if (synced || senderId == null) null else unopened(chatId, messageId, senderId, group, chat?.name)
        val who = who(chatId, row.senderId, group)
        val chatName = chat?.name ?: who.groupName
        if (row.kind == MessageKind.Payment && row.transferId != null) return paymentCard(chatId, chatName, group, who.name, row)
        val said = MessagePreview.of(row.kind, row.text, row.status)
        val event = NotificationEvent.ChatMessage(chatName, group, who.name, said, momentReply = row.momentQuote != null)
        val content = compose(event)
        val line = NotificationText.chatLine(event)
        val conversation = conversation(chatId, group, row.senderId, who, chatName, line, row.orderAt, messageId)
        return Notice(content, NoticeChannel.Message, "chat:$chatId", NotificationDestination.Chat(chatId, messageId, row.localId), chatId, conversation)
    }

    /** A message this phone couldn't pull yet: who sent it and where, without what it says. */
    private suspend fun unopened(chatId: String, messageId: String, senderId: String, group: Boolean, name: String?): Notice {
        val who = who(chatId, senderId, group)
        val chatName = name ?: who.groupName
        val event = NotificationEvent.ChatMessage(chatName, group, who.name, null)
        val conversation = conversation(chatId, group, senderId, who, chatName, NotificationText.chatLine(event), null, messageId)
        return Notice(compose(event), NoticeChannel.Message, "chat:$chatId", NotificationDestination.Chat(chatId, messageId), chatId, conversation)
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
        if (chatId != null) withTimeoutOrNull(SyncMillis) { messages.syncInbox() }
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
        withTimeoutOrNull(SyncMillis) { messages.syncInbox() }
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
        val caller = callerName ?: chat?.takeIf { it.kind != "GROUP" }?.name ?: chat?.name
        val content = compose(NotificationEvent.MissedCall(caller, video))
        return Notice(content, NoticeChannel.Message, "call:${callId ?: chatId}", NotificationDestination.MissedCall(chatId, callId), chatId)
    }

    /** A ringing call's words, with privacy: "Incoming call" and nothing more. */
    suspend fun incomingCall(callerName: String?, video: Boolean): NotificationContent =
        NotificationText.compose(NotificationEvent.IncomingCall(callerName, video), privacyOn(), null)

    private companion object {
        /** How far back a contribution's card is looked for in its group chat. */
        const val RecentCards = 30
        /** A local lookup (the chat's title and photos); never worth holding the notification for longer. */
        const val LookupMillis = 1_000L
        /** The inbox pull inside a push's window: the rest of the window words and shows it, by name only if need be. */
        const val SyncMillis = 4_500L
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
