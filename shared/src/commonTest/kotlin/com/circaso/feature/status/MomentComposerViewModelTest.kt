package com.circaso.feature.status

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
import com.circaso.core.platform.MomentPickMax
import com.circaso.core.platform.PickedImage
import com.circaso.core.platform.Recording
import com.circaso.core.platform.RecordingStart
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
import com.circaso.feature.status.domain.PendingState
import com.circaso.feature.status.presentation.ComposerCapture
import com.circaso.feature.status.presentation.ComposerMode
import com.circaso.feature.status.presentation.ForwardMedia
import com.circaso.feature.status.presentation.GalleryTab
import com.circaso.feature.status.presentation.MomentComposerViewModel
import com.circaso.feature.status.presentation.MomentForwardViewModel
import com.circaso.feature.status.presentation.MomentForwarder
import com.circaso.feature.status.presentation.SpacesUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun photo(id: String, at: Long = 0) = LibraryItem(id, LibraryKind.Photo, addedAt = at)
private fun video(id: String, ms: Long, at: Long = 0) = LibraryItem(id, LibraryKind.Video, durationMs = ms, addedAt = at)
private fun jpeg(n: Int) = CapturedMedia(ByteArray(n), "image/jpeg", 10, 10, thumb = ByteArray(1))
/** A picked video as the platforms hand it over: on disk, no bytes in memory. */
private fun clip(path: String, ms: Long) = CapturedMedia(ByteArray(0), "video/mp4", 720, 1280, durationMs = ms, thumb = ByteArray(1), path = path)

private class FakeLibrary : MediaLibrary {
    val access = mutableMapOf(LibraryKind.Photo to LibraryAccess.Granted, LibraryKind.Video to LibraryAccess.Granted, LibraryKind.Audio to LibraryAccess.Granted)
    val items = mutableMapOf<LibraryKind, List<LibraryItem>>()
    val media = mutableMapOf<String, CapturedMedia>()
    val files = mutableMapOf<String, ByteArray>()
    val asked = mutableListOf<Pair<LibraryKind, Boolean>>()
    val managed = mutableListOf<LibraryKind>()
    var afterManage: LibraryAccess? = null
    val trims = mutableListOf<Triple<String, Long, Long>>()
    var trimResult: ((String, Long, Long) -> CapturedMedia?)? = { p, s, e -> clip("$p-cut", e - s) }
    var systemPicks: List<CapturedMedia> = emptyList()

    override suspend fun access() = access.getValue(LibraryKind.Photo)
    override suspend fun recents(kind: LibraryKind, limit: Int, ask: Boolean): LibraryRecents {
        asked += kind to ask
        val a = access.getValue(kind)
        return LibraryRecents(if (a == LibraryAccess.Denied) emptyList() else items[kind].orEmpty().take(limit), a)
    }
    override suspend fun manage(kind: LibraryKind): LibraryAccess { managed += kind; afterManage?.let { access[kind] = it }; return access.getValue(kind) }
    override suspend fun load(item: LibraryItem) = media[item.id]
    override suspend fun pick(kind: LibraryKind): CapturedMedia? = systemPicks.firstOrNull()
    override suspend fun pickMany(max: Int) = systemPicks.take(max)
    override suspend fun read(path: String) = files[path]
    override suspend fun filmstrip(path: String, count: Int) = List(count) { ByteArray(1) }
    override suspend fun trim(path: String, startMs: Long, endMs: Long): CapturedMedia? {
        trims += Triple(path, startMs, endMs)
        return trimResult?.invoke(path, startMs, endMs)?.also { files[it.path!!] = ByteArray(7) }
    }
}

