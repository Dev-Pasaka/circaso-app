package com.circaso.feature.messaging.presentation

import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
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
import com.circaso.designsystem.components.SectionHeader
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.mediacompose.ShareCheck
import com.circaso.feature.mediacompose.ShareDestination
import com.circaso.feature.mediacompose.SharedFile
import com.circaso.feature.mediacompose.SharedKind
import com.circaso.feature.mediacompose.Tick
import com.circaso.feature.mediacompose.checkShare
import com.circaso.feature.mediacompose.sharedKind
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One place a share can go: a chat, or a contact with no chat yet (opened when the share goes). */
data class ShareTarget(val id: String, val title: String, val avatarUrl: String?, val group: Boolean, val userId: String? = null) {
    val isPerson: Boolean get() = userId != null
}

data class ShareTargetUiState(
    val files: List<SharedFile> = emptyList(),
    val chats: List<ShareTarget> = emptyList(),
    val people: List<ShareTarget> = emptyList(),
    val loaded: Boolean = false,
    val query: String = "",
    val moment: Boolean = false,
    /** Ticked targets in the order ticked (chat ids, or `person:<userId>`). */
    val selected: List<String> = emptyList(),
    val opening: Boolean = false,
    val error: String? = null,
    /** Where to go next, once chosen and every chat is open. */
    val go: ShareDestination? = null,
) {
    val check: ShareCheck get() = checkShare(files)
    val canBeMoment: Boolean get() = check.canBeMoment
    val shownChats: List<ShareTarget> get() = chats.filterBy(query)
    val shownPeople: List<ShareTarget> get() = people.filterBy(query)
    val canGo: Boolean get() = !opening && check.usable.isNotEmpty() && (moment || selected.isNotEmpty())
    val summary: String get() = shareSummary(check.usable)
    private fun List<ShareTarget>.filterBy(q: String) = if (q.isBlank()) this else filter { it.title.contains(q.trim(), ignoreCase = true) }
}

/** "1 photo", "3 videos", "2 photos and a document": what is being sent, for the picker's title line. */
fun shareSummary(files: List<SharedFile>): String {
    val kinds = files.groupingBy { sharedKind(it.mime, it.name) }.eachCount()
    val parts = listOfNotNull(
        kinds[SharedKind.Photo]?.let { if (it == 1) "1 photo" else "$it photos" },
        kinds[SharedKind.Video]?.let { if (it == 1) "1 video" else "$it videos" },
        kinds[SharedKind.Document]?.let { if (it == 1) "1 document" else "$it documents" },
    )
    return when (parts.size) { 0 -> "Nothing to send"; 1 -> parts[0]; else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last() }
}

/**
 * Where a share from another app goes (2026-11-07): My moment at the top, then chats (direct and groups, most recent
 * first) and contacts with no chat yet, with search, up to [MaxChats] ticked at once. My moment goes alone (it is
 * posted, not sent), and only when everything shared is a photo or a video.
 */
class ShareTargetViewModel(
    chats: ChatsRepository,
    observeContacts: ObserveContactsUseCase,
    private val openDirect: suspend (userId: String) -> AppResult<String>,
    files: () -> List<SharedFile>? = { com.circaso.feature.mediacompose.IncomingShares.pending.value },
) : ViewModel() {
    private val _state = MutableStateFlow(ShareTargetUiState(files = files().orEmpty()))
    val state: StateFlow<ShareTargetUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(chats.chats, observeContacts()) { list, contacts ->
                val sorted = list.sortedByDescending { it.latest?.orderAt ?: 0L }
                val withChat = sorted.mapNotNull { it.chat.peerId }.toSet()
                sorted.map { ShareTarget(it.chat.chatId, it.chat.title, it.chat.avatarUrl, it.chat.kind == ChatKind.Group) } to
                    contacts.filter { it.person.userId !in withChat }.sortedBy { it.person.displayName.lowercase() }
                        .map { ShareTarget("person:" + it.person.userId, it.person.displayName, it.person.avatarUrl, false, it.person.userId) }
            }.collect { (c, p) -> _state.update { it.copy(chats = c, people = p, loaded = true) } }
        }
    }

    fun onQuery(text: String) = _state.update { it.copy(query = text.take(80)) }

    /** My moment goes alone: choosing it lets the chats go, and the other way round. */
    fun toggleMoment() = _state.update { s ->
        when {
            !s.canBeMoment -> s.copy(error = "Moments are photos and videos. Send documents to a chat.")
            s.moment -> s.copy(moment = false)
            else -> s.copy(moment = true, selected = emptyList())
        }
    }

    fun toggle(id: String) = _state.update { s ->
        when {
            id in s.selected -> s.copy(selected = s.selected - id)
            s.selected.size >= MaxChats -> s.copy(error = "You can send to up to $MaxChats chats at a time.")
            else -> s.copy(selected = s.selected + id, moment = false)
        }
    }

    fun errorShown() = _state.update { it.copy(error = null) }

    /** Next: My moment, or the chosen chats (a contact's chat is opened first, which needs a connection). */
    fun next() {
        val s = _state.value
        if (!s.canGo) return
        if (s.moment) { _state.update { it.copy(go = ShareDestination.Moment) }; return }
        _state.update { it.copy(opening = true) }
        viewModelScope.launch {
            val ids = mutableListOf<String>()
            for (id in s.selected) {
                val person = (s.people + s.chats).firstOrNull { it.id == id }
                val uid = person?.userId
                if (uid == null) { ids += id; continue }
                when (val r = openDirect(uid)) {
                    is AppResult.Success -> ids += r.value
                    is AppResult.Failure -> { _state.update { it.copy(opening = false, error = "Couldn’t open a chat with ${person.title}. Check your connection and try again.") }; return@launch }
                }
            }
            _state.update { it.copy(opening = false, go = ShareDestination.Chats(ids)) }
        }
    }

    /** The destination was opened: going back here starts a fresh choice. */
    fun went() = _state.update { it.copy(go = null) }

    companion object { const val MaxChats = 5 }
}

