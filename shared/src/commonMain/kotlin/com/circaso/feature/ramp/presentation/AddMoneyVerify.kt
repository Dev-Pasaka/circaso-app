package com.circaso.feature.ramp.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.platform.localDayIndex
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.SheetBadge
import com.circaso.designsystem.chrome.SheetTone
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.ramp.domain.KycReasons
import com.circaso.feature.ramp.domain.KycStatus
import com.circaso.feature.ramp.domain.RampVerification

// Identity verification (design E3; guide "Identity verification (KYC)"), wherever it is done from: before a first
// top-up in Add Money, or from the profile's Verification row. The words for every state live here, apart from the
// drawing, so each can be tested: one state, one calm sentence or two, one thing to do.

/** Where the check is being done from. The states and their words are the same; only where it leads differs. */
enum class VerifyEntry {
    /** Before a first top-up: verified goes straight on to the amount, and the pages are closed with a cross. */
    AddMoney,
    /** The profile's Verification screen: verified stays, and the system's Back leaves. */
    Profile,
    /** Before a first sale in Cash Out: as Add Money, with its own words (verified goes on to the amount). */
    CashOut,
}

/** What being verified opens, in the entry's own words: "add money" (Add Money and the profile) or "cash out". */
internal fun VerifyEntry.goal(): String = if (this == VerifyEntry.CashOut) "cash out" else "add money"

/** What a verify page draws from, whichever screen holds it. */
internal data class VerifyPageState(
    val verify: VerifyUi,
    val online: Boolean,
    /** The check is for Testnet: a practice run that doesn't count on Mainnet. */
    val testnet: Boolean,
    /** The button may be tapped now. */
    val canVerify: Boolean,
    val entry: VerifyEntry = VerifyEntry.AddMoney,
) {
    /** The plain way out under the main button ("Not Now"); the profile's screen has Back for that. */
    val wayOut: Boolean get() = entry != VerifyEntry.Profile
}

internal fun AddMoneyUiState.verifyPage() = VerifyPageState(verify, online, testnet, canVerify, VerifyEntry.AddMoney)

/** Which of the verify pages' states is showing. */
internal enum class VerifyKind {
    /** E3 itself: never started. */
    Intro,
    /** A check is open and unfinished. */
    Continue,
    /** A reviewer asked for a step again. */
    OneMoreThing,
    /** Everything was sent; the result isn't in yet. Resolves by itself. */
    Checking,
    /** Back from Didit's hosted page: it isn't known whether they finished, so nothing is claimed while it is read. */
    Returned,
    /** Didit's reviewers are looking. */
    InReview,
    Verified,
    /** Declined for something another try can fix. */
    Declined,
    /** Declined, expired or unfinished, and today's tries are used up: back at a known time. */
    Waiting,
    /** Declined for good: Support is the way on. */
    DeclinedFinal,
    /** Not finished in time, or the ID has since expired. */
    Expired,
    /** `ramp.kyc_attempts_exceeded`. */
    TriesUsedUp,
    /** `ramp.kyc_unavailable`: on our side. */
    ProviderDown,
    /** `ramp.kyc_not_configured`: on our side. */
    NotConfigured,
    CameraDenied,
}

/** The one thing a verify state asks of the person. */
internal enum class VerifyAction {
    /** R0, then Didit's screens: start, continue, try again, verify again. */
    Start,
    /** The phone's Settings, for the camera. */
    Settings,
    Support,
    /** Nothing to do but wait or come back: the button only closes the page. */
    Close,
    /** The flow goes on by itself. */
    None,
}

internal data class VerifyLook(
    val kind: VerifyKind,
    /** Null is the spinner, in the badge's place. */
    val icon: CircasoIcon?,
    val tone: SheetTone,
    val title: String,
    val message: String,
    val action: VerifyAction,
    /** The main button's words; empty with [VerifyAction.None]. */
    val button: String,
    /** The check's own short story while a result is awaited, and as it lands. */
    val stages: List<OrderStage> = emptyList(),
    /** Something is under way: the badge is the waiting one, never a settled sign. */
    val waiting: Boolean = false,
    /** Under the button, while online: what happens next. */
    val note: String? = null,
)

