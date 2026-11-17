package com.circaso.gallery

import androidx.compose.runtime.Composable
import com.circaso.core.sync.nowMillis
import com.circaso.feature.support.TicketCategory
import com.circaso.feature.support.TicketDetailResponse
import com.circaso.feature.support.TicketDto
import com.circaso.feature.support.TicketMessageDto
import com.circaso.feature.support.TicketStatus
import com.circaso.feature.support.presentation.HelpContent
import com.circaso.feature.support.presentation.HelpUiState
import com.circaso.feature.support.presentation.NewTicketContent
import com.circaso.feature.support.presentation.NewTicketUiState
import com.circaso.feature.support.presentation.TicketContent
import com.circaso.feature.support.presentation.TicketUiState
import com.circaso.feature.support.presentation.TicketsContent
import com.circaso.feature.support.presentation.TicketsUiState
import kotlin.time.Instant

private fun ago(minutes: Int) = Instant.fromEpochMilliseconds(nowMillis() - minutes * 60_000L).toString()

private fun ticket(n: Int, subject: String, status: String, minutes: Int, category: TicketCategory = TicketCategory.Payments, asha: Boolean = false) = TicketDto(
    ticketId = "t$n", number = "CS-" + n.toString().padStart(6, '0'), subject = subject, category = category.wire, status = status,
    source = if (asha) "ASHA" else "APP", waitingOn = TicketStatus.waitingOn(status), createdAt = ago(minutes + 600), lastMessageAt = ago(minutes),
)

private val sampleTickets = listOf(
    ticket(123, "Payment to Baraka not received", TicketStatus.PendingUser, 12),
    ticket(118, "Can't see my Base network balance", TicketStatus.Open, 95, TicketCategory.Wallet, asha = true),
    ticket(97, "New phone, same number", TicketStatus.Resolved, 60 * 26, TicketCategory.Account),
    ticket(64, "Treasurer left our chama and we can't pay out the December contribution", TicketStatus.Closed, 60 * 24 * 9, TicketCategory.Groups),
)

private fun detail(status: String, asha: Boolean = false) = TicketDetailResponse(
    ticket = ticket(123, "Payment to Baraka not received", status, 12, asha = asha),
    description = "I sent 20 USDC on Base yesterday. My wallet says confirmed but Baraka has nothing.",
    messages = listOf(
        TicketMessageDto("m1", "STAFF", "Circaso Support", "Thanks! We're checking with the network. Did Baraka give you the address himself?", ago(50)),
        TicketMessageDto("m2", "USER", null, "Yes, he sent it in our chat.", ago(41)),
        TicketMessageDto("m3", "ASHA", "Asha", "I checked the payment: it **confirmed on Base** at 14:02. The team is following up with Baraka's wallet.", ago(30)),
        TicketMessageDto("m4", "STAFF", "Circaso Support", "It has landed in Baraka's wallet now. Can you ask him to refresh?", ago(12)),
    ),
)

