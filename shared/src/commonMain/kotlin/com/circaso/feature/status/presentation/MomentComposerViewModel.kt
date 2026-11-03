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
    /** When the current recording began (M1b, M5b: the elapsed time and the ring filling to 1:00). */
    val recordingSince: Long? = null,
    /** Listening back to a recorded voice before sharing (M5). */
    val playingBack: Boolean = false,
    val capture: ComposerCapture? = null,
    val caption: String = "",
    val text: String = "",
    val background: MomentBackground = MomentBackground.Ink,
    val style: MomentTextStyle = MomentTextStyle.Bold,
    /** The audience chip: "My contacts · 48 people". */
    val audienceLabel: String = "My contacts",
    val myName: String = "",
    val myAvatarUrl: String? = null,
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
    private val player: com.circaso.core.platform.AudioPlayer,
    observeProfile: com.circaso.feature.profile.domain.ObserveProfileUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(MomentComposerUiState())
    val state: StateFlow<MomentComposerUiState> = _state.asStateFlow()
    private var voiceStartedAt = 0L
    // Holds (M1b video, M5b voice): a release can arrive while the start is still in progress (a permission prompt,
    // the encoder warming up). It is remembered, and the recording stops as soon as it has started, never left running.
    private var videoStarting = false
    private var videoReleaseAsked = false
    private var voiceStarting = false
    private var voiceReleaseAsked = false
    private var voiceCap: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch { _state.update { it.copy(camera = camera.open()) } }
        viewModelScope.launch { observeProfile().collect { p -> _state.update { it.copy(myName = p?.displayName.orEmpty(), myAvatarUrl = p?.avatarUrl) } } }
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

    /** The shutter went down and stayed down: record a video (from Photo or Video mode). */
    fun holdStart() {
        if (videoStarting || _state.value.recordingVideo || _state.value.camera != CameraStart.Started) return
        videoStarting = true
        videoReleaseAsked = false
        viewModelScope.launch {
            val ok = camera.startVideo()
            videoStarting = false
            if (!ok) { _state.update { it.copy(error = "The video didn’t start. Try again.") }; return@launch }
            _state.update { it.copy(recordingVideo = true, recordingSince = com.circaso.core.sync.nowMillis()) }
            if (videoReleaseAsked) stopVideo()
        }
    }

    /** The finger lifted: stop whatever is recording, or ask the starting recording to stop as soon as it can. */
    fun holdEnd() {
        if (videoStarting) videoReleaseAsked = true else if (_state.value.recordingVideo) stopVideo()
    }

    private fun stopVideo() {
        _state.update { it.copy(recordingVideo = false, recordingSince = null) }
        viewModelScope.launch {
            val clip = camera.stopVideo()
            _state.update { if (clip != null) it.copy(capture = ComposerCapture.Shot(clip, MomentKind.Video), mode = ComposerMode.Video) else it.copy(error = "The video didn’t record. Try again.") }
        }
    }

    fun pickFromGallery() {
        viewModelScope.launch {
            val picked = picker.pick() ?: return@launch
            val thumb = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { momentThumbnail(picked.bytes) }
            val media = CapturedMedia(picked.bytes, picked.contentType, picked.width, picked.height, thumb = thumb)
            _state.update { it.copy(capture = ComposerCapture.Shot(media, MomentKind.Image)) }
        }
    }

    /** The mic went down (M5b): record until it lifts, or 1:00, whichever comes first. */
    fun voiceDown() {
        if (voiceStarting || _state.value.recordingVoice) return
        voiceStarting = true
        voiceReleaseAsked = false
        viewModelScope.launch {
            val started = recorder.start()
            voiceStarting = false
            when (started) {
                RecordingStart.Started -> {
                    voiceStartedAt = com.circaso.core.sync.nowMillis()
                    _state.update { it.copy(recordingVoice = true, micDenied = false, recordingSince = voiceStartedAt) }
                    voiceCap = viewModelScope.launch { kotlinx.coroutines.delay(com.circaso.core.platform.MomentVideoMaxMs); voiceUp() }
                    if (voiceReleaseAsked) voiceUp()
                }
                RecordingStart.PermissionDenied -> _state.update { it.copy(micDenied = true) }
                RecordingStart.Failed -> _state.update { it.copy(error = "The microphone isn’t available right now.") }
            }
        }
    }

    fun voiceUp() {
        if (voiceStarting) { voiceReleaseAsked = true; return }
        if (!_state.value.recordingVoice) return
        voiceCap?.cancel()
        _state.update { it.copy(recordingVoice = false, recordingSince = null) }
        viewModelScope.launch {
            val clip = recorder.stop()
            val heldMs = com.circaso.core.sync.nowMillis() - voiceStartedAt
            // Under half a second is a slip, not a moment.
            if (clip != null && heldMs >= 500) {
                _state.update { it.copy(capture = ComposerCapture.Voice(clip.bytes, clip.contentType, heldMs.coerceAtMost(com.circaso.core.platform.MomentVideoMaxMs))) }
            }
        }
    }

    /** Slide to cancel (M5b): nothing is kept. */
    fun voiceCancel() {
        if (voiceStarting) { voiceReleaseAsked = true }
        voiceCap?.cancel()
        _state.update { it.copy(recordingVoice = false, recordingSince = null) }
        viewModelScope.launch { recorder.cancel() }
    }

    /** M5: listen back before sharing. */
    fun togglePlayback() {
        val voice = _state.value.capture as? ComposerCapture.Voice ?: return
        if (_state.value.playingBack) { player.stop(); _state.update { it.copy(playingBack = false) }; return }
        _state.update { it.copy(playingBack = true) }
        viewModelScope.launch {
            player.play(voice.bytes, voice.mime)
            _state.update { it.copy(playingBack = false) }
        }
    }

    fun discardCapture() { player.stop(); _state.update { it.copy(capture = null, caption = "", playingBack = false) } }
    fun onCaption(text: String) = _state.update { it.copy(caption = text.take(700)) }
    fun onText(text: String) = _state.update { it.copy(text = text.take(700)) }
    fun onBackground(bg: MomentBackground) = _state.update { it.copy(background = bg) }
    fun toggleStyle() = _state.update { it.copy(style = if (it.style == MomentTextStyle.Bold) MomentTextStyle.Regular else MomentTextStyle.Bold) }
    fun errorShown() = _state.update { it.copy(error = null) }

    /** Hands the moment to the outbox and closes (optimistic; my ring shows Posting, M6). */
    fun share() {
        val s = _state.value
        if (!s.canShare) return
        val size = when (val cap = s.capture) { is ComposerCapture.Shot -> cap.media.bytes.size; is ComposerCapture.Voice -> cap.bytes.size; null -> 0 }
        if (size > com.circaso.core.platform.MomentMediaMaxBytes) {
            _state.update { it.copy(error = "This is too large to share. Keep videos under a minute.") }
            return
        }
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

    override fun onCleared() { player.stop(); camera.close() }
}
