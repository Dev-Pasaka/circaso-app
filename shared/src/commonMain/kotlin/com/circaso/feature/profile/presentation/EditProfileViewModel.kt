package com.circaso.feature.profile.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.CameraStart
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ImagePicker
import com.circaso.core.platform.LibraryAccess
import com.circaso.core.platform.LibraryItem
import com.circaso.core.platform.LibraryKind
import com.circaso.core.platform.MediaLibrary
import com.circaso.core.platform.MomentCamera
import com.circaso.core.platform.PickedImage
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.profile.domain.AvatarImage
import com.circaso.feature.profile.domain.CheckHandleUseCase
import com.circaso.feature.profile.domain.ClaimHandleUseCase
import com.circaso.feature.profile.domain.HandleStatus
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.profile.domain.Profile
import com.circaso.feature.profile.domain.ProfileChange
import com.circaso.feature.profile.domain.RemoveAvatarUseCase
import com.circaso.feature.profile.domain.UpdateProfileUseCase
import com.circaso.feature.profile.domain.UploadAvatarUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the handle field is saying about the typed handle. */
sealed interface HandleCheck {
    data object Idle : HandleCheck
    data object Checking : HandleCheck
    data object Available : HandleCheck
    data class Taken(val suggestion: String?) : HandleCheck
    data object Invalid : HandleCheck

    /** Could not ask (offline or a server problem): nothing is claimed. */
    data object Unavailable : HandleCheck

    /** Checks share the 30-an-hour handle lookup limit; this many seconds until the next one is allowed, when known. */
    data class TooManyChecks(val retryAfterSeconds: Int?) : HandleCheck
}

/**
 * The full-screen camera for a new profile photo. [start] is null while the camera opens; [shooting] while the shutter
 * is working; [shot] is the photo waiting for Use Photo or Retake (already encoded for upload).
 */
data class ProfileCamera(val start: CameraStart? = null, val shooting: Boolean = false, val shot: PickedImage? = null)

data class EditProfileUiState(
    val profile: Profile? = null,
    /** Everything the person changed was saved: the screen closes (Apple's Save commits and leaves). */
    val saved: Boolean = false,
    val name: String = "",
    val about: String = "",
    val handle: String = "",
    val handleCheck: HandleCheck = HandleCheck.Idle,
    val claiming: Boolean = false,
    /** A picture is being chosen, uploaded or removed. */
    val avatarBusy: Boolean = false,
    val online: Boolean = true,
    /** One line under the form: a confirmation or an error. */
    val message: String? = null,
    /** The photo sheet's recents strip, newest first; empty without library access. */
    val recents: List<LibraryItem> = emptyList(),
    /** Null until the sheet has asked. Denied hides the strip (the buttons stay); Limited adds Manage. */
    val recentsAccess: LibraryAccess? = null,
    /** The profile camera while it is open. */
    val camera: ProfileCamera? = null,
) {
    val nameChanged get() = profile != null && name.trim() != profile.displayName
    val aboutChanged get() = profile != null && about.trim() != profile.about.orEmpty()
    val canSave get() = profile != null && (nameChanged || aboutChanged) && name.isNotBlank()

    /** `null` when the handle may be changed now; otherwise why not. */
    val handleLockedUntil get() = profile?.handleChangeableAt?.takeIf { at ->
        // Handles can now change whenever the new one is free (decision 2026-10-30); only a time still ahead locks it.
        runCatching { kotlin.time.Instant.parse(at) > kotlin.time.Clock.System.now() }.getOrDefault(false)
    }
    /** Pictures go straight to the server (they are not queued), so they need a connection. */
    val canChangeAvatar get() = online && !avatarBusy && profile != null
    val canClaim get() = online && !claiming && handleCheck == HandleCheck.Available && handle != profile?.handle
}

