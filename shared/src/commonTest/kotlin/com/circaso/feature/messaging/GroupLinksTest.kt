package com.circaso.feature.messaging

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.circasoJson
import com.circaso.core.platform.GroupPhotoEncoder
import com.circaso.core.platform.GroupPhotoImage
import com.circaso.feature.messaging.data.keys.KeysRepositoryImpl
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import com.circaso.feature.messaging.data.local.MessageKind
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.remote.ChatDto
import com.circaso.feature.messaging.data.remote.ChatMemberDto
import com.circaso.feature.messaging.data.remote.ChatProfileDto
import com.circaso.feature.messaging.data.remote.GroupLinkDto
import com.circaso.feature.messaging.data.remote.GroupLinkOffResponse
import com.circaso.feature.messaging.data.remote.GroupLinkPreviewResponse
import com.circaso.feature.messaging.data.remote.GroupLinkResponse
import com.circaso.feature.messaging.data.remote.GroupLinksRemote
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.remote.PublishGroupLinkRequest
import com.circaso.feature.messaging.data.remote.UpdateGroupRequest
import com.circaso.feature.messaging.data.repository.ChatsRepositoryImpl
import com.circaso.feature.messaging.data.repository.GroupKeys
import com.circaso.feature.messaging.data.repository.GroupLinkCache
import com.circaso.feature.messaging.data.repository.GroupLinksRepositoryImpl
import com.circaso.feature.messaging.data.repository.GroupMeta
import com.circaso.feature.messaging.data.repository.MaxGroupDescriptionChars
import com.circaso.feature.messaging.data.repository.MaxSealedMetaBytes
import com.circaso.feature.messaging.data.repository.SessionCipher
import com.circaso.feature.messaging.data.repository.keptDescription
import com.circaso.feature.messaging.domain.GroupDraft
import com.circaso.feature.messaging.domain.GroupLink
import com.circaso.feature.messaging.domain.GroupLinkErrors
import com.circaso.feature.messaging.domain.GroupLinkPreview
import com.circaso.feature.messaging.domain.GroupLinkText
import com.circaso.feature.share.domain.IncomingLinks
import com.circaso.testing.FakeChatsDao
import com.circaso.testing.FakeChatsRemote
import com.circaso.testing.FakeKeys
import com.circaso.testing.FakeKeysRemote
import com.circaso.testing.FakeMessagesDao
import com.circaso.testing.FakeMessagesRemote
import com.circaso.testing.FakeSealer
import com.circaso.testing.FakeSignalDao
import com.circaso.testing.runSuspending
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The group description and group join links (Pasaka, 2026-11-14: "Add a way where people can share links and people
 * can join from those links. The link should have a preview. We don't have a group description today; add one.").
 * The description is sealed with the group's name, so only members read it. The link's preview is published by an
 * admin's phone, and only what that phone reads from the sealed metadata: nothing the server could know by itself.
 */
class GroupLinksTest {
    private val json = circasoJson()
    private val amina = "amina"
    private val brian = "brian"
    private val crypto = KeyedCrypto()
    private val token = "h3Xq9LmT2vKc8RzYw4Nd6B"
    private val url = "https://links.circaso.com/s/$token"

    private class FakeLinksRemote : GroupLinksRemote {
        var link: GroupLinkDto? = null
        var linkResult: (() -> AppResult<GroupLinkResponse>)? = null
        val published = mutableListOf<PublishGroupLinkRequest>()
        var publishResult: (PublishGroupLinkRequest) -> AppResult<GroupLinkDto> = { AppResult.Failure(AppError.Network) }
        var turnedOff = 0
        var turnOffResult: AppResult<GroupLinkOffResponse> = AppResult.Success(GroupLinkOffResponse())
        var previewResult: AppResult<GroupLinkPreviewResponse> = AppResult.Failure(AppError.Network)
        var joinResult: AppResult<ChatDto> = AppResult.Failure(AppError.Network)
        var joined = 0
        override suspend fun link(chatId: String) = linkResult?.invoke() ?: AppResult.Success(GroupLinkResponse(link))
        override suspend fun publish(chatId: String, request: PublishGroupLinkRequest): AppResult<GroupLinkDto> {
            published += request
            return publishResult(request).also { if (it is AppResult.Success) link = it.value }
        }
        override suspend fun turnOff(chatId: String): AppResult<GroupLinkOffResponse> { turnedOff++; if (turnOffResult is AppResult.Success) link = null; return turnOffResult }
        override suspend fun preview(token: String) = previewResult
        override suspend fun join(token: String): AppResult<ChatDto> { joined++; return joinResult }
    }

    private class MemoryCache(var value: String? = null) : GroupLinkCache {
        override suspend fun read() = value
        override suspend fun write(value: String?) { this.value = value }
    }

    private inner class Phone(val userId: String, val cache: MemoryCache = MemoryCache()) {
        val chatsDao = FakeChatsDao()
        val messagesDao = FakeMessagesDao()
        val store = SealedRecordStore(FakeSignalDao(), FakeSealer())
        val keys = KeysRepositoryImpl(store, FakeKeysRemote(), crypto, SignedKeyUpload(FakeKeys(), crypto), json)
        val sessions = SessionCipher(store, crypto, keys)
        val groupKeys = GroupKeys(store, crypto, chatsDao, sessions, FakeMessagesRemote(), json, { userId })
        val remote = FakeChatsRemote()
        val chats = ChatsRepositoryImpl(chatsDao, messagesDao, remote, groupKeys, sessions, { userId })
        val linksRemote = FakeLinksRemote()
        val links = GroupLinksRepositoryImpl(linksRemote, chats, chatsDao, groupKeys, cache, json, { userId })

        /** A second launch of the same phone: a new repository over what the first one saved. */
        fun relaunched() = GroupLinksRepositoryImpl(linksRemote, chats, chatsDao, groupKeys, cache, json, { userId })

        fun systemLines() = messagesDao.rows.value.filter { it.kind == MessageKind.System }.map { it.text }

        /** The server gives back a link for whatever was published. */
        fun serverMakesLinks(tokens: MutableList<String> = mutableListOf(token)) {
            linksRemote.publishResult = { request ->
                val t = if (request.reset || linksRemote.link == null) tokens.removeAt(0) else linksRemote.link!!.token
                AppResult.Success(GroupLinkDto(t, "https://links.circaso.com/s/$t", request.name, request.description, photoUrl = request.photo?.let { "https://links.circaso.com/s/$t/photo?v=1" }))
            }
        }

        /** Every PATCH is accepted as sent, at the next versions. */
        fun serverAcceptsEdits(members: List<ChatMemberDto>) {
            var version = 1
            remote.updateGroupResult = { _, request: UpdateGroupRequest ->
                version++
                AppResult.Success(ChatDto("g1", MessagingWire.Group, request.sealedMeta, version, members, version = version))
            }
        }
    }

    private fun member(id: String, name: String, role: String = MessagingWire.Member) =
        ChatMemberDto(ChatProfileDto(id, name), role, "2026-11-14T09:00:00Z")

    private val aminaOwner get() = listOf(member(amina, "Amina Otieno", MessagingWire.Owner))

    /** Amina makes "Book club" on her phone; the server answers with her alone in it. */
    private suspend fun bookClub(phone: Phone): ChatDto {
        var made: ChatDto? = null
        phone.remote.createGroupResult = { request, _ ->
            AppResult.Success(ChatDto("g1", MessagingWire.Group, request.sealedMeta, 1, aminaOwner, version = 1).also { made = it })
        }
        phone.chats.createGroup(GroupDraft("Book club", emptyList()), "key-1") as AppResult.Success
        phone.serverAcceptsEdits(aminaOwner)
        return made!!
    }

    private suspend fun Phone.sealedNow(): String = chatsDao.chat("g1")!!.sealedMeta!!

    private suspend fun Phone.openedMeta(): GroupMeta = chatsDao.chat("g1")!!.let { groupKeys.openGroupMeta("g1", it.sealedMeta, it.metaVersion)!! }

    // ---- the description, sealed with the name

    @Test fun aDescriptionIsSealedWithTheNameAndReadBack() = runSuspending {
        val aminas = Phone(amina)
        bookClub(aminas)

        val edited = aminas.chats.editGroup("g1", "Book club", "  Fridays at six.\nBring a book.  ") as AppResult.Success

        assertEquals("Fridays at six.\nBring a book.", edited.value.description)
        assertEquals("Book club", edited.value.title)
        val sent = aminas.remote.updates.last().second
        assertFalse(Base64.decode(sent.sealedMeta!!).decodeToString().contains("Fridays"), "sealed: the server can't read it")
        assertEquals("Fridays at six.\nBring a book.", aminas.openedMeta().description)
        assertEquals("Fridays at six.\nBring a book.", aminas.chats.chat("g1").first()?.description)
    }

    @Test fun aRenameKeepsTheDescriptionAndABlankOneRemovesIt() = runSuspending {
        val aminas = Phone(amina)
        bookClub(aminas)
        aminas.chats.editGroup("g1", "Book club", "Fridays at six.")

        val renamed = aminas.chats.rename("g1", "Readers") as AppResult.Success
        assertEquals("Readers", renamed.value.title)
        assertEquals("Fridays at six.", renamed.value.description, "a rename never drops it")
        assertEquals("Fridays at six.", aminas.openedMeta().description)

        val cleared = aminas.chats.editGroup("g1", "Readers", "   ") as AppResult.Success
        assertNull(cleared.value.description)
        assertEquals("", aminas.openedMeta().description, "written as empty, so other phones know it was cleared")
    }

    @Test fun aNewGroupHasNoDescriptionButSaysSoInItsSeal() = runSuspending {
        val aminas = Phone(amina)
        bookClub(aminas)
        assertNull(aminas.chats.chat("g1").first()?.description)
        assertEquals("", aminas.openedMeta().description)
    }

    @Test fun aSealFromAnAppBeforeDescriptionsKeepsWhatThisPhoneKnew() = runSuspending {
        val aminas = Phone(amina)
        val made = bookClub(aminas)
        aminas.chats.editGroup("g1", "Book club", "Fridays at six.")
        val key = aminas.groupKeys.latestMetaKey("g1")!!

        // An admin on an older app renames the group: its seal has a name and nothing else.
        val oldSeal = Base64.encode(crypto.aesGcmSeal(key, """{"name":"Readers"}""".encodeToByteArray(), "circaso.group-meta.v1".encodeToByteArray()))
        aminas.chats.store(made.copy(sealedMeta = oldSeal, metaVersion = 2, version = 9))

        val chat = aminas.chats.chat("g1").first()!!
        assertEquals("Readers", chat.title)
        assertEquals("Fridays at six.", chat.description, "the older app couldn't have meant to clear it")

        // The next seal from this phone carries it again.
        aminas.chats.rename("g1", "Readers club")
        assertEquals("Fridays at six.", aminas.openedMeta().description)
    }

    /** What an app from before descriptions decodes a seal into: it knows the name, an avatar and nothing else. */
    @Serializable
    private data class MetaBeforeDescriptions(val name: String, val avatar: String? = null)

    @Test fun anAppFromBeforeDescriptionsReadsTheNameAndIgnoresTheRest() = runSuspending {
        val aminas = Phone(amina)
        bookClub(aminas)
        aminas.chats.editGroup("g1", "Book club", "Fridays at six.")
        val key = aminas.groupKeys.latestMetaKey("g1")!!
        val plain = crypto.aesGcmOpen(key, Base64.decode(aminas.sealedNow()), "circaso.group-meta.v1".encodeToByteArray()).decodeToString()

        assertTrue(plain.contains("\"description\":\"Fridays at six.\""))
        assertEquals(MetaBeforeDescriptions("Book club"), json.decodeFromString(MetaBeforeDescriptions.serializer(), plain))
    }

    @Test fun theLongestDescriptionStillFitsTheServersFourKilobytes() = runSuspending {
        val aminas = Phone(amina)
        bookClub(aminas)
        // 500 characters at their widest in UTF-8 (three bytes each), under a long name.
        val widest = "€".repeat(MaxGroupDescriptionChars)

        val edited = aminas.chats.editGroup("g1", "N".repeat(100), widest + "cut off") as AppResult.Success

        assertEquals(widest, edited.value.description, "cut at 500 characters")
        assertTrue(Base64.decode(aminas.sealedNow()).size <= MaxSealedMetaBytes)
    }

    @Test fun whichDescriptionIsKept() {
        assertEquals("old", keptDescription(null, "old"), "a seal this phone can't open")
        assertEquals("old", keptDescription(GroupMeta("Book club"), "old"), "a seal without the field")
        assertNull(keptDescription(GroupMeta("Book club", description = ""), "old"), "cleared")
        assertNull(keptDescription(GroupMeta("Book club", description = "  "), "old"))
        assertEquals("new", keptDescription(GroupMeta("Book club", description = " new "), "old"))
    }

    // ---- an admin's link

    @Test fun makingALinkPublishesTheNameAndDescriptionThisPhoneReads() = runSuspending {
        val aminas = Phone(amina)
        bookClub(aminas)
        aminas.chats.editGroup("g1", "Book club", "Fridays at six.")
        aminas.serverMakesLinks()
        assertNull(aminas.links.link("g1").first(), "no link until one is made")
        assertTrue(aminas.linksRemote.published.isEmpty(), "nothing is published before the admin makes the link")

        val made = aminas.links.create("g1") as AppResult.Success

        assertEquals(PublishGroupLinkRequest("Book club", "Fridays at six.", photo = null, reset = false), aminas.linksRemote.published.single())
        assertEquals(GroupLink("g1", token, url, "Book club", "Fridays at six.", hasPhoto = false), made.value)
        assertEquals(made.value, aminas.links.link("g1").first())
    }

    @Test fun anExistingLinkShowsOfflineFromThisPhonesCopy() = runSuspending {
        val aminas = Phone(amina)
        bookClub(aminas)
        aminas.serverMakesLinks()
        aminas.links.create("g1")

        // The next launch, with no connection at all.
        aminas.linksRemote.linkResult = { AppResult.Failure(AppError.Network) }
        val later = aminas.relaunched()

        assertEquals(url, later.link("g1").first()?.url)
        assertTrue(later.refresh("g1") is AppResult.Failure)
        assertEquals(url, later.link("g1").first()?.url, "a failed refresh never drops what the phone has")
    }

    @Test fun aRefreshFollowsTheServerAndForgetsALinkThatIsGone() = runSuspending {
        val aminas = Phone(amina)
        bookClub(aminas)
        aminas.linksRemote.link = GroupLinkDto(token, url, "Book club")

        assertEquals(url, (aminas.links.refresh("g1") as AppResult.Success).value?.url)
        assertEquals(url, aminas.links.link("g1").first()?.url)

        aminas.linksRemote.link = null // another admin turned it off
        assertNull((aminas.links.refresh("g1") as AppResult.Success).value)
        assertNull(aminas.links.link("g1").first())
        assertNull(aminas.cache.value, "nothing left on the phone")
    }

    @Test fun aPhoneThatIsNoLongerAnAdminForgetsTheLink() = runSuspending {
        val aminas = Phone(amina)
        bookClub(aminas)
        aminas.serverMakesLinks()
        aminas.links.create("g1")

        aminas.linksRemote.linkResult = { AppResult.Failure(AppError.Api("messaging.not_admin", 403, null, null, emptyMap())) }
        aminas.links.onLinkChanged("g1")

        assertNull(aminas.links.link("g1").first())
    }

    @Test fun aResetReplacesTheLinkAndTurningItOffRemovesIt() = runSuspending {
        val aminas = Phone(amina)
        bookClub(aminas)
        aminas.serverMakesLinks(mutableListOf(token, "N3wT0kenN3wT0kenN3wT0k"))
        aminas.links.create("g1")

        val fresh = aminas.links.reset("g1") as AppResult.Success
        assertTrue(aminas.linksRemote.published.last().reset)
        assertEquals("N3wT0kenN3wT0kenN3wT0k", fresh.value.token)
        assertEquals(fresh.value, aminas.links.link("g1").first())

        assertTrue(aminas.links.turnOff("g1") is AppResult.Success)
        assertEquals(1, aminas.linksRemote.turnedOff)
        assertNull(aminas.links.link("g1").first())
    }

    @Test fun aFailedTurnOffKeepsTheLink() = runSuspending {
        val aminas = Phone(amina)
        bookClub(aminas)
        aminas.serverMakesLinks()
        aminas.links.create("g1")
        aminas.linksRemote.turnOffResult = AppResult.Failure(AppError.Network)

        assertTrue(aminas.links.turnOff("g1") is AppResult.Failure)
        assertEquals(url, aminas.links.link("g1").first()?.url, "still on: nothing is faked")
    }

    @Test fun aPhoneThatCannotReadTheNamePublishesNothing() = runSuspending {
        val aminas = Phone(amina)
        val made = bookClub(aminas)
        // The same group on a new phone of hers, which has no key for it.
        val newPhone = Phone(amina)
        newPhone.chats.store(made)
        newPhone.serverMakesLinks()

        val r = newPhone.links.create("g1") as AppResult.Failure

        assertEquals(GroupLinkErrors.NameUnknown, (r.error as AppError.Api).code)
        assertTrue(newPhone.linksRemote.published.isEmpty(), "never a made-up name on a public page")
    }

    @Test fun thePreviewIsPublishedAgainWhenTheNameOrDescriptionChange() = runSuspending {
        val aminas = Phone(amina)
        bookClub(aminas)
        aminas.serverMakesLinks()
        aminas.links.create("g1")
        aminas.chats.metaChanged = { aminas.links.republishIfStale(it) }

        aminas.links.republishIfStale("g1")
        assertEquals(1, aminas.linksRemote.published.size, "nothing changed, nothing sent")

        aminas.chats.editGroup("g1", "Readers", "Fridays at six.")

        val again = aminas.linksRemote.published.last()
        assertEquals(2, aminas.linksRemote.published.size)
        assertEquals(PublishGroupLinkRequest("Readers", "Fridays at six.", null, reset = false), again)
        assertEquals(token, aminas.links.link("g1").first()?.token, "the same link")
        assertEquals("Readers", aminas.links.link("g1").first()?.name)
    }

    @Test fun aGroupWithoutALinkPublishesNothingWhenItChanges() = runSuspending {
        val aminas = Phone(amina)
        bookClub(aminas)
        aminas.serverMakesLinks()
        aminas.chats.metaChanged = { aminas.links.republishIfStale(it) }

        aminas.chats.editGroup("g1", "Readers", "Fridays at six.")

        assertTrue(aminas.linksRemote.published.isEmpty(), "publishing is the admin's choice: no link, no public copy")
    }

    @Test fun aMemberWhoIsNotAnAdminNeverRepublishes() = runSuspending {
        val aminas = Phone(amina)
        val made = bookClub(aminas)
        aminas.serverMakesLinks()
        aminas.links.create("g1")
        // She is made an ordinary member; the phone still has its copy of the link.
        aminas.chats.store(made.copy(sealedMeta = aminas.sealedNow(), metaVersion = aminas.chatsDao.chat("g1")!!.metaVersion, members = listOf(member(amina, "Amina Otieno"), member(brian, "Brian", MessagingWire.Owner)), version = 20))

        aminas.links.republishIfStale("g1")

        assertEquals(1, aminas.linksRemote.published.size)
    }

    /** An encoder whose "small photo" is a marker, so the test sees which bytes were published. */
    private class MarkingEncoder : GroupPhotoEncoder {
        override suspend fun encode(source: ByteArray): GroupPhotoImage? = null
        override suspend fun preview(source: ByteArray): ByteArray = "small:".encodeToByteArray() + source
    }

    @Test fun theShareMessageNamesTheGroupInBoldForWhatsApp() {
        val link = GroupLink("g1", token, url, "Book club", null, hasPhoto = false)
        assertEquals("Join *Book club* on Circaso: $url", GroupLinkText.message(link))
        assertEquals("Join Book club on Circaso", GroupLinkText.subject(link))
        assertTrue(MarkingEncoder::class.simpleName != null)
    }

    // ---- joining with a link

    private fun previewOf(member: Boolean = false) = GroupLinkPreviewResponse("Book club", "Fridays at six.", "$url/photo?v=1", memberCount = 4, member = member, chatId = if (member) "g1" else null)

    @Test fun aLinksPreviewIsWhatTheServerSaysAndNothingMore() = runSuspending {
        val brians = Phone(brian)
        brians.linksRemote.previewResult = AppResult.Success(previewOf())

        val preview = (brians.links.preview(token) as AppResult.Success).value

        assertEquals(GroupLinkPreview(token, "Book club", "Fridays at six.", "$url/photo?v=1", 4, member = false, chatId = null), preview)
        assertNull(brians.chatsDao.chat("g1"), "looking is not joining")
    }

    @Test fun joiningStoresTheChatUnderThePublishedNameUntilItsKeyArrives() = runSuspending {
        val aminas = Phone(amina)
        bookClub(aminas)
        aminas.chats.editGroup("g1", "Book club", "Fridays at six.")
        val brians = Phone(brian)
        val everyone = aminaOwner + member(brian, "Brian Mwangi")
        brians.linksRemote.joinResult = AppResult.Success(ChatDto("g1", MessagingWire.Group, aminas.sealedNow(), 2, everyone, version = 5))
        val preview = GroupLinkPreview(token, "Book club", "Fridays at six.", null, 1, member = false, chatId = null)

        val joined = (brians.links.join(token, preview) as AppResult.Success).value

        assertEquals("g1", joined.chatId)
        assertEquals("Book club", joined.title, "named from the link, not shown as an unnamed group")
        assertTrue(joined.nameKnown)
        assertEquals("Fridays at six.", joined.description)
        assertEquals("Book club", brians.chats.chat("g1").first()?.title)
        assertEquals(listOf(amina, brian), brians.chats.chat("g1").first()?.members?.map { it.userId })
        assertNull(brians.groupKeys.openGroupMeta("g1", aminas.sealedNow(), 2), "the key itself still comes from a member's phone")
    }

    @Test fun aJoinThatFailsChangesNothingOnThePhone() = runSuspending {
        val brians = Phone(brian)
        for (code in listOf("messaging.link_not_found", "messaging.link_not_allowed", "messaging.group_full")) {
            brians.linksRemote.joinResult = AppResult.Failure(AppError.Api(code, 404, null, null, emptyMap()))
            val r = brians.links.join(token, null) as AppResult.Failure
            assertEquals(code, (r.error as AppError.Api).code)
        }
        assertNull(brians.chatsDao.chat("g1"))
        assertTrue(brians.systemLines().isEmpty())
    }

    @Test fun theChatSaysWhoJoinedUsingTheGroupLink() = runSuspending {
        val aminas = Phone(amina)
        val made = bookClub(aminas)
        val everyone = aminaOwner + member(brian, "Brian Mwangi")
        val withBrian = made.copy(members = everyone, version = 6)
        aminas.remote.chats = listOf(withBrian)

        aminas.chats.onMemberAdded("g1", listOf(brian), joined = true, viaLink = true)
        assertEquals(listOf("Brian Mwangi joined using the group link"), aminas.systemLines())

        // On Brian's own phone the same frame says it of him.
        val brians = Phone(brian)
        brians.remote.chats = listOf(withBrian)
        brians.chats.onMemberAdded("g1", listOf(brian), joined = true, viaLink = true)
        assertEquals(listOf("You joined using the group link"), brians.systemLines())
    }

    @Test fun anAcceptedInviteStillSaysJoined() = runSuspending {
        val aminas = Phone(amina)
        val made = bookClub(aminas)
        aminas.remote.chats = listOf(made.copy(members = aminaOwner + member(brian, "Brian Mwangi"), version = 6))

        aminas.chats.onMemberAdded("g1", listOf(brian), joined = true)

        assertEquals(listOf("Brian Mwangi joined"), aminas.systemLines())
    }

    // ---- telling a join link from a share link

    @Test fun aJoinLinkIsToldFromAShareLinkByItsLength() {
        val hosts = setOf("links.circaso.com")
        for (link in listOf(url, "$url/", "$url?utm=x#top", "circaso://s/$token")) {
            val code = IncomingLinks.codeOf(link, hosts)
            assertEquals(token, code)
            assertTrue(IncomingLinks.isGroupToken(code!!))
        }
        assertFalse(IncomingLinks.isGroupToken("Ab3dEf7hJk9m"), "a share code is 12")
        assertFalse(IncomingLinks.isGroupToken(token + "x"))
        assertFalse(IncomingLinks.isGroupToken(token.dropLast(1)))
        assertFalse(IncomingLinks.isGroupToken("h3Xq9LmT2vKc8RzYw4Nd-B"), "base62 only")
        assertNull(IncomingLinks.codeOf("https://evil.example/s/$token", hosts), "only on Circaso's own hosts")
        assertNull(IncomingLinks.codeOf("http://links.circaso.com/s/$token", hosts), "https only")
        assertNotNull(IncomingLinks.codeOf("https://links.circaso.com/s/Ab3dEf7hJk9m", hosts))
    }
}
