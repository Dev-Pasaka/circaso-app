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
    /**
     * A chat message's parts, for a conversation notification (Android's MessagingStyle with the sender's photo): who
     * said what, in which chat. Null with Notification privacy on, and for anything that isn't a message; then
     * [content] is shown as it is.
     */
    val conversation: NoticeConversation? = null,
    /**
     * Who money came from (2026-11-13), so a money notification shows their photo beside the amount. Null with
     * Notification privacy on, and when the sender isn't known on this phone.
     */
    val from: NoticePerson? = null,
    /** The group's photo when [from]'s money moved in a group chat (this phone's decrypted copy). */
    val groupPhoto: String? = null,
    /** Money in a group chat (titled by the group, [from] named in [fromLine]). */
    val group: Boolean = false,
    /** Money's words beside [from]'s name and photo, without the name ("Sent you KES 2,000", "Added KES 1,000"). */
    val fromLine: String? = null,
    /**
     * A notice about a person that isn't money (a reaction on my moment, a missed call, a contact who joined;
     * 2026-11-14): shown like a chat message, [from]'s photo as the icon and [fromLine] beside their name. Notices with
     * the same [key] gather in one notification, one line per person ([thread] titles it once two or more are in it).
     */
    val thread: String? = null,
)

/**
 * A person on a notification: the name as this phone shows it (a saved name first) and their photo's link.
 * [directChatId] is my chat with them, when there is one, so their notification shares the chat's conversation.
 */
data class NoticePerson(val userId: String?, val name: String, val photo: String?, val directChatId: String? = null)

/**
 * One message in a conversation notification. [title] is the group's name (null for a direct chat, which is titled by
 * the sender); [line] is what was said without the sender's name ("📷 Photo", "Reacted 👍 to …"). [groupPhoto] is the
 * group's decrypted local copy. Photos may be missing: the platform then draws initials.
 */
