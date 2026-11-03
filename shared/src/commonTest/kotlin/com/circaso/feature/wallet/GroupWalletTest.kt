package com.circaso.feature.wallet

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.flags.GroupWalletsFlag
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.wallet.data.remote.CounterpartyDto
import com.circaso.feature.wallet.data.remote.TransactionDto
import com.circaso.feature.wallet.data.repository.WalletRepositoryImpl
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.SdkSession
import com.circaso.feature.wallet.domain.SignOutcome
import com.circaso.feature.wallet.domain.SignPurpose
import com.circaso.feature.wallet.domain.SignSubject
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.WalletSigner
import com.circaso.feature.wallet.domain.WalletSignerBridge
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.data.remote.AmountDto
import com.circaso.feature.wallet.presentation.NamedWallet
import com.circaso.feature.wallet.presentation.nameWallets
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The flag that keeps group-wallet fields off a server that would ignore them (and pay from the personal wallet). */
class GroupWalletsFlagTest {
    @Test fun debugDefaultIsOnButNothingIsSentUntilTheServerAnswers() {
        val flag = GroupWalletsFlag(defaultOn = true)
        assertTrue(flag.isOn)
        assertFalse(flag.canSendWalletId, "walletId never goes to a server that hasn't shown it has group wallets")
        flag.onServerAnswered()
        assertTrue(flag.canSendWalletId)
    }

    @Test fun aServerWithoutGroupWalletsTurnsTheFlagOff() {
        val flag = GroupWalletsFlag(defaultOn = true)
        flag.onServerMissing()
        assertFalse(flag.isOn)
        assertFalse(flag.enabled.value)
        assertFalse(flag.canSendWalletId)
        flag.onServerAnswered()
        assertFalse(flag.canSendWalletId, "once missing, a later answer can't turn it back on in the same run")
    }

    @Test fun releaseDefaultIsOff() {
        val flag = GroupWalletsFlag(defaultOn = false)
        flag.onServerAnswered()
        assertFalse(flag.isOn)
        assertFalse(flag.canSendWalletId)
    }
}

class GroupWalletRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val dao = FakeWalletDao()
    private val remote = FakeWalletRemote()
    private val signer = FakeSigner(onExecute = { remote.groupStatus = "ACTIVE" })
    private val repo = WalletRepositoryImpl(remote, dao, { signer }, { "KES" }, json)

    @Test fun settingUpAGroupWalletSignsWithItsOwnSessionAndNamesIt() = runTest {
        val started = assertIs<AppResult.Success<com.circaso.feature.wallet.domain.GroupWallet>>(repo.startGroupWallet("g1", "Kilimani Chama")).value
        assertEquals("gw-1", started.wallet?.walletId)
        assertEquals(listOf("ch-group"), signer.challenges)
        val session = signer.sessions.single()
        assertEquals("group-token", session.userToken, "the group wallet's own Circle user, not the personal one")
        assertEquals(SignSubject.group("gw-1", "Kilimani Chama", SignPurpose.SetPin), session.subject)
        val active = assertIs<AppResult.Success<com.circaso.feature.wallet.domain.GroupWallet>>(repo.awaitGroupWallet("g1")).value
        assertEquals(WalletStatus.Active, active.status)
        assertTrue("gw-1" in remote.walletReads, "the group wallet's balance is read once it's active")
    }

    @Test fun aCancelledGroupPinLeavesTheWalletWaitingAndTheNextTryGetsAFreshChallenge() = runTest {
        signer.outcome = SignOutcome.Cancelled
        val r = assertIs<AppResult.Failure>(repo.startGroupWallet("g1", "Kilimani Chama"))
        assertEquals("wallet.cancelled", (r.error as AppError.Api).code)
        signer.outcome = SignOutcome.Done
        assertIs<AppResult.Success<*>>(repo.startGroupWallet("g1", "Kilimani Chama"))
        assertEquals(2, remote.setups)
    }

    @Test fun anAlreadyActiveWalletNeedsNoPin() = runTest {
        remote.groupStatus = "ACTIVE"
        val r = assertIs<AppResult.Success<com.circaso.feature.wallet.domain.GroupWallet>>(repo.startGroupWallet("g1", "Kilimani Chama"))
        assertEquals(WalletStatus.Active, r.value.status)
        assertTrue(signer.challenges.isEmpty())
    }

    @Test fun aPayoutLeavesTheGroupWalletAndStaysOutOfThePersonalList() = runTest {
        remote.status = "ACTIVE"; repo.refresh()
        remote.transferState = "SUBMITTED"
        val subject = SignSubject.group("gw-1", "Kilimani Chama")
        repo.pay(PaymentRequest("BASE-SEPOLIA", "u-2", null, "5000000", chatId = "g1", fromWalletId = "gw-1", payoutRequestId = "r1", subject = subject), "key-1")
        val (body, key) = remote.prepared.single()
        assertEquals("gw-1", body.walletId)
        assertEquals("r1", body.payoutRequestId)
        assertEquals("key-1", key)
        assertEquals(subject, signer.sessions.single().subject, "Circle's screen names the wallet it approves from")
        assertTrue(repo.transfers.first().none { it.transferId == "t-1" }, "a payout isn't the person's own money")
        assertEquals("t-1", repo.activity("gw-1").first().single().transferId)
    }

    @Test fun aContributionPaysTheGroupFromThePersonalWallet() = runTest {
        remote.status = "ACTIVE"; repo.refresh()
        repo.pay(PaymentRequest("BASE-SEPOLIA", null, null, "1000000", chatId = "g1", toGroupId = "g1", note = "Added to Kilimani Chama"), "key-2")
        val body = remote.prepared.single().first
        assertEquals("g1", body.to.groupId)
        assertNull(body.to.userId)
        assertNull(body.walletId, "a contribution leaves the person's own wallet")
        assertEquals("t-1", repo.transfers.first().single().transferId)
    }

    @Test fun biometricsAndNetworksRunPerWallet() = runTest {
        repo.enableBiometricApproval(SignSubject.group("gw-1", "Kilimani Chama"))
        assertEquals(listOf<String?>("gw-1"), remote.sessions)
        assertEquals("gw-1", signer.sessions.single().subject.walletId)
        assertEquals(SignPurpose.EnableBiometrics, signer.sessions.single().subject.purpose)
        repo.addMissingChains(SignSubject.group("gw-1", "Kilimani Chama"))
        assertEquals(listOf<String?>("gw-1"), remote.missingFor)
    }

    @Test fun onlyTheWalletCircleNamesLosesItsBiometrics() = runTest {
        val lost = mutableListOf<String?>()
        val scoped = WalletRepositoryImpl(remote, dao, { signer }, { "KES" }, json, scope = backgroundScope, onBiometricsLost = { lost += it })
        yield()
        WalletSignerBridge.reportBiometricsLost("gw-1")
        WalletSignerBridge.reportBiometricsLost()
        yield(); yield()
        assertEquals(listOf("gw-1", null), lost)
        scoped.hashCode()
    }

    @Test fun challengesNeverOverlapAcrossWallets() = runTest {
        var running = 0
        var most = 0
        val slow = object : WalletSigner {
            override val available = true
            override suspend fun execute(session: SdkSession, challengeId: String): SignOutcome { running++; most = maxOf(most, running); delay(100); running--; return SignOutcome.Done }
            override suspend fun enableBiometrics(session: SdkSession): SignOutcome = SignOutcome.Done
        }
        val gated = WalletRepositoryImpl(remote, dao, { slow }, { "KES" }, json)
        remote.status = "ACTIVE"; gated.refresh()
        val a = async { gated.pay(PaymentRequest("BASE-SEPOLIA", "u-2", null, "1000000"), "a") }
        val b = async { gated.pay(PaymentRequest("BASE-SEPOLIA", "u-3", null, "1000000", fromWalletId = "gw-1", payoutRequestId = "r1"), "b") }
        a.await(); b.await()
        assertEquals(1, most, "one wallet's PIN screen never opens over another's")
    }

    @Test fun theWalletListAndAGroupWalletsHistoryAreKeptOnThePhone() = runTest {
        val listed = assertIs<AppResult.Success<List<com.circaso.feature.wallet.domain.WalletListing>>>(repo.refreshWallets()).value
        assertEquals(3, listed.size)
        assertTrue(listed.first().personal)
        assertEquals(listed, repo.wallets.first())
        remote.history["gw-1"] = listOf(
            TransactionDto("row-1", "t-9", "IN", "CONFIRMED", "BASE-SEPOLIA", AmountDto("USDC", "1000000", "1"), CounterpartyDto(userId = "wanjiru", displayName = "Wanjiru"), "g1", occurredAt = "2026-11-03T10:00:00Z"),
        )
        repo.refreshActivity("gw-1")
        val row = repo.activity("gw-1").first().single()
        assertEquals(TransferDirection.In, row.direction)
        assertEquals("Wanjiru", row.counterpartName)
        assertTrue(repo.transfers.first().none { it.transferId == "t-9" }, "the group's history isn't the person's")
        repo.refreshWallet("gw-1")
        assertEquals("50000000", repo.walletOf("gw-1").first()?.usdc?.amountMinor)
        assertTrue(repo.walletOf("gw-1").first()!!.isGroup)
    }

    @Test fun aGroupWalletFrameRefreshesThatWalletOnly() = runTest {
        repo.onFrame("payment.received", buildJsonObject { put("transactionId", "tx-g"); put("walletId", "gw-1") })
        assertTrue(repo.transfers.first().none { it.transferId == "tx-g" })
        assertTrue("gw-1" in remote.walletReads)
        repo.onFrame("wallet.ready", buildJsonObject { put("walletId", "gw-1") })
        assertEquals(3, repo.wallets.first().size, "a group wallet turning active reloads the list")
    }

    @Test fun walletsAreNamedFromTheirGroupsOnThePhone() {
        val chat = Chat("g1", ChatKind.Group, "Kilimani Chama", null, emptyList(), false, 1, 1, MemberRole.Owner, null)
        val named = nameWallets(
            listOf(
                com.circaso.feature.wallet.domain.WalletListing("me", com.circaso.feature.wallet.domain.WalletOwner(com.circaso.feature.wallet.domain.WalletOwnerType.Person, "me", null, "me"), WalletStatus.Active, true, emptyList()),
                com.circaso.feature.wallet.domain.WalletListing("gw-1", com.circaso.feature.wallet.domain.WalletOwner(com.circaso.feature.wallet.domain.WalletOwnerType.Group, null, "g1", "me"), WalletStatus.Active, true, emptyList()),
                com.circaso.feature.wallet.domain.WalletListing("gw-9", com.circaso.feature.wallet.domain.WalletOwner(com.circaso.feature.wallet.domain.WalletOwnerType.Group, null, "g9", "x"), WalletStatus.Active, false, emptyList()),
            ),
            listOf(ChatSummary(chat, null, 0)),
        )
        assertEquals(NamedWallet.PersonalName, named[0].name)
        assertNull(named[0].walletId, "the personal wallet is called without walletId, as before")
        assertEquals("Kilimani Chama", named[1].name)
        assertEquals(SignSubject.group("gw-1", "Kilimani Chama"), named[1].subject)
        assertEquals("Group wallet", named[2].name, "a group this phone doesn't know yet")
    }
}
