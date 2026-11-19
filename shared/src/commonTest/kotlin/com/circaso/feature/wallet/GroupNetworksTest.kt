package com.circaso.feature.wallet

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.NetworkEvents
import com.circaso.core.network.NetworkHealth
import com.circaso.core.network.WalletNetworkGate
import com.circaso.core.network.WalletNetworkHeader
import com.circaso.core.network.circasoJson
import com.circaso.feature.wallet.data.remote.KtorWalletRemote
import com.circaso.feature.wallet.data.remote.NetworkDto
import com.circaso.feature.wallet.data.remote.NetworksResponse
import com.circaso.feature.wallet.data.remote.WalletListingDto
import com.circaso.feature.wallet.data.remote.WalletOwnerDto
import com.circaso.feature.wallet.data.repository.SavedNetworks
import com.circaso.feature.wallet.data.repository.WalletNetworksStore
import com.circaso.feature.wallet.data.repository.WalletRepositoryImpl
import com.circaso.feature.wallet.domain.GroupMainnet
import com.circaso.feature.wallet.domain.GroupMainnetOnlyCode
import com.circaso.feature.wallet.domain.GroupNetworks
import com.circaso.feature.wallet.domain.GroupNetworksState
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.PinnedNetwork
import com.circaso.feature.wallet.domain.WalletListing
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetwork.MAINNET
import com.circaso.feature.wallet.domain.WalletNetwork.TESTNET
import com.circaso.feature.wallet.domain.WalletNetworks
import com.circaso.feature.wallet.domain.WalletOwner
import com.circaso.feature.wallet.domain.WalletOwnerType
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.domain.groupNetwork
import com.circaso.feature.wallet.domain.newGroupWalletNetwork
import com.circaso.feature.wallet.domain.on
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val BothUsable = listOf(MAINNET, TESTNET)
private val Active = WalletStatus.Active
private val SettingUp = WalletStatus.SettingUp

/** The one rule for which network a group's money is on (Pasaka, 2026-11-19). */
class GroupNetworkRuleTest {
    @Test fun theSelectedNetworkWhenTheGroupHasAWalletThere() {
        val both = mapOf(MAINNET to Active, TESTNET to Active)
        assertEquals(MAINNET, groupNetwork(BothUsable, MAINNET, both))
        assertEquals(TESTNET, groupNetwork(BothUsable, TESTNET, both))
        assertEquals(TESTNET, groupNetwork(BothUsable, TESTNET, mapOf(TESTNET to Active)))
    }

    @Test fun otherwiseTheGroupsOnlyWalletsNetwork() {
        // An old Testnet group while the Wallet tab is on Mainnet, and a new Mainnet group while it is on Testnet.
        assertEquals(TESTNET, groupNetwork(BothUsable, MAINNET, mapOf(TESTNET to Active)))
        assertEquals(MAINNET, groupNetwork(BothUsable, TESTNET, mapOf(MAINNET to Active)))
    }

    @Test fun aWalletOnANetworkThePersonMayNotUseIsNotTheirs() {
        // Someone with only Mainnet in an old Testnet group: nothing of Testnet is ever asked for or shown.
        assertEquals(MAINNET, groupNetwork(listOf(MAINNET), MAINNET, mapOf(TESTNET to Active)))
        assertEquals(TESTNET, groupNetwork(listOf(TESTNET), TESTNET, mapOf(MAINNET to Active)))
    }

    @Test fun noWalletAnywhereIsWhereANewOneIsMade() {
        assertEquals(MAINNET, groupNetwork(BothUsable, TESTNET, emptyMap()), "Mainnet whenever the person may use it")
        assertEquals(MAINNET, groupNetwork(BothUsable, MAINNET, emptyMap()))
        assertEquals(MAINNET, groupNetwork(listOf(MAINNET), MAINNET, emptyMap()))
        assertEquals(TESTNET, groupNetwork(listOf(TESTNET), TESTNET, emptyMap()), "else Testnet")
        assertEquals(MAINNET, newGroupWalletNetwork(BothUsable, TESTNET))
        assertEquals(TESTNET, newGroupWalletNetwork(listOf(TESTNET), TESTNET))
    }