/**
 * "tomorrow, after 09:05" in the person's own time zone; "today, after 16:30" for later the same day; the weekday past
 * that. Null when there is no time or it has already passed (the page reads its state again then).
 */
internal fun retryWhen(retryAt: Long?, now: Long): String? {
    val at = retryAt?.takeIf { it > now } ?: return null
    val clock = formatLocalTime(at, LocalTimeStyle.Clock)
    return when (localDayIndex(at) - localDayIndex(now)) {
        0L -> "today, after $clock"
        1L -> "tomorrow, after $clock"
        else -> "on " + formatLocalTime(at, LocalTimeStyle.Weekday) + ", after $clock"
    }
}

/**
 * "You can try again tomorrow, after 09:05." With no time to go by, tomorrow: tries are counted by the day. A time
 * that has just passed is "in a moment": the page is reading its state again, and the button comes back with it.
 */
internal fun tryAgain(retryAt: Long?, now: Long) = when {
    retryAt == null -> "You can try again tomorrow."
    retryAt <= now -> "You can try again in a moment."
    else -> "You can try again ${retryWhen(retryAt, now)}."
}

/**
 * A decline by the server's reason code (guide: "Reason codes"): what went wrong, in a title; what to do about it
 * [now], when another try is offered; and the same advice for [later], when today's tries are used up.
 */
private class DeclineWords(val title: String, val now: String, val later: String)

private fun declineWords(reason: String?): DeclineWords = when (reason) {
    KycReasons.Document -> DeclineWords(
        "We couldn't verify your ID", "Try again with a clear photo of a valid ID, with all four corners showing.",
        "Next time, use a clear photo of a valid ID, with all four corners showing.",
    )
    KycReasons.DocumentExpired -> DeclineWords("Your ID has expired", "Verify again with an ID that is still valid.", "Next time, use an ID that is still valid.")
    KycReasons.DocumentUnreadable -> DeclineWords(
        "We couldn't read your ID", "Its details didn't come through clearly. Try again with your national ID or passport.",
        "Its details didn't come through clearly. Next time, use your national ID or passport.",
    )
    KycReasons.Liveness -> DeclineWords("The selfie check didn't pass", "Try again in good light, with your face uncovered.", "Next time, be in good light, with your face uncovered.")
    KycReasons.FaceMatch -> DeclineWords(
        "Your selfie didn't match your ID", "Try again in good light, facing the camera, with your own ID.",
        "Next time, be in good light, facing the camera, with your own ID.",
    )
    KycReasons.SessionExpired -> DeclineWords("Your verification ran out of time", "Start again when you're ready. It takes a few minutes.", "It closed before it was finished.")
    // `declined`, and any reason a newer server sends while another try is still offered.
    else -> DeclineWords("We couldn't verify you this time", "Something in the check didn't pass. Please try again.", "Something in the check didn't pass.")
}

private const val TriesUsed = "Today's tries are used up."

/** A decline another try can't fix (guide: `not_approved`, `identity_in_use`, `blocked`; an unknown reason reads as the first). */
private fun finalWords(reason: String?, entry: VerifyEntry): Pair<String, String> = when (reason) {
    KycReasons.IdentityInUse -> "This ID is already in use" to "It is verified on another Circaso account. Sign in to that account, or contact Support."
    KycReasons.Blocked -> (if (entry == VerifyEntry.CashOut) CashOutBarredTitle else BarredTitle) to BarredMessage
    else -> "We couldn't verify your identity" to "Trying again won't change this. Contact Support and we'll help you from here."
}

internal const val BarredTitle = "You can't add money on Circaso"
internal const val BarredMessage = "Contact Support if you think this is a mistake."
internal const val CashOutBarredTitle = "You can't cash out on Circaso"

