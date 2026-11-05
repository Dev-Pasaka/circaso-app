package com.circaso.feature.spaces.presentation.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.LocalAshaLauncher
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.SuggestionChip
import com.circaso.designsystem.components.textWithoutAshaCommand
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.SpaceSummary
import com.circaso.feature.spaces.domain.SpaceTopic
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.presentation.SpacesNav
import com.circaso.feature.spaces.presentation.countText
import org.koin.compose.viewmodel.koinViewModel

/** SD2: search spaces by name, topic and @handle, and people whose moments I can see. */
@Composable
fun SpaceSearchEntry(nav: SpacesNav) {
    val vm = koinViewModel<SpaceSearchViewModel>()
    val state by vm.state.collectAsState()
    val toast = rememberToastState()
    LaunchedEffect(state.toast) { state.toast?.let { vm.onToastShown(); toast.show(it) } }
    SpaceSearchContent(
        state, SpaceSearchActions(
            onQuery = vm::onQuery, onTopic = vm::onTopic, onCancel = nav.back, onRetry = vm::onRetry,
            onOpenSpace = { s -> if (s.spaceId in state.joined) nav.openSpaceChat(s.spaceId) else nav.openSpace(s.spaceId) },
            onFollow = vm::onFollow, onJoin = { s -> nav.joinSpace(s.spaceId) },
            onOpenPerson = nav.openPerson, onCreate = { nav.create(it) },
        ),
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter)) },
    )
}

class SpaceSearchActions(
    val onQuery: (String) -> Unit,
    val onTopic: (SpaceTopic?) -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenSpace: (SpaceSummary) -> Unit,
    val onFollow: (SpaceSummary) -> Unit,
    val onJoin: (SpaceSummary) -> Unit,
    val onOpenPerson: (userId: String) -> Unit,
    val onCreate: (name: String) -> Unit,
)

