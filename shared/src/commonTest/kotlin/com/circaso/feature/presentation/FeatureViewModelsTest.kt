package com.circaso.feature.presentation

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.account.domain.AccountRepository
import com.circaso.feature.account.domain.DeleteAccountUseCase
import com.circaso.feature.account.domain.LinkProviderUseCase
import com.circaso.feature.account.domain.ObserveSecurityOverviewUseCase
import com.circaso.feature.account.domain.RecoveryCase
import com.circaso.feature.account.domain.RefreshSecurityOverviewUseCase
import com.circaso.feature.account.domain.SecurityEvent
import com.circaso.feature.account.domain.SecurityOverview
import com.circaso.feature.account.domain.UnlinkProviderUseCase
import com.circaso.feature.account.presentation.SecurityConfirm
import com.circaso.feature.account.presentation.SecurityLogViewModel
import com.circaso.feature.account.presentation.SecurityUiState
import com.circaso.feature.account.presentation.SecurityViewModel
import com.circaso.feature.assistant.domain.AshaEvent
import com.circaso.feature.assistant.domain.AshaMessage
import com.circaso.feature.assistant.domain.AshaRepository
import com.circaso.feature.assistant.domain.AshaUsage
import com.circaso.core.platform.AudioPlayer
import com.circaso.core.platform.ImagePicker
import com.circaso.core.platform.PickedImage
import com.circaso.feature.profile.domain.RemoveAvatarUseCase
import com.circaso.feature.profile.domain.UploadAvatarUseCase
import com.circaso.core.platform.AudioRecorder
import com.circaso.core.platform.Recording
import com.circaso.core.platform.RecordingStart
import com.circaso.feature.assistant.domain.SpeakUseCase
import com.circaso.feature.assistant.domain.TranscribeVoiceUseCase
import com.circaso.feature.assistant.presentation.VoiceState
import com.circaso.feature.assistant.domain.AskAshaUseCase
import com.circaso.feature.assistant.domain.AskedFrom
import com.circaso.feature.assistant.domain.DeleteAshaHistoryUseCase
import com.circaso.feature.assistant.domain.NoChatsToolRunner
import com.circaso.feature.assistant.domain.ObserveAshaHistoryUseCase
import com.circaso.feature.assistant.domain.RefreshAshaHistoryUseCase
import com.circaso.feature.assistant.domain.Snippet
import com.circaso.feature.assistant.presentation.AshaViewModel
import com.circaso.feature.auth.domain.AuthRepository
import com.circaso.feature.auth.domain.ForgetThisPhoneUseCase
import com.circaso.feature.auth.domain.PhoneVerification
import com.circaso.feature.auth.domain.SignInOutcome
import com.circaso.feature.auth.domain.SignInStep
import com.circaso.feature.auth.domain.SignOutUseCase
import com.circaso.feature.auth.domain.SocialCredential
import com.circaso.feature.auth.domain.SocialProvider
import com.circaso.feature.auth.domain.SocialSignIn
import com.circaso.feature.auth.domain.SocialSignInResult
import com.circaso.feature.contacts.domain.BlockUseCase
import com.circaso.feature.contacts.domain.ConnectUseCase
import com.circaso.feature.contacts.domain.ConnectVia
import com.circaso.feature.contacts.domain.Connection
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.CreateInviteUseCase
import com.circaso.feature.contacts.domain.Invite
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.contacts.domain.RefreshContactsUseCase
import com.circaso.feature.contacts.domain.RemoveContactUseCase
import com.circaso.feature.contacts.domain.SyncPhoneBookUseCase
import com.circaso.feature.contacts.domain.SyncSummary
import com.circaso.core.platform.ContactsReader
import com.circaso.feature.contacts.presentation.ContactsViewModel
import com.circaso.feature.profile.domain.AvatarImage
import com.circaso.feature.profile.domain.CheckHandleUseCase
import com.circaso.feature.profile.domain.ClaimHandleUseCase
import com.circaso.feature.profile.domain.HandleAvailability
import com.circaso.feature.profile.domain.HandleStatus
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.profile.domain.Privacy
import com.circaso.feature.profile.domain.Profile
import com.circaso.feature.profile.domain.ProfileChange
import com.circaso.feature.profile.domain.ProfileRepository
import com.circaso.feature.profile.domain.PublicProfile
import com.circaso.feature.profile.domain.UpdateProfileUseCase
import com.circaso.feature.profile.presentation.EditProfileViewModel
import com.circaso.feature.profile.presentation.HandleCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.flow.flow
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val me = Profile("u1", "Amina Wanjiru", "Hi", "amina", null, null, Privacy("contacts", "contacts", true), 3, false)
private fun person(id: String, name: String = "P$id", handle: String? = "h$id") = Person(id, name, handle, null, null)
private fun contact(id: String, name: String = "P$id") = Contact(person(id, name), "sync", "2026-10-20")
private fun api(code: String, status: Int = 400, retry: Int? = null) = AppResult.Failure(AppError.Api(code, status, "req", retry, emptyMap()))

