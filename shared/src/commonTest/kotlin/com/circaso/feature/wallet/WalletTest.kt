package com.circaso.feature.wallet

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.wallet.data.local.TransferEntity
import com.circaso.feature.wallet.data.local.WalletDao
import com.circaso.feature.wallet.data.local.WalletSnapshotEntity
import com.circaso.feature.wallet.data.remote.AddChainResponse
import com.circaso.feature.wallet.data.remote.AmountDto
import com.circaso.feature.wallet.data.remote.AssetDto
import com.circaso.feature.wallet.data.remote.AssetTotalDto
import com.circaso.feature.wallet.data.remote.ChainDto
import com.circaso.feature.wallet.data.remote.ChainsResponse
import com.circaso.feature.wallet.data.remote.CreateWalletResponse
import com.circaso.feature.wallet.data.remote.FiatDto
import com.circaso.feature.wallet.data.remote.PayeeDto
import com.circaso.feature.wallet.data.remote.PrepareTransferRequest
import com.circaso.feature.wallet.data.remote.PrepareTransferResponse
import com.circaso.feature.wallet.data.remote.RecipientResponse
import com.circaso.feature.wallet.data.remote.RemindResponse
import com.circaso.feature.wallet.data.remote.SdkDto
import com.circaso.feature.wallet.data.remote.TransferResponse
import com.circaso.feature.wallet.data.remote.ValidateAddressRequest
import com.circaso.feature.wallet.data.remote.ValidateAddressResponse
import com.circaso.feature.wallet.data.remote.WalletRemote
import com.circaso.feature.wallet.data.remote.WalletResponse
import com.circaso.feature.wallet.data.repository.WalletRepositoryImpl
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.SdkSession
import com.circaso.feature.wallet.domain.SignOutcome
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.WalletSigner
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.domain.chainName
import com.circaso.feature.wallet.domain.fiatToUsdcMinor
import com.circaso.feature.wallet.domain.formatFiat
import com.circaso.feature.wallet.domain.shortAddress
import com.circaso.feature.wallet.domain.usdcToFiat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyMathTest {
    @Test fun minorUnitsRoundTrip() {
        assertEquals("20500000", MinorUnits.fromDisplay("20.5", 6))
        assertEquals("20000000", MinorUnits.fromDisplay("20", 6))
        assertEquals("1", MinorUnits.fromDisplay("0.000001", 6))
        assertEquals("0", MinorUnits.fromDisplay(".", 6))
        assertNull(MinorUnits.fromDisplay("1.2345678", 6), "too many decimals")
        assertNull(MinorUnits.fromDisplay("1,2x", 6))
        assertEquals("20.50", MinorUnits.toDisplay("20500000", 6))
        assertEquals("1,234,567.891", MinorUnits.toDisplay("1234567891000", 6))
        assertEquals("0.00", MinorUnits.toDisplay("0", 6))
        assertEquals("0.000001", MinorUnits.toDisplay("1", 6))
        assertTrue(MinorUnits.lessThan("999", "1000")); assertTrue(!MinorUnits.lessThan("1000", "999"))
    }

    @Test fun fiatFormatsAsTheDesignWrites() {
        assertEquals("KES 32,180", formatFiat("KES", "32180.00"))
        assertEquals("KES 15.50", formatFiat("KES", "15.5"))
        assertEquals("KES 2,000", formatFiat("KES", "2000"))
    }

    @Test fun usdcAndFiatConvertWithoutFloats() {
        assertEquals("2580.00", usdcToFiat("20000000", "129.00"))
        assertEquals("1935.00", usdcToFiat("15000000", "129"))
        assertEquals("3875968", fiatToUsdcMinor("500", "129.00"), "KES 500 at 129 is 3.875968… USDC, rounded down")
        assertEquals("20000000", fiatToUsdcMinor("2580", "129"))
        assertNull(fiatToUsdcMinor("10", "0"))
    }

    @Test fun chainsAndAddressesRead() {
        assertEquals("Base (testnet)", chainName("BASE-SEPOLIA"))
        assertEquals("Base", chainName("BASE"))
        assertEquals("Polygon (testnet)", chainName("MATIC-AMOY"))
        assertEquals("Solana (testnet)", chainName("SOL-DEVNET"))
        assertEquals("0x7a3f…1e4b00", shortAddress("0x7a3f0000000000000000000000000000001e4b00"))
    }
}

