package com.circaso.feature.auth.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.circaso.designsystem.adaptive.fillOrScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.common.maskedForDisplay
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.BrandMark
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.CodeInput
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.platform.PlatformStyle
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.auth.domain.PhoneNumber
import com.circaso.feature.auth.domain.PhoneVerification
import com.circaso.feature.auth.domain.SocialProvider

/** Launch screen (design ID-01). Shown for the few milliseconds it takes to read the saved session. */
@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(CircasoTheme.colors.bg), contentAlignment = Alignment.Center) {
        BrandMark()
    }
}

/** Design ID-02. Apple is offered on iOS only; Android signs in with Google. */
@Composable
fun WelcomeScreen(viewModel: WelcomeViewModel) {
    val state by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val ios = CircasoTheme.platform == PlatformStyle.Ios
    val providers = if (ios) listOf(SocialProvider.Apple, SocialProvider.Google) else listOf(SocialProvider.Google)
    val short = com.circaso.designsystem.adaptive.LocalWindowLayout.current.short
    CircasoScaffold(banner = { ConnectionBanner() }, maxContentWidth = if (short) com.circaso.designsystem.adaptive.WindowLayout.WideContentWidth else com.circaso.designsystem.adaptive.WindowLayout.ReadableWidth) {
        // On its side the mark and words sit on the left and the sign-in buttons on the right, both centred; upright,
        // the words float in the space above the buttons.
        val intro: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (short) 16.dp else 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandMark(size = if (short) 88.dp else 140.dp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText("Money, with meaning.", Modifier.semantics { heading() }, style = t.largeTitle.copy(color = c.ink, textAlign = TextAlign.Center))
                    BasicText("Send money to the people you love, as easily as a message.", style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                }
            }
        }
        val actions: @Composable () -> Unit = {
            val message = state.error ?: if (state.signedOutNotice) "You were signed out. Sign in again to carry on." else null
            if (message != null) {
                BasicText(message, Modifier.fillMaxWidth().padding(bottom = 12.dp), style = t.subhead.copy(color = if (state.error != null) c.critical else c.inkSecondary, textAlign = TextAlign.Center))
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                providers.forEachIndexed { index, provider ->
                    CircasoButton(
                        text = "Continue with ${provider.label}",
                        onClick = { viewModel.onContinue(provider) },
                        style = if (index == 0) ButtonStyle.Primary else ButtonStyle.Secondary,
                        size = ButtonSize.Large,
                        busy = state.busy == provider,
                        enabled = state.busy == null || state.busy == provider,
                        block = true,
                    )
                }
                // "Lost access to your account?" arrives with the recovery request flow; the terms and privacy links need their URLs.
                BasicText(
                    "By continuing you agree to our Terms and Privacy Policy.",
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                )
            }
        }
        if (short) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxSize().fillOrScroll().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { intro() }
                Column(Modifier.weight(1f)) { actions() }
            }
        } else if (com.circaso.designsystem.adaptive.LocalWindowLayout.current.widthClass != com.circaso.designsystem.adaptive.WidthClass.Compact) {
            // A tablet: the mark, the words and the buttons as one centred group, the buttons a hand's width rather
            // than the width of the screen (an iPad's sign-in, not a phone's stretched).
            Column(
                Modifier.fillMaxSize().fillOrScroll().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                intro()
                Spacer(Modifier.height(48.dp))
                Column(Modifier.widthIn(max = com.circaso.designsystem.adaptive.WindowLayout.FormWidth)) { actions() }
            }
        } else {
            Column(Modifier.fillMaxSize().fillOrScroll().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Spacer(Modifier.weight(1f))
                intro()
                Spacer(Modifier.weight(1f))
                actions()
            }
        }
    }
}

