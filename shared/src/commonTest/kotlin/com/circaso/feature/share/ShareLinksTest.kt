package com.circaso.feature.share

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.share.data.remote.OpenTargetDto
import com.circaso.feature.share.data.remote.SharedLinkDto
import com.circaso.feature.share.data.remote.SharedReceiptDto
import com.circaso.feature.share.data.repository.toDto
import com.circaso.feature.share.data.repository.toModel
import com.circaso.feature.share.domain.CreateShareLinkUseCase
import com.circaso.feature.share.domain.IncomingLinks
import com.circaso.feature.share.domain.LinkEnds
import com.circaso.feature.share.domain.OpenTarget
import com.circaso.feature.share.domain.ShareKind
import com.circaso.feature.share.domain.ShareLink
import com.circaso.feature.share.domain.ShareLinkPage
import com.circaso.feature.share.domain.ShareLinksRepository
import com.circaso.feature.share.domain.ShareRequest
import com.circaso.feature.share.domain.ShareTarget
import com.circaso.feature.share.domain.ShareText
import com.circaso.feature.share.domain.SharedLink
import com.circaso.feature.share.presentation.ShareLinkViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncomingLinksTest {
    private val hosts = setOf("sandbox.circaso.com", "cso.to")

    @Test fun aShareLinkOnAKnownHostGivesItsCode() {
        assertEquals("Ab3dEf7hJk9m", IncomingLinks.codeOf("https://sandbox.circaso.com/s/Ab3dEf7hJk9m", hosts))
        assertEquals("Ab3dEf7hJk9m", IncomingLinks.codeOf("https://CSO.to/s/Ab3dEf7hJk9m/?utm=x#top", hosts))
        assertEquals("Ab3dEf7hJk9m", IncomingLinks.codeOf("circaso://s/Ab3dEf7hJk9m", hosts))
    }

    @Test fun anythingElseIsNotALink() {
        assertNull(IncomingLinks.codeOf("http://sandbox.circaso.com/s/Ab3dEf7hJk9m", hosts), "not https")
        assertNull(IncomingLinks.codeOf("https://evil.example/s/Ab3dEf7hJk9m", hosts), "unknown host")
        assertNull(IncomingLinks.codeOf("https://sandbox.circaso.com.evil.example/s/Ab3dEf7hJk9m", hosts))
        assertNull(IncomingLinks.codeOf("https://sandbox.circaso.com/x/Ab3dEf7hJk9m", hosts))
        assertNull(IncomingLinks.codeOf("https://sandbox.circaso.com/s/Ab3dEf7hJk9m/more", hosts))
        assertNull(IncomingLinks.codeOf("https://sandbox.circaso.com/s/short", hosts))
        assertNull(IncomingLinks.codeOf("circaso://s/../../etc", hosts))
    }

    @Test fun anOpenedLinkWaitsAndIsTakenOnce() {
        IncomingLinks.take()
        assertTrue(IncomingLinks.open("https://sandbox.circaso.com/s/Ab3dEf7hJk9m"))
        assertEquals("Ab3dEf7hJk9m", IncomingLinks.pending.value)
        assertEquals("Ab3dEf7hJk9m", IncomingLinks.take())
        assertNull(IncomingLinks.take())
        assertFalse(IncomingLinks.open("https://example.com/s/Ab3dEf7hJk9m"))
        assertNull(IncomingLinks.pending.value)
    }
}

class ShareRequestTest {
    @Test fun aReceiptCarriesItsOptionsAndAnInviteNone() {
        val receipt = ShareRequest.of(ShareTarget.Receipt("t1", "preview", currency = "KES"), showNames = false, ends = LinkEnds.OneWeek)
        assertEquals(ShareRequest(ShareKind.Receipt, transferId = "t1", showNames = false, currency = "KES", expiresInDays = 7), receipt)
        assertEquals("receipt", receipt.toDto().kind)
        assertEquals(ShareRequest(ShareKind.Invite), ShareRequest.of(ShareTarget.Invite, showNames = false, ends = LinkEnds.OneDay))
        assertEquals("pay_me", ShareRequest.of(ShareTarget.PayMe).toDto().kind)
        assertNull(ShareRequest.of(ShareTarget.Statement("c1", "p")).expiresInDays)
    }

    @Test fun theShareSheetGetsOneLineAndTheUrl() {
        val link = ShareLink("Ab3dEf7hJk9m", "https://sandbox.circaso.com/s/Ab3dEf7hJk9m", ShareKind.Receipt, "Payment receipt", "", "2026-11-06T10:00:00Z", null)
        val message = ShareText.of(link)
        assertTrue(message.text.endsWith(link.url))
        assertFalse(message.text.contains("\n"))
        ShareKind.entries.forEach { kind -> assertTrue(ShareText.of(link.copy(kind = kind)).text.contains(link.url)) }
    }
}

