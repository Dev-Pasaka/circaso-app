package com.circaso.feature.mediacompose

import com.circaso.core.platform.CameraStart
import com.circaso.core.platform.CapturedMedia
import com.circaso.core.platform.LibraryAccess
import com.circaso.core.platform.LibraryItem
import com.circaso.core.platform.LibraryKind
import com.circaso.core.platform.MediaLibrary
import com.circaso.core.platform.MomentCamera
import com.circaso.core.platform.MomentMediaMaxBytes
import com.circaso.core.platform.MomentMediaMaxMs
import com.circaso.core.platform.MomentPickMax
import com.circaso.core.platform.PickedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The media composer that a moment and a chat share (Pasaka, 2026-11-07: "the same design and the same process"):
 * the camera with its recents strip (M1), the gallery (M3), the trimmer (M3b) and the review with its caption (M4).
 * What happens on Send is the caller's: a moment posts to an audience, a chat sends to the chat. Moments add text and
 * voice on top; chats add documents.
 */

/** The composer's modes, in the order of the mode row (M1). Photo and Video share the camera: tap or hold. */
enum class ComposerMode { Text, Photo, Video, Voice }

/** What is ready to send (M4 for a photo or video, M5 for a recorded voice, a document in a chat). */
sealed interface ComposerCapture {
    class Shot(val media: CapturedMedia) : ComposerCapture {
        val video: Boolean get() = media.mime.startsWith("video")
    }
    /** [levels]: the waveform sampled while recording (null for a picked audio file). Moments only. */
    class Voice(val bytes: ByteArray, val mime: String, val durationMs: Long, val levels: List<Float>? = null) : ComposerCapture
    /** A document picked from Files. Chats only: a moment has no file kind. */
    class Document(val file: PickedFile) : ComposerCapture
}

/**
 * One picked or captured item before sending (M4). Several picked at once go in order, each with its own caption.
 * [unplayable]: this phone couldn't preview the video (it still sends).
 */
data class ComposerItem(val capture: ComposerCapture, val caption: String = "", val unplayable: Boolean = false) {
    val video: CapturedMedia? get() = (capture as? ComposerCapture.Shot)?.takeIf { it.video }?.media
    /** Longer than a minute: it opens the trimmer, and can't be sent until it has been trimmed (M3b). */
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
) {
    val selectedMs: Long get() = endMs - startMs
}

/** Everything the shared composer shows: the camera, the strip, the gallery, the trimmer and the review. */
data class MediaComposeState(
    val mode: ComposerMode = ComposerMode.Photo,
    val camera: CameraStart? = null,
    val flash: Boolean = false,
    val recordingVideo: Boolean = false,
    /** When the current recording began (M1b, M5b: the elapsed time and the ring filling to 1:00). */
    val recordingSince: Long? = null,
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
    /** Re-encoding a video that is too large to send as it is. */
    val preparing: Boolean = false,
    val error: String? = null,
) {
    val item: ComposerItem? get() = items.getOrNull(current)
    val capture: ComposerCapture? get() = item?.capture
    val caption: String get() = item?.caption.orEmpty()
    /** Every item can go: nothing is waiting for the trimmer, nothing is still being made smaller. */
    val ready: Boolean get() = items.isNotEmpty() && items.none { it.tooLong } && trim == null && !preparing
}

/** What one item came to when it was read for sending; see [MediaComposer.prepare]. */
sealed interface PreparedItem {
    /** [media]: the item as it goes (a re-encoded copy when the original was too large); [bytes] are its file. */
    class Shot(val bytes: ByteArray, val media: CapturedMedia, val caption: String) : PreparedItem
    class Voice(val capture: ComposerCapture.Voice, val caption: String) : PreparedItem
    class Document(val bytes: ByteArray, val file: PickedFile, val caption: String) : PreparedItem
    /** The file isn't on the phone any more (a picked video's copy was cleared). */
    data object Missing : PreparedItem
    /** Too large to send even after re-encoding. */
    data object TooLarge : PreparedItem
}

/**
 * The shared composer's behaviour. It keeps no state of its own: [read] and [write] reach the caller's
 * [MediaComposeState] (a moment keeps it inside its own state, a chat likewise), so each screen stays one state flow.
 * [noun] names what is sent in its messages ("moment", "video").
 */
