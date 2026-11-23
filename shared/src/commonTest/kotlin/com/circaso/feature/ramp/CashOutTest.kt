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
import com.circaso.feature.ramp.data.remote.DepositDto
import com.circaso.feature.ramp.data.remote.KtorRampRemote
import com.circaso.feature.ramp.data.remote.OffRampDto
import com.circaso.feature.ramp.data.remote.OrderResponse
import com.circaso.feature.ramp.data.remote.OrdersResponse
import com.circaso.feature.ramp.data.remote.QuoteResponse
import com.circaso.feature.ramp.data.remote.RampAmountDto
import com.circaso.feature.ramp.data.remote.RampStatusResponse
import com.circaso.feature.ramp.data.remote.StartOffRampRequest
import com.circaso.feature.ramp.data.remote.toDomain
import com.circaso.feature.ramp.domain.RampDirection
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.wallet.data.remote.AmountRequest
import com.circaso.feature.wallet.data.remote.PayeeRequest
import com.circaso.feature.wallet.data.remote.PrepareTransferRequest
import com.circaso.feature.wallet.domain.WalletNetwork
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal val SaleUsdc = RampAmountDto("USDC", "10000000", "10")
internal val SaleKes = RampAmountDto("KES", "126900", "1269")

/** R2 for a sale, as the guide's example has it. */
internal fun saleQuoteDto(id: String = "sq1", expiresAt: String = "2026-11-23T18:01:00Z") = QuoteResponse(
    id, "OFF_RAMP", "TESTNET", "BASE-SEPOLIA", SaleUsdc, SaleKes, "130", RampAmountDto("KES", "2600", "26"), expiresAt,
    providerFee = RampAmountDto("KES", "500", "5"), worth = RampAmountDto("KES", "130000", "1300"),
)

/** A sale (R7, R4) in [state]; `deposit` only while it waits for the USDC. */
internal fun saleDto(state: String, id: String = "s1", receipt: String? = null, txHash: String? = null, reason: String? = null, expiresAt: String = "2026-11-23T18:15:00Z") = OrderResponse(
    orderId = id, direction = "OFF_RAMP", state = state, network = "TESTNET", blockchain = "BASE-SEPOLIA", pay = SaleUsdc, receive = SaleKes, rate = "130",
    fee = RampAmountDto("KES", "2600", "26"), maskedPhone = Masked, merchantName = "Crynce", txHash = txHash, failureReason = reason,
    expiresAt = expiresAt, createdAt = "2026-11-23T18:00:00Z", updatedAt = "2026-11-23T18:00:00Z",
    providerFee = RampAmountDto("KES", "500", "5"),
    deposit = if (state == "AWAITING_TRANSFER") DepositDto("0x5a3c0000000000000000000000000000000beef", "BASE-SEPOLIA", "USDC", "10000000", "10", expiresAt) else null,
    receipt = receipt,
)

/** R1 with selling open (or as [offRamp] says). */
internal fun saleStatusDto(offRamp: OffRampDto? = OffRampDto(true, true, true, com.circaso.feature.ramp.data.remote.LimitsDto("KES", kes("10"), kes("5000"), kes("20000"))), open: String? = null, kyc: String = "NOT_REQUIRED", verification: com.circaso.feature.ramp.data.remote.VerificationDto? = null, blocked: Boolean = false, active: Boolean = true) =
    statusDto(open = open, kyc = kyc, verification = verification, blocked = blocked, active = active).copy(offRamp = offRamp, offRampAvailable = offRamp?.canSell == true)

/** Selling USDC for M-Pesa against the guide's own JSON ("Selling USDC for M-Pesa", 2026-11-23). */
class CashOutWireTest {
    private val json = circasoJson()

