package com.circaso.core.notifications

import com.circaso.core.navigation.ActivityRoute
import com.circaso.core.navigation.AshaRoute
import com.circaso.core.navigation.ChatRoute
import com.circaso.core.navigation.ContactsRoute
import com.circaso.core.navigation.EditProfileRoute
import com.circaso.core.navigation.MomentViewerRoute
import com.circaso.core.navigation.NotificationsRoute
import com.circaso.core.navigation.SecurityRoute
import com.circaso.core.navigation.TransferRoute
import com.circaso.core.notifications.NotificationDestination.Companion.toExtras
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationRoutingTest {
    private class Lookups(
        val messages: Map<String, Long> = emptyMap(),
        val cards: Map<Pair<String, String>, Long> = emptyMap(),
        val calls: Map<String, Long> = emptyMap(),
        val paymentChats: Map<String, String> = emptyMap(),
    ) : NotificationLookups {
        override suspend fun messageLocalId(messageId: String) = messages[messageId]
        override suspend fun paymentCardLocalId(chatId: String, transferId: String) = cards[chatId to transferId]
        override suspend fun lastCallLocalId(chatId: String) = calls[chatId]
        override suspend fun chatOfPayment(key: String) = paymentChats[key]
    }

    private val lookups = Lookups(
        messages = mapOf("m1" to 11L),
        cards = mapOf(("c1" to "t1") to 21L),
        calls = mapOf("c1" to 31L),
        paymentChats = mapOf("t2" to "c2"),
    )

    private suspend fun open(d: NotificationDestination) = NotificationRoutes.resolve(d, lookups)

    @Test fun aMessageOpensItsChatAtThatMessage() = runTest {
        assertEquals(listOf(ChatRoute("c1", 11L)), open(NotificationDestination.Chat("c1", "m1")).routes)
        assertEquals(listOf(ChatRoute("c1", null)), open(NotificationDestination.Chat("c1", "unknown")).routes)
        assertEquals(listOf(ChatRoute("c1", 5L)), open(NotificationDestination.Chat("c1", localId = 5L)).routes)
    }

    @Test fun moneyOpensTheChatAtItsCardElseItsDetails() = runTest {
        assertEquals(listOf(ChatRoute("c1", 21L)), open(NotificationDestination.Payment("c1", "t1")).routes)
        // The wallet's record names the chat the push didn't.
        assertEquals(listOf(ChatRoute("c2", null)), open(NotificationDestination.Payment(null, "t2")).routes)
        // No chat: the payment's details; money from outside Circaso by its history row.
        assertEquals(listOf(TransferRoute("t9")), open(NotificationDestination.Payment(null, "t9")).routes)
        assertEquals(listOf(TransferRoute("tx1")), open(NotificationDestination.Payment(null, null, "tx1")).routes)
        assertEquals(listOf(ActivityRoute), open(NotificationDestination.Payment()).routes)
    }

    @Test fun everythingElseOpensWhereItCanBeRead() = runTest {
        assertEquals(listOf(ChatRoute("c1", 31L)), open(NotificationDestination.MissedCall("c1", "call1")).routes)
        assertEquals(listOf(NotificationsRoute), open(NotificationDestination.MissedCall(null)).routes)
        assertEquals(listOf(MomentViewerRoute("u1", "mo1")), open(NotificationDestination.Moment("u1", "mo1")).routes)
        assertEquals(listOf(MomentViewerRoute(null)), open(NotificationDestination.Moment()).routes)
        assertEquals(listOf(AshaRoute), open(NotificationDestination.Asha).routes)
        assertEquals(listOf(SecurityRoute), open(NotificationDestination.Security).routes)
        assertEquals(listOf(ContactsRoute), open(NotificationDestination.Contacts).routes)
        assertEquals(listOf(EditProfileRoute), open(NotificationDestination.Profile).routes)
        assertEquals(NotificationOpening(emptyList(), HomeTabName.Wallet), open(NotificationDestination.Wallet))
        assertEquals(listOf(NotificationsRoute), open(NotificationDestination.Inbox).routes)
    }

    @Test fun pushDataNamesTheDestination() {
        fun push(vararg pairs: Pair<String, String>) = NotificationDestination.fromPush(mapOf(*pairs))
        assertEquals(NotificationDestination.Chat("c1", "m1"), push("chatId" to "c1", "messageId" to "m1"))
        assertEquals(NotificationDestination.Chat("c1"), push("chatId" to "c1"))
        assertEquals(NotificationDestination.Payment("c1", "t1", "tx1"), push("kind" to "payment.received", "chatId" to "c1", "transferId" to "t1", "transactionId" to "tx1"))
        assertEquals(NotificationDestination.Payment(null, "t1"), push("kind" to "transfer.updated", "transferId" to "t1"))
        assertEquals(NotificationDestination.Chat("g1"), push("kind" to "chama.contribution", "chatId" to "g1", "userId" to "u1"))
        assertEquals(NotificationDestination.Chat("g1"), push("kind" to "chama.request.paid", "chatId" to "g1", "requestId" to "r1"))
        assertEquals(NotificationDestination.MissedCall("c1", "call1"), push("kind" to "call.missed", "chatId" to "c1", "callId" to "call1"))
        assertNull(push("kind" to "call.ringing", "chatId" to "c1", "callId" to "call1"))
        assertEquals(NotificationDestination.Moment(null), push("kind" to "moment.loved"))
        assertEquals(NotificationDestination.Contacts, push("kind" to "contact.joined", "userId" to "u1"))
        assertEquals(NotificationDestination.Wallet, push("kind" to "wallet.setup"))
        assertEquals(NotificationDestination.Security, push("kind" to "security.new_device"))
        assertEquals(NotificationDestination.Asha, push("kind" to "asha.reply"))
        assertEquals(NotificationDestination.Inbox, push("kind" to "something.new"))
        assertEquals(NotificationDestination.Inbox, push("google.message_id" to "0:123"))
        assertNull(push("kind" to "session.revoked"))
        // An ordinary launch has nothing to open.
        assertNull(push())
        assertNull(push("profile" to "debug"))
    }

    @Test fun anAppMadeNotificationRoundTripsThroughItsExtras() {
        val all = listOf(
            NotificationDestination.Chat("c1", "m1", 7L),
            NotificationDestination.Payment("c1", "t1", "tx1"),
            NotificationDestination.Payment(null, null, "tx1"),
            NotificationDestination.MissedCall("c1", "call1"),
            NotificationDestination.Moment("u1", "mo1"),
            NotificationDestination.Moment(),
            NotificationDestination.Asha, NotificationDestination.Security, NotificationDestination.Contacts,
            NotificationDestination.Wallet, NotificationDestination.Profile, NotificationDestination.NeedsAttention, NotificationDestination.Inbox,
        )
        all.forEach { d -> assertEquals(d, NotificationDestination.fromExtras(d.toExtras())) }
        // The launcher's own extras next to them don't get in the way.
        assertEquals(NotificationDestination.Asha, NotificationDestination.fromExtras(NotificationDestination.Asha.toExtras() + ("profile" to "x")))
    }

    @Test fun aTapWaitsUntilTheAppTakesIt() {
        assertTrue(NotificationTaps.openFrom(NotificationDestination.Asha.toExtras()))
        assertEquals(NotificationDestination.Asha, NotificationTaps.pending.value)
        // Something newer replaced it: taking the old one leaves the new one waiting.
        NotificationTaps.open(NotificationDestination.Security)
        NotificationTaps.consumed(NotificationDestination.Asha)
        assertEquals(NotificationDestination.Security, NotificationTaps.pending.value)
        NotificationTaps.consumed(NotificationDestination.Security)
        assertNull(NotificationTaps.pending.value)
        assertEquals(false, NotificationTaps.openFrom(emptyMap()))
    }
}