private class FakeProfileRepo : ProfileRepository {
    val me = MutableStateFlow<Profile?>(com.circaso.feature.presentation.me)
    override val profile: Flow<Profile?> = me
    val updates = mutableListOf<ProfileChange>()
    val checks = mutableListOf<String>()
    val claims = mutableListOf<String>()
    var availability: AppResult<HandleAvailability> = AppResult.Success(HandleAvailability("x", true, HandleStatus.Available, null))
    var claim: AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun refresh() = AppResult.Success(Unit)
    override suspend fun update(change: ProfileChange): Boolean { updates += change; return true }
    override suspend fun checkHandle(handle: String): AppResult<HandleAvailability> { checks += handle; return availability }
    override suspend fun claimHandle(handle: String): AppResult<Unit> { claims += handle; return claim }
    val uploads = mutableListOf<Int>()
    var removed = 0
    var uploadResult: AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun uploadAvatar(image: AvatarImage): AppResult<Unit> { uploads += image.bytes.size; return uploadResult }
    override suspend fun removeAvatar(): AppResult<Unit> { removed++; return AppResult.Success(Unit) }
    override suspend fun findByHandle(handle: String): AppResult<PublicProfile> = AppResult.Failure(AppError.Network)
    override suspend fun publicProfile(userId: String): AppResult<PublicProfile> = AppResult.Failure(AppError.Network)
}

private class FakeContactsRepo : ContactsRepository {
    val list = MutableStateFlow(listOf(contact("1", "Achieng Akinyi"), contact("2", "Juma Kariuki")))
    override val contacts: Flow<List<Contact>> = list
    override val blocks: Flow<List<Person>> = MutableStateFlow(emptyList())
    var connectResult: AppResult<Connection> = AppResult.Success(Connection(person("9", "Kevin"), true))
    var inviteResult: AppResult<Invite> = AppResult.Success(Invite("t", "https://circaso.com/i/t", "qr", "2026-11-01"))
    val connected = mutableListOf<Pair<ConnectVia, String>>()
    val removed = mutableListOf<String>()
    val blocked = mutableListOf<String>()
    override suspend fun refreshContacts() = AppResult.Success(Unit)
    override suspend fun refreshBlocks() = AppResult.Success(Unit)
    var syncResult: AppResult<SyncSummary> = AppResult.Success(SyncSummary(0, emptyList()))
    val synced = mutableListOf<List<String>>()
    override suspend fun syncPhoneBook(phones: List<String>): AppResult<SyncSummary> { synced += phones; return syncResult }
    override suspend fun connect(via: ConnectVia, value: String): AppResult<Connection> { connected += via to value; return connectResult }
    override suspend fun createInvite() = inviteResult
    override suspend fun revokeInvite(token: String) = AppResult.Success(Unit)
    override suspend fun removeContact(userId: String) { removed += userId }
    override suspend fun block(person: Person) { blocked += person.userId }
    override suspend fun unblock(userId: String) = Unit
    override suspend fun canReach(userId: String) = AppResult.Success(true)
}

private class FakeAccountRepo : AccountRepository {
    val overviewFlow = MutableStateFlow<SecurityOverview?>(null)
    override val overview: Flow<SecurityOverview?> = overviewFlow
    override val securityLog: Flow<List<SecurityEvent>> = MutableStateFlow(emptyList())
    var unlinkResult: AppResult<Unit> = AppResult.Success(Unit)
    var deleteResult: AppResult<Unit> = AppResult.Success(Unit)
    val calls = mutableListOf<String>()
    override suspend fun refreshOverview() = AppResult.Success(Unit)
    override suspend fun refreshSecurityLog() = AppResult.Success(Unit)
    override suspend fun linkProvider(provider: SocialProvider, credential: SocialCredential, nonce: String): AppResult<Unit> { calls += "link:${provider.apiName}"; return AppResult.Success(Unit) }
    override suspend fun unlinkProvider(provider: SocialProvider): AppResult<Unit> { calls += "unlink:${provider.apiName}"; return unlinkResult }
    override suspend fun deleteAccount(): AppResult<Unit> { calls += "delete"; return deleteResult }
    override suspend fun updatePushToken(token: String) = Unit
    override suspend fun requestRecovery(phone: String, contactEmail: String, note: String?) = AppResult.Success(RecoveryCase("r", "OPEN", null, null))
    override suspend fun recoveryStatus(requestId: String) = AppResult.Success(RecoveryCase(requestId, "OPEN", null, null))
}

private class FakeAuthRepo : AuthRepository {
    val calls = mutableListOf<String>()
    var forget: AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun requestNonce() = AppResult.Success("n")
    override suspend fun signInWithSocial(provider: SocialProvider, credential: SocialCredential, nonce: String): AppResult<SignInOutcome> = error("unused")
    override suspend fun startPhoneVerification(phone: String): AppResult<PhoneVerification> = error("unused")
    override suspend fun verifyPhoneCode(verificationId: String, code: String): AppResult<SignInStep> = error("unused")
    override suspend fun verifyPhoneWithCarrier(carrierToken: String): AppResult<SignInStep> = error("unused")
    override suspend fun registerDevice(pushToken: String?): AppResult<Unit> = error("unused")
    override suspend fun signOut() { calls += "signOut" }
    override suspend fun forgetThisPhone(): AppResult<Unit> { calls += "forget"; return forget }
}

