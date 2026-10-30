package com.circaso.feature.chama.data.remote

import com.circaso.core.common.AppResult
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.call
import com.circaso.core.network.idempotencyKey
import com.circaso.core.network.newIdempotencyKey
import com.circaso.feature.wallet.data.remote.AmountDto
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

// Guide section 7, Chama (CH1–CH6), and its section 11 shapes.

@Serializable data class ChamaAmountRequest(val asset: String = "USDC", val amountMinor: String)
@Serializable data class SetGoalRequest(val name: String, val target: ChamaAmountRequest, val deadline: String? = null, val treasurerUserId: String? = null)
@Serializable data class GoalDto(val chatId: String, val name: String, val target: AmountDto, val deadline: String? = null, val treasurerUserId: String, val createdBy: String, val updatedAt: String, val version: Long)
@Serializable data class ContributorDto(val userId: String, val contributed: AmountDto)
@Serializable data class ApprovalDto(val userId: String, val decision: String, val at: String)
@Serializable data class RequestDto(
    val requestId: String, val chatId: String, val requestedBy: String, val payeeUserId: String? = null, val payeeAddress: String? = null, val payeeName: String? = null,
    val amount: AmountDto, val note: String? = null, val state: String, val approvalsNeeded: Int, val approvals: List<ApprovalDto> = emptyList(), val transferId: String? = null,
    val expiresAt: String, val createdAt: String, val updatedAt: String,
)
@Serializable data class ChamaResponse(
    val goal: GoalDto, val memberCount: Int, val contributed: AmountDto, val paidOut: AmountDto, val held: AmountDto, val myContribution: AmountDto,
    val contributors: List<ContributorDto> = emptyList(), val openRequests: List<RequestDto> = emptyList(), val isAdmin: Boolean = false, val isTreasurer: Boolean = false,
)
@Serializable data class LedgerEntryDto(val direction: String, val at: String, val amount: AmountDto, val userId: String? = null, val transferId: String? = null, val request: RequestDto? = null)
@Serializable data class LedgerResponse(val entries: List<LedgerEntryDto> = emptyList(), val next: String? = null)
@Serializable data class ChamaPayeeRequest(val userId: String? = null, val address: String? = null, val name: String? = null)
@Serializable data class CreateRequestRequest(val to: ChamaPayeeRequest, val amount: ChamaAmountRequest, val note: String? = null)
@Serializable data class AttachPaymentRequest(val transferId: String)

interface ChamaRemote {
    suspend fun chama(chatId: String): AppResult<ChamaResponse>
    suspend fun setGoal(chatId: String, request: SetGoalRequest): AppResult<GoalDto>
    suspend fun ledger(chatId: String, before: String?, limit: Int): AppResult<LedgerResponse>
    suspend fun createRequest(chatId: String, request: CreateRequestRequest, idempotencyKey: String): AppResult<RequestDto>
    suspend fun decide(requestId: String, approve: Boolean): AppResult<RequestDto>
    suspend fun attachPayment(requestId: String, transferId: String): AppResult<RequestDto>
}

class KtorChamaRemote(private val api: CircasoApi) : ChamaRemote {
    override suspend fun chama(chatId: String) = api.call<ChamaResponse> { method = HttpMethod.Get; url("/v1/chama/chats/$chatId") }

    override suspend fun setGoal(chatId: String, request: SetGoalRequest) = api.call<GoalDto> {
        method = HttpMethod.Put
        url("/v1/chama/chats/$chatId/goal")
        idempotencyKey(newIdempotencyKey())
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun ledger(chatId: String, before: String?, limit: Int) = api.call<LedgerResponse> {
        method = HttpMethod.Get
        url("/v1/chama/chats/$chatId/ledger")
        parameter("limit", limit)
        before?.let { parameter("before", it) }
    }

    override suspend fun createRequest(chatId: String, request: CreateRequestRequest, idempotencyKey: String) = api.call<RequestDto> {
        method = HttpMethod.Post
        url("/v1/chama/chats/$chatId/requests")
        idempotencyKey(idempotencyKey)
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun decide(requestId: String, approve: Boolean) = api.call<RequestDto> {
        method = HttpMethod.Post
        url("/v1/chama/requests/$requestId/${if (approve) "approve" else "decline"}")
        idempotencyKey(newIdempotencyKey())
    }

    override suspend fun attachPayment(requestId: String, transferId: String) = api.call<RequestDto> {
        method = HttpMethod.Post
        url("/v1/chama/requests/$requestId/payment")
        idempotencyKey(newIdempotencyKey())
        contentType(ContentType.Application.Json)
        setBody(AttachPaymentRequest(transferId))
    }
}