private class ComposerFakeMoments : MomentsRepository {
    val posted = mutableListOf<MomentDraft>()
    override val moments: Flow<List<Moment>> = flowOf(emptyList())
    override val pending: Flow<List<PendingMoment>> = flowOf(emptyList())
    override val privacy: Flow<MomentPrivacy> = flowOf(MomentPrivacy())
    override val muted: Flow<Set<String>> = flowOf(emptySet())
    override suspend fun refresh() = AppResult.Success(Unit)
    override suspend fun post(draft: MomentDraft): AppResult<Unit> { posted += draft; return AppResult.Success(Unit) }
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

private class FakeCamera : MomentCamera {
    override suspend fun open() = CameraStart.Started
    override suspend fun takePhoto(): CapturedMedia? = jpeg(4)
    override suspend fun startVideo() = true
    override suspend fun stopVideo(): CapturedMedia? = null
    override fun flip() = Unit
    override fun setFlash(on: Boolean) = Unit
    override fun close() = Unit
}

private class Contacts : ContactsRepository {
    override val contacts: Flow<List<Contact>> = MutableStateFlow(listOf(Contact(Person("2", "Juma", null, null, null), "sync", "2026-11-01")))
    override val blocks: Flow<List<Person>> = MutableStateFlow(emptyList())
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

private class Me : ProfileRepository {
    override val profile: Flow<Profile?> = MutableStateFlow(null)
    override suspend fun refresh() = AppResult.Success(Unit)
    override suspend fun update(change: ProfileChange) = true
    override suspend fun checkHandle(handle: String): AppResult<HandleAvailability> = AppResult.Failure(AppError.Network)
    override suspend fun claimHandle(handle: String) = AppResult.Success(Unit)
    override suspend fun uploadAvatar(image: AvatarImage) = AppResult.Success(Unit)
    override suspend fun removeAvatar() = AppResult.Success(Unit)
    override suspend fun findByHandle(handle: String): AppResult<PublicProfile> = AppResult.Failure(AppError.Network)
    override suspend fun publicProfile(userId: String): AppResult<PublicProfile> = AppResult.Failure(AppError.Network)
}

private object NoPicker : ImagePicker { override suspend fun pick(): PickedImage? = null }
private object NoRecorder : AudioRecorder {
    override suspend fun start() = RecordingStart.Failed
    override suspend fun stop(): Recording? = null
    override suspend fun cancel() = Unit
}
private object NoPlayer : AudioPlayer { override suspend fun play(bytes: ByteArray, contentType: String) = Unit; override fun stop() = Unit }

/** A microphone whose meter rises with each read, so the waveform can be checked. */
private class RisingRecorder : AudioRecorder {
    var reads = 0
    override suspend fun start() = RecordingStart.Started
    override suspend fun stop(): Recording? = Recording(ByteArray(4), "audio/mp4", 1)
    override suspend fun cancel() = Unit
    override fun level(): Float = (++reads).toFloat()
}

/** A player that waits until stopped, and remembers pauses. */
private class HoldingPlayer : AudioPlayer {
    val calls = mutableListOf<String>()
    private val done = kotlinx.coroutines.CompletableDeferred<Unit>()
    override suspend fun play(bytes: ByteArray, contentType: String) { calls += "play"; done.await() }
    override fun stop() { calls += "stop"; done.complete(Unit) }
    override fun pause() { calls += "pause" }
    override fun resume() { calls += "resume" }
    override fun progress() = 0.4f
}

@OptIn(ExperimentalCoroutinesApi::class)
class MomentComposerViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val library = FakeLibrary()
    private val moments = ComposerFakeMoments()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private var clock = 0L
    private fun composer(recorder: AudioRecorder = NoRecorder, player: AudioPlayer = NoPlayer) =
        MomentComposerViewModel(moments, FakeCamera(), NoPicker, recorder, ObserveContactsUseCase(Contacts()), player, library, ObserveProfileUseCase(Me()), now = { clock })

