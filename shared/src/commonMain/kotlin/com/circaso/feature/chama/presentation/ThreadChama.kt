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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.circaso.feature.wallet.domain.on
import kotlinx.coroutines.flow.firstOrNull

/** What a group thread shows of its chama (designs D2, D5, D6). */
data class ThreadChamaState(
    val chama: Chama? = null,
    val fiat: FiatValue? = null,
    /** D6, the first time this phone opens a group that has a goal. */
    val showIntro: Boolean = false,
    val myUserId: String? = null,
    /** Group wallets (phase 2): the group's money, target or not; null in the treasurer model or before the first read. */
    val groupMoney: com.circaso.feature.chama.domain.GroupMoney? = null,
    /** The group's money is test money (its own network is Testnet, whatever the Wallet tab shows). */
    val testnet: Boolean = false,
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
    /** W17, for an approved payout that waits for the group's daily limit (GW14c); absent in older tests. */
    private val quotePayment: com.circaso.feature.wallet.domain.QuotePaymentUseCase? = null,
    private val observeWalletOf: com.circaso.feature.wallet.domain.ObserveWalletOfUseCase? = null,
    /** Which network each group's money is on; null (tests) follows the selection as before. */
    private val groupNetworks: com.circaso.feature.wallet.domain.GroupNetworks? = null,
) {
    private suspend fun <T> pinned(chatId: String, block: suspend () -> T): T {
        val network = groupNetworks?.now(chatId) ?: return block()
        return kotlinx.coroutines.withContext(com.circaso.feature.wallet.domain.PinnedNetwork(network)) { block() }
    }

    /**
     * With group wallets the thread reads the group's money (CH7): the goal strip and request cards see it as a
     * chama whose money is in the group wallet (the signer stands where the treasurer stood). Otherwise CH1, as before.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(chatId: String): Flow<ThreadChamaState> {
        val places = groupNetworks ?: return observeFollowing(chatId)
        // The group's own network: its goal, its requests and the mark on its strip, whatever the Wallet tab shows.
        return places.state.map { s -> s.networkOf(chatId).let { it to s.testnet(it) } }.distinctUntilChanged().flatMapLatest { (network, testnet) ->
            observeFollowing(chatId).on(network).map { it.copy(testnet = testnet) }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeFollowing(chatId: String): Flow<ThreadChamaState> = groupWallets.enabled.flatMapLatest { on ->
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
        pinned(chatId) { if (groupWallets.isOn && money != null) money(chatId) else refreshChama(chatId) }
    }

    suspend fun decide(request: ChamaRequest, approve: Boolean): AppResult<ChamaRequest> = pinned(request.chatId) { decideRequest(request.requestId, approve) }

    /**
     * GW14c: when an approved payout [request] doesn't fit the group wallet's rolling 24-hour limit now, the time it
     * does (ISO), so every member sees why it hasn't been paid; null when it fits, can't be asked (offline, no wallet
     * yet) or the server takes no fees. Online-only and read-only: W17 as a member.
     */
    suspend fun payoutWaitsUntil(money: com.circaso.feature.chama.domain.GroupMoney, request: ChamaRequest): String? =
        pinned(money.chatId) { payoutWaitsUntil(quotePayment ?: return@pinned null, observeWalletOf ?: return@pinned null, money, request) }

    suspend fun introSeen(chatId: String) = prefs.setFlag(introFlag(chatId), true)

    private fun introFlag(chatId: String) = "chama.intro.$chatId"
}

/** [ThreadChama.payoutWaitsUntil] on its own ports, so it can be tested without the thread's other parts. */
internal suspend fun payoutWaitsUntil(
    quote: com.circaso.feature.wallet.domain.QuotePaymentUseCase,
    observeWalletOf: com.circaso.feature.wallet.domain.ObserveWalletOfUseCase,
    money: com.circaso.feature.chama.domain.GroupMoney,
    request: ChamaRequest,
): String? {
    val wallet = money.wallet?.takeIf { it.active } ?: return null
    // The group wallet's own network; without its copy on this phone there is nothing right to ask.
    val chain = observeWalletOf(wallet.walletId).firstOrNull()?.chainsReceiving?.firstOrNull()?.blockchain ?: return null
    val asked = com.circaso.feature.wallet.domain.PaymentRequest(
        blockchain = chain, toUserId = request.payeeUserId, toAddress = request.payeeAddress.takeIf { request.payeeUserId == null },
        amountMinor = request.amountMinor, chatId = money.chatId, fromWalletId = wallet.walletId,
    )
    val answer = (quote(asked) as? AppResult.Success)?.value ?: return null
    return answer.fitsAt.takeIf { answer.problem == com.circaso.feature.wallet.domain.QuoteProblem.DailyLimit }
}
