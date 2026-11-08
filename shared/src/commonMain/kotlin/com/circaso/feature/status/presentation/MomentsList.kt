package com.circaso.feature.status.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.platform.localDayIndex
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.status.domain.MomentRing

/** What the Moments list in Chats can open. */
class MomentsActions(
    val onOpenRing: (posterId: String) -> Unit,
    val onOpenMine: () -> Unit,
    val onCompose: () -> Unit,
    val onFindPeople: () -> Unit,
    val onRetry: () -> Unit = {},
    val onRetryPost: (String) -> Unit = {},
    val onDeletePost: (String) -> Unit = {},
)

/** The Chats banner while the Moments chip is picked (S1e): what is on screen is what the phone has. */
const val MomentsOfflineLine = "Showing moments saved on this phone"

/** How a moment's ring is drawn: unseen (the action colour), seen (quiet), or none. */
enum class RingStyle { Unseen, Seen, None }

/**
 * The Moments chip's list (decided 2026-11-05, WhatsApp's Updates): "My moment" first, then Recent (not seen yet),
 * Viewed, and Muted folded away. It lives in the Chats list itself, so picking the chip swaps rows without leaving the
 * screen. [query] is the Chats search: it narrows the people by name.
 */
fun LazyListScope.momentsList(
    state: MomentsUiState,
    query: String,
    mutedOpen: Boolean,
    onToggleMuted: () -> Unit,
    actions: MomentsActions,
    now: Long,
    /** Each row's modifier, from its item scope: Chats passes `animateItem` so rows fade in and out as chips change. */
    rowModifier: @Composable LazyItemScope.() -> Modifier = { Modifier },
) {
    val q = query.trim()
    val match = { r: MomentRing -> q.isEmpty() || r.name.contains(q, ignoreCase = true) }
    val recent = state.recent.filter(match)
    val viewed = state.viewed.filter(match)
    val muted = state.mutedRings.filter(match)
    if (q.isEmpty()) item(key = "moments-me", contentType = "me") { MyMomentRow(state, actions, now, rowModifier()) }
    // M6: a moment that won't post says so right under mine, with Retry or Delete; nothing is lost silently.
    state.failedPost?.let { failed -> if (q.isEmpty()) item(key = "moments-failed", contentType = "failed") { FailedPostRow(failed.error, { actions.onRetryPost(failed.localId) }, { actions.onDeletePost(failed.localId) }, rowModifier()) } }
    // S1f's quiet line sits under My moment, so with a long list on screen it still says the list may be old.
    if (state.failed) item(key = "moments-check-failed", contentType = "failed-check") { CheckFailed(state.requestId, state.refreshing, actions.onRetry, rowModifier()) }
    if (state.loading) {
        item(key = "moments-skeleton-label", contentType = "label") { SectionLabel("Recent", rowModifier()) }
        items(3, key = { "moments-skeleton-$it" }, contentType = { "skeleton" }) { i -> SkeletonRow(rowModifier(), announce = i == 0) }
        return
    }
    if (recent.isNotEmpty()) {
        item(key = "moments-recent", contentType = "label") { SectionLabel("Recent", rowModifier()) }
        items(recent, key = { "moment-" + it.posterId }, contentType = { if (it.allLocked) "locked" else "ring" }) { r ->
            if (r.allLocked) LockedRingRow(r, rowModifier(), online = state.online) else RingRow(r, RingStyle.Unseen, now, rowModifier()) { actions.onOpenRing(r.posterId) }
        }
    }
    if (viewed.isNotEmpty()) {
        item(key = "moments-viewed", contentType = "label") { SectionLabel("Viewed", rowModifier()) }
        items(viewed, key = { "moment-" + it.posterId }, contentType = { if (it.allLocked) "locked" else "ring" }) { r ->
            if (r.allLocked) LockedRingRow(r, rowModifier(), online = state.online) else RingRow(r, RingStyle.Seen, now, rowModifier()) { actions.onOpenRing(r.posterId) }
        }
    }
    if (muted.isNotEmpty()) {
        item(key = "moments-muted", contentType = "muted-head") { MutedHeader(muted.size, mutedOpen, onToggleMuted, rowModifier()) }
        if (mutedOpen) items(muted, key = { "moment-" + it.posterId }, contentType = { if (it.allLocked) "locked" else "ring" }) { r ->
            if (r.allLocked) LockedRingRow(r, rowModifier(), online = state.online, dimmed = true) else RingRow(r, RingStyle.Seen, now, rowModifier(), dimmed = true) { actions.onOpenRing(r.posterId) }
        }
    }
    when {
        q.isNotEmpty() && recent.isEmpty() && viewed.isEmpty() && muted.isEmpty() -> item(key = "moments-none-match", contentType = "empty") {
            EmptyLines("No moments match", "Try another name.", rowModifier())
        }
        // Nothing to show is never an error screen (Pasaka, 2026-11-03): the empty state stays, and a failed check is
        // one quiet line under it with Try Again and the reference for support.
        q.isEmpty() && ((state.failed && !state.checkFailedWithData) || state.empty) && state.rings.isEmpty() -> item(key = "moments-empty", contentType = "empty") {
            NoMoments(state.hasContacts, actions, offline = !state.online, modifier = rowModifier())
        }
    }
}

