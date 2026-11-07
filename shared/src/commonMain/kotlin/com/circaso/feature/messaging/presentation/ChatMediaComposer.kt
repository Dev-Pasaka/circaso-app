package com.circaso.feature.messaging.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
) {
    val canSend: Boolean get() = !sending && media.ready
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
    private val _state = MutableStateFlow(ChatMediaUiState())
    val state: StateFlow<ChatMediaUiState> = _state.asStateFlow()

    val composer = MediaComposer(
        viewModelScope, library, camera, now,
        read = { _state.value.media },
        write = { f -> _state.update { it.copy(media = f(it.media)) } },
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
     * Each item, in order, read and made ready (a video too large is re-encoded with the same step a moment uses), then
     * queued as its own message. Only once every one is ready is anything queued: never half a batch sent and the rest
     * left behind. If queueing stops part-way, what went is taken off the review, so trying again never sends twice.
     */
    fun send() {
        val s = _state.value
        if (!s.canSend) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            val kind = when {
                s.media.items.any { it.video != null } -> com.circaso.core.platform.UploadKind.Video
                s.media.items.all { it.capture is ComposerCapture.Document } -> com.circaso.core.platform.UploadKind.File
                else -> com.circaso.core.platform.UploadKind.Photo
            }
            uploads.keep(kind, s.title) {
                val ready = mutableListOf<PreparedItem>()
                for ((index, item) in s.media.items.withIndex()) {
                    when (val p = composer.prepare(item)) {
                        PreparedItem.Missing -> { stop(index, if (item.capture is ComposerCapture.Document) "This document isn’t on the phone any more. Pick it again." else "This video isn’t on the phone any more. Pick it again."); return@keep }
                        PreparedItem.TooLarge -> { stop(index, when { item.capture is ComposerCapture.Document -> "Documents up to 32 MB can be sent."; item.video != null -> "This video is too large to send. Trim it shorter."; else -> "This photo is too large to send." }); return@keep }
                        else -> ready += p
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
                            val left = st.media.items.drop(index)
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

    private fun stop(index: Int, why: String) = _state.update { it.copy(sending = false, media = it.media.copy(preparing = false, current = index, error = why)) }

    override fun onCleared() { camera?.close() }
}

/** The words a chat uses in the shared composer. */
private val ChatCopy = MediaComposeCopy(itemNoun = "Item", sendVerb = "send", trimHint = "Videos are up to a minute. Drag the edges to choose which minute.")

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
    MediaComposeStage(
        state = media,
        composer = composer,
        camera = viewModel.camera,
        modes = listOf(ComposerMode.Photo, ComposerMode.Video),
        copy = ChatCopy,
        send = MediaSendButton(
            description = "Send ${if (n > 1) "$n items" else if (documents == 1) "document" else if (media.item?.video != null) "video" else "photo"} to ${state.title}",
            enabled = state.canSend, busy = state.sending || media.preparing, onClick = viewModel::send,
        ),
        footnote = when {
            media.preparing -> "Making the video smaller to send…"
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
