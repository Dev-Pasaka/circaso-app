package com.circaso.feature.ramp.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.sync.nowMillis
import com.circaso.feature.ramp.domain.GetKycUseCase
import com.circaso.feature.ramp.domain.ObserveIdentityUseCase
import com.circaso.feature.ramp.domain.RampCodes
import com.circaso.feature.wallet.domain.WalletNetworks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.SkeletonBlock
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.ramp.domain.IdentitySnapshot
import com.circaso.feature.ramp.domain.KycReasons
import com.circaso.feature.ramp.domain.KycStatus

// The profile's Verification row (design F2: "Verification" with a status at its end). What it says is worked out
// here, apart from the drawing, so every status can be tested. It only ever says what the server last said.

/** Where the person's identity check stands, as the profile's row names it. */
enum class IdentityRowKind {
    Verified,
    /** Never started, and it can be. */
    NotVerified,
    /** Started and left unfinished. */
    InProgress,
    /** A reviewer asked for a step again. */
    OneMoreThing,
    /** Finished by the person; the decision is awaited (`submitted`). */
    Checking,
    InReview,
    /** Declined or out of time, and another try can fix it. */
    NeedsAnotherTry,
    /** Nothing can be started until a known time (today's tries are used up). */
    Waiting,
    /** Declined for good: Support is the way on. */
    CouldNotVerify,
    /** The server doesn't ask for verification on this network, and won't start one. */
    NotNeeded,
    /** A state with nothing to do and nothing to say but that it is in hand (a newer server's status). */
    InHand,
    /** Nothing is known: the phone has no copy and the server couldn't be asked. */
    Unknown,
}

/** How the status at the row's end is drawn: the canvas's `cx-badge` (a small sign and a word in the tone's colour). */
enum class IdentityBadgeTone { Neutral, Warning, Critical }

data class IdentityBadge(val text: String, val icon: CircasoIcon, val tone: IdentityBadgeTone)

data class IdentityRowLook(
    val kind: IdentityRowKind,
    /** The status in a short sentence, under the title. */
    val sub: String,
    /** A settled or waiting status, at the row's end. */
    val badge: IdentityBadge? = null,
    /** What opening the row leads to, when there is something to do: "Verify", "Continue", "Try again". */
    val action: String? = null,
    /** Shown from the phone's copy because the server can't be asked right now: the action waits. */
    val lastKnown: Boolean = false,
    /** Under the status when it is the last known one: why it may be old, and that verifying waits. */
    val note: String? = null,
)

/** The row on the profile: its first paint, not there at all, or a status. */
sealed interface IdentityRowUi {
    /** Nothing is known yet and the server is being asked: the row's shape, breathing. */
    data object Loading : IdentityRowUi
    /** Verification isn't open to this person on this server (`ramp.unavailable`): no row. */
    data object Hidden : IdentityRowUi
    data class Shown(val look: IdentityRowLook) : IdentityRowUi
}

/**
 * The row for where the check stands. [copy] is the phone's last known status for the network in use (kept fresh by
 * every answer and every `kyc.updated` frame); [asking] is true until the server has answered or failed to once in
 * this visit; [unanswered] when the last read got no answer. `submitted` is read before `canStart`, as everywhere.
 */