/** "Just now", "12 minutes ago", "Today, 10:42", "Yesterday, 21:05" (a moment is at most a day old). */
internal fun momentTime(at: Long, now: Long): String {
    val minutes = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "Just now"
        minutes == 1L -> "1 minute ago"
        minutes < 60 -> "$minutes minutes ago"
        localDayIndex(at) == localDayIndex(now) -> "Today, " + formatLocalTime(at, LocalTimeStyle.Clock)
        else -> "Yesterday, " + formatLocalTime(at, LocalTimeStyle.Clock)
    }
}

/**
 * A ring around an avatar, drawn just outside it so a ringed photo is the same size as every other one in its list. Unseen is the action colour, seen a quiet one.
 */
fun Modifier.momentRing(style: RingStyle, colors: com.circaso.designsystem.theme.CircasoColors): Modifier =
    when (style) {
        RingStyle.None -> this
        RingStyle.Unseen -> momentRing(listOf(false), colors)
        RingStyle.Seen -> momentRing(listOf(true), colors)
    }

/**
 * One arc per moment, oldest first, clockwise from the top (Pasaka, 2026-11-07, as WhatsApp's statuses): a moment not
 * watched yet is drawn in [CircasoColors.ink] (white in dark), a watched one in `fillStrong`. One moment is a whole
 * ring; the gaps narrow as moments add up so ten still read as a ring.
 */
fun Modifier.momentRing(seen: List<Boolean>, colors: com.circaso.designsystem.theme.CircasoColors): Modifier =
    if (seen.isEmpty()) this else drawBehind {
        val w = 2.dp.toPx()
        val gap = 2.5.dp.toPx()
        val r = size.minDimension / 2 + gap + w / 2
        val n = seen.size.coerceAtMost(MaxRingArcs)
        if (n == 1) {
            drawCircle(if (seen[0]) colors.fillStrong else colors.ink, radius = r, style = Stroke(w))
            return@drawBehind
        }
        // The visible gap between arcs (the round caps eat into it), narrower when there are many.
        val gapPx = (if (n <= 8) 3.dp.toPx() else 2.dp.toPx()) + w
        val gapDeg = (gapPx / r * (180f / kotlin.math.PI.toFloat())).coerceAtMost(360f / n * 0.5f)
        val step = 360f / n
        val topLeft = androidx.compose.ui.geometry.Offset(center.x - r, center.y - r)
        val box = androidx.compose.ui.geometry.Size(r * 2, r * 2)
        val stroke = Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        for (i in 0 until n) {
            // Past the cap, the last arc stands for the newest moments: unseen if any of them is.
            val watched = if (i == n - 1 && seen.size > n) seen.drop(n - 1).all { it } else seen[i]
            drawArc(
                if (watched) colors.fillStrong else colors.ink,
                startAngle = -90f + i * step + gapDeg / 2, sweepAngle = (step - gapDeg).coerceAtLeast(1f),
                useCenter = false, topLeft = topLeft, size = box, style = stroke,
            )
        }
    }

