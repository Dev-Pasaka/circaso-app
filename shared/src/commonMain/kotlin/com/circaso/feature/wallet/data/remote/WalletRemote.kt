package com.circaso.feature.wallet.data.remote

import com.circaso.core.common.AppResult
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.call
import com.circaso.core.network.idempotencyKey
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

interface WalletRemote {
    suspend fun wallet(currency: String?): AppResult<WalletResponse>
    suspend fun create(idempotencyKey: String): AppResult<CreateWalletResponse>
    suspend fun session(): AppResult<SdkDto>
    suspend fun chains(): AppResult<ChainsResponse>
    suspend fun addChain(blockchain: String, idempotencyKey: String): AppResult<AddChainResponse>
    suspend fun addMissingChains(idempotencyKey: String): AppResult<AddMissingChainsResponse>
    suspend fun prepare(request: PrepareTransferRequest, idempotencyKey: String): AppResult<PrepareTransferResponse>
    suspend fun transfer(transferId: String): AppResult<TransferResponse>
    suspend fun transactions(cursor: String? = null, limit: Int = 50): AppResult<TransactionsResponse>
    suspend fun recipient(userId: String, blockchain: String?, chatId: String?): AppResult<RecipientResponse>
    suspend fun remind(userId: String, idempotencyKey: String): AppResult<RemindResponse>
    suspend fun validateAddress(request: ValidateAddressRequest): AppResult<ValidateAddressResponse>
}

class KtorWalletRemote(private val api: CircasoApi) : WalletRemote {
    override suspend fun wallet(currency: String?) = api.call<WalletResponse> {
        method = HttpMethod.Get
        url("/v1/wallet")
        currency?.let { parameter("currency", it) }
    }

    override suspend fun create(idempotencyKey: String) = api.call<CreateWalletResponse> {
        method = HttpMethod.Post
        url("/v1/wallet")
        idempotencyKey(idempotencyKey)
    }

    override suspend fun session() = api.call<SdkDto> {
        method = HttpMethod.Post
        url("/v1/wallet/session")
        idempotencyKey(com.circaso.core.network.newIdempotencyKey())
    }

    override suspend fun chains() = api.call<ChainsResponse> {
        method = HttpMethod.Get
        url("/v1/wallet/chains")
    }

    override suspend fun addMissingChains(idempotencyKey: String) = api.call<AddMissingChainsResponse> {
        method = HttpMethod.Post
        url("/v1/wallet/chains/missing")
        idempotencyKey(idempotencyKey)
    }

    override suspend fun addChain(blockchain: String, idempotencyKey: String) = api.call<AddChainResponse> {
        method = HttpMethod.Post
        url("/v1/wallet/chains")
        idempotencyKey(idempotencyKey)
        contentType(ContentType.Application.Json)
        setBody(AddChainRequest(blockchain))
    }

    override suspend fun prepare(request: PrepareTransferRequest, idempotencyKey: String) = api.call<PrepareTransferResponse> {
        method = HttpMethod.Post
        url("/v1/wallet/transfers")
        idempotencyKey(idempotencyKey)
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun transfer(transferId: String) = api.call<TransferResponse> {
        method = HttpMethod.Get
        url("/v1/wallet/transfers/$transferId")
    }

    override suspend fun transactions(cursor: String?, limit: Int) = api.call<TransactionsResponse> {
        method = HttpMethod.Get
        url("/v1/wallet/transactions")
        cursor?.let { parameter("cursor", it) }
        parameter("limit", limit)
    }

    override suspend fun recipient(userId: String, blockchain: String?, chatId: String?) = api.call<RecipientResponse> {
        method = HttpMethod.Get
        url("/v1/wallet/recipients/$userId")
        blockchain?.let { parameter("blockchain", it) }
        chatId?.let { parameter("chatId", it) }
    }

    override suspend fun remind(userId: String, idempotencyKey: String) = api.call<RemindResponse> {
        method = HttpMethod.Post
        url("/v1/wallet/recipients/$userId/remind")
        idempotencyKey(idempotencyKey)
    }

    override suspend fun validateAddress(request: ValidateAddressRequest) = api.call<ValidateAddressResponse> {
        method = HttpMethod.Post
        url("/v1/wallet/addresses/validate")
        idempotencyKey(com.circaso.core.network.newIdempotencyKey())
        contentType(ContentType.Application.Json)
        setBody(request)
    }
}

