package com.circaso.feature.messaging.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.BubbleMeta
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.MessageColumn
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.designsystem.theme.DarkColors
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.formatLocalTime
import com.circaso.feature.messaging.data.repository.MediaDetails
import com.circaso.feature.messaging.data.repository.MediaLoad
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.Message

/**
 * Albums (WhatsApp-style, Pasaka 2026-11-07): photos and videos sent together show as one bubble with a grid. Each
 * item is still its own encrypted message and upload; they share an album id in the encrypted payload, and the thread
 * draws a run of them as one.
 */

/** One row of the thread: a message on its own, or the photos and videos of one album, oldest first. */
internal sealed interface ThreadRow {
    /** The row's oldest message: its place in the thread, its day label and what came before it. */
    val first: Message
    /** [first]'s index in the thread's messages. */
    val index: Int
    /** Stable while items arrive: an album keeps its first item's id. */
    val key: Long get() = first.localId

    data class Single(override val first: Message, override val index: Int) : ThreadRow
    data class Album(val items: List<Message.Media>, override val index: Int) : ThreadRow {
        override val first: Message get() = items.first()
    }
}

/** Items of one album further apart than this (a resend much later) start a new bubble. */
internal const val AlbumGapMs = 5 * 60_000L

/** A photo or video that says it belongs to an album. */
private fun Message.albumOf(): String? = (this as? Message.Media)?.takeIf { it.isImage || it.isVideo }?.details?.album

/**
 * The thread's messages (oldest first) as rows: consecutive photos and videos with the same album id, from the same
 * person, each within [AlbumGapMs] of the one before, are one album. Anything else in between (a text, a payment,
 * someone else's photo) ends the run; an album of one is drawn as a plain photo.
 */
internal fun threadRows(messages: List<Message>): List<ThreadRow> {
    val rows = ArrayList<ThreadRow>(messages.size)
    var i = 0
    while (i < messages.size) {
        val m = messages[i]
        val album = m.albumOf()
        if (album == null) { rows += ThreadRow.Single(m, i); i++; continue }
        val run = mutableListOf(m as Message.Media)
        var j = i + 1
        while (j < messages.size) {
            val next = messages[j] as? Message.Media ?: break
            val last = run.last()
            if (next.albumOf() != album || next.senderId != last.senderId || next.outgoing != last.outgoing || next.orderAt - last.orderAt > AlbumGapMs) break
            run += next
            j++
        }
        rows += if (run.size == 1) ThreadRow.Single(m, i) else ThreadRow.Album(run, i)
        i = j
    }
    return rows
}

/**
 * The grid for an album of [count]: rows of item indexes, and how many more there are than it shows ("+N" on the
 * last tile). 2 side by side; 3 one large on top and two under it; 4 or more two by two, the first four.
 */
internal data class AlbumGrid(val rows: List<List<Int>>, val more: Int)

internal fun albumGrid(count: Int): AlbumGrid = when {
    count <= 1 -> AlbumGrid(listOf(listOf(0)), 0)
    count == 2 -> AlbumGrid(listOf(listOf(0, 1)), 0)
    count == 3 -> AlbumGrid(listOf(listOf(0), listOf(1, 2)), 0)
    else -> AlbumGrid(listOf(listOf(0, 1), listOf(2, 3)), count - 4)
}

/** How an album is going, from its items: failed if any failed, else on its way while any is, with their mean progress. */
internal fun albumState(items: List<Message.Media>): DeliveryState = when {
    items.any { it.state == DeliveryState.Failed } -> DeliveryState.Failed
    items.any { it.state == DeliveryState.Sending } -> DeliveryState.Sending
    items.any { it.state == DeliveryState.Waiting } -> DeliveryState.Waiting
    items.all { it.state == DeliveryState.Read } -> DeliveryState.Read
    items.all { it.state == DeliveryState.Read || it.state == DeliveryState.Delivered } -> DeliveryState.Delivered
    else -> items.last().state
}

/** The upload share of the whole album: items already sent count as whole, the rest by their own progress. */
internal fun albumProgress(items: List<Message.Media>, progress: (Long) -> Float?): Float? {
    val going = items.filter { it.state == DeliveryState.Sending || it.state == DeliveryState.Waiting }
    if (going.isEmpty() || going.none { progress(it.localId) != null }) return null
    val done = items.size - going.size
    return ((done + going.sumOf { (progress(it.localId) ?: 0f).toDouble() }.toFloat()) / items.size).coerceIn(0f, 1f)
}

/** One caption for the album, under the grid: the first item's that has one. */
internal fun albumCaption(items: List<Message.Media>): String? = items.firstNotNullOfOrNull { it.details.caption?.takeIf(String::isNotBlank) }

private const val GridGap = 2

/**
 * The album bubble: the grid (with "+N" on the fourth tile past four), the caption under it, and the album's state on
 * the grid as on a single photo (ring while it goes, clock while it waits, the failed badge). A tile opens the viewer
 * at that item; a failed album opens its options.
 */
