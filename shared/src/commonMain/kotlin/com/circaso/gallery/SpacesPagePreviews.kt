package com.circaso.gallery

import androidx.compose.runtime.Composable
import com.circaso.core.sync.nowMillis
import com.circaso.feature.spaces.domain.JoinRequestState
import com.circaso.feature.spaces.domain.Known
import com.circaso.feature.spaces.domain.MembersPageResult
import com.circaso.feature.spaces.domain.Membership
import com.circaso.feature.spaces.domain.MembershipPayment
import com.circaso.feature.spaces.domain.MembershipPaymentKind
import com.circaso.feature.spaces.domain.MembershipState
import com.circaso.feature.spaces.domain.MyInvite
import com.circaso.feature.spaces.domain.PostAuthor
import com.circaso.feature.spaces.domain.PricePeriod
import com.circaso.feature.spaces.domain.ReactionCount
import com.circaso.feature.spaces.domain.Relation
import com.circaso.feature.spaces.domain.Space
import com.circaso.feature.spaces.domain.SpaceMe
import com.circaso.feature.spaces.domain.SpaceMember
import com.circaso.feature.spaces.domain.SpacePerson
import com.circaso.feature.spaces.domain.SpacePost
import com.circaso.feature.spaces.domain.SpacePrice
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.domain.SpaceSummary
import com.circaso.feature.spaces.domain.SpaceTopic
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.presentation.page.JoinStep
import com.circaso.feature.spaces.presentation.page.PageProblem
import com.circaso.feature.spaces.presentation.page.SpacePageActions
import com.circaso.feature.spaces.presentation.page.SpacePageContent
import com.circaso.feature.spaces.presentation.page.SpacePageUiState
import com.circaso.feature.spaces.presentation.page.SpaceTab
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.AssetTotal
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.presentation.PayFailure
import com.circaso.feature.wallet.presentation.PayStep
import com.circaso.feature.wallet.presentation.PayUiState

// `-preview=space-*`: the space page and paid entry (SS, SM, SI2b) with the canvas's own samples.

private const val Day = 86_400_000L
private val kesFiat = FiatValue("KES", "0", "129.20", "2026-11-04T10:42:00Z", false)

private fun sneaker(me: SpaceMe = SpaceMe(), known: Known = Known(listOf("Juma", "Faith"), 3)) = Space(
    summary = SpaceSummary("s1", "sneakerdrop", "Nairobi Sneaker Drop", "Original sneakers in Nairobi CBD. New drops every Friday, M-Pesa-friendly, delivery in town.", SpaceTopic.Shopping, memberCount = 1204),
    owner = SpacePerson("baraka", "Baraka Mwita", "barakam"), known = known, me = me, asOf = nowMillis() - 600_000,
)

private fun books(me: SpaceMe = SpaceMe()) = Space(
    summary = SpaceSummary("s2", "nairobibooks", "Nairobi Book Club", "A monthly book, a Saturday meet-up at Alliance Française, and a chat that actually reads.", SpaceTopic.Books,
        visibility = SpaceVisibility.MembersOnly, price = SpacePrice("5000000", PricePeriod.Monthly), memberCount = 214),
    owner = SpacePerson("njeri", "Njeri Mwangi", "njerim"), known = Known(listOf("Njeri", "Achieng"), 2), me = me, asOf = nowMillis() - 600_000,
)

private fun founders(me: SpaceMe = SpaceMe()) = Space(
    summary = SpaceSummary("s3", "founderscircle", "Nairobi Founders Circle", "Founders building in Nairobi. Monthly dinners, honest numbers, no pitching.", SpaceTopic.Business,
        visibility = SpaceVisibility.InviteOnly, memberCount = 48),
    owner = SpacePerson("otieno", "Otieno Odhiambo"), known = Known(listOf("Wanjiru"), 1), me = me,
)

