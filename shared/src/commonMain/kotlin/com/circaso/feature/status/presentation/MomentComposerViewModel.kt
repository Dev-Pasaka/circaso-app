package com.circaso.feature.status.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.platform.AudioRecorder
import com.circaso.core.platform.CameraStart
import com.circaso.core.platform.CapturedMedia
import com.circaso.core.platform.ImagePicker
import com.circaso.core.platform.MomentCamera
import com.circaso.core.platform.RecordingStart
import com.circaso.core.platform.momentThumbnail
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.status.domain.MomentAudienceMode
import com.circaso.feature.status.domain.MomentBackground
import com.circaso.feature.status.domain.MomentDraft
import com.circaso.feature.status.domain.MomentKind
import com.circaso.feature.status.domain.MomentTextStyle
import com.circaso.feature.status.domain.MomentsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The composer's modes, in the order of the mode row (M1). Photo and Video share the camera: tap or hold. */
enum class ComposerMode { Text, Photo, Video, Voice }

/** What is ready to share (M4 for a photo or video, M5 for a recorded voice). */
sealed interface ComposerCapture {
    class Shot(val media: CapturedMedia, val kind: MomentKind) : ComposerCapture
    class Voice(val bytes: ByteArray, val mime: String, val durationMs: Long) : ComposerCapture
}

/** Designs M1–M6. */
data class MomentComposerUiState(
    val mode: ComposerMode = ComposerMode.Photo,
    val camera: CameraStart? = null,
    val micDenied: Boolean = false,
    val flash: Boolean = false,
    val recordingVideo: Boolean = false,
    val recordingVoice: Boolean = false,
    val capture: ComposerCapture? = null,
    val caption: String = "",
    val text: String = "",
    val background: MomentBackground = MomentBackground.Ink,
    val style: MomentTextStyle = MomentTextStyle.Bold,
    /** The audience chip: "My contacts · 48 people". */
    val audienceLabel: String = "My contacts",
    val sharing: Boolean = false,
    val shared: Boolean = false,
    val error: String? = null,
) {
    val canShare: Boolean get() = !sharing && when {
        capture != null -> true
        mode == ComposerMode.Text -> text.isNotBlank()
        else -> false
    }
}

class MomentComposerViewModel(
    private val repository: MomentsRepository,
    val camera: MomentCamera,
    private val picker: ImagePicker,
    private val recorder: AudioRecorder,
    observeContacts: ObserveContactsUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(MomentComposerUiState())
    val state: StateFlow<MomentComposerUiState> = _state.asStateFlow()
    private var voiceStartedAt = 0L

    init {
        viewModelScope.launch { _state.update { it.copy(camera = camera.open()) } }
        viewModelScope.launch {
            combine(observeContacts(), repository.privacy) { contacts, p ->
                val ids = contacts.map { it.person.userId }.toSet()
                when (p.mode) {
                    MomentAudienceMode.Contacts -> "My contacts" to ids.size
                    MomentAudienceMode.ContactsExcept -> "My contacts except ${p.except.count { it in ids }}" to ids.count { it !in p.except }
                    MomentAudienceMode.OnlyShareWith -> "Only share with" to p.only.count { it in ids }
                }
            }.collect { (label, n) -> _state.update { it.copy(audienceLabel = "$label · $n ${if (n == 1) "person" else "people"}") } }
        }
    }

    fun setMode(mode: ComposerMode) = _state.update { it.copy(mode = mode, error = null) }
    fun toggleFlash() { val on = !_state.value.flash; camera.setFlash(on); _state.update { it.copy(flash = on) } }
    fun flip() = camera.flip()

    fun takePhoto() {
        viewModelScope.launch {
            val shot = camera.takePhoto()
            _state.update { if (shot != null) it.copy(capture = ComposerCapture.Shot(shot, MomentKind.Image)) else it.copy(error = "The photo didn’t come out. Try again.") }
        }
    }

    fun startVideo() {
        viewModelScope.launch { if (camera.startVideo()) _state.update { it.copy(recordingVideo = true, mode = ComposerMode.Video) } }
    }

    fun stopVideo() {
        viewModelScope.launch {
            val clip = camera.stopVideo()
            _state.update { if (clip != null) it.copy(recordingVideo = false, capture = ComposerCapture.Shot(clip, MomentKind.Video)) else it.copy(recordingVideo = false, error = "The video didn’t record. Try again.") }
        }
    }

    fun pickFromGallery() {
        viewModelScope.launch {
            val picked = picker.pick() ?: return@launch
            val media = CapturedMedia(picked.bytes, picked.contentType, picked.width, picked.height, thumb = momentThumbnail(picked.bytes))
            _state.update { it.copy(capture = ComposerCapture.Shot(media, MomentKind.Image)) }
        }
    }

    fun startVoice() {
        viewModelScope.launch {
            when (recorder.start()) {
                RecordingStart.Started -> { voiceStartedAt = com.circaso.core.sync.nowMillis(); _state.update { it.copy(recordingVoice = true, micDenied = false) } }
                RecordingStart.PermissionDenied -> _state.update { it.copy(micDenied = true) }
                RecordingStart.Failed -> _state.update { it.copy(error = "The microphone isn’t available right now.") }
            }
        }
    }

    fun stopVoice() {
        viewModelScope.launch {
            val clip = recorder.stop()
            _state.update {
                // Under half a second is a slip, not a moment.
                if (clip == null || clip.durationSeconds < 1 && com.circaso.core.sync.nowMillis() - voiceStartedAt < 500) it.copy(recordingVoice = false)
                else it.copy(recordingVoice = false, capture = ComposerCapture.Voice(clip.bytes, clip.contentType, clip.durationSeconds * 1000L))
            }
        }
    }

    fun cancelVoice() { viewModelScope.launch { recorder.cancel(); _state.update { it.copy(recordingVoice = false) } } }
    fun discardCapture() = _state.update { it.copy(capture = null, caption = "") }
    fun onCaption(text: String) = _state.update { it.copy(caption = text.take(700)) }
    fun onText(text: String) = _state.update { it.copy(text = text.take(700)) }
    fun onBackground(bg: MomentBackground) = _state.update { it.copy(background = bg) }
    fun toggleStyle() = _state.update { it.copy(style = if (it.style == MomentTextStyle.Bold) MomentTextStyle.Regular else MomentTextStyle.Bold) }
    fun errorShown() = _state.update { it.copy(error = null) }

    /** Hands the moment to the outbox and closes (optimistic; my ring shows Posting, M6). */
    fun share() {
        val s = _state.value
        if (!s.canShare) return
        val draft = when (val cap = s.capture) {
            is ComposerCapture.Shot -> MomentDraft.Media(cap.kind, cap.media.bytes, cap.media.mime, s.caption, cap.media.thumb, cap.media.durationMs, cap.media.width, cap.media.height)
            is ComposerCapture.Voice -> MomentDraft.Media(MomentKind.Voice, cap.bytes, cap.mime, s.caption, null, cap.durationMs)
            null -> MomentDraft.Text(s.text.trim(), s.background, s.style)
        }
        _state.update { it.copy(sharing = true) }
        viewModelScope.launch {
            repository.post(draft)
            _state.update { it.copy(sharing = false, shared = true) }
        }
    }

    override fun onCleared() { camera.close() }
}
