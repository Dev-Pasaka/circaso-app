package com.circaso.feature.spaces.data.repository

import com.circaso.feature.spaces.data.remote.JoinRequestDto
import com.circaso.feature.spaces.data.remote.KnownDto
import com.circaso.feature.spaces.data.remote.MeDto
import com.circaso.feature.spaces.data.remote.MemberDto
import com.circaso.feature.spaces.data.remote.MembershipPaymentDto
import com.circaso.feature.spaces.data.remote.MySpaceDto
import com.circaso.feature.spaces.data.remote.PersonDto
import com.circaso.feature.spaces.data.remote.PostDto
import com.circaso.feature.spaces.data.remote.PriceDto
import com.circaso.feature.spaces.data.remote.ReceivedInviteDto
import com.circaso.feature.spaces.data.remote.ReportGroupDto
import com.circaso.feature.spaces.data.remote.SentInviteDto
import com.circaso.feature.spaces.data.remote.SpaceDto
import com.circaso.feature.spaces.data.remote.SpaceSummaryDto
import com.circaso.feature.spaces.data.remote.SuggestionDto
import com.circaso.feature.spaces.domain.InviteState
import com.circaso.feature.spaces.domain.JoinRequest
import com.circaso.feature.spaces.domain.JoinRequestState
import com.circaso.feature.spaces.domain.Known
import com.circaso.feature.spaces.domain.LastPost
import com.circaso.feature.spaces.domain.Membership
import com.circaso.feature.spaces.domain.MembershipPayment
import com.circaso.feature.spaces.domain.MembershipPaymentKind
import com.circaso.feature.spaces.domain.MembershipState
import com.circaso.feature.spaces.domain.MyInvite
import com.circaso.feature.spaces.domain.MySpace
import com.circaso.feature.spaces.domain.NotifyLevel
import com.circaso.feature.spaces.domain.PostAuthor
import com.circaso.feature.spaces.domain.PostMedia
import com.circaso.feature.spaces.domain.PostSendState
import com.circaso.feature.spaces.domain.PostingMode
import com.circaso.feature.spaces.domain.PricePeriod
import com.circaso.feature.spaces.domain.PrivateMessages
import com.circaso.feature.spaces.domain.ReactionCount
import com.circaso.feature.spaces.domain.ReceivedInvite
import com.circaso.feature.spaces.domain.Relation
import com.circaso.feature.spaces.domain.RemovedBy
import com.circaso.feature.spaces.domain.ReplyQuote
import com.circaso.feature.spaces.domain.ReportAction
import com.circaso.feature.spaces.domain.ReportGroup
import com.circaso.feature.spaces.domain.ReportReason
import com.circaso.feature.spaces.domain.ReportResolution
import com.circaso.feature.spaces.domain.SentInvite
import com.circaso.feature.spaces.domain.Space
import com.circaso.feature.spaces.domain.SpaceMe
import com.circaso.feature.spaces.domain.SpaceMember
import com.circaso.feature.spaces.domain.SpacePerson
import com.circaso.feature.spaces.domain.SpacePost
import com.circaso.feature.spaces.domain.SpacePrice
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.domain.SpaceState
import com.circaso.feature.spaces.domain.SpaceSummary
import com.circaso.feature.spaces.domain.SpaceTopic
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.domain.SpaceWalletRef
import com.circaso.feature.spaces.domain.SpaceWalletStatus
import com.circaso.feature.spaces.domain.Suggestion
import com.circaso.feature.spaces.domain.SuggestionReason
import kotlin.time.Instant

