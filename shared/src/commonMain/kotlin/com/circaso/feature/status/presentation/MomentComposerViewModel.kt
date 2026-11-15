package com.circaso.feature.status.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.platform.AudioRecorder
import com.circaso.core.platform.CameraStart
import com.circaso.core.platform.ImagePicker
import com.circaso.core.platform.LibraryAccess
import com.circaso.core.platform.LibraryItem
import com.circaso.core.platform.MediaLibrary
import com.circaso.core.platform.MomentCamera
import com.circaso.core.platform.RecordingStart
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.mediacompose.ComposerCapture
import com.circaso.feature.mediacompose.ComposerItem
import com.circaso.feature.mediacompose.ComposerMode
import com.circaso.feature.mediacompose.GalleryState
import com.circaso.feature.mediacompose.GalleryTab
import com.circaso.feature.mediacompose.MediaComposeState
import com.circaso.feature.mediacompose.MediaComposer
import com.circaso.feature.mediacompose.TrimState
import com.circaso.feature.status.domain.MomentAudienceMode
import com.circaso.feature.status.domain.MomentDraft
import com.circaso.feature.status.domain.MomentKind
import com.circaso.feature.status.domain.MomentTextStyle
import com.circaso.feature.status.domain.MomentsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Designs M1–M6. The camera, strip, gallery, trimmer and review are the shared composer's ([media], the same one a
 * chat uses); text, voice and the audience are a moment's own.
 */
data class MomentComposerUiState(
    val media: MediaComposeState = MediaComposeState(),
    val micDenied: Boolean = false,
    val recordingVoice: Boolean = false,
    /** Listening back to a recorded voice before sharing (M5). */
    val playingBack: Boolean = false,
    /** Listening back, held part-way: Play carries on from there. */
    val playbackHeld: Boolean = false,
    /** A voice moment's background (M5), from the same palette as text; Metal as before. */
    val voiceBackground: String = "Metal",
    /** The forward picker is open for the item on the stage (V4, from the review). */
    val forwarding: Boolean = false,
    val text: String = "",
    val background: String = "Ink",
    val style: MomentTextStyle = MomentTextStyle.Bold,
    /** The audience chip: "My contacts · 48 people". */
    val audienceLabel: String = "My contacts",
    /** How many people the next moment would reach; 0 means nobody yet (no contacts on Circaso, or nobody chosen). */
    val audienceCount: Int = -1,
    val myName: String = "",
    val myAvatarUrl: String? = null,
    val sharing: Boolean = false,
    val shared: Boolean = false,
    /** Opened with items shared in from another app and none of them could be used: nothing to show, so it closes. */
    val closed: Boolean = false,
) {
    val mode: ComposerMode get() = media.mode
    val camera: CameraStart? get() = media.camera
    val flash: Boolean get() = media.flash
    val recordingVideo: Boolean get() = media.recordingVideo
    val recordingSince: Long? get() = media.recordingSince
    val recents: List<LibraryItem> get() = media.recents
    val recentsAccess: LibraryAccess? get() = media.recentsAccess
    val recentsGap: com.circaso.feature.mediacompose.RecentsGap? get() = media.recentsGap
    val selection: List<LibraryItem> get() = media.selection
    val selecting: Boolean get() = media.selecting
    val gallery: GalleryState? get() = media.gallery
    val loadingPick: Boolean get() = media.loadingPick
    val items: List<ComposerItem> get() = media.items
    val current: Int get() = media.current
    val trim: TrimState? get() = media.trim
    val preparing: Boolean get() = media.preparing
    val error: String? get() = media.error
    val item: ComposerItem? get() = media.item
    val capture: ComposerCapture? get() = media.capture
    val caption: String get() = media.caption
    val nobody: Boolean get() = audienceCount == 0
    val canShare: Boolean get() = !sharing && !preparing && !nobody && trim == null && when {
        items.isNotEmpty() -> items.none { media.needsTrim(it) }
        mode == ComposerMode.Text -> text.isNotBlank()
        else -> false
    }
}

