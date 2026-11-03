package com.circaso.feature.chama

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.chama.data.local.ChamaDao
import com.circaso.feature.chama.data.local.ChamaSnapshotEntity
import com.circaso.feature.chama.data.remote.ApprovalDto
import com.circaso.feature.chama.data.remote.ChamaRemote
import com.circaso.feature.chama.data.remote.ChamaResponse
import com.circaso.feature.chama.data.remote.ContributorDto
import com.circaso.feature.chama.data.remote.CreateRequestRequest
import com.circaso.feature.chama.data.remote.GoalDto
import com.circaso.feature.chama.data.remote.LedgerEntryDto
import com.circaso.feature.chama.data.remote.LedgerResponse
import com.circaso.feature.chama.data.remote.RequestDto
import com.circaso.feature.chama.data.remote.SetGoalRequest
import com.circaso.feature.chama.data.repository.ChamaRepositoryImpl
import com.circaso.feature.chama.domain.Approval
import com.circaso.feature.chama.domain.Chama
import com.circaso.feature.chama.domain.ChamaGoal
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.domain.LedgerDirection
import com.circaso.feature.chama.domain.RequestState
import com.circaso.feature.chama.domain.PendingLinks
import com.circaso.feature.chama.presentation.ChamaUiState
import com.circaso.feature.chama.presentation.GoalDeadline
import com.circaso.feature.chama.presentation.PayoutDraft
import com.circaso.feature.chama.presentation.amountNumber
import com.circaso.feature.chama.presentation.deadlineIso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.circaso.feature.chama.presentation.amountText
import com.circaso.feature.chama.presentation.cleanAmount
import com.circaso.feature.wallet.data.remote.AmountDto
import com.circaso.feature.wallet.domain.FiatValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun usdc(minor: String) = AmountDto("USDC", minor, minor)

private fun goal(version: Long = 1) = GoalDto("g1", "Holiday Fund", usdc("386996905"), "2026-12-14T00:00:00Z", "otieno", "otieno", "2026-10-29T00:00:00Z", version)

private fun request(state: String = "PENDING", approvals: List<ApprovalDto> = emptyList(), transferId: String? = null) = RequestDto(
    "r1", "g1", "otieno", null, "0xcoast", "Coast Bus", usdc("92879000"), "bus deposit", state, 2, approvals, transferId,
    "2026-10-31T08:00:00Z", "2026-10-29T08:00:00Z", "2026-10-29T08:00:00Z",
)

private fun response(contributed: String = "250774000", open: List<RequestDto> = emptyList(), version: Long = 1) = ChamaResponse(
    goal(version), 3, usdc(contributed), usdc("0"), usdc(contributed), usdc("30960000"),
    listOf(ContributorDto("wanjiru", usdc("7740000")), ContributorDto("me", usdc("30960000"))), open, isAdmin = true, isTreasurer = false,
)

private class FakeRemote : ChamaRemote {
    var chama: AppResult<ChamaResponse> = AppResult.Success(response())
    var reads = 0
    val requests = mutableListOf<Pair<CreateRequestRequest, String>>()
    var goals = mutableListOf<SetGoalRequest>()
    override suspend fun chama(chatId: String): AppResult<ChamaResponse> { reads++; return chama }
    override suspend fun setGoal(chatId: String, request: SetGoalRequest): AppResult<GoalDto> { goals += request; return AppResult.Success(goal(2)) }
    override suspend fun ledger(chatId: String, before: String?, limit: Int): AppResult<LedgerResponse> = AppResult.Success(
        LedgerResponse(
            listOf(
                LedgerEntryDto("IN", "2026-10-28T17:02:00Z", usdc("7740000"), "wanjiru", "t1"),
                LedgerEntryDto("OUT", "2026-10-20T09:00:00Z", usdc("92879000"), null, "t0", request("PAID", transferId = "t0")),
            ),
        ),
    )
    override suspend fun createRequest(chatId: String, request: CreateRequestRequest, idempotencyKey: String): AppResult<RequestDto> { requests += request to idempotencyKey; return AppResult.Success(request()) }
    override suspend fun decide(requestId: String, approve: Boolean): AppResult<RequestDto> =
        AppResult.Success(request(if (approve) "APPROVED" else "DECLINED", listOf(ApprovalDto("otieno", "APPROVE", "2026-10-29T08:00:00Z"), ApprovalDto("me", if (approve) "APPROVE" else "DECLINE", "2026-10-29T09:00:00Z"))))
    var attach: AppResult<RequestDto>? = null
    val attached = mutableListOf<String>()
    override suspend fun attachPayment(requestId: String, transferId: String): AppResult<RequestDto> { attached += transferId; return attach ?: AppResult.Success(request("APPROVED", transferId = transferId)) }
    // Phase 2 (CH7–CH9).
    var money: AppResult<com.circaso.feature.chama.data.remote.GroupMoneyResponse> = AppResult.Success(groupMoney())
    var moneyReads = 0
    val policies = mutableListOf<Pair<String, String>>()
    var removed = 0
    override suspend fun money(chatId: String): AppResult<com.circaso.feature.chama.data.remote.GroupMoneyResponse> { moneyReads++; return money }
    override suspend fun removeGoal(chatId: String): AppResult<com.circaso.core.network.NoBody> { removed++; return AppResult.Success(com.circaso.core.network.NoBody()) }
    override suspend fun setSettings(chatId: String, request: com.circaso.feature.chama.data.remote.GroupSettingsRequest, idempotencyKey: String): AppResult<com.circaso.feature.chama.data.remote.GroupSettingsDto> {
        policies += request.approvalPolicy to idempotencyKey
        return AppResult.Success(com.circaso.feature.chama.data.remote.GroupSettingsDto(chatId, request.approvalPolicy))
    }
}

