package com.circaso.feature.status.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.platform.AudioRecorder
import com.circaso.core.platform.CameraStart
import com.circaso.core.platform.CapturedMedia
import com.circaso.core.platform.ImagePicker
import com.circaso.core.platform.LibraryAccess
import com.circaso.core.platform.LibraryItem
import com.circaso.core.platform.LibraryKind
import com.circaso.core.platform.MediaLibrary
import com.circaso.core.platform.MomentCamera
import com.circaso.core.platform.MomentMediaMaxBytes
import com.circaso.core.platform.MomentMediaMaxMs
import com.circaso.core.platform.MomentPickMax
import com.circaso.core.platform.RecordingStart
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
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

/** The composer's modes, in the order of the mode row (M1). Photo and Video share the camera: tap or hold. */
enum class ComposerMode { Text, Photo, Video, Voice }

/** What is ready to share (M4 for a photo or video, M5 for a recorded voice). */
sealed interface ComposerCapture {
    class Shot(val media: CapturedMedia, val kind: MomentKind) : ComposerCapture
    /** [levels]: the waveform sampled while recording (null for a picked audio file). */
    class Voice(val bytes: ByteArray, val mime: String, val durationMs: Long, val levels: List<Float>? = null) : ComposerCapture
}

/**
 * One picked or captured item before sharing (M4). Several picked at once each become their own moment, posted in
 * order; each keeps its own caption. [unplayable]: this phone couldn't preview the video (it still posts).
 */
data class ComposerItem(val capture: ComposerCapture, val caption: String = "", val unplayable: Boolean = false) {
    val video: CapturedMedia? get() = (capture as? ComposerCapture.Shot)?.takeIf { it.kind == MomentKind.Video }?.media
    /** Longer than a minute: it opens the trimmer, and can't be shared until it has been trimmed (M3b). */
    val tooLong: Boolean get() = (video?.durationMs ?: 0) > MomentMediaMaxMs + 500
}

/** The gallery's tabs (M3). */
enum class GalleryTab { All, Photos, Videos }

/** M3: the full gallery grid, opened from Gallery beside the shutter. */
data class GalleryState(
    val tab: GalleryTab = GalleryTab.All,
    val photos: List<LibraryItem> = emptyList(),
    val videos: List<LibraryItem> = emptyList(),
    /** Null until the phone has answered (asking for access can take a while). */
    val access: LibraryAccess? = null,
) {
    val items: List<LibraryItem>
        get() = when (tab) {
            GalleryTab.All -> (photos + videos).sortedByDescending { it.addedAt }
            GalleryTab.Photos -> photos
            GalleryTab.Videos -> videos
        }
    val loading: Boolean get() = access == null
}

/** M3b: trimming one video to a minute or less. Times are in milliseconds from the start of the original. */
data class TrimState(
    val index: Int,
    val durationMs: Long,
    val startMs: Long,
    val endMs: Long,
    /** The filmstrip under the handles: small JPEGs spread through the video. */
    val frames: List<ByteArray> = emptyList(),
    /** The video was over a minute: Cancel takes it out instead of keeping the long one. */
    val required: Boolean,
    val working: Boolean = false,
    /** The moment the stage shows: the edge last dragged, so the person sees exactly where the cut falls. */
    val previewMs: Long = startMs,
) {
    val selectedMs: Long get() = endMs - startMs
}

/**
 * The trim window's maths (M3b), apart from any screen. A minute is the most a moment holds, not a fixed window: any
 * clip, short or long, can be cut to anything from [MinMs] to [MaxMs], with either edge or the whole window moved.
 */
object TrimWindow {
    const val MinMs = 1_000L
    val MaxMs get() = MomentMediaMaxMs

    /** Where the window opens: the whole clip when it is a minute or less, else its first minute. */
    fun initial(durationMs: Long): Pair<Long, Long> = 0L to minOf(durationMs, MaxMs)

