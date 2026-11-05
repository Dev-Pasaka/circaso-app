package com.circaso.feature.spaces

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.flags.SpacesFlag
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.core.sync.Outbox
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.GroupCreated
import com.circaso.feature.messaging.domain.GroupDraft
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.SecurityCode
import com.circaso.feature.spaces.data.remote.MeDto
import com.circaso.feature.spaces.data.remote.MembershipDto
import com.circaso.feature.spaces.data.remote.MembershipPaymentDto
import com.circaso.feature.spaces.data.remote.MoneyDto
import com.circaso.feature.spaces.data.remote.PostsPage
import com.circaso.feature.spaces.data.repository.SpacesRepositoryImpl
import com.circaso.feature.spaces.domain.Membership
import com.circaso.feature.spaces.domain.MembershipState
import com.circaso.feature.spaces.domain.PostAuthor
import com.circaso.feature.spaces.domain.PricePeriod
import com.circaso.feature.spaces.domain.Relation
import com.circaso.feature.spaces.domain.Space
import com.circaso.feature.spaces.domain.SpaceMe
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.domain.SpaceSummary
import com.circaso.feature.spaces.presentation.page.JoinStep
import com.circaso.feature.spaces.presentation.page.PageProblem
import com.circaso.feature.spaces.presentation.page.SpacePageViewModel
import com.circaso.feature.spaces.presentation.page.membershipBannerText
import com.circaso.feature.wallet.domain.Amount
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.FakeSpacesDao
import com.circaso.testing.FakeSpacesRemote
import com.circaso.testing.SpaceSamples
import com.circaso.testing.apiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpacePageViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val dao = FakeSpacesDao()
    private val remote = FakeSpacesRemote()
    private val os = MutableStateFlow(ConnectionStatus.Online)
    private lateinit var connection: ConnectionState
    private val chats = PrivateChats()
    private val repo by lazy {
        SpacesRepositoryImpl(
            dao, remote, SpacesFlag.on(), Outbox(FakeOutboxDao(), owner = { "me" }), kickOutbox = {}, json = json, ownUserId = { "me" },
            me = { PostAuthor("me", "Pasaka Mutuku", "pasaka", null, null) },
        )
    }

    @BeforeTest fun setUp() {
        Dispatchers.setMain(main)
        connection = ConnectionState(object : ConnectivityMonitor { override val osStatus = os }, NetworkHealth(), CoroutineScope(main), offlineDelayMs = 0)
    }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm(id: String? = "s1", handle: String? = null, join: Boolean = false) =
        SpacePageViewModel(id, handle, join, repo, chats, connection, flowOf(null), joinPollMs = Long.MAX_VALUE / 4)

    @Test fun aPublicSpaceOpensWithItsLatestPostsForAVisitor() = runTest {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space()) }
        remote.postsAnswer = { _, _ -> AppResult.Success(PostsPage(listOf(SpaceSamples.post("p1", "New Jordan 1s land Friday")))) }
        val v = vm()
        val s = v.state.value
        assertFalse(s.loading)
        assertEquals("Nairobi Sneaker Drop", s.space?.name)
        assertEquals(1, s.posts.size)
        assertTrue(s.canSeeChat)
        assertFalse(s.canPost, "visitors read, they don't post (SS1)")
    }

    @Test fun aMembersOnlyVisitorNeverLoadsPosts() = runTest {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space(visibility = "MEMBERS_ONLY", price = SpaceSamples.books)) }
        val v = vm()
        assertFalse(v.state.value.canSeeChat)
        assertTrue(remote.calls.none { it.startsWith("posts") })
    }

    @Test fun aLinkOpensByHandle() = runTest {
        remote.byHandleAnswer = { AppResult.Success(SpaceSamples.space(id = "s9", handle = it)) }
        val v = vm(id = null, handle = "@SneakerDrop")
        assertEquals("s9", v.state.value.spaceId)
        assertTrue("byHandle:sneakerdrop" in remote.calls)
    }

    @Test fun aRemovedSpaceSaysSoCalmly() = runTest {
        remote.spaceAnswer = { apiError("spaces.space_unavailable", 404, mapOf("reason" to "REMOVED", "name" to "Gikomba Deals")) }
        val p = vm().state.value.problem
        assertEquals(PageProblem.Unavailable(removed = true, name = "Gikomba Deals"), p)
    }

    @Test fun aFailureWithNothingOnThePhoneShowsTheReference() = runTest {
        remote.spaceAnswer = { apiError("server.error", 500, requestId = "req_S6p0Qa") }
        val p = vm().state.value.problem
        assertIs<PageProblem.Failed>(p)
        assertTrue(p.reference!!.startsWith("req_S6p0Qa"), "the reference for support")
    }

    @Test fun followWaitsForTheServerAndARefusalIsSaid() = runTest {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space()) }
        val v = vm()
        remote.meAnswer = apiError("spaces.banned", 403)
        v.onFollow()
        assertEquals(Relation.None, v.state.value.space?.me?.relation)
        assertEquals("An admin banned you from this space.", v.state.value.actionError)
        remote.meAnswer = AppResult.Success(MeDto("MEMBER", "MEMBER"))
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space(me = MeDto("MEMBER", "MEMBER"))) }
        v.onFollow()
        assertEquals(Relation.Member, v.state.value.space?.me?.relation)
        assertNull(v.state.value.busy)
    }

    @Test fun offlineNothingOnlineOnlyIsSent() = runTest {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space()) }
        val v = vm()
        os.value = ConnectionStatus.Offline
        v.onFollow()
        assertTrue(remote.calls.none { it.startsWith("follow") })
        v.onJoin()
        assertEquals(JoinStep.Offline, v.state.value.join, "the card still opens with the price, Pay off (SM1f)")
    }

    @Test fun unfollowingAPublicSpaceOffersUndoAndAPaidOneConfirms() = runTest {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space(me = MeDto("MEMBER", "MEMBER"))) }
        val v = vm()
        v.onUnfollow()
        assertTrue(v.state.value.undoUnfollow)
        assertTrue("leave:s1" in remote.calls)

        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space(id = "s2", visibility = "MEMBERS_ONLY", price = SpaceSamples.books, me = MeDto("MEMBER", "MEMBER", membership = MembershipDto("ACTIVE", "MONTHLY", paidUntil = "2026-12-04T00:00:00Z")))) }
        val paid = vm("s2")
        paid.onUnfollow()
        assertTrue(paid.state.value.leaveConfirm, "a paid membership confirms inside the popover (SM6)")
        assertEquals(1, remote.calls.count { it.startsWith("leave") })
    }

    @Test fun joiningSaysJoiningUntilTheConfirmedMembershipArrives() = runTest {
        var me = MeDto()
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space(id = "s2", name = "Nairobi Book Club", visibility = "MEMBERS_ONLY", price = SpaceSamples.books, me = me)) }
        remote.paymentAnswer = AppResult.Success(MembershipPaymentDto("pay-1", "s2", "JOIN", MoneyDto(amountMinor = "5000000", amount = "5"), "MONTHLY", expiresAt = "2099-01-01T00:00:00Z", blockchains = listOf("BASE-SEPOLIA")))
        val v = vm("s2")
        v.onJoin()
        val quoted = v.state.value.join
        assertIs<JoinStep.Quoted>(quoted)
        assertEquals("5000000", quoted.payment.amountMinor)

        v.onMembershipSigned(transfer("t1", "5000000"))
        assertIs<JoinStep.Joining>(v.state.value.join, "never a member from the tap (SM1c)")
        assertEquals("t1", dao.receipts.value["s2"]?.transferId, "the receipt waits for the confirmation (SM2)")

        me = MeDto("MEMBER", "MEMBER", membership = MembershipDto("ACTIVE", "MONTHLY", paidUntil = "2026-12-04T00:00:00Z"))
        repo.onFrame("space.membership_updated", buildJsonObject { put("spaceId", "s2"); put("spaceName", "Nairobi Book Club"); put("reason", "JOINED") })
        assertEquals(JoinStep.Closed, v.state.value.join)
        assertTrue(v.state.value.welcome!!.startsWith("You joined · member until"))
    }

    @Test fun aFailedQuoteIsNotSentWithItsReference() = runTest {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space(visibility = "MEMBERS_ONLY", price = SpaceSamples.books)) }
        remote.paymentAnswer = apiError("spaces.wallet_not_ready", 409, requestId = "req_M1x7Pw")
        val v = vm()
        v.onJoin()
        val step = v.state.value.join
        assertIs<JoinStep.QuoteFailed>(step)
        assertTrue(step.reference!!.startsWith("req_M1x7Pw"))
        assertEquals("This space can’t take payments yet. Try again later.", step.message)
    }

    @Test fun alreadyPaidSaysWhenRenewOpens() = runTest {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space(visibility = "MEMBERS_ONLY", price = SpaceSamples.books)) }
        remote.paymentAnswer = apiError("spaces.already_member", 409, mapOf("renewFrom" to "2026-12-01T00:00:00Z"))
        val v = vm()
        v.onJoin()
        assertEquals(JoinStep.Closed, v.state.value.join)
        assertTrue(v.state.value.actionError!!.startsWith("You’re a member. Renew opens on"))
    }

    @Test fun messageOpensThePrivateChatThroughTheSpace() = runTest {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space()) }
        val v = vm()
        v.onMessage()
        assertEquals(listOf("baraka" to "s1"), chats.opened)
    }

    @Test fun acceptingAnInviteJoinsAndRecordsWhoInvited() = runTest {
        val invited = MeDto("INVITED", invite = com.circaso.feature.spaces.data.remote.MyInviteDto("i1", com.circaso.feature.spaces.data.remote.PersonDto("wanjiru", "Wanjiru Kamau"), "2026-11-02T09:00:00Z", "2099-11-16T09:00:00Z"))
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space(visibility = "INVITE_ONLY", me = invited)) }
        remote.acceptAnswer = AppResult.Success(SpaceSamples.space(visibility = "INVITE_ONLY", me = MeDto("MEMBER", "MEMBER")))
        val v = vm()
        v.onAcceptInvite()
        assertEquals(Relation.Member, v.state.value.space?.me?.relation)
        assertEquals("You joined · Wanjiru invited you", v.state.value.welcome)
    }

    @Test fun aMemberPostsFromThePageAndItWaitsOnThePhone() = runTest {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space(me = MeDto("MEMBER", "MEMBER"))) }
        val v = vm()
        v.onDraft("Is the CBD shop open Sunday?")
        v.onSend()
        assertEquals("", v.state.value.draft)
        assertEquals("Is the CBD shop open Sunday?", v.state.value.posts.last().text)
    }

    @Test fun theBannerSaysRenewSoonOrGraceAndNothingOtherwise() {
        val now = 1_000_000_000_000L
        val day = 86_400_000L
        fun space(m: Membership?) = Space(SpaceSummary("s", "h", "Nairobi Book Club"), me = SpaceMe(Relation.Member, SpaceRole.Member, membership = m))
        assertNull(membershipBannerText(space(null), now))
        assertNull(membershipBannerText(space(Membership(MembershipState.Active, PricePeriod.Monthly, paidUntil = now + 20 * day, renewFrom = now + 17 * day)), now))
        val soon = membershipBannerText(space(Membership(MembershipState.Active, PricePeriod.Monthly, paidUntil = now + 2 * day, renewFrom = now - day)), now)
        assertNotNull(soon); assertFalse(soon.second); assertTrue(soon.first.startsWith("Your membership ends on"))
        val grace = membershipBannerText(space(Membership(MembershipState.Grace, PricePeriod.Monthly, paidUntil = now - day, graceUntil = now + 2 * day)), now)
        assertNotNull(grace); assertTrue(grace.second); assertTrue(grace.first.contains("You can still read and post until"))
        assertNull(membershipBannerText(space(Membership(MembershipState.Active, PricePeriod.OneTime)), now), "one-time memberships never renew")
    }

    private fun transfer(id: String, minor: String) = Transfer(id, TransferDirection.Out, TransferState.Submitted, "BASE-SEPOLIA", "", null, "", Amount("USDC", minor, "5"), null, null, null, null, "Nairobi Book Club", null, 0, 0)
}