private fun groupMoney(wallet: Boolean = true, goal: Boolean = true, policy: String = "TWO_ADMINS") = com.circaso.feature.chama.data.remote.GroupMoneyResponse(
    chatId = "g1", wallet = if (wallet) com.circaso.feature.chama.data.remote.GroupWalletDto("gw-1", "ACTIVE", "me") else null, approvalPolicy = policy,
    goal = if (goal) goal(1).copy(treasurerUserId = "me") else null, memberCount = 3, contributed = usdc("100000000"), myContribution = usdc("0"),
    contributors = emptyList(), paidOut = usdc("0"), openRequests = listOf(request("APPROVED")), isAdmin = true, isOwner = true, isSigner = true,
)

private class FakeLinks : PendingLinks {
    val map = MutableStateFlow<Map<String, Pair<String, String>>>(emptyMap())
    override val pendingLinks: Flow<Map<String, Pair<String, String>>> = map
    override suspend fun putLink(requestId: String, chatId: String, transferId: String) { map.value = map.value + (requestId to (chatId to transferId)) }
    override suspend fun removeLink(requestId: String) { map.value = map.value - requestId }
}

private class FakeDao : ChamaDao {
    val rows = MutableStateFlow<Map<String, ChamaSnapshotEntity>>(emptyMap())
    override fun observe(chatId: String): Flow<ChamaSnapshotEntity?> = rows.map { it[chatId] }
    override suspend fun save(row: ChamaSnapshotEntity) { rows.value = rows.value + (row.chatId to row) }
    override suspend fun delete(chatId: String) { rows.value = rows.value - chatId }
    override suspend fun clearAll() { rows.value = emptyMap() }
    val money = MutableStateFlow<Map<String, com.circaso.feature.chama.data.local.GroupMoneyEntity>>(emptyMap())
    override fun observeMoney(chatId: String) = money.map { it[chatId] }
    override fun observeAllMoney() = money.map { it.values.toList() }
    override suspend fun saveMoney(row: com.circaso.feature.chama.data.local.GroupMoneyEntity) { money.value = money.value + (row.chatId to row) }
    override suspend fun clearMoney() { money.value = emptyMap() }
}

class ChamaTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val remote = FakeRemote()
    private val dao = FakeDao()
    private val links = FakeLinks()
    private val repo = ChamaRepositoryImpl(remote, dao, json, links, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun refresh_keeps_the_server_view_on_the_phone() = runTest {
        assertNull(repo.chama("g1").first())
        val chama = assertIs<AppResult.Success<Chama?>>(repo.refresh("g1")).value
        assertNotNull(chama)
        assertEquals("Holiday Fund", chama.goal.name)
        assertEquals(0.648f, chama.progress, 0.001f)
        assertEquals(chama, repo.chama("g1").first()?.copy(fetchedAt = chama.fetchedAt))
    }

    @Test
    fun no_goal_clears_the_copy_and_is_not_an_error() = runTest {
        repo.refresh("g1")
        remote.chama = AppResult.Failure(AppError.Api("chama.no_goal", 404, null, null, emptyMap()))
        val r = assertIs<AppResult.Success<Chama?>>(repo.refresh("g1"))
        assertNull(r.value)
        assertNull(repo.chama("g1").first())
    }

    @Test
    fun other_failures_keep_the_copy() = runTest {
        repo.refresh("g1")
        remote.chama = AppResult.Failure(AppError.Network)
        assertIs<AppResult.Failure>(repo.refresh("g1"))
        assertNotNull(repo.chama("g1").first())
    }

    @Test
    fun a_frame_refreshes_the_group_it_names() = runTest {
        repo.refresh("g1")
        remote.chama = AppResult.Success(response(open = listOf(request())))
        repo.onFrame("request.updated", buildJsonObject { put("chatId", "g1"); put("requestId", "r1") })
        val open = repo.chama("g1").first()?.openRequests.orEmpty()
        assertEquals(listOf("r1"), open.map { it.requestId })
        assertEquals(RequestState.Pending, open.single().state)
        assertEquals("Coast Bus", open.single().payeeName)
        repo.onFrame("goal.updated", null) // no chat id: nothing to do, nothing thrown
        assertEquals(2, remote.reads)
    }

    @Test
    fun writes_go_to_the_server_and_refresh_the_copy() = runTest {
        val r = assertIs<AppResult.Success<ChamaRequest>>(repo.createRequest("g1", null, "0xcoast", "Coast Bus", "92879000", "bus deposit", "key-1"))
        assertEquals("92879000", r.value.amountMinor)
        assertEquals("key-1", remote.requests.single().second)
        assertEquals("0xcoast", remote.requests.single().first.to.address)
        val decided = assertIs<AppResult.Success<ChamaRequest>>(repo.decide("r1", true)).value
        assertEquals(RequestState.Approved, decided.state)
        assertEquals(listOf("otieno", "me"), decided.approvedBy)
        val goal = assertIs<AppResult.Success<ChamaGoal>>(repo.setGoal("g1", "Holiday Fund", "386996905", null, null)).value
        assertEquals(2, goal.version)
        assertEquals("386996905", remote.goals.single().target.amountMinor)
        assertTrue(remote.reads >= 3)
    }

    @Test
    fun ledger_maps_both_directions() = runTest {
        val entries = assertIs<AppResult.Success<List<com.circaso.feature.chama.domain.LedgerEntry>>>(repo.ledger("g1", null)).value
        assertEquals(listOf(LedgerDirection.In, LedgerDirection.Out), entries.map { it.direction })
        assertEquals("Coast Bus", entries[1].request?.payeeName)
        assertEquals(RequestState.Paid, entries[1].request?.state)
    }

    @Test
    fun amounts_read_in_the_persons_currency_when_a_rate_is_known() {
        val fiat = FiatValue("KES", "0", "129.2", "2026-10-29T00:00:00Z", false)
        assertEquals("KES 32,400", amountText("250774000", fiat))
        assertEquals("50,000", amountNumber("386996905", fiat))
        assertEquals(com.circaso.feature.wallet.domain.MinorUnits.toDisplay("250774000", 6) + " USDC", amountText("250774000", null))
    }

    @Test
    fun converted_amounts_drop_cents_once_they_dont_matter() {
        assertEquals("32350", com.circaso.feature.chama.presentation.whole("32349.84"))
        assertEquals("32349", com.circaso.feature.chama.presentation.whole("32349.20"))
        assertEquals("99.50", com.circaso.feature.chama.presentation.whole("99.50"))
    }

    @Test
    fun typed_amounts_keep_one_point_and_the_units_decimals() {
        assertEquals("12000", cleanAmount("12,000", 2))
        assertEquals("12.50", cleanAmount("12.505", 2))
        assertEquals("1.5", cleanAmount("1.5.3", 2))
        assertEquals("0.123456", cleanAmount("0.1234567", 6))
    }

    @Test
    fun a_request_knows_who_approved_and_whether_it_is_open() {
        val r = ChamaRequest("r", "g", "a", null, null, null, "1", null, RequestState.Pending, 2, listOf(Approval("a", true, ""), Approval("b", false, "")), null, "", "", "")
        assertEquals(listOf("a"), r.approvedBy)
        assertTrue(r.open)
        assertTrue(!r.copy(state = RequestState.Declined).open)
    }

    @Test
    fun a_payment_that_cant_be_linked_stays_on_the_phone_and_shows_as_paying() = runTest {
        remote.chama = AppResult.Success(response(open = listOf(request("APPROVED"))))
        repo.refresh("g1")
        remote.attach = AppResult.Failure(AppError.Network)
        assertIs<AppResult.Failure>(repo.attachPayment("g1", "r1", "t9"))
        assertEquals(mapOf("r1" to ("g1" to "t9")), links.map.value)
        // The request now carries the transfer on the phone, so the treasurer isn't offered "Pay from the Group" again.
        assertEquals("t9", repo.chama("g1").first()?.openRequests?.single()?.transferId)

        remote.attach = null
        repo.retryPendingLinks()
        assertTrue(links.map.value.isEmpty())
        assertEquals(listOf("t9", "t9"), remote.attached)
    }

    @Test
    fun a_link_that_already_landed_is_forgotten() = runTest {
        remote.attach = AppResult.Failure(AppError.Api("chama.request_closed", 409, null, null, emptyMap()))
        repo.attachPayment("g1", "r1", "t9")
        assertTrue(links.map.value.isEmpty())
    }

    @Test
    fun deadlines_land_on_the_same_day_or_the_months_last() {
        val jan31 = kotlin.time.Instant.parse("2027-01-31T10:00:00Z").toEpochMilliseconds()
        assertEquals("2027-02-28T00:00:00Z", deadlineIso(GoalDeadline.OneMonth, jan31))
        assertEquals("2028-01-31T00:00:00Z", deadlineIso(GoalDeadline.OneYear, jan31))
        val oct29 = kotlin.time.Instant.parse("2026-10-29T23:59:00Z").toEpochMilliseconds()
        assertEquals("2027-01-29T00:00:00Z", deadlineIso(GoalDeadline.ThreeMonths, oct29))
        assertNull(deadlineIso(GoalDeadline.None, oct29))
    }

    @Test
    fun a_payout_needs_a_payee_and_an_amount() {
        val base = ChamaUiState(online = true, draft = PayoutDraft(amountText = "12", inFiat = false))
        assertTrue(!base.canAsk)
        assertTrue(base.copy(draft = base.draft!!.copy(toUserId = "otieno")).canAsk)
        val outside = base.copy(draft = base.draft!!.copy(someoneElse = true, toUserId = "otieno"))
        assertTrue(!outside.canAsk) // someone else needs their name and address, a member chip left over doesn't count
        assertTrue(outside.copy(draft = outside.draft!!.copy(toName = "Coast Bus", address = "0xcoast")).canAsk)
        assertTrue(!outside.copy(online = false, draft = outside.draft!!.copy(toName = "Coast Bus", address = "0xcoast")).canAsk)
        assertEquals("12000000", base.draftAmountMinor)
    }
}

