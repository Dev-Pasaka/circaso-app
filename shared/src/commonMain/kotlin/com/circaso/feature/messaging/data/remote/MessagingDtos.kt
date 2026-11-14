package com.circaso.feature.messaging.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/*
 * Named exactly as the integration guide's schema appendix (section 11). Keys and ciphertext are standard base64.
 */

// ---- encryption keys (M1–M3)

@Serializable data class SignedPreKeyDto(val keyId: Int, val publicKey: String, val signature: String)
@Serializable data class OneTimePreKeyDto(val keyId: Int, val publicKey: String)

/**
 * The signed Kyber (ML-KEM 1024) prekey PQXDH needs next to the signed EC prekey; libsignal 0.106 refuses a bundle
 * without one. Added to the backend on 2026-10-28 (guide, "Changed on 2026-10-28, later"); its public key is ~1.5 KB.
 */
@Serializable data class KyberPreKeyDto(val keyId: Int, val publicKey: String, val signature: String)

@Serializable
data class PublishKeysRequest(
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val kyberPreKey: KyberPreKeyDto,
    val oneTimePreKeys: List<OneTimePreKeyDto>? = null,
)

@Serializable data class AddOneTimeKeysRequest(val oneTimePreKeys: List<OneTimePreKeyDto>)
@Serializable data class OneTimePreKeysLeftResponse(val oneTimePreKeysLeft: Int)
@Serializable data class KeyStatusResponse(val oneTimePreKeysLeft: Int, val signedPreKeyAt: String)

@Serializable
data class KeyBundleResponse(
    val userId: String,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val kyberPreKey: KyberPreKeyDto,
    val oneTimePreKey: OneTimePreKeyDto? = null,
)

// ---- chats and members (M4–M11)

@Serializable data class ChatProfileDto(val userId: String, val displayName: String, val handle: String? = null, val avatarUrl: String? = null, val about: String? = null)
@Serializable data class ChatMemberDto(val user: ChatProfileDto, val role: String, val joinedAt: String)
@Serializable data class ChatSettingsDto(val membersCanAdd: Boolean? = null)

@Serializable
data class ChatDto(
    val chatId: String,
    val kind: String,
    val sealedMeta: String? = null,
    val metaVersion: Int,
    val members: List<ChatMemberDto>,
    val settings: ChatSettingsDto = ChatSettingsDto(),
    val lastMessageId: String? = null,
    val version: Int,
    val created: Boolean? = null,
    val notAdded: List<String>? = null,
    /** Set by a server that had Spaces (archived 2026-11-05): such a chat is an ordinary chat on the phone now. */
    val viaSpaceId: String? = null,
    /**
     * The group's current photo as the server keeps it (backend PR #47): the attachment id the last photo change named,
     * kept past the usual 30 days. Null for none, for a direct chat, and from a server older than the field.
     */
    val avatarMediaId: String? = null,
    /**
     * M5 and M9 since group invites (backend PR #83, 2026-11-09): the people who now have an open invite. They join
     * when they accept, so they aren't in [members]. Null from an older server, which put them straight in.
     */
    val invited: List<String>? = null,
)

@Serializable data class ChatsResponse(val chats: List<ChatDto>)
@Serializable data class OpenDirectChatRequest(val userId: String)

/**
 * The group's metadata key sealed 1:1 for one person invited (a `metaKey` payload, as a `SENDER_KEY` copy is), so their
 * invite shows the group's name and photo before they decide. Base64 of the wire bytes.
 */
@Serializable data class InviteKeyDto(val userId: String, val sealedKey: String)

@Serializable data class CreateGroupRequest(val sealedMeta: String, val memberIds: List<String>? = null, val inviteKeys: List<InviteKeyDto>? = null)
/**
 * M8. [avatarMediaId] has three meanings, so it is a [JsonElement]: Kotlin null leaves the key out (keep the photo; the
 * client's `explicitNulls = false` drops it), [JsonNull] sends `null` (clear it), a string sets it. Build it with
 * [AvatarChange]. An older server ignores the key.
 */
@Serializable
data class UpdateGroupRequest(
    val sealedMeta: String? = null,
    val settings: ChatSettingsDto? = null,
    val version: Int,
    val avatarMediaId: JsonElement? = null,
)