    @Test fun aRecordedVoiceKeepsItsWaveformAndChosenBackground() = runTest(dispatcher) {
        val recorder = RisingRecorder()
        val vm = composer(recorder)
        vm.setMode(ComposerMode.Voice)
        vm.voiceDown()
        advanceTimeBy(MomentComposerViewModel.SampleMs * 99 + 1)
        assertTrue(vm.liveLevels.value.isNotEmpty())
        clock = 5_000
        vm.voiceUp()
        val voice = vm.state.value.capture as ComposerCapture.Voice
        val levels = assertNotNull(voice.levels)
        assertEquals(com.circaso.feature.status.domain.VoiceLevelCount, levels.size)
        assertEquals(1f, levels.last()) // the loudest is full height
        assertTrue(levels.first() < levels.last())
        assertTrue(vm.liveLevels.value.isEmpty())
        assertEquals("Metal", vm.state.value.voiceBackground) // as before, until chosen
        vm.onVoiceBackground("g-ocean")
        vm.share()
        val draft = moments.posted.single() as MomentDraft.Media
        assertEquals(MomentKind.Voice, draft.kind)
        assertEquals("g-ocean", draft.background)
        assertEquals(levels, draft.levels)
    }

    @Test fun listeningBackPausesWhereItIsAndCarriesOn() = runTest(dispatcher) {
        val player = HoldingPlayer()
        library.systemPicks = listOf(CapturedMedia(ByteArray(3), "audio/mp4", null, null, durationMs = 9_000))
        val vm = composer(player = player)
        vm.setMode(ComposerMode.Voice)
        vm.pickFromLibrary()
        assertNull((vm.state.value.capture as ComposerCapture.Voice).levels) // picked audio: the gentle fallback wave
        vm.togglePlayback()
        assertTrue(vm.state.value.playingBack)
        assertEquals(0.4f, vm.playbackProgress())
        vm.togglePlayback()
        assertTrue(vm.state.value.playbackHeld)
        assertFalse(vm.state.value.playingBack)
        vm.togglePlayback()
        assertEquals(listOf("play", "pause", "resume"), player.calls)
        vm.discardCapture()
        assertFalse(vm.state.value.playbackHeld)
    }

    @Test fun recordedLevelsAreEvenedIntoBarsAndScaled() {
        val bars = com.circaso.feature.status.domain.voiceLevels(List(96) { if (it == 50) 0.5f else 0.1f }, 48)
        assertEquals(48, bars.size)
        assertEquals(1f, bars[25])
        assertEquals(0.2f, bars[0], 0.0001f)
        assertTrue(com.circaso.feature.status.domain.voiceLevels(emptyList()).isEmpty())
        assertEquals(List(4) { 0f }, com.circaso.feature.status.domain.voiceLevels(List(10) { 0f }, 4))
        // Fewer samples than bars still fills every bar.
        assertEquals(48, com.circaso.feature.status.domain.voiceLevels(listOf(0.2f, 0.4f)).size)
    }

    @Test fun aVoiceBodyCarriesItsLevelsAndOlderBodiesStillRead() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val body = com.circaso.feature.status.data.repository.MomentBody(kind = "Voice", background = "c-teal", levels = listOf(0, 50, 100))
        val back = json.decodeFromString(com.circaso.feature.status.data.repository.MomentBody.serializer(), json.encodeToString(com.circaso.feature.status.data.repository.MomentBody.serializer(), body))
        assertEquals(listOf(0, 50, 100), back.levels)
        assertEquals("c-teal", back.background)
        val older = json.decodeFromString(com.circaso.feature.status.data.repository.MomentBody.serializer(), """{"v":1,"kind":"Voice"}""")
        assertNull(older.levels)
    }

