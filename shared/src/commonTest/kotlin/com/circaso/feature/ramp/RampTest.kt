package com.circaso.feature.ramp

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.IdempotencyKeyHeader
import com.circaso.core.network.NetworkEvents
import com.circaso.core.network.NetworkHealth
import com.circaso.core.network.WalletNetworkGate
import com.circaso.core.network.WalletNetworkHeader
import com.circaso.core.network.circasoJson
import com.circaso.core.notifications.NotificationDestination
import com.circaso.feature.ramp.data.remote.KtorRampRemote
import com.circaso.feature.ramp.data.remote.LimitsDto
import com.circaso.feature.ramp.data.remote.OrderResponse
import com.circaso.feature.ramp.data.remote.OrdersResponse
import com.circaso.feature.ramp.data.remote.QuoteRequest
import com.circaso.feature.ramp.data.remote.QuoteResponse
import com.circaso.feature.ramp.data.remote.RampAmountDto
import com.circaso.feature.ramp.data.remote.RampRemote
import com.circaso.feature.ramp.data.remote.RampStatusResponse
import com.circaso.feature.ramp.data.remote.StartOnRampRequest
import com.circaso.feature.ramp.data.repository.RampRepositoryImpl
import com.circaso.feature.ramp.domain.RampKyc
import com.circaso.feature.ramp.domain.RampMoney
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.ramp.domain.RampOrderUpdate
import com.circaso.feature.ramp.domain.RampPhoneStore
import com.circaso.feature.wallet.domain.PinnedNetwork
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetworks
import com.circaso.feature.wallet.domain.pinnedNetwork
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun kes(whole: String) = RampAmountDto("KES", whole + "00", whole)
internal val Usdc = RampAmountDto("USDC", "15310000", "15.31")
internal const val Masked = "+254 7•• ••• 678"
internal const val Phone = "+254712345678"

internal fun statusDto(
    kyc: String = "NOT_REQUIRED", active: Boolean = true, merchant: Boolean = true, open: String? = null, chains: List<String> = listOf("BASE-SEPOLIA"),
) = RampStatusResponse(
    canTrade = kyc != "NONE" && active && merchant && open == null, network = "TESTNET", kyc = kyc, accountActive = active, blockchains = chains,
    merchantOnline = merchant, openOrderId = open, limits = LimitsDto("KES", kes("100"), kes("150000"), kes("300000")), feePercent = "1.5",
)

internal fun quoteDto(id: String = "q1", expiresAt: String = "2026-11-22T10:01:00Z") = QuoteResponse(id, "ON_RAMP", "TESTNET", "BASE-SEPOLIA", kes("2000"), Usdc, "128.7", kes("30"), expiresAt)

internal fun orderDto(state: String, id: String = "o1", txHash: String? = null, reason: String? = null) = OrderResponse(
    orderId = id, state = state, network = "TESTNET", blockchain = "BASE-SEPOLIA", pay = kes("2000"), receive = Usdc, rate = "128.7", fee = kes("30"),
    maskedPhone = Masked, merchantName = "Partner", txHash = txHash, failureReason = reason, expiresAt = "2026-11-22T10:03:00Z", createdAt = "2026-11-22T10:00:00Z", updatedAt = "2026-11-22T10:00:00Z",
)

internal fun refusal(code: String, retryAfter: Int? = null) = AppResult.Failure(AppError.Api(code, 409, "req-1", retryAfter, emptyMap()))

/** A scripted server: each route answers what the test set, and every call is written down. */
internal class FakeRampRemote : RampRemote {
    var status: AppResult<RampStatusResponse> = AppResult.Success(statusDto())
    var quote: AppResult<QuoteResponse> = AppResult.Success(quoteDto())
    var buy: AppResult<OrderResponse> = AppResult.Success(orderDto("PAYMENT_REQUESTED"))
    var order: AppResult<OrderResponse> = AppResult.Success(orderDto("PAYMENT_REQUESTED"))
    var cancel: AppResult<OrderResponse> = AppResult.Success(orderDto("CANCELLED", reason = "cancelled"))
    var orders: AppResult<OrdersResponse> = AppResult.Success(OrdersResponse())

    val quotes = mutableListOf<Pair<QuoteRequest, String>>()
    val buys = mutableListOf<Pair<StartOnRampRequest, String>>()
    val cancels = mutableListOf<Pair<String, String>>()
    val reads = mutableListOf<String>()
    val networks = mutableListOf<WalletNetwork?>()
    var statusAsks = 0

