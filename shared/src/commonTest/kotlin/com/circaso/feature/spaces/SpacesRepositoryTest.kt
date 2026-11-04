package com.circaso.feature.spaces

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.flags.SpacesFlag
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxProcessor
import com.circaso.feature.spaces.data.remote.KnownDto
import com.circaso.feature.spaces.data.remote.MeDto
import com.circaso.feature.spaces.data.remote.MembershipDto
import com.circaso.feature.spaces.data.remote.MySpaceDto
import com.circaso.feature.spaces.data.remote.MySpacesPage
import com.circaso.feature.spaces.data.remote.PostsPage
import com.circaso.feature.spaces.data.remote.ReceivedInviteDto
import com.circaso.feature.spaces.data.remote.ReceivedInvitesDto
import com.circaso.feature.spaces.data.remote.PersonDto
import com.circaso.feature.spaces.data.repository.SpacePostHandler
import com.circaso.feature.spaces.data.repository.SpaceReactionHandler
import com.circaso.feature.spaces.data.repository.SpacesRepositoryImpl
import com.circaso.feature.spaces.data.repository.adjust
import com.circaso.feature.spaces.domain.Known
import com.circaso.feature.spaces.domain.MembershipState
import com.circaso.feature.spaces.domain.PostAuthor
import com.circaso.feature.spaces.domain.PostSendState
import com.circaso.feature.spaces.domain.ReactionCount
import com.circaso.feature.spaces.domain.Relation
import com.circaso.feature.spaces.domain.SpaceEvent
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.FakeSpacesDao
import com.circaso.testing.FakeSpacesRemote
import com.circaso.testing.SpaceSamples
import com.circaso.testing.apiError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpacesRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val dao = FakeSpacesDao()
    private val remote = FakeSpacesRemote()
    private val outboxDao = FakeOutboxDao()
    private val outbox = Outbox(outboxDao, owner = { "me" })
    private val flag = SpacesFlag.on()
    private var receipts = 0

    private fun repository() = SpacesRepositoryImpl(
        dao, remote, flag, outbox, kickOutbox = {}, json = json, ownUserId = { "me" },
        me = { PostAuthor("me", "Pasaka Mutuku", "pasaka", null, null) },
        postReceipt = { _, _ -> receipts++; true },
        now = { 1_762_250_400_000 }, // 2025-11-04T10:00Z
    )

    @Test fun mySpacesComeFromThePhoneAfterARefresh() = runTest {
        remote.mineAnswer = AppResult.Success(MySpacesPage(listOf(
            MySpaceDto(SpaceSamples.summary("s1"), MeDto("MEMBER", "MEMBER"), unreadCount = 12, activityAt = "2026-11-04T18:02:00Z"),
            MySpaceDto(SpaceSamples.summary("s2", "Nairobi Book Club", "nairobibooks", "MEMBERS_ONLY"), MeDto("MEMBER", "MEMBER", membership = MembershipDto("ACTIVE", "MONTHLY", renewFrom = "2025-11-01T00:00:00Z")), unreadCount = 150, activityAt = "2026-11-03T08:00:00Z"),
        )))
        val repo = repository()
        assertTrue(repo.refreshMine() is AppResult.Success)
        val list = repo.mySpaces.first()
        assertEquals(listOf("s1", "s2"), list.map { it.space.spaceId }) // newest activity first
        assertEquals(99, list[1].unreadCount) // capped as the contract says
        assertTrue(list[1].me.renewOpen(1_762_250_400_000))
        assertTrue(repo.mineAsOf.first() > 0)
    }

    @Test fun aFailedRefreshKeepsWhatThePhoneHas() = runTest {
        remote.mineAnswer = AppResult.Success(MySpacesPage(listOf(MySpaceDto(SpaceSamples.summary("s1")))))
        val repo = repository()
        repo.refreshMine()
        remote.mineAnswer = AppResult.Failure(AppError.Network)
        assertTrue(repo.refreshMine() is AppResult.Failure)
        assertEquals(1, repo.mySpaces.first().size)
    }

    @Test fun aSpaceThatIsGoneIsForgotten() = runTest {
        remote.mineAnswer = AppResult.Success(MySpacesPage(listOf(MySpaceDto(SpaceSamples.summary("s1")))))
        val repo = repository()
        repo.refreshMine()
        remote.spaceAnswer = { apiError("spaces.space_unavailable", 404, mapOf("reason" to "REMOVED", "name" to "Gikomba Deals")) }
        val r = repo.refreshSpace("s1")
        assertEquals("spaces.space_unavailable", ((r as AppResult.Failure).error as AppError.Api).code)
        assertTrue(repo.mySpaces.first().isEmpty())
    }

    @Test fun followIsTheServersAnswerNotOptimistic() = runTest {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space(me = MeDto())) }
        val repo = repository()
        repo.refreshSpace("s1")
        assertEquals(Relation.None, repo.space("s1").first()!!.me.relation)
        remote.meAnswer = apiError("spaces.banned", 403)
        assertTrue(repo.follow("s1") is AppResult.Failure)
        assertEquals(Relation.None, repo.space("s1").first()!!.me.relation) // nothing changed on a refusal
        remote.meAnswer = AppResult.Success(MeDto("MEMBER", "MEMBER"))
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space(me = MeDto("MEMBER", "MEMBER"))) }
        val me = (repo.follow("s1") as AppResult.Success).value
        assertEquals(Relation.Member, me.relation)
        assertEquals(Relation.Member, repo.space("s1").first()!!.me.relation)
    }

    @Test fun aPostWaitsOnThePhoneThenTakesTheServersCopyInPlace() = runTest {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space(me = MeDto("MEMBER", "ADMIN"))) }
        val repo = repository()
        repo.refreshSpace("s1")
        repo.sendPost("s1", "  Is the CBD shop open Sunday?  ")
        val waiting = repo.posts("s1").first().single()
        assertEquals(PostSendState.Waiting, waiting.sendState)
        assertEquals("Is the CBD shop open Sunday?", waiting.text)
        assertEquals(SpaceRole.Admin, waiting.author.role)
        assertTrue(waiting.mine)
        val processor = OutboxProcessor(outbox, listOf(SpacePostHandler { repo }, SpaceReactionHandler { repo }))
        processor.drain()
        val sent = repo.posts("s1").first().single()
        assertEquals(waiting.localKey, sent.localKey) // same row: no flicker, no duplicate
        assertEquals(PostSendState.Sent, sent.sendState)
        assertEquals("p-${waiting.localKey}", sent.postId)
        assertEquals("post:Is the CBD shop open Sunday?", remote.calls.last { it.startsWith("post:") })
    }

    @Test fun anOfflinePostStaysWaitingAndARefusalSaysNotSent() = runTest {
        val repo = repository()
        repo.sendPost("s1", "hello")
        remote.postAnswer = { _, _ -> AppResult.Failure(AppError.Network) }
        val processor = OutboxProcessor(outbox, listOf(SpacePostHandler { repo }))
        processor.drain()
        assertEquals(PostSendState.Waiting, repo.posts("s1").first().single().sendState)
        remote.postAnswer = { _, _ -> apiError("spaces.muted", 403, mapOf("mutedUntil" to "2026-11-04T20:00:00Z")) }
        processor.drain()
        val failed = repo.posts("s1").first().single()
        assertEquals(PostSendState.Failed, failed.sendState)
        assertEquals("spaces.muted", failed.error)
    }

    @Test fun aReactionShowsAtOnceWithItsClockAndSettlesOnTheAnswer() = runTest {
        remote.postsAnswer = { _, _ -> AppResult.Success(PostsPage(listOf(SpaceSamples.post("p1", "New Jordan 1s land Friday")))) }
        val repo = repository()
        repo.refreshPosts("s1")
        val p = repo.posts("s1").first().single()
        repo.react("s1", p.localKey, "🔥")
        val shown = repo.posts("s1").first().single()
        assertEquals("🔥", shown.myReaction)
        assertEquals(49, shown.reactions.first { it.emoji == "🔥" }.count)
        assertTrue(shown.reactionWaiting)
        remote.reactAnswer = { id, e -> AppResult.Success(com.circaso.feature.spaces.data.remote.ReactionAnswerDto(id, listOf(com.circaso.feature.spaces.data.remote.ReactionCountDto("🔥", 49)), e)) }
        OutboxProcessor(outbox, listOf(SpaceReactionHandler { repo })).drain()
        val settled = repo.posts("s1").first().single()
        assertFalse(settled.reactionWaiting)
        assertEquals(49, settled.reactions.single().count)
    }

    @Test fun reactionCountsMoveWithMine() {
        val counts = listOf(ReactionCount("🔥", 48), ReactionCount("👟", 1))
        assertEquals(listOf(ReactionCount("🔥", 47), ReactionCount("👟", 1), ReactionCount("❤️", 1)), adjust(counts, "🔥", "❤️"))
        assertEquals(listOf(ReactionCount("🔥", 48)), adjust(counts, "👟", null))
        assertEquals(counts, adjust(counts, "🔥", "🔥"))
    }

    @Test fun framesKeepTheChatAndTheListLive() = runTest {
        remote.mineAnswer = AppResult.Success(MySpacesPage(listOf(MySpaceDto(SpaceSamples.summary("s1"), MeDto("MEMBER", "MEMBER"), unreadCount = 1))))
        val repo = repository()
        repo.refreshMine()
        val post = json.encodeToJsonElement(com.circaso.feature.spaces.data.remote.PostDto.serializer(), SpaceSamples.post("p9", "Size 42 back in stock", author = "amina", name = "Amina Wairimu", role = "ADMIN"))
        repo.onFrame("space.post_new", buildJsonObject { put("spaceId", "s1"); put("spaceName", "Nairobi Sneaker Drop"); put("post", post); put("notify", true) })
        assertEquals("Size 42 back in stock", repo.posts("s1").first().single().text)
        val row = repo.mySpaces.first().single()
        assertEquals(2, row.unreadCount)
        assertEquals("Amina", row.lastPost?.authorName)
        // Removed by a moderator: a tombstone, the text gone.
        repo.onFrame("space.post_updated", buildJsonObject {
            put("spaceId", "s1"); put("postId", "p9")
            put("removed", buildJsonObject { put("by", "MODERATOR"); put("at", "2026-11-04T10:05:00Z") })
        })
        val removed = repo.posts("s1").first().single()
        assertNull(removed.text)
        assertEquals(com.circaso.feature.spaces.domain.RemovedBy.Moderator, removed.removed)
    }

    @Test fun anOpenChatDoesNotCountItsOwnPostsAsUnread() = runTest {
        remote.mineAnswer = AppResult.Success(MySpacesPage(listOf(MySpaceDto(SpaceSamples.summary("s1"), unreadCount = 0))))
        val repo = repository()
        repo.refreshMine()
        repo.setOpen("s1")
        val post = json.encodeToJsonElement(com.circaso.feature.spaces.data.remote.PostDto.serializer(), SpaceSamples.post("p1", "hi"))
        repo.onFrame("space.post_new", buildJsonObject { put("spaceId", "s1"); put("post", post) })
        assertEquals(0, repo.mySpaces.first().single().unreadCount)
    }

    @Test fun aConfirmedMembershipPostsItsReceiptOnceAndSaysSo() = runTest {
        remote.spaceAnswer = { AppResult.Success(SpaceSamples.space(visibility = "MEMBERS_ONLY", price = SpaceSamples.books, me = MeDto("MEMBER", "MEMBER", membership = MembershipDto("ACTIVE", "MONTHLY")))) }
        val repo = repository()
        repo.rememberMembershipPayment("s1", "t1", "5000000", "BASE-SEPOLIA", "Membership · monthly")
        val events = mutableListOf<SpaceEvent>()
        val job = launch { repo.events.collect { events += it } }
        testScheduler.runCurrent()
        repo.onFrame("space.membership_updated", buildJsonObject { put("spaceId", "s1"); put("spaceName", "Nairobi Book Club"); put("reason", "JOINED"); put("until", "2026-12-04T00:00:00Z") })
        repo.onFrame("space.membership_updated", buildJsonObject { put("spaceId", "s1"); put("reason", "JOINED") })
        testScheduler.runCurrent()
        assertEquals(1, receipts)
        val first = events.first() as SpaceEvent.Membership
        assertEquals("JOINED", first.reason)
        assertEquals("Nairobi Book Club", first.spaceName)
        assertEquals(MembershipState.Active, repo.space("s1").first()!!.me.membership?.state)
        job.cancel()
    }

    @Test fun invitesComeAndGoWithTheirFrames() = runTest {
        remote.myInvitesAnswer = AppResult.Success(ReceivedInvitesDto(listOf(
            ReceivedInviteDto("i1", SpaceSamples.summary("s3", "Nairobi Founders Circle", "founderscircle", "INVITE_ONLY", members = 48), PersonDto("wanjiru", "Wanjiru Kamau"), "2026-11-02T09:00:00Z", "2099-11-16T09:00:00Z", KnownDto(listOf("Wanjiru"), 1)),
        )))
        val repo = repository()
        repo.onFrame("space.invited", buildJsonObject { put("inviteId", "i1"); put("spaceId", "s3"); put("spaceName", "Nairobi Founders Circle"); put("inviterId", "wanjiru"); put("inviterName", "Wanjiru") })
        val invite = repo.invites.first().single()
        assertEquals("Nairobi Founders Circle", invite.space.name)
        assertEquals(Known(listOf("Wanjiru"), 1), invite.known)
        repo.onFrame("space.invite_removed", buildJsonObject { put("inviteId", "i1"); put("spaceId", "s3") })
        assertTrue(repo.invites.first().isEmpty())
    }

    @Test fun aDeclineIsSentAndTheCardGoes() = runTest {
        remote.myInvitesAnswer = AppResult.Success(ReceivedInvitesDto(listOf(
            ReceivedInviteDto("i1", SpaceSamples.summary("s3", visibility = "INVITE_ONLY"), PersonDto("w", "Wanjiru"), "2026-11-02T09:00:00Z", "2099-11-16T09:00:00Z"),
        )))
        val repo = repository()
        repo.refreshInvites()
        assertTrue(repo.declineInvite("i1") is AppResult.Success)
        assertTrue(repo.invites.first().isEmpty())
        assertTrue("decline:i1" in remote.calls)
    }

    @Test fun createKeepsTheCallersKeySoTryAgainCantMakeTwo() = runTest {
        remote.createAnswer = AppResult.Failure(AppError.Network)
        val repo = repository()
        val draft = com.circaso.feature.spaces.domain.NewSpace("Kilimani Yoga Collective", "@KilimaniYoga", "Sunrise yoga", com.circaso.feature.spaces.domain.SpaceTopic.Health,
            visibility = com.circaso.feature.spaces.domain.SpaceVisibility.MembersOnly, price = com.circaso.feature.spaces.domain.SpacePrice("10000000", com.circaso.feature.spaces.domain.PricePeriod.Monthly))
        repo.create(draft, "key-1")
        remote.createAnswer = AppResult.Success(SpaceSamples.space("s9", "Kilimani Yoga Collective", "kilimaniyoga", "MEMBERS_ONLY", state = "SETTING_UP", me = MeDto("MEMBER", "OWNER")))
        val made = repo.create(draft, "key-1")
        assertEquals(listOf("key-1", "key-1"), remote.createKeys)
        assertEquals("create:kilimaniyoga", remote.calls.first { it.startsWith("create") })
        assertEquals(com.circaso.feature.spaces.domain.SpaceState.SettingUp, (made as AppResult.Success).value.state)
    }

    @Test fun aServerWithoutSpacesTurnsTheFlagOffAndAnyOtherAnswerKeepsItOn() {
        val f = SpacesFlag(true)
        assertTrue(f.isOn)
        assertEquals(SpacesFlag.Server.Unknown, f.serverState)
        f.onServerAnswered()
        assertEquals(SpacesFlag.Server.Supported, f.serverState)
        f.onServerMissing()
        assertFalse(f.isOn)
        assertFalse(f.enabled.value)
        // Release builds start off and stay off whatever the server says.
        val release = SpacesFlag(false)
        release.onServerAnswered()
        assertFalse(release.isOn)
    }

    @Test fun handlesAreCheckedWithoutTheirAt() = runTest {
        val repo = repository()
        repo.checkHandle("@KilimaniYoga")
        assertEquals("handle:kilimaniyoga", remote.calls.single())
    }
}
