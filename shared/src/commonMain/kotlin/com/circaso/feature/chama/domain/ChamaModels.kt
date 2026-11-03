package com.circaso.feature.chama.domain

import com.circaso.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * A chama (designs D2–D6, guide CH1–CH6): a group chat's shared goal. The money sits in the treasurer's own wallet;
 * contributing is paying the treasurer from the chat, and paying out needs the admins' approval then the treasurer's PIN.
 */
/** The goal, called a **target** since phase 2. [treasurerUserId] is the group wallet's signer (null before it has a wallet). */
data class ChamaGoal(val chatId: String, val name: String, val targetMinor: String, val deadline: String?, val treasurerUserId: String?, val version: Long)

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

/** Who approves payouts from the group wallet (CH9, GW1b): two admins (every admin when fewer), or any one admin. */
enum class ApprovalPolicy { TwoAdmins, AnyAdmin }

/** The group's wallet as the group-money route sees it (CH7 `wallet`). */
data class GroupWalletRef(val walletId: String, val active: Boolean, val signerUserId: String)

/**
 * A group's money (CH7, phase 2: chamas and groups are one thing). [wallet] null: the group has no wallet, so it shows
 * no money actions anywhere. [goal] is the optional target. Contributions count towards the target since it was set.
 */
data class GroupMoney(
    val chatId: String,
    val wallet: GroupWalletRef?,
    val approvalPolicy: ApprovalPolicy,
    val goal: ChamaGoal?,
    val memberCount: Int,
    val contributedMinor: String,
    val myContributedMinor: String,
    val contributors: List<Contributor>,
    val paidOutMinor: String,
    val openRequests: List<ChamaRequest>,
    val isAdmin: Boolean,
    val isOwner: Boolean,
    val isSigner: Boolean,
    val fetchedAt: Long,
) {
    val hasWallet get() = wallet != null
    val walletActive get() = wallet?.active == true
    val settingUp get() = wallet != null && !wallet.active

    /** 0..1 of the target reached; 0 without one. */
    val progress: Float
        get() {
            val target = goal?.targetMinor?.toDoubleOrNull() ?: return 0f
            val got = contributedMinor.toDoubleOrNull() ?: 0.0
            return if (target <= 0) 0f else (got / target).coerceIn(0.0, 1.0).toFloat()
        }

    /**
     * The thread's goal strip, request cards and intro still read a [Chama]: this is that view of a group whose wallet
     * holds the money (the signer stands where the treasurer stood). Null without a target.
     */
    fun asChama(): Chama? = goal?.let { g ->
        Chama(
            goal = g.copy(treasurerUserId = wallet?.signerUserId ?: g.treasurerUserId), memberCount = memberCount, contributedMinor = contributedMinor,
            paidOutMinor = paidOutMinor, heldMinor = "0", myContributedMinor = myContributedMinor, contributors = contributors,
            openRequests = openRequests, isAdmin = isAdmin, isTreasurer = isSigner, fetchedAt = fetchedAt,
        )
    }
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

    // ---- Phase 2: a group's money, target or not (CH7–CH9).

    /** Read: the last CH7 for the group, null before the first. */
    fun money(chatId: String): Flow<GroupMoney?>

    /** CH7. */
    suspend fun refreshMoney(chatId: String): AppResult<GroupMoney>

    /** Online-only: CH9 (the group's owner). Announced to every member. */
    suspend fun setApprovalPolicy(chatId: String, policy: ApprovalPolicy, idempotencyKey: String): AppResult<ApprovalPolicy>

    /** Online-only: CH8 (admins). The wallet, its money and open requests stay. */
    suspend fun removeGoal(chatId: String): AppResult<Unit>
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
/** Read. */
class ObserveGroupMoneyUseCase(private val chamas: ChamaRepository) { operator fun invoke(chatId: String) = chamas.money(chatId) }
class RefreshGroupMoneyUseCase(private val chamas: ChamaRepository) { suspend operator fun invoke(chatId: String) = chamas.refreshMoney(chatId) }
/** Online-only. */
class SetApprovalPolicyUseCase(private val chamas: ChamaRepository) { suspend operator fun invoke(chatId: String, policy: ApprovalPolicy, idempotencyKey: String) = chamas.setApprovalPolicy(chatId, policy, idempotencyKey) }
/** Online-only. */
class RemoveGoalUseCase(private val chamas: ChamaRepository) { suspend operator fun invoke(chatId: String) = chamas.removeGoal(chatId) }
class AttachChamaPaymentUseCase(private val chamas: ChamaRepository) { suspend operator fun invoke(chatId: String, requestId: String, transferId: String) = chamas.attachPayment(chatId, requestId, transferId) }
