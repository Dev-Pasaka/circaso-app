package com.circaso.feature.ramp

import com.circaso.core.network.circasoJson
import com.circaso.feature.ramp.data.remote.QuoteResponse
import com.circaso.feature.ramp.data.remote.toDomain
import com.circaso.feature.ramp.domain.RampAmount
import com.circaso.feature.ramp.presentation.feeText
import com.circaso.feature.ramp.presentation.mpesaAskNote
import com.circaso.feature.ramp.presentation.mpesaFeeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** M-Pesa's own fee on a purchase (backend 2026-11-24): inside what is paid, and named on the price and the order. */
class AddMoneyFeesTest {
    @Test
    fun aBuyingQuoteCarriesMpesasFeeInsideWhatIsPaid() {
        val body = """{"quoteId":"q1","direction":"ON_RAMP","network":"TESTNET","blockchain":"BASE-SEPOLIA",
            "pay":{"unit":"KES","amountMinor":"49900","amount":"499"},"receive":{"unit":"USDC","amountMinor":"3810000","amount":"3.81"},
            "rate":"129","fee":{"unit":"KES","amountMinor":"800","amount":"8"},"expiresAt":"2026-11-24T10:01:00Z",
            "providerFee":{"unit":"KES","amountMinor":"600","amount":"6"}}"""
        val quote = circasoJson().decodeFromString(QuoteResponse.serializer(), body).toDomain()
        assertEquals("600", quote.providerFee?.amountMinor)
        assertEquals("KES 6, included", mpesaFeeText(quote.providerFee))
        assertEquals("M-Pesa will ask for KES 499. Confirm with your M-Pesa PIN.", mpesaAskNote(quote.pay))
    }

    @Test
    fun noFeeReadsAsNoneAndAnOlderServerShowsNoRow() {
        assertEquals("None", mpesaFeeText(RampAmount("KES", "0", "0")))
        assertNull(mpesaFeeText(null))
        assertEquals("M-Pesa will ask for KES 1,306. Confirm with your M-Pesa PIN.", mpesaAskNote(RampAmount("KES", "130600", "1306")))
        assertEquals("None", feeText("0", "KES 0", named = true), "beside \"M-Pesa fee: None\"")
        assertEquals("No fee", feeText("0", "KES 0"))
    }
}
