package com.circaso.feature.status.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.platform.AudioPlayer
import com.circaso.core.platform.AudioRecorder
import com.circaso.core.platform.CameraStart
import com.circaso.core.platform.CapturedMedia
import com.circaso.core.platform.ImagePicker
import com.circaso.core.platform.LibraryAccess
import com.circaso.core.platform.LibraryItem
import com.circaso.core.platform.LibraryKind
import com.circaso.core.platform.LibraryRecents
import com.circaso.core.platform.MediaLibrary
import com.circaso.core.platform.MomentCamera
import com.circaso.core.platform.PickedImage
import com.circaso.core.platform.Recording
import com.circaso.core.platform.RecordingStart
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.contacts.domain.Connection
import com.circaso.feature.contacts.domain.ConnectVia
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.Invite
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.contacts.domain.SyncSummary
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.GroupCreated
import com.circaso.feature.messaging.domain.GroupDraft
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.SecurityCode
import com.circaso.feature.profile.domain.AvatarImage
import com.circaso.feature.profile.domain.HandleAvailability
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.profile.domain.Profile
import com.circaso.feature.profile.domain.ProfileChange
import com.circaso.feature.profile.domain.ProfileRepository
import com.circaso.feature.profile.domain.PublicProfile
import com.circaso.feature.status.domain.Moment
import com.circaso.feature.status.domain.MomentDraft
import com.circaso.feature.status.domain.MomentKind
import com.circaso.feature.status.domain.MomentMediaLoad
import com.circaso.feature.status.domain.MomentPrivacy
import com.circaso.feature.status.domain.MomentViewer
import com.circaso.feature.status.domain.MomentsRepository
import com.circaso.feature.status.domain.PendingMoment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// Previews of the composer (M1, M3, M3b, M3c, M4) and the forward picker (V4), in the four themes where the screen
// follows the person's theme. The stage itself is dark in every theme. Preview-only fakes: nothing here ships logic.

private val previewPhotos = List(12) { LibraryItem("preview-photo-$it", LibraryKind.Photo, addedAt = 100L - it) }
private val previewVideos = List(3) { LibraryItem("preview-video-$it", LibraryKind.Video, durationMs = 42_000L + it * 61_000L, addedAt = 99L - it * 4) }

private object PreviewLibrary : MediaLibrary {
    override suspend fun access() = LibraryAccess.Granted
    override suspend fun recents(kind: LibraryKind, limit: Int, ask: Boolean) =
        LibraryRecents(if (kind == LibraryKind.Video) previewVideos else if (kind == LibraryKind.Photo) previewPhotos else emptyList(), LibraryAccess.Granted)
    override suspend fun manage(kind: LibraryKind) = LibraryAccess.Granted
    override suspend fun load(item: LibraryItem): CapturedMedia? = null
    override suspend fun pick(kind: LibraryKind): CapturedMedia? = null
}

private object PreviewCamera : MomentCamera {
    override suspend fun open() = CameraStart.Unavailable
    override suspend fun takePhoto(): CapturedMedia? = null
    override suspend fun startVideo() = false
    override suspend fun stopVideo(): CapturedMedia? = null
    override fun flip() = Unit
    override fun setFlash(on: Boolean) = Unit
    override fun close() = Unit
}

private object PreviewMoments : MomentsRepository {
    override val moments: Flow<List<Moment>> = flowOf(emptyList())
    override val pending: Flow<List<PendingMoment>> = flowOf(emptyList())
    override val privacy: Flow<MomentPrivacy> = flowOf(MomentPrivacy())
    override val muted: Flow<Set<String>> = flowOf(emptySet())
    override suspend fun refresh() = AppResult.Success(Unit)
    override suspend fun post(draft: MomentDraft) = AppResult.Success(Unit)
    override suspend fun retry(localId: String) = Unit
    override suspend fun discard(localId: String) = Unit
    override suspend fun markViewed(momentId: String, reacted: Boolean?) = AppResult.Success(Unit)
    override suspend fun react(momentId: String, emoji: String?) = AppResult.Success(Unit)
    override suspend fun viewers(momentId: String): AppResult<List<MomentViewer>> = AppResult.Success(emptyList())
    override suspend fun delete(momentId: String) = AppResult.Success(Unit)
    override suspend fun media(moment: Moment): MomentMediaLoad = MomentMediaLoad.NeedsConnection
    override suspend fun setPrivacy(privacy: MomentPrivacy) = Unit
    override suspend fun setMuted(userId: String, muted: Boolean) = Unit
    override suspend fun onFrame(type: String, momentId: String?, posterId: String?) = Unit
}

