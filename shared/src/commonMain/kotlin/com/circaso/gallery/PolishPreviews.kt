package com.circaso.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import circaso.shared.generated.resources.Res
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.LocalVerifiedPeople
import com.circaso.designsystem.components.VerifiedName
import com.circaso.designsystem.components.VerifiedSheet
import com.circaso.designsystem.components.ZoomTransform
import com.circaso.designsystem.components.rememberVerifiedExplainer
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.messaging.data.repository.MediaDetails
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.messaging.presentation.ImageViewer
import com.circaso.feature.messaging.presentation.PdfViewerContent
import com.circaso.feature.messaging.presentation.PdfViewing
import com.circaso.feature.messaging.presentation.PeerNameLines
import com.circaso.feature.messaging.presentation.ThreadContent
import com.circaso.feature.messaging.presentation.ThreadMediaActions
import com.circaso.feature.messaging.presentation.ThreadUiState
import com.circaso.feature.status.presentation.momentRing
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The polish pass of 2026-11-24, `-preview=`:
 * - `verified-badge`: the seal on avatars from 28 to 120 dp on a page, a sheet and a call's black stage, and the
 *   name's mark at every text size;
 * - `chat-info-verified` (and `-sheet`, the explanation open): a verified person's profile header;
 * - `photo-viewer`, `photo-viewer-zoomed`: the chat's photo viewer, fitted and at 3×;
 * - `chat-pdf`: PDF bubbles (one with the page the sender put in the message, one drawn here from the file);
 * - `pdf-viewer`: a PDF open full screen.
 */
@Composable
internal fun PolishPreview(name: String): Boolean {
    when (name) {
        "verified-badge" -> VerifiedBadgePreview()
        "chat-info-verified", "chat-info-verified-sheet" -> VerifiedProfilePreview(open = name.endsWith("sheet"))
        "photo-viewer", "photo-viewer-zoomed" -> PhotoViewerPreview(zoomed = name.endsWith("zoomed"))
        "chat-pdf" -> PdfChatPreview()
        "pdf-viewer" -> PdfViewerPreview()
        else -> return false
    }
    return true
}

private val BadgeSizes = listOf(28.dp, 32.dp, 40.dp, 44.dp, 52.dp, 64.dp, 88.dp, 120.dp)

@Composable
private fun VerifiedBadgePreview() {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(grouped = true, topBar = { CircasoNavBar(title = "Verified badge", onBack = {}) }) { pad ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = pad.calculateTopPadding()).padding(CircasoSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
        ) {
            BadgeRow("On the page", Color.Transparent)
            BadgeRow("On a sheet", c.surface)
            BadgeRow("Initials", c.surface, photos = false)
            BadgeRow("With a moment's ring", c.surface, ring = true)
            BadgeRow("On a call's stage", Color.Black, stage = true)
            Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface).padding(CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                VerifiedName("Amina Odhiambo", true, t.largeTitle.copy(color = c.ink, fontWeight = FontWeight.Bold))
                VerifiedName("Amina Odhiambo", true, t.title1.copy(color = c.ink))
                VerifiedName("Amina Odhiambo", true, t.title2.copy(color = c.ink, fontWeight = FontWeight.Bold))
                VerifiedName("Amina Odhiambo", true, t.title3.copy(color = c.ink))
                VerifiedName("Amina Odhiambo", true, t.headline.copy(color = c.ink))
                VerifiedName("Amina Odhiambo", true, t.body.copy(color = c.ink))
                VerifiedName("Amina Odhiambo", true, t.footnote.copy(color = c.inkSecondary))
                VerifiedName("Wanjiru Kamau-Njoroge of the Kilimani Court Residents", true, t.title2.copy(color = c.ink, textAlign = TextAlign.Center), Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun BadgeRow(label: String, ground: Color, photos: Boolean = true, stage: Boolean = false, ring: Boolean = false) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(ground).padding(CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        BasicText(label, style = t.footnote.copy(color = if (stage) Color.White.copy(alpha = 0.7f) else c.inkSecondary))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            BadgeSizes.take(5).forEach { size ->
                Avatar(
                    "Achieng Otieno", size = size, imageUrl = if (photos) DemoPhotos.Achieng else null, verified = true, onDarkStage = stage,
                    discModifier = if (ring) Modifier.momentRing(listOf(false, true), c) else Modifier,
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
            BadgeSizes.drop(5).forEach { size -> Avatar("Juma Kariuki", size = size, imageUrl = if (photos) DemoPhotos.Juma else null, verified = true, onDarkStage = stage) }
        }
    }
}

/** Contact Info's header for a verified person: the seal on the photo and the mark after the name, as built. */
@Composable
private fun VerifiedProfilePreview(open: Boolean) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val peer = ChatMember("4", "Achieng Otieno", "achieng", DemoPhotos.Achieng, MemberRole.Member, "")
    val explainer = rememberVerifiedExplainer().also { if (open) it.open = true }
    androidx.compose.runtime.CompositionLocalProvider(LocalVerifiedPeople provides setOf("4")) {
        CircasoScaffold(grouped = true, topBar = { CircasoNavBar(title = "Contact Info", onBack = {}) }) { _ ->
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().padding(vertical = CircasoSpacing.s6), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    Avatar(peer.displayName, size = CircasoSizes.avatarXl, imageUrl = peer.avatarUrl, userId = peer.userId, onSealClick = { explainer.open = true })
                    VerifiedName(
                        peer.displayName, true, t.title1.copy(color = c.ink, textAlign = TextAlign.Center),
                        Modifier.padding(horizontal = CircasoSpacing.s6), onMarkClick = { explainer.open = true },
                    )
                    PeerNameLines(peer)
                }
                VerifiedSheet(explainer)
            }
        }
    }
}