@Composable
internal fun AlbumRow(
    items: List<Message.Media>, group: Boolean, firstOfRun: Boolean,
    load: suspend (MediaDetails) -> MediaLoad,
    progress: Float?,
    onOpen: (index: Int) -> Unit,
    onOptions: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val head = items.first()
    val outgoing = head.outgoing
    val state = albumState(items)
    val failed = state == DeliveryState.Failed
    val grid = albumGrid(items.size)
    val caption = albumCaption(items)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (group && !outgoing) {
            if (firstOfRun) Avatar(head.senderName, Modifier.padding(end = CircasoSpacing.s2, bottom = 18.dp), size = CircasoSizes.avatarSm)
            else Spacer(Modifier.width(CircasoSizes.avatarSm + CircasoSpacing.s2))
        }
        MessageColumn(outgoing, Modifier.weight(1f, fill = false)) {
            if (group && !outgoing && firstOfRun) BasicText(head.senderName, style = t.caption1.copy(color = c.inkSecondary), modifier = Modifier.padding(bottom = 2.dp))
            val photos = items.count { it.isImage }
            val videos = items.size - photos
            Column(
                Modifier.width(MediaBubbleWidth).clip(com.circaso.designsystem.components.bubbleShape(outgoing)).background(if (outgoing) c.action else c.fill).padding(3.dp)
                    .semantics(mergeDescendants = false) {
                        contentDescription = "Album from ${if (outgoing) "you" else head.senderName}: " + listOfNotNull(
                            photos.takeIf { it > 0 }?.let { "$it ${if (it == 1) "photo" else "photos"}" },
                            videos.takeIf { it > 0 }?.let { "$it ${if (it == 1) "video" else "videos"}" },
                        ).joinToString(" and ") + (caption?.let { ". $it" } ?: "")
                    },
            ) {
                val inner = MediaBubbleWidth - 6.dp
                Box(Modifier.clip(com.circaso.designsystem.components.bubbleShape(outgoing))) {
                    Column(verticalArrangement = Arrangement.spacedBy(GridGap.dp)) {
                        grid.rows.forEachIndexed { r, row ->
                            // 3: the large one on top is shorter, the pair under it square-ish; otherwise square tiles.
                            val h: Dp = when {
                                items.size == 2 -> inner * 0.62f
                                items.size == 3 && r == 0 -> inner * 0.56f
                                else -> (inner - GridGap.dp) / 2
                            }
                            Row(Modifier.height(h), horizontalArrangement = Arrangement.spacedBy(GridGap.dp)) {
                                row.forEach { i ->
                                    val more = if (grid.more > 0 && i == 3) grid.more else 0
                                    AlbumTile(items[i], load, more, Modifier.weight(1f).fillMaxHeight(), onClick = { if (failed) onOptions() else onOpen(i) })
                                }
                            }
                        }
                    }
                    // The album's state on the grid, as a single photo shows it on itself.
                    Box(Modifier.matchParentSize()) { SendState(state, progress) }
                }
                caption?.let { BasicText(it, style = t.body.copy(color = if (outgoing) c.onAction else c.ink), modifier = Modifier.padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2)) }
            }
            val n = items.size
            when (state) {
                DeliveryState.Failed -> Box(Modifier.pressable(role = Role.Button, onClick = onOptions)) {
                    BubbleMeta("${items.count { it.state == DeliveryState.Failed }} of $n not sent · tap for options", icon = CircasoIcon.Close, color = c.critical)
                }
                DeliveryState.Waiting -> BubbleMeta("Waiting to send", icon = CircasoIcon.Clock)
                DeliveryState.Sending -> BubbleMeta("Sending $n…", icon = CircasoIcon.Clock)
                else -> BubbleMeta(formatLocalTime(items.last().orderAt, LocalTimeStyle.Clock) + if (outgoing) when (state) { DeliveryState.Read -> " · Read"; DeliveryState.Delivered -> " · Delivered"; else -> "" } else "")
            }
        }
    }
}

