package com.circaso.feature.ramp.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.common.maskedForDisplay
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.SheetMessage
import com.circaso.designsystem.chrome.SheetTone
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.ToastKind
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.AmountDisplay
import com.circaso.designsystem.components.AmountSize
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
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
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.components.SuggestionChip
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.ramp.domain.RampOrder
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.ramp.domain.RampQuote
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.chainBaseName
import com.circaso.feature.wallet.domain.chainName
import com.circaso.feature.wallet.presentation.TestnetStrip

/** Design E2 (Add money) and E3 (verify), on the ramp: amount, price, the M-Pesa prompt, then the order until it lands. */
@Composable
fun AddMoneyScreen(viewModel: AddMoneyViewModel, onBack: () -> Unit, onSupport: () -> Unit) {
    val state by viewModel.state.collectAsState()
    AddMoneyContent(
        state, onBack, onSupport,
        onRetry = viewModel::load, onAmount = viewModel::onAmount, onChain = viewModel::onChain, onContinue = viewModel::onContinue,
        onBackToAmount = viewModel::onBackToAmount, onPhone = viewModel::onPhone, onNewPrice = viewModel::onNewPrice, onConfirm = viewModel::onConfirm,
        onCancel = viewModel::onCancel, onAgain = viewModel::onAgain, onErrorShown = viewModel::onErrorShown,
    )
}

/** Stateless, so every state can be opened with sample data (`-preview=add-money…`). */
@Composable
fun AddMoneyContent(
    state: AddMoneyUiState,
    onBack: () -> Unit,
    onSupport: () -> Unit = {},
    onRetry: () -> Unit = {},
    onAmount: (String) -> Unit = {},
    onChain: (String) -> Unit = {},
    onContinue: () -> Unit = {},
    onBackToAmount: () -> Unit = {},
    onPhone: (String) -> Unit = {},
    onNewPrice: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
    onAgain: () -> Unit = {},
    onErrorShown: () -> Unit = {},
) {
    val toast = rememberToastState()
    LaunchedEffect(state.error) { state.error?.let { toast.show(it, ToastKind.Problem); onErrorShown() } }
    // E3 is a page of its own with Close and no title; everything else carries E2's bar.
    val verify = state.step == AddMoneyStep.Blocked && state.block == RampBlock.Verify
    CircasoScaffold(
        grouped = !verify,
        topBar = {
            CircasoNavBar(
                title = when {
                    verify -> ""
                    state.step == AddMoneyStep.Review -> "Review"
                    else -> "Add Money"
                },
                // While the request is being sent nothing can be undone by leaving: Back waits for the answer.
                onBack = when {
                    state.placing -> null
                    state.step == AddMoneyStep.Review -> onBackToAmount
                    else -> onBack
                },
            )
        },
        // From the network this top-up is pinned to, not from whatever is selected on the Wallet tab.
        strip = { TestnetStrip(show = state.testnet, onGrouped = !verify) },
        toast = { ToastHost(toast) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = CircasoSpacing.s4).padding(top = CircasoSpacing.s4, bottom = padding.calculateBottomPadding() + CircasoSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
        ) {
            when (state.step) {
                AddMoneyStep.Loading -> LoadingState()
                AddMoneyStep.LoadFailed -> LoadFailedState(state, onRetry, onBack)
                AddMoneyStep.Blocked -> when (state.block) {
                    RampBlock.Verify -> VerifyState(onBack)
                    RampBlock.Restricted -> MessageState(
                        CircasoIcon.Lock, "Adding money is paused on this account",
                        "This account can't add money right now. Write to Support and we'll look into it. Sending and receiving aren't affected by this.",
                        "Contact Support", onSupport, "Done", onBack, tone = SheetTone.Warning,
                    )
                    RampBlock.NoMerchant -> MessageState(
                        CircasoIcon.Clock, "Adding money is unavailable right now",
                        "Circaso's M-Pesa partners are offline for the moment. No M-Pesa request was sent. Try again soon.",
                        "Try Again", onRetry, "Done", onBack, primaryEnabled = state.online, note = OfflineNote.takeIf { !state.online },
                    )
                    // `ramp.unavailable`: off, or a preview this person isn't in. Said as the wallet always said it.
                    else -> MessageState(
                        CircasoIcon.ArrowDown, "Adding money isn't open to you yet",
                        "Topping up from M-Pesa is opening to people a few at a time" + (if (state.testnet) ", and on Testnet it is for Circaso's own testing" else "") +
                            ". Until then, send USDC to your address from any wallet or exchange.",
                        "Done", onBack, null, {},
                    )
                }
                AddMoneyStep.Amount -> AmountStep(state, onAmount, onChain, onContinue)
                AddMoneyStep.Review -> ReviewStep(state, onPhone, onNewPrice, onConfirm)
                AddMoneyStep.Order -> state.order?.let { OrderStep(state, it, onBack, onSupport, onCancel, onAgain) }
            }
        }
    }
}

