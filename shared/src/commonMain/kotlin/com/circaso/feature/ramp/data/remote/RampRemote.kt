package com.circaso.feature.ramp.data.remote

import com.circaso.core.common.AppResult
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.WalletNetworkGate
import com.circaso.core.network.idempotencyKey
import com.circaso.core.network.walletCall
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

/** The ramp's routes for the app (guide R0–R7). */
interface RampRemote {
    suspend fun status(): AppResult<RampStatusResponse>
    suspend fun startKyc(idempotencyKey: String): AppResult<KycResponse>
    suspend fun kyc(): AppResult<VerificationDto>
    suspend fun quote(request: QuoteRequest, idempotencyKey: String): AppResult<QuoteResponse>
    suspend fun buy(request: StartOnRampRequest, idempotencyKey: String): AppResult<OrderResponse>
    /** R7: sell USDC for M-Pesa. */
    suspend fun sell(request: StartOffRampRequest, idempotencyKey: String): AppResult<OrderResponse>
    suspend fun order(orderId: String): AppResult<OrderResponse>
    suspend fun orders(limit: Int = 50, cursor: String? = null): AppResult<OrdersResponse>
    suspend fun cancel(orderId: String, idempotencyKey: String): AppResult<OrderResponse>
}

/**
 * The ramp over Ktor. Every route sits in the server's wallet-network group, so each call goes through
 * [walletCall]: it carries `X-Wallet-Network` from the same gate the wallet's calls use.
 */
class KtorRampRemote(
    private val api: CircasoApi,
    private val network: WalletNetworkGate = WalletNetworkGate.None,
) : RampRemote {
    private suspend inline fun <reified T> call(noinline block: HttpRequestBuilder.() -> Unit): AppResult<T> = api.walletCall(network, block)

    override suspend fun status() = call<RampStatusResponse> { method = HttpMethod.Get; url("/v1/ramp") }

    // R0. No body: the server knows who is asking and, from the header, on which network.
    override suspend fun startKyc(idempotencyKey: String) = call<KycResponse> {
        method = HttpMethod.Post
        url("/v1/ramp/kyc")
        idempotencyKey(idempotencyKey)
    }

    override suspend fun kyc() = call<VerificationDto> { method = HttpMethod.Get; url("/v1/ramp/kyc") }

    override suspend fun quote(request: QuoteRequest, idempotencyKey: String) = call<QuoteResponse> {
        method = HttpMethod.Post
        url("/v1/ramp/quotes")
        idempotencyKey(idempotencyKey)
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun buy(request: StartOnRampRequest, idempotencyKey: String) = call<OrderResponse> {
        method = HttpMethod.Post
        url("/v1/ramp/onramp/orders")
        idempotencyKey(idempotencyKey)
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun sell(request: StartOffRampRequest, idempotencyKey: String) = call<OrderResponse> {
        method = HttpMethod.Post
        url("/v1/ramp/offramp/orders")
        idempotencyKey(idempotencyKey)
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun order(orderId: String) = call<OrderResponse> { method = HttpMethod.Get; url("/v1/ramp/orders/$orderId") }

    override suspend fun orders(limit: Int, cursor: String?) = call<OrdersResponse> {
        method = HttpMethod.Get
        url("/v1/ramp/orders")
        parameter("limit", limit)
        cursor?.let { parameter("cursor", it) }
    }

    override suspend fun cancel(orderId: String, idempotencyKey: String) = call<OrderResponse> {
        method = HttpMethod.Post
        url("/v1/ramp/orders/$orderId/cancel")
        idempotencyKey(idempotencyKey)
    }
}