data class NoticeConversation(
    val chatId: String,
    val group: Boolean,
    val title: String?,
    val sender: NoticePerson,
    val groupPhoto: String?,
    val line: String,
    val at: Long,
    /** The message it is about, so a line already shown (FCM delivering twice) isn't added again. */
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
    /** Group invites (2026-11-09): an invite's push is worded from the invite itself. */
    private val invites: com.circaso.feature.messaging.domain.GroupInvitesRepository? = null,
    /** Names I saved for people: a notice the phone words itself uses them (the server's own pushes can't). */
    private val names: com.circaso.feature.names.domain.PeopleNames = com.circaso.feature.names.domain.PeopleNames.None,
    /** My contacts (saved names applied): who a person is when no chat of mine names them (2026-11-14). */
    private val contacts: com.circaso.feature.contacts.domain.ContactsRepository? = null,
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
            kind == "call.missed" || kind == "call.ended" -> missedCall(chatId, data["callId"], data["callerName"], data["callKind"] == "VIDEO", data["userId"])
            kind == MomentReactionKind -> momentReaction(data)
            kind == "contact.joined" || kind == "connection.added" -> newPerson(kind, data)
            kind != null && kind.startsWith("space.") -> null // Spaces is archived (2026-11-05)
            chatId != null && data["messageId"] == null && kind == null -> addedToGroup(chatId)
            chatId != null && data["messageId"] != null -> message(chatId, data["messageId"]!!)
            else -> null
        }
    }

    /**
     * A message that just arrived over the socket and is already decrypted and saved (2026-11-13): worded exactly as
     * its push would be, under the same key, without pulling the inbox first. Null for a control message, a taken-back
     * reaction, or one of my own.
     */
    suspend fun forArrived(chatId: String, messageId: String): Notice? = message(chatId, messageId, sync = false)

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

    /**
     * Who [userId] is in [chatId], as the chat itself shows them: the name I saved for them first, else their own, and
     * their photo; for a direct chat the chat's own title and photo stand in. Null when not known on this phone (the
     * words then fall back, and no person is ever made up). Never with privacy on.
     */
    private suspend fun person(chatId: String, userId: String?): NoticePerson? {
        if (privacyOn()) return null
        val chat = withTimeoutOrNull(LookupMillis) { chatsRepo.chat(chatId).first() }
        // No sender named (the wallet's `payment.received`): in a direct chat it is the other person.
        val id = userId ?: chat?.takeIf { it.kind == com.circaso.feature.messaging.domain.ChatKind.Direct }?.peerId
        val member = id?.let { i -> chat?.members?.firstOrNull { it.userId == i } }
        if (member != null) return NoticePerson(member.userId, member.displayName, member.avatarUrl)
        // Not in this chat on this phone yet (a reply to my moment that opened a new chat): my contacts know them.
        val name = senderName(chatId, userId) ?: return personById(userId)
        val raw = userId?.let { id -> chats.members(chatId).firstOrNull { it.userId == id } }
        return NoticePerson(userId, name, raw?.avatarUrl ?: personById(userId)?.photo)
    }

    /**
     * Who [userId] is on this phone with no chat to go by (2026-11-14: a reaction on my moment, a contact who joined):
     * my contact first (the name I saved for them, else theirs), else a member of one of my chats; with my direct
     * chat with them, so the notification shares its conversation. Null when unknown here, and with privacy on.
     */
    private suspend fun personById(userId: String?): NoticePerson? {
        userId ?: return null
        if (privacyOn()) return null
        val direct = withTimeoutOrNull(LookupMillis) { directChatWith(userId) }
        val contact = contacts?.let { repo -> withTimeoutOrNull(LookupMillis) { repo.contacts.first() } }?.firstOrNull { it.person.userId == userId }?.person
        if (contact != null) return NoticePerson(userId, contact.displayName, contact.avatarUrl, direct)
        val member = withTimeoutOrNull(LookupMillis) { chats.observeAllMembers().first() }?.filter { it.userId == userId }?.let { rows ->
            rows.firstOrNull { it.chatId == direct } ?: rows.firstOrNull { it.avatarUrl != null } ?: rows.firstOrNull()
        } ?: return null
        return NoticePerson(userId, names.of(userId, member.displayName), member.avatarUrl, direct)
    }

    /** My direct chat with [userId], when I have one I haven't left. */
    private suspend fun directChatWith(userId: String): String? {
        val mine = chats.observeAllMembers().first().filter { it.userId == userId }.map { it.chatId }.toSet()
        if (mine.isEmpty()) return null
        return chats.chats().firstOrNull { it.chatId in mine && it.kind != "GROUP" && !it.left }?.chatId
    }

    /**
     * `moment.loved` (2026-11-14: data-only, with `userId`, `momentId` and `reaction`): "Amina reacted ❤️ to your
     * moment" beside Amina's photo. One notification per moment gathers everyone's reactions, one line each; the tap
     * opens the moment, where its viewers and their reactions are. An older server sent only its own words.
     */
    private suspend fun momentReaction(data: Map<String, String>): Notice {
        val userId = data["userId"]?.takeIf { it.isNotBlank() }
        val momentId = data["momentId"]?.takeIf { it.isNotBlank() }
        val emoji = data["reaction"]?.let(NotificationText::emojiOrNull)
        val found = personById(userId)
        val name = found?.name ?: userId?.let { names.saved.value[it] } ?: nameBefore(data["body"], " reacted ")
        // Someone this phone doesn't know still joins the moment's thread (initials, the server's name or "Someone"),
        // so they never replace the lines already gathered there.
        val who = found ?: if (privacyOn()) null else NoticePerson(userId, name ?: "Someone", null)
        val event = NotificationEvent.MomentReaction(name, emoji)
        return Notice(
            compose(event), NoticeChannel.Message, "moment:${momentId ?: "mine"}", NotificationDestination.Moment(null, momentId),
            from = who, fromLine = NotificationText.personLine(event), thread = MomentThread,
        )
    }

    /** `contact.joined` and `connection.added` (data-only since 2026-11-14): the person as I know them, with their photo. */
    private suspend fun newPerson(kind: String, data: Map<String, String>): Notice {
        val userId = data["userId"]?.takeIf { it.isNotBlank() }
        val joined = kind == "contact.joined"
        // A contact who just joined may not be in my list yet: fetch it once, briefly.
        if (joined && userId != null && !privacyOn() && contacts?.let { r -> withTimeoutOrNull(LookupMillis) { r.contacts.first() } }?.none { it.person.userId == userId } == true) {
            withTimeoutOrNull(ContactsLoadMillis) { contacts.refreshContacts() }
        }
        val who = personById(userId)
        val serverName = nameBefore(data["body"], if (joined) " is on Circaso" else " connected with you")
        val name = who?.name ?: userId?.let { names.saved.value[it] } ?: serverName
        val event = if (joined) NotificationEvent.ContactJoined(name) else NotificationEvent.ConnectionAdded(name)
        return Notice(
            compose(event), NoticeChannel.Message, "person:$kind:${userId ?: data["body"].hashCode()}", NotificationDestination.Contacts,
            from = who, fromLine = NotificationText.personLine(event),
        )
    }

    /** The name at the start of the server's own words ("Amina reacted 😂 to your moment"), if they start with one. */
    private fun nameBefore(body: String?, marker: String): String? =
        body?.substringBefore(marker, "")?.takeIf { it.isNotBlank() && it != "Someone" && it != "Someone you know" }

    /**
     * A group's name as the chat list shows it: its own name, or its members' names while this phone can't read it
     * ("Wanjiru, Otieno"), never a bare "Group".
     */
    private suspend fun groupTitle(chatId: String, raw: String?): String? =
        raw ?: withTimeoutOrNull(LookupMillis) { chatsRepo.chat(chatId).first() }?.takeIf { it.kind == com.circaso.feature.messaging.domain.ChatKind.Group }?.title

    /** The group's photo (this phone's decrypted copy) for a group chat's notification. */
    private suspend fun groupPhoto(chatId: String): String? =
        withTimeoutOrNull(LookupMillis) { chatsRepo.chat(chatId).first() }?.takeIf { it.kind == com.circaso.feature.messaging.domain.ChatKind.Group }?.avatarUrl

    private suspend fun conversation(chatId: String, group: Boolean, title: String?, sender: NoticePerson?, line: String, at: Long?, messageId: String?): NoticeConversation? {
        sender ?: return null
        return NoticeConversation(
            chatId = chatId, group = group, title = if (group) title ?: "Group" else null, sender = sender,
            groupPhoto = if (group) groupPhoto(chatId) else null, line = line,
            at = at ?: com.circaso.core.sync.nowMillis(), messageId = messageId,
        )
    }

    private suspend fun message(chatId: String, messageId: String, sync: Boolean = true): Notice? {
        if (sync) messages.syncInbox()
        val chat = chats.chat(chatId)
        val group = chat?.kind == "GROUP"
        val chatName = if (group) groupTitle(chatId, chat?.name) else chat?.name
        // A reaction isn't a message: name it as WhatsApp does, or say nothing when one was taken back.
        inbox.reactionCarriedBy(messageId)?.let { r ->
            val target = messagesDao.byMessageId(r.targetMessageId)?.let { MessagePreview.of(it.kind, it.text, it.status) }
            val who = person(chatId, r.senderId)
            val name = who?.name ?: senderName(chatId, r.senderId)
            val content = compose(NotificationEvent.Reaction(chatName, group, name, r.emoji, target))
            val line = NotificationText.compose(NotificationEvent.Reaction(null, false, name, r.emoji, target), false, null).text
            val conversation = conversation(chatId, group, chatName, who, line, null, messageId)
            return Notice(content, NoticeChannel.Message, "chat:$chatId", NotificationDestination.Chat(chatId, r.targetMessageId), chatId, conversation)
        }
        val row = messagesDao.byMessageId(messageId) ?: return null // a control message: nothing to show
        if (row.outgoing) return null
        val who = person(chatId, row.senderId)
        val sender = who?.name ?: senderName(chatId, row.senderId)
        if (row.kind == MessageKind.Payment && row.transferId != null) return paymentCard(chatId, chatName, group, sender, row, who)
        // Media reads as what it is ("📷 Photo"), never as the row's JSON.
        val event = NotificationEvent.ChatMessage(chatName, group, sender, MessagePreview.of(row.kind, row.text, row.status), momentReply = row.momentQuote != null)
        val conversation = conversation(chatId, group, chatName, who, NotificationText.chatLine(event), row.orderAt, messageId)
        return Notice(compose(event), NoticeChannel.Message, "chat:$chatId", NotificationDestination.Chat(chatId, messageId, row.localId), chatId, conversation)
    }

    /** A money card in a chat: money, on the money channel, worded in the person's currency, with the sender's photo. */
    private suspend fun paymentCard(chatId: String, chatName: String?, group: Boolean, sender: String?, row: MessageEntity, from: NoticePerson? = null): Notice {
        val money = Money(row.amountMinor ?: "0", row.asset ?: "USDC")
        val note = row.text
        val event = when {
            !group -> NotificationEvent.MoneyReceived(sender, money, note, forMoment = row.momentQuote != null)
            // Group wallets (GW18): the card's note says the money left the group's wallet; otherwise it went in.
            note?.startsWith("Paid from") == true -> NotificationEvent.MoneyReceived(sender, money, null, groupName = chatName ?: "Your group")
            else -> NotificationEvent.GroupContribution(chatName, sender, money)
        }
        val destination = NotificationDestination.Payment(chatId, row.transferId)
        val content = compose(event)
        val person = from ?: person(chatId, row.senderId)
        return Notice(
            content, NoticeChannel.Money, "money:${row.transferId}", destination, chatId,
            from = person, groupPhoto = if (group && person != null) groupPhoto(chatId) else null, group = group,
            fromLine = person?.let { moneyLine(content.text, group, sender) },
        )
    }

    /** "Wanjiru added KES 1,000" beside Wanjiru's name and photo reads "Added KES 1,000"; a direct payment as it is. */
    private fun moneyLine(text: String, group: Boolean, name: String?): String =
        if (group && name != null && text.startsWith("$name ")) text.removePrefix("$name ").replaceFirstChar { it.uppercase() } else text

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
            val from = person(chatId, card.senderId)
            val group = chat?.kind == "GROUP"
            return paymentCard(chatId, if (group) groupTitle(chatId, chat?.name) else chat?.name, group, from?.name ?: senderName(chatId, card.senderId), card, from)
        }
        // No card (money from outside Circaso, or the card is still on its way): the wallet's own record of it.
        if (key != null) withTimeoutOrNull(3_000) { wallet.refresh() }
        val transfer = key?.let { withTimeoutOrNull(1_000) { wallet.transfer(it).first() } }
        val from = transfer?.counterpartName ?: data["fromName"]?.takeIf { it.isNotBlank() } ?: nameFromBody(data["body"])
        val money = transfer?.amount?.let { Money(it.amountMinor, it.asset) }
        val content = compose(NotificationEvent.MoneyReceived(from, money, transfer?.note))
        // A direct chat's money: the other person's photo beside it.
        val person = chatId?.let { id -> chats.chat(id)?.takeIf { it.kind != "GROUP" }?.let { person(id, null) } }
        // With no name from the wallet's record, the chat's other person names it ("Amina · Sent you money").
        val named = if (from == null && person != null) compose(NotificationEvent.MoneyReceived(person.name, money, transfer?.note)) else content
        return Notice(named, NoticeChannel.Money, "money:${key ?: data["body"].hashCode()}", destination, chatId, from = person, fromLine = person?.let { named.text })
    }

    /** "Amina sent you money" (an older server's words): the name, if that's what it says. */
    private fun nameFromBody(body: String?): String? = body?.takeIf { it.endsWith(" sent you money") }?.removeSuffix(" sent you money")

    /** `chama.contribution`: the member's card in the group chat carries the amount; it shows as the same notice. */
    private suspend fun contribution(chatId: String, userId: String?): Notice {
        messages.syncInbox()
        val chatName = groupTitle(chatId, chats.chat(chatId)?.name)
        val who = person(chatId, userId)
        val sender = who?.name ?: senderName(chatId, userId)
        val card = userId?.let { latestCardFrom(chatId, it) }
        val event = NotificationEvent.GroupContribution(chatName, sender, card?.let { Money(it.amountMinor ?: "0", it.asset ?: "USDC") })
        val key = card?.transferId?.let { "money:$it" } ?: "group:$chatId:${userId.orEmpty()}"
        val content = compose(event)
        return Notice(
            content, NoticeChannel.Money, key, NotificationDestination.Payment(chatId, card?.transferId), chatId,
            from = who, groupPhoto = who?.let { groupPhoto(chatId) }, group = true, fromLine = who?.let { moneyLine(content.text, true, sender) },
        )
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

    private suspend fun missedCall(chatId: String?, callId: String?, callerName: String?, video: Boolean, callerId: String? = null): Notice {
        val chat = chatId?.let { chats.chat(it) }
        val group = chat?.kind == "GROUP"
        // A direct call: the name I saved for them first, over the server's.
        val saved = chat?.takeIf { !group }?.let { c -> chats.members(c.chatId).firstNotNullOfOrNull { names.saved.value[it.userId] } }
        // Who called, with their photo (2026-11-14): the caller's id from the push, else a direct chat's other person.
        val who = (if (chatId != null) person(chatId, callerId) else personById(callerId))?.let { if (!group && chatId != null) it.copy(directChatId = chatId) else it }
        val caller = saved ?: who?.name ?: callerName ?: chat?.takeIf { !group }?.name ?: chat?.name
        val event = NotificationEvent.MissedCall(caller, video)
        val content = compose(event)
        return Notice(
            content, NoticeChannel.Message, "call:${callId ?: chatId}", NotificationDestination.MissedCall(chatId, callId), chatId,
            from = who, fromLine = who?.let { NotificationText.personLine(event) },
            // A group call: titled by the group, with its photo, on the group's conversation.
            groupPhoto = if (group && who != null && chatId != null) groupPhoto(chatId) else null, group = group,
            thread = if (group && chatId != null) groupTitle(chatId, chat?.name) ?: "Group" else null,
        )
    }

    /** A ringing call's words, with privacy: "Incoming call" and nothing more. */
    suspend fun incomingCall(callerName: String?, video: Boolean): NotificationContent =
        NotificationText.compose(NotificationEvent.IncomingCall(callerName, video), privacyOn(), null)

    private companion object {
        /** How far back a contribution's card is looked for in its group chat. */
        const val RecentCards = 30
        /** A local lookup (the chat's names and photos); never worth holding the notification for longer. */
        const val LookupMillis = 1_000L
        /** The invite push's `kind` (backend PR #83). */
        const val InviteKind = "chat.invite"
        /** How long an invite push waits for the invites to load before it words itself without the group's name. */
        const val InviteLoadMillis = 4_000L
        /** How long a contact who just joined waits for my contacts to load, for their photo. */
        const val ContactsLoadMillis = 3_000L
        /** The push kind for a reaction on my moment (named for the first reaction, a heart). */
        const val MomentReactionKind = "moment.loved"
        /** A moment's notification once two or more people reacted to it. */
        const val MomentThread = "Your moment"
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
