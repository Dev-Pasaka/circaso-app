package com.circaso.feature.support

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.Diagnostics
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.call
import com.circaso.core.network.idempotencyKey
import com.circaso.core.network.newIdempotencyKey
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A support ticket as the server lists it (guide → Support tickets, 2026-11-08). */
@Serializable data class TicketDto(
    val ticketId: String,
    /** `CS-000123`: what the person reads out to support; on every ticket screen. */
    val number: String,
    val subject: String,
    val category: String = TicketCategory.Other.wire,
    /** `OPEN` | `PENDING_USER` | `RESOLVED` | `CLOSED`. */
    val status: String = TicketStatus.Open,
    /** `APP` | `ASHA`. */
    val source: String = "APP",
    /** `STAFF` | `USER`; null once resolved or closed. */
    val waitingOn: String? = null,
    val transferId: String? = null,
    val chatId: String? = null,
    val createdAt: String = "",
    /** Moves with every reply either way: the list sorts and badges by it. */
    val lastMessageAt: String = "",
) {
    /** Support replied and it is the person's turn. */
    val replyNeeded: Boolean get() = waitingOn == "USER"
    val openedByAsha: Boolean get() = source == "ASHA"
    val closed: Boolean get() = status == TicketStatus.Closed
    val resolved: Boolean get() = status == TicketStatus.Resolved
}

/** One reply. [author] is `USER` (the person), `STAFF` or `ASHA`; [authorName] is null for the person's own. */
@Serializable data class TicketMessageDto(
    val messageId: String,
    val author: String,
    val authorName: String? = null,
    val body: String,
    val createdAt: String = "",
) {
    val mine: Boolean get() = author == "USER"
}

/** SU1, SU3 and SU4 answer this: the ticket, what the person wrote first, and the replies oldest first. */
@Serializable data class TicketDetailResponse(
    val ticket: TicketDto,
    val description: String = "",
    val messages: List<TicketMessageDto> = emptyList(),
)

/** SU2. */
@Serializable data class TicketsResponse(val tickets: List<TicketDto> = emptyList(), val nextCursor: String? = null)

/** SU1. */
@Serializable data class OpenTicketRequest(
    val subject: String,
    val description: String,
    val category: String,
    val transferId: String? = null,
    val chatId: String? = null,
)

/** SU4. */
@Serializable data class TicketReplyRequest(val body: String)

object TicketStatus {
    const val Open = "OPEN"
    const val PendingUser = "PENDING_USER"
    const val Resolved = "RESOLVED"
    const val Closed = "CLOSED"

    /** The status in the person's words; a status this version doesn't know reads as still open. */
    fun label(ticket: TicketDto): String = when (ticket.status) {
        PendingUser -> "Support replied"
        Resolved -> "Resolved"
        Closed -> "Closed"
        else -> if (ticket.replyNeeded) "Support replied" else "With our team"
    }

    /** Who a ticket waits on once its status changes (the frames carry the status only). */
    fun waitingOn(status: String): String? = when (status) {
        Open -> "STAFF"
        PendingUser -> "USER"
        else -> null
    }
}

/** What a ticket is about (SU1's `category`). */
enum class TicketCategory(val wire: String, val label: String) {
    Payments("PAYMENTS", "A payment"),
    Wallet("WALLET", "My wallet"),
    Account("ACCOUNT", "My account or signing in"),
    Groups("GROUPS", "A group"),
    Security("SECURITY", "Security"),
    Asha("ASHA", "Asha"),
    Other("OTHER", "Something else");

    companion object {
        fun of(wire: String?): TicketCategory? = entries.firstOrNull { it.wire == wire }
    }
}

/** The server's limits (SU1, SU4), counted after trimming, so the form says the same thing the server would. */
object TicketRules {
    const val SubjectMin = 3
    const val SubjectMax = 120
    const val DescriptionMin = 10
    const val DescriptionMax = 4_000
    const val ReplyMax = 4_000

    fun subjectOk(subject: String) = subject.trim().length in SubjectMin..SubjectMax && '\n' !in subject.trim()
    fun descriptionOk(description: String) = description.trim().length in DescriptionMin..DescriptionMax
    fun replyOk(body: String) = body.trim().length in 1..ReplyMax
}

/** The person's tickets as last loaded. [loaded] is false until SU2 has answered once. */
data class TicketList(val tickets: List<TicketDto> = emptyList(), val nextCursor: String? = null, val loaded: Boolean = false) {
    val hasMore: Boolean get() = nextCursor != null
}

