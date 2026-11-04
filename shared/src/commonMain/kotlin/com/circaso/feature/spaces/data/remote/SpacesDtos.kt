package com.circaso.feature.spaces.data.remote

import kotlinx.serialization.Serializable

// Spaces (backend `spaces` module), SP1–SP37: references/spaces-api.md until the guide has its Spaces section.
// Names follow the contract; every field the server may leave out has a default, so an older or newer answer decodes.

@Serializable data class MoneyDto(val asset: String = "USDC", val amountMinor: String, val amount: String = "")
@Serializable data class PriceDto(val asset: String = "USDC", val amountMinor: String, val amount: String = "", val period: String)
@Serializable data class PriceRequest(val asset: String = "USDC", val amountMinor: String, val period: String)

@Serializable data class PersonDto(val userId: String, val displayName: String, val handle: String? = null, val avatarUrl: String? = null)
@Serializable data class KnownDto(val names: List<String> = emptyList(), val count: Int = 0)

@Serializable data class SpaceSummaryDto(
    val spaceId: String,
    val handle: String,
    val name: String,
    val about: String? = null,
    val topic: String = "OTHER",
    val photoUrl: String? = null,
    val coverUrl: String? = null,
    val visibility: String,
    val price: PriceDto? = null,
    val memberCount: Int = 0,
    val state: String = "LIVE",
)

@Serializable data class SpaceWalletRefDto(val walletId: String? = null, val status: String = "NONE", val canSign: Boolean = false)

@Serializable data class MembershipDto(
    val state: String,
    val period: String? = null,
    val paidUntil: String? = null,
    val graceUntil: String? = null,
    val renewFrom: String? = null,
    val complimentary: Boolean = false,
    val nextPrice: MoneyDto? = null,
)
@Serializable data class JoinRequestStateDto(val state: String, val at: String, val canAskAgainAt: String? = null)
@Serializable data class MyInviteDto(val inviteId: String, val invitedBy: PersonDto, val invitedAt: String, val expiresAt: String)

@Serializable data class MeDto(
    val relation: String = "NONE",
    val role: String? = null,
    val joinedAt: String? = null,
    val invitedBy: PersonDto? = null,
    val membership: MembershipDto? = null,
    val mutedUntil: String? = null,
    val notifications: String = "ALL",
    val request: JoinRequestStateDto? = null,
    val invite: MyInviteDto? = null,
    val unreadCount: Int = 0,
    val lastReadPostId: String? = null,
)

@Serializable data class SpaceDto(
    val spaceId: String,
    val handle: String,
    val name: String,
    val about: String? = null,
    val topic: String = "OTHER",
    val photoUrl: String? = null,
    val coverUrl: String? = null,
    val visibility: String,
    val price: PriceDto? = null,
    val memberCount: Int = 0,
    val state: String = "LIVE",
    val owner: PersonDto? = null,
    val postingMode: String = "EVERYONE",
    val privateMessages: String = "ANYONE",
    val createdAt: String? = null,
    val version: Long = 0,
    val known: KnownDto = KnownDto(),
    val wallet: SpaceWalletRefDto? = null,
    val me: MeDto = MeDto(),
)

@Serializable data class LastPostDto(val postId: String, val authorName: String, val text: String? = null, val kind: String = "TEXT", val at: String, val removed: Boolean = false)
@Serializable data class MySpaceDto(val space: SpaceSummaryDto, val me: MeDto = MeDto(), val lastPost: LastPostDto? = null, val unreadCount: Int = 0, val activityAt: String? = null)
@Serializable data class MySpacesPage(val spaces: List<MySpaceDto> = emptyList(), val next: String? = null)

@Serializable data class SuggestionDto(
    val spaceId: String,
    val handle: String,
    val name: String,
    val about: String? = null,
    val topic: String = "OTHER",
    val photoUrl: String? = null,
    val coverUrl: String? = null,
    val visibility: String,
    val price: PriceDto? = null,
    val memberCount: Int = 0,
    val state: String = "LIVE",
    val reason: String = "POPULAR",
    val known: KnownDto = KnownDto(),
)
@Serializable data class SuggestionsPage(val spaces: List<SuggestionDto> = emptyList(), val next: String? = null)
@Serializable data class SearchPage(val exact: SpaceSummaryDto? = null, val spaces: List<SpaceSummaryDto> = emptyList(), val next: String? = null)

@Serializable data class HandleAvailabilityDto(val handle: String, val available: Boolean, val suggestions: List<String> = emptyList())

@Serializable data class TeamPickDto(val userId: String, val role: String)
@Serializable data class CreateSpaceRequest(
    val name: String,
    val handle: String,
    val about: String? = null,
    val topic: String,
    val photoMediaId: String? = null,
    val coverMediaId: String? = null,
    val visibility: String,
    val price: PriceRequest? = null,
    val postingMode: String = "EVERYONE",
    val privateMessages: String = "ANYONE",
    val team: List<TeamPickDto> = emptyList(),
)

/** SP5: every field left out stays. Nulls are not encoded, so "remove the photo" is not expressible here (not designed). */
@Serializable data class UpdateSpaceRequest(
    val name: String? = null,
    val about: String? = null,
    val topic: String? = null,
    val photoMediaId: String? = null,
    val coverMediaId: String? = null,
    val postingMode: String? = null,
    val handle: String? = null,
    val visibility: String? = null,
    val price: PriceRequest? = null,
    val privateMessages: String? = null,
)
@Serializable data class UpdatedSpaceDto(val space: SpaceDto, val notified: Int = 0)
@Serializable data class DeletedDto(val deleted: Boolean = true)
@Serializable data class LeftDto(val left: Boolean = true)
@Serializable data class NotificationsRequest(val level: String)