private object PreviewContacts : ContactsRepository {
    override val contacts: Flow<List<Contact>> = flowOf(List(48) { Contact(Person("u$it", "Person $it", null, null, null), "sync", "2026-11-01") })
    override val blocks: Flow<List<Person>> = flowOf(emptyList())
    override suspend fun refreshContacts() = AppResult.Success(Unit)
    override suspend fun refreshBlocks() = AppResult.Success(Unit)
    override suspend fun syncPhoneBook(phones: List<String>): AppResult<SyncSummary> = AppResult.Success(SyncSummary(0, emptyList()))
    override suspend fun connect(via: ConnectVia, value: String): AppResult<Connection> = AppResult.Failure(AppError.Network)
    override suspend fun createInvite(): AppResult<Invite> = AppResult.Failure(AppError.Network)
    override suspend fun revokeInvite(token: String) = AppResult.Success(Unit)
    override suspend fun removeContact(userId: String) = Unit
    override suspend fun block(person: Person) = Unit
    override suspend fun unblock(userId: String) = Unit
    override suspend fun canReach(userId: String) = AppResult.Success(true)
}

private object PreviewProfile : ProfileRepository {
    override val profile: Flow<Profile?> = flowOf(null)
    override suspend fun refresh() = AppResult.Success(Unit)
    override suspend fun update(change: ProfileChange) = true
    override suspend fun checkHandle(handle: String): AppResult<HandleAvailability> = AppResult.Failure(AppError.Network)
    override suspend fun claimHandle(handle: String) = AppResult.Success(Unit)
    override suspend fun uploadAvatar(image: AvatarImage) = AppResult.Success(Unit)
    override suspend fun removeAvatar() = AppResult.Success(Unit)
    override suspend fun findByHandle(handle: String): AppResult<PublicProfile> = AppResult.Failure(AppError.Network)
    override suspend fun publicProfile(userId: String): AppResult<PublicProfile> = AppResult.Failure(AppError.Network)
}