private class FakeWalletDao : WalletDao {
    val snapshot = MutableStateFlow<WalletSnapshotEntity?>(null)
    val transfers = MutableStateFlow<Map<String, TransferEntity>>(emptyMap())
    override fun observeSnapshot(): Flow<WalletSnapshotEntity?> = snapshot
    override suspend fun snapshot() = snapshot.value
    override suspend fun saveSnapshot(row: WalletSnapshotEntity) { snapshot.value = row }
    override fun observeTransfers(): Flow<List<TransferEntity>> = transfers.map { it.values.sortedByDescending { t -> t.createdAt } }
    override fun observeTransfer(id: String): Flow<TransferEntity?> = transfers.map { it[id] }
    override suspend fun transfer(id: String) = transfers.value[id]
    override suspend fun upsert(row: TransferEntity) { transfers.value = transfers.value + (row.transferId to row) }
    override suspend fun clearSnapshot() { snapshot.value = null }
    override suspend fun clearTransfers() { transfers.value = emptyMap() }
}

private class FakeWalletRemote : WalletRemote {
    override suspend fun transactions(cursor: String?, limit: Int): AppResult<com.circaso.feature.wallet.data.remote.TransactionsResponse> =
        AppResult.Success(com.circaso.feature.wallet.data.remote.TransactionsResponse())
    var status = "NONE"
    var creates = 0
    var challenge: String? = "ch-1"
    val prepared = mutableListOf<Pair<PrepareTransferRequest, String>>()
    var transferState = "AWAITING_SIGNATURE"
    val sdk = SdkDto("app", "token", "key", "2026-10-29T12:00:00Z")
    override suspend fun wallet(currency: String?): AppResult<WalletResponse> = AppResult.Success(
        WalletResponse(
            status,
            chains = if (status == "ACTIVE") listOf(ChainDto("BASE-SEPOLIA", "0xabc", "ACTIVE", listOf(AssetDto("USDC", "0xusdc", 6, "20000000", "20", "2580.00")))) else emptyList(),
            totals = if (status == "ACTIVE") listOf(AssetTotalDto("USDC", 6, "20000000", "20")) else emptyList(),
            fiat = if (currency != null && status == "ACTIVE") FiatDto(currency, "2580.00", "129.00", "2026-10-29T10:00:00Z", false) else null,
        ),
    )
    override suspend fun create(idempotencyKey: String): AppResult<CreateWalletResponse> { creates++; status = "SETTING_UP"; return AppResult.Success(CreateWalletResponse("SETTING_UP", challenge, sdk)) }
    override suspend fun session() = AppResult.Success(sdk)
    override suspend fun chains() = AppResult.Success(ChainsResponse("BASE-SEPOLIA", emptyList()))
    override suspend fun addChain(blockchain: String, idempotencyKey: String) = AppResult.Success(AddChainResponse(blockchain, "ENABLED"))
    override suspend fun addMissingChains(idempotencyKey: String) = AppResult.Success(com.circaso.feature.wallet.data.remote.AddMissingChainsResponse())
    override suspend fun prepare(request: PrepareTransferRequest, idempotencyKey: String): AppResult<PrepareTransferResponse> {
        prepared += request to idempotencyKey
        return AppResult.Success(PrepareTransferResponse("t-1", "AWAITING_SIGNATURE", request.blockchain, AmountDto("USDC", request.amount.amountMinor, MinorUnits.toDisplay(request.amount.amountMinor, 6)), "ch-pay", sdk, AmountDto("USDC", "0", "0"), "2026-10-29T12:15:00Z"))
    }
    override suspend fun transfer(transferId: String): AppResult<TransferResponse> =
        if (transferId == "t-1" || transferId == "t-in") AppResult.Success(TransferResponse(transferId, if (transferId == "t-in") "IN" else "OUT", transferState, "BASE-SEPOLIA", "0xabc", PayeeDto("u-2", "0xdef"), AmountDto("USDC", "5000000", "5"), null, "chat-1", "APP", null, null))
        else AppResult.Failure(AppError.Api("wallet.transfer_not_found", 404, null, null, emptyMap()))
    override suspend fun recipient(userId: String, blockchain: String?, chatId: String?) = AppResult.Success(RecipientResponse(userId, "CAN_RECEIVE", true, listOf("BASE-SEPOLIA")))
    override suspend fun remind(userId: String, idempotencyKey: String) = AppResult.Success(RemindResponse("SENT"))
    override suspend fun validateAddress(request: ValidateAddressRequest) = AppResult.Success(ValidateAddressResponse(request.blockchain, request.address.lowercase(), request.address.startsWith("0x")))
}