    override suspend fun status(): AppResult<RampStatusResponse> { statusAsks++; networks += pinnedNetwork(); return status }
    override suspend fun quote(request: QuoteRequest, idempotencyKey: String): AppResult<QuoteResponse> { quotes += request to idempotencyKey; networks += pinnedNetwork(); return quote }
    override suspend fun buy(request: StartOnRampRequest, idempotencyKey: String): AppResult<OrderResponse> { buys += request to idempotencyKey; networks += pinnedNetwork(); return buy }
    override suspend fun order(orderId: String): AppResult<OrderResponse> { reads += orderId; return order }
    override suspend fun orders(limit: Int, cursor: String?): AppResult<OrdersResponse> = orders
    override suspend fun cancel(orderId: String, idempotencyKey: String): AppResult<OrderResponse> { cancels += orderId to idempotencyKey; return cancel }
}

internal class MemoryPhone(var phone: String? = null) : RampPhoneStore {
    override suspend fun get(): String? = phone
    override suspend fun put(phone: String) { this.phone = phone }
    override suspend fun clear() { phone = null }
}

internal class RampFixture(val remote: FakeRampRemote = FakeRampRemote(), val phones: MemoryPhone = MemoryPhone(), networks: WalletNetworks = WalletNetworks.fixed(WalletNetwork.TESTNET, known = true)) {
    val saved = mutableMapOf<WalletNetwork, MutableStateFlow<String?>>()
    private fun slot(network: WalletNetwork) = saved.getOrPut(network) { MutableStateFlow(null) }
    var arrivals = 0
    val arrivedOn = mutableListOf<WalletNetwork?>()
    val repo = RampRepositoryImpl(
        remote, networks, phones, circasoJson(),
        savedTopUps = { slot(it) }, saveTopUps = { network, text -> slot(network).value = text },
        onArrived = { arrivals++; arrivedOn += pinnedNetwork() },
    )
}

private fun frame(json: String): JsonObject = circasoJson().parseToJsonElement(json).jsonObject

/** The ramp's data layer with a scripted server: what goes on the wire, what is kept, what a frame does. */
@OptIn(ExperimentalCoroutinesApi::class)
class RampRepositoryTest {
    @Test fun statusSaysWhichGateIsInTheWay() = runTest {
        val f = RampFixture()
        f.remote.status = AppResult.Success(statusDto(kyc = "NONE", open = "o9"))
        val status = (f.repo.status() as AppResult.Success).value
        assertFalse(status.canTrade)
        assertEquals(RampKyc.None, status.kyc)
        assertEquals("o9", status.openOrderId)
        assertEquals("KES 100", status.limits.minOrder.text)
        assertEquals("KES 150,000", status.limits.maxOrder.text)
        assertEquals(WalletNetwork.TESTNET, status.network)
    }

    @Test fun aQuoteAsksInWholeShillingsWithItsKey() = runTest {
        val f = RampFixture()
        val quote = (f.repo.quote("BASE-SEPOLIA", "200000", "key-1") as AppResult.Success).value
        val (sent, key) = f.remote.quotes.single()
        assertEquals("ON_RAMP", sent.direction)
        assertEquals("BASE-SEPOLIA", sent.blockchain)
        assertEquals("2000", sent.fiat?.amount)
        assertNull(sent.fiat?.amountMinor)
        assertNull(sent.crypto, "one side only: what is paid")
        assertEquals("key-1", key)
        assertEquals("KES 2,000", quote.pay.text)
        assertEquals("15.31 USDC", quote.receive.text)
        assertEquals("KES 30", quote.fee.text)
        assertEquals(1795341660000L, quote.expiresAt)
    }

    @Test fun buyingSendsTheNumberAndKeepsItOnceTheServerTookIt() = runTest {
        val f = RampFixture()
        val order = (f.repo.buy("q1", Phone, "key-2") as AppResult.Success).value
        assertEquals(StartOnRampRequest("q1", Phone) to "key-2", f.remote.buys.single())
        assertEquals(RampOrderState.PaymentRequested, order.state)
        assertEquals(Masked, order.maskedPhone)
        assertEquals(Phone, f.repo.ownPhone())
    }

    @Test fun aNumberTheServerRefusesIsForgotten() = runTest {
        val f = RampFixture(phones = MemoryPhone(Phone))
        f.remote.buy = refusal("ramp.phone_not_yours")
        assertTrue(f.repo.buy("q1", Phone, "k") is AppResult.Failure)
        assertNull(f.repo.ownPhone())
    }

