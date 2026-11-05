package com.circaso.gallery

import androidx.compose.runtime.Composable
import com.circaso.feature.spaces.domain.InviteState
import com.circaso.feature.spaces.domain.JoinRequest
import com.circaso.feature.spaces.domain.Known
import com.circaso.feature.spaces.domain.MemberFilter
import com.circaso.feature.spaces.domain.PostAuthor
import com.circaso.feature.spaces.domain.PostSendState
import com.circaso.feature.spaces.domain.PostingMode
import com.circaso.feature.spaces.domain.ReactionCount
import com.circaso.feature.spaces.domain.Relation
import com.circaso.feature.spaces.domain.RemovedBy
import com.circaso.feature.spaces.domain.ReplyQuote
import com.circaso.feature.spaces.domain.ReportGroup
import com.circaso.feature.spaces.domain.ReportReason
import com.circaso.feature.spaces.domain.SentInvite
import com.circaso.feature.spaces.domain.Space
import com.circaso.feature.spaces.domain.SpaceMe
import com.circaso.feature.spaces.domain.SpaceMember
import com.circaso.feature.spaces.domain.SpacePerson
import com.circaso.feature.spaces.domain.SpacePost
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.domain.SpaceSummary
import com.circaso.feature.spaces.domain.SpaceTopic
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.presentation.chat.InviteRow
import com.circaso.feature.spaces.presentation.chat.MemberMenu
import com.circaso.feature.spaces.presentation.chat.MemberMenuMode
import com.circaso.feature.spaces.presentation.chat.PostMenu
import com.circaso.feature.spaces.presentation.chat.SpaceChatActions
import com.circaso.feature.spaces.presentation.chat.SpaceChatContent
import com.circaso.feature.spaces.presentation.chat.SpaceChatUiState
import com.circaso.feature.spaces.presentation.chat.SpaceInviteActions
import com.circaso.feature.spaces.presentation.chat.SpaceInviteContent
import com.circaso.feature.spaces.presentation.chat.SpaceInviteUiState
import com.circaso.feature.spaces.presentation.chat.SpaceInvitesActions
import com.circaso.feature.spaces.presentation.chat.SpaceInvitesContent
import com.circaso.feature.spaces.presentation.chat.SpaceInvitesUiState
import com.circaso.feature.spaces.presentation.chat.SpaceMembersActions
import com.circaso.feature.spaces.presentation.chat.SpaceMembersContent
import com.circaso.feature.spaces.presentation.chat.SpaceMembersUiState
import com.circaso.feature.spaces.presentation.chat.SpaceReportActions
import com.circaso.feature.spaces.presentation.chat.SpaceReportContent
import com.circaso.feature.spaces.presentation.chat.SpaceReportUiState
import com.circaso.feature.spaces.presentation.chat.SpaceReportsActions
import com.circaso.feature.spaces.presentation.chat.SpaceReportsContent
import com.circaso.feature.spaces.presentation.chat.SpaceReportsUiState
import com.circaso.feature.spaces.presentation.chat.SpaceRolesContent

// Sample data from the canvas (Nairobi Sneaker Drop, Founders Circle): SH1–SH1f, SR1–SR4d, SI1–SI4b.

private const val Now = 1_762_272_000_000L // 2025-11-04 16:00 UTC
private const val Hour = 3_600_000L

private val sneaker = SpaceSummary("s1", "sneakerdrop", "Nairobi Sneaker Drop", "Original sneakers in Nairobi CBD.", SpaceTopic.Shopping, visibility = SpaceVisibility.Public, memberCount = 1204)
private val baraka = SpacePerson("baraka", "Baraka Mwita", "barakam")

private fun space(role: SpaceRole? = SpaceRole.Member, mode: PostingMode = PostingMode.Everyone, mutedUntil: Long? = null, summary: SpaceSummary = sneaker) =
    Space(summary, owner = baraka, postingMode = mode, known = Known(listOf("Juma", "Faith"), 3), me = SpaceMe(Relation.Member, role, mutedUntil = mutedUntil), asOf = Now)

private fun post(key: String, who: String, name: String, text: String?, role: SpaceRole? = null, at: Long, reactions: List<ReactionCount> = emptyList(), mine: Boolean = false, my: String? = null,
                 reply: ReplyQuote? = null, state: PostSendState = PostSendState.Sent, removed: RemovedBy? = null) =
    SpacePost(key, if (state == PostSendState.Sent) key else null, "s1", PostAuthor(who, name, null, null, role), text, emptyList(), reply, reactions, my, at, removed, mine, state)

