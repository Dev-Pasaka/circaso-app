package com.circaso.designsystem.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoTheme
import kotlinx.coroutines.delay

/** One toast at a time. [show] replaces the current one and hides it after [durationMs] (design: about 3 seconds). */
@Stable
class ToastState {
    var message by mutableStateOf<String?>(null)
        private set
    private var token = 0

    suspend fun show(text: String, durationMs: Long = 3_000) {
        val mine = ++token
        message = text
        delay(durationMs)
        if (mine == token) message = null
    }
}

@Composable
fun rememberToastState() = remember { ToastState() }

/** Glass pill with a check, centred under the top bar. Put it in the scaffold's `toast` slot. */
@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val text = state.message
    Box(modifier.padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = text != null,
            enter = slideInVertically { -it / 2 } + fadeIn(),
            exit = slideOutVertically { -it / 2 } + fadeOut(),
        ) {
            Row(
                Modifier
                    .glass(CircasoShapes.pill, floating = true)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircasoIconImage(CircasoIcon.Check, c.ink, Modifier.size(16.dp))
                BasicText(text.orEmpty(), style = CircasoTheme.typography.subhead.copy(color = c.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1)
            }
        }
    }
}
