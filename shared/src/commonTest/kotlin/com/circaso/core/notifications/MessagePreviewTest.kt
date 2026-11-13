package com.circaso.core.notifications

import com.circaso.feature.messaging.data.local.MessageKind
import com.circaso.feature.messaging.data.local.MessageStatus
import com.circaso.feature.messaging.data.repository.MediaDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessagePreviewTest {
    private fun media(d: MediaDetails, kind: String) = MessagePreview.of(kind, MediaDetails.write(d))

    @Test fun textReadsAsWritten() {
        assertEquals("Are we still on for 6?", MessagePreview.of(MessageKind.Text, "  Are we still on for 6? "))
        assertNull(MessagePreview.of(MessageKind.Text, "   "))
    }

    @Test fun mediaReadsAsItsPlaceholderNeverItsJson() {
        assertEquals("📷 Photo", media(MediaDetails(MediaDetails.Image, attachmentId = "a", key = "k"), MessageKind.Image))
        assertEquals("📷 Sunset at Diani", media(MediaDetails(MediaDetails.Image, caption = "Sunset at Diani"), MessageKind.Image))
        assertEquals("🎥 Video (0:42)", media(MediaDetails(MediaDetails.Video, durationMs = 42_400), MessageKind.Video))
        assertEquals("🎤 Voice message (1:05)", media(MediaDetails(MediaDetails.Voice, durationMs = 65_000), MessageKind.Voice))
        assertEquals("🎤 Voice message", media(MediaDetails(MediaDetails.Voice), MessageKind.Voice))
        assertEquals("📄 Lease.pdf", media(MediaDetails(MediaDetails.File, label = "Lease.pdf"), MessageKind.File))
        assertEquals("📄 Document", media(MediaDetails(MediaDetails.File), MessageKind.File))
        assertEquals("📍 Java House", media(MediaDetails(MediaDetails.Location, latitude = 1.0, longitude = 2.0, label = "Java House"), MessageKind.Location))
        assertEquals("📍 Location", media(MediaDetails(MediaDetails.Location, latitude = 1.0, longitude = 2.0), MessageKind.Location))
        assertEquals("📍 Live location", media(MediaDetails(MediaDetails.Location, liveId = "l1"), MessageKind.Location))
    }

    @Test fun nothingToSayFallsBackToNewMessage() {
        assertNull(MessagePreview.of(MessageKind.Text, null, MessageStatus.Undecryptable))
        assertNull(MessagePreview.of(MessageKind.Image, "not json"))
        assertNull(MessagePreview.of(MessageKind.System, "Amina's security code changed"))
        val event = NotificationEvent.ChatMessage(null, group = false, sender = "Amina", text = null)
        assertEquals(NotificationContent("Amina", "New message"), NotificationText.compose(event, false, null))
        assertEquals("New message", NotificationText.chatLine(event))
    }

    @Test fun directChatsAreTitledBySenderGroupsByGroup() {
        val direct = NotificationEvent.ChatMessage(null, group = false, sender = "Amina Wanjiku", text = "📷 Photo")
        assertEquals(NotificationContent("Amina Wanjiku", "📷 Photo"), NotificationText.compose(direct, false, null))
        val group = NotificationEvent.ChatMessage("Holiday Fund", group = true, sender = "Otieno", text = "🎤 Voice message (0:12)")
        assertEquals(NotificationContent("Holiday Fund", "Otieno: 🎤 Voice message (0:12)"), NotificationText.compose(group, false, null))
        // The conversation line leaves the sender out: Android and iOS name them beside it.
        assertEquals("🎤 Voice message (0:12)", NotificationText.chatLine(group))
    }

    @Test fun momentRepliesSayWhatTheyAre() {
        val event = NotificationEvent.ChatMessage(null, group = false, sender = "Amina", text = "😂 love it", momentReply = true)
        assertEquals("Replied to your moment: 😂 love it", NotificationText.chatLine(event))
    }

    @Test fun privacyKeepsTheGenericWords() {
        val event = NotificationEvent.ChatMessage("Holiday Fund", group = true, sender = "Otieno", text = "📷 Photo")
        assertEquals(NotificationContent("Circaso", "New message"), NotificationText.compose(event, true, null))
    }
}
