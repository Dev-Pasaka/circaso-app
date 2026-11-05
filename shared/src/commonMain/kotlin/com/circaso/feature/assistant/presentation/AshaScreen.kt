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

/**
 * [onVoice] opens the live conversation (A11); null where this phone can only record clips, and the mic records one
 * instead. [onVoiceSettings] opens Asha's voice picker from her menu (Settings → Voice).
 */
@Composable
fun AshaScreen(viewModel: AshaViewModel, onBack: () -> Unit, onVoice: (() -> Unit)? = null, onVoiceSettings: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsState()
    DisposableEffect(viewModel) { onDispose { viewModel.onCancelRecording() } }
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
                confirmLabel = "Delete All", destructive = true, onConfirm = onClearConfirmed, onDismiss = onClearDismissed,
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            val shown: List<Bubble> = buildList {
                state.messages.forEach { add(Bubble(it.text, it.isFromUser, speakable = !it.isFromUser)) }
                state.pendingQuestion?.let { add(Bubble(it, true)) }
                if (state.streaming.isNotEmpty() || state.sending) add(Bubble(state.status ?: state.streaming.ifEmpty { "…" }, false, thinking = state.streaming.isEmpty()))
            }
            if (shown.isEmpty()) {
                EmptyAsha(Modifier.weight(1f).fillMaxWidth(), online = state.online, onSuggestion = onSuggestion)
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = CircasoSpacing.s4, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                ) { items(shown.asReversed()) { BubbleView(it, speaking = it.speakable && it.text == state.speakingText, canSpeak = state.online, onSpeak = onSpeak) } }
            }
            state.error?.let { BasicText(it, Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1), style = t.footnote.copy(color = c.critical)) }
            ComposerBar(
                value = state.draft,
                onValueChange = onDraft,
                placeholder = when {
                    state.voice == VoiceState.Recording -> "Listening… ${clock(state.recordedSeconds)}"
                    state.voice == VoiceState.Transcribing -> "Turning your voice into words…"
                    state.online -> "Ask Asha"
                    else -> "Offline"
                },
                enabled = state.online && !state.sending && state.voice == VoiceState.Idle,
                canSend = state.canSend,
                onSend = onSend,
                trailing = {
                    val recording = state.voice == VoiceState.Recording
                    when {
                        // Live voice: the waveform opens the conversation screen (the design's "Ask by voice" button).
                        onVoice != null && !recording -> if (state.online && !state.sending) CircasoIconButton(CircasoIcon.Waveform, "Talk to Asha", onVoice)
                        state.canUseVoice || recording ->
                            CircasoIconButton(if (recording) CircasoIcon.Stop else CircasoIcon.Mic, if (recording) "Stop recording" else "Ask by voice", onMic, emphasised = recording)
                    }
                },
                above = {
                    if (!state.online) {
                        BasicText("Asha needs a connection. You can still read your past conversation.", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2))
                    } else if (shown.isNotEmpty() && !state.sending) {
                        LazyRow(contentPadding = PaddingValues(horizontal = CircasoSpacing.s3), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), modifier = Modifier.padding(top = 10.dp)) {
                            items(Suggestions) { SuggestionChip(it, { onSuggestion(it) }) }
                        }
                    }
                },
            )
        }
    }
}

private data class Bubble(val text: String, val mine: Boolean, val speakable: Boolean = false, val thinking: Boolean = false)

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
private fun BubbleView(b: Bubble, speaking: Boolean, canSpeak: Boolean, onSpeak: (String) -> Unit) {
    val c = CircasoTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (b.mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        MessageColumn(b.mine, Modifier.weight(1f, fill = false)) {
            if (b.mine || b.thinking) ChatBubble(b.text, b.mine, quiet = b.thinking, markdown = !b.mine)
            else {
                // Her answers: markdown, charts, statements and diagrams (the output contract), on the incoming bubble.
                Box(
                    Modifier.clip(com.circaso.designsystem.components.bubbleShape(false)).background(c.fill)
                        .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
                ) { AshaAnswer(b.text) }
            }
            if (speaking) BubbleMeta("Reading aloud", icon = CircasoIcon.Speaker)
        }
        if (b.speakable && (canSpeak || speaking)) {
            Box(Modifier.padding(start = CircasoSpacing.s2, bottom = 2.dp)) {
                CircasoIconButton(if (speaking) CircasoIcon.Stop else CircasoIcon.Speaker, if (speaking) "Stop reading aloud" else "Read aloud", { onSpeak(b.text) })
            }
        }
    }
}

private fun clock(seconds: Int) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
