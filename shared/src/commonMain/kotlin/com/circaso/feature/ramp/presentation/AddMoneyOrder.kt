package com.circaso.feature.ramp.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.core.common.maskedForDisplay
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.SheetBadge
import com.circaso.designsystem.chrome.SheetTone
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.DetailRow
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.MoneyCard
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.ramp.domain.RampOrder
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.wallet.domain.chainName
import kotlinx.coroutines.delay

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
    // The number never breaks across two lines in the middle of a sentence.
    val phone = order.maskedPhone.takeIf { it.isNotBlank() }?.maskedForDisplay()?.replace(' ', '\u00A0') ?: "your M-Pesa number"
    // An unpaid ending says nothing was taken only when nothing was: a reason this version doesn't know is never guessed at.
    val unpaid = !everPaid && order.failureReason.let { it == null || it == "payment_failed" || it == "cancelled" || it == "expired" }
    val ifPaid = "If M-Pesa did take the money, it isn't lost: we'll add your USDC or send the shillings back. Please don't pay again. Write to Support with the reference below."
    return when (order.state) {
        RampOrderState.Reserved -> OrderLook(null, SheetTone.Neutral, "Sending the request to your phone", "An M-Pesa request for $pay is on its way to $phone.", ended = false)
        RampOrderState.PaymentRequested -> OrderLook(CircasoIcon.Handset, SheetTone.Neutral, "Check your phone", "We sent an M-Pesa request for $pay to $phone. Enter your M-Pesa PIN there to approve it. If you don't, it closes by itself and nothing is taken.", ended = false)
        RampOrderState.Paid -> OrderLook(null, SheetTone.Neutral, "M-Pesa confirmed your payment", "$pay received. Your $get is being sent to your wallet now.", ended = false)
        RampOrderState.Releasing -> OrderLook(null, SheetTone.Neutral, "Sending your USDC", "$get is on its way to your wallet on $chain. This takes a few seconds.", ended = false)
        // A sale's states never reach a top-up's page (the flow hands a sale to Cash Out); a newer server's are this.
        RampOrderState.Unknown, RampOrderState.AwaitingTransfer, RampOrderState.Funded, RampOrderState.PayingOut, RampOrderState.Refunding, RampOrderState.Refunded ->
            OrderLook(null, SheetTone.Neutral, "Working on your top-up", "This is taking a moment. You don't need to do anything.", ended = false)
        RampOrderState.Completed -> OrderLook(CircasoIcon.Check, SheetTone.Neutral, "$get added", "It's in your wallet on $chain.", ended = true)
        RampOrderState.Cancelled -> OrderLook(
            CircasoIcon.Close, SheetTone.Neutral, "Top-up cancelled",
            if (unpaid) "Nothing was taken from your M-Pesa. You can start again whenever you like." else ifPaid, ended = true, needsReference = !unpaid, support = !unpaid,
        )
        RampOrderState.Expired -> OrderLook(
            CircasoIcon.Clock, SheetTone.Warning, "The M-Pesa request timed out",
            if (unpaid) "It wasn't approved in time, so nothing was taken. If you approved it at the last moment, your USDC still arrives and this screen updates by itself." else ifPaid,
            ended = true, needsReference = true, support = !unpaid,
        )
        RampOrderState.Failed -> OrderLook(
            CircasoIcon.Warning, SheetTone.Critical, "The top-up didn't go through",
            if (unpaid) "M-Pesa didn't complete the payment. The request was declined or cancelled, or the balance was too low. Nothing was taken, so you can try again." else ifPaid,
            ended = true, needsReference = true, support = !unpaid,
        )
        RampOrderState.Review -> OrderLook(
            CircasoIcon.Shield, SheetTone.Warning, "We're checking this top-up",
            "Something didn't match, so a person at Circaso is looking at it. Your money is safe: you'll get your USDC or your shillings back. Please don't pay again.",
            ended = true, needsReference = true, support = true,
        )
    }
}

/** Where a step of the order's story stands. */
internal enum class StageMark { Waiting, Active, Done }