    @Test fun aSalesQuoteDecodesFromTheGuidesJson() {
        val q = json.decodeFromString(QuoteResponse.serializer(), """
            { "quoteId": "0193aa", "direction": "OFF_RAMP", "network": "TESTNET", "blockchain": "BASE-SEPOLIA",
              "pay":         { "unit": "USDC", "amountMinor": "10000000", "amount": "10" },
              "receive":     { "unit": "KES",  "amountMinor": "126900",   "amount": "1269" },
              "rate": "130",
              "worth":       { "unit": "KES",  "amountMinor": "130000",   "amount": "1300" },
              "fee":         { "unit": "KES",  "amountMinor": "2600",     "amount": "26" },
              "providerFee": { "unit": "KES",  "amountMinor": "500",      "amount": "5" },
              "expiresAt": "2026-11-23T18:01:00Z" }
        """.trimIndent()).toDomain()
        assertEquals(RampDirection.OffRamp, q.direction)
        assertEquals("10 USDC", q.pay.text.replace(".00", ""))
        assertEquals("KES 1,269", q.receive.text)
        assertEquals("KES 1,300", q.worth?.text)
        assertEquals("KES 26", q.fee.text)
        assertEquals("KES 5", q.providerFee?.text)
        assertNotNull(q.expiresAt)
    }

    @Test fun aBuyingQuoteHasNoPayoutFee() {
        val q = quoteDto().toDomain()
        assertEquals(RampDirection.OnRamp, q.direction)
        assertNull(q.providerFee)
        assertNull(q.worth)
    }

    @Test fun r7DecodesFromTheGuidesJsonWithItsDeposit() {
        val o = json.decodeFromString(OrderResponse.serializer(), """
            { "orderId": "0193bb", "direction": "OFF_RAMP", "state": "AWAITING_TRANSFER", "network": "TESTNET", "blockchain": "BASE-SEPOLIA",
              "pay": { "unit": "USDC", "amountMinor": "10000000", "amount": "10" }, "receive": { "unit": "KES", "amountMinor": "126900", "amount": "1269" },
              "rate": "130", "fee": { "unit": "KES", "amountMinor": "2600", "amount": "26" }, "providerFee": { "unit": "KES", "amountMinor": "500", "amount": "5" },
              "maskedPhone": "+254 7•• ••• 678", "merchantName": "Crynce",
              "deposit": { "address": "0x5a3c", "blockchain": "BASE-SEPOLIA", "asset": "USDC",
                           "amountMinor": "10000000", "amount": "10", "expiresAt": "2026-11-23T18:15:00Z" },
              "expiresAt": "2026-11-23T18:15:00Z", "txHash": null, "receipt": null, "failureReason": null,
              "createdAt": "2026-11-23T18:00:00Z", "updatedAt": "2026-11-23T18:00:00Z" }
        """.trimIndent()).toDomain()
        assertTrue(o.sale)
        assertEquals(RampOrderState.AwaitingTransfer, o.state)
        assertFalse(o.state.settled)
        val d = assertNotNull(o.deposit)
        assertEquals("0x5a3c", d.address)
        assertEquals("BASE-SEPOLIA", d.blockchain)
        assertEquals("USDC", d.asset)
        assertEquals("10000000", d.amountMinor)
        assertNotNull(d.expiresAt)
        assertEquals("KES 5", o.providerFee?.text)
        assertNull(o.receipt)
    }

    @Test fun aPaidOutSaleCarriesItsReceipt() {
        val o = saleDto("COMPLETED", receipt = "TKN3H2X1QP").toDomain()
        assertEquals(RampOrderState.Completed, o.state)
        assertEquals("TKN3H2X1QP", o.receipt)
        assertNull(o.deposit, "deposit is only there while the sale waits for its USDC")
    }

    @Test fun aDepositWithNoAddressIsNeverSentTo() {
        val o = saleDto("AWAITING_TRANSFER").copy(deposit = DepositDto("", "BASE-SEPOLIA", "USDC", "10000000")).toDomain()
        assertNull(o.deposit)
    }

