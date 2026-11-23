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
import com.circaso.feature.ramp.data.remote.KycResponse
import com.circaso.feature.ramp.data.remote.KycSessionDto
import com.circaso.feature.ramp.data.remote.LimitsDto
import com.circaso.feature.ramp.data.remote.OrderResponse
import com.circaso.feature.ramp.data.remote.OrdersResponse
import com.circaso.feature.ramp.data.remote.QuoteRequest
import com.circaso.feature.ramp.data.remote.QuoteResponse
import com.circaso.feature.ramp.data.remote.RampAmountDto
import com.circaso.feature.ramp.data.remote.RampRemote
import com.circaso.feature.ramp.data.remote.RampStatusResponse
import com.circaso.feature.ramp.data.remote.StartOnRampRequest
import com.circaso.feature.ramp.data.remote.VerificationDto
import com.circaso.feature.ramp.data.repository.RampRepositoryImpl
import com.circaso.feature.ramp.data.remote.toDomain
import com.circaso.feature.ramp.domain.KycStatus
import com.circaso.feature.ramp.domain.KycUpdate
import com.circaso.feature.ramp.domain.RampKyc
import com.circaso.feature.ramp.domain.RampVerification
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
    verification: VerificationDto? = null, blocked: Boolean = false,
) = RampStatusResponse(
    canTrade = kyc != "NONE" && active && merchant && open == null && !blocked, network = "TESTNET", kyc = kyc, blocked = blocked, verification = verification,
    accountActive = active, blockchains = chains,
    merchantOnline = merchant, openOrderId = open, limits = LimitsDto("KES", kes("100"), kes("150000"), kes("300000")), feePercent = "1.5",
)

/** The identity check as the server words it (R1's `verification`, the read, the frame). */
internal fun checkDto(status: String, canStart: Boolean = status in setOf("NONE", "PENDING", "EXPIRED"), reason: String? = null, retryAt: String? = null, required: Boolean = true, submitted: Boolean = false) =
    VerificationDto(status = status, required = required, canStart = canStart, reason = reason, retryAt = retryAt, submitted = submitted)

/** R1 for someone who must verify and stands at [status]. */
internal fun unverified(status: String = "NONE", canStart: Boolean = status in setOf("NONE", "PENDING", "EXPIRED"), reason: String? = null, retryAt: String? = null) =
    statusDto(kyc = "NONE", verification = checkDto(status, canStart, reason, retryAt))

internal const val SessionToken = "tok-secret-1"
internal const val SessionUrl = "https://verify.didit.me/session/tok-secret-1"

/** R0 with a check open: run the SDK. */
internal fun openCheck(token: String = SessionToken) = KycResponse("PENDING", required = true, canStart = true, session = KycSessionDto(token, SessionUrl, "2026-11-23T12:00:00Z"))

/** R0 with nothing to do now. */
internal fun settledCheck(status: String, canStart: Boolean = false, reason: String? = null, retryAt: String? = null) = KycResponse(status, required = true, canStart = canStart, reason = reason, retryAt = retryAt, session = null)

internal fun quoteDto(id: String = "q1", expiresAt: String = "2026-11-22T10:01:00Z") = QuoteResponse(id, "ON_RAMP", "TESTNET", "BASE-SEPOLIA", kes("2000"), Usdc, "128.7", kes("30"), expiresAt)

internal fun orderDto(state: String, id: String = "o1", txHash: String? = null, reason: String? = null) = OrderResponse(
    orderId = id, state = state, network = "TESTNET", blockchain = "BASE-SEPOLIA", pay = kes("2000"), receive = Usdc, rate = "128.7", fee = kes("30"),
    maskedPhone = Masked, merchantName = "Partner", txHash = txHash, failureReason = reason, expiresAt = "2026-11-22T10:03:00Z", createdAt = "2026-11-22T10:00:00Z", updatedAt = "2026-11-22T10:00:00Z",
)

internal fun refusal(code: String, retryAfter: Int? = null, http: Int = 409) = AppResult.Failure(AppError.Api(code, http, "req-1", retryAfter, emptyMap()))

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

    /** R0 and the read of the check. */
    var kycStart: AppResult<KycResponse> = AppResult.Success(openCheck())
    var kyc: AppResult<VerificationDto> = AppResult.Success(checkDto("PENDING"))
    /** The `Idempotency-Key` of every R0, in order. */
    val kycStarts = mutableListOf<String>()
    var kycReads = 0
    /** When set, R0 waits on it: an answer still on its way. */
    var kycGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun startKyc(idempotencyKey: String): AppResult<KycResponse> { kycStarts += idempotencyKey; networks += pinnedNetwork(); kycGate?.await(); return kycStart }
    /** When set, a read waits on it and then answers what was scripted when it was asked: a slow, older answer. */
    var kycHold: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    override suspend fun kyc(): AppResult<VerificationDto> { kycReads++; networks += pinnedNetwork(); val then = kyc; kycHold?.await(); return then }

    override suspend fun status(): AppResult<RampStatusResponse> { statusAsks++; networks += pinnedNetwork(); return status }
    /** When set, a quote waits on it: a price still on its way. */
    var quoteGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    override suspend fun quote(request: QuoteRequest, idempotencyKey: String): AppResult<QuoteResponse> { quotes += request to idempotencyKey; networks += pinnedNetwork(); quoteGate?.await(); return quote }
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