private class PrivateChats : ChatsRepository {
    val opened = mutableListOf<Pair<String, String>>()
    override val chats: Flow<List<ChatSummary>> = MutableStateFlow(emptyList())
    override fun chat(chatId: String): Flow<Chat?> = flowOf(null)
    override suspend fun refresh() = AppResult.Success(Unit)
    override suspend fun refreshChat(chatId: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun openDirect(userId: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun openViaSpace(userId: String, spaceId: String): AppResult<Chat> {
        opened += userId to spaceId
        return AppResult.Success(Chat("c-$spaceId", ChatKind.Direct, "Baraka", null, emptyList(), false, 1, 1, MemberRole.Member, userId, viaSpaceId = spaceId))
    }
    override suspend fun createGroup(draft: GroupDraft, idempotencyKey: String): AppResult<GroupCreated> = AppResult.Failure(AppError.Network)
    override suspend fun rename(chatId: String, name: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun setMembersCanAdd(chatId: String, allowed: Boolean): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun addMembers(chatId: String, userIds: List<String>): AppResult<GroupCreated> = AppResult.Failure(AppError.Network)
    override suspend fun removeMember(chatId: String, userId: String) = AppResult.Success(Unit)
    override suspend fun leave(chatId: String) = AppResult.Success(Unit)
    override suspend fun setRole(chatId: String, userId: String, role: MemberRole): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun securityCode(userId: String): SecurityCode? = null
}