/** Design SD2, SD2b, SD2c, SD2d. */
@Composable
fun SpaceSearchContent(state: SpaceSearchUiState, actions: SpaceSearchActions, autoFocus: Boolean = true, toast: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(toast = toast) { padding ->
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            SpaceSearchField(state.query, actions.onQuery, "Spaces, @handles, people", onCancel = actions.onCancel, autoFocus = autoFocus)
            if (!state.online) Banner("You’re offline. Searching your spaces only.", CircasoIcon.Offline, Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, bottom = CircasoSpacing.s2))
            else if (!state.byHandle) TopicChips(state.topic, actions.onTopic)
            val q = state.trimmed.trimStart('@')
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + CircasoSpacing.s6)) {
                when {
                    // SD2d: offline, my spaces on the phone that match, and the honest line under them.
                    !state.online -> {
                        if (state.mine.isNotEmpty()) {
                            item { ResultsHead("Your spaces") }
                            items(state.mine, key = { "mine-" + it.space.spaceId }) { m ->
                                val sub = "@${m.space.handle} · ${m.space.visibility.label} · ${countText(m.space.memberCount)}"
                                SpaceListRow(m.space, AnnotatedString(sub), "${m.space.name}, $sub", { actions.onOpenSpace(m.space) }, end = { RowChevron() })
                            }
                        }
                        if (q.isNotEmpty()) item {
                            BasicText("More spaces show when you’re back online.", Modifier.padding(horizontal = CircasoSpacing.s5, vertical = 14.dp), style = t.subhead.copy(color = c.inkSecondary))
                        }
                    }
                    state.failed -> item { ErrorBox("Search didn’t work", "Something went wrong on our side.", state.requestId, actions.onRetry) }
                    // SD2c: nothing matches. Say so, say why an invite-only space can't be found, and offer to start one.
                    state.empty -> item { NoResults(state.trimmed, actions.onCreate) }
                    else -> {
                        if (state.byHandle) {
                            state.exact?.let { e ->
                                item { ResultsHead("Exact match") }
                                item(key = "exact") { SearchSpaceRow(e, state, actions, handleSub = true, query = q) }
                            }
                            if (state.spaces.isNotEmpty()) {
                                item { ResultsHead("Similar handles", top = if (state.exact != null) 14.dp else 6.dp) }
                                items(state.spaces, key = { "sim-" + it.spaceId }) { s -> SearchSpaceRow(s, state, actions, handleSub = true, query = q) }
                            }
                        } else {
                            if (state.spaces.isNotEmpty() || state.exact != null) {
                                item { ResultsHead("Spaces") }
                                items(listOfNotNull(state.exact) + state.spaces, key = { "sp-" + it.spaceId }) { s -> SearchSpaceRow(s, state, actions, handleSub = false, query = q) }
                            }
                            if (state.people.isNotEmpty()) {
                                item { ResultsHead("People", top = 14.dp) }
                                items(state.people, key = { "p-" + it.userId }) { p -> PersonRow(p, q) { actions.onOpenPerson(p.userId) } }
                            }
                        }
                        if (state.answered != null) item {
                            BasicText("Invite-only spaces never show in search.", Modifier.padding(horizontal = CircasoSpacing.s5, vertical = 18.dp), style = t.footnote.copy(color = c.inkSecondary))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsHead(text: String, top: androidx.compose.ui.unit.Dp = 6.dp) {
    BasicText(text, Modifier.padding(start = CircasoSpacing.s5, end = CircasoSpacing.s5, top = top).semantics { heading() }, style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary))
}

@Composable
private fun SearchSpaceRow(s: SpaceSummary, state: SpaceSearchUiState, actions: SpaceSearchActions, handleSub: Boolean, query: String) {
    val sub = if (handleSub) "@${s.handle} · ${s.visibility.label} · ${s.topic.label} · ${countText(s.memberCount)}"
    else "${s.topic.label} · " + kindSub(s, state.fiat).let { k -> if (s.visibility == SpaceVisibility.MembersOnly) k else "${s.visibility.label} · ${countText(s.memberCount)}" }
    SpaceListRow(
        s, highlight(sub, query), "${s.name}, $sub", { actions.onOpenSpace(s) },
        end = {
            if (s.spaceId in state.joined) RowChevron()
            else FollowButton(s, state.follow[s.spaceId] ?: FollowState.Idle, state.online, { actions.onFollow(s) }, { actions.onJoin(s) })
        },
    )
}

/** SD2's People: a contact whose moment matched shows the line, the matching words bold. */
@Composable
private fun PersonRow(p: SearchPerson, query: String, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val sub = p.momentText?.takeIf { it.contains(query, true) }?.let { "Shared a moment: “${it.take(80)}”" } ?: p.handle?.let { "@$it" } ?: "Shared a moment"
    Row(
        Modifier.fillMaxWidth().pressable(onClick = onClick).semantics(mergeDescendants = true) { contentDescription = "${p.name}, $sub" }
            .heightIn(min = 64.dp).padding(horizontal = CircasoSpacing.s4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Avatar(p.name, size = CircasoSizes.avatarMd, imageUrl = p.avatarUrl)
        Column(Modifier.weight(1f)) {
            BasicText(highlight(p.name, query), style = t.body.copy(color = c.ink))
            BasicText(highlight(sub, query), style = t.subhead.copy(color = c.inkSecondary), maxLines = 2)
        }
        RowChevron()
    }
}

@Composable
private fun NoResults(query: String, onCreate: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val name = query.trimStart('@').split(' ').filter { it.isNotBlank() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(top = 90.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CircasoIconImage(CircasoIcon.Search, c.inkTertiary, Modifier.size(40.dp), strokeWidth = 1.6f)
        BasicText("No spaces match “$query”", Modifier.semantics { heading() }, style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText("Check the spelling or try a topic. Invite-only spaces never show in search; ask someone in it for an invite.", style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        if (name.isNotEmpty()) CircasoButton("Create “$name”", { onCreate(name) }, style = ButtonStyle.Secondary, size = ButtonSize.Small)
    }
}

/** The topic chips (one list everywhere): All, then the ten topics, scrolling sideways. */
@Composable
internal fun TopicChips(selected: SpaceTopic?, onSelect: (SpaceTopic?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s1, bottom = 10.dp)
            .semantics { contentDescription = "Topic" },
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        SuggestionChip("All", { onSelect(null) }, selected = selected == null)
        SpaceTopic.entries.forEach { topic -> SuggestionChip(topic.label, { onSelect(topic) }, selected = selected == topic) }
    }
}

/** The search field (canvas `search_bar`): 44 dp at least, the glass icon, Clear while there is text, Cancel. */
@Composable
internal fun SpaceSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, onCancel: (() -> Unit)?, autoFocus: Boolean = false) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val focus = remember { FocusRequester() }
    val asha = LocalAshaLauncher.current
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Row(
            Modifier.weight(1f).heightIn(min = CircasoSizes.controlMd).clip(CircasoShapes.md).background(c.fill).padding(horizontal = CircasoSpacing.s3)
                .semantics { contentDescription = "Search" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
        ) {
            CircasoIconImage(CircasoIcon.Search, c.inkSecondary, Modifier.size(16.dp))
            Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) BasicText(placeholder, style = t.body.copy(color = c.inkSecondary), maxLines = 1)
                BasicTextField(
                    value, { next ->
                        // `/asha` opens Asha from this field too (it isn't a secret one); the command isn't kept.
                        val rest = if (asha != null) textWithoutAshaCommand(next) else null
                        if (rest != null) { onValueChange(rest); asha?.open() } else onValueChange(next)
                    },
                    singleLine = true, textStyle = t.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (value.isNotEmpty()) Box(
                Modifier.size(CircasoSizes.controlMd).pressable(role = Role.Button) { onValueChange("") }.semantics { contentDescription = "Clear" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(22.dp).clip(CircasoShapes.circle).background(c.fillStrong), contentAlignment = Alignment.Center) {
                    CircasoIconImage(CircasoIcon.Close, c.ink, Modifier.size(11.dp), strokeWidth = 2.6f)
                }
            }
        }
        if (onCancel != null) CircasoButton("Cancel", onCancel, style = ButtonStyle.Plain, size = ButtonSize.Small)
    }
}

/** Bold every place a word of [query] appears in [text] (SD2: "Matching words are bold"). */
internal fun highlight(text: String, query: String): AnnotatedString {
    val words = query.trim().trimStart('@').split(' ').filter { it.length >= 2 || (it.isNotEmpty() && query.trim().length == 1) }
    if (words.isEmpty()) return AnnotatedString(text)
    val marks = BooleanArray(text.length)
    words.forEach { w ->
        var i = text.indexOf(w, ignoreCase = true)
        while (i >= 0) { for (k in i until (i + w.length).coerceAtMost(text.length)) marks[k] = true; i = text.indexOf(w, i + w.length, ignoreCase = true) }
    }
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val bold = marks[i]
            var j = i
            while (j < text.length && marks[j] == bold) j++
            if (bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i, j)) } else append(text.substring(i, j))
            i = j
        }
    }
}

