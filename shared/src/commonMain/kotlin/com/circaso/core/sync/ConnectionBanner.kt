package com.circaso.core.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.ConnectionStatus
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.components.CircasoIcon
import org.koin.compose.koinInject

/**
 * The offline banner (designs G1 and G3b). Put it in the scaffold's `banner` slot.
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
    AnimatedVisibility(
        visible = status == ConnectionStatus.Offline,
        modifier = modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Banner(
            text = connectionBannerText(showing, waitingCount),
            icon = if (waitingCount > 0) CircasoIcon.Clock else CircasoIcon.Offline,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
        )
    }
}

internal fun connectionBannerText(showing: String, waitingCount: Int): String = when {
    waitingCount == 1 -> "You’re offline. 1 message waiting to send."
    waitingCount > 1 -> "You’re offline. $waitingCount messages waiting to send."
    else -> "You’re offline · $showing"
}
