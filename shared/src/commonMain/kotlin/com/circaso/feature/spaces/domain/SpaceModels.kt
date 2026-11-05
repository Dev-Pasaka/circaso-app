package com.circaso.feature.spaces.domain

// Spaces core (phase 3): references/spaces-api.md. Pure Kotlin; times are epoch milliseconds, money is USDC minor units
// as decimal strings (6 decimals), never a float.

/** One topic list everywhere: create, Discover chips, search (design decision). */
enum class SpaceTopic(val label: String) {
    Business("Business"), Shopping("Shopping"), Farming("Farming"), Health("Health"), Faith("Faith"),
    Sport("Sport"), Books("Books"), Chamas("Chamas"), Music("Music"), Other("Other");

    val wire: String get() = name.uppercase()

    companion object {
        fun of(wire: String?): SpaceTopic = entries.firstOrNull { it.wire == wire?.uppercase() } ?: Other
    }
}

/** Who can join (SN2). */
enum class SpaceVisibility(val wire: String, val label: String) {
    Public("PUBLIC", "Public"), MembersOnly("MEMBERS_ONLY", "Members only"), InviteOnly("INVITE_ONLY", "Invite only");

    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: Public }
}

enum class PricePeriod(val wire: String, val label: String, val per: String) {
    OneTime("ONE_TIME", "One time", ""), Monthly("MONTHLY", "Monthly", "a month"), Yearly("YEARLY", "Yearly", "a year");

    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: OneTime }
}

/** A members-only space's price, set in USDC (0.10 to 10,000). */
data class SpacePrice(val amountMinor: String, val period: PricePeriod) {
    companion object {
        const val MinMinor = "100000"
        const val MaxMinor = "10000000000"
    }
}

enum class SpaceState(val wire: String) {
    SettingUp("SETTING_UP"), Live("LIVE"), Deleted("DELETED"), Removed("REMOVED");

    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: Live }
}

enum class PostingMode(val wire: String) {
    Everyone("EVERYONE"), AdminsOnly("ADMINS_ONLY");

    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: Everyone }
}

enum class PrivateMessages(val wire: String) {
    Anyone("ANYONE"), Members("MEMBERS");

    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: Anyone }
}

/** SR2: each role adds to the one below it. */
enum class SpaceRole(val wire: String, val label: String, val rank: Int) {
    Owner("OWNER", "Owner", 3), Admin("ADMIN", "Admin", 2), Moderator("MODERATOR", "Moderator", 1), Member("MEMBER", "Member", 0);

    val isAdmin get() = rank >= Admin.rank
    val isModerator get() = rank >= Moderator.rank

    companion object { fun of(wire: String?): SpaceRole? = entries.firstOrNull { it.wire == wire } }
}

/** Where I stand with a space (`me.relation`). */
enum class Relation(val wire: String) {
    None("NONE"), Member("MEMBER"), Ended("ENDED"), Requested("REQUESTED"), Invited("INVITED"), Banned("BANNED");

    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: None }
}

enum class MembershipState(val wire: String) {
    Active("ACTIVE"), Grace("GRACE"), Ended("ENDED");

    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: Active }
}

/** SS1c's three levels. */
enum class NotifyLevel(val wire: String, val label: String) {
    All("ALL", "All posts"), Mentions("MENTIONS", "Mentions and admins"), Off("OFF", "Off");

    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: All }
}

data class SpacePerson(val userId: String, val displayName: String, val handle: String? = null, val avatarUrl: String? = null) {
    val firstName: String get() = displayName.substringBefore(' ')
}