internal fun iso(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
internal fun isoOr(s: String?, fallback: Long): Long = iso(s) ?: fallback

internal fun PersonDto.toDomain() = SpacePerson(userId, displayName, handle, avatarUrl)
internal fun KnownDto.toDomain() = Known(names, count)
internal fun PriceDto.toDomain() = SpacePrice(amountMinor, PricePeriod.of(period))

internal fun SpaceSummaryDto.toDomain() = SpaceSummary(
    spaceId = spaceId, handle = handle.trimStart('@').lowercase(), name = name, about = about, topic = SpaceTopic.of(topic),
    photoUrl = photoUrl, coverUrl = coverUrl, visibility = SpaceVisibility.of(visibility), price = price?.toDomain(),
    memberCount = memberCount, state = SpaceState.of(state),
)

internal fun SpaceDto.summary() = SpaceSummaryDto(spaceId, handle, name, about, topic, photoUrl, coverUrl, visibility, price, memberCount, state)

internal fun MeDto.toDomain() = SpaceMe(
    relation = Relation.of(relation),
    role = SpaceRole.of(role),
    joinedAt = iso(joinedAt),
    invitedBy = invitedBy?.toDomain(),
    membership = membership?.let { m ->
        Membership(
            state = MembershipState.of(m.state), period = PricePeriod.of(m.period), paidUntil = iso(m.paidUntil), graceUntil = iso(m.graceUntil),
            renewFrom = iso(m.renewFrom), complimentary = m.complimentary, nextPriceMinor = m.nextPrice?.amountMinor,
        )
    },
    mutedUntil = iso(mutedUntil),
    notifications = NotifyLevel.of(notifications),
    request = request?.let { JoinRequestState(it.state == "DECLINED", isoOr(it.at, 0), iso(it.canAskAgainAt)) },
    invite = invite?.let { MyInvite(it.inviteId, it.invitedBy.toDomain(), isoOr(it.invitedAt, 0), isoOr(it.expiresAt, 0)) },
    unreadCount = unreadCount,
    lastReadPostId = lastReadPostId,
)

internal fun SpaceDto.toDomain(asOf: Long) = Space(
    summary = summary().toDomain(),
    owner = owner?.toDomain(),
    postingMode = PostingMode.of(postingMode),
    privateMessages = PrivateMessages.of(privateMessages),
    version = version,
    known = known.toDomain(),
    wallet = wallet?.let {
        SpaceWalletRef(it.walletId, when (it.status) { "ACTIVE" -> SpaceWalletStatus.Active; "SETTING_UP" -> SpaceWalletStatus.SettingUp; else -> SpaceWalletStatus.None }, it.canSign)
    },
    me = me.toDomain(),
    asOf = asOf,
)

internal fun MySpaceDto.toDomain(now: Long) = MySpace(
    space = space.toDomain(), me = me.toDomain(),
    lastPost = lastPost?.let { LastPost(it.postId, it.authorName, it.text, it.kind, isoOr(it.at, now), it.removed) },
    unreadCount = unreadCount.coerceAtMost(99), activityAt = iso(activityAt) ?: lastPost?.let { iso(it.at) } ?: 0,
)

internal fun SuggestionDto.toDomain() = Suggestion(
    space = SpaceSummaryDto(spaceId, handle, name, about, topic, photoUrl, coverUrl, visibility, price, memberCount, state).toDomain(),
    reason = when (reason) { "CONTACTS" -> SuggestionReason.Contacts; "POPULAR_IN_COUNTRY" -> SuggestionReason.PopularInCountry; else -> SuggestionReason.Popular },
    known = known.toDomain(),
)

internal fun ReceivedInviteDto.toDomain() = ReceivedInvite(inviteId, space.toDomain(), invitedBy.toDomain(), isoOr(invitedAt, 0), isoOr(expiresAt, 0), known.toDomain())
internal fun JoinRequestDto.toDomain() = JoinRequest(user.toDomain(), isoOr(requestedAt, 0))
internal fun SentInviteDto.toDomain() = SentInvite(
    inviteId, user.toDomain(), invitedBy?.toDomain(),
    when (state) { "JOINED" -> InviteState.Joined; "EXPIRED" -> InviteState.Expired; else -> InviteState.Invited },
    isoOr(invitedAt, 0), iso(expiresAt), iso(joinedAt),
)
internal fun MemberDto.toDomain() = SpaceMember(SpacePerson(userId, displayName, handle, avatarUrl), SpaceRole.of(role), iso(joinedAt), iso(mutedUntil), iso(bannedAt))

internal fun MembershipPaymentDto.toDomain() = MembershipPayment(
    paymentId = paymentId, spaceId = spaceId,
    kind = when (kind) { "RENEWAL" -> MembershipPaymentKind.Renewal; "REJOIN" -> MembershipPaymentKind.Rejoin; else -> MembershipPaymentKind.Join },
    amountMinor = amount.amountMinor, period = PricePeriod.of(period), periodStart = iso(periodStart), periodEnd = iso(periodEnd),
    previousAmountMinor = previousAmount?.amountMinor, walletId = walletId, blockchains = blockchains, expiresAt = isoOr(expiresAt, 0),
)

/** A post as the screens read it. [state]/[error]/[pendingEmoji] are the phone's own (a queued post or reaction). */
internal fun PostDto.toDomain(
    localKey: String, mine: Boolean, state: String = "sent", error: String? = null, pendingEmoji: String? = null,
): SpacePost {
    // A reaction waiting to send shows as if it had gone (counts adjusted), with its own clock (SH1d).
    val shownMine = if (pendingEmoji != null) pendingEmoji.ifEmpty { null } else myReaction
    val counts = if (pendingEmoji == null) reactions.map { ReactionCount(it.emoji, it.count) } else adjust(reactions.map { ReactionCount(it.emoji, it.count) }, myReaction, shownMine)
    return SpacePost(
        localKey = localKey, postId = postId.takeIf { it.isNotEmpty() }, spaceId = spaceId,
        author = PostAuthor(author.userId, author.displayName, author.handle, author.avatarUrl, SpaceRole.of(author.role)),
        text = removed?.let { null } ?: text,
        media = if (removed != null) emptyList() else media.map { PostMedia(it.mediaId, it.kind, it.contentType, it.width, it.height, it.durationMs, it.url) },
        replyTo = replyTo?.let { ReplyQuote(it.postId, it.authorName, it.text, it.removed) },
        reactions = counts, myReaction = shownMine, createdAt = isoOr(createdAt, 0),
        removed = removed?.let { if (it.by == "AUTHOR") RemovedBy.Author else RemovedBy.Moderator },
        mine = mine,
        sendState = when (state) { "waiting" -> PostSendState.Waiting; "failed" -> PostSendState.Failed; else -> PostSendState.Sent },
        error = error, reactionWaiting = pendingEmoji != null,
    )
}

/** Counts with my reaction moved from [from] to [to], most first (as the server orders them). */
internal fun adjust(counts: List<ReactionCount>, from: String?, to: String?): List<ReactionCount> {
    if (from == to) return counts
    val m = counts.associate { it.emoji to it.count }.toMutableMap()
    from?.let { m[it] = (m[it] ?: 1) - 1 }
    to?.let { m[it] = (m[it] ?: 0) + 1 }
    return m.filterValues { it > 0 }.map { (e, n) -> ReactionCount(e, n) }.sortedByDescending { it.count }
}

internal fun ReportGroupDto.toDomain(ownUserId: String?) = ReportGroup(
    post = post.toDomain(post.postId, mine = post.author.userId == ownUserId),
    reason = ReportReason.of(reason), count = count, firstAt = iso(firstAt), lastAt = iso(lastAt), open = state != "DONE",
    resolution = resolution?.let { r -> ReportResolution(ReportAction.entries.firstOrNull { it.wire == r.action } ?: ReportAction.Keep, r.by?.toDomain(), iso(r.at)) },
)