/**
 * The check's short story once everything was sent: sent, being decided ([active] says by whom), ready. Only the
 * marks move as it lands; the last step keeps its words.
 */
private fun story(active: String?, entry: VerifyEntry) = listOf(
    OrderStage("ID and selfie sent", StageMark.Done),
    OrderStage(active ?: "Details checked", if (active == null) StageMark.Done else StageMark.Active),
    OrderStage("Ready to ${entry.goal()}", if (active == null) StageMark.Done else StageMark.Waiting),
)

/**
 * What the verify pages say for where the check stands. Built from the server's `verification` and from what this
 * phone was last told when it tried to start (never from a guess): one state, its words, and the one thing to do.
 */
internal fun verifyLook(verify: VerifyUi, now: Long = nowMillis(), entry: VerifyEntry = VerifyEntry.AddMoney): VerifyLook {
    // The button that only leaves: a cross's "Close" in Add Money, "Done" on the profile's pushed screen.
    val look = stateLook(verify, now, entry)
    return if (entry == VerifyEntry.Profile && look.action == VerifyAction.Close) look.copy(button = "Done") else look
}

private fun stateLook(verify: VerifyUi, now: Long, entry: VerifyEntry): VerifyLook {
    val v = verify.verification ?: RampVerification(KycStatus.None, required = true, canStart = true)
    fun waiting(title: String, message: String, retryAt: Long?) = VerifyLook(
        VerifyKind.Waiting, CircasoIcon.Clock, SheetTone.Neutral, title, "$message ${tryAgain(retryAt, now)}", VerifyAction.Close, "Close",
        note = "Nothing is needed from you until then.",
    )
    // Everything was sent and the decision is awaited. The promise is only made while it is likely to hold.
    fun checking() = VerifyLook(
        VerifyKind.Checking, null, SheetTone.Neutral, "We're checking your details",
        if (verify.slow) "This is taking longer than usual. Nothing more is needed from you." else "This usually takes less than a minute. You don't need to do anything.",
        VerifyAction.Close, "Close", stages = story("Result on its way", entry), waiting = true, note = "You can leave this screen. We'll tell you when it's done.",
    )
    return when {
        verify.verified || (v.status == KycStatus.Verified && (v.required || entry == VerifyEntry.Profile)) -> if (entry == VerifyEntry.Profile) VerifyLook(
            // From the profile it stays: nothing follows by itself, and the screen says what being verified means.
            VerifyKind.Verified, CircasoIcon.Check, SheetTone.Neutral, "You're verified", "Your identity is confirmed.", VerifyAction.None, "", stages = story(null, entry),
        ) else VerifyLook(
            VerifyKind.Verified, CircasoIcon.Check, SheetTone.Neutral, "You're verified", "That's it. You can ${entry.goal()} any time now.",
            VerifyAction.None, "", stages = story(null, entry), note = if (entry == VerifyEntry.CashOut) "Taking you to Cash Out." else "Taking you to Add Money.",
        )
        // `submitted`, read before `canStart` and before anything this phone was told when it last tried: the person
        // has finished and the server is waiting on the decision. Nothing is offered, whatever else is set.
        v.status == KycStatus.Pending && v.submitted -> checking()
        verify.trouble == VerifyTrouble.CameraDenied -> VerifyLook(
            VerifyKind.CameraDenied, CircasoIcon.Camera, SheetTone.Neutral, "Circaso can't use your camera",
            "Verifying needs the camera, for your ID and your selfie. Turn Camera on for Circaso in Settings, then come back and continue.",
            VerifyAction.Settings, "Open Settings",
        )
        verify.trouble == VerifyTrouble.ProviderDown -> VerifyLook(
            VerifyKind.ProviderDown, CircasoIcon.Warning, SheetTone.Warning, "We couldn't start your verification",
            "This is on our side, and nothing is lost: it didn't use one of your tries. Try again in a moment.",
            VerifyAction.Start, "Try Again",
        )
        verify.trouble == VerifyTrouble.NotConfigured -> VerifyLook(
            VerifyKind.NotConfigured, CircasoIcon.Clock, SheetTone.Neutral, "Identity verification isn't available yet",
            "We're still setting it up on our side. Nothing is needed from you and nothing is lost. Please try again later.",
            VerifyAction.Close, "Close",
        )
        verify.trouble == VerifyTrouble.TriesUsedUp -> VerifyLook(
            VerifyKind.TriesUsedUp, CircasoIcon.Clock, SheetTone.Neutral, "You've tried a few times today", tryAgain(verify.retryAt ?: v.retryAt, now),
            VerifyAction.Close, "Close", note = "Nothing is needed from you until then.",
        )
        // Didit's last screen just closed and the server hasn't caught up yet: the same wait.
        verify.checking -> checking()
        // Back from the hosted page: nothing is said to be sent, because it isn't known.
        verify.returned -> VerifyLook(
            VerifyKind.Returned, null, SheetTone.Neutral, "Checking where your verification stands", "If you finished, the result shows here in a moment.",
            VerifyAction.Close, "Close", waiting = true, note = "You can leave this screen.",
        )
        else -> when (v.status) {
            KycStatus.None -> when {
                v.canStart -> VerifyLook(
                    VerifyKind.Intro, null, SheetTone.Neutral, "Verify once, ${entry.goal()} any time",
                    // Where the server doesn't ask for it yet (offered from the profile), never said to be the law.
                    if (v.required) "Sending and receiving on Circaso never needs this. Moving money in or out of Circaso does, by law."
                    else "Sending and receiving on Circaso never needs this. ${if (entry == VerifyEntry.CashOut) "Cashing out" else "Adding money"} doesn't need it yet either: verify now and it's done when it does.",
                    VerifyAction.Start, "Start Verification",
                )
                // Required, never started, and the server won't start one now: said as it is, never a dead button.
                v.retryAt != null -> waiting("Today's tries are used up", "Verification can't be started again today.", v.retryAt)
                else -> VerifyLook(
                    VerifyKind.NotConfigured, CircasoIcon.Clock, SheetTone.Neutral, "Verification can't be started right now",
                    "Nothing is needed from you and nothing is lost. Please try again later.", VerifyAction.Close, "Close",
                )
            }
            KycStatus.InReview -> VerifyLook(
                VerifyKind.InReview, CircasoIcon.IdCard, SheetTone.Neutral, "We're taking a closer look",
                "A reviewer is going over your details. This usually takes a few minutes, and we'll notify you as soon as it's done.",
                VerifyAction.Close, "Close", stages = story("With a reviewer", entry), waiting = true, note = "You can leave this screen. Nothing more is needed from you.",
            )
            KycStatus.Declined -> when {
                v.canStart -> declineWords(v.reason).let { VerifyLook(VerifyKind.Declined, CircasoIcon.Warning, SheetTone.Warning, it.title, it.now, VerifyAction.Start, "Try Again") }
                v.retryAt != null -> declineWords(v.reason).let { waiting(it.title, "${it.later} $TriesUsed", v.retryAt) }
                else -> finalWords(v.reason, entry).let { (title, message) -> VerifyLook(VerifyKind.DeclinedFinal, CircasoIcon.Lock, SheetTone.Warning, title, message, VerifyAction.Support, "Contact Support") }
            }
            KycStatus.Expired -> {
                val words = if (v.reason == KycReasons.DocumentExpired) DeclineWords("Your ID has expired", "The ID you verified with is no longer valid. Verify again with one that is.", "The ID you verified with is no longer valid.")
                else declineWords(KycReasons.SessionExpired)
                when {
                    v.canStart -> VerifyLook(VerifyKind.Expired, CircasoIcon.Clock, SheetTone.Warning, words.title, words.now, VerifyAction.Start, "Verify Again")
                    v.retryAt != null -> waiting(words.title, "${words.later} $TriesUsed", v.retryAt)
                    // Out of time with no way to start again and no time given: its own title, and Support.
                    else -> VerifyLook(VerifyKind.DeclinedFinal, CircasoIcon.Lock, SheetTone.Warning, words.title, "Contact Support and we'll help you start again.", VerifyAction.Support, "Contact Support")
                }
            }
            // An open check, or a status a newer server sends: `canStart` alone says whether there is something to do.
            KycStatus.Pending, KycStatus.Unknown, KycStatus.Verified -> when {
                !v.canStart && v.status == KycStatus.Unknown -> VerifyLook(
                    VerifyKind.InReview, CircasoIcon.IdCard, SheetTone.Neutral, "We're working on your verification", "There's nothing you need to do right now. We'll notify you when there is news.",
                    VerifyAction.Close, "Close", waiting = true, note = "You can leave this screen.",
                )
                !v.canStart -> waiting("Today's tries are used up", "Your verification isn't finished yet, and it can't be continued today.", v.retryAt)
                v.reason == KycReasons.ResubmissionRequested -> VerifyLook(
                    VerifyKind.OneMoreThing, CircasoIcon.IdCard, SheetTone.Neutral, "We need one more thing", "A reviewer asked for one step again to finish verifying you. The rest is already done.",
                    VerifyAction.Start, "Continue",
                )
                else -> VerifyLook(
                    VerifyKind.Continue, CircasoIcon.IdCard, SheetTone.Neutral, "Finish verifying your identity", "You've already started. Pick up where you left off: it takes a few minutes.",
                    VerifyAction.Start, "Continue",
                )
            }
        }
    }
}