/** Words for the ticket calls' errors, keyed on the code (never the server's message). */
object TicketErrors {
    fun message(error: AppError): String {
        val api = error as? AppError.Api ?: return ErrorMessages.forError(error)
        return when (api.code) {
            "support.rate_limited" -> "You've opened the most tickets you can for now. " + (api.retryAfterSeconds?.let { "Try again ${wait(it)}" } ?: "Try again tomorrow") + ", or reply on one you already have."
            "request.rate_limited" -> "Too many tries. " + (api.retryAfterSeconds?.let { "Try again ${wait(it)}." } ?: "Please wait a moment.")
            "support.ticket_closed" -> "This ticket is closed, so it can't take more replies. Open a new ticket instead."
            "support.ticket_not_found" -> "We couldn't find that ticket."
            "support.subject_invalid" -> "The subject needs ${TicketRules.SubjectMin} to ${TicketRules.SubjectMax} characters on one line."
            "support.description_invalid" -> "Tell us what happened in ${TicketRules.DescriptionMin} to 4,000 characters."
            "support.body_invalid" -> "A reply needs 1 to 4,000 characters."
            "support.category_invalid" -> "Choose what it's about, then send again."
            "support.context_invalid" -> "We couldn't attach that payment or chat to the ticket. Send it without, and tell us about it in your own words."
            else -> ErrorMessages.forError(error)
        }
    }

    /** The message with the reference support can look up (the request id, and a code we have no words for). */
    fun withReference(error: AppError): String {
        val known = (error as? AppError.Api)?.code in Known
        val ref = if (known) (error as AppError.Api).requestId else ErrorMessages.reference(error)
        return message(error) + (ref?.let { " Ref $it" } ?: "")
    }

    /** "in 5 minutes", "in about 3 hours", "tomorrow": when a rate limit lifts, rounded up so it is never too early. */
    internal fun wait(seconds: Int): String = when {
        seconds <= 60 -> "in a minute"
        seconds < 3_600 -> "in ${(seconds + 59) / 60} minutes"
        seconds < 5_400 -> "in about an hour"
        seconds < 20 * 3_600 -> "in about ${(seconds + 1_800) / 3_600} hours"
        else -> "tomorrow"
    }

    private val Known = setOf(
        "support.rate_limited", "request.rate_limited", "support.ticket_closed", "support.ticket_not_found", "support.subject_invalid",
        "support.description_invalid", "support.body_invalid", "support.category_invalid", "support.context_invalid",
    )
}

/**
 * The person's support tickets (SU1–SU4). Online only: nothing is queued and nothing is stored on the phone. What was
 * last loaded is kept in memory ([list], [details]) so the list and a ticket show at once when opened again, then
 * refresh. Cleared when the person signs out ([clear]).
 *
 * A submit that may have reached the server (no answer, a 5xx, `request.in_progress`) keeps its `Idempotency-Key`:
 * sending the same thing again carries the same key, so a ticket or a reply is never made twice. A refusal the server
 * decided (4xx) or a change to what is sent starts a new key.
 */
class SupportTickets(private val api: CircasoApi, private val newKey: () -> String = ::newIdempotencyKey) {
    private val _list = MutableStateFlow(TicketList())
    val list: StateFlow<TicketList> = _list.asStateFlow()

    private val _details = MutableStateFlow<Map<String, TicketDetailResponse>>(emptyMap())
    /** Each ticket as last read, by id. */
    val details: StateFlow<Map<String, TicketDetailResponse>> = _details.asStateFlow()

    /** The tickets on screen now: a frame about one of them reloads it (SU3). */
    private val watched = MutableStateFlow<List<String>>(emptyList())
    private val listLock = Mutex()
    private var openAttempt: Pair<OpenTicketRequest, String>? = null
    private var replyAttempt: Pair<Pair<String, String>, String>? = null

    fun watch(ticketId: String) = watched.update { it + ticketId }
    fun unwatch(ticketId: String) = watched.update { it - ticketId }

    /** SU2 from the top: replaces what was listed. */
    suspend fun refresh(): AppResult<Unit> = listLock.withLock {
        when (val r = page(null)) {
            is AppResult.Success -> { _list.value = TicketList(sorted(r.value.tickets), r.value.nextCursor, loaded = true); AppResult.Success(Unit) }
            is AppResult.Failure -> r
        }
    }

    /** SU2's next page, added under what is listed. Nothing to do once the last page is in. */
    suspend fun loadMore(): AppResult<Unit> = listLock.withLock {
        val cursor = _list.value.nextCursor ?: return@withLock AppResult.Success(Unit)
        when (val r = page(cursor)) {
            is AppResult.Success -> {
                _list.update { now ->
                    val known = now.tickets.map { it.ticketId }.toSet()
                    now.copy(tickets = now.tickets + r.value.tickets.filter { it.ticketId !in known }, nextCursor = r.value.nextCursor)
                }
                AppResult.Success(Unit)
            }
            is AppResult.Failure -> r
        }
    }

