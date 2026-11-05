package com.circaso.feature.assistant.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.BubbleMeta
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.ChatBubble
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.MessageColumn
import com.circaso.designsystem.components.bubbleShape
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/**
 * Talking to Asha hands-free. There is no frame for this in the design (F1 only has the mic button), so it follows the
 * rest of the app: a call-style header, Asha's mark with voice waves that follow whoever is talking, the conversation
 * as a chat transcript (the person's words on their side as they speak, Asha's answer on hers as each sentence
 * arrives), and two round controls at the bottom like a call. Leaving the screen ends the conversation.
 */
@Composable
fun VoiceScreen(viewModel: VoiceViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    // The person hung up (or the server said `ended`): nothing to read, straight back to the chat.
    LaunchedEffect(state.ending) { if (state.ending == VoiceEnding.HungUp) onBack() }
    VoiceContent(state, onEnd = { viewModel.onEnd(); onBack() }, onMute = viewModel::onMute, onAgain = viewModel::start, onLeave = onBack, onNoticeShown = viewModel::onNoticeShown)
}

@Composable
fun VoiceContent(
    state: VoiceUiState,
    onEnd: () -> Unit,
    onMute: () -> Unit,
    onAgain: () -> Unit,
    onLeave: () -> Unit,
    onNoticeShown: () -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    LaunchedEffect(state.notice) { state.notice?.let { toast.show(it); onNoticeShown() } }
    val hasTranscript = state.turns.isNotEmpty() || state.heard.isNotEmpty()

    CircasoScaffold(
        topBar = {
            val back = if (state.phase == VoicePhase.Ended) onLeave else onEnd
            if (com.circaso.designsystem.adaptive.LocalTopBarOnSide.current) {
                Column(Modifier.fillMaxWidth().padding(start = CircasoSpacing.s4, end = CircasoSpacing.s2, top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircasoIconButton(CircasoIcon.Back, "Back to the chat", back)
                    BasicText("Asha", style = t.headline.copy(color = c.ink))
                    StatusLine(state, maxLines = 3)
                }
                return@CircasoScaffold
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = CircasoSpacing.s4, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircasoIconButton(CircasoIcon.Back, "Back to the chat", back)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText("Asha", style = t.headline.copy(color = c.ink))
                    StatusLine(state, maxLines = 1)
                }
                Spacer(Modifier.width(com.circaso.designsystem.theme.CircasoSizes.iconButton))
            }
        },
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The logo is the screen while nothing has been said; once there is a transcript it steps up and shrinks.
            val logoSize by animateDpAsState(if (hasTranscript) 132.dp else 208.dp, tween(if (CircasoTheme.reduceMotion) 0 else CircasoMotion.BaseMs), label = "logo")
            if (!hasTranscript) Spacer(Modifier.weight(0.4f))
            AshaVoiceLogo(state.visual, state.waveLevel, Modifier.padding(top = CircasoSpacing.s2), size = logoSize)
            // With reduced motion the waves are gone, so the state is also said in words under the logo.
            if (CircasoTheme.reduceMotion) StatePill(state.visual)
            if (hasTranscript) {
                Transcript(state, Modifier.weight(1f).fillMaxWidth())
            } else {
                Spacer(Modifier.height(CircasoSpacing.s4))
                BasicText(
                    hint(state),
                    Modifier.padding(horizontal = CircasoSpacing.s8),
                    style = t.body.copy(color = if (state.phase == VoicePhase.Ended) c.ink else c.inkSecondary, textAlign = TextAlign.Center),
                )
                Spacer(Modifier.weight(0.6f))
            }
            if (hasTranscript && state.phase == VoicePhase.Ended) {
                BasicText(endingText(state.ending), Modifier.padding(horizontal = CircasoSpacing.s6, vertical = CircasoSpacing.s2), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
            }
            Box(Modifier.padding(horizontal = CircasoSpacing.s6, vertical = CircasoSpacing.s4)) {
                Crossfade(state.phase == VoicePhase.Ended, label = "controls") { ended ->
                    if (ended) EndedActions(state.ending, onAgain, onLeave) else LiveControls(state.muted, onMute, onEnd)
                }
            }
            state.timing?.let { BasicText(it, style = t.caption2.copy(color = c.inkTertiary), modifier = Modifier.padding(bottom = CircasoSpacing.s2)) }
        }
    }
}

/** One line under the title, like a call's: what Asha is doing. Read out when it changes. */
@Composable
private fun StatusLine(state: VoiceUiState, maxLines: Int) {
    val c = CircasoTheme.colors
    BasicText(
        statusLine(state),
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = CircasoTheme.typography.caption1.copy(color = if (state.visual == VoiceVisual.Error) c.critical else c.inkSecondary),
        maxLines = maxLines,
    )
}

private fun statusLine(s: VoiceUiState): String = when (s.visual) {
    VoiceVisual.Connecting -> "Connecting…"
    VoiceVisual.Listening -> "Listening"
    VoiceVisual.Muted -> "Muted"
    VoiceVisual.Thinking -> s.status ?: "Thinking…"
    VoiceVisual.Speaking -> "Speaking"
    VoiceVisual.Error -> if (s.ending == VoiceEnding.Offline) "Offline" else "Not connected"
    VoiceVisual.Ended -> "Ended"
}