    @Test fun aRefusalThatIsNotAboutTheNumberKeepsIt() = runTest {
        val f = RampFixture(phones = MemoryPhone(Phone))
        f.remote.buy = refusal("ramp.no_merchant_available")
        f.repo.buy("q1", Phone, "k")
        assertEquals(Phone, f.repo.ownPhone())
    }

    @Test fun aNumberNeverShowsInALogLine() {
        assertFalse(StartOnRampRequest("q1", Phone).toString().contains("254"))
        assertFalse(kes("2000").toString().contains("2000"))
    }

    @Test fun cancelCarriesItsKey() = runTest {
        val f = RampFixture()
        val order = (f.repo.cancel("o1", "key-3") as AppResult.Success).value
        assertEquals("o1" to "key-3", f.remote.cancels.single())
        assertEquals(RampOrderState.Cancelled, order.state)
    }

    @Test fun aCompletedOrderRefreshesTheWalletOnceAndLabelsItsPayment() = runTest {
        val f = RampFixture()
        f.remote.order = AppResult.Success(orderDto("COMPLETED", txHash = "0xABCdef"))
        f.repo.order("o1"); f.repo.order("o1")
        assertEquals(1, f.arrivals, "a poll and a second poll are one arrival")
        assertEquals(mapOf("0xabcdef" to Masked), f.repo.topUps.first())
    }

    @Test fun anOrderStillUnderWayRefreshesNothing() = runTest {
        val f = RampFixture()
        f.remote.order = AppResult.Success(orderDto("RELEASING"))
        assertEquals(RampOrderState.Releasing, (f.repo.order("o1") as AppResult.Success).value.state)
        assertEquals(0, f.arrivals)
        assertTrue(f.repo.topUps.first().isEmpty())
    }

    @Test fun theOrdersPageLabelsOldTopUps() = runTest {
        val f = RampFixture()
        f.remote.orders = AppResult.Success(OrdersResponse(listOf(orderDto("COMPLETED", "a", "0x1"), orderDto("FAILED", "b"), orderDto("COMPLETED", "c", "0x2"))))
        assertTrue(f.repo.refreshOrders() is AppResult.Success)
        assertEquals(setOf("0x1", "0x2"), f.repo.topUps.first().keys)
    }

    @Test fun topUpsAreKeptPerNetwork() = runTest {
        val f = RampFixture()
        f.remote.orders = AppResult.Success(OrdersResponse(listOf(orderDto("COMPLETED", "a", "0x1"))))
        withContext(PinnedNetwork(WalletNetwork.MAINNET)) { f.repo.refreshOrders() }
        assertTrue(f.repo.topUps.first().isEmpty(), "the Testnet list knows nothing of a Mainnet top-up")
        assertNotNull(f.saved[WalletNetwork.MAINNET]?.value)
    }

    @Test fun aFrameIsPassedOnAndACompletedOneRefreshesTheWalletOnItsNetwork() = runTest(UnconfinedTestDispatcher()) {
        val f = RampFixture()
        val seen = mutableListOf<RampOrderUpdate>()
        backgroundScope.launch { f.repo.updates.collect { seen += it } }
        f.repo.onFrame("order.updated", frame("""{"orderId":"o1","direction":"ON_RAMP","state":"PAID","blockchain":"BASE-SEPOLIA","pay":{"unit":"KES","amountMinor":"200000","amount":"2000"},"receive":{"unit":"USDC","amountMinor":"15310000","amount":"15.31"}}"""))
        assertEquals(listOf(RampOrderUpdate("o1", RampOrderState.Paid, null)), seen)
        assertEquals(0, f.arrivals)

        f.remote.order = AppResult.Success(orderDto("COMPLETED", txHash = "0xfeed"))
        f.repo.onFrame("order.updated", frame("""{"orderId":"o1","direction":"ON_RAMP","state":"COMPLETED","blockchain":"BASE-SEPOLIA"}"""))
        assertEquals(RampOrderState.Completed, seen.last().state)
        assertEquals(1, f.arrivals)
        assertEquals(listOf<WalletNetwork?>(WalletNetwork.TESTNET), f.arrivedOn, "the chain says which network's wallet to read")
        assertEquals(setOf("0xfeed"), f.repo.topUps.first().keys)
        // The poll that sees the same order completed does not refresh the wallet a second time.
        f.repo.order("o1")
        assertEquals(1, f.arrivals)
    }