/** One line of the story the order tells while it is under way: [text] is already the words for its [mark]. */
internal data class OrderStage(val text: String, val mark: StageMark)

/**
 * The order's progress as four plain steps: the request reaches the phone, M-Pesa confirms the payment, the USDC is
 * sent, it is in the wallet. Each step's words change with where it stands: what is still to come is said as
 * something Circaso will do ("We send your USDC"), never as if it had happened. A finished top-up has all four done
 * (the page shows them for a moment as it lands). Empty for any other ending (the page says how it ended instead) and
 * for a state this version doesn't know (it can't say which step that is).
 */
internal fun orderStages(state: RampOrderState): List<OrderStage> {
    val at = when (state) {
        RampOrderState.Reserved -> 0
        RampOrderState.PaymentRequested -> 1
        RampOrderState.Paid, RampOrderState.Releasing -> 2
        RampOrderState.Completed -> 4
        else -> return emptyList()
    }
    fun mark(i: Int) = when { i < at -> StageMark.Done; i == at -> StageMark.Active; else -> StageMark.Waiting }
    return listOf(
        OrderStage(if (at == 0) "Sending the request to your phone" else "Request sent to your phone", mark(0)),
        OrderStage(when (mark(1)) { StageMark.Active -> "Waiting for your M-Pesa PIN"; StageMark.Done -> "Payment confirmed by M-Pesa"; else -> "M-Pesa confirms your payment" }, mark(1)),
        OrderStage(
            when (mark(2)) { StageMark.Done -> "USDC sent"; StageMark.Waiting -> "We send your USDC"; else -> if (state == RampOrderState.Paid) "Getting your USDC ready" else "Sending your USDC" },
            mark(2),
        ),
        OrderStage(if (mark(3) == StageMark.Done) "Added to your wallet" else "It lands in your wallet", mark(3)),
    )
}

/**
 * What the two figures are called for where the order stands: what you pay and get while it is under way, what you
 * paid and got once it is in, and for an ending nothing that claims money moved when it didn't (or that it didn't
 * when it may have).
 */
internal fun figureLabels(state: RampOrderState, look: OrderLook): Pair<String, String> = when {
    state == RampOrderState.Completed -> "You paid" to "You got"
    look.support -> "M-Pesa request" to "USDC due"
    look.ended -> "Amount" to "Would have added"
    else -> "You pay" to "You get"
}

/** What the buttons under an order are for: it only changes when the kind does, so a state moving on never flickers them. */
private enum class OrderActions { Following, Done, Support, Again }

/**
 * An order from the moment the request leaves until the USDC is in the wallet, or until it ends another way. The hero
 * is the money itself ([ConversionCard]): the M-Pesa card waiting for the PIN, coming apart into the silver USDC card
 * once M-Pesa has the money, settling as it lands. Under it one sentence says where it stands, then the story so far
 * in small steps (for clarity and for screen readers), then the figures. Every change of state eases in, and a top-up
 * that lands is felt and heard as well as seen (the haptic and the chime, on the frame the card settles).
 */