/** Phase 2: chamas and groups are one Group; its money (CH7–CH9) lives in the group's own wallet. */
class GroupMoneyTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val remote = FakeRemote()
    private val dao = FakeDao()
    private val repo = ChamaRepositoryImpl(remote, dao, json, FakeLinks(), CoroutineScope(Dispatchers.Unconfined), groupWallets = { true })

    @Test
    fun the_groups_money_is_kept_on_the_phone_and_reads_as_a_chama_for_the_strip() = runTest {
        val money = assertIs<AppResult.Success<com.circaso.feature.chama.domain.GroupMoney>>(repo.refreshMoney("g1")).value
        assertTrue(money.walletActive)
        assertTrue(money.isSigner)
        assertEquals(money.copy(fetchedAt = 0), repo.money("g1").first()?.copy(fetchedAt = 0))
        val chama = money.asChama()!!
        assertEquals("me", chama.goal.treasurerUserId, "the signer stands where the treasurer stood")
        assertTrue(chama.isTreasurer)
    }

    @Test
    fun a_group_without_a_wallet_has_no_money_actions() = runTest {
        remote.money = AppResult.Success(groupMoney(wallet = false, goal = false))
        val money = assertIs<AppResult.Success<com.circaso.feature.chama.domain.GroupMoney>>(repo.refreshMoney("g1")).value
        assertTrue(!money.hasWallet)
        assertNull(money.asChama())
    }

    @Test
    fun frames_reload_the_groups_money_not_the_treasurer_goal() = runTest {
        repo.onFrame("group.updated", buildJsonObject { put("chatId", "g1") })
        repo.onFrame("request.updated", buildJsonObject { put("chatId", "g1") })
        assertEquals(2, remote.moneyReads)
        assertEquals(0, remote.reads, "CH1 isn't read with group wallets on")
    }

    @Test
    fun who_approves_is_sent_with_its_key_and_the_target_can_go() = runTest {
        val r = repo.setApprovalPolicy("g1", com.circaso.feature.chama.domain.ApprovalPolicy.AnyAdmin, "key-9")
        assertEquals(com.circaso.feature.chama.domain.ApprovalPolicy.AnyAdmin, assertIs<AppResult.Success<*>>(r).value)
        assertEquals(listOf("ANY_ADMIN" to "key-9"), remote.policies)
        assertIs<AppResult.Success<Unit>>(repo.removeGoal("g1"))
        assertEquals(1, remote.removed)
    }

    @Test
    fun a_target_still_waiting_for_its_wallet_decodes() = runTest {
        // Migrated treasurer-model goals carry no treasurer (spaces-plan §11.4).
        remote.money = AppResult.Success(groupMoney(wallet = false).let { it.copy(goal = it.goal?.copy(treasurerUserId = null)) })
        val money = assertIs<AppResult.Success<com.circaso.feature.chama.domain.GroupMoney>>(repo.refreshMoney("g1")).value
        assertNull(money.goal?.treasurerUserId)
    }
}
