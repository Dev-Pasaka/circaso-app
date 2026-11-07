package com.circaso.feature.messaging.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.circaso.designsystem.chrome.CircasoSheet
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.platform.MediaLibrary
import com.circaso.core.platform.MomentCamera
import com.circaso.feature.mediacompose.ComposerCapture
import com.circaso.feature.mediacompose.ComposerMode
import com.circaso.feature.mediacompose.MediaComposeCopy
import com.circaso.feature.mediacompose.MediaComposeStage
import com.circaso.feature.mediacompose.MediaComposeState
import com.circaso.feature.mediacompose.MediaComposer
import com.circaso.feature.mediacompose.MediaSendButton
import com.circaso.feature.mediacompose.PreparedItem
import com.circaso.feature.mediacompose.RecipientChip
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A chat's limits (Pasaka, 2026-11-07: "Trimming is for moments. When sharing in a chat ... there's no need to trim
 * videos ... The limit should be on the file size"). No length limit: null means any video goes untrimmed; the
 * trimmer stays as an option, never a gate. The limit is the file's size, [com.circaso.core.platform.ChatFileMaxBytes].
 */
val ChatVideoMaxMs: Long? = null

/**
 * Ten at once, as a moment: each item can now be up to 100 MB and is copied into Circaso's files before it goes, so
 * ten keeps a batch to about a gigabyte on the phone and a reasonable wait, and matches the moment composer's picker.
 */
const val ChatPickMax: Int = com.circaso.core.platform.MomentPickMax

/** Where the attach sheet opens the composer: the camera (with the recents strip), the gallery, or Files. */
enum class ChatComposeStart {
    Camera, Gallery, Document,
    /** Items shared in from another app (IncomingShares), straight onto the review. */
    Shared,
}

/**
 * Puts one prepared item into a chat's queue through the chat's own media path (`MediaMessages`: encrypted on the
 * phone, uploaded and sent by the outbox, waiting offline like any message). False when this phone couldn't even
 * queue it.
 */
fun interface ChatMediaSender {
    /** [album]: the id the photos and videos sent together share, so the chat shows them as one album; null alone. */
    suspend fun send(chatId: String, item: PreparedItem, album: String?): Boolean
}

data class ChatMediaUiState(
    /** The camera, strip, gallery, trimmer and review: the shared composer's, the same a moment uses. */
    val media: MediaComposeState = MediaComposeState(),
    val title: String = "",
    val avatarUrl: String? = null,
    val group: Boolean = false,
    val sending: Boolean = false,
    val sent: Boolean = false,
    /** Opened for a document and Files was closed with nothing chosen: there is nothing to show, so it closes. */
    val closed: Boolean = false,
    /**
     * Send was tapped with items over the size limit among them: how many would be left out, while the sheet asks
     * whether to send the rest. Null when nothing is being asked.
     */
    val askLeaveOut: Int? = null,
) {
    /** Something can go: ready, and not every item is over the limit. */
    val canSend: Boolean get() = !sending && media.ready && media.anySendable
}

/**
 * Sending photos, videos and documents into a chat (1:1 or group) with the same composer, steps and look as posting a
 * moment (Pasaka, 2026-11-07). Several go in order, each with its own caption, each its own message.
 */
