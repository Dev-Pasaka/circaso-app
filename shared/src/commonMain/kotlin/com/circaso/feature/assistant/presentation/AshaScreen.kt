package com.circaso.feature.assistant.presentation

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.heightIn
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.chrome.CircasoSheet
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.designsystem.components.AshaMark
import com.circaso.designsystem.components.BubbleMeta
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.ChatBubble
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.ComposerBar
import com.circaso.designsystem.components.HeaderSpacer
import com.circaso.designsystem.components.MessageColumn
import com.circaso.designsystem.components.SuggestionChip
import com.circaso.designsystem.components.ThreadHeader
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.components.CircasoIconImage
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.launch

/**
 * [onVoice] opens the live conversation (A11); null where this phone can only record clips, and the mic records one
 * instead. [onVoiceSettings] opens Asha's voice picker from her menu (Settings → Voice).
 */
@Composable
fun AshaScreen(viewModel: AshaViewModel, onBack: () -> Unit, onVoice: (() -> Unit)? = null, onVoiceSettings: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsState()
    DisposableEffect(viewModel) { onDispose { viewModel.onCancelRecording() } }
    // Back in the foreground: Asha may have come back from maintenance meanwhile.
    androidx.lifecycle.compose.LifecycleResumeEffect(viewModel) { viewModel.onResume(); onPauseOrDispose { viewModel.onPause() } }
    AshaContent(
        state, onBack, viewModel::onDraft, viewModel::onSend, viewModel::onClearRequested, viewModel::onClearConfirmed, viewModel::onClearDismissed,
        onMic = viewModel::onMic, onSpeak = viewModel::onSpeak, onSuggestion = viewModel::onSuggestion, onVoice = onVoice,
        onMenu = viewModel::onMenu,
        menu = AshaMenuActions(
            onClose = { viewModel.onMenu(false) },
            onBack = viewModel::onSheetBack,
            onPage = viewModel::onSheet,
            onNewChat = viewModel::onNewChat,
            onOpen = viewModel::onOpenConversation,
            onRequestDelete = viewModel::onRequestDelete,
            onCancelDelete = viewModel::onCancelDelete,
            onConfirmDelete = viewModel::onConfirmDelete,
            onRetryUsage = viewModel::onRetryUsage,
            onVoiceSettings = onVoiceSettings?.let { open -> { viewModel.onMenu(false); open() } },
            onDeleteAll = { viewModel.onMenu(false); viewModel.onClearRequested() },
        ),
    )
}

/** Suggestions the design shows above the composer (F1); the money ones arrive with the wallet. */
private val Suggestions = listOf("What can you help me with?", "Who did I chat with this week?", "How does Circaso keep my chats private?")

/**
 * Asha's chat (design F1): Back, her mark and name in the middle, and the menu (⋮ / ellipsis) on the right, which opens
 * the sheet with New chat, Recent chats, Usage & credits and Settings. Then the thread, suggestion chips over the
 * composer, and the mic for voice. Stateless so it can be previewed.
 */