private val sneakerPosts = listOf(
    post("p1", "baraka", "Baraka Mwita", "New Jordan 1s land Friday 6 pm. Sizes 40 to 45. 🔥", SpaceRole.Owner, Now - 3 * Hour, listOf(ReactionCount("🔥", 48), ReactionCount("👟", 12)), my = "🔥"),
    post("p2", "juma", "Juma Kariuki", "Will there be a size 44 in the Chicago colourway?", null, Now - 2 * Hour),
    post("p3", "amina", "Amina Wairimu", "Yes, 3 pairs. Message Baraka privately to hold one", SpaceRole.Admin, Now - Hour, listOf(ReactionCount("🙏", 4)), reply = ReplyQuote("p2", "Juma", "Will there be a size 44 in the Chicago…", false)),
    post("c4", "me", "Pasaka Mutuku", "Holding a 42 for me? 🙏🏾", null, Now - 30 * 60_000, mine = true),
)

private val chat = SpaceChatUiState("s1", space(), sneakerPosts, myUserId = "me", loading = false, atStart = true, now = Now)

@Composable
internal fun SpaceChatPreview(name: String): Boolean {
    when (name) {
        "space-chat" -> SpaceChatContent(chat, SpaceChatActions())
        "space-chat-admins-only" -> SpaceChatContent(
            chat.copy(
                space = space(mode = PostingMode.AdminsOnly, summary = sneaker.copy(name = "Jubilee Youth Worship", handle = "jubileeyouth", memberCount = 3180)),
                posts = listOf(
                    post("w1", "njeri", "Njeri Mwangi", "Youth service moves to 4 pm this Sunday. Bring a friend! 🙌🏾", SpaceRole.Admin, Now - 2 * Hour, listOf(ReactionCount("🙌🏾", 214), ReactionCount("❤️", 96)), my = "🙌🏾"),
                    post("w2", "otieno", "Otieno Odhiambo", "Choir rehearsal: Thursday 6 pm, main hall", SpaceRole.Owner, Now - Hour, listOf(ReactionCount("🎶", 58))),
                ),
            ),
            SpaceChatActions(),
        )
        "space-chat-menu" -> SpaceChatContent(chat.copy(space = space(SpaceRole.Admin), menu = PostMenu("p2")), SpaceChatActions())
        "space-chat-mute" -> SpaceChatContent(chat.copy(space = space(SpaceRole.Admin), menu = PostMenu("p2", muting = true)), SpaceChatActions())
        "space-chat-offline" -> SpaceChatContent(
            chat.copy(online = false, posts = sneakerPosts.take(1) + post("c5", "me", "Pasaka Mutuku", "Is the CBD shop open Sunday?", null, Now - 60_000, mine = true, state = PostSendState.Waiting)),
            SpaceChatActions(),
        )
        "space-chat-muted" -> SpaceChatContent(chat.copy(space = space(mutedUntil = Now + 4 * Hour), posts = sneakerPosts.dropLast(1)), SpaceChatActions())
        "space-chat-removed" -> SpaceChatContent(
            chat.copy(
                atStart = false, olderFailed = true, olderRef = "req_H5tq0B",
                posts = listOf(
                    post("r1", "kevin", "Kevin Ochieng", null, null, Now - 2 * Hour, removed = RemovedBy.Moderator),
                    post("r2", "amina", "Amina Wairimu", "Reminder: no reselling posts here. Use your own Moments 🙏", SpaceRole.Admin, Now - Hour),
                ),
            ),
            SpaceChatActions(),
        )
        "space-chat-loading" -> SpaceChatContent(chat.copy(posts = emptyList(), loading = true, atStart = false), SpaceChatActions())
        "space-members-admin", "space-member-menu", "space-member-remove", "space-members-moderator" -> {
            val role = if (name == "space-members-moderator") SpaceRole.Moderator else SpaceRole.Admin
            val menu = when (name) {
                "space-member-menu" -> MemberMenu("faith")
                "space-member-remove" -> MemberMenu("faith", MemberMenuMode.ConfirmRemove)
                "space-members-moderator" -> MemberMenu("faith")
                else -> null
            }
            SpaceMembersContent(SpaceMembersUiState("s1", space(role), myUserId = "me", team = team, members = members, count = 1201, loading = false, menu = menu, now = Now), SpaceMembersActions(onInvite = {}))
        }
        "space-members-muted" -> SpaceMembersContent(SpaceMembersUiState("s1", space(SpaceRole.Admin), myUserId = "me", filter = MemberFilter.Muted, members = members.take(1), loading = false, now = Now), SpaceMembersActions())
        "space-roles" -> SpaceRolesContent {}
        "space-report" -> SpaceReportContent(SpaceReportUiState("s1", "k1", space(), kevinPost), SpaceReportActions())
        "space-report-sent" -> SpaceReportContent(SpaceReportUiState("s1", "k1", space(), kevinPost, sent = true), SpaceReportActions())
        "space-report-offline" -> SpaceReportContent(SpaceReportUiState("s1", "k1", space(), kevinPost, online = false), SpaceReportActions())
        "space-report-space" -> SpaceReportContent(SpaceReportUiState("s1", null, space()), SpaceReportActions())
        "space-reports" -> SpaceReportsContent(SpaceReportsUiState("s1", space(SpaceRole.Admin), reports = reports, loading = false, openCount = 2), SpaceReportsActions())
        "space-reports-empty" -> SpaceReportsContent(SpaceReportsUiState("s1", space(SpaceRole.Moderator), loading = false), SpaceReportsActions())
        "space-reports-loading" -> SpaceReportsContent(SpaceReportsUiState("s1", space(SpaceRole.Moderator)), SpaceReportsActions())
        "space-reports-error" -> SpaceReportsContent(SpaceReportsUiState("s1", space(SpaceRole.Moderator), loading = false, error = "Something went wrong on our side. No report is lost.", errorRef = "req_R4q8Vn"), SpaceReportsActions())
        "space-invite" -> SpaceInviteContent(inviteState, SpaceInviteActions())
        "space-invite-handle" -> SpaceInviteContent(inviteState.copy(query = "@kevo254", notFound = true), SpaceInviteActions())
        "space-invite-offline" -> SpaceInviteContent(inviteState.copy(online = false), SpaceInviteActions())
        "space-invites" -> SpaceInvitesContent(
            SpaceInvitesUiState(
                "s3", space(SpaceRole.Admin, summary = founders), loading = false,
                requests = listOf(JoinRequest(SpacePerson("njeri", "Njeri Mwangi", "njerim"), Now - 7 * Hour), JoinRequest(SpacePerson("baraka", "Baraka Mwita", "barakam"), Now - 26 * Hour)),
                invites = listOf(
                    SentInvite("i1", SpacePerson("kevin", "Kevin Ochieng", "kevo"), null, InviteState.Joined, Now - 72 * Hour, null, Now - 26 * Hour),
                    SentInvite("i2", SpacePerson("faith", "Faith Chebet", "faithc"), null, InviteState.Invited, Now - 48 * Hour, Now + 12 * 24 * Hour, null),
                    SentInvite("i3", SpacePerson("juma", "Juma Kariuki", "jumak"), null, InviteState.Expired, Now - 17 * 24 * Hour, Now - 3 * 24 * Hour, null),
                ),
            ),
            SpaceInvitesActions(), now = Now,
        )
        "space-invites-empty" -> SpaceInvitesContent(SpaceInvitesUiState("s3", space(SpaceRole.Admin, summary = founders), loading = false), SpaceInvitesActions(), now = Now)
        // SH2–SH2e: the private chat with the owner (an ordinary encrypted direct chat, labelled), `#` paying the space.
        "space-private" -> PrivateChatPreview(draft = "")
        "space-private-pay" -> PrivateChatPreview(draft = "#2000 deposit, size 42 Jordans", pay = spacePay)
        "space-private-not-member" -> PrivateChatPreview(draft = "#2000 deposit, size 42 Jordans", pay = spacePay, member = false)
        "space-private-owner" -> PrivateChatPreview(draft = "", owner = true)
        else -> return false
    }
    return true
}

