package com.circaso.feature.ramp.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.circaso.core.common.maskedForDisplay
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.components.AmountDisplay
import com.circaso.designsystem.components.AmountSize
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.DetailRow
import com.circaso.designsystem.components.LetterDisc
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SegmentedControl
import com.circaso.designsystem.components.SuggestionChip
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.ramp.domain.RampMoney
import com.circaso.feature.ramp.domain.RampQuote
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.chainBaseName
import com.circaso.feature.wallet.domain.chainName

/** The gap between a currency and its number, as `.cx-amount` has it. */
private val AmountGap = 6.dp
/** Room for the caret beside the last digit, so a number that only just fits doesn't clip it. */
private val CaretRoom = 4.dp
/** The tick that says M-Pesa is the way to pay (E2: 22 px). */
private val SelectedGlyph = 22.dp
/** The price's time line: thin enough to be read as a detail, not a progress bar. */
private val ClockTrack = 3.dp

/** E2's top half: the amount in whole shillings, the quick amounts, M-Pesa as the way to pay; Continue pinned below. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AmountPage(state: AddMoneyUiState, bottomInset: Dp, onAmount: (String) -> Unit, onChain: (String) -> Unit, onContinue: () -> Unit, onCopy: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val limits = state.limits
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    // Like Send: an empty amount is ready to be typed, so the keyboard comes up (and the button rides up on it) as the
    // page arrives. One that is already there (a quick amount, back from the review) is left alone.
    LaunchedEffect(Unit) { if (state.amountText.isEmpty()) runCatching { focus.requestFocus() } }
    AddMoneyPageFrame(
        bottomInset,
        footer = {
            CircasoButton("Continue", { focusManager.clearFocus(); onContinue() }, size = ButtonSize.Large, block = true, // Busy keeps the button's own colours (it is working, not unavailable) and takes no taps.
                enabled = state.canQuote || state.quoting, busy = state.quoting)
            FooterNote(if (!state.online) OfflineNote else "You'll see the exact price before you pay.")
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
            AmountEntry(state, onAmount, focus, onDone = { focusManager.clearFocus() })
            // One line under the amount, always there: what is wrong, else roughly what it buys, else the limits.
            val error = state.amountError
            val estimate = state.estimate
            val line = when {
                error != null -> error
                state.wholeOnlyHint -> "M-Pesa takes whole shillings."
                estimate != null -> "About ${estimate.text}"
                limits != null -> "From ${limits.minOrder.text} to ${limits.maxOrder.text}"
                else -> " "
            }
            val still = CircasoTheme.reduceMotion
            AnimatedContent(
                line to (error != null), contentKey = { it.first },
                transitionSpec = { fadeIn(tween(if (still) 0 else CircasoMotion.QuickMs)) togetherWith fadeOut(tween(if (still) 0 else CircasoMotion.ExitMs)) },
                // Only a problem is read out as it appears; the estimate changes with every digit and is read when asked for.
                label = "amount line", modifier = if (error != null || state.wholeOnlyHint) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
            ) { (text, wrong) ->
                BasicText(text, style = t.footnote.copy(color = if (wrong) c.critical else c.inkSecondary, textAlign = TextAlign.Center, fontFeatureSettings = "tnum"))
            }
        }
        // Wraps at large text sizes rather than cutting a chip.
        if (state.chips.isNotEmpty()) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            state.chips.forEach { a ->
                SuggestionChip(
                    RampMoney.Fiat + " " + MinorUnits.group(a),
                    // A quick amount is the whole answer: the keyboard gets out of the way of Continue.
                    { if (state.amountText != a) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); focusManager.clearFocus(); onAmount(a) },
                    selected = state.amountText == a,
                )
            }
        }
        Labelled("Pay with") {
            ListGroup {
                ListRow(
                    "M-Pesa", sub = state.maskedPhone?.maskedForDisplay() ?: "Your verified number",
                    leading = { LetterDisc("M") },
                    trailing = { CircasoIconImage(CircasoIcon.Check, c.ink, Modifier.size(SelectedGlyph).semantics { contentDescription = "Selected" }, strokeWidth = 2.4f) },
                )
            }
        }
        val chains = state.status?.blockchains.orEmpty()
        if (chains.size > 1) Labelled("Receive on") {
            SegmentedControl(chains.map { chainBaseName(it) }, chains.indexOf(state.blockchain).coerceAtLeast(0), { onChain(chains[it]) })
        }
        Column {
            ListGroup {
                DetailRow("You get", RampMoney.Coin + (state.blockchain?.let { " on " + chainName(it) } ?: ""))
                RowDivider()
                DetailRow("Fee", state.status?.feePercent?.let { feeLine(it) } ?: "Shown before you pay")
                if (limits != null) { RowDivider(); FigureRow("Daily limit", limits.daily.text) }
            }
            FailureNote(state.failure, onCopy)
        }
    }
}

/** A footnote label over what it names ("Pay with", "Receive on"), as E2 sets them. */
@Composable
internal fun Labelled(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AmountGap)) {
        BasicText(label, style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary), modifier = Modifier.padding(horizontal = CircasoSpacing.s1))
        content()
    }
}

