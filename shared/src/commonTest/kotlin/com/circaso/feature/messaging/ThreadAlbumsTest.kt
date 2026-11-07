package com.circaso.feature.messaging

import com.circaso.core.crypto.ChatPayload
import com.circaso.feature.messaging.data.repository.MediaDetails
import com.circaso.feature.messaging.data.repository.toMediaDetails
import com.circaso.feature.messaging.data.repository.toPayload
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.messaging.presentation.AlbumGapMs
import com.circaso.feature.messaging.presentation.AlbumGrid
import com.circaso.feature.messaging.presentation.ThreadRow
import com.circaso.feature.messaging.presentation.albumCaption
import com.circaso.feature.messaging.presentation.albumGrid
import com.circaso.feature.messaging.presentation.albumProgress
import com.circaso.feature.messaging.presentation.albumState
import com.circaso.feature.messaging.presentation.threadRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private fun shot(
    id: Long, album: String?, at: Long = id * 1_000, from: String = "me", video: Boolean = false,
    state: DeliveryState = DeliveryState.Read, caption: String? = null, kind: String = if (video) MediaDetails.Video else MediaDetails.Image,
) = Message.Media(
    id, "c1", at, null, "m$id", from, if (from == "me") "Me" else "Wanjiru", from == "me",
    MediaDetails(kind, attachmentId = "a$id", album = album, caption = caption), state, null,
)

private fun text(id: Long, at: Long = id * 1_000) = Message.Text(id, "c1", at, null, "m$id", "me", "Me", true, "hi", DeliveryState.Read, null, null)

class ThreadAlbumsTest {
    @Test fun consecutiveItemsOfOneAlbumAreOneRow() {
        val rows = threadRows(listOf(text(1), shot(2, "A"), shot(3, "A", video = true), shot(4, "A"), text(5)))
        assertEquals(3, rows.size)
        val album = assertIs<ThreadRow.Album>(rows[1])
        assertEquals(listOf(2L, 3L, 4L), album.items.map { it.localId })
        assertEquals(1, album.index)
        // Keyed by its first item, so it stays put as later items arrive.
        assertEquals(2L, album.key)
    }

    @Test fun anotherMessageInBetweenBreaksTheRun() {
        val rows = threadRows(listOf(shot(1, "A"), shot(2, "A"), text(3), shot(4, "A"), shot(5, "A")))
        assertEquals(listOf(2, 1, 2), rows.map { (it as? ThreadRow.Album)?.items?.size ?: 1 })
    }

    @Test fun aGapInTimeBreaksTheRun() {
        val rows = threadRows(listOf(shot(1, "A", at = 0), shot(2, "A", at = 1_000), shot(3, "A", at = 1_000 + AlbumGapMs + 1)))
        assertEquals(2, rows.size)
        assertEquals(2, (rows[0] as ThreadRow.Album).items.size)
        assertIs<ThreadRow.Single>(rows[1])
    }

    @Test fun differentAlbumsSendersAndLoneItemsStayApart() {
        val rows = threadRows(listOf(shot(1, "A"), shot(2, "B"), shot(3, "B", from = "them"), shot(4, null), shot(5, null)))
        // An album of one is a plain photo; no album id is never grouped.
        assertEquals(5, rows.size)
        rows.forEach { assertIs<ThreadRow.Single>(it) }
    }

    @Test fun documentsAndVoiceNotesAreNeverInAnAlbum() {
        val rows = threadRows(listOf(shot(1, "A"), shot(2, "A", kind = MediaDetails.File), shot(3, "A")))
        assertEquals(3, rows.size)
    }

    @Test fun theGridForTwoThreeFourAndMore() {
        assertEquals(AlbumGrid(listOf(listOf(0, 1)), 0), albumGrid(2))
        assertEquals(AlbumGrid(listOf(listOf(0), listOf(1, 2)), 0), albumGrid(3))
        assertEquals(AlbumGrid(listOf(listOf(0, 1), listOf(2, 3)), 0), albumGrid(4))
        // Seven: the first four, "+3" on the fourth.
        assertEquals(AlbumGrid(listOf(listOf(0, 1), listOf(2, 3)), 3), albumGrid(7))
    }

    @Test fun anAlbumGoesAsItsItemsGo() {
        val sending = listOf(shot(1, "A", state = DeliveryState.Read), shot(2, "A", state = DeliveryState.Sending), shot(3, "A", state = DeliveryState.Waiting), shot(4, "A", state = DeliveryState.Waiting))
        assertEquals(DeliveryState.Sending, albumState(sending))
        // One sent, one half up, two not started: (1 + 0.5) / 4.
        assertEquals(0.375f, albumProgress(sending) { if (it == 2L) 0.5f else null })
        assertNull(albumProgress(sending) { null })
        assertEquals(DeliveryState.Failed, albumState(sending + shot(5, "A", state = DeliveryState.Failed)))
        assertEquals(DeliveryState.Delivered, albumState(listOf(shot(1, "A"), shot(2, "A", state = DeliveryState.Delivered))))
    }

    @Test fun oneCaptionForTheAlbum() {
        assertEquals("Graduation", albumCaption(listOf(shot(1, "A"), shot(2, "A", caption = "Graduation"), shot(3, "A", caption = "later"))))
        assertNull(albumCaption(listOf(shot(1, "A"), shot(2, "A", caption = " "))))
    }

    @Test fun theAlbumIdTravelsInsideTheEncryptedPayloadAndOnlyOnPhotosAndVideos() {
        val d = MediaDetails(MediaDetails.Video, attachmentId = "a", key = "k", digest = "d", album = "abc123")
        val payload = d.toPayload("2026-11-07T10:00:00Z")
        assertEquals("abc123", payload.albumId)
        assertEquals("abc123", payload.toMediaDetails()?.album)
        assertNull(MediaDetails(MediaDetails.File, attachmentId = "a", key = "k", digest = "d", album = "abc123").toPayload("x").albumId)
        // An album id on a voice note from someone else is ignored.
        assertNull(ChatPayload(type = ChatPayload.Voice, attachmentId = "a", mediaKey = "k", digest = "d", albumId = "x").toMediaDetails()?.album)
    }
}