private fun post(key: String, who: String, name: String, text: String, role: SpaceRole?, reactions: List<ReactionCount> = emptyList(), mine: Boolean = false) =
    SpacePost(key, key, "s1", PostAuthor(who, name, null, null, role), text, emptyList(), null, reactions, null, nowMillis() - 3_600_000, null, mine)

private val sneakerPosts = listOf(
    post("p1", "baraka", "Baraka Mwita", "New Jordan 1s land Friday 6 pm. Sizes 40 to 45. 🔥", SpaceRole.Owner, listOf(ReactionCount("🔥", 48), ReactionCount("👟", 12))),
    post("p2", "amina", "Amina Wairimu", "Size 42 is back in stock for those who asked", SpaceRole.Admin, listOf(ReactionCount("🙌", 9))),
)
private val bookPosts = listOf(
    post("b1", "achieng", "Achieng Otieno", "Chapter 7 had me in tears. Who else is done?", null, listOf(ReactionCount("😭", 6))),
    post("b2", "njeri", "Njeri Mwangi", "Saturday’s meet-up is at 2 pm, Alliance Française garden", SpaceRole.Owner),
)

private val member = SpaceMe(Relation.Member, SpaceRole.Member)
private fun paid(state: MembershipState = MembershipState.Active, renewSoon: Boolean = false): SpaceMe {
    val until = nowMillis() + (if (renewSoon) 2 else 20) * Day
    return SpaceMe(
        if (state == MembershipState.Ended) Relation.Ended else Relation.Member, SpaceRole.Member,
        membership = Membership(state, PricePeriod.Monthly, paidUntil = if (state == MembershipState.Active) until else nowMillis() - Day,
            graceUntil = if (state != MembershipState.Active) nowMillis() + 2 * Day else null, renewFrom = until - 3 * Day),
    )
}

private fun quote(kind: MembershipPaymentKind = MembershipPaymentKind.Join, minor: String = "5000000", previous: String? = null) = MembershipPayment(
    "pay-1", "s2", kind, minor, PricePeriod.Monthly, nowMillis(), nowMillis() + 30 * Day, previous, "w1", listOf("BASE-SEPOLIA"), nowMillis() + 14 * 60_000 + 52_000,
)

private fun payState(step: PayStep = PayStep.Amount, balance: String = "141180000", online: Boolean = true, failure: PayFailure? = null) = PayUiState(
    step = step, payee = Person("", "Nairobi Book Club", null, null, null), online = online, inFiat = false, amountText = "5", blockchain = "BASE-SEPOLIA",
    wallet = Wallet(WalletStatus.Active, listOf(WalletChain("BASE-SEPOLIA", "0x51c0", "ACTIVE", listOf(Asset("USDC", "0xusdc", 6, balance, "", null)), true)),
        listOf(AssetTotal("USDC", 6, balance, "")), kesFiat, null, nowMillis()),
    spaceId = "s2", spacePaymentId = "pay-1", failure = failure,
)

private fun ui(space: Space?, vararg pairs: Pair<String, Any?>): SpacePageUiState {
    var s = SpacePageUiState(spaceId = space?.spaceId, space = space, loading = space == null, fiat = kesFiat, now = nowMillis(),
        posts = if (space?.spaceId == "s2") bookPosts else sneakerPosts)
    pairs.forEach { (k, v) ->
        s = when (k) {
            "tab" -> s.copy(tab = v as SpaceTab)
            "join" -> s.copy(join = v as JoinStep)
            "menu" -> s.copy(menuOpen = true)
            "leave" -> s.copy(menuOpen = true, leaveConfirm = true)
            "offline" -> s.copy(online = false)
            "welcome" -> s.copy(welcome = v as String)
            "problem" -> s.copy(problem = v as PageProblem, loading = false)
            "members" -> s.copy(members = v as MembersPageResult)
            "posts" -> s.copy(posts = @Suppress("UNCHECKED_CAST") (v as List<SpacePost>))
            else -> s
        }
    }
    return s
}