private const val OfflineNote = "Adding money needs a connection."

@Composable
private fun LoadingState() {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s14).semantics(mergeDescendants = true) { contentDescription = "Checking whether you can add money" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        Spinner(c.inkSecondary)
        BasicText("One moment…", style = t.subhead.copy(color = c.inkSecondary))
    }
}

@Composable
private fun LoadFailedState(state: AddMoneyUiState, onRetry: () -> Unit, onBack: () -> Unit) {
    val offline = !state.online
    MessageState(
        if (offline) CircasoIcon.Offline else CircasoIcon.Warning,
        if (offline) "You're offline" else if (state.orderUnread) "This top-up didn't load" else "Add Money didn't open",
        when {
            // An order that exists may already be paid: only what is known is said.
            state.orderUnread -> if (offline) "This top-up shows when you're back online. It isn't affected: if you approved the M-Pesa request, your USDC still arrives." else state.failure?.message ?: "We couldn't load this top-up just now. It isn't affected."
            offline -> "Adding money needs a connection: M-Pesa and Circaso have to talk while you pay. No M-Pesa request was sent."
            else -> state.failure?.message ?: "Something went wrong. No M-Pesa request was sent."
        },
        "Try Again", onRetry, "Done", onBack, reference = state.failure?.reference, tone = if (offline) SheetTone.Neutral else SheetTone.Warning,
    )
}

/** A whole-page message in the sheets' own pattern: badge, title, one paragraph, the main button, the way out. */
@Composable
private fun MessageState(
    icon: CircasoIcon,
    title: String,
    message: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String?,
    onSecondary: () -> Unit,
    reference: String? = null,
    tone: SheetTone = SheetTone.Neutral,
    primaryEnabled: Boolean = true,
    note: String? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Spacer(Modifier.height(CircasoSpacing.s8))
    SheetMessage(title, message, icon, tone = tone)
    reference?.let { Reference(it) }
    Spacer(Modifier.height(CircasoSpacing.s2))
    CircasoButton(primary, onPrimary, size = ButtonSize.Large, block = true, enabled = primaryEnabled)
    note?.let { BasicText(it, style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth()) }
    if (secondary != null) CircasoButton(secondary, onSecondary, style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
}

/** What support needs to find this: shown on every error and every order that didn't go through. */
@Composable
private fun Reference(text: String) {
    BasicText("Reference: $text", style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
}

/**
 * Design E3, "Verify once, add money any time". Identity verification has no route yet, so the page explains what it
 * will ask and its button says so instead of starting anything.
 */
@Composable
private fun VerifyState(onBack: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.padding(horizontal = CircasoSpacing.s2), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s6)) {
        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            BasicText("Verify once, add money any time", Modifier.semantics { heading() }, style = t.title1.copy(color = c.ink))
            BasicText("Sending and receiving on Circaso never needs this. Moving money in or out of Circaso does, by law.", style = t.body.copy(color = c.inkSecondary))
        }
        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5)) {
            VerifyRow(CircasoIcon.Person, "Your national ID or passport", "A photo of the front and back")
            VerifyRow(CircasoIcon.Camera, "A quick selfie", "To check it is really you holding it")
            VerifyRow(CircasoIcon.Clock, "About 3 minutes", "Most checks finish in under 10 minutes")
        }
        Row(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.fill).padding(horizontal = CircasoSpacing.s4, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircasoIconImage(CircasoIcon.Lock, c.inkSecondary, Modifier.padding(top = 1.dp).size(18.dp), strokeWidth = 1.8f)
            BasicText("Your documents go to our verification partner, encrypted, and are never shown to other people.", style = t.subhead.copy(color = c.inkSecondary))
        }
    }
    Spacer(Modifier.height(CircasoSpacing.s2))
    CircasoButton("Verification Is Coming Soon", {}, size = ButtonSize.Large, block = true, enabled = false)
    BasicText("Verification isn't open yet. Nothing is needed from you.", style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
    CircasoButton("Not Now", onBack, style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
}

@Composable
private fun VerifyRow(icon: CircasoIcon, title: String, sub: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(CircasoSizes.avatarMd).clip(CircasoShapes.circle).background(c.fillStrong), contentAlignment = Alignment.Center) {
            CircasoIconImage(icon, c.ink, Modifier.size(20.dp), strokeWidth = 1.8f)
        }
        Column {
            BasicText(title, style = t.headline.copy(color = c.ink))
            BasicText(sub, style = t.subhead.copy(color = c.inkSecondary))
        }
    }
}

