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

/** What a group thread shows of its chama (designs D2, D5, D6). */
data class ThreadChamaState(
    val chama: Chama? = null,
    val fiat: FiatValue? = null,
    /** D6, the first time this phone opens a group that has a goal. */
    val showIntro: Boolean = false,
    val myUserId: String? = null,
)

/** The thread's window onto the chama: the goal strip, the open requests, and the first-open intro. */
class ThreadChama(
    private val observeChama: ObserveChamaUseCase,
    private val refreshChama: RefreshChamaUseCase,
    private val decideRequest: DecideChamaRequestUseCase,
    private val observeWallet: ObserveWalletUseCase,
    private val prefs: AppPreferences,
    private val auth: AuthManager,
) {
    fun observe(chatId: String): Flow<ThreadChamaState> =
        combine(observeChama(chatId), observeWallet(), prefs.flag(introFlag(chatId))) { chama, wallet, seen ->
            ThreadChamaState(chama, wallet?.fiat, showIntro = chama != null && !seen, myUserId = auth.userId)
        }

    suspend fun refresh(chatId: String) = refreshChama(chatId)

    suspend fun decide(request: ChamaRequest, approve: Boolean): AppResult<ChamaRequest> = decideRequest(request.requestId, approve)

    suspend fun introSeen(chatId: String) = prefs.setFlag(introFlag(chatId), true)

    private fun introFlag(chatId: String) = "chama.intro.$chatId"
}
