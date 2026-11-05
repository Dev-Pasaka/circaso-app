package com.circaso.feature.messaging

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.circasoJson
import com.circaso.core.platform.GroupPhotoImage
import com.circaso.core.platform.MediaFiles
import com.circaso.feature.messaging.data.keys.KeysRepositoryImpl
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.remote.ChatDto
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
    private fun group(sealed: String?) = ChatDto(
        "g1", MessagingWire.Group, sealed, metaVersion,
        listOf(ChatMemberDto(ChatProfileDto(me, "Me"), MessagingWire.Owner, "t"), ChatMemberDto(ChatProfileDto("a", "Amina"), MessagingWire.Member, "t")),
        version = version,
    )

    private fun image(thumbBytes: Int = 1_400) = GroupPhotoImage(ByteArray(200_000) { (it % 251).toByte() }, 1024, 1024, ByteArray(thumbBytes) { 7 })

    private suspend fun createGroup() {
        remote.createGroupResult = { req, _ -> AppResult.Success(group(req.sealedMeta)) }
        remote.updateGroupResult = { _, req -> version++; metaVersion++; AppResult.Success(group(req.sealedMeta)) }
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
}

private class FakeAttachments : AttachmentsRemote {
    val created = mutableListOf<Triple<String, Long, String>>()
    val completed = mutableListOf<String>()
    var createResult: AppResult<NewAttachmentResponse>? = null
    var downloadUrl = "https://r2/x"
    override suspend fun create(chatId: String, sizeBytes: Long, kind: String): AppResult<NewAttachmentResponse> {
        createResult?.let { return it }
        created += Triple(chatId, sizeBytes, kind)
        return AppResult.Success(NewAttachmentResponse("att-${created.size}", "https://r2/upload"))
    }
    override suspend fun complete(attachmentId: String): AppResult<AttachmentReadyResponse> { completed += attachmentId; return AppResult.Success(AttachmentReadyResponse(attachmentId)) }
    override suspend fun download(attachmentId: String) = AppResult.Success(AttachmentDownloadResponse(downloadUrl))
}

private class MemoryFiles : MediaFiles {
    private val map = mutableMapOf<String, ByteArray>()
    override suspend fun read(name: String) = map[name]
    override suspend fun write(name: String, bytes: ByteArray) { map[name] = bytes }
    override suspend fun clear() = map.clear()
    override suspend fun path(name: String) = if (name in map) "/files/$name" else null
}