private class FakeAsha(var script: List<AshaEvent> = emptyList()) : AshaRepository {
    private val messages = MutableStateFlow<List<AshaMessage>>(emptyList())
    override val history: Flow<List<AshaMessage>> = messages
    val asked = mutableListOf<String>()
    var cleared = 0
    override suspend fun refreshHistory() = AppResult.Success(Unit)
    override fun ask(text: String, askedFrom: AskedFrom?, chatReading: Boolean): Flow<AshaEvent> = flow { asked += text; script.forEach { emit(it) } }
    override suspend fun submitToolResults(turnId: String, callId: String, snippets: List<Snippet>) = AppResult.Success(Unit)
    override suspend fun deleteMessage(messageId: String) = Unit
    override suspend fun deleteAll() { cleared++ }
    var transcript: AppResult<String> = AppResult.Success("")
    val spoken = mutableListOf<String>()
    override suspend fun transcribe(audio: ByteArray, contentType: String, durationSeconds: Int) = transcript
    override suspend fun speak(text: String, language: String?): AppResult<ByteArray> { spoken += text; return AppResult.Success(ByteArray(1)) }
    override suspend fun usage(): AppResult<AshaUsage> = AppResult.Failure(AppError.Network)
}

private class AvatarLibrary : com.circaso.core.platform.MediaLibrary {
    var access = com.circaso.core.platform.LibraryAccess.Granted
    var afterManage: com.circaso.core.platform.LibraryAccess? = null
    val items = listOf(com.circaso.core.platform.LibraryItem("p1", com.circaso.core.platform.LibraryKind.Photo), com.circaso.core.platform.LibraryItem("p2", com.circaso.core.platform.LibraryKind.Photo))
    val asked = mutableListOf<Boolean>()
    var managed = 0
    var avatar: PickedImage? = PickedImage(ByteArray(9), "image/jpeg")
    var momentLoads = 0
    override suspend fun access() = access
    override suspend fun recents(kind: com.circaso.core.platform.LibraryKind, limit: Int, ask: Boolean): com.circaso.core.platform.LibraryRecents {
        asked += ask
        return com.circaso.core.platform.LibraryRecents(if (access == com.circaso.core.platform.LibraryAccess.Denied) emptyList() else items.take(limit), access)
    }
    override suspend fun manage(kind: com.circaso.core.platform.LibraryKind): com.circaso.core.platform.LibraryAccess { managed++; afterManage?.let { access = it }; return access }
    override suspend fun load(item: com.circaso.core.platform.LibraryItem): com.circaso.core.platform.CapturedMedia? { momentLoads++; return null }
    override suspend fun loadAvatar(item: com.circaso.core.platform.LibraryItem): PickedImage? = avatar
    override suspend fun pick(kind: com.circaso.core.platform.LibraryKind): com.circaso.core.platform.CapturedMedia? = null
}

private class AvatarCamera : com.circaso.core.platform.MomentCamera {
    var start = com.circaso.core.platform.CameraStart.Started
    var shot: PickedImage? = PickedImage(ByteArray(7), "image/jpeg")
    var opened = 0
    var closed = 0
    var momentShots = 0
    override suspend fun open(): com.circaso.core.platform.CameraStart = error("the profile camera never opens the Moments camera (it would ask for the microphone)")
    override suspend fun openForPhoto(): com.circaso.core.platform.CameraStart { opened++; return start }
    override suspend fun takePhoto(): com.circaso.core.platform.CapturedMedia? { momentShots++; return null }
    override suspend fun takeProfilePhoto(): PickedImage? = shot
    override suspend fun startVideo() = false
    override suspend fun stopVideo(): com.circaso.core.platform.CapturedMedia? = null
    override fun flip() = Unit
    override fun setFlash(on: Boolean) = Unit
    override fun close() { closed++ }
}

private class FakePicker(var image: PickedImage? = PickedImage(ByteArray(5), "image/jpeg")) : ImagePicker { var opened = 0; override suspend fun pick(): PickedImage? { opened++; return image } }

private class FakeRecorder(var start: RecordingStart = RecordingStart.Started, var clip: Recording? = Recording(ByteArray(3), "audio/mp4", 4)) : AudioRecorder {
    var cancelled = 0
    override suspend fun start() = start
    override suspend fun stop() = clip
    override suspend fun cancel() { cancelled++ }
}

private class FakePlayer : AudioPlayer {
    val played = mutableListOf<Int>()
    var stops = 0
    override suspend fun play(bytes: ByteArray, contentType: String) { played += bytes.size }
    override fun stop() { stops++ }
}

private class FakeReader(var access: Boolean = true, var numbers: List<String> = emptyList()) : ContactsReader {
    var requests = 0
    override suspend fun hasAccess() = access
    override suspend fun requestAccess(): Boolean { requests++; return access }
    override suspend fun readNumbers() = if (access) numbers else emptyList()
}