/** A key and a figure: the value in tabular digits, a step stronger when it is the line that matters. */
@Composable
internal fun FigureRow(key: String, value: String, strong: Boolean = false) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    DetailRow(key, Modifier.semantics(mergeDescendants = true) {}) {
        BasicText(value, style = (if (strong) t.amountSmall else t.subhead.copy(fontFeatureSettings = "tnum")).copy(color = c.ink, textAlign = TextAlign.End))
    }
}

/**
 * The amount, large and calm like Send's: "KES" on the number's baseline, the digits grouped as they are typed. The
 * number steps down a size when it would no longer fit the line (a long amount, a large text size, a narrow phone), so
 * it is never cut and never pushes its currency off the screen.
 */
@Composable
private fun AmountEntry(state: AddMoneyUiState, onAmount: (String) -> Unit, focus: FocusRequester, onDone: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The line keeps the largest size's height, so stepping the number down a size moves nothing under it.
    val line = with(density) { t.amountHero.lineHeight.toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = line), contentAlignment = Alignment.Center) {
        val shown = MinorUnits.group(state.amountText).ifEmpty { "0" }
        val room = constraints.maxWidth
        val sizes = listOf(t.amountHero to t.title2, t.amountLarge to t.title2, t.amountMedium to t.title3)
        val (number, currency) = remember(shown, room, density, t) {
            val spare = with(density) { (AmountGap + CaretRoom).roundToPx() }
            sizes.firstOrNull { (n, cur) ->
                measurer.measure(shown, n, maxLines = 1).size.width + measurer.measure(RampMoney.Fiat, cur.copy(fontWeight = FontWeight.SemiBold), maxLines = 1).size.width + spare <= room
            } ?: sizes.last()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AmountGap)) {
            BasicText(RampMoney.Fiat, style = currency.copy(fontWeight = FontWeight.SemiBold, color = c.inkSecondary), modifier = Modifier.alignByBaseline())
            BasicTextField(
                // The raw digits are the value (the caret stays true); the thousands commas are only drawn.
                value = state.amountText, visualTransformation = GroupedDigits,
                onValueChange = onAmount, singleLine = true, enabled = !state.quoting,
                textStyle = number.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                // As wide as its digits at any text size, so the number stays centred with its currency.
                modifier = Modifier.alignByBaseline().focusRequester(focus).width(IntrinsicSize.Min).defaultMinSize(minWidth = CircasoSpacing.s8)
                    .semantics { contentDescription = "Amount in Kenyan shillings" },
                decorationBox = { field ->
                    Box {
                        if (state.amountText.isEmpty()) BasicText("0", style = number.copy(color = c.inkTertiary))
                        field()
                    }
                },
            )
        }
    }
}

/** Draws "10050" as "10,050" without changing the text, mapping the caret across the commas. */
internal val GroupedDigits = VisualTransformation { text ->
    val raw = text.text
    val grouped = MinorUnits.group(raw)
    val n = raw.length
    // Commas before raw offset o: one for every full group of three to its right, except at the very start.
    fun commasBefore(o: Int): Int = if (o <= 0) 0 else ((n - 1) / 3) - ((n - o) / 3)
    TransformedText(
        AnnotatedString(grouped),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = (offset + commasBefore(offset)).coerceIn(0, grouped.length)
            override fun transformedToOriginal(offset: Int): Int = grouped.take(offset.coerceIn(0, grouped.length)).count { it != ',' }
        },
    )
}

/** "1.5%, inside what you pay"; "No fee" at zero. */
internal fun feeLine(percent: String): String = if (percent.trim().trimEnd('0').trimEnd('.').let { it.isEmpty() || it == "0" }) "No fee" else "$percent%, inside what you pay"

/** "1 USDC = KES 129.50". */
internal fun rateLine(rate: String): String {
    val whole = rate.substringBefore('.')
    val frac = rate.substringAfter('.', "").take(2).padEnd(2, '0')
    return "1 USDC = KES ${MinorUnits.group(whole)}.$frac"
}

/** "0:42". */
internal fun clock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** "KES 30, included"; "No fee" at zero. */
internal fun feeText(feeMinor: String, text: String): String = if (MinorUnits.isZero(feeMinor)) "No fee" else "$text, included"

/**
 * The price, once it is known (E2's Rate / Fee / You pay, with "Continue to M-Pesa"): what leaves M-Pesa first and
 * largest, then what arrives and at what price, then where it goes and which number is asked. The price's minute runs
 * in the footer, in one place that never changes height, so running out and getting a new price move nothing.
 */