    @Test fun eachKindAsksForItsOwnAccessAndAnEmptyStripSaysWhy() = runTest(dispatcher) {
        library.items[LibraryKind.Photo] = listOf(photo("p1"))
        library.access[LibraryKind.Video] = LibraryAccess.Denied
        val vm = composer()
        assertEquals(listOf("p1"), vm.state.value.recents.map { it.id })
        vm.setMode(ComposerMode.Video)
        // Photo access alone is not video access: the video strip asks for its own, and says it has none.
        assertEquals(LibraryKind.Video to true, library.asked.last())
        assertEquals(LibraryAccess.Denied, vm.state.value.recentsAccess)
        assertTrue(vm.state.value.recents.isEmpty())
        // Allowing it reads the strip again, without asking a second time.
        library.items[LibraryKind.Video] = listOf(video("v1", 5_000))
        library.afterManage = LibraryAccess.Limited
        vm.manageAccess()
        assertEquals(listOf(LibraryKind.Video), library.managed)
        assertEquals(LibraryKind.Video to false, library.asked.last())
        assertEquals(LibraryAccess.Limited, vm.state.value.recentsAccess)
        assertEquals(listOf("v1"), vm.state.value.recents.map { it.id })
    }

    @Test fun aTapUsesOneAndSharePostsIt() = runTest(dispatcher) {
        library.items[LibraryKind.Photo] = listOf(photo("p1"))
        library.media["p1"] = jpeg(9)
        val vm = composer()
        vm.useRecent(photo("p1"))
        assertEquals(1, vm.state.value.items.size)
        vm.onCaption("Graduation day")
        vm.share()
        val draft = moments.posted.single() as MomentDraft.Media
        assertEquals(MomentKind.Image, draft.kind)
        assertEquals("Graduation day", draft.caption)
        assertTrue(vm.state.value.shared)
    }

    @Test fun holdingATileStartsPickingSeveralInOrderUpToTen() = runTest(dispatcher) {
        val vm = composer()
        vm.startSelecting(photo("a"))
        vm.useRecent(photo("b")) // while picking, a tap ticks
        vm.useRecent(photo("c"))
        vm.useRecent(photo("b")) // and a second tap unticks
        assertEquals(listOf("a", "c"), vm.state.value.selection.map { it.id })
        repeat(MomentPickMax) { vm.toggle(photo("x$it")) }
        assertEquals(MomentPickMax, vm.state.value.selection.size)
        assertEquals("You can pick up to 10 at a time.", vm.state.value.error)
        vm.clearSelection()
        assertFalse(vm.state.value.selecting)
    }

    @Test fun severalPickedPostAsTheirOwnMomentsInTheOrderTicked() = runTest(dispatcher) {
        library.media["a"] = jpeg(1)
        library.media["v"] = clip("/tmp/v.mp4", 8_000)
        library.files["/tmp/v.mp4"] = ByteArray(5)
        library.media["c"] = jpeg(3)
        val vm = composer()
        vm.openGallery()
        vm.toggle(photo("a")); vm.toggle(video("v", 8_000)); vm.toggle(photo("c"))
        vm.useSelection()
        val s = vm.state.value
        assertNull(s.gallery)
        assertEquals(3, s.items.size)
        // Each keeps its own caption.
        vm.select(1); vm.onCaption("the video")
        vm.select(2); vm.onCaption("last")
        vm.share()
        val posted = moments.posted.map { it as MomentDraft.Media }
        assertEquals(listOf(MomentKind.Image, MomentKind.Video, MomentKind.Image), posted.map { it.kind })
        assertEquals(listOf("", "the video", "last"), posted.map { it.caption })
        // The video was on disk, not in memory: read when it was posted.
        assertEquals(5, posted[1].bytes.size)
    }

    @Test fun theGalleryMergesPhotosAndVideosNewestFirst() = runTest(dispatcher) {
        library.items[LibraryKind.Photo] = listOf(photo("p-new", at = 30), photo("p-old", at = 10))
        library.items[LibraryKind.Video] = listOf(video("v-mid", 4_000, at = 20))
        val vm = composer()
        vm.openGallery()
        assertEquals(listOf("p-new", "v-mid", "p-old"), vm.state.value.gallery!!.items.map { it.id })
        vm.galleryTab(GalleryTab.Videos)
        assertEquals(listOf("v-mid"), vm.state.value.gallery!!.items.map { it.id })
    }