/** Before anything is said: what to do, or why it ended. */
private fun hint(s: VoiceUiState): String = when {
    s.phase == VoicePhase.Ended -> endingText(s.ending)
    s.phase == VoicePhase.Connecting -> "One moment…"
    s.muted -> "Unmute to talk."
    s.phase == VoicePhase.Thinking -> s.status ?: "Thinking…"
    else -> "Go ahead, I'm listening."
}

/** Reduced motion: the state in words, in a small pill under the still logo. */
@Composable
private fun StatePill(v: VoiceVisual) {
    val c = CircasoTheme.colors
    Row(
        Modifier.padding(top = CircasoSpacing.s2).clip(CircasoShapes.pill).background(c.fill).padding(horizontal = CircasoSpacing.s3, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircasoShapes.circle).background(if (v == VoiceVisual.Error) c.critical else if (v == VoiceVisual.Listening || v == VoiceVisual.Speaking) c.ink else c.inkTertiary))
        BasicText(v.label(), style = CircasoTheme.typography.caption1.copy(color = c.ink))
    }
}

/** A transcript row: the person's words or Asha's answer. */
private sealed interface Line {
    val key: String
    data class Mine(override val key: String, val text: String, val live: Boolean) : Line
    data class Hers(override val key: String, val text: String, val waiting: Boolean, val interrupted: Boolean) : Line
}

/** The call so far, newest at the bottom: each turn's question and answer, then what the person is saying now. */
@Composable
private fun Transcript(state: VoiceUiState, modifier: Modifier) {
    val lines = buildList {
        state.turns.forEachIndexed { i, turn ->
            if (turn.question.isNotBlank()) add(Line.Mine("q$i", turn.question, live = false))
            val open = i == state.turns.lastIndex && !turn.done
            when {
                turn.reply.isNotEmpty() -> add(Line.Hers("a$i", turn.replyText, waiting = false, interrupted = turn.interrupted))
                // Her side while she works: what she is doing ("Checking your transactions…") or a quiet ellipsis.
                open && state.heard.isEmpty() -> add(Line.Hers("a$i", state.status ?: "…", waiting = true, interrupted = false))
            }
        }
        if (state.heard.isNotEmpty()) add(Line.Mine("heard", state.heard, live = !state.heardFinal))
    }
    LazyColumn(
        modifier,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        items(lines.asReversed(), key = { it.key }) { line -> TranscriptRow(line, Modifier.animateItem()) }
    }
}

@Composable
private fun TranscriptRow(line: Line, modifier: Modifier) {
    val c = CircasoTheme.colors
    when (line) {
        is Line.Mine -> Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            MessageColumn(true, Modifier.weight(1f, fill = false)) {
                // While the person is still talking their words are faded: the caption may still change.
                ChatBubble(line.text, outgoing = true, faded = line.live)
                if (line.live) BubbleMeta("Hearing you…", icon = CircasoIcon.Mic)
            }
        }
        is Line.Hers -> Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            MessageColumn(false, Modifier.weight(1f, fill = false)) {
                if (line.waiting) ChatBubble(line.text, outgoing = false, quiet = true)
                else Box(
                    Modifier.clip(bubbleShape(false)).background(c.fill).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3)
                        .semantics(mergeDescendants = true) {},
                ) { AshaAnswer(line.text) }
                if (line.interrupted) BubbleMeta("You talked over this")
            }
        }
    }
}

private fun endingText(e: VoiceEnding?): String = when (e) {
    VoiceEnding.Quiet -> "It went quiet, so I hung up. Tap to talk again."
    VoiceEnding.Offline -> "The connection dropped. Try again when you're back online."
    is VoiceEnding.OutOfVoice -> "You've used this month's voice time with Asha." + (e.resetsInSeconds?.let { " It resets in about ${(it + 86_399) / 86_400} days." } ?: "")
    VoiceEnding.UseClips -> "Live voice isn't available here. You can still record a question in the chat."
    VoiceEnding.NoMicrophone -> "Allow the microphone in Settings to talk to Asha."
    is VoiceEnding.Failed -> e.message
    VoiceEnding.HungUp, null -> "Ended."
}

@Composable
private fun EndedActions(ending: VoiceEnding?, onAgain: () -> Unit, onLeave: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        val again = ending == VoiceEnding.Quiet || ending == VoiceEnding.Offline || ending is VoiceEnding.Failed
        if (again) CircasoButton("Talk Again", onAgain, size = ButtonSize.Large, block = true)
        CircasoButton("Back to the Chat", onLeave, style = if (again) ButtonStyle.Plain else ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
    }
}

/** Mute and hang up, round and large like a call's controls, with their names under them. */
@Composable
private fun LiveControls(muted: Boolean, onMute: () -> Unit, onEnd: () -> Unit) {
    val c = CircasoTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s10, Alignment.CenterHorizontally), verticalAlignment = Alignment.Top) {
        RoundControl(if (muted) CircasoIcon.MicOff else CircasoIcon.Mic, if (muted) "Unmute" else "Mute", background = if (muted) c.ink else c.fill, tint = if (muted) c.bg else c.ink, onClick = onMute)
        RoundControl(CircasoIcon.Close, "End", background = c.critical, tint = Color.White, onClick = onEnd)
    }
}

@Composable
private fun RoundControl(icon: CircasoIcon, label: String, background: Color, tint: Color, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Box(
            Modifier.size(64.dp).clip(CircasoShapes.circle).background(background).pressable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { CircasoIconImage(icon, tint, Modifier.size(26.dp)) }
        BasicText(label, style = t.caption1.copy(color = c.inkSecondary))
    }
}