internal class RampFixture(
    val remote: FakeRampRemote = FakeRampRemote(), val phones: MemoryPhone = MemoryPhone(), networks: WalletNetworks = WalletNetworks.fixed(WalletNetwork.TESTNET, known = true),
    /** The clock the phone's copy of the identity check is stamped with. */
    var clock: () -> Long = { 0L },
) {
    val saved = mutableMapOf<WalletNetwork, MutableStateFlow<String?>>()
    private fun slot(network: WalletNetwork) = saved.getOrPut(network) { MutableStateFlow(null) }
    /** The phone's last known identity check, per network, as it is kept (JSON). */
    val identities = mutableMapOf<WalletNetwork, MutableStateFlow<String?>>()
    fun identitySlot(network: WalletNetwork) = identities.getOrPut(network) { MutableStateFlow(null) }
    var arrivals = 0
    val arrivedOn = mutableListOf<WalletNetwork?>()
    val repo = RampRepositoryImpl(
        remote, networks, phones, circasoJson(),
        savedTopUps = { slot(it) }, saveTopUps = { network, text -> slot(network).value = text },
        onArrived = { arrivals++; arrivedOn += pinnedNetwork() },
        savedIdentity = { identitySlot(it) }, saveIdentity = { network, text -> identitySlot(network).value = text },
        now = { clock() },
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

    // ---- identity verification ------------------------------------------------------------------------------------

    @Test fun startingACheckCarriesItsKeyAndHandsBackTheSession() = runTest {
        val f = RampFixture()
        val start = (f.repo.startKyc("key-kyc") as AppResult.Success).value
        assertEquals(listOf("key-kyc"), f.remote.kycStarts)
        assertEquals(KycStatus.Pending, start.verification.status)
        assertEquals(SessionToken, start.session?.sessionToken)
        assertEquals(SessionUrl, start.session?.url)
    }

    @Test fun aRefusedStartKeepsItsCodeAndItsWait() = runTest {
        val f = RampFixture()
        f.remote.kycStart = refusal("ramp.kyc_attempts_exceeded", retryAfter = 3600, http = 429)
        val error = (f.repo.startKyc("k") as AppResult.Failure).error as AppError.Api
        assertEquals("ramp.kyc_attempts_exceeded" to 3600, error.code to error.retryAfterSeconds)
    }

    @Test fun theReadSaysWhereTheCheckStands() = runTest {
        val f = RampFixture()
        f.remote.kyc = AppResult.Success(checkDto("IN_REVIEW"))
        assertEquals(RampVerification(KycStatus.InReview, required = true, canStart = false), (f.repo.kyc() as AppResult.Success).value)
    }

    @Test fun aKycFrameIsPassedOnWithItsNetwork() = runTest(UnconfinedTestDispatcher()) {
        val f = RampFixture()
        val seen = mutableListOf<KycUpdate>()
        val orders = mutableListOf<RampOrderUpdate>()
        backgroundScope.launch { f.repo.kycUpdates.collect { seen += it } }
        backgroundScope.launch { f.repo.updates.collect { orders += it } }
        // The guide's own frame data.
        f.repo.onFrame("kyc.updated", frame("""{ "network": "TESTNET", "status": "VERIFIED", "required": true, "canStart": false, "reason": null, "retryAt": null }"""))
        f.repo.onFrame("kyc.updated", frame("""{"network":"MAINNET","status":"DECLINED","required":true,"canStart":true,"reason":"liveness"}"""))
        assertEquals(
            listOf(
                KycUpdate(WalletNetwork.TESTNET, RampVerification(KycStatus.Verified, required = true, canStart = false)),
                KycUpdate(WalletNetwork.MAINNET, RampVerification(KycStatus.Declined, required = true, canStart = true, reason = "liveness")),
            ),
            seen,
        )
        assertTrue(orders.isEmpty(), "a check moving is not an order moving")
        assertEquals(0, f.arrivals)
        // Unreadable, or with no data: ignored, never guessed at.
        f.repo.onFrame("kyc.updated", null)
        f.repo.onFrame("kyc.updated", frame("""{"network":"TESTNET"}"""))
        assertEquals(2, seen.size)
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
            "ramp.custody_unavailable", "ramp.pricing_unavailable", "ramp.merchant_unavailable",
        )
        codes.forEach { assertTrue(ErrorMessages.isKnown(it), "no words for $it") }
        assertFalse(codes.any { ErrorMessages.forError(AppError.Api(it, 400, null, null, emptyMap())).contains("CoinX") })
        // The seller can't take the payment right now: what didn't happen and what to do, never "on our side".
        assertEquals(
            "We couldn't start this payment right now. Nothing was taken. Please try again in a moment.",
            ErrorMessages.forError(AppError.Api("ramp.merchant_unavailable", 409, null, null, emptyMap())),
        )
    }

    // ---- identity verification (guide "Identity verification (KYC)"), against the guide's own JSON ---------------------

    @Test fun r0WithAnOpenCheckDecodesFromTheGuidesJson() {
        val start = json.decodeFromString(
            KycResponse.serializer(),
            """{ "status": "PENDING", "required": true, "canStart": true, "reason": null, "retryAt": null,
                 "session": { "sessionToken": "abc123", "url": "https://verify.didit.me/session/abc123", "expiresAt": "2026-11-23T12:00:00Z" } }""",
        ).toDomain()
        assertEquals(KycStatus.Pending, start.verification.status)
        assertTrue(start.verification.required && start.verification.canStart)
        assertNull(start.verification.reason)
        assertEquals("abc123", start.session?.sessionToken)
        assertEquals("https://verify.didit.me/session/abc123", start.session?.url)
        assertEquals(1795435200000L, start.session?.expiresAt)
    }

    @Test fun r0WithNothingToDoHasNoSession() {
        val start = json.decodeFromString(KycResponse.serializer(), """{ "status": "VERIFIED", "required": true, "canStart": false, "reason": null, "retryAt": null, "session": null }""").toDomain()
        assertEquals(KycStatus.Verified, start.verification.status)
        assertNull(start.session)
        assertTrue(start.verification.cleared)
    }

    @Test fun aSessionWithNoTokenIsNoSession() {
        val start = json.decodeFromString(KycResponse.serializer(), """{"status":"PENDING","required":true,"canStart":true,"session":{"sessionToken":"","url":""}}""").toDomain()
        assertNull(start.session)
    }

    @Test fun theReadDecodesFromTheGuidesJson() {
        val check = json.decodeFromString(VerificationDto.serializer(), """{ "status": "DECLINED", "required": true, "canStart": true, "reason": "face_match", "retryAt": null }""").toDomain()
        assertEquals(RampVerification(KycStatus.Declined, required = true, canStart = true, reason = "face_match", retryAt = null), check)
        assertFalse(check.cleared)
        val capped = json.decodeFromString(VerificationDto.serializer(), """{"status":"DECLINED","required":true,"canStart":false,"reason":"liveness","retryAt":"2026-11-23T09:05:00Z"}""").toDomain()
        assertEquals(1795424700000L, capped.retryAt)
    }

    @Test fun r1CarriesTheCheckAndTheBarBesideTheOldFields() {
        val status = json.decodeFromString(
            RampStatusResponse.serializer(),
            """{ "canTrade": false, "network": "TESTNET", "kyc": "NONE", "blocked": false,
                 "verification": { "status": "NONE", "required": true, "canStart": true, "reason": null, "retryAt": null },
                 "accountActive": true, "blockchains": ["BASE-SEPOLIA"], "merchantOnline": true, "offRampAvailable": false,
                 "openOrderId": null, "limits": {"currency":"KES","minOrder":{"unit":"KES","amountMinor":"10000","amount":"100"},"maxOrder":{"unit":"KES","amountMinor":"15000000","amount":"150000"},"daily":{"unit":"KES","amountMinor":"30000000","amount":"300000"}}, "feePercent": "1.5" }""",
        ).toDomain()
        assertFalse(status.blocked)
        assertEquals(RampVerification(KycStatus.None, required = true, canStart = true), status.verification)
        assertEquals(RampKyc.None, status.kyc)
        val barred = json.decodeFromString(RampStatusResponse.serializer(), json.encodeToString(RampStatusResponse.serializer(), statusDto(blocked = true))).toDomain()
        assertTrue(barred.blocked)
    }

    @Test fun aServerFromBeforeVerificationIsReadOffItsShortKyc() {
        fun old(kyc: String) = json.decodeFromString(
            RampStatusResponse.serializer(),
            """{"canTrade":true,"network":"TESTNET","kyc":"$kyc","limits":{"currency":"KES","minOrder":{"unit":"KES","amountMinor":"10000","amount":"100"},"maxOrder":{"unit":"KES","amountMinor":"15000000","amount":"150000"},"daily":{"unit":"KES","amountMinor":"30000000","amount":"300000"}}}""",
        ).toDomain()
        assertTrue(old("NOT_REQUIRED").verification.cleared, "not required: nothing changes")
        assertFalse(old("NOT_REQUIRED").verification.required)
        assertTrue(old("VERIFIED").verification.cleared)
        assertEquals(RampVerification(KycStatus.None, required = true, canStart = true), old("NONE").verification)
        assertFalse(old("NONE").blocked)
    }

    @Test fun checkStatusesMapAndANewOneIsNeverAnEnding() {
        assertEquals(listOf(KycStatus.None, KycStatus.Pending, KycStatus.InReview, KycStatus.Verified, KycStatus.Declined, KycStatus.Expired),
            listOf("NONE", "PENDING", "IN_REVIEW", "VERIFIED", "DECLINED", "EXPIRED").map(KycStatus::of))
        assertEquals(KycStatus.Unknown, KycStatus.of("ESCALATED"))
        assertEquals(KycStatus.InReview, KycStatus.of("in_review"))
    }

    @Test fun aSessionNeverShowsInALogLine() {
        val start = openCheck().toDomain()
        listOf(start.toString(), start.session.toString(), openCheck().toString(), openCheck().session.toString()).forEach {
            assertFalse(it.contains(SessionToken), it)
            assertFalse(it.contains("verify.didit.me"), it)
        }
    }

    @Test fun everyVerificationCodeHasItsOwnWords() {
        val codes = listOf("ramp.blocked", "ramp.kyc_attempts_exceeded", "ramp.kyc_unavailable", "ramp.kyc_not_configured", "ramp.kyc_required")
        codes.forEach { assertTrue(ErrorMessages.isKnown(it), "no words for $it") }
        assertEquals("You can't add money on Circaso. Contact Support if you think this is a mistake.", ErrorMessages.forError(AppError.Api("ramp.blocked", 403, null, null, emptyMap())))
    }

    @Test fun theVerificationPushCarriesItsNetwork() {
        val data = mapOf("kind" to "ramp.kyc", "network" to "MAINNET")
        assertEquals(NotificationDestination.RampKyc("MAINNET"), NotificationDestination.fromPush(data))
        assertEquals(NotificationDestination.RampKyc("MAINNET"), NotificationDestination.fromExtras(data + ("google.message_id" to "0:1")))
        with(NotificationDestination) { assertEquals(NotificationDestination.RampKyc("TESTNET"), fromExtras(NotificationDestination.RampKyc("TESTNET").toExtras())) }
        assertEquals(NotificationDestination.RampKyc(), NotificationDestination.fromPush(mapOf("kind" to "ramp.kyc")))
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
        remote.startKyc("k-kyc")
        remote.kyc()
        remote.quote(QuoteRequest("ON_RAMP", "BASE-SEPOLIA", com.circaso.feature.ramp.data.remote.AmountDto(amount = "2000")), "k-quote")
        remote.buy(StartOnRampRequest("q1", Phone), "k-buy")
        remote.order("o1")
        remote.orders(limit = 20, cursor = "2026-11-22T10:00:00Z~abc")
        remote.cancel("o1", "k-cancel")

        assertEquals(
            listOf("GET /v1/ramp", "POST /v1/ramp/kyc", "GET /v1/ramp/kyc", "POST /v1/ramp/quotes", "POST /v1/ramp/onramp/orders", "GET /v1/ramp/orders/o1", "GET /v1/ramp/orders", "POST /v1/ramp/orders/o1/cancel"),
            seen.map { it.method.value + " " + it.url.encodedPath },
        )
        assertTrue(seen.all { it.headers[WalletNetworkHeader] == "TESTNET" })
        assertEquals(listOf(null, "k-kyc", null, "k-quote", "k-buy", null, null, "k-cancel"), seen.map { it.headers[IdempotencyKeyHeader] })
        assertEquals("20", seen[6].url.parameters["limit"])
        assertEquals("2026-11-22T10:00:00Z~abc", seen[6].url.parameters["cursor"])

        val quoteBody = json.parseToJsonElement((seen[3].body as TextContent).text).jsonObject
        assertEquals(setOf("direction", "blockchain", "fiat"), quoteBody.keys)
        assertEquals("""{"amount":"2000"}""", quoteBody["fiat"].toString())
        val buyBody = json.parseToJsonElement((seen[4].body as TextContent).text).jsonObject
        assertEquals("""{"quoteId":"q1","phone":"$Phone"}""", buyBody.toString())
    }

    @Test fun aServerWithoutNetworksIsSentNoHeader() = runTest {
        KtorRampRemote(api, WalletNetworkGate.None).status()
        assertNull(seen.single().headers[WalletNetworkHeader])
    }
}