/**
 * The share destination picker, full screen in the person's own theme: what is being sent, My moment, then chats and
 * contacts with a tick each, and Next naming where it goes. The same list language as Forward (V4).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ShareTargetScreen(viewModel: ShareTargetViewModel, onClose: () -> Unit, onGo: (ShareDestination) -> Unit) {
    val state by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    LaunchedEffect(state.go) { state.go?.let { viewModel.went(); onGo(it) } }
    LaunchedEffect(state.error) { state.error?.let { toast.show(it, com.circaso.designsystem.chrome.ToastKind.Problem); viewModel.errorShown() } }
    androidx.compose.ui.backhandler.BackHandler(onBack = onClose)
    CircasoScaffold(
        Modifier.fillMaxSize().background(c.bg),
        topBar = { CircasoNavBar("Send to", onBack = onClose) },
        toast = { ToastHost(toast) },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
            ) {
                val names = if (state.moment) listOf("My moment") else state.selected.mapNotNull { id -> (state.chats + state.people).firstOrNull { it.id == id }?.title }
                BasicText(
                    if (names.isEmpty()) "Choose where it goes" else names.joinToString(", "), Modifier.weight(1f),
                    style = t.subhead.copy(color = if (names.isEmpty()) c.inkSecondary else c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                CircasoButton("Next", viewModel::next, enabled = state.canGo, busy = state.opening)
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = CircasoSpacing.s6)) {
            item(key = "what") {
                Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    BasicText(state.summary, style = t.subhead.copy(color = c.inkSecondary))
                    state.check.notice?.let { note ->
                        Row(
                            Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.fill).padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2),
                            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircasoIconImage(CircasoIcon.Info, c.inkSecondary, Modifier.size(18.dp))
                            BasicText(note, style = t.footnote.copy(color = c.ink))
                        }
                    }
                }
            }
            item(key = "search") { SearchPill(state.query, viewModel::onQuery, "Search", Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2)) }
            if (state.query.isBlank()) item(key = "moment") {
                ListRow(
                    "My moment", sub = if (state.canBeMoment) "Your contacts see it for 24 hours" else "Moments are photos and videos",
                    modifier = Modifier.semantics(mergeDescendants = true) { role = Role.Checkbox; selected = state.moment },
                    leading = {
                        Box(Modifier.size(CircasoSizes.avatarMd).clip(CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
                            CircasoIconImage(CircasoIcon.Spaces, if (state.canBeMoment) c.ink else c.inkTertiary, Modifier.size(22.dp))
                        }
                    },
                    trailing = { if (state.canBeMoment) Tick(state.moment) },
                    onClick = viewModel::toggleMoment,
                )
            }
            if (state.shownChats.isNotEmpty()) item(key = "chats-head") { SectionHeader("Chats") }
            items(state.shownChats, key = { it.id }) { target -> TargetRow(target, target.id in state.selected) { viewModel.toggle(target.id) } }
            if (state.shownPeople.isNotEmpty()) item(key = "people-head") { SectionHeader("Contacts") }
            items(state.shownPeople, key = { it.id }) { target -> TargetRow(target, target.id in state.selected) { viewModel.toggle(target.id) } }
            if (state.loaded && state.shownChats.isEmpty() && state.shownPeople.isEmpty()) item(key = "none") {
                BasicText(
                    if (state.query.isBlank()) "No chats yet. Start one, then share to it." else "No chats or contacts match “${state.query.trim()}”",
                    Modifier.fillMaxWidth().padding(CircasoSpacing.s6), style = t.subhead.copy(color = c.inkSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                )
            }
        }
    }
}

@Composable
private fun TargetRow(target: ShareTarget, on: Boolean, onClick: () -> Unit) {
    ListRow(
        target.title, sub = if (target.group) "Group" else null,
        modifier = Modifier.semantics(mergeDescendants = true) { role = Role.Checkbox; selected = on },
        leading = { Avatar(target.title, size = CircasoSizes.avatarMd, imageUrl = target.avatarUrl) },
        trailing = { Tick(on) },
        onClick = onClick,
    )
}