    /**
     * [startMs] and [endMs] asked for, given the window was [oldStart]..[oldEnd]: kept inside the clip, at least [MinMs]
     * (or the whole clip, if shorter) and at most [MaxMs]. Under the minimum the edge being dragged stops; past the
     * maximum the other edge follows it.
     */
    fun clamp(durationMs: Long, oldStart: Long, startMs: Long, endMs: Long): Pair<Long, Long> {
        val min = minOf(MinMs, durationMs)
        var start = startMs.coerceIn(0, durationMs)
        var end = endMs.coerceIn(0, durationMs)
        val movedStart = start != oldStart
        if (end - start < min) { if (movedStart) start = (end - min).coerceAtLeast(0) else end = (start + min).coerceAtMost(durationMs) }
        if (end - start < min) { start = (end - min).coerceAtLeast(0); end = (start + min).coerceAtMost(durationMs) }
        if (end - start > MaxMs) { if (movedStart) end = start + MaxMs else start = end - MaxMs }
        return start to end
    }

    /** The whole window slid by [deltaMs], stopping at either end of the clip; its length never changes. */
    fun slide(durationMs: Long, startMs: Long, endMs: Long, deltaMs: Long): Pair<Long, Long> {
        val shift = deltaMs.coerceIn(-startMs, durationMs - endMs)
        return (startMs + shift) to (endMs + shift)
    }
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
    /** Listening back, held part-way: Play carries on from there. */
    val playbackHeld: Boolean = false,
    /** A voice moment's background (M5), from the same palette as text; Metal as before. */
    val voiceBackground: String = "Metal",
    /** The recents strip for the current mode (WhatsApp-style), newest first; empty without library access. */
    val recents: List<LibraryItem> = emptyList(),
    /** What the phone lets the strip show for this mode; null until it has answered. */
    val recentsAccess: LibraryAccess? = null,
    /** Several at once (M3): a long press on the strip starts it, the gallery always picks this way. In order. */
    val selection: List<LibraryItem> = emptyList(),
    val selecting: Boolean = false,
    val gallery: GalleryState? = null,
    val loadingPick: Boolean = false,
    val items: List<ComposerItem> = emptyList(),
    /** The item on the stage when several were picked (M4's strip). */
    val current: Int = 0,
    val trim: TrimState? = null,
    /** Re-encoding a video that is too large to post as it is. */
    val preparing: Boolean = false,
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
    val error: String? = null,
) {
    val item: ComposerItem? get() = items.getOrNull(current)
    val capture: ComposerCapture? get() = item?.capture
    val caption: String get() = item?.caption.orEmpty()
    val nobody: Boolean get() = audienceCount == 0
    val canShare: Boolean get() = !sharing && !preparing && !nobody && trim == null && when {
        items.isNotEmpty() -> items.none { it.tooLong }
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
    /** Re-encoding a video after Share runs as background work, so leaving Circaso mid-way doesn't stop it. */
    private val uploads: com.circaso.core.platform.BackgroundUploads = com.circaso.core.platform.NoBackgroundUploads,
) : ViewModel() {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<MomentComposerUiState> = _state.asStateFlow()

    /**
     * The microphone's level over the last moments while recording, oldest first (the live wave, M5b). Apart from
     * [state] so twenty samples a second redraw the wave and nothing else.
     */
    private val _liveLevels = MutableStateFlow<List<Float>>(emptyList())
    val liveLevels: StateFlow<List<Float>> = _liveLevels.asStateFlow()
    private var voiceStartedAt = 0L
    // Holds (M1b video, M5b voice): a release can arrive while the start is still in progress (a permission prompt,
    // the encoder warming up). It is remembered, and the recording stops as soon as it has started, never left running.
    private var videoStarting = false
    private var videoReleaseAsked = false
    private var voiceStarting = false
    private var voiceReleaseAsked = false
    private var voiceCap: Job? = null
    private var voiceSampler: Job? = null
    private val voiceSamples = mutableListOf<Float>()
    private var recentsJob: Job? = null
    private var trimJob: Job? = null

    init {
        viewModelScope.launch { _state.update { it.copy(camera = camera.open()) }; loadRecents(initial.mode) }
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

    fun setMode(mode: ComposerMode) {
        _state.update { it.copy(mode = mode, error = null, recents = emptyList(), recentsAccess = null, selecting = false, selection = emptyList()) }
        loadRecents(mode)
    }

    /**
     * The strip under the camera: recent photos, videos or audio from the phone. Each kind asks for its own access the
     * first time it shows (on Android photo access says nothing about videos), so a strip is never silently empty:
     * without access it says so and offers to allow it.
     */
    private fun loadRecents(mode: ComposerMode, ask: Boolean = true) {
        val kind = libraryKind(mode) ?: return
        recentsJob?.cancel()
        recentsJob = viewModelScope.launch {
            val r = library.recents(kind, 30, ask)
            if (_state.value.mode == mode) _state.update { it.copy(recents = r.items, recentsAccess = r.access) }
        }
    }

    private fun libraryKind(mode: ComposerMode) = when (mode) {
        ComposerMode.Photo -> LibraryKind.Photo
        ComposerMode.Video -> LibraryKind.Video
        ComposerMode.Voice -> LibraryKind.Audio
        ComposerMode.Text -> null
    }

    /**
     * Limited access (M3c Manage) or none: the system's own way to allow more. The strip and the gallery read the
     * library again afterwards.
     */
    fun manageAccess() {
        val kind = libraryKind(_state.value.mode) ?: LibraryKind.Photo
        viewModelScope.launch {
            library.manage(kind)
            loadRecents(_state.value.mode, ask = false)
            if (_state.value.gallery != null) loadGallery()
        }
    }

    // ---- picking from the library

    /** One tile from the strip: used at once, or ticked when several are being picked. */
    fun useRecent(item: LibraryItem) {
        if (_state.value.selecting) toggle(item) else use { listOfNotNull(library.load(item)) }
    }

    /** A long press on a strip tile: start picking several (WhatsApp-style), this one first. */
    fun startSelecting(item: LibraryItem) {
        if (item.kind == LibraryKind.Audio) { useRecent(item); return }
        _state.update { it.copy(selecting = true, selection = if (it.selection.any { s -> s.id == item.id }) it.selection else it.selection + item) }
    }

    /** Tick or untick; the numbers are the order the moments post in. At most [MomentPickMax]. */
    fun toggle(item: LibraryItem) {
        val s = _state.value
        when {
            s.selection.any { it.id == item.id } -> _state.update { it.copy(selection = it.selection.filterNot { x -> x.id == item.id }, selecting = it.gallery != null || it.selection.size > 1) }
            s.selection.size >= MomentPickMax -> _state.update { it.copy(error = "You can pick up to $MomentPickMax at a time.") }
            else -> _state.update { it.copy(selection = it.selection + item, selecting = true) }
        }
    }

    fun clearSelection() = _state.update { it.copy(selection = emptyList(), selecting = false) }

    /** Next (M3): each ticked photo or video, in order, onto the review. */
    fun useSelection() {
        val picked = _state.value.selection
        if (picked.isEmpty()) return
        use { picked.mapNotNull { library.load(it) } }
    }

    /** Gallery beside the shutter (M3); for audio, the Files picker. */
    fun pickFromLibrary() {
        if (_state.value.mode == ComposerMode.Voice) { use { listOfNotNull(library.pick(LibraryKind.Audio)) }; return }
        openGallery()
    }

    fun pickFromGallery() = pickFromLibrary()

    /** The phone's own picker, several at once and no permission needed (from the gallery when access is off). */
    fun pickWithSystem() {
        val left = MomentPickMax - _state.value.items.size
        if (left <= 0) return
        use { library.pickMany(left) }
    }

    fun openGallery() {
        _state.update { it.copy(gallery = GalleryState(), selecting = true) }
        loadGallery()
    }

    private fun loadGallery() {
        viewModelScope.launch {
            val (photos, videos) = library.recentsVisual(120, ask = true)
            // The wider of the two answers decides what the screen says (one kind allowed is still a gallery).
            val access = listOf(photos.access, videos.access).minBy { it.ordinal }
            _state.update { s -> s.gallery?.let { s.copy(gallery = it.copy(photos = photos.items, videos = videos.items, access = access)) } ?: s }
        }
    }

    fun galleryTab(tab: GalleryTab) = _state.update { s -> s.copy(gallery = s.gallery?.copy(tab = tab)) }

    fun closeGallery() = _state.update { it.copy(gallery = null, selecting = false, selection = emptyList()) }

    /** Loads picks and puts them on the review, after anything already there; a long video opens the trimmer. */
    private fun use(load: suspend () -> List<CapturedMedia>) {
        if (_state.value.loadingPick) return
        _state.update { it.copy(loadingPick = true) }
        viewModelScope.launch {
            val media = load()
            val audio = media.firstOrNull { it.mime.startsWith("audio") }
            _state.update { s ->
                when {
                    media.isEmpty() -> s.copy(loadingPick = false)
                    audio != null && (audio.durationMs ?: 0) > MomentMediaMaxMs + 500 ->
                        s.copy(loadingPick = false, error = "Voice moments are up to a minute long. Pick a shorter one.")
                    audio != null -> s.copy(loadingPick = false, items = listOf(ComposerItem(ComposerCapture.Voice(audio.bytes, audio.mime, audio.durationMs ?: 0))), current = 0)
                    else -> {
                        val added = media.take(MomentPickMax - s.items.size).map { m ->
                            ComposerItem(ComposerCapture.Shot(m, if (m.mime.startsWith("video")) MomentKind.Video else MomentKind.Image))
                        }
                        // The first one over a minute goes on the stage, so its trimmer opens straight away.
                        val long = added.indexOfFirst { it.tooLong }
                        s.copy(loadingPick = false, items = s.items + added, current = s.items.size + long.coerceAtLeast(0), gallery = null, selecting = false, selection = emptyList())
                    }
                }
            }
            openTrimIfNeeded()
        }
    }

    /** M4's strip: put another picked item on the stage. */
    fun select(index: Int) {
        if (index !in _state.value.items.indices) return
        player.stop()
        _state.update { it.copy(current = index, playingBack = false, playbackHeld = false) }
        openTrimIfNeeded()
    }

    /** Takes the item on the stage out (the bin on the strip); with nothing left, back to the camera. */
    fun removeCurrent() {
        player.stop()
        _state.update { s ->
            val left = s.items.filterIndexed { i, _ -> i != s.current }
            s.copy(items = left, current = s.current.coerceAtMost((left.size - 1).coerceAtLeast(0)), playingBack = false, playbackHeld = false, trim = null)
        }
    }

    fun markUnplayable() = _state.update { s -> s.copy(items = s.items.mapIndexed { i, it -> if (i == s.current) it.copy(unplayable = true) else it }) }

    // ---- trimming (M3b)

    private fun openTrimIfNeeded() {
        val s = _state.value
        if (s.trim == null && s.item?.tooLong == true) openTrim(required = true)
    }

    /** The trimmer for the video on the stage: a minute from the start to begin with. */
    fun openTrim(required: Boolean = false) {
        val s = _state.value
        val item = s.item ?: return
        val video = item.video ?: return
        val path = video.path ?: return
        val duration = video.durationMs?.takeIf { it > 0 } ?: return
        val (start, end) = TrimWindow.initial(duration)
        _state.update { it.copy(trim = TrimState(s.current, duration, start, end, required = required || item.tooLong)) }
        trimJob?.cancel()
        trimJob = viewModelScope.launch {
            val frames = library.filmstrip(path, TrimFrames)
            _state.update { st -> st.trim?.let { st.copy(trim = it.copy(frames = frames)) } ?: st }
        }
    }

    /** Dragging a handle: the window stays between 1 s and a minute, inside the video ([TrimWindow]). */
    fun setTrim(startMs: Long, endMs: Long) {
        _state.update { s ->
            val t = s.trim ?: return@update s
            if (t.working) return@update s
            val (start, end) = TrimWindow.clamp(t.durationMs, t.startMs, startMs, endMs)
            // The stage follows the edge that moved (the start when both did: the window slid).
            val preview = if (end != t.endMs && start == t.startMs) end else start
            s.copy(trim = t.copy(startMs = start, endMs = end, previewMs = preview))
        }
    }

    /** Sliding the whole window by [deltaMs] from where it was when the drag began ([fromStart]..[fromEnd]). */
    fun slideTrim(fromStart: Long, fromEnd: Long, deltaMs: Long) {
        val t = _state.value.trim ?: return
        val (start, end) = TrimWindow.slide(t.durationMs, fromStart, fromEnd, deltaMs)
        setTrim(start, end)
    }

    /** Done: the trimmed copy replaces the original on this phone until it is shared. */
    fun applyTrim() {
        val t = _state.value.trim ?: return
        if (t.working) return
        val item = _state.value.items.getOrNull(t.index) ?: return
        val path = item.video?.path ?: return
        // The whole video, already a minute or less: nothing to cut.
        if (t.startMs == 0L && t.endMs >= t.durationMs && !item.tooLong) { _state.update { it.copy(trim = null) }; return }
        _state.update { it.copy(trim = t.copy(working = true)) }
        viewModelScope.launch {
            val cut = library.trim(path, t.startMs, t.endMs)
            _state.update { s ->
                if (cut == null) s.copy(trim = s.trim?.copy(working = false), error = "This video couldn’t be trimmed on this phone. Try a shorter one.")
                else s.copy(trim = null, items = s.items.mapIndexed { i, it -> if (i == t.index) it.copy(capture = ComposerCapture.Shot(cut, MomentKind.Video), unplayable = false) else it })
            }
        }
    }

    /** Cancel: a video that had to be trimmed (over a minute) leaves the review; otherwise it stays as it was. */
    fun cancelTrim() {
        val t = _state.value.trim ?: return
        if (t.working) return
        if (t.required) removeCurrent() else _state.update { it.copy(trim = null) }
    }

    // ---- camera and recording

    fun toggleFlash() { val on = !_state.value.flash; camera.setFlash(on); _state.update { it.copy(flash = on) } }
    fun flip() = camera.flip()

    fun takePhoto() {
        viewModelScope.launch {
            val shot = camera.takePhoto()
            _state.update { if (shot != null) it.withSingle(ComposerCapture.Shot(shot, MomentKind.Image)) else it.copy(error = "The photo didn’t come out. Try again.") }
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
            _state.update { it.copy(recordingVideo = true, recordingSince = now()) }
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
            _state.update { if (clip != null) it.withSingle(ComposerCapture.Shot(clip, MomentKind.Video)).copy(mode = ComposerMode.Video) else it.copy(error = "The video didn’t record. Try again.") }
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
                    voiceStartedAt = now()
                    _state.update { it.copy(recordingVoice = true, micDenied = false, recordingSince = voiceStartedAt) }
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
                RecordingStart.Failed -> _state.update { it.copy(error = "The microphone isn’t available right now.") }
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
        _state.update { it.copy(recordingVoice = false, recordingSince = null) }
        viewModelScope.launch {
            val clip = recorder.stop()
            val heldMs = now() - voiceStartedAt
            // Under half a second is a slip, not a moment.
            if (clip != null && heldMs >= 500) {
                _state.update { it.withSingle(ComposerCapture.Voice(clip.bytes, clip.contentType, heldMs.coerceAtMost(com.circaso.core.platform.MomentVideoMaxMs), levels)) }
            }
        }
    }

    /** Slide to cancel (M5b): nothing is kept. */
    fun voiceCancel() {
        if (voiceStarting) { voiceReleaseAsked = true }
        voiceCap?.cancel()
        voiceSampler?.cancel()
        _liveLevels.value = emptyList()
        _state.update { it.copy(recordingVoice = false, recordingSince = null) }
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

    /** How far listening back has got (0..1), for the wave's fill. */
    fun playbackProgress(): Float? = player.progress()

    fun onVoiceBackground(bg: String) = _state.update { it.copy(voiceBackground = bg) }

    /** The close button on the review: everything picked or captured is let go, back to the camera. */
    fun discardCapture() { player.stop(); _state.update { it.copy(items = emptyList(), current = 0, playingBack = false, playbackHeld = false, trim = null, forwarding = false) } }

    fun onCaption(text: String) = _state.update { s -> s.copy(items = s.items.mapIndexed { i, it -> if (i == s.current) it.copy(caption = text.take(700)) else it }) }
    fun onText(text: String) = _state.update { it.copy(text = text.take(700)) }
    fun onBackground(bg: String) = _state.update { it.copy(background = bg) }
    /** Aa cycles the fonts. */
    fun toggleStyle() = _state.update { it.copy(style = MomentTextStyle.entries[(it.style.ordinal + 1) % MomentTextStyle.entries.size]) }
    fun errorShown() = _state.update { it.copy(error = null) }
    /** A line for the toast that is not an error (after forwarding). */
    fun notice(text: String) = _state.update { it.copy(error = text) }

    // ---- forward (V4, from the review)

    fun openForward(open: Boolean) = _state.update { it.copy(forwarding = open && it.item != null) }

    /** The item on the stage as something to send to a chat; a video's bytes are read only when it is sent. */
    fun forwardItem(): ForwardMedia? {
        val item = _state.value.item ?: return null
        return when (val cap = item.capture) {
            is ComposerCapture.Shot -> ForwardMedia(cap.kind, cap.media.bytes.takeIf { it.isNotEmpty() }, cap.media.path, cap.media.mime, cap.media.width, cap.media.height, cap.media.durationMs, cap.media.thumb, item.caption)
            is ComposerCapture.Voice -> ForwardMedia(MomentKind.Voice, cap.bytes, null, cap.mime, null, null, cap.durationMs, null, null)
        }
    }

    // ---- sharing

    /**
     * Hands the moments to the outbox, in order, and closes (optimistic; my ring shows Posting, M6). Several picked
     * at once each become their own moment. A video too large to post as it is gets re-encoded first.
     */
    fun share() {
        val s = _state.value
        if (!s.canShare) return
        if (s.items.isEmpty()) {
            _state.update { it.copy(sharing = true) }
            viewModelScope.launch {
                repository.post(MomentDraft.Text(s.text.trim(), s.background, s.style))
                _state.update { it.copy(sharing = false, shared = true) }
            }
            return
        }
        _state.update { it.copy(sharing = true) }
        viewModelScope.launch {
            // Preparing and queueing run as one piece of background work: leaving Circaso while a video re-encodes
            // doesn't stop it, and the queued moments are the outbox's from there (they survive the process).
            uploads.keep(com.circaso.core.platform.UploadKind.Moment) {
                val drafts = mutableListOf<MomentDraft>()
                for ((index, item) in s.items.withIndex()) {
                    when (val draft = draftOf(item)) {
                        is Draft.Ready -> drafts += draft.draft
                        Draft.Missing -> { _state.update { it.copy(sharing = false, preparing = false, current = index, error = "This video isn’t on the phone any more. Pick it again.") }; return@keep }
                        Draft.TooLarge -> { _state.update { it.copy(sharing = false, preparing = false, current = index, error = "This is too large to share. Keep videos under a minute.") }; return@keep }
                    }
                }
                // Only once every one is ready: never half a batch posted and the rest left behind.
                drafts.forEach { repository.post(it) }
                _state.update { it.copy(sharing = false, preparing = false, shared = true) }
            }
        }
    }

    private sealed interface Draft {
        class Ready(val draft: MomentDraft) : Draft
        data object Missing : Draft
        data object TooLarge : Draft
    }

    /** The draft for one item, with its bytes read; or why not (the file is gone, or too large even re-encoded). */
    private suspend fun draftOf(item: ComposerItem): Draft = when (val cap = item.capture) {
        is ComposerCapture.Voice -> if (cap.bytes.size > MomentMediaMaxBytes) Draft.TooLarge
        else Draft.Ready(MomentDraft.Media(MomentKind.Voice, cap.bytes, cap.mime, item.caption, null, cap.durationMs, background = _state.value.voiceBackground, levels = cap.levels))
        is ComposerCapture.Shot -> {
            var media = cap.media
            var bytes = media.bytes.takeIf { it.isNotEmpty() } ?: media.path?.let { library.read(it) } ?: ByteArray(0)
            if (bytes.size > MomentMediaMaxBytes && cap.kind == MomentKind.Video && media.path != null) {
                // A minute shot at a high bit rate: the same clip at the moments' bit rate.
                _state.update { it.copy(preparing = true) }
                library.trim(media.path, 0, media.durationMs ?: MomentMediaMaxMs)?.let { smaller ->
                    media = smaller
                    bytes = smaller.bytes.takeIf { it.isNotEmpty() } ?: smaller.path?.let { library.read(it) } ?: ByteArray(0)
                }
            }
            when {
                bytes.isEmpty() -> Draft.Missing
                bytes.size > MomentMediaMaxBytes -> Draft.TooLarge
                else -> Draft.Ready(MomentDraft.Media(cap.kind, bytes, media.mime, item.caption, media.thumb, media.durationMs, media.width, media.height))
            }
        }
    }

    override fun onCleared() { player.stop(); camera.close() }

    private fun MomentComposerUiState.withSingle(capture: ComposerCapture) = copy(items = listOf(ComposerItem(capture)), current = 0)

    companion object {
        /** Frames in the trim bar's filmstrip (design M3b shows eight). */
        const val TrimFrames = 8
        /** The recorder's meter is read this often while recording (a minute is 1,200 samples, 48 bars). */
        const val SampleMs = 50L
        /** Bars in the live wave while recording. */
        const val LiveBars = 32
        /** The shortest clip the trimmer keeps. */
        const val MinTrimMs = TrimWindow.MinMs
    }
}

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