/** Edit your own profile. Name and about are saved on the phone at once and sent when online; the handle needs the server. */
class EditProfileViewModel(
    observeProfile: ObserveProfileUseCase,
    private val updateProfile: UpdateProfileUseCase,
    private val checkHandle: CheckHandleUseCase,
    private val claimHandle: ClaimHandleUseCase,
    connection: ConnectionState,
    private val picker: ImagePicker,
    private val uploadAvatar: UploadAvatarUseCase,
    private val removeAvatar: RemoveAvatarUseCase,
    private val library: MediaLibrary,
    /** The in-app camera (shared with Moments); the screen hands it to the viewfinder. */
    val cameraDevice: MomentCamera,
    private val debounceMillis: Long = 400,
) : ViewModel() {
    private val _state = MutableStateFlow(EditProfileUiState())
    val state: StateFlow<EditProfileUiState> = _state.asStateFlow()
    private var checkJob: Job? = null
    private var recentsJob: Job? = null

    init {
        viewModelScope.launch {
            // Fill the form once from what is saved; later changes (a sync) update the profile but never overwrite typing.
            val first = observeProfile().first { it != null }!!
            _state.update { it.copy(profile = first, name = first.displayName, about = first.about.orEmpty(), handle = first.handle.orEmpty()) }
            observeProfile().collect { p -> if (p != null) _state.update { it.copy(profile = p) } }
        }
        viewModelScope.launch { connection.status.collect { s -> _state.update { it.copy(online = s == ConnectionStatus.Online) } } }
    }

    fun onName(text: String) = _state.update { it.copy(name = text, message = null) }
    fun onAbout(text: String) = _state.update { it.copy(about = text, message = null) }

    fun onSave() {
        val s = _state.value
        if (!s.canSave) return
        val change = ProfileChange(
            displayName = if (s.nameChanged) s.name.trim() else null,
            about = if (s.aboutChanged) s.about.trim() else null,
        )
        viewModelScope.launch {
            updateProfile(change)
            _state.update { it.copy(message = if (it.online) "Saved." else "Saved on this phone. It will update when you're back online.") }
        }
    }

    /** The nav bar's Save: name and about, and the handle when it changed and is free; closes when all of it went. */
    fun onSaveAll() {
        val s = _state.value
        val handleChanged = s.handle.isNotBlank() && s.handle != s.profile?.handle
        if (!s.canSave && !(handleChanged && s.canClaim)) return
        _state.update { it.copy(claiming = handleChanged, message = null) }
        viewModelScope.launch {
            if (s.canSave) {
                updateProfile(ProfileChange(displayName = if (s.nameChanged) s.name.trim() else null, about = if (s.aboutChanged) s.about.trim() else null))
            }
            if (handleChanged && s.canClaim) {
                when (val r = claimHandle(s.handle)) {
                    is AppResult.Success -> Unit
                    is AppResult.Failure -> { _state.update { it.copy(claiming = false, message = ErrorMessages.forError(r.error)) }; return@launch }
                }
            }
            _state.update { it.copy(claiming = false, saved = true) }
        }
    }

    fun onMessageShown() = _state.update { it.copy(message = null) }

    fun onHandle(text: String) {
        val clean = text.trim().removePrefix("@").lowercase()
        checkJob?.cancel()
        _state.update { it.copy(handle = clean, message = null, handleCheck = if (clean.isEmpty() || clean == it.profile?.handle) HandleCheck.Idle else HandleCheck.Checking) }
        val s = _state.value
        if (s.handleCheck != HandleCheck.Checking) return
        if (clean.length < MinHandle) { _state.update { it.copy(handleCheck = HandleCheck.Invalid) }; return }
        checkJob = viewModelScope.launch {
            delay(debounceMillis) // wait for the person to stop typing: availability checks are rate limited (30 an hour)
            val result = when (val r = checkHandle(clean)) {
                is AppResult.Success -> when (r.value.status) {
                    HandleStatus.Available -> HandleCheck.Available
                    HandleStatus.Taken -> HandleCheck.Taken(r.value.suggestion)
                    HandleStatus.Invalid -> HandleCheck.Invalid
                    HandleStatus.Unknown -> HandleCheck.Unavailable
                }
                is AppResult.Failure -> {
                    val error = r.error as? AppError.Api
                    if (error?.code == "request.rate_limited") HandleCheck.TooManyChecks(error.retryAfterSeconds) else HandleCheck.Unavailable
                }
            }
            _state.update { if (it.handle == clean) it.copy(handleCheck = result) else it }
        }
    }

    fun onClaimHandle() {
        val s = _state.value
        if (!s.canClaim) return
        _state.update { it.copy(claiming = true, message = null) }
        viewModelScope.launch {
            when (val r = claimHandle(s.handle)) {
                is AppResult.Success -> _state.update { it.copy(claiming = false, handleCheck = HandleCheck.Idle, message = "Your handle is now @${s.handle}.") }
                is AppResult.Failure -> _state.update { it.copy(claiming = false, message = ErrorMessages.forError(r.error)) }
            }
        }
    }

    /** The system picker: everything the strip doesn't show. Closing it changes nothing. */
    fun onChangePhoto() = uploadFrom(unreadable = null) { picker.pick() }

    /**
     * The photo sheet opened: its recents strip is filled now, and only now is the library permission asked (never at
     * launch). Each kind is asked once by the platform, so a refusal isn't asked again; the strip then just stays hidden.
     */
    fun onPhotoSheetShown() = loadRecents(ask = true)

    /** Limited access: the system's own way to choose more photos (or the app's settings), then the strip again. */
    fun onManageRecents() {
        viewModelScope.launch {
            library.manage(LibraryKind.Photo)
            loadRecents(ask = false)
        }
    }

    /** A tap on the strip uploads that photo at once, at full avatar quality. */
    fun onUseRecent(item: LibraryItem) = uploadFrom(unreadable = "Couldn't open that photo. Try another one.") { library.loadAvatar(item) }

    private fun loadRecents(ask: Boolean) {
        recentsJob?.cancel()
        recentsJob = viewModelScope.launch {
            val r = try { library.recents(LibraryKind.Photo, RecentsLimit, ask) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            if (r != null) _state.update { it.copy(recents = r.items, recentsAccess = r.access) }
        }
    }

    /** Take Photo: the camera alone is asked for (no microphone); a refusal shows its own screen with Open Settings. */
    fun onOpenCamera() {
        if (!_state.value.canChangeAvatar || _state.value.camera != null) return
        _state.update { it.copy(camera = ProfileCamera(), message = null) }
        viewModelScope.launch {
            val start = try { cameraDevice.openForPhoto() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { CameraStart.Unavailable }
            _state.update { s -> s.copy(camera = s.camera?.copy(start = start)) }
        }
    }

    fun onShutter() {
        val cam = _state.value.camera ?: return
        if (cam.start != CameraStart.Started || cam.shooting || cam.shot != null) return
        _state.update { s -> s.copy(camera = s.camera?.copy(shooting = true)) }
        viewModelScope.launch {
            val shot = try { cameraDevice.takeProfilePhoto() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            _state.update { s ->
                s.copy(camera = s.camera?.copy(shooting = false, shot = shot), message = if (shot == null && s.camera != null) "Couldn't take the photo. Try again." else s.message)
            }
        }
    }

    fun onRetake() = _state.update { s -> s.copy(camera = s.camera?.copy(shot = null)) }

    fun onFlipCamera() { if (_state.value.camera?.start == CameraStart.Started) cameraDevice.flip() }

    /** Use Photo: the camera closes and the photo goes up, with the same busy and error states as the other sources. */
    fun onUsePhoto() {
        val shot = _state.value.camera?.shot ?: return
        if (!_state.value.canChangeAvatar) return // offline: the shot stays, and the screen says it needs a connection
        onCloseCamera()
        uploadFrom(unreadable = null) { shot }
    }

    fun onCloseCamera() {
        if (_state.value.camera == null) return
        cameraDevice.close()
        _state.update { it.copy(camera = null) }
    }

    /**
     * One way up for every source (the picker, the strip, the camera): busy over the avatar, then the server's answer
     * in plain words. Nothing to upload: [unreadable] is said when there is something to say (a closed picker is not).
     */
    private fun uploadFrom(unreadable: String?, load: suspend () -> PickedImage?) {
        if (!_state.value.canChangeAvatar) return
        _state.update { it.copy(avatarBusy = true, message = null) }
        viewModelScope.launch {
            val picked = try { load() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            if (picked == null) { _state.update { it.copy(avatarBusy = false, message = unreadable) }; return@launch }
            val message = when (val r = uploadAvatar(AvatarImage(picked.bytes, picked.contentType))) {
                is AppResult.Success -> "Your photo is updated."
                is AppResult.Failure -> ErrorMessages.forError(r.error)
            }
            _state.update { it.copy(avatarBusy = false, message = message) }
        }
    }

    override fun onCleared() { if (_state.value.camera != null) cameraDevice.close() }

    fun onRemovePhoto() {
        if (!_state.value.canChangeAvatar || _state.value.profile?.avatarUrl == null) return
        _state.update { it.copy(avatarBusy = true, message = null) }
        viewModelScope.launch {
            val message = when (val r = removeAvatar()) {
                is AppResult.Success -> "Your photo is removed."
                is AppResult.Failure -> ErrorMessages.forError(r.error)
            }
            _state.update { it.copy(avatarBusy = false, message = message) }
        }
    }

    private companion object {
        const val MinHandle = 3
        /** Enough to scroll a little; the picker has the rest. */
        const val RecentsLimit = 24
    }
}