private object PreviewChats : ChatsRepository {
    override val chats: Flow<List<ChatSummary>> = flowOf(listOf("Wanjiru Kamau", "Juma Otieno", "Chama ya Mama Njeri", "Achieng Odhiambo", "Kevin Mwangi").mapIndexed { i, n ->
        ChatSummary(Chat("c$i", if (i == 2) ChatKind.Group else ChatKind.Direct, n, null, emptyList(), false, 1, 1, MemberRole.Member, if (i == 2) null else "u$i"), null, 0)
    })
    override fun chat(chatId: String): Flow<Chat?> = flowOf(null)
    override suspend fun refresh() = AppResult.Success(Unit)
    override suspend fun refreshChat(chatId: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun openDirect(userId: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun createGroup(draft: GroupDraft, idempotencyKey: String): AppResult<GroupCreated> = AppResult.Failure(AppError.Network)
    override suspend fun rename(chatId: String, name: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun setMembersCanAdd(chatId: String, allowed: Boolean): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun addMembers(chatId: String, userIds: List<String>): AppResult<GroupCreated> = AppResult.Failure(AppError.Network)
    override suspend fun removeMember(chatId: String, userId: String) = AppResult.Success(Unit)
    override suspend fun leave(chatId: String) = AppResult.Success(Unit)
    override suspend fun setRole(chatId: String, userId: String, role: MemberRole): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun securityCode(userId: String): SecurityCode? = null
}

private object NoPicker : ImagePicker { override suspend fun pick(): PickedImage? = null }
private object NoRecorder : AudioRecorder {
    override suspend fun start() = RecordingStart.Failed
    override suspend fun stop(): Recording? = null
    override suspend fun cancel() = Unit
}
private object NoPlayer : AudioPlayer { override suspend fun play(bytes: ByteArray, contentType: String) = Unit; override fun stop() = Unit }

@Composable
private fun PreviewComposer(initial: MomentComposerUiState, dark: Boolean = true, highContrast: Boolean = false) {
    val vm = remember {
        MomentComposerViewModel(PreviewMoments, PreviewCamera, NoPicker, NoRecorder, ObserveContactsUseCase(PreviewContacts), NoPlayer, PreviewLibrary, ObserveProfileUseCase(PreviewProfile), initial = initial)
    }
    CircasoTheme(darkTheme = dark, highContrast = highContrast) { MomentComposerScreen(vm, onClose = {}, onAudience = {}) }
}

private val twoPhotos = List(2) { ComposerItem(ComposerCapture.Shot(CapturedMedia(ByteArray(0), "image/jpeg", 1080, 1920), MomentKind.Image), caption = if (it == 0) "Graduation day!" else "") }
private val longVideo = CapturedMedia(ByteArray(0), "video/mp4", 720, 1280, durationMs = 134_000, path = "/preview/long.mp4")

@Preview(name = "M1 · Photo, several ticked on the strip")
@Composable
private fun ComposerSelectingPreview() = PreviewComposer(MomentComposerUiState(selecting = true, selection = previewPhotos.take(3)))

@Preview(name = "M1 · Video, access off")
@Composable
private fun ComposerVideoNoAccessPreview() = PreviewComposer(MomentComposerUiState(mode = ComposerMode.Video, recentsAccess = LibraryAccess.Denied))

@Preview(name = "M3 · Gallery, light")
@Composable
private fun GalleryLightPreview() = PreviewComposer(MomentComposerUiState(gallery = GalleryState(photos = previewPhotos, videos = previewVideos, access = LibraryAccess.Granted), selecting = true, selection = listOf(previewPhotos[0], previewPhotos[2])), dark = false)

@Preview(name = "M3 · Gallery, dark")
@Composable
private fun GalleryDarkPreview() = PreviewComposer(MomentComposerUiState(gallery = GalleryState(photos = previewPhotos, videos = previewVideos, access = LibraryAccess.Granted), selecting = true, selection = listOf(previewPhotos[0])), dark = true)

@Preview(name = "M3c · Gallery, limited, light high contrast")
@Composable
private fun GalleryLimitedPreview() = PreviewComposer(MomentComposerUiState(gallery = GalleryState(photos = previewPhotos.take(3), access = LibraryAccess.Limited), selecting = true), dark = false, highContrast = true)

@Preview(name = "M3c · Gallery, access off, dark high contrast")
@Composable
private fun GalleryDeniedPreview() = PreviewComposer(MomentComposerUiState(gallery = GalleryState(access = LibraryAccess.Denied), selecting = true), dark = true, highContrast = true)

@Preview(name = "M3b · Trim a video")
@Composable
private fun TrimPreview() = PreviewComposer(
    MomentComposerUiState(items = listOf(ComposerItem(ComposerCapture.Shot(longVideo, MomentKind.Video))), trim = TrimState(0, 134_000, 21_000, 79_000, required = true)),
)

@Preview(name = "M4 · Two picked, caption and share")
@Composable
private fun ReviewSeveralPreview() = PreviewComposer(MomentComposerUiState(items = twoPhotos))

@Composable
private fun PreviewForward(dark: Boolean, highContrast: Boolean) {
    val vm = remember { MomentForwardViewModel(PreviewChats) { _, _ -> true }.also { it.toggle("c0"); it.toggle("c2") } }
    CircasoTheme(darkTheme = dark, highContrast = highContrast) {
        MomentForwardScreen(vm, ForwardMedia(MomentKind.Image, ByteArray(0), null, "image/jpeg", null, null, null, null, null), onClose = {}, onSent = {})
    }
}

@Preview(name = "V4 · Forward, light")
@Composable
private fun ForwardLightPreview() = PreviewForward(dark = false, highContrast = false)

@Preview(name = "V4 · Forward, dark")
@Composable
private fun ForwardDarkPreview() = PreviewForward(dark = true, highContrast = false)

@Preview(name = "V4 · Forward, light high contrast")
@Composable
private fun ForwardLightHcPreview() = PreviewForward(dark = false, highContrast = true)

@Preview(name = "V4 · Forward, dark high contrast", fontScale = 1.6f)
@Composable
private fun ForwardDarkHcPreview() = PreviewForward(dark = true, highContrast = true)

private val previewLevels = List(48) { i -> (0.2f + 0.8f * kotlin.math.abs(kotlin.math.sin(i / 5f))) }

@Preview(name = "M5 · Voice review, chosen background")
@Composable
private fun VoiceReviewPreview() = PreviewComposer(
    MomentComposerUiState(mode = ComposerMode.Voice, voiceBackground = "g-ocean", items = listOf(ComposerItem(ComposerCapture.Voice(ByteArray(0), "audio/mp4", 23_000, previewLevels)))),
)

@Preview(name = "M5 · Voice review on Lemon, no levels, person in light theme, large text", fontScale = 1.6f)
@Composable
private fun VoiceReviewLightPreview() = PreviewComposer(
    MomentComposerUiState(mode = ComposerMode.Voice, voiceBackground = "c-lemon", items = listOf(ComposerItem(ComposerCapture.Voice(ByteArray(0), "audio/mp4", 23_000, null)))),
    dark = false,
)

@Preview(name = "M5b · Recording a voice moment")
@Composable
private fun VoiceRecordingPreview() = PreviewComposer(MomentComposerUiState(mode = ComposerMode.Voice, recordingVoice = true, recordingSince = 0))

@Preview(name = "M5b · Recording on a light background, dark high contrast")
@Composable
private fun VoiceRecordingLemonPreview() = PreviewComposer(MomentComposerUiState(mode = ComposerMode.Voice, voiceBackground = "c-lemon"), dark = true, highContrast = true)

@Preview(name = "M5 · Voice review on Lemon, light high contrast")
@Composable
private fun VoiceReviewLemonHcPreview() = PreviewComposer(
    MomentComposerUiState(mode = ComposerMode.Voice, voiceBackground = "c-lemon", items = listOf(ComposerItem(ComposerCapture.Voice(ByteArray(0), "audio/mp4", 23_000, previewLevels)))),
    dark = false, highContrast = true,
)
