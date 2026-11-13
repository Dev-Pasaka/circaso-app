package com.circaso.feature.status.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.SearchPill
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.status.domain.MomentKind
import com.circaso.feature.mediacompose.Tick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sends a moment's media (or text) into one chat as an ordinary message, through the chat's own media path
 * (`MediaMessages`: encrypted, uploaded and sent by the outbox, waiting offline like any message). False when this
 * phone couldn't even queue it (the file is gone).
 */
fun interface MomentForwarder {
    suspend fun send(chatId: String, media: ForwardMedia): Boolean
}

/** One chat in the forward list. */
data class ForwardChat(val chatId: String, val title: String, val avatarUrl: String?, val group: Boolean)

data class MomentForwardUiState(
    val chats: List<ForwardChat> = emptyList(),
    val loaded: Boolean = false,
    val query: String = "",
    /** In the order ticked; the bottom bar names them. */
    val selected: List<String> = emptyList(),
    val sending: Boolean = false,
    val sent: Boolean = false,
    val error: String? = null,
) {
    val shown: List<ForwardChat>
        get() = if (query.isBlank()) chats else chats.filter { it.title.contains(query.trim(), ignoreCase = true) }
    val canSend: Boolean get() = selected.isNotEmpty() && !sending
}

/** V4 Forward: pick up to [MaxChats] chats and send the moment to each. Queued like any message, so it works offline. */
class MomentForwardViewModel(chats: ChatsRepository, private val forwarder: MomentForwarder) : ViewModel() {
    private val _state = MutableStateFlow(MomentForwardUiState())
    val state: StateFlow<MomentForwardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            chats.chats.collect { list ->
                _state.update { s ->
                    s.copy(loaded = true, chats = list.map { ForwardChat(it.chat.chatId, it.chat.title, it.chat.avatarUrl, it.chat.kind == ChatKind.Group) })
                }
            }
        }
    }

    fun onQuery(text: String) = _state.update { it.copy(query = text.take(80)) }

    fun toggle(chatId: String) = _state.update { s ->
        when {
            chatId in s.selected -> s.copy(selected = s.selected - chatId)
            s.selected.size >= MaxChats -> s.copy(error = "You can forward to up to $MaxChats chats at a time.")
            else -> s.copy(selected = s.selected + chatId)
        }
    }

    fun errorShown() = _state.update { it.copy(error = null) }

    /** Back to a fresh picker: the ViewModel outlives one open (it belongs to the screen underneath). */
    fun reset() = _state.update { it.copy(query = "", selected = emptyList(), sending = false, sent = false, error = null) }

    fun send(media: ForwardMedia) {
        val s = _state.value
        if (!s.canSend) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            // Only the chats it failed for stay ticked, so trying again never sends twice to the others.
            val failed = s.selected.filterNot { id -> forwarder.send(id, media) }
            _state.update {
                if (failed.isEmpty()) it.copy(sending = false, sent = true)
                else it.copy(
                    sending = false, selected = failed,
                    error = if (failed.size == s.selected.size) "This couldn’t be forwarded. Try again." else "Forwarded to some chats. Try again for the rest.",
                )
            }
        }
    }

    companion object { const val MaxChats = 5 }
}

/**
 * The forward picker, full screen (a list to search and tick fits a screen better than a sheet): Close, search, the
 * chats with a tick each, and Send naming who it goes to. In the person's own theme, not the dark stage.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MomentForwardScreen(viewModel: MomentForwardViewModel, media: ForwardMedia, onClose: () -> Unit, onSent: (chats: List<String>) -> Unit) {
    val state by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    LaunchedEffect(state.sent) {
        if (state.sent) {
            val names = state.selected.mapNotNull { id -> state.chats.firstOrNull { it.chatId == id }?.title }
            viewModel.reset()
            onSent(names)
        }
    }
    val close = { viewModel.reset(); onClose() }
    LaunchedEffect(state.error) { state.error?.let { toast.show(it, problem = true); viewModel.errorShown() } }
    androidx.compose.ui.backhandler.BackHandler(onBack = close)
    CircasoScaffold(
        Modifier.fillMaxSize().background(c.bg),
        topBar = { CircasoNavBar(forwardTitle(media), onBack = close) },
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = CircasoSpacing.s4)) },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
            ) {
                val names = state.selected.mapNotNull { id -> state.chats.firstOrNull { it.chatId == id }?.title }
                BasicText(
                    if (names.isEmpty()) "Choose chats" else names.joinToString(", "), Modifier.weight(1f),
                    style = t.subhead.copy(color = if (names.isEmpty()) c.inkSecondary else c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                CircasoButton("Send", { viewModel.send(media) }, enabled = state.selected.isNotEmpty(), busy = state.sending, icon = CircasoIcon.Send)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchPill(state.query, viewModel::onQuery, "Search", Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2))
            when {
                !state.loaded -> Unit
                state.chats.isEmpty() -> Empty("No chats yet", "Start a chat first, then forward your moment to it.")
                state.shown.isEmpty() -> Empty("No chats match “${state.query.trim()}”", null)
                else -> LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = CircasoSpacing.s6)) {
                    items(state.shown, key = { it.chatId }) { chat ->
                        val on = chat.chatId in state.selected
                        ListRow(
                            chat.title, sub = if (chat.group) "Group" else null,
                            modifier = Modifier.semantics(mergeDescendants = true) { role = Role.Checkbox; selected = on },
                            leading = { Avatar(chat.title, size = CircasoSizes.avatarMd, imageUrl = chat.avatarUrl) },
                            trailing = { Tick(on) },
                            onClick = { viewModel.toggle(chat.chatId) },
                        )
                    }
                }
            }
        }
    }
}

private fun forwardTitle(media: ForwardMedia) = when (media.kind) {
    MomentKind.Image -> "Forward photo"
    MomentKind.Video -> "Forward video"
    MomentKind.Voice -> "Forward voice"
    MomentKind.Text -> "Forward moment"
}

@Composable
private fun Empty(title: String, sub: String?) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s6, vertical = CircasoSpacing.s8), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        CircasoIconImage(CircasoIcon.Chat, c.inkTertiary, Modifier.size(36.dp))
        BasicText(title, style = t.headline.copy(color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        if (sub != null) BasicText(sub, style = t.subhead.copy(color = c.inkSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
    }
}

/**
 * The honest line after forwarding: the messages are queued in each chat (the chat outbox delivers them), so it says
 * "on its way", never "sent".
 */
fun forwardedNotice(chats: List<String>): String = when (chats.size) {
    0 -> "On its way"
    1 -> "On its way to ${chats.first()}"
    else -> "On its way to ${chats.size} chats"
}