@Composable
private fun PhotoViewerPreview(zoomed: Boolean) {
    val bytes by produceState<ByteArray?>(null) { value = runCatching { Res.readBytes("files/demo-avatars/wanjiru.jpg") }.getOrNull() }
    Box(Modifier.fillMaxSize()) {
        ImageViewer(bytes, initial = if (zoomed) ZoomTransform(3f, Offset(120f, -260f)) else ZoomTransform.Identity) {}
    }
}

/** The sample lease (3 pages) copied into Circaso's media files, as a decrypted chat document would be. */
@Composable
private fun samplePdfPath(): String? {
    val files = org.koin.compose.koinInject<com.circaso.core.platform.MediaFiles>()
    val path by produceState<String?>(null) {
        val bytes = runCatching { Res.readBytes("files/demo-docs/lease.pdf") }.getOrNull() ?: return@produceState
        files.write(SamplePdf, bytes)
        value = files.path(SamplePdf)
    }
    return path
}

private const val SamplePdf = "preview-lease.pdf"

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun PdfChatPreview() {
    val path = samplePdfPath()
    // The page the sender's phone puts in the message, made here the same way.
    val thumb by produceState<String?>(null, path) { value = path?.let { com.circaso.core.platform.pdfPreview(it) }?.let { Base64.encode(it.thumb) } }
    val now = remember { com.circaso.core.sync.nowMillis() }
    fun member(id: String, name: String) = ChatMember(id, name, name.lowercase().substringBefore(' '), DemoPhotos.of(name), MemberRole.Member, "2026-10-20T00:00:00Z")
    val chat = Chat("2", ChatKind.Direct, "Juma Kariuki", DemoPhotos.Juma, listOf(member("me", "Amina"), member("2", "Juma Kariuki")), false, 1, 0, MemberRole.Member, "2")
    fun file(localId: Long, outgoing: Boolean, minutes: Int, details: MediaDetails) =
        Message.Media(localId, "2", now - minutes * 60_000L, null, "m$localId", if (outgoing) "me" else "2", if (outgoing) "You" else "Juma", outgoing, details, DeliveryState.Read, null)
    val messages = listOf(
        file(1, false, 40, MediaDetails(MediaDetails.File, attachmentId = "pdf-in", label = "Lease agreement 2026.pdf", mime = "application/pdf", size = 248_000, pages = 3, thumb = thumb, caption = "Signed copy, have a look")),
        file(2, true, 12, MediaDetails(MediaDetails.File, file = SamplePdf, attachmentId = "pdf-out", label = "Kilimani Court lease.pdf", mime = "application/pdf", size = 5_690)),
        file(3, false, 2, MediaDetails(MediaDetails.File, attachmentId = "a4", label = "Budget.xlsx", mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", size = 48_200)),
    )
    val media = remember(path) {
        ThreadMediaActions(
            canShareLocation = true, onCompose = {}, onLocation = {}, onVoiceStart = {}, onVoiceEnd = {},
            load = { com.circaso.feature.messaging.data.repository.MediaLoad.Failed }, onPlay = {}, onRetry = {},
            onConfirmLocation = {}, onCancelLocation = {},
            openDocument = { path to null },
            localDocument = { d -> if (d.file == SamplePdf) path else null },
        )
    }
    if (thumb == null) return
    ThreadContent(ThreadUiState(chat = chat, messages = messages), {}, {}, null, {}, {}, {}, {}, {}, {}, {}, media = media)
}

@Composable
private fun PdfViewerPreview() {
    val path = samplePdfPath() ?: return
    PdfViewerContent(PdfViewing(path, "Lease agreement 2026.pdf", "application/pdf", 248_000), onClose = {})
}