internal const val VerifyOfflineNote = "You're offline. Verifying your identity needs a connection."
internal const val TestnetPracticeNote = "On Testnet this is a practice run with sample documents. It doesn't count on Mainnet."

/**
 * Design E3, "Verify once, add money any time": what is needed (an ID and a selfie), how long it takes, who runs the
 * check, and one button. The button asks the server for the check (R0) and opens Didit's screens; it is busy from the
 * tap until they are up. The frame's "About 3 minutes / Most checks finish in under 10 minutes" says only what Circaso
 * can stand behind ("A few minutes, once"; never "you won't be asked again": an ID can expire, and each network has
 * its own check), and its "verification partner" is named, as the guide's consent note asks.
 */
@Composable
internal fun VerifyIntroPage(state: VerifyPageState, bottomInset: Dp, onVerify: () -> Unit, onBack: () -> Unit, onCopy: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val look = verifyLook(state.verify, entry = state.entry)
    val busy = state.verify.starting
    AddMoneyPageFrame(
        bottomInset, grouped = false, side = CircasoSpacing.s6,
        footer = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // Busy keeps its colours and its place; offline it waits, and says why before anyone taps it.
                CircasoButton(look.button, onVerify, size = ButtonSize.Large, block = true, enabled = state.canVerify || busy, busy = busy)
                // Each note opens the room it needs, gap included, so the buttons never step.
                Reveal(!state.online) { FooterNote(VerifyOfflineNote, Modifier.padding(top = CircasoSpacing.s2)) }
                Reveal(state.online && state.testnet) { FooterNote(TestnetPracticeNote, Modifier.padding(top = CircasoSpacing.s2)) }
                if (state.wayOut) CircasoButton("Not Now", onBack, Modifier.padding(top = CircasoSpacing.s2), style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true, enabled = !busy)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            BasicText(look.title, Modifier.semantics { heading() }, style = t.title1.copy(color = c.ink))
            BasicText(look.message, style = t.body.copy(color = c.inkSecondary))
        }
        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5)) {
            VerifyRow(CircasoIcon.IdCard, "Your national ID or passport", "A photo of the front and back")
            VerifyRow(CircasoIcon.FaceScan, "A quick selfie", "To check it is really you holding it")
            VerifyRow(CircasoIcon.Clock, "A few minutes, once", "Then you can ${state.entry.goal()} any time")
        }
        Column {
            Row(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.fill).padding(horizontal = CircasoSpacing.s4, vertical = VerifyNoteInset), horizontalArrangement = Arrangement.spacedBy(VerifyNoteGap)) {
                CircasoIconImage(CircasoIcon.Lock, c.inkSecondary, Modifier.padding(top = 1.dp).size(VerifyNoteGlyph), strokeWidth = 1.8f)
                BasicText("Didit, our verification partner, runs this check. Your documents go to them encrypted and are never shown to other people.", style = t.subhead.copy(color = c.inkSecondary))
            }
            FailureNote(state.verify.failure, onCopy)
        }
    }
}