fun identityRow(copy: IdentitySnapshot?, asking: Boolean, unanswered: Boolean, online: Boolean, testnet: Boolean, now: Long = nowMillis()): IdentityRowUi {
    fun words(text: String) = if (testnet) "Testnet · $text" else text
    if (copy == null) return when {
        !online -> IdentityRowUi.Shown(IdentityRowLook(IdentityRowKind.Unknown, words("Shows when you're back online"), lastKnown = true))
        asking -> IdentityRowUi.Loading
        unanswered -> IdentityRowUi.Shown(IdentityRowLook(IdentityRowKind.Unknown, words("Couldn't check just now")))
        else -> IdentityRowUi.Loading
    }
    if (copy.unavailable) return IdentityRowUi.Hidden
    val v = copy.verification ?: return IdentityRowUi.Hidden
    val clock = IdentityBadgeTone.Warning
    val look = when {
        v.status == KycStatus.Verified -> IdentityRowLook(IdentityRowKind.Verified, "Your identity is confirmed", IdentityBadge("Verified", CircasoIcon.Check, IdentityBadgeTone.Neutral))
        // Read before `canStart`: finished by the person, the decision awaited.
        v.status == KycStatus.Pending && v.submitted -> IdentityRowLook(IdentityRowKind.Checking, "We're checking your details", IdentityBadge("Checking", CircasoIcon.Clock, clock))
        v.status == KycStatus.InReview -> IdentityRowLook(IdentityRowKind.InReview, "A reviewer is going over your details", IdentityBadge("In review", CircasoIcon.Clock, clock))
        v.status == KycStatus.None -> when {
            v.canStart -> IdentityRowLook(IdentityRowKind.NotVerified, if (v.required) "Needed to move money in or out of Circaso" else "Not verified", action = "Verify")
            !v.required -> IdentityRowLook(IdentityRowKind.NotNeeded, "Not needed right now")
            v.retryAt != null -> IdentityRowLook(IdentityRowKind.Waiting, triesUsed(v.retryAt, now))
            else -> IdentityRowLook(IdentityRowKind.InHand, "Can't be started right now")
        }
        v.status == KycStatus.Declined || v.status == KycStatus.Expired -> when {
            v.canStart -> IdentityRowLook(
                IdentityRowKind.NeedsAnotherTry,
                when {
                    v.reason == KycReasons.DocumentExpired -> "Your ID has expired"
                    v.status == KycStatus.Expired -> "The last check ran out of time"
                    else -> "The last check didn't pass"
                },
                // The screen's own words for its button.
                action = if (v.status == KycStatus.Expired) "Verify Again" else "Try Again",
            )
            v.retryAt != null -> IdentityRowLook(IdentityRowKind.Waiting, triesUsed(v.retryAt, now))
            else -> IdentityRowLook(IdentityRowKind.CouldNotVerify, "Contact Support for help", IdentityBadge("Couldn't verify", CircasoIcon.Warning, IdentityBadgeTone.Critical))
        }
        // An open check, or a status a newer server sends: `canStart` alone says whether there is something to do.
        !v.canStart && v.status == KycStatus.Unknown -> IdentityRowLook(IdentityRowKind.InHand, "We're working on your verification", IdentityBadge("In progress", CircasoIcon.Clock, clock))
        !v.canStart -> IdentityRowLook(IdentityRowKind.Waiting, triesUsed(v.retryAt, now))
        v.reason == KycReasons.ResubmissionRequested -> IdentityRowLook(IdentityRowKind.OneMoreThing, "One more step is needed", action = "Continue")
        else -> IdentityRowLook(IdentityRowKind.InProgress, "Started. Pick up where you left off", action = "Continue")
    }
    // The server can't be asked right now: the status still says what it was, a line under it says it is the last
    // known one, and verifying waits.
    val known = !online || unanswered
    val note = when {
        !known -> null
        look.action != null && !online -> "Last known status. Verifying needs a connection."
        !online -> "Last known status. Updates when you're back online."
        else -> "Last known status. Couldn't check just now."
    }
    return IdentityRowUi.Shown(look.copy(sub = words(look.sub), lastKnown = known, note = note))
}

/** "Today's tries are used up. You can try again tomorrow, after 09:05": the state named, then when it lifts. */
private fun triesUsed(retryAt: Long?, now: Long) = "Today's tries are used up. " + tryAgain(retryAt, now).removeSuffix(".")

/**
 * The profile's Verification row as it lives: the phone's last known status for the wallet network in use, and the
 * asking that keeps it true. **Read** (offline-first.md): the copy shows at once and offline, marked as last known;
 * `GET /v1/ramp/kyc`, a `kyc.updated` frame and a `ramp.kyc` push all land in that same copy, so the row follows by
 * itself. Each network has its own check: selecting another shows its copy and asks for it.
 */
class IdentityRowModel(
    observeIdentity: ObserveIdentityUseCase,
    /** Its answer is filed where [observeIdentity] reads. */
    private val readIdentity: GetKycUseCase,
    private val online: Flow<Boolean> = flowOf(true),
    private val networks: WalletNetworks = WalletNetworks.Single,
    private val now: () -> Long = ::nowMillis,
) {
    /** How asking the server is going, for the network in use. */
    private enum class Read {
        /** Being asked, with no answer yet in this visit. */
        Asking,
        /** The server answered (whatever it said). */
        Answered,
        /** The last ask got no answer (no connection, or a fault). */
        Unanswered,
    }

    private val read = MutableStateFlow(Read.Asking)
    private var ask: Job? = null
    private var scope: CoroutineScope? = null

    val ui: Flow<IdentityRowUi> = combine(observeIdentity(), read, online, networks.state) { copy, read, isOnline, all ->
        identityRow(copy, asking = read == Read.Asking, unanswered = read == Read.Unanswered, online = isOnline, testnet = all.testnet, now = now())
    }.distinctUntilChanged()

    /** For as long as the profile lives: the first ask, another network selected, the connection returning. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        // Another network selected: its own check is asked for (its copy shows meanwhile). The first value is the first ask.
        scope.launch { networks.selected.collect { ask?.cancel(); read.value = Read.Asking; refresh() } }
        // The connection is back: a status that couldn't be checked is checked now.
        scope.launch { online.distinctUntilChanged().drop(1).collect { back -> if (back) refresh() } }
    }

    /**
     * Asks the server where the check stands (the profile being opened, the app coming back to the front). Best
     * effort: what the phone knows is already on screen, and no answer only marks it as last known.
     */
    fun refresh() {
        val scope = scope ?: return
        if (ask?.isActive == true) return
        ask = scope.launch {
            val answer = readIdentity()
            // `ramp.unavailable` is an answer too (the row leaves); only no answer at all is "couldn't check".
            val answered = answer is AppResult.Success || ((answer as? AppResult.Failure)?.error as? AppError.Api)?.code == RampCodes.Unavailable
            read.value = if (answered) Read.Answered else Read.Unanswered
        }
    }
}

