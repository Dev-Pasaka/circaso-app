package com.circaso.feature.messaging

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.circasoJson
import com.circaso.core.platform.GroupPhotoImage
import com.circaso.core.platform.MediaFiles
import com.circaso.feature.messaging.data.keys.KeysRepositoryImpl
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.core.common.ErrorMessages
import com.circaso.feature.messaging.data.remote.AvatarChange
import com.circaso.feature.messaging.data.remote.ChatDto
import com.circaso.feature.messaging.data.remote.UpdateGroupRequest
import com.circaso.feature.messaging.data.remote.ChatMemberDto
import com.circaso.feature.messaging.data.remote.ChatProfileDto
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.repository.AttachmentDownloadResponse
import com.circaso.feature.messaging.data.repository.AttachmentReadyResponse
import com.circaso.feature.messaging.data.repository.AttachmentsRemote
import com.circaso.feature.messaging.data.repository.ChatsRepositoryImpl
import com.circaso.feature.messaging.data.repository.GroupKeys
import com.circaso.feature.messaging.data.repository.GroupPhotoRef
import com.circaso.feature.messaging.data.repository.GroupPhotos
import com.circaso.feature.messaging.data.repository.NewAttachmentResponse
import com.circaso.feature.messaging.data.repository.SessionCipher
import com.circaso.feature.messaging.domain.GroupDraft
import com.circaso.testing.FakeChatsDao
import com.circaso.testing.FakeChatsRemote
import com.circaso.testing.FakeKeys
import com.circaso.testing.FakeKeysRemote
import com.circaso.testing.FakeMessagesDao
import com.circaso.testing.FakeMessagesRemote
import com.circaso.testing.FakeSealer
import com.circaso.testing.FakeSignalCrypto
import com.circaso.testing.FakeSignalDao
import com.circaso.testing.runSuspending
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group photos end to end with the fake crypto: the photo is uploaded into the group's attachments, only a small
 * reference and a thumbnail go into the sealed metadata (kept under the server's 4 KB), a rename keeps the photo, and
 * the chat shows the photo from this phone.
 */
class GroupPhotoTest {
    private val json = circasoJson()
    private val me = "me"
    private val chatsDao = FakeChatsDao()
    private val store = SealedRecordStore(FakeSignalDao(), FakeSealer())
    private val crypto = FakeSignalCrypto()
    private val keys = KeysRepositoryImpl(store, FakeKeysRemote(), crypto, SignedKeyUpload(FakeKeys(), crypto), json)
    private val sessions = SessionCipher(store, crypto, keys)
    private val groupKeys = GroupKeys(store, crypto, chatsDao, sessions, FakeMessagesRemote(), json, { me })
    private val remote = FakeChatsRemote()
    private val attachments = FakeAttachments()
    private val files = MemoryFiles()
    private val storage = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
    private val photos = GroupPhotos(attachments, storage, crypto, files, CoroutineScope(Dispatchers.Unconfined))
    private val chats = ChatsRepositoryImpl(chatsDao, FakeMessagesDao(), remote, groupKeys, sessions, { me }, photos = photos)

    private var version = 1
    private var metaVersion = 1
    /** What the fake server keeps as the group's photo: M8 sets it, clears it, or (key left out) keeps it. */
    private var serverAvatar: String? = null
    private fun group(sealed: String?) = ChatDto(
        "g1", MessagingWire.Group, sealed, metaVersion,
        listOf(ChatMemberDto(ChatProfileDto(me, "Me"), MessagingWire.Owner, "t"), ChatMemberDto(ChatProfileDto("a", "Amina"), MessagingWire.Member, "t")),
        version = version,
        avatarMediaId = serverAvatar,
    )

    private fun applyAvatar(req: UpdateGroupRequest) {
        when (val a = req.avatarMediaId) {
            null -> Unit
            JsonNull -> serverAvatar = null
            else -> serverAvatar = (a as JsonPrimitive).content
        }
    }

    private fun image(thumbBytes: Int = 1_400) = GroupPhotoImage(ByteArray(200_000) { (it % 251).toByte() }, 1024, 1024, ByteArray(thumbBytes) { 7 })

    private suspend fun createGroup() {
        remote.createGroupResult = { req, _ -> AppResult.Success(group(req.sealedMeta)) }
        remote.updateGroupResult = { _, req -> version++; metaVersion++; applyAvatar(req); AppResult.Success(group(req.sealedMeta)) }
        assertIs<AppResult.Success<*>>(chats.createGroup(GroupDraft("Holiday Fund", listOf("a")), "k1"))
    }

    @Test fun aNewPhotoIsUploadedAndOnlyItsReferenceIsSealed() = runSuspending {
        createGroup()
        val r = chats.setGroupPhoto("g1", image())
        assertIs<AppResult.Success<*>>(r)
        // AT1 went to the group's own attachments with the ciphertext's size; the PUT and AT2 followed.
        assertEquals(listOf("g1" to "image"), attachments.created.map { it.first to it.third })
        assertEquals(listOf("att-1"), attachments.completed)
        val sealed = remote.updates.last().second.sealedMeta!!
        val decoded = Base64.decode(sealed)
        // The sealed value stays well under 4 KB even with the name, the key, the digest and the thumbnail.
        assertTrue(decoded.size + 28 <= 4 * 1024, "sealed meta is ${decoded.size} bytes")
        val chat = chats.chat("g1").first()!!
        assertEquals("Holiday Fund", chat.title)
        // This phone already has the full photo, so the chat shows it at once.
        assertEquals("/files/gphoto-att-1", chat.avatarUrl)
    }

    @Test fun aRenameKeepsThePhoto() = runSuspending {
        createGroup()
        chats.setGroupPhoto("g1", image())
        chats.rename("g1", "Holiday Fund 2027")
        val chat = chats.chat("g1").first()!!
        assertEquals("Holiday Fund 2027", chat.title)
        assertNotNull(chat.avatarUrl, "the photo survives a rename")
    }

    @Test fun removingThePhotoSealsTheNameAlone() = runSuspending {
        createGroup()
        chats.setGroupPhoto("g1", image())
        chats.setGroupPhoto("g1", null)
        assertNull(chats.chat("g1").first()!!.avatarUrl)
        assertEquals("Holiday Fund", chats.chat("g1").first()!!.title)
    }

    @Test fun aFailedUploadChangesNothing() = runSuspending {
        createGroup()
        attachments.createResult = AppResult.Failure(AppError.Network)
        val before = remote.updates.size
        assertEquals(AppError.Network, (chats.setGroupPhoto("g1", image()) as AppResult.Failure).error)
        assertEquals(before, remote.updates.size, "no M8 without the photo")
        assertNull(chats.chat("g1").first()!!.avatarUrl)
    }

    @Test fun aThumbnailTooBigForTheLimitIsLeftOutButThePhotoStays() = runSuspending {
        val key = groupKeys.newMetaKey()
        val ref = GroupPhotoRef("att-9", "a2V5", "ZGln", 1234, thumb = Base64.encode(ByteArray(3_500)))
        val sealed = groupKeys.sealMetaWithin(key, "N".repeat(64), ref)!!
        assertTrue(Base64.decode(sealed).size <= 4 * 1024)
        // Under the limit with a normal thumbnail: the thumbnail is kept.
        val small = groupKeys.sealMetaWithin(key, "N".repeat(64), ref.copy(thumb = Base64.encode(ByteArray(1_500))))!!
        assertTrue(Base64.decode(small).size > Base64.decode(sealed).size)
        assertTrue(Base64.decode(small).size + 28 <= 4 * 1024)
    }

    @Test fun anotherPhoneShowsTheThumbnailThenTheFullPhotoOnceFetched() = runSuspending {
        createGroup()
        chats.setGroupPhoto("g1", image())
        // Another phone: no copies on disk yet, the download link answers with the uploaded bytes.
        files.clear()
        attachments.downloadUrl = "https://r2/att-1"
        val sealedBlob = crypto.aesGcmSeal(ByteArray(32) { it.toByte() }, image().full)
        val otherStorage = HttpClient(MockEngine { respond(sealedBlob, HttpStatusCode.OK) })
        val otherPhotos = GroupPhotos(attachments, otherStorage, crypto, files, CoroutineScope(Dispatchers.Unconfined))
        val other = ChatsRepositoryImpl(chatsDao, FakeMessagesDao(), remote, groupKeys, sessions, { me }, photos = otherPhotos)
        val first = other.chat("g1").first()!!.avatarUrl
        assertNotNull(first)
        assertEquals("/files/gthumb-att-1", first, "the thumbnail from the sealed metadata, at once")
        // The fetch ran behind the first answer; once it lands the chat shows the full photo.
        var shown: String? = null
        repeat(200) { if (shown != "/files/gphoto-att-1") { kotlinx.coroutines.delay(10); shown = other.chat("g1").first()!!.avatarUrl } }
        assertEquals("/files/gphoto-att-1", shown)
    }

    // ---- avatarMediaId (M8, backend PR #47)

    @Test fun theRequestSendsTheIdANullOrNothing() {
        val set = json.encodeToString(UpdateGroupRequest.serializer(), UpdateGroupRequest(sealedMeta = "s", version = 3, avatarMediaId = AvatarChange.set("att-7")))
        assertTrue("\"avatarMediaId\":\"att-7\"" in set, set)
        val clear = json.encodeToString(UpdateGroupRequest.serializer(), UpdateGroupRequest(sealedMeta = "s", version = 3, avatarMediaId = AvatarChange.Clear))
        assertTrue("\"avatarMediaId\":null" in clear, "explicitNulls = false must not drop the clearing null: $clear")
        val keep = json.encodeToString(UpdateGroupRequest.serializer(), UpdateGroupRequest(sealedMeta = "s", version = 3, avatarMediaId = AvatarChange.Keep))
        assertTrue("avatarMediaId" !in keep, keep)
        // Every other optional stays out as before.
        assertTrue("settings" !in keep && "null" !in keep, keep)
    }

    @Test fun aChatFromAServerWithoutTheFieldDecodes() {
        val body = """{"chatId":"g1","kind":"GROUP","metaVersion":1,"members":[],"version":1}"""
        assertNull(json.decodeFromString(ChatDto.serializer(), body).avatarMediaId)
        val withIt = """{"chatId":"g1","kind":"GROUP","metaVersion":1,"members":[],"version":1,"avatarMediaId":"att-3"}"""
        assertEquals("att-3", json.decodeFromString(ChatDto.serializer(), withIt).avatarMediaId)
        val nulled = """{"chatId":"g1","kind":"GROUP","metaVersion":1,"members":[],"version":1,"avatarMediaId":null}"""
        assertNull(json.decodeFromString(ChatDto.serializer(), nulled).avatarMediaId)
    }

    @Test fun settingReplacingAndClearingNameTheAttachmentInTheSamePatch() = runSuspending {
        createGroup()
        chats.setGroupPhoto("g1", image())
        val first = remote.updates.last().second
        assertEquals(JsonPrimitive("att-1"), first.avatarMediaId)
        assertNotNull(first.sealedMeta, "the reference and the id travel together")
        assertEquals("att-1", chatsDao.chat("g1")!!.avatarMediaId, "kept with the chat")

        chats.setGroupPhoto("g1", image())
        assertEquals(JsonPrimitive("att-2"), remote.updates.last().second.avatarMediaId)
        assertEquals("att-2", chatsDao.chat("g1")!!.avatarMediaId)
        assertEquals("/files/gphoto-att-2", chats.chat("g1").first()!!.avatarUrl)

        chats.setGroupPhoto("g1", null)
        assertEquals(JsonNull, remote.updates.last().second.avatarMediaId)
        assertNull(chatsDao.chat("g1")!!.avatarMediaId)
        assertNull(chats.chat("g1").first()!!.avatarUrl)
    }

    @Test fun aRenameLeavesTheServersPhotoAlone() = runSuspending {
        createGroup()
        chats.setGroupPhoto("g1", image())
        chats.rename("g1", "Holiday Fund 2027")
        assertNull(remote.updates.last().second.avatarMediaId, "the key is left out: keep")
        assertEquals("att-1", chatsDao.chat("g1")!!.avatarMediaId)
    }

    @Test fun aVersionConflictRetriesWithTheSamePhoto() = runSuspending {
        createGroup()
        var conflicted = false
        val normal = remote.updateGroupResult
        remote.updateGroupResult = { id, req ->
            if (!conflicted) { conflicted = true; version++; AppResult.Failure(AppError.Api("messaging.version_conflict", 409, null, null, emptyMap())) }
            else normal(id, req)
        }
        remote.chats = listOf(group(chatsDao.chat("g1")!!.sealedMeta).copy(version = version + 1))
        assertIs<AppResult.Success<*>>(chats.setGroupPhoto("g1", image()))
        val (a, b) = remote.updates.takeLast(2).map { it.second }
        assertEquals(a.avatarMediaId, b.avatarMediaId)
        assertEquals(a.version + 1, b.version, "the retry carries the fresh version")
        assertEquals("att-1", chatsDao.chat("g1")!!.avatarMediaId)
    }

    @Test fun aPhotoTheServerRefusesSaysToTryAnother() = runSuspending {
        createGroup()
        remote.updateGroupResult = { _, _ -> AppResult.Failure(AppError.Api("messaging.avatar_invalid", 400, "req-1", null, emptyMap())) }
        val error = (chats.setGroupPhoto("g1", image()) as AppResult.Failure).error
        assertEquals("messaging.avatar_invalid", (error as AppError.Api).code)
        assertEquals("Couldn't use that photo. Try another one.", ErrorMessages.forError(error))
        assertNull(chats.chat("g1").first()!!.avatarUrl, "nothing changed")
    }

    @Test fun aServerThatIgnoresTheFieldStillGetsThePhoto() = runSuspending {
        createGroup()
        // An older server: answers without avatarMediaId whatever was sent.
        remote.updateGroupResult = { _, req -> version++; metaVersion++; AppResult.Success(group(req.sealedMeta).copy(avatarMediaId = null)) }
        assertIs<AppResult.Success<*>>(chats.setGroupPhoto("g1", image()))
        assertNull(chatsDao.chat("g1")!!.avatarMediaId)
        assertEquals("/files/gphoto-att-1", chats.chat("g1").first()!!.avatarUrl, "the sealed photo is shown")
    }

    @Test fun anOlderGroupTheServerSaysNullForKeepsItsThumbnail() = runSuspending {
        createGroup()
        chats.setGroupPhoto("g1", image())
        files.clear()
        attachments.downloadResult = AppResult.Failure(AppError.Api("messaging.attachment_not_found", 404, null, null, emptyMap()))
        chats.store(group(chatsDao.chat("g1")!!.sealedMeta).copy(avatarMediaId = null))
        assertEquals("/files/gthumb-att-1", chats.chat("g1").first()!!.avatarUrl)
    }

    @Test fun anotherAttachmentFromTheServerIsTriedFirstThenTheSealedOne() = runSuspending {
        createGroup()
        chats.setGroupPhoto("g1", image())
        files.clear()
        // The server names another attachment (an older app changed the photo without the field): its size isn't the
        // sealed one, so it is not downloaded and the sealed attachment is fetched instead.
        attachments.sizes["att-other"] = 10L
        chats.store(group(chatsDao.chat("g1")!!.sealedMeta).copy(avatarMediaId = "att-other"))
        attachments.downloads.clear()
        val sealedBlob = crypto.aesGcmSeal(ByteArray(32) { it.toByte() }, image().full)
        val otherPhotos = GroupPhotos(attachments, HttpClient(MockEngine { respond(sealedBlob, HttpStatusCode.OK) }), crypto, files, CoroutineScope(Dispatchers.Unconfined))
        val other = ChatsRepositoryImpl(chatsDao, FakeMessagesDao(), remote, groupKeys, sessions, { me }, photos = otherPhotos)
        other.chat("g1").first()
        var shown: String? = null
        repeat(200) { if (shown != "/files/gphoto-att-1") { kotlinx.coroutines.delay(10); shown = other.chat("g1").first()!!.avatarUrl } }
        assertEquals("/files/gphoto-att-1", shown)
        assertEquals(listOf("att-other", "att-1"), attachments.downloads.distinct())
    }

    @Test fun aChatUpdatedFrameWithAnotherPhotoReloadsTheChat() = runSuspending {
        createGroup()
        chats.setGroupPhoto("g1", image())
        val stored = chatsDao.chat("g1")!!
        serverAvatar = null
        remote.chats = listOf(group(stored.sealedMeta))
        // Same version, but the frame says the photo is gone: reload.
        chats.onChatUpdated("g1", stored.version, ChatsRepositoryImpl.Avatar(null))
        assertNull(chatsDao.chat("g1")!!.avatarMediaId)
    }

    // ---- a join link's published photo (2026-11-14)

    /** The phones' encoder makes a 256 px JPEG; this one marks the bytes, so the test sees what was published. */
    private class MarkingEncoder : com.circaso.core.platform.GroupPhotoEncoder {
        override suspend fun encode(source: ByteArray): GroupPhotoImage? = null
        override suspend fun preview(source: ByteArray): ByteArray = "small:${source.size}".encodeToByteArray()
    }

    private class LinksRemote : com.circaso.feature.messaging.data.remote.GroupLinksRemote {
        val published = mutableListOf<com.circaso.feature.messaging.data.remote.PublishGroupLinkRequest>()
        override suspend fun link(chatId: String) = AppResult.Success(com.circaso.feature.messaging.data.remote.GroupLinkResponse(null))
        override suspend fun publish(chatId: String, request: com.circaso.feature.messaging.data.remote.PublishGroupLinkRequest): AppResult<com.circaso.feature.messaging.data.remote.GroupLinkDto> {
            published += request
            return AppResult.Success(com.circaso.feature.messaging.data.remote.GroupLinkDto("h3Xq9LmT2vKc8RzYw4Nd6B", "https://links.circaso.com/s/h3Xq9LmT2vKc8RzYw4Nd6B", request.name, request.description, request.photo?.let { "https://links.circaso.com/s/h3Xq9LmT2vKc8RzYw4Nd6B/photo?v=1" }))
        }
        override suspend fun turnOff(chatId: String) = AppResult.Success(com.circaso.feature.messaging.data.remote.GroupLinkOffResponse())
        override suspend fun preview(token: String) = AppResult.Failure(com.circaso.core.common.AppError.Network)
        override suspend fun join(token: String) = AppResult.Failure(com.circaso.core.common.AppError.Network)
    }

    private class LinkCache(var value: String? = null) : com.circaso.feature.messaging.data.repository.GroupLinkCache {
        override suspend fun read() = value
        override suspend fun write(value: String?) { this.value = value }
    }

    private val linksRemote = LinksRemote()
    private val links = com.circaso.feature.messaging.data.repository.GroupLinksRepositoryImpl(
        linksRemote, chats, chatsDao, groupKeys, LinkCache(), json, { me }, photos = photos, encoder = MarkingEncoder(),
    )

    @Test fun aLinksPublishedPhotoIsASmallCopyOfTheGroupPhotoOnThisPhone() = runSuspending {
        createGroup()
        chats.setGroupPhoto("g1", image())

        val link = (links.create("g1") as AppResult.Success).value

        val sent = linksRemote.published.single()
        assertEquals("Holiday Fund", sent.name)
        assertEquals("small:200000", Base64.decode(sent.photo!!).decodeToString(), "the encoder's small copy of the full photo, never the full photo")
        assertTrue(link.hasPhoto)
    }

    @Test fun aGroupWithoutAPhotoPublishesNone() = runSuspending {
        createGroup()
        val link = (links.create("g1") as AppResult.Success).value
        assertNull(linksRemote.published.single().photo)
        assertTrue(!link.hasPhoto)
    }

    @Test fun aNewOrRemovedGroupPhotoIsPublishedAgainWhileALinkIsOn() = runSuspending {
        createGroup()
        links.create("g1")
        chats.metaChanged = { links.republishIfStale(it) }

        chats.setGroupPhoto("g1", image())
        assertEquals(2, linksRemote.published.size)
        assertNotNull(linksRemote.published.last().photo)

        links.republishIfStale("g1")
        assertEquals(2, linksRemote.published.size, "the same photo isn't sent twice")

        chats.setGroupPhoto("g1", null)
        assertEquals(3, linksRemote.published.size)
        assertNull(linksRemote.published.last().photo, "the public copy loses the photo when the group does")
    }

}

private class FakeAttachments : AttachmentsRemote {
    val created = mutableListOf<Triple<String, Long, String>>()
    val completed = mutableListOf<String>()
    var createResult: AppResult<NewAttachmentResponse>? = null
    var downloadUrl = "https://r2/x"
    var downloadResult: AppResult<AttachmentDownloadResponse>? = null
    val sizes = mutableMapOf<String, Long>()
    val downloads = mutableListOf<String>()
    override suspend fun create(chatId: String, sizeBytes: Long, kind: String): AppResult<NewAttachmentResponse> {
        createResult?.let { return it }
        created += Triple(chatId, sizeBytes, kind)
        return AppResult.Success(NewAttachmentResponse("att-${created.size}", "https://r2/upload"))
    }
    override suspend fun complete(attachmentId: String): AppResult<AttachmentReadyResponse> { completed += attachmentId; return AppResult.Success(AttachmentReadyResponse(attachmentId)) }
    override suspend fun download(attachmentId: String): AppResult<AttachmentDownloadResponse> {
        downloads += attachmentId
        return downloadResult ?: AppResult.Success(AttachmentDownloadResponse(downloadUrl, sizeBytes = sizes[attachmentId]))
    }
}

private class MemoryFiles : MediaFiles {
    private val map = mutableMapOf<String, ByteArray>()
    override suspend fun read(name: String) = map[name]
    override suspend fun write(name: String, bytes: ByteArray) { map[name] = bytes }
    override suspend fun clear() = map.clear()
    override suspend fun path(name: String) = if (name in map) "/files/$name" else null
}
