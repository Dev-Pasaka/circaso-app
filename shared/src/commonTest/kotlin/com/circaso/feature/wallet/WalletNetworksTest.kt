package com.circaso.feature.wallet

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.NetworkEvents
import com.circaso.core.network.NetworkHealth
import com.circaso.core.network.WalletNetworkGate
import com.circaso.core.network.WalletNetworkHeader
import com.circaso.core.network.circasoJson
import com.circaso.feature.chama.data.remote.KtorChamaRemote
import com.circaso.feature.vault.data.KtorVaultRemote
import com.circaso.feature.wallet.data.remote.KtorWalletRemote
import com.circaso.feature.wallet.data.remote.NetworkDto
import com.circaso.feature.wallet.data.remote.NetworksResponse
import com.circaso.feature.wallet.data.remote.PrepareTransferRequest
import com.circaso.feature.wallet.data.remote.WalletRemote
import com.circaso.feature.wallet.data.repository.SavedNetworks
import com.circaso.feature.wallet.data.repository.WalletNetworksStore
import com.circaso.feature.wallet.data.repository.WalletRepositoryImpl
import com.circaso.feature.wallet.domain.NetworkChangedCode
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.PinnedNetwork
import com.circaso.feature.wallet.domain.SignOutcome
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetworks
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.domain.isGasProblem
import com.circaso.feature.wallet.domain.isSolanaChain
import com.circaso.feature.wallet.domain.isTestnetChain
import com.circaso.feature.wallet.domain.pinnedNetwork
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val Both = NetworksResponse(listOf(NetworkDto("MAINNET", "NONE"), NetworkDto("TESTNET", "ACTIVE")), default = "MAINNET")
private val MainnetOnly = NetworksResponse(listOf(NetworkDto("MAINNET", "ACTIVE")), default = "MAINNET")
private val NotFound = AppResult.Failure(AppError.Api("request.not_found", 404, null, null, emptyMap()))

/** The two small settings the store keeps, in memory; a sign-out is `saved.value = SavedNetworks()`. */
private class Networks(scope: CoroutineScope, var answer: AppResult<NetworksResponse> = AppResult.Success(Both)) {
    val saved = MutableStateFlow(SavedNetworks())
    var asks = 0
    private var clock = 0L
    fun store(scope: CoroutineScope) = WalletNetworksStore(
        load = { asks++; answer }, saved = saved, save = { saved.value = it }, scope = scope,
        // Every look at the clock is ten seconds on, so no answer is ever "still fresh".
        now = { clock += 10_000; clock },
    )
    val store = store(scope)
}

/** Which networks the person may use, which is selected, and what goes in `X-Wallet-Network`. */
class WalletNetworksStoreTest {
    @Test fun beforeTheServerAnswersNothingIsClaimedAndNoHeaderIsSent() = runTest {
        val n = Networks(backgroundScope, AppResult.Failure(AppError.Network))
        assertNull(n.store.header(), "the server picks, as it does for an older app")
        val s = n.store.state.value
        assertFalse(s.known); assertFalse(s.switchable)
        assertFalse(s.testnet, "a placeholder never shows the word testnet to someone who may only ever have Mainnet")
    }

    @Test fun aServerWithoutNetworksIsOneNetworkWithNoSwitchAndNoHeader() = runTest {
        val n = Networks(backgroundScope, NotFound)
        assertEquals(WalletNetwork.TESTNET, n.store.effective())
        val s = n.store.state.value
        assertTrue(s.known); assertTrue(s.legacy)
        assertFalse(s.switchable, "no switch against today's server")
        assertEquals(listOf(WalletNetwork.TESTNET), s.options.map { it.network })
        assertNull(n.store.header(), "today's server is sent no header")
        assertFalse(n.store.select(WalletNetwork.MAINNET), "there is no Mainnet to pick")
        // It is asked again later, so the switch arrives when the server gains networks.
        n.answer = AppResult.Success(Both)
        n.store.refresh()
        assertTrue(n.store.state.value.switchable)
        assertEquals(WalletNetwork.MAINNET, n.store.state.value.selected, "with no choice made, the server's default leads")
    }