    @Test fun everySalesStateMapsAndOnlyTheEndingsSettle() {
        val states = mapOf(
            "AWAITING_TRANSFER" to RampOrderState.AwaitingTransfer, "FUNDED" to RampOrderState.Funded, "PAYING_OUT" to RampOrderState.PayingOut,
            "COMPLETED" to RampOrderState.Completed, "REFUNDING" to RampOrderState.Refunding, "REFUNDED" to RampOrderState.Refunded,
            "EXPIRED" to RampOrderState.Expired, "CANCELLED" to RampOrderState.Cancelled, "REVIEW" to RampOrderState.Review,
        )
        states.forEach { (wire, state) -> assertEquals(state, RampOrderState.of(wire), wire) }
        assertTrue(listOf(RampOrderState.Completed, RampOrderState.Refunded, RampOrderState.Expired, RampOrderState.Cancelled, RampOrderState.Review).all { it.settled })
        assertTrue(listOf(RampOrderState.AwaitingTransfer, RampOrderState.Funded, RampOrderState.PayingOut, RampOrderState.Refunding).none { it.settled }, "a refund under way is still followed")
    }

    @Test fun r1CarriesSellingOnItsOwnSwitch() {
        val s = json.decodeFromString(RampStatusResponse.serializer(), """
            { "canTrade": false, "network": "TESTNET", "kyc": "VERIFIED", "accountActive": true, "blockchains": ["BASE-SEPOLIA"], "merchantOnline": false,
              "offRampAvailable": true, "openOrderId": null, "feePercent": "2",
              "limits": { "currency": "KES", "minOrder": { "unit": "KES", "amountMinor": "10000", "amount": "100" }, "maxOrder": { "unit": "KES", "amountMinor": "15000000", "amount": "150000" }, "daily": { "unit": "KES", "amountMinor": "30000000", "amount": "300000" } },
              "offRamp": { "open": true, "merchantOnline": true, "canSell": true,
                           "limits": { "currency": "KES", "minOrder": { "unit": "KES", "amountMinor": "1000", "amount": "10" }, "maxOrder": { "unit": "KES", "amountMinor": "500000", "amount": "5000" }, "daily": { "unit": "KES", "amountMinor": "2000000", "amount": "20000" } } } }
        """.trimIndent()).toDomain()
        val sale = assertNotNull(s.offRamp)
        assertTrue(sale.open && sale.merchantOnline && sale.canSell)
        assertEquals("KES 10", sale.limits?.minOrder?.text)
        assertEquals("KES 5,000", sale.limits?.maxOrder?.text)
        assertEquals("KES 20,000", sale.limits?.daily?.text)
        assertFalse(s.canTrade, "canTrade stays buying's")
        assertEquals("KES 150,000", s.limits.maxOrder.text, "buying keeps its own limits")
    }

    @Test fun aServerFromBeforeSellingOffersNoSale() {
        assertNull(statusDto().toDomain().offRamp)
    }

    @Test fun theSalesFrameCarriesItsDirection() = runTest(UnconfinedTestDispatcher()) {
        val f = RampFixture()
        val heard = mutableListOf<com.circaso.feature.ramp.domain.RampOrderUpdate>()
        val job = backgroundScope.launch { f.repo.updates.collect { heard += it } }
        f.repo.onFrame("order.updated", circasoJson().parseToJsonElement("""{"orderId":"s1","direction":"OFF_RAMP","state":"FUNDED","blockchain":"BASE-SEPOLIA","pay":{"unit":"USDC","amountMinor":"10000000","amount":"10"},"receive":{"unit":"KES","amountMinor":"126900","amount":"1269"}}""").jsonObject)
        assertEquals(RampOrderState.Funded, heard.single().state)
        assertEquals(RampDirection.OffRamp, heard.single().direction)
        assertEquals(0, f.arrivals, "a sale moving on doesn't refresh the wallet")
        job.cancel()
    }