    @Test fun aFailedFrameCarriesItsReason() = runTest(UnconfinedTestDispatcher()) {
        val f = RampFixture()
        val seen = mutableListOf<RampOrderUpdate>()
        backgroundScope.launch { f.repo.updates.collect { seen += it } }
        f.repo.onFrame("order.updated", frame("""{"orderId":"o1","state":"FAILED","failureReason":"payment_failed"}"""))
        assertEquals(RampOrderUpdate("o1", RampOrderState.Failed, "payment_failed"), seen.single())
    }

    @Test fun framesThisVersionDoesNotKnowAreIgnored() = runTest(UnconfinedTestDispatcher()) {
        val f = RampFixture()
        val seen = mutableListOf<RampOrderUpdate>()
        backgroundScope.launch { f.repo.updates.collect { seen += it } }
        f.repo.onFrame("merchant.online", frame("""{"x":1}"""))
        f.repo.onFrame("order.updated", null)
        f.repo.onFrame("order.updated", frame("""{"state":"PAID"}""")) // no order id: not ours to guess at
        assertTrue(seen.isEmpty())
    }

    @Test fun aResyncAsksTheScreenToReadItsOrderAgain() = runTest(UnconfinedTestDispatcher()) {
        val f = RampFixture()
        val seen = mutableListOf<RampOrderUpdate>()
        backgroundScope.launch { f.repo.updates.collect { seen += it } }
        f.repo.reload()
        assertEquals(RampOrderUpdate.ReloadAll, seen.single().orderId)
    }
}

/** The server's shapes as the backend writes them (modules/ramp Dtos.kt), and what the phone makes of them. */
class RampWireTest {
    private val json = circasoJson()

    @Test fun anOrderDecodesFromTheServersJson() {
        val order = json.decodeFromString(
            OrderResponse.serializer(),
            """{"orderId":"o1","direction":"ON_RAMP","state":"RELEASING","network":"MAINNET","blockchain":"BASE","pay":{"unit":"KES","amountMinor":"202000","amount":"2020"},
               "receive":{"unit":"USDC","amountMinor":"15310000","amount":"15.31"},"rate":"129.5","fee":{"unit":"KES","amountMinor":"2000","amount":"20"},"maskedPhone":"+254 7•• ••• 678",
               "merchantName":"Partner","txHash":null,"failureReason":null,"expiresAt":"2026-11-22T10:03:00Z","createdAt":"2026-11-22T10:00:00Z","updatedAt":"2026-11-22T10:00:05Z","somethingNew":true}""",
        )
        assertEquals("RELEASING", order.state)
        assertEquals("202000", order.pay.amountMinor)
        assertNull(order.txHash)
    }

    @Test fun statesMapAndANewOneIsNeverAnEnding() {
        assertEquals(RampOrderState.PaymentRequested, RampOrderState.of("PAYMENT_REQUESTED"))
        assertEquals(RampOrderState.Review, RampOrderState.of("REVIEW"))
        val unknown = RampOrderState.of("AWAITING_SOMETHING_NEW")
        assertEquals(RampOrderState.Unknown, unknown)
        assertFalse(unknown.settled)
        assertTrue(listOf(RampOrderState.Completed, RampOrderState.Failed, RampOrderState.Expired, RampOrderState.Cancelled, RampOrderState.Review).all { it.settled })
        assertTrue(listOf(RampOrderState.Reserved, RampOrderState.PaymentRequested, RampOrderState.Paid, RampOrderState.Releasing).none { it.settled })
        assertTrue(RampOrderState.Paid.paid && RampOrderState.Releasing.paid && RampOrderState.Completed.paid)
        assertFalse(RampOrderState.PaymentRequested.paid)
    }

    @Test fun shillingsAreWholeAndExact() {
        assertEquals("200000", RampMoney.wholeToMinor("2000"))
        assertEquals("200000", RampMoney.wholeToMinor("2,000"))
        assertNull(RampMoney.wholeToMinor("0"))
        assertNull(RampMoney.wholeToMinor(""))
        assertNull(RampMoney.wholeToMinor("20.5"), "M-Pesa takes whole shillings")
        assertEquals("2,020", RampMoney.shillings("202000"))
        assertEquals("129.50", RampMoney.shillings("12950"))
        assertEquals("0.05", RampMoney.shillings("5"))
    }