    @Test fun twoNetworksShowTheSwitchAndStartOnTheServersDefault() = runTest {
        val n = Networks(backgroundScope)
        assertEquals("MAINNET", n.store.header())
        val s = n.store.state.value
        assertTrue(s.switchable)
        assertEquals(WalletNetwork.MAINNET, s.selected)
        assertEquals(WalletStatus.None, s.statusOf(WalletNetwork.MAINNET)); assertEquals(WalletStatus.Active, s.statusOf(WalletNetwork.TESTNET))
        assertFalse(s.testnet)
    }

    @Test fun someoneWithOnlyMainnetHasNoSwitchAndNeverTestnet() = runTest {
        val n = Networks(backgroundScope, AppResult.Success(MainnetOnly))
        assertEquals("MAINNET", n.store.header())
        assertFalse(n.store.state.value.switchable); assertFalse(n.store.state.value.testnet)
        assertFalse(n.store.select(WalletNetwork.TESTNET))
        assertEquals(WalletNetwork.MAINNET, n.store.current())
    }

    @Test fun theChoiceIsSentKeptAndSurvivesARestart() = runTest {
        val n = Networks(backgroundScope)
        n.store.refresh()
        assertTrue(n.store.select(WalletNetwork.TESTNET))
        assertEquals(WalletNetwork.TESTNET, n.store.current(), "at once, before any request")
        assertEquals("TESTNET", n.store.header())
        assertTrue(n.store.state.value.testnet)
        // A new run reads the same two settings, with no request needed to know the choice or show the switch.
        n.answer = AppResult.Failure(AppError.Network)
        val again = n.store(backgroundScope)
        assertEquals(WalletNetwork.TESTNET, again.effective())
        assertTrue(again.state.value.switchable)
        assertEquals("TESTNET", again.header())
    }

    @Test fun aNetworkTakenAwayGivesWayToTheDefaultAndSaysSoOnce() = runTest {
        val n = Networks(backgroundScope)
        n.store.refresh(); n.store.select(WalletNetwork.TESTNET)
        n.answer = AppResult.Success(MainnetOnly)
        n.store.refresh()
        val s = n.store.state.value
        assertEquals(WalletNetwork.MAINNET, s.selected)
        assertFalse(s.switchable)
        assertEquals(WalletNetwork.TESTNET, s.notice?.lost); assertEquals(WalletNetwork.MAINNET, s.notice?.now)
        n.store.noticeShown()
        assertNull(n.store.state.value.notice)
    }

    @Test fun aRefusedNetworkIsLeftForTheDefault() = runTest {
        val n = Networks(backgroundScope)
        n.store.refresh(); n.store.select(WalletNetwork.TESTNET)
        // `wallet.network_unavailable` on a call that carried TESTNET, while the list still names it.
        n.store.onRefused("TESTNET")
        assertEquals(WalletNetwork.MAINNET, n.store.current())
        assertEquals(WalletNetwork.TESTNET, n.store.state.value.notice?.lost)
    }

    @Test fun signingOutForgetsTheChoiceAndTheList() = runTest {
        val n = Networks(backgroundScope)
        n.store.refresh(); n.store.select(WalletNetwork.TESTNET)
        n.answer = AppResult.Failure(AppError.Network)
        testScheduler.runCurrent() // the settings' own flow has delivered what was saved
        n.saved.value = SavedNetworks() // AppPreferences.clear() wipes text.wallet.*
        testScheduler.runCurrent() // the store follows the settings
        n.store.effective()
        assertFalse(n.store.state.value.known); assertFalse(n.store.state.value.switchable)
        assertNull(n.store.header())
    }

    @Test fun aPinnedCallKeepsItsNetworkWhateverIsSelected() = runTest {
        val n = Networks(backgroundScope)
        n.store.refresh()
        assertEquals(WalletNetwork.MAINNET, n.store.current())
        withContext(PinnedNetwork(WalletNetwork.TESTNET)) {
            assertEquals(WalletNetwork.TESTNET, n.store.effective())
            assertEquals("TESTNET", n.store.header())
        }
        assertEquals("MAINNET", n.store.header())
    }