/** The people in my contacts who are in a space: up to 3 names, and how many. */
data class Known(val names: List<String> = emptyList(), val count: Int = 0) {
    /** "Juma, Faith and 1 other contact" (SS1), or null when nobody is known. */
    fun phrase(): String? {
        if (count <= 0 || names.isEmpty()) return null
        val others = count - names.size
        return when {
            others > 0 -> names.joinToString(", ") + " and $others other ${if (others == 1) "contact" else "contacts"}"
            names.size == 1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }
}

data class Membership(
    val state: MembershipState,
    val period: PricePeriod,
    val paidUntil: Long? = null,
    val graceUntil: Long? = null,
    /** When Renew opens: 3 days before [paidUntil] (7 for yearly). */
    val renewFrom: Long? = null,
    val complimentary: Boolean = false,
    /** What the next period costs when the owner changed the price (SM3b). */
    val nextPriceMinor: String? = null,
)

data class JoinRequestState(val declined: Boolean, val at: Long, val canAskAgainAt: Long? = null)
data class MyInvite(val inviteId: String, val invitedBy: SpacePerson, val invitedAt: Long, val expiresAt: Long)

data class SpaceMe(
    val relation: Relation = Relation.None,
    val role: SpaceRole? = null,
    val joinedAt: Long? = null,
    val invitedBy: SpacePerson? = null,
    val membership: Membership? = null,
    val mutedUntil: Long? = null,
    val notifications: NotifyLevel = NotifyLevel.All,
    val request: JoinRequestState? = null,
    val invite: MyInvite? = null,
    val unreadCount: Int = 0,
    val lastReadPostId: String? = null,
) {
    val isMember get() = relation == Relation.Member
    val isOwner get() = role == SpaceRole.Owner
    val isAdmin get() = role?.isAdmin == true
    val isModerator get() = role?.isModerator == true
    /** Muted until [at] (now given), "until unmuted" when far in the future. */
    fun mutedAt(now: Long): Boolean = mutedUntil?.let { it > now } == true
    fun renewOpen(now: Long): Boolean = membership?.let { m -> m.state == MembershipState.Grace || (m.renewFrom != null && m.renewFrom <= now && m.period != PricePeriod.OneTime) } == true

    companion object {
        /** `mutedUntil` from the year 9999 on means "until unmuted". */
        const val UntilUnmuted = 253_000_000_000_000L
    }
}

data class SpaceSummary(
    val spaceId: String,
    /** Without the `@`, lowercase. */
    val handle: String,
    val name: String,
    val about: String? = null,
    val topic: SpaceTopic = SpaceTopic.Other,
    val photoUrl: String? = null,
    val coverUrl: String? = null,
    val visibility: SpaceVisibility = SpaceVisibility.Public,
    val price: SpacePrice? = null,
    val memberCount: Int = 0,
    val state: SpaceState = SpaceState.Live,
) {
    /** The tile's letters when there is no photo ("Nairobi Sneaker Drop" → "ND"). */
    val initials: String get() = name.split(' ').filter { it.isNotBlank() }.let { w -> (w.firstOrNull()?.take(1).orEmpty() + (w.getOrNull(w.size - 1)?.takeIf { w.size > 1 }?.take(1).orEmpty())).uppercase() }
    val link: String get() = "circaso.app/s/$handle"
    val deepLink: String get() = "circaso://space/@$handle"
}

data class SpaceWalletRef(val walletId: String?, val status: SpaceWalletStatus, val canSign: Boolean)
enum class SpaceWalletStatus { None, SettingUp, Active }

/** SP3: a space as I may see it. [asOf] is when the phone read it (offline "as of"). */
data class Space(
    val summary: SpaceSummary,
    val owner: SpacePerson? = null,
    val postingMode: PostingMode = PostingMode.Everyone,
    val privateMessages: PrivateMessages = PrivateMessages.Anyone,
    val version: Long = 0,
    val known: Known = Known(),
    val wallet: SpaceWalletRef? = null,
    val me: SpaceMe = SpaceMe(),
    val asOf: Long = 0,
) {
    val spaceId get() = summary.spaceId
    val name get() = summary.name
    val handle get() = summary.handle
    val visibility get() = summary.visibility
    val state get() = summary.state
    /** I can post now (SH1, SH1b, SH1e). */
    fun canPost(now: Long): Boolean = me.isMember && summary.state == SpaceState.Live && !me.mutedAt(now) &&
        (postingMode == PostingMode.Everyone || me.isAdmin) && me.membership?.state != MembershipState.Ended
    /** Anyone who can see the space may message its owner, unless the owner limited it to members (invite-only always). */
    val canMessageOwner: Boolean get() = owner != null && !me.isOwner && me.relation != Relation.Banned && summary.state == SpaceState.Live &&
        (me.isMember || (privateMessages == PrivateMessages.Anyone && visibility != SpaceVisibility.InviteOnly))
}

data class LastPost(val postId: String, val authorName: String, val text: String?, val kind: String, val at: Long, val removed: Boolean)

/** SP7: one row of "Your spaces" (SD1). */
data class MySpace(val space: SpaceSummary, val me: SpaceMe, val lastPost: LastPost?, val unreadCount: Int, val activityAt: Long)

enum class SuggestionReason { Contacts, PopularInCountry, Popular }

data class Suggestion(val space: SpaceSummary, val reason: SuggestionReason, val known: Known)

data class SearchResult(val exact: SpaceSummary?, val spaces: List<SpaceSummary>, val next: String?)

data class Page<T>(val items: List<T>, val next: String?)

data class HandleCheck(val handle: String, val available: Boolean, val suggestions: List<String>)

data class TeamPick(val person: SpacePerson, val role: SpaceRole)

/** SN1–SN3: what the person chose. */
data class NewSpace(
    val name: String,
    val handle: String,
    val about: String?,
    val topic: SpaceTopic,
    val photo: ByteArray? = null,
    val visibility: SpaceVisibility,
    val price: SpacePrice? = null,
    val postingMode: PostingMode = PostingMode.Everyone,
    val privateMessages: PrivateMessages = PrivateMessages.Anyone,
    val team: List<TeamPick> = emptyList(),
)

/** SP5: each field null stays as it is. */
data class SpaceChange(
    val name: String? = null,
    val about: String? = null,
    val topic: SpaceTopic? = null,
    val photo: ByteArray? = null,
    val postingMode: PostingMode? = null,
    val handle: String? = null,
    val visibility: SpaceVisibility? = null,
    val price: SpacePrice? = null,
    val privateMessages: PrivateMessages? = null,
    /** Take the photo or cover away (SP5 `removePhoto` / `removeCover`). */
    val removePhoto: Boolean = false,
    val removeCover: Boolean = false,
)

data class SpaceUpdated(val space: Space, val notified: Int)

enum class MembershipPaymentKind { Join, Renewal, Rejoin }

/** SP13: what joining, renewing or rejoining costs, held 15 minutes. */
data class MembershipPayment(
    val paymentId: String,
    val spaceId: String,
    val kind: MembershipPaymentKind,
    val amountMinor: String,
    val period: PricePeriod,
    val periodStart: Long?,
    val periodEnd: Long?,
    val previousAmountMinor: String?,
    val walletId: String?,
    val blockchains: List<String>,
    val expiresAt: Long,
)

data class ReceivedInvite(val inviteId: String, val space: SpaceSummary, val invitedBy: SpacePerson, val invitedAt: Long, val expiresAt: Long, val known: Known)

data class JoinRequest(val user: SpacePerson, val requestedAt: Long)
enum class InviteState { Invited, Joined, Expired }
data class SentInvite(val inviteId: String, val user: SpacePerson, val invitedBy: SpacePerson?, val state: InviteState, val invitedAt: Long, val expiresAt: Long?, val joinedAt: Long?)
data class SpaceInvites(val requests: List<JoinRequest>, val invites: List<SentInvite>)

enum class InviteOutcome { Invited, AlreadyMember, Banned, Unreachable }

data class SpaceMember(val person: SpacePerson, val role: SpaceRole?, val joinedAt: Long?, val mutedUntil: Long?, val bannedAt: Long?)
enum class MemberFilter(val wire: String) { All("ALL"), Muted("MUTED"), Banned("BANNED") }
data class MembersPageResult(val team: List<SpaceMember>, val contacts: List<SpaceMember>, val members: List<SpaceMember>, val count: Int, val next: String?)

/** SR1b's mute choices. */
enum class MuteDuration(val wire: String, val label: String) { Hour("HOUR", "1 hour"), Day("DAY", "24 hours"), Week("WEEK", "7 days"), UntilUnmuted("UNTIL_UNMUTED", "Until unmuted") }

// ---- Space chat (not end-to-end encrypted)

data class PostAuthor(val userId: String, val displayName: String, val handle: String?, val avatarUrl: String?, val role: SpaceRole?)
data class PostMedia(val mediaId: String, val kind: String, val contentType: String, val width: Int?, val height: Int?, val durationMs: Long?, val url: String?)
data class ReplyQuote(val postId: String, val authorName: String, val text: String?, val removed: Boolean)
data class ReactionCount(val emoji: String, val count: Int)
enum class RemovedBy { Author, Moderator }

/** How my own post is getting on: posts are a queueable write (SH1d). */
enum class PostSendState { Sent, Waiting, Failed }

data class SpacePost(
    /** Stable on the phone: my client id for my own posts, the server id for everyone else's. */
    val localKey: String,
    val postId: String?,
    val spaceId: String,
    val author: PostAuthor,
    val text: String?,
    val media: List<PostMedia>,
    val replyTo: ReplyQuote?,
    val reactions: List<ReactionCount>,
    val myReaction: String?,
    val createdAt: Long,
    val removed: RemovedBy?,
    val mine: Boolean,
    val sendState: PostSendState = PostSendState.Sent,
    /** Why it failed (an error code), for [PostSendState.Failed]. */
    val error: String? = null,
    /** My reaction is waiting to send. */
    val reactionWaiting: Boolean = false,
)

enum class ReportReason(val wire: String, val label: String) {
    SpamScam("SPAM_SCAM", "Spam or a scam"), Counterfeit("COUNTERFEIT", "Fake or counterfeit goods"),
    Harassment("HARASSMENT", "Harassment or bullying"), HateViolence("HATE_VIOLENCE", "Hate or violence"),
    Sexual("SEXUAL", "Sexual content"), Other("OTHER", "Something else");

    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: Other }
}