@Composable
fun AshaContent(
    state: AshaUiState,
    onBack: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onClearRequested: () -> Unit,
    onClearConfirmed: () -> Unit,
    onClearDismissed: () -> Unit,
    onMic: () -> Unit = {},
    onSpeak: (String) -> Unit = {},
    onSuggestion: (String) -> Unit = {},
    onVoice: (() -> Unit)? = null,
    onMenu: (Boolean) -> Unit = {},
    menu: AshaMenuActions = AshaMenuActions(),
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        topBar = {
            ThreadHeader(
                title = "Asha",
                subtitle = state.conversationTitle ?: "Reads your activity · can't move money",
                onBack = onBack,
                avatar = { AshaMark(size = CircasoSizes.avatarSm) },
                // Everything else about Asha (new chat, recents, usage, settings) lives in her menu.
                trailing = { AshaMenuButton({ onMenu(true) }) },
                trailingCount = 1,
            )
        },
        banner = { ConnectionBanner(showing = "You can read past answers") },
        overlay = {
            CircasoSheet(state.sheet != null, { onMenu(false) }, label = "Asha menu") {
                // The last page stays while the sheet slides away, so it doesn't jump back to the menu as it closes.
                val last = remember { mutableStateOf(AshaSheet.Menu) }
                androidx.compose.runtime.SideEffect { state.sheet?.let { last.value = it } }
                AshaMenuSheetContent(state, state.sheet ?: last.value, menu)
            }
            ConfirmSheet(
                visible = state.confirmClear, title = "Delete all conversations?",
                message = "Every conversation with Asha is deleted from this phone and from Circaso.",
                confirmLabel = "Delete All", destructive = true, icon = com.circaso.designsystem.components.CircasoIcon.Trash, onConfirm = onClearConfirmed, onDismiss = onClearDismissed,
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            val shown = bubblesOf(state)
            // Each conversation opens at its newest line, following, whatever was scrolled in the last one.
            val list = androidx.compose.runtime.key(state.shownConversation) { androidx.compose.foundation.lazy.rememberLazyListState() }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val follow = rememberFollowLatest(list) { shown.size to shown.lastOrNull()?.key }
            val still = CircasoTheme.reduceMotion
            // Sending something new always brings the newest line back into view and resumes following.
            val send = { scope.launch { follow.jump(!still) }; onSend() }
            val suggest = { text: String -> scope.launch { follow.jump(!still) }; onSuggestion(text) }
            if (shown.isEmpty()) {
                EmptyAsha(Modifier.weight(1f).fillMaxWidth(), online = state.canAsk, onSuggestion = suggest)
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = list,
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = CircasoSpacing.s4, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                    ) { items(shown.asReversed(), key = { it.key }) { BubbleView(it, speaking = it.speakable && it.text == state.speakingText, canSpeak = state.online, onSpeak = onSpeak) } }
                    JumpToLatest(
                        !follow.following, { scope.launch { follow.jump(!still) } },
                        Modifier.align(Alignment.BottomEnd).padding(end = CircasoSpacing.s4, bottom = CircasoSpacing.s3),
                    )
                }
            }
            state.error?.let { BasicText(it, Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1), style = t.footnote.copy(color = c.critical)) }
            ComposerBar(
                value = state.draft,
                onValueChange = onDraft,
                placeholder = when {
                    state.voice == VoiceState.Recording -> "Listening… ${clock(state.recordedSeconds)}"
                    state.voice == VoiceState.Transcribing -> "Turning your voice into words…"
                    !state.online -> "Offline"
                    state.maintenance -> "Asha's on a short break"
                    else -> "Ask Asha"
                },
                enabled = state.canAsk && !state.sending && state.voice == VoiceState.Idle,
                canSend = state.canSend,
                onSend = { send() },
                trailing = {
                    val recording = state.voice == VoiceState.Recording
                    when {
                        // Live voice: the waveform opens the conversation screen (the design's "Ask by voice" button).
                        onVoice != null && !recording -> if (state.canAsk && !state.sending) CircasoIconButton(CircasoIcon.Waveform, "Talk to Asha", onVoice)
                        state.canUseVoice || recording ->
                            CircasoIconButton(if (recording) CircasoIcon.Stop else CircasoIcon.Mic, if (recording) "Stop recording" else "Ask by voice", onMic, emphasised = recording)
                    }
                },
                above = {
                    ConnectionLine(
                        online = state.online, backOnline = state.backOnline, waiting = state.waiting != null,
                        maintenance = if (state.maintenance) maintenanceText(questionKept = state.draft.isNotBlank(), toldWhenBack = state.toldWhenBack) else null,
                        ashaBack = state.ashaBack,
                    )
                    if (state.canAsk && !state.backOnline && !state.ashaBack && shown.isNotEmpty() && !state.sending && state.waiting == null) {
                        LazyRow(contentPadding = PaddingValues(horizontal = CircasoSpacing.s3), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), modifier = Modifier.padding(top = 10.dp)) {
                            items(Suggestions) { SuggestionChip(it, { suggest(it) }) }
                        }
                    }
                },
            )
        }
    }
}