    @Test fun chainsSayTheirNetwork() {
        listOf("BASE-SEPOLIA", "ETH-SEPOLIA", "MATIC-AMOY", "SOL-DEVNET", "AVAX-FUJI", "ARC-TESTNET", "base-sepolia").forEach { assertTrue(isTestnetChain(it), it) }
        listOf("BASE", "MATIC", "ETH", "SOL", "AVAX", "ARB").forEach { assertFalse(isTestnetChain(it), it) }
        assertEquals(WalletNetwork.TESTNET, WalletNetwork.ofChain("AVAX-FUJI"))
        assertEquals(WalletNetwork.MAINNET, WalletNetwork.ofChain("SOL"))
        assertNull(WalletNetwork.ofChainOrNull(""))
        assertEquals(WalletNetwork.MAINNET, WalletNetwork.of("mainnet")); assertNull(WalletNetwork.of("DEVNET"))
        assertTrue(isSolanaChain("SOL")); assertTrue(isSolanaChain("SOL-DEVNET")); assertFalse(isSolanaChain("BASE"))
        assertTrue(isGasProblem("wallet.insufficient_gas")); assertFalse(isGasProblem("wallet.insufficient_funds"))
    }

    @Test fun biometricSettingsAreKeptPerNetworkAndTestnetKeepsItsOldNames() {
        assertEquals("biometric_approval", AppPreferences.biometricFlag(null))
        assertEquals("biometric_approval", AppPreferences.biometricFlag(null, "TESTNET"))
        assertEquals("biometric_approval.gw-1", AppPreferences.biometricFlag("gw-1", "TESTNET"))
        assertEquals("biometric_approval.mainnet", AppPreferences.biometricFlag(null, "MAINNET"))
        assertEquals("biometric_approval.mainnet.gw-1", AppPreferences.biometricFlag("gw-1", "MAINNET"))
        assertEquals("biometric_lost.personal", AppPreferences.biometricLostFlag(null))
        assertEquals("biometric_lost.mainnet.gw-1", AppPreferences.biometricLostFlag("gw-1", "MAINNET"))
    }
}

/** `X-Wallet-Network` on the wire: every wallet-scoped route carries it; the list of networks itself doesn't. */
class WalletNetworkHeaderTest {
    private val json = circasoJson()
    private val seen = mutableListOf<Pair<String, String?>>()
    private var status = 200
    private var body = """{"httpStatusCode":200,"status":true,"message":"Success","code":null,"details":null,"requestId":"r","data":{}}"""
    private val api = CircasoApi(
        HttpClient(MockEngine { request ->
            seen += request.url.encodedPath to request.headers[WalletNetworkHeader]
            respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }) { install(ContentNegotiation) { json(json) } },
        json, NetworkHealth(), NetworkEvents(),
    )
    private var sends: String? = "MAINNET"
    private val refusals = mutableListOf<String?>()
    private val gate = object : WalletNetworkGate {
        override suspend fun header(): String? = sends
        override suspend fun refused(sent: String?) { refusals += sent }
    }
    private val flag = com.circaso.core.flags.GroupWalletsFlag.off()

    @Test fun everyWalletVaultAndChamaRouteCarriesTheNetwork() = runTest {
        val wallet = KtorWalletRemote(api, flag, gate)
        wallet.wallet("KES"); wallet.chains(); wallet.transfer("t-1"); wallet.transactions(); wallet.create("k"); wallet.recipient("u", null, null)
        val vault = KtorVaultRemote(api, gate)
        vault.status(); vault.open()
        val chama = KtorChamaRemote(api, gate)
        chama.chama("g1"); chama.money("g1"); chama.ledger("g1", null, 10)
        assertEquals(11, seen.size)
        assertTrue(seen.all { it.second == "MAINNET" }, seen.filter { it.second != "MAINNET" }.toString())
        assertTrue(seen.map { it.first }.containsAll(listOf("/v1/wallet", "/v1/wallet/transfers/t-1", "/v1/vault/status", "/v1/vault", "/v1/chama/chats/g1/money")))
    }