    @Test fun aWalletThatWorksComesBeforeOneStillBeingSetUp() {
        // An old group whose Mainnet wallet was started and not finished keeps showing its Testnet money.
        assertEquals(TESTNET, groupNetwork(BothUsable, MAINNET, mapOf(MAINNET to SettingUp, TESTNET to Active)))
        // Only a half-made one: its network, so Finish Wallet Setup carries on where it began (Testnet's too).
        assertEquals(TESTNET, groupNetwork(BothUsable, MAINNET, mapOf(TESTNET to SettingUp)))
        assertEquals(MAINNET, groupNetwork(BothUsable, TESTNET, mapOf(MAINNET to SettingUp)))
    }

    @Test fun beforeTheServerHasSaidWhichNetworksThereAreNothingIsDecided() {
        assertEquals(TESTNET, groupNetwork(emptyList(), TESTNET, mapOf(MAINNET to Active)))
        assertEquals(MAINNET, groupNetwork(emptyList(), MAINNET, emptyMap()))
        assertEquals(TESTNET, newGroupWalletNetwork(emptyList(), TESTNET))
    }
}

private fun group(walletId: String, chatId: String, status: WalletStatus = Active, canSign: Boolean = true) =
    WalletListing(walletId, WalletOwner(WalletOwnerType.Group, null, chatId, if (canSign) "me" else "amina"), status, canSign, emptyList())

/** What the phone's per-network W16 copies say about each group. */
class GroupNetworksStateTest {
    private val state = GroupNetworksState(
        usable = BothUsable, selected = MAINNET, known = true,
        listings = mapOf(
            MAINNET to listOf(group("m-new", "new"), group("m-both", "both"), group("m-half", "half", SettingUp)),
            TESTNET to listOf(group("t-old", "old"), group("t-both", "both"), group("t-half", "half"), group("t-theirs", "theirs", canSign = false)),
        ),
    )

    @Test fun eachGroupIsOnItsOwnNetwork() {
        assertEquals(MAINNET, state.networkOf("new"))
        assertEquals(TESTNET, state.networkOf("old"), "an old Testnet group, whatever is selected")
        assertEquals(MAINNET, state.networkOf("both"))
        assertEquals(TESTNET, state.copy(selected = TESTNET).networkOf("both"))
        assertEquals(MAINNET, state.copy(selected = TESTNET).networkOf("new"), "a Mainnet group while the tab is on Testnet")
        assertEquals(MAINNET, state.copy(selected = TESTNET).networkOf("no-wallet"), "a group without one: where a new one is made")
        assertEquals(TESTNET, state.networkOf("half"), "its Mainnet wallet isn't finished: its Testnet money still shows")
    }

    @Test fun aWalletIsOnTheNetworkWhoseListHoldsIt() {
        assertEquals(TESTNET, state.networkOfWallet("t-old"))
        assertEquals(MAINNET, state.networkOfWallet("m-new"))
        assertNull(state.networkOfWallet("unknown"))
    }

    @Test fun onlyAnOldGroupsOwnerIsOfferedAMainnetWallet() {
        assertEquals(GroupMainnet.Add, state.mainnetFor("old"))
        assertEquals(GroupMainnet.SettingUp, state.mainnetFor("half"), "started and not finished: Finish Mainnet Wallet Setup")
        assertNull(state.mainnetFor("both"), "it has one")
        assertNull(state.mainnetFor("new"), "a Mainnet group gets no Testnet wallet")
        assertNull(state.mainnetFor("theirs"), "members see nothing until it exists")
        assertNull(state.mainnetFor("no-wallet"))
        assertNull(state.copy(usable = listOf(TESTNET)).mainnetFor("old"), "someone who may not use Mainnet")
        assertEquals(listOf("old"), state.withoutMainnet.map { it.owner.chatId }, "the Group Wallets sheet lists the ones with nothing started")
    }

