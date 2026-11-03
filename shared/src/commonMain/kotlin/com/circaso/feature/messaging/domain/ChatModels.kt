package com.circaso.feature.messaging.domain

enum class ChatKind { Direct, Group }

enum class MemberRole { Owner, Admin, Member }

data class ChatMember(val userId: String, val displayName: String, val handle: String?, val avatarUrl: String?, val role: MemberRole, val joinedAt: String)

/** A chat as the screens see it. For a direct chat [title] is the other person's name; for a group the opened name. */
data class Chat(
    val chatId: String,
    val kind: ChatKind,
    val title: String,
    val avatarUrl: String?,
    val members: List<ChatMember>,
    val membersCanAdd: Boolean,
    val version: Int,
    val metaVersion: Int,
    val myRole: MemberRole,
    /** The other person in a direct chat; null for a group. */
    val peerId: String?,
)

/** One row of the chat list (design C1): the chat, its newest message and how many are unread. */
data class ChatSummary(val chat: Chat, val latest: Message?, val unread: Int)

enum class DeliveryState { Waiting, Sending, Sent, Delivered, Read, Failed }

sealed interface Message {
    val localId: Long
    val chatId: String
    val orderAt: Long
    val sentAt: String?

    data class Text(
        override val localId: Long,
        override val chatId: String,
        override val orderAt: Long,
        override val sentAt: String?,
        val messageId: String?,
        val senderId: String,
        val senderName: String,
        val outgoing: Boolean,
        val text: String,
        val state: DeliveryState,
        val errorCode: String?,
        val replyTo: String?,
        /** A reply to a moment (V8), quoted. */
        val momentQuote: com.circaso.core.crypto.MomentQuote? = null,
    ) : Message

    /** A payment card (designs C3–C7): money sent from this chat. The state comes from the wallet's copy of the transfer. */
    data class Payment(
        override val localId: Long,
        override val chatId: String,
        override val orderAt: Long,
        override val sentAt: String?,
        val messageId: String?,
        val senderId: String,
        val senderName: String,
        val outgoing: Boolean,
        val transferId: String,
        val amountMinor: String,
        val asset: String,
        val blockchain: String,
        val note: String?,
        val state: DeliveryState,
        val errorCode: String?,
    ) : Message

    /** A message the sender deleted for everyone. */
    data class Deleted(override val localId: Long, override val chatId: String, override val orderAt: Long, override val sentAt: String?, val outgoing: Boolean) : Message

    /** A message this phone could not open (it was meant for another phone, or its keys are gone). */
    data class Unreadable(override val localId: Long, override val chatId: String, override val orderAt: Long, override val sentAt: String?, val senderName: String) : Message

    /** A notice the phone wrote itself, such as a changed security code. */
    data class System(override val localId: Long, override val chatId: String, override val orderAt: Long, override val sentAt: String?, val text: String) : Message

    /**
     * A photo, voice note or shared location (WhatsApp-style). [details] says which and holds what's needed to show
     * it; a photo or voice note's bytes come from MediaMessages.bytes (this phone's copy, or downloaded and decrypted).
     */
    data class Media(
        override val localId: Long,
        override val chatId: String,
        override val orderAt: Long,
        override val sentAt: String?,
        val messageId: String?,
        val senderId: String,
        val senderName: String,
        val outgoing: Boolean,
        val details: com.circaso.feature.messaging.data.repository.MediaDetails,
        val state: DeliveryState,
        val errorCode: String?,
    ) : Message {
        val isImage get() = details.kind == com.circaso.feature.messaging.data.repository.MediaDetails.Image
        val isVoice get() = details.kind == com.circaso.feature.messaging.data.repository.MediaDetails.Voice
        val isVideo get() = details.kind == com.circaso.feature.messaging.data.repository.MediaDetails.Video
        val isLocation get() = details.kind == com.circaso.feature.messaging.data.repository.MediaDetails.Location
    }

    /**
     * A call in this chat (WhatsApp-style call history): who started it ([outgoing] = me), voice or [video], how it
     * ended and how long it lasted. Tapping it calls back.
     */
    data class Call(
        override val localId: Long,
        override val chatId: String,
        override val orderAt: Long,
        override val sentAt: String?,
        val outgoing: Boolean,
        val video: Boolean,
        val outcome: CallOutcome,
        val durationSeconds: Long,
        val callerName: String,
    ) : Message
}

/** The 60-digit safety number two people compare in person. */
data class SecurityCode(val digits: String) {
    /** Twelve groups of five, as Signal shows it. */
    val groups: List<String> get() = digits.chunked(5)
}

class GroupDraft(val name: String, val memberIds: List<String>)

/** How a call ended, as the chat shows it. */
enum class CallOutcome { Completed, Missed, Declined, Cancelled, NoAnswer, Failed }

/** Who could not be added to a group; the server gives no reason and the app shows none (guide, M5). */
class GroupCreated(val chat: Chat, val notAdded: List<String>)

/** What a payment card carries into the chat; the wallet already knows the transfer. */
data class PaymentCard(val transferId: String, val amountMinor: String, val asset: String, val blockchain: String, val note: String?)