class MediaComposer(
    private val scope: CoroutineScope,
    private val library: MediaLibrary,
    private val camera: MomentCamera?,
    private val now: () -> Long,
    private val read: () -> MediaComposeState,
    private val write: ((MediaComposeState) -> MediaComposeState) -> Unit,
    /** At most this many at once, the same for moments and chats. */
    private val maxItems: Int = MomentPickMax,
    /** The largest file that may be sent: the server's attachment limit, less encryption. */
    private val maxBytes: Long = MomentMediaMaxBytes,
) {
    private var recentsJob: Job? = null
    private var trimJob: Job? = null
    // A release can arrive while the recording is still starting (a permission prompt, the encoder warming up). It is
    // remembered, and the recording stops as soon as it has started, never left running.
    private var videoStarting = false
    private var videoReleaseAsked = false

    private val state get() = read()
    private fun update(f: (MediaComposeState) -> MediaComposeState) = write(f)

    /**
     * Opens the camera (asking the first time), then fills the strip for the current mode: one after the other, so the
     * camera's prompt and the library's never stack.
     */
    fun start(withCamera: Boolean = true) {
        scope.launch {
            if (withCamera && camera != null) { val started = camera.open(); update { it.copy(camera = started) } }
            loadRecents(state.mode)
        }
    }

    fun setMode(mode: ComposerMode) {
        update { it.copy(mode = mode, error = null, recents = emptyList(), recentsAccess = null, selecting = false, selection = emptyList()) }
        loadRecents(mode)
    }

    /**
     * The strip under the camera: recent photos, videos or audio from the phone. Each kind asks for its own access the
     * first time it shows (on Android photo access says nothing about videos), so a strip is never silently empty:
     * without access it says so and offers to allow it.
     */
    fun loadRecents(mode: ComposerMode, ask: Boolean = true) {
        val kind = libraryKind(mode) ?: return
        recentsJob?.cancel()
        recentsJob = scope.launch {
            val r = library.recents(kind, 30, ask)
            if (state.mode == mode) update { it.copy(recents = r.items, recentsAccess = r.access) }
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
        val kind = libraryKind(state.mode) ?: LibraryKind.Photo
        scope.launch {
            library.manage(kind)
            loadRecents(state.mode, ask = false)
            if (state.gallery != null) loadGallery()
        }
    }

    // ---- picking from the library

    /** One tile from the strip: used at once, or ticked when several are being picked. */
    fun useRecent(item: LibraryItem) {
        if (state.selecting) toggle(item) else use { listOfNotNull(library.load(item)) }
    }

    /** A long press on a strip tile: start picking several (WhatsApp-style), this one first. */
    fun startSelecting(item: LibraryItem) {
        if (item.kind == LibraryKind.Audio) { useRecent(item); return }
        update { it.copy(selecting = true, selection = if (it.selection.any { s -> s.id == item.id }) it.selection else it.selection + item) }
    }

    /** Tick or untick; the numbers are the order they go in. At most [maxItems]. */
    fun toggle(item: LibraryItem) {
        val s = state
        when {
            s.selection.any { it.id == item.id } -> update { it.copy(selection = it.selection.filterNot { x -> x.id == item.id }, selecting = it.gallery != null || it.selection.size > 1) }
            s.selection.size >= maxItems -> update { it.copy(error = "You can pick up to $maxItems at a time.") }
            else -> update { it.copy(selection = it.selection + item, selecting = true) }
        }
    }

    fun clearSelection() = update { it.copy(selection = emptyList(), selecting = false) }

    /** Next (M3): each ticked photo or video, in order, onto the review. */
    fun useSelection() {
        val picked = state.selection
        if (picked.isEmpty()) return
        use { picked.mapNotNull { library.load(it) } }
    }

    /** Gallery beside the shutter (M3); for audio, the Files picker. */
    fun pickFromLibrary() {
        if (state.mode == ComposerMode.Voice) { use { listOfNotNull(library.pick(LibraryKind.Audio)) }; return }
        openGallery()
    }

    /** The phone's own picker, several at once and no permission needed (from the gallery when access is off). */
    fun pickWithSystem() {
        val left = maxItems - state.items.size
        if (left <= 0) return
        use { library.pickMany(left) }
    }

    fun openGallery() {
        update { it.copy(gallery = GalleryState(), selecting = true) }
        loadGallery()
    }

    private fun loadGallery() {
        scope.launch {
            val (photos, videos) = library.recentsVisual(120, ask = true)
            // The wider of the two answers decides what the screen says (one kind allowed is still a gallery).
            val access = listOf(photos.access, videos.access).minBy { it.ordinal }
            update { s -> s.gallery?.let { s.copy(gallery = it.copy(photos = photos.items, videos = videos.items, access = access)) } ?: s }
        }
    }

    fun galleryTab(tab: GalleryTab) = update { s -> s.copy(gallery = s.gallery?.copy(tab = tab)) }

    fun closeGallery() = update { it.copy(gallery = null, selecting = false, selection = emptyList()) }

    /**
     * A document from Files (chats): onto the review like a photo, with its caption. Refused before the review when it
     * is over the attachment limit, so nobody waits for a send that can't happen. False when nothing was chosen.
     */
    suspend fun pickDocument(): Boolean {
        if (state.loadingPick) return true
        update { it.copy(loadingPick = true) }
        val file = library.pickFile()
        update { s ->
            when {
                file == null -> s.copy(loadingPick = false)
                file.size > maxBytes -> s.copy(loadingPick = false, error = "Documents up to 32 MB can be sent. This one is ${sizeLabel(file.size)}.")
                s.items.size >= maxItems -> s.copy(loadingPick = false, error = "You can pick up to $maxItems at a time.")
                else -> s.copy(loadingPick = false, items = s.items + ComposerItem(ComposerCapture.Document(file)), current = s.items.size)
            }
        }
        return file != null
    }

    /**
     * Items shared in from another app, onto the review in the order they came: photos and videos as the composer's
     * own (a long video opens the trimmer), documents as documents ([documents] false: a moment, which has none, leaves
     * them out). What can't go is said politely before anything is sent (over the limit, too many, unreadable).
     */
    suspend fun addShared(files: List<SharedFile>, documents: Boolean = true) {
        val check = checkShare(files, maxBytes, maxItems - state.items.size)
        update { it.copy(loadingPick = true) }
        var unreadable = 0
        var leftOut = 0
        val added = check.usable.mapNotNull { f ->
            when (sharedKind(f.mime, f.name)) {
                SharedKind.Document -> if (documents) ComposerItem(ComposerCapture.Document(PickedFile(f.name, f.mime.ifBlank { "application/octet-stream" }, f.size.coerceAtLeast(0), path = f.path))) else { leftOut++; null }
                SharedKind.Photo -> library.loadFile(f.path, f.mime)?.let { ComposerItem(ComposerCapture.Shot(it)) } ?: run { unreadable++; null }
                SharedKind.Video -> library.loadFile(f.path, f.mime.ifBlank { "video/mp4" })?.let { ComposerItem(ComposerCapture.Shot(it)) } ?: run { unreadable++; null }
            }
        }
        val notes = listOfNotNull(
            check.notice,
            unreadable.takeIf { it > 0 }?.let { if (it == 1) "One item couldn’t be opened." else "$it items couldn’t be opened." },
            leftOut.takeIf { it > 0 }?.let { "Moments are photos and videos, so ${if (it == 1) "the document was" else "$it documents were"} left out." },
        )
        update { s ->
            val long = added.indexOfFirst { it.tooLong }
            s.copy(
                loadingPick = false, items = s.items + added, current = s.items.size + long.coerceAtLeast(0),
                error = notes.joinToString(" ").ifBlank { null } ?: s.error,
            )
        }
        openTrimIfNeeded()
    }

    /** Loads picks and puts them on the review, after anything already there; a long video opens the trimmer. */
    fun use(load: suspend () -> List<CapturedMedia>) {
        if (state.loadingPick) return
        update { it.copy(loadingPick = true) }
        scope.launch {
            val media = load()
            val audio = media.firstOrNull { it.mime.startsWith("audio") }
            update { s ->
                when {
                    media.isEmpty() -> s.copy(loadingPick = false)
                    audio != null && (audio.durationMs ?: 0) > MomentMediaMaxMs + 500 ->
                        s.copy(loadingPick = false, error = "Voice moments are up to a minute long. Pick a shorter one.")
                    audio != null -> s.copy(loadingPick = false, items = listOf(ComposerItem(ComposerCapture.Voice(audio.bytes, audio.mime, audio.durationMs ?: 0))), current = 0)
                    else -> {
                        val added = media.take(maxItems - s.items.size).map { m -> ComposerItem(ComposerCapture.Shot(m)) }
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
        if (index !in state.items.indices) return
        update { it.copy(current = index) }
        openTrimIfNeeded()
    }

    /** Takes the item on the stage out (the bin on the strip); with nothing left, back to the camera. */
    fun removeCurrent() = update { s ->
        val left = s.items.filterIndexed { i, _ -> i != s.current }
        s.copy(items = left, current = s.current.coerceAtMost((left.size - 1).coerceAtLeast(0)), trim = null)
    }

    fun markUnplayable() = update { s -> s.copy(items = s.items.mapIndexed { i, it -> if (i == s.current) it.copy(unplayable = true) else it }) }

    /** The close button on the review: everything picked or captured is let go, back to the camera. */
    fun discard() = update { it.copy(items = emptyList(), current = 0, trim = null) }

    fun onCaption(text: String) = update { s -> s.copy(items = s.items.mapIndexed { i, it -> if (i == s.current) it.copy(caption = text.take(MaxCaption)) else it }) }

    fun errorShown() = update { it.copy(error = null) }

    /** A line for the toast. */
    fun notice(text: String) = update { it.copy(error = text) }

    /** One capture alone on the review (a photo or video just taken, a voice just recorded). */
    fun setSingle(capture: ComposerCapture) = update { it.copy(items = listOf(ComposerItem(capture)), current = 0) }

    // ---- trimming (M3b)

    private fun openTrimIfNeeded() {
        val s = state
        if (s.trim == null && s.item?.tooLong == true) openTrim(required = true)
    }

    /** The trimmer for the video on the stage: a minute from the start to begin with. */
    fun openTrim(required: Boolean = false) {
        val s = state
        val item = s.item ?: return
        val video = item.video ?: return
        val path = video.path ?: return
        val duration = video.durationMs?.takeIf { it > 0 } ?: return
        update { it.copy(trim = TrimState(s.current, duration, 0, minOf(duration, MomentMediaMaxMs), required = required || item.tooLong)) }
        trimJob?.cancel()
        trimJob = scope.launch {
            val frames = library.filmstrip(path, TrimFrames)
            update { st -> st.trim?.let { st.copy(trim = it.copy(frames = frames)) } ?: st }
        }
    }

    /** Dragging a handle: the window stays between 1 s and a minute, inside the video. */
    fun setTrim(startMs: Long, endMs: Long) = update { s ->
        val t = s.trim ?: return@update s
        var start = startMs.coerceIn(0, t.durationMs)
        var end = endMs.coerceIn(0, t.durationMs)
        val movedStart = start != t.startMs
        if (end - start < MinTrimMs) { if (movedStart) start = (end - MinTrimMs).coerceAtLeast(0) else end = (start + MinTrimMs).coerceAtMost(t.durationMs) }
        if (end - start > MomentMediaMaxMs) { if (movedStart) end = start + MomentMediaMaxMs else start = end - MomentMediaMaxMs }
        s.copy(trim = t.copy(startMs = start, endMs = end))
    }

    /** Done: the trimmed copy replaces the original on this phone until it is sent. */
    fun applyTrim() {
        val t = state.trim ?: return
        if (t.working) return
        val item = state.items.getOrNull(t.index) ?: return
        val path = item.video?.path ?: return
        // The whole video, already a minute or less: nothing to cut.
        if (t.startMs == 0L && t.endMs >= t.durationMs && !item.tooLong) { update { it.copy(trim = null) }; return }
        update { it.copy(trim = t.copy(working = true)) }
        scope.launch {
            val cut = library.trim(path, t.startMs, t.endMs)
            update { s ->
                if (cut == null) s.copy(trim = s.trim?.copy(working = false), error = "This video couldn’t be trimmed on this phone. Try a shorter one.")
                else s.copy(trim = null, items = s.items.mapIndexed { i, it -> if (i == t.index) it.copy(capture = ComposerCapture.Shot(cut), unplayable = false) else it })
            }
        }
    }

    /** Cancel: a video that had to be trimmed (over a minute) leaves the review; otherwise it stays as it was. */
    fun cancelTrim() {
        val t = state.trim ?: return
        if (t.working) return
        if (t.required) removeCurrent() else update { it.copy(trim = null) }
    }

    // ---- the camera

    fun toggleFlash() { val on = !state.flash; camera?.setFlash(on); update { it.copy(flash = on) } }
    fun flip() { camera?.flip() }

    fun takePhoto() {
        val cam = camera ?: return
        scope.launch {
            val shot = cam.takePhoto()
            if (shot != null) setSingle(ComposerCapture.Shot(shot)) else update { it.copy(error = "The photo didn’t come out. Try again.") }
        }
    }

    /** The shutter went down and stayed down: record a video (from Photo or Video mode). */
    fun holdStart() {
        val cam = camera ?: return
        if (videoStarting || state.recordingVideo || state.camera != CameraStart.Started) return
        videoStarting = true
        videoReleaseAsked = false
        scope.launch {
            val ok = cam.startVideo()
            videoStarting = false
            if (!ok) { update { it.copy(error = "The video didn’t start. Try again.") }; return@launch }
            update { it.copy(recordingVideo = true, recordingSince = now()) }
            if (videoReleaseAsked) stopVideo()
        }
    }

    /** The finger lifted: stop whatever is recording, or ask the starting recording to stop as soon as it can. */
    fun holdEnd() {
        if (videoStarting) videoReleaseAsked = true else if (state.recordingVideo) stopVideo()
    }

    private fun stopVideo() {
        val cam = camera ?: return
        update { it.copy(recordingVideo = false, recordingSince = null) }
        scope.launch {
            val clip = cam.stopVideo()
            if (clip != null) { setSingle(ComposerCapture.Shot(clip)); update { it.copy(mode = ComposerMode.Video) } }
            else update { it.copy(error = "The video didn’t record. Try again.") }
        }
    }

    // ---- sending

    /**
     * One item read for sending, the same for a moment and a chat: a photo as it is, a video from its file, and a video
     * too large to send as it is re-encoded first with the library's own encoder (the trimmer's, over the whole clip),
     * which shows "Making the video smaller…" meanwhile.
     */
    suspend fun prepare(item: ComposerItem): PreparedItem = when (val cap = item.capture) {
        is ComposerCapture.Voice -> if (cap.bytes.size > maxBytes) PreparedItem.TooLarge else PreparedItem.Voice(cap, item.caption)
        is ComposerCapture.Document -> {
            val bytes = cap.file.bytes?.takeIf { it.isNotEmpty() } ?: cap.file.path?.let { library.read(it) } ?: ByteArray(0)
            when {
                bytes.isEmpty() -> PreparedItem.Missing
                bytes.size > maxBytes -> PreparedItem.TooLarge
                else -> PreparedItem.Document(bytes, cap.file, item.caption)
            }
        }
        is ComposerCapture.Shot -> {
            var media = cap.media
            var bytes = media.bytes.takeIf { it.isNotEmpty() } ?: media.path?.let { library.read(it) } ?: ByteArray(0)
            val path = media.path
            if (bytes.size > maxBytes && cap.video && path != null) {
                // A minute shot at a high bit rate: the same clip at the moments' bit rate.
                update { it.copy(preparing = true) }
                library.trim(path, 0, media.durationMs ?: MomentMediaMaxMs)?.let { smaller ->
                    media = smaller
                    bytes = smaller.bytes.takeIf { it.isNotEmpty() } ?: smaller.path?.let { library.read(it) } ?: ByteArray(0)
                }
            }
            when {
                bytes.isEmpty() -> PreparedItem.Missing
                bytes.size > maxBytes -> PreparedItem.TooLarge
                else -> PreparedItem.Shot(bytes, media, item.caption)
            }
        }
    }

    companion object {
        /** Frames in the trim bar's filmstrip (design M3b shows eight). */
        const val TrimFrames = 8
        /** The shortest clip the trimmer keeps. */
        const val MinTrimMs = 1_000L
        const val MaxCaption = 700
    }
}

/** "2.4 MB", "820 KB": a file's size as people read it. */
fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> {
        val tenths = (bytes * 10 + 512 * 1024) / (1024 * 1024)
        "${tenths / 10}.${tenths % 10} MB"
    }
    bytes >= 1024 -> "${(bytes + 512) / 1024} KB"
    else -> "$bytes bytes"
}
