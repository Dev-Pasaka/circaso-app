package com.circaso.feature.messaging.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import com.circaso.designsystem.motion.pressable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.PdfDocument
import com.circaso.core.platform.PdfOpen
import com.circaso.core.platform.openFile
import com.circaso.core.platform.openPdf
import com.circaso.core.platform.shareFile
import com.circaso.designsystem.chrome.OverWindow
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.components.ZoomMath
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.messaging.data.repository.MediaDetails
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.roundToInt

/** A PDF to show: its decrypted file on this phone (Circaso's private files), its name, size and page count if known. */
class PdfViewing(val path: String, val name: String, val mime: String, val size: Long?)

/** Opens a PDF in [PdfViewer]; null where there is no viewer (the bubble then hands the file to other apps). */
internal val LocalOpenPdf = staticCompositionLocalOf<((PdfViewing) -> Unit)?> { null }

/** A document's decrypted file if it is already on this phone, without downloading it (a PDF bubble's first page). */
internal val LocalLocalDocument = staticCompositionLocalOf<(suspend (MediaDetails) -> String?)?> { null }

/**
 * A chat's PDF full screen (2026-11-24): every page, top to bottom, as Files' and Books' viewers show one. Scroll with a
 * fling; pinch (about the fingers) or double-tap to zoom up to 5×, then drag sideways to read across; the pages are
 * drawn again sharper once a zoom settles. "3 of 12" floats at the bottom while scrolling. Share hands the file to
 * other apps or Files; Close, Back or a swipe from the edge close it. Pages are drawn off the main thread and kept
 * in memory while it is open; the file never leaves Circaso's private files except through Share.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun BoxScope.PdfViewer(viewing: PdfViewing?, onClose: () -> Unit) {
    androidx.compose.ui.backhandler.BackHandler(enabled = viewing != null, onBack = onClose)
    OverWindow {
        var last by remember { mutableStateOf(viewing) }
        if (viewing != null) last = viewing
        val still = CircasoTheme.reduceMotion
        AnimatedVisibility(
            viewing != null, Modifier.matchParentSize(),
            enter = if (still) fadeIn(tween(CircasoMotion.QuickMs)) else fadeIn(tween(CircasoMotion.BaseMs)) + scaleIn(tween(CircasoMotion.BaseMs), initialScale = 0.96f),
            exit = if (still) fadeOut(tween(CircasoMotion.QuickMs)) else fadeOut(tween(CircasoMotion.QuickMs)) + scaleOut(tween(CircasoMotion.QuickMs), targetScale = 0.98f),
        ) {
            last?.let { PdfViewerContent(it, onClose) }
        }
    }
}

@Composable
internal fun PdfViewerContent(viewing: PdfViewing, onClose: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val scope = rememberCoroutineScope()
    // Closed when the viewer goes, even if it goes while the file is still opening.
    val opened by produceState<PdfOpen?>(null, viewing.path) {
        val o = openPdf(viewing.path)
        value = o
        awaitDispose { (o as? PdfOpen.Opened)?.document?.close() }
    }
    val document = (opened as? PdfOpen.Opened)?.document
    var problem by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(0) }
    // The top bar's height (it grows with Dynamic Type) is where the first page starts.
    var barHeight by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxSize().background(c.bgGrouped)) {
        when (val o = opened) {
            null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                Spinner(c.inkSecondary)
                BasicText("Opening ${viewing.name}", style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            is PdfOpen.Opened -> PdfPages(o.document, topPadding = with(LocalDensity.current) { barHeight.toDp() }, onPage = { page = it })
            PdfOpen.Locked, PdfOpen.Unreadable -> Column(
                Modifier.align(Alignment.Center).padding(horizontal = CircasoSpacing.s8).widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
            ) {
                Box(Modifier.size(com.circaso.designsystem.theme.CircasoSizes.sheetBadge).clip(CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
                    CircasoIconImage(if (o == PdfOpen.Locked) CircasoIcon.Lock else CircasoIcon.Document, c.ink, Modifier.size(com.circaso.designsystem.theme.CircasoSizes.sheetBadgeIcon))
                }
                BasicText(
                    if (o == PdfOpen.Locked) "This PDF has a password" else "This PDF can't be shown here",
                    Modifier.padding(top = CircasoSpacing.s2), style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center),
                )
                BasicText(
                    if (o == PdfOpen.Locked) "Open it in another app to enter the password." else "Another app on this phone may be able to open it.",
                    style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                )
                CircasoButton(
                    "Open in Another App", { scope.launch { if (!openFile(viewing.path, viewing.name, viewing.mime)) problem = NoApp } },
                    Modifier.padding(top = CircasoSpacing.s3), style = ButtonStyle.Secondary, size = ButtonSize.Medium,
                )
            }
        }
        PdfTopBar(
            viewing, document?.pageCount, onClose, onShare = { scope.launch { if (!shareFile(viewing.path, viewing.name, viewing.mime)) problem = NoApp } },
            Modifier.onSizeChanged { barHeight = it.height },
        )
        problem?.let {
            LaunchedEffect(it) { delay(ProblemShownMs); problem = null }
            BasicText(
                it, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = CircasoSpacing.s14)
                    .glass(CircasoShapes.pill, floating = true).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
                style = t.footnote.copy(color = c.ink),
            )
        }
    }
}

/** Close, the name over "12 pages · 2.4 MB", Share; glass over the pages as they scroll under it. */
@Composable
private fun BoxScope.PdfTopBar(viewing: PdfViewing, pages: Int?, onClose: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        modifier.align(Alignment.TopCenter).fillMaxWidth().glass(RectangleShape, floating = false, edge = false).statusBarsPadding()
            .padding(horizontal = CircasoSpacing.s2, vertical = CircasoSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircasoIconButton(CircasoIcon.Close, "Close", onClose)
        Column(Modifier.weight(1f).padding(horizontal = CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(viewing.name, style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
            pdfFacts(pages, viewing.size)?.let { BasicText(it, style = t.caption1.copy(color = c.inkSecondary, fontFeatureSettings = "tnum")) }
        }
        CircasoIconButton(CircasoIcon.Share, "Share", onShare)
    }
}

/** "12 pages · 2.4 MB", "1 page", or only the size while the pages are being counted. */
internal fun pdfFacts(pages: Int?, size: Long?): String? = listOfNotNull(
    pages?.let { if (it == 1) "1 page" else "$it pages" },
    size?.let { com.circaso.feature.mediacompose.sizeLabel(it) },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

/**
 * The pages, laid out at the zoom: a zoom makes the pages wider (not a picture of them stretched), so scrolling and
 * flinging stay the list's own and the pages are drawn again at the size they show. Sideways, the pages move together.
 */
@Composable
private fun PdfPages(document: PdfDocument, topPadding: androidx.compose.ui.unit.Dp, onPage: (Int) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val cache = remember(document) { PdfPageCache() }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    // The zoom the pages are drawn at: follows the pinch once it has rested a moment.
    var drawnAt by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) { snapshotFlow { scale }.collectLatest { delay(PdfRedrawDelayMs); drawnAt = it } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = constraints.maxWidth.toFloat()
        val margin = with(density) { CircasoSpacing.s4.toPx() }
        // Phones: the page fills the width less a margin; tablets: a readable column.
        val fitted = minOf(viewport - margin * 2, with(density) { 760.dp.toPx() })
        val pageWidth = fitted * scale
        fun maxOffset() = maxOf(0f, (pageWidth - viewport) / 2f + margin)
        fun zoomAbout(factor: Float, focus: Offset) {
            if (!factor.isFinite() || factor <= 0f) return
            val next = (scale * factor).coerceIn(ZoomMath.MinScale, ZoomMath.MaxScale)
            val f = next / scale
            if (f == 1f) return
            // Keep the point under the fingers there: sideways by the offset, down the list by scrolling.
            val fx = if (focus.isSpecified && focus.x.isFinite()) focus.x - viewport / 2f else 0f
            val fy = if (focus.isSpecified && focus.y.isFinite()) focus.y else 0f
            val within = list.firstVisibleItemScrollOffset.toFloat()
            scale = next
            offsetX = (fx - (fx - offsetX) * f).coerceIn(-maxOffset(), maxOffset())
            list.dispatchRawDelta((within + fy) * (f - 1f))
        }
        var tapZoom by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        val pan = rememberDraggableState { dx -> offsetX = (offsetX + dx).coerceIn(-maxOffset(), maxOffset()) }
        val current by remember { derivedStateOf { currentPage(list) } }
        LaunchedEffect(current) { onPage(current) }
        LazyColumn(
            Modifier.fillMaxSize()
                // Two fingers: zoom and move, before the list sees them (one finger scrolls as usual).
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.count { it.pressed && it.previousPressed }
                            if (down >= 2) {
                                tapZoom?.cancel()
                                zoomAbout(event.calculateZoom(), event.calculateCentroid(useCurrent = true))
                                val move = event.calculatePan()
                                offsetX = (offsetX + move.x).coerceIn(-maxOffset(), maxOffset())
                                list.dispatchRawDelta(-move.y)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { at ->
                        val target = if (scale > 1f + ZoomMathEpsilon) 1f else ZoomMath.DoubleTapScale
                        tapZoom?.cancel()
                        tapZoom = scope.launch {
                            var at0 = scale
                            animate(scale, target, animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { v, _ ->
                                zoomAbout(v / at0, at)
                                at0 = v
                            }
                            if (target == 1f) offsetX = 0f
                        }
                    })
                }
                .draggable(pan, Orientation.Horizontal, enabled = scale > 1f + ZoomMathEpsilon)
                .semantics { contentDescription = "PDF, ${document.pageCount} pages" },
            state = list,
            // Under the top bar to start with, and clear of the page indicator at the end.
            contentPadding = PaddingValues(
                top = maxOf(topPadding, androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + CircasoSpacing.s14) + CircasoSpacing.s4,
                bottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + CircasoSpacing.s14 + CircasoSpacing.s4,
            ),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3 * scale),
        ) {
            items(document.pageCount, key = { it }) { index ->
                val aspect = document.pageSizes[index].aspect
                Box(Modifier.fillMaxWidth().wrapContentWidth(unbounded = true)) {
                    PdfPage(
                        document, index, cache,
                        drawWidthPx = drawWidth(fitted, drawnAt, aspect),
                        modifier = Modifier.requiredWidth(with(density) { pageWidth.toDp() }).aspectRatio(1f / aspect)
                            .graphicsLayer { translationX = offsetX },
                    )
                }
            }
        }
        PageIndicator(current, document.pageCount, list.isScrollInProgress, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = CircasoSpacing.s4))
    }
}

