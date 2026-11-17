package com.circaso.feature.support

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.IdempotencyKeyHeader
import com.circaso.core.network.NetworkEvents
import com.circaso.core.network.NetworkHealth
import com.circaso.core.network.circasoJson
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.support.presentation.NewTicketViewModel
import com.circaso.feature.support.presentation.TicketViewModel
import com.circaso.feature.support.presentation.TicketsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** One call the fake server saw. */
private class Seen(val method: HttpMethod, val path: String, val query: Map<String, String>, val key: String?, val body: String)

private class TicketsMonitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

/** Support tickets (SU1–SU4): the repository over a fake server, then the screens' view models on top of it. */
@OptIn(ExperimentalCoroutinesApi::class)
class SupportTicketsTest {
    private val json = circasoJson()
    private val seen = mutableListOf<Seen>()

    /** What the fake server answers: a status and the envelope's JSON; throwing is a dropped connection. */
    private var answer: suspend (Seen) -> Pair<Int, String> = { 500 to fail("internal.error") }
    private var keys = 0
    private val api = CircasoApi(
        HttpClient(MockEngine { request ->
            val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
            val call = Seen(request.method, request.url.encodedPath, request.url.parameters.names().associateWith { request.url.parameters[it]!! }, request.headers[IdempotencyKeyHeader], body)
            seen += call
            val (status, text) = answer(call)
            respond(text, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }) { install(ContentNegotiation) { json(json) } },
        json, NetworkHealth(), NetworkEvents(),
    )
    private val tickets = SupportTickets(api) { "key-${++keys}" }

    private val dispatcher = UnconfinedTestDispatcher()
    private val monitor = TicketsMonitor()
    private lateinit var connection: ConnectionState

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0)
    }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    // ---- what the server says ----

    private fun ok(data: String) = """{"httpStatusCode":200,"status":true,"data":$data}"""
    private fun fail(code: String, retryAfter: Int? = null, requestId: String? = "req-1") =
        """{"httpStatusCode":400,"status":false,"code":"$code","requestId":${requestId?.let { "\"$it\"" }},"details":{"retryAfterSeconds":$retryAfter}}"""

    private fun ticket(n: Int, status: String = "OPEN", last: String = "2026-11-08T10:00:${n.toString().padStart(2, '0')}.000Z", source: String = "APP") =
        """{"ticketId":"t$n","number":"CS-${n.toString().padStart(6, '0')}","subject":"Subject $n","category":"PAYMENTS","status":"$status","source":"$source","waitingOn":${TicketStatus.waitingOn(status)?.let { "\"$it\"" }},"transferId":null,"chatId":null,"createdAt":"2026-11-08T09:00:00.000Z","lastMessageAt":"$last"}"""

    private fun page(tickets: List<String>, next: String? = null) = ok("""{"tickets":[${tickets.joinToString(",")}],"nextCursor":${next?.let { "\"$it\"" }}}""")

    private fun detail(n: Int, status: String = "OPEN", messages: List<String> = emptyList(), last: String = "2026-11-08T10:00:${n.toString().padStart(2, '0')}.000Z") =
        ok("""{"ticket":${ticket(n, status, last)},"description":"What happened with ticket $n.","messages":[${messages.joinToString(",")}]}""")

    private fun reply(id: String, author: String, body: String) =
        """{"messageId":"$id","author":"$author","authorName":${if (author == "STAFF") "\"Circaso Support\"" else "null"},"body":"$body","createdAt":"2026-11-08T10:40:51.004Z"}"""

    /** A view model's state once [ready], or a failure after a few real seconds (never a hang). */
    private suspend fun <T> StateFlow<T>.await(ready: (T) -> Boolean): T = withContext(Dispatchers.Default) { withTimeout(10.seconds) { first(ready) } }

    private fun request(subject: String = "Payment to Baraka not received") = OpenTicketRequest(subject, "I sent 20 USDC on Base yesterday.", "PAYMENTS")

    // ---- the repository ----

    @Test fun theListLoadsInPagesWithTheServersCursor() = runTest {
        answer = { call -> 200 to if (call.query["cursor"] == null) page(listOf(ticket(3), ticket(2)), next = "c2") else page(listOf(ticket(2), ticket(1))) }
        assertFalse(tickets.list.value.loaded)
        assertIs<AppResult.Success<Unit>>(tickets.refresh())
        assertEquals(listOf("t3", "t2"), tickets.list.value.tickets.map { it.ticketId })
        assertTrue(tickets.list.value.loaded)
        assertTrue(tickets.list.value.hasMore)
        assertEquals(mapOf("limit" to "50"), seen.last().query)
        assertEquals("/v1/support/tickets", seen.last().path)

        assertIs<AppResult.Success<Unit>>(tickets.loadMore())
        assertEquals(mapOf("limit" to "50", "cursor" to "c2"), seen.last().query)
        // A ticket the next page repeats (it moved while paging) is listed once.
        assertEquals(listOf("t3", "t2", "t1"), tickets.list.value.tickets.map { it.ticketId })
        assertFalse(tickets.list.value.hasMore)

        // Nothing left: no call is made.
        val calls = seen.size
        tickets.loadMore()
        assertEquals(calls, seen.size)
        assertNull(seen.last().key, "reads carry no Idempotency-Key from here")
    }

    @Test fun aFailedRefreshKeepsWhatWasListed() = runTest {
        answer = { 200 to page(listOf(ticket(1))) }
        tickets.refresh()
        answer = { throw IllegalStateException("dropped") }
        assertEquals(AppResult.Failure(AppError.Network), tickets.refresh())
        assertEquals(listOf("t1"), tickets.list.value.tickets.map { it.ticketId })
        assertTrue(tickets.list.value.loaded)
    }

    @Test fun errorsReadAsClearSentences() {
        fun api(code: String, retry: Int? = null, status: Int = 400) = AppError.Api(code, status, "req-9", retry, emptyMap())
        assertEquals(
            "You've opened the most tickets you can for now. Try again in about 2 hours, or reply on one you already have.",
            TicketErrors.message(api("support.rate_limited", 7_200, 429)),
        )
        assertTrue(TicketErrors.message(api("support.rate_limited", 300, 429)).contains("in 5 minutes"))
        assertTrue(TicketErrors.message(api("support.rate_limited", 301, 429)).contains("in 6 minutes"), "rounded up, never too early")
        assertTrue(TicketErrors.message(api("support.rate_limited", 80_000, 429)).contains("Try again tomorrow"))
        assertTrue(TicketErrors.message(api("support.rate_limited", null, 429)).contains("Try again tomorrow"))
        assertEquals("Too many tries. Try again in 12 minutes.", TicketErrors.message(api("request.rate_limited", 700, 429)))
        assertEquals("This ticket is closed, so it can't take more replies. Open a new ticket instead.", TicketErrors.message(api("support.ticket_closed", status = 409)))
        assertEquals("The subject needs 3 to 120 characters on one line.", TicketErrors.message(api("support.subject_invalid")))
        assertEquals("Tell us what happened in 10 to 4,000 characters.", TicketErrors.message(api("support.description_invalid")))
        assertEquals("A reply needs 1 to 4,000 characters.", TicketErrors.message(api("support.body_invalid")))
        assertTrue(TicketErrors.message(api("support.context_invalid")).startsWith("We couldn't attach that payment or chat"))
        assertEquals("We couldn't find that ticket.", TicketErrors.message(api("support.ticket_not_found", status = 404)))
        assertEquals(ErrorMessages.Offline, TicketErrors.message(AppError.Network))
        assertEquals(ErrorMessages.SomethingWrong, TicketErrors.message(api("support.something_new")))
        // The reference support can look up: the request id, and the code only when we had no words for it.
        assertTrue(TicketErrors.withReference(api("support.ticket_closed", status = 409)).endsWith("Ref req-9"))
        assertTrue(TicketErrors.withReference(api("support.something_new")).endsWith("Ref req-9 · support.something_new"))
        assertEquals(ErrorMessages.Offline, TicketErrors.withReference(AppError.Network))
    }

    @Test fun aSubmitThatMayHaveRunIsRetriedWithTheSameKey() = runTest {
        answer = { throw IllegalStateException("dropped") }
        assertEquals(AppResult.Failure(AppError.Network), tickets.open(request()))
        answer = { 500 to fail("internal.error") }
        tickets.open(request())
        answer = { 200 to detail(7) }
        assertIs<AppResult.Success<TicketDetailResponse>>(tickets.open(request()))
        val posts = seen.filter { it.method == HttpMethod.Post }
        assertEquals(listOf("key-1", "key-1", "key-1"), posts.map { it.key }, "no answer, a 5xx, then the retry: one key, so one ticket")
        assertEquals("/v1/support/tickets", posts.last().path)
        val sent = json.parseToJsonElement(posts.last().body).jsonObject
        assertEquals(""""Payment to Baraka not received"""", sent["subject"].toString())
        assertEquals(""""PAYMENTS"""", sent["category"].toString())

        // It went through: the same words again are a new ticket.
        tickets.open(request())
        assertEquals("key-2", seen.last().key)
    }

    @Test fun aRefusalOrAChangeStartsANewKey() = runTest {
        answer = { 429 to fail("support.rate_limited", retryAfter = 3_600) }
        val refused = tickets.open(request())
        assertEquals("support.rate_limited", ((refused as AppResult.Failure).error as AppError.Api).code)
        assertEquals(3_600, (refused.error as AppError.Api).retryAfterSeconds)
        // The server decided: trying again later is a new request, not a replay of the refusal.
        tickets.open(request())
        assertEquals(listOf("key-1", "key-2"), seen.map { it.key })

        answer = { throw IllegalStateException("dropped") }
        tickets.open(request())
        tickets.open(request(subject = "Something else entirely"))
        assertEquals(listOf("key-3", "key-4"), seen.takeLast(2).map { it.key }, "different words are a different ticket")
    }

    @Test fun aReplyKeepsItsKeyAcrossARetryAndUpdatesTheTicket() = runTest {
        answer = { 200 to page(listOf(ticket(2), ticket(1, status = "RESOLVED"))) }
        tickets.refresh()
        answer = { throw IllegalStateException("dropped") }
        tickets.reply("t1", "Yes, he sent it in our chat.")
        answer = { 200 to detail(1, status = "OPEN", messages = listOf(reply("m1", "USER", "Yes, he sent it in our chat.")), last = "2026-11-08T11:00:00.000Z") }
        val sent = tickets.reply("t1", "Yes, he sent it in our chat.")
        assertIs<AppResult.Success<TicketDetailResponse>>(sent)
        val posts = seen.filter { it.method == HttpMethod.Post }
        assertEquals(listOf("key-1", "key-1"), posts.map { it.key })
        assertEquals("/v1/support/tickets/t1/replies", posts.last().path)
        assertEquals("""{"body":"Yes, he sent it in our chat."}""", posts.last().body)
        // The answer is the new state: the resolved ticket is open again and now the most recent.
        assertEquals("OPEN", tickets.details.value["t1"]?.ticket?.status)
        assertEquals(listOf("t1" to "OPEN", "t2" to "OPEN"), tickets.list.value.tickets.map { it.ticketId to it.status })
        // The same words on another ticket, or new words on this one, are new replies.
        tickets.reply("t2", "Yes, he sent it in our chat.")
        tickets.reply("t1", "Thank you!")
        assertEquals(listOf("key-2", "key-3"), seen.takeLast(2).map { it.key })
    }

    @Test fun aNewTicketGoesToTheTopOfTheList() = runTest {
        answer = { 200 to page(listOf(ticket(1))) }
        tickets.refresh()
        answer = { 200 to detail(9) }
        tickets.open(request())
        assertEquals(listOf("t9", "t1"), tickets.list.value.tickets.map { it.ticketId })
        assertEquals("What happened with ticket 9.", tickets.details.value["t9"]?.description)
    }

    @Test fun framesMarkTheListOrReloadTheTicketOnScreen() = runTest {
        answer = { call -> 200 to if (call.path.endsWith("/tickets")) page(listOf(ticket(2), ticket(1))) else detail(1, "PENDING_USER", listOf(reply("m1", "STAFF", "We're checking."))) }
        tickets.refresh()
        fun frame(id: String, status: String) = json.parseToJsonElement("""{"ticketId":"$id","number":"CS-000001","status":"$status"}""").jsonObject

        // Not on screen: marked in the list, nothing fetched.
        var calls = seen.size
        tickets.onFrame("ticket.replied", frame("t1", "PENDING_USER"))
        assertEquals(calls, seen.size)
        val marked = tickets.list.value.tickets.first { it.ticketId == "t1" }
        assertEquals("PENDING_USER", marked.status)
        assertTrue(marked.replyNeeded)
        tickets.onFrame("ticket.updated", frame("t2", "CLOSED"))
        assertTrue(tickets.list.value.tickets.first { it.ticketId == "t2" }.let { it.closed && it.waitingOn == null })

        // On screen: read again (SU3).
        tickets.watch("t1")
        tickets.onFrame("ticket.replied", frame("t1", "PENDING_USER"))
        assertEquals("/v1/support/tickets/t1", seen.last().path)
        assertEquals(listOf("We're checking."), tickets.details.value["t1"]?.messages?.map { it.body })
        tickets.unwatch("t1")

        // One the list doesn't have (Asha just opened it): the list is loaded again.
        calls = seen.size
        tickets.onFrame("ticket.updated", frame("t77", "OPEN"))
        assertEquals("/v1/support/tickets", seen.last().path)
        assertEquals(calls + 1, seen.size)

        // A gap too old to replay: the list again.
        tickets.onFrame("resync", null)
        assertEquals(calls + 2, seen.size)
        // Frames this version doesn't know, or without an id, do nothing.
        tickets.onFrame("ticket.assigned", frame("t1", "OPEN"))
        tickets.onFrame("ticket.replied", null)
        assertEquals(calls + 2, seen.size)
    }

    @Test fun signingOutForgetsEverything() = runTest {
        answer = { call -> 200 to if (call.path.endsWith("/tickets")) page(listOf(ticket(1))) else detail(1) }
        tickets.refresh(); tickets.load("t1")
        tickets.clear()
        assertEquals(TicketList(), tickets.list.value)
        assertTrue(tickets.details.value.isEmpty())
    }

    // ---- the list's view model ----

    @Test fun theListShowsItsTicketsThenAnErrorItCanRetry() = runTest {
        answer = { throw IllegalStateException("dropped") }
        val vm = TicketsViewModel(tickets, connection)
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        val failed = vm.state.await { it.error != null }
        assertEquals(ErrorMessages.Offline, failed.error)
        assertFalse(failed.loaded)
        assertFalse(failed.showSkeleton, "the error takes the skeleton's place")

        answer = { call -> 200 to if (call.query["cursor"] == null) page(listOf(ticket(2, "PENDING_USER"), ticket(1, source = "ASHA")), next = "c2") else page(listOf(ticket(0))) }
        vm.onRefresh()
        val loaded = vm.state.await { it.loaded && !it.refreshing }
        assertNull(loaded.error)
        assertEquals(listOf("t2", "t1"), loaded.tickets.map { it.ticketId })
        assertTrue(loaded.tickets[0].replyNeeded)
        assertTrue(loaded.tickets[1].openedByAsha)
        assertTrue(loaded.hasMore)

        vm.onLoadMore()
        val all = vm.state.await { it.tickets.size == 3 && !it.loadingMore }
        assertFalse(all.hasMore)
        // The end: asking again does nothing.
        val calls = seen.size
        vm.onLoadMore()
        assertEquals(calls, seen.size)
    }

    @Test fun anEmptyListSaysSo() = runTest {
        answer = { 200 to page(emptyList()) }
        val vm = TicketsViewModel(tickets, connection)
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        assertTrue(vm.state.await { it.loaded }.empty)
    }

    // ---- the new-ticket form ----

    @Test fun theFormHoldsBackWhatTheServerWouldRefuse() = runTest {
        val vm = NewTicketViewModel("WALLET", tickets, connection)
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        assertEquals(TicketCategory.Wallet, vm.state.value.category)
        assertNull(vm.state.value.subjectProblem, "nothing is wrong before anything is typed")

        vm.onSubject("  Hi  ")
        vm.onDescription("Too short")
        assertEquals("Use at least 3 characters.", vm.state.value.subjectProblem)
        assertEquals("Use at least 10 characters.", vm.state.value.descriptionProblem)
        assertEquals("2/120", vm.state.value.subjectCount)
        vm.onSend()
        assertTrue(seen.isEmpty(), "nothing is sent while it can't be accepted")
        assertTrue(vm.state.value.tried)

        // One line, 120 characters at most; 4,000 for what happened.
        vm.onSubject("Line one\nline two " + "x".repeat(200))
        assertEquals(120, vm.state.value.subject.length)
        assertFalse('\n' in vm.state.value.subject)
        vm.onDescription("y".repeat(5_000))
        assertEquals(4_000, vm.state.value.description.length)
        assertEquals("4,000/4,000", vm.state.value.descriptionCount)
        assertTrue(vm.state.value.canSend)

        // A category is needed too.
        val bare = NewTicketViewModel(null, tickets, connection)
        backgroundScope.launch(dispatcher) { bare.state.collect { } }
        bare.onSubject("Payment missing"); bare.onDescription("It never arrived at all.")
        assertFalse(bare.state.value.canSend)
        bare.onSend()
        assertEquals("Choose what it's about.", bare.state.value.categoryProblem)
        bare.onCategory(TicketCategory.Payments)
        assertTrue(bare.state.value.canSend)
        assertTrue(seen.isEmpty())
    }

    @Test fun sendingOpensTheTicketAndHandsOverItsId() = runTest {
        val gate = CompletableDeferred<Unit>()
        answer = { gate.await(); 200 to detail(5) }
        val vm = NewTicketViewModel("PAYMENTS", tickets, connection)
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        val opened = mutableListOf<String>()
        backgroundScope.launch(dispatcher) { vm.opened.collect { opened += it } }
        vm.onSubject("  Payment to Baraka not received ")
        vm.onDescription("  I sent 20 USDC on Base yesterday.\n")
        vm.onSend()
        vm.onSend() // a double tap
        assertTrue(vm.state.value.busy)
        gate.complete(Unit)
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (opened.isEmpty()) kotlinx.coroutines.delay(10) } }
        assertEquals(listOf("t5"), opened)
        assertEquals(1, seen.size)
        // Trimmed, as the server counts it.
        assertEquals("""{"subject":"Payment to Baraka not received","description":"I sent 20 USDC on Base yesterday.","category":"PAYMENTS"}""", seen.single().body)
    }

    @Test fun aRefusedTicketSaysWhyAndCanBeSentAgain() = runTest {
        answer = { 429 to fail("support.rate_limited", retryAfter = 7_200) }
        val vm = NewTicketViewModel("OTHER", tickets, connection)
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        vm.onSubject("App closes when I open Moments"); vm.onDescription("It happens every time since this morning.")
        vm.onSend()
        val refused = vm.state.await { it.error != null }
        assertEquals("You've opened the most tickets you can for now. Try again in about 2 hours, or reply on one you already have. Ref req-1", refused.error)
        assertFalse(refused.busy)
        assertEquals("App closes when I open Moments", refused.subject, "what was written is kept")
        // Editing clears the error.
        vm.onDescription("It happens every time since this morning!")
        assertNull(vm.state.value.error)
    }

    @Test fun offlineTheFormWaits() = runTest {
        monitor.osStatus.value = ConnectionStatus.Offline
        val vm = NewTicketViewModel("OTHER", tickets, connection)
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        vm.onSubject("App closes when I open Moments"); vm.onDescription("It happens every time since this morning.")
        assertFalse(vm.state.value.online)
        assertFalse(vm.state.value.canSend)
        vm.onSend()
        assertTrue(seen.isEmpty())
        assertEquals("App closes when I open Moments", vm.state.value.subject)
    }

    // ---- one ticket ----

    @Test fun aReplyShowsAtOnceAndStaysOnceSent() = runTest {
        answer = { 200 to detail(1, "PENDING_USER", listOf(reply("m1", "STAFF", "Did Baraka give you the address himself?"))) }
        val vm = TicketViewModel("t1", tickets, connection)
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        val open = vm.state.await { it.detail != null && !it.loading }
        assertEquals("CS-000001", open.detail?.ticket?.number)
        assertFalse(open.canSend, "nothing typed yet")

        val gate = CompletableDeferred<Unit>()
        answer = { gate.await(); 200 to detail(1, "OPEN", listOf(reply("m1", "STAFF", "Did Baraka give you the address himself?"), reply("m2", "USER", "Yes, he sent it in our chat."))) }
        vm.onDraft("  Yes, he sent it in our chat. ")
        assertTrue(vm.state.value.canSend)
        vm.onSend()
        assertEquals("Yes, he sent it in our chat.", vm.state.value.pending)
        assertEquals("", vm.state.value.draft)
        assertFalse(vm.state.value.canSend, "one reply at a time")
        gate.complete(Unit)
        val sent = vm.state.await { it.pending == null }
        assertEquals(listOf("m1", "m2"), sent.detail?.messages?.map { it.messageId })
        assertEquals("OPEN", sent.detail?.ticket?.status)
        assertNull(sent.sendError)
        assertEquals("""{"body":"Yes, he sent it in our chat."}""", seen.last().body)
    }

    @Test fun aReplyThatFailsComesBackWithTheReason() = runTest {
        answer = { 200 to detail(1, "PENDING_USER") }
        val vm = TicketViewModel("t1", tickets, connection)
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        vm.state.await { it.detail != null && !it.loading }
        answer = { throw IllegalStateException("dropped") }
        vm.onDraft("Yes, he sent it in our chat.")
        vm.onSend()
        val back = vm.state.await { it.sendError != null }
        assertNull(back.pending, "nothing looks sent that wasn't")
        assertEquals("Yes, he sent it in our chat.", back.draft)
        assertEquals(ErrorMessages.Offline, back.sendError)
        assertTrue(back.detail?.messages.orEmpty().isEmpty())
        // Sending the same words again carries the same key, so it can't land twice.
        answer = { 200 to detail(1, "OPEN", listOf(reply("m2", "USER", "Yes, he sent it in our chat."))) }
        vm.onSend()
        vm.state.await { it.pending == null && it.detail?.messages?.size == 1 }
        val posts = seen.filter { it.method == HttpMethod.Post }
        assertEquals(2, posts.size)
        assertEquals(posts[0].key, posts[1].key)
    }

    @Test fun aClosedTicketTakesNoReplies() = runTest {
        answer = { 200 to detail(1, "CLOSED") }
        val vm = TicketViewModel("t1", tickets, connection)
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        val closed = vm.state.await { it.detail != null && !it.loading }
        assertTrue(closed.closed)
        vm.onDraft("Hello?")
        assertFalse(vm.state.value.canSend)
        vm.onSend()
        assertTrue(seen.none { it.method == HttpMethod.Post })
    }

    @Test fun aTicketClosedMeanwhileSaysSoAndGivesTheWordsBack() = runTest {
        answer = { 200 to detail(1, "RESOLVED") }
        val vm = TicketViewModel("t1", tickets, connection)
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        assertTrue(vm.state.await { it.detail != null && !it.loading }.resolved)
        // Staff closed it while the reply was being typed (and the frame hasn't arrived).
        answer = { call -> if (call.method == HttpMethod.Post) 409 to fail("support.ticket_closed") else 200 to detail(1, "CLOSED") }
        vm.onDraft("It happened again.")
        vm.onSend()
        val after = vm.state.await { it.closed && it.sendError != null }
        assertEquals("This ticket is closed, so it can't take more replies. Open a new ticket instead. Ref req-1", after.sendError)
        assertEquals("It happened again.", after.draft)
        assertNull(after.pending)
        assertFalse(after.canSend)
    }

    @Test fun aFrameForTheOpenTicketBringsTheNewReply() = runTest {
        answer = { 200 to detail(1, "OPEN") }
        val vm = TicketViewModel("t1", tickets, connection)
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        vm.state.await { it.detail != null && !it.loading }
        answer = { 200 to detail(1, "PENDING_USER", listOf(reply("m1", "STAFF", "We're checking with the network."))) }
        tickets.onFrame("ticket.replied", json.parseToJsonElement("""{"ticketId":"t1","number":"CS-000001","status":"PENDING_USER"}""").jsonObject)
        val updated = vm.state.await { it.detail?.messages?.size == 1 }
        assertEquals("Circaso Support", updated.detail?.messages?.single()?.authorName)
        assertTrue(updated.detail?.ticket?.replyNeeded == true)
    }

    @Test fun aTicketThatCantBeLoadedSaysWhyAndRetries() = runTest {
        answer = { 404 to fail("support.ticket_not_found") }
        val vm = TicketViewModel("nope", tickets, connection)
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        val failed = vm.state.await { it.loadError != null }
        assertEquals("We couldn't find that ticket. Ref req-1", failed.loadError)
        assertFalse(failed.showSkeleton)
        answer = { 200 to detail(1).replace("\"t1\"", "\"nope\"") }
        vm.onRetry()
        assertNull(vm.state.await { it.detail != null && !it.loading }.loadError)
    }

    @Test fun aTicketNotYetLoadedShowsItsSkeletonUnlessItFailed() {
        assertTrue(com.circaso.feature.support.presentation.TicketUiState().showSkeleton)
        assertFalse(com.circaso.feature.support.presentation.TicketUiState(loadError = "No connection.").showSkeleton)
        assertTrue(com.circaso.feature.support.presentation.TicketsUiState().showSkeleton)
        assertNotEquals(request(), request(subject = "Other"))
    }
}