/** What the thread shows: the history, then the question in flight (or waiting) and its answer so far. */
internal fun bubblesOf(state: AshaUiState): List<Bubble> = buildList {
    state.messages.forEach { add(Bubble("m:${it.messageId}", it.text, it.isFromUser, speakable = !it.isFromUser)) }
    // The question in flight and its answer keep the keys "q" and "a" whether asking, waiting or asking
    // again, so a dropped line never adds a second bubble.
    val waiting = state.waiting
    if (waiting != null) {
        add(Bubble("q", waiting.question, true, waiting = true, withScreen = waiting.extras.image != null))
        if (waiting.partial.isNotEmpty()) add(Bubble("a", waiting.partial, false, thinking = true, cutOff = true))
    } else {
        state.pendingQuestion?.let { add(Bubble("q", it, true, withScreen = state.pendingWithScreen)) }
        if (state.streaming.isNotEmpty() || state.sending) {
            add(Bubble("a", state.status ?: state.streaming.ifEmpty { state.carried.ifEmpty { "…" } }, false, thinking = state.streaming.isEmpty()))
        }
    }
}

internal data class Bubble(
    val key: String,
    val text: String,
    val mine: Boolean,
    val speakable: Boolean = false,
    val thinking: Boolean = false,
    /** A question waiting for the connection: faded, with a clock (design G3). */
    val waiting: Boolean = false,
    /** The part of an answer that arrived before the line dropped: quiet, and labelled so it never reads as complete. */
    val cutOff: Boolean = false,
    /** The question went with a picture of the screen (Asha's bubble). */
    val withScreen: Boolean = false,
)

/**
 * The line over the composer about the connection. Offline it says Asha needs one; when the connection comes back it
 * turns into a quiet "Back online" with a check for about 3 s ([AshaUiState.backOnline]), then folds away.
 *
 * Departure from G4 (decided 2026-11-07): G4 is a glass toast that shows only after waiting chat messages went out.
 * Asha's chat already has its offline line in this spot, so the same line turning into "Back online" says the change
 * where the person was reading, with nothing new popping over the thread; it shows on every return while Asha is open
 * because asking needs the connection. With a question waiting it adds "Asking Asha again", never "asked" before an
 * answer exists. The words and timing follow G4; reduced motion swaps them without the fade and fold.
 *
 * Asha down for maintenance (2026-11-07) uses the same line, in the same calm voice: no design frame exists, and an
 * error toast or a red line would read as something the person did wrong. Offline wins over it (the app can't know).
 * When she is back it turns into "Asha is back" with a check that springs in once, then folds away like "Back online".
 */
@Composable
internal fun ConnectionLine(online: Boolean, backOnline: Boolean, waiting: Boolean, maintenance: String? = null, ashaBack: Boolean = false) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val still = CircasoTheme.reduceMotion
    val enterMs = if (still) 0 else CircasoMotion.BaseMs
    val exitMs = if (still) 0 else CircasoMotion.QuickMs
    val words = when {
        !online && waiting -> "You're offline. Your question goes to Asha when you reconnect."
        !online -> "Asha needs a connection. You can still read your past conversation."
        backOnline && waiting -> "Back online · Asking Asha again"
        backOnline -> "Back online"
        maintenance != null -> maintenance
        ashaBack -> AshaBackText
        else -> null
    }
    // A stable node carries the live region, so TalkBack and VoiceOver hear each change of words.
    Box(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite; words?.let { contentDescription = it } }) {
        androidx.compose.animation.AnimatedVisibility(
            visible = words != null,
            enter = androidx.compose.animation.fadeIn(tween(enterMs)) + androidx.compose.animation.expandVertically(tween(enterMs)),
            exit = androidx.compose.animation.fadeOut(tween(exitMs)) + androidx.compose.animation.shrinkVertically(tween(exitMs)),
        ) {
            // The last words stay while the line folds away.
            val last = remember { mutableStateOf("") }
            androidx.compose.runtime.SideEffect { words?.let { last.value = it } }
            // The height eases between a long line and a short one, so the thread and composer never jump.
            Crossfade(words ?: last.value, Modifier.animateContentSize(tween(enterMs)), animationSpec = tween(enterMs), label = "connection line") { text ->
                val long = text.startsWith(MaintenancePrefix)
                Row(
                    Modifier.fillMaxWidth().padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2).clearAndSetSemantics {},
                    horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1),
                    // A paragraph keeps its icon on its first line.
                    verticalAlignment = if (long) Alignment.Top else Alignment.CenterVertically,
                ) {
                    if (text.startsWith("Back online")) CircasoIconImage(CircasoIcon.Check, c.inkSecondary, Modifier.size(16.dp))
                    if (text == AshaBackText) BackCheck(still)
                    if (long) CircasoIconImage(CircasoIcon.Clock, c.inkSecondary, Modifier.padding(top = 1.dp).size(16.dp))
                    BasicText(text, style = t.footnote.copy(color = c.inkSecondary))
                }
            }
        }
    }
}