@Composable
internal fun ReviewPage(state: AddMoneyUiState, bottomInset: Dp, onPhone: (String) -> Unit, onNewPrice: () -> Unit, onConfirm: () -> Unit, onCopy: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val quote = state.quote ?: return
    val expired = state.quoteExpired
    val focusManager = LocalFocusManager.current
    AddMoneyPageFrame(
        bottomInset,
        footer = {
            PriceClock(state.secondsLeft, state.quoteExpired)
            CircasoButton(
                when {
                    state.placing -> "Sending…"
                    expired -> "Get a New Price"
                    state.failure != null -> "Try Again"
                    else -> "Continue to M-Pesa"
                },
                // The keyboard (the number field's) gets out of the way before the page changes under it.
                { focusManager.clearFocus(); if (expired) onNewPrice() else onConfirm() }, size = ButtonSize.Large, block = true,
                // Busy keeps the button's own colours: it is working, not unavailable.
                enabled = if (expired) state.online else state.canConfirm || state.placing || state.quoting, busy = state.placing || state.quoting,
            )
            FooterNote(if (!state.online) OfflineNote else "You'll confirm with your M-Pesa PIN on your phone.")
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s2).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
            BasicText("You pay", style = t.subhead.copy(color = c.inkSecondary))
            AmountDisplay(quote.pay.unit, quote.pay.number, size = AmountSize.Large)
        }
        Column {
            QuoteDetails(quote, state.maskedPhone)
            Reveal(state.repriced && !expired) {
                Banner("The price was updated while you were here. Check it, then continue.", CircasoIcon.Info, modifier = Modifier.padding(top = CircasoSpacing.s4), tone = BannerTone.Neutral)
            }
            FailureNote(state.failure, onCopy)
        }
        if (state.needsPhone) Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            val ending = state.maskedPhone?.filter { it.isDigit() }?.takeLast(3)?.takeIf { it.length == 3 }
            CircasoTextField(
                state.phoneText, onPhone, label = "Your M-Pesa number", placeholder = "0712 345 678", error = state.phoneError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done), enabled = !state.placing,
            )
            BasicText(
                "Type the number your account is verified with" + (ending?.let { ", ending in $it" } ?: "") + ". The request can only go to that number, and this phone asks for it once.",
                style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(horizontal = CircasoSpacing.s1),
            )
        }
    }
}

/** What arrives and at what price, then where it goes and from which number: two groups, the money first. */
@Composable
private fun QuoteDetails(quote: RampQuote, maskedPhone: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        ListGroup {
            FigureRow("You get", quote.receive.text, strong = true)
            RowDivider()
            FigureRow("Rate", rateLine(quote.rate))
            RowDivider()
            FigureRow("Fee", feeText(quote.fee.amountMinor, quote.fee.text))
        }
        ListGroup {
            DetailRow("To", "Your wallet on " + chainName(quote.blockchain))
            maskedPhone?.let { RowDivider(); FigureRow("From", "M-Pesa " + it.maskedForDisplay()) }
        }
    }
}

/**
 * How long the price still holds: a thin line that drains over its minute with the time beside it in tabular digits,
 * turning amber for the last ten seconds. When it runs out the same line says so, in the same place and at the same
 * height, and the button under it becomes "Get a New Price": nothing on the page moves.
 */
@Composable
internal fun PriceClock(secondsLeft: Int, expired: Boolean) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val still = CircasoTheme.reduceMotion
    val low = !expired && secondsLeft <= LowSeconds
    val ink by animateColorAsState(if (low) c.warning else c.inkSecondary, tween(if (still) 0 else CircasoMotion.BaseMs), label = "clock ink")
    val target = (secondsLeft.toFloat() / RampQuote.QuoteSeconds).coerceIn(0f, 1f)
    // Drains a second at a time in one even glide; a new price fills it back quickly; run out, it is empty at once
    // (the words and the line agree).
    val before = remember { floatArrayOf(target) }
    val refill = target > before[0]
    SideEffect { before[0] = target }
    val left = animateFloatAsState(
        target, if (still || expired) tween(0) else if (refill) tween(CircasoMotion.BaseMs) else tween(TickMs, easing = LinearEasing), label = "clock",
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = if (expired) "This price has run out. Get a new one to carry on." else "This price is held for $secondsLeft more seconds"
            // Only running out is announced; the seconds are read when asked for, not every second.
            if (expired) liveRegion = LiveRegionMode.Polite
        },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        Box(
            Modifier.fillMaxWidth().height(ClockTrack).clip(CircasoShapes.pill).background(c.fillStrong).drawBehind {
                val w = size.width * left.value
                drawRoundRect(ink, topLeft = Offset(if (rtl) size.width - w else 0f, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2))
            },
        )
        AnimatedContent(
            expired, transitionSpec = { fadeIn(tween(if (still) 0 else CircasoMotion.QuickMs)) togetherWith fadeOut(tween(if (still) 0 else CircasoMotion.ExitMs)) }, label = "clock line",
        ) { out ->
            BasicText(
                if (out) "This price has run out" else "Price held for ${clock(secondsLeft)}",
                style = t.footnote.copy(color = if (out) c.inkSecondary else ink, textAlign = TextAlign.Center, fontFeatureSettings = "tnum"), maxLines = 1,
            )
        }
    }
}

/** The last seconds of a price, when its line turns amber. */
private const val LowSeconds = 10
/** The view model counts the price down once a second; the line glides across exactly that. */
private const val TickMs = 1_000