private const val ZoomMathEpsilon = 0.01f
private const val PdfRedrawDelayMs = 180L
private const val ProblemShownMs = 3_500L
private const val NoApp = "No app here can open this file"

/** The page whose middle is nearest the middle of the screen. */
private fun currentPage(list: androidx.compose.foundation.lazy.LazyListState): Int {
    val info = list.layoutInfo
    val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - middle) }?.index ?: 0
}

/**
 * Pages are drawn at 1× or 2× the fitted width: sharp to about 2.5× zoom, then softening a little towards 5×. A page
 * is never more than [MaxPagePixels] (about 32 MB), so a tall page or a large phone can't run memory out.
 */
internal fun drawWidth(fitted: Float, zoom: Float, aspect: Float): Int {
    val level = if (zoom > 1.4f) 2f else 1f
    val wanted = fitted * level
    val budget = kotlin.math.sqrt(MaxPagePixels / aspect.coerceAtLeast(0.1f))
    return minOf(wanted, budget).roundToInt().coerceIn(1, 4096)
}

private const val MaxPagePixels = 8_000_000f

/** One page: white paper with a soft shadow; its drawing fades in, and a sharper one replaces it without a blank. */
@Composable
private fun PdfPage(document: PdfDocument, index: Int, cache: PdfPageCache, drawWidthPx: Int, modifier: Modifier) {
    val c = CircasoTheme.colors
    var bitmap by remember(document, index) { mutableStateOf(cache.best(index)) }
    var failed by remember(document, index) { mutableStateOf(false) }
    var attempt by remember(document, index) { mutableStateOf(0) }
    LaunchedEffect(document, index, drawWidthPx, attempt) {
        cache.get(index, drawWidthPx)?.let { bitmap = it; return@LaunchedEffect }
        val drawn = document.render(index, drawWidthPx)
        if (drawn != null) { cache.put(index, drawWidthPx, drawn); bitmap = drawn; failed = false } else if (bitmap == null) failed = true
    }
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier.shadow(if (c.isDark) 0.dp else 2.dp, shape).clip(shape).background(Color.White)
            .then(if (c.isDark || c.isHighContrast) Modifier.border(0.5.dp, c.fillStrong, shape) else Modifier)
            .semantics { contentDescription = "Page ${index + 1}" },
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        if (shown == null && failed) {
            BasicText(
                "This page can't be shown · Try again",
                Modifier.pressable(onClick = { failed = false; attempt++ }).padding(CircasoSpacing.s4),
                style = CircasoTheme.typography.footnote.copy(color = PaperInk.copy(alpha = 0.6f), textAlign = TextAlign.Center),
            )
        } else if (shown == null) Spinner(PaperInk.copy(alpha = 0.35f), Modifier.size(20.dp))
        else if (shown != null) Image(shown, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

/** What a page's own grey would be on paper: pages are white in every theme, as printed. */
private val PaperInk = Color(0xFF0B0B0C)

/** "3 of 12", floating at the bottom while the pages move, fading a moment after they stop. */
@Composable
private fun PageIndicator(page: Int, pages: Int, scrolling: Boolean, modifier: Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(scrolling, page) {
        if (scrolling) visible = true else { delay(PageIndicatorHoldMs); visible = false }
    }
    AnimatedVisibility(visible && pages > 1, modifier, enter = fadeIn(tween(CircasoMotion.QuickMs)), exit = fadeOut(tween(CircasoMotion.BaseMs))) {
        BasicText(
            "${page + 1} of $pages",
            Modifier.glass(CircasoShapes.pill, floating = true).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
            style = t.footnote.copy(color = c.ink, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        )
    }
}

private const val PageIndicatorHoldMs = 1_400L

/**
 * Drawn pages kept while the viewer is open: the most recently used first out once they pass about 96 MB (a dozen
 * phone-width pages at 2×), so scrolling back is instant and memory stays bounded. Main thread only.
 */
internal class PdfPageCache(private val budgetBytes: Long = 96L * 1024 * 1024) {
    private val pages = LinkedHashMap<Pair<Int, Int>, ImageBitmap>()
    private var bytes = 0L

    fun get(page: Int, width: Int): ImageBitmap? = pages.remove(page to width)?.also { pages[page to width] = it }

    /** The sharpest drawing of [page] kept, to show while the one wanted is drawn. */
    fun best(page: Int): ImageBitmap? = pages.entries.filter { it.key.first == page }.maxByOrNull { it.key.second }?.value

    fun put(page: Int, width: Int, bitmap: ImageBitmap) {
        pages.remove(page to width)?.let { bytes -= it.cost() }
        pages[page to width] = bitmap
        bytes += bitmap.cost()
        val it = pages.entries.iterator()
        while (bytes > budgetBytes && pages.size > 1 && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == page to width) continue
            bytes -= eldest.value.cost()
            it.remove()
        }
    }

    private fun ImageBitmap.cost() = width.toLong() * height * 4
}

/**
 * A PDF's first page in its bubble: drawn here, sharp, from the file when it is on this phone (it always is for the
 * sender, and after the first open for others); until then the small page the sender put in the message.
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
internal fun PdfFirstPage(details: MediaDetails, modifier: Modifier, onPages: (Int) -> Unit = {}) {
    val local = LocalLocalDocument.current
    val density = LocalDensity.current
    val sent = remember(details.thumb) { details.thumb?.let { runCatching { Base64.decode(it) }.getOrNull() } }
    val key = details.attachmentId ?: details.file
    var drawn by remember(key) { mutableStateOf(key?.let { FirstPages.get(it) }) }
    BoxWithConstraints(modifier.background(Color.White)) {
        val widthPx = constraints.maxWidth.coerceAtLeast(1)
        LaunchedEffect(key, widthPx) {
            if (key == null || local == null || drawn?.let { it.bitmap.width >= widthPx } == true) return@LaunchedEffect
            val path = local(details) ?: return@LaunchedEffect
            val opened = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { openPdf(path) }
            val document = (opened as? PdfOpen.Opened)?.document ?: return@LaunchedEffect
            try {
                document.render(0, widthPx)?.let { bitmap ->
                    FirstPage(bitmap, document.pageCount).also { FirstPages.put(key, it); drawn = it }
                }
            } finally {
                document.close() // also when the bubble scrolls away mid-draw
            }
        }
        LaunchedEffect(drawn) { drawn?.let { onPages(it.pages) } }
        val page = drawn
        when {
            page != null -> Image(page.bitmap, null, Modifier.fillMaxSize(), alignment = Alignment.TopCenter, contentScale = ContentScale.Crop)
            sent != null -> coil3.compose.AsyncImage(sent, null, Modifier.fillMaxSize(), alignment = Alignment.TopCenter, contentScale = ContentScale.Crop)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircasoIconImage(CircasoIcon.Document, PaperInk.copy(alpha = 0.25f), Modifier.size(CircasoSpacing.s8))
            }
        }
    }
}

internal class FirstPage(val bitmap: ImageBitmap, val pages: Int)

/** First pages drawn for bubbles this run, by attachment: the last 24, so scrolling a chat never draws one twice. */
private object FirstPages {
    private val pages = LinkedHashMap<String, FirstPage>()
    fun get(key: String): FirstPage? = pages.remove(key)?.also { pages[key] = it }
    fun put(key: String, page: FirstPage) {
        pages.remove(key)
        pages[key] = page
        while (pages.size > 24) pages.remove(pages.keys.first())
    }
}
