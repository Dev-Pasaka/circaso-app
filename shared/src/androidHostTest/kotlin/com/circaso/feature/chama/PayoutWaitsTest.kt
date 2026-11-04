package com.circaso.feature.chama

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.chama.domain.ApprovalPolicy
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.domain.GroupMoney
import com.circaso.feature.chama.domain.GroupWalletRef
import com.circaso.feature.chama.domain.RequestState
import com.circaso.feature.chama.presentation.payoutWaitsUntil
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.AssetTotal
import com.circaso.feature.wallet.domain.ObserveWalletOfUseCase
import com.circaso.feature.wallet.domain.PaymentQuote
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.QuotePaymentUseCase
import com.circaso.feature.wallet.domain.QuoteProblem
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletRepository
import com.circaso.feature.wallet.domain.WalletStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GW14c: an approved payout from a group wallet says when it fits the daily limit, asked of W17 as a member. */
class PayoutWaitsTest {
    private val asked = mutableListOf<Pair<String, PaymentRequest>>()
    private var answer: AppResult<PaymentQuote?> = AppResult.Success(null)
    private var groupWallet: Wallet? = Wallet(
        WalletStatus.Active,
        listOf(WalletChain("BASE-SEPOLIA", "0xgroup", "ACTIVE", listOf(Asset("USDC", "0xusdc", 6, "20000000000", "20000", "0")), true)),
        listOf(AssetTotal("USDC", 6, "20000000000", "20000")), null, null, 0,
    )

    private val repo = Proxy.newProxyInstance(WalletRepository::class.java.classLoader, arrayOf(WalletRepository::class.java)) { _, method, args ->
        val list = args.orEmpty().toList().filterNot { it is kotlin.coroutines.Continuation<*> }
        when (method.name) {
            "walletOf" -> flowOf(groupWallet)
            "quote" -> { asked += "quote" to (list[0] as PaymentRequest); answer }
            "hashCode" -> 0
            "toString" -> "fake"
            else -> null
        }
    } as WalletRepository

    private fun money(active: Boolean = true) = GroupMoney(
        "g1", GroupWalletRef("gw-1", active, "me"), ApprovalPolicy.TwoAdmins, null, 8, "0", "0", emptyList(), "0", emptyList(),
        isAdmin = false, isOwner = false, isSigner = false, fetchedAt = 0,
    )

    private val request = ChamaRequest("r1", "g1", "2", null, "0xbus", "Coast Bus", "1160990000", "trip", RequestState.Approved, 2, emptyList(), null, "", "", "")

    private fun quote(problem: QuoteProblem?, fitsAt: String?) =
        PaymentQuote("11609900", 100, false, "0", null, "0", problem, fitsAt, "0", "10000000000", null, null, null)

    private suspend fun ask(m: GroupMoney = money()) = payoutWaitsUntil(QuotePaymentUseCase(repo), ObserveWalletOfUseCase(repo), m, request)

    @Test fun anApprovedPayoutOverTheCapSaysWhenItFits() = runTest {
        answer = AppResult.Success(quote(QuoteProblem.DailyLimit, "2026-11-05T14:30:00Z"))

        assertEquals("2026-11-05T14:30:00Z", ask())
        val (_, r) = asked.single()
        assertEquals("gw-1", r.fromWalletId, "from the group wallet, as a member")
        assertEquals("0xbus", r.toAddress)
        assertEquals("1160990000", r.amountMinor)
        assertEquals("BASE-SEPOLIA", r.blockchain, "on the group wallet's own network")
    }

    @Test fun aPayoutThatFitsOrCantBeAskedWaitsForNothing() = runTest {
        answer = AppResult.Success(quote(null, null))
        assertNull(ask(), "fits now")
        answer = AppResult.Success(quote(QuoteProblem.InsufficientFunds, null))
        assertNull(ask(), "not the limit's doing")
        answer = AppResult.Failure(AppError.Network)
        assertNull(ask(), "offline: no guess")
        answer = AppResult.Success(null)
        assertNull(ask(), "a server without fees")
    }

    @Test fun aWalletNotSetUpOrNotOnThisPhoneIsNotAsked() = runTest {
        assertNull(ask(money(active = false)))
        groupWallet = null
        assertNull(ask())
        assertTrue(asked.isEmpty())
    }
}
