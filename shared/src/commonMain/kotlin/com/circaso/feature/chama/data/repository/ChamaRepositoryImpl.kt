package com.circaso.feature.chama.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.map
import com.circaso.core.sync.nowMillis
import com.circaso.feature.chama.data.local.ChamaDao
import com.circaso.feature.chama.data.local.ChamaSnapshotEntity
import com.circaso.feature.chama.data.remote.ChamaAmountRequest
import com.circaso.feature.chama.data.remote.ChamaPayeeRequest
import com.circaso.feature.chama.data.remote.ChamaRemote
import com.circaso.feature.chama.data.remote.ChamaResponse
import com.circaso.feature.chama.data.remote.CreateRequestRequest
import com.circaso.feature.chama.data.remote.GoalDto
import com.circaso.feature.chama.data.remote.RequestDto
import com.circaso.feature.chama.data.remote.SetGoalRequest
import com.circaso.feature.chama.domain.Approval
import com.circaso.feature.chama.domain.Chama
import com.circaso.feature.chama.domain.ChamaGoal
import com.circaso.feature.chama.domain.ChamaRepository
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.domain.Contributor
import com.circaso.feature.chama.domain.LedgerDirection
import com.circaso.feature.chama.domain.LedgerEntry
import com.circaso.feature.chama.domain.RequestState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.circaso.feature.chama.domain.AlreadyLinkedCodes
import com.circaso.feature.chama.domain.PendingLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class ChamaRepositoryImpl(
    private val remote: ChamaRemote,
    private val dao: ChamaDao,
    private val json: Json,
    private val links: PendingLinks,
    /** The app's scope: a link in flight outlives the screen that started it. */
    private val scope: CoroutineScope,
) : ChamaRepository {
    private val linking = Mutex()

    /** The copy, with any payment still being linked shown on its request, so nobody is offered to pay it again. */
    override fun chama(chatId: String): Flow<Chama?> = combine(dao.observe(chatId), links.pendingLinks) { row, pending ->
        row?.let { decode(it) }?.let { c ->
            c.copy(openRequests = c.openRequests.map { r -> if (r.transferId == null) pending[r.requestId]?.let { r.copy(transferId = it.second) } ?: r else r })
        }
    }

    override suspend fun refresh(chatId: String): AppResult<Chama?> = when (val r = remote.chama(chatId)) {
        is AppResult.Success -> AppResult.Success(save(chatId, r.value))
        is AppResult.Failure -> if ((r.error as? AppError.Api)?.code == "chama.no_goal") { dao.delete(chatId); AppResult.Success(null) } else r
    }

    private suspend fun save(chatId: String, response: ChamaResponse): Chama {
        val at = nowMillis()
        dao.save(ChamaSnapshotEntity(chatId, json.encodeToString(ChamaResponse.serializer(), response), at))
        return response.toDomain(at)
    }

    override suspend fun setGoal(chatId: String, name: String, targetMinor: String, deadline: String?, treasurerUserId: String?): AppResult<ChamaGoal> =
        remote.setGoal(chatId, SetGoalRequest(name, ChamaAmountRequest(amountMinor = targetMinor), deadline = deadline, treasurerUserId = treasurerUserId)).map { it.toDomain() }.also { if (it is AppResult.Success) refresh(chatId) }

    override suspend fun ledger(chatId: String, before: String?): AppResult<List<LedgerEntry>> = remote.ledger(chatId, before, 50).map { r ->
        r.entries.map { e -> LedgerEntry(if (e.direction == "IN") LedgerDirection.In else LedgerDirection.Out, e.at, e.amount.amountMinor, e.userId, e.transferId, e.request?.toDomain()) }
    }

    override suspend fun createRequest(chatId: String, payeeUserId: String?, payeeAddress: String?, payeeName: String?, amountMinor: String, note: String?, idempotencyKey: String): AppResult<ChamaRequest> =
        remote.createRequest(chatId, CreateRequestRequest(ChamaPayeeRequest(payeeUserId, payeeAddress, payeeName), ChamaAmountRequest(amountMinor = amountMinor), note), idempotencyKey)
            .map { it.toDomain() }.also { if (it is AppResult.Success) refresh(chatId) }

    override suspend fun decide(requestId: String, approve: Boolean): AppResult<ChamaRequest> =
        remote.decide(requestId, approve).map { it.toDomain() }.also { r -> if (r is AppResult.Success) refresh(r.value.chatId) }

    override suspend fun attachPayment(chatId: String, requestId: String, transferId: String): AppResult<ChamaRequest> = scope.async {
        links.putLink(requestId, chatId, transferId)
        send(chatId, requestId, transferId)
    }.await()

    override suspend fun retryPendingLinks() {
        if (!linking.tryLock()) return
        try {
            links.pendingLinks.first().forEach { (requestId, pair) -> send(pair.first, requestId, pair.second) }
        } finally { linking.unlock() }
    }

    /** The server has it (or had it already): forget it. A network failure keeps it for the next try. */
    private suspend fun send(chatId: String, requestId: String, transferId: String): AppResult<ChamaRequest> {
        val r = remote.attachPayment(requestId, transferId).map { it.toDomain() }
        val code = ((r as? AppResult.Failure)?.error as? AppError.Api)?.code
        when {
            r is AppResult.Success -> links.removeLink(requestId)
            code in AlreadyLinkedCodes -> links.removeLink(requestId)
            code == "chama.transfer_mismatch" || code == "chama.request_not_found" || code == "chama.not_treasurer" -> links.removeLink(requestId)
        }
        refresh(chatId)
        return r
    }

    override suspend fun onFrame(type: String, data: JsonObject?) {
        val chatId = data?.get("chatId")?.jsonPrimitive?.contentOrNull ?: return
        when (type) {
            "goal.updated", "contribution.added", "request.updated" -> { refresh(chatId); retryPendingLinks() }
        }
    }

    private fun decode(row: ChamaSnapshotEntity): Chama? = try { json.decodeFromString(ChamaResponse.serializer(), row.json).toDomain(row.fetchedAt) } catch (e: Exception) { null }
}

internal fun GoalDto.toDomain() = ChamaGoal(chatId, name, target.amountMinor, deadline, treasurerUserId, version)

internal fun RequestDto.toDomain() = ChamaRequest(
    requestId, chatId, requestedBy, payeeUserId, payeeAddress, payeeName, amount.amountMinor, note,
    state = when (state) { "PENDING" -> RequestState.Pending; "APPROVED" -> RequestState.Approved; "DECLINED" -> RequestState.Declined; "PAID" -> RequestState.Paid; else -> RequestState.Expired },
    approvalsNeeded = approvalsNeeded, approvals = approvals.map { Approval(it.userId, it.decision == "APPROVE", it.at) }, transferId = transferId,
    expiresAt = expiresAt, createdAt = createdAt, updatedAt = updatedAt,
)

internal fun ChamaResponse.toDomain(fetchedAt: Long) = Chama(
    goal = goal.toDomain(), memberCount = memberCount, contributedMinor = contributed.amountMinor, paidOutMinor = paidOut.amountMinor, heldMinor = held.amountMinor,
    myContributedMinor = myContribution.amountMinor, contributors = contributors.map { Contributor(it.userId, it.contributed.amountMinor) },
    openRequests = openRequests.map { it.toDomain() }, isAdmin = isAdmin, isTreasurer = isTreasurer, fetchedAt = fetchedAt,
)