    private suspend fun page(cursor: String?): AppResult<TicketsResponse> = api.call<TicketsResponse> {
        method = HttpMethod.Get; url("/v1/support/tickets")
        parameter("limit", PageSize)
        if (cursor != null) parameter("cursor", cursor)
    }

    /** SU3. */
    suspend fun load(ticketId: String): AppResult<TicketDetailResponse> =
        api.call<TicketDetailResponse> { method = HttpMethod.Get; url("/v1/support/tickets/$ticketId") }.also { remember(it) }

    /** SU1. The new ticket goes to the top of the list. */
    suspend fun open(request: OpenTicketRequest): AppResult<TicketDetailResponse> {
        val key = openAttempt?.takeIf { it.first == request }?.second ?: newKey()
        openAttempt = request to key
        Diagnostics.note("support: opening a ticket")
        val result = api.call<TicketDetailResponse> {
            method = HttpMethod.Post; url("/v1/support/tickets"); idempotencyKey(key)
            contentType(ContentType.Application.Json); setBody(request)
        }
        if (!mayHaveRun(result)) openAttempt = null
        return result.also { remember(it) }
    }

    /** SU4. The answer is the ticket's new state (a resolved ticket is open again). */
    suspend fun reply(ticketId: String, body: String): AppResult<TicketDetailResponse> {
        val what = ticketId to body
        val key = replyAttempt?.takeIf { it.first == what }?.second ?: newKey()
        replyAttempt = what to key
        val result = api.call<TicketDetailResponse> {
            method = HttpMethod.Post; url("/v1/support/tickets/$ticketId/replies"); idempotencyKey(key)
            contentType(ContentType.Application.Json); setBody(TicketReplyRequest(body))
        }
        if (!mayHaveRun(result)) replyAttempt = null
        return result.also { remember(it) }
    }

    /**
     * The `support` socket frames. `ticket.replied` and `ticket.updated` carry `ticketId`, `number` and `status`: the
     * ticket on screen is read again (SU3); any other is marked in the list, or the list is reloaded when it isn't
     * there (a ticket Asha just opened). `resync` reloads the list.
     */
    suspend fun onFrame(type: String, data: JsonObject?) {
        if (type == "resync") { reloadAll(); return }
        if (type != "ticket.replied" && type != "ticket.updated") return
        data ?: return
        val ticketId = (data["ticketId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return
        val status = (data["status"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        if (ticketId in watched.value) { load(ticketId); return }
        val listed = _list.value.tickets.any { it.ticketId == ticketId }
        if (status != null) {
            val mark = { t: TicketDto -> t.copy(status = status, waitingOn = TicketStatus.waitingOn(status)) }
            _list.update { now -> now.copy(tickets = now.tickets.map { if (it.ticketId == ticketId) mark(it) else it }) }
            // The copy read earlier keeps its replies until it is opened (and read again); its status is already right.
            _details.update { all -> all[ticketId]?.let { all + (ticketId to it.copy(ticket = mark(it.ticket))) } ?: all }
        }
        if (_list.value.loaded && (!listed || status == null)) refresh()
    }

    private suspend fun reloadAll() {
        refresh()
        watched.value.distinct().forEach { load(it) }
    }

    /** Signed out: the next person never sees these. */
    fun clear() {
        _list.value = TicketList()
        _details.value = emptyMap()
        openAttempt = null
        replyAttempt = null
    }

    private fun remember(result: AppResult<TicketDetailResponse>) {
        val detail = (result as? AppResult.Success)?.value ?: return
        _details.update { it + (detail.ticket.ticketId to detail) }
        _list.update { now ->
            when {
                now.tickets.any { it.ticketId == detail.ticket.ticketId } -> now.copy(tickets = sorted(now.tickets.map { if (it.ticketId == detail.ticket.ticketId) detail.ticket else it }))
                // A new ticket is the newest; before the first load there is no list to add it to.
                now.loaded -> now.copy(tickets = listOf(detail.ticket) + now.tickets)
                else -> now
            }
        }
    }

    /** Newest activity first, as the server lists them (ISO-8601 UTC sorts as text). */
    private fun sorted(tickets: List<TicketDto>) = tickets.sortedByDescending { it.lastMessageAt }

    /** True when the server may have done it, or still be doing it: the same submit again must carry the same key. */
    private fun mayHaveRun(result: AppResult<*>): Boolean = when (val error = (result as? AppResult.Failure)?.error) {
        null -> false
        AppError.Network -> true
        is AppError.Unexpected -> true
        is AppError.Api -> error.httpStatus >= 500 || error.code == "request.in_progress"
    }

    companion object {
        const val PageSize = 50
    }
}