/** Design ID-04, plus ID-07 when the number belongs to another account. */
@Composable
fun PhoneScreen(viewModel: PhoneViewModel, onLostAccess: (phone: String) -> Unit = {}, onCodeSent: (phone: String, verification: PhoneVerification) -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            if (event is PhoneViewModel.Event.CodeSent) onCodeSent(event.phone, event.verification)
        }
    }
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        topBar = { CircasoNavBar(title = "", onBack = viewModel::onCancel) },
        banner = { ConnectionBanner() },
    ) {
        if (state.numberTaken) {
            Column(Modifier.fillMaxSize().fillOrScroll().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText("This number already has a Circaso account", Modifier.semantics { heading() }, style = t.title1.copy(color = c.ink))
                    BasicText("Sign in with the Google or Apple account you used before, and you'll go straight in.", style = t.body.copy(color = c.inkSecondary))
                }
                Spacer(Modifier.weight(1f))
                CircasoButton("Sign In with a Different Account", viewModel::onCancel, size = ButtonSize.Large, block = true)
                CircasoButton("Use a Different Number", viewModel::onUseDifferentNumber, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
                // Design SP06: the account is theirs but the Google or Apple login is gone.
                CircasoButton("Lost Access to That Account?", { onLostAccess(state.input) }, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
            }
        } else {
            Column(Modifier.fillMaxSize().fillOrScroll().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    BasicText("Add your number", Modifier.semantics { heading() }, style = t.title1.copy(color = c.ink))
                    BasicText(
                        state.accountNumberHint?.let { "Enter the number on your account. We'll verify it on this phone." }
                            ?: "Friends find and pay you by your number. We'll verify it once.",
                        style = t.body.copy(color = c.inkSecondary),
                    )
                    // The masked number gets its own line: on some phones the bullets are wide and would wrap mid-number.
                    state.accountNumberHint?.let { BasicText(it.maskedForDisplay(), style = t.headline.copy(color = c.ink)) }
                }
                Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText("Country", Modifier.padding(horizontal = 4.dp), style = t.footnote.copy(color = c.inkSecondary))
                        Box(Modifier.fillMaxWidth().height(50.dp).background(c.fill, CircasoShapes.md).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                            BasicText("KE ${PhoneNumber.CountryCode}", style = t.body.copy(color = c.ink))
                        }
                    }
                    CircasoTextField(
                        value = state.input,
                        onValueChange = viewModel::onInput,
                        label = "Phone number",
                        placeholder = "712 345 678",
                        error = state.error,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        visualTransformation = PhoneNumberTransformation,
                    )
                }
                Spacer(Modifier.weight(1f))
                CircasoButton("Send Code", viewModel::onSendCode, size = ButtonSize.Large, busy = state.busy, block = true)
            }
        }
    }
}

/** Design ID-06. */
@Composable
fun CodeScreen(viewModel: CodeViewModel, codeLength: Int, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        topBar = { CircasoNavBar(title = "", onBack = onBack) },
        banner = { ConnectionBanner() },
    ) {
        Column(Modifier.fillMaxSize().fillOrScroll().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                BasicText("Enter the code", Modifier.semantics { heading() }, style = t.title1.copy(color = c.ink))
                BasicText("Sent to ${viewModel.maskedPhone.maskedForDisplay()}", style = t.body.copy(color = c.inkSecondary))
            }
            Box(Modifier.padding(top = 40.dp)) {
                CodeInput(state.code, viewModel::onCode, length = codeLength, error = state.error != null, enabled = !state.locked && !state.busy)
            }
            Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                when {
                    state.busy -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spinner(c.ink)
                        BasicText("Checking…", style = t.subhead.copy(color = c.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                    }
                    state.error != null -> BasicText(state.error.orEmpty(), style = t.subhead.copy(color = c.critical, textAlign = TextAlign.Center))
                }
            }
            if (state.locked) {
                BasicText("This keeps your number safe if someone else is guessing.", Modifier.fillMaxWidth().padding(top = 8.dp), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
            } else {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    CircasoButton(
                        text = if (state.resendInSeconds > 0) "Send a new code in ${state.resendInSeconds}s" else "Send a New Code",
                        onClick = viewModel::onResend,
                        style = ButtonStyle.Plain,
                        size = ButtonSize.Small,
                        enabled = state.canResend,
                        busy = state.resending,
                    )
                }
            }
        }
    }
}

/** Design ID-03 "Signing you in…". */
@Composable
fun RegisteringScreen(viewModel: RegisteringViewModel) {
    val state by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(banner = { ConnectionBanner() }) {
        Column(
            Modifier.fillMaxSize().fillOrScroll().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark(size = 140.dp)
            if (state.error == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(c.ink)
                    BasicText("Signing you in…", style = t.headline.copy(color = c.ink))
                }
            } else {
                BasicText(state.error.orEmpty(), style = t.body.copy(color = c.critical, textAlign = TextAlign.Center))
                state.reference?.let { BasicText("Reference: $it", style = t.footnote.copy(color = c.inkSecondary)) }
                CircasoButton("Try Again", viewModel::retry, size = ButtonSize.Large)
            }
        }
    }
}
