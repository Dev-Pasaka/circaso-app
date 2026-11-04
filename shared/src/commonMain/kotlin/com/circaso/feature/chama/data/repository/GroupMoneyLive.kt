package com.circaso.feature.chama.data.repository

import com.circaso.core.common.AppResult
import com.circaso.feature.chama.domain.ChamaRepository
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.WalletRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Keeps a group's money live on every screen that shows it (group info, the thread's goal strip, the group wallet):
 * they all read Room, so this only reloads the copies when money moves.
 *
 * - **Someone else's** contribution or payout: the server sends every member `chama` / `money.updated` (and
 *   `contribution.added` with a target, `request.updated` when a payout is paid). [ChamaRepositoryImpl] reloads the
 *   group's money (CH7); this reloads the group wallet's balance and history (W1 and W6 `?walletId=`) behind it.
 * - **My own** payment into or out of a group: the moment this phone learns it is confirmed (my `transfer.updated`, or
 *   a W5 read) it reloads the group at once rather than wait for the group's frame, and once more a moment later,
 *   because the server records the contribution just after the chain confirms it.
 */
class GroupMoneyLive(
    private val wallet: WalletRepository,
    private val chama: ChamaRepository,
    private val scope: CoroutineScope,
    private val groupWallets: () -> Boolean,
    private val againAfterMillis: Long = AgainAfterMillis,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { wallet.settled.collect { onSettled(it) } }
    }

    suspend fun onSettled(transfer: Transfer) {
        if (!groupWallets()) return
        val chatId = transfer.toGroupId ?: transfer.fromGroupId ?: return
        reload(chatId, transfer.walletId)
        scope.launch { delay(againAfterMillis); reload(chatId, transfer.walletId) }
    }

    /** After [ChamaRepository.onFrame]: the group's money is fresh; bring its wallet's balance and history along. */
    suspend fun onChamaFrame(type: String, data: JsonObject?) {
        if (!groupWallets()) return
        val chatId = data?.get("chatId")?.jsonPrimitive?.contentOrNull ?: return
        val moved = when (type) {
            "money.updated", "contribution.added" -> true
            "request.updated" -> data["state"]?.jsonPrimitive?.contentOrNull == "PAID"
            else -> false
        }
        if (!moved) return
        val walletId = chama.money(chatId).first()?.wallet?.walletId ?: return
        wallet.refreshWallet(walletId)
        wallet.refreshActivity(walletId)
    }

    private suspend fun reload(chatId: String, walletId: String?) {
        val money = (chama.refreshMoney(chatId) as? AppResult.Success)?.value
        val id = walletId ?: money?.wallet?.walletId ?: return
        wallet.refreshWallet(id)
        wallet.refreshActivity(id)
    }

    private companion object {
        const val AgainAfterMillis = 2_500L
    }
}
