package com.circaso.feature.assistant.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.circaso.designsystem.adaptive.fillOrScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.AshaOrb
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.OrbMood
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/**
 * Talking to Asha hands-free. There is no frame for this in the design (F1 only has the mic button), so it follows the
 * rest of the app: the thread header, the orb built from Asha's mark, captions in the type scale, and two round
 * controls at the bottom like a call. Leaving the screen ends the conversation.
 */
@Composable
fun VoiceScreen(viewModel: VoiceViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    // The person hung up (or the server said `ended`): nothing to read, straight back to the chat.
    LaunchedEffect(state.ending) { if (state.ending == VoiceEnding.HungUp) onBack() }
    VoiceContent(state, onEnd = { viewModel.onEnd(); onBack() }, onMute = viewModel::onMute, onAgain = viewModel::start, onLeave = onBack, onNoticeShown = viewModel::onNoticeShown, reactions = viewModel.reactions)
}

@Composable
fun VoiceContent(
    state: VoiceUiState,
    onEnd: () -> Unit,
    onMute: () -> Unit,
    onAgain: () -> Unit,
    onLeave: () -> Unit,
    onNoticeShown: () -> Unit = {},
    /** One-shot mascot reactions (the server's `reaction` tags). */
    reactions: kotlinx.coroutines.flow.Flow<com.circaso.feature.assistant.domain.AshaReaction> = kotlinx.coroutines.flow.emptyFlow(),
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    LaunchedEffect(state.notice) { state.notice?.let { toast.show(it); onNoticeShown() } }
    val mood = when (state.phase) {
        VoicePhase.Connecting, VoicePhase.Ended -> OrbMood.Idle
        VoicePhase.Listening -> OrbMood.Listening
        VoicePhase.Thinking -> OrbMood.Thinking
        VoicePhase.Speaking -> OrbMood.Speaking
    }
    val level = if (state.phase == VoicePhase.Speaking) state.voiceLevel else state.micLevel

    CircasoScaffold(
        topBar = {
            if (com.circaso.designsystem.adaptive.LocalTopBarOnSide.current) {
                Column(Modifier.fillMaxWidth().padding(start = CircasoSpacing.s4, end = CircasoSpacing.s2, top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircasoIconButton(CircasoIcon.Back, "Back to the chat", if (state.phase == VoicePhase.Ended) onLeave else onEnd)
                    BasicText("Asha", style = t.headline.copy(color = c.ink))
                    BasicText(statusLine(state), style = t.caption1.copy(color = c.inkSecondary), maxLines = 3)
                }
                return@CircasoScaffold
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = CircasoSpacing.s4, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircasoIconButton(CircasoIcon.Back, "Back to the chat", if (state.phase == VoicePhase.Ended) onLeave else onEnd)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText("Asha", style = t.headline.copy(color = c.ink))
                    BasicText(statusLine(state), style = t.caption1.copy(color = c.inkSecondary), maxLines = 1)
                }
                Spacer(Modifier.width(44.dp))
            }
        },
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().fillOrScroll().padding(bottom = padding.calculateBottomPadding()).padding(horizontal = CircasoSpacing.s6),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            // Asha on her stage (asha.riv, `Asha`): her mouth follows her voice; the orb is the fallback if Rive can't draw.
            AshaAvatar(
                state.mascot, AshaAvatarVariant.Stage,
                // Gives way on small phones and at large text sizes, so captions and controls keep their room.
                Modifier.weight(1f, fill = false).widthIn(max = AshaStageMaxWidth).aspectRatio(AshaStageAspect, matchHeightConstraintsFirst = true),
                reactions = reactions, pokeable = true,
            ) { AshaOrb(mood, level) }
            Spacer(Modifier.height(CircasoSpacing.s6))
            Captions(state, Modifier.fillMaxWidth().heightIn(min = 120.dp))
            Spacer(Modifier.weight(1f))
            Crossfade(state.phase == VoicePhase.Ended, label = "controls") { ended ->
                if (ended) EndedActions(state.ending, onAgain, onLeave) else LiveControls(state.muted, onMute, onEnd)
            }
            state.timing?.let { BasicText(it, style = t.caption2.copy(color = c.inkTertiary), modifier = Modifier.padding(top = CircasoSpacing.s2)) }
            Spacer(Modifier.height(CircasoSpacing.s6))
        }
    }
}

/** One line under the title, like a call's: what Asha is doing, or how much voice is left. */
private fun statusLine(s: VoiceUiState): String = when (s.phase) {
    VoicePhase.Connecting -> "Connecting…"
    VoicePhase.Listening -> if (s.muted) "Muted" else "Listening"
    VoicePhase.Thinking -> s.status ?: "Thinking…"
    VoicePhase.Speaking -> "Speaking"
    VoicePhase.Ended -> "Ended"
}

/** What was heard, then the question and the answer's sentences as they are spoken. Only the latest matters, so it is short. */
@Composable
private fun Captions(state: VoiceUiState, modifier: Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val key = when {
        state.phase == VoicePhase.Ended -> "ended"
        state.reply.isNotEmpty() -> "reply"
        state.question != null && state.heard.isEmpty() -> "question"
        state.heard.isNotEmpty() -> "heard"
        else -> "hint"
    }
    AnimatedContent(key, modifier, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "caption") { which ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            when (which) {
                "ended" -> BasicText(endingText(state.ending), style = t.body.copy(color = c.ink, textAlign = TextAlign.Center))
                "reply" -> {
                    state.question?.let { BasicText(it, style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    BasicText(state.reply.takeLast(2).joinToString(" "), style = t.title3.copy(color = c.ink, textAlign = TextAlign.Center), maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
                "question" -> BasicText(state.question.orEmpty(), style = t.title3.copy(color = c.ink, textAlign = TextAlign.Center), maxLines = 4, overflow = TextOverflow.Ellipsis)
                "heard" -> BasicText(state.heard, style = t.title3.copy(color = if (state.heardFinal) c.ink else c.inkSecondary, textAlign = TextAlign.Center), maxLines = 4, overflow = TextOverflow.Ellipsis)
                else -> BasicText(
                    when (state.phase) {
                        VoicePhase.Connecting -> "One moment…"
                        else -> if (state.muted) "Unmute to talk." else "Go ahead, I'm listening."
                    },
                    style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                )
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
        if (again) CircasoButton("Talk again", onAgain, size = ButtonSize.Large, block = true)
        CircasoButton("Back to the chat", onLeave, style = if (again) ButtonStyle.Plain else ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
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
