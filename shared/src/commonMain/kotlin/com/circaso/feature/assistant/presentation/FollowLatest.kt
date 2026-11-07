package com.circaso.feature.assistant.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Alignment
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The follow-the-bottom rule for Asha's chat and voice transcripts, apart from Compose so it is tested on its own.
 * The list follows the newest line while the person is at (or within [NearLatestDp] of) the bottom. Scrolling up to read
 * stops it; scrolling back down, the jump button, or sending something new starts it again. Only the person's own
 * scrolling changes it: the list moving because a line was added never does.
 */
class FollowRule {
    var following: Boolean = true
        private set

    /** The person's scroll, while it moves and when it settles. Leaving the bottom stops following at once, so nothing pulls against their finger. */
    fun onUserScroll(moving: Boolean, nearLatest: Boolean) {
        if (!nearLatest) following = false
        else if (!moving) following = true
    }

    /** The jump button, or a new question sent. */
    fun resume() { following = true }

    companion object {
        /** How close to the bottom still counts as "at the latest". */
        const val NearLatestDp = 80

        /** For a list laid out from the bottom (`reverseLayout`): the newest item is index 0 and its offset is how far it is scrolled past. */
        fun nearLatest(firstVisibleIndex: Int, firstVisibleOffsetPx: Int, thresholdPx: Int): Boolean =
            firstVisibleIndex == 0 && firstVisibleOffsetPx <= thresholdPx
    }
}

/** [FollowRule] bound to a bottom-anchored [LazyListState]. */
class FollowLatest internal constructor(private val list: LazyListState, private val thresholdPx: Int) {
    private val rule = FollowRule()
    /** True while this class scrolls the list itself; any other scroll is the person's (finger, wheel, trackpad, TalkBack). */
    internal var programmatic = false
        private set

    internal suspend fun scrollToLatest(animate: Boolean) {
        programmatic = true
        try { if (animate) list.animateScrollToItem(0) else list.scrollToItem(0) } finally { programmatic = false }
    }
    var following by mutableStateOf(true)
        private set

    val nearLatest: Boolean get() = FollowRule.nearLatest(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, thresholdPx)

    internal fun onUserScroll(moving: Boolean, near: Boolean) { rule.onUserScroll(moving, near); following = rule.following }

    /** Back to the newest line and following again: animated, or a jump with reduced motion. */
    suspend fun jump(animate: Boolean) {
        rule.resume(); following = true
        scrollToLatest(animate)
    }
}

/**
 * Keeps a `reverseLayout` list on its newest line as lines arrive.
 *
 * A growing newest line (a streamed answer, the live caption) needs no scrolling at all: the list is laid out from the
 * bottom, so with the newest item at offset 0 it grows upwards and its last words stay in view, with no per-token
 * scroll to jank. Only a *new* line ([newest] changes: its key, or the count) needs a scroll, because a keyed list keeps
 * the previous line where it was; that is one animated scroll by the new line's height, and a newer line cancels it
 * and starts from where it got to, so it never snaps.
 */
@Composable
fun rememberFollowLatest(list: LazyListState, newest: () -> Any?): FollowLatest {
    val thresholdPx = with(LocalDensity.current) { FollowRule.NearLatestDp.dp.roundToPx() }
    val follow = remember(list, thresholdPx) { FollowLatest(list, thresholdPx) }
    val still by rememberUpdatedState(CircasoTheme.reduceMotion)
    val latest by rememberUpdatedState(newest)
    LaunchedEffect(follow) {
        // A scroll this class didn't start is the person's, whatever drove it (a finger, a wheel or trackpad, an
        // accessibility action). A line being added moves the list without any scroll in progress, so it never counts.
        var personScrolling = false
        snapshotFlow { list.isScrollInProgress to follow.nearLatest }.distinctUntilChanged().collect { (moving, near) ->
            if (moving && !follow.programmatic) personScrolling = true
            if (!personScrolling) return@collect
            follow.onUserScroll(moving, near)
            if (!moving) personScrolling = false
        }
    }
    LaunchedEffect(follow) {
        snapshotFlow { latest() }.distinctUntilChanged().collectLatest {
            if (follow.following && (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0)) {
                follow.scrollToLatest(!still)
            }
        }
    }
    return follow
}

/** The round "jump to the latest" button, as the chat thread has it. */
@Composable
fun JumpToLatest(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val ms = if (CircasoTheme.reduceMotion) 0 else CircasoMotion.QuickMs
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(tween(ms)) + scaleIn(tween(ms)), exit = fadeOut(tween(ms)) + scaleOut(tween(ms))) {
        // The look of the thread's button, inside a 44 dp touch area.
        Box(Modifier.sizeIn(minWidth = CircasoSizes.controlMd, minHeight = CircasoSizes.controlMd), contentAlignment = Alignment.Center) {
            Box(Modifier.shadow(CircasoSpacing.s2, CircasoShapes.circle).clip(CircasoShapes.circle).background(c.surface)) {
                CircasoIconButton(CircasoIcon.ArrowDown, "Jump to the latest", onClick)
            }
        }
    }
}
