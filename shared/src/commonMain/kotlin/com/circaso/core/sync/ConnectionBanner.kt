package com.circaso.core.sync

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import com.circaso.designsystem.theme.CircasoSpacing
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.circaso.core.platform.ConnectionStatus
import com.circaso.designsystem.chrome.FloatingNotice
import com.circaso.designsystem.chrome.FloatingNoticeHost
import com.circaso.designsystem.components.CircasoIcon
import org.koin.compose.koinInject

/**
 * The offline banner (designs G1 and G3b). Put it in the scaffold's `banner` slot: it floats under the top bar and
 * slides down over the content, so the screen below never jumps (Pasaka, 2026-11-13).
 * [showing] finishes the sentence on screens that show cached data ("Showing chats as of 21:05");
 * with [waitingCount] above zero it says how many messages are waiting instead.
 */
@Composable
fun ConnectionBanner(
    modifier: Modifier = Modifier,
    showing: String = "Showing what is saved on this phone",
    waitingCount: Int = 0,
) {
    val state: ConnectionState = koinInject()
    val status by state.status.collectAsState()
    val offline = status == ConnectionStatus.Offline
    // Offline can last minutes: after a few seconds the notice settles into a small "Offline" capsule so it stops
    // covering the top of the screen; a tap opens the full notice again for a few seconds.
    var expanded by remember { mutableStateOf(true) }
    LaunchedEffect(offline, expanded) {
        if (!offline) expanded = true
        else if (expanded) { delay(ExpandedMs); expanded = false }
    }
    FloatingNoticeHost(offline, modifier) {
        val icon = if (waitingCount > 0) CircasoIcon.Clock else CircasoIcon.Offline
        FloatingNotice(
            title = if (expanded) "You’re offline" else "Offline",
            detail = if (expanded) connectionBannerDetail(showing, waitingCount) else null,
            icon = icon,
            modifier = Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
            spoken = connectionBannerText(showing, waitingCount),
            onTap = { expanded = !expanded },
        )
    }
}

/** How long the full offline notice stays open before it settles into the small capsule. */
private const val ExpandedMs = 4_000L

/** The notice's second line: what is waiting, or what the screen is showing meanwhile. */
internal fun connectionBannerDetail(showing: String, waitingCount: Int): String = when {
    waitingCount == 1 -> "1 message waiting to send"
    waitingCount > 1 -> "$waitingCount messages waiting to send"
    else -> showing
}

internal fun connectionBannerText(showing: String, waitingCount: Int): String = when {
    waitingCount == 1 -> "You’re offline. 1 message waiting to send."
    waitingCount > 1 -> "You’re offline. $waitingCount messages waiting to send."
    else -> "You’re offline · $showing"
}