private val sneakerMembers = MembersPageResult(
    team = listOf(
        SpaceMember(SpacePerson("baraka", "Baraka Mwita", "barakam"), SpaceRole.Owner, null, null, null),
        SpaceMember(SpacePerson("amina", "Amina Wairimu", "amina.w"), SpaceRole.Admin, null, null, null),
        SpaceMember(SpacePerson("kevin", "Kevin Ochieng", "kevo"), SpaceRole.Moderator, null, null, null),
    ),
    contacts = listOf(
        SpaceMember(SpacePerson("juma", "Juma Kariuki", "jumak"), SpaceRole.Member, null, null, null),
        SpaceMember(SpacePerson("faith", "Faith Chebet", "faithc"), SpaceRole.Member, null, null, null),
    ),
    members = emptyList(), count = 1204, next = null,
)

@Composable
internal fun SpacePagePreview(name: String): Boolean {
    val a = SpacePageActions.None
    val s: Pair<SpacePageUiState, PayUiState?> = when (name) {
        "space-public" -> ui(sneaker()) to null
        "space-following" -> ui(sneaker(member)) to null
        "space-following-menu" -> ui(sneaker(member), "menu" to true) to null
        "space-members-only" -> ui(books()) to null
        "space-invite-only" -> ui(founders()) to null
        "space-requested" -> ui(founders(SpaceMe(Relation.Requested, request = JoinRequestState(false, nowMillis() - 3_600_000)))) to null
        "space-invited" -> ui(founders(SpaceMe(Relation.Invited, invite = MyInvite("i1", SpacePerson("wanjiru", "Wanjiru Kamau"), nowMillis() - 2 * Day, nowMillis() + 12 * Day))).copy(known = Known(listOf("Wanjiru", "Otieno", "Achieng"), 3))) to null
        "space-about" -> ui(sneaker(member), "tab" to SpaceTab.About) to null
        "space-members" -> ui(sneaker(member), "tab" to SpaceTab.Members, "members" to sneakerMembers) to null
        "space-loading" -> ui(null) to null
        "space-offline" -> ui(sneaker(), "offline" to true) to null
        "space-error" -> ui(null, "problem" to PageProblem.Failed("Something went wrong on our side. Try again in a moment.", "req_S6p0Qa")) to null
        "space-unavailable" -> ui(null, "problem" to PageProblem.Unavailable(removed = true, name = "Gikomba Deals")) to null
        "space-banned" -> ui(sneaker(SpaceMe(Relation.Banned))) to null
        "space-join" -> ui(books(), "join" to JoinStep.Quoted(quote())) to payState()
        "space-join-pin" -> ui(books(), "join" to JoinStep.Quoted(quote())) to payState(PayStep.Signing)
        "space-joining" -> ui(books(), "join" to JoinStep.Joining(quote(), "t1")) to payState(PayStep.Done)
        "space-welcome" -> ui(books(paid()), "welcome" to "You joined · member until 4 Dec") to null
        "space-join-failed" -> ui(books(), "join" to JoinStep.Quoted(quote())) to payState(failure = PayFailure("your wallet", "Something went wrong on our side. Please try again.", "req_M1x7Pw"))
        "space-join-offline" -> ui(books(), "join" to JoinStep.Offline, "offline" to true) to null
        "space-join-short" -> ui(books(), "join" to JoinStep.Quoted(quote())) to payState(balance = "3170000")
        "space-renew" -> ui(books(paid(renewSoon = true))) to null
        "space-renew-price" -> ui(books(paid(renewSoon = true)), "join" to JoinStep.Quoted(quote(MembershipPaymentKind.Renewal, "6000000", "5000000"))) to payState()
        "space-grace" -> ui(books(paid(MembershipState.Grace))) to null
        "space-ended" -> ui(books(paid(MembershipState.Ended))) to null
        "space-leave" -> ui(books(paid()), "leave" to true) to null
        else -> return false
    }
    SpacePageContent(s.first, s.second, a)
    return true
}
