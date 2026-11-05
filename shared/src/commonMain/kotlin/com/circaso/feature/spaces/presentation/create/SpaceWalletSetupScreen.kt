package com.circaso.feature.spaces.presentation.create

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.navigation.SpaceWalletSetupRoute
import com.circaso.core.platform.shareText
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.QrCode
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.PricePeriod
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.presentation.SpaceTile
import com.circaso.feature.spaces.presentation.SpacesNav
import com.circaso.feature.spaces.presentation.usdcText
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** SN4–SN6 and SN5b. After SN3 ([SpaceWalletSetupRoute.created]) it ends on SN6; from Finish Wallet Setup, it goes back. */
@Composable
fun SpaceWalletSetupEntry(route: SpaceWalletSetupRoute, nav: SpacesNav) {
    val vm = koinViewModel<SpaceWalletSetupViewModel>(key = "space-setup-" + route.spaceId, parameters = { parametersOf(route.spaceId) })
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.onStart() }
    LaunchedEffect(state.step) {
        // Every tick stays in view a moment, then on (SN6 after a new space; back to where Finish Setup was tapped).
        if (state.step == SpaceSetupStep.Done && !route.created) { kotlinx.coroutines.delay(CircasoMotion.BaseMs * 3L); nav.back() }
    }
    SpaceWalletSetupContent(
        state, live = route.created && state.step == SpaceSetupStep.Done,
        onTryAgain = vm::onTryAgain, onLater = nav.back, onTurnOnBiometrics = vm::onTurnOnBiometrics, onNotNow = vm::onNotNow,
        onOpenSpace = { nav.back(); nav.openSpaceChat(route.spaceId) }, onInvite = { nav.back(); nav.openSpaceChat(route.spaceId); nav.invite(route.spaceId) },
    )
}

@Composable
fun SpaceWalletSetupContent(
    state: SpaceWalletSetupState,
    live: Boolean,
    onTryAgain: () -> Unit,
    onLater: () -> Unit,
    onTurnOnBiometrics: () -> Unit,
    onNotNow: () -> Unit,
    onOpenSpace: () -> Unit,
    onInvite: () -> Unit,
) {
    val name = state.spaceName.ifEmpty { "Your space" }
    val closable = state.problem != null
    CircasoScaffold(topBar = { CircasoNavBar(title = if (closable) name else "", onBack = if (closable) onLater else null) }) { padding ->
        val phase = when {
            live -> "live"
            state.problem != null -> "problem"
            state.step == SpaceSetupStep.Biometrics -> "biometrics"
            else -> "steps"
        }
        AnimatedContent(phase, transitionSpec = { fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs)) }, label = "space-setup") { p ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val tall = maxHeight
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = tall).padding(horizontal = CircasoSpacing.s6)
                        .padding(bottom = padding.calculateBottomPadding() + CircasoSpacing.s6),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (p) {
                        "live" -> Live(state, name, onOpenSpace, onInvite)
                        "biometrics" -> Biometrics(state, name, onTurnOnBiometrics, onNotNow)
                        "problem" -> Problem(state, name, onTryAgain, onLater)
                        else -> Steps(state, name)
                    }
                }
            }
        }
    }
}

/** SN5: the space exists; the wallet's steps tick as they finish, announced politely. */
@Composable
private fun Steps(state: SpaceWalletSetupState, name: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Spacer(Modifier.height(160.dp))
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        SpaceWalletBadge(name, state.photoUrl)
        BasicText("Setting up $name", Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StepLine("Space created", done = true, running = false)
            StepLine(if (state.pinSet) "PIN set" else "Set the wallet’s PIN", done = state.pinSet, running = state.step == SpaceSetupStep.Pin)
            StepLine(if (state.created) "Wallet created" else "Creating the wallet", done = state.created, running = state.step == SpaceSetupStep.Creating)
            StepLine(if (state.networksAdded) "Networks added" else "Adding networks", done = state.networksAdded, running = state.step == SpaceSetupStep.Networks || state.step == SpaceSetupStep.Live)
        }
    }
}

@Composable
private fun StepLine(text: String, done: Boolean, running: Boolean) {
    val c = CircasoTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when {
                done -> CircasoIconImage(CircasoIcon.Check, c.ink, Modifier.size(18.dp), strokeWidth = 2.4f)
                running -> Spinner(c.inkSecondary, Modifier.size(16.dp))
            }
        }
        BasicText(text, style = CircasoTheme.typography.subhead.copy(color = if (done) c.ink else c.inkSecondary))
    }
}

