package com.circaso.feature.messaging

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.circasoJson
import com.circaso.core.notifications.NotificationDestination
import com.circaso.core.notifications.NotificationEvent
import com.circaso.core.notifications.NotificationText
import com.circaso.feature.messaging.data.keys.KeysRepositoryImpl
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import com.circaso.feature.messaging.data.local.MessageKind
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.remote.ChatDto
import com.circaso.feature.messaging.data.remote.ChatInvitesResponse
import com.circaso.feature.messaging.data.remote.ChatMemberDto
import com.circaso.feature.messaging.data.remote.ChatProfileDto
import com.circaso.feature.messaging.data.remote.CreateGroupRequest
import com.circaso.feature.messaging.data.remote.InviteCancelledResponse
import com.circaso.feature.messaging.data.remote.InviteDeclinedResponse
import com.circaso.feature.messaging.data.remote.InviteDto
import com.circaso.feature.messaging.data.remote.InvitesRemote
import com.circaso.feature.messaging.data.remote.InvitesResponse
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.remote.PendingInviteDto
import com.circaso.feature.messaging.data.repository.ChatsRepositoryImpl
import com.circaso.feature.messaging.data.repository.GroupInvitesRepositoryImpl
import com.circaso.feature.messaging.data.repository.GroupKeys
import com.circaso.feature.messaging.data.repository.InviteCache
import com.circaso.feature.messaging.data.repository.SessionCipher
import com.circaso.feature.messaging.data.repository.isoToMillis
import com.circaso.feature.messaging.domain.GroupDraft
import com.circaso.feature.messaging.domain.GroupInvite
import com.circaso.feature.messaging.domain.InvitePerson
import com.circaso.feature.messaging.presentation.addedNote
import com.circaso.feature.messaging.presentation.inviteErrorText
import com.circaso.feature.messaging.presentation.inviteExpiry
import com.circaso.feature.messaging.presentation.inviteLine
import com.circaso.feature.messaging.presentation.inviteTitle
import com.circaso.testing.FakeChatsDao
import com.circaso.testing.FakeChatsRemote
import com.circaso.testing.FakeKeys
import com.circaso.testing.FakeKeysRemote
import com.circaso.testing.FakeMessagesDao
import com.circaso.testing.FakeMessagesRemote
import com.circaso.testing.FakeSealer
import com.circaso.testing.FakeSignalDao
import com.circaso.testing.runSuspending
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group invites (Pasaka, 2026-11-09: "They should see an inbox and into the group invite and choose to decline or
 * accept. If they decline, the invite fails. If they accept, they now join the group officially."). The group's name
 * stays end-to-end encrypted: the inviter's phone seals the group's key for each person, so the invite names the group
 * on the invitee's phone and nowhere else.
 */
class GroupInvitesTest {
    private val json = circasoJson()
    private val amina = "amina"
    private val brian = "brian"
    private var clock = isoToMillis("2026-11-09T09:00:00Z")!!
    private val crypto = KeyedCrypto()

    private class FakeInvitesRemote : InvitesRemote {
        var invites: List<InviteDto> = emptyList()
        var acceptResult: (String) -> AppResult<ChatDto> = { AppResult.Failure(AppError.Network) }
        var declineResult: AppResult<InviteDeclinedResponse> = AppResult.Success(InviteDeclinedResponse())
        var pending: List<PendingInviteDto> = emptyList()
        val cancelled = mutableListOf<Pair<String, String>>()
        override suspend fun mine() = AppResult.Success(InvitesResponse(invites))
        override suspend fun accept(inviteId: String) = acceptResult(inviteId)
        override suspend fun decline(inviteId: String) = declineResult
        override suspend fun ofChat(chatId: String) = AppResult.Success(ChatInvitesResponse(pending))
        override suspend fun cancel(chatId: String, userId: String): AppResult<InviteCancelledResponse> { cancelled += chatId to userId; return AppResult.Success(InviteCancelledResponse()) }
    }

    private class MemoryCache(var value: String? = null) : InviteCache {
        override suspend fun read() = value
        override suspend fun write(value: String?) { this.value = value }
    }

    private inner class Phone(val userId: String) {
        val chatsDao = FakeChatsDao()
        val messagesDao = FakeMessagesDao()
        val store = SealedRecordStore(FakeSignalDao(), FakeSealer())
        val keys = KeysRepositoryImpl(store, FakeKeysRemote(), crypto, SignedKeyUpload(FakeKeys(), crypto), json)
        val sessions = SessionCipher(store, crypto, keys)
        val groupKeys = GroupKeys(store, crypto, chatsDao, sessions, FakeMessagesRemote(), json, { userId }, now = { clock })
        val remote = FakeChatsRemote()
        val chats = ChatsRepositoryImpl(chatsDao, messagesDao, remote, groupKeys, sessions, { userId }, now = { clock })
        val invitesRemote = FakeInvitesRemote()
        val cache = MemoryCache()
        val invites = GroupInvitesRepositoryImpl(invitesRemote, chats, chatsDao, groupKeys, cache, json, now = { clock })

        fun systemLines() = messagesDao.rows.value.filter { it.kind == MessageKind.System }.map { it.text }
    }

    private fun member(id: String, name: String, role: String = MessagingWire.Member) =
        ChatMemberDto(ChatProfileDto(id, name), role, "2026-11-09T09:00:00Z")

    private fun group(sealed: String, members: List<ChatMemberDto>, invited: List<String>? = null) =
        ChatDto("g1", MessagingWire.Group, sealed, 1, members, version = 1, invited = invited)

    /** Amina makes "Book club" with Brian asked for: the server answers with her alone, and Brian invited. */
    private suspend fun aminaCreates(aminas: Phone): CreateGroupRequest {
        var sent: CreateGroupRequest? = null
        aminas.remote.createGroupResult = { request, _ ->
            sent = request
            AppResult.Success(group(request.sealedMeta, listOf(member(amina, "Amina Otieno", MessagingWire.Owner)), invited = listOf(brian)))
        }
        val created = aminas.chats.createGroup(GroupDraft("Book club", listOf(brian)), "key-1") as AppResult.Success
        assertEquals(listOf(brian), created.value.invited)
        assertEquals(listOf(amina), created.value.chat.members.map { it.userId }, "Brian isn't in it until he accepts")
        return sent!!
    }

    private fun inviteFor(request: CreateGroupRequest, sealedKey: String? = request.inviteKeys?.single()?.sealedKey, expiresAt: String = "2026-12-09T09:00:00Z") =
        InviteDto(
            "inv-1", "g1", ChatProfileDto(amina, "Amina Otieno"), request.sealedMeta, 1, null, memberCount = 1,
            sealedKey = sealedKey, createdAt = "2026-11-09T09:00:00Z", expiresAt = expiresAt,
        )

    // ---- the inviter's side

    @Test fun creatingAGroupSealsItsKeyForEachPersonInvited() = runSuspending {
        val request = aminaCreates(Phone(amina))
        val key = request.inviteKeys?.single()
        assertNotNull(key)
        assertEquals(brian, key.userId)
        assertTrue(key.sealedKey.isNotBlank(), "a 1:1 copy, never the key in the clear")
    }

    @Test fun addingPeopleInvitesThemWithTheGroupsCurrentKey() = runSuspending {
        val aminas = Phone(amina)
        val request = aminaCreates(aminas)
        var sentKeys: List<String>? = null
        aminas.remote.addMembersResult = { _, req ->
            sentKeys = req.inviteKeys?.map { it.userId }
            AppResult.Success(group(request.sealedMeta, listOf(member(amina, "Amina Otieno", MessagingWire.Owner)), invited = listOf("chidi")))
        }
        val added = aminas.chats.addMembers("g1", listOf("chidi")) as AppResult.Success
        assertEquals(listOf("chidi"), added.value.invited)
        assertEquals(listOf("chidi"), sentKeys)
    }

    // ---- the invitee's side

    @Test fun anInviteNamesTheGroupFromTheKeySealedForMe() = runSuspending {
        val request = aminaCreates(Phone(amina))
        val brians = Phone(brian)
        brians.invitesRemote.invites = listOf(inviteFor(request))

        brians.invites.refresh()

        val invite = brians.invites.invites.first().single()
        assertEquals("Book club", invite.groupName)
        assertEquals("Book club", inviteTitle(invite))
        assertEquals("Amina invited you · 1 member", inviteLine(invite))
        assertEquals(1, invite.memberCount)
        assertNull(brians.chatsDao.chat("g1"), "not a chat on my phone: I'm not in it")
    }

    @Test fun anInviteWithoutAKeySaysAGroup() = runSuspending {
        val request = aminaCreates(Phone(amina))
        val brians = Phone(brian)
        brians.invitesRemote.invites = listOf(inviteFor(request, sealedKey = null))

        brians.invites.refresh()

        val invite = brians.invites.invites.first().single()
        assertNull(invite.groupName)
        assertEquals("A group", inviteTitle(invite))
    }

    @Test fun acceptingStoresTheGroupAndTakesTheInviteOff() = runSuspending {
        val request = aminaCreates(Phone(amina))
        val brians = Phone(brian)
        brians.invitesRemote.invites = listOf(inviteFor(request))
        brians.invites.refresh()
        val joined = group(request.sealedMeta, listOf(member(amina, "Amina Otieno", MessagingWire.Owner), member(brian, "Brian Kip")))
        brians.invitesRemote.acceptResult = { AppResult.Success(joined) }
        brians.remote.chats = listOf(joined)

        val chat = brians.invites.accept("inv-1") as AppResult.Success

        assertEquals("Book club", chat.value.title, "the invite's key opens the group's name at once")
        assertEquals(setOf(amina, brian), chat.value.members.map { it.userId }.toSet())
        assertTrue(brians.invites.invites.first().isEmpty())
        assertNull(brians.cache.value, "nothing left to show offline")
    }

    @Test fun decliningTakesTheInviteOffAndForgetsTheKeyItGave() = runSuspending {
        val request = aminaCreates(Phone(amina))
        val brians = Phone(brian)
        brians.invitesRemote.invites = listOf(inviteFor(request))
        brians.invites.refresh()
        assertNotNull(brians.chatsDao.latestMetaKey("g1"))

        assertTrue(brians.invites.decline("inv-1") is AppResult.Success)

        assertTrue(brians.invites.invites.first().isEmpty())
        assertNull(brians.chatsDao.latestMetaKey("g1"), "the group's name is no longer readable here")
    }

    @Test fun anInviteNoLongerOpenLeavesTheListAndSaysSo() = runSuspending {
        val request = aminaCreates(Phone(amina))
        val brians = Phone(brian)
        brians.invitesRemote.invites = listOf(inviteFor(request))
        brians.invites.refresh()
        val gone = AppError.Api("messaging.invite_not_found", 404, null, null, emptyMap())
        brians.invitesRemote.acceptResult = { AppResult.Failure(gone) }

        assertTrue(brians.invites.accept("inv-1") is AppResult.Failure)

        assertTrue(brians.invites.invites.first().isEmpty())
        assertEquals("This invite is no longer open.", inviteErrorText(gone))
    }

    @Test fun invitesShowOfflineFromThisPhoneButNotOnceTheyRunOut() = runSuspending {
        val request = aminaCreates(Phone(amina))
        val brians = Phone(brian)
        val open = inviteFor(request)
        val runOut = open.copy(inviteId = "inv-2", chatId = "g2", expiresAt = "2026-11-08T09:00:00Z")
        brians.cache.value = json.encodeToString(InvitesResponse.serializer(), InvitesResponse(listOf(open, runOut)))

        val shown = brians.invites.invites.first()

        assertEquals(listOf("inv-1"), shown.map { it.inviteId })
    }

    @Test fun aCancelledInviteLeavesMyList() = runSuspending {
        val request = aminaCreates(Phone(amina))
        val brians = Phone(brian)
        brians.invitesRemote.invites = listOf(inviteFor(request))
        brians.invites.refresh()

        brians.invites.onInviteRemoved("inv-1")

        assertTrue(brians.invites.invites.first().isEmpty())
    }

    @Test fun groupInfoListsTheOpenInvitesAndCancelsThem() = runSuspending {
        val aminas = Phone(amina)
        aminas.invitesRemote.pending = listOf(PendingInviteDto("inv-1", ChatProfileDto(brian, "Brian Kip"), ChatProfileDto(amina, "Amina Otieno")))

        val pending = aminas.invites.pending("g1") as AppResult.Success
        assertEquals("Brian Kip", pending.value.single().person.displayName)

        assertTrue(aminas.invites.cancel("g1", brian) is AppResult.Success)
        assertEquals(listOf("g1" to brian), aminas.invitesRemote.cancelled)
    }

    // ---- joining: the group says who joined

    @Test fun aJoinSaysWhoJoinedOnEveryPhone() = runSuspending {
        val request = aminaCreates(Phone(amina))
        val joined = group(request.sealedMeta, listOf(member(amina, "Amina Otieno", MessagingWire.Owner), member(brian, "Brian Kip")))

        val aminas = Phone(amina)
        aminas.chats.store(group(request.sealedMeta, listOf(member(amina, "Amina Otieno", MessagingWire.Owner))))
        aminas.remote.chats = listOf(joined)
        aminas.chats.onMemberAdded("g1", listOf(brian), joined = true)
        assertEquals(listOf("Brian Kip joined"), aminas.systemLines())

        val brians = Phone(brian)
        brians.remote.chats = listOf(joined)
        brians.chats.onMemberAdded("g1", listOf(brian), joined = true)
        assertEquals(listOf("You joined"), brians.systemLines())
    }

    @Test fun anAddWithoutJoinedSaysNothing() = runSuspending {
        val request = aminaCreates(Phone(amina))
        val aminas = Phone(amina)
        aminas.chats.store(group(request.sealedMeta, listOf(member(amina, "Amina Otieno", MessagingWire.Owner))))
        aminas.remote.chats = listOf(group(request.sealedMeta, listOf(member(amina, "Amina Otieno", MessagingWire.Owner), member(brian, "Brian Kip"))))
        aminas.chats.onMemberAdded("g1", listOf(brian))
        assertTrue(aminas.systemLines().isEmpty())
    }

    // ---- words

    @Test fun anInviteWarnsOnlyInItsLastWeek() {
        val day = 24L * 60 * 60 * 1000
        fun invite(expires: Long?) = GroupInvite("i", "g", "Book club", null, 3, InvitePerson(amina, "Amina", null), clock, expires)
        assertNull(inviteExpiry(invite(clock + 10 * day), clock))
        assertEquals("Expires in 3 days", inviteExpiry(invite(clock + 3 * day + 1), clock))
        assertEquals("Expires tomorrow", inviteExpiry(invite(clock + day + 1), clock))
        assertEquals("Expires today", inviteExpiry(invite(clock + 60_000), clock))
        assertNull(inviteExpiry(invite(null), clock))
    }

    @Test fun anAddSaysWhoWasInvited() {
        assertEquals("Invited 2 people. They'll join when they accept.", addedNote(2, 0))
        assertEquals("Invited 1 person. They'll join when they accept. 1 person couldn't be invited.", addedNote(1, 1))
        assertNull(addedNote(0, 0))
        assertEquals(
            "This group is full. Ask an admin to make room, then try again.",
            inviteErrorText(AppError.Api("messaging.group_full", 409, null, null, emptyMap())),
        )
    }

    @Test fun theInvitePushNamesTheInviterAndTheGroupUnlessPrivate() {
        val event = NotificationEvent.GroupInvite("Amina", "Book club")
        val shown = NotificationText.compose(event, private = false, fiat = null)
        assertEquals("Amina invited you to Book club", shown.text)
        assertEquals(NotificationText.GroupInviteTitle, shown.title)
        assertEquals("Someone invited you to a group", NotificationText.compose(NotificationEvent.GroupInvite(null, null), false, null).text)
        val private = NotificationText.compose(event, private = true, fiat = null)
        assertEquals(NotificationText.PrivateGroupInvite, private.text)
        assertTrue("Book club" !in private.title + private.text && "Amina" !in private.title + private.text)
        assertEquals(NotificationDestination.Invites, NotificationDestination.fromPush(mapOf("kind" to "chat.invite", "chatId" to "g1", "userId" to amina)))
    }
}
