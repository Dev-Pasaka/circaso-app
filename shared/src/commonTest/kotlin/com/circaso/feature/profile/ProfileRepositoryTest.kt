package com.circaso.feature.profile

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.circasoJson
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxEntity
import com.circaso.feature.profile.data.local.ProfileDao
import com.circaso.feature.profile.data.local.ProfileEntity
import com.circaso.feature.profile.data.remote.AvatarUploader
import com.circaso.feature.profile.data.remote.ClaimHandleRequest
import com.circaso.feature.profile.data.remote.HandleAvailabilityResponse
import com.circaso.feature.profile.data.remote.MyProfileResponse
import com.circaso.feature.profile.data.remote.PrivacyDto
import com.circaso.feature.profile.data.remote.ProfileRemote
import com.circaso.feature.profile.data.remote.PublicProfileDto
import com.circaso.feature.profile.data.remote.SetAvatarRequest
import com.circaso.feature.profile.data.remote.UpdateProfileRequest
import com.circaso.feature.profile.data.remote.UploadUrlRequest
import com.circaso.feature.profile.data.remote.UploadUrlResponse
import com.circaso.feature.profile.data.repository.ProfileEditPayload
import com.circaso.feature.profile.data.repository.ProfileRepositoryImpl
import com.circaso.feature.profile.data.repository.ProfileUpdateHandler
import com.circaso.feature.profile.domain.AvatarImage
import com.circaso.feature.profile.domain.HandleStatus
import com.circaso.feature.profile.domain.ProfileChange
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.runSuspending
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeProfileDao : ProfileDao {
    val row = MutableStateFlow<ProfileEntity?>(null)
    override fun observe(): Flow<ProfileEntity?> = row
    override suspend fun current() = row.value
    override suspend fun upsert(profile: ProfileEntity) { row.value = profile }
    override suspend fun clear() { row.value = null }
}

private fun server(version: Int = 5, name: String = "Amina", about: String? = "Hi", avatar: String? = null) = MyProfileResponse(
    userId = "u1", displayName = name, about = about, handle = "amina", handleChangeableAt = null, avatarUrl = avatar,
    privacy = PrivacyDto("contacts", "contacts", true), version = version,
)

private class FakeRemote : ProfileRemote {
    var profile: AppResult<MyProfileResponse> = AppResult.Success(server())
    var update: AppResult<MyProfileResponse> = AppResult.Success(server(version = 6, name = "Amina W"))
    var uploadUrl: AppResult<UploadUrlResponse> = AppResult.Success(UploadUrlResponse("https://storage.example/put", "key-1", 300))
    var setAvatar: AppResult<MyProfileResponse> = AppResult.Success(server(version = 7, avatar = "https://cdn/a.jpg"))
    val updates = mutableListOf<Pair<UpdateProfileRequest, String?>>()
    val calls = mutableListOf<String>()

    override suspend fun profile() = profile
    override suspend fun updateProfile(request: UpdateProfileRequest, idempotencyKey: String?): AppResult<MyProfileResponse> { updates += request to idempotencyKey; return update }
    override suspend fun avatarUploadUrl(request: UploadUrlRequest): AppResult<UploadUrlResponse> { calls += "upload-url"; return uploadUrl }
    override suspend fun setAvatar(request: SetAvatarRequest): AppResult<MyProfileResponse> { calls += "set-avatar:${request.objectKey}"; return setAvatar }
    override suspend fun removeAvatar(): AppResult<MyProfileResponse> = AppResult.Success(server())
    override suspend fun handleAvailability(handle: String) = AppResult.Success(HandleAvailabilityResponse(handle, false, "taken", "amina2"))
    override suspend fun claimHandle(request: ClaimHandleRequest) = AppResult.Success(server(version = 8).copy(handle = request.handle))
    override suspend fun byHandle(handle: String) = AppResult.Success(PublicProfileDto("u2", "Otieno", handle))
    override suspend fun user(userId: String) = AppResult.Success(PublicProfileDto(userId, "Otieno"))
}