    @Test fun theListOfNetworksCarriesNone() = runTest {
        KtorWalletRemote(api, flag, gate).networks()
        assertEquals(listOf<Pair<String, String?>>("/v1/wallet/networks" to null), seen)
    }

    @Test fun aServerWithoutNetworksIsSentNoHeader() = runTest {
        sends = null
        KtorWalletRemote(api, flag, gate).wallet("KES"); KtorVaultRemote(api, gate).status(); KtorChamaRemote(api, gate).money("g1")
        assertTrue(seen.all { it.second == null })
    }

    @Test fun aRefusedNetworkIsReported() = runTest {
        sends = "TESTNET"
        status = 403
        body = """{"httpStatusCode":403,"status":false,"message":"That network isn't available.","code":"wallet.network_unavailable","details":null,"requestId":"r","data":null}"""
        val r = KtorWalletRemote(api, flag, gate).wallet("KES")
        assertEquals("wallet.network_unavailable", ((r as AppResult.Failure).error as AppError.Api).code)
        assertEquals(listOf<String?>("TESTNET"), refusals)
    }

    @Test fun networksParseAndOlderAnswersStillDo() {
        val listed = json.decodeFromString(NetworksResponse.serializer(), """{"networks":[{"network":"MAINNET","walletStatus":"NONE"},{"network":"TESTNET","walletStatus":"ACTIVE"}],"default":"MAINNET"}""")
        assertEquals(listOf("MAINNET", "TESTNET"), listed.networks.map { it.network }); assertEquals("MAINNET", listed.default)
        val wallet = com.circaso.feature.wallet.data.remote.WalletResponse.serializer()
        assertEquals("MAINNET", json.decodeFromString(wallet, """{"status":"ACTIVE","network":"MAINNET"}""").network)
        assertNull(json.decodeFromString(wallet, """{"status":"ACTIVE"}""").network, "a server before networks sends none")
        val transfer = com.circaso.feature.wallet.data.remote.TransferResponse.serializer()
        val old = """{"transferId":"t","direction":"OUT","state":"CONFIRMED","blockchain":"BASE-SEPOLIA","from":"0x","to":{"address":"0x"},"amount":{"asset":"USDC","amountMinor":"1","amount":"0.000001"}}"""
        assertNull(json.decodeFromString(transfer, old).network)
    }
}

/** One side of Circle per network, picked by the network the call is pinned to (what the real remote puts in the header). */
private class TwoSidedRemote(val mainnet: FakeWalletRemote, val testnet: FakeWalletRemote, var listed: NetworksResponse? = Both) : WalletRemote {
    val asked = mutableListOf<WalletNetwork?>()
    /** Runs while W4 is answering, so a test can move the selection between the prepare and the PIN. */
    var duringPrepare: suspend () -> Unit = {}
    private suspend fun side(): FakeWalletRemote = pinnedNetwork().also { asked += it }.let { if (it == WalletNetwork.MAINNET) mainnet else testnet }
    override suspend fun networks(): AppResult<NetworksResponse> = listed?.let { AppResult.Success(it) } ?: NotFound
    override suspend fun wallet(currency: String?, walletId: String?) = side().wallet(currency, walletId)
    override suspend fun create(idempotencyKey: String) = side().create(idempotencyKey)
    override suspend fun session(walletId: String?) = side().session(walletId)
    override suspend fun chains(walletId: String?) = side().chains(walletId)
    override suspend fun addChain(blockchain: String, idempotencyKey: String) = side().addChain(blockchain, idempotencyKey)
    override suspend fun addMissingChains(idempotencyKey: String, walletId: String?) = side().addMissingChains(idempotencyKey, walletId)
    override suspend fun prepare(request: PrepareTransferRequest, idempotencyKey: String) = side().prepare(request, idempotencyKey).also { duringPrepare() }
    override suspend fun transfer(transferId: String) = side().transfer(transferId)
    override suspend fun transactions(cursor: String?, limit: Int, walletId: String?) = side().transactions(cursor, limit, walletId)
    override suspend fun recipient(userId: String, blockchain: String?, chatId: String?) = side().recipient(userId, blockchain, chatId)
    override suspend fun remind(userId: String, idempotencyKey: String) = side().remind(userId, idempotencyKey)
    override suspend fun validateAddress(request: com.circaso.feature.wallet.data.remote.ValidateAddressRequest) = side().validateAddress(request)
    override suspend fun wallets() = side().wallets()
    override suspend fun groupWallet(chatId: String) = side().groupWallet(chatId)
    override suspend fun setUpGroupWallet(chatId: String, idempotencyKey: String) = side().setUpGroupWallet(chatId, idempotencyKey)
}