// ---------------------------------------------------------------- SD3

/** SD3: Suggested, See All. */
@Composable
fun SpacesSuggestedEntry(nav: SpacesNav) {
    val vm = koinViewModel<SpacesSuggestedViewModel>()
    val state by vm.state.collectAsState()
    val toast = rememberToastState()
    LaunchedEffect(state.toast) { state.toast?.let { vm.onToastShown(); toast.show(it) } }
    SpacesSuggestedContent(state, onBack = nav.back, onTopic = vm::onTopic, onOpen = { nav.openSpace(it.space.spaceId) }, onFollow = vm::onFollow,
        onJoin = nav.joinSpace, onRetry = vm::onRetry, onLoadMore = vm::onLoadMore, toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter)) })
}

@Composable
fun SpacesSuggestedContent(
    state: SpacesSuggestedUiState,
    onBack: () -> Unit,
    onTopic: (SpaceTopic?) -> Unit,
    onOpen: (com.circaso.feature.spaces.domain.Suggestion) -> Unit,
    onFollow: (String) -> Unit,
    onJoin: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit = {},
    toast: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val list = rememberLazyListState()
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last -> if (last >= list.layoutInfo.totalItemsCount - 3) onLoadMore() }
    }
    CircasoScaffold(topBar = { CircasoNavBar("Suggested", onBack) }, toast = toast) { padding ->
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), state = list, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + CircasoSpacing.s6)) {
            item { TopicChips(state.topic, onTopic) }
            when {
                !state.online && state.items.isEmpty() -> item {
                    BasicText("Suggestions show when you’re back online.", Modifier.padding(horizontal = CircasoSpacing.s5, vertical = CircasoSpacing.s3), style = t.subhead.copy(color = c.inkSecondary))
                }
                state.failed -> item { ErrorBox("Suggestions didn’t load", "Something went wrong on our side.", state.requestId, onRetry) }
                state.loading -> items(5) { SkeletonRow() }
                state.items.isEmpty() -> item {
                    BasicText("No suggestions in ${state.topic?.label ?: "this topic"} yet.", Modifier.padding(horizontal = CircasoSpacing.s5, vertical = CircasoSpacing.s3), style = t.subhead.copy(color = c.inkSecondary))
                }
                else -> items(state.items, key = { it.space.spaceId }) { s ->
                    SuggestionRow(s, state.follow[s.space.spaceId] ?: FollowState.Idle, state.fiat, state.online,
                        onOpen = { onOpen(s) }, onFollow = { onFollow(s.space.spaceId) }, onJoin = { onJoin(s.space.spaceId) })
                }
            }
            if (state.loadingMore) item { SkeletonRow() }
        }
    }
}