    @Test fun theSalesCodesHaveWords() {
        listOf("ramp.offramp_unavailable", "ramp.payout_fee_unavailable", "wallet.ramp_order_funded").forEach { assertTrue(ErrorMessages.isKnown(it), "no words for $it") }
    }

    @Test fun theSaleRequestNeverShowsTheNumber() {
        assertFalse(StartOffRampRequest("q1", Phone).toString().contains("712"))
        assertFalse(DepositDto("0x5a3c", "BASE-SEPOLIA", "USDC", "10000000").toString().contains("5a3c"))
    }

    @Test fun w4NamesTheSaleAndLeavesItOutOtherwise() {
        val json = circasoJson()
        val named = json.encodeToString(PrepareTransferRequest.serializer(), PrepareTransferRequest("BASE-SEPOLIA", PayeeRequest(address = "0x5a3c"), AmountRequest("USDC", "10000000"), rampOrderId = "s1"))
        assertEquals("s1", json.parseToJsonElement(named).jsonObject["rampOrderId"].toString().trim('"'))
        val plain = json.encodeToString(PrepareTransferRequest.serializer(), PrepareTransferRequest("BASE-SEPOLIA", PayeeRequest(address = "0x5a3c"), AmountRequest("USDC", "10000000")))
        assertFalse(json.parseToJsonElement(plain).jsonObject.containsKey("rampOrderId"), "an ordinary payment never carries the field")
    }
}

/** The sale's data layer: what goes on the wire, what is kept, what a frame does. */
@OptIn(ExperimentalCoroutinesApi::class)
class CashOutRepositoryTest {
    @Test fun aSalesQuoteSendsOneSideOnly() = runTest {
        val f = RampFixture()
        f.remote.quote = AppResult.Success(saleQuoteDto())
        f.repo.sellQuote("BASE-SEPOLIA", "10500000", null, "k1")
        f.repo.sellQuote("BASE-SEPOLIA", null, "100000", "k2")
        val (usdc, k1) = f.remote.quotes[0]
        assertEquals("OFF_RAMP", usdc.direction)
        assertEquals("10.5", usdc.crypto?.amount)
        assertNull(usdc.fiat)
        assertEquals("k1", k1)
        val (kesAsk, _) = f.remote.quotes[1]
        assertEquals("1000", kesAsk.fiat?.amount, "whole shillings")
        assertNull(kesAsk.crypto)
    }

    @Test fun aLargeSaleIsSentWithoutGrouping() = runTest {
        val f = RampFixture()
        f.repo.sellQuote("BASE-SEPOLIA", "1250000000", null, "k")
        assertEquals("1250", f.remote.quotes.single().first.crypto?.amount)
    }

    @Test fun sellingKeepsTheNumberOnceTheServerTookIt() = runTest {
        val f = RampFixture()
        val order = (f.repo.sell("sq1", Phone, "k-sell") as AppResult.Success).value
        assertEquals("sq1" to Phone, f.remote.sells.single().first.let { it.quoteId to it.phone })
        assertEquals("k-sell", f.remote.sells.single().second)
        assertEquals(Phone, f.phones.phone)
        assertEquals(RampOrderState.AwaitingTransfer, order.state)
    }

    @Test fun aNumberTheSaleRefusesIsForgotten() = runTest {
        val f = RampFixture(phones = MemoryPhone(Phone))
        f.remote.sell = refusal("ramp.phone_not_yours", http = 400)
        f.repo.sell("sq1", Phone, "k")
        assertNull(f.phones.phone)
    }