/** Support tickets with sample data: `--es preview <name>`. */
@Composable
internal fun TicketPreview(name: String): Boolean {
    when (name) {
        "tickets" -> TicketsContent(TicketsUiState(tickets = sampleTickets, loaded = true), {}, {}, {}, {}, {})
        "tickets-more" -> TicketsContent(TicketsUiState(tickets = sampleTickets, loaded = true, hasMore = true, loadingMore = true), {}, {}, {}, {}, {})
        "tickets-offline" -> TicketsContent(TicketsUiState(tickets = sampleTickets, loaded = true, online = false), {}, {}, {}, {}, {})
        "tickets-long" -> TicketsContent(
            TicketsUiState(tickets = listOf(ticket(131, "Treasurer left our chama and we can't pay out the December contribution to anyone", TicketStatus.PendingUser, 3, TicketCategory.Groups, asha = true)) + sampleTickets, loaded = true),
            {}, {}, {}, {}, {},
        )
        "tickets-loading" -> TicketsContent(TicketsUiState(refreshing = true), {}, {}, {}, {}, {})
        "tickets-empty" -> TicketsContent(TicketsUiState(loaded = true), {}, {}, {}, {}, {})
        "tickets-error" -> TicketsContent(TicketsUiState(error = "No connection. Check your internet and try again.", online = false), {}, {}, {}, {}, {})
        "ticket-new" -> NewTicketContent(
            NewTicketUiState(category = TicketCategory.Payments, subject = "Payment to Baraka not received", description = "I sent 20 USDC on Base yesterday. My wallet says confirmed but Baraka has nothing."),
            {}, {}, {}, {}, {},
        )
        "ticket-new-empty" -> NewTicketContent(NewTicketUiState(), {}, {}, {}, {}, {})
        "ticket-new-problems" -> NewTicketContent(NewTicketUiState(subject = "Hi", description = "Help", tried = true), {}, {}, {}, {}, {})
        "ticket-new-category" -> NewTicketContent(NewTicketUiState(category = TicketCategory.Wallet), {}, {}, {}, {}, {}, pickingCategory = true)
        "ticket-new-sending" -> NewTicketContent(NewTicketUiState(category = TicketCategory.Other, subject = "App closes when I open Moments", description = "It happens every time since this morning.", busy = true), {}, {}, {}, {}, {})
        "ticket-new-offline" -> NewTicketContent(NewTicketUiState(category = TicketCategory.Other, subject = "App closes when I open Moments", description = "It happens every time since this morning.", online = false), {}, {}, {}, {}, {})
        "ticket-new-limit" -> NewTicketContent(
            NewTicketUiState(category = TicketCategory.Other, subject = "App closes when I open Moments", description = "It happens every time since this morning.", error = "You've opened the most tickets you can for now. Try again in about 3 hours, or reply on one you already have. Ref 01JD8Q2W"),
            {}, {}, {}, {}, {},
        )
        "ticket-open" -> TicketContent(TicketUiState(detail = detail(TicketStatus.Open).let { it.copy(messages = it.messages.take(2)) }), {}, {}, {}, {}, {})
        "ticket-pending-user" -> TicketContent(TicketUiState(detail = detail(TicketStatus.PendingUser), draft = "He has it now, thank you!"), {}, {}, {}, {}, {})
        "ticket-sending" -> TicketContent(TicketUiState(detail = detail(TicketStatus.PendingUser), pending = "He has it now, thank you!"), {}, {}, {}, {}, {})
        "ticket-send-failed" -> TicketContent(TicketUiState(detail = detail(TicketStatus.PendingUser), draft = "He has it now, thank you!", sendError = "No connection. Check your internet and try again."), {}, {}, {}, {}, {})
        "ticket-resolved" -> TicketContent(TicketUiState(detail = detail(TicketStatus.Resolved)), {}, {}, {}, {}, {})
        "ticket-closed" -> TicketContent(TicketUiState(detail = detail(TicketStatus.Closed)), {}, {}, {}, {}, {})
        "ticket-asha" -> TicketContent(TicketUiState(detail = detail(TicketStatus.Open, asha = true).copy(messages = emptyList())), {}, {}, {}, {}, {})
        "ticket-stale" -> TicketContent(TicketUiState(detail = detail(TicketStatus.Open), loadError = "Something went wrong on our side. Please try again. Ref 01JD8Q2W"), {}, {}, {}, {}, {})
        "ticket-loading" -> TicketContent(TicketUiState(loading = true), {}, {}, {}, {}, {})
        "ticket-error" -> TicketContent(TicketUiState(loadError = "We couldn't find that ticket."), {}, {}, {}, {}, {})
        "ticket-offline" -> TicketContent(TicketUiState(detail = detail(TicketStatus.PendingUser), online = false), {}, {}, {}, {}, {})
        "help-tickets" -> HelpContent(HelpUiState(tickets = sampleTickets.take(3), moreTickets = true), {}, {}, {}, null, onSendLog = {})
        else -> return false
    }
    return true
}