/** E2's top half: the amount in whole shillings, the quick amounts, M-Pesa as the way to pay. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AmountStep(state: AddMoneyUiState, onAmount: (String) -> Unit, onChain: (String) -> Unit, onContinue: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val limits = state.limits
    Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText("KES", style = t.title2.copy(fontWeight = FontWeight.SemiBold, color = c.inkSecondary), modifier = Modifier.padding(bottom = CircasoSpacing.s2))
            Box {
                if (state.amountText.isEmpty()) BasicText("0", style = t.amountHero.copy(color = c.inkTertiary))
                BasicTextField(
                    // The raw digits are the value (the caret stays true); the thousands commas are only drawn.
                    value = state.amountText, visualTransformation = GroupedDigits,
                    onValueChange = onAmount, singleLine = true, enabled = !state.quoting,
                    textStyle = t.amountHero.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    // As wide as its digits at any text size, so the number stays centred with its currency.
                    modifier = Modifier.width(IntrinsicSize.Min).defaultMinSize(minWidth = 32.dp).semantics { contentDescription = "Amount in Kenyan shillings" },
                )
            }
        }
        val error = state.amountError
        BasicText(
            when {
                error != null -> error
                state.wholeOnlyHint -> "M-Pesa takes whole shillings."
                limits != null -> "From ${limits.minOrder.text} to ${limits.maxOrder.text}"
                else -> " "
            },
            style = t.footnote.copy(color = if (error != null) c.critical else c.inkSecondary, textAlign = TextAlign.Center),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    // Wraps at large text sizes rather than cutting a chip.
    if (state.chips.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        state.chips.forEach { a -> SuggestionChip("KES " + MinorUnits.group(a), { onAmount(a) }, selected = state.amountText == a) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText("Pay with", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(horizontal = CircasoSpacing.s1))
        ListGroup {
            ListRow(
                "M-Pesa", sub = state.maskedPhone?.maskedForDisplay() ?: "Your verified number",
                leading = { LetterDisc("M") },
                trailing = { CircasoIconImage(CircasoIcon.Check, c.ink, Modifier.size(22.dp).semantics { contentDescription = "Selected" }, strokeWidth = 2.4f) },
            )
        }
    }
    val chains = state.status?.blockchains.orEmpty()
    if (chains.size > 1) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText("Receive on", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(horizontal = CircasoSpacing.s1))
        SegmentedControl(chains.map { chainBaseName(it) }, chains.indexOf(state.blockchain).coerceAtLeast(0), { onChain(chains[it]) })
    }
    ListGroup {
        DetailRow("You get", "USDC" + (state.blockchain?.let { " on " + chainName(it) } ?: ""))
        RowDivider()
        DetailRow("Fee", state.status?.feePercent?.let { feeLine(it) } ?: "Shown before you pay")
        if (limits != null) { RowDivider(); DetailRow("Daily limit", limits.daily.text) }
    }
    state.failure?.let { f ->
        Banner(f.message, CircasoIcon.Warning, tone = BannerTone.Critical)
        f.reference?.let { Reference(it) }
    }
    CircasoButton("Continue", onContinue, size = ButtonSize.Large, block = true, enabled = state.canQuote, busy = state.quoting)
    BasicText(if (!state.online) OfflineNote else "You'll see the exact price before you pay.", style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
}

/** Draws "10050" as "10,050" without changing the text, mapping the caret across the commas. */
internal val GroupedDigits = androidx.compose.ui.text.input.VisualTransformation { text ->
    val raw = text.text
    val grouped = MinorUnits.group(raw)
    val n = raw.length
    // Commas before raw offset o: one for every full group of three to its right, except at the very start.
    fun commasBefore(o: Int): Int = if (o <= 0) 0 else ((n - 1) / 3) - ((n - o) / 3)
    androidx.compose.ui.text.input.TransformedText(
        androidx.compose.ui.text.AnnotatedString(grouped),
        object : androidx.compose.ui.text.input.OffsetMapping {
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

/** E2's bottom half once the price is known: you pay, the fee, the rate, you get, and "Continue to M-Pesa". */
@Composable
private fun ReviewStep(state: AddMoneyUiState, onPhone: (String) -> Unit, onNewPrice: () -> Unit, onConfirm: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val quote = state.quote ?: return
    Spacer(Modifier.height(CircasoSpacing.s2))
    AmountDisplay(quote.pay.unit, quote.pay.number, sub = "You get ${quote.receive.text}", size = AmountSize.Large)
    QuoteDetails(quote, state.maskedPhone)
    if (state.needsPhone) {
        val ending = state.maskedPhone?.filter { it.isDigit() }?.takeLast(3)?.takeIf { it.length == 3 }
        CircasoTextField(
            state.phoneText, onPhone, label = "Your M-Pesa number", placeholder = "0712 345 678", error = state.phoneError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), enabled = !state.placing,
        )
        BasicText(
            "Type the number your account is verified with" + (ending?.let { ", ending in $it" } ?: "") + ". The request can only go to that number, and this phone asks for it once.",
            style = t.footnote.copy(color = c.inkSecondary),
        )
    }
    if (state.repriced && !state.quoteExpired) Banner("The price was updated while you were here. Check it, then continue.", CircasoIcon.Info, tone = BannerTone.Neutral)
    state.failure?.let { f ->
        Banner(f.message, CircasoIcon.Warning, tone = BannerTone.Critical)
        f.reference?.let { Reference(it) }
    }
    if (state.quoteExpired) {
        Banner("This price has run out. Get a new one to carry on.", CircasoIcon.Clock, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, tone = BannerTone.Neutral)
        CircasoButton("Get a New Price", onNewPrice, size = ButtonSize.Large, block = true, enabled = state.online && !state.quoting, busy = state.quoting)
    } else {
        BasicText(
            "Price held for ${clock(state.secondsLeft)}",
            style = t.footnote.copy(color = if (state.secondsLeft <= 10) c.warning else c.inkSecondary, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "This price is held for ${state.secondsLeft} more seconds" },
        )
        CircasoButton(
            if (state.placing) "Sending the Request…" else if (state.failure != null) "Try Again" else "Continue to M-Pesa",
            onConfirm, size = ButtonSize.Large, block = true, enabled = state.canConfirm, busy = state.placing || state.quoting,
        )
    }
    BasicText(if (!state.online) OfflineNote else "You'll confirm with your M-Pesa PIN on your phone.", style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun QuoteDetails(quote: RampQuote, maskedPhone: String?) {
    ListGroup {
        DetailRow("Rate", rateLine(quote.rate))
        RowDivider()
        DetailRow("Fee", if (MinorUnits.isZero(quote.fee.amountMinor)) "No fee" else quote.fee.text + " · included")
        RowDivider()
        DetailRow("You pay", quote.pay.text)
        RowDivider()
        DetailRow("You get", quote.receive.text)
        RowDivider()
        DetailRow("To", "Your wallet on " + chainName(quote.blockchain))
        maskedPhone?.let { RowDivider(); DetailRow("M-Pesa number", it.maskedForDisplay()) }
    }
}

/** What an order's state says to the person. Kept apart from the drawing so every state's words can be tested. */
internal data class OrderLook(
    val icon: CircasoIcon?, val tone: SheetTone, val title: String, val message: String, val ended: Boolean, val needsReference: Boolean = false,
    /** Money may have been taken: the way on is Support, never "Try Again" (which would invite paying twice). */
    val support: Boolean = false,
)

internal fun orderLook(order: RampOrder, everPaid: Boolean): OrderLook {
    val pay = order.pay.text
    val get = order.receive.text
    val chain = chainName(order.blockchain)
    val phone = order.maskedPhone.takeIf { it.isNotBlank() }?.maskedForDisplay() ?: "your M-Pesa number"
    // An unpaid ending says nothing was taken only when nothing was: a reason this version doesn't know is never guessed at.
    val unpaid = !everPaid && order.failureReason.let { it == null || it == "payment_failed" || it == "cancelled" || it == "expired" }
    val ifPaid = "If M-Pesa did take the money, it isn't lost: we'll add your USDC or send the shillings back. Please don't pay again. Write to Support with the reference below."
    return when (order.state) {
        RampOrderState.Reserved -> OrderLook(null, SheetTone.Neutral, "Sending the request to your phone", "An M-Pesa request for $pay is on its way to $phone.", ended = false)
        RampOrderState.PaymentRequested -> OrderLook(CircasoIcon.Phone, SheetTone.Neutral, "Check your phone", "We sent an M-Pesa request for $pay to $phone. Enter your M-Pesa PIN there to approve it. If you don't, it closes by itself and nothing is taken.", ended = false)
        RampOrderState.Paid -> OrderLook(null, SheetTone.Neutral, "M-Pesa confirmed your payment", "$pay received. Your $get is being sent to your wallet now.", ended = false)
        RampOrderState.Releasing -> OrderLook(null, SheetTone.Neutral, "Sending your USDC", "$get is on its way to your wallet on $chain. This takes a few seconds.", ended = false)
        RampOrderState.Unknown -> OrderLook(null, SheetTone.Neutral, "Working on your top-up", "This is taking a moment. You don't need to do anything.", ended = false)
        RampOrderState.Completed -> OrderLook(CircasoIcon.Check, SheetTone.Neutral, "$get added", "It's in your wallet on $chain. You paid $pay from M-Pesa.", ended = true)
        RampOrderState.Cancelled -> OrderLook(CircasoIcon.Close, SheetTone.Neutral, "Top-up cancelled", if (unpaid) "Nothing was taken from your M-Pesa." else ifPaid, ended = true, needsReference = !unpaid, support = !unpaid)
        RampOrderState.Expired -> OrderLook(
            CircasoIcon.Clock, SheetTone.Warning, "The M-Pesa request timed out",
            if (unpaid) "It wasn't approved in time, so nothing was taken. If you approved it at the last moment, your USDC still arrives and this screen updates by itself." else ifPaid,
            ended = true, needsReference = true, support = !unpaid,
        )
        RampOrderState.Failed -> OrderLook(
            CircasoIcon.Warning, SheetTone.Critical, "The top-up didn't go through",
            if (unpaid) "M-Pesa didn't complete the payment: the request was declined, cancelled or had too little balance. Nothing was taken." else ifPaid,
            ended = true, needsReference = true, support = !unpaid,
        )
        RampOrderState.Review -> OrderLook(
            CircasoIcon.Shield, SheetTone.Warning, "We're checking this top-up",
            "Something didn't match, so a person at Circaso is looking at it. Your money is safe: you'll get your USDC or your shillings back. Please don't pay again.",
            ended = true, needsReference = true, support = true,
        )
    }
}

@Composable
private fun OrderStep(state: AddMoneyUiState, order: RampOrder, onBack: () -> Unit, onSupport: () -> Unit, onCancel: () -> Unit, onAgain: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val look = orderLook(order, state.everPaid)
    Spacer(Modifier.height(CircasoSpacing.s4))
    // The state is announced as it changes: the person may be looking at the M-Pesa prompt, not at this screen.
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
        // Under way: the badge's place holds a spinner; once it ends, the badge says how.
        SheetMessage(
            look.title, look.message, look.icon, tone = look.tone,
            face = if (look.icon == null) { { Box(Modifier.size(CircasoSizes.sheetBadge).clip(CircasoShapes.pill).background(c.fill), contentAlignment = Alignment.Center) { Spinner(c.ink) } } } else null,
        )
    }
    if (!look.ended) ListGroup {
        val s = order.state
        StepRow("Approve on your phone", done = s.paid, active = s == RampOrderState.Reserved || s == RampOrderState.PaymentRequested)
        RowDivider(Modifier.padding(start = CircasoSpacing.s6 + CircasoSpacing.s3))
        StepRow("M-Pesa confirms the payment", done = s == RampOrderState.Releasing, active = s == RampOrderState.Paid)
        RowDivider(Modifier.padding(start = CircasoSpacing.s6 + CircasoSpacing.s3))
        StepRow("USDC arrives in your wallet", done = false, active = s == RampOrderState.Releasing)
    }
    ListGroup {
        DetailRow("You pay", order.pay.text)
        RowDivider()
        DetailRow("You get", order.receive.text)
        RowDivider()
        DetailRow("Fee", if (MinorUnits.isZero(order.fee.amountMinor)) "No fee" else order.fee.text + " · included")
        RowDivider()
        DetailRow("Rate", rateLine(order.rate))
        RowDivider()
        DetailRow("Network", chainName(order.blockchain))
    }
    if (look.needsReference) Reference(order.orderId)
    if (!look.ended) {
        val note = when {
            !state.online -> "You're offline. This updates by itself when you're back. If you approve the request on your phone, your USDC still arrives."
            state.stale -> "Couldn't check just now. Still trying."
            else -> "You can leave this screen. We'll tell you when your USDC arrives."
        }
        BasicText(note, style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
    }
    state.cancelNote?.let { Banner(it, CircasoIcon.Info, tone = BannerTone.Neutral) }
    when {
        order.state == RampOrderState.Completed -> CircasoButton("Done", onBack, size = ButtonSize.Large, block = true)
        look.support -> {
            CircasoButton("Contact Support", onSupport, size = ButtonSize.Large, block = true)
            CircasoButton("Done", onBack, style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
        }
        look.ended -> {
            CircasoButton("Try Again", onAgain, size = ButtonSize.Large, block = true, enabled = state.online)
            if (!state.online) BasicText(OfflineNote, style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
            CircasoButton("Done", onBack, style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
        }
        else -> {
            CircasoButton("Close", onBack, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
            // R6 only works before the payment, so it is only offered then; the server has the last word.
            if (state.canCancel) CircasoButton("Cancel Top-Up", onCancel, style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true, enabled = state.online && !state.cancelling, busy = state.cancelling)
        }
    }
}

/** One step of the order's progress: a tick when done, a spinner while it is the one under way. */
@Composable
private fun StepRow(text: String, done: Boolean, active: Boolean) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3).semantics(mergeDescendants = true) { contentDescription = text + if (done) ", done" else if (active) ", in progress" else ", waiting" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                done -> CircasoIconImage(CircasoIcon.Check, c.ink, Modifier.size(20.dp), strokeWidth = 2.4f)
                active -> Spinner(c.ink)
                else -> Box(Modifier.size(8.dp).clip(CircasoShapes.circle).background(c.inkTertiary))
            }
        }
        BasicText(text, style = t.body.copy(color = if (done || active) c.ink else c.inkSecondary))
    }
}