private class FakeUploader(var result: AppResult<Unit> = AppResult.Success(Unit)) : AvatarUploader {
    val puts = mutableListOf<Triple<String, Int, String>>()
    override suspend fun put(url: String, bytes: ByteArray, contentType: String): AppResult<Unit> { puts += Triple(url, bytes.size, contentType); return result }
}

class ProfileRepositoryTest {
    private val json = circasoJson()
    private val dao = FakeProfileDao()
    private val outboxDao = FakeOutboxDao()
    private val outbox = Outbox(outboxDao) { 1_000L }
    private val remote = FakeRemote()
    private val uploader = FakeUploader()
    private var kicks = 0
    private val repo = ProfileRepositoryImpl(remote, dao, outbox, uploader, json) { kicks++ }
    private val handler = ProfileUpdateHandler(remote, dao, outbox, json)

    private suspend fun loaded() { repo.refresh() }
    private fun pendingItems() = outboxDao.rows.value.filter { it.type == ProfileUpdateHandler.Type }

    @Test fun refreshStoresTheServersProfile() = runSuspending {
        loaded()
        val p = repo.profile.first()!!
        assertEquals("Amina", p.displayName)
        assertEquals(5, p.version)
        assertTrue(!p.pendingSync)
    }

    @Test fun anEditIsSavedAtOnceMarkedPendingAndQueued() = runSuspending {
        loaded()
        assertTrue(repo.update(ProfileChange(displayName = "Amina W")))
        val p = repo.profile.first()!!
        assertEquals("Amina W", p.displayName)
        assertTrue(p.pendingSync)
        assertEquals(1, pendingItems().size)
        assertEquals(1, kicks)
    }

    @Test fun anEmptyChangeDoesNothing() = runSuspending {
        loaded()
        assertTrue(!repo.update(ProfileChange()))
        assertEquals(0, pendingItems().size)
    }

    @Test fun twoQuickEditsBecomeOneRequestWithTheLatestValues() = runSuspending {
        loaded()
        repo.update(ProfileChange(displayName = "A"))
        repo.update(ProfileChange(about = "New about", displayName = "B"))
        val item = pendingItems().single()
        val payload = json.decodeFromString(ProfileEditPayload.serializer(), item.payload)
        assertEquals("B", payload.displayName)
        assertEquals("New about", payload.about)
    }

    @Test fun anEmptyAboutClearsItLocallyAndIsSentAsEmpty() = runSuspending {
        loaded()
        repo.update(ProfileChange(about = ""))
        assertNull(repo.profile.first()!!.about)
        assertEquals("", json.decodeFromString(ProfileEditPayload.serializer(), pendingItems().single().payload).about)
    }

    @Test fun theHandlerSendsOnlyChangedFieldsWithTheServersVersionAndTheItemsKey() = runSuspending {
        loaded()
        repo.update(ProfileChange(displayName = "Amina W", handleLookup = false))
        val item: OutboxEntity = pendingItems().single()
        assertIs<AppResult.Success<Unit>>(handler.send(item))
        val (request, key) = remote.updates.single()
        assertEquals(5, request.version)
        assertEquals("Amina W", request.displayName)
        assertNull(request.about)
        assertEquals(false, request.privacy?.handleLookup)
        assertNull(request.privacy?.avatar)
        assertEquals(item.idempotencyKey, key)
    }

    @Test fun whenTheServerConfirmsTheConfirmedProfileReplacesTheLocalOne() = runSuspending {
        loaded()
        repo.update(ProfileChange(displayName = "Amina W"))
        handler.send(pendingItems().single())
        val p = repo.profile.first()!!
        assertEquals(6, p.version)
        assertTrue(!p.pendingSync) // nothing else is waiting: this is now the confirmed profile
    }