class MomentComposerViewModel(
    private val repository: MomentsRepository,
    val camera: MomentCamera,
    @Suppress("unused") private val picker: ImagePicker,
    private val recorder: AudioRecorder,
    observeContacts: ObserveContactsUseCase,
    private val player: com.circaso.core.platform.AudioPlayer,
    private val library: MediaLibrary,
    observeProfile: com.circaso.feature.profile.domain.ObserveProfileUseCase,
    private val now: () -> Long = { com.circaso.core.sync.nowMillis() },
    /** Where the composer starts; previews start it on a given screen (the gallery, the trimmer, several picked). */
    initial: MomentComposerUiState = MomentComposerUiState(),
    /** Opened from another app's share sheet with "My moment": where its photos and videos come from. */
    shared: (() -> List<com.circaso.feature.mediacompose.SharedFile>?)? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<MomentComposerUiState> = _state.asStateFlow()

    /** The camera, strip, gallery, trimmer and review: the same composer a chat uses (MediaCompose). */
    val composer = MediaComposer(
        viewModelScope, library, camera, now,
        read = { _state.value.media },
        write = { f -> _state.update { it.copy(media = f(it.media)) } },
    )

    private fun media(f: (MediaComposeState) -> MediaComposeState) = _state.update { it.copy(media = f(it.media)) }

    /**
     * The microphone's level over the last moments while recording, oldest first (the live wave, M5b). Apart from
     * [state] so twenty samples a second redraw the wave and nothing else.
     */
    private val _liveLevels = MutableStateFlow<List<Float>>(emptyList())
    val liveLevels: StateFlow<List<Float>> = _liveLevels.asStateFlow()
    private var voiceStartedAt = 0L
    // Holds (M5b voice): a release can arrive while the start is still in progress (a permission prompt). It is
    // remembered, and the recording stops as soon as it has started, never left running.
    private var voiceStarting = false
    private var voiceReleaseAsked = false
    private var voiceCap: Job? = null
    private var voiceSampler: Job? = null
    private val voiceSamples = mutableListOf<Float>()

    init {
        if (shared == null) composer.start()
        else viewModelScope.launch {
            // Straight onto the review; the camera opens only if the person discards what was shared.
            composer.addShared(shared().orEmpty(), documents = false)
            if (_state.value.items.isEmpty()) _state.update { it.copy(closed = true) }
        }
        viewModelScope.launch { observeProfile().collect { p -> _state.update { it.copy(myName = p?.displayName.orEmpty(), myAvatarUrl = p?.avatarUrl) } } }
        viewModelScope.launch {
            combine(observeContacts(), repository.privacy) { contacts, p ->
                val ids = contacts.map { it.person.userId }.toSet()
                when (p.mode) {
                    MomentAudienceMode.Contacts -> "My contacts" to ids.size
                    MomentAudienceMode.ContactsExcept -> "My contacts except ${p.except.count { it in ids }}" to ids.count { it !in p.except }
                    MomentAudienceMode.OnlyShareWith -> "Only share with" to p.only.count { it in ids }
                }
            }.collect { (label, n) -> _state.update { it.copy(audienceCount = n, audienceLabel = if (n == 0) "Nobody can see this yet" else "$label · $n ${if (n == 1) "person" else "people"}") } }
        }
    }

    fun setMode(mode: ComposerMode) = composer.setMode(mode)

    // ---- picking from the library (the shared composer)

    fun manageAccess() = composer.manageAccess()
    fun refreshAccess() = composer.refreshAccess()
    fun useRecent(item: LibraryItem) = composer.useRecent(item)
    fun startSelecting(item: LibraryItem) = composer.startSelecting(item)
    fun toggle(item: LibraryItem) = composer.toggle(item)
    fun clearSelection() = composer.clearSelection()
    fun useSelection() = composer.useSelection()
    fun pickFromLibrary() = composer.pickFromLibrary()
    fun pickFromGallery() = composer.pickFromLibrary()
    fun openGallery() = composer.openGallery()
    fun galleryTab(tab: GalleryTab) = composer.galleryTab(tab)
    fun loadMoreGallery() = composer.loadMoreGallery()
    fun closeGallery() = composer.closeGallery()

    /** M4's strip: put another picked item on the stage. */
    fun select(index: Int) {
        if (index !in _state.value.items.indices) return
        stopPlayback()
        composer.select(index)
    }

    /** Takes the item on the stage out (the bin on the strip); with nothing left, back to the camera. */
    fun removeCurrent() { stopPlayback(); composer.removeCurrent() }

    fun markUnplayable() = composer.markUnplayable()

    // ---- trimming (M3b)

    fun openTrim(required: Boolean = false) = composer.openTrim(required)
    fun setTrim(startMs: Long, endMs: Long) = composer.setTrim(startMs, endMs)
    /** Sliding the whole window by [deltaMs] from where it was when the drag began ([fromStart]..[fromEnd]). */
    fun slideTrim(fromStart: Long, fromEnd: Long, deltaMs: Long) = composer.slideTrim(fromStart, fromEnd, deltaMs)
    fun applyTrim() = composer.applyTrim()
    fun cancelTrim() = composer.cancelTrim()

    // ---- camera and recording

    fun toggleFlash() = composer.toggleFlash()
    fun flip() = composer.flip()
    fun takePhoto() = composer.takePhoto()
    fun holdStart() = composer.holdStart()
    fun holdEnd() = composer.holdEnd()

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
                    voiceStartedAt = now()
                    _state.update { it.copy(recordingVoice = true, micDenied = false, media = it.media.copy(recordingSince = voiceStartedAt)) }
                    voiceCap = viewModelScope.launch { kotlinx.coroutines.delay(com.circaso.core.platform.MomentVideoMaxMs); voiceUp() }
                    // The person's own waveform: the recorder's meter, sampled while the mic is held.
                    voiceSamples.clear()
                    voiceSampler?.cancel()
                    voiceSampler = viewModelScope.launch {
                        while (_state.value.recordingVoice) {
                            val level = recorder.level()
                            voiceSamples += level
                            _liveLevels.value = voiceSamples.takeLast(LiveBars)
                            kotlinx.coroutines.delay(SampleMs)
                        }
                    }
                    if (voiceReleaseAsked) voiceUp()
                }
                RecordingStart.PermissionDenied -> _state.update { it.copy(micDenied = true) }
                RecordingStart.Failed -> composer.notice("The microphone isn’t available right now.")
            }
        }
    }

    fun voiceUp() {
        if (voiceStarting) { voiceReleaseAsked = true; return }
        if (!_state.value.recordingVoice) return
        voiceCap?.cancel()
        voiceSampler?.cancel()
        val levels = com.circaso.feature.status.domain.voiceLevels(voiceSamples.toList()).takeIf { l -> l.any { it > 0f } }
        _liveLevels.value = emptyList()
        _state.update { it.copy(recordingVoice = false, media = it.media.copy(recordingSince = null)) }
        viewModelScope.launch {
            val clip = recorder.stop()
            val heldMs = now() - voiceStartedAt
            // Under half a second is a slip, not a moment.
            if (clip != null && heldMs >= 500) {
                composer.setSingle(ComposerCapture.Voice(clip.bytes, clip.contentType, heldMs.coerceAtMost(com.circaso.core.platform.MomentVideoMaxMs), levels))
            }
        }
    }

    /** Slide to cancel (M5b): nothing is kept. */
    fun voiceCancel() {
        if (voiceStarting) { voiceReleaseAsked = true }
        voiceCap?.cancel()
        voiceSampler?.cancel()
        _liveLevels.value = emptyList()
        _state.update { it.copy(recordingVoice = false, media = it.media.copy(recordingSince = null)) }
        viewModelScope.launch { recorder.cancel() }
    }

    /** M5: listen back before sharing. Pause holds it where it is; Play again carries on from there. */
    fun togglePlayback() {
        val s = _state.value
        val voice = s.capture as? ComposerCapture.Voice ?: return
        when {
            s.playingBack -> { player.pause(); _state.update { it.copy(playingBack = false, playbackHeld = true) } }
            s.playbackHeld -> { player.resume(); _state.update { it.copy(playingBack = true, playbackHeld = false) } }
            else -> {
                _state.update { it.copy(playingBack = true) }
                viewModelScope.launch {
                    player.play(voice.bytes, voice.mime)
                    _state.update { it.copy(playingBack = false, playbackHeld = false) }
                }
            }
        }
    }

    private fun stopPlayback() { player.stop(); _state.update { it.copy(playingBack = false, playbackHeld = false) } }

    /** How far listening back has got (0..1), for the wave's fill. */
    fun playbackProgress(): Float? = player.progress()

    fun onVoiceBackground(bg: String) = _state.update { it.copy(voiceBackground = bg) }

    /** The close button on the review: everything picked or captured is let go, back to the camera. */
    fun discardCapture() {
        if (_state.value.sharing) return
        stopPlayback(); composer.discard(); _state.update { it.copy(forwarding = false) }
        // Opened on a share: back to the camera, which starts now.
        if (_state.value.camera == null) composer.start()
    }

    fun onCaption(text: String) = composer.onCaption(text)
    fun onText(text: String) = _state.update { it.copy(text = text.take(700)) }
    fun onBackground(bg: String) = _state.update { it.copy(background = bg) }
    /** Aa cycles the fonts. */
    fun toggleStyle() = _state.update { it.copy(style = MomentTextStyle.entries[(it.style.ordinal + 1) % MomentTextStyle.entries.size]) }
    fun errorShown() = composer.errorShown()
    /** A line for the toast that is not an error (after forwarding). */
    fun notice(text: String) = composer.notice(text)

    // ---- forward (V4, from the review)

    fun openForward(open: Boolean) = _state.update { it.copy(forwarding = open && it.item != null) }

    /** The item on the stage as something to send to a chat; a video's bytes are read only when it is sent. */
    fun forwardItem(): ForwardMedia? {
        val item = _state.value.item ?: return null
        return when (val cap = item.capture) {
            is ComposerCapture.Shot -> ForwardMedia(cap.momentKind, cap.media.bytes.takeIf { it.isNotEmpty() }, cap.media.path, cap.media.mime, cap.media.width, cap.media.height, cap.media.durationMs, cap.media.thumb, item.caption)
            is ComposerCapture.Voice -> ForwardMedia(MomentKind.Voice, cap.bytes, null, cap.mime, null, null, cap.durationMs, null, null)
            is ComposerCapture.Document -> null
        }
    }

    // ---- sharing

    /**
     * Post (2026-11-14, Pasaka: "the same experience as WhatsApp"). The preview checks only what can stop a moment
     * for good ([momentRefusal]: a file that is gone, one too large to post at all; a video's minute is the trimmer's).
     * Then each moment is recorded as pending, in the order picked (several picked are several moments), and the
     * composer closes at once, for a video too: making it smaller and sending it happen in the background
     * ([MomentsRepository.post]), and my ring in the Moments list shows how far it has got (M6).
     */
    fun share() {
        val s = _state.value
        if (!s.canShare) return
        _state.update { it.copy(sharing = true) }
        viewModelScope.launch {
            if (s.items.isEmpty()) {
                repository.post(MomentDraft.Text(s.text.trim(), s.background, s.style))
                _state.update { it.copy(sharing = false, shared = true) }
                return@launch
            }
            // Every one is checked before any is recorded: never half a batch posted and the rest refused.
            for ((index, item) in s.items.withIndex()) {
                momentRefusal(item, library)?.let { stop(index, it); return@launch }
            }
            for ((index, item) in s.items.withIndex()) {
                val draft = momentDraft(item, s.voiceBackground) ?: continue
                if (repository.post(draft) is com.circaso.core.common.AppResult.Failure) {
                    // Its file went between the check and now. The ones before it are on their way: they leave the
                    // review, so posting again never sends them twice.
                    val what = if ((item.capture as? ComposerCapture.Shot)?.video == false) "photo" else "video"
                    val rest = when (index) { 0 -> ""; 1 -> " The one before it is posting."; else -> " The ones before it are posting." }
                    _state.update { it.copy(sharing = false, media = it.media.copy(items = it.media.items.drop(index), current = 0, error = "This $what isn’t on the phone any more. Pick it again.$rest")) }
                    return@launch
                }
            }
            _state.update { it.copy(sharing = false, shared = true) }
        }
    }

    private fun stop(index: Int, why: String) = _state.update { it.copy(sharing = false, media = it.media.copy(current = index, error = why)) }

    override fun onCleared() { player.stop(); camera.close() }

    companion object {
        /** Frames in the trim bar's filmstrip (design M3b shows eight). */
        const val TrimFrames = MediaComposer.TrimFrames
        /** The recorder's meter is read this often while recording (a minute is 1,200 samples, 48 bars). */
        const val SampleMs = 50L
        /** Bars in the live wave while recording. */
        const val LiveBars = 32
        /** The shortest clip the trimmer keeps. */
        const val MinTrimMs = MediaComposer.MinTrimMs
    }
}