/** GW4 for a space: biometrics for this wallet only; the space's tile on the mark says which. */
@Composable
private fun ColumnScope.Biometrics(state: SpaceWalletSetupState, name: String, onTurnOn: () -> Unit, onNotNow: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val label = state.biometricLabel ?: "Fingerprint"
    val faceId = label.endsWith(" ID")
    val inSentence = if (faceId) label else "your ${label.lowercase()}"
    Spacer(Modifier.height(96.dp))
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Box {
            CircasoIconImage(if (faceId) CircasoIcon.FaceId else CircasoIcon.Fingerprint, c.ink, Modifier.size(84.dp), strokeWidth = 1.4f)
            Box(Modifier.align(Alignment.BottomEnd).clip(CircasoShapes.md).background(c.bg).padding(3.dp)) { SpaceTile(name, state.photoUrl, 40.dp) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText("Approve $name payments with $inSentence?", Modifier.semantics { heading() }, style = t.title1.copy(color = c.ink, textAlign = TextAlign.Center))
            BasicText("Only for this space wallet. Your personal wallet keeps its own setting. If it doesn’t work, the space’s PIN takes over.", style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        }
    }
    Spacer(Modifier.height(CircasoSpacing.s8).weight(1f))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CircasoButton("Turn On ${if (faceId) label else "Fingerprint"}", onTurnOn, size = ButtonSize.Large, block = true, busy = state.turningOnBiometrics, enabled = state.online && !state.turningOnBiometrics)
        if (!state.online) BasicText("Needs a connection", Modifier.fillMaxWidth(), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        CircasoButton("Not Now", onNotNow, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true, enabled = !state.turningOnBiometrics)
    }
}

/** SN5b, offline, and a PIN backed out of: the space is kept and hidden until its wallet exists. */
@Composable
private fun ColumnScope.Problem(state: SpaceWalletSetupState, name: String, onTryAgain: () -> Unit, onLater: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Spacer(Modifier.height(120.dp))
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.problem == SpaceSetupProblem.Offline) CircasoIconImage(CircasoIcon.Offline, c.inkSecondary, Modifier.size(44.dp), strokeWidth = 1.6f)
        else SpaceWalletBadge(name, state.photoUrl)
        BasicText(
            when (state.problem) {
                SpaceSetupProblem.Offline -> "The connection dropped"
                SpaceSetupProblem.Cancelled -> "The wallet’s PIN isn’t set"
                else -> "The space is saved. Its wallet isn’t ready yet."
            },
            Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center),
        )
        BasicText(
            "$name goes live once its wallet is set up. Until then nobody else can see it." +
                if (state.problem == SpaceSetupProblem.Offline) " You can finish it from Space Settings when you’re back online." else "",
            style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
        )
        state.message?.takeIf { it != com.circaso.core.common.ErrorMessages.SomethingWrong && state.problem == SpaceSetupProblem.Failed }?.let {
            BasicText(it, style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        }
        if (state.problem == SpaceSetupProblem.Failed) state.reference?.let { BasicText("Ref $it", style = t.caption1.copy(color = c.inkTertiary, fontFeatureSettings = "tnum")) }
    }
    Spacer(Modifier.height(CircasoSpacing.s8).weight(1f))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CircasoButton(if (state.problem == SpaceSetupProblem.Cancelled && !state.pinSet) "Set the PIN" else "Finish Wallet Setup", onTryAgain, size = ButtonSize.Large, block = true, enabled = state.online)
        if (!state.online) BasicText("Needs a connection", Modifier.fillMaxWidth(), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        CircasoButton("Later", onLater, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
    }
}

/** SN6: live. The link to share (a members-only link opens the page with its price), the QR, Open Space, Invite People. */
@Composable
private fun ColumnScope.Live(state: SpaceWalletSetupState, name: String, onOpenSpace: () -> Unit, onInvite: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val clipboard = LocalClipboardManager.current
    val link = "circaso.app/s/${state.handle}"
    var qr by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    Spacer(Modifier.height(110.dp))
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SpaceTile(name, state.photoUrl, 88.dp)
        BasicText("$name is live", Modifier.semantics { heading() }, style = t.title1.copy(color = c.ink, textAlign = TextAlign.Center))
        val kind = when (state.visibility) {
            SpaceVisibility.MembersOnly -> state.price?.let { p -> "Members only · ${usdcText(p.amountMinor)}" + if (p.period == PricePeriod.OneTime) "" else " ${p.period.per}" } ?: "Members only"
            SpaceVisibility.InviteOnly -> "Invite only"
            SpaceVisibility.Public -> "Public"
        }
        val why = if (state.visibility == SpaceVisibility.InviteOnly) "Share the link so people can ask to join, or invite them." else "Share the link so people can find and join it."
        BasicText("$kind. $why", style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        Row(
            Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.fill).padding(start = CircasoSpacing.s4, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(link, Modifier.weight(1f), style = t.subhead.copy(color = c.ink))
            CircasoButton(if (copied) "Copied" else "Copy", { clipboard.setText(AnnotatedString("https://$link")); copied = true }, style = ButtonStyle.Secondary, size = ButtonSize.Small, icon = CircasoIcon.Copy)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircasoButton("Share", { shareText("$name on Circaso: https://$link", name) }, Modifier.weight(1f), style = ButtonStyle.Secondary, block = true, icon = CircasoIcon.Share)
            CircasoButton(if (qr) "Hide QR" else "QR Code", { qr = !qr }, Modifier.weight(1f), style = ButtonStyle.Secondary, block = true, icon = CircasoIcon.Qr)
        }
        // The QR opens in place (no sheet): the space's own deep link, as SS4's share row gives it.
        if (qr) QrCode("circaso://space/@${state.handle}", label = "QR code for $name")
    }
    Spacer(Modifier.height(CircasoSpacing.s8).weight(1f))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CircasoButton("Open Space", onOpenSpace, size = ButtonSize.Large, block = true)
        CircasoButton("Invite People", onInvite, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
    }
}