    @Test fun theProfileStaysPendingWhileAnotherEditIsStillQueued() = runSuspending {
        loaded()
        repo.update(ProfileChange(displayName = "First"))
        val first = pendingItems().single()
        outboxDao.update(first.id, "sending", 1, null) // the first is on its way, so the next edit cannot merge into it
        repo.update(ProfileChange(about = "Second"))
        assertEquals(2, pendingItems().size)
        handler.send(first)
        assertTrue(repo.profile.first()!!.pendingSync)
    }

    @Test fun aVersionConflictIsReportedAndTheLocalEditIsKept() = runSuspending {
        loaded()
        repo.update(ProfileChange(displayName = "Mine"))
        remote.update = AppResult.Failure(AppError.Api("users.version_conflict", 409, "r", null, emptyMap()))
        val result = handler.send(pendingItems().single())
        assertEquals("users.version_conflict", ((result as AppResult.Failure).error as AppError.Api).code)
        assertEquals("Mine", repo.profile.first()!!.displayName)
    }

    @Test fun refreshNeverOverwritesAWaitingEditButRefreshesThePictureLink() = runSuspending {
        loaded()
        repo.update(ProfileChange(displayName = "Mine"))
        remote.profile = AppResult.Success(server(version = 9, name = "Someone else", avatar = "https://cdn/new.jpg"))
        repo.refresh()
        val p = repo.profile.first()!!
        assertEquals("Mine", p.displayName)
        assertEquals("https://cdn/new.jpg", p.avatarUrl)
        assertEquals(5, p.version) // unchanged: the clash surfaces as a conflict when sending
    }

    @Test fun anotherAccountsCachedProfileIsReplacedNotKept() = runSuspending {
        dao.upsert(ProfileEntity("someone-else", "Old", null, null, null, null, "a", "a", true, 1, pendingSync = true))
        repo.refresh()
        assertEquals("u1", repo.profile.first()!!.userId)
    }

    @Test fun anInvalidPictureIsRefusedBeforeAnyRequest() = runSuspending {
        loaded()
        val gif = repo.uploadAvatar(AvatarImage(ByteArray(10), "image/gif"))
        assertEquals("users.avatar_invalid", ((gif as AppResult.Failure).error as AppError.Api).code)
        val big = repo.uploadAvatar(AvatarImage(ByteArray(5 * 1024 * 1024 + 1), "image/jpeg"))
        assertIs<AppResult.Failure>(big)
        assertTrue(remote.calls.isEmpty())
    }

    @Test fun theAvatarFlowAsksForALinkSendsTheBytesThenConfirms() = runSuspending {
        loaded()
        assertIs<AppResult.Success<Unit>>(repo.uploadAvatar(AvatarImage(ByteArray(2048), "image/jpeg")))
        assertEquals(listOf("upload-url", "set-avatar:key-1"), remote.calls)
        assertEquals(Triple("https://storage.example/put", 2048, "image/jpeg"), uploader.puts.single())
        assertEquals("https://cdn/a.jpg", repo.profile.first()!!.avatarUrl)
    }

    @Test fun aFailedUploadNeverConfirmsThePicture() = runSuspending {
        loaded()
        uploader.result = AppResult.Failure(AppError.Network)
        assertIs<AppResult.Failure>(repo.uploadAvatar(AvatarImage(ByteArray(10), "image/png")))
        assertEquals(listOf("upload-url"), remote.calls)
    }

    @Test fun claimingAHandleStoresTheReturnedProfile() = runSuspending {
        loaded()
        repo.claimHandle("amina_k")
        assertEquals("amina_k", repo.profile.first()!!.handle)
        assertEquals(8, repo.profile.first()!!.version)
    }

    @Test fun handleStatusIsMappedAndNeverLeftAsAString() = runSuspending {
        val result = (repo.checkHandle("amina") as AppResult.Success).value
        assertEquals(HandleStatus.Taken, result.status)
        assertEquals("amina2", result.suggestion)
    }
}