/** The values [UpdateGroupRequest.avatarMediaId] takes. */
object AvatarChange {
    /** The field is left out: the server keeps the group's photo. */
    val Keep: JsonElement? = null
    /** `"avatarMediaId": null`: the group has no photo any more. */
    val Clear: JsonElement = JsonNull
    /** `"avatarMediaId": "<id>"`: the group's own image attachment, uploaded and confirmed (AT1, PUT, AT2). */
    fun set(attachmentId: String): JsonElement = JsonPrimitive(attachmentId)
}
@Serializable data class AddMembersRequest(val memberIds: List<String>, val inviteKeys: List<InviteKeyDto>? = null)
@Serializable data class MemberRemovedResponse(val removed: Boolean)
@Serializable data class SetRoleRequest(val role: String)

// ---- group invites (M23–M27, backend PR #83)

/**
 * One of my open invites (M23): the group's sealed name and photo as they now are, how many are in it and who invited
 * me; never its members or messages. [sealedKey] is the group's metadata key sealed for me by [invitedBy]'s phone.
 */
@Serializable
data class InviteDto(
    val inviteId: String,
    val chatId: String,
    val invitedBy: ChatProfileDto,
    val sealedMeta: String? = null,
    val metaVersion: Int = 0,
    val avatarMediaId: String? = null,
    val memberCount: Int = 0,
    val sealedKey: String? = null,
    val createdAt: String = "",
    val expiresAt: String = "",
)

@Serializable data class InvitesResponse(val invites: List<InviteDto>)

/** An open invite as group info lists it under "Invited" (M26). */
@Serializable
data class PendingInviteDto(val inviteId: String, val user: ChatProfileDto, val invitedBy: ChatProfileDto, val createdAt: String = "", val expiresAt: String = "")

@Serializable data class ChatInvitesResponse(val invites: List<PendingInviteDto>)
@Serializable data class InviteDeclinedResponse(val declined: Boolean = true)
@Serializable data class InviteCancelledResponse(val cancelled: Boolean = true)

// ---- messages and delivery (M12–M14, M22)

@Serializable data class RecipientDto(val userId: String, val ciphertext: String)

@Serializable
data class SendChatMessageRequest(
    val clientMessageId: String,
    val kind: String,
    val ciphertext: String? = null,
    val recipients: List<RecipientDto>? = null,
    val mediaIds: List<String>? = null,
    val pushable: Boolean = false,
    /**
     * A `SENDER_KEY` whose copies ask for the group's metadata key (2026-11-09): offline members get a silent
     * `chat.meta_key_requested` push so their phone answers. An older server ignores it.
     */
    val keyRequest: Boolean = false,
)

@Serializable data class SentMessageResponse(val messageId: String, val sentAt: String)

@Serializable
data class InboxMessageDto(
    val messageId: String,
    val chatId: String,
    val senderId: String,
    val kind: String,
    val ciphertext: String,
    val mediaIds: List<String> = emptyList(),
    val sentAt: String,
)

@Serializable data class InboxResponse(val messages: List<InboxMessageDto>, val next: String? = null)
@Serializable data class AckRequest(val messageIds: List<String>)
@Serializable data class AckResponse(val acked: Int)
@Serializable data class MessageDeletedResponse(val deleted: Boolean)
@Serializable data class MessagingSettingsRequest(val readReceipts: Boolean? = null, val typing: Boolean? = null)
@Serializable data class MessagingSettingsResponse(val readReceipts: Boolean, val typing: Boolean)

/** String constants the server uses; kept as strings so an unknown value never breaks decoding. */
object MessagingWire {
    const val Direct = "DIRECT"
    const val Group = "GROUP"
    const val Owner = "OWNER"
    const val Admin = "ADMIN"
    const val Member = "MEMBER"
    const val KindMessage = "MESSAGE"
    const val KindSenderKey = "SENDER_KEY"
    const val KindControl = "CONTROL"
    const val Delivered = "DELIVERED"
    const val Read = "READ"
}

// ---- group join links (M28–M32, 2026-11-14)

/** M29: the preview the admin's phone publishes for the link's page. [photo] is base64 of a small JPEG; null for none. */
@Serializable
data class PublishGroupLinkRequest(val name: String, val description: String? = null, val photo: String? = null, val reset: Boolean = false)

@Serializable
data class GroupLinkDto(
    val token: String,
    val url: String,
    val name: String = "",
    val description: String? = null,
    val photoUrl: String? = null,
    val createdBy: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/** M28: [link] null when the group has none. */
@Serializable
data class GroupLinkResponse(val link: GroupLinkDto? = null)

@Serializable
data class GroupLinkOffResponse(val off: Boolean = true)

/** M31: a join link before joining. [chatId] only when I am already in the group. */
@Serializable
data class GroupLinkPreviewResponse(
    val name: String = "",
    val description: String? = null,
    val photoUrl: String? = null,
    val memberCount: Int = 0,
    val member: Boolean = false,
    val chatId: String? = null,
)
