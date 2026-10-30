package com.circaso.feature.chama.domain

import com.circaso.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * A chama (designs D2–D6, guide CH1–CH6): a group chat's shared goal. The money sits in the treasurer's own wallet;
 * contributing is paying the treasurer from the chat, and paying out needs the admins' approval then the treasurer's PIN.
 */
data class ChamaGoal(val chatId: String, val name: String, val targetMinor: String, val deadline: String?, val treasurerUserId: String, val version: Long)

/** Codes that mean the request already has its payment: the link landed on an earlier try. */
val AlreadyLinkedCodes = setOf("chama.request_closed", "chama.not_approved")

data class Contributor(val userId: String, val amountMinor: String)

enum class RequestState { Pending, Approved, Declined, Paid, Expired }

data class Approval(val userId: String, val approved: Boolean, val at: String)

data class ChamaRequest(
    val requestId: String,
    val chatId: String,
    val requestedBy: String,
    val payeeUserId: String?,
    val payeeAddress: String?,
    val payeeName: String?,
    val amountMinor: String,
    val note: String?,
    val state: RequestState,
    val approvalsNeeded: Int,
    val approvals: List<Approval>,
    val transferId: String?,
    val expiresAt: String,
    val createdAt: String,
    val updatedAt: String,
) {
    val approvedBy get() = approvals.filter { it.approved }.map { it.userId }
    val open get() = state == RequestState.Pending || state == RequestState.Approved
}

data class Chama(
    val goal: ChamaGoal,
    val memberCount: Int,
    val contributedMinor: String,
    val paidOutMinor: String,
    val heldMinor: String,
    val myContributedMinor: String,
    val contributors: List<Contributor>,
    val openRequests: List<ChamaRequest>,
    val isAdmin: Boolean,
    val isTreasurer: Boolean,
    val fetchedAt: Long,
) {
    /** 0..1 of the target reached. */
    val progress: Float
        get() {
            val target = goal.targetMinor.toDoubleOrNull() ?: return 0f
            val got = contributedMinor.toDoubleOrNull() ?: 0.0
            return if (target <= 0) 0f else (got / target).coerceIn(0.0, 1.0).toFloat()
        }
}

enum class LedgerDirection { In, Out }

data class LedgerEntry(val direction: LedgerDirection, val at: String, val amountMinor: String, val userId: String?, val transferId: String?, val request: ChamaRequest?)

/**
 * A treasurer's payment that went but isn't linked to its request yet (CH6). It is written before the link is sent and
 * removed only when the server has it, so a crash or a closed screen can't leave the request looking unpaid.
 */
interface PendingLinks {
    /** Request id → (chat id, transfer id). */
    val pendingLinks: Flow<Map<String, Pair<String, String>>>
    suspend fun putLink(requestId: String, chatId: String, transferId: String)
    suspend fun removeLink(requestId: String)
}

/** Reads come from the phone's copy (the last CH1 per chat); everything else is online-only. */
interface ChamaRepository {
    /** The chama of a group, null before the first read or when the group has no goal. */
    fun chama(chatId: String): Flow<Chama?>

    /** CH1; `chama.no_goal` clears the copy and is not an error for the screens. */
    suspend fun refresh(chatId: String): AppResult<Chama?>

    suspend fun setGoal(chatId: String, name: String, targetMinor: String, deadline: String?, treasurerUserId: String?): AppResult<ChamaGoal>

    suspend fun ledger(chatId: String, before: String?): AppResult<List<LedgerEntry>>

    suspend fun createRequest(chatId: String, payeeUserId: String?, payeeAddress: String?, payeeName: String?, amountMinor: String, note: String?, idempotencyKey: String): AppResult<ChamaRequest>

    suspend fun decide(requestId: String, approve: Boolean): AppResult<ChamaRequest>

    /** CH6. Recorded on the phone first, then sent on the app's own scope so leaving the screen doesn't drop it. */
    suspend fun attachPayment(chatId: String, requestId: String, transferId: String): AppResult<ChamaRequest>

    /** Sends every link still waiting (at start, on reconnect, on a chama frame). */
    suspend fun retryPendingLinks()

    /** A `chama` realtime frame. */
    suspend fun onFrame(type: String, data: kotlinx.serialization.json.JsonObject?)
}

class ObserveChamaUseCase(private val chamas: ChamaRepository) { operator fun invoke(chatId: String) = chamas.chama(chatId) }
class RefreshChamaUseCase(private val chamas: ChamaRepository) { suspend operator fun invoke(chatId: String) = chamas.refresh(chatId) }
class SetGoalUseCase(private val chamas: ChamaRepository) { suspend operator fun invoke(chatId: String, name: String, targetMinor: String, deadline: String? = null, treasurerUserId: String? = null) = chamas.setGoal(chatId, name, targetMinor, deadline, treasurerUserId) }
class LoadLedgerUseCase(private val chamas: ChamaRepository) { suspend operator fun invoke(chatId: String, before: String? = null) = chamas.ledger(chatId, before) }
class CreateChamaRequestUseCase(private val chamas: ChamaRepository) {
    suspend operator fun invoke(chatId: String, payeeUserId: String?, payeeAddress: String?, payeeName: String?, amountMinor: String, note: String?, idempotencyKey: String) =
        chamas.createRequest(chatId, payeeUserId, payeeAddress, payeeName, amountMinor, note, idempotencyKey)
}
class DecideChamaRequestUseCase(private val chamas: ChamaRepository) { suspend operator fun invoke(requestId: String, approve: Boolean) = chamas.decide(requestId, approve) }
class AttachChamaPaymentUseCase(private val chamas: ChamaRepository) { suspend operator fun invoke(chatId: String, requestId: String, transferId: String) = chamas.attachPayment(chatId, requestId, transferId) }