private const val GoneCopy = "This video isn’t on the phone any more. Pick it again."

/**
 * Why [item] can't be posted as a moment at all, in plain words, or null when it can (2026-11-14: the preview checks
 * only the minute, which the trimmer holds, and the size). The size rule: a photo or a voice goes as it is, so it must
 * fit the post limit ([com.circaso.core.platform.MomentMediaMaxBytes]); a video over that limit is fine, because it is
 * made smaller after Post, in the background (a minute always fits once encoded), so only a file that is gone, or one
 * past [com.circaso.core.platform.MomentSourceMaxBytes], is refused here.
 */
internal suspend fun momentRefusal(item: ComposerItem, library: MediaLibrary): String? {
    val max = com.circaso.core.platform.MomentMediaMaxBytes
    return when (val cap = item.capture) {
        is ComposerCapture.Voice -> when {
            cap.bytes.isEmpty() -> "This recording isn’t on the phone any more. Record it again."
            cap.bytes.size > max -> "This recording is too large to post. Voice moments are up to a minute."
            else -> null
        }
        is ComposerCapture.Document -> "Moments are photos, videos, voice and text."
        is ComposerCapture.Shot -> {
            val path = cap.media.path
            val onDisk = path != null && (cap.video || cap.media.bytes.isEmpty())
            val size = if (onDisk) library.size(path!!) ?: 0 else cap.media.bytes.size.toLong()
            when {
                size <= 0 -> if (cap.video) GoneCopy else "This photo isn’t on the phone any more. Pick it again."
                !cap.video && size > max -> "This photo is too large to post. Pick another."
                // In memory only (no file for the encoder to read): it goes as it is, or not at all.
                cap.video && !onDisk && size > max -> "This video is too large to post. Pick a shorter one."
                cap.video && size > com.circaso.core.platform.MomentSourceMaxBytes -> {
                    // "1.4 GB"; a file a little over reads "just over 1 GB", never "1.0 GB" beside "up to 1 GB".
                    val tenths = (size * 10 + com.circaso.core.platform.MomentSourceMaxBytes / 2) / com.circaso.core.platform.MomentSourceMaxBytes
                    "This video is ${if (tenths <= 10) "just over 1 GB" else "${tenths / 10}.${tenths % 10} GB"}. Videos up to 1 GB can be posted: trim it shorter or pick another."
                }
                else -> null
            }
        }
    }
}