    @Test fun aSalePaidFromHereIsLabelledAndFollowsItsOrder() = runTest {
        val f = RampFixture()
        val order = saleDto("AWAITING_TRANSFER").toDomain()
        f.repo.saleSent(order, "TR-1")
        assertEquals("tr-1", f.repo.saleTransfer("s1"))
        var sales = f.repo.sales.first()
        val label = assertNotNull(sales.paidBy("TR-1"))
        assertEquals("KES 1,269", label.receive.text)
        assertEquals(Masked, label.maskedPhone)
        assertNull(label.receipt)

        // Paid out: R4 brings the receipt, and the label keeps it.
        f.remote.order = AppResult.Success(saleDto("COMPLETED", receipt = "TKN3H2X1QP"))
        f.repo.order("s1")
        sales = f.repo.sales.first()
        assertEquals("TKN3H2X1QP", sales.paidBy("tr-1")?.receipt)
        assertEquals(RampOrderState.Completed, sales.paidBy("tr-1")?.state)
        assertEquals(0, f.arrivals, "a payout doesn't change the wallet")
        assertTrue(f.repo.topUps.first().isEmpty(), "a sale is never labelled as a top-up")
    }

    @Test fun aRefundedSaleLabelsItsRefundAndRefreshesTheWalletOnce() = runTest {
        val f = RampFixture()
        f.repo.saleSent(saleDto("AWAITING_TRANSFER").toDomain(), "tr-1")
        f.remote.order = AppResult.Success(saleDto("REFUNDED", txHash = "0xREFUND", reason = "payout_failed"))
        f.repo.order("s1")
        f.repo.order("s1")
        assertEquals("s1", f.repo.sales.first().refundedBy("0xrefund")?.orderId)
        assertEquals(1, f.arrivals, "the USDC came back: the wallet is read again, once")
    }

    @Test fun aSaleThisPhoneDidNotPayIsNotLabelled() = runTest {
        val f = RampFixture()
        f.remote.order = AppResult.Success(saleDto("COMPLETED", receipt = "X"))
        f.repo.order("s1")
        assertNull(f.repo.sales.first().paidBy("anything"))
        assertNull(f.repo.saleTransfer("s1"))
    }

    @Test fun salesAreKeptPerNetwork() = runTest {
        val f = RampFixture()
        f.repo.saleSent(saleDto("AWAITING_TRANSFER").toDomain(), "tr-1")
        assertNotNull(f.salesSlot(WalletNetwork.TESTNET).value)
        assertNull(f.sales[WalletNetwork.MAINNET]?.value)
    }

    @Test fun aPaidOutFrameReadsTheSaleSoItsLabelGetsTheReceipt() = runTest(UnconfinedTestDispatcher()) {
        val f = RampFixture()
        f.repo.saleSent(saleDto("AWAITING_TRANSFER").toDomain(), "tr-1")
        f.remote.order = AppResult.Success(saleDto("COMPLETED", receipt = "TKN3H2X1QP"))
        f.repo.onFrame("order.updated", circasoJson().parseToJsonElement("""{"orderId":"s1","direction":"OFF_RAMP","state":"COMPLETED","blockchain":"BASE-SEPOLIA","network":"TESTNET"}""").jsonObject)
        assertEquals(listOf("s1"), f.remote.reads)
        assertEquals("TKN3H2X1QP", f.repo.sales.first().paidBy("tr-1")?.receipt)
    }
}

/** R7 on the wire: the network header, the key, the body. */
class CashOutRemoteTest {
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

    @Test fun r7CarriesTheNetworkTheKeyAndTheBody() = runTest {
        KtorRampRemote(api, object : WalletNetworkGate { override suspend fun header(): String? = "TESTNET" }).sell(StartOffRampRequest("sq1", Phone), "k-sell")
        val r = seen.single()
        assertEquals("POST /v1/ramp/offramp/orders", r.method.value + " " + r.url.encodedPath)
        assertEquals("TESTNET", r.headers[WalletNetworkHeader])
        assertEquals("k-sell", r.headers[IdempotencyKeyHeader])
        assertEquals("""{"quoteId":"sq1","phone":"$Phone"}""", json.parseToJsonElement((r.body as TextContent).text).jsonObject.toString())
    }
}