/** The phone's copies per network: switching is instant and nothing of one network lands in the other's. */
class WalletNetworksRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val dao = FakeWalletDao()
    private val mainnet = FakeWalletRemote()
    private val testnet = FakeWalletRemote().apply { status = "ACTIVE" }
    private val remote = TwoSidedRemote(mainnet, testnet)
    private val signer = FakeSigner(onExecute = { mainnet.status = "ACTIVE" })

    private class Fixture(val networks: WalletNetworks, val repo: WalletRepositoryImpl)

    private fun TestScope.fixture(): Fixture {
        val saved = MutableStateFlow(SavedNetworks())
        var clock = 0L
        val networks = WalletNetworksStore({ remote.networks() }, saved, { saved.value = it }, backgroundScope, now = { clock += 10_000; clock })
        return Fixture(networks, WalletRepositoryImpl(remote, dao, { signer }, { "KES" }, json, networks = networks))
    }

    @Test fun eachNetworkKeepsItsOwnCopyAndSwitchingShowsItAtOnce() = runTest {
        val f = fixture()
        // Mainnet first (the server's default): no wallet there yet.
        assertEquals(WalletStatus.None, (f.repo.refresh() as AppResult.Success).value.status)
        assertEquals(WalletStatus.None, f.repo.wallet.first()?.status)
        f.networks.select(WalletNetwork.TESTNET)
        assertNull(f.repo.wallet.first(), "nothing of Mainnet shows under Testnet")
        assertEquals(WalletStatus.Active, (f.repo.refresh() as AppResult.Success).value.status)
        assertEquals("20000000", f.repo.wallet.first()?.usdc?.amountMinor)
        // Back to Mainnet: its copy is there at once, with no request.
        val before = remote.asked.size
        f.networks.select(WalletNetwork.MAINNET)
        assertEquals(WalletStatus.None, f.repo.wallet.first()?.status)
        assertEquals(before, remote.asked.size)
        assertEquals(setOf("MAINNET", "TESTNET"), dao.snapshots.value.keys)
    }

    @Test fun aNewNetworksWalletIsCreatedOnThatNetworkWithItsOwnKey() = runTest {
        val f = fixture()
        f.networks.refresh()
        assertEquals(WalletStatus.Active, (f.repo.create() as AppResult.Success).value, "Mainnet's wallet, with Mainnet's PIN challenge")
        assertEquals(1, mainnet.creates); assertEquals(0, testnet.creates)
        assertEquals(WalletStatus.Active, f.repo.wallet.first()?.status)
        f.networks.select(WalletNetwork.TESTNET)
        assertNull(f.repo.wallet.first(), "Testnet's copy is its own")
    }

    @Test fun aFrameForTheOtherNetworkUpdatesItsCopyAndLeavesTheVisibleOneAlone() = runTest {
        val f = fixture()
        f.repo.refresh() // Mainnet on screen
        val visible = dao.snapshots.value.getValue("MAINNET")
        f.repo.onFrame("payment.received", buildJsonObject {
            put("transactionId", "tx-9"); put("blockchain", "BASE-SEPOLIA"); put("network", "TESTNET"); put("fromName", "Amina")
            putJsonObject("amount") { put("asset", "USDC"); put("amountMinor", "5000000"); put("amount", "5") }
        })
        assertEquals(visible, dao.snapshots.value.getValue("MAINNET"), "the visible network's copy is untouched")
        assertEquals(WalletNetwork.TESTNET, remote.asked.last(), "the refresh went to the frame's network")
        assertTrue(f.repo.transfers.first().isEmpty(), "Testnet's money isn't in Mainnet's list")
        f.networks.select(WalletNetwork.TESTNET)
        assertEquals("tx-9", f.repo.transfers.first().single().transferId)
        assertEquals("20000000", f.repo.wallet.first()?.usdc?.amountMinor, "and its balance was read while Mainnet was on screen")
    }

    @Test fun anOlderFrameWithoutANetworkIsFiledByItsChain() = runTest {
        val f = fixture()
        f.repo.refresh()
        f.repo.onFrame("payment.received", buildJsonObject {
            put("transactionId", "tx-7"); put("blockchain", "BASE-SEPOLIA")
            putJsonObject("amount") { put("asset", "USDC"); put("amountMinor", "1000000"); put("amount", "1") }
        })
        assertEquals("TESTNET", dao.transfers.value.getValue("tx-7").network)
    }

    @Test fun aPaymentIsNeverSentForANetworkThePersonHasLeft() = runTest {
        val f = fixture()
        f.networks.refresh(); f.networks.select(WalletNetwork.TESTNET)
        // Reviewed on Testnet, then the selection moved to Mainnet before Pay.
        f.networks.select(WalletNetwork.MAINNET)
        val r = f.repo.pay(PaymentRequest("BASE-SEPOLIA", "u-2", null, "5000000", network = WalletNetwork.TESTNET), "key-1")
        assertEquals(NetworkChangedCode, ((r as AppResult.Failure).error as AppError.Api).code)
        assertTrue(testnet.prepared.isEmpty() && mainnet.prepared.isEmpty(), "nothing reached the server")
        assertTrue(signer.challenges.isEmpty())
    }

    @Test fun aPaymentPreparedOnOneNetworkIsNeverSignedAfterASwitch() = runTest {
        val f = fixture()
        f.networks.refresh(); f.networks.select(WalletNetwork.TESTNET)
        remote.duringPrepare = { f.networks.select(WalletNetwork.MAINNET) }
        val r = f.repo.pay(PaymentRequest("BASE-SEPOLIA", "u-2", null, "5000000", network = WalletNetwork.TESTNET), "key-1")
        assertEquals(NetworkChangedCode, ((r as AppResult.Failure).error as AppError.Api).code)
        assertEquals(1, testnet.prepared.size, "W4 went to the network it was reviewed on")
        assertTrue(signer.challenges.isEmpty(), "its PIN screen never opened")
        assertEquals(TransferState.Expired, f.repo.transfer("t-1").first()?.state)
    }

    @Test fun aPaymentOnTheSelectedNetworkGoesThroughOnIt() = runTest {
        val f = fixture()
        f.networks.refresh(); f.networks.select(WalletNetwork.TESTNET)
        signer.outcome = SignOutcome.Done
        val r = f.repo.pay(PaymentRequest("BASE-SEPOLIA", "u-2", null, "5000000", network = WalletNetwork.TESTNET), "key-1")
        assertTrue(r is AppResult.Success)
        assertEquals(listOf("ch-pay"), signer.challenges)
        assertEquals(1, testnet.prepared.size); assertTrue(mainnet.prepared.isEmpty())
        assertEquals("TESTNET", dao.transfers.value.getValue("t-1").network)
    }

    @Test fun againstAServerWithoutNetworksEverythingIsOneNetworkAsBefore() = runTest {
        remote.listed = null
        val f = fixture()
        assertEquals(WalletStatus.Active, (f.repo.refresh() as AppResult.Success).value.status)
        assertEquals(setOf("TESTNET"), dao.snapshots.value.keys, "the copies from before networks, where they always were")
        assertFalse(f.networks.state.value.switchable)
        assertNull(f.networks.header())
        assertTrue(f.repo.pay(PaymentRequest("BASE-SEPOLIA", "u-2", null, "5000000"), "key-1") is AppResult.Success)
    }
}
