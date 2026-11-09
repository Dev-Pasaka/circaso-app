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
 * A newer Circaso downloaded in the background (Google Play's flexible in-app update, Pasaka 2026-11-08): the app shows
 * "A new version is ready · Restart" until the person restarts or dismisses it. Android's MainActivity reports the
 * download and how to install it; on iOS nothing reports, so nothing shows (the App Store updates apps itself).
 */
object AppUpdates {
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    private var install: (() -> Unit)? = null

    /** The update is on the phone; [install] restarts Circaso into it. */
    fun downloaded(install: () -> Unit) {
        this.install = install
        _ready.value = true
    }

    fun restartNow() {
        _ready.value = false
        install?.invoke()
    }

    fun later() {
        _ready.value = false
    }
}

/** The quiet bar at the top of the window while a downloaded update waits for a restart. */
@Composable
fun BoxScope.AppUpdateBar() {
    val ready by AppUpdates.ready.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    AnimatedVisibility(
        visible = ready,
        modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = CircasoSpacing.s2, start = CircasoSpacing.s4, end = CircasoSpacing.s4),
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Row(
            Modifier.widthIn(max = 440.dp).shadow(8.dp, CircasoShapes.pill).clip(CircasoShapes.pill).background(c.surfaceRaised)
                .padding(start = CircasoSpacing.s4, end = CircasoSpacing.s1, top = CircasoSpacing.s1, bottom = CircasoSpacing.s1)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
        ) {
            BasicText("A new version of Circaso is ready", Modifier.weight(1f, fill = false), style = t.subhead.copy(color = c.ink), maxLines = 2)
            CircasoButton("Later", AppUpdates::later, style = com.circaso.designsystem.components.ButtonStyle.Plain, size = ButtonSize.Small)
            CircasoButton("Restart", AppUpdates::restartNow, size = ButtonSize.Small)
        }
    }
}
