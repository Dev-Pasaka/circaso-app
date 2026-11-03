package com.circaso.feature.chama.presentation

import com.circaso.core.common.AppResult
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.session.AuthManager
import com.circaso.feature.chama.domain.Chama
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.domain.DecideChamaRequestUseCase
import com.circaso.feature.chama.domain.ObserveChamaUseCase
import com.circaso.feature.chama.domain.RefreshChamaUseCase
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

/** What a group thread shows of its chama (designs D2, D5, D6). */
data class ThreadChamaState(
    val chama: Chama? = null,
    val fiat: FiatValue? = null,
    /** D6, the first time this phone opens a group that has a goal. */
    val showIntro: Boolean = false,
    val myUserId: String? = null,
    /** Group wallets (phase 2): the group's money, target or not; null in the treasurer model or before the first read. */
    val groupMoney: com.circaso.feature.chama.domain.GroupMoney? = null,
)

/** The thread's window onto the chama: the goal strip, the open requests, and the first-open intro. */
class ThreadChama(
    private val observeChama: ObserveChamaUseCase,
    private val refreshChama: RefreshChamaUseCase,
    private val decideRequest: DecideChamaRequestUseCase,
    private val observeWallet: ObserveWalletUseCase,
    private val prefs: AppPreferences,
    private val auth: AuthManager,
    private val groupWallets: com.circaso.core.flags.GroupWalletsFlag = com.circaso.core.flags.GroupWalletsFlag.off(),
    private val observeMoney: com.circaso.feature.chama.domain.ObserveGroupMoneyUseCase? = null,
    private val refreshMoney: com.circaso.feature.chama.domain.RefreshGroupMoneyUseCase? = null,
) {
    /**
     * With group wallets the thread reads the group's money (CH7): the goal strip and request cards see it as a
     * chama whose money is in the group wallet (the signer stands where the treasurer stood). Otherwise CH1, as before.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(chatId: String): Flow<ThreadChamaState> = groupWallets.enabled.flatMapLatest { on ->
        val money = observeMoney
        if (on && money != null) combine(money(chatId), observeWallet(), prefs.flag(introFlag(chatId))) { m, wallet, seen ->
            val chama = m?.asChama()
            ThreadChamaState(chama, wallet?.fiat, showIntro = chama != null && !seen, myUserId = auth.userId, groupMoney = m)
        }
        else combine(observeChama(chatId), observeWallet(), prefs.flag(introFlag(chatId))) { chama, wallet, seen ->
            ThreadChamaState(chama, wallet?.fiat, showIntro = chama != null && !seen, myUserId = auth.userId)
        }
    }

    suspend fun refresh(chatId: String) {
        val money = refreshMoney
        if (groupWallets.isOn && money != null) money(chatId) else refreshChama(chatId)
    }

    suspend fun decide(request: ChamaRequest, approve: Boolean): AppResult<ChamaRequest> = decideRequest(request.requestId, approve)

    suspend fun introSeen(chatId: String) = prefs.setFlag(introFlag(chatId), true)

    private fun introFlag(chatId: String) = "chama.intro.$chatId"
}