@Composable
internal fun OrderPage(
    state: AddMoneyUiState, order: RampOrder, bottomInset: Dp, onBack: () -> Unit, onSupport: () -> Unit, onCancel: () -> Unit, onAgain: () -> Unit, onCopy: (String) -> Unit,
    onLanded: () -> Unit = {}, frozenAt: Float? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val look = orderLook(order, state.everPaid)
    val still = CircasoTheme.reduceMotion
    val done = order.state == RampOrderState.Completed
    // Watched as it lands, the four steps stay a moment, all ticked, before they give way to what was added; a top-up
    // opened after it had ended shows none.
    var seen by remember { mutableStateOf(order.state) }
    LaunchedEffect(order.state) {
        if (done && seen != RampOrderState.Completed && !still) delay((ConvertMs + SheenMs).toLong())
        seen = order.state
    }
    val landing = done && seen != RampOrderState.Completed
    val actions = when {
        done -> OrderActions.Done
        look.support -> OrderActions.Support
        look.ended -> OrderActions.Again
        else -> OrderActions.Following
    }
    AddMoneyPageFrame(
        bottomInset,
        footer = {
            AnimatedContent(actions, transitionSpec = { easeOver(still) }, label = "order actions") { kind ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (kind) {
                        OrderActions.Done -> CircasoButton("Done", onBack, size = ButtonSize.Large, block = true)
                        OrderActions.Support -> {
                            CircasoButton("Contact Support", onSupport, size = ButtonSize.Large, block = true)
                            CircasoButton("Done", onBack, Modifier.padding(top = CircasoSpacing.s2), style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
                        }
                        OrderActions.Again -> {
                            CircasoButton("Try Again", onAgain, size = ButtonSize.Large, block = true, enabled = state.online)
                            Reveal(!state.online) { FooterNote(OfflineNote, Modifier.padding(top = CircasoSpacing.s2)) }
                            CircasoButton("Done", onBack, Modifier.padding(top = CircasoSpacing.s2), style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
                        }
                        OrderActions.Following -> {
                            CircasoButton("Close", onBack, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
                            // Under the button, as on every page; its words change with the connection, and the footer makes room.
                            AnimatedContent(
                                when {
                                    !state.online -> "You're offline. This updates by itself when you're back. If you approve the request on your phone, your USDC still arrives."
                                    state.stale -> "Couldn't check just now. Still trying."
                                    else -> "You can leave this screen. We'll tell you when your USDC arrives."
                                },
                                transitionSpec = { easeOver(still) }, label = "order note", modifier = Modifier.padding(top = CircasoSpacing.s2),
                            ) { FooterNote(it) }
                            // R6 only works before the payment, so it is only offered then; the server has the last word.
                            Reveal(state.canCancel) {
                                CircasoButton("Cancel Top-Up", onCancel, Modifier.padding(top = CircasoSpacing.s2), style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true, enabled = state.online && !state.cancelling, busy = state.cancelling)
                            }
                        }
                    }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally) {
            // The money, as the hero: the card keeps its place while everything under it changes.
            ConversionCard(
                order, look, everPaid = state.everPaid, celebrate = state.celebrate == order.orderId, quiet = state.quietLanding == order.orderId,
                onLanded = onLanded, frozenAt = frozenAt,
            )
            // An ending other than the money arriving says what it is at a glance, as the sheets do: a warning, a
            // clock, the shield of a review, a cross.
            val ending = lastOf(look.icon?.takeIf { look.ended && !done }?.let { it to look.tone })
            Reveal(look.ended && !done && look.icon != null) { ending?.let { (icon, tone) -> SheetBadge(icon, tone, Modifier.padding(top = CircasoSpacing.s5)) } }
            // The state is announced as it changes: the person may be looking at the M-Pesa prompt, not at this screen.
            AnimatedContent(
                order.state to look, contentKey = { it.first }, transitionSpec = { easeOver(still) }, label = "order state",
                modifier = Modifier.fillMaxWidth().padding(top = CircasoSpacing.s5).semantics { liveRegion = LiveRegionMode.Polite },
            ) { (_, l) ->
                // The sheets' own title and paragraph (title-2 over body, 6 dp apart), the same size in every state.
                Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    BasicText(l.title, Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
                    BasicText(l.message, style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                }
            }
        }
        Column {
            val stages = orderStages(order.state)
            val shown = lastOf(stages.takeIf { it.isNotEmpty() })
            // Under the card the steps are a quiet caption: the card tells the story, the words keep it exact.
            Reveal(stages.isNotEmpty() && (!done || landing)) { if (shown != null) Stages(shown, Modifier.padding(bottom = CircasoSpacing.s5), compact = true) }
            val (paying, getting) = figureLabels(order.state, look)
            ListGroup {
                // Where Support will ask for it, the reference comes first.
                if (look.needsReference) { ReferenceRow(order.orderId, onCopy); RowDivider() }
                FigureRow(paying, order.pay.text)
                RowDivider()
                FigureRow(getting, order.receive.text, strong = true)
                RowDivider()
                FigureRow("Rate", rateLine(order.rate))
                RowDivider()
                FigureRow(if (order.providerFee != null) "Circaso fee" else "Fee", feeText(order.fee.amountMinor, order.fee.text, named = order.providerFee != null))
                mpesaFeeText(order.providerFee)?.let { RowDivider(); FigureRow("M-Pesa fee", it) }
                RowDivider()
                DetailRow("Network", chainName(order.blockchain))
                if (!look.needsReference) { RowDivider(); ReferenceRow(order.orderId, onCopy) }
            }
            val note = lastOf(state.cancelNote)
            Reveal(state.cancelNote != null) { if (note != null) Banner(note, CircasoIcon.Info, modifier = Modifier.padding(top = CircasoSpacing.s4), tone = BannerTone.Neutral) }
        }
    }
}

/** Words giving way to other words: a cross-fade, while what holds them eases to the new height (nothing under them jumps). */
internal fun AnimatedContentTransitionScope<*>.easeOver(still: Boolean): ContentTransform =
    (fadeIn(tween(if (still) 0 else CircasoMotion.BaseMs)) togetherWith fadeOut(tween(if (still) 0 else CircasoMotion.QuickMs))) using SizeTransform(clip = false)

/** Between a title and its paragraph, as the sheets have it (the canvas's 6 px). */
internal val TitleGap = 6.dp

/**
 * The top of an order that is still under way, in the badge's own place and size: the phone while the request waits
 * on it (a slow ring breathes around it), a spinner while Circaso is working. Still under Reduce Motion.
 */
@Composable
internal fun WaitingBadge(icon: CircasoIcon?) {
    val c = CircasoTheme.colors
    val still = CircasoTheme.reduceMotion
    Box(Modifier.size(CircasoSizes.sheetBadge), contentAlignment = Alignment.Center) {
        if (icon != null && !still) {
            val breath by rememberInfiniteTransition(label = "waiting").animateFloat(0f, 1f, infiniteRepeatable(tween(CircasoMotion.MomentMs * 2), RepeatMode.Restart), label = "waiting ring")
            Box(
                Modifier.size(CircasoSizes.sheetBadge).graphicsLayer {
                    val grow = 1f + RingGrowth * breath
                    scaleX = grow; scaleY = grow; alpha = 1f - breath
                }.clip(CircasoShapes.circle).background(c.fillStrong),
            )
        }
        Box(Modifier.size(CircasoSizes.sheetBadge).clip(CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
            if (icon != null) CircasoIconImage(icon, c.ink, Modifier.size(CircasoSizes.sheetBadgeIcon), strokeWidth = 1.8f) else Spinner(c.ink)
        }
    }
}

/** How far the waiting ring spreads past the badge before it has faded: a quarter of its size. */
private const val RingGrowth = 0.25f

/** The steps' marks share one column: a tick, a spinner or a dot in the same 24 dp box, joined by a hairline. */
private val MarkBox = 24.dp
private val MarkGlyph = 20.dp
private val MarkDot = 8.dp
private val StageLine = 2.dp
private val MarkBoxCompact = 20.dp
private val MarkGlyphCompact = 16.dp
private val MarkDotCompact = 6.dp

/** [compact]: the smaller caption under Add Money's card (subhead words, a smaller mark column). */
@Composable
internal fun Stages(stages: List<OrderStage>, modifier: Modifier = Modifier, compact: Boolean = false) {
    ListGroup(modifier) {
        Column(Modifier.padding(horizontal = CircasoSpacing.s4, vertical = if (compact) CircasoSpacing.s2 else CircasoSpacing.s3)) {
            stages.forEachIndexed { i, stage ->
                if (i > 0) StageJoin(reached = stage.mark != StageMark.Waiting, compact)
                StageRow(stage, compact)
            }
        }
    }
}

/** The line from one step's mark to the next: it darkens as the order reaches the step below it. */
@Composable
private fun StageJoin(reached: Boolean, compact: Boolean) {
    val c = CircasoTheme.colors
    val ink by animateColorAsState(if (reached) c.ink else c.separator, tween(if (CircasoTheme.reduceMotion) 0 else CircasoMotion.BaseMs), label = "stage join")
    val box = if (compact) MarkBoxCompact else MarkBox
    Box(Modifier.padding(start = (box - StageLine) / 2).width(StageLine).height(if (compact) CircasoSpacing.s2 else CircasoSpacing.s3).clip(CircasoShapes.pill).background(ink))
}

/** One step of the order's story: a tick when done, a spinner while it is the one under way, a dot while it waits. */
@Composable
private fun StageRow(stage: OrderStage, compact: Boolean) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val still = CircasoTheme.reduceMotion
    val ink by animateColorAsState(if (stage.mark == StageMark.Waiting) c.inkSecondary else c.ink, tween(if (still) 0 else CircasoMotion.BaseMs), label = "stage ink")
    Row(
        Modifier.fillMaxWidth().padding(vertical = if (compact) 2.dp else CircasoSpacing.s1).clearAndSetSemantics {
            contentDescription = stage.text + when (stage.mark) { StageMark.Done -> ", done"; StageMark.Active -> ", in progress"; StageMark.Waiting -> ", waiting" }
        },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        // The mark turns over in place: the spinner settles into a tick rather than being swapped for one.
        AnimatedContent(
            stage.mark,
            transitionSpec = {
                if (still) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else (fadeIn(tween(CircasoMotion.BaseMs)) + scaleIn(tween(CircasoMotion.BaseMs), initialScale = MarkFrom)) togetherWith (fadeOut(tween(CircasoMotion.QuickMs)) + scaleOut(tween(CircasoMotion.QuickMs), targetScale = MarkFrom))
            },
            contentAlignment = Alignment.Center, label = "stage mark",
        ) { mark ->
            Box(Modifier.size(if (compact) MarkBoxCompact else MarkBox), contentAlignment = Alignment.Center) {
                when (mark) {
                    StageMark.Done -> CircasoIconImage(CircasoIcon.Check, c.ink, Modifier.size(if (compact) MarkGlyphCompact else MarkGlyph), strokeWidth = 2.4f)
                    StageMark.Active -> Spinner(c.ink, if (compact) Modifier.graphicsLayer { scaleX = 0.85f; scaleY = 0.85f } else Modifier)
                    StageMark.Waiting -> Box(Modifier.size(if (compact) MarkDotCompact else MarkDot).clip(CircasoShapes.circle).background(c.inkTertiary))
                }
            }
        }
        // The words leaving keep the weight they had: only what arrives is drawn in the new one.
        AnimatedContent(
            stage.text to (stage.mark == StageMark.Active), transitionSpec = { fadeIn(tween(if (still) 0 else CircasoMotion.BaseMs)) togetherWith fadeOut(tween(if (still) 0 else CircasoMotion.QuickMs)) },
            label = "stage text", modifier = Modifier.weight(1f),
        ) { (text, active) ->
            val style = if (compact) (if (active) t.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else t.subhead) else if (active) t.headline else t.body
            BasicText(text, style = style.copy(color = ink))
        }
    }
}

/** A mark arrives from, and leaves to, a little under its size. */
private const val MarkFrom = 0.6f

/**
 * The order's own id, which is what Support asks for: shortened to read, whole when copied. On every order, so it can
 * be quoted before anything has gone wrong as well as after.
 */
@Composable
internal fun ReferenceRow(orderId: String, onCopy: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    DetailRow(
        "Reference",
        Modifier.pressable(role = Role.Button, onClickLabel = "Copy the reference") { onCopy(orderId) }
            .semantics(mergeDescendants = true) { contentDescription = "Reference $orderId" },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
            BasicText(shortReference(orderId), style = t.subhead.copy(color = c.ink, textAlign = TextAlign.End, fontFeatureSettings = "tnum"), maxLines = 1)
            CircasoIconImage(CircasoIcon.Copy, c.inkSecondary, Modifier.size(CopyGlyph))
        }
    }
}

/** "0C9F6A52…2B11": enough of a long id to tell two apart at a glance; a short one is shown whole. */
internal fun shortReference(id: String): String = if (id.length <= 16) id.uppercase() else (id.take(8) + "…" + id.takeLast(4)).uppercase()