private val spacePay = com.circaso.feature.wallet.presentation.PayUiState(
    payee = com.circaso.feature.contacts.domain.Person("", "Nairobi Sneaker Drop", null, null, null), chatId = "pv", amountText = "2000", note = "deposit, size 42 Jordans",
    spaceId = "s1", online = true, blockchain = "BASE-SEPOLIA",
    wallet = com.circaso.feature.wallet.domain.Wallet(
        status = com.circaso.feature.wallet.domain.WalletStatus.Active,
        chains = listOf(com.circaso.feature.wallet.domain.WalletChain("BASE-SEPOLIA", "0x51c0", "ACTIVE", listOf(com.circaso.feature.wallet.domain.Asset("USDC", "0xusdc", 6, "141180000", "", null)), true)),
        totals = emptyList(), fiat = com.circaso.feature.wallet.domain.FiatValue("KES", "18240", "129.20", "2026-11-04T10:00:00Z", false), holdUntil = null, fetchedAt = Now,
    ),
)

@Composable
private fun PrivateChatPreview(draft: String, pay: com.circaso.feature.wallet.presentation.PayUiState? = null, member: Boolean = true, owner: Boolean = false) {
    val me = com.circaso.feature.messaging.domain.ChatMember("me", "Pasaka Mutuku", "pasaka", null, com.circaso.feature.messaging.domain.MemberRole.Member, "")
    val them = com.circaso.feature.messaging.domain.ChatMember("baraka", "Baraka Mwita", "barakam", null, com.circaso.feature.messaging.domain.MemberRole.Member, "")
    val chat = com.circaso.feature.messaging.domain.Chat("pv", com.circaso.feature.messaging.domain.ChatKind.Direct, if (owner) "Pasaka Mutuku" else "Baraka Mwita", null, listOf(me, them), false, 1, 0,
        com.circaso.feature.messaging.domain.MemberRole.Member, "baraka", viaSpaceId = "s1")
    fun text(id: Long, body: String, out: Boolean, ago: Long) = com.circaso.feature.messaging.domain.Message.Text(
        id, "pv", Now - ago, null, "t$id", if (out) "me" else "baraka", if (out) "Pasaka" else "Baraka", out, body, com.circaso.feature.messaging.domain.DeliveryState.Read, null, null,
    )
    val state = com.circaso.feature.messaging.presentation.ThreadUiState(
        chat = chat, myUserId = "me", draft = draft,
        messages = listOf(text(1, "Hi Baraka, can you hold the size 42 Jordans for me till Friday?", !owner, 20 * 60_000), text(2, "Sure. A KES 2,000 deposit holds them, the rest on pickup", owner, 15 * 60_000)),
    )
    val via = com.circaso.feature.spaces.presentation.chat.ViaSpace(
        "s1", "Nairobi Sneaker Drop", null, "Baraka", iAmOwner = owner,
        payBlocked = if (member) null else "Only people who follow Nairobi Sneaker Drop can pay it. Following is free.",
        payBlockedAction = if (member) null else "Follow to Pay",
    )
    com.circaso.feature.messaging.presentation.ThreadContent(
        state, {}, {}, null, {}, {}, {}, {}, {}, {}, {},
        quickPay = pay?.let { com.circaso.feature.messaging.presentation.QuickPay(it, {}, {}, {}, {}, {}, {}) },
        viaSpace = via,
    )
}