// E3's own measures where the scale has no step: the row's 14 px gap, the note's 14 px inset, 10 px gap and 18 px lock.
// Its sections are 28 px apart; the scale has 24 and 32, and the flow's pages share one 20 dp rhythm, which E3 keeps.
private val VerifyRowGap = 14.dp
private val VerifyNoteInset = 14.dp
private val VerifyNoteGap = 10.dp
private val VerifyNoteGlyph = 18.dp
internal val VerifyRowGlyph = 20.dp

@Composable
private fun VerifyRow(icon: CircasoIcon, title: String, sub: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(VerifyRowGap)) {
        Box(Modifier.size(CircasoSizes.avatarMd).clip(CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
            CircasoIconImage(icon, c.ink, Modifier.size(VerifyRowGlyph), strokeWidth = 1.8f)
        }
        Column {
            BasicText(title, style = t.headline.copy(color = c.ink))
            BasicText(sub, style = t.subhead.copy(color = c.inkSecondary))
        }
    }
}

/** What the buttons under a verify state are for: it only changes when the kind of action does, so they never flicker. */
private data class VerifyFooter(val action: VerifyAction, val button: String)

/**
 * Every state of the check after E3: continuing, waiting for the result, in review, verified, declined (with what to
 * do about it), out of time, out of tries, a fault on our side, the camera refused. One layout for all of them, the
 * one an order's status uses, so moving between states is a change of words and of the sign above them, never a jump:
 * the badge keeps its place, the title and paragraph cross-fade, the short story of the check ticks over in place,
 * and "You're verified" is felt as well as seen before the flow carries on by itself.
 */