    @Test fun aVideoOverAMinuteOpensTheTrimmerAndCannotBeSharedUntilTrimmed() = runTest(dispatcher) {
        library.media["long"] = clip("/tmp/long.mp4", 134_000)
        val vm = composer()
        vm.useRecent(video("long", 134_000))
        val trim = assertNotNull(vm.state.value.trim)
        assertTrue(trim.required)
        assertEquals(0L to 60_000L, trim.startMs to trim.endMs)
        assertEquals(8, trim.frames.size)
        assertFalse(vm.state.value.canShare)
        // Never longer than a minute, never shorter than a second.
        vm.setTrim(21_000, 130_000)
        assertEquals(60_000L, vm.state.value.trim!!.selectedMs)
        vm.setTrim(21_000, 21_200)
        assertEquals(1_000L, vm.state.value.trim!!.selectedMs)
        vm.setTrim(21_000, 79_000)
        vm.applyTrim()
        assertEquals(Triple("/tmp/long.mp4", 21_000L, 79_000L), library.trims.single())
        assertNull(vm.state.value.trim)
        assertTrue(vm.state.value.canShare)
        assertEquals(58_000L, (vm.state.value.capture as ComposerCapture.Shot).media.durationMs)
    }

    @Test fun cancellingARequiredTrimLeavesTheVideoOut() = runTest(dispatcher) {
        library.media["a"] = jpeg(1)
        library.media["long"] = clip("/tmp/long.mp4", 90_000)
        val vm = composer()
        vm.toggle(photo("a")); vm.toggle(video("long", 90_000))
        vm.useSelection()
        assertNotNull(vm.state.value.trim)
        vm.cancelTrim()
        assertNull(vm.state.value.trim)
        assertEquals(1, vm.state.value.items.size)
        assertTrue(vm.state.value.canShare)
    }

    @Test fun aFailedTrimStaysOpenAndSaysSo() = runTest(dispatcher) {
        library.media["long"] = clip("/tmp/long.mp4", 90_000)
        library.trimResult = null
        val vm = composer()
        vm.useRecent(video("long", 90_000))
        vm.applyTrim()
        assertNotNull(vm.state.value.trim)
        assertFalse(vm.state.value.trim!!.working)
        assertEquals("This video couldn’t be trimmed on this phone. Try a shorter one.", vm.state.value.error)
    }

    @Test fun aVideoTooLargeToPostIsReencodedFirst() = runTest(dispatcher) {
        library.media["big"] = clip("/tmp/big.mp4", 50_000)
        library.files["/tmp/big.mp4"] = ByteArray((com.circaso.core.platform.MomentMediaMaxBytes + 1).toInt())
        val vm = composer()
        vm.useRecent(video("big", 50_000))
        vm.share()
        assertEquals(Triple("/tmp/big.mp4", 0L, 50_000L), library.trims.single())
        assertEquals(7, (moments.posted.single() as MomentDraft.Media).bytes.size)
    }

    @Test fun theSystemPickerIsThereWhenAccessIsOff() = runTest(dispatcher) {
        library.access[LibraryKind.Photo] = LibraryAccess.Denied
        library.access[LibraryKind.Video] = LibraryAccess.Denied
        library.systemPicks = listOf(jpeg(1), jpeg(2))
        val vm = composer()
        vm.openGallery()
        assertEquals(LibraryAccess.Denied, vm.state.value.gallery!!.access)
        vm.closeGallery(); vm.pickWithSystem()
        assertEquals(2, vm.state.value.items.size)
    }

    @Test fun theReviewForwardsTheItemOnTheStage() = runTest(dispatcher) {
        library.media["v"] = clip("/tmp/v.mp4", 8_000)
        val vm = composer()
        vm.useRecent(video("v", 8_000))
        vm.onCaption("hi")
        vm.openForward(true)
        assertTrue(vm.state.value.forwarding)
        val f = assertNotNull(vm.forwardItem())
        assertEquals(MomentKind.Video, f.kind)
        assertNull(f.bytes) // read from the file only when it is sent
        assertEquals("/tmp/v.mp4", f.path)
        assertEquals("hi", f.caption)
    }

