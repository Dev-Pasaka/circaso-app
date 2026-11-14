package com.circaso.feature.messaging.domain

enum class ChatKind { Direct, Group }

enum class MemberRole { Owner, Admin, Member }

/** A chat member; [displayName] is the name shown (the one I saved for them, else theirs), [profileName] their own. */
data class ChatMember(val userId: String, val displayName: String, val handle: String?, val avatarUrl: String?, val role: MemberRole, val joinedAt: String, val profileName: String = displayName) {
    val renamed: Boolean get() = profileName != displayName
}

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
    /** A private chat with a space's owner (SH2, "Private · via {space}"), separate from any ordinary chat with them. */
    val viaSpaceId: String? = null,
    /**
     * False for a group whose name this phone can't read (no key for it, a new phone): [title] is then made from the
     * members' names, never a name of its own, and [nameState] says whether the key is still on its way.
     */
    val nameKnown: Boolean = true,
    val nameState: GroupNameState = GroupNameState.Known,
    /**
     * Whose phone the key is expected from, when that is one person (the group's only other member): their first
     * name. Null means any member's.
     */
    val waitingFor: String? = null,
    /**
     * What the photo's initials are made from when it differs from [title]: a group shown by its members' names
     * ("You and Amina" would give "YA") uses their first names ("Amina", "Amina Brian").
     */
    val initialsFrom: String? = null,
) {
    val avatarLabel: String get() = initialsFrom ?: title
}

/**
 * A group's sealed name and photo on this phone (2026-11-09). A new phone has no key for them and asks the members:
 * [Waiting] until one answers; [Unavailable] once every member asked said they have no key either (an admin can then
 * name the group again, which gives it a new key).
 */
enum class GroupNameState { Known, Waiting, Unavailable }

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
        /** Money sent from a moment (2026-11-14): the moment it was sent for, shown attached to the card. */
        val momentQuote: com.circaso.core.crypto.MomentQuote? = null,
    ) : Message

    /** A message the sender deleted for everyone. */
    data class Deleted(override val localId: Long, override val chatId: String, override val orderAt: Long, override val sentAt: String?, val outgoing: Boolean) : Message

    /** A message this phone could not open (it was meant for another phone, or its keys are gone). */
    data class Unreadable(override val localId: Long, override val chatId: String, override val orderAt: Long, override val sentAt: String?, val senderName: String) : Message

    /**
     * A notice the phone wrote itself, such as a changed security code. [securityNotice]: it is about someone's security
     * code, so the thread shows it with a shield and (in a direct chat) it opens the code.
     */
    data class System(override val localId: Long, override val chatId: String, override val orderAt: Long, override val sentAt: String?, val text: String, val securityNotice: Boolean = false) : Message

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
        val isFile get() = details.kind == com.circaso.feature.messaging.data.repository.MediaDetails.File
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

/**
 * Which security codes this phone has checked (2026-11-13), Signal's model: kept with the code itself, so when the
 * other person's keys change (they sign in again) the new code is not verified until it's checked again. On this phone
 * only; nothing goes to the server.
 */
interface VerifiedCodes {
    /** The code last verified with [peerId], or null. */
    fun verifiedCode(peerId: String): kotlinx.coroutines.flow.Flow<String?>
    suspend fun setVerifiedCode(peerId: String, digits: String?)
}

/** How every "‹Name›'s security code changed" notice ends; the thread knows such a notice by it. */
const val SecurityCodeChangedSuffix = "'s security code changed"

class GroupDraft(val name: String, val memberIds: List<String>)

/** How a call ended, as the chat shows it. */
enum class CallOutcome { Completed, Missed, Declined, Cancelled, NoAnswer, Failed }

/**
 * Who could not be added to a group; the server gives no reason and the app shows none (guide, M5). [invited]: the
 * people who now have an invite and join when they accept (group invites, 2026-11-09).
 */
class GroupCreated(val chat: Chat, val notAdded: List<String>, val invited: List<String> = emptyList())

/** Someone, as an invite names them: who invited me, or who is invited. */
data class InvitePerson(val userId: String, val displayName: String, val avatarUrl: String?)

/**
 * An invite to a group waiting for my answer (group invites, 2026-11-09). [groupName] and [groupPhoto] open with the
 * key the inviter's phone sealed for me; null when it couldn't (an older app sent none), and the card says "A group".
 * Never the group's members or messages: I'm not in it yet.
 */
data class GroupInvite(
    val inviteId: String,
    val chatId: String,
    val groupName: String?,
    val groupPhoto: String?,
    val memberCount: Int,
    val invitedBy: InvitePerson,
    val createdAt: Long?,
    val expiresAt: Long?,
)

/** An open invite in group info's "Invited" list: who, and who invited them. */
data class PendingInvite(val inviteId: String, val person: InvitePerson, val invitedBy: InvitePerson)

/**
 * What a payment card carries into the chat; the wallet already knows the transfer. [moment] is the moment the money
 * was sent for (from the Moment viewer, 2026-11-14): only ever context on the card, never part of the transfer.
 */
data class PaymentCard(
    val transferId: String, val amountMinor: String, val asset: String, val blockchain: String, val note: String?,
    val moment: com.circaso.core.crypto.MomentQuote? = null,
)