/** The row's name, as design F2 has it. */
const val IdentityRowTitle = "Verification"

/**
 * The profile's Verification row: a row like its neighbours (title, a sub line, the chevron), with the status at its
 * end as the canvas draws it (F2's `cx-badge`), or the one word for what opening it does. Its first paint is the row's
 * own shape, never a spinner. Whatever it shows, it opens the Verification screen. At large text sizes the status
 * moves under the words, so the title is never squeezed. Only what changes fades; the row eases to its new height.
 */
@Composable
fun IdentityRow(ui: IdentityRowUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val still = CircasoTheme.reduceMotion
    // The placeholder gives way to the row in place; after that, only the words inside it change.
    AnimatedContent(ui, modifier, contentKey = { it is IdentityRowUi.Shown }, transitionSpec = { easeWords(still) }, label = "identity row") { shown ->
        when (shown) {
            is IdentityRowUi.Shown -> IdentityRowShown(shown.look, onClick, still)
            IdentityRowUi.Loading -> IdentityRowSkeleton()
            IdentityRowUi.Hidden -> Unit
        }
    }
}

@Composable
private fun IdentityRowShown(look: IdentityRowLook, onClick: () -> Unit, still: Boolean) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    // Past the larger text sizes the end of the row would squeeze the title: the status goes under the words instead.
    val stacked = LocalDensity.current.fontScale >= StackedFontScale
    val end = look.badge to look.action
    ListRow(
        IdentityRowTitle, onClick = onClick, onClickLabel = "Open verification",
        below = {
            AnimatedContent(Triple(look.sub, look.note, if (stacked) end else null), transitionSpec = { easeWords(still) }, label = "identity status") { (sub, note, under) ->
                Column {
                    // The status: never cut, whatever the text size.
                    BasicText(sub, style = t.subhead.copy(color = c.inkSecondary))
                    note?.let { BasicText(it, style = t.footnote.copy(color = c.inkTertiary)) }
                    under?.let { RowEnd(it.first, it.second, look.lastKnown, Modifier.padding(top = CircasoSpacing.s1)) }
                }
            }
        },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
                if (!stacked) AnimatedContent(end, transitionSpec = { easeWords(still) }, label = "identity end") { (badge, action) -> RowEnd(badge, action, look.lastKnown) }
                RowChevron()
            }
        },
    )
}

/** The badge, or the word for what opening the row leads to; the word waits (quieter) while the server can't be reached. */
@Composable
private fun RowEnd(badge: IdentityBadge?, action: String?, waiting: Boolean, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    Box(modifier) {
        badge?.let { RowBadge(it) }
        action?.let { BasicText(it, style = CircasoTheme.typography.body.copy(color = if (waiting) c.inkTertiary else c.action)) }
    }
}

/** From here on the row's end goes under its words (the app's large text sizes start at 1.3×). */
private const val StackedFontScale = 1.3f

/** The canvas's `cx-badge` in a row (F2 "In review"): a 14 px sign and a 13/18 semibold word, in the tone's colour. */
@Composable
private fun RowBadge(badge: IdentityBadge) {
    val c = CircasoTheme.colors
    val ink = when (badge.tone) { IdentityBadgeTone.Neutral -> c.inkSecondary; IdentityBadgeTone.Warning -> c.warning; IdentityBadgeTone.Critical -> c.critical }
    Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1), verticalAlignment = Alignment.CenterVertically) {
        CircasoIconImage(badge.icon, ink, Modifier.size(CopyGlyph), strokeWidth = 2f)
        BasicText(badge.text, style = CircasoTheme.typography.footnote.copy(color = ink, fontWeight = FontWeight.SemiBold))
    }
}

/** The row before anything is known: a title and a sub line's worth of placeholder, as tall as the row it becomes. */
@Composable
private fun IdentityRowSkeleton() {
    val t = CircasoTheme.typography
    val density = LocalDensity.current
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3)
            .semantics(mergeDescendants = true) { contentDescription = "$IdentityRowTitle. Checking your status"; progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // Each bar is one line of its text at the person's size, centred in that line's height.
            with(density) {
                val title = t.body.fontSize.toDp()
                val sub = t.subhead.fontSize.toDp()
                SkeletonBlock(Modifier.padding(vertical = (t.body.lineHeight.toDp() - title) / 2).fillMaxWidth(0.34f).height(title))
                SkeletonBlock(Modifier.padding(vertical = (t.subhead.lineHeight.toDp() - sub) / 2).fillMaxWidth(0.62f).height(sub))
            }
        }
        // The row's end is already where it will be.
        RowChevron()
    }
}