class ChatMediaComposerViewModel(
    private val chatId: String,
    val start: ChatComposeStart,
    library: MediaLibrary,
    val camera: MomentCamera?,
    chats: ChatsRepository,
    private val sender: ChatMediaSender,
    private val uploads: com.circaso.core.platform.BackgroundUploads = com.circaso.core.platform.NoBackgroundUploads,
    now: () -> Long = { com.circaso.core.sync.nowMillis() },
    /** Shared in from another app: further chats the same items go to (each its own messages, its own album). */
    private val moreChats: List<String> = emptyList(),
    /** Where [ChatComposeStart.Shared] takes its items from. */
    private val shared: () -> List<com.circaso.feature.mediacompose.SharedFile>? = com.circaso.feature.mediacompose.IncomingShares::take,
) : ViewModel() {
    private val chatIds = (listOf(chatId) + moreChats).distinct()
    private val _state = MutableStateFlow(ChatMediaUiState(media = MediaComposeState(maxVideoMs = ChatVideoMaxMs)))
    val state: StateFlow<ChatMediaUiState> = _state.asStateFlow()

    val composer = MediaComposer(
        viewModelScope, library, camera, now,
        read = { _state.value.media },
        write = { f -> _state.update { it.copy(media = f(it.media)) } },
        maxItems = ChatPickMax,
        maxBytes = com.circaso.core.platform.ChatFileMaxBytes,
        sendAsIs = true,
        // Chats: any length, up to the size limit (Pasaka, 2026-11-07); a moment keeps its minute.
        recordingLimits = com.circaso.core.platform.RecordingLimits.chat(com.circaso.core.platform.ChatFileMaxBytes),
    )

    init {
        viewModelScope.launch {
            if (chatIds.size == 1) chats.chat(chatId).collect { chat ->
                if (chat != null) _state.update { it.copy(title = chat.title, avatarUrl = chat.avatarUrl, group = chat.kind == ChatKind.Group) }
            } else chats.chats.collect { list ->
                // Several chats: their names, as many as fit ("Wanjiru, Holiday Fund and 1 more").
                val names = chatIds.mapNotNull { id -> list.firstOrNull { it.chat.chatId == id }?.chat?.title }
                val title = when {
                    names.size <= 2 -> names.joinToString(" and ")
                    else -> "${names.take(2).joinToString(", ")} and ${names.size - 2} more"
                }
                _state.update { it.copy(title = title, group = true) }
            }
        }
        when (start) {
            ChatComposeStart.Camera -> composer.start()
            // Straight to the gallery (M3); the camera opens behind it only if the person closes the gallery.
            ChatComposeStart.Gallery -> { composer.openGallery() }
            ChatComposeStart.Document -> viewModelScope.launch {
                if (!composer.pickDocument() && _state.value.media.items.isEmpty()) _state.update { it.copy(closed = true) }
            }
            ChatComposeStart.Shared -> viewModelScope.launch {
                val files = shared().orEmpty()
                composer.addShared(files)
                if (_state.value.media.items.isEmpty()) _state.update { it.copy(closed = true) }
            }
        }
    }

    /** The gallery closed without a pick when it was the way in: the camera behind it starts then (and asks then). */
    fun closeGallery() {
        composer.closeGallery()
        if (_state.value.media.camera == null && _state.value.media.items.isEmpty()) composer.start()
    }

    /**
     * Close on the review: everything picked is let go. Opened on Files, that is the way back to the chat; otherwise
     * back to the camera (as a moment does), started now if the composer was opened on the gallery.
     */
    fun discard() {
        // Being made ready and queued: what is on screen is what goes, so it can't be let go part-way.
        if (_state.value.sending) return
        composer.discard()
        when {
            start == ChatComposeStart.Document || start == ChatComposeStart.Shared -> _state.update { it.copy(closed = true) }
            _state.value.media.camera == null -> composer.start()
        }
    }

    /** Another document onto the review (the + on a document's review). */
    fun addDocument() { viewModelScope.launch { composer.pickDocument() } }

    /**
     * Each item, in order, checked and made ready (a video or document goes as it is, from its file, up to
     * [com.circaso.core.platform.ChatFileMaxBytes]), then queued as its own message. Items over the limit are marked on
     * the strip and never go: with any among them, Send first asks whether to send the rest ([askLeaveOut]), and
     * [leaveOutConfirmed] sends them. Only once every one is ready is anything queued: never half a batch sent and the
     * rest left behind. If queueing stops part-way, what went is taken off the review, so trying again never sends twice.
     */
    fun send() = send(confirmed = false)

    /** The sheet's "Send the rest": the items over the limit stay out, the others go. */
    fun leaveOutConfirmed() {
        _state.update { it.copy(askLeaveOut = null) }
        send(confirmed = true)
    }

    /** The sheet's "Go Back": nothing is sent, the marked items stay on the strip to remove or trim. */
    fun leaveOutCancelled() = _state.update { st ->
        st.copy(askLeaveOut = null, media = st.media.copy(current = st.media.items.indexOfFirst { it.overLimit != null }.takeIf { it >= 0 } ?: st.media.current))
    }

    private fun send(confirmed: Boolean) {
        val s = _state.value
        if (!s.canSend) return
        if (!confirmed && s.media.overLimitCount > 0) { _state.update { it.copy(askLeaveOut = it.media.overLimitCount) }; return }
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            val kind = when {
                s.media.items.any { it.video != null } -> com.circaso.core.platform.UploadKind.Video
                s.media.items.all { it.capture is ComposerCapture.Document } -> com.circaso.core.platform.UploadKind.File
                else -> com.circaso.core.platform.UploadKind.Photo
            }
            uploads.keep(kind, s.title) {
                val ready = mutableListOf<PreparedItem>()
                val going = mutableListOf<com.circaso.feature.mediacompose.ComposerItem>()
                for ((index, item) in s.media.items.withIndex()) {
                    if (item.overLimit != null) continue // marked and confirmed: left out
                    when (val p = composer.prepare(item)) {
                        PreparedItem.Missing -> { stop(index, if (item.capture is ComposerCapture.Document) "This document isn’t on the phone any more. Pick it again." else "This video isn’t on the phone any more. Pick it again."); return@keep }
                        is PreparedItem.TooLarge -> {
                            // Found only now (its size wasn't known at pick time): marked like the others, then asked
                            // about, or said, before anything goes.
                            _state.update { st ->
                                val marked = st.media.items.mapIndexed { i, it -> if (i == index) it.copy(overLimit = p.size ?: (composer.maxBytes + 1)) else it }
                                val media = st.media.copy(items = marked, preparing = false, current = index)
                                st.copy(sending = false, media = if (media.anySendable) media else media.copy(error = tooLarge(item, p.size)),
                                    askLeaveOut = media.overLimitCount.takeIf { media.anySendable })
                            }
                            return@keep
                        }
                        else -> { ready += p; going += item }
                    }
                }
                _state.update { it.copy(media = it.media.copy(preparing = false)) }
                // Two or more photos and videos go as one album (WhatsApp-style); documents always go on their own.
                // Several chats (shared in): each gets the same items, as its own messages and its own album.
                for (target in chatIds) {
                val album = if (ready.count { it is PreparedItem.Shot } >= 2) newAlbumId() else null
                for ((index, p) in ready.withIndex()) {
                    if (!sender.send(target, p, album.takeIf { p is PreparedItem.Shot })) {
                        _state.update { st ->
                            val left = going.drop(index)
                            val why = if (index == 0) "This couldn’t be saved to send. Try again." else "$index sent. The rest couldn’t be saved to send. Try again."
                            st.copy(sending = false, media = st.media.copy(items = left, current = 0, error = why))
                        }
                        return@keep
                    }
                }
                }
                _state.update { it.copy(sending = false, sent = true) }
            }
        }
    }

    /** Said on the item over the limit, before anything is sent: what it is, its size, and the limit. */
    internal fun tooLarge(item: com.circaso.feature.mediacompose.ComposerItem, size: Long?): String {
        val limit = composer.limitLabel
        val its = size?.let { " This one is ${com.circaso.feature.mediacompose.overLimitLabel(it, composer.maxBytes)}." }.orEmpty()
        return when {
            item.video != null && size != null -> composer.tooLargeVideo(size)
            item.video != null -> "Videos up to $limit can be sent."
            item.capture is ComposerCapture.Document -> "Documents up to $limit can be sent.$its"
            else -> "Photos up to $limit can be sent.$its"
        }
    }

    private fun stop(index: Int, why: String) = _state.update { it.copy(sending = false, media = it.media.copy(preparing = false, current = index, error = why)) }

    override fun onCleared() { camera?.close() }
}

