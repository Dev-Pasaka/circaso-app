package com.circaso.feature.wallet.data.remote

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.flags.GroupWalletsFlag
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.call
import com.circaso.core.network.idempotencyKey
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

/**
 * Every wallet route. A `walletId` of null is the person's own wallet, exactly as before group wallets; a group
 * wallet's id reads (W1, W6, W12) or signs for (W3, W4, W13) that group's wallet (guide "Group wallets", PR #28).
 */
interface WalletRemote {
    suspend fun wallet(currency: String?, walletId: String? = null): AppResult<WalletResponse>
    suspend fun create(idempotencyKey: String): AppResult<CreateWalletResponse>
    suspend fun session(walletId: String? = null): AppResult<SdkDto>
    suspend fun chains(walletId: String? = null): AppResult<ChainsResponse>
    suspend fun addChain(blockchain: String, idempotencyKey: String): AppResult<AddChainResponse>
    suspend fun addMissingChains(idempotencyKey: String, walletId: String? = null): AppResult<AddMissingChainsResponse>
    suspend fun prepare(request: PrepareTransferRequest, idempotencyKey: String): AppResult<PrepareTransferResponse>
    suspend fun transfer(transferId: String): AppResult<TransferResponse>
    suspend fun transactions(cursor: String? = null, limit: Int = 50, walletId: String? = null): AppResult<TransactionsResponse>
    suspend fun recipient(userId: String, blockchain: String?, chatId: String?): AppResult<RecipientResponse>
    suspend fun remind(userId: String, idempotencyKey: String): AppResult<RemindResponse>
    suspend fun validateAddress(request: ValidateAddressRequest): AppResult<ValidateAddressResponse>

    /** W16: every wallet the person can see, their own first. */
    suspend fun wallets(): AppResult<WalletsResponse>

    /** W15: a group's wallet (members). */
    suspend fun groupWallet(chatId: String): AppResult<GroupWalletResponse>

    /** W14: set up a group's wallet (the owner); run its challenge with its own `sdk`. */
    suspend fun setUpGroupWallet(chatId: String, idempotencyKey: String): AppResult<GroupWalletSetupResponse>

    /** W19: a space's wallet (its owner and admins). */
    suspend fun spaceWallet(spaceId: String): AppResult<SpaceWalletResponse> = AppResult.Failure(AppError.Api("request.not_found", 404, null, null, emptyMap()))

    /** W18: the space's owner sets up its wallet; run its challenge with its own `sdk`. */
    suspend fun setUpSpaceWallet(spaceId: String, idempotencyKey: String): AppResult<SpaceWalletSetupResponse> = AppResult.Failure(AppError.Api("request.not_found", 404, null, null, emptyMap()))
}

/**
 * The wallet over Ktor. [groupWallets] guards the wire: a group-wallet field (`walletId`, `payoutRequestId`,
 * `to.groupId`) is never sent to a server that hasn't shown it understands them, because an older server ignores them
 * and would act on the personal wallet instead. The group-wallet routes tell the flag what the server is.
 */
class KtorWalletRemote(private val api: CircasoApi, private val groupWallets: GroupWalletsFlag = GroupWalletsFlag.off()) : WalletRemote {
    /** The answer for a group-wallet call the server can't be trusted with yet: nothing was sent. */
    private fun <T> refused(): AppResult<T> = AppResult.Failure(AppError.Api(GroupWalletsUnavailable, 400, null, null, emptyMap()))

    private fun HttpRequestBuilder.wallet(walletId: String?) { walletId?.let { parameter("walletId", it) } }

    override suspend fun wallet(currency: String?, walletId: String?): AppResult<WalletResponse> {
        if (walletId != null && !groupWallets.canSendWalletId) return refused()
        return api.call<WalletResponse> {
            method = HttpMethod.Get
            url("/v1/wallet")
            currency?.let { parameter("currency", it) }
            wallet(walletId)
        }
    }

    override suspend fun create(idempotencyKey: String) = api.call<CreateWalletResponse> {
        method = HttpMethod.Post
        url("/v1/wallet")
        idempotencyKey(idempotencyKey)
    }

    override suspend fun session(walletId: String?): AppResult<SdkDto> {
        if (walletId != null && !groupWallets.canSendWalletId) return refused()
        return api.call<SdkDto> {
            method = HttpMethod.Post
            url("/v1/wallet/session")
            wallet(walletId)
            idempotencyKey(com.circaso.core.network.newIdempotencyKey())
        }
    }

