package com.circaso.feature.chama.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.presentation.WalletAvatar

/** GW2–GW5c: setting up a group's wallet. [onOpenGroup] leaves for the group; [onDone] when everything is set. */
@Composable
fun GroupWalletSetupScreen(viewModel: GroupWalletSetupViewModel, onOpenGroup: () -> Unit, onDone: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.onStart() }
    LaunchedEffect(state.step) { if (state.step == SetupStep.Done) onDone() }
    GroupWalletSetupContent(state, viewModel::onTryAgain, onOpenGroup, viewModel::onTurnOnBiometrics, viewModel::onNotNow)
}

@Composable
fun GroupWalletSetupContent(
    state: GroupWalletSetupState,
    onTryAgain: () -> Unit,
    onOpenGroup: () -> Unit,
    onTurnOnBiometrics: () -> Unit,
    onNotNow: () -> Unit,
) {
    val name = state.groupName.ifEmpty { "The group" }
    // While the steps run there is nothing to go back to (the PIN may be on screen); a problem offers the way out.
    val closable = state.problem != null
    CircasoScaffold(
        topBar = { CircasoNavBar(title = if (closable) name else "", onBack = if (closable) onOpenGroup else null) },
    ) { padding ->
        val phase = when {
            state.problem != null -> "problem"
            state.step == SetupStep.Biometrics -> "biometrics"
            else -> "steps"
        }
        AnimatedContent(phase, transitionSpec = { fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs)) }, label = "setup") { p ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s6)
                    .padding(bottom = padding.calculateBottomPadding() + CircasoSpacing.s6),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (p) {
                    "biometrics" -> BiometricsOffer(state, name, onTurnOnBiometrics, onNotNow)
                    "problem" -> Problem(state, name, onTryAgain, onOpenGroup)
                    else -> Steps(state, name)
                }
            }
        }
    }
}

/** GW5: the steps tick as they finish, announced politely, so a slow network never looks stuck. */
@Composable
private fun Steps(state: GroupWalletSetupState, name: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Spacer(Modifier.height(120.dp))
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        WalletAvatar(name, state.avatarUrl, group = true)
        BasicText("Setting up $name’s wallet", Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StepLine(if (state.pinSet) "PIN set" else "Set the wallet’s PIN", done = state.pinSet, running = state.step == SetupStep.Pin)
            StepLine(if (state.created) "Wallet created" else "Creating the wallet", done = state.created, running = state.step == SetupStep.Creating)
            StepLine(if (state.step.ordinal > SetupStep.Networks.ordinal) "Networks added" else "Adding networks", done = state.step.ordinal > SetupStep.Networks.ordinal, running = state.step == SetupStep.Networks)
            if (state.step == SetupStep.Rules) StepLine("Saving the group’s rules", done = false, running = true)
        }
    }
}

@Composable
private fun StepLine(text: String, done: Boolean, running: Boolean) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when {
                done -> CircasoIconImage(CircasoIcon.Check, c.ink, Modifier.size(18.dp))
                running -> Spinner(c.inkSecondary, Modifier.size(16.dp))
                else -> Unit
            }
        }
        BasicText(text, style = t.subhead.copy(color = if (done) c.ink else c.inkSecondary))
    }
}

/** GW4 / GW4b: biometrics for this wallet only; the group's picture sits on the mark so it's clear which wallet. */
@Composable
private fun BiometricsOffer(state: GroupWalletSetupState, name: String, onTurnOn: () -> Unit, onNotNow: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val label = state.biometricLabel ?: "Fingerprint"
    val faceId = label.endsWith(" ID")
    val inSentence = if (faceId) label else "your ${label.lowercase()}"
    Spacer(Modifier.height(96.dp))
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Box {
            CircasoIconImage(if (faceId) CircasoIcon.FaceId else CircasoIcon.Fingerprint, c.ink, Modifier.size(84.dp))
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 14.dp, y = 10.dp)) {
                com.circaso.designsystem.components.Avatar(name, size = CircasoSizes.avatarMd, imageUrl = state.avatarUrl)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText("Approve $name payments with $inSentence?", Modifier.semantics { heading() }, style = t.title1.copy(color = c.ink, textAlign = TextAlign.Center))
            BasicText("Only for this group wallet. Your personal wallet keeps its own setting. If it doesn’t work, the group PIN takes over.", style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        }
    }
    Spacer(Modifier.height(CircasoSpacing.s8))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CircasoButton("Turn On ${if (faceId) label else "Fingerprint"}", onTurnOn, size = ButtonSize.Large, block = true, busy = state.turningOnBiometrics, enabled = state.online && !state.turningOnBiometrics)
        CircasoButton("Not Now", onNotNow, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true, enabled = !state.turningOnBiometrics)
        BasicText("You can change this in $name’s group info.", Modifier.fillMaxWidth().padding(top = 8.dp), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
}

/** GW5b, GW5c, and a PIN the owner backed out of. The group exists in every case; nothing was lost. */
@Composable
private fun Problem(state: GroupWalletSetupState, name: String, onTryAgain: () -> Unit, onOpenGroup: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Spacer(Modifier.height(120.dp))
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (state.problem) {
            SetupProblem.Offline -> {
                CircasoIconImage(CircasoIcon.Offline, c.inkSecondary, Modifier.size(44.dp))
                BasicText("The connection dropped", Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
                BasicText(
                    if (state.created) "$name’s wallet is made, but it isn’t finished. You can finish it from the group when you’re back online."
                    else "$name is created. Its wallet isn’t finished; you can finish it from the group when you’re back online.",
                    style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                )
            }
            SetupProblem.Cancelled -> {
                WalletAvatar(name, state.avatarUrl, group = true)
                BasicText("The wallet’s PIN isn’t set", Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
                BasicText("$name is ready to chat. Set its wallet’s PIN whenever you like: Finish Wallet Setup is in the group’s info.", style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
            }
            else -> {
                WalletAvatar(name, state.avatarUrl, group = true)
                BasicText(
                    if (state.rulesFailed) "The wallet is ready. Its settings didn’t save." else "The group is ready. The wallet isn’t yet.",
                    Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center),
                )
                BasicText(
                    if (state.rulesFailed) "Who approves payouts, or the target, wasn’t saved. Try again, or change them later from group info."
                    else "We couldn’t finish setting up the wallet. Try again; if its PIN was already set, you won’t be asked for it again.",
                    style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                )
                state.message?.takeIf { it != com.circaso.core.common.ErrorMessages.SomethingWrong }?.let { BasicText(it, style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center)) }
                state.reference?.let { BasicText("Ref $it", style = t.caption1.copy(color = c.inkTertiary, fontFeatureSettings = "tnum")) }
            }
        }
    }
    Spacer(Modifier.height(CircasoSpacing.s8))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.problem) {
            SetupProblem.Offline -> CircasoButton("Open the Group", onOpenGroup, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
            SetupProblem.Cancelled -> {
                CircasoButton("Set the PIN", onTryAgain, size = ButtonSize.Large, block = true, enabled = state.online)
                CircasoButton("Open the Group", onOpenGroup, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
            }
            else -> {
                CircasoButton("Try Again", onTryAgain, size = ButtonSize.Large, block = true, enabled = state.online)
                CircasoButton("Open the Group", onOpenGroup, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
            }
        }
    }
}