    @Test fun testMoneyIsNamedOnlyOnceTheServerHasSaid() {
        assertTrue(state.testnet(TESTNET))
        assertTrue(!state.testnet(MAINNET))
        assertTrue(!state.copy(known = false).testnet(TESTNET))
    }
}

private fun dto(walletId: String, chatId: String, status: String = "ACTIVE", canSign: Boolean = true) =
    WalletListingDto(walletId, WalletOwnerDto("GROUP", chatId = chatId, signerUserId = if (canSign) "me" else "amina"), status, canSign, emptyList())

/** Group money runs on the group's own network through the real repository: what is asked, saved and read. */
class GroupNetworksRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val dao = FakeWalletDao()
    private val mainnet = FakeWalletRemote().apply { status = "ACTIVE"; groupListings = listOf(dto("m-new", "new")) }
    private val testnet = FakeWalletRemote().apply { status = "ACTIVE"; groupListings = listOf(dto("t-old", "old")) }
    private val remote = TwoSidedRemote(mainnet, testnet)
    private val signer = FakeSigner()

    private class Fixture(val networks: WalletNetworks, val repo: WalletRepositoryImpl, val places: GroupNetworks)

    private suspend fun TestScope.fixture(selected: WalletNetwork): Fixture {
        val saved = MutableStateFlow(SavedNetworks())
        var clock = 0L
        val networks = WalletNetworksStore({ remote.networks() }, saved, { saved.value = it }, backgroundScope, now = { clock += 10_000; clock })
        val repo = WalletRepositoryImpl(remote, dao, { signer }, { "KES" }, json, networks = networks)
        val places = GroupNetworks(networks, { repo.walletsOn(it) }, { repo.refreshWallets() }, backgroundScope)
        networks.refresh()
        networks.select(selected)
        places.refresh()
        places.state.first { it.known && it.listings.size == it.usable.size && it.selected == selected }
        return Fixture(networks, repo, places)
    }

    @Test fun w16IsReadForEveryNetworkThePersonMayUseAndNeverForOneThatIsntTheirs() = runTest {
        val f = fixture(TESTNET)
        assertEquals(1, mainnet.listReads); assertEquals(1, testnet.listReads)
        assertEquals(setOf("MAINNET", "TESTNET"), dao.lists.value.keys)
        assertEquals(MAINNET, f.places.now("new")); assertEquals(TESTNET, f.places.now("old"))
        // Someone who only has Mainnet: Testnet is never asked for anything.
        remote.listed = MainnetOnly
        remote.asked.clear()
        f.networks.refresh()
        f.places.refresh()
        assertTrue(remote.asked.none { it == TESTNET }, remote.asked.toString())
    }

    @Test fun aNewGroupsWalletIsMadeOnMainnetEvenWhileTestnetIsSelected() = runTest {
        val f = fixture(TESTNET)
        assertEquals(MAINNET, f.places.newWalletNetwork())
        val on = f.places.now("brand-new")
        assertEquals(MAINNET, on, "no picker: Mainnet whenever the person may use it")
        // The set-up runs pinned to it (GroupWalletSetupViewModel's scope), whatever the Wallet tab shows.
        val started = withContext(PinnedNetwork(on)) { f.repo.startGroupWallet("brand-new", "Brand New") }
        assertTrue(started is AppResult.Success)
        assertEquals(1, mainnet.setups, "W14 went to Mainnet")
        assertEquals(0, testnet.setups)
        assertEquals(TESTNET, f.networks.current(), "the selection never moved")
    }

    @Test fun someoneWithOnlyTestnetStillMakesATestnetWallet() = runTest {
        remote.listed = NetworksResponse(listOf(NetworkDto("TESTNET", "ACTIVE")), default = "TESTNET")
        val f = fixture(TESTNET)
        assertEquals(TESTNET, f.places.newWalletNetwork())
        assertEquals(TESTNET, f.places.now("brand-new"))
    }

    @Test fun aGroupScreenPinnedToAnotherNetworkAsksAndReadsThatNetwork() = runTest {
        val f = fixture(MAINNET)
        val on = f.places.now("old")
        assertEquals(TESTNET, on, "an old Testnet group while Mainnet is selected")
        remote.asked.clear()
        withContext(PinnedNetwork(on)) { f.repo.refreshWallet("t-old"); f.repo.refreshActivity("t-old"); f.repo.groupWallet("old") }
        assertEquals(listOf<WalletNetwork?>(TESTNET, TESTNET, TESTNET), remote.asked, "W1, W6 and W15 all went to Testnet")
        assertEquals(listOf<String?>("t-old"), testnet.walletReads); assertTrue(mainnet.walletReads.isEmpty())
        // Its copy is Testnet's, read by a screen pinned there and absent under the selected network.
        assertNotNull(f.repo.walletOf("t-old").on(on).first())
        assertEquals(TESTNET, f.repo.walletOf("t-old").on(on).first()?.network)
        assertNull(f.repo.walletOf("t-old").first(), "nothing of Testnet shows under Mainnet")
        assertEquals(listOf("t-old"), f.repo.wallets.on(on).first().filter { !it.personal }.map { it.walletId })
        assertEquals(listOf("m-new"), f.repo.wallets.first().filter { !it.personal }.map { it.walletId })
    }

    @Test fun payingIntoAGroupOnAnotherNetworkIsNotStoppedByTheSelection() = runTest {
        val f = fixture(MAINNET)
        val on = f.places.now("old")
        val r = f.repo.pay(PaymentRequest("BASE-SEPOLIA", null, null, "1000000", chatId = "old", toGroupId = "old", network = on), "key-1")
        assertTrue(r is AppResult.Success, r.toString())
        assertEquals(1, testnet.prepared.size, "W4 went to the group's network, from the person's wallet there")
        assertEquals("old", testnet.prepared.single().first.to.groupId)
        assertTrue(mainnet.prepared.isEmpty())
        assertEquals(MAINNET, f.networks.current())
    }
}

