package com.circaso.feature.status.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.SearchPill
import com.circaso.designsystem.components.Section
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.status.domain.MomentAudienceMode
import com.circaso.feature.status.domain.MomentPrivacy
import com.circaso.feature.status.domain.MomentsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Designs P1–P3. The setting lives on this phone, so it works offline; it applies to moments shared from now on. */
data class MomentsPrivacyUiState(
    val privacy: MomentPrivacy = MomentPrivacy(),
    val contacts: List<Contact> = emptyList(),
    val muted: Set<String> = emptySet(),
    /** P2: picking people for "except" or "only"; the ticks are a draft until Done. */
    val picking: MomentAudienceMode? = null,
    val draft: Set<String> = emptySet(),
    val query: String = "",
    val mutedOpen: Boolean = false,
) {
    val shown: List<Contact> get() = if (query.isBlank()) contacts else contacts.filter { it.person.displayName.contains(query.trim(), true) }
}

class MomentsPrivacyViewModel(private val repository: MomentsRepository, observeContacts: ObserveContactsUseCase) : ViewModel() {
    private val _state = MutableStateFlow(MomentsPrivacyUiState())
    val state: StateFlow<MomentsPrivacyUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.privacy, observeContacts(), repository.muted) { p, c, m -> Triple(p, c, m) }.collect { (p, c, m) ->
                _state.update { it.copy(privacy = p, contacts = c.sortedBy { x -> x.person.displayName.lowercase() }, muted = m) }
            }
        }
    }

    fun choose(mode: MomentAudienceMode) {
        val p = _state.value.privacy
        if (mode == MomentAudienceMode.Contacts) save(p.copy(mode = mode))
        else _state.update { it.copy(picking = mode, draft = if (mode == MomentAudienceMode.ContactsExcept) p.except else p.only, query = "") }
    }

    fun toggle(userId: String) = _state.update { it.copy(draft = if (userId in it.draft) it.draft - userId else it.draft + userId) }
    fun onQuery(q: String) = _state.update { it.copy(query = q) }
    fun cancelPicking() = _state.update { it.copy(picking = null) }

    fun donePicking() {
        val s = _state.value
        val mode = s.picking ?: return
        save(if (mode == MomentAudienceMode.ContactsExcept) s.privacy.copy(mode = mode, except = s.draft) else s.privacy.copy(mode = mode, only = s.draft))
        _state.update { it.copy(picking = null) }
    }

    fun openMuted(open: Boolean) = _state.update { it.copy(mutedOpen = open) }
    fun unmute(userId: String) { viewModelScope.launch { repository.setMuted(userId, false) } }

    private fun save(p: MomentPrivacy) { viewModelScope.launch { repository.setPrivacy(p) } }
}

@Composable
fun MomentsPrivacyScreen(viewModel: MomentsPrivacyViewModel, onBack: () -> Unit, onFindPeople: () -> Unit) {
    val state by viewModel.state.collectAsState()
    if (state.picking != null) Picker(state, viewModel, onFindPeople) else Settings(state, viewModel, onBack)
}