private val founders = SpaceSummary("s3", "founderscircle", "Nairobi Founders Circle", null, SpaceTopic.Business, visibility = SpaceVisibility.InviteOnly, memberCount = 48)

private val team = listOf(
    SpaceMember(baraka, SpaceRole.Owner, null, null, null),
    SpaceMember(SpacePerson("amina", "Amina Wairimu", "amina.w"), SpaceRole.Admin, null, null, null),
    SpaceMember(SpacePerson("kevin", "Kevin Ochieng", "kevo"), SpaceRole.Moderator, null, null, null),
)
private val members = listOf(
    SpaceMember(SpacePerson("juma", "Juma Kariuki", "jumak"), SpaceRole.Member, null, Now + 4 * Hour, null),
    SpaceMember(SpacePerson("faith", "Faith Chebet", "faithc"), SpaceRole.Member, null, null, null),
    SpaceMember(SpacePerson("njeri", "Njeri Mwangi", "njerim"), SpaceRole.Member, null, null, null),
)

private val kevinPost = post("k1", "kevin", "Kevin Ochieng", "DM me for Jordans at half price, pay to my till first 🔥", null, Now - Hour)
private val reports = listOf(
    ReportGroup(kevinPost, ReportReason.SpamScam, 4, Now - 3 * Hour, Now - Hour, true, null),
    ReportGroup(post("o1", "otieno", "Otieno Odhiambo", "Anyone selling fake AF1s? Need 20 pairs for resale", null, Now - 4 * Hour), ReportReason.Counterfeit, 1, Now - 4 * Hour, Now - 4 * Hour, true, null),
)

private val inviteState = SpaceInviteUiState(
    "s3", space(SpaceRole.Admin, summary = founders),
    contacts = listOf(
        SpacePerson("faith", "Faith Chebet", "faithc"), SpacePerson("juma", "Juma Kariuki", "jumak"), SpacePerson("kevin", "Kevin Ochieng", "kevo"),
        SpacePerson("otieno", "Otieno Odhiambo", "otieno"), SpacePerson("pasaka", "Pasaka Mutuku", "pasaka"), SpacePerson("wanjiru", "Wanjiru Kamau", "wanjiru"),
    ),
    rows = mapOf("pasaka" to InviteRow.Invited, "faith" to InviteRow.Busy, "wanjiru" to InviteRow.Member),
)