    @Test fun theRingFillsOverTheWholeBatchAndNeverRunsBackwards() {
        fun p(progress: Float?, state: PendingState = PendingState.Posting) = PendingMoment("l$progress", MomentKind.Image, state, null, 0, progress)
        assertNull(SpacesUiState(pending = listOf(p(null))).postingProgress)
        assertEquals(0.5f, SpacesUiState(pending = listOf(p(1f), p(null))).postingProgress)
        assertEquals(0.25f, SpacesUiState(pending = listOf(p(0.25f), p(0.9f, PendingState.Failed))).postingProgress)
        // Three picked: the first has posted and left the queue, the next hasn't started. Still a third, not zero.
        assertEquals(1f / 3, SpacesUiState(pending = listOf(p(null), p(null)), postingBatch = 3).postingProgress)
    }
}

private class FakeChats(names: List<String>) : ChatsRepository {
    override val chats: Flow<List<ChatSummary>> = MutableStateFlow(names.mapIndexed { i, n ->
        ChatSummary(Chat("c$i", ChatKind.Direct, n, null, emptyList(), false, 1, 1, MemberRole.Member, "u$i"), null, 0)
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

@OptIn(ExperimentalCoroutinesApi::class)
class MomentForwardViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val media = ForwardMedia(MomentKind.Image, ByteArray(3), null, "image/jpeg", 1, 1, null, null, "cap")

    @Test fun searchTickAndSendToEachChosenChat() = runTest(dispatcher) {
        val sent = mutableListOf<String>()
        val vm = MomentForwardViewModel(FakeChats(listOf("Juma", "Achieng", "Mum")), MomentForwarder { id, _ -> sent += id; true })
        vm.onQuery("ach")
        assertEquals(listOf("Achieng"), vm.state.value.shown.map { it.title })
        vm.toggle("c1"); vm.toggle("c2")
        vm.send(media)
        assertEquals(listOf("c1", "c2"), sent)
        assertTrue(vm.state.value.sent)
    }

    @Test fun atMostFiveChatsAndAPartialFailureSaysSo() = runTest(dispatcher) {
        val vm = MomentForwardViewModel(FakeChats(List(7) { "P$it" }), MomentForwarder { id, _ -> id != "c0" })
        repeat(6) { vm.toggle("c$it") }
        assertEquals(5, vm.state.value.selected.size)
        assertEquals("You can forward to up to 5 chats at a time.", vm.state.value.error)
        vm.send(media)
        assertFalse(vm.state.value.sent)
        assertEquals("Forwarded to some chats. Try again for the rest.", vm.state.value.error)
    }

    @Test fun tryingAgainSendsOnlyToTheChatsThatFailed() = runTest(dispatcher) {
        val sent = mutableListOf<String>()
        var failFirst = true
        val vm = MomentForwardViewModel(FakeChats(listOf("A", "B")), MomentForwarder { id, _ -> if (id == "c0" && failFirst) { failFirst = false; false } else { sent += id; true } })
        vm.toggle("c0"); vm.toggle("c1")
        vm.send(media)
        assertEquals(listOf("c0"), vm.state.value.selected)
        vm.send(media)
        assertEquals(listOf("c1", "c0"), sent)
        assertTrue(vm.state.value.sent)
        vm.reset()
        assertFalse(vm.state.value.sent)
        assertTrue(vm.state.value.selected.isEmpty())
    }

    @Test fun nothingToSendUntilAChatIsChosen() = runTest(dispatcher) {
        var calls = 0
        val vm = MomentForwardViewModel(FakeChats(listOf("Juma")), MomentForwarder { _, _ -> calls++; true })
        assertFalse(vm.state.value.canSend)
        vm.send(media)
        assertEquals(0, calls)
    }
}
