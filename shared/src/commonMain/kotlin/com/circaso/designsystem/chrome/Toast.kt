package com.circaso.designsystem.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.CircasoIcon
import kotlinx.coroutines.delay

/** One toast at a time. [show] replaces the current one and hides it after [durationMs] (design: about 3 seconds). */
@Stable
class ToastState {
    var message by mutableStateOf<String?>(null)
        private set
    /** Done (a check) or a problem (a red alert): a toast that says something failed never wears a check. */
    var problem by mutableStateOf(false)
        private set
    private var token = 0

    suspend fun show(text: String, durationMs: Long = 3_000, problem: Boolean = false) {
        val mine = ++token
        this.problem = problem
        message = text
        delay(durationMs)
        if (mine == token) message = null
    }
}

@Composable
fun rememberToastState() = remember { ToastState() }

/** A floating glass pill with a tinted check, centred under the top bar. Put it in the scaffold's `toast` slot. */
@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    // Keeps the last words on screen while it leaves, so the pill doesn't empty out mid-exit.
    val last = remember { arrayOf("" to false) }
    state.message?.let { last[0] = it to state.problem }
    val (shown, problem) = last[0]
    Box(modifier.padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        FloatingNoticeHost(state.message != null) {
            FloatingNotice(
                shown,
                if (problem) CircasoIcon.Warning else CircasoIcon.Check,
                tone = if (problem) BannerTone.Critical else BannerTone.Neutral,
                compact = true,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