/** The words a chat uses in the shared composer. */
private val ChatCopy = MediaComposeCopy(itemNoun = "Item", sendVerb = "send", trimHint = "Drag the edges to cut the video, or cancel to send it whole.", trimHintAlways = true)

/**
 * The chat's media composer: MediaComposeStage, the screen a moment is posted from, with who it goes to beside Send.
 * Photos and videos (Photo and Video modes, the strip, the gallery, the trimmer) and documents (on the same review,
 * with the same caption and Send).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ChatMediaComposerScreen(viewModel: ChatMediaComposerViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val media = state.media
    LaunchedEffect(state.sent, state.closed) { if (state.sent || state.closed) onClose() }
    val composer = viewModel.composer
    androidx.compose.ui.backhandler.BackHandler {
        when {
            media.trim != null -> composer.cancelTrim()
            media.gallery != null -> viewModel.closeGallery()
            media.selecting -> composer.clearSelection()
            state.sending -> Unit
            media.capture != null -> viewModel.discard()
            else -> onClose()
        }
    }
    val n = media.items.size
    val documents = media.items.count { it.capture is ComposerCapture.Document }
    val out = media.overLimitCount
    val going = n - out
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
    MediaComposeStage(
        state = media,
        composer = composer,
        camera = viewModel.camera,
        modes = listOf(ComposerMode.Photo, ComposerMode.Video),
        copy = ChatCopy,
        send = MediaSendButton(
            description = "Send ${if (n > 1) "$going ${if (going == 1) "item" else "items"}" else if (documents == 1) "document" else if (media.item?.video != null) "video" else "photo"} to ${state.title}" +
                (if (out > 0 && going > 0) ", $out over ${composer.limitLabel} left out" else ""),
            enabled = state.canSend, busy = state.sending || media.preparing, onClick = viewModel::send,
        ),
        footnote = when {
            // Said where Send is, so what it will do is never a surprise.
            out > 0 && going == 0 -> if (n == 1) "Over ${composer.limitLabel}, so it can’t be sent." else "All $n are over ${composer.limitLabel}, so none can be sent."
            out > 0 -> "$going of $n will be sent, in this order; $out over ${composer.limitLabel} ${if (out == 1) "is" else "are"} left out. End-to-end encrypted."
            n > 1 -> "$n items, in this order. End-to-end encrypted."
            else -> "End-to-end encrypted."
        },
        onClose = onClose,
        topActions = {
            if (media.capture is ComposerCapture.Document) com.circaso.feature.mediacompose.StageButton(
                com.circaso.designsystem.components.CircasoIcon.Plus, "Add another document", onClick = viewModel::addDocument,
            )
        },
        leading = { RecipientChip(state.title, state.avatarUrl, state.group) },
        onDiscard = viewModel::discard,
        onCloseGallery = viewModel::closeGallery,
        // Opened on Files there is no camera behind it: while Files is up or the document is copied, it says so.
        // Opened on Files or with a share: no camera behind it.
        showCamera = viewModel.start != ChatComposeStart.Document && viewModel.start != ChatComposeStart.Shared,
        customStage = when {
            media.capture != null -> null
            viewModel.start == ChatComposeStart.Document -> ({ OpeningFiles(media.loadingPick) })
            viewModel.start == ChatComposeStart.Shared -> ({ OpeningFiles(true, "Getting what you shared…") })
            else -> null
        },
    )
    // Send with items over the limit among them: ask before leaving them out (the stage's dark, as everything here).
    com.circaso.designsystem.theme.CircasoTheme(darkTheme = true, highContrast = com.circaso.designsystem.theme.CircasoTheme.colors.isHighContrast) {
        val asked = state.askLeaveOut
        var lastAsked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
        if (asked != null) lastAsked = asked
        val rest = n - lastAsked
        CircasoSheet(visible = asked != null, onDismiss = viewModel::leaveOutCancelled, label = "Items over the limit") {
            LeaveOutSheet(lastAsked, rest, composer.limitLabel, onSend = viewModel::leaveOutConfirmed, onBack = viewModel::leaveOutCancelled)
        }
    }
    }
}

/**
 * Send tapped with items over the size limit among them: they can't go in a chat, so before anything is sent it asks
 * whether to send the rest, or go back to remove or trim them (they are marked on the strip).
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.LeaveOutSheet(out: Int, rest: Int, limit: String, onSend: () -> Unit, onBack: () -> Unit) {
    val c = com.circaso.designsystem.theme.CircasoTheme.colors
    val t = com.circaso.designsystem.theme.CircasoTheme.typography
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.fillMaxWidth(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(com.circaso.designsystem.theme.CircasoSpacing.s3),
    ) {
        androidx.compose.foundation.text.BasicText(
            if (out == 1) "1 item is over $limit" else "$out items are over $limit",
            androidx.compose.ui.Modifier.semantics { heading() },
            style = t.title3.copy(color = c.ink),
        )
        androidx.compose.foundation.text.BasicText(
            "Chats take files up to $limit, so ${if (out == 1) "it’s" else "they’re"} marked and won’t be sent. Send the other $rest now, or go back to trim or remove ${if (out == 1) "it" else "them"}.",
            style = t.body.copy(color = c.inkSecondary),
        )
        com.circaso.designsystem.components.CircasoButton(
            if (rest == 1) "Send the Other 1" else "Send the Other $rest", onSend,
            size = com.circaso.designsystem.components.ButtonSize.Large, block = true,
        )
        com.circaso.designsystem.components.CircasoButton(
            "Go Back", onBack, style = com.circaso.designsystem.components.ButtonStyle.Plain,
            size = com.circaso.designsystem.components.ButtonSize.Large, block = true,
        )
    }
}

/** Opened on Files: the document review's card, waiting for the picker (and the copy into the app) to finish. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.OpeningFiles(busy: Boolean, busyText: String = "Opening your document…") {
    val c = com.circaso.designsystem.theme.CircasoTheme.colors
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.Center)
            .padding(horizontal = com.circaso.designsystem.theme.CircasoSpacing.s6)
            .clip(com.circaso.designsystem.theme.CircasoShapes.lg).background(c.glass)
            .padding(horizontal = com.circaso.designsystem.theme.CircasoSpacing.s5, vertical = com.circaso.designsystem.theme.CircasoSpacing.s6)
            .semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(com.circaso.designsystem.theme.CircasoSpacing.s3),
    ) {
        com.circaso.designsystem.components.CircasoIconImage(com.circaso.designsystem.components.CircasoIcon.Document, c.ink, androidx.compose.ui.Modifier.size(36.dp))
        androidx.compose.foundation.text.BasicText(if (busy) busyText else "Choose a document in Files", style = com.circaso.designsystem.theme.CircasoTheme.typography.subhead.copy(color = c.ink))
        if (busy) com.circaso.designsystem.components.Spinner(c.ink)
    }
}

/** A random album id: 16 random bytes as hex, never derived from anything about the items. */
internal fun newAlbumId(): String = kotlin.random.Random.Default.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
