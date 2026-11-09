package com.circaso.core.platform

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A newer Circaso on Google Play, while the app is in use (Pasaka, 2026-11-08/09): Android's MainActivity checks Play at
 * launch, on every return to the app and every 10 minutes, and reports here. The bar at the top says what's happening:
 * "A new version is available · Update" (a tap downloads it in the background), "Downloading update… 45%", then
 * "A new version is ready · Restart". On iOS nothing reports, so nothing shows (the App Store updates apps itself).
 */
object AppUpdates {
    sealed interface Status {
        data object None : Status
        /** On Play, not downloaded; [start] downloads it in the background (flexible update). */
        data class Available(val start: () -> Unit) : Status
        /** Downloading; [percent] 0..100, or null before Play says how big it is. */
        data class Downloading(val percent: Int?) : Status
        /** On the phone; [install] restarts Circaso into it. */
        data class Ready(val install: () -> Unit) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.None)
    val status: StateFlow<Status> = _status
    /** Hidden by "Later" until something new happens (a different status kind). */
    private val _dismissed = MutableStateFlow(false)
    val dismissed: StateFlow<Boolean> = _dismissed

    fun report(status: Status) {
        val old = _status.value
        if (old::class != status::class) _dismissed.value = false
        _status.value = status
    }

    fun later() {
        _dismissed.value = true
    }
}

/** The quiet bar at the top of the window while a downloaded update waits for a restart. */
@Composable
fun BoxScope.AppUpdateBar() {
    val status by AppUpdates.status.collectAsState()
    val dismissed by AppUpdates.dismissed.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val shown = status !is AppUpdates.Status.None && !dismissed
    AnimatedVisibility(
        visible = shown,
        modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = CircasoSpacing.s2, start = CircasoSpacing.s4, end = CircasoSpacing.s4),
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Row(
            Modifier.widthIn(max = 440.dp).shadow(8.dp, CircasoShapes.pill).clip(CircasoShapes.pill).background(c.surfaceRaised)
                .padding(start = CircasoSpacing.s4, end = CircasoSpacing.s1, top = CircasoSpacing.s1, bottom = CircasoSpacing.s1)
                .heightIn(min = 40.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
        ) {
            val text = when (val s = status) {
                is AppUpdates.Status.Available -> "A new version of Circaso is available"
                is AppUpdates.Status.Downloading -> s.percent?.let { "Downloading update… $it%" } ?: "Downloading update…"
                is AppUpdates.Status.Ready -> "A new version of Circaso is ready"
                AppUpdates.Status.None -> ""
            }
            BasicText(text, Modifier.weight(1f, fill = false), style = t.subhead.copy(color = c.ink, fontFeatureSettings = "tnum"), maxLines = 2)
            when (val s = status) {
                is AppUpdates.Status.Available -> {
                    CircasoButton("Later", AppUpdates::later, style = com.circaso.designsystem.components.ButtonStyle.Plain, size = ButtonSize.Small)
                    CircasoButton("Update", s.start, size = ButtonSize.Small)
                }
                is AppUpdates.Status.Ready -> {
                    CircasoButton("Later", AppUpdates::later, style = com.circaso.designsystem.components.ButtonStyle.Plain, size = ButtonSize.Small)
                    CircasoButton("Restart", s.install, size = ButtonSize.Small)
                }
                is AppUpdates.Status.Downloading -> CircasoButton("Hide", AppUpdates::later, style = com.circaso.designsystem.components.ButtonStyle.Plain, size = ButtonSize.Small)
                AppUpdates.Status.None -> Unit
            }
        }
    }
}