private class FakeSigner(var outcome: SignOutcome = SignOutcome.Done, val onExecute: suspend () -> Unit = {}) : WalletSigner {
    val challenges = mutableListOf<String>()
    override val available = true
    override suspend fun execute(session: SdkSession, challengeId: String): SignOutcome { challenges += challengeId; onExecute(); return outcome }
    var biometricsEnabled = false
    override suspend fun enableBiometrics(session: SdkSession): SignOutcome = outcome.also { if (it == SignOutcome.Done) biometricsEnabled = true }
}

class WalletRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val dao = FakeWalletDao()
    private val remote = FakeWalletRemote()
    private val signer = FakeSigner(onExecute = { remote.status = "ACTIVE" })
    private val repo = WalletRepositoryImpl(remote, dao, { signer }, { "KES" }, json)

    @Test fun refreshSavesTheWalletWithTheLocalCurrency() = runTest {
        remote.status = "ACTIVE"
        val w = (repo.refresh() as AppResult.Success).value
        assertEquals(WalletStatus.Active, w.status)
        assertEquals("KES", w.fiat?.currency)
        assertEquals("20000000", w.usdc?.amountMinor)
        assertEquals("20000000", repo.wallet.first()?.chains?.single()?.usdc?.amountMinor, "the phone's copy")
    }

    @Test fun creatingRunsTheChallengeThenWaitsForActive() = runTest {
        val status = (repo.create() as AppResult.Success).value
        assertEquals(listOf("ch-1"), signer.challenges, "the SDK ran W2's challenge")
        assertEquals(WalletStatus.Active, status)
        assertEquals(1, remote.creates)
    }

    @Test fun aCancelledPinLeavesNothingSent() = runTest {
        remote.status = "ACTIVE"; repo.refresh()
        signer.outcome = SignOutcome.Cancelled
        val r = repo.pay(PaymentRequest("BASE-SEPOLIA", "u-2", null, "5000000", chatId = "chat-1", note = "lunch", counterpartName = "Mum"), "key-1")
        assertIs<AppResult.Failure>(r)
        assertEquals("wallet.cancelled", (r.error as AppError.Api).code)
        val row = repo.transfer("t-1").first()!!
        assertEquals(TransferState.Expired, row.state)
        assertEquals("cancelled", row.failureReason)
    }

    @Test fun payingKeepsTheNoteAndNameOnThePhoneOnly() = runTest {
        remote.status = "ACTIVE"; repo.refresh()
        remote.transferState = "SUBMITTED"
        val t = (repo.pay(PaymentRequest("BASE-SEPOLIA", "u-2", null, "5000000", chatId = "chat-1", note = "lunch", counterpartName = "Mum"), "key-1") as AppResult.Success).value
        assertEquals(listOf("ch-pay"), signer.challenges)
        assertEquals("key-1", remote.prepared.single().second, "the screen's key reaches the server")
        assertEquals(TransferState.Submitted, t.state)
        assertEquals("lunch", t.note); assertEquals("Mum", t.counterpartName)
        assertEquals(TransferDirection.Out, t.direction)
    }

    @Test fun framesUpdateTheCopyAndUnknownTransfersAreRead() = runTest {
        remote.status = "ACTIVE"; repo.refresh()
        repo.onFrame("transfer.updated", buildJsonObject { put("transferId", "t-1"); put("state", "CONFIRMED"); put("txHash", "0xhash") })
        val t = repo.transfer("t-1").first()!!
        assertEquals(TransferState.AwaitingSignature, t.state, "an unknown transfer is read from W5 as it is there")
        repo.onFrame("transfer.updated", buildJsonObject { put("transferId", "t-1"); put("state", "CONFIRMED"); put("txHash", "0xhash") })
        assertEquals(TransferState.Confirmed, repo.transfer("t-1").first()!!.state)
        assertEquals("0xhash", repo.transfer("t-1").first()!!.txHash)

        repo.onFrame("payment.received", buildJsonObject { put("transactionId", "tx-9"); put("blockchain", "BASE-SEPOLIA"); put("fromName", "Amina"); putJsonObject("amount") { put("asset", "USDC"); put("amountMinor", "2000000"); put("amount", "2") } })
        val received = repo.transfers.first().first { it.transferId == "tx-9" }
        assertEquals(TransferDirection.In, received.direction)
        assertEquals("Amina", received.counterpartName)
        assertEquals(TransferState.Confirmed, received.state)

        repo.onFrame("setup.requested", buildJsonObject { put("fromName", "Otieno") })
        assertEquals("Otieno", repo.setupRequestedBy.value)
    }
}
