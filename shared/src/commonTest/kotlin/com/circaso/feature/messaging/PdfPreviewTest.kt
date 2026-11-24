package com.circaso.feature.messaging

import com.circaso.core.crypto.ChatPayload
import com.circaso.core.platform.isPdf
import com.circaso.feature.messaging.data.repository.MediaDetails
import com.circaso.feature.messaging.data.repository.toMediaDetails
import com.circaso.feature.messaging.data.repository.toPayload
import com.circaso.feature.messaging.presentation.drawWidth
import com.circaso.feature.messaging.presentation.pdfFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PDFs in chats (2026-11-24): what the message carries, what a phone keeps from it, and how pages are sized. */
class PdfPreviewTest {
    private val pdf = MediaDetails(MediaDetails.File, attachmentId = "a1", key = "k", digest = "d", size = 248_000, mime = "application/pdf", label = "Lease.pdf", thumb = "AAAA", pages = 3)

    @Test
    fun aPdfTravelsWithItsFirstPageAndPageCount() {
        val payload = pdf.toPayload("2026-11-24T10:00:00Z")
        assertEquals(ChatPayload.File, payload.type)
        assertEquals("AAAA", payload.thumb)
        assertEquals(3, payload.pages)
        val back = payload.toMediaDetails()!!
        assertEquals("AAAA", back.thumb)
        assertEquals(3, back.pages)
    }

    @Test
    fun onlyFilesCarryPagesAndOversizedThumbsAreDropped() {
        assertNull(pdf.copy(kind = MediaDetails.Image).toPayload("t").pages)
        val image = ChatPayload(type = ChatPayload.Image, clientSentAt = "t", attachmentId = "a", mediaKey = "k", digest = "d", pages = 4, thumb = "AAAA")
        assertNull(image.toMediaDetails()!!.pages)
        assertNull(image.toMediaDetails()!!.thumb) // a photo's own thumb isn't kept from the message
        val huge = pdf.toPayload("t").copy(thumb = "A".repeat(40_000), pages = 0)
        val kept = huge.toMediaDetails()!!
        assertNull(kept.thumb)
        assertNull(kept.pages)
    }

    @Test
    fun aPdfIsKnownByItsTypeOrItsName() {
        assertTrue(isPdf("x", "application/pdf"))
        assertTrue(isPdf("Lease.PDF", null))
        assertTrue(isPdf("Lease.pdf", "application/octet-stream"))
        assertFalse(isPdf("Lease.pdf", "image/png"))
        assertFalse(isPdf("Budget.xlsx", "application/vnd.ms-excel"))
    }

    @Test
    fun factsReadNaturally() {
        assertEquals("3 pages · 242 KB", pdfFacts(3, 248_000))
        assertEquals("1 page", pdfFacts(1, null))
        assertNull(pdfFacts(null, null))
    }

    @Test
    fun aPageNeverCostsMoreThanItsBudget() {
        // A4 on a 1080 px phone: 1× fitted, then 2× once zoomed.
        assertEquals(1048, drawWidth(1048f, 1f, 1.414f))
        assertEquals(2096, drawWidth(1048f, 3f, 1.414f))
        // A very tall page (a receipt) is drawn narrower so it stays within about 8 million pixels.
        val w = drawWidth(1048f, 5f, 10f)
        assertTrue(w.toLong() * (w * 10L) <= 8_100_000, "$w")
    }
}