enum class ReportAction(val wire: String) { Keep("KEEP"), RemovePost("REMOVE_POST"), Mute("MUTE"), RemoveMember("REMOVE_MEMBER") }
data class ReportResolution(val action: ReportAction, val by: SpacePerson?, val at: Long?)
data class ReportGroup(val post: SpacePost, val reason: ReportReason, val count: Int, val firstAt: Long?, val lastAt: Long?, val open: Boolean, val resolution: ReportResolution?)

/** What happened to me in a space, from the realtime frames: toasts, the welcome line, the receipt. */
sealed interface SpaceEvent {
    val spaceId: String
    data class Membership(override val spaceId: String, val spaceName: String, val reason: String, val until: Long?) : SpaceEvent
    data class Invited(override val spaceId: String, val spaceName: String, val inviterName: String) : SpaceEvent
    data class InviteAccepted(override val spaceId: String, val spaceName: String, val name: String) : SpaceEvent
    data class RequestNew(override val spaceId: String, val spaceName: String, val name: String) : SpaceEvent
    data class ReportNew(override val spaceId: String, val postId: String, val count: Int) : SpaceEvent
    data class ReportResolved(override val spaceId: String, val action: String) : SpaceEvent
    data class PriceChanged(override val spaceId: String, val priceMinor: String, val previousMinor: String) : SpaceEvent
}