@Composable
private fun Settings(state: MomentsPrivacyUiState, vm: MomentsPrivacyViewModel, onBack: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val p = state.privacy
    val ids = state.contacts.map { it.person.userId }.toSet()
    CircasoScaffold(grouped = true, topBar = { CircasoNavBar(title = if (state.mutedOpen) "Muted" else "Moments", onBack = if (state.mutedOpen) ({ vm.openMuted(false) }) else onBack) }) { padding ->
        if (state.mutedOpen) {
            val muted = state.contacts.filter { it.person.userId in state.muted }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                if (muted.isEmpty()) item { BasicText("Nobody is muted.", Modifier.padding(CircasoSpacing.s4), style = t.subhead.copy(color = c.inkSecondary)) }
                else item {
                    ListGroup(Modifier.padding(CircasoSpacing.s4)) {
                        muted.forEach { m -> ListRow(m.person.displayName, sub = "Tap to unmute", leading = { Avatar(m.person.displayName, size = CircasoSizes.avatarMd, imageUrl = m.person.avatarUrl) }, onClick = { vm.unmute(m.person.userId) }) }
                    }
                }
            }
            return@CircasoScaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + CircasoSpacing.s3, bottom = padding.calculateBottomPadding() + CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            item {
                Section("Who can see my moments", Modifier.padding(horizontal = CircasoSpacing.s4)) {
                    Choice("My contacts", "People who have your number saved, and whose number you have saved", p.mode == MomentAudienceMode.Contacts) { vm.choose(MomentAudienceMode.Contacts) }
                    Choice("My contacts except…", p.except.count { it in ids }.let { if (it == 0) "None excluded" else "$it excluded" }, p.mode == MomentAudienceMode.ContactsExcept) { vm.choose(MomentAudienceMode.ContactsExcept) }
                    Choice("Only share with…", p.only.count { it in ids }.let { if (it == 0) "Nobody chosen" else "$it chosen" }, p.mode == MomentAudienceMode.OnlyShareWith) { vm.choose(MomentAudienceMode.OnlyShareWith) }
                }
            }
            item { Note("Changes apply to moments you share from now on. Moments are end-to-end encrypted; only the people you share with can see them, for 24 hours.") }
            item { Note("People can see that you viewed their moment, as you can see who viewed yours.") }
            item {
                Section("Muted", Modifier.padding(horizontal = CircasoSpacing.s4).padding(top = CircasoSpacing.s3)) {
                    ListRow("Muted moments", sub = "${state.muted.size} ${if (state.muted.size == 1) "person" else "people"}", trailing = { RowChevron() }, onClick = { vm.openMuted(true) })
                }
            }
            item { Note("Muted people’s moments move to the end of the row. They aren’t told.") }
        }
    }
}

@Composable
private fun Choice(title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    ListRow(
        title, Modifier.semantics { role = Role.RadioButton; selected = on }, sub = sub,
        leading = { Radio(on) }, onClick = onClick,
    )
}

@Composable
private fun Radio(on: Boolean) {
    val c = CircasoTheme.colors
    Box(Modifier.size(22.dp).clip(CircasoShapes.circle).border(if (on) 7.dp else 2.dp, if (on) c.ink else c.fillStrong, CircasoShapes.circle))
}

@Composable
private fun Note(text: String) {
    BasicText(text, Modifier.padding(horizontal = CircasoSpacing.s5), style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary))
}

/** P2 (and P3 when there is nobody to pick). */
@Composable
private fun Picker(state: MomentsPrivacyUiState, vm: MomentsPrivacyViewModel, onFindPeople: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val except = state.picking == MomentAudienceMode.ContactsExcept
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    androidx.compose.ui.backhandler.BackHandler(onBack = vm::cancelPicking)
    CircasoScaffold(
        topBar = {
            CircasoNavBar(
                title = if (except) "Hide my moments from" else "Only share with", onBack = vm::cancelPicking,
                trailing = { CircasoButton(if (state.draft.isEmpty()) "Done" else "Done (${state.draft.size})", vm::donePicking, style = com.circaso.designsystem.components.ButtonStyle.Plain, size = com.circaso.designsystem.components.ButtonSize.Small, enabled = state.contacts.isNotEmpty() && (except || state.draft.isNotEmpty())) },
            )
        },
    ) { padding ->
        if (state.contacts.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                CircasoIconImage(CircasoIcon.Person, c.inkTertiary, Modifier.size(44.dp))
                BasicText("None of your contacts are on Circaso yet", style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center))
                BasicText("Moments go only to people who have your number saved, and whose number you have saved.", style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                CircasoButton("Find People", onFindPeople)
            }
            return@CircasoScaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item { SearchPill(state.query, vm::onQuery, "Search contacts", Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2)) }
            items(state.shown, key = { it.person.userId }) { contact ->
                val on = contact.person.userId in state.draft
                ListRow(
                    contact.person.displayName,
                    Modifier.semantics { role = Role.Checkbox; stateDescription = if (on) "Chosen" else "Not chosen" },
                    leading = { Avatar(contact.person.displayName, size = CircasoSizes.avatarMd, imageUrl = contact.person.avatarUrl) },
                    trailing = { Check(on) },
                    onClick = { vm.toggle(contact.person.userId) },
                )
            }
        }
    }
}

@Composable
private fun Check(on: Boolean) {
    val c = CircasoTheme.colors
    Box(
        Modifier.size(24.dp).clip(CircasoShapes.circle).then(if (on) Modifier.background(c.ink) else Modifier.border(2.dp, c.fillStrong, CircasoShapes.circle)),
        contentAlignment = Alignment.Center,
    ) { if (on) CircasoIconImage(CircasoIcon.Check, c.bg, Modifier.size(14.dp)) }
}