    @Test fun everyRampCodeTheAppCanReceiveHasItsOwnWords() {
        val codes = listOf(
            "ramp.unavailable", "ramp.kyc_required", "ramp.account_restricted", "ramp.wallet_required", "ramp.chain_unsupported", "ramp.offramp_unavailable",
            "ramp.amount_invalid", "ramp.limit_exceeded", "ramp.quote_not_found", "ramp.quote_expired", "ramp.phone_not_yours", "ramp.order_in_progress",
            "ramp.no_merchant_available", "ramp.too_many_prompts", "ramp.order_not_found", "ramp.order_not_cancellable", "ramp.collections_unavailable",
            "ramp.custody_unavailable", "ramp.pricing_unavailable",
        )
        codes.forEach { assertTrue(ErrorMessages.isKnown(it), "no words for $it") }
        assertFalse(codes.any { ErrorMessages.forError(AppError.Api(it, 400, null, null, emptyMap())).contains("CoinX") })
    }

    @Test fun theRampPushOpensItsOrder() {
        val data = mapOf("kind" to "ramp.order", "orderId" to "o1")
        assertEquals(NotificationDestination.RampOrder("o1"), NotificationDestination.fromPush(data))
        // As the system hands it over when it showed the alert itself, and as an app-made notification carries it.
        assertEquals(NotificationDestination.RampOrder("o1"), NotificationDestination.fromExtras(data + ("google.message_id" to "0:1")))
        with(NotificationDestination) { assertEquals(NotificationDestination.RampOrder("o1"), fromExtras(NotificationDestination.RampOrder("o1").toExtras())) }
        assertEquals(NotificationDestination.RampOrder(), NotificationDestination.fromPush(mapOf("kind" to "ramp.order")))
    }
}

/** On the wire: every ramp route carries `X-Wallet-Network` like the wallet's, and every write its `Idempotency-Key`. */
class RampRemoteTest {
    private val json = circasoJson()
    private val seen = mutableListOf<HttpRequestData>()
    private val api = CircasoApi(
        HttpClient(MockEngine { request ->
            seen += request
            respond(
                """{"httpStatusCode":200,"status":true,"message":"Success","code":null,"details":null,"requestId":"r","data":{}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) { install(ContentNegotiation) { json(json) } },
        json, NetworkHealth(), NetworkEvents(),
    )
    private val gate = object : WalletNetworkGate { override suspend fun header(): String? = "TESTNET" }

    @Test fun everyRouteCarriesTheNetworkAndWritesCarryTheirKey() = runTest {
        val remote = KtorRampRemote(api, gate)
        remote.status()
        remote.quote(QuoteRequest("ON_RAMP", "BASE-SEPOLIA", com.circaso.feature.ramp.data.remote.AmountDto(amount = "2000")), "k-quote")
        remote.buy(StartOnRampRequest("q1", Phone), "k-buy")
        remote.order("o1")
        remote.orders(limit = 20, cursor = "2026-11-22T10:00:00Z~abc")
        remote.cancel("o1", "k-cancel")

        assertEquals(
            listOf("GET /v1/ramp", "POST /v1/ramp/quotes", "POST /v1/ramp/onramp/orders", "GET /v1/ramp/orders/o1", "GET /v1/ramp/orders", "POST /v1/ramp/orders/o1/cancel"),
            seen.map { it.method.value + " " + it.url.encodedPath },
        )
        assertTrue(seen.all { it.headers[WalletNetworkHeader] == "TESTNET" })
        assertEquals(listOf(null, "k-quote", "k-buy", null, null, "k-cancel"), seen.map { it.headers[IdempotencyKeyHeader] })
        assertEquals("20", seen[4].url.parameters["limit"])
        assertEquals("2026-11-22T10:00:00Z~abc", seen[4].url.parameters["cursor"])

        val quoteBody = json.parseToJsonElement((seen[1].body as TextContent).text).jsonObject
        assertEquals(setOf("direction", "blockchain", "fiat"), quoteBody.keys)
        assertEquals("""{"amount":"2000"}""", quoteBody["fiat"].toString())
        val buyBody = json.parseToJsonElement((seen[2].body as TextContent).text).jsonObject
        assertEquals("""{"quoteId":"q1","phone":"$Phone"}""", buyBody.toString())
    }

    @Test fun aServerWithoutNetworksIsSentNoHeader() = runTest {
        KtorRampRemote(api, WalletNetworkGate.None).status()
        assertNull(seen.single().headers[WalletNetworkHeader])
    }
}