private class Monitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureViewModelsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val monitor = Monitor()
    private lateinit var connection: ConnectionState

    /** A screen always collects the state; ViewModels that share it only run while someone does. */
    private fun TestScope.keep(flow: StateFlow<*>) { backgroundScope.launch(dispatcher) { flow.collect { } } }

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // ---- Edit profile

    private val profileRepo = FakeProfileRepo()
    private val picker = FakePicker()
    private val library = AvatarLibrary()
    private val camera = AvatarCamera()
    private fun editProfile() = EditProfileViewModel(
        ObserveProfileUseCase(profileRepo), UpdateProfileUseCase(profileRepo), CheckHandleUseCase(profileRepo), ClaimHandleUseCase(profileRepo), connection,
        picker, UploadAvatarUseCase(profileRepo), RemoveAvatarUseCase(profileRepo), library, camera, debounceMillis = 400,
    )

    @Test fun thePhotoSheetAsksForTheLibraryOnlyWhenItOpens() = runTest(dispatcher) {
        val vm = editProfile()
        assertEquals(emptyList(), library.asked)
        vm.onPhotoSheetShown()
        assertEquals(listOf(true), library.asked)
        assertEquals(listOf("p1", "p2"), vm.state.value.recents.map { it.id })
        assertEquals(com.circaso.core.platform.LibraryAccess.Granted, vm.state.value.recentsAccess)
    }

    @Test fun withoutLibraryAccessTheStripIsEmptyAndLimitedAccessCanChooseMore() = runTest(dispatcher) {
        library.access = com.circaso.core.platform.LibraryAccess.Denied
        val vm = editProfile()
        vm.onPhotoSheetShown()
        assertEquals(emptyList(), vm.state.value.recents)
        assertEquals(com.circaso.core.platform.LibraryAccess.Denied, vm.state.value.recentsAccess)

        library.access = com.circaso.core.platform.LibraryAccess.Limited
        library.afterManage = com.circaso.core.platform.LibraryAccess.Granted
        vm.onManageRecents()
        assertEquals(1, library.managed)
        assertEquals(false, library.asked.last()) // after Manage the strip reloads without asking again
        assertEquals(com.circaso.core.platform.LibraryAccess.Granted, vm.state.value.recentsAccess)
        assertEquals(2, vm.state.value.recents.size)
    }

    @Test fun aRecentPhotoIsUploadedAtAvatarQualityNotAMomentsSize() = runTest(dispatcher) {
        val vm = editProfile()
        vm.onPhotoSheetShown()
        vm.onUseRecent(vm.state.value.recents.first())
        assertEquals(listOf(9), profileRepo.uploads)
        assertEquals(0, library.momentLoads)
        assertEquals("Your photo is updated.", vm.state.value.message)
        assertFalse(vm.state.value.avatarBusy)
    }

    @Test fun aRecentThatCantBeReadSaysSoAndUploadsNothing() = runTest(dispatcher) {
        library.avatar = null
        val vm = editProfile()
        vm.onUseRecent(library.items.first())
        assertEquals(emptyList(), profileRepo.uploads)
        assertEquals("Couldn't open that photo. Try another one.", vm.state.value.message)
        assertFalse(vm.state.value.avatarBusy)
    }

    @Test fun aCameraShotWaitsForUsePhotoThenUploads() = runTest(dispatcher) {
        val vm = editProfile()
        vm.onOpenCamera()
        assertEquals(com.circaso.core.platform.CameraStart.Started, vm.state.value.camera?.start)
        vm.onShutter()
        assertTrue(vm.state.value.camera?.shot != null)
        assertEquals(emptyList(), profileRepo.uploads) // nothing goes up before Use Photo
        assertEquals(0, camera.momentShots)

        vm.onRetake()
        assertNull(vm.state.value.camera?.shot)
        vm.onShutter()
        vm.onUsePhoto()
        assertNull(vm.state.value.camera)
        assertEquals(1, camera.closed)
        assertEquals(listOf(7), profileRepo.uploads)
        assertEquals("Your photo is updated.", vm.state.value.message)
        assertFalse(vm.state.value.avatarBusy)
    }

    @Test fun aRefusedCameraSaysSoAndCloses() = runTest(dispatcher) {
        camera.start = com.circaso.core.platform.CameraStart.PermissionDenied
        val vm = editProfile()
        vm.onOpenCamera()
        assertEquals(com.circaso.core.platform.CameraStart.PermissionDenied, vm.state.value.camera?.start)
        vm.onShutter() // nothing to shoot with
        assertNull(vm.state.value.camera?.shot)
        vm.onCloseCamera()
        assertNull(vm.state.value.camera)
        assertEquals(emptyList(), profileRepo.uploads)
    }

    @Test fun aFailedShotSaysSoAndKeepsTheCameraOpen() = runTest(dispatcher) {
        camera.shot = null
        val vm = editProfile()
        vm.onOpenCamera()
        vm.onShutter()
        assertEquals("Couldn't take the photo. Try again.", vm.state.value.message)
        assertFalse(vm.state.value.camera?.shooting ?: true)
        assertNull(vm.state.value.camera?.shot)
    }

    @Test fun offlineTheCameraShotStaysAndNothingIsUploaded() = runTest(dispatcher) {
        val vm = editProfile()
        vm.onOpenCamera()
        vm.onShutter()
        monitor.osStatus.value = ConnectionStatus.Offline
        vm.onUsePhoto()
        assertTrue(vm.state.value.camera?.shot != null)
        assertEquals(emptyList(), profileRepo.uploads)
        vm.onUseRecent(library.items.first())
        assertEquals(emptyList(), profileRepo.uploads)
    }

    @Test fun anUploadRefusedAfterTheCameraShowsTheServersReason() = runTest(dispatcher) {
        profileRepo.uploadResult = api("users.avatar_too_large", 413)
        val vm = editProfile()
        vm.onOpenCamera(); vm.onShutter(); vm.onUsePhoto()
        assertEquals(ErrorMessages.forError((profileRepo.uploadResult as AppResult.Failure).error), vm.state.value.message)
        assertFalse(vm.state.value.avatarBusy)
    }

    @Test fun aChosenPhotoIsUploadedAndTheScreenSaysSo() = runTest(dispatcher) {
        val vm = editProfile()
        vm.onChangePhoto()
        assertEquals(listOf(5), profileRepo.uploads)
        assertEquals("Your photo is updated.", vm.state.value.message)
        assertFalse(vm.state.value.avatarBusy)
    }

    @Test fun closingThePickerChangesNothing() = runTest(dispatcher) {
        picker.image = null
        val vm = editProfile()
        vm.onChangePhoto()
        assertEquals(emptyList(), profileRepo.uploads)
        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.avatarBusy)
    }

    @Test fun aPhotoNeedsAConnectionAndNeverOpensThePickerOffline() = runTest(dispatcher) {
        val vm = editProfile()
        monitor.osStatus.value = ConnectionStatus.Offline
        vm.onChangePhoto(); vm.onRemovePhoto()
        assertEquals(0, picker.opened)
        assertEquals(0, profileRepo.removed)
    }

    @Test fun aRefusedPhotoShowsTheServersReasonInPlainWords() = runTest(dispatcher) {
        profileRepo.uploadResult = api("users.avatar_invalid", 400)
        val vm = editProfile()
        vm.onChangePhoto()
        assertEquals(ErrorMessages.forError((profileRepo.uploadResult as AppResult.Failure).error), vm.state.value.message)
    }

    @Test fun theFormFillsOnceFromTheSavedProfileAndTypingIsNeverOverwritten() = runTest(dispatcher) {
        val vm = editProfile()
        assertEquals("Amina Wanjiru", vm.state.value.name)
        vm.onName("Amina K")
        profileRepo.me.value = me.copy(displayName = "Changed elsewhere", version = 4)
        assertEquals("Amina K", vm.state.value.name)
        assertEquals(4, vm.state.value.profile?.version)
    }

    @Test fun saveSendsOnlyWhatChangedAndTellsTheTruthWhenOffline() = runTest(dispatcher) {
        val vm = editProfile()
        assertFalse(vm.state.value.canSave)
        vm.onName("Amina K"); vm.onSave()
        assertEquals(ProfileChange(displayName = "Amina K", about = null), profileRepo.updates.single())
        assertEquals("Saved.", vm.state.value.message)
        monitor.osStatus.value = ConnectionStatus.Offline
        vm.onAbout("New about"); vm.onSave()
        assertEquals("Saved on this phone. It will update when you're back online.", vm.state.value.message)
    }

    @Test fun aBlankNameCannotBeSaved() = runTest(dispatcher) {
        val vm = editProfile(); vm.onName("   ")
        assertFalse(vm.state.value.canSave)
    }

    @Test fun theHandleIsCheckedOnlyAfterTheyStopTyping() = runTest(dispatcher) {
        val vm = editProfile()
        vm.onHandle("@Ami"); vm.onHandle("@Amina_k")
        assertEquals(emptyList(), profileRepo.checks)
        assertEquals(HandleCheck.Checking, vm.state.value.handleCheck)
        advanceTimeBy(401)
        assertEquals(listOf("amina_k"), profileRepo.checks) // one request, lower case, no @
        assertEquals(HandleCheck.Available, vm.state.value.handleCheck)
        assertTrue(vm.state.value.canClaim)
    }

    @Test fun tooShortOrUnchangedHandlesNeverHitTheServer() = runTest(dispatcher) {
        val vm = editProfile()
        vm.onHandle("ab"); advanceTimeBy(1_000)
        assertEquals(HandleCheck.Invalid, vm.state.value.handleCheck)
        vm.onHandle("amina"); advanceTimeBy(1_000)
        assertEquals(HandleCheck.Idle, vm.state.value.handleCheck)
        assertEquals(emptyList(), profileRepo.checks)
    }

    @Test fun aTakenHandleOffersTheSuggestionAndCannotBeClaimed() = runTest(dispatcher) {
        profileRepo.availability = AppResult.Success(HandleAvailability("x", false, HandleStatus.Taken, "amina2"))
        val vm = editProfile(); vm.onHandle("amina_k"); advanceTimeBy(401)
        assertEquals(HandleCheck.Taken("amina2"), vm.state.value.handleCheck)
        assertFalse(vm.state.value.canClaim)
    }

    @Test fun claimingNeedsAConnectionAndReportsTheServersAnswer() = runTest(dispatcher) {
        val vm = editProfile(); vm.onHandle("amina_k"); advanceTimeBy(401)
        monitor.osStatus.value = ConnectionStatus.Offline
        assertFalse(vm.state.value.canClaim)
        monitor.osStatus.value = ConnectionStatus.Online
        profileRepo.claim = api("users.handle_cooldown", 409)
        vm.onClaimHandle()
        assertEquals("You changed your handle recently. You can change it again later.", vm.state.value.message)
        profileRepo.claim = AppResult.Success(Unit)
        vm.onClaimHandle()
        assertEquals("Your handle is now @amina_k.", vm.state.value.message)
    }

    // ---- Contacts

    private val contactsRepo = FakeContactsRepo()
    private val reader = FakeReader()
    private fun contactsVm() = ContactsViewModel(
        ObserveContactsUseCase(contactsRepo), RefreshContactsUseCase(contactsRepo), ConnectUseCase(contactsRepo), CreateInviteUseCase(contactsRepo),
        RemoveContactUseCase(contactsRepo), BlockUseCase(contactsRepo), connection, reader, SyncPhoneBookUseCase(contactsRepo),
    )

    @Test fun findingFriendsExplainsFirstThenAsksThePhoneForAccess() = runTest(dispatcher) {
        val vm = contactsVm(); keep(vm.state)
        vm.onFindFriends()
        assertTrue(vm.state.value.showFindFriends)
        assertEquals(0, reader.requests) // nothing is requested until the person says Allow
        vm.onFindFriendsDismissed()
        assertFalse(vm.state.value.showFindFriends)
        assertEquals(0, reader.requests)
    }

    @Test fun refusingAccessSendsNothingAndSaysHowToAllowIt() = runTest(dispatcher) {
        reader.access = false; reader.numbers = listOf("0712345678")
        val vm = contactsVm(); keep(vm.state)
        vm.onFindFriends(); vm.onAllowAccess()
        assertEquals(emptyList(), contactsRepo.synced)
        assertEquals("Circaso can't see your contacts. You can allow it in your phone's settings.", vm.state.value.message)
    }

    @Test fun onlyNormalisedNumbersAreSentAndMatchesAreCounted() = runTest(dispatcher) {
        reader.numbers = listOf("0712 345 678", "+254712345678", "junk", "0722104556")
        contactsRepo.syncResult = AppResult.Success(SyncSummary(2, listOf(com.circaso.feature.contacts.domain.SyncMatch("+254712345678", person("5")), com.circaso.feature.contacts.domain.SyncMatch("+254722104556", person("6")))))
        val vm = contactsVm(); keep(vm.state)
        vm.onAllowAccess()
        assertEquals(listOf(listOf("+254712345678", "+254722104556")), contactsRepo.synced)
        assertEquals("Found 2 friends on Circaso.", vm.state.value.message)
        assertFalse(vm.state.value.syncing)
    }

    @Test fun theMessageIsHonestWhenNoOneIsOnCircasoYetOrNothingIsNew() = runTest(dispatcher) {
        reader.numbers = listOf("0712345678")
        contactsRepo.syncResult = AppResult.Success(SyncSummary(1, emptyList()))
        val vm = contactsVm(); keep(vm.state)
        vm.onAllowAccess()
        assertEquals("No one from your contacts is on Circaso yet.", vm.state.value.message)
        contactsRepo.syncResult = AppResult.Success(SyncSummary(0, emptyList()))
        vm.onAllowAccess()
        assertEquals("You're up to date.", vm.state.value.message)
    }

    @Test fun theHourlyLimitIsExplainedPlainly() = runTest(dispatcher) {
        reader.numbers = listOf("0712345678")
        contactsRepo.syncResult = api("request.rate_limited", 429, retry = 1800)
        val vm = contactsVm(); keep(vm.state)
        vm.onAllowAccess()
        assertEquals("You can look for friends 5 times an hour. Try again a little later.", vm.state.value.message)
    }

    @Test fun searchFiltersByNameOrHandle() = runTest(dispatcher) {
        val vm = contactsVm(); keep(vm.state)
        vm.onQuery("juma")
        assertEquals(listOf("2"), vm.state.value.visible.map { it.person.userId })
        vm.onQuery("@h1")
        assertEquals(listOf("1"), vm.state.value.visible.map { it.person.userId })
    }

    @Test fun connectingStripsTheAtSignAndClearsTheBoxOnSuccess() = runTest(dispatcher) {
        val vm = contactsVm(); keep(vm.state)
        vm.onConnectInput("@kevo"); vm.onConnect()
        assertEquals(ConnectVia.Handle to "kevo", contactsRepo.connected.single())
        assertEquals("", vm.state.value.connectInput)
        assertEquals("Connected with Kevin.", vm.state.value.message)
    }

    @Test fun aFailedConnectKeepsWhatWasTypedAndSaysWhy() = runTest(dispatcher) {
        contactsRepo.connectResult = api("users.handle_not_found", 404)
        val vm = contactsVm(); keep(vm.state)
        vm.onConnectInput("nobody"); vm.onConnect()
        assertEquals("nobody", vm.state.value.connectInput)
        assertEquals("We couldn't find anyone with that handle.", vm.state.value.message)
    }

    @Test fun removingAndBlockingNeedAConfirmationFirst() = runTest(dispatcher) {
        val vm = contactsVm(); keep(vm.state)
        val achieng = contactsRepo.list.value[0]
        vm.onSelect(achieng); vm.onBlockRequested(achieng)
        assertEquals(emptyList(), contactsRepo.blocked) // nothing happens until confirmed
        vm.onActionConfirmed()
        assertEquals(listOf("1"), contactsRepo.blocked)
        assertEquals(listOf("1"), contactsRepo.removed) // a blocked person also leaves your contacts
        vm.onRemoveRequested(contactsRepo.list.value[1]); vm.onActionDismissed(); vm.onActionConfirmed()
        assertEquals(listOf("1"), contactsRepo.removed) // cancelled: not removed
    }

    @Test fun anInviteLinkIsShownAndCanBeDismissed() = runTest(dispatcher) {
        val vm = contactsVm(); keep(vm.state)
        vm.onInvite()
        assertEquals("https://circaso.com/i/t", vm.state.value.invite?.url)
        vm.onInviteDismissed()
        assertNull(vm.state.value.invite)
    }

    // ---- Security

    private val accountRepo = FakeAccountRepo()
    private val authRepo = FakeAuthRepo()
    private class Social(var result: SocialSignInResult) : SocialSignIn { override suspend fun signIn(provider: SocialProvider, nonce: String) = result }
    private fun security(social: SocialSignInResult = SocialSignInResult.Unavailable) = SecurityViewModel(
        ObserveSecurityOverviewUseCase(accountRepo), RefreshSecurityOverviewUseCase(accountRepo),
        LinkProviderUseCase(authRepo, accountRepo, Social(social)), UnlinkProviderUseCase(accountRepo),
        ForgetThisPhoneUseCase(authRepo), DeleteAccountUseCase(accountRepo), SignOutUseCase(authRepo), connection,
    )

    @Test fun deletingNeedsTheWordDeleteTypedExactlyAndAConnection() = runTest(dispatcher) {
        val vm = security(); keep(vm.state)
        vm.onConfirmRequested(SecurityConfirm.DeleteAccount)
        vm.onDeleteText("delete"); assertFalse(vm.state.value.canDelete)
        vm.onDeleteText("DELETE"); assertTrue(vm.state.value.canDelete)
        monitor.osStatus.value = ConnectionStatus.Offline
        assertFalse(vm.state.value.canDelete)
    }

    @Test fun confirmingWithoutTypingDeleteDoesNothing() = runTest(dispatcher) {
        val vm = security(); keep(vm.state)
        vm.onConfirmRequested(SecurityConfirm.DeleteAccount); vm.onConfirmed()
        assertEquals(emptyList(), accountRepo.calls)
    }

    @Test fun aScheduledDeletionSignsThePersonOutOnThisPhone() = runTest(dispatcher) {
        val vm = security(); keep(vm.state)
        vm.onConfirmRequested(SecurityConfirm.DeleteAccount); vm.onDeleteText("DELETE"); vm.onConfirmed()
        assertEquals(listOf("delete"), accountRepo.calls)
        assertEquals(listOf("signOut"), authRepo.calls)
    }

    @Test fun aRefusedDeletionKeepsThePersonSignedInAndSaysWhy() = runTest(dispatcher) {
        accountRepo.deleteResult = api("identity.wallet_not_empty", 409)
        val vm = security(); keep(vm.state)
        vm.onConfirmRequested(SecurityConfirm.DeleteAccount); vm.onDeleteText("DELETE"); vm.onConfirmed()
        assertEquals(emptyList(), authRepo.calls)
        assertEquals("Send your money out first, then you can delete your account.", vm.state.value.message)
    }

    @Test fun theLastLoginCannotBeRemovedAndTheMessageSaysSo() = runTest(dispatcher) {
        accountRepo.unlinkResult = api("identity.provider_last", 409)
        val vm = security(); keep(vm.state)
        vm.onConfirmRequested(SecurityConfirm.Unlink(SocialProvider.Google)); vm.onConfirmed()
        assertEquals("That's your only sign-in, so it can't be removed.", vm.state.value.message)
    }

    @Test fun removingThisPhoneNeedsTheServerAndIsNotRetriedLocally() = runTest(dispatcher) {
        authRepo.forget = AppResult.Failure(AppError.Network)
        val vm = security(); keep(vm.state)
        vm.onConfirmRequested(SecurityConfirm.RemovePhone); vm.onConfirmed()
        assertEquals(listOf("forget"), authRepo.calls)
        assertEquals("No connection. Check your internet and try again.", vm.state.value.message)
    }

    @Test fun linkingReportsCancelUnavailableAndSuccessPlainly() = runTest(dispatcher) {
        var vm = security(SocialSignInResult.Cancelled); keep(vm.state)
        vm.onLink(SocialProvider.Apple); assertNull(vm.state.value.message)
        vm = security(SocialSignInResult.Unavailable); keep(vm.state)
        vm.onLink(SocialProvider.Apple); assertEquals("Linking Apple isn't available in this version yet.", vm.state.value.message)
        vm = security(SocialSignInResult.Success(SocialCredential("t"))); keep(vm.state)
        vm.onLink(SocialProvider.Google); assertEquals("Google is linked.", vm.state.value.message)
    }

    @Test fun eventTypesAreShownInPlainWordsAndUnknownOnesNeverRaw() {
        assertEquals("Signed in", SecurityLogViewModel.label("sign_in"))
        assertEquals("New phone", SecurityLogViewModel.label("device_added"))
        assertEquals("Something new happened", SecurityLogViewModel.label("something_new_happened"))
    }

    // ---- Asha

    private val asha = FakeAsha()
    private val recorder = FakeRecorder()
    private val player = FakePlayer()
    private fun ashaVm() = AshaViewModel(
        ObserveAshaHistoryUseCase(asha), RefreshAshaHistoryUseCase(asha), AskAshaUseCase(asha, NoChatsToolRunner), DeleteAshaHistoryUseCase(asha), connection,
        recorder, player, TranscribeVoiceUseCase(asha), SpeakUseCase(asha),
    )
    private val reply = AshaMessage("m", "assistant", "Hello there", null, null, null, "2026-10-25T10:00:00Z")

    @Test fun anEmptyOrOfflineDraftCannotBeSent() = runTest(dispatcher) {
        val vm = ashaVm(); keep(vm.state)
        vm.onDraft("   "); vm.onSend()
        assertEquals(emptyList(), asha.asked)
        monitor.osStatus.value = ConnectionStatus.Offline
        vm.onDraft("hi"); vm.onSend()
        assertEquals(emptyList(), asha.asked)
        assertFalse(vm.state.value.canSend)
    }

    @Test fun theQuestionIsSentOnceAndTheDraftIsClearedWhileAnswering() = runTest(dispatcher) {
        asha.script = listOf(AshaEvent.Delta("Hel"), AshaEvent.Delta("lo"), AshaEvent.Done(reply))
        val vm = ashaVm(); keep(vm.state)
        vm.onDraft("hi there"); vm.onSend(); vm.onSend()
        assertEquals(listOf("hi there"), asha.asked)
        assertEquals("", vm.state.value.draft)
        assertNull(vm.state.value.pendingQuestion) // finished
        assertEquals("", vm.state.value.streaming)
    }

    @Test fun aFailureGivesTheQuestionBackSoItIsNotLost() = runTest(dispatcher) {
        asha.script = listOf(AshaEvent.Failed(AppError.Network))
        val vm = ashaVm(); keep(vm.state)
        vm.onDraft("how much did I send?"); vm.onSend()
        assertEquals("how much did I send?", vm.state.value.draft)
        assertEquals("No connection. Check your internet and try again.", vm.state.value.error)
    }

    @Test fun theDailyLimitSaysWhenItResets() = runTest(dispatcher) {
        asha.script = listOf(AshaEvent.Failed(AppError.Api("assistant.quota_exceeded", 429, "r", 7200, emptyMap())))
        val vm = ashaVm(); keep(vm.state)
        vm.onDraft("hi"); vm.onSend()
        assertEquals("You've used today's questions for Asha. They reset in about 2 hours.", vm.state.value.error)
    }

    @Test fun clearingTheConversationNeedsConfirmation() = runTest(dispatcher) {
        val vm = ashaVm(); keep(vm.state)
        vm.onClearRequested(); vm.onClearDismissed(); vm.onClearConfirmed()
        assertEquals(0, asha.cleared)
        vm.onClearRequested(); vm.onClearConfirmed()
        assertEquals(1, asha.cleared)
    }

    @Test fun aSpokenQuestionLandsInTheDraftForTheReaderToCheckBeforeSending() = runTest(dispatcher) {
        asha.transcript = AppResult.Success("how do I add a friend")
        val vm = ashaVm(); keep(vm.state)
        vm.onDraft("hi,"); vm.onMic()
        assertEquals(VoiceState.Recording, vm.state.value.voice)
        vm.onMic()
        assertEquals(VoiceState.Idle, vm.state.value.voice)
        assertEquals("hi, how do I add a friend", vm.state.value.draft)
        assertEquals(emptyList(), asha.asked) // never sent on its own
    }

    @Test fun aDeniedMicrophoneSaysHowToFixItAndNeverRecords() = runTest(dispatcher) {
        recorder.start = RecordingStart.PermissionDenied
        val vm = ashaVm(); keep(vm.state)
        vm.onMic()
        assertEquals(VoiceState.Idle, vm.state.value.voice)
        assertEquals("Allow the microphone in Settings to ask Asha by voice.", vm.state.value.error)
    }

    @Test fun anEmptyRecordingIsReportedNotSent() = runTest(dispatcher) {
        recorder.clip = null
        val vm = ashaVm(); keep(vm.state)
        vm.onMic(); vm.onMic()
        assertEquals(VoiceState.Idle, vm.state.value.voice)
        assertEquals("Nothing was recorded. Hold on a little longer and try again.", vm.state.value.error)
    }

    @Test fun aFailedTranscriptKeepsWhatWasTypedAndSaysWhy() = runTest(dispatcher) {
        asha.transcript = AppResult.Failure(AppError.Network)
        val vm = ashaVm(); keep(vm.state)
        vm.onDraft("keep me"); vm.onMic(); vm.onMic()
        assertEquals("keep me", vm.state.value.draft)
        assertEquals("No connection. Check your internet and try again.", vm.state.value.error)
    }

    @Test fun voiceIsNotOfferedOffline() = runTest(dispatcher) {
        val vm = ashaVm(); keep(vm.state)
        monitor.osStatus.value = ConnectionStatus.Offline
        assertFalse(vm.state.value.canUseVoice)
        vm.onMic()
        assertEquals(VoiceState.Idle, vm.state.value.voice)
    }

    @Test fun readingAReplyAloudPlaysItOnceAndTapsAgainStop() = runTest(dispatcher) {
        val vm = ashaVm(); keep(vm.state)
        vm.onSpeak("Hello there")
        assertEquals(listOf("Hello there"), asha.spoken)
        assertEquals(listOf(1), player.played)
        assertNull(vm.state.value.speakingText) // finished
    }

    @Test fun leavingTheScreenWhileRecordingThrowsTheClipAway() = runTest(dispatcher) {
        val vm = ashaVm(); keep(vm.state)
        vm.onMic(); vm.onCancelRecording()
        assertEquals(VoiceState.Idle, vm.state.value.voice)
        assertEquals(1, recorder.cancelled)
    }
}
