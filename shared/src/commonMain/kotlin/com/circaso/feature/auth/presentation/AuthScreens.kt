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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.SheetHead
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.motion.pressable
import com.circaso.feature.auth.domain.Countries
import com.circaso.feature.auth.domain.Country
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import com.circaso.designsystem.components.SearchField
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoSizes
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
                Spacer(Modifier.height(com.circaso.designsystem.theme.CircasoSpacing.s14))
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
    PhoneContent(
        state,
        PhoneActions(
            onInput = viewModel::onInput, onSendCode = viewModel::onSendCode, onCancel = viewModel::onCancel,
            onUseDifferentNumber = viewModel::onUseDifferentNumber, onLostAccess = { onLostAccess(state.typed) },
            onPickCountry = viewModel::onPickCountry, onQuery = viewModel::onQuery, onCountry = viewModel::onCountry,
        ),
    )
}

/** What ID-04 does, so previews can draw it from sample state. */
class PhoneActions(
    val onInput: (String) -> Unit = {},
    val onSendCode: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onUseDifferentNumber: () -> Unit = {},
    val onLostAccess: () -> Unit = {},
    val onPickCountry: (Boolean) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onCountry: (Country) -> Unit = {},
)

@Composable
fun PhoneContent(state: PhoneViewModel.UiState, actions: PhoneActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val focus = LocalFocusManager.current
    val numberFocus = remember { FocusRequester() }
    // Back to typing once a country is picked: the sheet closes and the number field takes the keyboard again.
    var refocus by remember { mutableStateOf(false) }
    LaunchedEffect(state.picking) {
        if (!state.picking && refocus) {
            refocus = false
            runCatching { numberFocus.requestFocus() }
        }
    }
    CircasoScaffold(
        topBar = { CircasoNavBar(title = "", onBack = actions.onCancel) },
        banner = { ConnectionBanner() },
        overlay = {
            CircasoSheet(state.picking, { actions.onPickCountry(false) }, label = "Choose your country") {
                CountryPickerContent(state, actions.onQuery, { refocus = true; actions.onCountry(it) }, onClose = { actions.onPickCountry(false) })
            }
        },
    ) {
        if (state.numberTaken) {
            Column(Modifier.fillMaxSize().fillOrScroll().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText("This number already has a Circaso account", Modifier.semantics { heading() }, style = t.title1.copy(color = c.ink))
                    BasicText("Sign in with the Google or Apple account you used before, and you'll go straight in.", style = t.body.copy(color = c.inkSecondary))
                }
                Spacer(Modifier.weight(1f))
                CircasoButton("Sign In with a Different Account", actions.onCancel, size = ButtonSize.Large, block = true)
                CircasoButton("Use a Different Number", actions.onUseDifferentNumber, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
                // Design SP06: the account is theirs but the Google or Apple login is gone.
                CircasoButton("Lost Access to That Account?", actions.onLostAccess, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
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
                Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 112.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText("Country", Modifier.padding(horizontal = CircasoSpacing.s1), style = t.footnote.copy(color = c.inkSecondary))
                        CountryButton(state, onClick = {
                            // The number pad would otherwise stay up under the sheet, typing into a hidden field.
                            focus.clearFocus()
                            actions.onPickCountry(true)
                        })
                    }
                    CircasoTextField(
                        value = state.input,
                        onValueChange = actions.onInput,
                        label = "Phone number",
                        placeholder = state.country.example.takeIf { it.isNotEmpty() }?.let { PhoneNumberTransformation(state.country.lengths.last).spaced(it) }.orEmpty(),
                        error = state.error,
                        modifier = Modifier.weight(1f).focusRequester(numberFocus),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        visualTransformation = remember(state.country) { PhoneNumberTransformation(state.country.lengths.last) },
                    )
                }
                Spacer(Modifier.weight(1f))
                CircasoButton("Send Code", actions.onSendCode, size = ButtonSize.Large, busy = state.busy, block = true)
            }
        }
    }
}

/** The country box (frame ID04c, a picker since 2026-11-12): flag and code on the left, the chevron at the right edge. */
@Composable
private fun CountryButton(state: PhoneViewModel.UiState, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val ink = if (state.busy) c.inkTertiary else c.ink
    Row(
        Modifier.fillMaxWidth().heightIn(min = CircasoSizes.controlLg).clip(CircasoShapes.md).background(c.fill)
            .pressable(enabled = !state.busy, onClickLabel = "Change country", onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "Country, ${state.country.name}, plus ${state.country.dialCode}"
                role = Role.Button
                if (state.busy) disabled()
            }
            .padding(start = CircasoSpacing.s4, end = CircasoSpacing.s3),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        val reduce = CircasoTheme.reduceMotion
        AnimatedContent(
            state.country,
            transitionSpec = { if (reduce) fadeIn(snap()) togetherWith fadeOut(snap()) else fadeIn(tween(CircasoMotion.QuickMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs)) },
            label = "country",
        ) { country ->
            Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
                BasicText(country.flag, style = t.body)
                BasicText("+${country.dialCode}", style = t.body.copy(color = ink), maxLines = 1)
            }
        }
        Box(Modifier.padding(start = CircasoSpacing.s2)) {
            CircasoIconImage(CircasoIcon.ChevronDown, ink, Modifier.size(14.dp), strokeWidth = 2.2f)
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


/**
 * The country picker (2026-11-12): search by name or calling code; Kenya first (and the chosen country next), then A to Z.
 * The list takes the room left above the keyboard, so the head and search never scroll away.
 */
@Composable
private fun CountryPickerContent(state: PhoneViewModel.UiState, onQuery: (String) -> Unit, onPick: (Country) -> Unit, onClose: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val list = remember(state.query, state.country) { state.countries }
    val density = LocalDensity.current
    val window = com.circaso.designsystem.adaptive.LocalWindowLayout.current
    val keyboard = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val status = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    // Window less the keyboard, the status bar, a gap above the sheet and the sheet's own head, grabber, search and padding.
    val listMax = (window.height - keyboard - status - CircasoSpacing.s3 - PickerChrome).coerceIn(PickerMinList, PickerMaxList)
    Column(Modifier.fillMaxWidth()) {
        SheetHead("Country", onClose)
        SearchField(
            state.query, onQuery, placeholder = "Search country or code", modifier = Modifier.padding(vertical = CircasoSpacing.s2),
            // One match: the Search key picks it.
            onSearch = list.singleOrNull()?.let { only -> { onPick(only) } },
        )
        if (list.isEmpty()) {
            BasicText(
                "No country matches “${state.query.trim()}”.",
                Modifier.fillMaxWidth().padding(CircasoSpacing.s6).semantics { liveRegion = LiveRegionMode.Polite },
                style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = listMax)) {
            items(list, key = { it.iso }) { country ->
                val selected = country == state.country
                ListRow(
                    country.name,
                    leading = { BasicText(country.flag, Modifier.clearAndSetSemantics {}, style = t.title3) },
                    meta = "+${country.dialCode}",
                    trailing = if (selected) ({ CircasoIconImage(CircasoIcon.Check, c.ink, Modifier.size(18.dp)) }) else null,
                    onClick = { onPick(country) },
                    // The sheet already insets its content; rows line up with its title.
                    horizontalPadding = 0.dp,
                    role = Role.RadioButton,
                    modifier = Modifier.semantics { this.selected = selected },
                )
            }
        }
    }
}

private val PickerChrome = 190.dp
private val PickerMinList = 168.dp
private val PickerMaxList = 460.dp
