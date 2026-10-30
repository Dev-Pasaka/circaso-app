package com.circaso.feature.profile.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ImagePicker
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
    private val debounceMillis: Long = 400,
) : ViewModel() {
    private val _state = MutableStateFlow(EditProfileUiState())
    val state: StateFlow<EditProfileUiState> = _state.asStateFlow()
    private var checkJob: Job? = null

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

    fun onChangePhoto() {
        if (!_state.value.canChangeAvatar) return
        _state.update { it.copy(avatarBusy = true, message = null) }
        viewModelScope.launch {
            val picked = try { picker.pick() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            if (picked == null) { _state.update { it.copy(avatarBusy = false) }; return@launch } // closed the picker: nothing changes
            val message = when (val r = uploadAvatar(AvatarImage(picked.bytes, picked.contentType))) {
                is AppResult.Success -> "Your photo is updated."
                is AppResult.Failure -> ErrorMessages.forError(r.error)
            }
            _state.update { it.copy(avatarBusy = false, message = message) }
        }
    }

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

    private companion object { const val MinHandle = 3 }
}