/** [item] as the moment to record: a video by its file (never read into memory here), the rest by their bytes. */
internal fun momentDraft(item: ComposerItem, voiceBackground: String): MomentDraft.Media? = when (val cap = item.capture) {
    is ComposerCapture.Shot -> {
        val m = cap.media
        val onDisk = m.path != null && (cap.video || m.bytes.isEmpty())
        MomentDraft.Media(cap.momentKind, if (onDisk) ByteArray(0) else m.bytes, m.mime, item.caption, m.thumb, m.durationMs, m.width, m.height, path = m.path.takeIf { onDisk })
    }
    is ComposerCapture.Voice -> MomentDraft.Media(MomentKind.Voice, cap.bytes, cap.mime, item.caption, null, cap.durationMs, background = voiceBackground, levels = cap.levels)
    // A moment has no file kind; the composer never offers one here.
    is ComposerCapture.Document -> null
}

/** A shot's kind as a moment knows it. */
internal val ComposerCapture.Shot.momentKind: MomentKind get() = if (video) MomentKind.Video else MomentKind.Image

/** A moment's media (or text), ready to forward to chats. [bytes] null: read from [path] when it is sent. */
class ForwardMedia(
    val kind: MomentKind,
    val bytes: ByteArray?,
    val path: String?,
    val mime: String,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val thumb: ByteArray?,
    val caption: String?,
    val text: String? = null,
)