@Serializable data class MembershipPaymentDto(
    val paymentId: String,
    val spaceId: String,
    val kind: String,
    val amount: MoneyDto,
    val period: String? = null,
    val periodStart: String? = null,
    val periodEnd: String? = null,
    val previousAmount: MoneyDto? = null,
    val walletId: String? = null,
    val blockchains: List<String> = emptyList(),
    val expiresAt: String,
)

@Serializable data class JoinRequestDto(val user: PersonDto, val requestedAt: String)
@Serializable data class SentInviteDto(val inviteId: String, val user: PersonDto, val invitedBy: PersonDto? = null, val state: String, val invitedAt: String, val expiresAt: String? = null, val joinedAt: String? = null)
@Serializable data class InvitesDto(val requests: List<JoinRequestDto> = emptyList(), val invites: List<SentInviteDto> = emptyList())
@Serializable data class RequestAnswerDto(val state: String)
@Serializable data class InviteRequest(val userIds: List<String>)
@Serializable data class InviteResultDto(val userId: String, val outcome: String)
@Serializable data class InviteResultsDto(val results: List<InviteResultDto> = emptyList())
@Serializable data class CancelledDto(val cancelled: Boolean = true)
@Serializable data class ReceivedInviteDto(val inviteId: String, val space: SpaceSummaryDto, val invitedBy: PersonDto, val invitedAt: String, val expiresAt: String, val known: KnownDto = KnownDto())
@Serializable data class ReceivedInvitesDto(val invites: List<ReceivedInviteDto> = emptyList())
@Serializable data class DeclinedDto(val declined: Boolean = true)

@Serializable data class MemberDto(
    val userId: String,
    val displayName: String,
    val handle: String? = null,
    val avatarUrl: String? = null,
    val role: String? = null,
    val joinedAt: String? = null,
    val mutedUntil: String? = null,
    val bannedAt: String? = null,
)
@Serializable data class MembersPage(val team: List<MemberDto> = emptyList(), val contacts: List<MemberDto> = emptyList(), val members: List<MemberDto> = emptyList(), val count: Int = 0, val next: String? = null)
@Serializable data class RoleRequest(val role: String)
@Serializable data class MuteRequest(val duration: String)
@Serializable data class RemovedDto(val removed: Boolean = true)
@Serializable data class BannedDto(val banned: Boolean = false)

@Serializable data class PostAuthorDto(val userId: String, val displayName: String, val handle: String? = null, val avatarUrl: String? = null, val role: String? = null)
@Serializable data class PostMediaDto(
    val mediaId: String, val kind: String, val contentType: String = "", val sizeBytes: Long = 0,
    val width: Int? = null, val height: Int? = null, val durationMs: Long? = null, val url: String? = null,
)
@Serializable data class ReplyToDto(val postId: String, val authorName: String, val text: String? = null, val removed: Boolean = false)
@Serializable data class ReactionCountDto(val emoji: String, val count: Int)
@Serializable data class RemovedMarkDto(val by: String, val at: String)
@Serializable data class PostDto(
    val postId: String,
    val spaceId: String,
    val clientPostId: String? = null,
    val author: PostAuthorDto,
    val text: String? = null,
    val media: List<PostMediaDto> = emptyList(),
    val replyTo: ReplyToDto? = null,
    val reactions: List<ReactionCountDto> = emptyList(),
    val myReaction: String? = null,
    val createdAt: String,
    val removed: RemovedMarkDto? = null,
)
@Serializable data class PostsPage(val posts: List<PostDto> = emptyList(), val next: String? = null)
@Serializable data class NewPostRequest(val clientPostId: String, val text: String? = null, val replyToPostId: String? = null, val mediaIds: List<String> = emptyList())
/** SP31. The emoji is always sent, as JSON null when taken back (the app's Json leaves nulls out, so this is built by hand). */
@Serializable data class ReactionAnswerDto(val postId: String, val reactions: List<ReactionCountDto> = emptyList(), val myReaction: String? = null)
@Serializable data class ReadRequest(val postId: String)
@Serializable data class UnreadDto(val unreadCount: Int = 0)

@Serializable data class SpaceMediaRequest(val purpose: String, val kind: String, val contentType: String, val sizeBytes: Long, val width: Int? = null, val height: Int? = null, val durationMs: Long? = null)
@Serializable data class SpaceMediaUploadDto(val mediaId: String, val uploadUrl: String, val uploadHeaders: Map<String, String> = emptyMap(), val expiresAt: String)
@Serializable data class SpaceMediaDoneDto(val mediaId: String, val sizeBytes: Long = 0)

@Serializable data class ReportRequest(val postId: String? = null, val reason: String, val detail: String? = null)
@Serializable data class ReportIdDto(val reportId: String)
@Serializable data class ResolutionDto(val action: String, val by: PersonDto? = null, val at: String? = null)
@Serializable data class ReportGroupDto(
    val post: PostDto,
    val reason: String = "OTHER",
    val count: Int = 1,
    val firstAt: String? = null,
    val lastAt: String? = null,
    val state: String = "OPEN",
    val resolution: ResolutionDto? = null,
)
@Serializable data class ReportsPage(val reports: List<ReportGroupDto> = emptyList(), val next: String? = null)
@Serializable data class ResolveRequest(val action: String, val muteDuration: String? = null)