    override suspend fun chains(walletId: String?): AppResult<ChainsResponse> {
        if (walletId != null && !groupWallets.canSendWalletId) return refused()
        return api.call<ChainsResponse> {
            method = HttpMethod.Get
            url("/v1/wallet/chains")
            wallet(walletId)
        }
    }

    override suspend fun addMissingChains(idempotencyKey: String, walletId: String?): AppResult<AddMissingChainsResponse> {
        if (walletId != null && !groupWallets.canSendWalletId) return refused()
        return api.call<AddMissingChainsResponse> {
            method = HttpMethod.Post
            url("/v1/wallet/chains/missing")
            wallet(walletId)
            idempotencyKey(idempotencyKey)
        }
    }

    override suspend fun addChain(blockchain: String, idempotencyKey: String) = api.call<AddChainResponse> {
        method = HttpMethod.Post
        url("/v1/wallet/chains")
        idempotencyKey(idempotencyKey)
        contentType(ContentType.Application.Json)
        setBody(AddChainRequest(blockchain))
    }

    override suspend fun prepare(request: PrepareTransferRequest, idempotencyKey: String): AppResult<PrepareTransferResponse> {
        val groupShaped = request.walletId != null || request.payoutRequestId != null || request.to.groupId != null
        if (groupShaped && !groupWallets.canSendWalletId) return refused()
        return api.call<PrepareTransferResponse> {
            method = HttpMethod.Post
            url("/v1/wallet/transfers")
            idempotencyKey(idempotencyKey)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun transfer(transferId: String) = api.call<TransferResponse> {
        method = HttpMethod.Get
        url("/v1/wallet/transfers/$transferId")
    }

    override suspend fun transactions(cursor: String?, limit: Int, walletId: String?): AppResult<TransactionsResponse> {
        if (walletId != null && !groupWallets.canSendWalletId) return refused()
        return api.call<TransactionsResponse> {
            method = HttpMethod.Get
            url("/v1/wallet/transactions")
            cursor?.let { parameter("cursor", it) }
            parameter("limit", limit)
            wallet(walletId)
        }
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

    override suspend fun wallets(): AppResult<WalletsResponse> {
        if (!groupWallets.isOn) return refused()
        return api.call<WalletsResponse> { method = HttpMethod.Get; url("/v1/wallets") }.also(::learn)
    }

    override suspend fun groupWallet(chatId: String): AppResult<GroupWalletResponse> {
        if (!groupWallets.isOn) return refused()
        return api.call<GroupWalletResponse> { method = HttpMethod.Get; url("/v1/wallet/groups/$chatId") }.also(::learn)
    }

    override suspend fun setUpGroupWallet(chatId: String, idempotencyKey: String): AppResult<GroupWalletSetupResponse> {
        // A server that didn't answer W15/W16 yet is asked first, so W14 can't land on one that would ignore it.
        if (!groupWallets.canSendWalletId) {
            wallets()
            if (!groupWallets.canSendWalletId) return refused()
        }
        return api.call<GroupWalletSetupResponse> {
            method = HttpMethod.Post
            url("/v1/wallet/groups/$chatId")
            idempotencyKey(idempotencyKey)
        }
    }

    override suspend fun spaceWallet(spaceId: String): AppResult<SpaceWalletResponse> =
        api.call<SpaceWalletResponse> { method = HttpMethod.Get; url("/v1/wallet/spaces/$spaceId") }.also(::learn)

    override suspend fun setUpSpaceWallet(spaceId: String, idempotencyKey: String): AppResult<SpaceWalletSetupResponse> =
        api.call<SpaceWalletSetupResponse> {
            method = HttpMethod.Post
            url("/v1/wallet/spaces/$spaceId")
            idempotencyKey(idempotencyKey)
        }.also(::learn)

    /** What a group-wallet route's answer says about the server. */
    private fun learn(result: AppResult<*>) {
        when (result) {
            is AppResult.Success -> groupWallets.onServerAnswered()
            is AppResult.Failure -> {
                val api = result.error as? AppError.Api ?: return
                if (api.code == "request.not_found" && api.httpStatus == 404) groupWallets.onServerMissing()
                else if (api.code.startsWith("wallet.")) groupWallets.onServerAnswered()
            }
        }
    }

    companion object {
        /** Nothing was sent: this server doesn't have group wallets (or hasn't said so yet). */
        const val GroupWalletsUnavailable = "wallet.group_wallets_unavailable"
    }
}