/** One tile: the photo (loaded and decrypted on demand) or the video's poster with a play glyph and its length. */
@Composable
private fun AlbumTile(m: Message.Media, load: suspend (MediaDetails) -> MediaLoad, more: Int, modifier: Modifier, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val picture = rememberPicture(m, load)
    // A video coming in fills its ring on the tile, and still does when the album scrolls away and back.
    val incoming = if (m.isVideo) downloadProgress(m) else null
    Box(
        modifier.background(c.fillStrong).pressable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = (if (m.isVideo) "Video" + (m.details.durationMs?.let { ", ${longDuration(it)}" } ?: "") else "Photo") +
                    (if (more > 0) ", and $more more" else "") + (incoming?.let { ". " + downloadLabel(it) } ?: "")
            },
        contentAlignment = Alignment.Center,
    ) {
        picture?.let { coil3.compose.AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        if (picture == null && m.isImage) CircasoIconImage(CircasoIcon.Photo, c.inkSecondary, Modifier.size(22.dp))
        if (m.isVideo && more == 0) {
            if (incoming != null) DownloadDisc(incoming, CircasoSizes.iconButton)
            else Box(Modifier.size(CircasoSizes.iconButton).clip(CircasoShapes.circle).background(DarkColors.scrim), contentAlignment = Alignment.Center) {
                CircasoIconImage(CircasoIcon.Play, DarkColors.ink, Modifier.size(16.dp))
            }
            m.details.durationMs?.let {
                BasicText(
                    longDuration(it), Modifier.align(Alignment.BottomStart).padding(CircasoSpacing.s2),
                    style = t.caption1.copy(
                        color = DarkColors.ink, fontFeatureSettings = "tnum",
                        shadow = androidx.compose.ui.graphics.Shadow(DarkColors.scrim, androidx.compose.ui.geometry.Offset(0f, 1f), 4f),
                    ),
                )
            }
        }
        if (more > 0) {
            Box(Modifier.matchParentSize().background(DarkColors.scrim), contentAlignment = Alignment.Center) {
                if (incoming != null) com.circaso.feature.mediacompose.UploadRing(incoming, Modifier.size(CircasoSizes.controlMd), ink = DarkColors.ink)
                BasicText("+$more", style = t.title2.copy(color = DarkColors.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            }
        }
    }
}

/** A photo's decrypted bytes (loaded once per item), or a video's poster from the message; null while it loads. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
@Composable
private fun rememberPicture(m: Message.Media, load: suspend (MediaDetails) -> MediaLoad): ByteArray? {
    if (m.isVideo) return remember(m.details.thumb) { m.details.thumb?.let { runCatching { kotlin.io.encoding.Base64.decode(it) }.getOrNull() } }
    var bytes by remember(m.localId) { mutableStateOf<ByteArray?>(null) }
    // Keyed on the row, not the attachment id: the id arriving after upload must not blank a shown photo.
    LaunchedEffect(m.localId, m.details.attachmentId) {
        if (bytes == null) bytes = (load(m.details) as? MediaLoad.Loaded)?.bytes
    }
    return bytes
}

/** "0:42", "2:05". */
private fun longDuration(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

/**
 * The album full screen: swipe through every item, "3 of 7" at the top, each item's own caption at the bottom. A photo
 * shows whole; a video shows its poster and plays full screen on tap.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun BoxScope.AlbumViewer(
    album: Pair<List<Message.Media>, Int>?,
    load: suspend (MediaDetails) -> MediaLoad,
    onPlayVideo: (Message.Media) -> Unit,
    onClose: () -> Unit,
) {
    androidx.compose.ui.backhandler.BackHandler(enabled = album != null, onBack = onClose)
    var last by remember { mutableStateOf(album) }
    if (album != null) last = album
    AnimatedVisibility(album != null, Modifier.matchParentSize(), enter = fadeIn(tween(CircasoMotion.BaseMs)), exit = fadeOut(tween(CircasoMotion.QuickMs))) {
        val (items, start) = last ?: return@AnimatedVisibility
        val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = start) { items.size }
        val t = CircasoTheme.typography
        // Dark in every theme: the status bar's icons go light while it is open.
        com.circaso.core.platform.StatusBarIcons(light = true)
        Box(Modifier.fillMaxSize().background(DarkColors.bg)) {
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize(), key = { items[it].localId }) { page ->
                val m = items[page]
                val picture = rememberPicture(m, load)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    picture?.let { coil3.compose.AsyncImage(it, if (m.isVideo) "Video" else m.details.caption ?: "Photo", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
                    if (picture == null && m.isImage) com.circaso.designsystem.components.Spinner(DarkColors.ink)
                    if (m.isVideo) {
                        val incoming = downloadProgress(m)
                        val disc = Modifier.pressable(role = Role.Button, onClick = { onPlayVideo(m) })
                            .semantics { contentDescription = if (incoming != null) downloadLabel(incoming) else "Play video" }
                        if (incoming != null) DownloadDisc(incoming, CircasoSizes.controlMd + CircasoSpacing.s4, disc)
                        else Box(
                            disc.size(CircasoSizes.controlMd + CircasoSpacing.s4).clip(CircasoShapes.circle).background(DarkColors.scrim),
                            contentAlignment = Alignment.Center,
                        ) { CircasoIconImage(CircasoIcon.Play, DarkColors.ink, Modifier.size(26.dp)) }
                    }
                }
            }
            CircasoTheme(darkTheme = true, highContrast = CircasoTheme.colors.isHighContrast) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircasoIconButton(CircasoIcon.Close, "Close album", onClose)
                    BasicText(
                        "${pager.currentPage + 1} of ${items.size}", Modifier.weight(1f).padding(end = CircasoSizes.controlMd),
                        style = t.headline.copy(color = DarkColors.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontFeatureSettings = "tnum"),
                    )
                }
                items.getOrNull(pager.currentPage)?.details?.caption?.takeIf { it.isNotBlank() }?.let {
                    BasicText(
                        it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(DarkColors.scrim).navigationBarsPadding()
                            .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
                        style = t.body.copy(color = DarkColors.ink),
                    )
                }
            }
        }
    }
}