internal const val MaintenancePrefix = "Asha's taking a short break for maintenance."

/**
 * What the line says while Asha is down for maintenance: calm, saying the question is kept when there is one, and
 * promising "we'll let you know" only when the server will (it recorded this person's turn and sends one push).
 */
internal fun maintenanceText(questionKept: Boolean, toldWhenBack: Boolean): String = MaintenancePrefix + when {
    questionKept && toldWhenBack -> " Your question is saved, and we'll let you know when she's back."
    questionKept -> " Your question is saved for when she's back."
    toldWhenBack -> " We'll let you know when she's back."
    else -> " She'll be back soon."
}

/** What it says for a moment once she is back. */
internal const val AshaBackText = "Asha is back"

/** The "Asha is back" check: springs in once (a small, positive beat); with reduced motion it is simply there. */
@Composable
private fun BackCheck(still: Boolean) {
    val c = CircasoTheme.colors
    val scale = remember { androidx.compose.animation.core.Animatable(if (still) 1f else 0.8f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!still) scale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy, stiffness = androidx.compose.animation.core.Spring.StiffnessMedium))
    }
    CircasoIconImage(
        CircasoIcon.Check, c.inkSecondary,
        Modifier.size(16.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value },
    )
}

@Composable
private fun EmptyAsha(modifier: Modifier, online: Boolean, onSuggestion: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(modifier.padding(horizontal = CircasoSpacing.s6), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        AshaMark(size = CircasoSizes.avatarXl)
        BasicText("Habari, I'm Asha.", style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText("Ask about your money, your contacts or how Circaso works. I read your activity; I never move money.", style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        if (online) {
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = CircasoSpacing.s2)) {
                Suggestions.forEach { SuggestionChip(it, { onSuggestion(it) }) }
            }
        }
    }
}

@Composable
internal fun BubbleView(b: Bubble, speaking: Boolean, canSpeak: Boolean, onSpeak: (String) -> Unit) {
    val c = CircasoTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (b.mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        MessageColumn(b.mine, Modifier.weight(1f, fill = false)) {
            if (b.mine || b.thinking) ChatBubble(b.text, b.mine, faded = b.waiting, quiet = b.thinking, markdown = !b.mine)
            else {
                // Her answers: markdown, charts, statements and diagrams (the output contract), on the incoming bubble.
                Box(
                    Modifier.clip(com.circaso.designsystem.components.bubbleShape(false)).background(c.fill)
                        .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
                ) { AshaAnswer(b.text) }
            }
            if (speaking) BubbleMeta("Reading aloud", icon = CircasoIcon.Speaker)
            if (b.withScreen) BubbleMeta("With your screen", icon = CircasoIcon.Screen)
            if (b.waiting) BubbleMeta("Waiting to send", icon = CircasoIcon.Clock)
            if (b.cutOff) BubbleMeta("Cut off. Asha answers again when you're back online.")
        }
        if (b.speakable && (canSpeak || speaking)) {
            Box(Modifier.padding(start = CircasoSpacing.s2, bottom = 2.dp)) {
                CircasoIconButton(if (speaking) CircasoIcon.Stop else CircasoIcon.Speaker, if (speaking) "Stop reading aloud" else "Read aloud", { onSpeak(b.text) })
            }
        }
    }
}

private fun clock(seconds: Int) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
