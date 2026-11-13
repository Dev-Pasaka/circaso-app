package com.circaso.designsystem.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.theme.CircasoSpacing
import kotlinx.coroutines.delay

/**
 * What a toast says about itself (2026-11-13): only a confirmed success wears a check, so a toast that reports a
 * failure can never look like one went through. A message whose kind the screen doesn't know is [Info].
 */
enum class ToastKind { Done, Info, Problem }

/**
 * One toast at a time. [show] replaces the current one and hides it after its time on screen: about 3 seconds
 * (design G4), and longer for a problem, which people need time to read (WCAG 2.2.1).
 */
@Stable
class ToastState {
    var message by mutableStateOf<String?>(null)
        private set
    var kind by mutableStateOf(ToastKind.Info)
        private set
    private var token = 0

    suspend fun show(text: String, kind: ToastKind = ToastKind.Info, durationMs: Long = if (kind == ToastKind.Problem) 5_500 else 3_000) {
        val mine = ++token
        this.kind = kind
        message = text
        delay(durationMs)
        if (mine == token) message = null
    }

    /** Swiped away. */
    fun dismiss() {
        token++
        message = null
    }
}

@Composable
fun rememberToastState() = remember { ToastState() }

/**
 * The toast of the screen a tab sits in (Home): a tab shows its toasts through it, so they land where the screen's
 * scaffold places them (under the strip and any notice) rather than on the status bar. Null outside one.
 */
val LocalScreenToast = androidx.compose.runtime.staticCompositionLocalOf<ToastState?> { null }

/**
 * A floating notice for the current toast. In the scaffold's `toast` slot the scaffold places it, centred under the top
 * bar and the strip; pass no placement. Elsewhere (a full-screen viewer) [modifier] places it.
 */
@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    // Keeps the last words and kind on screen while it leaves, so the capsule doesn't empty out mid-exit.
    val last = remember { LastToast() }
    state.message?.let { last.text = it; last.kind = state.kind }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        FloatingNoticeHost(state.message != null) {
            FloatingNotice(
                last.text,
                when (last.kind) {
                    ToastKind.Done -> CircasoIcon.Check
                    ToastKind.Info -> CircasoIcon.Info
                    ToastKind.Problem -> CircasoIcon.Warning
                },
                tone = if (last.kind == ToastKind.Problem) BannerTone.Critical else BannerTone.Neutral,
                modifier = Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
                onDismiss = state::dismiss,
            )
        }
    }
}

private class LastToast {
    var text = ""
    var kind = ToastKind.Info
}