@Composable
internal fun VerifyStatePage(
    state: VerifyPageState, bottomInset: Dp, onVerify: () -> Unit, onSettings: () -> Unit, onSupport: () -> Unit, onBack: () -> Unit, onCopy: (String) -> Unit,
    /** Said under the state when it is the phone's last known copy, not something the server just said (the profile, offline). */
    lastChecked: String? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val still = CircasoTheme.reduceMotion
    val verify = state.verify
    val look = verifyLook(verify, entry = state.entry)
    val busy = verify.starting
    // Verified in front of the person: felt once, as it happens.
    val haptics = LocalHapticFeedback.current
    var felt by remember { mutableStateOf(false) }
    LaunchedEffect(verify.verified) {
        if (verify.verified && !felt) { felt = true; haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    }
    AddMoneyPageFrame(
        bottomInset,
        footer = {
            AnimatedContent(VerifyFooter(look.action, look.button), transitionSpec = { easeWords(still) }, label = "verify actions") { footer ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (footer.action) {
                        VerifyAction.Start -> {
                            CircasoButton(footer.button, onVerify, size = ButtonSize.Large, block = true, enabled = state.canVerify || busy, busy = busy)
                            Reveal(!state.online) { FooterNote(VerifyOfflineNote, Modifier.padding(top = CircasoSpacing.s2)) }
                            if (state.wayOut) CircasoButton("Not Now", onBack, Modifier.padding(top = CircasoSpacing.s2), style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true, enabled = !busy)
                        }
                        VerifyAction.Settings -> {
                            CircasoButton(footer.button, onSettings, size = ButtonSize.Large, block = true)
                            if (state.wayOut) CircasoButton("Not Now", onBack, Modifier.padding(top = CircasoSpacing.s2), style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
                        }
                        VerifyAction.Support -> {
                            CircasoButton(footer.button, onSupport, size = ButtonSize.Large, block = true)
                            if (state.wayOut) CircasoButton("Close", onBack, Modifier.padding(top = CircasoSpacing.s2), style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
                        }
                        VerifyAction.Close -> {
                            CircasoButton(footer.button, onBack, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
                            // Under the button, as on every page; its words change with the connection, and the footer makes room.
                            val note = when {
                                look.waiting && !state.online -> "You're offline. This updates by itself when you're back."
                                look.waiting && verify.stale -> "Couldn't check just now. Still trying."
                                else -> look.note
                            }
                            AnimatedContent(note, transitionSpec = { easeWords(still) }, label = "verify note", modifier = Modifier.padding(top = CircasoSpacing.s2)) { words ->
                                if (words != null) FooterNote(words)
                            }
                        }
                        // The flow carries on by itself; the line holds the buttons' place so the page doesn't jump.
                        VerifyAction.None -> look.note?.let { FooterNote(it, Modifier.padding(vertical = CircasoSpacing.s4)) }
                    }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally) {
            // The sign keeps its place while the words under it change; only a different sign settles in anew.
            AnimatedContent(
                Triple(look.icon, look.tone, look.waiting),
                transitionSpec = { fadeIn(tween(if (still) 0 else CircasoMotion.BaseMs)) togetherWith fadeOut(tween(if (still) 0 else CircasoMotion.QuickMs)) },
                contentAlignment = Alignment.Center, label = "verify badge",
            ) { (icon, tone, waiting) ->
                if (waiting || icon == null) WaitingBadge(icon) else SheetBadge(icon, tone)
            }
            // Announced as it changes: the result may arrive while the person is looking elsewhere.
            AnimatedContent(
                look.kind to look, contentKey = { Triple(it.first, it.second.title, it.second.message) }, transitionSpec = { easeWords(still) }, label = "verify state",
                // One region that lasts, so a screen reader hears the words change inside it.
                modifier = Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4).semantics { liveRegion = LiveRegionMode.Polite },
            ) { (_, l) ->
                // Only the current words speak: the ones on their way out are silent, so nothing is read twice.
                val leaving = l.title != look.title || l.message != look.message
                Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s2).then(if (leaving) Modifier.clearAndSetSemantics { } else Modifier), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(VerifyTitleGap)) {
                    BasicText(l.title, Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
                    BasicText(l.message, style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                }
            }
        }
        Column {
            val shown = lastOf(look.stages.takeIf { it.isNotEmpty() })
            Reveal(look.stages.isNotEmpty()) { if (shown != null) Stages(shown) }
            // A fault on our side carries what Support needs to find it.
            val reference = lastOf(verify.reference)
            Reveal(verify.reference != null && verify.trouble != null) { if (reference != null) Reference(reference, onCopy) }
            FailureNote(verify.failure, onCopy)
            val checked = lastOf(lastChecked)
            Reveal(lastChecked != null) { if (checked != null) FooterNote(checked, Modifier.padding(top = CircasoSpacing.s4)) }
        }
    }
}

/** Words giving way to other words: a cross-fade, while what holds them eases to the new height. */
internal fun AnimatedContentTransitionScope<*>.easeWords(still: Boolean): ContentTransform =
    (fadeIn(tween(if (still) 0 else CircasoMotion.BaseMs)) togetherWith fadeOut(tween(if (still) 0 else CircasoMotion.QuickMs))) using SizeTransform(clip = false)

/** Between a title and its paragraph, as the sheets have it (the canvas's 6 px). */
internal val VerifyTitleGap = 6.dp
