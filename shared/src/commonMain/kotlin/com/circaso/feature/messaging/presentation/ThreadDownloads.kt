package com.circaso.feature.messaging.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.DarkColors
import com.circaso.feature.messaging.domain.Message

/**
 * How far each incoming file's download has got (0..1), by attachment id (MediaMessages.downloading), collected once by
 * the thread; each bubble or album tile reads only its own share, so a percent step redraws that one alone. The
 * download itself runs in the app's scope, so a bubble scrolled away and back finds it where it got to.
 */
internal val LocalDownloading = staticCompositionLocalOf<State<Map<String, Float>>?> { null }

/** This item's download progress while it comes in; null when it isn't downloading. */
@Composable
internal fun downloadProgress(m: Message.Media): Float? {
    val all = LocalDownloading.current ?: return null
    val id = m.details.attachmentId ?: return null
    val mine by remember(all, id) { derivedStateOf { all.value[id] } }
    return mine
}

/**
 * The play glyph's disc while a video or file comes in: the upload ring's language (M6) the other way, filling as the
 * bytes arrive, with an arrow down in the middle; before the first byte, the ring turns. Reads as "downloading 40%".
 */
@Composable
internal fun DownloadDisc(
    progress: Float?,
    size: Dp,
    modifier: Modifier = Modifier,
    ink: Color = DarkColors.ink,
    disc: Color = DarkColors.scrim,
) {
    Box(modifier.size(size).clip(CircasoShapes.circle).background(disc), contentAlignment = Alignment.Center) {
        com.circaso.feature.mediacompose.UploadRing(progress, Modifier.matchParentSize().padding(RingInset), ink = ink)
        CircasoIconImage(CircasoIcon.ArrowDown, ink, Modifier.size(size * 0.4f))
    }
}

private val RingInset = 3.dp

/** "Downloading, 40%" for a screen reader, on the bubble or tile that shows the ring. */
internal fun downloadLabel(progress: Float?): String = "Downloading" + (progress?.let { ", ${(it * 100).toInt()}%" } ?: "")