/** More than this and each arc would be a dot: the ring stops splitting. */
private const val MaxRingArcs = 30

/** A ring's arcs: each moment, oldest first, watched or not. */
fun MomentRing.arcs(): List<Boolean> = moments.sortedBy { it.postedAt }.map { it.viewed }

@Composable
private fun SectionLabel(text: String, modifier: Modifier) {
    BasicText(
        text,
        modifier.fillMaxWidth().padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s4, bottom = CircasoSpacing.s1).semantics { heading() },
        style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary, fontWeight = FontWeight.SemiBold),
    )
}

/**
 * The row frame every moments row shares: the chat row's inset. Departure (Pasaka, 2026-11-06): the photos are a step
 * larger than the chat list's ([MomentAvatar]), as in WhatsApp's Updates, so these rows are 8 dp taller.
 */
@Composable
private fun MomentRowFrame(
    modifier: Modifier,
    label: String,
    onClick: (() -> Unit)?,
    leading: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s2).clip(CircasoShapes.md)
            .then(if (onClick != null) Modifier.pressable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = CircasoSpacing.s2, vertical = CircasoSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        leading()
        Column(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun MyMomentRow(state: MomentsUiState, actions: MomentsActions, now: Long, modifier: Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val ring = state.myRing
    val sub = when (ring) {
        MyRing.Add -> "Tap to add a moment"
        MyRing.Seen -> {
            val views = state.myViews
            val seen = if (views == 0) "No views yet" else if (views == 1) "Seen by 1" else "Seen by $views"
            "$seen · ${state.myLatestAt?.let { momentTime(it, now) } ?: ""}".trimEnd(' ', '·')
        }
        MyRing.Posting -> state.postingProgress?.let { "Posting… ${(it * 100).toInt()}%" } ?: "Posting…"
        MyRing.Waiting -> "Waiting for a connection"
        MyRing.Failed -> "Not posted"
    }
    MomentRowFrame(
        modifier,
        label = "My moment, $sub",
        onClick = if (ring == MyRing.Seen) actions.onOpenMine else actions.onCompose,
        leading = {
            Box(Modifier.size(MomentAvatar)) {
                Avatar(state.myName.ifBlank { "Me" }, Modifier.momentRing(if (ring == MyRing.Seen) state.myMoments.sortedBy { it.postedAt }.map { true } else emptyList(), c), size = MomentAvatar, imageUrl = state.myAvatarUrl)
                when (ring) {
                    // The shared upload ring (MediaComposeUi), drawn just outside the avatar as the moment ring is.
                    MyRing.Posting -> com.circaso.feature.mediacompose.UploadRing(
                        state.postingProgress, Modifier.size(MomentAvatar), c.ink, track = c.fillStrong, stroke = 2.dp, outset = 2.5.dp, spinSweep = 220f,
                    )
                    MyRing.Waiting -> Badge(c.fillStrong) { CircasoIconImage(CircasoIcon.Clock, c.ink, Modifier.size(BadgeGlyph)) }
                    MyRing.Failed -> Badge(c.critical) { CircasoIconImage(CircasoIcon.Warning, c.bg, Modifier.size(BadgeGlyph)) }
                    // Its own target (Pasaka, 2026-11-03): the plus always opens the camera, even when the row opens my moments.
                    else -> Badge(c.action, onClick = actions.onCompose, label = "New moment") { CircasoIconImage(CircasoIcon.Plus, c.onAction, Modifier.size(BadgeGlyph)) }
                }
            }
        },
    ) {
        BasicText("My moment", style = t.body.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(sub, style = t.subhead.copy(color = if (ring == MyRing.Failed) c.critical else c.inkSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One person's row. Departure from S1e (recorded 2026-11-05): the frame greys a ring whose media isn't on the phone
 * yet; the phone doesn't track that per ring today (media loads in the viewer, which says V6 "needs a connection"),
 * so offline rows look as online ones do and the banner says what is shown.
 */
@Composable
private fun RingRow(ring: MomentRing, style: RingStyle, now: Long, modifier: Modifier, dimmed: Boolean = false, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val time = momentTime(ring.moments.maxOf { it.postedAt }, now)
    val count = ring.moments.size
    val what = when { style == RingStyle.Unseen -> "new moment"; else -> "seen" } + if (dimmed) ", muted" else ""
    // Some of theirs can't be opened on this phone yet (a new phone): said, so the count never looks short.
    val sub = if (ring.waiting > 0) "$time · ${ring.waiting} more on the way" else time
    MomentRowFrame(
        modifier,
        label = "${ring.name}, $what, $time" + (if (count > 1) ", $count moments" else "") + if (ring.waiting > 0) ", ${ring.waiting} more on the way" else "",
        onClick = onClick,
        leading = { Avatar(ring.name, Modifier.momentRing(ring.arcs(), c).alpha(if (dimmed) 0.5f else 1f), size = MomentAvatar, imageUrl = ring.avatarUrl) },
    ) {
        BasicText(ring.name, style = t.body.copy(color = if (dimmed) c.inkSecondary else c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(sub, style = t.subhead.copy(color = c.inkSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A person whose moments this phone can't open yet (decided 2026-11-08; no frame, the S1 row's own parts). A new
 * phone has new keys, so it asks the poster's phone for fresh copies: the row says whose phone it is waiting for, with
 * the clock badge My moment uses while waiting, instead of leaving the person out. Unavailable (shared from the
 * poster's earlier phone, so their phone answered without them) says so, with a lock. A tap opens the reason under
 * the row; there is nothing to view yet.
 */
@Composable
internal fun LockedRingRow(ring: MomentRing, modifier: Modifier, online: Boolean = true, dimmed: Boolean = false, initiallyOpen: Boolean = false) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    var open by rememberSaveable(ring.posterId) { mutableStateOf(initiallyOpen) }
    val first = ring.name.trim().substringBefore(' ').ifBlank { ring.name }
    val waiting = ring.waiting > 0
    val n = ring.locked.size
    // Offline, this phone is the reason (it may not have asked yet): said as My moment says it.
    val sub = when { waiting && !online -> "Waiting for a connection"; waiting -> "Waiting for $first’s phone"; else -> "Can’t be opened on this phone" }
    Column(modifier) {
        MomentRowFrame(
            Modifier.semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
            label = "${ring.name}, $sub" + if (dimmed) ", muted" else "",
            onClick = { open = !open },
            leading = {
                Box(Modifier.size(MomentAvatar).alpha(if (dimmed) 0.5f else 1f)) {
                    Avatar(ring.name, Modifier.momentRing(ring.locked.map { true }, c), size = MomentAvatar, imageUrl = ring.avatarUrl)
                    Badge(c.fillStrong) { CircasoIconImage(if (waiting) CircasoIcon.Clock else CircasoIcon.Lock, c.ink, Modifier.size(BadgeGlyph)) }
                }
            },
        ) {
            BasicText(ring.name, style = t.body.copy(color = if (dimmed) c.inkSecondary else c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(sub, style = t.subhead.copy(color = c.inkSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val reduce = CircasoTheme.reduceMotion
        AnimatedVisibility(
            open,
            enter = if (reduce) fadeIn(snap()) else expandVertically(tween(CircasoMotion.QuickMs)) + fadeIn(tween(CircasoMotion.QuickMs)),
            exit = if (reduce) fadeOut(snap()) else shrinkVertically(tween(CircasoMotion.QuickMs)) + fadeOut(tween(CircasoMotion.QuickMs)),
        ) {
            BasicText(
                when {
                    waiting && !online -> "Moments are end-to-end encrypted, so this phone needs a key from $first’s phone. It asks as soon as it’s online. You don’t need to do anything."
                    waiting -> "Moments are end-to-end encrypted, so this phone needs a key from $first’s phone. They’ll show here once $first’s phone sends it. You don’t need to do anything."
                    else -> "$first’s phone couldn’t send the key for ${if (n == 1) "this moment" else "these moments"}, so ${if (n == 1) "it" else "they"} can’t be opened on this phone. $first’s next moments will show as usual."
                },
                Modifier.fillMaxWidth().padding(start = CircasoSpacing.s4 + MomentAvatar + CircasoSpacing.s3, end = CircasoSpacing.s4, bottom = CircasoSpacing.s3),
                style = t.subhead.copy(color = c.inkSecondary),
            )
        }
    }
}

@Composable
private fun MutedHeader(count: Int, open: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val turn by animateFloatAsState(if (open) 180f else 0f, if (CircasoTheme.reduceMotion) snap() else tween(CircasoMotion.QuickMs), label = "muted")
    Row(
        modifier.fillMaxWidth().padding(top = CircasoSpacing.s3).padding(horizontal = CircasoSpacing.s2).clip(CircasoShapes.md)
            .pressable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = "Muted, $count ${if (count == 1) "person" else "people"}"
                stateDescription = if (open) "Expanded" else "Collapsed"
            }
            .padding(start = CircasoSpacing.s2, end = CircasoSpacing.s4).padding(vertical = CircasoSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("Muted", Modifier.weight(1f), style = t.footnote.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold))
        BasicText("$count", style = t.footnote.copy(color = c.inkSecondary))
        CircasoIconImage(CircasoIcon.ChevronDown, c.inkSecondary, Modifier.padding(start = CircasoSpacing.s1).size(16.dp).rotate(turn))
    }
}

@Composable
private fun FailedPostRow(error: String?, onRetry: () -> Unit, onDelete: () -> Unit, modifier: Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    // The message on top and the two buttons under it, so with large text the words keep the card's width.
    Column(
        modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1).fillMaxWidth().clip(CircasoShapes.md).background(c.surfaceRaised)
            .border(1.dp, c.separator, CircasoShapes.md).padding(start = CircasoSpacing.s4, end = CircasoSpacing.s2, top = CircasoSpacing.s3, bottom = CircasoSpacing.s1)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), modifier = Modifier.padding(end = CircasoSpacing.s2)) {
            CircasoIconImage(CircasoIcon.Warning, c.critical, Modifier.size(18.dp))
            BasicText(postError(error), Modifier.weight(1f), style = t.subhead.copy(color = c.ink))
        }
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
            CircasoButton("Retry", onRetry, style = ButtonStyle.Plain, size = ButtonSize.Small)
            CircasoButton("Delete", onDelete, style = ButtonStyle.Destructive, size = ButtonSize.Small)
        }
    }
}

private fun postError(code: String?) = when (code) {
    "status.audience_invalid" -> "None of your contacts can see moments yet, so it didn't post."
    "outbox.stale" -> "Your moment waited too long and didn't post."
    "status.media_invalid", "status.moment_too_large" -> "Your moment was too large to post. Try a shorter video."
    "status.too_many_moments" -> "You’ve shared a lot of moments today. Try again later."
    else -> "Your moment didn’t post."
}

/** Every row's photo in the Moments list. */
private val MomentAvatar = CircasoSizes.avatarMl

/**
 * My moment's badge (Pasaka, 2026-11-06; S1's .mo-add): a 22 dp coloured disc with a 2.5 dp edge in the page colour
 * outside it, its centre ON the avatar's circle at the lower right (45°), so it sits half over the photo and half
 * outside it, never wholly inside. The 44 dp touch box is aligned to the avatar's corner, so the disc's centre moves
 * out by r·cos45° − (avatar − 44) / 2. The box itself stays [BadgeTouchInset] further in, so the row's clip never cuts
 * the bottom of the target; the disc is moved back by the same amount inside it.
 */
private val BadgeDisc = 22.dp
private val BadgeEdge = 2.5.dp
private val BadgeOffset = MomentAvatar / 2 * (kotlin.math.sqrt(2f) / 2) - (MomentAvatar - CircasoSizes.controlMd) / 2
private val BadgeTouchInset = 3.dp
private val BadgeGlyph = 13.dp

@Composable
private fun BoxScope.Badge(color: Color, onClick: (() -> Unit)? = null, label: String? = null, content: @Composable BoxScope.() -> Unit) {
    val c = CircasoTheme.colors
    // Always the same 44 dp box at the same place, so the disc never shifts between states; tappable only for the +.
    Box(
        Modifier.align(Alignment.BottomEnd).offset(BadgeOffset - BadgeTouchInset, BadgeOffset - BadgeTouchInset).size(CircasoSizes.controlMd).clip(CircasoShapes.circle)
            .then(if (onClick != null) Modifier.pressable(role = Role.Button, onClick = onClick).semantics { label?.let { contentDescription = it } } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.offset(BadgeTouchInset, BadgeTouchInset).size(BadgeDisc + BadgeEdge * 2).background(c.bg, CircasoShapes.circle)
                .padding(BadgeEdge).clip(CircasoShapes.circle).background(color),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}


/** S1d: skeleton rows, only on the very first open before anything is stored or answered. */
@Composable
private fun SkeletonRow(modifier: Modifier, announce: Boolean) {
    val c = CircasoTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s2).padding(horizontal = CircasoSpacing.s2, vertical = CircasoSpacing.s3)
            // Said once for the three rows, not three times.
            .semantics { if (announce) contentDescription = "Loading moments" else hideFromAccessibility() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Box(Modifier.size(MomentAvatar).clip(CircasoShapes.circle).background(c.fill))
        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            Box(Modifier.size(120.dp, 12.dp).clip(CircasoShapes.xs).background(c.fill))
            Box(Modifier.size(72.dp, 10.dp).clip(CircasoShapes.xs).background(c.fill))
        }
    }
}

@Composable
private fun EmptyLines(title: String, line: String, modifier: Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s8, vertical = CircasoSpacing.s10), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        BasicText(title, style = t.title3.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(line, style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
}

/** S1c: nobody has posted; the line says who mine will reach. Offline (S1e) it says when they load. */
@Composable
private fun NoMoments(hasContacts: Boolean, actions: MomentsActions, offline: Boolean, modifier: Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s8).padding(top = CircasoSpacing.s8, bottom = CircasoSpacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        CircasoIconImage(CircasoIcon.Camera, c.inkTertiary, Modifier.size(CircasoSpacing.s10))
        BasicText(
            when { offline -> "Moments load when you’re back online"; hasContacts -> "No moments yet"; else -> "Find your people to see their moments" },
            Modifier.semantics { heading() },
            style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center),
        )
        BasicText(
            if (offline) "You can still share one now: it posts as soon as you’re connected."
            else "When your contacts share a moment, it shows here for 24 hours. Yours go to people who have your number saved, and whose number you have saved.",
            style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
        )
        if (hasContacts) CircasoButton("Share a Moment", actions.onCompose, icon = CircasoIcon.Camera)
        else CircasoButton("Find People", actions.onFindPeople)
    }
}

/** S1f, quiet form: the check for new moments failed; whatever is on screen stays as it is. */
@Composable
private fun CheckFailed(requestId: String?, busy: Boolean, onRetry: () -> Unit, modifier: Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(modifier.fillMaxWidth().padding(top = CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
            BasicText("Couldn’t check for new moments.", style = t.footnote.copy(color = c.inkSecondary))
            CircasoButton("Try Again", onRetry, style = ButtonStyle.Plain, size = ButtonSize.Small, busy = busy)
        }
        requestId?.let { BasicText("Ref $it", style = t.caption2.copy(color = c.inkTertiary)) }
    }
}