class SharedLinkMappingTest {
    @Test fun whereTheAppMayGoIsMappedAndAnUnknownScreenDropped() {
        assertEquals(OpenTarget.Transfer("t1"), OpenTargetDto("transfer", transferId = "t1").toModel())
        assertEquals(OpenTarget.Invite("tok", "Wanjiru"), OpenTargetDto("invite", userId = "u", displayName = "Wanjiru", inviteToken = "tok").toModel())
        assertEquals(OpenTarget.PayAddress("0xabc", "BASE", null), OpenTargetDto("pay_address", address = "0xabc", blockchain = "BASE").toModel())
        assertNull(OpenTargetDto("invite").toModel(), "an invite without its token")
        assertNull(OpenTargetDto("teleport").toModel())
    }

    @Test fun aReceiptKeepsItsDirectionAndFiat() {
        val dto = SharedLinkDto(
            code = "Ab3dEf7hJk9m", url = "u", kind = "receipt", title = "Payment receipt", createdAt = "2026-11-06T10:00:00Z",
            receipt = SharedReceiptDto("sent", "12.5", "USDC", com.circaso.feature.share.data.remote.SharedFiatDto("KES", "1612.50"), "CONFIRMED", "BASE", at = "2026-11-06T10:00:00Z"),
        )
        val receipt = dto.toModel().receipt!!
        assertTrue(receipt.sent)
        assertEquals("KES", receipt.fiat?.currency)
        assertEquals(ShareKind.Receipt, dto.toModel().kind)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShareLinkViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val os = MutableStateFlow(ConnectionStatus.Online)
    private val monitor = object : ConnectivityMonitor { override val osStatus = os }
    private lateinit var connection: ConnectionState
    private val repo = FakeShareLinks()

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm() = ShareLinkViewModel(CreateShareLinkUseCase(repo), connection)

    @Test fun aReceiptAsksFirstThenSharesTheServersLink() = runTest(dispatcher) {
        val vm = vm()
        vm.start(ShareTarget.Receipt("t1", "12.5 USDC to Juma"))
        assertTrue(vm.state.value.sheetOpen)
        assertNull(repo.made, "nothing is made before the person confirms")
        vm.onShowNames(false)
        vm.onEnds(LinkEnds.OneMonth)
        vm.onConfirm()
        assertEquals(ShareRequest(ShareKind.Receipt, transferId = "t1", showNames = false, expiresInDays = 30), repo.made)
        val s = vm.state.value
        assertFalse(s.sheetOpen)
        assertEquals("t1", (s.target as ShareTarget.Receipt).transferId, "the sheet keeps its content while it slides out")
        assertTrue(s.share!!.text.contains("https://sandbox.circaso.com/s/Ab3dEf7hJk9m"))
        vm.onShared()
        assertNull(vm.state.value.share)
    }

    @Test fun anInviteGoesStraightToTheShareSheet() = runTest(dispatcher) {
        val vm = vm()
        vm.start(ShareTarget.Invite)
        assertFalse(vm.state.value.sheetOpen)
        assertEquals(ShareKind.Invite, repo.made?.kind)
        assertEquals(ShareText.InviteSubject, vm.state.value.share?.subject)
    }

    @Test fun aRefusalIsSaidInTheKindsOwnWords() = runTest(dispatcher) {
        repo.fail = AppError.Api("share.not_shareable", 400, null, null, emptyMap())
        val vm = vm()
        vm.start(ShareTarget.PayMe)
        assertEquals("Set your @handle first, from the You tab.", vm.state.value.notice)
        assertNull(vm.state.value.share)
    }

    @Test fun aSheetClosedWhileTheLinkIsMadeStillShares() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        repo.gate = gate
        val vm = vm()
        vm.start(ShareTarget.Receipt("t1", "p"))
        vm.onConfirm()
        assertTrue(vm.state.value.creating)
        vm.onDismiss()
        assertFalse(vm.state.value.sheetOpen, "a drag-down closes it even while busy")
        gate.complete(Unit)
        assertTrue(vm.state.value.share!!.text.contains("/s/Ab3dEf7hJk9m"))
    }

    @Test fun offlineNothingIsMadeAndItSaysWhy() = runTest(dispatcher) {
        os.value = ConnectionStatus.Offline
        val vm = vm()
        vm.start(ShareTarget.Statement("c1", "Holiday Fund"))
        vm.onConfirm()
        assertNull(repo.made)
        assertEquals(ShareLinkViewModel.OfflineText, vm.state.value.error)
    }
}

private class FakeShareLinks : ShareLinksRepository {
    var made: ShareRequest? = null
    var fail: AppError? = null
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun create(request: ShareRequest): AppResult<ShareLink> {
        gate?.await()
        fail?.let { return AppResult.Failure(it) }
        made = request
        return AppResult.Success(
            ShareLink("Ab3dEf7hJk9m", "https://sandbox.circaso.com/s/Ab3dEf7hJk9m", request.kind, "Title", "Summary", "2026-11-06T10:00:00Z", null),
        )
    }

    override suspend fun list(cursor: String?): AppResult<ShareLinkPage> = AppResult.Success(ShareLinkPage(emptyList(), null))
    override suspend fun revoke(code: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun open(code: String): AppResult<SharedLink> = AppResult.Failure(AppError.Network)
}