/** On the wire: a call pinned to a group's network carries that network, not the selected one. */
class GroupNetworkHeaderTest {
    private val json = circasoJson()
    private val seen = mutableListOf<Pair<String, String?>>()
    private val api = CircasoApi(
        HttpClient(MockEngine { request ->
            seen += request.url.encodedPath to request.headers[WalletNetworkHeader]
            respond("""{"httpStatusCode":200,"status":true,"message":"Success","code":null,"details":null,"requestId":"r","data":{}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }) { install(ContentNegotiation) { json(json) } },
        json, NetworkHealth(), NetworkEvents(),
    )

    @Test fun aPinnedGroupCallSendsItsOwnNetwork() = runTest {
        val saved = MutableStateFlow(SavedNetworks())
        val networks = WalletNetworksStore({ AppResult.Success(Both) }, saved, { saved.value = it }, backgroundScope)
        networks.refresh()
        networks.select(MAINNET)
        val gate = object : WalletNetworkGate {
            override suspend fun header(): String? = networks.header()
            override suspend fun refused(sent: String?) = networks.onRefused(sent)
        }
        val wallet = KtorWalletRemote(api, com.circaso.core.flags.GroupWalletsFlag.on(), gate)
        val chama = com.circaso.feature.chama.data.remote.KtorChamaRemote(api, gate)
        withContext(PinnedNetwork(TESTNET)) { wallet.groupWallet("old"); wallet.wallet("KES", "t-old"); chama.money("old") }
        wallet.wallet("KES")
        assertEquals(listOf<String?>("TESTNET", "TESTNET", "TESTNET", "MAINNET"), seen.map { it.second })
        assertEquals("/v1/wallet/groups/old", seen.first().first)
    }

    @Test fun theMainnetOnlyRefusalHasItsOwnWords() {
        val refused = AppError.Api(GroupMainnetOnlyCode, 403, "req_1", null, emptyMap())
        assertEquals("wallet.group_mainnet_only", GroupMainnetOnlyCode)
        assertEquals("New group wallets are made on Mainnet.", ErrorMessages.forError(refused))
    }
}
